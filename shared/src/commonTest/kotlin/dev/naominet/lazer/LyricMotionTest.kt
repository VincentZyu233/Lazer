package dev.naominet.lazer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LyricMotionTest {
    @Test
    fun currentWordFollowsThePlayhead() {
        val words = listOf(
            TimedLyricWord(1_000L, 400L, "还"),
            TimedLyricWord(1_400L, 400L, "没"),
            TimedLyricWord(1_800L, 400L, "见"),
        )
        assertEquals(-1, currentLyricWordIndex(words, 999L))
        assertEquals(0, currentLyricWordIndex(words, 1_000L))
        assertEquals(0, currentLyricWordIndex(words, 1_399L))
        assertEquals(1, currentLyricWordIndex(words, 1_400L))
        assertEquals(2, currentLyricWordIndex(words, 2_500L))
        assertTrue(currentLyricWordIndex(words, 1_400L) >= 1)
    }

    @Test
    fun lineScanTravelsWithTheCurrentWord() {
        val words = listOf(
            TimedLyricWord(1_000L, 400L, "还"),
            TimedLyricWord(1_400L, 400L, "没"),
            TimedLyricWord(1_800L, 400L, "见"),
        )
        assertEquals(0f, lyricLineScanFraction(words, 999L, LyricAnimationSpeed.STANDARD))
        assertTrue(lyricLineScanFraction(words, 1_000L, LyricAnimationSpeed.STANDARD) > 0f)
        assertTrue(
            lyricLineScanFraction(words, 1_400L, LyricAnimationSpeed.STANDARD) >
                lyricLineScanFraction(words, 1_200L, LyricAnimationSpeed.STANDARD),
        )
        assertEquals(1f, lyricLineScanFraction(words, 2_500L, LyricAnimationSpeed.STANDARD), 0.001f)
    }

    @Test
    fun longSyllablesHaveFractionalProgressAndSeekBackwards() {
        val word = TimedLyricWord(1_000L, 2_000L, "你")
        assertEquals(0f, lyricWordProgress(word, 900L))
        assertEquals(0.25f, lyricWordProgress(word, 1_500L))
        assertEquals(1f, lyricWordProgress(word, 3_500L))
        assertEquals(0.1f, lyricWordProgress(word, 1_200L))
        assertEquals(1f, lyricWordProgress(word.copy(durationMillis = 0), 1_001L))
    }

    @Test
    fun followConvergesWithoutOvershootAtDifferentFrameRates() {
        fun simulate(fps: Int): Float {
            var position = 0f
            repeat(fps) {
                val next = nextLyricScrollPosition(position, 112f, 1f / fps, LyricAnimationSpeed.STANDARD)
                assertTrue(next >= position && next <= 112f)
                position = next
            }
            return position
        }
        assertEquals(simulate(30), simulate(120), 0.001f)
        assertTrue(simulate(60) > 111f)
    }

    @Test
    fun followUsesANonlinearEaseCurve() {
        val firstFrame = nextLyricScrollPosition(0f, 100f, 0.05f, LyricAnimationSpeed.STANDARD)
        val secondFrame = nextLyricScrollPosition(firstFrame, 100f, 0.05f, LyricAnimationSpeed.STANDARD)
        val thirdFrame = nextLyricScrollPosition(secondFrame, 100f, 0.05f, LyricAnimationSpeed.STANDARD)
        assertTrue(firstFrame > secondFrame - firstFrame)
        assertTrue(secondFrame - firstFrame > thirdFrame - secondFrame)
    }

    @Test
    fun visualWordTimingStartsEarlyAndFinishesLate() {
        val word = TimedLyricWord(1_000L, 300L, "你")
        val nextWord = TimedLyricWord(1_300L, 300L, "好")
        assertTrue(lyricWordVisualProgress(word, 999L, LyricAnimationSpeed.STANDARD) > 0f)
        assertTrue(lyricWordVisualProgress(word, 1_301L, LyricAnimationSpeed.STANDARD) < 1f)
        assertTrue(lyricWordVisualProgress(word, 1_300L, LyricAnimationSpeed.STANDARD) < 1f)
        assertTrue(lyricWordVisualProgress(nextWord, 1_300L, LyricAnimationSpeed.STANDARD) > 0f)
        assertTrue(
            lyricWordSmoothingMillis(LyricAnimationSpeed.VERY_RELAXED) >
                lyricWordSmoothingMillis(LyricAnimationSpeed.VERY_RESPONSIVE),
        )
    }

    @Test
    fun amllMaskMovesOneHalfEmFadeBandAcrossTheMeasuredWord() {
        val word = TimedLyricWord(1_000L, 1_000L, "你好")
        assertEquals(0f, lyricWordMaskProgress(word, 999L), 0.0001f)
        assertEquals(0.5f, lyricWordMaskProgress(word, 1_500L), 0.0001f)
        assertEquals(1f, lyricWordMaskProgress(word, 2_001L), 0.0001f)
        assertEquals(0f, lyricWordMaskTravel(0f, 0f, 100f, 20f), 0.0001f)
        assertEquals(30f, lyricWordMaskTravel(50f, 0f, 100f, 20f), 0.0001f)
        assertEquals(120f, lyricWordMaskTravel(900f, 0f, 100f, 20f), 0.0001f)
    }

    @Test
    fun theLineOpensAndClosesWithExtraFeatherRunway() {
        // AMLL sweeps its first word one and a half feather widths further and its last half a
        // feather further. The head start is what centres the feather on the sung position for every
        // word after the first; the tail is what lands the lit edge on the end of the line.
        assertEquals(0f, lyricLineSweepAllowance(0f, 0f), 0.0001f)
        assertEquals(1.5f, lyricLineSweepAllowance(1f, 0f), 0.0001f)
        assertEquals(2f, lyricLineSweepAllowance(1f, 1f), 0.0001f)
        assertEquals(130f, lyricLineSweptWidth(listOf(100f, 100f), listOf(1f, 0.3f)), 0.0001f)
    }

    @Test
    fun theLitEdgeIsOneContinuousSweepAcrossTheLine() {
        val widths = listOf(100f, 100f, 100f)
        val fade = 50f
        val words = listOf(
            TimedLyricWord(0L, 1_000L, "还"),
            TimedLyricWord(1_400L, 1_000L, "没"),
            TimedLyricWord(2_400L, 1_000L, "好"),
        )
        fun travelAt(positionMillis: Long, index: Int): Float {
            val fractions = words.map { lyricWordMaskProgress(it, positionMillis) }
            val swept = lyricLineSweptWidth(widths, fractions) +
                fade * lyricLineSweepAllowance(fractions.first(), fractions.last())
            return lyricWordMaskTravel(
                sweptWidth = swept,
                widthBefore = widths.take(index).sum(),
                wordWidth = widths[index],
                fadeWidth = fade,
                headStart = if (index == 0) fade else 0f,
            )
        }
        // Nothing is lit before the line, and the whole line is lit exactly when it ends.
        assertEquals(0f, travelAt(-1L, 0), 0.0001f)
        assertEquals(0f, travelAt(-1L, 2), 0.0001f)
        for (index in widths.indices) {
            assertEquals(widths[index] + fade, travelAt(3_400L, index), 0.0001f)
        }
        // Halfway through a word the feather is centred on the sung position, so half the word plus
        // half the feather has travelled.
        assertEquals(75f, travelAt(1_900L, 1), 0.0001f)
        // The edge crosses a word boundary as one wave: the first word is lit to its last pixel at
        // the same moment the next one is lit from its first, so no hard edge appears between them.
        assertEquals(widths[0] + fade, travelAt(1_000L, 0), 0.0001f)
        assertEquals(fade / 2f, travelAt(1_000L, 1), 0.0001f)
        // A pause holds the sweep where it stopped rather than finishing the word or rewinding it.
        assertEquals(travelAt(1_000L, 0), travelAt(1_400L, 0), 0.0001f)
        assertEquals(travelAt(1_000L, 1), travelAt(1_400L, 1), 0.0001f)
        // The line's first word lights as the line starts: upstream can afford to hold it dark
        // because upstream also pulls the line's start time forward, and this sheet does not.
        assertEquals(0f, travelAt(0L, 0), 0.0001f)
        assertTrue(travelAt(1L, 0) > 0f)
    }

    @Test
    fun amllLineFocusUsesExactScaleAndDistanceBlurTargets() {
        assertEquals(0.97f, amllLyricLineScale(0f), 0.0001f)
        assertEquals(1f, amllLyricLineScale(1f), 0.0001f)
        assertEquals(0f, amllLyricBlurRadiusDp(3f, 1f, false, false), 0.0001f)
        val justLeavingFocus = amllLyricBlurRadiusDp(0f, 0.9f, false, false)
        val halfwayBlurred = amllLyricBlurRadiusDp(0f, 0.5f, false, false)
        assertTrue(justLeavingFocus > 0f)
        assertTrue(halfwayBlurred > justLeavingFocus)
        assertEquals(3.2f, amllLyricBlurRadiusDp(3f, 0f, true, false), 0.0001f)
        assertEquals(5f, amllLyricBlurRadiusDp(12f, 0f, false, false), 0.0001f)
        assertEquals(0f, amllLyricBlurRadiusDp(3f, 0f, false, true), 0.0001f)
    }

    @Test
    fun inactiveWordMaskFreezesInsteadOfRewindingAtALineChange() {
        assertEquals(
            1_980L,
            lyricMaskTargetPositionMillis(
                active = false,
                reportedPositionMillis = 1_000L,
                retainedActivePositionMillis = 1_980L,
            ),
        )
        assertEquals(
            2_040L,
            lyricMaskTargetPositionMillis(
                active = true,
                reportedPositionMillis = 2_040L,
                retainedActivePositionMillis = 1_980L,
            ),
        )
    }

    @Test
    fun glyphMappingKeepsYrcSyllablesOnTheirTimedWords() {
        val words = listOf(
            TimedLyricWord(16_210L, 670L, "还"),
            TimedLyricWord(16_880L, 410L, "没 "),
            TimedLyricWord(17_290L, 980L, "见"),
        )
        val text = words.joinToString(separator = "", transform = TimedLyricWord::text)
        val glyphs = buildTimedLyricGlyphs(text, words)
        assertEquals("还没 见", text)
        assertEquals(listOf(0, 1, 1, 2), glyphs.map { it.wordIndex })
        assertEquals(listOf(true, true, false, true), glyphs.map { it.isVisible })
    }

    @Test
    fun glyphMappingKeepsEmojiAndCombiningMarksTogether() {
        val text = "A👩‍🎤e\u0301好"
        val glyphs = buildTimedLyricGlyphs(text, listOf(TimedLyricWord(0L, 2_000L, text)))
        assertEquals(4, glyphs.size)
        assertEquals(listOf("A", "👩‍🎤", "e\u0301", "好"), glyphs.map {
            text.substring(it.startOffset, it.endOffset)
        })
        assertTrue(glyphs.all { it.characterCount == 4 })
    }

    @Test
    fun lineSpringsCascadeAndThenSettleAtTheSameTarget() {
        val field = LyricLineMotionField().apply { reset(lineCount = 8, position = 0f) }
        field.advance(
            target = 112f,
            activeIndex = 3,
            seconds = 1f / 60f,
            intervalMillis = 500L,
            speed = LyricAnimationSpeed.STANDARD,
        )
        assertTrue(field.positionFor(0) > field.positionFor(3))

        repeat(240) {
            field.advance(
                target = 112f,
                activeIndex = 3,
                seconds = 1f / 60f,
                intervalMillis = 500L,
                speed = LyricAnimationSpeed.STANDARD,
            )
        }
        repeat(8) { assertEquals(112f, field.positionFor(it), 0.05f) }
    }

}
