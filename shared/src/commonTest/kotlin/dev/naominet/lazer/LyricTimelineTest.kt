package dev.naominet.lazer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LyricTimelineTest {
    @Test
    fun parsesAndMergesTheSameTimelineForEveryPlatform() {
        val lyrics = parseTimedLrc("[00:01.00]first\n[00:02.50][00:04.00]second")
        val translations = parseTimedLrc("[00:01.40]第一句\n[00:04.20]第二句")

        assertEquals(listOf(1_000L, 2_500L, 4_000L), lyrics.map(TimedLyricLine::timeMs))
        assertEquals(
            listOf("第一句", null, "第二句"),
            mergeTimedLyrics(lyrics, translations).map(TimedLyricLine::translation),
        )
    }

    @Test
    fun selectionCopiesOnlySelectedVocalRowsAndTheirTranslations() {
        val lines = listOf(
            TimedLyricLine(0L, "first", translation = "第一句"),
            TimedLyricLine(1_000L, ""),
            TimedLyricLine(2_000L, "second"),
        )
        val selection = LyricSelectionState().begin(lyricLineKeys(lines), 0).extendTo(lyricLineKeys(lines), 2)

        assertEquals("first\n第一句\nsecond", buildLyricClipboardText(lines, selection.selectedKeys))
        assertEquals(2, selection.selectedCount)
    }

    @Test
    fun selectionKeepsItsLinesWhenAnInterludeRowEntersTheSheet() {
        val before = listOf(
            TimedLyricLine(0L, "first"),
            TimedLyricLine(2_000L, "second"),
            TimedLyricLine(4_000L, "third"),
        )
        val withInterlude = listOf(
            before[0],
            TimedLyricLine(1_000L, ""),
            before[1],
            before[2],
        )
        val selection = LyricSelectionState().begin(lyricLineKeys(before), 1).extendTo(lyricLineKeys(before), 2)

        assertEquals(
            selection.selectedKeys,
            selection.extendTo(lyricLineKeys(withInterlude), 3).selectedKeys,
        )
        assertTrue(selection.isSelected(lyricLineKeys(withInterlude), 2))
        assertFalse(selection.isSelected(lyricLineKeys(withInterlude), 1))
        assertEquals("second\nthird", buildLyricClipboardText(withInterlude, selection.selectedKeys))
    }

    @Test
    fun droppingTheAnchorReanchorsOnTheNearestSurvivingLine() {
        val lines = listOf(
            TimedLyricLine(0L, "first"),
            TimedLyricLine(1_000L, "second"),
            TimedLyricLine(2_000L, "third"),
        )
        val keys = lyricLineKeys(lines)
        val selection = LyricSelectionState().begin(keys, 0).extendTo(keys, 1)
        val dropped = selection.toggle(keys, 0)

        assertEquals(lyricLineKey(lines[1]), dropped.anchorKey)
        assertEquals(setOf(lyricLineKey(lines[1])), dropped.selectedKeys)
        assertTrue(dropped.isActive)
        assertEquals(
            setOf(lyricLineKey(lines[1]), lyricLineKey(lines[2])),
            dropped.extendTo(keys, 2).selectedKeys,
        )
    }

    @Test
    fun selectAllSkipsTheInterludeRow() {
        val lines = listOf(
            TimedLyricLine(0L, "first"),
            TimedLyricLine(1_000L, ""),
            TimedLyricLine(2_000L, "second"),
        )
        val selection = LyricSelectionState().selectAll(lyricLineKeys(lines))

        assertEquals(2, selection.selectedCount)
        assertEquals("first\nsecond", buildLyricClipboardText(lines, selection.selectedKeys))
    }

    @Test
    fun selectionInkLlightsMarkedLinesAndDimsTheRest() {
        assertEquals(0.6f, lyricSelectionRowAlpha(0.6f, modePresence = 0f, rowPresence = 0f))
        // A marked row is lifted by the focus it borrows, not by the alpha helper.
        assertEquals(0.6f, lyricSelectionRowAlpha(0.6f, modePresence = 1f, rowPresence = 1f))
        assertTrue(lyricSelectionRowAlpha(0.6f, 1f, 0f) < 0.6f)
        assertEquals(1f, lyricSelectionHighlight(focus = 0f, presence = 1f))
        assertEquals(0.8f, lyricSelectionHighlight(focus = 0.8f, presence = 0f))
        assertEquals(0f, lyricSelectionHighlight(focus = 0f, presence = 0f))
        assertEquals(260, lyricSelectionCascadeDelay(index = 40, anchorIndex = 0))
        assertEquals(16, lyricSelectionCascadeDelay(index = 1, anchorIndex = 0))
    }
}
