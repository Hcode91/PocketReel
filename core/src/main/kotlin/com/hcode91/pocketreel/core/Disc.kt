package com.hcode91.pocketreel.core

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Random-access bytes: a track file on disk, or a file opened through Android's document APIs. */
interface ByteSource : Closeable {
    val length: Long

    /** Reads up to [len] bytes at [position]; returns how many were read (short at the end). */
    fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int
}

/** A [ByteSource] over a [FileChannel], which is what both plain files and Android file descriptors give. */
class ChannelByteSource(
    private val channel: FileChannel,
    private val owner: Closeable? = null,
) : ByteSource {
    override val length: Long = channel.size()

    override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
        var total = 0
        val bb = ByteBuffer.wrap(buffer, offset, len)
        while (bb.hasRemaining()) {
            val n = channel.read(bb, position + total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    override fun close() {
        channel.close()
        owner?.close()
    }

    companion object {
        fun open(file: File): ChannelByteSource {
            val raf = RandomAccessFile(file, "r")
            return ChannelByteSource(raf.channel, raf)
        }
    }
}

/** One track of a mounted disc, exposed as a byte range of a [ByteSource]. */
class DiscTrack internal constructor(
    val disc: DiscImage,
    val number: Int,
    val title: String?,
    internal val source: ByteSource,
    val byteOffset: Long,
    val byteLength: Long,
) {
    val sectorCount: Long get() = byteLength / CueSheet.SECTOR_SIZE

    /** Reads into [buffer] from [offset] within the track; returns bytes read. */
    fun read(offset: Long, buffer: ByteArray, bufOffset: Int = 0, len: Int = buffer.size - bufOffset): Int {
        require(offset >= 0)
        val available = byteLength - offset
        if (available <= 0) return 0
        val n = if (len > available) available.toInt() else len
        return disc.readSource(source, byteOffset + offset, buffer, bufOffset, n)
    }
}

/**
 * A VideoNow disc: a set of tracks over one or more byte sources. Built from a cue sheet
 * (single .bin or one .bin per track), or from a lone raw track or .wav rip.
 */
class DiscImage private constructor(val name: String) : Closeable {
    private val lock = Any()
    private val sources = LinkedHashSet<ByteSource>()
    private val trackList = ArrayList<DiscTrack>()
    @Volatile private var closed = false

    val tracks: List<DiscTrack> get() = trackList

    fun findTrack(number: Int): DiscTrack? = trackList.firstOrNull { it.number == number }

    internal fun readSource(source: ByteSource, position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
        synchronized(lock) {
            if (closed) throw IOException("Disc is closed")
            return source.read(position, buffer, offset, len)
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            sources.forEach { runCatching { it.close() } }
        }
    }

    companion object {
        /**
         * Mounts a disc from cue sheet text. [resolve] opens the file a FILE line names
         * (relative to the cue sheet), or returns null if it is missing.
         */
        fun fromCue(name: String, cueText: String, resolve: (String) -> ByteSource?): DiscImage {
            val cueTracks = CueSheet.parse(cueText.lines())
            val disc = DiscImage(name)
            val opened = HashMap<String, ByteSource>()
            try {
                for ((i, cue) in cueTracks.withIndex()) {
                    val key = cue.fileName.lowercase()
                    val source = opened.getOrPut(key) {
                        resolve(cue.fileName)?.also { disc.sources.add(it) }
                            ?: throw IOException("Missing track file: ${cue.fileName}")
                    }
                    val fileLength = source.length
                    val start = cue.indexOneSector.toLong() * CueSheet.SECTOR_SIZE
                    var end = fileLength
                    if (i + 1 < cueTracks.size) {
                        val next = cueTracks[i + 1]
                        if (next.fileName.equals(cue.fileName, ignoreCase = true)) {
                            end = (next.indexZeroSector ?: next.indexOneSector).toLong() * CueSheet.SECTOR_SIZE
                        }
                    }
                    if (end > fileLength) end = fileLength
                    if (end < start) end = start
                    disc.trackList.add(DiscTrack(disc, cue.number, cue.title, source, start, end - start))
                }
            } catch (e: Exception) {
                disc.close()
                throw e
            }
            return disc
        }

        /** Mounts a cue sheet file whose track files sit beside it. */
        fun fromCueFile(cue: File): DiscImage {
            val dir = cue.absoluteFile.parentFile
            return fromCue(cue.nameWithoutExtension, cue.readText()) { fileName ->
                resolveFile(dir, fileName)?.let { ChannelByteSource.open(it) }
            }
        }

        /** Finds a cue FILE entry in [dir], tolerating Windows separators and case differences. */
        fun resolveFile(dir: File, fileName: String): File? {
            val normalised = fileName.replace('\\', '/')
            val direct = File(dir, normalised)
            if (direct.isFile) return direct
            val bare = normalised.substringAfterLast('/')
            return dir.listFiles()?.firstOrNull { it.isFile && it.name.equals(bare, ignoreCase = true) }
        }

        /**
         * Mounts a single raw track (a .bin or .raw of one track) or a .wav rip as track 1.
         * A RIFF WAV's header is skipped so only its sample data is read.
         */
        fun single(name: String, source: ByteSource): DiscImage {
            val disc = DiscImage(name)
            disc.sources.add(source)
            val (offset, length) = wavDataRange(source) ?: (0L to source.length)
            disc.trackList.add(DiscTrack(disc, 1, name, source, offset, length))
            return disc
        }

        /** The data chunk of a RIFF/WAVE file, or null if [source] is not one. */
        fun wavDataRange(source: ByteSource): Pair<Long, Long>? {
            val head = ByteArray(12)
            if (source.read(0, head, 0, 12) < 12) return null
            if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            var pos = 12L
            val chunk = ByteArray(8)
            while (pos + 8 <= source.length) {
                if (source.read(pos, chunk, 0, 8) < 8) return null
                val id = String(chunk, 0, 4, Charsets.US_ASCII)
                val size = (chunk[4].toLong() and 0xFF) or ((chunk[5].toLong() and 0xFF) shl 8) or
                    ((chunk[6].toLong() and 0xFF) shl 16) or ((chunk[7].toLong() and 0xFF) shl 24)
                if (id == "data") {
                    val start = pos + 8
                    return start to minOf(size, source.length - start)
                }
                pos += 8 + size + (size and 1)
            }
            return null
        }
    }
}

/** A TRACK entry of a cue sheet. */
data class CueTrack(
    val number: Int,
    val dataType: String,
    val fileName: String,
    val title: String?,
    val indexOneSector: Int,
    val indexZeroSector: Int?,
)

/** Minimal cue sheet reader: FILE, TRACK, TITLE and INDEX; single-file and file-per-track. */
object CueSheet {
    const val SECTOR_SIZE = 2352

    fun parse(lines: List<String>): List<CueTrack> {
        val tracks = ArrayList<CueTrack>()
        var currentFile: String? = null
        var number: Int? = null
        var dataType: String? = null
        var title: String? = null
        var index0: Int? = null
        var index1: Int? = null

        fun flush() {
            val n = number ?: return
            val file = currentFile ?: throw IOException("TRACK $n appears before any FILE")
            tracks.add(CueTrack(n, dataType ?: "AUDIO", file, title, index1 ?: index0 ?: 0, index0))
            number = null; dataType = null; title = null; index0 = null; index1 = null
        }

        for (raw in lines) {
            val line = raw.trim().trimStart('﻿')
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            when (parts[0].uppercase()) {
                "FILE" -> {
                    flush()
                    currentFile = quoted(line) ?: parts.getOrNull(1) ?: ""
                }
                "TRACK" -> {
                    flush()
                    number = parts.getOrNull(1)?.toIntOrNull() ?: throw IOException("Malformed TRACK line: $line")
                    dataType = parts.getOrNull(2) ?: "AUDIO"
                }
                "TITLE" -> if (number != null) title = quoted(line)
                "INDEX" -> {
                    val idx = parts.getOrNull(1)?.toIntOrNull() ?: continue
                    val msf = parts.getOrNull(2) ?: continue
                    val sector = parseMsf(msf)
                    if (idx == 0) index0 = sector else if (idx == 1) index1 = sector
                }
            }
        }
        flush()
        if (tracks.isEmpty()) throw IOException("Cue sheet contains no tracks")
        return tracks
    }

    fun parseMsf(msf: String): Int {
        val p = msf.split(':')
        if (p.size != 3) throw IOException("Malformed MSF timestamp: $msf")
        val m = p[0].toInt(); val s = p[1].toInt(); val f = p[2].toInt()
        return (m * 60 + s) * 75 + f
    }

    private fun quoted(line: String): String? {
        val open = line.indexOf('"')
        if (open < 0) return null
        val close = line.lastIndexOf('"')
        return if (close > open) line.substring(open + 1, close) else null
    }
}
