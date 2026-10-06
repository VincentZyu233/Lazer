package dev.naominet.lazer

import android.media.MediaMetadata
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AndroidSessionMetadataTest {
    @Test
    fun frameworkSessionMetadataPublishesExtendedLocalTags() {
        val metadata = androidSessionMetadataBuilder(
            LazerTrack(
                id = -1L,
                title = "Track",
                artist = "Performer",
                album = "Album",
                durationMillis = 120_000L,
                albumArtist = "Ensemble",
                genre = "Jazz",
                year = 2024,
                trackNumber = 3,
                totalTracks = 11,
                discNumber = 2,
                totalDiscs = 3,
            ),
        ).build()

        assertEquals("Ensemble", metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))
        assertEquals("Jazz", metadata.getString(MediaMetadata.METADATA_KEY_GENRE))
        assertEquals(2024L, metadata.getLong(MediaMetadata.METADATA_KEY_YEAR))
        assertEquals(3L, metadata.getLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER))
        assertEquals(11L, metadata.getLong(MediaMetadata.METADATA_KEY_NUM_TRACKS))
        assertEquals(2L, metadata.getLong(MediaMetadata.METADATA_KEY_DISC_NUMBER))
        assertEquals(3L, metadata.getLong("android.media.metadata.TOTAL_DISCS"))
    }
}
