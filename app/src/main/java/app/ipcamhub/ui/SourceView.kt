package app.ipcamhub.ui

import app.ipcamhub.R
import app.ipcamhub.Strings

import android.graphics.SurfaceTexture
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import app.ipcamhub.core.DemoScene
import app.ipcamhub.core.MotionDetector
import app.ipcamhub.core.MotionMath
import app.ipcamhub.core.PlayerFactory
import app.ipcamhub.core.RtspOptions
import app.ipcamhub.core.UvcManager
import app.ipcamhub.data.CameraConfig
import app.ipcamhub.data.SourceType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive


/** Live preview of one camera (RTSP, capture card or demo) with optional motion detection. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
fun SourceView(
    cam: CameraConfig,
    modifier: Modifier = Modifier,
    sound: Boolean = false,
    showFps: Boolean = false,
    onMotion: (Double) -> Unit = {},
) {
    val context = LocalContext.current
    val tv = remember(cam.id) { TextureView(context) }
    var status by remember(cam.id) { mutableStateOf<String?>(Strings.get(R.string.connecting)) }
    var ratio by remember(cam.id) { mutableFloatStateOf(16f / 9f) }

    // Frames shown in the last second, for the small frame-rate label.
    var fps by remember(cam.id) { mutableFloatStateOf(0f) }

    if (cam.type == SourceType.DEMO) {
        DisposableEffect(cam.id) {
            var surface: android.view.Surface? = null
            fun attach(st: SurfaceTexture) {
                st.setDefaultBufferSize(DemoScene.WIDTH, DemoScene.HEIGHT)
                surface = android.view.Surface(st).also { DemoScene.addSurface(it) }
                status = null
            }
            fun detach() {
                surface?.let { DemoScene.removeSurface(it); it.release() }
                surface = null
            }
            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = attach(st)
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    detach()
                    return true
                }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
            if (tv.isAvailable) tv.surfaceTexture?.let { attach(it) }
            fps = DemoScene.FPS.toFloat()
            onDispose {
                detach()
                tv.surfaceTextureListener = null
            }
        }
    } else if (cam.type == SourceType.RTSP) {
        DisposableEffect(cam.id, cam.url, sound) {
            var rendered = false
            var frames = 0
            var windowStartNs = System.nanoTime()
            val player = PlayerFactory.createRtsp(context, cam.url, RtspOptions(muted = !sound, tcp = cam.tcp), onError = { err ->
                if (err != null) {
                    rendered = false
                    status = Strings.get(R.string.error_with, err)
                }
            })
            val listener = object : Player.Listener {
                override fun onVideoSizeChanged(size: VideoSize) {
                    if (size.width > 0 && size.height > 0) {
                        ratio = size.width * size.pixelWidthHeightRatio / size.height
                    }
                }

                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_BUFFERING && !player.isPlaying && !rendered) status = Strings.get(R.string.connecting)
                }

                override fun onRenderedFirstFrame() {
                    rendered = true
                    status = null
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) status = null
                }
            }
            player.addListener(listener)
            player.setVideoFrameMetadataListener { _, _, _, _ ->
                frames++
                val now = System.nanoTime()
                if (now - windowStartNs >= 1_000_000_000L) {
                    val value = frames * 1e9f / (now - windowStartNs)
                    frames = 0
                    windowStartNs = now
                    tv.post { fps = value }
                }
            }
            player.setVideoTextureView(tv)
            onDispose { player.release() }
        }
    } else {
        val uvc by UvcManager.state.collectAsState()
        LaunchedEffect(cam.uvcSize) { UvcManager.setPreferredMode(cam.uvcSize) }
        DisposableEffect(cam.id) {
            UvcManager.start(context)
            tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = UvcManager.setPreview(st)
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                    UvcManager.setPreview(null)
                    return true
                }

                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
            if (tv.isAvailable) UvcManager.setPreview(tv.surfaceTexture)
            onDispose {
                UvcManager.setPreview(null)
                tv.surfaceTextureListener = null
            }
        }
        LaunchedEffect(uvc) {
            status = if (uvc.opened) null else uvc.message
            if (uvc.opened && uvc.height > 0) ratio = uvc.width.toFloat() / uvc.height
        }
        val measured by UvcManager.measuredFps.collectAsState()
        LaunchedEffect(measured, uvc.opened) { fps = if (uvc.opened) measured.toFloat() else 0f }
    }

    val motionCallback by rememberUpdatedState(onMotion)
    LaunchedEffect(cam.motion, cam.sensitivity, tv) {
        if (!cam.motion) return@LaunchedEffect
        val detector = MotionDetector(cam.sensitivity)
        val pixels = IntArray(MotionMath.GRID_COLS * MotionMath.GRID_ROWS)
        while (isActive) {
            delay(400)
            if (!tv.isAvailable) continue
            val bitmap = tv.getBitmap(MotionMath.GRID_COLS, MotionMath.GRID_ROWS) ?: continue
            bitmap.getPixels(pixels, 0, MotionMath.GRID_COLS, 0, 0, MotionMath.GRID_COLS, MotionMath.GRID_ROWS)
            bitmap.recycle()
            detector.feed(MotionMath.toLuma(pixels), System.currentTimeMillis())?.let { motionCallback(it) }
        }
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        AndroidView(factory = { tv }, modifier = Modifier.aspectRatio(ratio))
        status?.let { Text(it, color = Color.White) }
        if (showFps && status == null && fps > 0f) {
            Text(
                "%.0f fps".format(java.util.Locale.US, fps),
                color = Color.White,
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .background(Color(0x99000000), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}
