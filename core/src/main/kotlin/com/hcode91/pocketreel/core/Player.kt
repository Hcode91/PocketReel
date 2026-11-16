package com.hcode91.pocketreel.core

/*
 * Player logic ported from vxp (https://github.com/zdiemer/vxp, MIT licence):
 * transport, branch tables, play lists, score, timed prompts and held choices.
 */

enum class TransportState { Stopped, Playing, Paused }

/** FollowHeader plays the disc the way it declares; DiscOrder walks every segment in turn. */
enum class NavigationPolicy { FollowHeader, DiscOrder }

/** What a Choice segment does when it ends unanswered. */
enum class ChoiceTimeout { Wait, FirstBranch, DiscOrder }

enum class LoopMode { None, Track, Disc }

/** Summary of one track, for the track browser. */
data class TrackInfo(
    val number: Int,
    val title: String?,
    val hasVideo: Boolean,
    val format: DiscFormat,
    val frameCount: Int,
    val durationSeconds: Double,
    val kind: SegmentKind,
    val continueTrack: Int,
    val branches: List<BranchEntry>,
    val blank: Boolean,
) {
    val offersChoice: Boolean get() = kind == SegmentKind.Choice || kind == SegmentKind.TaggedChoice
}

/**
 * A VideoNow player. Audio is the master clock: the host pulls PCM through [renderAudio]
 * at [outputSampleRate] and the player decodes exactly as many frames as that consumes.
 * All public members are thread-safe.
 */
class VideoNowPlayer(val disc: DiscImage, layout: FrameLayout? = null) : AutoCloseable {
    private val gate = Any()
    private val readers = HashMap<Int, TrackReader?>()
    private val blankCache = HashMap<Int, Boolean>()
    private val history = ArrayList<Int>()
    private val followOn = ArrayList<Int>()

    private var reader: TrackReader? = null
    private var frameAudio = ByteArray(0)
    private var audioPosition = 0.0
    private var pendingChoice: BranchEntry? = null

    val layout: FrameLayout = layout ?: FormatDetector.detect(disc)
        ?: throw IllegalArgumentException("No VideoNow video was found on this disc.")

    /** Decoded ARGB picture of the current frame, at [layout]'s picture size. */
    val framebuffer = IntArray(this.layout.pixelCount)
    val pictureWidth: Int get() = layout.pictureWidth
    val pictureHeight: Int get() = layout.pictureHeight
    val outputSampleRate: Int get() = layout.playbackSampleRate

    @Volatile var state = TransportState.Stopped; private set
    @Volatile var navigation = NavigationPolicy.FollowHeader
    @Volatile var choiceTimeout = ChoiceTimeout.Wait
    @Volatile var loop = LoopMode.None
    /** When true (default) a button takes its branch at once, as the discs are cut for. */
    @Volatile var instantChoices = true

    var speed = 1.0
        set(value) { field = value.coerceIn(0.25, 8.0) }

    var currentFrame = 0; private set
    var currentHeader: FrameHeader? = null; private set
    var branches: List<BranchEntry> = emptyList(); private set
    var score = INITIAL_SCORE; private set
    /** Samples produced by [renderAudio] so far; read in [onFrame] it is the new frame's start sample. */
    var samplesRendered = 0L; private set

    /** Called (on the audio thread, under the player lock) whenever [framebuffer] changes. */
    @Volatile var onFrame: ((VideoNowPlayer) -> Unit)? = null
    @Volatile var onTrackChanged: ((VideoNowPlayer) -> Unit)? = null

    val currentTrack: Int get() = reader?.trackNumber ?: 0
    val trackFrameCount: Int get() = reader?.frameCount ?: 0
    val trackLayout: FrameLayout get() = reader?.layout ?: layout
    val positionSeconds: Double get() = trackLayout.frameSeconds * currentFrame
    val trackSeconds: Double get() = trackLayout.frameSeconds * trackFrameCount
    val canGoBack: Boolean get() = synchronized(gate) { history.isNotEmpty() }

    /** True while the frame on screen asks the viewer something (ignoring the standing menu key in slot 6). */
    val isChoicePoint: Boolean
        get() = synchronized(gate) {
            val h = currentHeader
            if (h != null && h.offersChoice) branches.isNotEmpty()
            else branches.any { it.slot != FrameHeader.BRANCH_ENTRY_COUNT - 1 }
        }

    /** Slots (0-5) the frame on screen offers. */
    val activeSlots: Set<Int>
        get() = synchronized(gate) {
            if (currentHeader?.kind == SegmentKind.TaggedChoice) emptySet() else branches.map { it.slot }.toSet()
        }

    init {
        selectTrack(firstPlayableTrack())
    }

    fun play() = synchronized(gate) { if (reader != null) state = TransportState.Playing }
    fun pause() = synchronized(gate) { if (state == TransportState.Playing) state = TransportState.Paused }

    fun togglePause() = synchronized(gate) {
        state = when (state) {
            TransportState.Playing -> TransportState.Paused
            TransportState.Paused -> TransportState.Playing
            TransportState.Stopped -> if (reader == null) TransportState.Stopped else TransportState.Playing
        }
    }

    fun stop() = synchronized(gate) {
        state = TransportState.Stopped
        history.clear()
        score = INITIAL_SCORE
        selectTrackCore(firstPlayableTrack())
    }

    fun selectTrack(number: Int) = synchronized(gate) { selectTrackCore(number) }

    fun nextTrack() = synchronized(gate) { nextPlayableTrack(currentTrack)?.let { selectTrackCore(it) } }

    fun previousTrack() = synchronized(gate) {
        if (currentFrame > trackLayout.playbackFrameRate) selectTrackCore(currentTrack)
        else selectTrackCore(previousPlayableTrack(currentTrack) ?: currentTrack)
    }

    /** Back to the segment played before this one; undoes a wrong turn. */
    fun goBack(): Boolean = synchronized(gate) {
        if (history.isEmpty()) return false
        val target = history.removeAt(history.size - 1)
        val restore = history.size
        selectTrackCore(target)
        while (history.size > restore) history.removeAt(history.size - 1)
        true
    }

    fun seekToFrame(frame: Int) = synchronized(gate) { seekCore(frame) }

    fun seekBy(seconds: Double) = synchronized(gate) {
        seekCore(currentFrame + Math.round(seconds * trackLayout.playbackFrameRate).toInt())
    }

    fun stepFrame(delta: Int) = synchronized(gate) {
        state = TransportState.Paused
        seekCore(currentFrame + delta)
    }

    /** A press of button [slot] (0-5). Returns true if the frame on screen offers it. */
    fun press(slot: Int): Boolean = synchronized(gate) {
        val branch = branches.firstOrNull { it.slot == slot } ?: return false
        if (currentHeader?.kind == SegmentKind.TaggedChoice) return false
        if (instantChoices) {
            if (!takeBranch(branch)) return false
            decodeCurrentFrameForDisplay()
            if (state == TransportState.Stopped) state = TransportState.Playing
        } else {
            pendingChoice = branch
        }
        true
    }

    /** Fills [out] with mono 16-bit PCM at [outputSampleRate], advancing playback by that much. */
    fun renderAudio(out: ShortArray, count: Int = out.size): Int = synchronized(gate) {
        var i = 0
        while (i < count) {
            if (state != TransportState.Playing) return silence(out, i, count)
            while (audioPosition >= frameAudio.size) {
                audioPosition -= frameAudio.size
                if (!advanceFrame()) return silence(out, i, count)
            }
            out[i] = sampleAt(audioPosition)
            audioPosition += speed * trackLayout.playbackSampleRate / layout.playbackSampleRate
            samplesRendered++
            i++
        }
        count
    }

    /** Surveys every track's first frame (and blank status) for the track browser. */
    fun trackInfos(): List<TrackInfo> = synchronized(gate) {
        disc.tracks.map { t ->
            val r = reader(t.number)
            if (r == null || r.frameCount == 0) {
                TrackInfo(t.number, t.title, false, DiscFormat.Unknown, 0, 0.0, SegmentKind.None, 0, emptyList(), false)
            } else {
                val h = r.readHeader(0)
                TrackInfo(
                    t.number, t.title, true, r.layout.format, r.frameCount, r.durationSeconds,
                    h?.kind ?: SegmentKind.None, h?.continueTrack ?: 0, h?.branches ?: emptyList(), isBlank(t.number),
                )
            }
        }
    }

    // ---- internals (call under gate) ----

    private fun silence(out: ShortArray, from: Int, count: Int): Int {
        java.util.Arrays.fill(out, from, count, 0.toShort())
        samplesRendered += (count - from)
        return count
    }

    private fun sampleAt(position: Double): Short {
        if (frameAudio.isEmpty()) return 0
        val idx = position.toInt().coerceIn(0, frameAudio.size - 1)
        val cur = ((frameAudio[idx].toInt() and 0xFF) - 128) shl 8
        val next = if (idx + 1 < frameAudio.size) ((frameAudio[idx + 1].toInt() and 0xFF) - 128) shl 8 else cur
        return (cur + (next - cur) * (position - idx)).toInt().toShort()
    }

    private fun seekCore(frame: Int) {
        val r = reader ?: return
        currentFrame = frame.coerceIn(0, maxOf(0, r.frameCount - 1))
        frameAudio = ByteArray(0)
        audioPosition = 0.0
        decodeCurrentFrameForDisplay()
    }

    private fun advanceFrame(): Boolean {
        if (reader == null) return false
        if (currentFrame >= reader!!.frameCount && !goToNextSegment()) return false
        var frame = reader!!.readFrame(currentFrame)
        if (frame == null) {
            if (!goToNextSegment()) return false
            frame = reader!!.readFrame(currentFrame)
            if (frame == null) { state = TransportState.Stopped; return false }
        }
        currentFrame++
        applyFrame(frame)
        if (frame.frameIndex == 0) applyScore(currentHeader!!.kind)
        frameAudio = frame.audio
        return true
    }

    private fun applyFrame(frame: VideoNowFrame) {
        currentHeader = frame.readHeader()
        branches = currentHeader!!.branches
        if (frame.layout.pixelCount == framebuffer.size) VideoDecoder.decode(frame, framebuffer)
        else java.util.Arrays.fill(framebuffer, 0xFF000000.toInt())
        onFrame?.invoke(this)
    }

    private fun applyScore(kind: SegmentKind) {
        score = when (kind) {
            SegmentKind.ScoreUp -> minOf(score + 1, 255)
            SegmentKind.ScoreDown -> maxOf(score - 1, 0)
            SegmentKind.ScoreReset -> INITIAL_SCORE
            else -> score
        }
    }

    private fun takeBranch(branch: BranchEntry): Boolean {
        if (!loadTrack(branch.track, remember = true)) return false
        followOn.clear()
        followOn.addAll(branch.then)
        return true
    }

    private fun goToNextSegment(): Boolean {
        if (loop == LoopMode.Track) { currentFrame = 0; return true }
        val header = currentHeader

        pendingChoice?.let { if (takeBranch(it)) return true }

        if (navigation == NavigationPolicy.FollowHeader && header != null) {
            if (header.continueTrack > 0 && loadTrack(header.continueTrack, remember = true)) return true
            while (followOn.isNotEmpty()) {
                val queued = followOn.removeAt(0)
                if (loadTrack(queued, remember = true)) return true
            }
            if (header.kind == SegmentKind.TaggedChoice) {
                for (b in header.branches) if (score >= b.tag && takeBranch(b)) return true
            }
        }

        if (header != null && header.offersChoice) {
            if (choiceTimeout == ChoiceTimeout.Wait && header.kind == SegmentKind.Choice &&
                loadTrack(currentTrack, remember = false)
            ) return true
            if (choiceTimeout == ChoiceTimeout.FirstBranch && header.branches.isNotEmpty() &&
                takeBranch(header.branches[0])
            ) return true
        }

        val next = nextPlayableTrack(currentTrack)
        if (next != null && loadTrack(next, remember = true)) return true
        return endOfPlayback()
    }

    private fun endOfPlayback(): Boolean {
        if (loop == LoopMode.Disc && loadTrack(firstPlayableTrack(), remember = false)) {
            followOn.clear()
            score = INITIAL_SCORE
            return true
        }
        state = TransportState.Stopped
        return false
    }

    private fun loadTrack(number: Int, remember: Boolean): Boolean {
        val r = reader(number)
        if (r == null || r.frameCount == 0) return false
        val previous = reader?.trackNumber ?: 0
        val changed = r.trackNumber != previous
        if (remember && changed && previous != 0) {
            history.add(previous)
            if (history.size > 256) history.removeAt(0)
        }
        reader = r
        currentFrame = 0
        pendingChoice = null
        frameAudio = ByteArray(0)
        audioPosition = 0.0
        if (changed) onTrackChanged?.invoke(this)
        return true
    }

    private fun selectTrackCore(number: Int) {
        if (!loadTrack(number, remember = true)) return
        followOn.clear()
        decodeCurrentFrameForDisplay()
    }

    private fun decodeCurrentFrameForDisplay() {
        val frame = reader?.readFrame(currentFrame) ?: return
        applyFrame(frame)
    }

    private fun reader(number: Int): TrackReader? = readers.getOrPut(number) {
        val track = disc.findTrack(number) ?: return@getOrPut null
        val l = FormatDetector.detect(track) ?: return@getOrPut null
        TrackReader(track, l)
    }

    fun isPlayable(number: Int): Boolean = synchronized(gate) { (reader(number)?.frameCount ?: 0) > 0 }

    fun isBlank(number: Int): Boolean = synchronized(gate) { blankCache.getOrPut(number) { reader(number)?.isBlank() == true } }

    private fun worthVisiting(n: Int) = isPlayable(n) && !isBlank(n)

    private fun firstPlayableTrack(): Int {
        disc.tracks.firstOrNull { worthVisiting(it.number) }?.let { return it.number }
        disc.tracks.firstOrNull { isPlayable(it.number) }?.let { return it.number }
        return disc.tracks.firstOrNull()?.number ?: 1
    }

    private fun nextPlayableTrack(n: Int): Int? {
        val i = disc.tracks.indexOfFirst { it.number == n }
        if (i < 0) return null
        for (j in i + 1 until disc.tracks.size) if (worthVisiting(disc.tracks[j].number)) return disc.tracks[j].number
        return null
    }

    private fun previousPlayableTrack(n: Int): Int? {
        val i = disc.tracks.indexOfFirst { it.number == n }
        if (i < 0) return null
        for (j in i - 1 downTo 0) if (worthVisiting(disc.tracks[j].number)) return disc.tracks[j].number
        return null
    }

    override fun close() = disc.close()

    companion object {
        const val INITIAL_SCORE = 0x64
    }
}
