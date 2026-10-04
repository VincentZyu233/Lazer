package dev.naominet.lazer

import android.media.AudioManager
import dev.naominet.lazer.gateway.AudioQuality
import dev.naominet.lazer.gateway.model.SongUrl
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import org.junit.Test

class AndroidPlaybackUrlTest {
    @Test
    fun exclusiveAudioUsesExclusiveTransientFocus() {
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, androidAudioFocusGain(exclusiveAudio = false))
        assertEquals(
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE,
            androidAudioFocusGain(exclusiveAudio = true),
        )
    }

    @Test
    fun independentPlaybackOptsOutOfSystemMediaControls() {
        assertEquals(false, AndroidPlaybackInterface.INDEPENDENT.usesSystemMediaControls())
        assertEquals(true, AndroidPlaybackInterface.SYSTEM_MEDIA.usesSystemMediaControls())
    }

    @Test
    fun unknownPlaybackInterfaceFallsBackToSystemMedia() {
        assertEquals(AndroidPlaybackInterface.SYSTEM_MEDIA, parseAndroidPlaybackInterface(null))
        assertEquals(AndroidPlaybackInterface.SYSTEM_MEDIA, parseAndroidPlaybackInterface("future-interface"))
        assertEquals(
            AndroidPlaybackInterface.INDEPENDENT,
            parseAndroidPlaybackInterface(AndroidPlaybackInterface.INDEPENDENT.name),
        )
    }

    @Test
    fun defaultsUnknownStoredAudioQualityToExHigh() {
        assertEquals(AudioQuality.EXHIGH, parseAndroidAudioQuality(null))
        assertEquals(AudioQuality.EXHIGH, parseAndroidAudioQuality("future-quality"))
        assertEquals(AudioQuality.LOSSLESS, parseAndroidAudioQuality(AudioQuality.LOSSLESS.name))
    }

    @Test
    fun fallsBackFromPreferredQualityWithoutUpgrading() {
        assertEquals(
            listOf(
                AudioQuality.LOSSLESS to false,
                AudioQuality.EXHIGH to false,
                AudioQuality.HIGHER to false,
                AudioQuality.STANDARD to false,
                AudioQuality.LOSSLESS to true,
            ),
            androidAudioQualityAttempts(AudioQuality.LOSSLESS),
        )
        assertEquals(
            listOf(
                AudioQuality.STANDARD to false,
                AudioQuality.STANDARD to true,
            ),
            androidAudioQualityAttempts(AudioQuality.STANDARD),
        )
    }

    @Test
    fun gatewayAndLocalTracksTakeSeparatePlaybackRoutes() {
        val gatewayTrack = LazerTrack(
            id = 42L,
            title = "Remote",
            artist = "Artist",
            album = "Album",
            durationMillis = 1_000L,
        )
        val localTrack = gatewayTrack.copy(
            id = Long.MIN_VALUE,
            title = "Local",
            source = LazerTrackSource.LocalFile("content://provider/document/7"),
        )

        assertEquals(AndroidAudioPlaybackSource.GatewaySong(42L), androidAudioPlaybackSource(gatewayTrack))
        assertEquals(
            AndroidAudioPlaybackSource.LocalUri("content://provider/document/7"),
            androidAudioPlaybackSource(localTrack),
        )
    }

    @Test
    fun resolvedSourceKeepsRequestedAndSuccessfulQualitySeparateFromReportedStreamMetadata() {
        val resolved = androidResolvedAudioStream(
            songUrl = SongUrl(
                id = 42L,
                url = "http://m801.music.126.net/audio.flac?signature=secret",
                br = 921_600,
                size = 58_000_000L,
                type = "flac",
            ),
            requestedQuality = AudioQuality.HI_RES,
            resolvedQualityAttempt = AudioQuality.LOSSLESS,
            usedUnblockFallback = false,
            expiresAtMillis = 10_000L,
        ) ?: error("Expected a valid network stream")

        assertEquals("https://m801.music.126.net/audio.flac?signature=secret", resolved.url)
        assertEquals(10_000L, resolved.expiresAtMillis)
        assertEquals(PlaybackSourceStatus.Resolved, resolved.source.status)
        assertEquals(AudioQuality.HI_RES, resolved.source.requestedQuality)
        assertEquals(AudioQuality.LOSSLESS, resolved.source.resolvedQualityAttempt)
        assertEquals(false, resolved.source.usedUnblockFallback)
        assertEquals("flac", resolved.source.reportedType)
        assertEquals(921_600, resolved.source.reportedBitrate)
        assertEquals(58_000_000L, resolved.source.reportedSizeBytes)
        assertFalse(resolved.source.toString().contains("signature=secret"))
    }

    @Test
    fun streamSelectionDoesNotAttachMetadataFromAnUnrelatedSong() {
        val wrong = SongUrl(id = 99L, url = "https://m801.music.126.net/wrong.mp3")
        val requested = SongUrl(id = 42L, url = "https://m801.music.126.net/requested.mp3")

        assertEquals(requested, androidSelectSongUrl(listOf(wrong, requested), trackId = 42L))
        assertNull(androidSelectSongUrl(listOf(wrong), trackId = 42L))
        assertEquals(
            SongUrl(id = 0L, url = "https://m801.music.126.net/no-id.mp3"),
            androidSelectSongUrl(
                listOf(SongUrl(id = 0L, url = "https://m801.music.126.net/no-id.mp3")),
                trackId = 42L,
            ),
        )
    }

    @Test
    fun infersAUserOnlyWhenOneLegacyPlaylistCacheExists() {
        assertEquals(42L, cachedUserIdHint(setOf("featured.playlists", "user.playlists.42")))
        assertNull(cachedUserIdHint(setOf("user.playlists.42", "user.playlists.99")))
    }

    @Test
    fun upgradesClearTextCdnUrlForModernAndroid() {
        assertEquals(
            "https://m801.music.126.net/song.mp3?token=1",
            normalizedPlaybackUrl("http://m801.music.126.net/song.mp3?token=1"),
        )
    }

    @Test
    fun fillsProtocolRelativeUrl() {
        assertEquals(
            "https://m801.music.126.net/song.mp3",
            normalizedPlaybackUrl("//m801.music.126.net/song.mp3"),
        )
    }

    @Test
    fun rejectsNonNetworkPlaybackSource() {
        assertNull(normalizedPlaybackUrl("file:///storage/emulated/0/song.mp3"))
    }

    @Test
    fun upgradesClearTextArtistArtworkUrl() {
        assertEquals(
            "https://p4.music.126.net/109951169164936450.jpg",
            sequenceOf("http://p4.music.126.net/109951169164936450.jpg", null, null)
                .mapNotNull(::normalizedArtworkUrl)
                .firstOrNull(),
        )
    }

    @Test
    fun blankArtistCoverDoesNotHideAvatar() {
        assertEquals(
            "https://p4.music.126.net/avatar.jpg",
            sequenceOf("", null, "http://p4.music.126.net/avatar.jpg")
                .mapNotNull(::normalizedArtworkUrl)
                .firstOrNull(),
        )
    }
}
