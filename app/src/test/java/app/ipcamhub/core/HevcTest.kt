package app.ipcamhub.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

class HevcTest {
    // Parameter sets of a 1280x720 Main profile, level 3.1 clip encoded with ffmpeg/x265, and the hvcC
    // record ffmpeg's MP4 muxer wrote for it. Our record must match it byte for byte.
    private val vps = hex("40010C01FFFF01600000030090000003000003005D959809")
    private val sps = hex("42010101600000030090000003000003005DA00280802D165959A4932BC05A020000030002000003003C10")
    private val pps = hex("4401C172B46240")
    private val ffmpegHvcC = hex(
        "0101600000009000000000005DF000FCFDF8F800000F03" +
            "A00001001840010C01FFFF01600000030090000003000003005D959809" +
            "A10001002B42010101600000030090000003000003005DA00280802D165959A4932BC05A020000030002000003003C10" +
            "A2000100074401C172B46240"
    )
    private val sc = byteArrayOf(0, 0, 0, 1)
    private val csd = sc + vps + sc + sps + sc + pps

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun findsTheParameterSetsInCsd() {
        val ps = Hevc.parameterSets(csd)!!
        assertArrayEquals(vps, ps.vps)
        assertArrayEquals(sps, ps.sps)
        assertArrayEquals(pps, ps.pps)
    }

    @Test
    fun decoderConfigurationRecordMatchesFfmpeg() {
        assertArrayEquals(ffmpegHvcC, Hevc.decoderConfigurationRecord(Hevc.parameterSets(csd)!!))
    }

    @Test
    fun codecStringMatchesTheProfileAndLevel() {
        // Main profile, compatibility flags 0x60000000 (reversed: 0x6), main tier, level 93 (3.1), constraint 0x90.
        assertEquals("hvc1.1.6.L93.90", Hevc.codecString(sps))
    }

    @Test
    fun removesEmulationPreventionBytes() {
        assertArrayEquals(hex("0000010000"), Hevc.unescape(hex("000003010000")))
    }

    @Test
    fun initSegmentUsesHvc1AndIsWellFormed() {
        val init = Fmp4.initSegmentHevc(Hevc.parameterSets(csd)!!, 1280, 720)
        val text = String(init, Charsets.ISO_8859_1)
        assertTrue(text.contains("hvc1") && text.contains("hvcC") && !text.contains("avcC"))
        val ftyp = ByteBuffer.wrap(init, 0, 4).int
        assertEquals(init.size, ftyp + ByteBuffer.wrap(init, ftyp, 4).int) // ftyp + moov, nothing else
    }

    @Test
    fun accessUnitsDropHevcParameterSetsAndDelimiters() {
        val au = byteArrayOf(0, 0, 0, 1, 0x46, 0x01, 0x50) + csd + byteArrayOf(0, 0, 0, 1, 0x26, 0x01, 0x55)
        assertArrayEquals(byteArrayOf(0, 0, 0, 3, 0x26, 0x01, 0x55), AnnexB.toAvcc(au, hevc = true))
    }

    @Test
    fun transportStreamAnnouncesHevc() {
        val tables = MpegTsWriter(0, 2, Hevc.annexB(Hevc.parameterSets(csd)!!), hevc = true).tables()
        // PMT packet: 4 header bytes, pointer field, then 12 section bytes before the first stream type.
        assertEquals(0x24, tables[188 + 5 + 12].toInt() and 0xFF)
    }
}
