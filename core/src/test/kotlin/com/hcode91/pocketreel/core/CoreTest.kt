package com.hcode91.pocketreel.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class CoreTest {
    @Test
    fun cueSheetParsesFilePerTrackAndSingleFile() {
        val tracks = CueSheet.parse(
            """
            FILE "a (Track 01).bin" BINARY
              TRACK 01 AUDIO
                TITLE "01logo.vn5"
                INDEX 01 00:00:00
            FILE "b.bin" BINARY
              TRACK 02 AUDIO
                INDEX 00 00:00:00
                INDEX 01 00:02:00
            """.trimIndent().lines(),
        )
        assertEquals(2, tracks.size)
        assertEquals("01logo.vn5", tracks[0].title)
        assertEquals(150, tracks[1].indexOneSector)
        assertEquals(0, tracks[1].indexZeroSector)
    }

    /** Builds one synthetic XP frame: 12 sync words, a register block, a picture and audio. */
    private fun xpFrame(kind: Int, branches: Map<Int, Int>, pixelByte: Int, audio: Int): ByteArray {
        val layout = FrameLayout.Xp
        val video = ByteArray(layout.videoBytes) { 0xFF.toByte() }
        var p = 0
        repeat(12) { FrameLayout.SYNC_WORD.copyInto(video, p); p += 9 }
        val regs = mutableListOf(kind to 0x4B, 5 to 0x77)
        branches.forEach { (slot, track) -> regs.add(track to 0x50 + slot * 4) }
        for (chunk in regs.chunked(4)) {
            var q = p
            for ((value, reg) in chunk) { video[q++] = value.toByte(); video[q++] = reg.toByte() }
            p += 9
        }
        java.util.Arrays.fill(video, layout.headerBytes, video.size, pixelByte.toByte())
        val stream = ByteArray(layout.streamBytes)
        for (g in 0 until layout.streamBytes / 10) {
            System.arraycopy(video, g * 9, stream, g * 10, 9)
            stream[g * 10 + 9] = audio.toByte()
        }
        return stream
    }

    @Test
    fun xpFrameRoundTrips() {
        val stream = xpFrame(2, mapOf(0 to 13, 1 to 32), 0x5A, 0x90)
        assertEquals(FrameLayout.Xp, FormatDetector.detect(stream))

        val video = ByteArray(FrameLayout.Xp.videoBytes)
        val audio = ByteArray(FrameLayout.Xp.audioBytes)
        TrackReader.deinterleave(stream, stream.size, video, audio)
        val header = FrameHeader.parse(video, FrameLayout.Xp)
        assertEquals(SegmentKind.Choice, header.kind)
        assertEquals(5, header.trackNumber)
        assertEquals(listOf(13, 32), header.branches.map { it.track })
        assertEquals(0x90.toByte(), audio[0])

        val argb = IntArray(FrameLayout.Xp.pixelCount)
        VideoDecoder.decodeArgb(video, FrameLayout.Xp.headerBytes, argb)
        // Every nibble is 0xA or 0x5, so every channel decodes to 0xAA or 0x55.
        val c = argb[0]
        assertNotNull(c)
        assertEquals(0xFF, c ushr 24)
        for (shift in listOf(0, 8, 16)) {
            val v = (c shr shift) and 0xFF
            assert(v == 0xAA || v == 0x55) { "channel $v" }
        }
    }

    @Test
    fun playerFollowsAPressAndHoldsAnUnansweredChoice() {
        val dir = kotlin.io.path.createTempDirectory("reel").toFile()
        val t1 = java.io.File(dir, "t1.bin").apply { writeBytes(xpFrame(2, mapOf(0 to 2), 0x11, 0x80) + xpFrame(2, mapOf(0 to 2), 0x11, 0x80)) }
        val t2 = java.io.File(dir, "t2.bin").apply { writeBytes(xpFrame(1, emptyMap(), 0x22, 0x80)) }
        val cue = java.io.File(dir, "d.cue").apply {
            writeText("FILE \"${t1.name}\" BINARY\n TRACK 01 AUDIO\n  INDEX 01 00:00:00\nFILE \"${t2.name}\" BINARY\n TRACK 02 AUDIO\n  INDEX 01 00:00:00\n")
        }
        VideoNowPlayer(DiscImage.fromCueFile(cue)).use { player ->
            player.play()
            val buf = ShortArray(1976 * 5)
            player.renderAudio(buf)
            assertEquals(1, player.currentTrack) // unanswered choice repeats
            assert(player.press(0))
            assertEquals(2, player.currentTrack)
            assert(player.canGoBack)
            player.goBack()
            assertEquals(1, player.currentTrack)
        }
        dir.deleteRecursively()
    }
}
