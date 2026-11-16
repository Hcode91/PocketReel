package com.hcode91.pocketreel

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** The on-screen controls. Slots 0-5 are the disc's six buttons. */
enum class Control(val slot: Int = -1) {
    Up(0), Right(1), Down(2), Left(3), Ok(4), Menu(5),
    Prev, PlayPause, Next, Back, Tracks, Settings, Fullscreen
}

/**
 * An original handheld skin: the picture in a bezel, a D-pad and two face buttons for the
 * disc's six choices, and a row of transport keys. Portrait and landscape layouts.
 */
class HandheldView(context: Context) : View(context) {
    var listener: ((Control, Boolean) -> Unit)? = null

    var picture: Bitmap? = null
    /** Draw the picture at 4:3 (the cartoons' real shape) rather than the stored pixel grid. */
    var trueAspect = true
    var lcdEffect = true
    var showHints = true
    var activeSlots: Set<Int> = emptySet()
    var playing = false
    /** Hide the skin and show only the picture (tap to bring the controls back). */
    var fullscreen = false

    private var osdText: String? = null
    private var osdUntil = 0L

    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val picturePaint = Paint().apply { isFilterBitmap = false }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val body = RectF()
    private val bezel = RectF()
    private val screen = RectF()
    private val pictureRect = RectF()
    private var dpadX = 0f
    private var dpadY = 0f
    private var dpadR = 0f
    private var okX = 0f
    private var okY = 0f
    private var menuX = 0f
    private var menuY = 0f
    private var faceR = 0f
    private val pills = LinkedHashMap<Control, RectF>()
    private var logoX = 0f
    private var logoY = 0f
    private var logoSize = 0f

    private val pressed = HashMap<Int, Control>()

    fun osd(text: String) {
        osdText = text
        osdUntil = SystemClock.uptimeMillis() + 1800
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        layoutFor(w.toFloat(), h.toFloat())
    }

    private fun layoutFor(w: Float, h: Float) {
        val m = min(w, h) * 0.035f
        body.set(m * 0.5f, m * 0.5f, w - m * 0.5f, h - m * 0.5f)
        pills.clear()
        val pillControls = listOf(Control.Prev, Control.PlayPause, Control.Next, Control.Back, Control.Tracks, Control.Settings, Control.Fullscreen)

        if (fullscreen) {
            screen.set(0f, 0f, w, h)
            bezel.set(screen)
            return
        }

        if (h >= w) {
            // Portrait: logo, screen, controls, transport row.
            logoSize = w * 0.05f
            logoX = w / 2
            logoY = m * 2 + logoSize
            val sw = w - m * 4
            val sh = sw * 3f / 4f
            bezel.set(m * 2, logoY + m, w - m * 2, logoY + m + sh + m * 2)
            screen.set(bezel.left + m, bezel.top + m, bezel.right - m, bezel.bottom - m)

            val pillH = min(w * 0.11f, 56 * dp)
            val pillTop = h - m * 2 - pillH
            layoutPills(pillControls, m * 2, pillTop, w - m * 2, pillH)

            val ctrlTop = bezel.bottom + m
            val ctrlBottom = pillTop - m
            val cy = (ctrlTop + ctrlBottom) / 2
            dpadR = min(w * 0.2f, (ctrlBottom - ctrlTop) * 0.45f)
            dpadX = w * 0.28f
            dpadY = cy
            faceR = dpadR * 0.42f
            okX = w * 0.80f; okY = cy - faceR * 0.6f
            menuX = w * 0.62f; menuY = cy + faceR * 0.9f
        } else {
            // Landscape: controls either side of the screen, transport row underneath.
            val pillH = min(h * 0.12f, 52 * dp)
            val pillTop = h - m * 1.5f - pillH
            var sh = pillTop - m * 2 - m * 2
            var sw = sh * 4f / 3f
            val side = w * 0.22f
            if (sw > w - side * 2) { sw = w - side * 2; sh = sw * 3f / 4f }
            val left = (w - sw) / 2
            screen.set(left, m * 2, left + sw, m * 2 + sh)
            bezel.set(screen.left - m, screen.top - m, screen.right + m, screen.bottom + m)
            layoutPills(pillControls, bezel.left, pillTop, bezel.right, pillH)

            logoSize = h * 0.035f
            logoX = bezel.left / 2
            logoY = m * 2 + logoSize
            val cy = h * 0.5f
            dpadR = min(bezel.left * 0.42f, h * 0.22f)
            dpadX = bezel.left / 2
            dpadY = cy
            faceR = dpadR * 0.42f
            val rightMid = (bezel.right + w) / 2
            okX = rightMid + faceR * 0.9f; okY = cy - faceR * 0.8f
            menuX = rightMid - faceR * 0.9f; menuY = cy + faceR * 0.8f
        }
    }

    private fun layoutPills(controls: List<Control>, left: Float, top: Float, right: Float, height: Float) {
        val gap = height * 0.18f
        val pw = (right - left - gap * (controls.size - 1)) / controls.size
        controls.forEachIndexed { i, c ->
            val x = left + i * (pw + gap)
            pills[c] = RectF(x, top, x + pw, top + height)
        }
    }

    fun relayout() {
        layoutFor(width.toFloat(), height.toFloat())
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (fullscreen) {
            canvas.drawColor(Color.BLACK)
            drawPicture(canvas)
            drawOsd(canvas)
            return
        }

        // Body
        paint.color = Color.WHITE // a shader is drawn with the paint's alpha
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), BODY_TOP, BODY_BOTTOM, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(body, 40 * dp, 40 * dp, paint)
        paint.shader = null

        // Logo
        textPaint.textSize = logoSize
        textPaint.color = ACCENT
        canvas.drawText("PocketReel", logoX, logoY, textPaint)

        // Bezel and screen
        paint.color = BEZEL
        canvas.drawRoundRect(bezel, 18 * dp, 18 * dp, paint)
        paint.color = Color.BLACK
        canvas.drawRect(screen, paint)
        drawPicture(canvas)
        drawOsd(canvas)

        // D-pad
        drawDpad(canvas)

        // Face buttons
        drawFace(canvas, okX, okY, "OK", Control.Ok)
        drawFace(canvas, menuX, menuY, "MENU", Control.Menu)

        // Transport row
        for ((c, r) in pills) drawPill(canvas, c, r)
    }

    private fun drawPicture(canvas: Canvas) {
        val bmp = picture ?: return
        val aspect = if (trueAspect) 4f / 3f else bmp.width.toFloat() / bmp.height
        var w = screen.width()
        var h = w / aspect
        if (h > screen.height()) { h = screen.height(); w = h * aspect }
        val l = screen.centerX() - w / 2
        val t = screen.centerY() - h / 2
        pictureRect.set(l, t, l + w, t + h)
        canvas.drawBitmap(bmp, null, pictureRect, picturePaint)

        if (lcdEffect) {
            paint.color = Color.argb(70, 0, 0, 0)
            paint.strokeWidth = max(1f, h / bmp.height * 0.18f)
            for (y in 1 until bmp.height) {
                val yy = t + h * y / bmp.height
                canvas.drawLine(l, yy, l + w, yy, paint)
            }
            paint.color = Color.argb(35, 0, 0, 0)
            paint.strokeWidth = max(1f, w / bmp.width * 0.15f)
            for (x in 1 until bmp.width) {
                val xx = l + w * x / bmp.width
                canvas.drawLine(xx, t, xx, t + h, paint)
            }
            // A soft glare across the panel.
            paint.color = Color.WHITE // a shader is drawn with the paint's alpha
            paint.shader = LinearGradient(l, t, l + w * 0.6f, t + h, Color.argb(28, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawRect(pictureRect, paint)
            paint.shader = null
        }
    }

    private fun drawOsd(canvas: Canvas) {
        val text = osdText ?: return
        val now = SystemClock.uptimeMillis()
        if (now > osdUntil) { osdText = null; return }
        textPaint.textSize = max(12 * dp, screen.height() * 0.07f)
        textPaint.color = Color.WHITE
        val tw = textPaint.measureText(text)
        val pad = textPaint.textSize * 0.5f
        val y = screen.top + pad + textPaint.textSize
        paint.color = Color.argb(160, 0, 0, 0)
        canvas.drawRoundRect(screen.centerX() - tw / 2 - pad, screen.top + pad * 0.6f, screen.centerX() + tw / 2 + pad, y + pad * 0.6f, pad, pad, paint)
        canvas.drawText(text, screen.centerX(), y, textPaint)
        postInvalidateDelayed(osdUntil - now)
    }

    private fun isDown(c: Control) = pressed.values.contains(c)

    private fun hinted(c: Control) = showHints && c.slot >= 0 && c.slot in activeSlots

    private fun drawDpad(canvas: Canvas) {
        val arm = dpadR * 0.36f
        // Base disc
        paint.color = Color.WHITE
        paint.shader = RadialGradient(dpadX, dpadY, dpadR * 1.1f, Color.argb(90, 0, 0, 0), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        canvas.drawCircle(dpadX, dpadY, dpadR * 1.1f, paint)
        paint.shader = null

        val cross = Path().apply {
            addRoundRect(RectF(dpadX - arm, dpadY - dpadR, dpadX + arm, dpadY + dpadR), arm * 0.4f, arm * 0.4f, Path.Direction.CW)
            addRoundRect(RectF(dpadX - dpadR, dpadY - arm, dpadX + dpadR, dpadY + arm), arm * 0.4f, arm * 0.4f, Path.Direction.CW)
        }
        paint.color = DPAD
        canvas.drawPath(cross, paint)

        for (c in listOf(Control.Up, Control.Right, Control.Down, Control.Left)) {
            val (dx, dy) = when (c) {
                Control.Up -> 0f to -1f
                Control.Right -> 1f to 0f
                Control.Down -> 0f to 1f
                else -> -1f to 0f
            }
            val cx = dpadX + dx * dpadR * 0.62f
            val cy = dpadY + dy * dpadR * 0.62f
            if (isDown(c)) {
                paint.color = Color.argb(90, 0, 0, 0)
                canvas.drawCircle(cx, cy, arm * 0.95f, paint)
            }
            if (hinted(c)) {
                paint.color = HINT
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 3 * dp
                canvas.drawCircle(cx, cy, arm * 0.85f, paint)
                paint.style = Paint.Style.FILL
            }
            // Arrow
            val s = arm * 0.42f
            val p = Path().apply {
                moveTo(cx + dx * s, cy + dy * s)
                lineTo(cx - dx * s * 0.6f + dy * s, cy - dy * s * 0.6f + dx * s)
                lineTo(cx - dx * s * 0.6f - dy * s, cy - dy * s * 0.6f - dx * s)
                close()
            }
            paint.color = if (hinted(c)) HINT else DPAD_ARROW
            canvas.drawPath(p, paint)
        }
    }

    private fun drawFace(canvas: Canvas, x: Float, y: Float, label: String, c: Control) {
        paint.color = Color.argb(80, 0, 0, 0)
        canvas.drawCircle(x, y + faceR * 0.08f, faceR, paint)
            paint.color = Color.WHITE
            paint.shader = RadialGradient(x - faceR * 0.3f, y - faceR * 0.3f, faceR * 1.3f,
            if (isDown(c)) ACCENT_DARK else ACCENT, ACCENT_DARK, Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, faceR * (if (isDown(c)) 0.94f else 1f), paint)
        paint.shader = null
        if (hinted(c)) {
            paint.color = HINT
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3 * dp
            canvas.drawCircle(x, y, faceR * 1.15f, paint)
            paint.style = Paint.Style.FILL
        }
        textPaint.textSize = faceR * (if (label.length > 2) 0.42f else 0.6f)
        textPaint.color = BODY_BOTTOM
        canvas.drawText(label, x, y + textPaint.textSize * 0.36f, textPaint)
    }

    private fun drawPill(canvas: Canvas, c: Control, r: RectF) {
        paint.color = if (isDown(c)) PILL_DOWN else PILL
        canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, paint)
        val cx = r.centerX()
        val cy = r.centerY()
        val s = min(r.height(), r.width()) * 0.22f
        paint.color = ICON
        val p = Path()
        when (c) {
            Control.PlayPause -> if (playing) {
                canvas.drawRect(cx - s * 0.9f, cy - s, cx - s * 0.3f, cy + s, paint)
                canvas.drawRect(cx + s * 0.3f, cy - s, cx + s * 0.9f, cy + s, paint)
            } else {
                p.moveTo(cx - s * 0.7f, cy - s); p.lineTo(cx + s, cy); p.lineTo(cx - s * 0.7f, cy + s); p.close()
            }
            Control.Next -> {
                p.moveTo(cx - s, cy - s); p.lineTo(cx + s * 0.4f, cy); p.lineTo(cx - s, cy + s); p.close()
                canvas.drawRect(cx + s * 0.5f, cy - s, cx + s * 0.9f, cy + s, paint)
            }
            Control.Prev -> {
                p.moveTo(cx + s, cy - s); p.lineTo(cx - s * 0.4f, cy); p.lineTo(cx + s, cy + s); p.close()
                canvas.drawRect(cx - s * 0.9f, cy - s, cx - s * 0.5f, cy + s, paint)
            }
            Control.Back -> {
                // Curved "undo" arrow
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.35f
                canvas.drawArc(RectF(cx - s * 0.8f, cy - s * 0.7f, cx + s, cy + s), 200f, 250f, false, paint)
                paint.style = Paint.Style.FILL
                p.moveTo(cx - s * 1.2f, cy - s * 0.1f); p.lineTo(cx - s * 0.3f, cy - s * 0.25f); p.lineTo(cx - s * 0.95f, cy + s * 0.6f); p.close()
            }
            Control.Tracks -> for (i in -1..1) canvas.drawRect(cx - s, cy + i * s * 0.7f - s * 0.15f, cx + s, cy + i * s * 0.7f + s * 0.15f, paint)
            Control.Settings -> {
                for (i in -1..1) {
                    canvas.drawCircle(cx + i * s * 0.75f, cy, s * 0.28f, paint)
                }
            }
            Control.Fullscreen -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = s * 0.25f
                val q = s * 0.45f
                p.moveTo(cx - s, cy - q); p.lineTo(cx - s, cy - s); p.lineTo(cx - q, cy - s)
                p.moveTo(cx + q, cy - s); p.lineTo(cx + s, cy - s); p.lineTo(cx + s, cy - q)
                p.moveTo(cx + s, cy + q); p.lineTo(cx + s, cy + s); p.lineTo(cx + q, cy + s)
                p.moveTo(cx - q, cy + s); p.lineTo(cx - s, cy + s); p.lineTo(cx - s, cy + q)
                canvas.drawPath(p, paint)
                paint.style = Paint.Style.FILL
                p.reset()
            }
            else -> {}
        }
        if (!p.isEmpty) canvas.drawPath(p, paint)
    }

    private fun hit(x: Float, y: Float): Control? {
        if (fullscreen) return Control.Fullscreen
        for ((c, r) in pills) if (r.contains(x, y)) return c
        if (hypot(x - okX, y - okY) <= faceR * 1.25f) return Control.Ok
        if (hypot(x - menuX, y - menuY) <= faceR * 1.25f) return Control.Menu
        val dx = x - dpadX
        val dy = y - dpadY
        val d = hypot(dx, dy)
        if (d <= dpadR * 1.25f && d > dpadR * 0.15f) {
            return if (kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                if (dx > 0) Control.Right else Control.Left
            } else {
                if (dy > 0) Control.Down else Control.Up
            }
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                val c = hit(event.getX(i), event.getY(i)) ?: return true
                pressed[event.getPointerId(i)] = c
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                listener?.invoke(c, true)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val i = event.actionIndex
                pressed.remove(event.getPointerId(i))?.let { listener?.invoke(it, false) }
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed.values.forEach { listener?.invoke(it, false) }
                pressed.clear()
                invalidate()
            }
        }
        return true
    }

    companion object {
        val BODY_TOP = Color.rgb(0x3E, 0x2A, 0x6E)
        val BODY_BOTTOM = Color.rgb(0x1E, 0x14, 0x3A)
        val BEZEL = Color.rgb(0x10, 0x0A, 0x1E)
        val ACCENT = Color.rgb(0xFF, 0xB1, 0x3B)
        val ACCENT_DARK = Color.rgb(0xD9, 0x7A, 0x14)
        val DPAD = Color.rgb(0x2A, 0x24, 0x38)
        val DPAD_ARROW = Color.rgb(0x8C, 0x84, 0xA6)
        val HINT = Color.rgb(0x4F, 0xD2, 0xE3)
        val PILL = Color.rgb(0x2B, 0x1E, 0x4A)
        val PILL_DOWN = Color.rgb(0x52, 0x3A, 0x8A)
        val ICON = Color.rgb(0xE8, 0xE2, 0xF6)
    }
}
