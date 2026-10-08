package dev.naominet.lazer

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DesktopLocalPlaybackQueueStoreTest {
    @Test
    fun `round trips queue order CUE identity mode and position`() {
        val directory = Files.createTempDirectory("desktop-local-queue")
        try {
            val audioFile = Files.write(directory.resolve("disc.flac"), byteArrayOf(1, 2, 3, 4))
            val cueFile = Files.writeString(directory.resolve("disc.cue"), "FILE disc.flac WAVE\n")
            val audio = audioFile.toAbsolutePath().normalize().toString()
            val cue = cueFile.toAbsolutePath().normalize().toString()
            val snapshot = DesktopLocalPlaybackQueueSnapshot(
                tracks = listOf(
                    DesktopLocalPlaybackQueueTrack(
                        absolutePath = audio,
                        title = "First cue track",
                        artist = "Artist",
                        album = "Album",
                        durationMillis = 121_000L,
                        coverUrl = "file:///cover.jpg",
                        replayGain = DesktopReplayGainTags(-5.4, 0.9, -6.2, 0.95),
                        cueSheetPath = cue,
                        cueTrackNumber = 2,
                        cueStartFrame75 = 900L,
                        cueEndFrame75 = 9_000L,
                    ),
                    DesktopLocalPlaybackQueueTrack(
                        absolutePath = directory.resolve("next.wav").toAbsolutePath().normalize().toString(),
                        title = "Next",
                        artist = "Artist",
                        album = "Album",
                        durationMillis = 80_000L,
                    ),
                ),
                currentIndex = 1,
                positionMillis = 42_500L,
                playMode = DesktopPlayMode.Shuffle,
            )
            val store = DesktopLocalPlaybackQueueStore(directory.resolve("queue.json"))

            store.save(snapshot)

            val persisted = snapshot.copy(tracks = listOf(
                snapshot.tracks[0].copy(
                    audioSizeBytes = Files.size(audioFile),
                    audioModifiedMillis = Files.getLastModifiedTime(audioFile).toMillis(),
                    cueSheetSizeBytes = Files.size(cueFile),
                    cueSheetModifiedMillis = Files.getLastModifiedTime(cueFile).toMillis(),
                ),
                snapshot.tracks[1],
            ))
            assertEquals(persisted, store.load())

            val checkpoint = persisted.copy(currentIndex = 0, positionMillis = 90_000L)
            store.updateProgress(checkpoint)
            assertEquals(checkpoint, store.load())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `ignores corrupt and unsupported snapshots`() {
        val directory = Files.createTempDirectory("desktop-local-queue-corrupt")
        try {
            val path = directory.resolve("queue.json")
            val store = DesktopLocalPlaybackQueueStore(path)

            Files.writeString(path, "not-json")
            assertNull(store.load())
            Files.writeString(path, "{\"schemaVersion\":1}")
            assertNull(store.load())
            Files.writeString(path, "{\"schemaVersion\":99}")
            assertNull(store.load())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `invalid replacement leaves previous valid snapshot available`() {
        val directory = Files.createTempDirectory("desktop-local-queue-invalid-write")
        try {
            val store = DesktopLocalPlaybackQueueStore(directory.resolve("queue.json"))
            val saved = DesktopLocalPlaybackQueueSnapshot(
                tracks = listOf(
                    DesktopLocalPlaybackQueueTrack(
                        absolutePath = directory.resolve("track.flac").toAbsolutePath().normalize().toString(),
                        title = "Track",
                        artist = "Artist",
                        album = "Album",
                        durationMillis = 30_000L,
                    ),
                ),
                currentIndex = 0,
                positionMillis = 12_000L,
                playMode = DesktopPlayMode.ListLoop,
            )
            store.save(saved)

            assertThrows(IllegalArgumentException::class.java) {
                store.save(saved.copy(currentIndex = 3))
            }

            assertEquals(saved, store.load())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
