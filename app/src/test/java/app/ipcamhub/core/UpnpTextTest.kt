package app.ipcamhub.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpnpTextTest {
    private val description = """
        <root><URLBase>http://192.168.1.1:5000/</URLBase><device><deviceList><device><deviceList><device>
        <serviceList>
          <service><serviceType>urn:schemas-upnp-org:service:WANCommonInterfaceConfig:1</serviceType><controlURL>/ctl/CmnIfCfg</controlURL></service>
          <service><serviceType>urn:schemas-upnp-org:service:WANIPConnection:1</serviceType><controlURL>/ctl/IPConn</controlURL></service>
        </serviceList></device></deviceList></device></deviceList></device></root>"""

    private val gateway = Gateway("http://192.168.1.1:5000/ctl/IPConn", "urn:schemas-upnp-org:service:WANIPConnection:1")

    @Test
    fun findsTheWanConnectionService() {
        assertEquals(gateway, UpnpText.parseGateway(description, "http://x/desc.xml"))
        assertNull(UpnpText.parseGateway("<root><device></device></root>", "http://x"))
    }

    @Test
    fun addPortMappingCarriesAllRequiredFields() {
        val body = UpnpText.addPortMapping(gateway, 8443, "192.168.1.50", 8443, "IPCam Hub", 3600)
        listOf(
            "<u:AddPortMapping xmlns:u=\"urn:schemas-upnp-org:service:WANIPConnection:1\">",
            "<NewExternalPort>8443</NewExternalPort>",
            "<NewProtocol>TCP</NewProtocol>",
            "<NewInternalClient>192.168.1.50</NewInternalClient>",
            "<NewEnabled>1</NewEnabled>",
            "<NewLeaseDuration>3600</NewLeaseDuration>",
        ).forEach { assertTrue("missing $it", body.contains(it)) }
    }

    @Test
    fun readsTheExternalAddressAndErrorCodes() {
        assertEquals("203.0.113.9", UpnpText.parseExternalIp("<u:r><NewExternalIPAddress>203.0.113.9</NewExternalIPAddress></u:r>"))
        assertNull(UpnpText.parseExternalIp("<u:r><NewExternalIPAddress></NewExternalIPAddress></u:r>"))
        assertEquals(725, UpnpText.parseErrorCode("<UPnPError><errorCode>725</errorCode></UPnPError>"))
    }

    @Test
    fun soapActionUsesTheServiceType() {
        assertEquals(
            "\"urn:schemas-upnp-org:service:WANIPConnection:1#AddPortMapping\"",
            UpnpText.soapAction(gateway, "AddPortMapping")
        )
    }

    @Test
    fun recognisesPublicAndPrivateAddresses() {
        assertTrue(UpnpText.isPublicIpv4("203.0.113.9"))
        assertTrue(UpnpText.isPublicIpv4("8.8.8.8"))
        listOf("10.1.2.3", "192.168.1.1", "172.16.0.1", "172.31.255.255", "100.64.0.1", "127.0.0.1", "169.254.1.1", "0.0.0.0")
            .forEach { assertFalse("$it should be private", UpnpText.isPublicIpv4(it)) }
        assertTrue(UpnpText.isPublicIpv4("172.32.0.1"))
        assertFalse(UpnpText.isPublicIpv4("not an ip"))
    }
}
