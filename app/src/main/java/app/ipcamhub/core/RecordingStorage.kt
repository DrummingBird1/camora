package app.ipcamhub.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.system.Os
import android.util.Log
import app.ipcamhub.R
import app.ipcamhub.Strings
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * A place recordings can go. Two kinds:
 * - a folder of the app on a storage Android lets it use directly (the phone, most SD cards), [dir] set;
 * - a folder the user picked with the system folder picker, [treeUri] set. This is how USB drives and SSDs
 *   are reached on phones (Samsung, for one) that do not give apps direct access to them.
 */
data class StorageOption(
    /** "primary" for the phone, a volume UUID for an app folder, "tree:<uri>" for a picked folder. */
    val id: String,
    val label: String,
    val dir: File?,
    val treeUri: Uri?,
    val removable: Boolean,
    /** Free and total bytes, when Android reports them. */
    val freeBytes: Long?,
    val totalBytes: Long?,
)

/** One saved recording, wherever it is. [key] is stable and unique: the item's URI. */
data class RecordingItem(
    val name: String,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val uri: Uri,
    /** The file, for recordings in an app folder; null for recordings in a picked folder. */
    val file: File?,
    val storageId: String,
) {
    val key: String get() = uri.toString()
    val extension: String get() = name.substringAfterLast('.', "").lowercase()
    val nameWithoutExtension: String get() = name.substringBeforeLast('.')
}

/** Where a recording is being written. Opened once as a read-write file descriptor. */
class RecordingOutput(val name: String, val uri: Uri, val file: File?, val storageId: String, private val context: Context) {
    val key: String get() = uri.toString()
    private var pfd: ParcelFileDescriptor? = null

    fun open(): ParcelFileDescriptor {
        pfd?.let { return it }
        val p = if (file != null) {
            file.parentFile?.mkdirs()
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            )
        } else {
            context.contentResolver.openFileDescriptor(uri, "rw") ?: error("cannot open $uri")
        }
        pfd = p
        return p
    }

    /** Free bytes on the storage this file is on, read from the open file. */
    fun freeBytes(): Long? = try {
        val st = Os.fstatvfs((pfd ?: return null).fileDescriptor)
        st.f_bavail * st.f_frsize
    } catch (_: Exception) {
        null
    }

    fun close() {
        try {
            pfd?.close()
        } catch (_: Exception) {
        }
        pfd = null
    }

    fun delete() {
        close()
        if (file != null) file.delete() else RecordingStorage.deleteUri(context, uri)
    }
}

object RecordingStorage {
    const val PRIMARY = "primary"
    private const val TREE = "tree:"
    private const val FOLDER = "IPCamHub" // kept from the first versions so existing recordings stay visible
    private const val TAG = "RecordingStorage"

    fun isTree(id: String) = id.startsWith(TREE)

    fun treeId(uri: Uri) = TREE + uri

    /** Every storage that can be written to right now. */
    fun options(context: Context): List<StorageOption> = appFolders(context) + pickedFolders(context).filter { it.second }.map { it.first }

    /** Picked folders whose permission is kept, with whether they are reachable now (a drive may be unplugged). */
    fun pickedFolders(context: Context): List<Pair<StorageOption, Boolean>> =
        context.contentResolver.persistedUriPermissions.filter { it.isWritePermission && it.isReadPermission }.map { p ->
            val uri = p.uri
            val reachable = runCatching { children(context, uri) }.isSuccess
            StorageOption(treeId(uri), treeLabel(context, uri), null, uri, true, null, null) to reachable
        }

    private fun appFolders(context: Context): List<StorageOption> {
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
            StorageOption(id, label, dir, null, volume?.isRemovable == true, dir.usableSpace, dir.totalSpace)
        }
    }

    /** "USB drive of WDC WDS5 › Camora": the drive's name and the picked folder. */
    private fun treeLabel(context: Context, uri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull() ?: return uri.toString()
        val root = docId.substringBefore(':')
        val folder = docId.substringAfter(':', "").ifEmpty { "/" }
        val sm = context.getSystemService(StorageManager::class.java)
        val drive = sm.storageVolumes.firstOrNull { it.uuid == root }?.getDescription(context)
            ?: if (root == "primary") Strings.get(R.string.storage_phone) else Strings.get(R.string.storage_removable)
        return "$drive › $folder"
    }

    /** Takes the permission the folder picker returned, so it survives restarts. Returns the storage id. */
    fun rememberPickedFolder(context: Context, uri: Uri): String {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        return treeId(uri)
    }

    fun primaryDir(context: Context): File =
        File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), FOLDER).also { it.mkdirs() }

    /** Whether [id] can be written to right now. */
    fun isAvailable(context: Context, id: String): Boolean = options(context).any { it.id == id }

    /** Creates a new recording file named [name] on storage [id]; null when that storage cannot be used. */
    fun createOutput(context: Context, id: String, name: String, mime: String): RecordingOutput? {
        val option = options(context).firstOrNull { it.id == id } ?: return null
        option.dir?.let { dir ->
            val f = File(dir, name)
            return RecordingOutput(name, Uri.fromFile(f), f, id, context)
        }
        val tree = option.treeUri ?: return null
        return try {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            val doc = DocumentsContract.createDocument(context.contentResolver, parent, mime, name) ?: return null
            // The provider may adjust the name (for example when it already exists).
            RecordingOutput(displayName(context, doc) ?: name, doc, null, id, context)
        } catch (e: Exception) {
            Log.w(TAG, "cannot create $name in $tree", e)
            null
        }
    }

    /** Recordings on every reachable storage, newest first. */
    fun allRecordings(context: Context): List<RecordingItem> {
        val items = ArrayList<RecordingItem>()
        for (o in appFolders(context)) {
            o.dir?.listFiles { f -> f.isFile && f.extension.lowercase() in RecordingFormat.extensions }?.forEach { f ->
                items.add(RecordingItem(f.name, f.length(), f.lastModified(), Uri.fromFile(f), f, o.id))
            }
        }
        for ((o, reachable) in pickedFolders(context)) {
            if (!reachable) continue
            val tree = o.treeUri ?: continue
            runCatching { children(context, tree) }.getOrDefault(emptyList()).forEach { c ->
                if (c.name.substringAfterLast('.', "").lowercase() in RecordingFormat.extensions) items.add(c.copy(storageId = o.id))
            }
        }
        return items.sortedByDescending { it.modifiedMs }
    }

    /** Files in a picked folder. Throws when the folder cannot be reached. */
    private fun children(context: Context, tree: Uri): List<RecordingItem> {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val out = ArrayList<RecordingItem>()
        context.contentResolver.query(childrenUri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(4) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                out.add(RecordingItem(c.getString(1) ?: "", c.getLong(2), c.getLong(3), uri, null, treeId(tree)))
            }
        } ?: error("folder not reachable")
        return out
    }

    private fun displayName(context: Context, doc: Uri): String? = runCatching {
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    fun openInput(context: Context, item: RecordingItem): InputStream =
        item.file?.inputStream() ?: context.contentResolver.openInputStream(item.uri) ?: error("cannot read ${item.name}")

    fun delete(context: Context, item: RecordingItem): Boolean =
        item.file?.delete() ?: deleteUri(context, item.uri)

    internal fun deleteUri(context: Context, uri: Uri): Boolean = try {
        DocumentsContract.deleteDocument(context.contentResolver, uri)
    } catch (e: Exception) {
        Log.w(TAG, "cannot delete $uri", e)
        false
    }

    /** Which storage a recording is on, for showing it next to the file. */
    fun optionOf(context: Context, item: RecordingItem): StorageOption? = options(context).firstOrNull { it.id == item.storageId }

    /** Copies [item] to [target] and deletes the original. True on success. */
    fun move(context: Context, item: RecordingItem, target: StorageOption): Boolean {
        if (item.storageId == target.id) return true
        // Same device and both plain files: a rename is instant.
        val dir = target.dir
        if (item.file != null && dir != null) {
            var dest = File(dir, item.name)
            var n = 1
            while (dest.exists()) dest = File(dir, "${item.nameWithoutExtension}_${n++}.${item.extension}")
            if (item.file.renameTo(dest)) return true
        }
        val out = createOutput(context, target.id, item.name, RecordingFormat.mimeOf(item.name)) ?: return false
        return try {
            openInput(context, item).use { input ->
                ParcelFileDescriptor.AutoCloseOutputStream(out.open()).use { o: OutputStream -> input.copyTo(o, 1 shl 20) }
            }
            out.close()
            val copied = out.file?.length() ?: sizeOf(context, out.uri)
            if (copied == item.sizeBytes) {
                out.file?.setLastModified(item.modifiedMs)
                delete(context, item)
                true
            } else {
                out.delete()
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "moving ${item.name} failed", e)
            out.delete()
            false
        }
    }

    private fun sizeOf(context: Context, doc: Uri): Long = runCatching {
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else -1L } ?: -1L
    }.getOrDefault(-1L)
}
