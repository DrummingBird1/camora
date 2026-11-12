package app.ipcamhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MpegTsTest {
    private val params = byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3)
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 9, 9, 9)

    private fun pid(data: ByteArray, packet: Int): Int =
        ((data[packet * 188 + 1].toInt() and 0x1F) shl 8) or (data[packet * 188 + 2].toInt() and 0xFF)

    @Test
    fun crcOfASectionIncludingItsCrcIsZero() {
        val tables = MpegTsWriter(48_000, 2, params).tables()
        assertEquals(376, tables.size)
        for (packet in 0..1) {
            val start = packet * 188 + 5 // after header and pointer field
            val length = (((tables[start + 1].toInt() and 0x0F) shl 8) or (tables[start + 2].toInt() and 0xFF)) + 3
            assertEquals(0, MpegTsWriter.crc32(tables.copyOfRange(start, start + length)))
        }
    }

    @Test
    fun videoPacketsAreWholeAndCarryAClockOnTheFirstOne() {
        val w = MpegTsWriter(0, 2, params)
        val data = w.video(idr + ByteArray(1000) { 7 }, 0, true)
        assertEquals(0, data.size % 188)
        for (i in data.indices step 188) assertEquals(0x47.toByte(), data[i])
        assertEquals(MpegTsWriter.PID_VIDEO, pid(data, 0))
        assertTrue("start indicator", (data[1].toInt() and 0x40) != 0)
        assertTrue("adaptation field with clock", (data[3].toInt() and 0x20) != 0 && (data[5].toInt() and 0x10) != 0)
    }

    @Test
    fun continuityCounterIncreasesPerPid() {
        val w = MpegTsWriter(0, 2, params)
        val data = w.video(ByteArray(2000) { 1 }, 0, false)
        val counters = (0 until data.size / 188).map { data[it * 188 + 3].toInt() and 0xF }
        assertEquals((0 until counters.size).map { it % 16 }, counters)
    }

    @Test
    fun audioFramesGetAnAdtsHeader() {
        val w = MpegTsWriter(48_000, 2, params)
        val data = w.audio(ByteArray(100) { 5 }, 0)
        assertEquals(0, data.size % 188)
        val adts = (0 until data.size - 1).first { data[it] == 0xFF.toByte() && data[it + 1] == 0xF1.toByte() }
        assertTrue(adts > 0)
        assertEquals(0xF1.toByte(), data[adts + 1])
    }

    @Test
    fun avccIsConvertedToStartCodes() {
        val out = MpegTsWriter.avccToAnnexB(byteArrayOf(0, 0, 0, 3, 0x65, 1, 2, 0, 0, 0, 2, 0x41, 4))
        assertEquals(listOf<Byte>(0, 0, 0, 1, 0x65, 1, 2, 0, 0, 0, 1, 0x41, 4), out.toList())
    }
}
