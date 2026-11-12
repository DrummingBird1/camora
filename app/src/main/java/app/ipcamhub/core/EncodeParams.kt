package app.ipcamhub.core

/** Per-camera quality choices for recording and streaming. */
data class Quality(
    /** Largest output height in pixels. 0 keeps the source size. */
    val maxHeight: Int = 0,
    /** 0 = low, 1 = medium, 2 = high, 3 = very high. */
    val bitrate: Int = 1,
    /** Encode H.265 (HEVC) instead of H.264: about the same picture at 60% of the bitrate. */
    val hevc: Boolean = false,
)

object EncodeParams {
    /** Scales (width, height) down to [maxHeight], keeping the aspect ratio and even sizes. */
    fun targetSize(width: Int, height: Int, maxHeight: Int): Pair<Int, Int> {
        val w0 = width and 1.inv()
        val h0 = height and 1.inv()
        if (maxHeight <= 0 || h0 <= maxHeight) return w0 to h0
        val h = maxHeight and 1.inv()
        val w = ((w0.toLong() * h / h0).toInt()) and 1.inv()
        return w to h
    }

    /** Bits per pixel per frame for each preset. */
    private val bitsPerPixel = doubleArrayOf(0.05, 0.10, 0.16, 0.24)

    fun bitrate(width: Int, height: Int, fps: Int, preset: Int, hevc: Boolean = false): Int {
        val bpp = bitsPerPixel[preset.coerceIn(0, bitsPerPixel.size - 1)] * if (hevc) 0.6 else 1.0
        return (width.toLong() * height * fps * bpp).toLong().coerceIn(1_500_000L, 100_000_000L).toInt()
    }
}
