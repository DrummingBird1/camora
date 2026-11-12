package app.ipcamhub.core

import java.io.ByteArrayOutputStream

/** H.265 / HEVC helpers: parameter sets, the MP4 hvcC record and the browser codec string. */
object Hevc {
    const val VPS = 32
    const val SPS = 33
    const val PPS = 34
    const val AUD = 35

    fun nalType(firstByte: Byte): Int = (firstByte.toInt() shr 1) and 0x3F

    /** Removes emulation prevention bytes (00 00 03 -> 00 00) so header fields can be read. */
    fun unescape(nal: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (b in nal) {
            if (zeros >= 2 && b == 3.toByte()) {
                zeros = 0
                continue
            }
            out.write(b.toInt())
            zeros = if (b == 0.toByte()) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** VPS, SPS and PPS from the encoder's csd-0 (Annex B), each without its start code. */
    class ParameterSets(val vps: ByteArray, val sps: ByteArray, val pps: ByteArray)

    fun parameterSets(csd: ByteArray): ParameterSets? {
        val nals = AnnexB.nals(csd).map { (s, e) -> csd.copyOfRange(s, e) }
        val vps = nals.firstOrNull { nalType(it[0]) == VPS } ?: return null
        val sps = nals.firstOrNull { nalType(it[0]) == SPS } ?: return null
        val pps = nals.firstOrNull { nalType(it[0]) == PPS } ?: return null
        return ParameterSets(vps, sps, pps)
    }

    /** The 12-byte general profile_tier_level from an SPS (after the 2-byte header and 1 byte of ids). */
    private fun profileTierLevel(sps: ByteArray): ByteArray {
        val rbsp = unescape(sps)
        require(rbsp.size >= 15) { "SPS too short" }
        return rbsp.copyOfRange(3, 15)
    }

    /** RFC 6381 codec string for MSE, for example "hvc1.1.6.L120.B0". */
    fun codecString(sps: ByteArray): String {
        val ptl = profileTierLevel(sps)
        val space = (ptl[0].toInt() shr 6) and 3
        val tier = (ptl[0].toInt() shr 5) and 1
        val profile = ptl[0].toInt() and 0x1F
        var compat = 0L
        for (i in 1..4) compat = (compat shl 8) or (ptl[i].toLong() and 0xFF)
        // The compatibility flags are written in reverse bit order.
        var reversed = 0L
        for (i in 0 until 32) if ((compat shr i) and 1L == 1L) reversed = reversed or (1L shl (31 - i))
        val level = ptl[11].toInt() and 0xFF
        val constraints = (5..10).map { ptl[it].toInt() and 0xFF }.dropLastWhile { it == 0 }
        val sb = StringBuilder("hvc1.")
        if (space > 0) sb.append(('A' + space - 1))
        sb.append(profile).append('.').append(java.lang.Long.toHexString(reversed).uppercase())
        sb.append('.').append(if (tier == 1) 'H' else 'L').append(level)
        constraints.forEach { sb.append('.').append(Integer.toHexString(it).uppercase()) }
        return sb.toString()
    }

    /** HEVCDecoderConfigurationRecord (the payload of an MP4 hvcC box). 8-bit 4:2:0, 4-byte lengths. */
    fun decoderConfigurationRecord(ps: ParameterSets): ByteArray {
        val ptl = profileTierLevel(ps.sps)
        val out = ByteArrayOutputStream()
        out.write(1) // configurationVersion
        out.write(ptl, 0, 12) // profile space/tier/idc, compatibility flags, constraint flags, level
        out.write(0xF0); out.write(0x00) // min_spatial_segmentation_idc = 0
        out.write(0xFC) // parallelismType = 0
        out.write(0xFD) // chroma_format_idc = 1 (4:2:0)
        out.write(0xF8) // bit depth luma - 8
        out.write(0xF8) // bit depth chroma - 8
        out.write(0); out.write(0) // avgFrameRate unknown
        out.write(0x0F) // constantFrameRate 0, 1 temporal layer, nested, lengthSizeMinusOne = 3
        val arrays = listOf(VPS to ps.vps, SPS to ps.sps, PPS to ps.pps)
        out.write(arrays.size)
        for ((type, nal) in arrays) {
            out.write(0x80 or type) // array_completeness = 1
            out.write(0); out.write(1) // one NAL unit
            out.write(nal.size shr 8); out.write(nal.size and 0xFF)
            out.write(nal)
        }
        return out.toByteArray()
    }

    /** Annex B parameter sets with start codes, to repeat before key frames in a transport stream. */
    fun annexB(ps: ParameterSets): ByteArray {
        val sc = byteArrayOf(0, 0, 0, 1)
        return sc + ps.vps + sc + ps.sps + sc + ps.pps
    }
}
