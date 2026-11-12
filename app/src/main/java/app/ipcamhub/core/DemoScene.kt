package app.ipcamhub.core

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A built-in test picture, so the app can be tried without a camera: a calm scene with a clock,
 * and a ball that crosses it every [CYCLE_MS] so motion detection and motion recording have
 * something to react to. Frames are drawn on every attached surface (preview, encoders, motion
 * sampler) at 30 frames per second while at least one surface is attached.
 */
object DemoScene {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val FPS = 30
    private const val CYCLE_MS = 15_000L
    private const val CROSSING_MS = 3_000L

    private val surfaces = CopyOnWriteArrayList<Surface>()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val startMs = SystemClock.uptimeMillis()

    private val sky = Paint()
    private val ground = Paint().apply { color = Color.rgb(38, 52, 44) }
    private val house = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(58, 58, 74) }
    private val window = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 214, 120) }
    private val ball = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(184, 164, 255) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(184, 164, 255)
        typeface = Typeface.DEFAULT_BOLD
    }
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun addSurface(surface: Surface) {
        surfaces.addIfAbsent(surface)
        if (thread == null) {
            val t = HandlerThread("demo-scene").also { it.start() }
            thread = t
            handler = Handler(t.looper).also { it.post(tick) }
        }
    }

    @Synchronized
    fun removeSurface(surface: Surface) {
        surfaces.remove(surface)
        if (surfaces.isEmpty()) {
            handler?.removeCallbacksAndMessages(null)
            thread?.quitSafely()
            thread = null
            handler = null
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val h = handler ?: return
            val next = SystemClock.uptimeMillis() + 1000L / FPS
            val now = System.currentTimeMillis()
            for (s in surfaces) draw(s, now)
            h.postAtTime(this, next)
        }
    }

    private fun draw(surface: Surface, now: Long) {
        if (!surface.isValid) return
        val canvas: Canvas = try {
            surface.lockHardwareCanvas()
        } catch (e: Exception) {
            return // surface is being released or is already connected to another producer
        }
        try {
            paint(canvas, now)
        } finally {
            try {
                surface.unlockCanvasAndPost(canvas)
            } catch (_: Exception) {
            }
        }
    }

    /** Draws the scene scaled to the canvas, so any surface size works. */
    private fun paint(c: Canvas, now: Long) {
        val sx = c.width / WIDTH.toFloat()
        val sy = c.height / HEIGHT.toFloat()
        c.save()
        c.scale(sx, sy)
        if (sky.shader == null) {
            sky.shader = LinearGradient(0f, 0f, 0f, 480f, Color.rgb(27, 27, 34), Color.rgb(60, 54, 96), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, WIDTH.toFloat(), 480f, sky)
        c.drawRect(0f, 480f, WIDTH.toFloat(), HEIGHT.toFloat(), ground)
        // A house with lit windows.
        c.drawRect(760f, 280f, 1080f, 480f, house)
        c.drawRect(800f, 320f, 870f, 380f, window)
        c.drawRect(970f, 320f, 1040f, 380f, window)

        // The ball crosses the scene for a few seconds in every cycle.
        val t = (SystemClock.uptimeMillis() - startMs) % CYCLE_MS
        if (t < CROSSING_MS) {
            val p = t / CROSSING_MS.toFloat()
            val x = -100f + p * (WIDTH + 200f)
            val y = 560f - kotlin.math.abs(kotlin.math.sin(p * Math.PI * 3)).toFloat() * 180f
            c.drawOval(RectF(x - 90f, y - 90f, x + 90f, y + 90f), ball)
        }

        label.textSize = 34f
        c.drawText("CAMORA DEMO", 40f, 70f, label)
        text.textSize = 56f
        c.drawText(clock.format(Date(now)), 40f, 140f, text)
        c.restore()
    }
}
