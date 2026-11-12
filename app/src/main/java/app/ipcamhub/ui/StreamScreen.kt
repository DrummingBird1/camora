package app.ipcamhub.ui

import app.ipcamhub.R
import app.ipcamhub.Strings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.ipcamhub.core.CastController
import app.ipcamhub.core.Dlna
import app.ipcamhub.core.Hub
import app.ipcamhub.core.Renderer
import app.ipcamhub.data.CameraRepository
import kotlinx.coroutines.launch

private fun shareText(context: android.content.Context, text: String) {
    val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
        .putExtra(android.content.Intent.EXTRA_TEXT, text)
    context.startActivity(android.content.Intent.createChooser(send, null))
}

/** Controls for the local web stream, and for sending a camera to a TV (Cast or DLNA). */
@Composable
fun StreamScreen(repo: CameraRepository, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val urls by Hub.serverUrls.collectAsState()
    val cameras by repo.cameras.collectAsState(initial = emptyList())
    val viewers by Hub.viewers.collectAsState()
    var wanted by remember { mutableStateOf(Hub.serverRunning) }

    // Sending to a screen
    val castAvailable = remember { CastController.init(context) }
    val castTargets by CastController.targets.collectAsState()
    val castStatus by CastController.status.collectAsState()
    var dlna by remember { mutableStateOf<List<Renderer>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var selectedCamera by remember { mutableStateOf<String?>(null) }
    var format by remember { mutableStateOf("ts") }
    var message by remember { mutableStateOf<String?>(null) }
    var qrUrl by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        CastController.startDiscovery(context)
        onDispose { CastController.stopDiscovery() }
    }

    val shared = cameras.filter { it.shared }
    val camera = shared.firstOrNull { it.id == selectedCamera } ?: shared.firstOrNull()

    fun ensureServer() {
        if (!Hub.serverRunning) Hub.startServer()
        wanted = true
    }

    Column(
        modifier.padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(Strings.get(R.string.stream_title), style = MaterialTheme.typography.titleLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(Strings.get(R.string.local_server), Modifier.weight(1f))
            Switch(checked = wanted, onCheckedChange = {
                wanted = it
                if (it) Hub.startServer() else Hub.stopServer()
            })
        }
        if (wanted && urls.isEmpty()) {
            Text(Strings.get(R.string.no_network_wifi))
        }
        urls.forEach { url ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Strings.get(R.string.open_in_browser))
                    SelectionContainer { Text(url, style = MaterialTheme.typography.titleMedium.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { qrUrl = url }) { Text(Strings.get(R.string.show_qr)) }
                        TextButton(onClick = { shareText(context, url) }) { Text(Strings.get(R.string.share)) }
                    }
                }
            }
        }
        qrUrl?.let { QrDialog(it) { qrUrl = null } }
        val remote by Hub.remoteUrl.collectAsState()
        val remoteStatus by Hub.remoteStatus.collectAsState()
        remoteStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        remote?.let { url ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(Strings.get(R.string.internet_address))
                    SelectionContainer { Text(url, style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
        Text(Strings.get(R.string.streamed_cameras), style = MaterialTheme.typography.titleMedium)
        if (shared.isEmpty()) {
            Text(Strings.get(R.string.no_streamed_cameras))
        }
        shared.forEach { Text(Strings.get(R.string.streamed_camera_line, it.name, viewers[it.id] ?: 0)) }

        HorizontalDivider()
        Text(Strings.get(R.string.send_to_screen), style = MaterialTheme.typography.titleMedium)
        if (camera == null) {
            Text(Strings.get(R.string.mark_camera_first), style = MaterialTheme.typography.bodySmall)
        } else {
            ChipChoice(Strings.get(R.string.which_camera), shared.map { it.name to it.id }, camera.id) { selectedCamera = it }
            ChipChoice(
                Strings.get(R.string.dlna_format),
                listOf(Strings.get(R.string.live_ts) to "ts", "HLS" to "hls", Strings.get(R.string.live_mp4) to "mp4"),
                format
            ) { format = it }

            Text("Chromecast / Google TV", style = MaterialTheme.typography.titleSmall)
            when {
                !castAvailable -> Text(Strings.get(R.string.cast_unavailable), style = MaterialTheme.typography.bodySmall)
                castTargets.isEmpty() -> Text(Strings.get(R.string.searching_screens), style = MaterialTheme.typography.bodySmall)
                else -> castTargets.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.name, Modifier.weight(1f))
                        Button(onClick = {
                            ensureServer()
                            val url = Hub.streamUrl(camera.id, hls = true)
                            if (url == null) message = Strings.get(R.string.no_network_address) else CastController.cast(t.id, url, camera.name)
                        }) { Text(Strings.get(R.string.cast)) }
                    }
                }
            }
            castStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (castStatus != null) OutlinedButton(onClick = { CastController.stop() }) { Text(Strings.get(R.string.stop_cast)) }

            Text(Strings.get(R.string.dlna_title), style = MaterialTheme.typography.titleSmall)
            TextButton(enabled = !scanning, onClick = {
                scanning = true
                message = null
                scope.launch {
                    dlna = runCatching { Dlna.discover(context) }.getOrDefault(emptyList())
                    scanning = false
                    if (dlna.isEmpty()) message = Strings.get(R.string.no_dlna)
                }
            }) { Text(if (scanning) Strings.get(R.string.searching) else Strings.get(R.string.find_screens)) }
            dlna.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(r.name, Modifier.weight(1f))
                    Button(onClick = {
                        ensureServer()
                        val hls = format == "hls"
                        val url = if (format == "ts") Hub.streamUrlTs(camera.id) else Hub.streamUrl(camera.id, hls)
                        if (url == null) {
                            message = Strings.get(R.string.no_network_address)
                        } else {
                            val mime = when (format) {
                                "hls" -> "application/vnd.apple.mpegurl"
                                "ts" -> "video/mpeg"
                                else -> "video/mp4"
                            }
                            scope.launch {
                                message = Dlna.play(r, url, camera.name, mime, camera.hevc) ?: Strings.get(R.string.sent_to, r.name)
                            }
                        }
                    }) { Text(Strings.get(R.string.cast)) }
                    TextButton(onClick = { scope.launch { Dlna.stop(r) } }) { Text(Strings.get(R.string.stop)) }
                }
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        Text(
            Strings.get(R.string.stream_tips),
            style = MaterialTheme.typography.bodySmall
        )
    }
}
