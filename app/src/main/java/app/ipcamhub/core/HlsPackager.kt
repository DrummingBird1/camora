package app.ipcamhub.core

import java.util.Locale

/**
 * Cuts the live samples into HLS segments (MPEG-TS, a key frame at the start of each).
 * All viewers share one timeline, so any HLS player (a TV's built-in player, VLC, Cast) can join.
 */
class HlsPackager(
    /** AAC sample rate, or 0 when the stream has no sound. */
    private val audioRate: Int,
    private val targetSegmentUs: Long = 900_000L,
    private val keep: Int = 8,
    audioChannels: Int = 2,
    /** SPS and PPS in Annex B form. */
    parameterSets: ByteArray = ByteArray(0),
    hevc: Boolean = false,
) : SegmentPackager {

    class Segment(val index: Int, val durationSec: Double, val data: ByteArray)

    private val writer = MpegTsWriter(audioRate, audioChannels, parameterSets, hevc)
    private val segments = ArrayDeque<Segment>()
    private var nextIndex = 0

    // Segment being built.
    private var current: java.io.ByteArrayOutputStream? = null
    private var currentStartUs = 0L

    private var baseUs = -1L
    private var lastVideoUs = Long.MIN_VALUE

    @Volatile
    var lastAccessMs: Long = System.currentTimeMillis()

    @Synchronized
    override fun video(avcc: ByteArray, ptsUs: Long, key: Boolean) {
        if (baseUs < 0) {
            if (!key) return
            baseUs = ptsUs
        }
        if (key && current != null && ptsUs - currentStartUs >= targetSegmentUs) finishSegment(ptsUs)
        if (current == null) {
            if (!key) return
            current = java.io.ByteArrayOutputStream().also { it.write(writer.tables()) }
            currentStartUs = ptsUs
        }
        // Timestamps must increase.
        val us = if (ptsUs <= lastVideoUs) lastVideoUs + 1_000 else ptsUs
        lastVideoUs = us
        current!!.write(writer.video(MpegTsWriter.avccToAnnexB(avcc), us - baseUs, key))
    }

    @Synchronized
    override fun audio(frame: ByteArray, ptsUs: Long) {
        val out = current ?: return
        if (audioRate <= 0 || ptsUs < baseUs) return
        out.write(writer.audio(frame, ptsUs - baseUs))
    }

    private fun finishSegment(nextStartUs: Long) {
        val out = current ?: return
        val durationSec = (nextStartUs - currentStartUs) / 1_000_000.0
        segments.addLast(Segment(nextIndex++, durationSec, out.toByteArray()))
        while (segments.size > keep) segments.removeFirst()
        current = null
    }

    /** Number of finished segments available. */
    @Synchronized
    fun segmentCount(): Int = segments.size

    @Synchronized
    fun segment(index: Int): Segment? = segments.firstOrNull { it.index == index }

    /**
     * The live playlist. [query] (for example "?k=secret") is appended to every address so the
     * player keeps sending the access key.
     */
    @Synchronized
    fun playlist(query: String): String {
        val list = segments.toList()
        val target = Math.ceil(list.maxOfOrNull { it.durationSec } ?: 1.0).toInt().coerceAtLeast(1)
        val sb = StringBuilder()
        sb.append("#EXTM3U\n#EXT-X-VERSION:3\n")
        sb.append("#EXT-X-TARGETDURATION:").append(target).append('\n')
        sb.append("#EXT-X-MEDIA-SEQUENCE:").append(list.firstOrNull()?.index ?: 0).append('\n')
        for (s in list) {
            sb.append("#EXTINF:").append(String.format(Locale.US, "%.3f", s.durationSec)).append(",\n")
            sb.append("seg").append(s.index).append(".ts").append(query).append('\n')
        }
        return sb.toString()
    }
}
