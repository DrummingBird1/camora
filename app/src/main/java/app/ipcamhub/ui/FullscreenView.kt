package app.ipcamhub.ui

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import app.ipcamhub.R
import app.ipcamhub.Strings
import app.ipcamhub.core.Hub
import app.ipcamhub.data.CameraConfig
import app.ipcamhub.data.CameraRepository
import app.ipcamhub.data.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One source on the whole screen, with controls that appear on a tap and hide again after a few
 * seconds: record or stop, sound, motion detection, fit or fill, snapshot, performance info, close.
 */
@Composable
fun FullscreenView(cam: CameraConfig, repo: CameraRepository, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recording by Hub.recording.collectAsState()
    val since by Hub.recordingSince.collectAsState()
    val settings by Hub.settingsRepo.settings.collectAsState(initial = Hub.settings)
    val isRecording = cam.id in recording

    var sound by remember { mutableStateOf(cam.audio && cam.type != SourceType.DEMO) }
    var fill by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var touches by remember { mutableIntStateOf(0) } // restarts the hide timer on every touch
    var fps by remember { mutableFloatStateOf(0f) }
    var texture by remember { mutableStateOf<TextureView?>(null) }

    // Hide the controls 4 seconds after the last touch.
    LaunchedEffect(controls, touches) {
        if (controls) {
            delay(4_000)
            controls = false
        }
    }
    fun touched() {
        touches++
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                controls = !controls
                touched()
            }
    ) {
        SourceView(
            cam, Modifier.fillMaxSize(), sound = sound, fill = fill,
            onTextureView = { texture = it }, onFps = { fps = it },
        ) { Hub.reportMotion(cam, it) }

        // Always visible: the recording indicator, and the stats when they are switched on.
        // Below the top bar, so it never covers the name or the close button.
        Column(Modifier.align(Alignment.TopStart).padding(start = 16.dp, end = 16.dp, top = 72.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (isRecording) RecordingBadge(since[cam.id])
            if (settings.showStats) StatsBar(cameraId = cam.id, fps = fps, overlay = true)
        }

        AnimatedVisibility(controls, Modifier.fillMaxSize(), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize()) {
                // Top: name and close.
                Row(
                    Modifier.fillMaxWidth().align(Alignment.TopCenter)
                        .background(Color(0x66000000)).padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(cam.name, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp))
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = Strings.get(R.string.close), tint = Color.White)
                    }
                }
                // Bottom: the controls.
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
                        .background(Color(0x99000000), CircleShape).padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (cam.type != SourceType.DEMO && cam.audio) {
                        Control(
                            painterResource(if (sound) R.drawable.ic_volume_on else R.drawable.ic_volume_off),
                            Strings.get(if (sound) R.string.sound_off else R.string.sound_on), active = sound
                        ) { sound = !sound; touched() }
                    }
                    Control(
                        rememberVectorPainter(Icons.Default.Notifications),
                        Strings.get(R.string.motion_detection), active = cam.motion
                    ) {
                        scope.launch { repo.update(cam.id) { it.copy(motion = !it.motion) } }
                        touched()
                    }
                    // The main action, larger: record or stop.
                    IconButton(
                        onClick = {
                            if (isRecording) Hub.stopRecording(cam.id) else Hub.startRecording(cam)
                            touched()
                        },
                        modifier = Modifier.size(64.dp).background(if (isRecording) Color.White else Color(0xFFFF3B30), CircleShape)
                    ) {
                        Icon(
                            painterResource(if (isRecording) R.drawable.ic_stop else R.drawable.ic_record),
                            contentDescription = Strings.get(if (isRecording) R.string.stop_recording else R.string.record),
                            tint = if (isRecording) Color(0xFFFF3B30) else Color.White,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Control(
                        painterResource(R.drawable.ic_fill_screen),
                        Strings.get(if (fill) R.string.fit_screen else R.string.fill_screen), active = fill
                    ) { fill = !fill; touched() }
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        Control(painterResource(R.drawable.ic_snapshot), Strings.get(R.string.snapshot), active = false) {
                            touched()
                            val bitmap = texture?.bitmap
                            scope.launch {
                                val ok = bitmap != null && withContext(Dispatchers.IO) { saveSnapshot(context, bitmap, cam.name) }
                                android.widget.Toast.makeText(
                                    context, Strings.get(if (ok) R.string.snapshot_saved else R.string.save_failed),
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    Control(painterResource(R.drawable.ic_stats), Strings.get(R.string.show_stats), active = settings.showStats) {
                        scope.launch { Hub.settingsRepo.update { it.copy(showStats = !it.showStats) } }
                        touched()
                    }
                }
            }
        }
    }
}

@Composable
private fun Control(icon: Painter, label: String, active: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(icon, contentDescription = label, tint = if (active) MaterialTheme.colorScheme.primary else Color.White)
    }
}

/** Saves the current frame as a JPEG in Pictures/Camora. Android 10 and newer. */
@androidx.annotation.RequiresApi(29)
private fun saveSnapshot(context: android.content.Context, bitmap: Bitmap, cameraName: String): Boolean {
    val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US).format(java.util.Date())
    val safe = cameraName.replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "${safe}_$stamp.jpg")
        put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Camora")
        put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.JPEG, 92, it) } ?: error("no stream")
        values.clear()
        values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        false
    } finally {
        bitmap.recycle()
    }
}
