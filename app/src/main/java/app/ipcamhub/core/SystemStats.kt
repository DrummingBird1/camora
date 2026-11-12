package app.ipcamhub.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import java.io.File

/** One reading of the phone's state, as far as Android lets an app see it. */
data class StatsSnapshot(
    val batteryTempC: Float?,
    /** CPU temperature when the phone exposes it to apps (many do not). */
    val cpuTempC: Float?,
    /** PowerManager thermal status: 0 none … 6 shutdown; -1 when unknown. */
    val thermalStatus: Int,
    /** Camora's own CPU use, in percent of the whole CPU. Android does not let apps read the total. */
    val appCpuPercent: Float,
    val appRamMb: Int,
    val deviceRamFreeMb: Long,
    val deviceRamTotalMb: Long,
)

/**
 * Samples temperature, CPU and memory. Call [sample] about once a second; CPU use is measured between
 * two calls. Reading the app's own memory (PSS) takes a few milliseconds, so it is refreshed every 5 s.
 */
class StatsSampler(private val context: Context) {
    private var lastCpuMs = Process.getElapsedCpuTime()
    private var lastWallMs = SystemClock.elapsedRealtime()
    private var ramMb = 0
    private var ramAt = 0L
    // Found on the first sample (off the main thread): scanning the thermal zones takes a moment.
    private val cpuZone: File? by lazy { findCpuZone() }

    fun sample(): StatsSnapshot {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        val thermal = if (Build.VERSION.SDK_INT >= 29) context.getSystemService(PowerManager::class.java).currentThermalStatus else -1

        val cpuMs = Process.getElapsedCpuTime()
        val wallMs = SystemClock.elapsedRealtime()
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val cpu = if (wallMs > lastWallMs) (cpuMs - lastCpuMs) * 100f / ((wallMs - lastWallMs) * cores) else 0f
        lastCpuMs = cpuMs
        lastWallMs = wallMs

        if (wallMs - ramAt > 5_000 || ramAt == 0L) {
            ramMb = (Debug.getPss() / 1024).toInt()
            ramAt = wallMs
        }
        val mi = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)

        return StatsSnapshot(
            batteryTempC = if (tenths == Int.MIN_VALUE) null else tenths / 10f,
            cpuTempC = readCpuTemp(),
            thermalStatus = thermal,
            appCpuPercent = cpu.coerceIn(0f, 100f),
            appRamMb = ramMb,
            deviceRamFreeMb = mi.availMem / (1024 * 1024),
            deviceRamTotalMb = mi.totalMem / (1024 * 1024),
        )
    }

    private fun readCpuTemp(): Float? {
        val raw = try {
            cpuZone?.resolve("temp")?.readText()?.trim()?.toFloatOrNull()
        } catch (_: Exception) {
            null
        } ?: return null
        // Zones report millidegrees on most phones, degrees on a few.
        val c = if (raw > 1000) raw / 1000f else raw
        return c.takeIf { it in 5f..130f }
    }

    /** A readable thermal zone whose type names the CPU, if the phone exposes one. */
    private fun findCpuZone(): File? = try {
        File("/sys/class/thermal").listFiles { f -> f.name.startsWith("thermal_zone") }
            ?.firstOrNull { zone ->
                val type = runCatching { zone.resolve("type").readText().lowercase() }.getOrNull() ?: return@firstOrNull false
                ("cpu" in type || "soc" in type || "tsens" in type) && runCatching { zone.resolve("temp").readText() }.isSuccess
            }
    } catch (_: Exception) {
        null
    }
}
