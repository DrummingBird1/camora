package app.ipcamhub.core

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioFmp4Test {
    private val sps = byteArrayOf(0x67, 0x64, 0x00, 0x28, 0xAC.toByte(), 0xD9.toByte())
    private val pps = byteArrayOf(0x68, 0xEE.toByte(), 0x3C, 0x80.toByte())
    private val asc = byteArrayOf(0x15, 0x88.toByte()) // AAC-LC, 8 kHz, mono

    private fun children(data: ByteArray, from: Int, to: Int): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        var p = from
        while (p < to) {
            val size = ByteBuffer.wrap(data, p, 4).int
            assertTrue("bad box size $size at $p", size >= 8 && p + size <= to)
            out.add(Triple(String(data, p + 4, 4, Charsets.US_ASCII), p, size))
            p += size
        }
        assertEquals(to, p)
        return out
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..data.size - needle.size) {
            for (j in needle.indices) if (data[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    @Test
    fun initSegmentWithAudioHasTwoTracksAndTwoTrex() {
        val init = Fmp4.initSegment(sps, pps, 1280, 720, Fmp4.AudioConfig(8000, 1, asc))
        val top = children(init, 0, init.size)
        assertEquals(listOf("ftyp", "moov"), top.map { it.first })
        val moov = top[1]
        val inner = children(init, moov.second + 8, moov.second + moov.third)
        assertEquals(listOf("mvhd", "trak", "trak", "mvex"), inner.map { it.first })
        val mvex = inner[3]
        assertEquals(listOf("trex", "trex"), children(init, mvex.second + 8, mvex.second + mvex.third).map { it.first })
    }

    @Test
    fun esdsCarriesTheAudioSpecificConfig() {
        val init = Fmp4.initSegment(sps, pps, 1280, 720, Fmp4.AudioConfig(8000, 1, asc))
        val esds = indexOf(init, "esds".toByteArray(Charsets.US_ASCII))
        assertTrue("esds box missing", esds > 0)
        // DecoderSpecificInfo: tag 5, length 2, then the config bytes.
        assertTrue(indexOf(init, byteArrayOf(0x05, 0x02, 0x15, 0x88.toByte())) > esds)
        // objectTypeIndication 0x40 (AAC) in the decoder config descriptor
        assertTrue(indexOf(init, byteArrayOf(0x04, 0x11, 0x40, 0x15)) > esds)
    }

    @Test
    fun videoOnlyInitSegmentStillHasOneTrack() {
        val init = Fmp4.initSegment(sps, pps, 1280, 720)
        val moov = children(init, 0, init.size)[1]
        assertEquals(
            listOf("mvhd", "trak", "mvex"),
            children(init, moov.second + 8, moov.second + moov.third).map { it.first }
        )
    }

    @Test
    fun audioFragmentUsesTrackTwo() {
        val frame = byteArrayOf(1, 2, 3, 4)
        val frag = Fmp4.fragment(3, 8000, 1024, true, frame, trackId = 2)
        assertEquals(listOf("moof", "mdat"), children(frag, 0, frag.size).map { it.first })
        // moof header (8) + mfhd (16) + traf header (8) + tfhd header/version (12) = track id
        assertEquals(2, ByteBuffer.wrap(frag, 44, 4).int)
    }

    @Test
    fun codecStringsForMse() {
        assertEquals("mp4a.40.2", Fmp4.AUDIO_CODEC)
        assertEquals("avc1.640028", Fmp4.codecString(sps))
    }
}
