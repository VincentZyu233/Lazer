@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.naominet.lazer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import platform.Foundation.NSUserDefaults

class SharedLogicIOSTest {

    @Test
    fun playbackQueueAndPositionSurviveStoreRecreation() {
        withDefaults { defaults ->
            val track = LazerTrack(
                id = -42L,
                title = "Local test track",
                artist = "Test artist",
                album = "Test album",
                durationMillis = 180_000L,
                source = LazerTrackSource.LocalFile("file:///managed/test.flac"),
            )
            val snapshot = LazerPlaybackQueueSnapshot(listOf(track), index = 0, mode = LazerPlayMode.Shuffle)

            assertTrue(IosPlaybackQueueStore(defaults).saveQueue(snapshot))
            assertTrue(IosPlaybackQueueStore(defaults).savePosition(track.id, 91_250L))

            val reopened = IosPlaybackQueueStore(defaults)
            assertEquals(IosStoredQueue.Valid(snapshot), reopened.loadQueue())
            assertEquals(track.id to 91_250L, reopened.loadPosition())
        }
    }

    @Test
    fun corruptQueueIsDistinguishedFromFirstLaunch() {
        withDefaults { defaults ->
            val store = IosPlaybackQueueStore(defaults)
            assertIs<IosStoredQueue.Missing>(store.loadQueue())
            defaults.setObject("{broken", "lazer.ios.playbackQueue.v1")
            assertIs<IosStoredQueue.Invalid>(store.loadQueue())
        }
    }

    private fun withDefaults(block: (NSUserDefaults) -> Unit) {
        val suiteName = "dev.naominet.lazer.ios-test-${kotlin.random.Random.nextLong()}"
        val defaults = NSUserDefaults(suiteName = suiteName) ?: error("Could not create test defaults")
        try {
            block(defaults)
        } finally {
            defaults.removePersistentDomainForName(suiteName)
        }
    }
}
