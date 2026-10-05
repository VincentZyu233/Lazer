package dev.naominet.lazer

import dev.naominet.lazer.gateway.model.Artist
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LazerPlaybackQueueCodecTest {
    @AfterTest
    fun tearDown() {
        LazerPlaybackQueue.restoreSnapshot(LazerPlaybackQueueSnapshot())
    }

    @Test
    fun `queue codec restores online and local metadata and selected track`() {
        val online = LazerTrack(
            id = 37L,
            title = "夜曲",
            artist = "周杰伦",
            album = "十一月的萧邦",
            durationMillis = 242_000L,
            coverUrl = "https://example.test/cover.jpg",
            artists = listOf(Artist(id = 100L, name = "周杰伦")),
            translatedTitle = "Nocturne",
            replayGain = LazerReplayGainTags(
                trackGainDb = -7.25,
                trackPeak = 0.82,
                albumGainDb = -8.5,
                albumPeak = 0.91,
            ),
        )
        val local = LazerTrack(
            id = Long.MIN_VALUE + 20L,
            title = "Local take",
            artist = "Listener",
            album = "Demo",
            durationMillis = 90_000L,
            source = LazerTrackSource.LocalFile("content://provider/document/track-1"),
            replayGain = LazerReplayGainTags(trackGainDb = 3.0, trackPeak = 0.5),
        )
        val expected = LazerPlaybackQueueSnapshot(
            tracks = listOf(online, local),
            index = 1,
            mode = LazerPlayMode.Shuffle,
        )

        val encoded = LazerPlaybackQueueCodec.encode(expected)
        assertTrue(encoded.contains("\"schemaVersion\":2"))
        val restored = LazerPlaybackQueueCodec.decode(encoded)

        assertEquals(expected, restored)
    }

    @Test
    fun `decoder rejects unsupported schema malformed selection and oversized queues`() {
        assertNull(LazerPlaybackQueueCodec.decode("{\"schemaVersion\":3,\"tracks\":[],\"index\":-1,\"mode\":\"ListLoop\"}"))
        assertNull(LazerPlaybackQueueCodec.decode("{\"schemaVersion\":1,\"tracks\":[],\"index\":0,\"mode\":\"ListLoop\"}"))
        assertNull(LazerPlaybackQueueCodec.decode("{not-json"))

        val oversized = LazerPlaybackQueueSnapshot(
            tracks = (1L..(LazerPlaybackQueue.MAX_TRACKS + 1L)).map(::track),
            index = 0,
        )
        assertFalse(LazerPlaybackQueue.restoreSnapshot(oversized))
        assertFalse(runCatching { LazerPlaybackQueueCodec.encode(oversized) }.isSuccess)
    }

    @Test
    fun `schema one queues restore with replay gain absent`() {
        val schemaOne = """
            {"schemaVersion":1,"tracks":[{"id":41,"title":"Legacy","artist":"Artist","album":"Album","durationMillis":90000,"sourceType":"gateway"}],"index":0,"mode":"ListLoop"}
        """.trimIndent()

        val restored = LazerPlaybackQueueCodec.decode(schemaOne)

        assertEquals(1, restored?.tracks?.size)
        assertEquals("Legacy", restored?.tracks?.single()?.title)
        assertNull(restored?.tracks?.single()?.replayGain)
    }

    @Test
    fun `restoring a local queue reserves its IDs before the next picker item`() {
        val trackId = LazerLocalTrackIdentity.nextId() + 100L
        val local = track(trackId).copy(source = LazerTrackSource.LocalFile("content://provider/item"))

        assertTrue(LazerPlaybackQueue.restoreSnapshot(LazerPlaybackQueueSnapshot(listOf(local), 0)))

        assertTrue(LazerLocalTrackIdentity.nextId() > trackId)
    }

    @Test
    fun `position checkpoint is track-bound and clamped to known duration`() {
        val selected = track(7L)

        assertEquals(1_000L, LazerPlaybackQueueCodec.restoredPositionMillis(selected, 7L, 2_000L))
        assertEquals(0L, LazerPlaybackQueueCodec.restoredPositionMillis(selected, 8L, 500L))
        assertEquals(0L, LazerPlaybackQueueCodec.restoredPositionMillis(selected, 7L, -50L))
    }

    private fun track(id: Long) = LazerTrack(
        id = id,
        title = "Track $id",
        artist = "Artist",
        album = "Album",
        durationMillis = 1_000L,
    )
}
