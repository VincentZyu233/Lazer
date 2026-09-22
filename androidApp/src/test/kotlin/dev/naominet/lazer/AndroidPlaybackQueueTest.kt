package dev.naominet.lazer

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test

class AndroidPlaybackQueueTest {

    private fun track(id: Long) = AndroidTrack(
        id = id,
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        durationMillis = 1_000L,
    )

    private fun fiveTrack(mode: AndroidPlayMode = AndroidPlayMode.ListLoop) {
        val tracks = (1L..5L).map(::track)
        AndroidPlaybackQueue.replace(tracks, tracks.first())
        AndroidPlaybackQueue.setMode(mode)
    }

    @After
    fun tearDown() {
        AndroidPlaybackQueue.replace(emptyList(), track(1))
        AndroidPlaybackQueue.setMode(AndroidPlayMode.ListLoop)
    }

    @Test
    fun `tapping a track from the middle of a list makes it the audible one`() {
        val tracks = (1L..4L).map(::track)
        AndroidPlaybackQueue.replace(tracks, tracks[2])

        assertEquals(2, AndroidPlaybackQueue.index)
        assertEquals(3L, AndroidPlaybackQueue.current()?.id)
    }

    @Test
    fun `list loop wraps in both directions`() {
        fiveTrack()
        AndroidPlaybackQueue.jumpTo(4)

        assertEquals(1L, AndroidPlaybackQueue.next()?.id)
        assertEquals(0, AndroidPlaybackQueue.index)

        assertEquals(5L, AndroidPlaybackQueue.previous()?.id)
        assertEquals(4, AndroidPlaybackQueue.index)
    }

    @Test
    fun `shuffle never hands back the track that is already playing`() {
        fiveTrack(AndroidPlayMode.Shuffle)
        val before = AndroidPlaybackQueue.index

        repeat(20) {
            val advanced = AndroidPlaybackQueue.next()
            assertTrue(advanced != null && AndroidPlaybackQueue.index != before, "stayed put")
            AndroidPlaybackQueue.jumpTo(before)
        }
    }

    @Test
    fun `moving an item past the audible one keeps the same track playing`() {
        fiveTrack()
        AndroidPlaybackQueue.jumpTo(2)

        AndroidPlaybackQueue.move(from = 0, to = 4)

        assertEquals(1, AndroidPlaybackQueue.index)
        assertEquals(3L, AndroidPlaybackQueue.current()?.id)
        assertEquals(listOf(2L, 3L, 4L, 5L, 1L), AndroidPlaybackQueue.tracks.map { it.id })
    }

    @Test
    fun `moving the audible track follows it to its new slot`() {
        fiveTrack()
        AndroidPlaybackQueue.jumpTo(1)

        AndroidPlaybackQueue.move(from = 1, to = 3)

        assertEquals(3, AndroidPlaybackQueue.index)
        assertEquals(2L, AndroidPlaybackQueue.current()?.id)
    }

    @Test
    fun `removing an earlier item shifts the audible index down by one`() {
        fiveTrack()
        AndroidPlaybackQueue.jumpTo(3)

        assertEquals(AndroidQueueEdit.Kept, AndroidPlaybackQueue.removeAt(1))

        assertEquals(2, AndroidPlaybackQueue.index)
        assertEquals(4L, AndroidPlaybackQueue.current()?.id)
    }

    @Test
    fun `removing the audible item reports a switch onto its successor`() {
        fiveTrack()
        AndroidPlaybackQueue.jumpTo(2)

        assertEquals(AndroidQueueEdit.Switched, AndroidPlaybackQueue.removeAt(2))

        assertEquals(2, AndroidPlaybackQueue.index)
        assertEquals(4L, AndroidPlaybackQueue.current()?.id)
    }

    @Test
    fun `removing the last item while it plays lands inside the shortened queue`() {
        fiveTrack()
        AndroidPlaybackQueue.jumpTo(4)

        assertEquals(AndroidQueueEdit.Switched, AndroidPlaybackQueue.removeAt(4))

        assertTrue(AndroidPlaybackQueue.index in AndroidPlaybackQueue.tracks.indices)
        assertEquals(3, AndroidPlaybackQueue.index)
        assertEquals(4L, AndroidPlaybackQueue.current()?.id)
    }

    @Test
    fun `removing the only item empties the queue`() {
        AndroidPlaybackQueue.replace(listOf(track(9)), track(9))

        assertEquals(AndroidQueueEdit.Emptied, AndroidPlaybackQueue.removeAt(0))

        assertTrue(AndroidPlaybackQueue.tracks.isEmpty())
        assertEquals(-1, AndroidPlaybackQueue.index)
        assertNotEquals(9L, AndroidPlaybackQueue.current()?.id ?: -1L)
    }

    @Test
    fun `single loop still moves when the listener asks for next`() {
        fiveTrack(AndroidPlayMode.SingleLoop)
        AndroidPlaybackQueue.jumpTo(0)

        // The mode only governs what happens when a track runs to its end, which the queue itself
        // never decides, so an explicit advance still advances.
        assertEquals(2L, AndroidPlaybackQueue.next()?.id)
    }
}
