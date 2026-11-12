package app.ipcamhub.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
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
    /** Container for new recordings: a RecordingFormat name (MP4, MKV, TS). */
    val recordingFormat: String = "MP4",
    /** Where new recordings go: a RecordingStorage id ("primary" = the phone). */
    val storageId: String = "primary",
    /** Show temperature, CPU, memory, frame rate and bitrate on the Cameras screen and in full screen. */
    val showStats: Boolean = false,
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
    val format = stringPreferencesKey("recordingFormat")
    val storage = stringPreferencesKey("storageId")
    val stats = booleanPreferencesKey("showStats")
}

class SettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.settingsStore.data.map { read(it) }

    suspend fun update(change: (AppSettings) -> AppSettings) {
        context.settingsStore.edit { p -> write(p, change(read(p))) }
    }

    private fun read(p: Preferences): AppSettings {
        val d = AppSettings()
        return AppSettings(
            postMotionSeconds = p[Keys.postMotion] ?: d.postMotionSeconds,
            retentionDays = p[Keys.retentionDays] ?: d.retentionDays,
            maxStorageMb = p[Keys.maxStorageMb] ?: d.maxStorageMb,
            minFreeMb = p[Keys.minFreeMb] ?: d.minFreeMb,
            backgroundMonitoring = p[Keys.background] ?: d.backgroundMonitoring,
            httpsEnabled = p[Keys.https] ?: d.httpsEnabled,
            remoteAccess = p[Keys.remote] ?: d.remoteAccess,
            lowBatteryStop = p[Keys.lowBattery] ?: d.lowBatteryStop,
            recordingFormat = p[Keys.format] ?: d.recordingFormat,
            storageId = p[Keys.storage] ?: d.storageId,
            showStats = p[Keys.stats] ?: d.showStats,
        )
    }

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[Keys.postMotion] = s.postMotionSeconds
        p[Keys.retentionDays] = s.retentionDays
        p[Keys.maxStorageMb] = s.maxStorageMb
        p[Keys.minFreeMb] = s.minFreeMb
        p[Keys.background] = s.backgroundMonitoring
        p[Keys.https] = s.httpsEnabled
        p[Keys.remote] = s.remoteAccess
        p[Keys.lowBattery] = s.lowBatteryStop
        p[Keys.format] = s.recordingFormat
        p[Keys.storage] = s.storageId
        p[Keys.stats] = s.showStats
    }
}
