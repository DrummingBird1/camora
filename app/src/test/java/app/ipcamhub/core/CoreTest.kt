package app.ipcamhub.core

import java.nio.ByteBuffer
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {

    // ---- Annex B ----

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun nalsSplitsOnThreeAndFourByteStartCodes() {
        val data = bytes(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 1, 0x68, 3, 0, 0, 1, 0x65, 9, 9, 9)
        val nals = AnnexB.nals(data)
        assertEquals(3, nals.size)
        assertEquals(0x67, data[nals[0].first].toInt())
        assertEquals(3, nals[0].second - nals[0].first)
        assertEquals(0x65, data[nals[2].first].toInt())
        assertEquals(4, nals[2].second - nals[2].first)
    }

    @Test
    fun toAvccDropsParameterSetsAndPrefixesLengths() {
        // SPS(7), PPS(8), IDR slice(5)
        val data = bytes(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3, 0, 0, 1, 0x65, 9, 9)
        val avcc = AnnexB.toAvcc(data)
        assertArrayEquals(bytes(0, 0, 0, 3, 0x65, 9, 9), avcc)
    }

    @Test
    fun firstNalStripsStartCode() {
        assertArrayEquals(bytes(0x67, 1, 2), AnnexB.firstNal(bytes(0, 0, 0, 1, 0x67, 1, 2)))
    }

    // ---- fragmented MP4 ----

    private data class Box(val type: String, val start: Int, val size: Int)

    private fun topLevelBoxes(data: ByteArray, from: Int = 0, to: Int = data.size): List<Box> {
        val out = ArrayList<Box>()
        var p = from
        while (p < to) {
            val size = ByteBuffer.wrap(data, p, 4).int
            val type = String(data, p + 4, 4, Charsets.US_ASCII)
            assertTrue("box $type has size $size", size >= 8 && p + size <= to)
            out.add(Box(type, p, size))
            p += size
        }
        assertEquals(to, p)
        return out
    }

    private val sps = bytes(0x67, 0x64, 0x00, 0x28, 0xAC, 0xD9)
    private val pps = bytes(0x68, 0xEE, 0x3C, 0x80)

    @Test
    fun initSegmentIsWellFormed() {
        val init = Fmp4.initSegment(sps, pps, 1920, 1080)
        val boxes = topLevelBoxes(init)
        assertEquals(listOf("ftyp", "moov"), boxes.map { it.type })
        val moov = boxes[1]
        val inner = topLevelBoxes(init, moov.start + 8, moov.start + moov.size)
        assertEquals(listOf("mvhd", "trak", "mvex"), inner.map { it.type })
        assertEquals(108, inner[0].size)
    }

    @Test
    fun codecStringUsesProfileCompatibilityAndLevel() {
        assertEquals("avc1.640028", Fmp4.codecString(sps))
    }

    @Test
    fun fragmentHasMoofThenMdatAndCorrectDataOffset() {
        val sample = bytes(0, 0, 0, 3, 0x65, 9, 9)
        val frag = Fmp4.fragment(7, 90_000, 3000, true, sample)
        val boxes = topLevelBoxes(frag)
        assertEquals(listOf("moof", "mdat"), boxes.map { it.type })
        val moof = boxes[0]
        // trun is the last box inside traf, which is the last box inside moof.
        val trunStart = moof.start + moof.size - 32
        assertEquals("trun", String(frag, trunStart + 4, 4, Charsets.US_ASCII))
        val dataOffset = ByteBuffer.wrap(frag, trunStart + 16, 4).int
        assertEquals(moof.size + 8, dataOffset)
        assertEquals(sample.size, ByteBuffer.wrap(frag, trunStart + 24, 4).int)
        assertArrayEquals(sample, frag.copyOfRange(boxes[1].start + 8, boxes[1].start + boxes[1].size))
    }

    // ---- motion ----

    @Test
    fun identicalFramesHaveNoChange() {
        val f = IntArray(100) { 0xFF336699.toInt() }
        assertEquals(0.0, MotionMath.changedFraction(MotionMath.toLuma(f), MotionMath.toLuma(f)), 0.0)
    }

    @Test
    fun halfTheFrameChangingIsHalf() {
        val a = IntArray(100) { 0xFF000000.toInt() }
        val b = IntArray(100) { if (it < 50) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() }
        assertEquals(0.5, MotionMath.changedFraction(MotionMath.toLuma(a), MotionMath.toLuma(b)), 1e-9)
    }

    @Test
    fun smallBrightnessNoiseIsIgnored() {
        val a = IntArray(100) { 0xFF808080.toInt() }
        val b = IntArray(100) { 0xFF868686.toInt() }
        assertEquals(0.0, MotionMath.changedFraction(MotionMath.toLuma(a), MotionMath.toLuma(b)), 0.0)
    }

    // ---- ONVIF ----

    @Test
    fun passwordDigestMatchesTheOnvifSpecExample() {
        val nonce = Base64.getDecoder().decode("LKqI6G/AikKCQrN0zqZFlg==")
        val digest = OnvifText.passwordDigest(nonce, "2010-09-16T07:50:45Z", "userpassword")
        assertEquals("tuOSpGlFlIXsozq4HFNeeGeFLEI=", digest)
    }

    @Test
    fun parsesProbeMatch() {
        val xml = """<d:ProbeMatches><d:ProbeMatch><d:Scopes>onvif://www.onvif.org/name/Front%20Door onvif://www.onvif.org/hardware/X1</d:Scopes>
            <d:XAddrs>http://192.168.1.50:80/onvif/device_service http://[fe80::1]/onvif/device_service</d:XAddrs></d:ProbeMatch></d:ProbeMatches>"""
        val d = OnvifText.parseProbeMatch(xml)!!
        assertEquals("192.168.1.50", d.host)
        assertEquals("Front Door", d.name)
        assertEquals("http://192.168.1.50:80/onvif/device_service", d.serviceUrl)
        assertNull(OnvifText.parseProbeMatch("<nothing/>"))
    }

    @Test
    fun parsesMediaAddressProfilesAndStreamUri() {
        val caps = """<tds:Capabilities><tt:Device><tt:XAddr>http://h/onvif/device</tt:XAddr></tt:Device><tt:Media><tt:XAddr>http://h/onvif/media</tt:XAddr></tt:Media></tds:Capabilities>"""
        assertEquals("http://h/onvif/media", OnvifText.parseMediaXAddr(caps))

        val profiles = """
            <trt:Profiles token="sub" fixed="true"><tt:VideoEncoderConfiguration><tt:Resolution><tt:Width>640</tt:Width><tt:Height>360</tt:Height></tt:Resolution></tt:VideoEncoderConfiguration></trt:Profiles>
            <trt:Profiles token="main" fixed="true"><tt:VideoEncoderConfiguration><tt:Resolution><tt:Width>3840</tt:Width><tt:Height>2160</tt:Height></tt:Resolution></tt:VideoEncoderConfiguration></trt:Profiles>"""
        assertEquals("main", OnvifText.pickProfileToken(profiles))

        assertEquals(
            "rtsp://h:554/stream1?a=1&b=2",
            OnvifText.parseStreamUri("<trt:MediaUri><tt:Uri>rtsp://h:554/stream1?a=1&amp;b=2</tt:Uri></trt:MediaUri>")
        )
    }

    @Test
    fun addsEncodedCredentials() {
        assertEquals("rtsp://admin:p%40ss@192.0.2.2:554/s", OnvifText.withCredentials("rtsp://192.0.2.2:554/s", "admin", "p@ss"))
        assertEquals("rtsp://192.0.2.2/s", OnvifText.withCredentials("rtsp://192.0.2.2/s", "", ""))
    }
}
