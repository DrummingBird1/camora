package app.ipcamhub.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Something that produces video (and sound) and renders it into encoders. */
interface EncodeSource {
    /** Starts producing video. [sink] receives the encoded H.264 and AAC. */
    fun start(sink: EncodedSink)

    fun stop()

    /** Asks for a key frame so a new viewer can start immediately. */
    fun requestKeyFrame()
}

/** Opens its own RTSP connection, decodes it, and renders into an encoder. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class RtspEncodeSource(
    private val context: Context,
    private val url: String,
    private val quality: Quality = Quality(),
    private val audio: Boolean = true,
    private val tcp: Boolean = true,
) : EncodeSource {
    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null

    @Volatile
    private var encoder: VideoEncoder? = null

    @Volatile
    private var audioEncoder: AudioEncoder? = null
    private var stopped = false
    private val syncProbe = RtspSyncProbe()

    // Pairs a media time with the wall-clock time the video frame is shown, to timestamp sound.
    @Volatile
    private var frameMediaUs = UNSET

    @Volatile
    private var frameReleaseNs = UNSET

    override fun start(sink: EncodedSink) {
        main.post {
            if (stopped) return@post
            val tap = if (audio) AudioTap { pcm, rate, channels, mediaUs -> onPcm(sink, pcm, rate, channels, mediaUs) } else null
            val p = PlayerFactory.createRtsp(
                context, url,
                RtspOptions(
                    smooth = true, muted = true, renderers = tap?.renderersFactory(context), tcp = tcp,
                    socketFactory = syncProbe.socketFactory
                )
            )
            player = p
            if (audio) {
                p.setVideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
                    frameMediaUs = presentationTimeUs
                    frameReleaseNs = releaseTimeNs
                }
            }
            p.addListener(object : Player.Listener {
                override fun onTracksChanged(tracks: Tracks) {
                    if (encoder != null) return
                    val hasAudio = audio && tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
                    sink.onTracks(hasAudio)
                    val format = tracks.groups
                        .firstOrNull { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
                        ?.getTrackFormat(0)
                    val w = format?.width?.takeIf { it > 0 } ?: 1920
                    val h = format?.height?.takeIf { it > 0 } ?: 1080
                    val fps = format?.frameRate?.takeIf { it > 0 }?.toInt() ?: 30
                    android.util.Log.i("RtspEncodeSource", "tracks: ${w}x$h@$fps audio=$hasAudio, starting encoder")
                    val e = VideoEncoder(w, h, fps, sink, quality)
                    encoder = e
                    p.setVideoSurface(e.inputSurface)
                }
            })
        }
    }

    /** Runs on the player's audio thread. */
    private fun onPcm(sink: EncodedSink, pcm: ByteArray, rate: Int, channels: Int, mediaUs: Long) {
        val releaseNs = frameReleaseNs
        val frameUs = frameMediaUs
        if (releaseNs == UNSET || frameUs == UNSET || channels !in 1..2 || stopped) return
        // ExoPlayer adds a fixed 1e12 microseconds to the clocks inside its renderers, but not to the
        // frame metadata. Remove it so sound lines up with the video frame that is on screen.
        var delta = mediaUs - frameUs
        if (delta > RENDERER_OFFSET_US / 2) delta -= RENDERER_OFFSET_US
        // Media3 starts the video clock at the first video packet but the sound at the RTP-Info time;
        // move the video onto the sound's timeline (see RtspSyncProbe).
        delta -= syncProbe.videoOffsetUs
        val wallUs = releaseNs / 1000 + delta
        val enc = audioEncoder ?: AudioEncoder(rate, channels, sink).also { audioEncoder = it }
        enc.queuePcm(pcm, wallUs)
    }

    override fun requestKeyFrame() {
        encoder?.requestKeyFrame()
    }

    override fun stop() {
        main.post {
            stopped = true
            player?.release()
            player = null
            audioEncoder?.stop()
            audioEncoder = null
            encoder?.stop()
            encoder = null
        }
    }

    private companion object {
        const val UNSET = Long.MIN_VALUE
        const val RENDERER_OFFSET_US = 1_000_000_000_000L
    }
}

/** The built-in demo picture ([DemoScene]) with a short beep every second, for trying the app without a camera. */
class DemoEncodeSource(
    private val quality: Quality = Quality(),
    private val audio: Boolean = true,
) : EncodeSource {
    @Volatile
    private var encoder: VideoEncoder? = null

    @Volatile
    private var running = false
    private var audioThread: Thread? = null

    override fun start(sink: EncodedSink) {
        running = true
        sink.onTracks(audio)
        val e = VideoEncoder(DemoScene.WIDTH, DemoScene.HEIGHT, DemoScene.FPS, sink, quality)
        encoder = e
        DemoScene.addSurface(e.inputSurface)
        if (audio) {
            val enc = AudioEncoder(RATE, 1, sink)
            audioThread = Thread({
                val chunk = RATE / 50 // 20 ms
                val startUs = System.nanoTime() / 1000
                var sent = 0L
                try {
                    while (running) {
                        val pcm = ByteArray(chunk * 2)
                        for (i in 0 until chunk) {
                            val n = sent + i
                            // 880 Hz for the first 80 ms of every second.
                            val v = if (n % RATE < RATE * 8 / 100) (Math.sin(2 * Math.PI * 880 * n / RATE) * 6000).toInt() else 0
                            pcm[2 * i] = (v and 0xff).toByte()
                            pcm[2 * i + 1] = (v shr 8).toByte()
                        }
                        val ptsUs = startUs + sent * 1_000_000L / RATE
                        enc.queuePcm(pcm, ptsUs)
                        sent += chunk
                        // Stay in step with the wall clock, like a real sound card.
                        val aheadUs = startUs + sent * 1_000_000L / RATE - System.nanoTime() / 1000
                        if (aheadUs > 0) Thread.sleep(aheadUs / 1000, ((aheadUs % 1000) * 1000).toInt())
                    }
                } catch (_: InterruptedException) {
                } finally {
                    enc.stop()
                }
            }, "demo-audio").apply { isDaemon = true; start() }
        }
    }

    override fun requestKeyFrame() {
        encoder?.requestKeyFrame()
    }

    override fun stop() {
        running = false
        audioThread?.interrupt()
        audioThread = null
        val e = encoder ?: return
        encoder = null
        DemoScene.removeSurface(e.inputSurface)
        Handler(Looper.getMainLooper()).postDelayed({ e.stop() }, 300)
    }

    private companion object {
        const val RATE = 48_000
    }
}

/** Renders the shared UVC capture device into an encoder. */
class UvcEncodeSource(
    private val context: Context,
    private val quality: Quality = Quality(),
    private val audio: Boolean = true,
) : EncodeSource {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var audioSource: UvcAudioSource? = null

    @Volatile
    private var encoder: VideoEncoder? = null

    override fun start(sink: EncodedSink) {
        UvcManager.start(context)
        job = scope.launch {
            val s = UvcManager.state.first { it.opened && it.width > 0 }
            // The capture card's sound is a separate USB audio device; it needs the microphone permission.
            val audioDevice = if (audio && UvcAudioSource.hasPermission(context)) UvcAudioSource.findUsbInput(context) else null
            sink.onTracks(audioDevice != null)
            val e = VideoEncoder(s.width, s.height, s.fps.takeIf { it > 0 } ?: 30, sink, quality)
            encoder = e
            UvcManager.addSurface(e.inputSurface)
            if (audioDevice != null) {
                val a = UvcAudioSource(context, sink)
                if (a.start()) audioSource = a else android.util.Log.w("UvcEncodeSource", "audio capture failed")
            }
        }
    }

    override fun requestKeyFrame() {
        encoder?.requestKeyFrame()
    }

    override fun stop() {
        job?.cancel()
        audioSource?.stop()
        audioSource = null
        val e = encoder
        encoder = null
        if (e != null) {
            UvcManager.removeSurface(e.inputSurface)
            // Give the UVC renderer a moment to detach before the surface is released.
            main.postDelayed({ e.stop() }, 300)
        }
        scope.cancel()
    }
}
