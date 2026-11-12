package app.ipcamhub.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** Matroska's EBML encoding: sizes, IDs and numbers must be exact or players reject the file. */
class EbmlTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun sizesUseTheShortestForm() {
        assertArrayEquals(bytes(0x80), Ebml.size(0))
        assertArrayEquals(bytes(0x81), Ebml.size(1))
        assertArrayEquals(bytes(0xFE), Ebml.size(126))
        // 127 in one byte would be the "unknown size" marker, so it takes two.
        assertArrayEquals(bytes(0x40, 0x7F), Ebml.size(127))
        assertArrayEquals(bytes(0x7F, 0xFE), Ebml.size(16382))
        assertArrayEquals(bytes(0x20, 0x3F, 0xFF), Ebml.size(16383))
    }

    @Test
    fun eightByteSizeForPlaceholders() {
        assertArrayEquals(bytes(0x01, 0, 0, 0, 0, 0, 0x12, 0x34), Ebml.size8(0x1234))
        assertEquals(8, Ebml.UNKNOWN_SIZE.size)
    }

    @Test
    fun idsKeepTheirLength() {
        assertArrayEquals(bytes(0xA3), Ebml.id(0xA3))
        assertArrayEquals(bytes(0x42, 0x86), Ebml.id(0x4286))
        assertArrayEquals(bytes(0x2A, 0xD7, 0xB1), Ebml.id(0x2AD7B1))
        assertArrayEquals(bytes(0x1A, 0x45, 0xDF, 0xA3), Ebml.id(Ebml.EBML))
    }

    @Test
    fun unsignedIntegersAreMinimal() {
        // ID 0xD7 (TrackNumber), size 1, value 1.
        assertArrayEquals(bytes(0xD7, 0x81, 0x01), Ebml.uint(0xD7, 1))
        // TimestampScale 1 000 000 = 0x0F4240, three bytes.
        assertArrayEquals(bytes(0x2A, 0xD7, 0xB1, 0x83, 0x0F, 0x42, 0x40), Ebml.uint(0x2AD7B1, 1_000_000))
        assertArrayEquals(bytes(0xE7, 0x81, 0x00), Ebml.uint(0xE7, 0L))
    }

    @Test
    fun elementsWrapTheirChildren() {
        val e = Ebml.element(0xE0, Ebml.uint(0xB0, 1280L), Ebml.uint(0xBA, 720L))
        // E0, size 8, then B0 82 05 00 and BA 82 02 D0.
        assertArrayEquals(bytes(0xE0, 0x88, 0xB0, 0x82, 0x05, 0x00, 0xBA, 0x82, 0x02, 0xD0), e)
    }

    @Test
    fun floatsAreEightBytes() {
        val f = Ebml.float(0x4489, 1000.0)
        assertEquals(0x44.toByte(), f[0])
        assertEquals(0x88.toByte(), f[2]) // size 8
        assertEquals(11, f.size)
    }

    @Test
    fun avcConfigRecord() {
        val sps = bytes(0x67, 0x64, 0x00, 0x28, 0xAC)
        val pps = bytes(0x68, 0xEE, 0x3C, 0x80)
        val c = VideoTrackInfo.avcConfig(sps, pps)
        assertArrayEquals(
            bytes(1, 0x64, 0x00, 0x28, 0xFF, 0xE1, 0, 5, 0x67, 0x64, 0x00, 0x28, 0xAC, 1, 0, 4, 0x68, 0xEE, 0x3C, 0x80),
            c
        )
    }
}
