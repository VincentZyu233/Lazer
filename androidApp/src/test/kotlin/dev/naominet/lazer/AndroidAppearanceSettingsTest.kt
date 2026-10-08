package dev.naominet.lazer

import kotlin.test.assertEquals
import org.junit.Test

class AndroidAppearanceSettingsTest {
    @Test
    fun backgroundModeParserFallsBackToSolidAndRestoresEveryMode() {
        assertEquals(AndroidBackgroundMode.SOLID, parseAndroidBackgroundMode(null))
        assertEquals(AndroidBackgroundMode.SOLID, parseAndroidBackgroundMode("future-mode"))
        AndroidBackgroundMode.entries.forEach { mode ->
            assertEquals(mode, parseAndroidBackgroundMode(mode.name))
        }
    }

    @Test
    fun backgroundImageBlurIntensityIsBounded() {
        assertEquals(0f, normalizeBackgroundImageBlurIntensity(-0.5f))
        assertEquals(0.4f, normalizeBackgroundImageBlurIntensity(0.4f))
        assertEquals(1f, normalizeBackgroundImageBlurIntensity(1.5f))
    }
}
