package com.hcode91.pocketreel

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import com.hcode91.pocketreel.core.LoopMode
import com.hcode91.pocketreel.core.NavigationPolicy
import com.hcode91.pocketreel.core.SegmentKind
import com.hcode91.pocketreel.core.TransportState
import com.hcode91.pocketreel.core.VideoNowPlayer

class PlayerActivity : Activity(), Choreographer.FrameCallback {
    private lateinit var player: VideoNowPlayer
    private lateinit var engine: AudioEngine
    private lateinit var view: HandheldView
    private lateinit var bitmap: Bitmap
    private lateinit var pixels: IntArray
    private var lastTrack = -1
    private var lastSlots: Set<Int> = emptySet()
    private var lastPlaying = false
    private var resumed = false

    private val prefs by lazy { getSharedPreferences("pocketreel", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val disc = DiscSession.disc
        if (disc == null) { finish(); return }

        player = try {
            VideoNowPlayer(disc)
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle("Not a VideoNow disc")
                .setMessage(e.message ?: e.toString())
                .setPositiveButton("OK") { _, _ -> finish() }
                .setCancelable(false)
                .show()
            return
        }
        player.navigation = if (prefs.getBoolean(KEY_DISC_ORDER, false)) NavigationPolicy.DiscOrder else NavigationPolicy.FollowHeader
        player.loop = if (prefs.getBoolean(KEY_LOOP, false)) LoopMode.Disc else LoopMode.None

        pixels = IntArray(player.pictureWidth * player.pictureHeight)
        bitmap = Bitmap.createBitmap(player.pictureWidth, player.pictureHeight, Bitmap.Config.ARGB_8888)
        engine = AudioEngine(player)

        view = HandheldView(this).apply {
            picture = bitmap
            trueAspect = prefs.getBoolean(KEY_TRUE_ASPECT, true)
            lcdEffect = prefs.getBoolean(KEY_LCD, true)
            showHints = prefs.getBoolean(KEY_HINTS, true)
            listener = { c, down -> if (down) onControl(c) }
            isFocusable = true
        }
        setContentView(view)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        view.osd("${disc.name.take(40)} · ${player.layout.format.shortName}")
        player.play()
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        if (!::engine.isInitialized) return
        resumed = true
        engine.start()
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onPause() {
        super.onPause()
        if (!::engine.isInitialized) return
        resumed = false
        player.pause()
        engine.stop()
        Choreographer.getInstance().removeFrameCallback(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::engine.isInitialized) engine.release()
        // The disc stays mounted in DiscSession so the library can reopen it quickly.
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!resumed) return
        var dirty = false
        if (engine.picture(pixels)) {
            bitmap.setPixels(pixels, 0, player.pictureWidth, 0, 0, player.pictureWidth, player.pictureHeight)
            dirty = true
        }
        val track = player.currentTrack
        if (track != lastTrack) {
            lastTrack = track
            if (player.isBlank(track).not()) view.osd(trackLabel(track))
        }
        val slots = if (player.isChoicePoint) player.activeSlots else player.activeSlots.filter { it == 5 }.toSet()
        if (slots != lastSlots) { lastSlots = slots; view.activeSlots = slots; dirty = true }
        val playing = player.state == TransportState.Playing
        if (playing != lastPlaying) { lastPlaying = playing; view.playing = playing; dirty = true }
        if (dirty) view.invalidate()
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun trackLabel(track: Int): String {
        val total = player.disc.tracks.size
        return "Track $track / $total"
    }

    private fun onControl(c: Control) {
        if (c.slot >= 0) {
            if (!player.press(c.slot)) {
                // Not offered right now; the real player ignores it too.
            }
            return
        }
        when (c) {
            Control.PlayPause -> {
                player.togglePause()
                view.osd(if (player.state == TransportState.Playing) "Play" else "Paused")
            }
            Control.Next -> player.nextTrack()
            Control.Prev -> player.previousTrack()
            Control.Back -> view.osd(if (player.goBack()) "Back to track ${player.currentTrack}" else "Nothing to go back to")
            Control.Tracks -> showTracks()
            Control.Settings -> showSettings()
            Control.Fullscreen -> {
                view.fullscreen = !view.fullscreen
                view.relayout()
                if (view.fullscreen) view.osd("Tap to show controls")
            }
            else -> {}
        }
    }

    private fun showTracks() {
        val infos = player.trackInfos()
        val labels = infos.map { t ->
            val time = "%d:%02d".format((t.durationSeconds / 60).toInt(), (t.durationSeconds % 60).toInt())
            val kind = when {
                !t.hasVideo -> "no video"
                t.blank -> "blank"
                t.kind == SegmentKind.Choice -> "choice"
                t.kind == SegmentKind.TaggedChoice -> "score check"
                t.kind == SegmentKind.ScoreUp -> "+1"
                t.kind == SegmentKind.ScoreDown -> "−1"
                else -> ""
            }
            val mark = if (t.number == player.currentTrack) "▶ " else ""
            "$mark${t.number}.  $time  ${t.format.shortName}  $kind"
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Tracks")
            .setItems(labels) { _, which ->
                val t = infos[which]
                if (t.hasVideo) {
                    player.selectTrack(t.number)
                    player.play()
                }
            }
            .show()
    }

    private fun showSettings() {
        val names = arrayOf(
            "Highlight buttons that do something",
            "LCD screen effect",
            "Show at 4:3 (real shape)",
            "Play every track in disc order (ignore branching)",
            "Loop the disc",
        )
        val keys = arrayOf(KEY_HINTS, KEY_LCD, KEY_TRUE_ASPECT, KEY_DISC_ORDER, KEY_LOOP)
        val defaults = booleanArrayOf(true, true, true, false, false)
        val checked = BooleanArray(keys.size) { prefs.getBoolean(keys[it], defaults[it]) }
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setMultiChoiceItems(names, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Done") { _, _ ->
                prefs.edit().apply { keys.forEachIndexed { i, k -> putBoolean(k, checked[i]) } }.apply()
                view.showHints = checked[0]
                view.lcdEffect = checked[1]
                view.trueAspect = checked[2]
                player.navigation = if (checked[3]) NavigationPolicy.DiscOrder else NavigationPolicy.FollowHeader
                player.loop = if (checked[4]) LoopMode.Disc else LoopMode.None
                view.invalidate()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!::player.isInitialized) return super.onKeyDown(keyCode, event)
        if (event.repeatCount > 0) return true
        val control = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_W -> Control.Up
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_D -> Control.Right
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_S -> Control.Down
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_A -> Control.Left
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_5 -> Control.Ok
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_6 -> Control.Menu
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> Control.PlayPause
            KeyEvent.KEYCODE_BUTTON_R1, KeyEvent.KEYCODE_MEDIA_NEXT -> Control.Next
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> Control.Prev
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_DEL -> Control.Back
            KeyEvent.KEYCODE_BUTTON_SELECT -> Control.Tracks
            else -> null
        } ?: return super.onKeyDown(keyCode, event)
        onControl(control)
        return true
    }

    companion object {
        const val KEY_HINTS = "hints"
        const val KEY_LCD = "lcd"
        const val KEY_TRUE_ASPECT = "true_aspect"
        const val KEY_DISC_ORDER = "disc_order"
        const val KEY_LOOP = "loop_disc"
    }
}
