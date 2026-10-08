package dev.naominet.lazer

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopOpenHomeRendererStatusTest {
    @Test
    fun `maps OpenHome transport states and current track into renderer status`() {
        val track = DesktopOpenHomePlaylistTrack(19L, "http://device/track.flac", "<item />")

        val status = desktopOpenHomeRendererStatus(
            DesktopOpenHomeQueueSnapshot(
                tracksMax = 100L,
                token = 7L,
                ids = listOf(19L),
                currentId = 19L,
                transportState = "Playing",
                tracksById = mapOf(19L to track),
                currentTrack = track,
                currentTrackKnown = true,
            ),
        )

        assertEquals("PLAYING", status.transportState)
        assertEquals("OK", status.transportStatus)
        assertEquals(track.uri, status.trackUri)
        assertEquals(track.metadata, status.trackMetadata)
        assertEquals(DesktopUpnpConnectionState.CONNECTED, status.connectionState)
        assertNull(status.positionMillis)
        assertNull(status.durationMillis)
        assertFalse(status.isPlaybackSnapshot)
    }

    @Test
    fun `keeps unknown detached current track absent and normalizes other states`() {
        val base = DesktopOpenHomeQueueSnapshot(
            tracksMax = 100L,
            token = 8L,
            ids = emptyList(),
            currentId = 99L,
            transportState = "Paused",
            tracksById = emptyMap(),
            currentTrack = null,
            currentTrackKnown = false,
        )

        val paused = desktopOpenHomeRendererStatus(base)
        assertEquals("PAUSED_PLAYBACK", paused.transportState)
        assertNull(paused.trackUri)

        assertEquals(
            "STOPPED",
            desktopOpenHomeRendererStatus(base.copy(transportState = "Stopped")).transportState,
        )
        assertEquals(
            "TRANSITIONING",
            desktopOpenHomeRendererStatus(base.copy(transportState = "Buffering")).transportState,
        )
    }

    @Test
    fun `OpenHome polling replaces playback state without dropping device capabilities`() {
        val previous = DesktopUpnpRendererStatus(
            sinkProtocolInfo = "http-get:*:audio/flac:*",
            avTransportActions = setOf("Play", "Pause"),
            rendererVolume = 27,
            rendererVolumeMaximum = 60,
            transportState = "PLAYING",
            trackUri = "http://device/old.flac",
            trackMetadata = "<old />",
            positionMillis = 12_000L,
            durationMillis = 100_000L,
        )
        val stopped = desktopOpenHomeRendererStatus(
            DesktopOpenHomeQueueSnapshot(
                tracksMax = 100L,
                token = 9L,
                ids = emptyList(),
                currentId = 0L,
                transportState = "Stopped",
                tracksById = emptyMap(),
                currentTrack = null,
                currentTrackKnown = true,
            ),
        )

        val merged = mergeDesktopOpenHomeRendererStatus(previous, stopped)

        assertEquals(previous.sinkProtocolInfo, merged.sinkProtocolInfo)
        assertEquals(previous.avTransportActions, merged.avTransportActions)
        assertEquals(previous.rendererVolume, merged.rendererVolume)
        assertEquals(previous.rendererVolumeMaximum, merged.rendererVolumeMaximum)
        assertEquals("STOPPED", merged.transportState)
        assertNull(merged.trackUri)
        assertNull(merged.trackMetadata)
        assertNull(merged.positionMillis)
        assertNull(merged.durationMillis)
        assertEquals(DesktopUpnpConnectionState.CONNECTED, merged.connectionState)
    }

    @Test
    fun `uses one control protocol at a time for dual service devices`() {
        val device = DesktopUpnpRendererDevice(
            udn = "uuid:dual-renderer",
            friendlyName = "Dual renderer",
            manufacturer = null,
            modelName = null,
            modelNumber = null,
            descriptionUri = URI("http://192.0.2.10/device.xml"),
            services = mapOf(
                DesktopUpnpRendererServiceKind.AV_TRANSPORT to DesktopUpnpServiceEndpoint(
                    serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
                    controlUri = URI("http://192.0.2.10/av/control"),
                    eventSubUri = null,
                    scpdUri = null,
                ),
                DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST to DesktopUpnpServiceEndpoint(
                    serviceType = "urn:av-openhome-org:service:Playlist:1",
                    controlUri = URI("http://192.0.2.10/playlist/control"),
                    eventSubUri = null,
                    scpdUri = null,
                ),
            ),
        )

        assertEquals(true, desktopUpnpShouldUseOpenHomePlaylist(device, activeAvSessionIdentity = null))
        assertEquals(false, desktopUpnpShouldUseOpenHomePlaylist(device, activeAvSessionIdentity = device.identity))
        assertEquals(false, desktopUpnpShouldUseOpenHomePlaylist(device.copy(services = emptyMap()), null))
    }
}
