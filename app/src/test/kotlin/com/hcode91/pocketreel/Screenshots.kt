package com.hcode91.pocketreel

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.net.Uri
import android.view.View
import android.widget.BaseAdapter
import android.widget.ListView
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders the tutorial screenshots in docs/screenshots off-device. The picture on the
 * player's screen is an original drawing, not a frame from any real disc.
 *
 *   gradle :app:testDebugUnitTest --tests '*Screenshots*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-port-xxhdpi")
class Screenshots {
    private val out = File(System.getProperty("screenshots.dir") ?: "../docs/screenshots").apply { mkdirs() }

    private fun save(view: View, name: String, w: Int, h: Int) {
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, w, h)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bmp))
        val small = Bitmap.createScaledBitmap(bmp, w / 2, h / 2, true)
        File(out, "$name.png").outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** An original 144 x 80 scene standing in for a disc's picture. */
    private fun demoPicture(): Bitmap {
        val bmp = Bitmap.createBitmap(144, 80, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, 80f, Color.rgb(0x3B, 0x2A, 0x8C), Color.rgb(0xFF, 0x9E, 0x5E), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 144f, 80f, p)
        p.shader = null
        p.color = Color.rgb(0xFF, 0xE0, 0x7A); c.drawCircle(110f, 34f, 13f, p)
        p.color = Color.rgb(0x2E, 0x6B, 0x4F)
        c.drawPath(Path().apply { moveTo(0f, 62f); quadTo(40f, 44f, 80f, 60f); quadTo(115f, 72f, 144f, 56f); lineTo(144f, 80f); lineTo(0f, 80f); close() }, p)
        p.color = Color.rgb(0x1E, 0x4A, 0x36)
        c.drawPath(Path().apply { moveTo(0f, 72f); quadTo(60f, 60f, 144f, 74f); lineTo(144f, 80f); lineTo(0f, 80f); close() }, p)
        p.color = Color.WHITE; p.textSize = 12f; p.isFakeBoldText = true; p.textAlign = Paint.Align.CENTER
        c.drawText("YOUR DISC HERE", 60f, 22f, p)
        // Quantise to 4 bits per channel, as a real disc would be.
        val px = IntArray(144 * 80)
        bmp.getPixels(px, 0, 144, 0, 0, 144, 80)
        for (i in px.indices) {
            val v = px[i]
            fun q(s: Int) = (((v shr s) and 0xFF) shr 4) * 0x11
            px[i] = (0xFF shl 24) or (q(16) shl 16) or (q(8) shl 8) or q(0)
        }
        bmp.setPixels(px, 0, 144, 0, 0, 144, 80)
        return bmp
    }

    private fun handheld(w: Int, h: Int, hints: Set<Int>, osd: String?): HandheldView {
        val ctx = RuntimeEnvironment.getApplication()
        return HandheldView(ctx).apply {
            picture = demoPicture()
            activeSlots = hints
            playing = true
            measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            layout(0, 0, w, h)
            if (osd != null) osd(osd)
        }
    }

    @Test
    fun library() {
        val activity: Activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val field = MainActivity::class.java.getDeclaredField("entries").apply { isAccessible = true }
        fun doc(name: String, mb: Long) = DocFile(Uri.parse("content://demo/$name"), name, mb * 1_000_000, false, null)
        field.set(activity, listOf(
            LibraryEntry("Example Adventure (XP)", doc("Example Adventure (XP).cue", 412), emptyList(), "Cue sheet"),
            LibraryEntry("Sample Cartoons Disc 1", doc("Sample Cartoons Disc 1.zip", 150), emptyList(), "Zip"),
            LibraryEntry("Test Card (Color)", doc("Test Card (Color).bin", 96), emptyList(), "Single track"),
        ))
        val root = activity.window.decorView.findViewById<View>(android.R.id.content)
        fun findList(v: View): ListView? = when (v) {
            is ListView -> v
            is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { findList(v.getChildAt(it)) }
            else -> null
        }
        (findList(root)!!.adapter as BaseAdapter).notifyDataSetChanged()
        val status = activity.javaClass.getDeclaredField("status").apply { isAccessible = true }.get(activity) as android.widget.TextView
        status.text = "3 discs in your library"
        save(root, "1-library", 1080, 2340)
    }

    @Test
    fun playerPortrait() {
        save(handheld(1080, 2340, setOf(0, 1, 2, 3), "Track 5 / 97"), "2-player-choice", 1080, 2340)
    }

    @Test
    fun playerLandscape() {
        save(handheld(2340, 1080, setOf(5), null), "3-player-landscape", 2340, 1080)
    }
}
