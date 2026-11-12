package app.ipcamhub.ui

import app.ipcamhub.R
import app.ipcamhub.Strings

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.ipcamhub.core.Hub
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Copies a recording to Movies/Camora in the shared gallery. Android 10 and newer. */
@androidx.annotation.RequiresApi(29)
private fun saveToGallery(context: android.content.Context, file: File): Boolean {
    val resolver = context.contentResolver
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, file.name)
        put(android.provider.MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/Camora")
        put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("no stream")
        values.clear()
        values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        false
    }
}

/** Saved recordings and the motion event log. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val recording by Hub.recording.collectAsState()
    val events by Hub.events.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    val files = remember(version, recording) { Hub.listRecordings() }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }

    fun uriOf(f: File) = FileProvider.getUriForFile(context, "${context.packageName}.files", f)

    var playing by remember { mutableStateOf<File?>(null) }
    var toDelete by remember { mutableStateOf<File?>(null) }
    toDelete?.let { f ->
        ConfirmDeleteDialog(
            Strings.get(R.string.confirm_delete_recording, f.name),
            onConfirm = { f.delete(); version++ },
            onDismiss = { toDelete = null },
        )
    }
    playing?.let { file ->
        PlayerScreen(file) { playing = null }
        return
    }

    LazyColumn(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(Strings.get(R.string.tab_recordings), style = MaterialTheme.typography.titleLarge) }
        if (files.isEmpty()) item { Text(Strings.get(R.string.no_recordings)) }
        items(files, key = { it.absolutePath }) { f ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    // File names read left to right (date, time, ".mp4") even in a right-to-left language.
                    // A left-to-right mark before the date keeps it after a Hebrew or Arabic camera name.
                    Text(f.name.replace(Regex("_(?=\\d)"), "_\u200E"), style = MaterialTheme.typography.titleSmall.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr))
                    Text(
                        "${"%.1f".format(f.length() / 1024.0 / 1024.0)} MB  ·  ${fmt.format(Date(f.lastModified()))}",
                        // Numbers and units: keep their order in a right-to-left layout.
                        style = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr)
                    )
                    // Wraps to a second line when the button labels are long (German, French, ...).
                    androidx.compose.foundation.layout.FlowRow {
                        TextButton(onClick = { playing = f }) { Text(Strings.get(R.string.play)) }
                        TextButton(onClick = {
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).setType("video/mp4")
                                        .putExtra(Intent.EXTRA_STREAM, uriOf(f))
                                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                    null
                                )
                            )
                        }) { Text(Strings.get(R.string.share)) }
                        // Recordings live in the app's own folder and go away with the app; this keeps a copy.
                        if (android.os.Build.VERSION.SDK_INT >= 29) TextButton(onClick = {
                            val ok = saveToGallery(context, f)
                            android.widget.Toast.makeText(
                                context, Strings.get(if (ok) R.string.saved_to_gallery else R.string.save_failed), android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }) { Text(Strings.get(R.string.save_to_gallery)) }
                        TextButton(onClick = { toDelete = f }) { Text(Strings.get(R.string.delete)) }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(Strings.get(R.string.motion_events), style = MaterialTheme.typography.titleLarge)
                if (events.isNotEmpty()) TextButton(onClick = { Hub.clearEvents() }) { Text(Strings.get(R.string.clear)) }
            }
        }
        if (events.isEmpty()) item { Text(Strings.get(R.string.no_motion_events)) }
        items(events) { e ->
            Text("${fmt.format(Date(e.timeMs))}  ·  ${e.cameraName}  ·  ${(e.score * 100).toInt()}%")
        }
    }
}
