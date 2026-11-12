package app.ipcamhub.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.ipcamhub.R
import app.ipcamhub.Strings

/** Asks before something is removed for good. */
@Composable
fun ConfirmDeleteDialog(message: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(Strings.get(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(Strings.get(R.string.cancel)) } },
    )
}
