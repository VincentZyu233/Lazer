package dev.naominet.lazer

import com.sun.jna.Pointer
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopUpnpRecoveryIntegrationTest {
    @Test
    fun `stale endpoint rediscovers same udn restores playback and swaps queue media lease`() = runBlocking {
        val directory = Files.createTempDirectory("upnp-recovery-integration").toRealPath()
        val audioFile = directory.resolve("fixture.wav")
        Files.write(audioFile, minimalWav())
        val queueStore = DesktopLocalPlaybackQueueStore(directory.resolve("queue.json"))
        val previousDeviceIdentity = DesktopSettings.hifiDeviceIdentity
        val renderer = LoopbackRenderer()
        val loopback = InetAddress.getByName("127.0.0.1")
        val oldDescription = renderer.descriptionUri("old")
        val newDescription = renderer.descriptionUri("new")
        val descriptionLoader = DesktopUpnpDescriptionLoader { uri ->
            uri.toURL().openStream().use { it.readBytes() }
        }
        val recoveryLocation = AtomicReference(oldDescription)
        val controllerDiscovery = DesktopUpnpRendererDiscovery(
            ssdpSearch = DesktopSsdpSearch {
                listOf(DesktopSsdpReply(loopback, recoveryLocation.get()))
            },
            descriptionLoader = descriptionLoader,
        )
        val library = fakeNativeAudioLibrary()
        val controller = DesktopPlayerController(
            DesktopPlayerControllerTestOverrides(
                nativeAudioLibrary = library,
                enumerateOutputDevices = { emptyList() },
                awaitRecoveryRetry = { delay(1L) },
                localPlaybackQueueStore = queueStore,
                networkRendererDiscovery = controllerDiscovery,
            ),
        )

        try {
            onUi { controller.discoverNetworkRenderers() }
            awaitCondition { onUiValue { controller.networkRendererDevices.singleOrNull()?.descriptionUri == oldDescription } }
            val oldDevice = onUiValue { controller.networkRendererDevices.single() }
            assertEquals("upnp:uuid:loopback-renderer", oldDevice.identity)
            val track = TrackItem(
                id = 731_400L,
                title = "UPnP recovery fixture",
                artist = "Integration test",
                album = "Loopback renderer",
                durationMillis = 120_000L,
                coverUrl = null,
                playbackSource = DesktopTrackSource.LocalFile(audioFile.toAbsolutePath().toString()),
            )
            onUi { controller.playTrack(track) }
            awaitCondition { onUiValue { controller.nowPlaying?.id == track.id } }
            onUi { controller.sendCurrentLocalTrackToNetworkRenderer(oldDevice) }

            awaitCondition {
                renderer.oldSetUris.isNotEmpty() &&
                    onUiValue { controller.networkRendererPlayingIdentity == oldDevice.identity }
            }
            val oldMediaUri = URI(renderer.oldSetUris.last())
            assertEquals(200, httpStatus(oldMediaUri))

            // The old endpoint stays alive through setup and becomes stale only after the
            // controller has a complete local media lease and an active renderer session.
            recoveryLocation.set(newDescription)
            renderer.oldEndpointGone.set(true)

            awaitCondition(timeoutMillis = 12_000L) {
                val recoveredUri = renderer.newSetUris.lastOrNull()?.let(::URI)
                val status = onUiValue { controller.networkRendererStatusByIdentity[oldDevice.identity] }
                recoveredUri != null && status?.connectionState == DesktopUpnpConnectionState.CONNECTED &&
                    status.trackUri == recoveredUri.toASCIIString() &&
                    status.transportState.equals("PLAYING", ignoreCase = true) &&
                    status.positionMillis?.let { kotlin.math.abs(it - RECOVERY_POSITION_MILLIS) <= 3_000L } == true
            }

            val recoveredMediaUri = URI(renderer.newSetUris.last())
            assertFalse(oldMediaUri == recoveredMediaUri)
            assertArrayEquals(Files.readAllBytes(audioFile), httpBody(recoveredMediaUri))
            assertFalse("the previous server and lease should be retired after confirmation", canRead(oldMediaUri))
            assertTrue(renderer.discoveryDescriptionRequests.get() >= 1)
            assertTrue(renderer.oldControlActions.contains("GetTransportInfo"))
            assertTrue(renderer.newControlActions.containsAll(listOf("SetAVTransportURI", "Play")))
            assertTrue(renderer.newSeekTargets.any { kotlin.math.abs(it - RECOVERY_POSITION_MILLIS) <= 3_000L })
        } finally {
            onUi { controller.dispose() }
            renderer.close()
            DesktopSettings.hifiDeviceIdentity = previousDeviceIdentity
            Files.deleteIfExists(directory.resolve("queue.json"))
            Files.deleteIfExists(audioFile)
            Files.deleteIfExists(directory)
        }
    }

    private suspend fun awaitCondition(
        timeoutMillis: Long = 5_000L,
        condition: suspend () -> Boolean,
    ) {
        withTimeout(timeoutMillis) {
            while (!condition()) delay(10L)
        }
    }

    private suspend fun <T> onUiValue(block: () -> T): T = withContext(Dispatchers.Swing) { block() }

    private suspend fun onUi(block: () -> Unit) = withContext(Dispatchers.Swing) { block() }

    private fun canRead(uri: URI): Boolean = runCatching { httpStatus(uri) == 200 }.getOrDefault(false)

    private fun httpBody(uri: URI): ByteArray {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 1_000
        connection.readTimeout = 1_000
        return try {
            assertEquals(200, connection.responseCode)
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun httpStatus(uri: URI): Int {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 1_000
        connection.readTimeout = 1_000
        return try {
            connection.requestMethod = "GET"
            val code = connection.responseCode
            if (code in 200..299) connection.inputStream.use { it.copyTo(java.io.OutputStream.nullOutputStream()) }
            code
        } finally {
            connection.disconnect()
        }
    }

    private fun minimalWav(): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(36)
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16)
        putShort(1)
        putShort(2)
        putInt(44_100)
        putInt(176_400)
        putShort(4)
        putShort(16)
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(0)
    }.array()

    private fun fakeNativeAudioLibrary(): LazerAudioLibrary = java.lang.reflect.Proxy.newProxyInstance(
        LazerAudioLibrary::class.java.classLoader,
        arrayOf(LazerAudioLibrary::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "lazer_audio_create" -> Pointer(1L)
            "lazer_audio_snapshot" -> {
                val snapshot = arguments!![1] as LazerAudioSnapshot
                snapshot.state = LAZER_AUDIO_STATE_PLAYING
                snapshot.positionMillis = 0L
                snapshot.durationMillis = 120_000L
                snapshot.bufferedPercent = 100
                LAZER_AUDIO_OK
            }
            else -> when (method.returnType) {
                Integer.TYPE -> LAZER_AUDIO_OK
                java.lang.Long.TYPE -> 0L
                java.lang.Boolean.TYPE -> false
                else -> null
            }
        }
    } as LazerAudioLibrary

    private companion object {
        const val RECOVERY_POSITION_MILLIS = 54_000L
    }

    private class LoopbackRenderer : AutoCloseable {
        private val httpServer = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        private val serverExecutor = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "upnp-recovery-fixture").apply { isDaemon = true }
        }
        private val mediaUri = AtomicReference<String?>(null)
        private val transportState = AtomicReference("STOPPED")
        private val positionMillis = java.util.concurrent.atomic.AtomicLong(RECOVERY_POSITION_MILLIS)
        val oldEndpointGone = AtomicBoolean(false)
        val oldSetUris = CopyOnWriteArrayList<String>()
        val newSetUris = CopyOnWriteArrayList<String>()
        val newSeekTargets = CopyOnWriteArrayList<Long>()
        val oldControlActions = CopyOnWriteArrayList<String>()
        val newControlActions = CopyOnWriteArrayList<String>()
        val discoveryDescriptionRequests = java.util.concurrent.atomic.AtomicInteger()

        init {
            httpServer.executor = serverExecutor
            httpServer.createContext("/") { exchange -> handle(exchange) }
            httpServer.start()
        }

        fun descriptionUri(endpoint: String): URI = URI("http://127.0.0.1:${httpServer.address.port}/$endpoint/device.xml")

        private fun handle(exchange: HttpExchange) {
            try {
                val path = exchange.requestURI.path
                val endpoint = when {
                    path.startsWith("/old/") -> "old"
                    path.startsWith("/new/") -> "new"
                    else -> null
                }
                if (endpoint == null) {
                    respond(exchange, 404, ByteArray(0))
                    return
                }
                if (path.endsWith("/device.xml")) {
                    if (endpoint == "new") discoveryDescriptionRequests.incrementAndGet()
                    respond(exchange, 200, deviceDescription(endpoint).toByteArray(Charsets.UTF_8), "text/xml")
                    return
                }
                if (path.endsWith("/av/scpd.xml")) {
                    respond(exchange, 200, avTransportScpd().toByteArray(Charsets.UTF_8), "text/xml")
                    return
                }
                if (path.endsWith("/control")) {
                    if (endpoint == "old" && oldEndpointGone.get()) {
                        respond(exchange, 404, ByteArray(0))
                        return
                    }
                    handleSoap(exchange, endpoint)
                    return
                }
                respond(exchange, 404, ByteArray(0))
            } catch (_: IOException) {
                runCatching { exchange.close() }
            } catch (_: Throwable) {
                runCatching { exchange.close() }
            }
        }

        private fun handleSoap(exchange: HttpExchange, endpoint: String) {
            val request = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
            val action = Regex("<u:([A-Za-z0-9]+)").find(request)?.groupValues?.get(1)
            if (action == null) {
                respond(exchange, 400, ByteArray(0))
                return
            }
            if (endpoint == "old") oldControlActions += action else newControlActions += action
            when (action) {
                "SetAVTransportURI" -> {
                    val uri = Regex("<CurrentURI>(.*?)</CurrentURI>", RegexOption.DOT_MATCHES_ALL)
                        .find(request)?.groupValues?.get(1)?.let(::unescapeXml)
                    if (uri == null) {
                        respond(exchange, 400, ByteArray(0))
                        return
                    }
                    mediaUri.set(uri)
                    transportState.set("STOPPED")
                    if (endpoint == "old") oldSetUris += uri else newSetUris += uri
                }
                "Play" -> transportState.set("PLAYING")
                "Pause" -> transportState.set("PAUSED_PLAYBACK")
                "Stop" -> transportState.set("STOPPED")
                "Seek" -> {
                    val target = Regex("<Target>(.*?)</Target>", RegexOption.DOT_MATCHES_ALL)
                        .find(request)?.groupValues?.get(1)?.let(::unescapeXml)?.let(::parseDesktopUpnpTime)
                    if (target == null) {
                        respond(exchange, 400, ByteArray(0))
                        return
                    }
                    positionMillis.set(target)
                    if (endpoint == "new") newSeekTargets += target
                }
            }
            val values = when (action) {
                "GetProtocolInfo" -> mapOf(
                    "Source" to "",
                    "Sink" to "http-get:*:audio/wav:*",
                )
                "GetTransportInfo" -> mapOf(
                    "CurrentTransportState" to transportState.get(),
                    "CurrentTransportStatus" to "OK",
                    "CurrentSpeed" to "1",
                )
                "GetPositionInfo" -> mapOf(
                    "Track" to "1",
                    "TrackDuration" to "00:02:00",
                    "TrackMetaData" to "",
                    "TrackURI" to mediaUri.get().orEmpty(),
                    "RelTime" to formatDesktopUpnpTime(positionMillis.get()),
                    "AbsTime" to formatDesktopUpnpTime(positionMillis.get()),
                    "RelCount" to "0",
                    "AbsCount" to "0",
                )
                "GetCurrentTransportActions" -> mapOf("Actions" to "Play,Pause,Stop,Seek")
                else -> emptyMap()
            }
            respond(exchange, 200, soapResponse(action, values).toByteArray(Charsets.UTF_8), "text/xml")
        }

        private fun deviceDescription(endpoint: String): String = """
            <?xml version="1.0" encoding="utf-8"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0">
              <URLBase>http://127.0.0.1:${httpServer.address.port}/$endpoint/</URLBase>
              <device>
                <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
                <UDN>uuid:loopback-renderer</UDN>
                <friendlyName>Loopback renderer</friendlyName>
                <serviceList>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType>
                    <SCPDURL>av/scpd.xml</SCPDURL>
                    <controlURL>av/control</controlURL>
                  </service>
                  <service>
                    <serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType>
                    <controlURL>connection/control</controlURL>
                  </service>
                </serviceList>
              </device>
            </root>
        """.trimIndent()

        private fun avTransportScpd(): String = """
            <scpd xmlns="urn:schemas-upnp-org:service-1-0">
              <actionList>
                <action><name>SetAVTransportURI</name></action>
                <action><name>Play</name></action>
                <action><name>Pause</name></action>
                <action><name>Stop</name></action>
                <action><name>GetTransportInfo</name></action>
                <action><name>GetPositionInfo</name></action>
                <action><name>GetCurrentTransportActions</name></action>
                <action><name>Seek</name><argumentList>
                  <argument><name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument>
                </argumentList></action>
              </actionList>
              <serviceStateTable>
                <stateVariable sendEvents="no">
                  <name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType>
                  <allowedValueList><allowedValue>REL_TIME</allowedValue></allowedValueList>
                </stateVariable>
              </serviceStateTable>
            </scpd>
        """.trimIndent()

        override fun close() {
            httpServer.stop(0)
            serverExecutor.shutdownNow()
        }

        private fun respond(
            exchange: HttpExchange,
            status: Int,
            bytes: ByteArray,
            contentType: String = "text/xml; charset=utf-8",
        ) {
            if (bytes.isNotEmpty()) exchange.responseHeaders.set("Content-Type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) } else exchange.close()
        }

        private fun soapResponse(action: String, values: Map<String, String>): String {
            val service = if (action == "GetProtocolInfo") "ConnectionManager" else "AVTransport"
            val serviceType = "urn:schemas-upnp-org:service:$service:1"
            return buildString {
                append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\">")
                append("<s:Body><u:").append(action).append("Response xmlns:u=\"")
                    .append(serviceType).append("\">")
                values.forEach { (key, value) ->
                    append('<').append(key).append('>').append(escapeXml(value)).append("</").append(key).append('>')
                }
                append("</u:").append(action).append("Response></s:Body></s:Envelope>")
            }
        }

        private fun unescapeXml(value: String): String = value
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")

        private fun escapeXml(value: String): String = value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }
}
