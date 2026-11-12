package app.ipcamhub.ui

import app.ipcamhub.R
import app.ipcamhub.Strings

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.ipcamhub.core.Hub
import app.ipcamhub.core.Onvif
import app.ipcamhub.core.OnvifDevice
import app.ipcamhub.core.UvcManager
import app.ipcamhub.data.CameraConfig
import app.ipcamhub.data.CameraRepository
import app.ipcamhub.data.SourceType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Addresses, user names and passwords read left to right even in a right-to-left language. */
private val LTR_FIELD = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr)

@Composable
fun CamerasScreen(repo: CameraRepository) {
    val cameras by repo.cameras.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var showAdd by remember { mutableStateOf(false) }
    var addType by remember { mutableStateOf(SourceType.RTSP) }
    var fullscreenId by remember { mutableStateOf<String?>(null) }
    // Avoid showing the welcome card for a moment while the saved list is still loading.
    val loaded by repo.cameras.collectAsState(initial = null)

    val fullscreen = cameras.firstOrNull { it.id == fullscreenId }
    if (fullscreen != null) {
        ImmersiveWhileShown()
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            SourceView(fullscreen, Modifier.fillMaxSize(), sound = true) { Hub.reportMotion(fullscreen, it) }
            IconButton(onClick = { fullscreenId = null }, modifier = Modifier.align(Alignment.TopEnd)) {
                Icon(Icons.Default.Close, contentDescription = Strings.get(R.string.close), tint = Color.White)
            }
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        if (loaded != null && cameras.isEmpty()) {
            WelcomeCard(
                onIpCamera = { addType = SourceType.RTSP; showAdd = true },
                onCaptureCard = { addType = SourceType.UVC; showAdd = true },
                onDemo = { scope.launch { repo.add(Strings.get(R.string.demo_name), SourceType.DEMO, "") } },
                modifier = Modifier.align(Alignment.Center)
            )
        }
        // Only when there is something to show: an empty grid on top would swallow taps on the welcome card.
        if (cameras.isNotEmpty()) LazyVerticalGrid(
            columns = GridCells.Adaptive(320.dp),
            // Room at the bottom so the + button never covers the last camera's controls.
            contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(cameras, key = { it.id }) { cam ->
                CameraTile(cam, repo, onOpen = { fullscreenId = cam.id })
            }
        }
        if (cameras.isNotEmpty()) FloatingActionButton(
            onClick = { addType = SourceType.RTSP; showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
        ) { Icon(Icons.Default.Add, contentDescription = Strings.get(R.string.add)) }
    }

    if (showAdd) {
        AddCameraDialog(
            initialType = addType,
            onDismiss = { showAdd = false },
            onSave = { name, type, url ->
                scope.launch { repo.add(name, type, url) }
                showAdd = false
            }
        )
    }
}

/** Full-screen view for mirroring the phone to a TV: screen stays on and the system bars are hidden. */
@Composable
private fun ImmersiveWhileShown() {
    val activity = androidx.activity.compose.LocalActivity.current
    androidx.compose.runtime.DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, it.decorView) }
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        UiState.fullscreen.value = true
        controller?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            UiState.fullscreen.value = false
            controller?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CameraTile(cam: CameraConfig, repo: CameraRepository, onOpen: () -> Unit) {
    val scope = rememberCoroutineScope()
    val recording by Hub.recording.collectAsState()
    val viewers by Hub.viewers.collectAsState()
    val events by Hub.events.collectAsState()
    val recordingSince by Hub.recordingSince.collectAsState()
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    val isRecording = cam.id in recording
    val viewerCount = viewers[cam.id] ?: 0
    var showSettings by remember { mutableStateOf(false) }
    var soundOn by remember(cam.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (confirmDelete) {
        ConfirmDeleteDialog(
            Strings.get(R.string.confirm_delete_camera, cam.name),
            onConfirm = { scope.launch { repo.remove(cam.id) } },
            onDismiss = { confirmDelete = false },
        )
    }
    val uvcModes by UvcManager.modes.collectAsState()
    val uvcState by UvcManager.state.collectAsState()
    val measured by UvcManager.measuredFps.collectAsState()
    val uvcActive = if (uvcState.opened) {
        "${uvcState.width}x${uvcState.height}@${uvcState.fps}" + Strings.get(R.string.measured_fps, "%.0f".format(measured))
    } else ""
    val lastMotion = events.firstOrNull { it.cameraId == cam.id }?.timeMs
    var motionShown by remember { mutableStateOf(false) }
    LaunchedEffect(lastMotion) {
        if (lastMotion != null && System.currentTimeMillis() - lastMotion < 5000) {
            motionShown = true
            delay(5000)
            motionShown = false
        }
    }

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onOpen)
        ) {
            SourceView(cam, Modifier.fillMaxSize(), sound = soundOn, showFps = true) { Hub.reportMotion(cam, it) }
            Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isRecording) RecordingBadge(recordingSince[cam.id])
                if (motionShown) Badge(Strings.get(R.string.badge_motion), Color(0xFFF57C00))
                if (viewerCount > 0) Badge(Strings.get(R.string.badge_viewers, viewerCount), Color(0xFF1976D2))
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                cam.name,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f)
            )
            // The demo has no sound in the preview; a camera with sound turned off in its settings neither.
            if (cam.type != SourceType.DEMO && cam.audio) {
                IconButton(onClick = { soundOn = !soundOn }) {
                    Icon(
                        androidx.compose.ui.res.painterResource(if (soundOn) R.drawable.ic_volume_on else R.drawable.ic_volume_off),
                        contentDescription = Strings.get(if (soundOn) R.string.sound_off else R.string.sound_on),
                        tint = if (soundOn) MaterialTheme.colorScheme.primary else androidx.compose.material3.LocalContentColor.current
                    )
                }
            }
            IconButton(onClick = { showSettings = true }) {
                Icon(Icons.Default.Settings, contentDescription = Strings.get(R.string.camera_settings))
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = Strings.get(R.string.delete))
            }
        }
        if (showSettings) {
            CameraSettingsDialog(
                cam = cam,
                uvcModes = uvcModes,
                uvcActive = uvcActive,
                onChange = { updated -> scope.launch { repo.update(cam.id) { updated } } },
                onDismiss = { showSettings = false }
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = isRecording,
                onClick = {
                    haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    if (isRecording) Hub.stopRecording(cam.id) else Hub.startRecording(cam)
                },
                label = { Text(if (isRecording) Strings.get(R.string.stop_recording) else Strings.get(R.string.record)) }
            )
            FilterChip(
                selected = cam.motion,
                onClick = { scope.launch { repo.update(cam.id) { it.copy(motion = !it.motion) } } },
                label = { Text(Strings.get(R.string.motion_detection)) }
            )
            FilterChip(
                selected = cam.autoRecord,
                onClick = { scope.launch { repo.update(cam.id) { it.copy(autoRecord = !it.autoRecord, motion = true) } } },
                label = { Text(Strings.get(R.string.record_on_motion)) }
            )
            FilterChip(
                selected = cam.shared,
                onClick = { scope.launch { repo.update(cam.id) { it.copy(shared = !it.shared) } } },
                label = { Text(Strings.get(R.string.share_to_tv)) }
            )
        }
    }
}

/** A pulsing red dot with the recording time, for example "● REC 02:15". */
@Composable
private fun RecordingBadge(since: Long?) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(since) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "rec")
    val alpha by pulse.animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse
        ),
        label = "dot"
    )
    // Dot, "REC" and the time keep this order in right-to-left languages too.
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Ltr
    ) { RecordingBadgeContent(seconds = if (since == null) 0 else ((now - since) / 1000).coerceAtLeast(0), alpha = alpha) }
}

@Composable
private fun RecordingBadgeContent(seconds: Long, alpha: Float) {
    val time = if (seconds >= 3600) "%d:%02d:%02d".format(java.util.Locale.US, seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%02d:%02d".format(java.util.Locale.US, seconds / 60, seconds % 60)
    Row(
        Modifier.background(Color(0xCC000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            Modifier
                .padding(top = 1.dp)
                .size(9.dp)
                .background(Color(0xFFFF3B30).copy(alpha = alpha), androidx.compose.foundation.shape.CircleShape)
        )
        // Digits and "REC" read left to right in every language.
        Text(
            "REC $time", color = Color.White,
            style = MaterialTheme.typography.labelMedium.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr)
        )
    }
}

/** First-run card: what to add first, or try the built-in demo. */
@Composable
private fun WelcomeCard(onIpCamera: () -> Unit, onCaptureCard: () -> Unit, onDemo: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.Card(
        modifier.padding(24.dp).widthIn(max = 460.dp)
    ) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // The launcher icon is an adaptive icon, which Compose cannot draw; use its vector layer on the brand colour.
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(androidx.compose.ui.res.colorResource(R.color.ic_launcher_background))
            )
            Text(Strings.get(R.string.welcome_title), style = MaterialTheme.typography.titleLarge)
            Text(
                Strings.get(R.string.welcome_text),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            androidx.compose.material3.Button(onClick = onIpCamera, modifier = Modifier.fillMaxWidth()) {
                Text(Strings.get(R.string.welcome_add_ip))
            }
            androidx.compose.material3.Button(onClick = onCaptureCard, modifier = Modifier.fillMaxWidth()) {
                Text(Strings.get(R.string.welcome_add_card))
            }
            androidx.compose.material3.OutlinedButton(onClick = onDemo, modifier = Modifier.fillMaxWidth()) {
                Text(Strings.get(R.string.welcome_demo))
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.background(color, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun AddCameraDialog(initialType: SourceType, onDismiss: () -> Unit, onSave: (String, SourceType, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(initialType) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("rtsp://") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<OnvifDevice>?>(null) }
    var selected by remember { mutableStateOf<OnvifDevice?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    val valid = name.isNotBlank() && (type == SourceType.UVC || (url.startsWith("rtsp://") && url.length > 7))

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.get(R.string.add_source)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(type == SourceType.RTSP, { type = SourceType.RTSP }, label = { Text(Strings.get(R.string.source_ip_camera)) })
                    FilterChip(type == SourceType.UVC, { type = SourceType.UVC }, label = { Text(Strings.get(R.string.source_capture_card)) })
                }
                LaunchedEffect(type) { if (name.isEmpty() && type == SourceType.UVC) name = Strings.get(R.string.source_capture_card) }
                OutlinedTextField(name, { name = it }, label = { Text(Strings.get(R.string.name)) }, singleLine = true)
                if (type == SourceType.RTSP) {
                    OutlinedTextField(
                        url, { url = it }, label = { Text(Strings.get(R.string.rtsp_url)) }, singleLine = true,
                        // An address: left to right, no auto-correct or automatic spaces from the keyboard.
                        textStyle = LTR_FIELD,
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri, autoCorrectEnabled = false
                        )
                    )
                    TextButton(
                        enabled = !scanning,
                        onClick = {
                            scanning = true
                            note = null
                            scope.launch {
                                found = runCatching { Onvif.discover(context) }.getOrDefault(emptyList())
                                scanning = false
                                if (found.isNullOrEmpty()) note = Strings.get(R.string.no_onvif_found)
                            }
                        }
                    ) { Text(if (scanning) Strings.get(R.string.searching) else Strings.get(R.string.find_cameras)) }
                    found?.forEach { d ->
                        TextButton(onClick = {
                            selected = d
                            if (name.isBlank()) name = d.name
                            url = "rtsp://${d.host}:554/"
                        }) { Text("${d.name} (${d.host})") }
                    }
                    if (selected != null) {
                        OutlinedTextField(
                            user, { user = it }, label = { Text(Strings.get(R.string.username)) }, singleLine = true, textStyle = LTR_FIELD,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(autoCorrectEnabled = false)
                        )
                        OutlinedTextField(
                            password, { password = it }, label = { Text(Strings.get(R.string.password)) }, singleLine = true, textStyle = LTR_FIELD,
                            visualTransformation = PasswordVisualTransformation()
                        )
                        TextButton(onClick = {
                            val d = selected ?: return@TextButton
                            note = Strings.get(R.string.requesting_url)
                            scope.launch {
                                val uri = Onvif.streamUri(d, user, password)
                                if (uri != null) {
                                    url = uri
                                    note = Strings.get(R.string.url_received)
                                } else {
                                    note = Strings.get(R.string.url_failed)
                                }
                            }
                        }) { Text(Strings.get(R.string.get_url_from_camera)) }
                    }
                    note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                } else {
                    Text(Strings.get(R.string.otg_hint), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onSave(name.trim(), type, if (type == SourceType.UVC) "" else url.filterNot { it.isWhitespace() }) }
            ) { Text(Strings.get(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.get(R.string.cancel)) } }
    )
}
