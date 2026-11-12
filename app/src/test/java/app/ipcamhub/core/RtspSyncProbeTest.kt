package app.ipcamhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RtspSyncProbeTest {
    private fun feed(p: RtspSyncProbe.Parser, s: String) = s.toByteArray(Charsets.ISO_8859_1).forEach { p.feed(it.toInt() and 0xFF) }

    private fun packet(p: RtspSyncProbe.Parser, channel: Int, ts: Long) {
        val rtp = byteArrayOf(0x80.toByte(), 96, 0, 1, (ts shr 24).toByte(), (ts shr 16).toByte(), (ts shr 8).toByte(), ts.toByte(), 1, 2, 3, 4, 9, 9)
        (byteArrayOf('$'.code.toByte(), channel.toByte(), 0, rtp.size.toByte()) + rtp).forEach { p.feed(it.toInt() and 0xFF) }
    }

    private fun conversation(p: RtspSyncProbe.Parser, videoFirst: Boolean = true) {
        val video = "m=video 0 RTP/AVP 96\r\na=control:track0\r\n"
        val audio = "m=audio 0 RTP/AVP 0\r\na=control:track1\r\n"
        val sdp = "v=0\r\ns=x\r\n" + if (videoFirst) video + audio else audio + video
        feed(p, "RTSP/1.0 200 OK\r\nCSeq: 2\r\nContent-Type: application/sdp\r\nContent-Length: ${sdp.length}\r\n\r\n$sdp")
        feed(p, "RTSP/1.0 200 OK\r\nCSeq: 3\r\nTransport: RTP/AVP/TCP;unicast;interleaved=0-1\r\nSession: 1\r\n\r\n")
        feed(p, "RTSP/1.0 200 OK\r\nCSeq: 4\r\nTransport: RTP/AVP/TCP;unicast;interleaved=2-3\r\nSession: 1\r\n\r\n")
        feed(p, "RTSP/1.0 200 OK\r\nCSeq: 5\r\nRTP-Info: url=rtsp://cam/live/track0;seq=0;rtptime=1000,url=rtsp://cam/live/track1;seq=0;rtptime=5000\r\n\r\n")
    }

    @Test
    fun measuresHowLateTheFirstVideoPacketIs() {
        var offset: Long? = null
        val p = RtspSyncProbe.Parser { offset = it }
        conversation(p)
        packet(p, 2, 5000) // audio first
        packet(p, 0, 1000 + 45_000) // video half a second later
        packet(p, 0, 1000 + 48_000)
        assertEquals(500_000L, offset)
    }

    @Test
    fun findsTheVideoTrackWhenAudioComesFirstInTheSdp() {
        var offset: Long? = null
        val p = RtspSyncProbe.Parser { offset = it }
        conversation(p, videoFirst = false)
        // Now track0 (video) was set up second, on channel 2; its rtptime is still matched by url.
        packet(p, 2, 1000 + 9_000)
        assertEquals(100_000L, offset)
    }

    @Test
    fun ignoresStreamsWithoutRtpInfo() {
        var offset: Long? = null
        val p = RtspSyncProbe.Parser { offset = it }
        feed(p, "RTSP/1.0 200 OK\r\nCSeq: 5\r\nSession: 1\r\n\r\n")
        packet(p, 0, 123_456)
        assertNull(offset)
    }

    @Test
    fun handlesTheRtpClockWrappingAround() {
        var offset: Long? = null
        val p = RtspSyncProbe.Parser { offset = it }
        val sdp = "m=video 0 RTP/AVP 96\r\na=control:v\r\n"
        feed(p, "RTSP/1.0 200 OK\r\nContent-Length: ${sdp.length}\r\n\r\n$sdp")
        feed(p, "RTSP/1.0 200 OK\r\nTransport: RTP/AVP/TCP;interleaved=0-1\r\n\r\n")
        feed(p, "RTSP/1.0 200 OK\r\nRTP-Info: url=rtsp://cam/v;seq=1;rtptime=4294967000\r\n\r\n")
        packet(p, 0, 9000L - 296) // wrapped: 9000 ticks after the RTP-Info time
        assertEquals(100_000L, offset)
    }
}
