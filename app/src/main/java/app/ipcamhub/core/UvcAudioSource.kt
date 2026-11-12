package app.ipcamhub.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.content.ContextCompat

/**
 * Records the sound of a capture card. HDMI capture cards show up on Android as a separate USB audio
 * input next to the video interface; [CardAudio] captures it once and this hands the sound to an
 * [AudioEncoder] for recording and streaming, while the speaker can listen to the same capture.
 */
class UvcAudioSource(
    private val context: Context,
    private val sink: EncodedSink,
) : CardAudio.Listener {
    private var encoder: AudioEncoder? = null

    fun start(): Boolean = CardAudio.add(context, this)

    override fun onPcm(pcm: ByteArray, size: Int, rate: Int, channels: Int, ptsUs: Long) {
        val enc = encoder ?: AudioEncoder(rate, channels, sink).also { encoder = it }
        enc.queuePcm(pcm.copyOf(size), ptsUs)
    }

    fun stop() {
        CardAudio.remove(this)
        encoder?.stop()
        encoder = null
    }

    companion object {
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
