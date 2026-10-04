package dev.naominet.lazer

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class AndroidLocalAudioPickerTest {
    @Test
    fun acceptsWavAndFlacByExtensionOrKnownMimeType() {
        assertTrue(isSupportedAndroidLocalAudio("album track.WAV", null))
        assertTrue(isSupportedAndroidLocalAudio("disc.flac", "application/octet-stream"))
        assertTrue(isSupportedAndroidLocalAudio("track", "audio/x-flac"))
    }

    @Test
    fun rejectsFormatsOutsideTheFirstLocalPlaybackSlice() {
        assertFalse(isSupportedAndroidLocalAudio("track.dsf", "audio/x-dsf"))
        assertFalse(isSupportedAndroidLocalAudio("track.mp3", "audio/mpeg"))
    }
}
