package dev.naominet.lazer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackAudioSessionSnapshotTest {
    @Test
    fun `active session readback keeps valid route and hardware values`() {
        val snapshot = PlaybackAudioSessionSnapshot.fromSystemReadback(
            sampleRateHz = 96_000.0,
            outputChannelCount = 2,
            outputRouteName = "  USB DAC  ",
            outputPortTypes = "  USBAudio  ",
            ioBufferDurationSeconds = 0.005,
            active = true,
            interrupted = false,
            configurationError = "  ",
            sourceTrackSampleRateHz = 44_100.0,
            preferredSampleRateHz = 44_100.0,
        )

        assertTrue(snapshot.active)
        assertFalse(snapshot.interrupted)
        assertEquals(44_100.0, snapshot.sourceTrackSampleRateHz)
        assertEquals(44_100.0, snapshot.preferredSampleRateHz)
        assertEquals(96_000.0, snapshot.sampleRateHz)
        assertEquals(2, snapshot.outputChannelCount)
        assertEquals("USB DAC", snapshot.outputRouteName)
        assertEquals("USBAudio", snapshot.outputPortTypes)
        assertEquals(5, snapshot.ioBufferDurationMillis)
        assertNull(snapshot.configurationError)
    }

    @Test
    fun `inactive or interrupted session does not present stale format as active`() {
        val snapshot = PlaybackAudioSessionSnapshot.fromSystemReadback(
            sampleRateHz = 48_000.0,
            outputChannelCount = 2,
            outputRouteName = "Built-in Speaker",
            outputPortTypes = "BuiltInSpeaker",
            ioBufferDurationSeconds = 0.01,
            active = false,
            interrupted = true,
            configurationError = "activation failed",
            sourceTrackSampleRateHz = 44_100.0,
            preferredSampleRateHz = 44_100.0,
        )

        assertFalse(snapshot.active)
        assertTrue(snapshot.interrupted)
        assertEquals(44_100.0, snapshot.sourceTrackSampleRateHz)
        assertEquals(44_100.0, snapshot.preferredSampleRateHz)
        assertNull(snapshot.sampleRateHz)
        assertNull(snapshot.outputChannelCount)
        assertNull(snapshot.ioBufferDurationMillis)
        assertEquals("Built-in Speaker", snapshot.outputRouteName)
        assertEquals("activation failed", snapshot.configurationError)
    }

    @Test
    fun `invalid numeric readback and empty route details are omitted`() {
        val snapshot = PlaybackAudioSessionSnapshot.fromSystemReadback(
            sampleRateHz = Double.NaN,
            outputChannelCount = 0,
            outputRouteName = " ",
            outputPortTypes = "",
            ioBufferDurationSeconds = Double.POSITIVE_INFINITY,
            active = true,
            interrupted = false,
            configurationError = "",
            sourceTrackSampleRateHz = Double.NaN,
            preferredSampleRateHz = Double.POSITIVE_INFINITY,
        )

        assertNull(snapshot.sourceTrackSampleRateHz)
        assertNull(snapshot.preferredSampleRateHz)
        assertNull(snapshot.sampleRateHz)
        assertNull(snapshot.outputChannelCount)
        assertNull(snapshot.outputRouteName)
        assertNull(snapshot.outputPortTypes)
        assertNull(snapshot.ioBufferDurationMillis)
        assertNull(snapshot.configurationError)
    }
}
