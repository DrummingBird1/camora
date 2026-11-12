package app.ipcamhub.core

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.ipcamhub.App
import app.ipcamhub.MainActivity
import app.ipcamhub.R
import app.ipcamhub.Strings

/** Keeps the process alive (with a visible notification) while recording, streaming or watching for motion. */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    @android.annotation.SuppressLint("InlinedApi") // ServiceCompat ignores the types on older Android versions
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_RECORDING) {
            // From the notification button; the service is already in the foreground.
            Hub.stopRecordingAll()
            refresh(this)
            return START_NOT_STICKY
        }
        val notification = build(this)
        // The microphone type is only allowed once the microphone permission has been granted.
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (UvcAudioSource.hasPermission(this)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            running = true
        } catch (e: Exception) {
            // The microphone type can be refused when the app is not visible; keep running without it.
            android.util.Log.w("KeepAliveService", "foreground with microphone refused, retrying without", e)
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                running = true
            } catch (e2: Exception) {
                android.util.Log.e("KeepAliveService", "cannot run in the foreground", e2)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP_RECORDING = "app.ipcamhub.action.STOP_RECORDING"
        private const val NOTIFICATION_ID = 1

        @Volatile
        private var running = false

        /** What the app is doing right now, with a Stop recording button while anything records. */
        fun build(context: Context): Notification {
            val recording = Hub.recording.value.size
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val b = NotificationCompat.Builder(context, App.CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(
                    if (recording > 0) Strings.get(R.string.service_recording, recording) else Strings.get(R.string.service_text)
                )
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
            if (recording > 0) {
                val stop = PendingIntent.getService(
                    context, 1,
                    Intent(context, KeepAliveService::class.java).setAction(ACTION_STOP_RECORDING),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                b.addAction(0, Strings.get(R.string.stop_recording), stop)
            }
            return b.build()
        }

        /** Updates the notification text and buttons without restarting the service. */
        fun refresh(context: Context) {
            if (!running) return
            context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, build(context))
        }
    }
}
