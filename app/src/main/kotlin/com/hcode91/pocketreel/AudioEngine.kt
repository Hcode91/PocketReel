package com.hcode91.pocketreel

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.hcode91.pocketreel.core.VideoNowPlayer

/**
 * Drives the player from the audio clock. A thread pulls PCM from the player into an
 * AudioTrack; each decoded frame is queued with the sample it starts at, and the UI shows
 * the newest frame whose sound has actually reached the speaker, so lips stay in sync.
 */
class AudioEngine(private val player: VideoNowPlayer) {
    private class Pending(val sample: Long, val pixels: IntArray)

    private val lock = Any()
    private val queue = ArrayDeque<Pending>()
    private val pool = ArrayDeque<IntArray>()
    private var lastShown: Pending? = null

    @Volatile private var running = false
    private var thread: Thread? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile private var baseSample = 0L

    init {
        player.onFrame = { p -> enqueue(p.samplesRendered, p.framebuffer) }
        enqueue(player.samplesRendered, player.framebuffer)
    }

    private fun enqueue(sample: Long, pixels: IntArray) {
        synchronized(lock) {
            val copy = pool.removeFirstOrNull()?.takeIf { it.size == pixels.size } ?: IntArray(pixels.size)
            System.arraycopy(pixels, 0, copy, 0, pixels.size)
            queue.addLast(Pending(sample, copy))
            while (queue.size > 48) pool.addLast(queue.removeFirst().pixels)
        }
    }

    /** Samples the speaker has actually played, on the player's clock. */
    private fun playedSample(): Long {
        val t = audioTrack ?: return Long.MAX_VALUE
        return baseSample + (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL)
    }

    /** Copies the frame due now into [dest]; returns false if it hasn't changed since last time. */
    fun picture(dest: IntArray): Boolean {
        val now = playedSample()
        synchronized(lock) {
            var due: Pending? = null
            while (queue.isNotEmpty() && queue.first().sample <= now) {
                due?.let { pool.addLast(it.pixels) }
                due = queue.removeFirst()
            }
            // Nothing due yet but the queue is far ahead (e.g. right after starting): show the oldest.
            if (due == null && lastShown == null && queue.isNotEmpty()) due = queue.removeFirst()
            if (due == null) return false
            lastShown?.let { if (it !== due) pool.addLast(it.pixels) }
            lastShown = due
            if (due.pixels.size == dest.size) System.arraycopy(due.pixels, 0, dest, 0, dest.size)
            return true
        }
    }

    fun start() {
        if (running) return
        val rate = player.outputSampleRate
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val bufferBytes = maxOf(min * 2, rate / 10 * 2)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        baseSample = player.samplesRendered
        audioTrack = track
        running = true
        track.play()
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buffer = ShortArray(1024)
            while (running) {
                player.renderAudio(buffer)
                var off = 0
                while (off < buffer.size && running) {
                    val n = track.write(buffer, off, buffer.size - off)
                    if (n < 0) { running = false; break }
                    off += n
                }
            }
        }, "PocketReel audio").also { it.start() }
    }

    fun stop() {
        if (!running) return
        running = false
        audioTrack?.pause()
        audioTrack?.flush()
        thread?.join(500)
        thread = null
        audioTrack?.release()
        audioTrack = null
        // Frames queued against the old track's clock would never come due; keep only the latest.
        synchronized(lock) {
            val latest = queue.lastOrNull()
            queue.clear()
            if (latest != null) {
                lastShown = null
                queue.addLast(Pending(Long.MIN_VALUE, latest.pixels))
            }
        }
    }

    fun release() {
        stop()
        player.onFrame = null
    }
}
