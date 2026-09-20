package dev.naominet.lazer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DesktopLyricInterludeTest {
    @Test
    fun desktopTimelineAddsPreludeAndWordTimedInstrumentalRows() {
        val vocal = listOf(
            TimedLyricLine(
                timeMs = 6_000L,
                text = "第一句",
                words = listOf(TimedLyricWord(6_000L, 1_000L, "第一句")),
                endTimeMs = 7_000L,
            ),
            TimedLyricLine(timeMs = 13_000L, text = "第二句"),
        )

        val timeline = desktopLyricsWithInterludes(vocal, durationMs = 20_000L)

        assertEquals(listOf(0L, 6_000L, 7_000L, 13_000L), timeline.map(TimedLyricLine::timeMs))
        assertEquals(listOf(true, false, true, false), timeline.map { it.text.isBlank() })
    }

    @Test
    fun onlyTheActiveInterludeIsInsertedIntoTheRenderedSheet() {
        val timeline = listOf(
            TimedLyricLine(0L, "第一句", endTimeMs = 1_000L),
            TimedLyricLine(1_000L, "", endTimeMs = 6_000L),
            TimedLyricLine(6_000L, "第二句"),
        )
        val active = activeDesktopInterlude(timeline, 3_000L)

        assertSame(timeline[1], active)
        assertEquals(timeline, desktopLyricDisplayLines(timeline, active))
        assertNull(activeDesktopInterlude(timeline, 6_000L))
        assertEquals(listOf(timeline[0], timeline[2]), desktopLyricDisplayLines(timeline, null))
    }
}
