package dev.naominet.lazer

import java.net.URI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopOpenHomeReconnectTest {
    @Test
    fun `rediscovery accepts only the same UDN with an OpenHome Playlist service`() {
        val expected = device(udn = "uuid:expected", name = "Living Room", playlist = true)
        val sameNameDifferentIdentity = device(udn = "uuid:other", name = "Living Room", playlist = true)
        val sameIdentityWithoutPlaylist = device(udn = "uuid:expected", name = "Living Room", playlist = false)

        assertSame(
            expected,
            selectDesktopOpenHomeRecoveryDevice(
                expected.identity,
                listOf(sameNameDifferentIdentity, sameIdentityWithoutPlaylist, expected),
            ),
        )
        assertNull(selectDesktopOpenHomeRecoveryDevice(expected.identity, listOf(sameNameDifferentIdentity)))
        assertNull(selectDesktopOpenHomeRecoveryDevice(expected.identity, listOf(sameIdentityWithoutPlaylist)))
    }

    @Test
    fun `rediscovery failures back off and a successful recovery resets the schedule`() {
        val backoff = DesktopOpenHomeReconnectBackoff()
        assertTrue(backoff.isAttemptDue(0L))

        backoff.recordFailure(0L)
        assertFalse(backoff.isAttemptDue(999_000_000L))
        assertTrue(backoff.isAttemptDue(1_000_000_000L))

        backoff.recordFailure(1_000_000_000L)
        assertFalse(backoff.isAttemptDue(3_999_000_000L))
        assertTrue(backoff.isAttemptDue(4_000_000_000L))

        backoff.reset()
        assertTrue(backoff.isAttemptDue(0L))
    }

    @Test
    fun `GENA detach fences an in-flight subscribe result`() {
        val state = DesktopOpenHomeGenaBindingState<String, String, String, String>()
        assertTrue(state.attach("receiver", "old-device", "old-address"))

        val detached = state.detach("fallback-device")

        assertEquals("old-device", detached.device)
        assertEquals("old-address", detached.rendererAddress)
        assertNull(detached.lease)
        assertFalse(state.installLease("receiver", "late-sid"))
        assertNull(state.receiver())
        assertNull(state.lease())
    }

    @Test
    fun `GENA detach captures an already installed SID`() {
        val state = DesktopOpenHomeGenaBindingState<String, String, String, String>()
        assertTrue(state.attach("receiver", "old-device", "old-address"))
        assertTrue(state.installLease("receiver", "sid-1"))

        val detached = state.detach("fallback-device")

        assertEquals("sid-1", detached.lease)
        assertNull(state.receiver())
        assertNull(state.lease())
    }

    @Test
    fun `explicit OpenHome command maps to recovery intent and is confirmed from status`() {
        assertEquals(
            DesktopOpenHomePlaybackIntent.STOP,
            desktopOpenHomePlaybackIntentFor(DesktopUpnpTransportCommand.STOP),
        )
        assertTrue(
            desktopOpenHomeCommandMatches(
                DesktopUpnpTransportCommand.PAUSE,
                DesktopUpnpRendererStatus(transportState = "PAUSED_PLAYBACK"),
            ),
        )
        assertFalse(
            desktopOpenHomeCommandMatches(
                DesktopUpnpTransportCommand.PLAY,
                DesktopUpnpRendererStatus(transportState = "PAUSED_PLAYBACK"),
            ),
        )
    }

    private fun device(udn: String, name: String, playlist: Boolean): DesktopUpnpRendererDevice {
        val service = DesktopUpnpServiceEndpoint(
            serviceType = "urn:av-openhome-org:service:Playlist:1",
            controlUri = URI("http://192.0.2.10/playlist/control"),
            eventSubUri = URI("http://192.0.2.10/playlist/event"),
            scpdUri = URI("http://192.0.2.10/playlist/scpd.xml"),
        )
        return DesktopUpnpRendererDevice(
            udn = udn,
            friendlyName = name,
            manufacturer = null,
            modelName = null,
            modelNumber = null,
            descriptionUri = URI("http://192.0.2.10/description.xml"),
            services = if (playlist) mapOf(DesktopUpnpRendererServiceKind.OPENHOME_PLAYLIST to service) else emptyMap(),
        )
    }
}
