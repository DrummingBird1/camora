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

/** A router's port-mapping service (UPnP Internet Gateway Device). */
data class Gateway(val controlUrl: String, val serviceType: String)

/** Text helpers kept free of Android types so they can be unit tested. */
object UpnpText {
    fun searchMessage(): String =
        "M-SEARCH * HTTP/1.1\r\n" +
            "HOST: 239.255.255.250:1900\r\n" +
            "MAN: \"ssdp:discover\"\r\n" +
            "MX: 2\r\n" +
            "ST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n"

    /** Finds the WAN connection service (where port mappings are made) in a router description. */
    fun parseGateway(xml: String, location: String): Gateway? {
        val services = Regex("""<service>.*?</service>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { it.value }
        val wan = services.firstOrNull { it.contains("WANIPConnection") || it.contains("WANPPPConnection") } ?: return null
        val type = Regex("""<serviceType>([^<]+)</serviceType>""").find(wan)?.groupValues?.get(1)?.trim() ?: return null
        val control = Regex("""<controlURL>([^<]+)</controlURL>""").find(wan)?.groupValues?.get(1)?.trim() ?: return null
        val base = Regex("""<URLBase>([^<]+)</URLBase>""").find(xml)?.groupValues?.get(1)?.trim() ?: location
        return Gateway(URI(base).resolve(control).toString(), type)
    }

    private fun envelope(type: String, action: String, args: String): String =
        "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$action xmlns:u=\"$type\">$args</u:$action></s:Body></s:Envelope>"

    fun addPortMapping(
        g: Gateway, externalPort: Int, internalIp: String, internalPort: Int, description: String, leaseSeconds: Int,
    ): String = envelope(
        g.serviceType, "AddPortMapping",
        "<NewRemoteHost></NewRemoteHost><NewExternalPort>$externalPort</NewExternalPort>" +
            "<NewProtocol>TCP</NewProtocol><NewInternalPort>$internalPort</NewInternalPort>" +
            "<NewInternalClient>$internalIp</NewInternalClient><NewEnabled>1</NewEnabled>" +
            "<NewPortMappingDescription>${DlnaText.escape(description)}</NewPortMappingDescription>" +
            "<NewLeaseDuration>$leaseSeconds</NewLeaseDuration>"
    )

    fun deletePortMapping(g: Gateway, externalPort: Int): String = envelope(
        g.serviceType, "DeletePortMapping",
        "<NewRemoteHost></NewRemoteHost><NewExternalPort>$externalPort</NewExternalPort><NewProtocol>TCP</NewProtocol>"
    )

    fun getExternalIp(g: Gateway): String = envelope(g.serviceType, "GetExternalIPAddress", "")

    fun soapAction(g: Gateway, action: String): String = "\"${g.serviceType}#$action\""

    fun parseExternalIp(xml: String): String? =
        Regex("""<NewExternalIPAddress>([^<]*)</NewExternalIPAddress>""").find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** UPnP error code from a SOAP fault, for example 725 = only permanent leases are supported. */
    fun parseErrorCode(xml: String): Int? =
        Regex("""<errorCode>(\d+)</errorCode>""").find(xml)?.groupValues?.get(1)?.toIntOrNull()

    /** True for an address that can be reached from the internet (not private, shared or local). */
    fun isPublicIpv4(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        val (a, b) = p[0] to p[1]
        return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
            (a == 100 && b in 64..127) || // carrier-grade NAT
            (a == 169 && b == 254) ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168))
    }
}

/** Asks the home router to forward a port to this phone, so the stream can be reached from outside. */
object Upnp {
    suspend fun findGateway(context: Context, timeoutMs: Int = 3000): Gateway? = withContext(Dispatchers.IO) {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("upnp").apply { setReferenceCounted(false); acquire() }
        val locations = LinkedHashSet<String>()
        try {
            MulticastSocket().use { socket ->
                socket.soTimeout = 500
                val probe = UpnpText.searchMessage().toByteArray()
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
        for (loc in locations) {
            try {
                // The router description may list the WAN service under an embedded device.
                UpnpText.parseGateway(get(loc), loc)?.let { return@withContext it }
            } catch (_: Exception) {
            }
        }
        null
    }

    /** Returns null on success, otherwise an error text. A router that only allows permanent leases is handled. */
    suspend fun addPortMapping(g: Gateway, externalPort: Int, internalIp: String, internalPort: Int, description: String): String? =
        withContext(Dispatchers.IO) {
            val first = soap(g, "AddPortMapping", UpnpText.addPortMapping(g, externalPort, internalIp, internalPort, description, 3600))
            if (first.error == null) return@withContext null
            if (first.upnpCode == 725) {
                val second = soap(g, "AddPortMapping", UpnpText.addPortMapping(g, externalPort, internalIp, internalPort, description, 0))
                return@withContext second.error
            }
            first.error
        }

    suspend fun deletePortMapping(g: Gateway, externalPort: Int) {
        withContext(Dispatchers.IO) { soap(g, "DeletePortMapping", UpnpText.deletePortMapping(g, externalPort)) }
    }

    suspend fun externalIp(g: Gateway): String? = withContext(Dispatchers.IO) {
        soap(g, "GetExternalIPAddress", UpnpText.getExternalIp(g)).body?.let { UpnpText.parseExternalIp(it) }
    }

    private class Reply(val body: String?, val error: String?, val upnpCode: Int?)

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 3000
            conn.readTimeout = 4000
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun soap(g: Gateway, action: String, body: String): Reply {
        val conn = URL(g.controlUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4000
            conn.readTimeout = 8000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
            conn.setRequestProperty("SOAPACTION", UpnpText.soapAction(g, action))
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code in 200..299) return Reply(conn.inputStream.bufferedReader().readText(), null, null)
            val text = conn.errorStream?.bufferedReader()?.readText().orEmpty()
            val upnp = UpnpText.parseErrorCode(text)
            return Reply(null, Strings.get(R.string.upnp_router_error, (upnp ?: code).toString()), upnp)
        } catch (e: Exception) {
            return Reply(null, e.message ?: Strings.get(R.string.network_error), null)
        } finally {
            conn.disconnect()
        }
    }
}
