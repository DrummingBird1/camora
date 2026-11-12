package app.ipcamhub.core

import java.nio.ByteBuffer
import kotlin.math.abs

/** Pure functions for frame-difference motion detection on tiny thumbnails. */
object MotionMath {
    const val GRID_COLS = 64
    const val GRID_ROWS = 36

    /** Converts ARGB pixels to 0..255 luma. */
    fun toLuma(argb: IntArray): IntArray = IntArray(argb.size) {
        val p = argb[it]
        (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000
    }

    /** Fraction (0..1) of pixels whose luma changed by at least [pixelThreshold]. */
    fun changedFraction(a: IntArray, b: IntArray, pixelThreshold: Int = 24): Double {
        require(a.size == b.size && a.isNotEmpty()) { "frames must have the same non-zero size" }
        var changed = 0
        for (i in a.indices) if (abs(a[i] - b[i]) >= pixelThreshold) changed++
        return changed.toDouble() / a.size
    }

    /**
     * Samples a [cols] x [rows] grid from the Y (luma) plane of a camera frame without converting
     * the whole picture. [pixelStride] is 1 for a normal Y plane.
     */
    fun lumaGrid(
        plane: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        cols: Int = GRID_COLS,
        rows: Int = GRID_ROWS,
    ): IntArray {
        val out = IntArray(cols * rows)
        for (y in 0 until rows) {
            val srcY = if (rows == 1) 0 else y * (height - 1) / (rows - 1)
            for (x in 0 until cols) {
                val srcX = if (cols == 1) 0 else x * (width - 1) / (cols - 1)
                out[y * cols + x] = plane.get(srcY * rowStride + srcX * pixelStride).toInt() and 0xFF
            }
        }
        return out
    }
}

/** Turns a stream of thumbnails into motion events, with a cool-down so one movement is one event. */
class MotionDetector(private val sensitivity: Int, private val cooldownMs: Long = 10_000L) {
    private var previous: IntArray? = null
    private var lastEventMs = Long.MIN_VALUE / 2

    /** Share of the picture that must change: low, medium, high sensitivity. */
    val threshold: Double
        get() = when (sensitivity) {
            0 -> 0.05
            2 -> 0.008
            else -> 0.02
        }

    /** Returns the changed fraction when this frame starts a new motion event, otherwise null. */
    fun feed(luma: IntArray, nowMs: Long): Double? {
        val before = previous
        previous = luma
        if (before == null || before.size != luma.size) return null
        val changed = MotionMath.changedFraction(before, luma)
        if (changed >= threshold && nowMs - lastEventMs > cooldownMs) {
            lastEventMs = nowMs
            return changed
        }
        return null
    }
}
