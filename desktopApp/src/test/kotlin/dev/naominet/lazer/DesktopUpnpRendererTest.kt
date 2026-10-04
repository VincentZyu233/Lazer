package dev.naominet.lazer

import java.net.InetAddress
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.SAXException

class DesktopUpnpRendererTest {
    @Test
    fun `SSDP reply parser accepts case insensitive location headers and rejects non success`() {
        val source = InetAddress.getByName("192.168.1.20")
        val reply = parseDesktopSsdpReply(
            source,
            "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nlOcAtIoN: http://192.168.1.20:1400/device.xml\r\n\r\n",
        )

        assertEquals(URI("http://192.168.1.20:1400/device.xml"), reply?.location)
        assertNull(parseDesktopSsdpReply(source, "HTTP/1.1 404 Not Found\r\nLOCATION: http://192.168.1.20/device.xml\r\n"))
        assertNull(parseDesktopSsdpReply(source, "HTTP/1.1 200 OK\r\nLOCATION: javascript:alert(1)\r\n"))
    }

    @Test
    fun `M-SEARCH has the required multicast discovery headers and terminator`() {
        val request = String(
            desktopUpnpMSearchRequest("urn:schemas-upnp-org:device:MediaRenderer:1"),
            Charsets.US_ASCII,
        )

        assertTrue(request.startsWith("M-SEARCH * HTTP/1.1\r\n"))
        assertTrue(request.contains("HOST: 239.255.255.250:1900\r\n"))
        assertTrue(request.contains("MAN: \"ssdp:discover\"\r\n"))
        assertTrue(request.contains("MX: 2\r\n"))
        assertTrue(request.endsWith("\r\n\r\n"))
    }

    @Test
    fun `description parser finds embedded MediaRenderer and resolves service URLs`() {
        val location = URI("http://192.168.1.20:1400/xml/device.xml")
        val renderers = parseDesktopUpnpRendererDescription(
            rendererDescription().toByteArray(Charsets.UTF_8),
            location,
        )

        assertEquals(1, renderers.size)
        val renderer = renderers.single()
        assertEquals("uuid:renderer-1", renderer.udn)
        assertEquals("Living Room DAC", renderer.friendlyName)
        assertEquals("Example Audio", renderer.manufacturer)
        assertEquals("R1", renderer.modelName)
        assertEquals("upnp:uuid:renderer-1", renderer.identity)
        assertTrue(renderer.supportsAvTransport)
        assertTrue(renderer.hasOpenHomePlaylist)
        assertEquals(
            URI("http://192.168.1.20:1400/renderer/av/control"),
            renderer.services.getValue(DesktopUpnpRendererServiceKind.AV_TRANSPORT).controlUri,
        )
        assertEquals(
            "urn:schemas-upnp-org:service:RenderingControl:2",
            renderer.services.getValue(DesktopUpnpRendererServiceKind.RENDERING_CONTROL).serviceType,
        )
        assertEquals(
            "urn:av-openhome-org:service:Playlist:2",
            renderer.services.getValue(DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST).serviceType,
        )
        assertEquals(
            URI("http://192.168.1.20:1400/renderer/oh/playlist-control-v2"),
            renderer.services.getValue(DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST).controlUri,
        )
    }

    @Test
    fun `description parser rejects cross origin service URLs`() {
        val xml = rendererDescription().replace(
            "<controlURL>av/control</controlURL>",
            "<controlURL>http://attacker.example/collect</controlURL>",
        )
        val renderer = parseDesktopUpnpRendererDescription(
            xml.toByteArray(Charsets.UTF_8),
            URI("http://192.168.1.20:1400/xml/device.xml"),
        ).single()

        assertFalse(renderer.supportsAvTransport)
        assertTrue(renderer.hasOpenHomePlaylist)
        assertTrue(renderer.services.containsKey(DesktopUpnpRendererServiceKind.CONNECTION_MANAGER))
    }

    @Test
    fun `description parser refuses external entity declarations`() {
        val xml = """
            <!DOCTYPE root [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <root><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
            <UDN>uuid:evil</UDN><friendlyName>&xxe;</friendlyName></device></root>
        """.trimIndent().toByteArray(Charsets.UTF_8)

        try {
            parseDesktopUpnpRendererDescription(xml, URI("http://192.168.1.20/device.xml"))
            throw AssertionError("An XML external entity declaration must be rejected.")
        } catch (_: SAXException) {
        }
    }

    @Test
    fun `discovery fetches only numeric locations belonging to the SSDP responder`() = runBlocking {
        val source = InetAddress.getByName("192.168.1.20")
        val fetched = mutableListOf<URI>()
        val discovery = DesktopUpnpRendererDiscovery(
            ssdpSearch = DesktopSsdpSearch {
                listOf(
                    DesktopSsdpReply(source, URI("http://192.168.1.20:1400/xml/device.xml")),
                    DesktopSsdpReply(source, URI("http://192.168.1.20:1400/xml/device.xml")),
                    DesktopSsdpReply(source, URI("http://192.168.1.99:1400/other.xml")),
                    DesktopSsdpReply(source, URI("http://renderer.example/device.xml")),
                )
            },
            descriptionLoader = DesktopUpnpDescriptionLoader { uri ->
                fetched += uri
                rendererDescription().toByteArray(Charsets.UTF_8)
            },
        )

        val devices = discovery.discover(timeoutMillis = 1_000)

        assertEquals(1, fetched.size)
        assertEquals(listOf("Living Room DAC"), devices.map(DesktopUpnpRendererDevice::friendlyName))
    }

    @Test
    fun `SSDP location must match sender address`() {
        assertTrue(
            isDesktopUpnpLocationFromResponder(
                URI("http://192.168.1.20:1400/device.xml"),
                InetAddress.getByName("192.168.1.20"),
            ),
        )
        assertFalse(
            isDesktopUpnpLocationFromResponder(
                URI("http://192.168.1.99:1400/device.xml"),
                InetAddress.getByName("192.168.1.20"),
            ),
        )
        assertTrue(
            isDesktopUpnpLocationFromResponder(
                URI("https://192.168.1.20:1400/device.xml"),
                InetAddress.getByName("192.168.1.20"),
            ),
        )
        assertTrue(
            isDesktopUpnpLocationFromResponder(
                URI("http://[::1]:1400/device.xml"),
                InetAddress.getByName("::1"),
            ),
        )
    }

    @Test
    fun `M-SEARCH can target OpenHome Playlist service namespace`() {
        val request = String(desktopUpnpMSearchRequest("urn:av-openhome-org:service:Playlist:1"), Charsets.US_ASCII)
        assertTrue(request.contains("ST: urn:av-openhome-org:service:Playlist:1\r\n"))
    }
}

private fun rendererDescription(): String = """
    <?xml version="1.0" encoding="utf-8"?>
    <root xmlns="urn:schemas-upnp-org:device-1-0">
      <URLBase>http://192.168.1.20:1400/renderer/</URLBase>
      <device>
        <deviceType>urn:schemas-upnp-org:device:MediaServer:1</deviceType>
        <UDN>uuid:root</UDN>
        <friendlyName>Root</friendlyName>
        <deviceList>
          <device>
            <deviceType>urn:schemas-upnp-org:device:MediaRenderer:2</deviceType>
            <UDN>uuid:renderer-1</UDN>
            <friendlyName>Living Room DAC</friendlyName>
            <manufacturer>Example Audio</manufacturer>
            <modelName>R1</modelName>
            <serviceList>
              <service>
                <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                <controlURL>av/control</controlURL>
                <eventSubURL>av/events</eventSubURL>
                <SCPDURL>av/service.xml</SCPDURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                <controlURL>/cm/control</controlURL>
                <eventSubURL>/cm/events</eventSubURL>
                <SCPDURL>/cm/service.xml</SCPDURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:1</serviceType>
                <controlURL>/rc/control</controlURL>
                <eventSubURL>/rc/events</eventSubURL>
                <SCPDURL>/rc/service.xml</SCPDURL>
              </service>
              <service>
                <serviceType>urn:schemas-upnp-org:service:RenderingControl:2</serviceType>
                <controlURL>/rc2/control</controlURL>
                <eventSubURL>/rc2/events</eventSubURL>
                <SCPDURL>/rc2/service.xml</SCPDURL>
              </service>
              <service>
                <serviceType>urn:av-openhome-org:service:Playlist:1</serviceType>
                <controlURL>oh/playlist-control-v1</controlURL>
                <eventSubURL>oh/playlist-events-v1</eventSubURL>
                <SCPDURL>oh/playlist-v1.xml</SCPDURL>
              </service>
              <service>
                <serviceType>urn:av-openhome-org:service:Playlist:2</serviceType>
                <controlURL>oh/playlist-control-v2</controlURL>
                <eventSubURL>oh/playlist-events-v2</eventSubURL>
                <SCPDURL>oh/playlist-v2.xml</SCPDURL>
              </service>
            </serviceList>
          </device>
        </deviceList>
      </device>
    </root>
""".trimIndent()
