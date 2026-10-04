package dev.naominet.lazer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LazerTrackSourceTest {
    @Test
    fun onlineTracksDefaultToTheGatewaySource() {
        val track = LazerTrack(1L, "Title", "Artist", "Album", 1_000L)

        assertEquals(LazerTrackSource.GatewaySong, track.source)
    }

    @Test
    fun localQueueTokensAreNegativeAndUniqueWithinTheProcess() {
        val first = LazerLocalTrackIdentity.nextId()
        val second = LazerLocalTrackIdentity.nextId()

        assertTrue(first < 0L)
        assertTrue(second < 0L)
        assertNotEquals(first, second)
    }
}
