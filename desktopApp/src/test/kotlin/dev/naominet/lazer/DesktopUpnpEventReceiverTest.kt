package dev.naominet.lazer

import java.net.InetAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopUpnpEventReceiverTest {
    @Test
    fun `accepts callback after SID setup and flattens LastChange fields`() {
        val received = AtomicReference<DesktopUpnpEvent?>()
        DesktopUpnpEventReceiver(LOOPBACK, LOOPBACK, onEvent = received::set).use { receiver ->
            val callbackPath = receiver.callbackUri.rawPath
            assertTrue(callbackPath.matches(Regex("^/upnp/event/[A-Za-z0-9_-]{43}$")))
            assertEquals(412, post(receiver.callbackUri, headers(sequence = "0"), propertySet()).statusCode())

            receiver.acceptSid(SID)
            val first = post(receiver.callbackUri, headers(sequence = "0"), propertySet())
            assertEquals(200, first.statusCode())
            val event = received.get()
            assertNotNull(event)
            assertEquals(SID, event!!.sid)
            assertEquals(0L, event.sequence)
            assertEquals("PLAYING", event.transportState)
            assertEquals("OK", event.transportStatus)
            assertEquals("Pause,Stop,Seek", event.currentTransportActions)
            assertEquals("00:01:23", event.relativeTimePosition)
            assertEquals("00:04:56", event.currentTrackDuration)
            assertEquals("http://renderer.local/track.flac", event.currentTrackUri)
            assertEquals("<DIDL-Lite><item id=\"track-1\"/></DIDL-Lite>", event.currentTrackMetadata)
            assertEquals("http://renderer.local/track.flac", event.avTransportUri)
            assertEquals("http://renderer.local/track.flac", event.properties["AVTransportURI"])

            assertEquals(200, post(receiver.callbackUri, headers(sequence = "1"), propertySet()).statusCode())
            // Accept a newer state after packet loss; reject stale packets after that gap.
            assertEquals(200, post(receiver.callbackUri, headers(sequence = "3"), propertySet()).statusCode())
            assertEquals(412, post(receiver.callbackUri, headers(sequence = "2"), propertySet()).statusCode())
            assertEquals(3L, received.get()!!.sequence)
        }
    }

    @Test
    fun `requires renderer peer and exact callback path`() {
        DesktopUpnpEventReceiver(LOOPBACK, InetAddress.getByName("127.0.0.2"), onEvent = {}).use { receiver ->
            receiver.acceptSid(SID)
            assertEquals(403, post(receiver.callbackUri, headers(), propertySet()).statusCode())
        }

        DesktopUpnpEventReceiver(LOOPBACK, LOOPBACK, onEvent = {}).use { receiver ->
            receiver.acceptSid(SID)
            val wrongPath = URI(receiver.callbackUri.toString() + "?extra=1")
            assertEquals(404, post(wrongPath, headers(), propertySet()).statusCode())
            assertEquals(404, post(receiver.callbackUri.resolve("/wrong"), headers(), propertySet()).statusCode())
        }
    }

    @Test
    fun `validates method subscription headers SID and sequence`() {
        DesktopUpnpEventReceiver(LOOPBACK, LOOPBACK, onEvent = {}).use { receiver ->
            receiver.acceptSid(SID)
            assertEquals(405, request(receiver.callbackUri, "GET", headers(), "").statusCode())
            assertEquals(400, post(receiver.callbackUri, headers().filterKeys { it != "NT" }, propertySet()).statusCode())
            assertEquals(400, post(receiver.callbackUri, headers(nt = "upnp:other"), propertySet()).statusCode())
            assertEquals(400, post(receiver.callbackUri, headers(nts = "upnp:other"), propertySet()).statusCode())
            assertEquals(400, post(receiver.callbackUri, headers(sid = "uuid:not-a-sid"), propertySet()).statusCode())
            assertEquals(
                412,
                post(receiver.callbackUri, headers(sid = "uuid:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"), propertySet()).statusCode(),
            )
            assertEquals(400, post(receiver.callbackUri, headers(sequence = "4294967296"), propertySet()).statusCode())
            assertEquals(400, post(receiver.callbackUri, headers(sequence = "-1"), propertySet()).statusCode())

            receiver.clearSid()
            assertEquals(412, post(receiver.callbackUri, headers(), propertySet()).statusCode())
        }
    }

    @Test
    fun `rejects malformed XML and XXE without consuming sequence`() {
        val received = AtomicReference<DesktopUpnpEvent?>()
        DesktopUpnpEventReceiver(LOOPBACK, LOOPBACK, onEvent = received::set).use { receiver ->
            receiver.acceptSid(SID)
            assertEquals(400, post(receiver.callbackUri, headers(), "<propertyset".toByteArray()).statusCode())
            val xxe = """
                <!DOCTYPE propertyset [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">
                  <e:property><TransportState>&xxe;</TransportState></e:property>
                </e:propertyset>
            """.trimIndent().toByteArray()
            assertEquals(400, post(receiver.callbackUri, headers(), xxe).statusCode())
            assertNull(received.get())
            assertEquals(200, post(receiver.callbackUri, headers(), propertySet()).statusCode())
            assertEquals("PLAYING", received.get()!!.transportState)
        }
    }

    @Test
    fun `rejects oversized NOTIFY body`() {
        DesktopUpnpEventReceiver(LOOPBACK, LOOPBACK, onEvent = {}).use { receiver ->
            receiver.acceptSid(SID)
            val tooLarge = ByteArray(256 * 1024 + 1) { ' '.code.toByte() }
            assertEquals(413, post(receiver.callbackUri, headers(), tooLarge).statusCode())
        }
    }

    @Test
    fun `OpenHome-sized initial IdArray event can exceed AVTransport body limit`() {
        val received = AtomicReference<DesktopUpnpEvent?>()
        val encodedIds = "A".repeat(300 * 1024)
        val body = """
            <e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">
              <e:property><IdArray>$encodedIds</IdArray></e:property>
            </e:propertyset>
        """.trimIndent()

        DesktopUpnpEventReceiver(
            LOOPBACK,
            LOOPBACK,
            onEvent = received::set,
            maxEventBodyBytes = DESKTOP_OPENHOME_MAX_GENA_EVENT_BODY_BYTES,
        ).use { receiver ->
            receiver.acceptSid(SID)
            assertEquals(200, post(receiver.callbackUri, headers(), body).statusCode())
            assertEquals(encodedIds, received.get()?.properties?.get("IdArray"))
        }
    }

    @Test
    fun `notification sequence wraps from maximum uint32 to one`() {
        assertTrue(isNewerDesktopUpnpEventSequence(4_294_967_294L, 4_294_967_295L))
        assertTrue(isNewerDesktopUpnpEventSequence(4_294_967_295L, 1L))
        assertFalse(isNewerDesktopUpnpEventSequence(4_294_967_295L, 0L))
        assertFalse(isNewerDesktopUpnpEventSequence(2L, 1L))
    }

    private fun post(uri: URI, headers: Map<String, String> = headers(), body: ByteArray): HttpResponse<Void> =
        request(uri, "NOTIFY", headers, body)

    private fun post(uri: URI, headers: Map<String, String> = headers(), body: String): HttpResponse<Void> =
        post(uri, headers, body.toByteArray(StandardCharsets.UTF_8))

    private fun request(
        uri: URI,
        method: String,
        headers: Map<String, String>,
        body: String,
    ): HttpResponse<Void> = request(uri, method, headers, body.toByteArray(StandardCharsets.UTF_8))

    private fun request(
        uri: URI,
        method: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): HttpResponse<Void> {
        val builder = HttpRequest.newBuilder(uri)
        headers.forEach { (name, value) -> builder.header(name, value) }
        builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body))
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.discarding())
    }

    private fun headers(
        nt: String = "upnp:event",
        nts: String = "upnp:propchange",
        sid: String = SID,
        sequence: String = "0",
    ): Map<String, String> = mapOf("NT" to nt, "NTS" to nts, "SID" to sid, "SEQ" to sequence)

    private fun propertySet(): ByteArray = """
        <?xml version="1.0" encoding="utf-8"?>
        <e:propertyset xmlns:e="urn:schemas-upnp-org:event-1-0">
          <e:property><LastChange>&lt;Event&gt;&lt;InstanceID val="0"&gt;&lt;TransportState val="PLAYING"/&gt;&lt;TransportStatus val="OK"/&gt;&lt;CurrentTransportActions val="Pause,Stop,Seek"/&gt;&lt;RelativeTimePosition val="00:01:23"/&gt;&lt;CurrentTrackDuration val="00:04:56"/&gt;&lt;CurrentTrackURI val="http://renderer.local/track.flac"/&gt;&lt;CurrentTrackMetaData val="&amp;lt;DIDL-Lite&amp;gt;&amp;lt;item id=&amp;quot;track-1&amp;quot;/&amp;gt;&amp;lt;/DIDL-Lite&amp;gt;"/&gt;&lt;AVTransportURI val="http://renderer.local/track.flac"/&gt;&lt;/InstanceID&gt;&lt;/Event&gt;</LastChange></e:property>
        </e:propertyset>
    """.trimIndent().toByteArray(StandardCharsets.UTF_8)

    private companion object {
        val HTTP_CLIENT: HttpClient = HttpClient.newHttpClient()
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
        const val SID = "uuid:12345678-1234-1234-1234-123456789abc"
    }
}
