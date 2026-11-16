package com.hcode91.pocketreel.core

/*
 * The VideoNow disc format. Ported from vxp by the VideoNow XP Emulator contributors
 * (https://github.com/zdiemer/vxp, MIT licence), which builds on PVDTools by saramibreak.
 * See NOTICE in the repository root.
 */

enum class DiscFormat(val shortName: String) {
    Unknown("?"), BlackAndWhite("B&W"), Color("Color"), Xp("XP")
}

/** Geometry of one frame as it appears in the raw CD byte stream. */
class FrameLayout private constructor(
    val format: DiscFormat,
    /** Multiple of CD speed the player reads this variant at. */
    val discSpeed: Int,
    /** Bytes the frame occupies in the interleaved disc stream. */
    val streamBytes: Int,
    /** Video header bytes (de-interleaved). */
    val headerBytes: Int,
    /** Back-to-back sync words at the start of a frame: 24 Color, 12 XP, 0 B&W. */
    val syncRepeatCount: Int,
    val interleaveBytes: Int = GROUP_BYTES,
    val interleaveVideoBytes: Int = VIDEO_BYTES_PER_GROUP,
    val pictureWidth: Int = WIDTH,
    val pictureHeight: Int = HEIGHT,
    val bitsPerPixel: Int = 12,
) {
    val monochrome: Boolean get() = format == DiscFormat.BlackAndWhite
    val pictureBytes: Int get() = pictureWidth * pictureHeight * bitsPerPixel / 8
    val pixelCount: Int get() = pictureWidth * pictureHeight
    val videoBytes: Int get() = streamBytes / interleaveBytes * interleaveVideoBytes
    val audioBytes: Int get() = streamBytes / interleaveBytes * AUDIO_BYTES_PER_GROUP
    val streamSampleRate: Int get() = DISC_BYTES_PER_SECOND / interleaveBytes * AUDIO_BYTES_PER_GROUP
    val playbackSampleRate: Int get() = streamSampleRate * discSpeed
    val playbackFrameRate: Double get() = playbackSampleRate / audioBytes.toDouble()
    val frameSeconds: Double get() = audioBytes / playbackSampleRate.toDouble()

    override fun toString() = format.shortName

    companion object {
        const val VIDEO_BYTES_PER_GROUP = 9
        const val AUDIO_BYTES_PER_GROUP = 1
        const val GROUP_BYTES = VIDEO_BYTES_PER_GROUP + AUDIO_BYTES_PER_GROUP
        const val DISC_BYTES_PER_SECOND = 75 * 2352
        const val WIDTH = 144
        const val HEIGHT = 80
        const val PIXEL_BYTES = WIDTH * HEIGHT * 3 / 2
        const val PIXEL_ROW_STRIDE = PIXEL_BYTES / (HEIGHT * 2)

        val SYNC_WORD = byteArrayOf(
            0x81.toByte(), 0xE3.toByte(), 0xE3.toByte(), 0xC7.toByte(), 0xC7.toByte(),
            0x81.toByte(), 0x81.toByte(), 0xE3.toByte(), 0xC7.toByte(),
        )

        /** VideoNow XP: 19 760-byte frames, 504-byte header, 12 sync words, 2x CD speed. */
        val Xp = FrameLayout(DiscFormat.Xp, 2, 19760, 504, 12)

        /** VideoNow Color / Jr / Color FX: 19 600-byte frames, 360-byte header, 24 sync words, 2x. */
        val Color = FrameLayout(DiscFormat.Color, 2, 19600, 360, 24)

        /** Original black and white VideoNow: 80 x 80 greys, 1x CD speed. */
        val BlackAndWhite = FrameLayout(
            DiscFormat.BlackAndWhite, 1,
            BlackAndWhiteStream.FRAME_GROUPS * BlackAndWhiteStream.GROUP_BYTES,
            BlackAndWhiteStream.HEADER_GROUPS * BlackAndWhiteStream.VIDEO_BYTES_PER_GROUP,
            0,
            interleaveBytes = BlackAndWhiteStream.GROUP_BYTES,
            interleaveVideoBytes = BlackAndWhiteStream.VIDEO_BYTES_PER_GROUP,
            pictureWidth = BlackAndWhiteStream.WIDTH,
            pictureHeight = BlackAndWhiteStream.HEIGHT,
            bitsPerPixel = 4,
        )

        val Known = listOf(Xp, Color, BlackAndWhite)
    }
}

internal fun ByteArray.indexOf(pattern: ByteArray, from: Int = 0, end: Int = size): Int {
    val last = end - pattern.size
    outer@ for (i in from..last) {
        for (j in pattern.indices) if (this[i + j] != pattern[j]) continue@outer
        return i
    }
    return -1
}

internal fun ByteArray.matchesAt(pattern: ByteArray, at: Int): Boolean {
    if (at < 0 || at + pattern.size > size) return false
    for (j in pattern.indices) if (this[at + j] != pattern[j]) return false
    return true
}

/** Tells XP, Color and black and white apart. */
object FormatDetector {
    fun detect(track: DiscTrack): FrameLayout? {
        val probe = minOf(track.byteLength, 64L * 1024).toInt()
        if (probe < FrameLayout.GROUP_BYTES * 32) return null
        val buffer = ByteArray(probe)
        val read = track.read(0, buffer)
        return detect(buffer, read)
    }

    fun detect(stream: ByteArray, length: Int = stream.size): FrameLayout? {
        val sync = FrameLayout.SYNC_WORD
        val start = stream.indexOf(sync, 0, length)
        if (start < 0) {
            return if (BlackAndWhiteStream.findFrame(stream, length, 0) >= 0) FrameLayout.BlackAndWhite else null
        }
        var repeats = 0
        var offset = start
        while (offset + FrameLayout.GROUP_BYTES <= length && stream.matchesAt(sync, offset)) {
            repeats++
            offset += FrameLayout.GROUP_BYTES
        }
        return FrameLayout.Known.firstOrNull { it.syncRepeatCount == repeats }
    }

    /** The most common per-track layout. */
    fun detect(disc: DiscImage): FrameLayout? =
        disc.tracks.mapNotNull { detect(it) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}

/**
 * The original black and white stream: 4-byte groups of (video, video, marker, audio).
 * Frames are 670 header groups, 1600 picture groups (marker 0x5A) and 670 footer groups.
 */
object BlackAndWhiteStream {
    const val GROUP_BYTES = 4
    const val VIDEO_BYTES_PER_GROUP = 2
    const val MARKER_OFFSET = 2
    const val AUDIO_OFFSET = 3
    const val HEADER_GROUPS = 670
    const val PICTURE_GROUPS = 1600
    const val FOOTER_GROUPS = 670
    const val FRAME_GROUPS = HEADER_GROUPS + PICTURE_GROUPS + FOOTER_GROUPS
    const val WIDTH = 80
    const val HEIGHT = 80
    const val PICTURE_MARKER = 0x5A
    private const val MAX_HEADER_GROUPS = HEADER_GROUPS + 130

    fun isHeaderMarker(m: Int) = m == 0xE1 || m == 0xC3 || m == 0xA5
    fun isFooterMarker(m: Int) = m == 0xD2 || m == 0xB4 || m == 0x96

    class FrameIndex(val starts: LongArray, val pictureOffsets: IntArray, val alignment: Int) {
        val count: Int get() = starts.size
    }

    /** Finds every whole frame in a track by reading it through once. */
    fun indexFrames(track: DiscTrack): FrameIndex {
        val probe = ByteArray(minOf(64L * 1024, track.byteLength).toInt())
        val probed = track.read(0, probe)
        val first = findFrame(probe, probed, 0)
        if (first < 0) return FrameIndex(LongArray(0), IntArray(0), 0)

        val alignment = first % GROUP_BYTES
        val starts = ArrayList<Long>()
        val pictures = ArrayList<Int>()
        var headerStart = -1L
        var pictureStart = -1L
        var headerGroups = 0
        var pictureGroups = 0
        var previous = 0

        val chunk = ByteArray(1024 * 1024)
        var position = alignment.toLong()
        while (position + GROUP_BYTES <= track.byteLength) {
            val read = track.read(position, chunk) / GROUP_BYTES * GROUP_BYTES
            if (read == 0) break
            var o = 0
            while (o < read) {
                val marker = chunk[o + MARKER_OFFSET].toInt() and 0xFF
                if (isHeaderMarker(marker)) {
                    if (!isHeaderMarker(previous)) {
                        headerStart = position
                        headerGroups = 0
                    }
                    headerGroups++
                    pictureStart = -1
                } else if (marker == PICTURE_MARKER) {
                    if (isHeaderMarker(previous) && headerStart >= 0 && headerGroups <= MAX_HEADER_GROUPS) {
                        pictureStart = position
                        pictureGroups = 0
                    }
                    if (pictureStart >= 0 && ++pictureGroups == PICTURE_GROUPS) {
                        starts.add(headerStart)
                        pictures.add((pictureStart - headerStart).toInt())
                        headerStart = -1
                        pictureStart = -1
                    }
                } else {
                    pictureStart = -1
                }
                previous = marker
                o += GROUP_BYTES
                position += GROUP_BYTES
            }
        }
        return FrameIndex(starts.toLongArray(), pictures.toIntArray(), alignment)
    }

    /** Offset of the first frame header (followed by a whole picture) at or after [from], or -1. */
    fun findFrame(stream: ByteArray, length: Int, from: Int): Int {
        val last = length - (PICTURE_GROUPS + 1) * GROUP_BYTES
        var start = maxOf(0, from)
        while (start <= last) {
            if (!isHeaderMarker(stream[start + MARKER_OFFSET].toInt() and 0xFF)) { start++; continue }
            if (start >= GROUP_BYTES && isHeaderMarker(stream[start - GROUP_BYTES + MARKER_OFFSET].toInt() and 0xFF)) { start++; continue }
            var offset = start
            var groups = 0
            while (offset + MARKER_OFFSET < length && isHeaderMarker(stream[offset + MARKER_OFFSET].toInt() and 0xFF)) {
                offset += GROUP_BYTES
                if (++groups > MAX_HEADER_GROUPS) break
            }
            if (groups <= MAX_HEADER_GROUPS && isPictureRun(stream, length, offset)) return start
            start++
        }
        return -1
    }

    private fun isPictureRun(stream: ByteArray, length: Int, offset: Int): Boolean {
        if (offset + PICTURE_GROUPS * GROUP_BYTES > length) return false
        for (g in 0 until PICTURE_GROUPS) {
            if ((stream[offset + g * GROUP_BYTES + MARKER_OFFSET].toInt() and 0xFF) != PICTURE_MARKER) return false
        }
        return true
    }

    /** Splits groups into video and audio; padding (zero marker) becomes silence. */
    fun deinterleave(stream: ByteArray, start: Int, length: Int, video: ByteArray?, audio: ByteArray?) {
        val groups = length / GROUP_BYTES
        for (g in 0 until groups) {
            val s = start + g * GROUP_BYTES
            if (video != null) {
                video[g * 2] = stream[s]
                video[g * 2 + 1] = stream[s + 1]
            }
            if (audio != null) {
                audio[g] = if (stream[s + MARKER_OFFSET].toInt() == 0) 0x80.toByte() else stream[s + AUDIO_OFFSET]
            }
        }
    }

    /** 80 x 80 picture, two pixels a byte, high nibble on the left, into ARGB. */
    fun decodeArgb(picture: ByteArray, offset: Int, argb: IntArray) {
        var o = 0
        for (i in 0 until WIDTH * HEIGHT / 2) {
            val b = picture[offset + i].toInt()
            argb[o++] = grey(((b shr 4) and 0xF) * 0x11)
            argb[o++] = grey((b and 0xF) * 0x11)
        }
    }

    private fun grey(l: Int) = (0xFF shl 24) or (l shl 16) or (l shl 8) or l
}

/** Unpacks the packed Color/XP picture (zig-zag of two 108-byte half-rows) into ARGB. */
object VideoDecoder {
    fun decode(frame: VideoNowFrame, argb: IntArray) {
        if (frame.layout.monochrome) BlackAndWhiteStream.decodeArgb(frame.video, frame.pictureOffset, argb)
        else decodeArgb(frame.video, frame.pictureOffset, argb)
    }

    fun decodeArgb(data: ByteArray, offset: Int, argb: IntArray) {
        val stride = FrameLayout.PIXEL_ROW_STRIDE
        val groupsPerRow = FrameLayout.WIDTH / 4
        var out = 0
        for (y in 0 until FrameLayout.HEIGHT) {
            val upper = offset + y * stride * 2
            val lower = upper + stride
            for (g in 0 until groupsPerRow) {
                val i = g * 3
                val a0 = data[upper + i].toInt(); val a1 = data[upper + i + 1].toInt(); val a2 = data[upper + i + 2].toInt()
                val b0 = data[lower + i].toInt(); val b1 = data[lower + i + 1].toInt(); val b2 = data[lower + i + 2].toInt()
                argb[out++] = px(lo(a0), lo(b0), hi(b0))
                argb[out++] = px(lo(b1), hi(a0), lo(a1))
                argb[out++] = px(hi(a1), hi(b1), lo(b2))
                argb[out++] = px(hi(b2), lo(a2), hi(a2))
            }
        }
    }

    private fun lo(v: Int) = (v and 0x0F) * 0x11
    private fun hi(v: Int) = ((v shr 4) and 0x0F) * 0x11
    private fun px(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

/** Register 0x4B: what a segment is and what it does to the score. */
enum class SegmentKind(val code: Int) {
    None(0), Linear(1), Choice(2), TaggedChoice(3), ScoreUp(4), ScoreDown(5), ScoreReset(6), Other(-1);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: Other
    }
}

/** One branch-table entry: a play list chosen by a button (slot) or, on tagged segments, by score. */
data class BranchEntry(val slot: Int, val tag: Int, val track: Int, val then: List<Int> = emptyList()) {
    val playList: List<Int> get() = listOf(track) + then
}

/** The display-controller command stream at the head of each Color/XP frame, decoded to a register file. */
class FrameHeader private constructor(private val registers: IntArray) {
    operator fun get(register: Int): Int = if (register in registers.indices) registers[register] else 0

    val trackNumber: Int get() = this[REG_TRACK_NUMBER]
    val frameIndex: Int get() = this[0x49] or (this[0x4A] shl 8)
    val frameCount: Int get() = this[0x4D] or (this[0x4E] shl 8)
    val kind: SegmentKind get() = SegmentKind.of(this[REG_SEGMENT_KIND])
    val continueTrack: Int get() = this[REG_CONTINUE_TRACK]
    val offersChoice: Boolean get() = kind == SegmentKind.Choice || kind == SegmentKind.TaggedChoice

    val branches: List<BranchEntry> by lazy {
        val tagged = kind == SegmentKind.TaggedChoice
        val list = ArrayList<BranchEntry>(BRANCH_ENTRY_COUNT)
        for (slot in 0 until BRANCH_ENTRY_COUNT) {
            val reg = REG_BRANCH_BASE + slot * BRANCH_STRIDE
            val tag = if (tagged) this[reg] else 0
            val first = if (tagged) reg + 1 else reg
            val track = this[first]
            if (track == 0) continue
            val then = ArrayList<Int>(3)
            var r = first + 1
            while (r < reg + BRANCH_STRIDE && this[r] != 0) { then.add(this[r]); r++ }
            list.add(BranchEntry(slot, tag, track, then))
        }
        list
    }

    companion object {
        const val REG_SEGMENT_KIND = 0x4B
        const val REG_CONTINUE_TRACK = 0x4F
        const val REG_BRANCH_BASE = 0x50
        const val BRANCH_STRIDE = 4
        const val BRANCH_ENTRY_COUNT = 6
        const val REG_TRACK_NUMBER = 0x77

        val EMPTY = FrameHeader(IntArray(256))

        /** Parses the header from a frame's de-interleaved video bytes. */
        fun parse(video: ByteArray, layout: FrameLayout): FrameHeader {
            if (layout.monochrome) return EMPTY
            require(video.size >= layout.headerBytes) { "Frame is shorter than its header" }
            val regs = IntArray(256)
            val sync = FrameLayout.SYNC_WORD
            val group = 9
            var i = 0
            // Read by position: 0xFF is also a legal value (frame index low byte on frame 255...).
            while (i + group <= layout.headerBytes) {
                if (video.matchesAt(sync, i)) { i += group; continue }
                var pair = i
                while (pair < i + group - 1) {
                    val reg = video[pair + 1].toInt() and 0xFF
                    if (reg != 0xFF) regs[reg] = video[pair].toInt() and 0xFF
                    pair += 2
                }
                i += group
            }
            return FrameHeader(regs)
        }
    }
}

/** One de-interleaved frame. */
class VideoNowFrame internal constructor(
    val trackNumber: Int,
    val frameIndex: Int,
    val video: ByteArray,
    val audio: ByteArray,
    val layout: FrameLayout,
    val pictureOffset: Int = layout.headerBytes,
) {
    fun readHeader(): FrameHeader = FrameHeader.parse(video, layout)

    fun pictureIsBlank(): Boolean {
        for (i in pictureOffset until pictureOffset + layout.pictureBytes) if (video[i].toInt() != 0) return false
        return true
    }

    fun audioIsSilent(): Boolean = audio.all { it == 0x80.toByte() }
}

/** Reads frames from one track, de-interleaving as it goes. Not thread-safe. */
class TrackReader(private val track: DiscTrack, val layout: FrameLayout) {
    private val index: BlackAndWhiteStream.FrameIndex?
    private val stream: ByteArray
    val startOffset: Long
    val frameCount: Int
    val trackNumber: Int get() = track.number
    val durationSeconds: Double get() = layout.frameSeconds * frameCount

    init {
        if (layout.monochrome) {
            index = BlackAndWhiteStream.indexFrames(track)
            frameCount = index.count
            startOffset = if (frameCount > 0) index.starts[0] else 0
            stream = ByteArray(0)
        } else {
            index = null
            startOffset = findFirstFrameOffset()
            frameCount = maxOf(0L, (track.byteLength - startOffset) / layout.streamBytes).toInt()
            stream = ByteArray(layout.streamBytes)
        }
    }

    /** True for a Linear segment whose every frame is black and silent. */
    fun isBlank(): Boolean {
        if (frameCount == 0 || layout.monochrome) return false
        for (i in 0 until frameCount) {
            val f = readFrame(i) ?: return false
            if (i == 0 && f.readHeader().kind != SegmentKind.Linear) return false
            if (!f.pictureIsBlank() || !f.audioIsSilent()) return false
        }
        return true
    }

    fun readFrame(frameIndex: Int): VideoNowFrame? {
        if (frameIndex < 0 || frameIndex >= frameCount) return null
        if (layout.monochrome) return readBlackAndWhiteFrame(frameIndex)
        val offset = startOffset + frameIndex.toLong() * layout.streamBytes
        if (track.read(offset, stream) < layout.streamBytes) return null
        val video = ByteArray(layout.videoBytes)
        val audio = ByteArray(layout.audioBytes)
        deinterleave(stream, layout.streamBytes, video, audio)
        return VideoNowFrame(track.number, frameIndex, video, audio, layout)
    }

    /** Reads only the header of a frame (enough to survey branch tables). */
    fun readHeader(frameIndex: Int): FrameHeader? {
        if (frameIndex < 0 || frameIndex >= frameCount) return null
        if (layout.monochrome) return FrameHeader.EMPTY
        val groups = (layout.headerBytes + 8) / 9
        val len = groups * FrameLayout.GROUP_BYTES
        val offset = startOffset + frameIndex.toLong() * layout.streamBytes
        if (track.read(offset, stream, 0, len) < len) return null
        val video = ByteArray(groups * 9)
        deinterleave(stream, len, video, null)
        return FrameHeader.parse(video, layout)
    }

    private fun readBlackAndWhiteFrame(frameIndex: Int): VideoNowFrame? {
        val g = BlackAndWhiteStream.GROUP_BYTES
        val idx = index!!
        val start = idx.starts[frameIndex]
        val picture = start + idx.pictureOffsets[frameIndex]
        val pictureEnd = picture + BlackAndWhiteStream.PICTURE_GROUPS * g
        val from = if (frameIndex == 0) idx.alignment.toLong() else start
        val to = if (frameIndex + 1 < idx.count) idx.starts[frameIndex + 1]
        else idx.alignment + (track.byteLength - idx.alignment) / g * g

        val bytes = ByteArray((to - from).toInt())
        if (track.read(from, bytes) < bytes.size) return null
        val audio = ByteArray(bytes.size / g)
        BlackAndWhiteStream.deinterleave(bytes, 0, bytes.size, null, audio)
        val video = ByteArray(((pictureEnd - start) / g * 2).toInt())
        BlackAndWhiteStream.deinterleave(bytes, (start - from).toInt(), (pictureEnd - start).toInt(), video, null)
        val pictureOffset = ((picture - start) / g * 2).toInt()
        return VideoNowFrame(track.number, frameIndex, video, audio, layout, pictureOffset)
    }

    private fun findFirstFrameOffset(): Long {
        val window = ByteArray(minOf(layout.streamBytes * 2L, track.byteLength).toInt())
        val read = track.read(0, window)
        val i = window.indexOf(FrameLayout.SYNC_WORD, 0, read)
        return if (i < 0) 0 else i.toLong()
    }

    companion object {
        /** Splits an interleaved Color/XP stream (9 video : 1 audio). */
        fun deinterleave(stream: ByteArray, length: Int, video: ByteArray, audio: ByteArray?) {
            val groups = length / FrameLayout.GROUP_BYTES
            for (gi in 0 until groups) {
                val s = gi * FrameLayout.GROUP_BYTES
                System.arraycopy(stream, s, video, gi * 9, 9)
                if (audio != null) audio[gi] = stream[s + 9]
            }
        }
    }
}
