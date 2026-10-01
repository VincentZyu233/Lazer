package dev.naominet.lazer

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the lyric emphasis math to AMLL's own numbers. Every expectation here is computed from the
 * upstream formulas, so a change to one of them has to be a deliberate decision, not a slip.
 */
class LyricEmphasizeTest {
    @Test
    fun shortWordsFallOffTheCubicShaping() {
        val strength = lyricEmphasizeStrength(wordDurationMillis = 1000L, isLastWord = false)
        assertEquals(1000f, strength.durationMillis, 0.01f)
        // (1000/2000)^3 * 0.6 and (1000/3000)^3 * 0.5
        assertEquals(0.075f, strength.amount, 0.0001f)
        assertEquals(0.018518f, strength.blur, 0.0001f)
    }

    @Test
    fun longWordsAreTamedByTheSquareRootInstead() {
        val strength = lyricEmphasizeStrength(wordDurationMillis = 4000L, isLastWord = false)
        // sqrt(4000/2000) * 0.6 and sqrt(4000/3000) * 0.5
        assertEquals(sqrt(2f) * 0.6f, strength.amount, 0.0001f)
        assertEquals(sqrt(4f / 3f) * 0.5f, strength.blur, 0.0001f)
    }

    @Test
    fun veryLongWordsHitTheCeilingsAmllClampsTo() {
        val strength = lyricEmphasizeStrength(wordDurationMillis = 20_000L, isLastWord = false)
        assertEquals(1.2f, strength.amount, 0.0001f)
        assertEquals(0.8f, strength.blur, 0.0001f)
    }

    @Test
    fun theLastWordOfALineIsPushedHarderAndHeldLonger() {
        val plain = lyricEmphasizeStrength(wordDurationMillis = 1000L, isLastWord = false)
        val final = lyricEmphasizeStrength(wordDurationMillis = 1000L, isLastWord = true)
        assertEquals(plain.amount * 1.6f, final.amount, 0.0001f)
        assertEquals(plain.blur * 1.5f, final.blur, 0.0001f)
        assertEquals(1200f, final.durationMillis, 0.0001f)
    }

    @Test
    fun charactersOfAWordStartInAQuarterOfItsDuration() {
        val strength = lyricEmphasizeStrength(wordDurationMillis = 1000L, isLastWord = false)
        val starts = (0 until 4).map { index ->
            lyricEmphasizeCharStartMillis(
                wordStartMillis = 5_000L,
                strength = strength,
                charIndex = index,
                characterCount = 4,
            )
        }
        // du / 2.5 / n == 100ms apart, so the word is spread over its first 400ms.
        assertEquals(listOf(5000f, 5100f, 5200f, 5300f), starts)
    }

    @Test
    fun theEasingIsAPulseThatLeavesTheCharacterAtRest() {
        // AMLL hands over from the in curve to a mirrored out curve halfway, so a character swells
        // to full emphasis in the middle of its window and is back to plain by the end of it.
        assertEquals(0f, lyricEmphasizeEasing(0f), 0.0001f)
        assertEquals(1f, lyricEmphasizeEasing(0.5f), 0.001f)
        assertEquals(0f, lyricEmphasizeEasing(1f), 0.0001f)
    }

    @Test
    fun thePulseRisesFastAndFallsWithoutOvershoot() {
        val samples = (0..LYRIC_EMPHASIZE_FRAMES).map { lyricEmphasizeEasing(it / 32f) }
        assertTrue(samples.all { it in -0.0001f..1.0001f })
        // Front-loaded climb: a quarter of the way in, most of the swell is already there.
        assertTrue(lyricEmphasizeEasing(0.25f) > 0.6f)
        // The two halves are deliberately not mirror images — the in curve is steeper than the
        // mirrored out curve, so a character swells quickly and eases the last of the way back.
        assertTrue(lyricEmphasizeEasing(0.25f) > lyricEmphasizeEasing(0.75f))
        assertEquals(1f, samples.max(), 0.001f)
    }

    @Test
    fun aCharacterAtTheTopOfItsPulseCarriesItsFullEmphasis() {
        val strength = lyricEmphasizeStrength(wordDurationMillis = 1000L, isLastWord = false)
        val emphasis = lyricCharEmphasis(
            strength = strength,
            glowProgress = 0.5f,
            charIndex = 0,
            characterCount = 4,
        )
        assertEquals(1f + 0.1f * strength.amount, emphasis.scale, 0.0001f)
        assertEquals(strength.blur, emphasis.glowAlpha, 0.0001f)
        assertEquals(0.3f * strength.blur, emphasis.glowRadiusEm, 0.0001f)
    }

    @Test
    fun charactersLeanAwayFromTheMiddleOfTheirWord() {
        val strength = lyricEmphasizeStrength(wordDurationMillis = 1000L, isLastWord = false)
        fun offsetFor(index: Int) = lyricCharEmphasis(
            strength = strength,
            glowProgress = 0.5f,
            charIndex = index,
            characterCount = 4,
        ).offsetXEm
        // AMLL offsets each character by (n/2 - i), so the word spreads outward from its middle and
        // characters further from that middle travel proportionally further.
        val offsets = (0 until 4).map { offsetFor(it) }
        assertTrue(offsets.zipWithNext().all { (left, right) -> left < right })
        assertEquals(0f, offsets[2], 0.0001f)
        assertEquals(2f * offsets[1], offsets[0], 0.0001f)
    }

    @Test
    fun onlyHeldWordsGetThePerCharacterShow() {
        // AMLL's shouldEmphasize: a second of holding is the floor, and a Latin word has to be short
        // enough to read as one unit. CJK words are exempt from the length rule, because a single
        // hanzi can carry a whole syllable for longer than that.
        assertTrue(lyricWordIsEmphasizable(TimedLyricWord(0L, 1_000L, "长")))
        assertTrue(lyricWordIsEmphasizable(TimedLyricWord(0L, 1_000L, "hello")))
        assertTrue(!lyricWordIsEmphasizable(TimedLyricWord(0L, 999L, "长")))
        assertTrue(!lyricWordIsEmphasizable(TimedLyricWord(0L, 1_200L, "a")))
        assertTrue(!lyricWordIsEmphasizable(TimedLyricWord(0L, 1_200L, "absolutely")))
    }

    @Test
    fun elapsedProgressHoldsBothEndsLikeFillBoth() {
        assertEquals(0f, lyricEmphasizeElapsed(0L, startMillis = 1000f, durationMillis = 500f), 0f)
        assertEquals(0.5f, lyricEmphasizeElapsed(1250L, startMillis = 1000f, durationMillis = 500f), 0.0001f)
        assertEquals(1f, lyricEmphasizeElapsed(9_000L, startMillis = 1000f, durationMillis = 500f), 0f)
        // A zero-length word is simply already finished.
        assertEquals(1f, lyricEmphasizeElapsed(0L, startMillis = 0f, durationMillis = 0f), 0f)
    }

    @Test
    fun theShapingIsContinuousWhereTheCubicTurnsIntoTheRoot() {
        // 2000ms is where du/2000 crosses one, so the two halves of the shaping must meet there
        // rather than jump — a jump would show as a visible pop in words of exactly that length.
        val below = lyricEmphasizeStrength(wordDurationMillis = 1999L, isLastWord = false)
        val at = lyricEmphasizeStrength(wordDurationMillis = 2000L, isLastWord = false)
        val above = lyricEmphasizeStrength(wordDurationMillis = 2001L, isLastWord = false)
        assertEquals(0.6f, at.amount, 0.0001f)
        assertTrue(above.amount - below.amount < 0.002f)
    }
}
