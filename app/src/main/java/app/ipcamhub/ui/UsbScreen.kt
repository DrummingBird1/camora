package app.ipcamhub.ui

import app.ipcamhub.R
import app.ipcamhub.Strings

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

private const val ACTION_USB_PERMISSION = "app.ipcamhub.USB_PERMISSION"

/**
 * Phase 0 diagnostic: lists connected USB devices and whether they expose a UVC
 * (USB Video Class) interface. Use it to verify that a capture card is visible to Android.
 */
@Composable
fun UsbScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val usb = remember { context.getSystemService(Context.USB_SERVICE) as UsbManager }
    var refresh by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                refresh++
            }
        }
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }

    // Reading `refresh` makes the list recompose when a USB event arrives.
    val devices = refresh.let { usb.deviceList.values.toList() }

    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(Strings.get(R.string.usb_connected_devices), style = MaterialTheme.typography.titleLarge)
        if (devices.isEmpty()) {
            Text(Strings.get(R.string.usb_none))
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(devices, key = { it.deviceName }) { d ->
                DeviceCard(d, usb.hasPermission(d)) { requestPermission(context, usb, d) }
            }
        }
    }
}

private fun isUvc(d: UsbDevice): Boolean =
    (0 until d.interfaceCount).any { d.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_VIDEO }

private fun requestPermission(context: Context, usb: UsbManager, d: UsbDevice) {
    val pi = PendingIntent.getBroadcast(
        context, 0,
        Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
        PendingIntent.FLAG_MUTABLE
    )
    usb.requestPermission(d, pi)
}

@Composable
private fun DeviceCard(d: UsbDevice, granted: Boolean, onRequest: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(d.productName ?: d.deviceName, style = MaterialTheme.typography.titleMedium)
            Text("VID:PID = %04X:%04X".format(d.vendorId, d.productId))
            Text(if (isUvc(d)) Strings.get(R.string.uvc_yes) else Strings.get(R.string.uvc_no))
            Text(if (granted) Strings.get(R.string.permission_granted) else Strings.get(R.string.permission_not_granted))
            if (!granted) Button(onClick = onRequest) { Text(Strings.get(R.string.request_permission)) }
        }
    }
}
