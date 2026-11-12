package app.ipcamhub.core

import java.io.ByteArrayOutputStream

/**
 * Minimal MPEG transport stream writer for H.264 video and AAC sound (ADTS).
 * TVs and set-top boxes play HLS built from this far more reliably than HLS built from fragmented MP4.
 */
class MpegTsWriter(
    /** AAC sample rate, or 0 when the stream has no sound. */
    private val audioRate: Int,
    private val audioChannels: Int,
    /** SPS and PPS in Annex B form (with start codes); repeated before every key frame. */
    private val parameterSets: ByteArray,
    /** H.265 instead of H.264. */
    private val hevc: Boolean = false,
) {
    private val counters = IntArray(0x2000)

    /** PAT and PMT packets. Write them at the start of every segment. */
    fun tables(): ByteArray {
        val out = ByteArrayOutputStream()
        section(PID_PAT, patSection(), out)
        section(PID_PMT, pmtSection(), out)
        return out.toByteArray()
    }

    /** One video access unit in Annex B form. [ptsUs] is relative to the start of the stream. */
    fun video(annexB: ByteArray, ptsUs: Long, key: Boolean): ByteArray {
        val es = ByteArrayOutputStream()
        es.write(if (hevc) AUD_HEVC else AUD)
        if (key) es.write(parameterSets)
        es.write(annexB)
        val pts = ts90(ptsUs)
        val out = ByteArrayOutputStream()
        packetize(PID_VIDEO, pes(0xE0, pts, es.toByteArray(), unbounded = true), pts - PCR_LEAD, key, out)
        return out.toByteArray()
    }

    /** One raw AAC frame. */
    fun audio(frame: ByteArray, ptsUs: Long): ByteArray {
        if (audioRate <= 0) return ByteArray(0)
        val adts = ByteArray(7 + frame.size)
        val rateIndex = RATES.indexOf(audioRate).let { if (it < 0) 4 else it }
        val length = adts.size
        adts[0] = 0xFF.toByte()
        adts[1] = 0xF1.toByte() // MPEG-4, no CRC
        adts[2] = ((1 shl 6) or (rateIndex shl 2) or (audioChannels shr 2)).toByte() // AAC LC
        adts[3] = (((audioChannels and 3) shl 6) or (length shr 11)).toByte()
        adts[4] = ((length shr 3) and 0xFF).toByte()
        adts[5] = (((length and 7) shl 5) or 0x1F).toByte()
        adts[6] = 0xFC.toByte()
        System.arraycopy(frame, 0, adts, 7, frame.size)
        val out = ByteArrayOutputStream()
        packetize(PID_AUDIO, pes(0xC0, ts90(ptsUs), adts, unbounded = false), -1, false, out)
        return out.toByteArray()
    }

    private fun ts90(ptsUs: Long): Long = ptsUs * 9 / 100 + BASE

    private fun patSection(): ByteArray {
        val body = ByteArrayOutputStream()
        body.write(0x00) // table id
        sectionHeader(body, 13) // length counted after the length field: 5 header + 4 program + 4 crc
        body.write(0); body.write(1) // transport stream id
        body.write(0xC1); body.write(0); body.write(0) // version 0, current, section 0 of 0
        body.write(0); body.write(1) // program number 1
        body.write(0xE0 or (PID_PMT shr 8)); body.write(PID_PMT and 0xFF)
        return withCrc(body.toByteArray())
    }

    private fun pmtSection(): ByteArray {
        val streams = if (audioRate > 0) 2 else 1
        val body = ByteArrayOutputStream()
        body.write(0x02)
        sectionHeader(body, 9 + 5 * streams + 4)
        body.write(0); body.write(1) // program number
        body.write(0xC1); body.write(0); body.write(0)
        body.write(0xE0 or (PID_VIDEO shr 8)); body.write(PID_VIDEO and 0xFF) // PCR on the video PID
        body.write(0xF0); body.write(0) // no program descriptors
        stream(body, if (hevc) 0x24 else 0x1B, PID_VIDEO)
        if (audioRate > 0) stream(body, 0x0F, PID_AUDIO)
        return withCrc(body.toByteArray())
    }

    private fun stream(out: ByteArrayOutputStream, type: Int, pid: Int) {
        out.write(type)
        out.write(0xE0 or (pid shr 8)); out.write(pid and 0xFF)
        out.write(0xF0); out.write(0)
    }

    private fun sectionHeader(out: ByteArrayOutputStream, length: Int) {
        out.write(0xB0 or (length shr 8))
        out.write(length and 0xFF)
    }

    private fun withCrc(section: ByteArray): ByteArray {
        val crc = crc32(section)
        return section + byteArrayOf((crc shr 24).toByte(), (crc shr 16).toByte(), (crc shr 8).toByte(), crc.toByte())
    }

    private fun section(pid: Int, section: ByteArray, out: ByteArrayOutputStream) {
        val payload = ByteArray(184) { 0xFF.toByte() }
        payload[0] = 0 // pointer field
        System.arraycopy(section, 0, payload, 1, section.size)
        out.write(0x47)
        out.write(0x40 or (pid shr 8)); out.write(pid and 0xFF)
        out.write(0x10 or (counters[pid]++ and 0xF))
        out.write(payload)
    }

    private fun pes(streamId: Int, pts90: Long, payload: ByteArray, unbounded: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0); out.write(0); out.write(1); out.write(streamId)
        val length = if (unbounded) 0 else 3 + 5 + payload.size
        out.write(length shr 8); out.write(length and 0xFF)
        out.write(0x84) // marker, data alignment
        out.write(0x80) // PTS only
        out.write(5)
        out.write(0x20 or (((pts90 shr 30) and 7).toInt() shl 1) or 1)
        out.write(((pts90 shr 22) and 0xFF).toInt())
        out.write((((pts90 shr 15) and 0x7F).toInt() shl 1) or 1)
        out.write(((pts90 shr 7) and 0xFF).toInt())
        out.write((((pts90 and 0x7F).toInt()) shl 1) or 1)
        out.write(payload)
        return out.toByteArray()
    }

    /** Splits [data] into 188 byte packets. [pcr90] below zero means no clock reference. */
    private fun packetize(pid: Int, data: ByteArray, pcr90: Long, randomAccess: Boolean, out: ByteArrayOutputStream) {
        var pos = 0
        var first = true
        while (pos < data.size) {
            val withPcr = first && pcr90 >= 0
            var adaptation = if (withPcr) 8 else 0 // bytes including the length byte
            val payload = minOf(data.size - pos, 184 - adaptation)
            adaptation += 184 - adaptation - payload // stuffing
            out.write(0x47)
            out.write((if (first) 0x40 else 0) or (pid shr 8)); out.write(pid and 0xFF)
            out.write((if (adaptation > 0) 0x30 else 0x10) or (counters[pid]++ and 0xF))
            if (adaptation > 0) {
                out.write(adaptation - 1)
                var written = 1
                if (adaptation > 1) {
                    out.write((if (randomAccess && first) 0x40 else 0) or (if (withPcr) 0x10 else 0))
                    written++
                }
                if (withPcr) {
                    out.write(((pcr90 shr 25) and 0xFF).toInt())
                    out.write(((pcr90 shr 17) and 0xFF).toInt())
                    out.write(((pcr90 shr 9) and 0xFF).toInt())
                    out.write(((pcr90 shr 1) and 0xFF).toInt())
                    out.write((((pcr90 and 1).toInt()) shl 7) or 0x7E)
                    out.write(0)
                    written += 6
                }
                while (written < adaptation) { out.write(0xFF); written++ }
            }
            out.write(data, pos, payload)
            pos += payload
            first = false
        }
    }

    companion object {
        const val PID_PAT = 0
        const val PID_PMT = 0x1000
        const val PID_VIDEO = 0x100
        const val PID_AUDIO = 0x101

        /** Timestamps start here so the clock reference, which runs a little ahead, stays positive. */
        private const val BASE = 126_000L
        private const val PCR_LEAD = 4_500L // 50 ms: how far the clock runs ahead of the picture, so a TV buffers less
        private val AUD = byteArrayOf(0, 0, 0, 1, 0x09, 0xF0.toByte())
        private val AUD_HEVC = byteArrayOf(0, 0, 0, 1, 0x46, 0x01, 0x50)
        private val RATES = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        /** CRC-32/MPEG-2, as used by PAT and PMT sections. */
        fun crc32(data: ByteArray): Int {
            var crc = -1
            for (b in data) {
                crc = crc xor ((b.toInt() and 0xFF) shl 24)
                repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
            }
            return crc
        }

        /** Converts length-prefixed NAL units (MP4 style) to Annex B with start codes. */
        fun avccToAnnexB(avcc: ByteArray): ByteArray {
            val out = ByteArrayOutputStream(avcc.size + 16)
            var i = 0
            while (i + 4 <= avcc.size) {
                val n = ((avcc[i].toInt() and 0xFF) shl 24) or ((avcc[i + 1].toInt() and 0xFF) shl 16) or
                    ((avcc[i + 2].toInt() and 0xFF) shl 8) or (avcc[i + 3].toInt() and 0xFF)
                i += 4
                if (n <= 0 || i + n > avcc.size) break
                out.write(0); out.write(0); out.write(0); out.write(1)
                out.write(avcc, i, n)
                i += n
            }
            return out.toByteArray()
        }
    }
}
