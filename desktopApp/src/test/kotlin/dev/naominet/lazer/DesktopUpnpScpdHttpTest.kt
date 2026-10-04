package dev.naominet.lazer

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopUpnpScpdHttpTest {
    @Test
    fun `does not follow same origin SCPD redirects`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val redirectedRequestReached = AtomicBoolean(false)
        server.createContext("/scpd.xml") { exchange ->
            exchange.responseHeaders.set("Location", "/final.xml")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/final.xml") { exchange ->
            redirectedRequestReached.set(true)
            val body = minimalScpd()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val device = rendererAt(server.address.port)
            val client = DesktopUpnpRendererClient(soap = statusSoap())

            val status = client.readStatus(device)

            assertNull(status.avTransportActions)
            assertFalse(redirectedRequestReached.get())
            assertTrue(status.supplementalErrors.any { it.contains("HTTP 302") })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `rejects an oversized SCPD response before XML parsing`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val body = ByteArray(256 * 1024 + 1) { ' '.code.toByte() }
        server.createContext("/scpd.xml") { exchange ->
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = DesktopUpnpRendererClient(soap = statusSoap())

            val status = client.readStatus(rendererAt(server.address.port))

            assertNull(status.avTransportActions)
            assertTrue(status.supplementalErrors.any { it.contains("size limit") })
        } finally {
            server.stop(0)
        }
    }
}

private fun rendererAt(port: Int): DesktopUpnpRendererDevice {
    val origin = "http://127.0.0.1:$port"
    return DesktopUpnpRendererDevice(
        udn = "uuid:test-http-renderer",
        friendlyName = "Test HTTP renderer",
        manufacturer = null,
        modelName = null,
        modelNumber = null,
        descriptionUri = URI("$origin/device.xml"),
        services = mapOf(
            DesktopUpnpRendererServiceKind.AV_TRANSPORT to DesktopUpnpServiceEndpoint(
                serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
                controlUri = URI("$origin/control"),
                eventSubUri = null,
                scpdUri = URI("$origin/scpd.xml"),
            ),
            DesktopUpnpRendererServiceKind.CONNECTION_MANAGER to DesktopUpnpServiceEndpoint(
                serviceType = "urn:schemas-upnp-org:service:ConnectionManager:1",
                controlUri = URI("$origin/connection"),
                eventSubUri = null,
                scpdUri = null,
            ),
        ),
    )
}

private fun statusSoap() = DesktopUpnpSoapTransport { _, _, action, _ ->
    when (action) {
        "GetProtocolInfo" -> mapOf("Sink" to "http-get:*:audio/flac:*")
        "GetTransportInfo" -> mapOf("CurrentTransportState" to "STOPPED")
        else -> emptyMap()
    }
}

private fun minimalScpd(): ByteArray = """
    <scpd xmlns="urn:schemas-upnp-org:service-1-0"><actionList><action><name>Play</name></action></actionList></scpd>
""".trimIndent().toByteArray(Charsets.UTF_8)
