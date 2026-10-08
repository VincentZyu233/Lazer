package dev.naominet.lazer

import java.io.IOException
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopOpenHomePlaylistTest {
    @Test
    fun `SCPD capabilities are discovered with typed input and output arguments`() {
        val capabilities = parseDesktopOpenHomePlaylistCapabilities(playlistScpd().toByteArray())

        assertEquals(
            listOf(
                DesktopOpenHomePlaylistArgument("AfterId", DesktopOpenHomePlaylistArgument.Direction.IN, "ui4"),
                DesktopOpenHomePlaylistArgument("Uri", DesktopOpenHomePlaylistArgument.Direction.IN, "string"),
                DesktopOpenHomePlaylistArgument("Metadata", DesktopOpenHomePlaylistArgument.Direction.IN, "string"),
                DesktopOpenHomePlaylistArgument("NewId", DesktopOpenHomePlaylistArgument.Direction.OUT, "ui4"),
            ),
            capabilities.actions.getValue("Insert"),
        )
        assertTrue("DeleteAll" in capabilities.actions)
        assertTrue(capabilities.actions.getValue("DeleteAll").isEmpty())
        // The fixture omits sendEvents. UPnP defines the omitted attribute as "yes".
        assertEquals(
            mapOf(
                "State0" to "string",
                "State1" to "ui4",
                "State2" to "bin.base64",
                "State3" to "boolean",
            ),
            capabilities.eventedStateVariables,
        )
    }

    @Test
    fun `SCPD evented state metadata honors yes and no and fails closed for malformed values`() {
        val capabilities = parseDesktopOpenHomePlaylistCapabilities(
            playlistScpd(
                sendEvents = mapOf(
                    "State0" to "yes",
                    "State1" to "no",
                    "State2" to "YES",
                    "State3" to "invalid",
                ),
            ).toByteArray(),
        )

        assertEquals(
            mapOf("State0" to "string", "State2" to "bin.base64"),
            capabilities.eventedStateVariables,
        )
    }

    @Test
    fun `SCPD sendEvents accepts numeric schema values and treats zero as not evented`() {
        val capabilities = parseDesktopOpenHomePlaylistCapabilities(
            playlistScpd(
                sendEvents = mapOf(
                    "State0" to "1",
                    "State1" to "0",
                    "State2" to " ",
                    "State3" to "no",
                ),
            ).toByteArray(),
        )

        assertEquals(mapOf("State0" to "string"), capabilities.eventedStateVariables)
    }

    @Test
    fun `IdArray parser reads unsigned big endian opaque IDs and enforces bounds`() {
        val encoded = java.util.Base64.getEncoder().encodeToString(
            byteArrayOf(
                0x01, 0x02, 0x03, 0x04,
                0x7f, 0x00, 0x00, 0x01,
                0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(),
            ),
        )
        assertEquals(listOf(0x01020304L, 0x7f000001L, 0xffffffffL), parseDesktopOpenHomePlaylistIdArray(encoded))
        assertEquals(listOf(1L), parseDesktopOpenHomePlaylistIdArray("AAAAAQ==\n", maximumEntries = 1))

        listOf("%%%", "AQI=", "AAAAAAAA", "AB==", "AQAAAA==").forEachIndexed { index, malformed ->
            try {
                parseDesktopOpenHomePlaylistIdArray(malformed, maximumEntries = if (index == 4) 0 else 2)
                throw AssertionError("Invalid or out-of-bound ID array must be rejected: $malformed")
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            parseDesktopOpenHomePlaylistIdArray("AAAAAA==")
            throw AssertionError("The reserved zero ID must not appear in the queue ID array.")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `client maps OpenHome SOAP actions arguments and chains returned insertion IDs`() = runBlocking {
        val calls = mutableListOf<Triple<String, Map<String, String>, DesktopUpnpRendererServiceKind>>()
        var nextInsertedId = 0
        val playlistXml = "<TrackList><Entry><Id>41</Id><Uri>https://music.test/a&amp;b.flac</Uri><Metadata>&lt;item&gt;A&lt;/item&gt;</Metadata></Entry></TrackList>"
        val client = DesktopOpenHomePlaylistClient(
            soap = DesktopUpnpSoapTransport { _, service, action, arguments ->
                calls += Triple(action, arguments, service)
                when (action) {
                    "TransportState" -> mapOf("Value" to "Playing")
                    "Id" -> mapOf("Value" to "4294967295")
                    "TracksMax" -> mapOf("Value" to "1000")
                    "IdArray" -> mapOf(
                        "Token" to "77",
                        "Array" to java.util.Base64.getEncoder().encodeToString(
                            byteArrayOf(0, 0, 0, 41, 0, 0, 3, 0x84.toByte()),
                        ),
                    )
                    "IdArrayChanged" -> mapOf("Value" to "true")
                    "ProtocolInfo" -> mapOf("Value" to "http-get:*:audio/flac:*")
                    "ReadList" -> mapOf("TrackList" to playlistXml)
                    "Insert" -> mapOf("NewId" to listOf(41, 900, 7)[nextInsertedId++].toString())
                    else -> emptyMap()
                }
            },
            scpd = DesktopUpnpScpdTransport { playlistScpd().toByteArray() },
        )
        val device = playlistDevice()

        assertEquals("Playing", client.transportState(device))
        assertEquals(0xffffffffL, client.id(device))
        assertEquals(1_000L, client.tracksMax(device))
        assertEquals(DesktopOpenHomePlaylistIdArray(77L, listOf(41L, 900L)), client.idArray(device))
        assertTrue(client.idArrayChanged(device, 77L))
        assertEquals("http-get:*:audio/flac:*", client.protocolInfo(device))
        assertEquals(
            listOf(DesktopOpenHomePlaylistTrack(41L, "https://music.test/a&b.flac", "<item>A</item>")),
            client.readList(device, listOf(41L, 900L)),
        )
        assertEquals(listOf(41L, 900L, 7L), client.insertAtHead(device, listOf("u1" to "m1", "u2" to "m2", "u3" to "m3")))
        client.deleteId(device, 900L)
        client.deleteAll(device)
        client.play(device)
        client.pause(device)
        client.stop(device)
        client.next(device)
        client.previous(device)
        client.seekId(device, 41L)
        client.seekSecondAbsolute(device, 65L)

        assertTrue(calls.all { it.third == DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST })
        assertEquals(
            listOf("0", "41", "900"),
            calls.filter { it.first == "Insert" }.map { it.second.getValue("AfterId") },
        )
        assertEquals(
            listOf(
                "AfterId" to "0", "Uri" to "u1", "Metadata" to "m1",
            ).associate { it.first to it.second },
            calls.first { it.first == "Insert" }.second,
        )
        assertEquals(mapOf("IdList" to "41 900"), calls.first { it.first == "ReadList" }.second)
        assertEquals(mapOf("Token" to "77"), calls.first { it.first == "IdArrayChanged" }.second)
        assertEquals(mapOf("Value" to "900"), calls.first { it.first == "DeleteId" }.second)
        assertEquals(mapOf("Value" to "41"), calls.first { it.first == "SeekId" }.second)
        assertEquals(mapOf("Value" to "65"), calls.first { it.first == "SeekSecondAbsolute" }.second)
        assertTrue(calls.any { it.first == "DeleteAll" && it.second.isEmpty() })
        assertTrue(listOf("Play", "Pause", "Stop", "Next", "Previous").all { action ->
            calls.any { it.first == action && it.second.isEmpty() }
        })
    }

    @Test
    fun `ReadList parser rejects malformed and oversized XML`() {
        assertEquals(emptyList<DesktopOpenHomePlaylistTrack>(), parseDesktopOpenHomePlaylistReadList("<TrackList/>"))
        listOf(
            "<NotTrackList/>",
            "<TrackList><Entry><Id>4294967296</Id><Uri>x</Uri><Metadata/></Entry></TrackList>",
            "<TrackList><Entry><Id>1</Id><Uri>x</Uri></Entry></TrackList>",
        ).forEach { malformed ->
            try {
                parseDesktopOpenHomePlaylistReadList(malformed)
                throw AssertionError("Malformed ReadList XML should be rejected.")
            } catch (_: Exception) {
            }
        }
        try {
            parseDesktopOpenHomePlaylistReadList(" ".repeat(1024 * 1024 + 1))
            throw AssertionError("Oversized ReadList XML should be rejected.")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `client requires advertised typed actions and valid bounded arguments`() = runBlocking {
        var soapCalls = 0
        val scpdXml = playlistScpd().replace("<name>SeekSecondAbsolute</name>", "<name>SeekAbsolute</name>")
        val client = DesktopOpenHomePlaylistClient(
            soap = DesktopUpnpSoapTransport { _, _, _, _ -> soapCalls++; emptyMap() },
            scpd = DesktopUpnpScpdTransport { scpdXml.toByteArray() },
        )
        try {
            client.seekSecondAbsolute(playlistDevice(), 1)
            throw AssertionError("An unadvertised OpenHome action must not be called.")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("does not advertise"))
        }
        assertEquals(0, soapCalls)
        try {
            client.insert(playlistDevice(), 0, "", "")
            throw AssertionError("An empty track URI must be rejected.")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun `client rejects cross origin control and SCPD URLs before transport calls`() = runBlocking {
        var soapCalls = 0
        var scpdCalls = 0
        val client = DesktopOpenHomePlaylistClient(
            soap = DesktopUpnpSoapTransport { _, _, _, _ -> soapCalls++; emptyMap() },
            scpd = DesktopUpnpScpdTransport { scpdCalls++; playlistScpd().toByteArray() },
        )
        val device = playlistDevice()
        val crossOriginControl = device.copy(
            services = device.services + (DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST to
                device.services.getValue(DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST).copy(
                    controlUri = URI("http://192.168.1.99:1400/playlist/control"),
                )),
        )
        try {
            client.transportState(crossOriginControl)
            throw AssertionError("Cross-origin control URLs must be rejected.")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("origin"))
        }

        val crossOriginScpd = device.copy(
            services = device.services + (DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST to
                device.services.getValue(DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST).copy(
                    scpdUri = URI("http://192.168.1.99:1400/playlist/scpd.xml"),
                )),
        )
        try {
            client.transportState(crossOriginScpd)
            throw AssertionError("Cross-origin SCPD URLs must be rejected.")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("origin"))
        }
        assertEquals(0, soapCalls)
        assertEquals(0, scpdCalls)
    }

    @Test
    fun `capability cache is reused invalidated and not poisoned by fetch failures`() = runBlocking {
        var scpdCalls = 0
        var failFirstFetch = true
        val client = DesktopOpenHomePlaylistClient(
            soap = DesktopUpnpSoapTransport { _, _, action, _ ->
                if (action == "TransportState") mapOf("Value" to "Playing") else emptyMap()
            },
            scpd = DesktopUpnpScpdTransport {
                scpdCalls++
                if (failFirstFetch) {
                    failFirstFetch = false
                    throw IOException("transient")
                }
                playlistScpd().toByteArray()
            },
        )
        val device = playlistDevice()
        try {
            client.transportState(device)
            throw AssertionError("A failed SCPD fetch must fail the action.")
        } catch (_: IOException) {
        }
        assertEquals("Playing", client.transportState(device))
        assertEquals("Playing", client.transportState(device))
        assertEquals(2, scpdCalls)
        client.invalidateCapabilities(device)
        assertEquals("Playing", client.transportState(device))
        assertEquals(3, scpdCalls)
    }
}

private fun playlistDevice(): DesktopUpnpRendererDevice = DesktopUpnpRendererDevice(
    udn = "uuid:openhome-renderer",
    friendlyName = "OpenHome Renderer",
    manufacturer = "Example",
    modelName = "OH1",
    modelNumber = null,
    descriptionUri = URI("http://192.168.1.20:1400/device.xml"),
    services = mapOf(
        DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST to DesktopUpnpServiceEndpoint(
            serviceType = "urn:av-openhome-org:service:Playlist:1",
            controlUri = URI("http://192.168.1.20:1400/playlist/control"),
            eventSubUri = URI("http://192.168.1.20:1400/playlist/events"),
            scpdUri = URI("http://192.168.1.20:1400/playlist/scpd.xml"),
        ),
    ),
)

private data class ScpdArg(val name: String, val direction: String, val type: String)

private fun playlistScpd(sendEvents: Map<String, String> = emptyMap()): String {
    val signatures = linkedMapOf(
        "TransportState" to listOf(ScpdArg("Value", "out", "string")),
        "Id" to listOf(ScpdArg("Value", "out", "ui4")),
        "TracksMax" to listOf(ScpdArg("Value", "out", "ui4")),
        "IdArray" to listOf(ScpdArg("Token", "out", "ui4"), ScpdArg("Array", "out", "bin.base64")),
        "IdArrayChanged" to listOf(ScpdArg("Token", "in", "ui4"), ScpdArg("Value", "out", "boolean")),
        "ProtocolInfo" to listOf(ScpdArg("Value", "out", "string")),
        "ReadList" to listOf(ScpdArg("IdList", "in", "string"), ScpdArg("TrackList", "out", "string")),
        "Insert" to listOf(
            ScpdArg("AfterId", "in", "ui4"), ScpdArg("Uri", "in", "string"),
            ScpdArg("Metadata", "in", "string"), ScpdArg("NewId", "out", "ui4"),
        ),
        "DeleteAll" to emptyList(),
        "DeleteId" to listOf(ScpdArg("Value", "in", "ui4")),
        "Play" to emptyList(), "Pause" to emptyList(), "Stop" to emptyList(), "Next" to emptyList(),
        "Previous" to emptyList(),
        "SeekId" to listOf(ScpdArg("Value", "in", "ui4")),
        "SeekSecondAbsolute" to listOf(ScpdArg("Value", "in", "ui4")),
    )
    val types = signatures.values.flatten().map { it.type }.distinct()
    return buildString {
        append("<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><serviceStateTable>")
        types.forEachIndexed { index, type ->
            val name = "State$index"
            val sendEventsAttribute = sendEvents[name]?.let { " sendEvents=\"$it\"" }.orEmpty()
            append("<stateVariable$sendEventsAttribute><name>$name</name><dataType>$type</dataType></stateVariable>")
        }
        append("</serviceStateTable><actionList>")
        signatures.forEach { (action, arguments) ->
            append("<action><name>").append(action).append("</name>")
            if (arguments.isNotEmpty()) {
                append("<argumentList>")
                arguments.forEach { argument ->
                    val typeIndex = types.indexOf(argument.type)
                    append("<argument><name>").append(argument.name)
                        .append("</name><direction>").append(argument.direction)
                        .append("</direction><relatedStateVariable>State").append(typeIndex)
                        .append("</relatedStateVariable></argument>")
                }
                append("</argumentList>")
            }
            append("</action>")
        }
        append("</actionList></scpd>")
    }
}
