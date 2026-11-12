package app.ipcamhub.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import app.ipcamhub.R
import app.ipcamhub.Strings
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** QR code of a stream address, so another phone can open it by pointing its camera at the screen. */
@Composable
fun QrDialog(url: String, onDismiss: () -> Unit) {
    val bitmap = remember(url) { qrBitmap(url) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(Strings.get(R.string.qr_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    bitmap.asImageBitmap(),
                    contentDescription = url,
                    filterQuality = FilterQuality.None, // keep the modules sharp when scaled up
                    modifier = Modifier.background(Color.White).padding(12.dp).size(240.dp)
                )
                Text(Strings.get(R.string.qr_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Strings.get(R.string.close)) } }
    )
}

/** One pixel per QR module; the image is scaled up without smoothing. */
internal fun qrBitmap(text: String): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h) { i -> if (matrix.get(i % w, i / w)) android.graphics.Color.BLACK else android.graphics.Color.WHITE }
    return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
}
