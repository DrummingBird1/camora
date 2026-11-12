package app.ipcamhub.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import app.ipcamhub.data.CameraConfig
import app.ipcamhub.data.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Watches one camera for motion while the app is in the background. The video is decoded into a
 * GPU texture and a tiny brightness thumbnail is examined a few times per second.
 */
class MotionMonitor(
    private val context: Context,
    private val camera: CameraConfig,
    private val onMotion: (Double) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val detector = MotionDetector(camera.sensitivity)

    private var player: ExoPlayer? = null
    private var sampler: GlFrameSampler? = null
    private var job: Job? = null
    private var stopped = false

    fun start() {
        when (camera.type) {
            SourceType.RTSP -> startRtsp()
            SourceType.UVC -> startUvc()
            SourceType.DEMO -> main.post {
                if (stopped) return@post
                val s = newSampler()
                sampler = s
                DemoScene.addSurface(s.start())
            }
        }
    }

    private var samples = 0L

    private fun newSampler() = GlFrameSampler { luma ->
        if (++samples % 100 == 1L) android.util.Log.d("MotionMonitor", "${camera.name}: $samples frames sampled in the background")
        detector.feed(luma, System.currentTimeMillis())?.let { score -> main.post { if (!stopped) onMotion(score) } }
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun startRtsp() {
        main.post {
            if (stopped) return@post
            val s = newSampler()
            sampler = s
            val p = PlayerFactory.createRtsp(context, camera.url, RtspOptions(muted = true, tcp = camera.tcp))
            player = p
            p.setVideoSurface(s.start())
        }
    }

    private fun startUvc() {
        UvcManager.start(context)
        job = scope.launch {
            UvcManager.state.first { it.opened && it.width > 0 }
            val s = newSampler()
            sampler = s
            UvcManager.addSurface(s.start())
        }
    }

    fun stop() {
        main.post {
            stopped = true
            job?.cancel()
            scope.cancel()
            player?.release()
            player = null
            sampler?.let { s ->
                if (camera.type == SourceType.UVC) s.surface?.let { UvcManager.removeSurface(it) }
                if (camera.type == SourceType.DEMO) s.surface?.let { DemoScene.removeSurface(it) }
                // Let in-flight frames drain before the GPU objects go away.
                main.postDelayed({ s.release() }, 300)
            }
            sampler = null
        }
    }
}
