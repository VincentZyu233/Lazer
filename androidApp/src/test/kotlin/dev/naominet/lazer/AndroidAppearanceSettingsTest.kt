package dev.naominet.lazer

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
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

    @Test
    fun libraryTipsCycleThroughTenEntriesWithoutImmediateRepeats() {
        val visited = buildList {
            var current = -1
            repeat(ANDROID_LIBRARY_TIP_COUNT) {
                val next = nextAndroidLibraryTipIndex(current)
                assertNotEquals(current, next)
                add(next)
                current = next
            }
        }
        assertEquals(ANDROID_LIBRARY_TIP_COUNT, visited.toSet().size)
        assertTrue(visited.all { it in 0 until ANDROID_LIBRARY_TIP_COUNT })
        assertEquals(visited.first(), nextAndroidLibraryTipIndex(visited.last()))
    }
}
