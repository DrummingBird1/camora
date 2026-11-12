package app.ipcamhub

import android.app.Application
import androidx.annotation.StringRes

/** Looks up translated text outside composables. Follows the system (or per-app) language. */
object Strings {
    private lateinit var app: Application

    fun init(application: Application) {
        app = application
    }

    fun get(@StringRes id: Int, vararg args: Any): String = app.getString(id, *args)
}
