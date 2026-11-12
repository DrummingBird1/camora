package app.ipcamhub.ui

import app.ipcamhub.R
import app.ipcamhub.Strings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.ipcamhub.data.CameraConfig
import app.ipcamhub.data.SourceType

/** Per-source options: recording and streaming quality, motion sensitivity, sound, capture mode. */
@Composable
fun CameraSettingsDialog(
    cam: CameraConfig,
    uvcModes: List<String>,
    uvcActive: String = "",
    onChange: (CameraConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(cam.name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(Strings.get(R.string.recording_and_stream), style = MaterialTheme.typography.titleSmall)
                ChipChoice(
                    Strings.get(R.string.max_resolution),
                    listOf(Strings.get(R.string.original) to 0, "4K" to 2160, "1080p" to 1080, "720p" to 720, "480p" to 480),
                    cam.maxHeight
                ) { onChange(cam.copy(maxHeight = it)) }
                ChipChoice(
                    Strings.get(R.string.quality_bitrate),
                    listOf(
                        Strings.get(R.string.low) to 0, Strings.get(R.string.medium) to 1,
                        Strings.get(R.string.high) to 2, Strings.get(R.string.very_high) to 3
                    ),
                    cam.bitrate
                ) { onChange(cam.copy(bitrate = it)) }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(Strings.get(R.string.include_sound), Modifier.weight(1f))
                    Switch(checked = cam.audio, onCheckedChange = { onChange(cam.copy(audio = it)) })
                }
                ChipChoice(
                    Strings.get(R.string.video_codec),
                    listOf(Strings.get(R.string.codec_h264) to false, Strings.get(R.string.codec_h265) to true),
                    cam.hevc
                ) { onChange(cam.copy(hevc = it)) }
                Text(Strings.get(R.string.codec_note), style = MaterialTheme.typography.bodySmall)
                Text(
                    Strings.get(R.string.settings_apply_note),
                    style = MaterialTheme.typography.bodySmall
                )

                if (cam.type == SourceType.RTSP) {
                    ChipChoice(
                        Strings.get(R.string.video_transport),
                        listOf(Strings.get(R.string.tcp_stable) to true, Strings.get(R.string.udp_fast) to false),
                        cam.tcp
                    ) { onChange(cam.copy(tcp = it)) }
                }

                Text(Strings.get(R.string.motion_detection), style = MaterialTheme.typography.titleSmall)
                ChipChoice(
                    Strings.get(R.string.sensitivity),
                    listOf(Strings.get(R.string.low) to 0, Strings.get(R.string.medium) to 1, Strings.get(R.string.high) to 2),
                    cam.sensitivity
                ) { onChange(cam.copy(sensitivity = it)) }

                if (cam.type == SourceType.UVC) {
                    Text(Strings.get(R.string.capture_card), style = MaterialTheme.typography.titleSmall)
                    if (uvcActive.isNotEmpty()) Text(Strings.get(R.string.active_mode, uvcActive), style = MaterialTheme.typography.bodySmall)
                    if (uvcModes.isEmpty()) {
                        Text(Strings.get(R.string.connect_card_for_modes), style = MaterialTheme.typography.bodySmall)
                    } else {
                        ChipChoice(
                            Strings.get(R.string.resolution_and_fps),
                            listOf(Strings.get(R.string.automatic) to "") + uvcModes.map { it to it },
                            cam.uvcSize
                        ) { onChange(cam.copy(uvcSize = it)) }
                        Text(Strings.get(R.string.mode_restarts_card), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(Strings.get(R.string.close)) } }
    )
}
