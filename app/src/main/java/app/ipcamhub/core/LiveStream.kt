package app.ipcamhub.core

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Turns the encoder output into fragmented MP4 and hands it to every connected viewer.
 * Each viewer gets its own timeline starting at 0 on its first key frame.
 */
class LiveStream : EncodedSink {

    @Volatile
    var initSegment: ByteArray? = null
        private set

    /** Codec string for MSE, for example "avc1.640028,mp4a.40.2". */
    @Volatile
    var codec: String = ""
        private set

    private val clients = CopyOnWriteArrayList<Client>()

    val clientCount: Int get() = clients.size

    /** Called when the number of viewers changes. */
    @Volatile
    var onClientsChanged: (() -> Unit)? = null

    /** True when the encoder produces H.265. */
    @Volatile
    var hevc: Boolean = false
        private set

    /** Parameter sets in Annex B form, for transport streams. */
    @Volatile
    var parameterSets: ByteArray = ByteArray(0)
        private set

    private var sampleCount = 0L
    private var hasAudio = false
    private var videoFormat: MediaFormat? = null
    private var audioConfig: Fmp4.AudioConfig? = null
    private var firstVideoUs = -1L

    @Volatile
    private var audioActive = false

    @Synchronized
    override fun onTracks(hasAudio: Boolean) {
        this.hasAudio = hasAudio
    }

    @Synchronized
    override fun onFormat(format: MediaFormat) {
        videoFormat = format
        tryBuildInit(force = false)
    }

    @Synchronized
    override fun onAudioFormat(format: MediaFormat) {
        val csd = format.getByteBuffer("csd-0") ?: return
        audioConfig = Fmp4.AudioConfig(
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            bytesOf(csd)
        )
        tryBuildInit(force = false)
    }

    private fun tryBuildInit(force: Boolean) {
        if (initSegment != null) return
        val format = videoFormat ?: return
        if (hasAudio && audioConfig == null && !force) return
        val audio = audioConfig.takeIf { hasAudio }
        val width = format.getInteger(MediaFormat.KEY_WIDTH)
        val height = format.getInteger(MediaFormat.KEY_HEIGHT)
        val audioCodec = if (audio != null) "," + Fmp4.AUDIO_CODEC else ""
        if (format.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_VIDEO_HEVC) {
            // H.265 keeps VPS, SPS and PPS together in csd-0.
            val ps = Hevc.parameterSets(bytesOf(format.getByteBuffer("csd-0") ?: return)) ?: return
            hevc = true
            codec = Hevc.codecString(ps.sps) + audioCodec
            parameterSets = Hevc.annexB(ps)
            audioActive = audio != null
            initSegment = Fmp4.initSegmentHevc(ps, width, height, audio)
            return
        }
        val sps = AnnexB.firstNal(bytesOf(format.getByteBuffer("csd-0") ?: return))
        val pps = AnnexB.firstNal(bytesOf(format.getByteBuffer("csd-1") ?: return))
        codec = Fmp4.codecString(sps) + audioCodec
        audioActive = audio != null
        parameterSets = START_CODE + sps + START_CODE + pps
        initSegment = Fmp4.initSegment(sps, pps, width, height, audio)
    }

    override fun onSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (initSegment == null) {
            synchronized(this) {
                if (firstVideoUs < 0) firstVideoUs = info.presentationTimeUs
                // Stop waiting for sound after two seconds and stream video only.
                if (hasAudio && info.presentationTimeUs - firstVideoUs > 2_000_000) tryBuildInit(force = true)
            }
        }
        if ((++sampleCount % 120) == 0L) {
            // How long after the camera frame was taken the encoder delivered it (same clock as the timestamps).
            Log.d("LiveStream", "encoder lag ${System.nanoTime() / 1000 - info.presentationTimeUs} us, clients=${clients.size}")
        }
        if (clients.isEmpty() && packager == null) return
        val raw = ByteArray(info.size)
        data.get(raw)
        val avcc = AnnexB.toAvcc(raw, hevc = hevc)
        if (avcc.isEmpty()) return
        val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
        for (c in clients) c.offer(avcc, info.presentationTimeUs, key)
        packager?.video(avcc, info.presentationTimeUs, key)
    }

    override fun onAudioSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!audioActive) return
        if (clients.isEmpty() && packager == null) return
        val frame = ByteArray(info.size)
        data.get(frame)
        for (c in clients) c.offerAudio(frame, info.presentationTimeUs)
        packager?.audio(frame, info.presentationTimeUs)
    }

    override fun onEnd() {
        clients.forEach { it.finish() }
    }

    /** Optional consumer of the same samples with one shared timeline, used for HLS. */
    @Volatile
    var packager: SegmentPackager? = null

    /** AAC sample rate when sound is part of the stream, otherwise 0. */
    fun audioRate(): Int = if (audioActive) audioConfig?.sampleRate ?: 0 else 0

    fun audioChannels(): Int = audioConfig?.channels ?: 2

    fun newClient(ts: Boolean = false): Client {
        val c = Client(ts)
        clients.add(c)
        onClientsChanged?.invoke()
        return c
    }

    private fun bytesOf(buffer: ByteBuffer): ByteArray {
        val copy = buffer.duplicate()
        copy.rewind()
        return ByteArray(copy.remaining()).also { copy.get(it) }
    }

    /** A viewer. Read it as an [InputStream]: first the init segment, then fragments. */
    inner class Client(private val tsMode: Boolean = false) : InputStream() {
        private var tsWriter: MpegTsWriter? = null
        private var lastTsUs = -1L
        private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_LIMIT)
        private var current: ByteArray? = null
        private var position = 0

        @Volatile
        private var closed = false
        private var waitingForKey = true
        private var baseUs = 0L
        private var lastUs = 0L
        private var lastDecode = -1L
        private var sequence = 1
        private var startedWithInit = false

        @Synchronized
        fun offer(avcc: ByteArray, ptsUs: Long, key: Boolean) {
            if (closed) return
            if (tsMode) {
                offerTs(avcc, ptsUs, key)
                return
            }
            if (!startedWithInit) {
                val i = initSegment ?: return
                queue.offer(i)
                startedWithInit = true
            }
            if (waitingForKey) {
                if (!key) return
                waitingForKey = false
                baseUs = ptsUs
                lastUs = ptsUs
                lastDecode = -1
            }
            var decodeTime = (ptsUs - baseUs) * Fmp4.TIMESCALE / 1_000_000
            // Frames released in a burst can share a timestamp; keep them strictly increasing.
            if (decodeTime <= lastDecode) decodeTime = lastDecode + 90
            lastDecode = decodeTime
            val delta = (ptsUs - lastUs).coerceIn(1_000, 200_000)
            val duration = (delta * Fmp4.TIMESCALE / 1_000_000).toInt()
            lastUs = ptsUs
            push(Fmp4.fragment(sequence++, decodeTime, duration, key, avcc))
        }

        /** MPEG-TS viewers (TVs over DLNA): no init segment, tables repeat at every key frame. */
        private fun offerTs(avcc: ByteArray, ptsUs: Long, key: Boolean) {
            if (initSegment == null) return
            val w = tsWriter ?: MpegTsWriter(audioRate(), audioChannels(), parameterSets, hevc).also { tsWriter = it }
            if (waitingForKey) {
                if (!key) return
                waitingForKey = false
                baseUs = ptsUs
                lastTsUs = -1
            }
            var us = ptsUs - baseUs
            if (us <= lastTsUs) us = lastTsUs + 1_000
            lastTsUs = us
            if (key) push(w.tables())
            push(w.video(MpegTsWriter.avccToAnnexB(avcc), us, key))
        }

        @Synchronized
        fun offerAudio(frame: ByteArray, ptsUs: Long) {
            if (tsMode) {
                val w = tsWriter
                if (!closed && w != null && !waitingForKey && ptsUs >= baseUs) push(w.audio(frame, ptsUs - baseUs))
                return
            }
            if (closed || waitingForKey || !startedWithInit || ptsUs < baseUs) return
            val rate = audioConfig?.sampleRate ?: return
            val decodeTime = (ptsUs - baseUs) * rate / 1_000_000
            push(Fmp4.fragment(sequence++, decodeTime, 1024, true, frame, trackId = 2))
        }

        private fun push(fragment: ByteArray) {
            if (!queue.offer(fragment)) {
                // Viewer is too slow: drop the backlog and resume at the next key frame.
                queue.clear()
                waitingForKey = true
                startedWithInit = true
            }
        }

        fun finish() {
            closed = true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                val cur = current
                if (cur != null && position < cur.size) {
                    val n = minOf(len, cur.size - position)
                    System.arraycopy(cur, position, b, off, n)
                    position += n
                    return n
                }
                val next = queue.poll(500, TimeUnit.MILLISECONDS)
                if (next != null) {
                    current = next
                    position = 0
                } else if (closed) {
                    return -1
                }
            }
        }

        override fun close() {
            if (closed && !clients.contains(this)) return
            closed = true
            if (clients.remove(this)) onClientsChanged?.invoke()
        }
    }

    private companion object {
        const val QUEUE_LIMIT = 480
        val START_CODE = byteArrayOf(0, 0, 0, 1)
    }
}

/** Receives video and audio samples with their original timestamps. Implemented by the HLS packager. */
interface SegmentPackager {
    fun video(avcc: ByteArray, ptsUs: Long, key: Boolean)
    fun audio(frame: ByteArray, ptsUs: Long)
}
