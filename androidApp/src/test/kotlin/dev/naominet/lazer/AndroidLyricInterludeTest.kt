package dev.naominet.lazer

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class AndroidLyricInterludeTest {
    @Test fun interludeIsInsertedOnlyWhileItsIntervalIsActive() {
        val timeline = androidLyricsWithInterludes(
            listOf(AndroidTimedLyricLine(0, "first", endTimeMs = 2_000),
                AndroidTimedLyricLine(12_000, "second", endTimeMs = 14_000)), 20_000,
        )
        fun display(at: Long) = androidLyricDisplayLines(timeline, activeAndroidInterlude(timeline, at))
        assertEquals(listOf("first", "second"), display(1_999).map { it.text })
        assertEquals(listOf("first", "", "second"), display(2_000).map { it.text })
        assertEquals(1, display(11_999).count { it.text.isBlank() })
        assertEquals(listOf("first", "second"), display(12_000).map { it.text })
        assertEquals(listOf("first", "second", ""), display(14_000).map { it.text })
        assertEquals(listOf("first", "second"), display(20_000).map { it.text })
        assertEquals(listOf("first", "", "second"), display(3_000).map { it.text })
        assertTrue(display(3_000).first() === timeline.first())
    }

    @Test fun introAndSeekUseTheSameTimeline() {
        val lines = androidLyricsWithInterludes(listOf(AndroidTimedLyricLine(6_000, "vocal")), 10_000)
        assertTrue(lines[activeAndroidLyricIndex(lines, 5_999)].text.isBlank())
        assertEquals("vocal", lines[activeAndroidLyricIndex(lines, 6_000)].text)
        assertTrue(lines[activeAndroidLyricIndex(lines, 0)].text.isBlank())
    }

    @Test fun wordEndingDefinesTheGapWithoutTruncatingSustainedWords() {
        val parsed = parseAndroidWordLyrics("[0,9000](0,9000,0)hold\n[16000,1000](16000,1000,0)next")
        val lines = androidLyricsWithInterludes(parsed, 18_000)
        assertEquals("hold", lines[activeAndroidLyricIndex(lines, 8_999)].text)
        assertTrue(lines[activeAndroidLyricIndex(lines, 9_000)].text.isBlank())
        assertEquals("next", lines[activeAndroidLyricIndex(lines, 16_000)].text)
    }

    @Test fun explicitLrcEmptyLinesArePreserved() {
        val parsed = parseAndroidLrc("[00:00.00]sing\n[00:02.00]\n[00:12.00]again")
        val lines = androidLyricsWithInterludes(parsed, 15_000)
        assertEquals(3, lines.size)
        assertTrue(lines[activeAndroidLyricIndex(lines, 2_000)].text.isBlank())
        assertEquals(12_000L, lines[1].endTimeMillis)
    }

    @Test fun normalLrcPhrasesDoNotGetGuessedGaps() {
        val parsed = parseAndroidLrc("[00:00]long phrase\n[00:10]next")
        assertFalse(androidLyricsWithInterludes(parsed, 14_000).any { it.text.isBlank() })
    }

    @Test fun unknownDurationDoesNotInventAnOutro() {
        val lines = androidLyricsWithInterludes(listOf(AndroidTimedLyricLine(0, "last", endTimeMs = 2_000)), 0)
        assertEquals(1, lines.size)
        assertTrue(androidLyricsWithInterludes(emptyList(), 20_000).isEmpty())
    }
}
