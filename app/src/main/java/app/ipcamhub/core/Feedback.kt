package app.ipcamhub.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import app.ipcamhub.R
import app.ipcamhub.Strings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * User-initiated feedback: an email to the developer with a report attached (device, app version,
 * the last crash and the app's own recent log). Nothing is sent unless the user presses Send in their
 * email app, and they can read the report first. Addresses, passwords and stream keys are removed.
 */
object Feedback {
    const val EMAIL = "Solvaris2@gmail.com"
    private const val CRASH_FILE = "last_crash.txt"
    private const val PREFS = "feedback"

    /** Saves the stack trace of a crash so it can be included in the next report. */
    fun installCrashHandler(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = buildString {
                    append(stamp()).append('\n')
                    append(appVersion(app)).append(" on Android ").append(Build.VERSION.RELEASE).append('\n')
                    append("thread: ").append(thread.name).append('\n')
                    append(android.util.Log.getStackTraceString(error))
                }
                File(app.filesDir, CRASH_FILE).writeText(redact(text))
                app.getSharedPreferences(PREFS, 0).edit().putBoolean("crash_unseen", true).commit()
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** True once after a crash, so the app can mention that a report can be sent. */
    fun takeUnseenCrash(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, 0)
        val unseen = prefs.getBoolean("crash_unseen", false)
        if (unseen) prefs.edit().putBoolean("crash_unseen", false).apply()
        return unseen
    }

    /** Writes the report and opens the user's email app. Call off the main thread for the log read. */
    fun buildIntent(context: Context): Intent {
        val dir = File(context.cacheDir, "feedback").also { it.mkdirs() }
        val report = File(dir, "camora-report.txt")
        report.writeText(report(context))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", report)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "Camora feedback (${appVersion(context)})")
            putExtra(Intent.EXTRA_TEXT, Strings.get(R.string.feedback_body) + "\n\n" + deviceLine(context) + "\n")
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("report", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return send
    }

    /**
     * Opens an email app with the report. The selector limits the list to email apps; a chooser
     * would drop it (and then finds no app), so the intent is started directly. If no email app
     * accepts it, falls back to the normal share sheet.
     */
    fun open(context: Context, send: Intent) {
        // The apps that handle mailto: links are the email apps. Offer the report to those only.
        val emailApps = context.packageManager
            .queryIntentActivities(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")), 0)
            .map { it.activityInfo.packageName }
            .distinct()
        val targeted = emailApps.map { Intent(send).setPackage(it) }
        val intent = when {
            targeted.isEmpty() -> Intent.createChooser(send, Strings.get(R.string.feedback_send))
            targeted.size == 1 -> targeted[0]
            else -> Intent.createChooser(targeted[0], Strings.get(R.string.feedback_send))
                .putExtra(Intent.EXTRA_INITIAL_INTENTS, targeted.drop(1).toTypedArray())
        }
        context.startActivity(intent)
    }

    fun report(context: Context): String = buildString {
        append("Camora report, ").append(stamp()).append("\n\n")
        append(deviceLine(context)).append('\n')
        append("ABI: ").append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
        append("Language: ").append(Locale.getDefault().toLanguageTag()).append('\n')
        val cams = runCatching { Hub.sourcesSummary() }.getOrDefault("?")
        append("Sources: ").append(cams).append('\n')
        val s = Hub.settings
        append("Settings: background=${s.backgroundMonitoring} https=${s.httpsEnabled} remote=${s.remoteAccess} ")
        append("lowBattery=${s.lowBatteryStop}% minFree=${s.minFreeMb}MB\n")
        val crash = File(context.filesDir, CRASH_FILE)
        append("\n--- last crash ---\n")
        append(if (crash.exists()) crash.readText() else "none\n")
        append("\n--- recent log ---\n")
        append(redact(appLog()))
    }

    private fun deviceLine(context: Context) =
        "${appVersion(context)}, ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    private fun appVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "Camora ${info.versionName} (${if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()})"
    } catch (_: Exception) {
        "Camora"
    }

    /** The app's own log lines only (Android does not let apps read other apps' logs). */
    private fun appLog(): String = try {
        val p = ProcessBuilder("logcat", "-d", "-t", "5000", "--pid=${Process.myPid()}").redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { r ->
            // Leave out the system's per-frame drawing messages, which would push everything useful out.
            r.readLines().filterNot { NOISE.containsMatchIn(it) }.takeLast(400).joinToString("\n")
        }
    } catch (e: Exception) {
        "log unavailable: ${e.message}"
    }

    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())

    private val NOISE = Regex("""\s[VDI] (View|ViewRootImpl|InsetsController|InputMethodManager\S*|ImeTracker|BLASTBufferQueue\S*|SurfaceView|HWUI|Choreographer)\s*:""")

    private val CREDENTIALS = Regex("""(rtsps?|https?)://[^\s/@]*@""", RegexOption.IGNORE_CASE)
    private val RTSP_PATH = Regex("""rtsps?://[^\s"']+""", RegexOption.IGNORE_CASE)
    private val KEY = Regex("""([?&]k=)[A-Za-z0-9_\-]+""")

    /** Removes camera addresses, user names, passwords and stream keys. */
    fun redact(text: String): String {
        var t = text.replace(CREDENTIALS) { "${it.groupValues[1]}://***@" }
        t = t.replace(RTSP_PATH, "rtsp://[address removed]")
        t = t.replace(KEY) { "${it.groupValues[1]}***" }
        val token = runCatching { Hub.token }.getOrDefault("")
        if (token.length >= 6) t = t.replace(token, "***")
        return t
    }
}
