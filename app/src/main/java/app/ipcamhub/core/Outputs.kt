package app.ipcamhub.core

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import app.ipcamhub.data.CameraConfig
import app.ipcamhub.data.SourceType
import java.nio.ByteBuffer

/** Fans the encoder output out to an optional recorder and the live stream. */
class Mux(private val live: LiveStream) : EncodedSink {
    @Volatile
    private var recorder: Recorder? = null

    @Volatile
    private var format: MediaFormat? = null

    @Volatile
    private var audioFormat: MediaFormat? = null

    @Volatile
    private var hasAudio = false

    @Volatile
    private var recorderWaitingForKey = false

    /** Bytes the encoders produced so far (video and sound), for the bitrate readout. */
    @Volatile
    var bytesOut = 0L
        private set

    /** Starts writing to [r]. The file begins at the next key frame. */
    @Synchronized
    fun attachRecorder(r: Recorder) {
        r.onTracks(hasAudio)
        format?.let { r.onFormat(it) }
        audioFormat?.let { r.onAudioFormat(it) }
        recorderWaitingForKey = true
        recorder = r
    }

    @Synchronized
    fun detachRecorder() {
        val r = recorder
        recorder = null
        r?.onEnd()
    }

    override fun onTracks(hasAudio: Boolean) {
        this.hasAudio = hasAudio
        live.onTracks(hasAudio)
        recorder?.onTracks(hasAudio)
    }

    override fun onFormat(format: MediaFormat) {
        this.format = format
        live.onFormat(format)
        recorder?.onFormat(format)
    }

    override fun onAudioFormat(format: MediaFormat) {
        audioFormat = format
        live.onAudioFormat(format)
        recorder?.onAudioFormat(format)
    }

    override fun onSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        bytesOut += info.size
        val r = recorder
        if (r != null) {
            val key = (info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
            if (recorderWaitingForKey && key) recorderWaitingForKey = false
            if (!recorderWaitingForKey) r.onSample(data.duplicate(), info)
        }
        live.onSample(data.duplicate(), info)
    }

    override fun onAudioSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        bytesOut += info.size
        val r = recorder
        if (r != null && !recorderWaitingForKey) r.onAudioSample(data.duplicate(), info)
        live.onAudioSample(data.duplicate(), info)
    }

    override fun onEnd() {
        detachRecorder()
        live.onEnd()
    }
}

/** Everything that runs for one camera in the background: the encoder source, recording and viewers. */
class Outputs(var camera: CameraConfig) {
    val live = LiveStream()
    val mux = Mux(live)
    var source: EncodeSource? = null
    var recording: Boolean = false

    /** File being written right now, so cleanup never deletes it. */
    var currentFile: java.io.File? = null

    fun ensureSource(context: Context) {
        if (source != null) return
        val s: EncodeSource = when (camera.type) {
            SourceType.RTSP -> RtspEncodeSource(context, camera.url, camera.quality, camera.audio, camera.tcp)
            SourceType.UVC -> UvcEncodeSource(context, camera.quality, camera.audio)
            SourceType.DEMO -> DemoEncodeSource(camera.quality, camera.audio)
        }
        source = s
        s.start(mux)
    }

    fun stopSource() {
        source?.stop()
        source = null
    }

    val idle: Boolean get() = !recording && live.clientCount == 0 && live.packager == null
}
