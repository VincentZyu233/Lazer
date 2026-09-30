package dev.naominet.lazer

import kotlin.test.Test
import kotlin.test.assertEquals

/** The scan frame both platforms measure here, so their corner guides cannot drift apart. */
class LazerScanFrameTest {
    @Test
    fun ordinaryPreviewFramesSixtyTwoPercentOfTheShorterEdge() {
        assertEquals(248f, lazerScanFrameSidePt(400f), 0.01f)
    }

    @Test
    fun theFrameStaysInsideTheReachableBand() {
        assertEquals(330f, lazerScanFrameSidePt(900f), 0.01f)
        assertEquals(190f, lazerScanFrameSidePt(200f), 0.01f)
    }

    @Test
    fun aPreviewNarrowerThanTheFloorUsesItWhole() {
        assertEquals(100f, lazerScanFrameSidePt(100f), 0.01f)
    }
}
