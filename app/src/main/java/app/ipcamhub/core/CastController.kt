package app.ipcamhub.core

import app.ipcamhub.R
import app.ipcamhub.Strings

import android.content.Context
import android.util.Log
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionManagerListener
import com.google.android.gms.cast.framework.CastOptions as GmsCastOptions
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.SessionProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Tells the Cast SDK to use Google's Default Media Receiver, which plays HLS streams. */
class AppCastOptions : OptionsProvider {
    override fun getCastOptions(context: Context): GmsCastOptions =
        GmsCastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

/** Casts a camera's HLS stream to a Chromecast, Google TV or other Cast receiver. Needs Google Play services. */
object CastController {
    private const val TAG = "CastController"

    data class Target(val id: String, val name: String)

    private val _targets = MutableStateFlow<List<Target>>(emptyList())
    val targets: StateFlow<List<Target>> = _targets

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status

    private var router: MediaRouter? = null
    private var castContext: CastContext? = null
    private var pending: Pair<String, MediaInfo>? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var connectingTo = ""

    /** A receiver that is off or unreachable never answers; say so instead of waiting for ever. */
    private val connectTimeout = Runnable {
        if (pending != null) {
            pending = null
            _status.value = Strings.get(R.string.cast_timeout, connectingTo)
        }
    }

    private val selector: MediaRouteSelector by lazy {
        MediaRouteSelector.Builder()
            .addControlCategory(
                CastMediaControlIntent.categoryForCast(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            )
            .build()
    }

    private val routerCallback = object : MediaRouter.Callback() {
        override fun onRouteAdded(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(r: MediaRouter, route: MediaRouter.RouteInfo) = refresh()
    }

    private val sessionListener = object : SessionManagerListener<CastSession> {
        override fun onSessionStarted(session: CastSession, sessionId: String) = load(session)
        override fun onSessionResumed(session: CastSession, wasSuspended: Boolean) = load(session)
        override fun onSessionStartFailed(session: CastSession, error: Int) {
            handler.removeCallbacks(connectTimeout)
            _status.value = Strings.get(R.string.cast_failed, error.toString())
        }

        override fun onSessionStarting(session: CastSession) {}
        override fun onSessionEnding(session: CastSession) {}
        override fun onSessionEnded(session: CastSession, error: Int) {}
        override fun onSessionResuming(session: CastSession, sessionId: String) {}
        override fun onSessionResumeFailed(session: CastSession, error: Int) {}
        override fun onSessionSuspended(session: CastSession, reason: Int) {}
    }

    /** False when Google Play services or the Cast framework are not available on this device. */
    fun init(context: Context): Boolean {
        if (castContext != null) return true
        return try {
            @Suppress("DEPRECATION")
            val cc = CastContext.getSharedInstance(context.applicationContext)
            castContext = cc
            router = MediaRouter.getInstance(context.applicationContext)
            cc.sessionManager.addSessionManagerListener(sessionListener, CastSession::class.java)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Cast not available", e)
            false
        }
    }

    /** Starts looking for receivers. Call [stopDiscovery] when the screen is no longer visible. */
    fun startDiscovery(context: Context) {
        if (!init(context)) return
        router?.addCallback(selector, routerCallback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        refresh()
    }

    fun stopDiscovery() {
        router?.removeCallback(routerCallback)
    }

    private fun refresh() {
        val r = router ?: return
        _targets.value = r.routes
            .filter { it.matchesSelector(selector) && !it.isDefault && !it.isBluetooth }
            .map { Target(it.id, it.name) }
    }

    /** Shows [url] (an HLS playlist) on the receiver. */
    fun cast(targetId: String, url: String, title: String) {
        val r = router ?: return
        val route = r.routes.firstOrNull { it.id == targetId } ?: run {
            _status.value = Strings.get(R.string.screen_not_found)
            return
        }
        val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_GENERIC).apply {
            putString(MediaMetadata.KEY_TITLE, title)
        }
        val info = MediaInfo.Builder(url)
            .setStreamType(MediaInfo.STREAM_TYPE_LIVE)
            .setContentType("application/x-mpegURL")
            .setMetadata(metadata)
            .build()
        pending = targetId to info
        connectingTo = route.name
        handler.removeCallbacks(connectTimeout)
        handler.postDelayed(connectTimeout, 25_000)
        _status.value = Strings.get(R.string.connecting_to, route.name)
        val session = castContext?.sessionManager?.currentCastSession
        if (session != null && session.isConnected && r.selectedRoute.id == route.id) {
            load(session)
        } else {
            r.selectRoute(route)
        }
    }

    private fun load(session: CastSession) {
        val (_, info) = pending ?: return
        handler.removeCallbacks(connectTimeout)
        val client = session.remoteMediaClient ?: return
        client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
        _status.value = Strings.get(R.string.casting_to, session.castDevice?.friendlyName ?: Strings.get(R.string.screen_generic))
        pending = null
    }

    fun stop() {
        handler.removeCallbacks(connectTimeout)
        pending = null
        castContext?.sessionManager?.endCurrentSession(true)
        _status.value = null
    }
}
