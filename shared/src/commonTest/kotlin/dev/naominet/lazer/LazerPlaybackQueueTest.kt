package dev.naominet.lazer

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class LazerPlaybackQueueTest {

    private fun track(id: Long) = LazerTrack(
        id = id,
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        durationMillis = 1_000L,
    )

    private fun fiveTrack(mode: LazerPlayMode = LazerPlayMode.ListLoop) {
        val tracks = (1L..5L).map(::track)
        LazerPlaybackQueue.replace(tracks, tracks.first())
        LazerPlaybackQueue.setMode(mode)
    }

    @AfterTest
    fun tearDown() {
        LazerPlaybackQueue.replace(emptyList(), track(1))
        LazerPlaybackQueue.setMode(LazerPlayMode.ListLoop)
    }

    @Test
    fun `tapping a track from the middle of a list makes it the audible one`() {
        val tracks = (1L..4L).map(::track)
        LazerPlaybackQueue.replace(tracks, tracks[2])

        assertEquals(2, LazerPlaybackQueue.index)
        assertEquals(3L, LazerPlaybackQueue.current()?.id)
    }

    @Test
    fun `list loop wraps in both directions`() {
        fiveTrack()
        LazerPlaybackQueue.jumpTo(4)

        assertEquals(1L, LazerPlaybackQueue.next()?.id)
        assertEquals(0, LazerPlaybackQueue.index)

        assertEquals(5L, LazerPlaybackQueue.previous()?.id)
        assertEquals(4, LazerPlaybackQueue.index)
    }

    @Test
    fun `shuffle never hands back the track that is already playing`() {
        fiveTrack(LazerPlayMode.Shuffle)
        val before = LazerPlaybackQueue.index

        repeat(20) {
            val advanced = LazerPlaybackQueue.next()
            assertTrue(advanced != null && LazerPlaybackQueue.index != before, "stayed put")
            LazerPlaybackQueue.jumpTo(before)
        }
    }

    @Test
    fun `shuffle plays every other track once before repeating`() {
        fiveTrack(LazerPlayMode.Shuffle)
        LazerPlaybackQueue.jumpTo(0)

        val drawn = (1..4).map { LazerPlaybackQueue.next()!!.id }

        assertEquals(setOf(2L, 3L, 4L, 5L), drawn.toSet())
    }

    @Test
    fun `shuffle previous replays the track that was actually heard`() {
        fiveTrack(LazerPlayMode.Shuffle)
        LazerPlaybackQueue.jumpTo(0)

        val next = LazerPlaybackQueue.next()!!.id
        val back = LazerPlaybackQueue.previous()!!.id

        assertEquals(1L, back)
        assertEquals(next, LazerPlaybackQueue.next()!!.id)
    }

    @Test
    fun `moving an item past the audible one keeps the same track playing`() {
        fiveTrack()
        LazerPlaybackQueue.jumpTo(2)

        LazerPlaybackQueue.move(from = 0, to = 4)

        assertEquals(1, LazerPlaybackQueue.index)
        assertEquals(3L, LazerPlaybackQueue.current()?.id)
        assertEquals(listOf(2L, 3L, 4L, 5L, 1L), LazerPlaybackQueue.tracks.map { it.id })
    }

    @Test
    fun `moving the audible track follows it to its new slot`() {
        fiveTrack()
        LazerPlaybackQueue.jumpTo(1)

        LazerPlaybackQueue.move(from = 1, to = 3)

        assertEquals(3, LazerPlaybackQueue.index)
        assertEquals(2L, LazerPlaybackQueue.current()?.id)
    }

    @Test
    fun `removing an earlier item shifts the audible index down by one`() {
        fiveTrack()
        LazerPlaybackQueue.jumpTo(3)

        assertEquals(LazerQueueEdit.Kept, LazerPlaybackQueue.removeAt(1))

        assertEquals(2, LazerPlaybackQueue.index)
        assertEquals(4L, LazerPlaybackQueue.current()?.id)
    }

    @Test
    fun `removing the audible item reports a switch onto its successor`() {
        fiveTrack()
        LazerPlaybackQueue.jumpTo(2)

        assertEquals(LazerQueueEdit.Switched, LazerPlaybackQueue.removeAt(2))

        assertEquals(2, LazerPlaybackQueue.index)
        assertEquals(4L, LazerPlaybackQueue.current()?.id)
    }

    @Test
    fun `removing the last item while it plays lands inside the shortened queue`() {
        fiveTrack()
        LazerPlaybackQueue.jumpTo(4)

        assertEquals(LazerQueueEdit.Switched, LazerPlaybackQueue.removeAt(4))

        assertTrue(LazerPlaybackQueue.index in LazerPlaybackQueue.tracks.indices)
        assertEquals(3, LazerPlaybackQueue.index)
        assertEquals(4L, LazerPlaybackQueue.current()?.id)
    }

    @Test
    fun `removing the only item empties the queue`() {
        LazerPlaybackQueue.replace(listOf(track(9)), track(9))

        assertEquals(LazerQueueEdit.Emptied, LazerPlaybackQueue.removeAt(0))

        assertTrue(LazerPlaybackQueue.tracks.isEmpty())
        assertEquals(-1, LazerPlaybackQueue.index)
        assertNotEquals(9L, LazerPlaybackQueue.current()?.id ?: -1L)
    }

    @Test
    fun `a queue longer than the cap keeps a window around the audible track`() {
        val tracks = (1L..4_000L).map(::track)
        LazerPlaybackQueue.replace(tracks, tracks[2_000])

        assertEquals(LazerPlaybackQueue.MAX_TRACKS, LazerPlaybackQueue.tracks.size)
        assertEquals(2_001L, LazerPlaybackQueue.current()?.id)
        assertTrue(LazerPlaybackQueue.index >= 0)
    }

    @Test
    fun `the window reaches the far end of a long queue without losing the tail`() {
        val tracks = (1L..4_000L).map(::track)
        LazerPlaybackQueue.replace(tracks, tracks.last())

        assertEquals(4_000L, LazerPlaybackQueue.tracks.last().id)
        assertEquals(4_000L, LazerPlaybackQueue.current()?.id)
    }

    @Test
    fun `single loop still moves when the listener asks for next`() {
        fiveTrack(LazerPlayMode.SingleLoop)
        LazerPlaybackQueue.jumpTo(0)

        // The mode only governs what happens when a track runs to its end, which the queue itself
        // never decides, so an explicit advance still advances.
        assertEquals(2L, LazerPlaybackQueue.next()?.id)
    }
}
