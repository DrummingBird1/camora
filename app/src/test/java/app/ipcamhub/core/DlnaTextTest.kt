package app.ipcamhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DlnaTextTest {
    @Test
    fun searchMessageAsksForMediaRenderers() {
        val m = DlnaText.searchMessage()
        assertTrue(m.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(m.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1"))
        assertTrue(m.endsWith("\r\n\r\n"))
    }

    @Test
    fun readsLocationHeaderCaseInsensitively() {
        val response = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.20:9197/dmr\r\nST: x\r\n\r\n"
        assertEquals("http://192.168.1.20:9197/dmr", DlnaText.parseLocation(response))
        assertNull(DlnaText.parseLocation("HTTP/1.1 200 OK\r\n\r\n"))
    }

    private val description = """
        <root><device><friendlyName>Living Room TV</friendlyName>
        <serviceList>
          <service><serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType><controlURL>/RC/control</controlURL></service>
          <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/AVT/control</controlURL></service>
        </serviceList></device></root>"""

    @Test
    fun findsNameAndAvTransportControlAddress() {
        val r = DlnaText.parseDescription(description, "http://192.168.1.20:9197/dmr")!!
        assertEquals("Living Room TV", r.name)
        assertEquals("http://192.168.1.20:9197/AVT/control", r.controlUrl)
    }

    @Test
    fun usesUrlBaseWhenTheDeviceGivesOne() {
        val xml = description.replace("<root>", "<root><URLBase>http://192.0.2.5:55000/</URLBase>")
        assertEquals("http://192.0.2.5:55000/AVT/control", DlnaText.parseDescription(xml, "http://x/y")!!.controlUrl)
    }

    @Test
    fun deviceWithoutAvTransportIsIgnored() {
        val xml = "<root><device><friendlyName>Router</friendlyName><serviceList></serviceList></device></root>"
        assertNull(DlnaText.parseDescription(xml, "http://x/y"))
    }

    @Test
    fun escapesSpecialCharactersInUrlsAndTitles() {
        val body = DlnaText.setUriBody("http://h/s?a=1&k=2", "Front <door>", "application/vnd.apple.mpegurl")
        assertTrue(body.contains("<CurrentURI>http://h/s?a=1&amp;k=2</CurrentURI>"))
        // The DIDL document is itself escaped inside the SOAP body.
        assertTrue(body.contains("&lt;dc:title&gt;Front &amp;lt;door&amp;gt;&lt;/dc:title&gt;"))
        assertTrue(body.contains("protocolInfo=&quot;http-get:*:application/vnd.apple.mpegurl:*&quot;"))
    }

    @Test
    fun soapActionIsQuotedWithTheServiceType() {
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"", DlnaText.soapAction("Play"))
        assertTrue(DlnaText.playBody().contains("<Speed>1</Speed>"))
        assertTrue(DlnaText.stopBody().contains("<u:Stop "))
    }

    @Test
    fun relativeControlAddressOnAHostWithoutPathKeepsTheSlash() {
        val xml = "<root><device><friendlyName>PC</friendlyName><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>AVTransport</controlURL></service></serviceList></device></root>"
        assertEquals("http://192.168.1.19:16713/AVTransport", DlnaText.parseDescription(xml, "http://192.168.1.19:16713")!!.controlUrl)
    }

}
