package app.ipcamhub.core

import app.ipcamhub.R
import app.ipcamhub.Strings

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL

/** A TV or speaker that can be told to play a URL (UPnP / DLNA MediaRenderer). */
data class Renderer(val name: String, val controlUrl: String)

/** Text helpers kept free of Android types so they can be unit tested. */
object DlnaText {
    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"

    fun searchMessage(): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n"

    /** Reads the LOCATION header of an SSDP response. */
    fun parseLocation(response: String): String? =
        response.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(':')?.trim()?.takeIf { it.startsWith("http") }

    /**
     * Reads a device description. Returns the friendly name and the absolute AVTransport control
     * address, or null when the device cannot play media.
     */
    fun parseDescription(xml: String, location: String): Renderer? {
        val name = Regex("""<friendlyName>([^<]*)</friendlyName>""").find(xml)?.groupValues?.get(1)?.trim()
        val service = Regex("""<service>.*?</service>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(xml).map { it.value }
            .firstOrNull { it.contains("AVTransport") } ?: return null
        val control = Regex("""<controlURL>([^<]+)</controlURL>""").find(service)?.groupValues?.get(1)?.trim() ?: return null
        val base = Regex("""<URLBase>([^<]+)</URLBase>""").find(xml)?.groupValues?.get(1)?.trim() ?: location
        // java.net.URI glues a relative address straight onto a base without a path ("...:16713AVTransport").
        val root = if (URI(base).rawPath.isNullOrEmpty()) "$base/" else base
        val absolute = URI(root).resolve(control).toString()
        return Renderer(name ?: absolute, absolute)
    }

    fun escape(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun didl(title: String, url: String, mime: String, hevc: Boolean = false): String =
        "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">" +
            "<item id=\"1\" parentID=\"0\" restricted=\"1\"><dc:title>${escape(title)}</dc:title>" +
            "<upnp:class>object.item.videoItem</upnp:class>" +
            "<res protocolInfo=\"http-get:*:$mime:${protocolFeatures(mime, hevc)}\">${escape(url)}</res></item></DIDL-Lite>"

    /** The fourth field of protocolInfo; TVs such as Samsung's read the DLNA profile and flags from it. */
    private const val LIVE_FLAGS = "DLNA.ORG_OP=00;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    /** DLNA profile names only exist for H.264, so H.265 streams go without one. */
    fun features(mime: String, hevc: Boolean = false): String = when {
        hevc -> LIVE_FLAGS
        mime == "video/mp4" -> "DLNA.ORG_PN=AVC_MP4_HP_HD_AAC;$LIVE_FLAGS"
        mime == "video/mpeg" -> "DLNA.ORG_PN=MPEG_TS_HD_NA;$LIVE_FLAGS"
        else -> "*"
    }

    private fun protocolFeatures(mime: String, hevc: Boolean): String = features(mime, hevc)

    fun envelope(action: String, argsXml: String): String =
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$AV_TRANSPORT\">$argsXml</u:$action></s:Body></s:Envelope>"

    fun setUriBody(url: String, title: String, mime: String, hevc: Boolean = false): String =
        envelope(
            "SetAVTransportURI",
            "<InstanceID>0</InstanceID><CurrentURI>${escape(url)}</CurrentURI>" +
                "<CurrentURIMetaData>${escape(didl(title, url, mime, hevc))}</CurrentURIMetaData>"
        )

    fun playBody(): String = envelope("Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")

    fun stopBody(): String = envelope("Stop", "<InstanceID>0</InstanceID>")

    fun soapAction(action: String): String = "\"$AV_TRANSPORT#$action\""
}

object Dlna {
    /** Finds TVs and speakers on the local network (SSDP multicast, then their device descriptions). */
    suspend fun discover(context: Context, timeoutMs: Int = 3000): List<Renderer> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("dlna").apply { setReferenceCounted(false); acquire() }
        val locations = LinkedHashSet<String>()
        try {
            MulticastSocket().use { socket ->
                socket.soTimeout = 500
                val probe = DlnaText.searchMessage().toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { socket.send(DatagramPacket(probe, probe.size, group, 1900)) }
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < end) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        DlnaText.parseLocation(String(packet.data, 0, packet.length))?.let { locations.add(it) }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } finally {
            lock.release()
        }
        android.util.Log.i("Dlna", "SSDP answers: $locations")
        locations.mapNotNull { loc ->
            try {
                val xml = httpGet(loc)
                DlnaText.parseDescription(xml, loc).also { if (it == null) android.util.Log.i("Dlna", "no renderer in description of $loc") }
            } catch (e: Exception) {
                android.util.Log.i("Dlna", "description fetch failed for $loc: $e")
                null
            }
        }.distinctBy { it.controlUrl }
    }

    /** Tells the renderer to play [url]. Returns an error text, or null on success. */
    suspend fun play(renderer: Renderer, url: String, title: String, mime: String, hevc: Boolean = false): String? = withContext(Dispatchers.IO) {
        try {
            soap(renderer, "SetAVTransportURI", DlnaText.setUriBody(url, title, mime, hevc))
            soap(renderer, "Play", DlnaText.playBody())
            null
        } catch (e: Exception) {
            e.message ?: Strings.get(R.string.error)
        }
    }

    suspend fun stop(renderer: Renderer): String? = withContext(Dispatchers.IO) {
        try {
            soap(renderer, "Stop", DlnaText.stopBody())
            null
        } catch (e: Exception) {
            e.message ?: Strings.get(R.string.error)
        }
    }

    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun soap(renderer: Renderer, action: String, body: String) {
        val conn = URL(renderer.controlUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = 8000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", DlnaText.soapAction(action))
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) throw IllegalStateException(Strings.get(R.string.screen_returned_error, conn.responseCode))
        } finally {
            conn.disconnect()
        }
    }
}
