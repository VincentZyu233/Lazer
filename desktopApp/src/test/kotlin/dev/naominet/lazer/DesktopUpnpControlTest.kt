package dev.naominet.lazer

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class DesktopUpnpControlTest {
    @Test
    fun `SOAP request uses advertised service type and escapes argument values`() {
        val request = String(
            buildDesktopUpnpSoapRequest(
                "urn:schemas-upnp-org:service:AVTransport:2",
                "SetAVTransportURI",
                linkedMapOf("InstanceID" to "0", "CurrentURI" to "http://host/a?x=1&y=2<3"),
            ),
            Charsets.UTF_8,
        )

        assertTrue(request.contains("<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:2\">") )
        assertTrue(request.contains("http://host/a?x=1&amp;y=2&lt;3"))
        assertFalse(request.contains("urn:schemas-upnp-org:service:AVTransport:1"))
    }

    @Test
    fun `SOAP request accepts OpenHome Playlist URN and rejects unsupported service namespaces`() {
        val request = String(
            buildDesktopUpnpSoapRequest(
                "urn:av-openhome-org:service:Playlist:1",
                "TransportState",
                emptyMap(),
            ),
            Charsets.UTF_8,
        )
        assertTrue(request.contains("xmlns:u=\"urn:av-openhome-org:service:Playlist:1\""))

        listOf(
            "urn:example-org:service:Playlist:1",
            "urn:av-openhome-org:service:Radio:1",
            "urn:schemas-upnp-org:service:ContentDirectory:1",
            "urn:av-openhome-org:service:Playlist:0",
        ).forEach { unsupported ->
            try {
                buildDesktopUpnpSoapRequest(unsupported, "Play", emptyMap())
                throw AssertionError("Unsupported service URN should be rejected: $unsupported")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test
    fun `default HTTP SOAP transport sends Playlist namespace and rejects an unknown namespace`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
        val receivedSoapAction = AtomicReference<String?>()
        val receivedBody = AtomicReference<String?>()
        server.createContext("/control") { exchange ->
            receivedSoapAction.set(exchange.requestHeaders.getFirst("SOAPAction"))
            receivedBody.set(exchange.requestBody.use { String(it.readBytes(), Charsets.UTF_8) })
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val validDevice = renderer().copy(
                descriptionUri = URI("$base/device.xml"),
                services = mapOf(
                    DesktopUpnpRendererServiceKind.AV_TRANSPORT to DesktopUpnpServiceEndpoint(
                        serviceType = "urn:av-openhome-org:service:Playlist:1",
                        controlUri = URI("$base/control"),
                        eventSubUri = null,
                        scpdUri = null,
                    ),
                ),
            )
            DesktopUpnpRendererClient().control(validDevice, DesktopUpnpTransportCommand.PLAY)

            assertEquals("\"urn:av-openhome-org:service:Playlist:1#Play\"", receivedSoapAction.get())
            assertTrue(receivedBody.get().orEmpty().contains("xmlns:u=\"urn:av-openhome-org:service:Playlist:1\""))

            val invalidDevice = validDevice.copy(
                services = validDevice.services + (
                    DesktopUpnpRendererServiceKind.AV_TRANSPORT to validDevice.services
                        .getValue(DesktopUpnpRendererServiceKind.AV_TRANSPORT)
                        .copy(serviceType = "urn:example-org:service:Playlist:1")
                    ),
            )
            try {
                DesktopUpnpRendererClient().control(invalidDevice, DesktopUpnpTransportCommand.PLAY)
                throw AssertionError("Unknown SOAP service namespaces must be rejected before sending.")
            } catch (_: IllegalArgumentException) {
            }
            assertEquals("\"urn:av-openhome-org:service:Playlist:1#Play\"", receivedSoapAction.get())
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `SOAP parser resolves response by local name and keeps renderer state strings`() {
        val response = """
            <env:Envelope xmlns:env="http://schemas.xmlsoap.org/soap/envelope/">
              <env:Body><v:GetTransportInfoResponse xmlns:v="urn:schemas-upnp-org:service:AVTransport:2">
                <CurrentTransportState>VENDOR_BUFFERING</CurrentTransportState>
                <CurrentTransportStatus>OK</CurrentTransportStatus><CurrentSpeed>1</CurrentSpeed>
              </v:GetTransportInfoResponse></env:Body>
            </env:Envelope>
        """.trimIndent().toByteArray()

        val values = parseDesktopUpnpSoapResponse(response, "GetTransportInfo")

        assertEquals("VENDOR_BUFFERING", values["CurrentTransportState"])
        assertEquals("OK", values["CurrentTransportStatus"])
        assertEquals("1", values["CurrentSpeed"])
    }

    @Test
    fun `SOAP parser reports UPnP faults and rejects missing response action`() {
        val fault = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body><s:Fault><detail><UPnPError>
                <errorCode>701</errorCode><errorDescription>Transition not available</errorDescription>
              </UPnPError></detail></s:Fault></s:Body>
            </s:Envelope>
        """.trimIndent().toByteArray()
        try {
            parseDesktopUpnpSoapResponse(fault, "Play")
            throw AssertionError("SOAP faults should be surfaced to the caller.")
        } catch (error: DesktopUpnpSoapFaultException) {
            assertEquals("Transition not available", error.message)
            assertEquals(701, error.errorCode)
            assertEquals("Play", error.action)
        }

        val wrongAction = """
            <s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">
              <s:Body><u:PauseResponse xmlns:u="urn:schemas-upnp-org:service:AVTransport:1"/></s:Body>
            </s:Envelope>
        """.trimIndent().toByteArray()
        try {
            parseDesktopUpnpSoapResponse(wrongAction, "Play")
            throw AssertionError("A response for a different action should be rejected.")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("PlayResponse"))
        }
    }

    @Test
    fun `control client queries capabilities and status and preserves unknown enum values`() = runBlocking {
        val calls = mutableListOf<Triple<DesktopUpnpRendererServiceKind, String, Map<String, String>>>()
        val client = DesktopUpnpRendererClient(DesktopUpnpSoapTransport { _, service, action, args ->
            calls += Triple(service, action, args)
            when (action) {
                "GetProtocolInfo" -> mapOf("Source" to "", "Sink" to "http-get:*:audio/flac:*")
                "GetTransportInfo" -> mapOf(
                    "CurrentTransportState" to "BUFFERING_VENDOR",
                    "CurrentTransportStatus" to "OK",
                    "CurrentSpeed" to "1",
                )
                "GetPositionInfo" -> mapOf(
                    "TrackURI" to "http://192.168.1.20/song.flac",
                    "TrackMetaData" to "<DIDL-Lite/>",
                    "RelTime" to "00:01:02.34567",
                    "TrackDuration" to "01:02:03.5",
                )
                else -> emptyMap()
            }
        })

        val status = client.readStatus(renderer())

        assertEquals("http-get:*:audio/flac:*", status.sinkProtocolInfo)
        assertEquals("BUFFERING_VENDOR", status.transportState)
        assertEquals("OK", status.transportStatus)
        assertEquals("http://192.168.1.20/song.flac", status.trackUri)
        assertEquals(62_345L, status.positionMillis)
        assertEquals(3_723_500L, status.durationMillis)
        assertEquals(listOf("GetProtocolInfo", "GetTransportInfo", "GetPositionInfo"), calls.map { it.second })
        assertEquals(mapOf("InstanceID" to "0"), calls[1].third)
    }

    @Test
    fun `GENA and lightweight polling merge only the fields they update`() {
        val initial = DesktopUpnpRendererStatus(
            sinkProtocolInfo = "http-get:*:audio/flac:*",
            avTransportActions = setOf("Play", "Pause", "Stop", "Seek"),
            currentTransportActions = setOf("Pause", "Stop"),
            supportsRelativeTimeSeek = true,
            renderingControlActions = setOf("GetVolume", "SetVolume"),
            rendererVolume = 37,
            rendererVolumeMaximum = 127,
            transportState = "PLAYING",
            trackUri = "http://renderer/one.flac",
            trackMetadata = "<item>one</item>",
            positionMillis = 2_000L,
            durationMillis = 10_000L,
        )
        val eventUpdated = mergeDesktopUpnpEventStatus(
            initial,
            DesktopUpnpEvent(
                sid = "uuid:12345678-1234-1234-1234-123456789abc",
                sequence = 1L,
                properties = mapOf(
                    "TransportState" to "PAUSED_PLAYBACK",
                    "CurrentTransportActions" to "Play,Stop",
                    "RelativeTimePosition" to "00:00:05.250",
                    "CurrentTrackURI" to "http://renderer/two.flac",
                    "CurrentTrackMetaData" to "<item>two</item>",
                ),
            ),
        )

        assertEquals("PAUSED_PLAYBACK", eventUpdated.transportState)
        assertEquals(setOf("Play", "Stop"), eventUpdated.currentTransportActions)
        assertEquals(5_250L, eventUpdated.positionMillis)
        assertEquals(10_000L, eventUpdated.durationMillis)
        assertEquals("http://renderer/two.flac", eventUpdated.trackUri)
        assertEquals("<item>two</item>", eventUpdated.trackMetadata)
        assertEquals(initial.sinkProtocolInfo, eventUpdated.sinkProtocolInfo)
        assertEquals(initial.avTransportActions, eventUpdated.avTransportActions)

        val polled = mergeDesktopUpnpPlaybackSnapshot(
            eventUpdated,
            DesktopUpnpRendererStatus(transportState = "STOPPED", positionMillis = 0L, isPlaybackSnapshot = true),
        )
        assertEquals("STOPPED", polled.transportState)
        assertEquals(0L, polled.positionMillis)
        assertEquals(eventUpdated.trackUri, polled.trackUri)
        assertEquals(eventUpdated.currentTransportActions, polled.currentTransportActions)
        assertEquals(eventUpdated.renderingControlActions, polled.renderingControlActions)
        assertEquals(37, polled.rendererVolume)
        assertEquals(127, polled.rendererVolumeMaximum)
        assertFalse(polled.isPlaybackSnapshot)
    }

    @Test
    fun `playback polling publishes connection loss and refreshes state after the next reconnect`() = runBlocking {
        var reachable = false
        val client = DesktopUpnpRendererClient(DesktopUpnpSoapTransport { _, _, action, _ ->
            if (!reachable) throw DesktopUpnpConnectionException(IOException("socket closed"))
            when (action) {
                "GetTransportInfo" -> mapOf(
                    "CurrentTransportState" to "PLAYING",
                    "CurrentTransportStatus" to "OK",
                    "CurrentSpeed" to "1",
                )
                "GetPositionInfo" -> mapOf(
                    "TrackURI" to "http://renderer/current.flac",
                    "RelTime" to "00:00:02",
                    "TrackDuration" to "00:00:10",
                )
                else -> emptyMap()
            }
        })
        val previous = DesktopUpnpRendererStatus(
            transportState = "PLAYING",
            transportStatus = "OK",
            trackUri = "http://renderer/current.flac",
            positionMillis = 8_000L,
            durationMillis = 10_000L,
            connectionState = DesktopUpnpConnectionState.CONNECTED,
        )

        val disconnected = client.readPlaybackSnapshot(renderer())
        val unavailable = mergeDesktopUpnpPlaybackSnapshot(previous, disconnected)

        assertEquals(DesktopUpnpConnectionState.DISCONNECTED, unavailable.connectionState)
        assertEquals("Renderer connection is temporarily unavailable.", unavailable.connectionError)
        assertEquals("UNAVAILABLE", unavailable.transportState)
        assertEquals("UNKNOWN", unavailable.transportStatus)
        assertEquals("http://renderer/current.flac", unavailable.trackUri)
        assertNull(unavailable.positionMillis)
        assertNull(unavailable.durationMillis)
        assertFalse(unavailable.isPlaybackSnapshot)

        reachable = true
        val reconnected = client.readPlaybackSnapshot(renderer())
        val recovered = mergeDesktopUpnpPlaybackSnapshot(unavailable, reconnected)

        assertEquals(DesktopUpnpConnectionState.CONNECTED, recovered.connectionState)
        assertNull(recovered.connectionError)
        assertEquals("PLAYING", recovered.transportState)
        assertEquals("OK", recovered.transportStatus)
        assertEquals(2_000L, recovered.positionMillis)
        assertEquals(10_000L, recovered.durationMillis)
    }

    @Test
    fun `playback polling does not disguise SOAP failures as connection loss`() = runBlocking {
        val client = DesktopUpnpRendererClient(DesktopUpnpSoapTransport { _, _, _, _ ->
            throw IOException("UPnP action fault")
        })

        try {
            client.readPlaybackSnapshot(renderer())
            throw AssertionError("A SOAP failure should keep its original failure behavior.")
        } catch (error: IOException) {
            assertEquals("UPnP action fault", error.message)
        }
    }

    @Test
    fun `playback polling preserves typed SOAP action faults`() = runBlocking {
        val fault = DesktopUpnpSoapFaultException("GetTransportInfo", 401, "Invalid Action")
        val client = DesktopUpnpRendererClient(DesktopUpnpSoapTransport { _, _, _, _ -> throw fault })

        try {
            client.readPlaybackSnapshot(renderer())
            throw AssertionError("SOAP action faults must remain visible to the caller.")
        } catch (error: DesktopUpnpSoapFaultException) {
            assertTrue(error === fault)
            assertEquals(401, error.errorCode)
        }
    }

    @Test
    fun `HTTP 404 and 410 control responses classify stale endpoint and disconnect playback polling`() = runBlocking {
        for (responseCode in listOf(404, 410)) {
            val server = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0)
            server.createContext("/av/control") { exchange ->
                exchange.requestBody.use { it.readBytes() }
                exchange.sendResponseHeaders(responseCode, -1)
                exchange.close()
            }
            server.start()
            try {
                val endpointUri = URI("http://127.0.0.1:${server.address.port}/av/control")
                val original = renderer()
                val endpoint = original.services.getValue(DesktopUpnpRendererServiceKind.AV_TRANSPORT)
                    .copy(controlUri = endpointUri)
                val device = original.copy(
                    descriptionUri = URI("http://127.0.0.1:${server.address.port}/device.xml"),
                    services = mapOf(DesktopUpnpRendererServiceKind.AV_TRANSPORT to endpoint),
                )
                val client = DesktopUpnpRendererClient()

                try {
                    client.readStatus(device)
                    throw AssertionError("HTTP $responseCode should be surfaced as a typed stale endpoint.")
                } catch (error: DesktopUpnpStaleEndpointException) {
                    assertEquals(DesktopUpnpRendererServiceKind.AV_TRANSPORT, error.serviceKind)
                    assertEquals(responseCode, error.statusCode)
                }

                val snapshot = client.readPlaybackSnapshot(device)
                assertTrue(snapshot.isPlaybackSnapshot)
                assertEquals(DesktopUpnpConnectionState.DISCONNECTED, snapshot.connectionState)
                assertEquals("UNAVAILABLE", snapshot.transportState)
                assertEquals("UNKNOWN", snapshot.transportStatus)
                assertTrue(snapshot.connectionError.orEmpty().contains("HTTP $responseCode"))
                assertTrue(snapshot.connectionError.orEmpty().contains("rediscovery is required"))

                val previous = DesktopUpnpRendererStatus(
                    transportState = "PLAYING",
                    transportStatus = "OK",
                    trackUri = "http://renderer/current.flac",
                    positionMillis = 8_000L,
                    durationMillis = 10_000L,
                    connectionState = DesktopUpnpConnectionState.CONNECTED,
                )
                val merged = mergeDesktopUpnpPlaybackSnapshot(previous, snapshot)
                assertEquals(DesktopUpnpConnectionState.DISCONNECTED, merged.connectionState)
                assertEquals(snapshot.connectionError, merged.connectionError)
                assertEquals("http://renderer/current.flac", merged.trackUri)
                assertNull(merged.positionMillis)
                assertNull(merged.durationMillis)
            } finally {
                server.stop(0)
            }
        }
    }

    @Test
    fun `status loads and caches SCPD actions and reads current transport actions`() = runBlocking {
        var scpdLoads = 0
        val calls = mutableListOf<String>()
        val client = DesktopUpnpRendererClient(
            soap = DesktopUpnpSoapTransport { _, _, action, _ ->
                calls += action
                when (action) {
                    "GetProtocolInfo" -> mapOf("Sink" to "http-get:*:audio/flac:*")
                    "GetTransportInfo" -> mapOf("CurrentTransportState" to "PLAYING")
                    "GetCurrentTransportActions" -> mapOf("Actions" to "Play,Pause,Stop,Seek")
                    else -> emptyMap()
                }
            },
            scpd = DesktopUpnpScpdTransport {
                scpdLoads++
                rendererScpd()
            },
        )
        val device = rendererWithScpd()

        val first = client.readStatus(device)
        client.readStatus(device)

        assertEquals(1, scpdLoads)
        client.invalidateCapabilities(device)
        client.readStatus(device)
        assertEquals(2, scpdLoads)
        assertEquals(setOf("Play", "Pause", "Stop", "Seek", "SetAVTransportURI", "GetCurrentTransportActions"), first.avTransportActions)
        assertEquals(setOf("Play", "Pause", "Stop", "Seek"), first.currentTransportActions)
        assertEquals(true, first.supportsRelativeTimeSeek)
        assertEquals(3, calls.count { it == "GetCurrentTransportActions" })
    }

    @Test
    fun `status reads renderer volume using RenderingControl capability and device range`() = runBlocking {
        val calls = mutableListOf<Triple<DesktopUpnpRendererServiceKind, String, Map<String, String>>>()
        val client = DesktopUpnpRendererClient(
            soap = DesktopUpnpSoapTransport { _, service, action, args ->
                calls += Triple(service, action, args)
                when (action) {
                    "GetTransportInfo" -> mapOf("CurrentTransportState" to "STOPPED")
                    "GetVolume" -> mapOf("CurrentVolume" to "64")
                    else -> emptyMap()
                }
            },
            scpd = DesktopUpnpScpdTransport { uri ->
                if (uri.path.contains("rendering")) renderingControlScpd("100") else rendererScpd()
            },
        )

        val status = client.readStatus(rendererWithRenderingControlScpd())
        val volumeCall = calls.single { it.second == "GetVolume" }

        assertEquals(setOf("GetVolume", "SetVolume"), status.renderingControlActions)
        assertEquals(64, status.rendererVolume)
        assertEquals(100, status.rendererVolumeMaximum)
        assertEquals(DesktopUpnpRendererServiceKind.RENDERING_CONTROL, volumeCall.first)
        assertEquals(linkedMapOf("InstanceID" to "0", "Channel" to "Master"), volumeCall.third)
        assertTrue(desktopUpnpRendererVolumeAdjustEnabled(status))
        assertTrue(desktopUpnpRendererVolumeSliderEnabled(status))
    }

    @Test
    fun `setting renderer volume reads back the device accepted value`() = runBlocking {
        var deviceVolume = 40
        val calls = mutableListOf<Triple<DesktopUpnpRendererServiceKind, String, Map<String, String>>>()
        val client = DesktopUpnpRendererClient(
            soap = DesktopUpnpSoapTransport { _, service, action, args ->
                calls += Triple(service, action, args)
                when (action) {
                    "GetTransportInfo" -> mapOf("CurrentTransportState" to "STOPPED")
                    "GetVolume" -> mapOf("CurrentVolume" to deviceVolume.toString())
                    "SetVolume" -> {
                        deviceVolume = 47
                        emptyMap()
                    }
                    else -> emptyMap()
                }
            },
            scpd = DesktopUpnpScpdTransport { uri ->
                if (uri.path.contains("rendering")) renderingControlScpd("100") else rendererScpd()
            },
        )

        val status = client.setVolume(rendererWithRenderingControlScpd(), 50)
        val setCall = calls.single { it.second == "SetVolume" }

        assertEquals(47, status.rendererVolume)
        assertEquals(DesktopUpnpRendererServiceKind.RENDERING_CONTROL, setCall.first)
        assertEquals(
            linkedMapOf("InstanceID" to "0", "Channel" to "Master", "DesiredVolume" to "50"),
            setCall.third,
        )
        assertEquals(2, calls.count { it.second == "GetVolume" })
    }

    @Test
    fun `unknown device volume scale supports one-step adjustment without assuming percent`() = runBlocking {
        var deviceVolume = 27
        var requestedVolume: String? = null
        val client = DesktopUpnpRendererClient(
            soap = DesktopUpnpSoapTransport { _, _, action, args ->
                when (action) {
                    "GetTransportInfo" -> mapOf("CurrentTransportState" to "STOPPED")
                    "GetVolume" -> mapOf("CurrentVolume" to deviceVolume.toString())
                    "SetVolume" -> {
                        requestedVolume = args["DesiredVolume"]
                        deviceVolume = 31
                        emptyMap()
                    }
                    else -> emptyMap()
                }
            },
            scpd = DesktopUpnpScpdTransport { uri ->
                if (uri.path.contains("rendering")) renderingControlScpd("Vendor defined") else rendererScpd()
            },
        )

        val status = client.adjustVolume(rendererWithRenderingControlScpd(), 1)

        assertNull(status.rendererVolumeMaximum)
        assertEquals("28", requestedVolume)
        assertEquals(31, status.rendererVolume)
        assertTrue(desktopUpnpRendererVolumeAdjustEnabled(status))
        assertFalse(desktopUpnpRendererVolumeSliderEnabled(status))
    }

    @Test
    fun `renderer volume writes require both advertised read and write actions`() = runBlocking {
        val calls = mutableListOf<String>()
        val client = DesktopUpnpRendererClient(
            soap = DesktopUpnpSoapTransport { _, _, action, _ ->
                calls += action
                if (action == "GetVolume") mapOf("CurrentVolume" to "5") else emptyMap()
            },
            scpd = DesktopUpnpScpdTransport { uri ->
                if (uri.path.contains("rendering")) renderingControlScpd("10", actions = "<action><name>GetVolume</name></action>")
                else rendererScpd()
            },
        )

        try {
            client.setVolume(rendererWithRenderingControlScpd(), 6)
            throw AssertionError("A missing SetVolume action must not be invoked.")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("readable and writable"))
        }

        assertFalse("SetVolume" in calls)
    }

    @Test
    fun `volume step controls respect published and protocol value bounds`() {
        val knownScale = DesktopUpnpRendererStatus(
            renderingControlActions = setOf("GetVolume", "SetVolume"),
            rendererVolume = 100,
            rendererVolumeMaximum = 100,
        )
        val unknownScaleAtWireMaximum = DesktopUpnpRendererStatus(
            renderingControlActions = setOf("GetVolume", "SetVolume"),
            rendererVolume = 65_535,
        )

        assertTrue(desktopUpnpRendererVolumeStepEnabled(knownScale, -1))
        assertFalse(desktopUpnpRendererVolumeStepEnabled(knownScale, 1))
        assertFalse(desktopUpnpRendererVolumeStepEnabled(unknownScaleAtWireMaximum, 1))
        assertFalse(desktopUpnpRendererVolumeStepEnabled(knownScale, 0))
    }

    @Test
    fun `failed SCPD read leaves actions unknown and does not fail status refresh`() = runBlocking {
        val client = DesktopUpnpRendererClient(
            soap = DesktopUpnpSoapTransport { _, _, action, _ ->
                if (action == "GetTransportInfo") mapOf("CurrentTransportState" to "STOPPED") else emptyMap()
            },
            scpd = DesktopUpnpScpdTransport { throw IOException("offline") },
        )

        val status = client.readStatus(rendererWithScpd())

        assertNull(status.avTransportActions)
        assertNull(status.supportsRelativeTimeSeek)
        assertTrue(status.supplementalErrors.any { it.contains("SCPD") })
    }

    @Test
    fun `action availability uses explicit SCPD and current action lists but preserves unknown`() {
        assertTrue(desktopUpnpActionEnabled(null, "Pause"))
        assertFalse(
            desktopUpnpActionEnabled(
                DesktopUpnpRendererStatus(avTransportActions = setOf("Play", "Stop")),
                "Pause",
            ),
        )
        assertFalse(
            desktopUpnpActionEnabled(
                DesktopUpnpRendererStatus(
                    avTransportActions = setOf("Play", "Pause", "Stop"),
                    currentTransportActions = setOf("Play", "Stop"),
                ),
                "Pause",
            ),
        )
        assertTrue(
            desktopUpnpRelativeTimeSeekEnabled(
                DesktopUpnpRendererStatus(
                    avTransportActions = setOf("Seek"),
                    currentTransportActions = setOf("Seek"),
                    supportsRelativeTimeSeek = true,
                ),
            ),
        )
        assertFalse(desktopUpnpRelativeTimeSeekEnabled(DesktopUpnpRendererStatus()))
    }

    @Test
    fun `control maps actions to AVTransport arguments and seek formats relative time`() = runBlocking {
        val calls = mutableListOf<Pair<String, Map<String, String>>>()
        val client = DesktopUpnpRendererClient(DesktopUpnpSoapTransport { _, _, action, args ->
            calls += action to args
            emptyMap()
        })
        val device = renderer()

        client.control(device, DesktopUpnpTransportCommand.PLAY)
        client.control(device, DesktopUpnpTransportCommand.PAUSE)
        client.control(device, DesktopUpnpTransportCommand.STOP)
        client.seek(device, 3_723_456)

        assertEquals("Play", calls[0].first)
        assertEquals(mapOf("InstanceID" to "0", "Speed" to "1"), calls[0].second)
        assertEquals("Pause", calls[1].first)
        assertEquals(mapOf("InstanceID" to "0"), calls[1].second)
        assertEquals("Stop", calls[2].first)
        assertEquals(mapOf("InstanceID" to "0"), calls[2].second)
        assertEquals("Seek", calls[3].first)
        assertEquals(
            mapOf("InstanceID" to "0", "Unit" to "REL_TIME", "Target" to "01:02:03.456"),
            calls[3].second,
        )
    }

    @Test
    fun `local media URI metadata and protocol matching preserve the advertised MIME`() {
        val sink = """
            http-get:*:audio/mpeg:*,http-get:*:audio/x-wav:*,rtsp-rtp-udp:*:audio/flac:*
        """.trimIndent()

        assertEquals("audio/x-wav", chooseDesktopUpnpHttpMime(sink, listOf("audio/wav", "audio/x-wav")))
        assertNull(chooseDesktopUpnpHttpMime(sink, listOf("audio/flac")))
        assertEquals("audio/flac", chooseDesktopUpnpHttpMime("http-get:*:*:*", listOf("audio/flac")))

        val didl = buildDesktopUpnpDidlMetadata(
            title = "A & B <Live>",
            artist = "Artist",
            album = "Album",
            resourceUri = URI("http://192.168.1.10:38000/abcdef"),
            mimeType = "audio/x-wav",
            fileSize = 12_345L,
            durationMillis = 61_234L,
        )
        assertTrue(didl.contains("A &amp; B &lt;Live&gt;"))
        assertTrue(didl.contains("protocolInfo=\"http-get:*:audio/x-wav:*\""))
        assertTrue(didl.contains("size=\"12345\""))
        assertTrue(didl.contains("duration=\"00:01:01.234\""))
        assertTrue(didl.contains("http://192.168.1.10:38000/abcdef"))
    }

    @Test
    fun `set media URI sends DIDL metadata through AVTransport`() = runBlocking {
        var call: Triple<String, Map<String, String>, DesktopUpnpRendererServiceKind>? = null
        val client = DesktopUpnpRendererClient(DesktopUpnpSoapTransport { _, service, action, args ->
            call = Triple(action, args, service)
            emptyMap()
        })
        val didl = "<DIDL-Lite/>"

        client.setMediaUri(renderer(), URI("http://192.168.1.10:38000/token"), didl)

        assertEquals(DesktopUpnpRendererServiceKind.AV_TRANSPORT, call?.third)
        assertEquals("SetAVTransportURI", call?.first)
        assertEquals(
            linkedMapOf(
                "InstanceID" to "0",
                "CurrentURI" to "http://192.168.1.10:38000/token",
                "CurrentURIMetaData" to didl,
            ),
            call?.second,
        )
    }

    @Test
    fun `time parser handles not implemented malformed and overflow values`() {
        assertEquals(0L, parseDesktopUpnpTime("00:00:00"))
        assertEquals(3_661_234L, parseDesktopUpnpTime("01:01:01.2349"))
        assertNull(parseDesktopUpnpTime("NOT_IMPLEMENTED"))
        assertNull(parseDesktopUpnpTime("00:60:00"))
        assertNull(parseDesktopUpnpTime("999999999999999999999:00:00"))
        assertEquals("100:00:00.007", formatDesktopUpnpTime(360_000_007))
    }

    @Test
    fun `control endpoint must stay on the discovered device origin`() {
        val origin = URI("http://192.168.1.20:1400/device.xml")

        assertTrue(isDesktopUpnpControlUriAllowed(origin, URI("http://192.168.1.20:1400/control")))
        assertFalse(isDesktopUpnpControlUriAllowed(origin, URI("http://192.168.1.99:1400/control")))
        assertFalse(isDesktopUpnpControlUriAllowed(origin, URI("https://192.168.1.20:1400/control")))
    }

    @Test
    fun `only whole local WAV and FLAC items can be sent to a renderer`() {
        fun track(path: String, source: DesktopTrackSource = DesktopTrackSource.LocalFile(path)) = TrackItem(
            id = 1,
            title = "Local track",
            artist = "Artist",
            album = "Album",
            durationMillis = 1_000L,
            coverUrl = null,
            playbackSource = source,
        )

        assertTrue(isNetworkRendererEligibleLocalTrack(track("C:/music/one.wav")))
        assertTrue(isNetworkRendererEligibleLocalTrack(track("C:/music/two.FLAC")))
        assertFalse(isNetworkRendererEligibleLocalTrack(track("C:/music/one.wav", DesktopTrackSource.Remote)))
        assertFalse(
            isNetworkRendererEligibleLocalTrack(
                track("C:/music/image.flac", DesktopTrackSource.LocalFile("C:/music/image.flac", cueSheetPath = "x.cue", cueTrackNumber = 1)),
            ),
        )
        assertFalse(isNetworkRendererEligibleLocalTrack(track("C:/music/one.mp3")))
    }

    @Test
    fun `renderer addresses must be numeric literals`() {
        assertEquals(
            "192.168.1.20",
            desktopUpnpNumericAddress(URI("http://192.168.1.20:1400/device.xml"))?.hostAddress,
        )
        assertNull(desktopUpnpNumericAddress(URI("http://renderer.example/device.xml")))
        assertNull(desktopUpnpNumericAddress(URI("http://999.1.1.1/device.xml")))
    }
}

private fun renderer(): DesktopUpnpRendererDevice {
    val descriptionUri = URI("http://192.168.1.20:1400/device.xml")
    val avTransport = DesktopUpnpServiceEndpoint(
        serviceType = "urn:schemas-upnp-org:service:AVTransport:2",
        controlUri = URI("http://192.168.1.20:1400/av/control"),
        eventSubUri = null,
        scpdUri = null,
    )
    val connectionManager = DesktopUpnpServiceEndpoint(
        serviceType = "urn:schemas-upnp-org:service:ConnectionManager:1",
        controlUri = URI("http://192.168.1.20:1400/cm/control"),
        eventSubUri = null,
        scpdUri = null,
    )
    return DesktopUpnpRendererDevice(
        udn = "uuid:test-renderer",
        friendlyName = "Test renderer",
        manufacturer = null,
        modelName = null,
        modelNumber = null,
        descriptionUri = descriptionUri,
        services = mapOf(
            DesktopUpnpRendererServiceKind.AV_TRANSPORT to avTransport,
            DesktopUpnpRendererServiceKind.CONNECTION_MANAGER to connectionManager,
        ),
    )
}

private fun rendererWithScpd(): DesktopUpnpRendererDevice {
    val original = renderer()
    val endpoint = original.services.getValue(DesktopUpnpRendererServiceKind.AV_TRANSPORT)
    return original.copy(
        services = original.services + (
            DesktopUpnpRendererServiceKind.AV_TRANSPORT to endpoint.copy(
                scpdUri = URI("http://192.168.1.20:1400/av/scpd.xml"),
            )
        ),
    )
}

private fun rendererWithRenderingControlScpd(): DesktopUpnpRendererDevice {
    val original = rendererWithScpd()
    val endpoint = DesktopUpnpServiceEndpoint(
        serviceType = "urn:schemas-upnp-org:service:RenderingControl:2",
        controlUri = URI("http://192.168.1.20:1400/rendering/control"),
        eventSubUri = null,
        scpdUri = URI("http://192.168.1.20:1400/rendering/scpd.xml"),
    )
    return original.copy(
        services = original.services + (DesktopUpnpRendererServiceKind.RENDERING_CONTROL to endpoint),
    )
}

private fun rendererScpd(): ByteArray = """
    <scpd xmlns="urn:schemas-upnp-org:service-1-0">
      <actionList>
        <action><name>Play</name></action>
        <action><name>Pause</name></action>
        <action><name>Stop</name></action>
        <action><name>SetAVTransportURI</name></action>
        <action><name>GetCurrentTransportActions</name></action>
        <action><name>Seek</name><argumentList><argument>
          <name>Unit</name><direction>in</direction><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable>
        </argument></argumentList></action>
      </actionList>
      <serviceStateTable><stateVariable sendEvents="no">
        <name>A_ARG_TYPE_SeekMode</name><dataType>string</dataType>
        <allowedValueList><allowedValue>REL_TIME</allowedValue><allowedValue>TRACK_NR</allowedValue></allowedValueList>
      </stateVariable></serviceStateTable>
    </scpd>
""".trimIndent().toByteArray(Charsets.UTF_8)

private fun renderingControlScpd(maximum: String, actions: String = """
    <action><name>GetVolume</name></action>
    <action><name>SetVolume</name></action>
""".trimIndent()): ByteArray = """
    <scpd xmlns="urn:schemas-upnp-org:service-1-0">
      <actionList>$actions</actionList>
      <serviceStateTable><stateVariable sendEvents="no">
        <name>Volume</name><dataType>ui2</dataType><allowedValueRange>
          <minimum>0</minimum><maximum>$maximum</maximum><step>1</step>
        </allowedValueRange>
      </stateVariable></serviceStateTable>
    </scpd>
""".trimIndent().toByteArray(Charsets.UTF_8)
