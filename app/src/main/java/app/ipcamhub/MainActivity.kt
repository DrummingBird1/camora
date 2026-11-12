package app.ipcamhub

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import app.ipcamhub.core.Hub
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import app.ipcamhub.core.UvcAudioSource
import app.ipcamhub.core.UvcManager
import app.ipcamhub.data.SourceType
import app.ipcamhub.ui.CamerasScreen
import app.ipcamhub.ui.LibraryScreen
import app.ipcamhub.ui.SettingsScreen
import app.ipcamhub.ui.StreamScreen
import app.ipcamhub.ui.UsbScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = camoraColors()) {
                AppRoot()
            }
        }
        if (savedInstanceState == null) handleAction(intent)
        if (app.ipcamhub.core.Feedback.takeUnseenCrash(this)) {
            Hub.showNotice(Strings.get(R.string.crash_notice))
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleAction(intent)
    }

    /** Launcher shortcuts and the quick settings tile. The app is visible here, so recording may start. */
    private fun handleAction(intent: android.content.Intent?) {
        when (intent?.action) {
            ACTION_RECORD_ALL -> {
                app.ipcamhub.ui.UiState.openTab.value = 0
                // On a cold start the saved sources may still be loading.
                lifecycleScope.launch {
                    Hub.repo.cameras.first().forEach { Hub.startRecording(it) }
                }
            }
            ACTION_STOP_ALL -> Hub.stopRecordingAll()
            ACTION_OPEN_STREAM -> app.ipcamhub.ui.UiState.openTab.value = 1
        }
    }

    companion object {
        const val ACTION_RECORD_ALL = "app.ipcamhub.action.RECORD_ALL"
        const val ACTION_STOP_ALL = "app.ipcamhub.action.STOP_ALL"
        const val ACTION_OPEN_STREAM = "app.ipcamhub.action.OPEN_STREAM"
    }
}

/** Dark theme in the Camora colours (lavender accent on near-black). */
private fun camoraColors() = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFFB8A4FF),
    onPrimary = androidx.compose.ui.graphics.Color(0xFF1B1B22),
    primaryContainer = androidx.compose.ui.graphics.Color(0xFF3D3366),
    onPrimaryContainer = androidx.compose.ui.graphics.Color(0xFFE6DEFF),
    secondaryContainer = androidx.compose.ui.graphics.Color(0xFF3D3366),
    onSecondaryContainer = androidx.compose.ui.graphics.Color(0xFFE6DEFF),
    background = androidx.compose.ui.graphics.Color(0xFF1B1B22),
    surface = androidx.compose.ui.graphics.Color(0xFF1B1B22),
    surfaceContainer = androidx.compose.ui.graphics.Color(0xFF24242E),
    surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF2A2A36),
    surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF30303D),
)

private fun tabs() = listOf(Strings.get(R.string.tab_cameras), Strings.get(R.string.tab_stream), Strings.get(R.string.tab_recordings), Strings.get(R.string.tab_settings))

@Composable
private fun AppRoot() {
    var tab by remember { mutableIntStateOf(0) }
    var showUsb by remember { mutableStateOf(false) }
    var showLicenses by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val notice by Hub.notice.collectAsState()
    val fullscreen by app.ipcamhub.ui.UiState.fullscreen.collectAsState()
    val openTab by app.ipcamhub.ui.UiState.openTab.collectAsState()
    LaunchedEffect(openTab) {
        openTab?.let {
            tab = it
            showUsb = false
            showLicenses = false
            app.ipcamhub.ui.UiState.openTab.value = null
        }
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    // A capture card's sound is read through the microphone API, so that permission is needed once.
    val context = LocalContext.current
    val cameras by Hub.repo.cameras.collectAsState(initial = emptyList())
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    var askedMic by rememberSaveable { mutableStateOf(false) }
    // Android only lets an app open a capture card (a USB video device) if it holds the CAMERA permission.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) UvcManager.retry()
    }
    LaunchedEffect(cameras) {
        val hasUvc = cameras.any { it.type == SourceType.UVC }
        if (hasUvc && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
    LaunchedEffect(cameras) {
        val needsMic = cameras.any { it.type == SourceType.UVC && it.audio }
        if (needsMic && !askedMic && !UvcAudioSource.hasPermission(context)) {
            askedMic = true
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(notice) {
        notice?.let {
            snackbar.showSnackbar(it)
            Hub.dismissNotice()
        }
    }
    BackHandler(enabled = showUsb || showLicenses) { showUsb = false; showLicenses = false }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (!fullscreen) NavigationBar {
                val icons = listOf(
                    androidx.compose.material.icons.Icons.Default.Home,
                    androidx.compose.material.icons.Icons.Default.Share,
                    androidx.compose.material.icons.Icons.Default.PlayArrow,
                    androidx.compose.material.icons.Icons.Default.Settings,
                )
                tabs().forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i; showUsb = false; showLicenses = false },
                        label = { Text(label, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
                        icon = { androidx.compose.material3.Icon(icons[i], contentDescription = null) }
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                tab == 0 -> CamerasScreen(Hub.repo)
                tab == 1 -> StreamScreen(Hub.repo)
                tab == 2 -> LibraryScreen()
                showUsb -> UsbScreen()
                showLicenses -> app.ipcamhub.ui.LicensesScreen()
                else -> SettingsScreen(onOpenUsb = { showUsb = true }, onOpenLicenses = { showLicenses = true })
            }
        }
    }
}
