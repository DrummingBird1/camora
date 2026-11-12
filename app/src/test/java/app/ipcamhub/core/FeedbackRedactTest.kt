package app.ipcamhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Feedback reports must never carry camera addresses, credentials or stream keys. */
class FeedbackRedactTest {
    @Test
    fun removesRtspAddressesAndCredentials() {
        val out = Feedback.redact("opening rtsp://admin:secret@192.168.1.50:554/stream1 now")
        assertFalse(out.contains("secret"))
        assertFalse(out.contains("admin"))
        assertFalse(out.contains("stream1"))
        assertEquals("opening rtsp://[address removed] now", out)
    }

    @Test
    fun removesStreamKeys() {
        val out = Feedback.redact("GET /stream/cam.ts?k=AbC_12-xyz HTTP/1.1 and http://10.0.0.2:8080/?k=Zz9")
        assertFalse(out.contains("AbC_12-xyz"))
        assertFalse(out.contains("Zz9"))
    }

    @Test
    fun removesCredentialsFromHttpUrls() {
        assertEquals("http://***@cam.local/snap", Feedback.redact("http://user:pw@cam.local/snap"))
    }

    @Test
    fun keepsOrdinaryText() {
        val text = "MotionMonitor: Garage: 101 frames sampled in the background"
        assertEquals(text, Feedback.redact(text))
    }
}
