package dev.naominet.lazer

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopUpnpGenaTest {
    @Test
    fun `subscribe renew and unsubscribe follow GENA headers and bind to callback interface`() {
        val observed = CopyOnWriteArrayList<ObservedGenaRequest>()
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        server.createContext("/events") { exchange ->
            observed += ObservedGenaRequest(
                method = exchange.requestMethod,
                requestPath = exchange.requestURI.path,
                callback = exchange.requestHeaders.getFirst("CALLBACK"),
                nt = exchange.requestHeaders.getFirst("NT"),
                sid = exchange.requestHeaders.getFirst("SID"),
                timeout = exchange.requestHeaders.getFirst("TIMEOUT"),
                peerAddress = exchange.remoteAddress.address,
            )
            when (exchange.requestMethod) {
                "SUBSCRIBE" -> {
                    if (exchange.requestHeaders.getFirst("SID") == null) {
                        exchange.responseHeaders.set("SID", TEST_SID)
                        exchange.responseHeaders.set("TIMEOUT", "Second-40")
                    } else {
                        exchange.responseHeaders.set("TIMEOUT", "Second-60")
                    }
                    exchange.sendResponseHeaders(200, -1)
                }
                "UNSUBSCRIBE" -> exchange.sendResponseHeaders(200, -1)
                else -> exchange.sendResponseHeaders(405, -1)
            }
            exchange.close()
        }
        server.start()
        try {
            val device = rendererAt(server.address.port)
            val client = DesktopUpnpGenaClient()
            val rendererAddress = InetAddress.getByName("127.0.0.1")
            val localAddress = InetAddress.getByName("127.0.0.1")
            val callback = URI("http://127.0.0.1:45678/callback/random-token")

            val initial = client.subscribe(device, rendererAddress, localAddress, callback)
            val renewed = client.renew(device, rendererAddress, localAddress, initial.sid)
            client.unsubscribe(device, rendererAddress, localAddress, renewed.sid)

            assertEquals(TEST_SID, initial.sid)
            assertEquals(40L, initial.timeoutSeconds)
            assertEquals(30_000L, initial.renewalDelayMillis)
            assertEquals(60L, renewed.timeoutSeconds)
            assertEquals(listOf("SUBSCRIBE", "SUBSCRIBE", "UNSUBSCRIBE"), observed.map { it.method })
            assertEquals(listOf("/events", "/events", "/events"), observed.map { it.requestPath })
            assertEquals(callback.toASCIIString().let { "<$it>" }, observed[0].callback)
            assertEquals("upnp:event", observed[0].nt)
            assertEquals("Second-1800", observed[0].timeout)
            assertEquals(TEST_SID, observed[1].sid)
            assertNull(observed[1].callback)
            assertNull(observed[1].nt)
            assertEquals(TEST_SID, observed[2].sid)
            assertNull(observed[2].timeout)
            assertTrue(observed.all { it.peerAddress.address.contentEquals(localAddress.address) })
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `OpenHome service selection uses Playlist event URL for subscribe renew and unsubscribe`() {
        val device = rendererWithPlaylistAt(1400)
        val transport = RecordingGenaTransport()
        val client = DesktopUpnpGenaClient(transport)
        val address = InetAddress.getByName("127.0.0.1")
        val callback = URI("http://127.0.0.1:45678/callback/random-token")
        val playlistEventUri = device.services.getValue(DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST).eventSubUri

        val lease = client.subscribe(
            device = device,
            rendererAddress = address,
            localAddress = address,
            callbackUri = callback,
            serviceKind = DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST,
        )
        client.renew(
            device = device,
            rendererAddress = address,
            localAddress = address,
            sid = lease.sid,
            serviceKind = DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST,
        )
        client.unsubscribe(
            device = device,
            rendererAddress = address,
            localAddress = address,
            sid = lease.sid,
            serviceKind = DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST,
        )

        assertEquals(listOf(playlistEventUri, playlistEventUri, playlistEventUri), transport.eventSubUris)
    }

    @Test
    fun `selected service without an event URL fails closed`() {
        val completeDevice = rendererWithPlaylistAt(1400)
        val playlistService = completeDevice.services.getValue(DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST)
        val missingEventUrl = completeDevice.copy(
            services = completeDevice.services + (
                DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST to playlistService.copy(eventSubUri = null)
                ),
        )
        val transport = RecordingGenaTransport()
        val client = DesktopUpnpGenaClient(transport)
        val address = InetAddress.getByName("127.0.0.1")
        val callback = URI("http://127.0.0.1:45678/callback/random-token")

        listOf(rendererAt(1400), missingEventUrl).forEach { device ->
            expectGenaIOException {
                client.subscribe(
                    device,
                    address,
                    address,
                    callback,
                    DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST,
                )
            }
            expectGenaIOException {
                client.renew(
                    device,
                    address,
                    address,
                    TEST_SID,
                    DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST,
                )
            }
            expectGenaIOException {
                client.unsubscribe(
                    device,
                    address,
                    address,
                    TEST_SID,
                    DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST,
                )
            }
        }

        assertTrue(transport.eventSubUris.isEmpty())
    }

    @Test
    fun `selected service event URL must be same origin HTTP`() {
        val device = rendererWithPlaylistAt(1400)
        val playlistService = device.services.getValue(DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST)
        val invalidDevices = listOf(
            device.copy(
                services = device.services + (
                    DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST to playlistService.copy(
                        eventSubUri = URI("http://192.0.2.10:1400/playlist/events"),
                    )
                    ),
            ),
            device.copy(
                services = device.services + (
                    DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST to playlistService.copy(
                        eventSubUri = URI("https://127.0.0.1:1400/playlist/events"),
                    )
                    ),
            ),
        )
        val transport = RecordingGenaTransport()
        val client = DesktopUpnpGenaClient(transport)
        val address = InetAddress.getByName("127.0.0.1")

        invalidDevices.forEach { invalidDevice ->
            try {
                client.subscribe(
                    invalidDevice,
                    address,
                    address,
                    URI("http://127.0.0.1:45678/callback/random-token"),
                    DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST,
                )
                throw AssertionError("An invalid Playlist event URL must be rejected.")
            } catch (_: IllegalArgumentException) {
                // Expected.
            }
        }
        assertTrue(transport.eventSubUris.isEmpty())
    }

    @Test
    fun `subscription accepts infinite timeout and renewal delay saturates`() {
        assertNull(parseGenaTimeout("Second-infinite"))
        assertNull(parseGenaTimeout("second-INFINITE"))
        assertEquals(750L, DesktopUpnpGenaLease(TEST_SID, 1L).renewalDelayMillis)
        assertEquals(64_800_000L, DesktopUpnpGenaLease(TEST_SID, Long.MAX_VALUE).renewalDelayMillis)
    }

    @Test
    fun `event receiver retry policy is initially due and observes the first deadline`() {
        val policy = DesktopUpnpEventReceiverRetryPolicy()
        val failedAt = 10_000L

        assertTrue(policy.isAttemptDue(failedAt))
        policy.recordFailure(failedAt)

        val deadline = failedAt + 1_000_000_000L
        assertFalse(policy.isAttemptDue(deadline - 1L))
        assertTrue(policy.isAttemptDue(deadline))
    }

    @Test
    fun `event receiver retry policy increases delays and saturates at thirty seconds`() {
        val policy = DesktopUpnpEventReceiverRetryPolicy()
        val delaysMillis = listOf(1_000L, 3_000L, 6_000L, 12_000L, 30_000L, 30_000L, 30_000L)
        var failedAt = 0L

        for (delayMillis in delaysMillis) {
            assertTrue(policy.isAttemptDue(failedAt))
            policy.recordFailure(failedAt)
            val deadline = failedAt + delayMillis * 1_000_000L
            assertFalse(policy.isAttemptDue(deadline - 1L))
            assertTrue(policy.isAttemptDue(deadline))
            failedAt = deadline
        }
    }

    @Test
    fun `event receiver retry policy resets and handles monotonic clock wrap`() {
        val policy = DesktopUpnpEventReceiverRetryPolicy()
        val beforeWrap = Long.MAX_VALUE - 100_000_000L

        policy.recordFailure(beforeWrap)
        val wrappedDeadline = beforeWrap + 1_000_000_000L
        assertFalse(policy.isAttemptDue(wrappedDeadline - 1L))
        assertTrue(policy.isAttemptDue(wrappedDeadline))

        policy.reset()
        assertTrue(policy.isAttemptDue(beforeWrap))
        policy.recordFailure(beforeWrap)
        assertFalse(policy.isAttemptDue(beforeWrap + 999_999_999L))
        assertTrue(policy.isAttemptDue(beforeWrap + 1_000_000_000L))
    }

    @Test
    fun `initial subscription failure is retried without losing the callback session`() = runBlocking {
        val refreshRequests = Channel<Unit>(Channel.CONFLATED)
        val attempts = AtomicInteger()
        val recoveredLease = CompletableDeferred<DesktopUpnpGenaLease>()
        val replacement = DesktopUpnpGenaLease(TEST_SID, null)
        val manager = DesktopUpnpGenaSubscriptionManager(
            initialLease = null,
            refreshRequests = refreshRequests,
            retryDelayMillis = 15L,
            isCurrent = { true },
            subscribe = {
                if (attempts.incrementAndGet() == 1) throw IOException("Renderer is restarting")
                replacement
            },
            renew = { error("A lease-less session must subscribe before renewing") },
            installLease = { recoveredLease.complete(it); true },
            onLeaseChanged = { if (it != null) recoveredLease.complete(it) },
            onSidCleared = {},
            abandonLease = {},
        )
        val job = manager.start(this)
        try {
            assertEquals(replacement, withTimeout(2_000L) { recoveredLease.await() })
            assertEquals(2, attempts.get())
        } finally {
            job.cancelAndJoin()
            refreshRequests.close()
        }
    }

    @Test
    fun `reconnect refresh renews promptly and forgotten SID is replaced`() = runBlocking {
        val refreshRequests = Channel<Unit>(Channel.CONFLATED)
        val oldLease = DesktopUpnpGenaLease(TEST_SID, null)
        val newSid = "uuid:87654321-4321-4321-4321-cba987654321"
        val replacement = DesktopUpnpGenaLease(newSid, null)
        val renewedSid = CompletableDeferred<String>()
        val replacementLease = CompletableDeferred<DesktopUpnpGenaLease>()
        var acceptedSid: String? = oldLease.sid
        var activeLease: DesktopUpnpGenaLease? = oldLease
        var subscribeCalls = 0
        val manager = DesktopUpnpGenaSubscriptionManager(
            initialLease = oldLease,
            refreshRequests = refreshRequests,
            retryDelayMillis = 2_000L,
            isCurrent = { true },
            subscribe = {
                subscribeCalls += 1
                replacement
            },
            renew = { sid ->
                renewedSid.complete(sid)
                throw DesktopUpnpGenaHttpException(412, "SUBSCRIBE")
            },
            installLease = {
                acceptedSid = it.sid
                activeLease = it
                if (it == replacement) replacementLease.complete(it)
                true
            },
            onLeaseChanged = {
                activeLease = it
                if (it == replacement) replacementLease.complete(it)
            },
            onSidCleared = { acceptedSid = null; activeLease = null },
            abandonLease = {},
        )
        val job = manager.start(this)
        try {
            refreshRequests.send(Unit)
            assertEquals(oldLease.sid, withTimeout(1_000L) { renewedSid.await() })
            assertEquals(replacement, withTimeout(1_000L) { replacementLease.await() })
            assertEquals(newSid, acceptedSid)
            assertEquals(replacement, activeLease)
            assertEquals(1, subscribeCalls)
        } finally {
            job.cancelAndJoin()
            refreshRequests.close()
        }
    }

    @Test
    fun `subscription result from a closed session is abandoned`() = runBlocking {
        val refreshRequests = Channel<Unit>(Channel.CONFLATED)
        val sessionCurrent = AtomicBoolean(true)
        val subscribeStarted = CompletableDeferred<Unit>()
        val allowSubscribeToFinish = CompletableDeferred<Unit>()
        val abandonedLease = CompletableDeferred<DesktopUpnpGenaLease>()
        val replacement = DesktopUpnpGenaLease(TEST_SID, null)
        val manager = DesktopUpnpGenaSubscriptionManager(
            initialLease = null,
            refreshRequests = refreshRequests,
            retryDelayMillis = 15L,
            isCurrent = sessionCurrent::get,
            subscribe = {
                subscribeStarted.complete(Unit)
                allowSubscribeToFinish.await()
                replacement
            },
            renew = { error("A lease-less session must subscribe before renewing") },
            installLease = { sessionCurrent.get() },
            onLeaseChanged = {},
            onSidCleared = {},
            abandonLease = { abandonedLease.complete(it) },
        )
        val job = manager.start(this)
        try {
            withTimeout(1_000L) { subscribeStarted.await() }
            sessionCurrent.set(false)
            allowSubscribeToFinish.complete(Unit)
            assertEquals(replacement, withTimeout(1_000L) { abandonedLease.await() })
        } finally {
            job.cancelAndJoin()
            refreshRequests.close()
        }
    }

    @Test
    fun `invalid timeout and SID values are rejected`() {
        listOf(null, "Second-0", "Second--1", "infinite", "Second-2 trailing").forEach { value ->
            try {
                parseGenaTimeout(value)
                throw AssertionError("Invalid TIMEOUT should fail: $value")
            } catch (_: IOException) {
                // Expected.
            }
        }
        listOf("", "SID", "uuid:", "uuid:test", "uuid:test\r\nInjected: yes").forEach { value ->
            try {
                requireValidSid(value)
                throw AssertionError("Invalid SID should fail: $value")
            } catch (_: IOException) {
                // Expected.
            }
        }
    }

    @Test
    fun `rejects callbacks on a different host and event endpoints using HTTPS`() {
        val device = rendererAt(1400)
        val client = DesktopUpnpGenaClient(RecordingGenaTransport())
        val localAddress = InetAddress.getByName("127.0.0.1")
        val rendererAddress = InetAddress.getByName("127.0.0.1")
        try {
            client.subscribe(device, rendererAddress, localAddress, URI("http://192.0.2.1:1234/callback/token"))
            throw AssertionError("A callback outside the selected local interface must be rejected.")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        val httpsDevice = device.copy(
            descriptionUri = URI("https://127.0.0.1:1400/device.xml"),
            services = device.services + (DesktopUpnpRendererServiceKind.AV_TRANSPORT to
                DesktopUpnpServiceEndpoint(
                    serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
                    controlUri = URI("https://127.0.0.1:1400/control"),
                    eventSubUri = URI("https://127.0.0.1:1400/events"),
                    scpdUri = null,
                )),
        )
        try {
            client.subscribe(httpsDevice, rendererAddress, localAddress, URI("http://127.0.0.1:1234/callback/token"))
            throw AssertionError("HTTPS event subscriptions are not sent over a plain socket.")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}

private data class ObservedGenaRequest(
    val method: String,
    val requestPath: String,
    val callback: String?,
    val nt: String?,
    val sid: String?,
    val timeout: String?,
    val peerAddress: InetAddress,
)

private class RecordingGenaTransport : DesktopUpnpGenaTransport {
    val eventSubUris = CopyOnWriteArrayList<URI>()

    override fun subscribe(
        eventSubUri: URI,
        rendererAddress: InetAddress,
        localAddress: InetAddress,
        callbackUri: URI,
        requestedTimeoutSeconds: Long,
    ): DesktopUpnpGenaLease {
        eventSubUris += eventSubUri
        return DesktopUpnpGenaLease(TEST_SID, 60L)
    }

    override fun renew(
        eventSubUri: URI,
        rendererAddress: InetAddress,
        localAddress: InetAddress,
        sid: String,
        requestedTimeoutSeconds: Long,
    ): DesktopUpnpGenaLease {
        eventSubUris += eventSubUri
        return DesktopUpnpGenaLease(sid, 60L)
    }

    override fun unsubscribe(eventSubUri: URI, rendererAddress: InetAddress, localAddress: InetAddress, sid: String) {
        eventSubUris += eventSubUri
    }
}

private fun expectGenaIOException(block: () -> Unit) {
    try {
        block()
        throw AssertionError("The GENA request must fail when the selected service endpoint is missing.")
    } catch (_: IOException) {
        // Expected.
    }
}

private fun rendererAt(port: Int): DesktopUpnpRendererDevice {
    val origin = "http://127.0.0.1:$port"
    return DesktopUpnpRendererDevice(
        udn = "uuid:test-gena-renderer",
        friendlyName = "Test GENA renderer",
        manufacturer = null,
        modelName = null,
        modelNumber = null,
        descriptionUri = URI("$origin/device.xml"),
        services = mapOf(
            DesktopUpnpRendererServiceKind.AV_TRANSPORT to DesktopUpnpServiceEndpoint(
                serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
                controlUri = URI("$origin/control"),
                eventSubUri = URI("$origin/events"),
                scpdUri = null,
            ),
        ),
    )
}

private fun rendererWithPlaylistAt(port: Int): DesktopUpnpRendererDevice {
    val renderer = rendererAt(port)
    val origin = "http://127.0.0.1:$port"
    return renderer.copy(
        services = renderer.services + (
            DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST to DesktopUpnpServiceEndpoint(
                serviceType = "urn:av-openhome-org:service:Playlist:1",
                controlUri = URI("$origin/playlist/control"),
                eventSubUri = URI("$origin/playlist/events"),
                scpdUri = URI("$origin/playlist/scpd.xml"),
            )
            ),
    )
}

private const val TEST_SID = "uuid:12345678-1234-1234-1234-123456789abc"
