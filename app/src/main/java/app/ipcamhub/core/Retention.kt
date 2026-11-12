package app.ipcamhub.core

data class StoredFile(val path: String, val sizeBytes: Long, val modifiedMs: Long)

/** Decides which old recordings to delete. Pure, so it can be tested without a device. */
object Retention {
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /**
     * Returns the files to delete: first everything older than [maxAgeDays], then the oldest
     * files until the total is within [maxTotalBytes]. Zero disables a rule. [protectedPaths]
     * (files being written right now) are never returned.
     */
    fun filesToDelete(
        files: List<StoredFile>,
        nowMs: Long,
        maxAgeDays: Int,
        maxTotalBytes: Long,
        protectedPaths: Set<String> = emptySet(),
    ): List<StoredFile> {
        val candidates = files.filter { it.path !in protectedPaths }
        val doomed = LinkedHashSet<StoredFile>()
        if (maxAgeDays > 0) {
            candidates.filter { nowMs - it.modifiedMs > maxAgeDays * DAY_MS }.forEach { doomed.add(it) }
        }
        if (maxTotalBytes > 0) {
            var total = files.filter { it !in doomed }.sumOf { it.sizeBytes }
            for (f in candidates.filter { it !in doomed }.sortedBy { it.modifiedMs }) {
                if (total <= maxTotalBytes) break
                doomed.add(f)
                total -= f.sizeBytes
            }
        }
        return doomed.toList()
    }
}
