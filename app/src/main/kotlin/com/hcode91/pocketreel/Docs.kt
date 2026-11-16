package com.hcode91.pocketreel

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.hcode91.pocketreel.core.ByteSource
import com.hcode91.pocketreel.core.ChannelByteSource
import com.hcode91.pocketreel.core.DiscImage
import com.hcode91.pocketreel.core.DiscLoader
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** A file the app can see through Android's document APIs. */
data class DocFile(val uri: Uri, val name: String, val size: Long, val isDir: Boolean, val docId: String?) {
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
}

/** Something in the library that can be played: a cue sheet with its folder, a zip, or a lone track. */
data class LibraryEntry(val title: String, val main: DocFile, val siblings: List<DocFile>, val kind: String)

/** The disc currently loaded, handed from the library screen to the player. */
object DiscSession {
    @Volatile var disc: DiscImage? = null

    fun replace(new: DiscImage?) {
        val old = disc
        disc = new
        if (old !== new) runCatching { old?.close() }
    }
}

/**
 * Opens its file only when first read, so a disc with one .bin per track (often 90+ files)
 * doesn't hold a descriptor for every track it never plays.
 */
class LazyByteSource(override val length: Long, private val opener: () -> ByteSource) : ByteSource {
    private var inner: ByteSource? = null

    override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
        val source = inner ?: opener().also { inner = it }
        return source.read(position, buffer, offset, len)
    }

    override fun close() {
        inner?.close()
        inner = null
    }
}

object Docs {
    fun open(resolver: ContentResolver, uri: Uri): ByteSource {
        val pfd = resolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open $uri")
        val stream = FileInputStream(pfd.fileDescriptor)
        return ChannelByteSource(stream.channel) { stream.close(); pfd.close() }
    }

    fun describe(resolver: ContentResolver, uri: Uri): DocFile {
        var name = uri.lastPathSegment?.substringAfterLast('/') ?: "disc"
        var size = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        return DocFile(uri, name, size, false, null)
    }

    /** Lists a folder of a granted tree. */
    fun children(resolver: ContentResolver, tree: Uri, parentDocId: String): List<DocFile> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocId)
        val out = ArrayList<DocFile>()
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            ),
            null, null, null,
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0)
                val isDir = c.getString(3) == DocumentsContract.Document.MIME_TYPE_DIR
                out.add(
                    DocFile(
                        DocumentsContract.buildDocumentUriUsingTree(tree, id),
                        c.getString(1) ?: id, if (c.isNull(2)) -1 else c.getLong(2), isDir, id,
                    ),
                )
            }
        }
        return out
    }

    /** Finds playable discs under a granted folder, up to a few folders deep. */
    fun scanLibrary(resolver: ContentResolver, tree: Uri): List<LibraryEntry> {
        val entries = ArrayList<LibraryEntry>()
        fun walk(docId: String, depth: Int) {
            val files = children(resolver, tree, docId)
            val cues = files.filter { !it.isDir && it.ext == "cue" }
            for (cue in cues) entries.add(LibraryEntry(cue.name.substringBeforeLast('.'), cue, files, "Cue sheet"))
            for (zip in files.filter { !it.isDir && it.ext == "zip" }) {
                entries.add(LibraryEntry(zip.name.substringBeforeLast('.'), zip, emptyList(), "Zip"))
            }
            if (cues.isEmpty()) {
                for (t in files.filter { !it.isDir && it.ext in DiscLoader.TRACK_EXTENSIONS }) {
                    entries.add(LibraryEntry(t.name.substringBeforeLast('.'), t, emptyList(), "Single track"))
                }
            }
            if (depth < 4) files.filter { it.isDir }.forEach { walk(it.docId!!, depth + 1) }
        }
        walk(DocumentsContract.getTreeDocumentId(tree), 0)
        return entries.sortedBy { it.title.lowercase() }
    }

    /**
     * Mounts a disc from a cue document and the files beside it, a zip (extracted to the
     * cache first), or a single track.
     */
    fun mount(
        context: Context,
        main: DocFile,
        siblings: List<DocFile>,
        progress: (String) -> Unit,
    ): DiscImage {
        val resolver = context.contentResolver
        return when (main.ext) {
            "cue" -> {
                val text = resolver.openInputStream(main.uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IOException("Cannot read ${main.name}")
                val byName = siblings.filter { !it.isDir }.associateBy { it.name.lowercase() }
                DiscImage.fromCue(main.name.substringBeforeLast('.'), text) { fileName ->
                    val bare = fileName.replace('\\', '/').substringAfterLast('/').lowercase()
                    byName[bare]?.let { doc ->
                        if (doc.size >= 0) LazyByteSource(doc.size) { open(resolver, doc.uri) } else open(resolver, doc.uri)
                    }
                }
            }
            "zip" -> {
                val dir = File(context.cacheDir, "disc")
                DiscSession.replace(null) // release any files in the cache before overwriting them
                val total = main.size
                // Reopening the same zip reuses what was unpacked last time.
                val marker = File(context.cacheDir, "disc.source")
                val key = "${main.uri}|${main.size}"
                if (marker.isFile && marker.readText() == key) {
                    val cached = dir.listFiles()?.let { files ->
                        files.firstOrNull { it.extension.equals("cue", true) }
                            ?: files.filter { it.extension.lowercase() in DiscLoader.TRACK_EXTENSIONS }.maxByOrNull { it.length() }
                    }
                    if (cached != null) return DiscLoader.open(cached)
                }
                marker.delete()
                val target = resolver.openInputStream(main.uri)?.use { input ->
                    var last = 0L
                    DiscLoader.extractZip(input, dir) { written ->
                        if (written - last > 4_000_000) {
                            last = written
                            progress("Unpacking… ${written / 1_000_000} MB" + if (total > 0) " (zip is ${total / 1_000_000} MB)" else "")
                        }
                    }
                } ?: throw IOException("Cannot read ${main.name}")
                marker.writeText(key)
                DiscLoader.open(target)
            }
            else -> DiscImage.single(main.name.substringBeforeLast('.'), open(resolver, main.uri))
        }
    }
}
