package app.ipcamhub

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.ipcamhub.core.Hub

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Strings.init(this)
        app.ipcamhub.core.Feedback.installCrashHandler(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, Strings.get(R.string.channel_service), NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MOTION, Strings.get(R.string.motion_detection), NotificationManager.IMPORTANCE_HIGH)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, Strings.get(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH)
        )
        Hub.init(this)
        // The whole app counts as "in the background" when no screen of it is visible.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = Hub.onAppForeground()
            override fun onStop(owner: LifecycleOwner) = Hub.onAppBackground()
        })
    }

    companion object {
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_MOTION = "motion"
        const val CHANNEL_ALERTS = "alerts"
    }
}
