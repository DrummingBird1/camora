package app.ipcamhub.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Records the sound of a capture card. HDMI capture cards show up on Android as a separate
 * USB audio input next to the video interface, so the sound is read with [AudioRecord] and
 * handed to an [AudioEncoder].
 */
class UvcAudioSource(
    private val device: AudioDeviceInfo,
    private val sink: EncodedSink,
) {
    private var record: AudioRecord? = null
    private var encoder: AudioEncoder? = null

    @Volatile
    private var running = false
    private var thread: Thread? = null

    /** Loudest sample since the last time it was read (0..32767). */
    @Volatile
    var levelPeak = 0
        private set

    fun takeLevel(): Int = levelPeak.also { levelPeak = 0 }

    @android.annotation.SuppressLint("MissingPermission") // checked by hasPermission() before this is used
    fun start() {
        val rates = device.sampleRates.takeIf { it.isNotEmpty() }
        val rate = when {
            rates == null || 48_000 in rates -> 48_000
            44_100 in rates -> 44_100
            else -> rates.max()
        }
        val channels = if (device.channelCounts.isEmpty() || 2 in device.channelCounts) 2 else 1
        val mask = if (channels == 2) AudioFormat.CHANNEL_IN_STEREO else AudioFormat.CHANNEL_IN_MONO
        val minBuffer = AudioRecord.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            Log.w(TAG, "audio format not supported: $rate Hz, $channels channels")
            return
        }
        val chunkBytes = rate * 2 * channels / 50 // 20 ms
        val r = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(mask)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(minBuffer, chunkBytes * 8))
            .build()
        val accepted = r.setPreferredDevice(device)
        Log.i(TAG, "preferred device accepted=$accepted source=${device.productName} id=${device.id}")
        val enc = AudioEncoder(rate, channels, sink)
        record = r
        encoder = enc
        running = true
        r.startRecording()
        Log.i(TAG, "routed to ${r.routedDevice?.productName} type=${r.routedDevice?.type} state=${r.recordingState}")
        thread = Thread({
            val buf = ByteArray(chunkBytes)
            var chunks = 0
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) continue
                var peak = 0
                var i = 0
                while (i + 1 < n) {
                    val s = kotlin.math.abs(((buf[i + 1].toInt() shl 8) or (buf[i].toInt() and 0xff)).toShort().toInt())
                    if (s > peak) peak = s
                    i += 2
                }
                if (peak > levelPeak) levelPeak = peak
                if (++chunks % 50 == 0) Log.d(TAG, "card audio peak ${takeLevel()} of 32767 at $rate Hz")
                // The chunk ends now, so it began one chunk-length ago. Same clock as the video timestamps.
                val ptsUs = System.nanoTime() / 1000 - n / (2L * channels) * 1_000_000L / rate
                enc.queuePcm(buf.copyOf(n), ptsUs)
            }
        }, "uvc-audio").apply { isDaemon = true; start() }
    }

    val sampleRate: Int get() = record?.sampleRate ?: 0

    fun stop() {
        running = false
        thread?.join(500)
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        record?.release()
        record = null
        encoder?.stop()
        encoder = null
    }

    companion object {
        private const val TAG = "UvcAudioSource"

        fun hasPermission(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** The USB audio input that belongs to a capture card, if one is plugged in. */
        fun findUsbInput(context: Context): AudioDeviceInfo? {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            return am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        }
    }
}
