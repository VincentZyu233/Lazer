package dev.naominet.lazer

import kotlin.test.assertEquals
import org.junit.Test

class AndroidLyricIndexTest {
    private val lines = listOf(
        AndroidTimedLyricLine(0L, "first"),
        AndroidTimedLyricLine(4_000L, "second"),
        AndroidTimedLyricLine(9_500L, "third"),
    )

    @Test
    fun activeLineFollowsThePlaybackPosition() {
        assertEquals(-1, activeAndroidLyricIndex(lines, -1L))
        assertEquals(0, activeAndroidLyricIndex(lines, 0L))
        assertEquals(0, activeAndroidLyricIndex(lines, 3_999L))
        assertEquals(1, activeAndroidLyricIndex(lines, 4_000L))
        assertEquals(2, activeAndroidLyricIndex(lines, 9_500L))
        assertEquals(2, activeAndroidLyricIndex(lines, 120_000L))
    }

    @Test
    fun anEmptyLyricListNeverHighlights() {
        assertEquals(-1, activeAndroidLyricIndex(emptyList(), 0L))
    }
}
