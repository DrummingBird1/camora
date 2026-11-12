package app.ipcamhub.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.ipcamhub.core.Quality
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** DEMO is the built-in test picture (see DemoScene). */
enum class SourceType { RTSP, UVC, DEMO }

data class CameraConfig(
    val id: String,
    val name: String,
    val type: SourceType = SourceType.RTSP,
    /** RTSP address, possibly with credentials. Empty for UVC sources. Encrypted at rest. */
    val url: String = "",
    val motion: Boolean = false,
    val autoRecord: Boolean = false,
    val shared: Boolean = false,
    /** Motion sensitivity: 0 = low, 1 = medium, 2 = high. */
    val sensitivity: Int = 1,
    /** Largest height for recording and streaming. 0 = same as the source. */
    val maxHeight: Int = 0,
    /** Bitrate preset: 0 = low, 1 = medium, 2 = high, 3 = very high. */
    val bitrate: Int = 1,
    /** Encode H.265 instead of H.264. */
    val hevc: Boolean = false,
    /** Include sound in recordings and streams when the source has it. */
    val audio: Boolean = true,
    /** Capture card mode such as "1920x1080@60". Empty = choose automatically. */
    val uvcSize: String = "",
    /** Carry RTSP video over TCP (robust, works through any network) instead of trying UDP first. */
    val tcp: Boolean = true,
) {
    val quality: Quality get() = Quality(maxHeight, bitrate, hevc)
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("cameras")
private val CAMERAS_KEY = stringPreferencesKey("cameras_json")

class CameraRepository(private val context: Context) {

    val cameras: Flow<List<CameraConfig>> = context.dataStore.data.map { prefs ->
        parse(prefs[CAMERAS_KEY])
    }

    suspend fun add(name: String, type: SourceType, url: String) {
        context.dataStore.edit { prefs ->
            val camera = CameraConfig(UUID.randomUUID().toString(), name, type, url)
            prefs[CAMERAS_KEY] = serialize(parse(prefs[CAMERAS_KEY]) + camera)
        }
    }

    suspend fun remove(id: String) {
        context.dataStore.edit { prefs ->
            prefs[CAMERAS_KEY] = serialize(parse(prefs[CAMERAS_KEY]).filterNot { it.id == id })
        }
    }

    suspend fun update(id: String, change: (CameraConfig) -> CameraConfig) {
        context.dataStore.edit { prefs ->
            prefs[CAMERAS_KEY] = serialize(parse(prefs[CAMERAS_KEY]).map { if (it.id == id) change(it) else it })
        }
    }

    private fun parse(json: String?): List<CameraConfig> {
        if (json.isNullOrBlank()) return emptyList()
        val arr = JSONArray(json)
        return List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            CameraConfig(
                id = o.getString("id"),
                name = o.getString("name"),
                type = runCatching { SourceType.valueOf(o.optString("type", SourceType.RTSP.name)) }.getOrDefault(SourceType.RTSP),
                url = SecretBox.decrypt(o.optString("url", "")),
                motion = o.optBoolean("motion", false),
                autoRecord = o.optBoolean("autoRecord", false),
                shared = o.optBoolean("shared", false),
                sensitivity = o.optInt("sensitivity", 1),
                maxHeight = o.optInt("maxHeight", 0),
                bitrate = o.optInt("bitrate", 1),
                hevc = o.optBoolean("hevc", false),
                audio = o.optBoolean("audio", true),
                uvcSize = o.optString("uvcSize", ""),
                tcp = o.optBoolean("tcp", true),
            )
        }
    }

    private fun serialize(list: List<CameraConfig>): String {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("type", it.type.name)
                    .put("url", if (it.url.isEmpty()) "" else SecretBox.encrypt(it.url))
                    .put("motion", it.motion)
                    .put("autoRecord", it.autoRecord)
                    .put("shared", it.shared)
                    .put("sensitivity", it.sensitivity)
                    .put("maxHeight", it.maxHeight)
                    .put("bitrate", it.bitrate)
                    .put("hevc", it.hevc)
                    .put("audio", it.audio)
                    .put("uvcSize", it.uvcSize)
                    .put("tcp", it.tcp)
            )
        }
        return arr.toString()
    }
}
