package app.ipcamhub.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.rtsp.RtspMediaSource

/** Options for [PlayerFactory.createRtsp]. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
data class RtspOptions(
    /** A little more buffer, for video that feeds the encoder and must arrive at an even pace. */
    val smooth: Boolean = false,
    /** Play without sound through the speaker. */
    val muted: Boolean = false,
    /** Custom renderers, used to tap the audio. */
    val renderers: RenderersFactory? = null,
    /** Carry the video over TCP. When false the player tries UDP first and falls back to TCP. */
    val tcp: Boolean = true,
    /** Optional socket factory for the RTSP connection, used by [RtspSyncProbe]. */
    val socketFactory: javax.net.SocketFactory? = null,
)

object PlayerFactory {
    private const val TAG = "PlayerFactory"
    private const val RETRY_MS = 3000L
    private const val WATCHDOG_MS = 2000L
    private const val STALL_LIMIT_MS = 10_000L

    /**
     * An RTSP player with a tiny buffer (low latency) that reconnects by itself when the
     * connection fails, is closed by the camera, or silently stops delivering video.
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun createRtsp(
        context: Context,
        url: String,
        options: RtspOptions = RtspOptions(),
        onError: (String?) -> Unit = {},
    ): ExoPlayer {
        val loadControl = DefaultLoadControl.Builder()
            .apply {
                if (options.smooth) setBufferDurationsMs(500, 1500, 250, 500) else setBufferDurationsMs(250, 500, 100, 200)
            }
            .build()
        val builder = if (options.renderers != null) {
            ExoPlayer.Builder(context.applicationContext, options.renderers)
        } else {
            ExoPlayer.Builder(context.applicationContext)
        }
        val player = builder.setLoadControl(loadControl).build()
        if (options.muted) player.volume = 0f
        val item = MediaItem.Builder()
            .setUri(url)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(500).build())
            .build()
        fun source() = RtspMediaSource.Factory().setForceUseRtpTcp(options.tcp)
            .apply { options.socketFactory?.let { setSocketFactory(it) } }
            .createMediaSource(item)

        val main = Handler(Looper.getMainLooper())
        var reconnectPending = false

        // A fresh media source each time: prepare() alone does not restart an ended stream.
        fun reconnect() {
            if (reconnectPending) return
            reconnectPending = true
            android.util.Log.i(TAG, "reconnecting in ${RETRY_MS}ms")
            main.postDelayed({
                reconnectPending = false
                if (!player.isReleased) {
                    player.setMediaSource(source())
                    player.prepare()
                    player.playWhenReady = true
                }
            }, RETRY_MS)
        }

        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.w(TAG, "player error ${error.errorCodeName}", error)
                onError(error.errorCodeName)
                reconnect()
            }

            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    onError("ENDED")
                    reconnect()
                }
            }
        })

        // Detects connections that die without any error (for example a camera losing power).
        var lastPosition = -1L
        var stalledMs = 0L
        val watchdog = object : Runnable {
            override fun run() {
                if (player.isReleased) return
                val position = player.currentPosition
                val waiting = player.playbackState == Player.STATE_BUFFERING ||
                    (player.playbackState == Player.STATE_READY && player.isPlaying)
                if (waiting && position == lastPosition) stalledMs += WATCHDOG_MS else stalledMs = 0
                lastPosition = position
                if (stalledMs >= STALL_LIMIT_MS) {
                    stalledMs = 0
                    onError("STALLED")
                    reconnect()
                }
                main.postDelayed(this, WATCHDOG_MS)
            }
        }
        main.postDelayed(watchdog, WATCHDOG_MS)

        player.setMediaSource(source())
        player.playWhenReady = true
        player.prepare()
        return player
    }
}
