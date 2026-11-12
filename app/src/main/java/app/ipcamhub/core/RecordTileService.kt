package app.ipcamhub.core

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.ipcamhub.MainActivity
import app.ipcamhub.R
import app.ipcamhub.Strings

/**
 * Quick settings tile: shows whether anything is recording, and starts or stops recording of all
 * sources. Android only lets an app start recording (a foreground service) while it is visible, so
 * the tile opens the app with the request instead of recording from the background.
 */
class RecordTileService : TileService() {
    override fun onStartListening() {
        update()
    }

    override fun onClick() {
        val action = if (Hub.recording.value.isEmpty()) MainActivity.ACTION_RECORD_ALL else MainActivity.ACTION_STOP_ALL
        if (action == MainActivity.ACTION_STOP_ALL) {
            // Stopping needs no foreground start, so do it right here.
            Hub.stopRecordingAll()
            update()
            return
        }
        val intent = Intent(this, MainActivity::class.java).setAction(action)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun update() {
        val tile = qsTile ?: return
        val recording = Hub.recording.value.size
        tile.state = if (recording > 0) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = Strings.get(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = if (recording > 0) Strings.get(R.string.tile_recording, recording) else Strings.get(R.string.tile_off)
        }
        tile.updateTile()
    }
}
