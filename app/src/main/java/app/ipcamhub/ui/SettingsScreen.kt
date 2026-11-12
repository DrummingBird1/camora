package app.ipcamhub.ui

import app.ipcamhub.R
import app.ipcamhub.Strings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import app.ipcamhub.core.Feedback
import app.ipcamhub.core.Hub
import app.ipcamhub.core.Links
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(onOpenUsb: () -> Unit, onOpenLicenses: () -> Unit = {}, modifier: Modifier = Modifier) {
    val settings by Hub.settingsRepo.settings.collectAsState(initial = Hub.settings)
    val scope = rememberCoroutineScope()
    var confirmRemote by remember { mutableStateOf(false) }
    fun change(f: (app.ipcamhub.data.AppSettings) -> app.ipcamhub.data.AppSettings) {
        scope.launch { Hub.settingsRepo.update(f) }
    }
    if (confirmRemote) {
        AlertDialog(
            onDismissRequest = { confirmRemote = false },
            title = { Text(Strings.get(R.string.remote_title)) },
            text = {
                Text(
                    Strings.get(R.string.remote_warning)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemote = false
                    change { it.copy(remoteAccess = true, httpsEnabled = true) }
                }) { Text(Strings.get(R.string.remote_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemote = false }) { Text(Strings.get(R.string.cancel)) } }
        )
    }

    Column(
        modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(Strings.get(R.string.tab_settings), style = MaterialTheme.typography.titleLarge)

        Text(Strings.get(R.string.recording), style = MaterialTheme.typography.titleMedium)
        ChipChoice(
            Strings.get(R.string.recording_format),
            listOf("MP4" to "MP4", "MKV" to "MKV", "TS" to "TS"),
            settings.recordingFormat
        ) { v -> change { it.copy(recordingFormat = v) } }
        Text(Strings.get(R.string.recording_format_note), style = MaterialTheme.typography.bodySmall)
        StorageChoice(settings.storageId) { id -> change { it.copy(storageId = id) } }
        SliderChoice(
            Strings.get(R.string.keep_recording_after_motion),
            listOf(Strings.get(R.string.seconds_short, 10) to 10, Strings.get(R.string.seconds_short, 30) to 30, Strings.get(R.string.minute_one) to 60, Strings.get(R.string.minutes_two) to 120),
            settings.postMotionSeconds
        ) { v -> change { it.copy(postMotionSeconds = v) } }
        // "Off" (keep forever) sits at the far end of the scale.
        SliderChoice(
            Strings.get(R.string.delete_old_after),
            listOf(Strings.get(R.string.days, 3) to 3, Strings.get(R.string.days, 7) to 7, Strings.get(R.string.days, 14) to 14, Strings.get(R.string.days, 30) to 30, Strings.get(R.string.days, 90) to 90, Strings.get(R.string.off) to 0),
            settings.retentionDays
        ) { v -> change { it.copy(retentionDays = v) } }
        SliderChoice(
            Strings.get(R.string.storage_limit),
            listOf("\u200E1 GB" to 1024, "\u200E2 GB" to 2048, "\u200E5 GB" to 5120, "\u200E10 GB" to 10240, "\u200E20 GB" to 20480, "\u200E50 GB" to 51200, Strings.get(R.string.none) to 0),
            settings.maxStorageMb
        ) { v -> change { it.copy(maxStorageMb = v) } }
        SliderChoice(
            Strings.get(R.string.stop_when_free_below),
            listOf("\u200E200 MB" to 200, "\u200E500 MB" to 500, "\u200E1 GB" to 1024, "\u200E2 GB" to 2048),
            settings.minFreeMb
        ) { v -> change { it.copy(minFreeMb = v) } }
        SliderChoice(
            Strings.get(R.string.stop_when_battery_below),
            listOf(Strings.get(R.string.off) to 0, "5%" to 5, "10%" to 10, "15%" to 15, "20%" to 20, "25%" to 25, "30%" to 30, "40%" to 40, "50%" to 50),
            settings.lowBatteryStop
        ) { v -> change { it.copy(lowBatteryStop = v) } }
        Text(Strings.get(R.string.heat_note), style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text(Strings.get(R.string.monitoring), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Strings.get(R.string.background_motion))
                Text(
                    Strings.get(R.string.background_motion_desc),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(checked = settings.backgroundMonitoring, onCheckedChange = { v -> change { it.copy(backgroundMonitoring = v) } })
        }
        if (settings.backgroundMonitoring) BatteryOptimizationHint()

        HorizontalDivider()
        Text(Strings.get(R.string.network), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("HTTPS")
                Text(
                    Strings.get(R.string.https_desc, Hub.HTTPS_PORT),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = settings.httpsEnabled,
                onCheckedChange = { v -> change { it.copy(httpsEnabled = v, remoteAccess = it.remoteAccess && v) } }
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Strings.get(R.string.remote_access))
                Text(
                    Strings.get(R.string.remote_access_desc),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Switch(
                checked = settings.remoteAccess,
                onCheckedChange = { v -> if (v) confirmRemote = true else change { it.copy(remoteAccess = false) } }
            )
        }

        HorizontalDivider()
        Text(Strings.get(R.string.tools), style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(Strings.get(R.string.show_stats))
                Text(Strings.get(R.string.show_stats_desc), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = settings.showStats, onCheckedChange = { v -> change { it.copy(showStats = v) } })
        }
        TextButton(onClick = onOpenUsb) { Text(Strings.get(R.string.usb_diagnostics)) }

        HorizontalDivider()
        AboutSection(onOpenLicenses)
    }
}

/** Where new recordings are saved: every connected storage with its free space. Checked again on return to the app. */
@Composable
private fun StorageChoice(selected: String, onSelect: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var options by remember { mutableStateOf(app.ipcamhub.core.RecordingStorage.options(context)) }
    // A card or drive plugged in while the app is open shows up when the screen is shown again.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) options = app.ipcamhub.core.RecordingStorage.options(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(Strings.get(R.string.save_recordings_to), style = MaterialTheme.typography.labelLarge)
        options.forEach { o ->
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(o.id) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.RadioButton(selected = o.id == selected, onClick = { onSelect(o.id) })
                Column(Modifier.weight(1f)) {
                    Text(o.label)
                    Text(
                        Strings.get(R.string.storage_free, "‎" + gb(o.freeBytes), "‎" + gb(o.totalBytes)),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        if (options.none { it.id == selected }) {
            Text(Strings.get(R.string.storage_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        if (options.size == 1) Text(Strings.get(R.string.storage_hint), style = MaterialTheme.typography.bodySmall)
        else Text(Strings.get(R.string.storage_uninstall_note), style = MaterialTheme.typography.bodySmall)
    }
}

private fun gb(bytes: Long): String = "%.1f GB".format(java.util.Locale.US, bytes / 1e9)

/** Version, links to the website, source and donation pages, the privacy policy, and feedback. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AboutSection(onOpenLicenses: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var showDonate by remember { mutableStateOf(false) }
    var preparing by remember { mutableStateOf(false) }
    fun open(url: String) {
        try {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (_: android.content.ActivityNotFoundException) {
        }
    }
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
    }

    Text(Strings.get(R.string.about), style = MaterialTheme.typography.titleMedium)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier
                .size(48.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                .background(androidx.compose.ui.res.colorResource(R.color.ic_launcher_background))
        )
        Column {
            Text(Strings.get(R.string.app_name), style = MaterialTheme.typography.titleSmall)
            Text(Strings.get(R.string.version_line, version), style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(Strings.get(R.string.about_text), style = MaterialTheme.typography.bodySmall)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { open(Links.WEBSITE) }) { Text(Strings.get(R.string.website)) }
        TextButton(onClick = { open(Links.SOURCE) }) { Text(Strings.get(R.string.source_code)) }
        TextButton(onClick = { open(Links.privacy()) }) { Text(Strings.get(R.string.privacy_policy)) }
        TextButton(onClick = onOpenLicenses) { Text(Strings.get(R.string.licenses_title)) }
    }
    androidx.compose.material3.OutlinedButton(
        enabled = !preparing,
        onClick = {
            preparing = true
            scope.launch {
                val intent = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { Feedback.buildIntent(context) }.getOrNull()
                }
                preparing = false
                try {
                    if (intent != null) Feedback.open(context, intent) else open("mailto:${Feedback.EMAIL}")
                } catch (_: android.content.ActivityNotFoundException) {
                    open(Links.ISSUES)
                }
            }
        }
    ) { Text(if (preparing) Strings.get(R.string.feedback_preparing) else Strings.get(R.string.feedback_send)) }
    Text(Strings.get(R.string.feedback_note), style = MaterialTheme.typography.bodySmall)
    androidx.compose.material3.Button(onClick = { showDonate = true }) { Text("♥ " + Strings.get(R.string.support_project)) }

    if (showDonate) {
        AlertDialog(
            onDismissRequest = { showDonate = false },
            title = { Text(Strings.get(R.string.support_project)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Strings.get(R.string.support_text))
                    Links.DONATE.forEach { (name, url) ->
                        androidx.compose.material3.OutlinedButton(
                            onClick = { open(url) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(name) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDonate = false }) { Text(Strings.get(R.string.close)) } }
        )
    }
}

/**
 * Phones stop background apps to save battery, which ends motion watching overnight. If the app is
 * still optimised, explain that and open the system list where the user can exempt it.
 */
@Composable
private fun BatteryOptimizationHint() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val power = context.getSystemService(android.os.PowerManager::class.java)
    var exempt by remember { mutableStateOf(power.isIgnoringBatteryOptimizations(context.packageName)) }
    // Check again when the user comes back from the system settings.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) exempt = power.isIgnoringBatteryOptimizations(context.packageName)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (exempt) return
    Text(Strings.get(R.string.battery_desc), style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }) { Text(Strings.get(R.string.battery_allow)) }
}
