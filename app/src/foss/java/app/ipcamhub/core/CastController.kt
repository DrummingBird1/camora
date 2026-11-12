package app.ipcamhub.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The "foss" flavor has no Chromecast support: Google's Cast library is not free software, and
 * F-Droid only builds apps without it. Same API as the full flavor's CastController, doing nothing.
 * DLNA TVs, browsers and HLS players still work.
 */
object CastController {
    const val supported = false

    data class Target(val id: String, val name: String)

    val targets: StateFlow<List<Target>> = MutableStateFlow(emptyList())
    val status: StateFlow<String?> = MutableStateFlow(null)

    fun init(context: Context): Boolean = false

    fun startDiscovery(context: Context) {}

    fun stopDiscovery() {}

    fun cast(targetId: String, url: String, title: String) {}

    fun stop() {}
}
