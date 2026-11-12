package app.ipcamhub.core

import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Works around a Media3 RTSP behaviour: its H.264/H.265 readers start the video clock at the first
 * video packet, while every audio reader starts at the RTP-Info time from the PLAY reply. A camera
 * that begins with the next key frame therefore puts the sound ahead of the picture by up to a whole
 * key-frame interval, different on every connection.
 *
 * This reads the RTSP conversation as it passes (TCP interleaved mode only) and works out how far the
 * first video packet is behind the RTP-Info time: [videoOffsetUs]. Adding it to a video media time
 * puts the video on the same timeline as the sound. Stays 0 when it cannot be measured.
 */
class RtspSyncProbe {
    @Volatile
    var videoOffsetUs: Long = 0L
        private set

    /** Pass to RtspMediaSource.Factory.setSocketFactory. A new connection starts a new measurement. */
    val socketFactory: SocketFactory = object : SocketFactory() {
        override fun createSocket(host: String, port: Int): Socket = Probed(host, port)
        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            Probed(host, port, localHost, localPort)
        override fun createSocket(host: InetAddress, port: Int): Socket = Probed(host.hostAddress!!, port)
        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            Probed(address.hostAddress!!, port, localAddress, localPort)
        override fun createSocket(): Socket = throw UnsupportedOperationException()
    }

    private inner class Probed : Socket {
        constructor(host: String, port: Int) : super(host, port)
        constructor(host: String, port: Int, local: InetAddress, localPort: Int) : super(host, port, local, localPort)

        private val parser = Parser {
            videoOffsetUs = it
            android.util.Log.i("RtspSyncProbe", "first video packet is ${it / 1000} ms after the RTP-Info time")
        }
        private fun raw(): InputStream = super.getInputStream()
        private val wrapped by lazy { Tap(raw(), parser) }

        override fun getInputStream(): InputStream {
            videoOffsetUs = 0
            return wrapped
        }
    }

    private class Tap(input: InputStream, private val parser: Parser) : FilterInputStream(input) {
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) parser.feed(b)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            for (i in off until off + maxOf(n, 0)) parser.feed(b[i].toInt() and 0xFF)
            return n
        }
    }

    /** Byte-by-byte reader of an RTSP-over-TCP stream: text messages and $-framed RTP packets. */
    internal class Parser(private val onOffset: (Long) -> Unit) {
        private enum class State { START, TEXT, BODY, CHANNEL, LEN1, LEN2, PACKET }

        private var state = State.START
        private val text = StringBuilder()
        private var bodyLeft = 0
        private var channel = 0
        private var packetLen = 0
        private var packetPos = 0
        private val head = ByteArray(8)

        // What the conversation told us so far.
        private val mediaTypes = mutableListOf<String>() // "video" / "audio" in SDP order
        private val controls = mutableListOf<String>()
        private val setupChannels = mutableListOf<Int>()
        private var rtpInfo = listOf<Pair<String, Long>>() // url to rtptime
        private var videoChannel = -1
        private var videoRtpInfoTs = -1L
        private var done = false

        fun feed(b: Int) {
            when (state) {
                State.START -> if (b == '$'.code) state = State.CHANNEL else { text.setLength(0); text.append(b.toChar()); state = State.TEXT }
                State.TEXT -> {
                    text.append(b.toChar())
                    if (text.endsWith("\r\n\r\n") || text.length > 16_384) {
                        bodyLeft = Regex("(?im)^content-length:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                        if (bodyLeft > 0) state = State.BODY else { onMessage(text.toString()); state = State.START }
                    }
                }
                State.BODY -> {
                    text.append(b.toChar())
                    if (--bodyLeft <= 0) { onMessage(text.toString()); state = State.START }
                }
                State.CHANNEL -> { channel = b; state = State.LEN1 }
                State.LEN1 -> { packetLen = b shl 8; state = State.LEN2 }
                State.LEN2 -> { packetLen = packetLen or b; packetPos = 0; state = if (packetLen > 0) State.PACKET else State.START }
                State.PACKET -> {
                    if (packetPos < head.size) head[packetPos] = b.toByte()
                    packetPos++
                    if (packetPos == head.size) onPacketHead()
                    if (packetPos >= packetLen) state = State.START
                }
            }
        }

        private fun onMessage(message: String) {
            // SDP (DESCRIBE reply): media types and their control addresses, in order.
            if (message.contains("\nm=")) {
                mediaTypes.clear(); controls.clear()
                var current: String? = null
                for (line in message.lines()) {
                    val l = line.trim()
                    if (l.startsWith("m=")) { current = l.substring(2).substringBefore(' '); mediaTypes += current; controls += "" }
                    else if (l.startsWith("a=control:") && current != null) controls[controls.size - 1] = l.substring(10)
                }
            }
            // SETUP reply: interleaved channels of the next track.
            Regex("(?i)interleaved=(\\d+)").find(message)?.let { setupChannels += it.groupValues[1].toInt() }
            // PLAY reply: where each track's RTP clock starts.
            Regex("(?im)^rtp-info:\\s*(.+)$").find(message)?.let { m ->
                rtpInfo = m.groupValues[1].trim().split(',').mapNotNull { part ->
                    val url = Regex("url=([^;]+)").find(part)?.groupValues?.get(1)?.trim() ?: return@mapNotNull null
                    val ts = Regex("rtptime=(\\d+)").find(part)?.groupValues?.get(1)?.toLongOrNull() ?: return@mapNotNull null
                    url to ts
                }
                resolveVideo()
            }
        }

        private fun resolveVideo() {
            val index = mediaTypes.indexOf("video")
            if (index < 0 || index >= setupChannels.size) return
            videoChannel = setupChannels[index]
            val control = controls.getOrNull(index).orEmpty()
            videoRtpInfoTs = rtpInfo.firstOrNull { control.isNotEmpty() && it.first.endsWith(control) }?.second
                ?: rtpInfo.getOrNull(index)?.second ?: -1L
        }

        private fun onPacketHead() {
            if (done || channel != videoChannel || videoRtpInfoTs < 0) return
            val ts = ((head[4].toLong() and 0xFF) shl 24) or ((head[5].toLong() and 0xFF) shl 16) or
                ((head[6].toLong() and 0xFF) shl 8) or (head[7].toLong() and 0xFF)
            done = true
            // 32-bit wrap-safe difference, 90 kHz video clock.
            val diff = ((ts - videoRtpInfoTs) shl 32) shr 32
            if (diff in 0..(90_000L * 10)) onOffset(diff * 1_000_000L / 90_000L)
        }
    }
}
