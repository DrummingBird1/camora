package app.ipcamhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAndQualityTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day

    private fun f(name: String, mb: Long, ageDays: Int) = StoredFile(name, mb * 1024 * 1024, now - ageDays * day)

    @Test
    fun deletesFilesOlderThanTheLimit() {
        val files = listOf(f("new", 10, 1), f("old", 10, 20), f("older", 10, 40))
        val doomed = Retention.filesToDelete(files, now, maxAgeDays = 14, maxTotalBytes = 0)
        assertEquals(setOf("old", "older"), doomed.map { it.path }.toSet())
    }

    @Test
    fun deletesOldestFilesUntilUnderTheSizeCap() {
        val files = listOf(f("a", 100, 1), f("b", 100, 2), f("c", 100, 3), f("d", 100, 4))
        val doomed = Retention.filesToDelete(files, now, 0, 250L * 1024 * 1024)
        assertEquals(listOf("d", "c"), doomed.map { it.path })
    }

    @Test
    fun neverDeletesProtectedFiles() {
        val files = listOf(f("recording-now", 500, 100), f("x", 10, 1))
        val doomed = Retention.filesToDelete(files, now, 7, 1024L, protectedPaths = setOf("recording-now"))
        assertTrue(doomed.none { it.path == "recording-now" })
    }

    @Test
    fun zeroDisablesBothRules() {
        val files = listOf(f("a", 100, 400))
        assertTrue(Retention.filesToDelete(files, now, 0, 0).isEmpty())
    }

    @Test
    fun scalesSizeKeepingAspectAndEvenNumbers() {
        assertEquals(1280 to 720, EncodeParams.targetSize(1920, 1080, 720))
        assertEquals(1920 to 1080, EncodeParams.targetSize(3840, 2160, 1080))
        assertEquals(1280 to 720, EncodeParams.targetSize(1280, 720, 0))
        assertEquals(852 to 480, EncodeParams.targetSize(1280, 720, 480)) // 853.3 rounded down to an even number
        // never upscales
        assertEquals(640 to 360, EncodeParams.targetSize(640, 360, 1080))
    }

    @Test
    fun bitrateGrowsWithPresetAndStaysInRange() {
        val low = EncodeParams.bitrate(1920, 1080, 30, 0)
        val mid = EncodeParams.bitrate(1920, 1080, 30, 1)
        val high = EncodeParams.bitrate(1920, 1080, 30, 2)
        assertTrue(low < mid && mid < high)
        assertEquals(1_500_000, EncodeParams.bitrate(320, 180, 15, 0))
        assertEquals(100_000_000, EncodeParams.bitrate(7680, 4320, 60, 3))
        // 4K30 very high fits under the cap; H.265 needs about 60% of the H.264 bitrate.
        val uhd = EncodeParams.bitrate(3840, 2160, 30, 3)
        assertTrue(uhd in 50_000_000..70_000_000)
        assertEquals(uhd * 0.6, EncodeParams.bitrate(3840, 2160, 30, 3, hevc = true).toDouble(), 2.0)
    }
}
