package app.ipcamhub.core

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One capture of the capture card's sound (a USB audio input), shared by everything that needs it:
 * the recorder/streamer ([UvcAudioSource]) and the phone speaker ([CardAudioMonitor]). Opening the same
 * USB input twice is unreliable, so the capture runs while at least one listener is attached.
 */
object CardAudio {
    private const val TAG = "CardAudio"

    fun interface Listener {
        /** Called on the capture thread with 16-bit PCM. [ptsUs] uses the same clock as video frames. */
        fun onPcm(pcm: ByteArray, size: Int, rate: Int, channels: Int, ptsUs: Long)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** Loudest sample since the last time it was read (0..32767). */
    @Volatile
    var levelPeak = 0
        private set

    /** Starts delivering sound to [listener]. False when there is no card audio or no permission. */
    @Synchronized
    fun add(context: Context, listener: Listener): Boolean {
        if (!UvcAudioSource.hasPermission(context)) return false
        if (record == null) {
            val device = UvcAudioSource.findUsbInput(context) ?: return false
            if (!open(device)) return false
        }
        listeners.addIfAbsent(listener)
        return true
    }

    @Synchronized
    fun remove(listener: Listener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) close()
    }

    @android.annotation.SuppressLint("MissingPermission") // checked in add()
    private fun open(device: AudioDeviceInfo): Boolean {
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
            return false
        }
        val chunkBytes = rate * 2 * channels / 50 // 20 ms
        val r = try {
            AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(
                    AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(rate).setChannelMask(mask).build()
                )
                .setBufferSizeInBytes(maxOf(minBuffer, chunkBytes * 8))
                .build()
        } catch (e: Exception) {
            Log.w(TAG, "cannot open the card's audio input", e)
            return false
        }
        r.setPreferredDevice(device)
        r.startRecording()
        Log.i(TAG, "capturing ${device.productName} at $rate Hz, $channels channels, routed to ${r.routedDevice?.productName}")
        record = r
        running = true
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
                if (++chunks % 250 == 0) Log.d(TAG, "card audio peak ${takeLevel()} of 32767")
                // The chunk ends now, so it began one chunk-length ago. Same clock as the video timestamps.
                val ptsUs = System.nanoTime() / 1000 - n / (2L * channels) * 1_000_000L / rate
                for (l in listeners) l.onPcm(buf, n, rate, channels, ptsUs)
            }
        }, "card-audio").apply { isDaemon = true; start() }
        return true
    }

    fun takeLevel(): Int = levelPeak.also { levelPeak = 0 }

    private fun close() {
        running = false
        thread?.join(500)
        thread = null
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        record?.release()
        record = null
        Log.i(TAG, "capture stopped")
    }
}

/** Plays the capture card's sound on the phone's speaker or headphones, with as little delay as possible. */
class CardAudioMonitor(private val context: Context) : CardAudio.Listener {
    private var track: AudioTrack? = null

    fun start(): Boolean = CardAudio.add(context, this)

    fun stop() {
        CardAudio.remove(this)
        track?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        track = null
    }

    override fun onPcm(pcm: ByteArray, size: Int, rate: Int, channels: Int, ptsUs: Long) {
        val t = track ?: build(rate, channels).also { track = it }
        // Never block the shared capture thread: drop what does not fit (it only happens when the speaker stalls).
        t.write(pcm, 0, size, AudioTrack.WRITE_NON_BLOCKING)
    }

    private fun build(rate: Int, channels: Int): AudioTrack {
        val mask = if (channels == 2) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val min = AudioTrack.getMinBufferSize(rate, mask, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()
            )
            .setAudioFormat(
                AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate).setChannelMask(mask).build()
            )
            .setBufferSizeInBytes(maxOf(min, rate * 2 * channels / 10)) // about 100 ms
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .also { it.play() }
    }
}
