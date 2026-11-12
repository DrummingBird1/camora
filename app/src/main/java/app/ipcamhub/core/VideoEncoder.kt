package app.ipcamhub.core

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/** Receives encoded output. All callbacks come from encoder threads. */
interface EncodedSink {
    /** Called once, before any format, to say whether the source also has sound. */
    fun onTracks(hasAudio: Boolean) {}

    fun onFormat(format: MediaFormat) {}

    /** [data] is only valid during the call. Samples are in Annex B form. */
    fun onSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {}

    /** Called once when the audio encoder knows its output format (AAC). */
    fun onAudioFormat(format: MediaFormat) {}

    /** [data] is a raw AAC frame, only valid during the call. */
    fun onAudioSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {}

    fun onEnd() {}
}

/**
 * Hardware H.264 encoder fed through an input [Surface]. Anything that can render to a Surface
 * (a video decoder, the UVC renderer) can be recorded or streamed through it.
 */
class VideoEncoder(
    sourceWidth: Int,
    sourceHeight: Int,
    fps: Int,
    private val sink: EncodedSink,
    quality: Quality = Quality(),
) {

    val width: Int
    val height: Int
    val inputSurface: Surface

    /** The codec actually used: H.265 can fall back to H.264 when the phone has no suitable encoder. */
    val mime: String

    private val thread = HandlerThread("video-encoder").apply { start() }
    private val handler = Handler(thread.looper)
    private val codec: MediaCodec
    private var stopped = false

    init {
        val (w0, h0) = EncodeParams.targetSize(sourceWidth, sourceHeight, quality.maxHeight)
        // Requested size first, then a 1080p fallback in case the device cannot encode that size.
        val sizes = buildList {
            add(w0 to h0)
            if (h0 > 1080) add(EncodeParams.targetSize(w0, h0, 1080))
        }
        val mimes = if (quality.hevc) listOf(MediaFormat.MIMETYPE_VIDEO_HEVC, MediaFormat.MIMETYPE_VIDEO_AVC)
        else listOf(MediaFormat.MIMETYPE_VIDEO_AVC)
        var created: MediaCodec? = null
        var chosen = sizes.first()
        var chosenMime = mimes.first()
        var lastError: Exception? = null
        loop@ for (m in mimes) {
            for ((w, h) in sizes) {
                for (cbr in listOf(true, false)) {
                    try {
                        created = createCodec(m, w, h, fps.coerceIn(15, 60), quality.bitrate, cbr)
                        chosen = w to h
                        chosenMime = m
                        break@loop
                    } catch (e: Exception) {
                        lastError = e
                        Log.w(TAG, "Encoder $m ${w}x$h cbr=$cbr failed", e)
                    }
                }
            }
        }
        mime = chosenMime
        Log.i(TAG, "encoding $mime ${chosen.first}x${chosen.second}")
        codec = created ?: throw IllegalStateException("No H.264 encoder available", lastError)
        width = chosen.first
        height = chosen.second
        inputSurface = codec.createInputSurface()
        codec.start()
    }

    private fun createCodec(mime: String, w: Int, h: Int, fps: Int, preset: Int, cbr: Boolean): MediaCodec {
        val hevc = mime == MediaFormat.MIMETYPE_VIDEO_HEVC
        val bitrate = EncodeParams.bitrate(w, h, fps, preset, hevc)
        val format = MediaFormat.createVideoFormat(mime, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(
                MediaFormat.KEY_BITRATE_MODE,
                if (cbr) MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR
                else MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            )
            setInteger(MediaFormat.KEY_PRIORITY, 0)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            if (hevc) setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain)
        }
        val c = MediaCodec.createEncoderByType(mime)
        try {
            c.setCallback(codecCallback(), handler)
            c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            c.release()
            throw e
        }
        return c
    }

    // A function (not a property): the init block above runs before later property initializers.
    private fun codecCallback() = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            try {
                if (info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                    val buffer = codec.getOutputBuffer(index)
                    if (buffer != null) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        sink.onSample(buffer, info)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "sink failed", e)
            } finally {
                try {
                    codec.releaseOutputBuffer(index, false)
                } catch (_: Exception) {
                }
            }
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            Log.i(TAG, "encoder output format: $format")
            sink.onFormat(format)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "encoder error", e)
        }
    }

    /** Asks the encoder for a key frame now, so a new viewer does not wait for the next one. */
    fun requestKeyFrame() {
        handler.post {
            try {
                if (!stopped) {
                    codec.setParameters(android.os.Bundle().apply {
                        putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
                    })
                }
            } catch (_: Exception) {
            }
        }
    }

    /** Stops encoding, then tells the sink the stream ended. Safe to call more than once. */
    fun stop() {
        synchronized(this) {
            if (stopped) return
            stopped = true
        }
        handler.post {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
            inputSurface.release()
            sink.onEnd()
            thread.quitSafely()
        }
    }

    private companion object {
        const val TAG = "VideoEncoder"
    }
}
