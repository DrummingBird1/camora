package app.ipcamhub.core

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class OnvifDevice(val name: String, val host: String, val serviceUrl: String)

/** Pure helpers, kept free of Android types so they can be unit tested. */
object OnvifText {
    fun probeMessage(messageId: String = UUID.randomUUID().toString()): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl">
<e:Header><w:MessageID>uuid:$messageId</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>
<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"""

    /** Parses a WS-Discovery ProbeMatch reply. Returns null if it has no usable address. */
    fun parseProbeMatch(xml: String): OnvifDevice? {
        val xaddrs = Regex("""XAddrs[^>]*>([^<]+)<""").find(xml)?.groupValues?.get(1)?.trim() ?: return null
        val service = xaddrs.split(Regex("\\s+")).firstOrNull { it.startsWith("http") } ?: return null
        val host = Regex("""https?://([^/:]+)""").find(service)?.groupValues?.get(1) ?: return null
        val scopes = Regex("""Scopes[^>]*>([^<]+)<""").find(xml)?.groupValues?.get(1).orEmpty()
        val name = Regex("""onvif://www\.onvif\.org/name/(\S+)""").find(scopes)?.groupValues?.get(1)
            ?: Regex("""onvif://www\.onvif\.org/hardware/(\S+)""").find(scopes)?.groupValues?.get(1)
            ?: host
        return OnvifDevice(name.replace("%20", " "), host, service)
    }

    /** WS-Security PasswordDigest = Base64(SHA1(nonce + created + password)). */
    fun passwordDigest(nonce: ByteArray, created: String, password: String): String {
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(nonce)
        sha.update(created.toByteArray(Charsets.UTF_8))
        sha.update(password.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(sha.digest())
    }

    fun securityHeader(user: String, password: String, nonce: ByteArray, created: String): String {
        val digest = passwordDigest(nonce, created, password)
        val nonceB64 = Base64.getEncoder().encodeToString(nonce)
        return """<s:Header><Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"><UsernameToken><Username>${xml(user)}</Username><Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</Password><Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$nonceB64</Nonce><Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created></UsernameToken></Security></s:Header>"""
    }

    fun envelope(header: String, body: String): String =
        """<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">$header<s:Body>$body</s:Body></s:Envelope>"""

    /** The Media service address from a GetCapabilities reply. */
    fun parseMediaXAddr(xml: String): String? =
        Regex("""Media>.*?XAddr>([^<]+)<""", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.trim()

    /** Picks the profile token with the largest video width from a GetProfiles reply. */
    fun pickProfileToken(xml: String): String? {
        val blocks = Regex("""<[\w:]*Profiles\b([^>]*)>(.*?)</[\w:]*Profiles>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)
        return blocks.mapNotNull { m ->
            val token = Regex("""token="([^"]+)"""").find(m.groupValues[1])?.groupValues?.get(1) ?: return@mapNotNull null
            val width = Regex("""<[\w:]*Width>(\d+)<""").find(m.groupValues[2])?.groupValues?.get(1)?.toIntOrNull() ?: 0
            token to width
        }.maxByOrNull { it.second }?.first
    }

    fun parseStreamUri(xml: String): String? =
        Regex("""<[\w:]*Uri>([^<]+)<""").find(xml)?.groupValues?.get(1)?.trim()?.replace("&amp;", "&")

    /** Adds credentials to an rtsp:// address. */
    fun withCredentials(uri: String, user: String, password: String): String {
        if (user.isEmpty() || !uri.startsWith("rtsp://")) return uri
        val u = URLEncoder.encode(user, "UTF-8").replace("+", "%20")
        val p = URLEncoder.encode(password, "UTF-8").replace("+", "%20")
        return "rtsp://$u:$p@" + uri.removePrefix("rtsp://").substringAfter('@')
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}

object Onvif {
    /** Finds ONVIF cameras on the local network using WS-Discovery (multicast UDP). */
    suspend fun discover(context: Context, timeoutMs: Int = 3000): List<OnvifDevice> = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("onvif").apply { setReferenceCounted(false); acquire() }
        val found = LinkedHashMap<String, OnvifDevice>()
        try {
            MulticastSocket().use { socket ->
                socket.soTimeout = 500
                val probe = OnvifText.probeMessage().toByteArray()
                socket.send(DatagramPacket(probe, probe.size, InetAddress.getByName("239.255.255.250"), 3702))
                val end = System.currentTimeMillis() + timeoutMs
                val buf = ByteArray(16 * 1024)
                while (System.currentTimeMillis() < end) {
                    try {
                        val packet = DatagramPacket(buf, buf.size)
                        socket.receive(packet)
                        OnvifText.parseProbeMatch(String(packet.data, 0, packet.length))?.let { found[it.host] = it }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } finally {
            lock.release()
        }
        found.values.toList()
    }

    /** Asks the camera for its main RTSP address. Returns null on any failure. */
    suspend fun streamUri(device: OnvifDevice, user: String, password: String): String? = withContext(Dispatchers.IO) {
        try {
            val caps = soap(
                device.serviceUrl, user, password,
                """<GetCapabilities xmlns="http://www.onvif.org/ver10/device/wsdl"><Category>Media</Category></GetCapabilities>"""
            )
            val media = OnvifText.parseMediaXAddr(caps) ?: return@withContext null
            val profiles = soap(media, user, password, """<GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>""")
            val token = OnvifText.pickProfileToken(profiles) ?: return@withContext null
            val uriReply = soap(
                media, user, password,
                """<GetStreamUri xmlns="http://www.onvif.org/ver10/media/wsdl"><StreamSetup><Stream xmlns="http://www.onvif.org/ver10/schema">RTP-Unicast</Stream><Transport xmlns="http://www.onvif.org/ver10/schema"><Protocol>RTSP</Protocol></Transport></StreamSetup><ProfileToken>$token</ProfileToken></GetStreamUri>"""
            )
            OnvifText.parseStreamUri(uriReply)?.let { OnvifText.withCredentials(it, user, password) }
        } catch (e: Exception) {
            null
        }
    }

    private fun soap(url: String, user: String, password: String, body: String): String {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val header = if (user.isNotEmpty()) OnvifText.securityHeader(user, password, nonce, created) else ""
        val payload = OnvifText.envelope(header, body).toByteArray(Charsets.UTF_8)
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = 6000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8")
            conn.outputStream.use { it.write(payload) }
            if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}
