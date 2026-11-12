package app.ipcamhub.core

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer

/** Container a recording is written in. MP4 is the default; MKV and TS keep what was written if the app is killed. */
enum class RecordingFormat(val extension: String, val mime: String) {
    MP4("mp4", "video/mp4"),
    MKV("mkv", "video/x-matroska"),
    TS("ts", "video/mp2t");

    companion object {
        fun of(name: String?): RecordingFormat = entries.firstOrNull { it.name == name } ?: MP4
        val extensions: Set<String> = entries.map { it.extension }.toSet()
        fun mimeOf(file: File): String = entries.firstOrNull { it.extension == file.extension.lowercase() }?.mime ?: "video/*"
    }
}

/** Something that writes the encoded stream to [file]. */
interface Recorder : EncodedSink {
    val file: File
}

fun newRecorder(format: RecordingFormat, file: File): Recorder = when (format) {
    RecordingFormat.MP4 -> Mp4Recorder(file)
    RecordingFormat.MKV -> MkvRecorder(file)
    RecordingFormat.TS -> TsRecorder(file)
}

/** Codec details of the video track, read from the encoder's output format. */
class VideoTrackInfo(
    val hevc: Boolean,
    val width: Int,
    val height: Int,
    /** avcC or hvcC record, as MP4 and Matroska store it. */
    val config: ByteArray,
    /** Parameter sets in Annex B form, as MPEG-TS repeats them. */
    val annexBParams: ByteArray,
) {
    companion object {
        fun from(format: MediaFormat): VideoTrackInfo? {
            val w = format.getInteger(MediaFormat.KEY_WIDTH)
            val h = format.getInteger(MediaFormat.KEY_HEIGHT)
            val csd0 = bytes(format.getByteBuffer("csd-0") ?: return null)
            if (format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_HEVC) {
                val ps = Hevc.parameterSets(csd0) ?: return null
                return VideoTrackInfo(true, w, h, Hevc.decoderConfigurationRecord(ps), Hevc.annexB(ps))
            }
            val sps = AnnexB.firstNal(csd0)
            val pps = AnnexB.firstNal(bytes(format.getByteBuffer("csd-1") ?: return null))
            return VideoTrackInfo(false, w, h, avcConfig(sps, pps), START + sps + START + pps)
        }

        /** AVCDecoderConfigurationRecord with one SPS and one PPS, 4-byte NAL lengths. */
        fun avcConfig(sps: ByteArray, pps: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(1)
            out.write(sps[1].toInt())
            out.write(sps[2].toInt())
            out.write(sps[3].toInt())
            out.write(0xFF)
            out.write(0xE1)
            out.write(sps.size shr 8); out.write(sps.size)
            out.write(sps)
            out.write(1)
            out.write(pps.size shr 8); out.write(pps.size)
            out.write(pps)
            return out.toByteArray()
        }

        fun bytes(buffer: ByteBuffer): ByteArray {
            val copy = buffer.duplicate()
            copy.rewind()
            return ByteArray(copy.remaining()).also { copy.get(it) }
        }

        private val START = byteArrayOf(0, 0, 0, 1)
    }
}

/**
 * Shared logic for the recorders that write their own container: waits for the formats (and up to two
 * seconds for sound), starts the timeline at the first video frame, keeps timestamps increasing, and
 * deletes a file that never got a frame.
 */
abstract class PacketRecorder(final override val file: File) : Recorder {
    private var expectAudio = false
    private var videoFormat: MediaFormat? = null
    private var audio: Fmp4.AudioConfig? = null
    private var video: VideoTrackInfo? = null
    private var started = false
    private var failed = false
    private var baseUs = -1L
    private var lastVideoUs = -1L
    private var lastAudioUs = -1L
    private var firstSeenUs = -1L
    protected var samples = 0
        private set
    private val pending = ArrayList<Triple<ByteArray, Long, Boolean>>()

    protected abstract fun start(video: VideoTrackInfo, audio: Fmp4.AudioConfig?)

    /** [avcc] has 4-byte NAL lengths; [us] counts from the start of the recording. */
    protected abstract fun writeVideo(avcc: ByteArray, us: Long, key: Boolean)

    protected abstract fun writeAudio(frame: ByteArray, us: Long)

    protected abstract fun finish()

    @Synchronized
    override fun onTracks(hasAudio: Boolean) {
        expectAudio = hasAudio
    }

    @Synchronized
    override fun onFormat(format: MediaFormat) {
        videoFormat = format
        tryStart(force = false)
    }

    @Synchronized
    override fun onAudioFormat(format: MediaFormat) {
        val csd = format.getByteBuffer("csd-0") ?: return
        audio = Fmp4.AudioConfig(
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE), format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            VideoTrackInfo.bytes(csd)
        )
        tryStart(force = false)
    }

    private fun tryStart(force: Boolean) {
        if (started || failed) return
        val format = videoFormat ?: return
        if (expectAudio && audio == null && !force) return
        val v = VideoTrackInfo.from(format) ?: return
        video = v
        try {
            file.parentFile?.mkdirs()
            start(v, audio.takeIf { expectAudio })
            started = true
        } catch (e: Exception) {
            Log.e("Recorder", "cannot start ${file.name}", e)
            failed = true
            return
        }
        pending.forEach { (raw, pts, key) -> video(raw, pts, key) }
        pending.clear()
    }

    @Synchronized
    override fun onSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (failed) return
        val raw = ByteArray(info.size).also { data.get(it) }
        val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        if (firstSeenUs < 0) firstSeenUs = info.presentationTimeUs
        if (!started) {
            // Give up waiting for sound after two seconds and record video only.
            if (expectAudio && info.presentationTimeUs - firstSeenUs > 2_000_000) {
                expectAudio = false
                tryStart(force = true)
            }
            if (!started) {
                pending.add(Triple(raw, info.presentationTimeUs, key))
                return
            }
        }
        video(raw, info.presentationTimeUs, key)
    }

    private fun video(raw: ByteArray, ptsUs: Long, key: Boolean) {
        val v = video ?: return
        if (baseUs < 0) {
            if (!key) return // a recording starts at a key frame
            baseUs = ptsUs
        }
        var us = ptsUs - baseUs
        // Frames released in a burst can share a timestamp; players expect strictly increasing ones.
        if (us <= lastVideoUs) us = lastVideoUs + 1_000
        lastVideoUs = us
        val avcc = AnnexB.toAvcc(raw, hevc = v.hevc)
        if (avcc.isEmpty()) return
        try {
            writeVideo(avcc, us, key)
            samples++
        } catch (e: Exception) {
            Log.e("Recorder", "write failed for ${file.name}", e)
            failed = true
        }
    }

    @Synchronized
    override fun onAudioSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started || failed || audio == null || !expectAudio || baseUs < 0) return
        var us = info.presentationTimeUs - baseUs
        if (us < 0) return // sound from before the first video frame
        if (us <= lastAudioUs) us = lastAudioUs + 1
        lastAudioUs = us
        val frame = ByteArray(info.size).also { data.get(it) }
        try {
            writeAudio(frame, us)
        } catch (e: Exception) {
            Log.e("Recorder", "audio write failed for ${file.name}", e)
            failed = true
        }
    }

    @Synchronized
    override fun onEnd() {
        if (started) {
            try {
                finish()
            } catch (e: Exception) {
                Log.w("Recorder", "finishing ${file.name} failed", e)
            }
        }
        if (samples == 0) file.delete()
    }
}

/** MPEG transport stream: tables before every key frame, so the file plays from any point and survives a crash. */
class TsRecorder(file: File) : PacketRecorder(file) {
    private var out: BufferedOutputStream? = null
    private var writer: MpegTsWriter? = null

    override fun start(video: VideoTrackInfo, audio: Fmp4.AudioConfig?) {
        writer = MpegTsWriter(audio?.sampleRate ?: 0, audio?.channels ?: 2, video.annexBParams, video.hevc)
        out = BufferedOutputStream(FileOutputStream(file), 256 * 1024)
    }

    override fun writeVideo(avcc: ByteArray, us: Long, key: Boolean) {
        val w = writer ?: return
        val o = out ?: return
        if (key) o.write(w.tables())
        o.write(w.video(MpegTsWriter.avccToAnnexB(avcc), us, key))
    }

    override fun writeAudio(frame: ByteArray, us: Long) {
        val w = writer ?: return
        out?.write(w.audio(frame, us))
    }

    override fun finish() {
        out?.flush()
        out?.close()
        out = null
    }
}

/**
 * Matroska (.mkv). Each cluster is written whole, starting at a video key frame, so a file cut off by a
 * crash still plays up to its last cluster. On a normal stop the segment size, the duration and a cue
 * index (for fast seeking) are filled in.
 */
class MkvRecorder(file: File) : PacketRecorder(file) {
    private var raf: RandomAccessFile? = null
    private var segmentDataStart = 0L
    private var segmentSizeAt = 0L
    private var durationAt = 0L
    private var cuesSeekAt = 0L
    private var hasAudio = false
    private val cluster = ByteArrayOutputStream()
    private var clusterStartMs = -1L
    private var lastMs = 0L
    private val cues = ArrayList<Pair<Long, Long>>() // (time ms, cluster position in the segment)

    override fun start(video: VideoTrackInfo, audio: Fmp4.AudioConfig?) {
        hasAudio = audio != null
        val r = RandomAccessFile(file, "rw")
        r.setLength(0)
        raf = r
        r.write(Ebml.element(Ebml.EBML,
            Ebml.uint(0x4286, 1), Ebml.uint(0x42F7, 1), Ebml.uint(0x42F2, 4), Ebml.uint(0x42F3, 8),
            Ebml.string(0x4282, "matroska"), Ebml.uint(0x4287, 4), Ebml.uint(0x4285, 2)))
        // Segment with an unknown size until the recording ends.
        r.write(Ebml.id(Ebml.SEGMENT))
        segmentSizeAt = r.filePointer
        r.write(Ebml.UNKNOWN_SIZE)
        segmentDataStart = r.filePointer

        // SeekHead with fixed-size positions, so the Cues position can be filled in at the end.
        val seekHead = Ebml.element(Ebml.SEEK_HEAD,
            seek(Ebml.INFO, 0), seek(Ebml.TRACKS, 0), seek(Ebml.CUES, 0))
        val seekHeadPos = r.filePointer
        r.write(seekHead)

        val infoPos = r.filePointer - segmentDataStart
        val info = Ebml.element(Ebml.INFO,
            Ebml.uint(0x2AD7B1, 1_000_000), // timestamps in milliseconds
            Ebml.string(0x4D80, "Camora"), Ebml.string(0x5741, "Camora"),
            Ebml.float(0x4489, 0.0))
        r.write(info)
        durationAt = r.filePointer - 8 // the duration's 8 value bytes end the Info element

        val tracksPos = r.filePointer - segmentDataStart
        val videoTrack = Ebml.element(0xAE,
            Ebml.uint(0xD7, 1), Ebml.uint(0x73C5, 1), Ebml.uint(0x83, 1),
            Ebml.string(0x86, if (video.hevc) "V_MPEGH/ISO/HEVC" else "V_MPEG4/ISO/AVC"),
            Ebml.binary(0x63A2, video.config),
            Ebml.element(0xE0, Ebml.uint(0xB0, video.width.toLong()), Ebml.uint(0xBA, video.height.toLong())))
        val tracks = if (audio != null) {
            val audioTrack = Ebml.element(0xAE,
                Ebml.uint(0xD7, 2), Ebml.uint(0x73C5, 2), Ebml.uint(0x83, 2),
                Ebml.string(0x86, "A_AAC"), Ebml.binary(0x63A2, audio.asc),
                Ebml.element(0xE1, Ebml.float(0xB5, audio.sampleRate.toDouble()), Ebml.uint(0x9F, audio.channels.toLong())))
            Ebml.element(Ebml.TRACKS, videoTrack, audioTrack)
        } else {
            Ebml.element(Ebml.TRACKS, videoTrack)
        }
        r.write(tracks)

        // Fill in the Info and Tracks positions now; the Cues position comes at the end.
        val positions = Ebml.element(Ebml.SEEK_HEAD,
            seek(Ebml.INFO, infoPos), seek(Ebml.TRACKS, tracksPos), seek(Ebml.CUES, 0))
        r.seek(seekHeadPos)
        r.write(positions)
        // Where the Cues position's 8 bytes are: the end of the SeekHead.
        cuesSeekAt = seekHeadPos + positions.size - 8
        r.seek(r.length())
    }

    private fun seek(id: Int, position: Long): ByteArray =
        Ebml.element(0x4DBB, Ebml.binary(0x53AB, Ebml.id(id)), Ebml.fixedUint(0x53AC, position))

    override fun writeVideo(avcc: ByteArray, us: Long, key: Boolean) {
        val ms = us / 1000
        // A new cluster at every key frame, and before the 16-bit relative timestamp could overflow.
        if (clusterStartMs < 0 || (key && cluster.size() > 0) || ms - clusterStartMs > 30_000) startCluster(ms, key)
        block(1, ms, avcc, key)
    }

    override fun writeAudio(frame: ByteArray, us: Long) {
        if (clusterStartMs < 0) return // sound only goes into clusters a video frame started
        val ms = us / 1000
        // Sound slightly before the cluster's key frame is fine: block times are signed.
        if (ms - clusterStartMs > 30_000) startCluster(ms, false)
        block(2, ms, frame, true)
    }

    private fun startCluster(ms: Long, key: Boolean) {
        flushCluster()
        clusterStartMs = ms
        val r = raf ?: return
        if (key) cues.add(ms to (r.filePointer - segmentDataStart))
    }

    private fun block(track: Int, ms: Long, data: ByteArray, key: Boolean) {
        val rel = (ms - clusterStartMs).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val header = byteArrayOf((0x80 or track).toByte(), (rel shr 8).toByte(), rel.toByte(), (if (key) 0x80 else 0).toByte())
        cluster.write(Ebml.id(0xA3))
        cluster.write(Ebml.size((header.size + data.size).toLong()))
        cluster.write(header)
        cluster.write(data)
        if (ms > lastMs) lastMs = ms
    }

    private fun flushCluster() {
        val r = raf ?: return
        if (cluster.size() == 0) return
        val body = Ebml.uint(0xE7, clusterStartMs) + cluster.toByteArray()
        r.write(Ebml.id(Ebml.CLUSTER))
        r.write(Ebml.size(body.size.toLong()))
        r.write(body)
        cluster.reset()
    }

    override fun finish() {
        val r = raf ?: return
        flushCluster()
        if (cues.isNotEmpty()) {
            val cuesPos = r.filePointer - segmentDataStart
            val points = cues.map { (ms, pos) ->
                Ebml.element(0xBB, Ebml.uint(0xB3, ms),
                    Ebml.element(0xB7, Ebml.uint(0xF7, 1), Ebml.uint(0xF1, pos)))
            }
            r.write(Ebml.element(Ebml.CUES, *points.toTypedArray()))
            r.seek(cuesSeekAt)
            r.write(ByteBuffer.allocate(8).putLong(cuesPos).array())
        }
        val segmentSize = r.length() - segmentDataStart
        r.seek(segmentSizeAt)
        r.write(Ebml.size8(segmentSize))
        r.seek(durationAt)
        r.write(ByteBuffer.allocate(8).putDouble((lastMs + 33).toDouble()).array())
        r.close()
        raf = null
    }
}

/** Minimal EBML (Matroska) encoding. */
internal object Ebml {
    const val EBML = 0x1A45DFA3
    const val SEGMENT = 0x18538067
    const val SEEK_HEAD = 0x114D9B74
    const val INFO = 0x1549A966
    const val TRACKS = 0x1654AE6B
    const val CUES = 0x1C53BB6B
    const val CLUSTER = 0x1F43B675

    /** An 8-byte size meaning "unknown", for a segment still being written. */
    val UNKNOWN_SIZE = byteArrayOf(0x01, -1, -1, -1, -1, -1, -1, -1)

    /** Element IDs are written as their bytes, which already carry the length marker. */
    fun id(id: Int): ByteArray = when {
        id > 0xFFFFFF -> byteArrayOf((id ushr 24).toByte(), (id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
        id > 0xFFFF -> byteArrayOf((id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
        id > 0xFF -> byteArrayOf((id shr 8).toByte(), id.toByte())
        else -> byteArrayOf(id.toByte())
    }

    /** Variable-length size, as short as possible. */
    fun size(value: Long): ByteArray {
        var len = 1
        while (len < 8 && value >= (1L shl (7 * len)) - 1) len++
        val out = ByteArray(len)
        var v = value
        for (i in len - 1 downTo 0) {
            out[i] = v.toByte()
            v = v ushr 8
        }
        out[0] = (out[0].toInt() or (0x80 ushr (len - 1))).toByte()
        return out
    }

    /** Size in exactly 8 bytes, to overwrite a placeholder. */
    fun size8(value: Long): ByteArray {
        val b = ByteBuffer.allocate(8).putLong(value).array()
        b[0] = 0x01
        return b
    }

    fun element(id: Int, vararg children: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        children.forEach { body.write(it) }
        return id(id) + size(body.size().toLong()) + body.toByteArray()
    }

    fun binary(id: Int, data: ByteArray): ByteArray = id(id) + size(data.size.toLong()) + data

    fun string(id: Int, s: String): ByteArray = binary(id, s.toByteArray(Charsets.UTF_8))

    fun uint(id: Int, value: Long): ByteArray {
        var len = 1
        while (len < 8 && value ushr (8 * len) != 0L) len++
        val b = ByteArray(len)
        var v = value
        for (i in len - 1 downTo 0) {
            b[i] = v.toByte()
            v = v ushr 8
        }
        return binary(id, b)
    }

    fun uint(id: Int, value: Int): ByteArray = uint(id, value.toLong())

    fun fixedUint(id: Int, value: Long): ByteArray = binary(id, ByteBuffer.allocate(8).putLong(value).array())

    fun float(id: Int, value: Double): ByteArray = binary(id, ByteBuffer.allocate(8).putDouble(value).array())
}
