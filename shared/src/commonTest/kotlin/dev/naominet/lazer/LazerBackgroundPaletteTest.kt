package dev.naominet.lazer

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LazerBackgroundPaletteTest {

    @Test
    fun dominantColoursComeBackInCountOrder() {
        val pixels = IntArray(6) { 0xFFFF0000.toInt() } +
            IntArray(5) { 0xFF00FF00.toInt() } +
            IntArray(4) { 0xFF0000FF.toInt() } +
            // AMLL rejects near-white from the histogram, so this one never competes.
            intArrayOf(0xFFFFFFFF.toInt())
        val palette = lazerDominantPalette(pixels, width = 16, height = 1)

        assertEquals(4, palette.size)
        assertEquals(Color(0xFFFF0000), palette[0])
        assertEquals(Color(0xFF00FF00), palette[1])
        assertEquals(Color(0xFF0000FF), palette[2])
        // A short palette is padded by cycling what was found rather than inventing a neutral.
        assertEquals(palette[0], palette[3])
    }

    @Test
    fun theCoverIsReducedToAthumbnailBeforeItIsCounted() {
        val width = 128
        val height = 128
        val pixels = IntArray(width * height) { 0xFF00FF00.toInt() }
        // A single hot pixel cannot outvote a 64 px thumbnail of the field around it, which is the
        // whole point of sampling like AMLL does: one bright highlight must not become the theme.
        pixels[0] = 0xFFFF0000.toInt()
        val palette = lazerDominantPalette(pixels, width = width, height = height)

        assertEquals(Color(0xFF00FF00), palette[0])
        // The highlight still exists in the four, exactly as AMLL keeps it, but it ranks behind the
        // field it sits in instead of becoming the theme the way a mean colour would.
        assertEquals(Color(0xFFFF0000), palette[1])
    }

    @Test
    fun transparentAndBlankArtworkYieldNoPaletteAtAll() {
        val transparent = IntArray(16) { 0x00FF0000 }
        assertTrue(lazerDominantPalette(transparent, width = 4, height = 4).isEmpty())
        val white = IntArray(16) { 0xFFFFFFFF.toInt() }
        assertTrue(lazerDominantPalette(white, width = 4, height = 4).isEmpty())
    }
}
