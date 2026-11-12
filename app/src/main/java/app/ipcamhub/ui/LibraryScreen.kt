package app.ipcamhub.ui

import app.ipcamhub.R
import app.ipcamhub.Strings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import app.ipcamhub.core.Hub
import app.ipcamhub.core.RecordingFormat
import app.ipcamhub.core.RecordingStorage
import app.ipcamhub.core.StorageOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Copies a recording to Movies/Camora in the shared gallery under [name]. Android 10 and newer. */
@androidx.annotation.RequiresApi(29)
private fun saveToGallery(context: android.content.Context, file: File, name: String): Boolean {
    val resolver = context.contentResolver
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, name)
        put(android.provider.MediaStore.Video.Media.MIME_TYPE, RecordingFormat.mimeOf(file))
        put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, "Movies/Camora")
        put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
    }
    val uri = resolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return try {
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out, 1 shl 20) } } ?: error("no stream")
        values.clear()
        values.put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        true
    } catch (e: Exception) {
        resolver.delete(uri, null, null)
        false
    }
}

private fun toast(context: android.content.Context, text: String) =
    android.widget.Toast.makeText(context, text, android.widget.Toast.LENGTH_SHORT).show()

/** Saved recordings and the motion event log. Long-press a recording (or "Select") to act on several at once. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
fun LibraryScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val recording by Hub.recording.collectAsState()
    val events by Hub.events.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    val files = remember(version, recording) { Hub.listRecordings() }
    val storages = remember(version) { RecordingStorage.options(context) }
    val active = remember(version, recording) { Hub.activeRecordingFiles() }
    val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM) }

    fun uriOf(f: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.files", f)

    var playing by remember { mutableStateOf<File?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf<List<File>?>(null) }
    var toSave by remember { mutableStateOf<File?>(null) }
    var moveChoice by remember { mutableStateOf<List<File>?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }

    // Files being recorded cannot be selected.
    val selectable = files.filter { it.absolutePath !in active }.map { it.absolutePath }.toSet()
    val selectedFiles = files.filter { it.absolutePath in selected && it.absolutePath in selectable }
    fun endSelection() {
        selecting = false
        selected = emptySet()
    }

    /** Runs [work] on a background thread with a progress dialog, then refreshes the list. */
    fun runBusy(label: String, work: () -> String) {
        busy = label
        scope.launch {
            val message = withContext(Dispatchers.IO) { work() }
            busy = null
            version++
            toast(context, message)
        }
    }

    busy?.let { label ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(label) },
            text = { LinearProgressIndicator(Modifier.fillMaxWidth()) },
            confirmButton = {}
        )
    }
    confirmDelete?.let { list ->
        ConfirmDeleteDialog(
            if (list.size == 1) Strings.get(R.string.confirm_delete_recording, list[0].name)
            else Strings.get(R.string.confirm_delete_recordings, list.size),
            onConfirm = {
                list.forEach { it.delete() }
                endSelection()
                version++
            },
            onDismiss = { confirmDelete = null },
        )
    }
    toSave?.let { f ->
        GalleryNameDialog(
            initial = f.nameWithoutExtension,
            extension = f.extension,
            onDismiss = { toSave = null },
            onSave = { name ->
                toSave = null
                runBusy(Strings.get(R.string.saving_to_gallery, 1)) {
                    val ok = android.os.Build.VERSION.SDK_INT >= 29 && saveToGallery(context, f, "$name.${f.extension}")
                    Strings.get(if (ok) R.string.saved_to_gallery else R.string.save_failed)
                }
            },
        )
    }
    moveChoice?.let { list ->
        MoveDialog(
            storages = storages,
            onDismiss = { moveChoice = null },
            onMove = { target ->
                moveChoice = null
                endSelection()
                runBusy(Strings.get(R.string.moving_recordings, list.size)) {
                    val failed = list.count { RecordingStorage.move(it, target.dir) == null }
                    if (failed == 0) Strings.get(R.string.moved_to, target.label) else Strings.get(R.string.move_failed, failed)
                }
            },
        )
    }
    playing?.let { file ->
        PlayerScreen(file) { playing = null }
        return
    }

    LazyColumn(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (selecting) Strings.get(R.string.selected_count, selectedFiles.size) else Strings.get(R.string.tab_recordings),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                if (files.isNotEmpty()) {
                    if (selecting) {
                        val allChosen = selectable.isNotEmpty() && selected.containsAll(selectable)
                        TextButton(onClick = { selected = if (allChosen) emptySet() else selectable }) {
                            Text(Strings.get(if (allChosen) R.string.select_none else R.string.select_all))
                        }
                        TextButton(onClick = { endSelection() }) { Text(Strings.get(R.string.cancel)) }
                    } else {
                        TextButton(onClick = { selecting = true }) { Text(Strings.get(R.string.select)) }
                    }
                }
            }
        }
        if (selecting) item {
            // Actions for everything selected.
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val enabled = selectedFiles.isNotEmpty()
                TextButton(enabled = enabled, onClick = {
                    val uris = ArrayList(selectedFiles.map { uriOf(it) })
                    context.startActivity(
                        Intent.createChooser(
                            Intent(Intent.ACTION_SEND_MULTIPLE).setType("video/*")
                                .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                            null
                        )
                    )
                }) { Text(Strings.get(R.string.share)) }
                if (android.os.Build.VERSION.SDK_INT >= 29) TextButton(enabled = enabled, onClick = {
                    val list = selectedFiles
                    endSelection()
                    runBusy(Strings.get(R.string.saving_to_gallery, list.size)) {
                        val failed = list.count { !saveToGallery(context, it, it.name) }
                        Strings.get(if (failed == 0) R.string.saved_to_gallery else R.string.save_failed)
                    }
                }) { Text(Strings.get(R.string.save_to_gallery)) }
                if (storages.size > 1) TextButton(enabled = enabled, onClick = { moveChoice = selectedFiles }) {
                    Text(Strings.get(R.string.move_to))
                }
                TextButton(enabled = enabled, onClick = { confirmDelete = selectedFiles }) { Text(Strings.get(R.string.delete)) }
            }
        }
        if (files.isEmpty()) item { Text(Strings.get(R.string.no_recordings)) }
        items(files, key = { it.absolutePath }) { f ->
            val isActive = f.absolutePath in active
            val isSelected = f.absolutePath in selected && !isActive
            fun toggle() {
                if (isActive) return
                selected = if (isSelected) selected - f.absolutePath else selected + f.absolutePath
            }
            Card(
                Modifier.fillMaxWidth().combinedClickable(
                    onClick = { if (selecting) toggle() },
                    onLongClick = {
                        if (!selecting) selecting = true
                        toggle()
                    },
                ),
                colors = if (isSelected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer) else CardDefaults.cardColors()
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selecting) Checkbox(checked = isSelected, enabled = !isActive, onCheckedChange = { toggle() })
                    Column(Modifier.weight(1f)) {
                        // File names read left to right (date, time, extension) even in a right-to-left language.
                        // A left-to-right mark before the date keeps it after a Hebrew or Arabic camera name.
                        Text(
                            f.name.replace(Regex("_(?=\\d)"), "_‎"),
                            style = MaterialTheme.typography.titleSmall.copy(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr)
                        )
                        val where = if (storages.size > 1) RecordingStorage.optionOf(context, f)?.label?.let { "  ·  $it" } ?: "" else ""
                        Text(
                            "${"%.1f".format(f.length() / 1024.0 / 1024.0)} MB  ·  ${fmt.format(Date(f.lastModified()))}$where",
                            // Numbers and units: keep their order in a right-to-left layout.
                            style = androidx.compose.ui.text.TextStyle(textDirection = androidx.compose.ui.text.style.TextDirection.Ltr)
                        )
                        if (isActive) {
                            Text(Strings.get(R.string.still_recording), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        if (!selecting) {
                            // Wraps to a second line when the button labels are long (German, French, ...).
                            androidx.compose.foundation.layout.FlowRow {
                                TextButton(onClick = { playing = f }) { Text(Strings.get(R.string.play)) }
                                TextButton(onClick = {
                                    context.startActivity(
                                        Intent.createChooser(
                                            Intent(Intent.ACTION_SEND).setType(RecordingFormat.mimeOf(f))
                                                .putExtra(Intent.EXTRA_STREAM, uriOf(f))
                                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                                            null
                                        )
                                    )
                                }) { Text(Strings.get(R.string.share)) }
                                // Recordings live in the app's own folder and go away with the app; this keeps a copy.
                                if (android.os.Build.VERSION.SDK_INT >= 29) TextButton(onClick = { toSave = f }) {
                                    Text(Strings.get(R.string.save_to_gallery))
                                }
                                if (!isActive) TextButton(onClick = { confirmDelete = listOf(f) }) { Text(Strings.get(R.string.delete)) }
                            }
                        }
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

/** Asks for the name the recording gets in the gallery (without the extension). */
@Composable
private fun GalleryNameDialog(initial: String, extension: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    // Characters that file systems or the gallery reject.
    val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.')
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.get(R.string.save_to_gallery)) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(Strings.get(R.string.file_name)) },
                singleLine = true,
                // The left-to-right mark keeps the dot before the extension in a right-to-left layout.
                suffix = { Text("‎.$extension") },
            )
        },
        confirmButton = { TextButton(enabled = clean.isNotEmpty(), onClick = { onSave(clean) }) { Text(Strings.get(R.string.save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.get(R.string.cancel)) } },
    )
}

/** Picks the storage to move recordings to. */
@Composable
private fun MoveDialog(storages: List<StorageOption>, onDismiss: () -> Unit, onMove: (StorageOption) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.get(R.string.move_to)) },
        text = {
            Column {
                storages.forEach { s ->
                    TextButton(onClick = { onMove(s) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${s.label}  (‎${"%.1f".format(java.util.Locale.US, s.freeBytes / 1e9)} GB)")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.get(R.string.cancel)) } },
    )
}
