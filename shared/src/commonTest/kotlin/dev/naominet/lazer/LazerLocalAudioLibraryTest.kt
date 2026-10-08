package dev.naominet.lazer

import kotlin.test.Test
import kotlin.test.assertEquals

class LazerLocalAudioLibraryTest {
    @Test
    fun `orders by album disc track and title with missing numbers last and ties stable`() {
        val tracks = listOf(
            track(1, album = "Beta", disc = 1, number = 1, title = "Beta"),
            track(2, album = "Alpha", disc = 1, number = 2, title = "Second"),
            track(3, album = "Alpha", disc = 1, number = null, title = "Unnumbered"),
            track(4, album = "Alpha", disc = 1, number = 1, title = "zeta"),
            track(5, album = "Alpha", disc = 1, number = 1, title = "Alpha"),
            track(6, album = "Alpha", disc = 1, number = 1, title = "Alpha"),
            track(7, album = "Alpha", disc = 2, number = 1, title = "Later disc"),
            track(8, album = "Alpha", disc = null, number = 1, title = "No disc"),
        )

        assertEquals(
            listOf(5L, 6L, 4L, 2L, 3L, 7L, 8L, 1L),
            orderLazerLocalLibraryTracks(tracks).map(LazerTrack::id),
        )
    }

    @Test
    fun `search matches title artist or album without case sensitivity`() {
        val tracks = listOf(
            track(1, title = "Glass Room", artist = "Mira", album = "Night Lines"),
            track(2, title = "Open Water", artist = "The Mira Trio", album = "Live"),
            track(3, title = "Morning", artist = "Sol", album = "Glass Houses"),
        )

        assertEquals(listOf(1L), filterLazerLocalLibraryTracks(tracks, "ROOM").map(LazerTrack::id))
        assertEquals(listOf(1L, 2L), filterLazerLocalLibraryTracks(tracks, "mIrA").map(LazerTrack::id))
        assertEquals(listOf(1L, 3L), filterLazerLocalLibraryTracks(tracks, "glass").map(LazerTrack::id))
        assertEquals(tracks, filterLazerLocalLibraryTracks(tracks, "  "))
    }

    private fun track(
        id: Long,
        album: String = "",
        disc: Int? = null,
        number: Int? = null,
        title: String = "",
        artist: String = "",
    ) = LazerTrack(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMillis = 0L,
        discNumber = disc,
        trackNumber = number,
    )
}
