package app.ipcamhub.core

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import java.nio.ByteBuffer

/**
 * Copies the decoded sound of an ExoPlayer so it can be recorded and streamed.
 * Build the player with [renderersFactory]; [onPcm] then receives 16-bit PCM together with the
 * media time of its first sample. The audio still plays normally (mute the player to silence it).
 */
@UnstableApi
class AudioTap(private val onPcm: (pcm: ByteArray, sampleRate: Int, channels: Int, mediaTimeUs: Long) -> Unit) {

    @Volatile
    private var bufferStartUs = 0L
    private var framesSinceStart = 0L

    private val processor = object : BaseAudioProcessor() {
        private var rate = 0
        private var channels = 0

        override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
            // Anything but 16-bit PCM is left alone (the processor stays inactive).
            if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
            rate = inputAudioFormat.sampleRate
            channels = inputAudioFormat.channelCount
            return inputAudioFormat
        }

        override fun queueInput(inputBuffer: ByteBuffer) {
            val remaining = inputBuffer.remaining()
            if (remaining == 0) return
            val bytes = ByteArray(remaining)
            inputBuffer.duplicate().get(bytes)
            val mediaUs = bufferStartUs + framesSinceStart * 1_000_000L / rate
            framesSinceStart += remaining / (2L * channels)
            onPcm(bytes, rate, channels, mediaUs)
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
        }

        override fun onFlush() {
            framesSinceStart = 0
        }
    }

    fun renderersFactory(context: Context): DefaultRenderersFactory =
        object : DefaultRenderersFactory(context) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): AudioSink {
                val delegate = DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(false) // keep 16-bit PCM so the tap can read it
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(processor))
                    .build()
                return object : ForwardingAudioSink(delegate) {
                    private var lastBuffer: ByteBuffer? = null
                    private var lastPts = Long.MIN_VALUE

                    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
                        // The same buffer comes back until it is fully consumed; only a new one resets the clock.
                        if (buffer !== lastBuffer || presentationTimeUs != lastPts) {
                            lastBuffer = buffer
                            lastPts = presentationTimeUs
                            bufferStartUs = presentationTimeUs
                            framesSinceStart = 0
                        }
                        return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
                    }
                }
            }
        }
}
