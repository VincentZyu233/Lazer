package dev.naominet.lazer

import kotlin.test.Test
import kotlin.test.assertEquals

class LazerReplayGainTest {
    @Test
    fun `off mode returns unity gain and track mode uses negative gain without peak`() {
        val tags = LazerReplayGainTags(trackGainDb = -6.0, trackPeak = null)

        assertEquals(0.0, resolveLazerReplayGain(tags, LazerReplayGainMode.Off).appliedGainDb)
        assertEquals(LazerReplayGainSource.None, resolveLazerReplayGain(tags, LazerReplayGainMode.Off).source)
        val track = resolveLazerReplayGain(tags, LazerReplayGainMode.Track)
        assertEquals(-6.0, track.appliedGainDb)
        assertEquals(LazerReplayGainSource.Track, track.source)
    }

    @Test
    fun `album mode uses album values and falls back to track values when album gain is missing`() {
        val albumTags = LazerReplayGainTags(
            trackGainDb = -5.0,
            trackPeak = 0.5,
            albumGainDb = -8.0,
            albumPeak = 0.8,
        )
        val trackFallback = LazerReplayGainTags(trackGainDb = -5.0, trackPeak = 0.5)

        val album = resolveLazerReplayGain(albumTags, LazerReplayGainMode.Album)
        val fallback = resolveLazerReplayGain(trackFallback, LazerReplayGainMode.Album)
        assertEquals(-8.0, album.appliedGainDb)
        assertEquals(LazerReplayGainSource.Album, album.source)
        assertEquals(-5.0, fallback.appliedGainDb)
        assertEquals(LazerReplayGainSource.Track, fallback.source)
    }

    @Test
    fun `positive gain requires its paired peak and is limited to one dB headroom`() {
        val noPeak = LazerReplayGainTags(trackGainDb = 4.0)
        val invalidPeak = LazerReplayGainTags(trackGainDb = 4.0, trackPeak = 0.0)
        val headroomLimited = LazerReplayGainTags(trackGainDb = 12.0, trackPeak = 0.5)

        assertEquals(0.0, resolveLazerReplayGain(noPeak, LazerReplayGainMode.Track).appliedGainDb)
        assertEquals(0.0, resolveLazerReplayGain(invalidPeak, LazerReplayGainMode.Track).appliedGainDb)
        val limited = resolveLazerReplayGain(headroomLimited, LazerReplayGainMode.Track)
        assertEquals(
            -1.0 - 20.0 * kotlin.math.log10(0.5),
            limited.appliedGainDb,
            1e-12,
        )
        assertEquals(LazerReplayGainSource.Track, limited.source)
        assertEquals(0.5, limited.peak)
        assertEquals(true, limited.peakLimited)
        assertEquals(-1.0, resolveLazerReplayGain(
            LazerReplayGainTags(trackGainDb = 3.0, trackPeak = 1.0),
            LazerReplayGainMode.Track,
        ).appliedGainDb)
    }

    @Test
    fun `invalid gain or peak values are ignored and invalid album gain falls back`() {
        val badGainValues = listOf(Double.NaN, Double.POSITIVE_INFINITY, -60.01, 24.01)
        badGainValues.forEach { gain ->
            assertEquals(0.0, resolveLazerReplayGain(
                LazerReplayGainTags(trackGainDb = gain, trackPeak = 0.5),
                LazerReplayGainMode.Track,
            ).appliedGainDb)
        }

        val badPeakValues = listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -0.5, 16.01)
        badPeakValues.forEach { peak ->
            assertEquals(0.0, resolveLazerReplayGain(
                LazerReplayGainTags(trackGainDb = 4.0, trackPeak = peak),
                LazerReplayGainMode.Track,
            ).appliedGainDb)
        }

        assertEquals(
            2.0,
            resolveLazerReplayGain(
                LazerReplayGainTags(trackGainDb = 2.0, trackPeak = 0.5, albumGainDb = 25.0),
                LazerReplayGainMode.Album,
            ).appliedGainDb,
        )
        assertEquals(0.0, resolveLazerReplayGain(null, LazerReplayGainMode.Track).appliedGainDb)
        assertEquals(0.0, resolveLazerReplayGain(
            LazerReplayGainTags(trackGainDb = 0.0),
            LazerReplayGainMode.Track,
        ).appliedGainDb)
    }

    @Test
    fun `gain and peak range endpoints are valid`() {
        val lowerGain = resolveLazerReplayGain(
            LazerReplayGainTags(trackGainDb = -60.0),
            LazerReplayGainMode.Track,
        )
        val upperGain = resolveLazerReplayGain(
            LazerReplayGainTags(trackGainDb = 24.0, trackPeak = 0.01),
            LazerReplayGainMode.Track,
        )
        val upperPeak = resolveLazerReplayGain(
            LazerReplayGainTags(trackGainDb = 2.0, trackPeak = 16.0),
            LazerReplayGainMode.Track,
        )

        assertEquals(-60.0, lowerGain.appliedGainDb)
        assertEquals(24.0, upperGain.appliedGainDb)
        assertEquals(LazerReplayGainSource.Track, upperGain.source)
        assertEquals(16.0, upperPeak.peak)
        assertEquals(-1.0 - 20.0 * kotlin.math.log10(16.0), upperPeak.appliedGainDb, 1e-12)
    }
}
