package dev.naominet.lazer

import org.junit.Assert.assertEquals
import org.junit.Test

class WindowsAcrylicTest {
    @Test
    fun `native tint keeps compose channels in windows order`() {
        val argb = 0x12345678

        assertEquals(0xB8785634.toInt(), windowsAcrylicTint(argb, isDark = false))
        assertEquals(0x98785634.toInt(), windowsAcrylicTint(argb, isDark = true))
    }

    @Test
    fun `visual wallpaper disappears when the app surface is opaque`() {
        assertEquals(0f, windowsVisualBackgroundAlpha(osGlassActive = true, uiAlpha = 1f))
        assertEquals(0.88f, windowsVisualBackgroundAlpha(osGlassActive = true, uiAlpha = 0f))
        assertEquals(1f, windowsVisualBackgroundAlpha(osGlassActive = false, uiAlpha = 0f))
    }
}
