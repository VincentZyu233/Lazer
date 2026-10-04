package dev.naominet.lazer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LazerEqualizerTest {

    @Test
    fun `the curve survives a settings round trip`() {
        val state = LazerEqualizerState(
            enabled = true,
            preampDb = -2.5,
            limiterEnabled = false,
            bands = defaultLazerEqBands().mapIndexed { index, band ->
                band.copy(gainDb = if (index % 2 == 0) 3.0 else -4.0)
            },
        )

        assertEquals(state, parseLazerEqualizer(state.serialize()))
    }

    @Test
    fun `a malformed preference falls back to a flat curve`() {
        val flat = LazerEqualizerState()
        assertEquals(flat, parseLazerEqualizer(null))
        assertEquals(flat, parseLazerEqualizer("garbage"))
        assertEquals(flat, parseLazerEqualizer("1|0|1"))
    }

    @Test
    fun `applying a preset matches that preset and replaces the gains`() {
        val pop = LazerEqualizerPresets.first { it.id == "pop" }

        val state = LazerEqualizerState(enabled = true).withPreset(pop)

        assertEquals("pop", state.matchingPresetId())
        assertEquals(pop.gainsDb, state.bands.map { it.gainDb })
        assertEquals(pop.preampDb, state.preampDb)
    }

    @Test
    fun `a hand made curve is not a preset`() {
        val state = LazerEqualizerState().copy(
            bands = defaultLazerEqBands().mapIndexed { index, band -> band.copy(gainDb = index.toDouble()) },
        )

        assertNull(state.matchingPresetId())
    }

    @Test
    fun `the gain sliders stay inside their range`() {
        assertEquals(MAX_LAZER_EQ_GAIN_DB, clampLazerEqGainDb(99.0))
        assertEquals(MIN_LAZER_EQ_GAIN_DB, clampLazerEqGainDb(-99.0))
        assertEquals(0.0, clampLazerEqGainDb(0.0))
    }
}
