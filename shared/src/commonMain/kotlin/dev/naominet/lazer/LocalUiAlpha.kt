package dev.naominet.lazer

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Opacity applied to the app's own surfaces (background, cards, bars) when a custom background
 * wallpaper is active. The wallpaper itself is always drawn fully opaque; this value controls how
 * much of it shows through the UI. 1 = UI fully opaque (wallpaper hidden), 0 = UI fully
 * transparent (wallpaper fully visible). Text and icons are never affected.
 */
val LocalLazerUiAlpha = staticCompositionLocalOf { 1f }

/** One linear opacity for the root veil; never apply this to the wallpaper or its content. */
fun resolveLazerUiAlpha(hasVisualBackground: Boolean, value: Float): Float =
    if (hasVisualBackground && value.isFinite()) value.coerceIn(0f, 1f) else 1f

/** Retains a surface's tuned opacity while applying the user's surface-opacity setting. */
fun lazerSurfaceAlpha(baseAlpha: Float, uiAlpha: Float): Float =
    baseAlpha.coerceIn(0f, 1f) * resolveLazerUiAlpha(true, uiAlpha)
