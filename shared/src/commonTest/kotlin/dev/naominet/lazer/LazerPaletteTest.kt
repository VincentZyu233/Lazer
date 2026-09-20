package dev.naominet.lazer

import kotlin.test.Test
import kotlin.test.assertEquals

class LazerPaletteTest {
    @Test
    fun nowPlayingPaletteRoundTripsThroughSettings() {
        assertEquals(LazerPalette.NowPlaying, LazerPalette.parse("NOW_PLAYING"))
        assertEquals("NOW_PLAYING", LazerPalette.NowPlaying.serialize())
    }
}
