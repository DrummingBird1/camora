package app.ipcamhub.core

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.nio.ByteBuffer

/** Writes the encoded stream (video, and AAC audio when the source has it) to an MP4 file. */
class Mp4Recorder(override val output: RecordingOutput) : Recorder {

    private class Pending(val data: ByteArray, val info: MediaCodec.BufferInfo, val audio: Boolean)

    private var expectAudio = false
    private var videoFormat: MediaFormat? = null
    private var audioFormat: MediaFormat? = null
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var baseUs = -1L
    private var firstVideoUs = -1L
    private var samples = 0
    private var lastVideoPts = -1L
    private var lastAudioPts = -1L
    private val pending = ArrayList<Pending>()

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
        audioFormat = format
        tryStart(force = false)
    }

    private fun tryStart(force: Boolean) {
        if (started) return
        val v = videoFormat ?: return
        if (expectAudio && audioFormat == null && !force) return
        val m = try {
            MediaMuxer(output.open().fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (e: Exception) {
            Log.e("Mp4Recorder", "cannot open ${output.name}", e)
            return
        }
        videoTrack = m.addTrack(v)
        audioFormat?.let { audioTrack = m.addTrack(it) }
        m.start()
        muxer = m
        started = true
        // Samples that arrived while waiting for the audio format.
        pending.forEach { write(it.data, it.info, it.audio) }
        pending.clear()
    }

    @Synchronized
    override fun onSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (baseUs < 0) baseUs = info.presentationTimeUs
        if (firstVideoUs < 0) firstVideoUs = info.presentationTimeUs
        if (!started) {
            // Give up waiting for sound after two seconds and record video only.
            if (expectAudio && info.presentationTimeUs - firstVideoUs > 2_000_000) tryStart(force = true)
        }
        val bytes = ByteArray(info.size).also { data.get(it) }
        val copy = MediaCodec.BufferInfo().also { it.set(0, info.size, info.presentationTimeUs, info.flags) }
        if (started) write(bytes, copy, audio = false) else pending.add(Pending(bytes, copy, false))
    }

    @Synchronized
    override fun onAudioSample(data: ByteBuffer, info: MediaCodec.BufferInfo) {
        if (!started || audioTrack < 0 || baseUs < 0) return
        val bytes = ByteArray(info.size).also { data.get(it) }
        val copy = MediaCodec.BufferInfo().also { it.set(0, info.size, info.presentationTimeUs, info.flags) }
        write(bytes, copy, audio = true)
    }

    private fun write(bytes: ByteArray, info: MediaCodec.BufferInfo, audio: Boolean) {
        val m = muxer ?: return
        var pts = info.presentationTimeUs - baseUs
        if (pts < 0) return // audio that started before the first video frame
        // Frames released in a burst can share a timestamp; players expect strictly increasing ones.
        if (!audio) {
            if (pts <= lastVideoPts) pts = lastVideoPts + 1000
            lastVideoPts = pts
        } else {
            if (pts <= lastAudioPts) pts = lastAudioPts + 1
            lastAudioPts = pts
        }
        val out = MediaCodec.BufferInfo().also { it.set(0, bytes.size, pts, info.flags) }
        m.writeSampleData(if (audio) audioTrack else videoTrack, ByteBuffer.wrap(bytes), out)
        if (!audio) samples++
    }

    @Synchronized
    override fun onEnd() {
        val m = muxer
        muxer = null
        if (m == null) {
            if (samples == 0) output.delete() else output.close()
            return
        }
        try {
            if (samples > 0) m.stop()
        } catch (e: Exception) {
            Log.w("Mp4Recorder", "stop failed", e)
        }
        m.release()
        output.close()
        if (samples == 0) output.delete()
    }
}
