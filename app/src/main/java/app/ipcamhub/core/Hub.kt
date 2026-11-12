package app.ipcamhub.core

import app.ipcamhub.R
import app.ipcamhub.Strings

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.ipcamhub.App
import app.ipcamhub.MainActivity
import app.ipcamhub.data.AppSettings
import app.ipcamhub.data.CameraConfig
import app.ipcamhub.data.CameraRepository
import app.ipcamhub.data.SettingsRepository
import app.ipcamhub.data.SourceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class MotionEvent(val cameraId: String, val cameraName: String, val timeMs: Long, val score: Double)

/**
 * App-wide coordinator: owns recording, the live-stream web server, motion events and the
 * foreground service that keeps them alive in the background.
 */
object Hub {
    const val PORT = 8080
    const val HTTPS_PORT = 8443

    private val MOBILE_INTERFACE = Regex("^(rmnet|ccmni|pdp|v4-rmnet|clat)")
    private const val RENEW_MS = 30 * 60 * 1000L
    private const val TAG = "Hub"
    private const val IDLE_GRACE_MS = 5_000L
    private const val HLS_IDLE_MS = 20_000L
    private const val STORAGE_CHECK_MS = 30_000L
    private const val MAX_EVENTS = 200
    private const val HOT_BATTERY_TENTHS = 500 // 50 °C
    private const val ALERT_NOTIFICATION_ID = 2

    private lateinit var app: Application
    lateinit var repo: CameraRepository
        private set
    lateinit var settingsRepo: SettingsRepository
        private set

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val outputs = ConcurrentHashMap<String, Outputs>()
    private val autoStops = HashMap<String, Runnable>()
    private var cameras: List<CameraConfig> = emptyList()
    private val servers = mutableListOf<StreamServer>()
    private var netSettings: Pair<Boolean, Boolean> = false to false
    private val monitors = HashMap<String, Pair<CameraConfig, MotionMonitor>>()
    private var inBackground = false

    @Volatile
    var settings: AppSettings = AppSettings()
        private set

    @Volatile
    var token: String = ""
        private set

    private val _recording = MutableStateFlow<Set<String>>(emptySet())
    val recording: StateFlow<Set<String>> = _recording

    /** When each running recording started (wall clock), for the timer on the camera tile. */
    private val _recordingSince = MutableStateFlow<Map<String, Long>>(emptyMap())
    val recordingSince: StateFlow<Map<String, Long>> = _recordingSince

    private val _viewers = MutableStateFlow<Map<String, Int>>(emptyMap())
    val viewers: StateFlow<Map<String, Int>> = _viewers

    private val _events = MutableStateFlow<List<MotionEvent>>(emptyList())
    val events: StateFlow<List<MotionEvent>> = _events

    private val _serverUrls = MutableStateFlow<List<String>>(emptyList())
    val serverUrls: StateFlow<List<String>> = _serverUrls

    /** Short message for the user, for example "recording stopped: storage is full". */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    @SuppressLint("StaticFieldLeak") // application context only
    fun init(application: Application) {
        app = application
        repo = CameraRepository(application)
        settingsRepo = SettingsRepository(application)
        val prefs = application.getSharedPreferences("hub", 0)
        token = prefs.getString("token", null) ?: run {
            val bytes = ByteArray(9).also { SecureRandom().nextBytes(it) }
            val t = android.util.Base64.encodeToString(
                bytes, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
            prefs.edit().putString("token", t).apply()
            t
        }
        _events.value = loadEvents()
        scope.launch {
            repo.cameras.collect { list ->
                cameras = list
                val ids = list.map { it.id }.toSet()
                outputs.keys.filter { it !in ids }.forEach { id -> outputs[id]?.let { shutdown(it) } }
                // Settings of idle cameras take effect on their next session.
                list.forEach { c -> outputs[c.id]?.takeIf { it.source == null }?.camera = c }
                list.firstOrNull { it.type == SourceType.UVC }?.let { UvcManager.setPreferredMode(it.uvcSize) }
                refreshMonitors()
            }
        }
        scope.launch {
            settingsRepo.settings.collect { s ->
                settings = s
                cleanup()
                refreshMonitors()
                onNetworkSettingsChanged()
            }
        }
    }

    // ---- background motion monitoring ----

    fun onAppBackground() {
        inBackground = true
        refreshMonitors()
    }

    fun onAppForeground() {
        inBackground = false
        refreshMonitors()
    }

    /** While the app is in the background and the setting is on, watch every motion-enabled camera. */
    private fun refreshMonitors() {
        val wanted = if (inBackground && settings.backgroundMonitoring) cameras.filter { it.motion } else emptyList()
        val wantedById = wanted.associateBy { it.id }
        monitors.keys.toList().forEach { id ->
            val (config, monitor) = monitors[id]!!
            if (wantedById[id] != config) {
                monitor.stop()
                monitors.remove(id)
            }
        }
        wanted.forEach { cam ->
            if (cam.id !in monitors) {
                val monitor = MotionMonitor(app, cam) { score -> reportMotion(cam, score) }
                monitors[cam.id] = cam to monitor
                monitor.start()
            }
        }
        updateService()
    }

    fun showNotice(text: String) {
        _notice.value = text
    }

    fun dismissNotice() {
        _notice.value = null
    }

    // ---- recording ----

    fun recordingsDir(): File =
        File(app.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "IPCamHub").also { it.mkdirs() }

    fun listRecordings(): List<File> =
        recordingsDir().listFiles { f -> f.extension == "mp4" }?.sortedByDescending { it.lastModified() } ?: emptyList()

    private fun hasFreeSpace(): Boolean =
        recordingsDir().usableSpace >= settings.minFreeMb.toLong() * 1024 * 1024

    private enum class Problem { STORAGE, BATTERY, HEAT }

    private var batteryPercent = 100

    /** Something that should stop (or prevent) recording right now: low storage, low battery or heat. */
    private fun recordingProblem(): Problem? {
        if (!hasFreeSpace()) return Problem.STORAGE
        val battery = app.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            val level = battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            val charging = battery.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else 100
            val limit = settings.lowBatteryStop
            if (limit > 0 && !charging && batteryPercent <= limit) return Problem.BATTERY
            // Battery temperature, in tenths of a degree.
            if (battery.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0) >= HOT_BATTERY_TENTHS) return Problem.HEAT
        }
        if (Build.VERSION.SDK_INT >= 29) {
            val power = app.getSystemService(android.os.PowerManager::class.java)
            if (power.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_SEVERE) return Problem.HEAT
        }
        return null
    }

    private fun problemText(p: Problem, stopped: Boolean): String = when (p) {
        Problem.STORAGE -> Strings.get(if (stopped) R.string.recording_stopped_no_space else R.string.no_space_to_record)
        Problem.BATTERY -> Strings.get(if (stopped) R.string.recording_stopped_battery else R.string.no_record_battery, batteryPercent)
        Problem.HEAT -> Strings.get(if (stopped) R.string.recording_stopped_heat else R.string.no_record_heat)
    }

    /** Shown in the app and, because the app may be in the background, as a notification. */
    private fun alert(text: String) {
        _notice.value = text
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = android.app.PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(app, App.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(Strings.get(R.string.app_name))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(ALERT_NOTIFICATION_ID, n)
    }

    /** Asks the quick settings tile, if the user added it, to show the new recording state. */
    private fun refreshTile() {
        try {
            android.service.quicksettings.TileService.requestListeningState(
                app, android.content.ComponentName(app, RecordTileService::class.java)
            )
        } catch (_: Exception) {
        }
    }

    /** Starts recording every source (shortcut, quick-settings tile). */
    fun startRecordingAll() = cameras.forEach { startRecording(it) }

    fun stopRecordingAll() = _recording.value.toList().forEach { stopRecording(it) }

    fun startRecording(cam: CameraConfig) {
        recordingProblem()?.let {
            alert(problemText(it, stopped = false))
            return
        }
        val o = outputsFor(cam)
        if (o.recording) return
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val safeName = cam.name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_")
        val file = File(recordingsDir(), "${safeName}_$stamp.mp4")
        o.currentFile = file
        o.mux.attachRecorder(Mp4Recorder(file))
        o.recording = true
        _recording.value = _recording.value + cam.id
        _recordingSince.value = _recordingSince.value + (cam.id to System.currentTimeMillis())
        o.ensureSource(app)
        o.source?.requestKeyFrame()
        updateService()
        KeepAliveService.refresh(app)
        refreshTile()
        scheduleStorageCheck()
    }

    fun stopRecording(id: String) {
        autoStops.remove(id)?.let { main.removeCallbacks(it) }
        val o = outputs[id] ?: return
        if (!o.recording) return
        o.mux.detachRecorder()
        o.recording = false
        o.currentFile = null
        _recording.value = _recording.value - id
        _recordingSince.value = _recordingSince.value - id
        KeepAliveService.refresh(app)
        refreshTile()
        releaseIfIdle(o)
        cleanup()
    }

    /** Deletes old recordings according to the retention settings. Never touches files being written. */
    fun cleanup() {
        val s = settings
        if (s.retentionDays <= 0 && s.maxStorageMb <= 0) return
        val protectedPaths = outputs.values.mapNotNull { it.currentFile?.absolutePath }.toSet()
        scope.launch(Dispatchers.IO) {
            val files = listRecordings().map { StoredFile(it.absolutePath, it.length(), it.lastModified()) }
            val doomed = Retention.filesToDelete(
                files, System.currentTimeMillis(), s.retentionDays, s.maxStorageMb.toLong() * 1024 * 1024, protectedPaths
            )
            doomed.forEach { File(it.path).delete() }
            if (doomed.isNotEmpty()) Log.i(TAG, "cleanup removed ${doomed.size} recording(s)")
        }
    }

    private val storageCheck = object : Runnable {
        override fun run() {
            if (_recording.value.isEmpty()) return
            val problem = recordingProblem()
            if (problem != null) {
                Log.w(TAG, "stopping all recordings: $problem")
                stopRecordingAll()
                alert(problemText(problem, stopped = true))
                return
            }
            cleanup()
            main.postDelayed(this, STORAGE_CHECK_MS)
        }
    }

    private fun scheduleStorageCheck() {
        main.removeCallbacks(storageCheck)
        main.postDelayed(storageCheck, STORAGE_CHECK_MS)
    }

    // ---- motion ----

    fun reportMotion(cam: CameraConfig, score: Double) {
        val event = MotionEvent(cam.id, cam.name, System.currentTimeMillis(), score)
        _events.value = (listOf(event) + _events.value).take(MAX_EVENTS)
        saveEvents(_events.value)
        notifyMotion(cam)
        if (cam.autoRecord) {
            startRecording(cam)
            autoStops.remove(cam.id)?.let { main.removeCallbacks(it) }
            val stop = Runnable { stopRecording(cam.id) }
            autoStops[cam.id] = stop
            main.postDelayed(stop, settings.postMotionSeconds * 1000L)
        }
    }

    fun clearEvents() {
        _events.value = emptyList()
        saveEvents(emptyList())
    }

    private fun loadEvents(): List<MotionEvent> = try {
        val arr = JSONArray(app.getSharedPreferences("events", 0).getString("list", "[]"))
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            MotionEvent(o.getString("id"), o.getString("name"), o.getLong("time"), o.getDouble("score"))
        }
    } catch (e: Exception) {
        emptyList()
    }

    private fun saveEvents(list: List<MotionEvent>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.cameraId).put("name", it.cameraName).put("time", it.timeMs).put("score", it.score))
        }
        app.getSharedPreferences("events", 0).edit().putString("list", arr.toString()).apply()
    }

    private fun notifyMotion(cam: CameraConfig) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val n = NotificationCompat.Builder(app, App.CHANNEL_MOTION)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(Strings.get(R.string.motion_detected))
            .setContentText(cam.name)
            .setAutoCancel(true)
            .build()
        app.getSystemService(NotificationManager::class.java).notify(cam.id.hashCode(), n)
    }

    // ---- live streaming ----

    fun startServer() {
        if (servers.isNotEmpty()) return
        try {
            val http = StreamServer(PORT)
            http.start()
            servers.add(http)
        } catch (e: Exception) {
            Log.e(TAG, "server start failed", e)
            return
        }
        var urls = localAddresses().map { "http://$it:$PORT/?k=$token" }
        if (settings.httpsEnabled || settings.remoteAccess) {
            try {
                val https = StreamServer(HTTPS_PORT)
                https.makeSecure(TlsSupport.serverSocketFactory(), null)
                https.start()
                servers.add(https)
                urls = urls + localAddresses().map { "https://$it:$HTTPS_PORT/?k=$token" }
            } catch (e: Exception) {
                Log.e(TAG, "https start failed", e)
                _notice.value = Strings.get(R.string.https_unavailable, e.message ?: "")
            }
        }
        _serverUrls.value = urls
        netSettings = settings.httpsEnabled to settings.remoteAccess
        if (settings.remoteAccess && servers.size == 2) enableRemote()
        updateService()
    }

    fun stopServer() {
        disableRemote()
        servers.forEach { it.stop() }
        servers.clear()
        _serverUrls.value = emptyList()
        outputs.values.toList().forEach { it.live.onEnd(); releaseIfIdle(it) }
        updateService()
    }

    /** The server must be restarted for HTTPS or remote-access changes to take effect. */
    private fun onNetworkSettingsChanged() {
        val now = settings.httpsEnabled to settings.remoteAccess
        if (servers.isEmpty() || now == netSettings) return
        stopServer()
        startServer()
    }

    // ---- remote access (HTTPS + a port opened on the router through UPnP) ----

    private val _remoteUrl = MutableStateFlow<String?>(null)

    /** Address that works from the internet, once the router has forwarded the port. */
    val remoteUrl: StateFlow<String?> = _remoteUrl

    private val _remoteStatus = MutableStateFlow<String?>(null)
    val remoteStatus: StateFlow<String?> = _remoteStatus

    private var gateway: Gateway? = null
    private var remoteJob: kotlinx.coroutines.Job? = null

    private val renewMapping = object : Runnable {
        override fun run() {
            val g = gateway ?: return
            val ip = localAddresses().firstOrNull() ?: return
            scope.launch(Dispatchers.IO) { Upnp.addPortMapping(g, HTTPS_PORT, ip, HTTPS_PORT, "Camora") }
            main.postDelayed(this, RENEW_MS)
        }
    }

    private fun enableRemote() {
        remoteJob?.cancel()
        remoteJob = scope.launch {
            _remoteStatus.value = Strings.get(R.string.upnp_searching)
            val g = Upnp.findGateway(app)
            if (g == null) {
                _remoteStatus.value = Strings.get(R.string.upnp_not_found)
                return@launch
            }
            val ip = localAddresses().firstOrNull()
            if (ip == null) {
                _remoteStatus.value = Strings.get(R.string.no_network_address)
                return@launch
            }
            val error = Upnp.addPortMapping(g, HTTPS_PORT, ip, HTTPS_PORT, "Camora")
            if (error != null) {
                _remoteStatus.value = Strings.get(R.string.upnp_refused, error)
                return@launch
            }
            gateway = g
            main.removeCallbacks(renewMapping)
            main.postDelayed(renewMapping, RENEW_MS)
            val external = Upnp.externalIp(g)
            if (external == null || !UpnpText.isPublicIpv4(external)) {
                _remoteStatus.value = Strings.get(R.string.upnp_no_public, external?.let { " ($it)" } ?: "")
                _remoteUrl.value = null
            } else {
                _remoteUrl.value = "https://$external:$HTTPS_PORT/?k=$token"
                _remoteStatus.value = Strings.get(R.string.upnp_available)
            }
        }
    }

    private fun disableRemote() {
        remoteJob?.cancel()
        main.removeCallbacks(renewMapping)
        val g = gateway
        gateway = null
        _remoteUrl.value = null
        _remoteStatus.value = null
        if (g != null) scope.launch(Dispatchers.IO) { Upnp.deletePortMapping(g, HTTPS_PORT) }
    }

    val serverRunning: Boolean get() = servers.isNotEmpty()

    /** Address of a camera's stream on this phone's local network, for other devices to open. */
    fun streamUrl(cameraId: String, hls: Boolean): String? {
        val ip = localAddresses().firstOrNull() ?: return null
        return if (hls) "http://$ip:$PORT/hls/$cameraId/index.m3u8?k=$token" else "http://$ip:$PORT/stream/$cameraId.mp4?k=$token"
    }

    /** Live MPEG-TS over plain HTTP: the lowest delay for TVs that play it over DLNA. */
    fun streamUrlTs(cameraId: String): String? {
        val ip = localAddresses().firstOrNull() ?: return null
        return "http://$ip:$PORT/stream/$cameraId.ts?k=$token"
    }

    fun sharedCameras(): List<CameraConfig> = cameras.filter { it.shared }

    /** Source types and how many of each, for a feedback report (no names or addresses). */
    fun sourcesSummary(): String =
        cameras.groupingBy { it.type }.eachCount().entries.joinToString { "${it.key}=${it.value}" }.ifEmpty { "none" }

    /** Called from server threads. Starts the camera's background source if needed. */
    fun attachViewer(id: String, ts: Boolean = false): LiveStream.Client? {
        val cam = cameras.firstOrNull { it.id == id && it.shared } ?: return null
        return runBlocking {
            withContext(Dispatchers.Main.immediate) {
                val o = outputsFor(cam)
                o.ensureSource(app)
                updateService()
                o.live.newClient(ts)
            }
        }
    }

    fun liveOf(id: String): LiveStream? = outputs[id]?.live

    /**
     * Called from server threads for HLS requests: makes sure the camera's background source is
     * running and that an [HlsPackager] collects its segments. Returns null for unknown cameras.
     */
    fun hlsPackager(id: String): HlsPackager? {
        val cam = cameras.firstOrNull { it.id == id && it.shared } ?: return null
        val live = runBlocking {
            withContext(Dispatchers.Main.immediate) {
                val o = outputsFor(cam)
                o.ensureSource(app)
                updateService()
                o.live
            }
        }
        // The packager needs to know whether sound is part of the stream, which is known once the init segment exists.
        var waited = 0
        while (live.initSegment == null && waited < 15_000) {
            Thread.sleep(100)
            waited += 100
        }
        if (live.initSegment == null) return null
        val packager = synchronized(live) {
            (live.packager as? HlsPackager) ?: HlsPackager(
                live.audioRate(),
                audioChannels = live.audioChannels(), parameterSets = live.parameterSets, hevc = live.hevc
            ).also { live.packager = it }
        }
        packager.lastAccessMs = System.currentTimeMillis()
        main.post(hlsWatchdog)
        return packager
    }

    /** Stops collecting HLS segments, and the camera session, when no player has asked for a while. */
    private val hlsWatchdog = object : Runnable {
        override fun run() {
            main.removeCallbacks(this)
            var anyActive = false
            outputs.values.toList().forEach { o ->
                val p = o.live.packager as? HlsPackager ?: return@forEach
                if (System.currentTimeMillis() - p.lastAccessMs > HLS_IDLE_MS) {
                    o.live.packager = null
                    releaseIfIdle(o)
                } else {
                    anyActive = true
                }
            }
            if (anyActive) main.postDelayed(this, 5_000)
        }
    }

    private fun outputsFor(cam: CameraConfig): Outputs =
        outputs.getOrPut(cam.id) {
            Outputs(cam).also { o ->
                var last = 0
                o.live.onClientsChanged = {
                    val n = o.live.clientCount
                    main.post {
                        _viewers.value = _viewers.value + (cam.id to n)
                        if (n > last) o.source?.requestKeyFrame()
                        last = n
                        if (n == 0) main.postDelayed({ releaseIfIdle(o) }, IDLE_GRACE_MS)
                    }
                }
            }
        }

    private fun releaseIfIdle(o: Outputs) {
        if (!o.idle) return
        o.stopSource()
        outputs.remove(o.camera.id, o)
        updateService()
    }

    private fun shutdown(o: Outputs) {
        stopRecording(o.camera.id)
        o.stopSource()
        outputs.remove(o.camera.id, o)
        updateService()
    }

    private fun updateService() {
        // Android does not let an app start a foreground service once it is in the background, so when
        // background motion watching is set up the service starts (and stays) while the app is visible.
        val needed = servers.isNotEmpty() || monitors.isNotEmpty() ||
            (settings.backgroundMonitoring && cameras.any { it.motion }) ||
            outputs.values.any { it.recording || it.live.clientCount > 0 }
        val intent = Intent(app, KeepAliveService::class.java)
        if (needed) ContextCompat.startForegroundService(app, intent) else app.stopService(intent)
    }

    fun localAddresses(): List<String> =
        NetworkInterface.getNetworkInterfaces().toList()
            // Mobile data interfaces are not reachable from a TV or another computer.
            .filter { it.isUp && !it.isLoopback && !MOBILE_INTERFACE.containsMatchIn(it.name) }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it.hostAddress } }
            .sortedBy { if (it.first.startsWith("wlan")) 0 else 1 }
            .mapNotNull { it.second }
}
