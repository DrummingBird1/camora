package app.ipcamhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsPackagerTest {
    private val frame = byteArrayOf(0, 0, 0, 3, 0x41, 1, 2)
    private val key = byteArrayOf(0, 0, 0, 3, 0x65, 1, 2)

    /** Feeds [seconds] of 30 fps video with a key frame every [gop] frames. */
    private fun feed(p: HlsPackager, seconds: Int, gop: Int = 30, withAudio: Boolean = false) {
        var audioPts = 0L
        for (i in 0 until seconds * 30) {
            val pts = 1_000_000_000L + i * 33_333L
            p.video(if (i % gop == 0) key else frame, pts, i % gop == 0)
            if (withAudio) while (audioPts <= pts - 1_000_000_000L) {
                p.audio(byteArrayOf(9, 9, 9), 1_000_000_000L + audioPts)
                audioPts += 128_000L
            }
        }
    }

    @Test
    fun cutsOneSegmentPerKeyFrameInterval() {
        val p = HlsPackager(0)
        feed(p, seconds = 5)
        // 5 s of video = 5 key frames; the last segment is still being built.
        assertEquals(4, p.segmentCount())
        val s = p.segment(0)!!
        assertEquals(1.0, s.durationSec, 0.05)
    }

    @Test
    fun segmentsAreWholeTransportPacketsStartingWithTables() {
        val p = HlsPackager(0)
        feed(p, seconds = 3)
        val data = p.segment(0)!!.data
        assertEquals(0, data.size % 188)
        for (i in data.indices step 188) assertEquals(0x47.toByte(), data[i])
        // first packet is the PAT (PID 0), second the PMT
        assertEquals(0, ((data[1].toInt() and 0x1F) shl 8) or (data[2].toInt() and 0xFF))
        assertEquals(0x1000, ((data[188 + 1].toInt() and 0x1F) shl 8) or (data[188 + 2].toInt() and 0xFF))
    }

    @Test
    fun playlistListsSegmentsInOrderWithKeyQuery() {
        val p = HlsPackager(0)
        feed(p, seconds = 4)
        val text = p.playlist("?k=abc")
        assertTrue(text.startsWith("#EXTM3U"))
                assertTrue(text.contains("#EXT-X-MEDIA-SEQUENCE:0"))
        assertTrue(text.contains("seg0.ts?k=abc"))
        assertTrue(text.contains("seg2.ts?k=abc"))
        assertTrue(text.contains("#EXT-X-TARGETDURATION:1"))
        assertTrue("live playlists must not end", !text.contains("#EXT-X-ENDLIST"))
    }

    @Test
    fun keepsOnlyTheNewestSegmentsAndAdvancesSequence() {
        val p = HlsPackager(0, keep = 3)
        feed(p, seconds = 8)
        assertEquals(3, p.segmentCount())
        assertNull(p.segment(0))
        assertNotNull(p.segment(6))
        assertTrue(p.playlist("").contains("#EXT-X-MEDIA-SEQUENCE:4"))
    }

    @Test
    fun ignoresVideoUntilTheFirstKeyFrame() {
        val p = HlsPackager(0)
        p.video(frame, 5_000_000, false)
        p.video(frame, 5_033_333, false)
        assertEquals(0, p.segmentCount())
        feed(p, 3)
        assertEquals(2, p.segmentCount())
    }

    @Test
    fun audioIsInterleavedIntoTheSegments() {
        val withAudio = HlsPackager(8000)
        val without = HlsPackager(0)
        feed(withAudio, 3, withAudio = true)
        feed(without, 3, withAudio = true)
        assertTrue(withAudio.segment(0)!!.data.size > without.segment(0)!!.data.size)
    }
}
