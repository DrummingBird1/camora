package app.ipcamhub.core

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class MotionDetectorTest {
    private fun frame(value: Int, changedCells: Int = 0): IntArray =
        IntArray(100) { if (it < changedCells) 255 - value else value }

    @Test
    fun firstFrameNeverTriggers() {
        assertNull(MotionDetector(1).feed(frame(0), 0))
    }

    @Test
    fun identicalFramesDoNotTrigger() {
        val d = MotionDetector(1)
        d.feed(frame(10), 0)
        assertNull(d.feed(frame(10), 500))
    }

    @Test
    fun bigChangeTriggersOnceThenCoolsDown() {
        val d = MotionDetector(1, cooldownMs = 10_000)
        d.feed(frame(0), 0)
        assertNotNull(d.feed(frame(0, changedCells = 50), 1_000))
        d.feed(frame(0), 2_000)
        assertNull("inside cool-down", d.feed(frame(0, changedCells = 50), 3_000))
        // The previous frame was the changed one, so going back to still is a change too.
        assertNotNull("after cool-down", d.feed(frame(0), 14_000))
    }

    @Test
    fun sensitivityDecidesHowMuchChangeCounts() {
        // 3% of the picture changes: ignored by low (5%) and medium (2%)? medium triggers, low does not.
        val small = frame(0, changedCells = 3)
        val low = MotionDetector(0).also { it.feed(frame(0), 0) }
        val medium = MotionDetector(1).also { it.feed(frame(0), 0) }
        val high = MotionDetector(2).also { it.feed(frame(0), 0) }
        assertNull(low.feed(small, 1_000))
        assertNotNull(medium.feed(small, 1_000))
        assertNotNull(high.feed(small, 1_000))
    }

    @Test
    fun lumaGridSamplesAYPlaneWithPadding() {
        // 8x4 picture stored with a row stride of 10 (2 bytes of padding per row).
        val width = 8
        val height = 4
        val stride = 10
        val buffer = ByteBuffer.allocate(stride * height)
        for (y in 0 until height) for (x in 0 until stride) buffer.put((y * 10 + x).toByte())
        buffer.rewind()
        val grid = MotionMath.lumaGrid(buffer, width, height, stride, 1, cols = 2, rows = 2)
        // corners: (0,0) (7,0) (0,3) (7,3)
        assertEquals(listOf(0, 7, 30, 37), grid.toList())
    }
}
