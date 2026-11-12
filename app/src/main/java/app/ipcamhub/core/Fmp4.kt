package app.ipcamhub.core

import java.nio.ByteBuffer

/** Helpers for H.264 and H.265 streams in Annex B form (start codes), as produced by MediaCodec. */
object AnnexB {
    /** Returns [start, end) of each NAL unit, excluding start codes and trailing zero bytes. */
    fun nals(data: ByteArray, off: Int = 0, len: Int = data.size - off): List<Pair<Int, Int>> {
        val end = off + len
        val starts = ArrayList<Int>()
        val codeBegins = ArrayList<Int>()
        var i = off
        while (i + 2 < end) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                codeBegins.add(i)
                starts.add(i + 3)
                i += 3
            } else {
                i++
            }
        }
        val out = ArrayList<Pair<Int, Int>>()
        for (k in starts.indices) {
            var e = if (k + 1 < starts.size) codeBegins[k + 1] else end
            while (e > starts[k] && data[e - 1] == 0.toByte()) e--
            if (e > starts[k]) out.add(starts[k] to e)
        }
        return out
    }

    /** First NAL unit of [csd] without its start code (used for SPS / PPS from the codec format). */
    fun firstNal(csd: ByteArray): ByteArray {
        val n = nals(csd).firstOrNull() ?: return csd
        return csd.copyOfRange(n.first, n.second)
    }

    /**
     * Converts an Annex B access unit to length-prefixed (AVCC) form for MP4.
     * Parameter sets and access unit delimiters are dropped because they live in the init segment.
     */
    fun toAvcc(data: ByteArray, off: Int = 0, len: Int = data.size - off, hevc: Boolean = false): ByteArray {
        val list = nals(data, off, len).filter { (s, _) ->
            if (hevc) {
                Hevc.nalType(data[s]) !in Hevc.VPS..Hevc.AUD
            } else {
                val type = data[s].toInt() and 0x1F
                type != 7 && type != 8 && type != 9
            }
        }
        val out = ByteBuffer.allocate(list.sumOf { 4 + (it.second - it.first) })
        for ((s, e) in list) {
            out.putInt(e - s)
            out.put(data, s, e - s)
        }
        return out.array()
    }
}

/** Builds fragmented MP4 (ISO BMFF) pieces for one H.264 or H.265 video track, suitable for MSE playback. */
object Fmp4 {
    const val TIMESCALE = 90_000

    fun codecString(sps: ByteArray): String =
        "avc1.%02X%02X%02X".format(sps[1].toInt() and 0xFF, sps[2].toInt() and 0xFF, sps[3].toInt() and 0xFF)

    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    private fun ints(vararg v: Int): ByteArray {
        val b = ByteBuffer.allocate(4 * v.size)
        v.forEach { b.putInt(it) }
        return b.array()
    }

    private fun short(v: Int): ByteArray = ByteBuffer.allocate(2).putShort(v.toShort()).array()

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val size = 8 + parts.sumOf { it.size }
        val out = ByteBuffer.allocate(size)
        out.putInt(size)
        out.put(ascii(type))
        parts.forEach { out.put(it) }
        return out.array()
    }

    private fun fullBox(type: String, version: Int, flags: Int, vararg parts: ByteArray): ByteArray =
        box(type, ints((version shl 24) or flags), *parts)

    private val matrix = ints(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)

    /** AAC audio parameters. [asc] is the AudioSpecificConfig from the encoder's csd-0. */
    class AudioConfig(val sampleRate: Int, val channels: Int, val asc: ByteArray)

    /** "mp4a.40.2" for AAC-LC. */
    const val AUDIO_CODEC = "mp4a.40.2"

    private fun descriptor(tag: Int, vararg parts: ByteArray): ByteArray {
        val size = parts.sumOf { it.size }
        require(size < 128) { "descriptor too large" }
        return byteArrayOf(tag.toByte(), size.toByte()) + parts.fold(ByteArray(0)) { acc, p -> acc + p }
    }

    private fun esds(asc: ByteArray): ByteArray {
        val decoderSpecific = descriptor(0x05, asc)
        val decoderConfig = descriptor(
            0x04,
            byteArrayOf(0x40, 0x15, 0, 0, 0), // AAC, audio stream, buffer size
            ints(0, 0), // max and average bitrate (unknown)
            decoderSpecific
        )
        val slConfig = descriptor(0x06, byteArrayOf(0x02))
        val es = descriptor(0x03, short(2), byteArrayOf(0), decoderConfig, slConfig)
        return fullBox("esds", 0, 0, es)
    }

    private fun audioTrak(audio: AudioConfig): ByteArray {
        val tkhd = fullBox(
            "tkhd", 0, 7,
            ints(0, 0, 2, 0, 0), ByteArray(8), short(0), short(0), short(0x0100), short(0),
            matrix, ints(0, 0)
        )
        val mdhd = fullBox("mdhd", 0, 0, ints(0, 0, audio.sampleRate, 0), short(0x55C4), short(0))
        val hdlr = fullBox("hdlr", 0, 0, ints(0), ascii("soun"), ByteArray(12), ascii("SoundHandler") + 0.toByte())
        val smhd = fullBox("smhd", 0, 0, ByteArray(4))
        val dinf = box("dinf", fullBox("dref", 0, 0, ints(1), fullBox("url ", 0, 1)))
        val mp4a = box(
            "mp4a",
            ByteArray(6), short(1), ByteArray(8), short(audio.channels), short(16), short(0), short(0),
            ints(audio.sampleRate shl 16), esds(audio.asc)
        )
        val stbl = box(
            "stbl",
            fullBox("stsd", 0, 0, ints(1), mp4a),
            fullBox("stts", 0, 0, ints(0)),
            fullBox("stsc", 0, 0, ints(0)),
            fullBox("stsz", 0, 0, ints(0, 0)),
            fullBox("stco", 0, 0, ints(0))
        )
        return box("trak", tkhd, box("mdia", mdhd, hdlr, box("minf", smhd, dinf, stbl)))
    }

    /**
     * ftyp + moov for one video track (id 1) and optionally one AAC audio track (id 2).
     * [sps] and [pps] are raw NAL units without start codes.
     */
    fun initSegment(sps: ByteArray, pps: ByteArray, width: Int, height: Int, audio: AudioConfig? = null): ByteArray {
        val avcC = box(
            "avcC",
            byteArrayOf(1, sps[1], sps[2], sps[3], 0xFF.toByte(), 0xE1.toByte()),
            short(sps.size), sps, byteArrayOf(1), short(pps.size), pps
        )
        return init(sampleEntry("avc1", width, height, avcC), "avc1", width, height, audio)
    }

    /** The same for H.265: an hvc1 sample entry with an hvcC record. */
    fun initSegmentHevc(ps: Hevc.ParameterSets, width: Int, height: Int, audio: AudioConfig? = null): ByteArray {
        val hvcC = box("hvcC", Hevc.decoderConfigurationRecord(ps))
        return init(sampleEntry("hvc1", width, height, hvcC), "hvc1", width, height, audio)
    }

    private fun sampleEntry(type: String, width: Int, height: Int, config: ByteArray): ByteArray = box(
        type,
        ByteArray(6), short(1), ByteArray(16), short(width), short(height),
        ints(0x00480000, 0x00480000, 0), short(1), ByteArray(32), short(0x0018), short(0xFFFF), config
    )

    private fun init(sampleEntry: ByteArray, brand: String, width: Int, height: Int, audio: AudioConfig?): ByteArray {
        val ftyp = box("ftyp", ascii("isom"), ints(512), ascii("isom"), ascii("iso2"), ascii(brand), ascii("mp41"))
        val mvhd = fullBox(
            "mvhd", 0, 0,
            ints(0, 0, 1000, 0, 0x00010000), short(0x0100), short(0), ByteArray(8),
            matrix, ByteArray(24), ints(2)
        )
        val tkhd = fullBox(
            "tkhd", 0, 7,
            ints(0, 0, 1, 0, 0), ByteArray(8), short(0), short(0), short(0), short(0),
            matrix, ints(width shl 16, height shl 16)
        )
        val mdhd = fullBox("mdhd", 0, 0, ints(0, 0, TIMESCALE, 0), short(0x55C4), short(0))
        val hdlr = fullBox("hdlr", 0, 0, ints(0), ascii("vide"), ByteArray(12), ascii("VideoHandler") + 0.toByte())
        val vmhd = fullBox("vmhd", 0, 1, ByteArray(8))
        val dinf = box("dinf", fullBox("dref", 0, 0, ints(1), fullBox("url ", 0, 1)))
        // mvhd below advertises next_track_ID = 2 for the video only case; audio uses track 2.
        val stbl = box(
            "stbl",
            fullBox("stsd", 0, 0, ints(1), sampleEntry),
            fullBox("stts", 0, 0, ints(0)),
            fullBox("stsc", 0, 0, ints(0)),
            fullBox("stsz", 0, 0, ints(0, 0)),
            fullBox("stco", 0, 0, ints(0))
        )
        val trak = box("trak", tkhd, box("mdia", mdhd, hdlr, box("minf", vmhd, dinf, stbl)))
        val trexes = arrayListOf(fullBox("trex", 0, 0, ints(1, 1, 0, 0, 0)))
        if (audio != null) trexes.add(fullBox("trex", 0, 0, ints(2, 1, 0, 0, 0)))
        val mvex = box("mvex", *trexes.toTypedArray())
        val tracks = if (audio != null) arrayOf(trak, audioTrak(audio)) else arrayOf(trak)
        return ftyp + box("moov", mvhd, *tracks, mvex)
    }

    /**
     * One moof+mdat pair holding a single sample.
     * [decodeTime] and [duration] are in [TIMESCALE] units. [sample] is in AVCC form.
     */
    fun fragment(
        sequence: Int,
        decodeTime: Long,
        duration: Int,
        key: Boolean,
        sample: ByteArray,
        trackId: Int = 1,
    ): ByteArray {
        fun moof(dataOffset: Int): ByteArray {
            val mfhd = fullBox("mfhd", 0, 0, ints(sequence))
            val tfhd = fullBox("tfhd", 0, 0x020000, ints(trackId))
            val tfdt = fullBox("tfdt", 1, 0, ByteBuffer.allocate(8).putLong(decodeTime).array())
            val sampleFlags = if (key) 0x02000000 else 0x01010000
            val trun = fullBox("trun", 0, 0x000701, ints(1, dataOffset, duration, sample.size, sampleFlags))
            return box("moof", mfhd, box("traf", tfhd, tfdt, trun))
        }
        val size = moof(0).size
        return moof(size + 8) + box("mdat", sample)
    }
}
