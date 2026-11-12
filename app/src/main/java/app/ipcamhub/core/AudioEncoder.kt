package app.ipcamhub.core

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Encodes 16-bit PCM to AAC-LC on its own thread. Feed it with [queuePcm]; encoded frames
 * go to [EncodedSink.onAudioSample]. Timestamps are in microseconds in the same clock as video.
 */
class AudioEncoder(
    private val sampleRate: Int,
    private val channels: Int,
    private val sink: EncodedSink,
) {
    private class Chunk(val data: ByteArray, val ptsUs: Long)

    private val queue = LinkedBlockingQueue<Chunk>(200)
    private val encoderDelayUs = ENCODER_DELAY_SAMPLES * 1_000_000L / sampleRate

    @Volatile
    private var running = true
    private val thread = Thread({ run() }, "audio-encoder").apply { isDaemon = true; start() }

    /** Returns false (and drops the data) when the encoder cannot keep up. */
    fun queuePcm(pcm: ByteArray, ptsUs: Long): Boolean = running && queue.offer(Chunk(pcm, ptsUs))

    fun stop() {
        running = false
    }

    private fun run() {
        val codec: MediaCodec
        try {
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000 * channels)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            Log.w(TAG, "AAC encoder unavailable", e)
            return
        }
        val info = MediaCodec.BufferInfo()
        var pending: Chunk? = null
        var pendingOffset = 0
        val bytesPerFrame = 2 * channels
        try {
            while (running || pending != null || queue.isNotEmpty()) {
                if (pending == null) pending = queue.poll(20, TimeUnit.MILLISECONDS)
                val chunk = pending
                if (chunk != null) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        buffer.clear()
                        val n = minOf(buffer.remaining(), chunk.data.size - pendingOffset)
                        buffer.put(chunk.data, pendingOffset, n)
                        val ptsUs = chunk.ptsUs + pendingOffset / bytesPerFrame * 1_000_000L / sampleRate
                        codec.queueInputBuffer(index, 0, n, ptsUs, 0)
                        pendingOffset += n
                        if (pendingOffset >= chunk.data.size) {
                            pending = null
                            pendingOffset = 0
                        }
                    }
                }
                drain(codec, info)
            }
            // Flush what is left.
            val index = codec.dequeueInputBuffer(50_000)
            if (index >= 0) codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            var ended = false
            var spins = 0
            while (!ended && spins++ < 50) ended = drain(codec, info)
        } catch (e: Exception) {
            Log.w(TAG, "AAC encoder failed", e)
        } finally {
            try {
                codec.stop()
            } catch (_: Exception) {
            }
            codec.release()
        }
    }

    /** Returns true when the end-of-stream marker came out. */
    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo): Boolean {
        while (true) {
            val out = codec.dequeueOutputBuffer(info, 0)
            when {
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    Log.i(TAG, "AAC output format: ${codec.outputFormat}")
                    sink.onAudioFormat(codec.outputFormat)
                }
                out >= 0 -> {
                    val buffer = codec.getOutputBuffer(out)
                    val eos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    if (buffer != null && info.size > 0 && (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        // The encoder outputs its start-up delay as extra sound at the front; MP4 files written
                        // here do not record that, so players would play everything that much late.
                        info.presentationTimeUs -= encoderDelayUs
                        try {
                            sink.onAudioSample(buffer, info)
                        } catch (e: Exception) {
                            Log.w(TAG, "audio sink failed", e)
                        }
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (eos) return true
                }
                else -> return false
            }
        }
    }

    private companion object {
        const val TAG = "AudioEncoder"

        /** Measured on a phone with a flash-and-beep clip (380 ms at 8 kHz): three AAC frames. */
        const val ENCODER_DELAY_SAMPLES = 3072L
    }
}
