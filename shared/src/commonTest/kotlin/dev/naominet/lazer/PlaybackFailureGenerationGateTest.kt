package dev.naominet.lazer

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaybackFailureGenerationGateTest {
    @Test
    fun `accepts one failure for the active playback generation`() {
        val gate = PlaybackFailureGenerationGate()
        gate.begin(7L)

        assertTrue(gate.accept(7L))
        assertFalse(gate.accept(7L))
    }

    @Test
    fun `drops failure from a previous track after a new generation begins`() {
        val gate = PlaybackFailureGenerationGate()
        gate.begin(7L)
        gate.begin(8L)

        assertFalse(gate.accept(7L))
        assertTrue(gate.accept(8L))
    }

    @Test
    fun `ignores failures before any playback generation is active`() {
        val gate = PlaybackFailureGenerationGate()

        assertFalse(gate.accept(1L))
    }
}
