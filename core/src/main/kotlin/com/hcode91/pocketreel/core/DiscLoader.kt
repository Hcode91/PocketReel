package com.hcode91.pocketreel.core

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Opens the kinds of rip people have: cue + bins, a zip of those, a lone .bin/.raw track or a .wav. */
object DiscLoader {
    val TRACK_EXTENSIONS = setOf("bin", "raw", "wav", "img")

    /** Opens a local file of any supported kind. Zips must be extracted first with [extractZip]. */
    fun open(file: File): DiscImage = when (file.extension.lowercase()) {
        "cue" -> DiscImage.fromCueFile(file)
        else -> DiscImage.single(file.nameWithoutExtension, ChannelByteSource.open(file))
    }

    /**
     * Extracts a zipped rip into [destDir] (cleared first) and returns the file to open:
     * the cue sheet if there is one, otherwise the largest track file.
     * [progress] receives bytes written so far.
     */
    fun extractZip(input: InputStream, destDir: File, progress: (Long) -> Unit = {}): File {
        destDir.deleteRecursively()
        destDir.mkdirs()
        val root = destDir.canonicalFile
        var written = 0L
        val buffer = ByteArray(1 shl 16)
        ZipInputStream(input.buffered(1 shl 16)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                // Flatten folders; cue FILE lines are resolved by bare name as a fallback anyway.
                val name = entry.name.replace('\\', '/').substringAfterLast('/')
                if (name.isEmpty()) continue
                val ext = name.substringAfterLast('.', "").lowercase()
                if (ext != "cue" && ext !in TRACK_EXTENSIONS) continue
                val out = File(root, name)
                if (!out.canonicalPath.startsWith(root.path)) throw IOException("Bad entry name in zip: ${entry.name}")
                out.outputStream().use { os ->
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        os.write(buffer, 0, n)
                        written += n
                        progress(written)
                    }
                }
            }
        }
        val files = root.listFiles()?.toList().orEmpty()
        return files.filter { it.extension.equals("cue", true) }.minByOrNull { it.name }
            ?: files.filter { it.extension.lowercase() in TRACK_EXTENSIONS }.maxByOrNull { it.length() }
            ?: throw IOException("No cue sheet or track files in the zip")
    }
}
