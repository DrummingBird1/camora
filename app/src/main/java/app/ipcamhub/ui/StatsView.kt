package app.ipcamhub.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import app.ipcamhub.R
import app.ipcamhub.Strings
import app.ipcamhub.core.Hub
import app.ipcamhub.core.StatsSampler
import app.ipcamhub.core.StatsSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Temperature, heat level, Camora's CPU and memory, the phone's free memory, and, for [cameraId], the
 * encoder bitrate. Refreshed once a second while shown. [overlay] draws light text on a dark pill for
 * use over video.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsBar(modifier: Modifier = Modifier, cameraId: String? = null, fps: Float? = null, overlay: Boolean = false) {
    val context = LocalContext.current
    val sampler = remember { StatsSampler(context.applicationContext) }
    var snap by remember { mutableStateOf<StatsSnapshot?>(null) }
    var kbps by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(cameraId) {
        var lastBytes = cameraId?.let { Hub.encodedBytes(it) }
        var lastAt = System.nanoTime()
        while (isActive) {
            snap = withContext(Dispatchers.Default) { sampler.sample() }
            if (cameraId != null) {
                val bytes = Hub.encodedBytes(cameraId)
                val now = System.nanoTime()
                kbps = if (bytes != null && lastBytes != null && bytes >= lastBytes) {
                    ((bytes - lastBytes) * 8 / ((now - lastAt) / 1e6)).toInt() // bits per millisecond = kbit/s
                } else null
                lastBytes = bytes
                lastAt = now
            }
            delay(1000)
        }
    }
    val s = snap ?: return
    // Numbers with units stay in order inside a right-to-left sentence.
    fun iso(v: String) = "\u2066$v\u2069"
    val items = buildList {
        s.batteryTempC?.let { add(Strings.get(R.string.stats_battery, iso("%.1f°C".format(Locale.US, it))) to tempColor(it)) }
        s.cpuTempC?.let { add(Strings.get(R.string.stats_cpu_temp, iso("%.0f°C".format(Locale.US, it))) to tempColor(it - 10)) }
        if (s.thermalStatus >= 0) add(Strings.get(R.string.stats_heat, heatLabel(s.thermalStatus)) to heatColor(s.thermalStatus))
        add(Strings.get(R.string.stats_app_cpu, iso("%.0f%%".format(Locale.US, s.appCpuPercent))) to null)
        add(Strings.get(R.string.stats_app_ram, iso("${s.appRamMb} MB")) to null)
        add(Strings.get(R.string.stats_free_ram, iso("%.1f / %.1f GB".format(Locale.US, s.deviceRamFreeMb / 1024f, s.deviceRamTotalMb / 1024f))) to null)
        fps?.takeIf { it > 0f }?.let { add(iso("%.0f fps".format(Locale.US, it)) to null) }
        kbps?.let { add(Strings.get(R.string.stats_bitrate, iso(if (it >= 1000) "%.1f Mb/s".format(Locale.US, it / 1000f) else "$it kb/s")) to null) }
    }
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items.forEach { (text, color) ->
            Text(
                text,
                style = MaterialTheme.typography.labelMedium.copy(textDirection = TextDirection.ContentOrLtr),
                color = color ?: if (overlay) Color.White else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .background(
                        if (overlay) Color(0x99000000) else MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

/** Battery temperature colours: fine below 40 °C, warm to 45 °C, hot above. */
private fun tempColor(c: Float): Color? = when {
    c >= 45f -> Color(0xFFFF6B6B)
    c >= 40f -> Color(0xFFFFB74D)
    else -> null
}

private fun heatColor(status: Int): Color? = when {
    status >= 3 -> Color(0xFFFF6B6B) // severe and worse
    status >= 1 -> Color(0xFFFFB74D)
    else -> null
}

private fun heatLabel(status: Int): String = Strings.get(
    when (status) {
        0 -> R.string.heat_none
        1 -> R.string.heat_light
        2 -> R.string.heat_moderate
        3 -> R.string.heat_severe
        else -> R.string.heat_critical
    }
)
