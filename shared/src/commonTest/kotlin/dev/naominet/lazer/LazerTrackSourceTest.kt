package dev.naominet.lazer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest

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

    @Test
    fun localQueueTokensStayUniqueWhenSeveralThreadsAllocateThem() = runTest {
        val ids = coroutineScope {
            (0 until 8).map {
            async(Dispatchers.Default) { List(500) { LazerLocalTrackIdentity.nextId() } }
            }.awaitAll().flatten()
        }

        assertEquals(ids.size, ids.distinct().size)
        assertTrue(ids.all { it < 0L })
    }
}
