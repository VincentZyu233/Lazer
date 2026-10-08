package dev.naominet.lazer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun `selecting a preset activates the same curve on every platform`() {
        val state = LazerEqualizerState(enabled = false, limiterEnabled = false)
        val pop = LazerEqualizerPresets.first { it.id == "pop" }

        val updated = state.activatePreset(pop)

        assertTrue(updated.enabled)
        assertFalse(updated.limiterEnabled)
        assertEquals(pop.gainsDb, updated.bands.map { it.gainDb })
        assertEquals(pop.preampDb, updated.preampDb)
    }

    @Test
    fun `band edit clamps only the selected gain and preserves filter settings`() {
        val state = LazerEqualizerState(
            enabled = true,
            preampDb = -3.0,
            limiterEnabled = false,
            bands = defaultLazerEqBands().mapIndexed { index, band ->
                band.copy(q = index + 0.5, enabled = index % 2 == 0)
            },
        )

        val updated = state.withBandGain(index = 4, gainDb = 100.0)

        assertEquals(12.0, updated.bands[4].gainDb, 0.0)
        assertEquals(state.bands[4].kind, updated.bands[4].kind)
        assertEquals(state.bands[4].q, updated.bands[4].q, 0.0)
        assertEquals(state.bands[4].enabled, updated.bands[4].enabled)
        assertEquals(
            state.bands.mapIndexed { index, band -> if (index == 4) 12.0 else band.gainDb },
            updated.bands.map { it.gainDb },
        )
        assertEquals(state.preampDb, updated.preampDb, 0.0)
        assertFalse(updated.limiterEnabled)
        assertEquals(updated, updated.withBandGain(index = -1, gainDb = 3.0))
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
