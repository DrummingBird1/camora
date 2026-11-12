package app.ipcamhub.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class AppSettings(
    /** How long to keep recording after the last motion event, in seconds. */
    val postMotionSeconds: Int = 30,
    /** Delete recordings older than this many days. 0 = keep forever. */
    val retentionDays: Int = 0,
    /** Delete the oldest recordings above this total size in MB. 0 = no limit. */
    val maxStorageMb: Int = 0,
    /** Refuse to record, and stop recording, when free space drops below this many MB. */
    val minFreeMb: Int = 500,
    /** Keep detecting motion while the app is in the background. */
    val backgroundMonitoring: Boolean = false,
    /** Also serve the stream over HTTPS (self-signed certificate). */
    val httpsEnabled: Boolean = false,
    /** Ask the router (UPnP) to forward the HTTPS port so the stream is reachable from the internet. */
    val remoteAccess: Boolean = false,
    /** Stop recording when the battery drops to this percent and the phone is not charging. 0 = never. */
    val lowBatteryStop: Int = 15,
)

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("settings")

private object Keys {
    val postMotion = intPreferencesKey("postMotionSeconds")
    val retentionDays = intPreferencesKey("retentionDays")
    val maxStorageMb = intPreferencesKey("maxStorageMb")
    val minFreeMb = intPreferencesKey("minFreeMb")
    val background = booleanPreferencesKey("backgroundMonitoring")
    val https = booleanPreferencesKey("httpsEnabled")
    val remote = booleanPreferencesKey("remoteAccess")
    val lowBattery = intPreferencesKey("lowBatteryStop")
}

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.settingsStore.data.map { p ->
        val d = AppSettings()
        AppSettings(
            postMotionSeconds = p[Keys.postMotion] ?: d.postMotionSeconds,
            retentionDays = p[Keys.retentionDays] ?: d.retentionDays,
            maxStorageMb = p[Keys.maxStorageMb] ?: d.maxStorageMb,
            minFreeMb = p[Keys.minFreeMb] ?: d.minFreeMb,
            backgroundMonitoring = p[Keys.background] ?: d.backgroundMonitoring,
            httpsEnabled = p[Keys.https] ?: d.httpsEnabled,
            remoteAccess = p[Keys.remote] ?: d.remoteAccess,
            lowBatteryStop = p[Keys.lowBattery] ?: d.lowBatteryStop,
        )
    }

    suspend fun update(change: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { p ->
            val d = AppSettings()
            val next = change(
                AppSettings(
                    p[Keys.postMotion] ?: d.postMotionSeconds,
                    p[Keys.retentionDays] ?: d.retentionDays,
                    p[Keys.maxStorageMb] ?: d.maxStorageMb,
                    p[Keys.minFreeMb] ?: d.minFreeMb,
                    p[Keys.background] ?: d.backgroundMonitoring,
                    p[Keys.https] ?: d.httpsEnabled,
                    p[Keys.remote] ?: d.remoteAccess,
                    p[Keys.lowBattery] ?: d.lowBatteryStop,
                )
            )
            p[Keys.postMotion] = next.postMotionSeconds
            p[Keys.retentionDays] = next.retentionDays
            p[Keys.maxStorageMb] = next.maxStorageMb
            p[Keys.minFreeMb] = next.minFreeMb
            p[Keys.background] = next.backgroundMonitoring
            p[Keys.https] = next.httpsEnabled
            p[Keys.remote] = next.remoteAccess
            p[Keys.lowBattery] = next.lowBatteryStop
        }
    }
}
