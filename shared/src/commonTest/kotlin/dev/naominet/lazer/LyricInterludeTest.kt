package dev.naominet.lazer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LyricInterludeTest {
    @Test fun transientRowMergesIntoTheStableLyricLayout() {
        val heights = listOf(40f, 52f, 60f)
        val withoutDots = lyricLineCenters(listOf(40f, 60f), 10f, 20f)
        val collapsed = lyricLineCentersWithTransientRow(heights, 1, 0f, 10f, 20f)
        val expanded = lyricLineCentersWithTransientRow(heights, 1, 1f, 10f, 20f)
        val normal = lyricLineCenters(heights, 10f, 20f)
        assertEquals(withoutDots[0], collapsed[0])
        assertEquals(withoutDots[1], collapsed[2])
        assertEquals(normal.toList(), expanded.toList())
        val halfway = lyricLineCentersWithTransientRow(heights, 1, 0.5f, 10f, 20f)
        assertEquals((collapsed[2] + expanded[2]) / 2f, halfway[2])
    }

    @Test fun insertingAndRemovingRowsPreservesExistingSpringPositions() {
        val motion = LyricLineMotionField()
        motion.reset(2, 30f)
        motion.advance(100f, 1, 0.05f, 500, LyricAnimationSpeed.STANDARD)
        val first = motion.positionFor(0)
        val second = motion.positionFor(1)
        motion.remap(listOf(0, -1, 1), 40f)
        assertEquals(first, motion.positionFor(0))
        assertEquals(40f, motion.positionFor(1))
        assertEquals(second, motion.positionFor(2))
        motion.remap(listOf(0, 2), 40f)
        assertEquals(first, motion.positionFor(0))
        assertEquals(second, motion.positionFor(1))
    }

    @Test fun interludeDotsFadeAsAStaggeredGroup() {
        repeat(3) { index ->
            assertEquals(1f, lyricInterludeDotVisibility(1f, index))
            assertEquals(0f, lyricInterludeDotVisibility(0f, index))
        }
        val halfway = (0..2).map { lyricInterludeDotVisibility(0.5f, it) }
        assertTrue(halfway[0] > halfway[1])
        assertTrue(halfway[1] > halfway[2])
    }

    @Test fun amllInterludeChoreographyUsesHoldBreathAndExitPhases() {
        val hiddenDuringHold = lyricInterludeVisualSnapshot(5_000L, 11_000L, 5_200L)
        assertEquals(0f, hiddenDuringHold.opacity)

        val entered = lyricInterludeVisualSnapshot(5_000L, 11_000L, 6_500L)
        assertTrue(entered.opacity > 0.99f)
        assertTrue(entered.scale >= 1f)
        assertTrue(entered.dotOpacities[0] > entered.dotOpacities[2])

        val exiting = lyricInterludeVisualSnapshot(5_000L, 11_000L, 10_500L)
        assertTrue(exiting.scale > 1f)
        assertTrue(exiting.opacity > 0f)

        val ended = lyricInterludeVisualSnapshot(5_000L, 11_000L, 11_000L)
        assertEquals(0f, ended.opacity, 0.001f)
    }

    @Test fun exactVocalEndsTakePriority() {
        assertEquals(9_000L, lyricInterludeStart(0, 9_000, 15_000))
        assertNull(lyricInterludeStart(0, 13_000, 15_000))
        assertNull(lyricInterludeStart(0, 16_000, 15_000))
    }
    @Test fun lrcFallbackRequiresALongGap() {
        assertNull(lyricInterludeStart(0, null, 11_000))
        assertEquals(8_000L, lyricInterludeStart(0, null, 16_000))
        assertNull(lyricInterludeStart(5_000, null, 0))
    }
}
