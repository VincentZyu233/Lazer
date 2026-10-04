package dev.naominet.lazer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopCueGaplessTest {
    @Test
    fun `accepts only directly adjacent segments from the same CUE and source`() {
        val first = cueTrack(id = 1, number = 1, start = 0, end = 75)
        val adjacent = cueTrack(id = 2, number = 2, start = 75, end = 150)
        assertTrue(isContiguousDesktopCueSuccessor(first, adjacent))

        assertFalse(isContiguousDesktopCueSuccessor(first, adjacent.copy(
            playbackSource = localSource(number = 2, start = 76, end = 150),
        )))
        assertFalse(isContiguousDesktopCueSuccessor(first, adjacent.copy(
            playbackSource = localSource(number = 2, start = 75, end = 150, sheet = "other.cue"),
        )))
        assertFalse(isContiguousDesktopCueSuccessor(first, adjacent.copy(
            playbackSource = localSource(
                number = 2,
                start = 75,
                end = 150,
                audio = "other.wav",
            ),
        )))
        assertFalse(isContiguousDesktopCueSuccessor(first, adjacent.copy(id = first.id)))
        assertFalse(isContiguousDesktopCueSuccessor(first, adjacent.copy(
            playbackSource = DesktopTrackSource.LocalFile("/music/album.wav"),
        )))
    }

    @Test
    fun `ordinary WAV and FLAC queue successors allow per-track ReplayGain`() {
        val current = localTrack(id = 11, path = "/music/01.wav")
        val successor = localTrack(id = 12, path = "/music/02.flac")

        assertTrue(canQueueDesktopLocalGaplessSuccessor(current, successor))
        assertFalse(canQueueDesktopLocalGaplessSuccessor(current, successor.copy(id = current.id)))
        assertFalse(canQueueDesktopLocalGaplessSuccessor(
            current,
            successor.copy(playbackSource = DesktopTrackSource.Remote),
        ))
        assertFalse(canQueueDesktopLocalGaplessSuccessor(
            current,
            localTrack(id = 13, path = "/music/03.mp3"),
        ))
        assertFalse(canQueueDesktopLocalGaplessSuccessor(
            current,
            cueTrack(id = 14, number = 2, start = 75, end = 150),
        ))
    }

    @Test
    fun `DSF and DFF local successors are candidates only during an active DoP stream`() {
        val current = localTrack(id = 31, path = "/music/01.dsf")
        val successor = localTrack(id = 32, path = "/music/02.dff")

        assertFalse(canQueueDesktopLocalGaplessSuccessor(current, successor))
        assertTrue(canQueueDesktopLocalGaplessSuccessor(current, successor, doPOutputActive = true))
        assertFalse(canQueueDesktopLocalGaplessSuccessor(
            current,
            localTrack(id = 33, path = "/music/03.wav"),
            doPOutputActive = true,
        ))
        assertFalse(canQueueDesktopLocalGaplessSuccessor(
            current,
            successor.copy(playbackSource = DesktopTrackSource.Remote),
            doPOutputActive = true,
        ))
        assertFalse(canQueueDesktopLocalGaplessSuccessor(
            current,
            cueTrack(id = 34, number = 2, start = 75, end = 150),
            doPOutputActive = true,
        ))
        assertFalse(canQueueDesktopLocalGaplessSuccessor(
            current,
            localTrack(id = 35, path = "/music/segmented.dff").copy(
                playbackSource = DesktopTrackSource.LocalFile(
                    absolutePath = "/music/segmented.dff",
                    cueSheetPath = "/music/album.cue",
                    cueTrackNumber = 2,
                    cueStartFrame75 = 75,
                    cueEndFrame75 = 150,
                ),
            ),
            doPOutputActive = true,
        ))
    }

    @Test
    fun `CUE gapless still requires adjacent ranges and allows per-track ReplayGain`() {
        val first = cueTrack(id = 21, number = 1, start = 0, end = 75)
        val successor = cueTrack(id = 22, number = 2, start = 75, end = 150)

        assertTrue(canQueueDesktopLocalGaplessSuccessor(first, successor))
        assertFalse(canQueueDesktopLocalGaplessSuccessor(
            first,
            successor.copy(playbackSource = localSource(number = 2, start = 76, end = 150)),
        ))
    }

    private fun cueTrack(id: Long, number: Int, start: Long, end: Long) = TrackItem(
        id = id,
        title = "Track $number",
        artist = "",
        album = "",
        durationMillis = 1_000,
        coverUrl = null,
        playbackSource = localSource(number, start, end),
    )

    private fun localTrack(id: Long, path: String) = TrackItem(
        id = id,
        title = path.substringAfterLast('/'),
        artist = "",
        album = "",
        durationMillis = 1_000,
        coverUrl = null,
        playbackSource = DesktopTrackSource.LocalFile(path),
    )

    private fun localSource(
        number: Int,
        start: Long,
        end: Long,
        audio: String = "album.wav",
        sheet: String = "album.cue",
    ) = DesktopTrackSource.LocalFile(
        absolutePath = "/music/$audio",
        cueSheetPath = "/music/$sheet",
        cueTrackNumber = number,
        cueStartFrame75 = start,
        cueEndFrame75 = end,
    )
}
