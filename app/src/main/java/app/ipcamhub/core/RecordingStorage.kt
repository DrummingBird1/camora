package app.ipcamhub.core

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import app.ipcamhub.R
import app.ipcamhub.Strings
import java.io.File

/** A place recordings can go: the phone, an SD card, a USB drive or SSD. */
data class StorageOption(
    /** "primary" for the phone, otherwise the volume's UUID. */
    val id: String,
    val label: String,
    val dir: File,
    val removable: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
)

/**
 * The app's own recordings folder on every storage device Android lets it use without extra permissions
 * (phone storage, SD cards and, on most phones, USB drives). Files there are deleted when the app is
 * uninstalled; "Save to gallery" keeps a copy.
 */
object RecordingStorage {
    const val PRIMARY = "primary"
    private const val FOLDER = "IPCamHub" // kept from the first versions so existing recordings stay visible

    fun options(context: Context): List<StorageOption> {
        val sm = context.getSystemService(StorageManager::class.java)
        return context.getExternalFilesDirs(Environment.DIRECTORY_MOVIES).withIndex().mapNotNull { (i, base) ->
            if (base == null || Environment.getExternalStorageState(base) != Environment.MEDIA_MOUNTED) return@mapNotNull null
            val volume = sm.getStorageVolume(base)
            val id = if (i == 0) PRIMARY else volume?.uuid ?: base.absolutePath
            val label = when {
                i == 0 -> Strings.get(R.string.storage_phone)
                volume != null -> volume.getDescription(context)
                else -> Strings.get(R.string.storage_removable)
            }
            val dir = File(base, FOLDER).also { it.mkdirs() }
            StorageOption(id, label, dir, volume?.isRemovable == true, dir.usableSpace, dir.totalSpace)
        }
    }

    /** The folder for [id], or null when that storage is not connected right now. */
    fun dirFor(context: Context, id: String): File? = options(context).firstOrNull { it.id == id }?.dir

    fun primaryDir(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), FOLDER).also { it.mkdirs() }

    /** Recordings on every connected storage, newest first. */
    fun allRecordings(context: Context): List<File> =
        options(context).flatMap { o ->
            o.dir.listFiles { f -> f.isFile && f.extension.lowercase() in RecordingFormat.extensions }?.toList() ?: emptyList()
        }.sortedByDescending { it.lastModified() }

    /** Which storage a recording is on, for showing it next to the file. */
    fun optionOf(context: Context, file: File): StorageOption? =
        options(context).firstOrNull { file.absolutePath.startsWith(it.dir.absolutePath) }

    /** Copies [file] into [target] and deletes the original. Returns the new file, or null on failure. */
    fun move(file: File, target: File): File? {
        if (file.parentFile == target) return file
        var dest = File(target, file.name)
        var n = 1
        while (dest.exists()) dest = File(target, "${file.nameWithoutExtension}_${n++}.${file.extension}")
        // Same device: a rename is instant. Different devices: copy, then delete.
        if (file.renameTo(dest)) return dest
        return try {
            file.inputStream().use { input -> dest.outputStream().use { input.copyTo(it, 1 shl 20) } }
            if (dest.length() == file.length()) {
                dest.setLastModified(file.lastModified())
                file.delete()
                dest
            } else {
                dest.delete()
                null
            }
        } catch (e: Exception) {
            dest.delete()
            null
        }
    }
}
