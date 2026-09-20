package dev.naominet.lazer

import kotlin.test.Test
import kotlin.test.assertEquals

class LiquidGlassTest {
    @Test
    fun backgroundVeilUsesOneLinearOpacityAndOpaqueFallback() {
        assertEquals(0f, resolveLazerUiAlpha(true, 0f))
        assertEquals(0.5f, resolveLazerUiAlpha(true, 0.5f))
        assertEquals(1f, resolveLazerUiAlpha(true, 1f))
        assertEquals(1f, resolveLazerUiAlpha(false, 0.2f))
        assertEquals(1f, resolveLazerUiAlpha(true, Float.NaN))
        assertEquals(0f, resolveLazerUiAlpha(true, -1f))
        assertEquals(1f, resolveLazerUiAlpha(true, 2f))
    }

    @Test
    fun glassTintRetainsItsMaterialOpacityAndRespectsTheSlider() {
        assertEquals(0f, lazerSurfaceAlpha(0.18f, 0f))
        assertEquals(0.09f, lazerSurfaceAlpha(0.18f, 0.5f))
        assertEquals(0.18f, lazerSurfaceAlpha(0.18f, 1f))
    }

    @Test
    fun blurIntensityIsClampedToItsSupportedRange() {
        assertEquals(0f, normalizeLiquidGlassBlurIntensity(-0.4f))
        assertEquals(0.6f, normalizeLiquidGlassBlurIntensity(0.6f))
        assertEquals(1f, normalizeLiquidGlassBlurIntensity(1.4f))
    }

    @Test
    fun defaultIntensityPreservesTheTunedSurfaceBlur() {
        assertEquals(1f, liquidGlassBlurScale(DEFAULT_LIQUID_GLASS_BLUR_INTENSITY))
        assertEquals(0f, liquidGlassBlurScale(0f))
        assertEquals(2f, liquidGlassBlurScale(1f))
    }
}
