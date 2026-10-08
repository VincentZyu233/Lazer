package dev.naominet.lazer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopReplayGainTest {
    @Test
    fun `off bypasses replaygain and missing tags resolve to none`() {
        val tags = DesktopReplayGainTags(3.0, 0.5, -2.0, 0.8)

        assertEquals(DesktopReplayGainResolution(), resolveDesktopReplayGain(tags, DesktopReplayGainMode.Off))
        assertEquals(
            DesktopReplayGainResolution(),
            resolveDesktopReplayGain(null, DesktopReplayGainMode.Track),
        )
        assertEquals(
            DesktopReplayGainResolution(),
            resolveDesktopReplayGain(DesktopReplayGainTags(null, null, null, null), DesktopReplayGainMode.Album),
        )
    }

    @Test
    fun `track and album modes use gain and peak from the same tag level`() {
        val tags = DesktopReplayGainTags(
            trackGainDb = 4.0,
            trackPeak = 0.5,
            albumGainDb = -3.0,
            albumPeak = 1.0,
        )

        assertEquals(
            DesktopReplayGainResolution(appliedGainDb = 4.0, source = DesktopReplayGainSource.Track),
            resolveDesktopReplayGain(tags, DesktopReplayGainMode.Track),
        )
        assertEquals(
            DesktopReplayGainResolution(appliedGainDb = -3.0, source = DesktopReplayGainSource.Album),
            resolveDesktopReplayGain(tags, DesktopReplayGainMode.Album),
        )

        val levelSpecificPeaks = DesktopReplayGainTags(
            trackGainDb = 4.0,
            trackPeak = 16.0,
            albumGainDb = 4.0,
            albumPeak = 0.5,
        )
        assertEquals(
            DesktopReplayGainResolution(
                source = DesktopReplayGainSource.Track,
                boostBlocked = true,
                boostLimited = true,
            ),
            resolveDesktopReplayGain(levelSpecificPeaks, DesktopReplayGainMode.Track),
        )
        assertEquals(
            DesktopReplayGainResolution(appliedGainDb = 4.0, source = DesktopReplayGainSource.Album),
            resolveDesktopReplayGain(levelSpecificPeaks, DesktopReplayGainMode.Album),
        )
    }

    @Test
    fun `album mode falls back to track gain and matching track peak`() {
        val tags = DesktopReplayGainTags(
            trackGainDb = 4.0,
            trackPeak = 0.5,
            albumGainDb = null,
            albumPeak = 16.0,
        )

        assertEquals(
            DesktopReplayGainResolution(appliedGainDb = 4.0, source = DesktopReplayGainSource.TrackFallback),
            resolveDesktopReplayGain(tags, DesktopReplayGainMode.Album),
        )
    }

    @Test
    fun `positive gain requires its matching peak and preserves one dB sample peak headroom`() {
        val missingPeak = resolveDesktopReplayGain(
            DesktopReplayGainTags(trackGainDb = 4.0, trackPeak = null, albumGainDb = null, albumPeak = null),
            DesktopReplayGainMode.Track,
        )
        assertEquals(0.0, missingPeak.appliedGainDb, 0.0)
        assertTrue(missingPeak.boostBlocked)
        assertTrue(missingPeak.boostLimited)

        val limitedByPeak = resolveDesktopReplayGain(
            DesktopReplayGainTags(trackGainDb = 6.0, trackPeak = 0.5, albumGainDb = null, albumPeak = null),
            DesktopReplayGainMode.Track,
        )
        assertEquals(-1.0 - 20.0 * kotlin.math.log10(0.5), limitedByPeak.appliedGainDb, 1e-12)
        assertFalse(limitedByPeak.boostBlocked)
        assertTrue(limitedByPeak.boostLimited)

        val unrestricted = resolveDesktopReplayGain(
            DesktopReplayGainTags(trackGainDb = 4.0, trackPeak = 0.5, albumGainDb = null, albumPeak = null),
            DesktopReplayGainMode.Track,
        )
        assertEquals(4.0, unrestricted.appliedGainDb, 0.0)
        assertFalse(unrestricted.boostBlocked)
        assertFalse(unrestricted.boostLimited)
    }

    @Test
    fun `invalid manually supplied numeric values are rejected`() {
        val invalid = DesktopReplayGainTags(
            trackGainDb = Double.NaN,
            trackPeak = Double.POSITIVE_INFINITY,
            albumGainDb = 24.1,
            albumPeak = 16.1,
        )

        assertEquals(
            DesktopReplayGainResolution(),
            resolveDesktopReplayGain(invalid, DesktopReplayGainMode.Track),
        )
        assertEquals(
            DesktopReplayGainResolution(),
            resolveDesktopReplayGain(invalid, DesktopReplayGainMode.Album),
        )
    }

    @Test
    fun `negative gain remains available without peak and exact limits are accepted`() {
        assertEquals(
            DesktopReplayGainResolution(appliedGainDb = -60.0, source = DesktopReplayGainSource.Track),
            resolveDesktopReplayGain(
                DesktopReplayGainTags(-60.0, null, null, null),
                DesktopReplayGainMode.Track,
            ),
        )
        assertEquals(
            DesktopReplayGainResolution(appliedGainDb = 24.0, source = DesktopReplayGainSource.Track),
            resolveDesktopReplayGain(
                DesktopReplayGainTags(24.0, 1e-12, null, null),
                DesktopReplayGainMode.Track,
            ),
        )
    }
}
