package dev.naominet.lazer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kashif_e.backdrop.backdrops.LayerBackdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import com.kashif_e.backdrop.drawBackdrop
import com.kashif_e.backdrop.effects.blur
import com.kashif_e.backdrop.effects.colorControls
import com.kashif_e.backdrop.effects.lens
import com.kashif_e.backdrop.effects.vibrancy
import com.kashif_e.backdrop.highlight.Highlight
import com.kashif_e.backdrop.shadow.InnerShadow

const val DEFAULT_LIQUID_GLASS_BLUR_INTENSITY = 0.5f

fun normalizeLiquidGlassBlurIntensity(value: Float): Float = value.coerceIn(0f, 1f)

internal fun liquidGlassBlurScale(intensity: Float): Float =
    normalizeLiquidGlassBlurIntensity(intensity) * 2f

/**
 * Holder for the optional liquid-glass backdrop. When disabled the surface modifiers below are
 * no-ops, so pages keep their existing flat construction without any graphics-layer overhead.
 */
@Immutable
class LazerLiquidGlass internal constructor(
    val backdrop: LayerBackdrop?,
    val blurIntensity: Float,
) {
    val isEnabled: Boolean get() = backdrop != null

    companion object {
        val Disabled = LazerLiquidGlass(null, DEFAULT_LIQUID_GLASS_BLUR_INTENSITY)
    }
}

/**
 * Creates a backdrop that captures the real content it is applied to, filling the app background
 * color everywhere else. This is the single source of truth for glass sampling: no fabricated
 * decorative content is ever injected as a backdrop.
 */
@Composable
fun rememberLazerLiquidGlass(
    enabled: Boolean,
    backgroundColor: Color,
    blurIntensity: Float = DEFAULT_LIQUID_GLASS_BLUR_INTENSITY,
): LazerLiquidGlass {
    val currentBackgroundColor = rememberUpdatedState(backgroundColor)
    // Keep this callback—and therefore the LayerBackdrop instance—stable across theme changes.
    // Replacing a positioned backdrop would briefly leave the new instance without coordinates,
    // so floating glass surfaces would have nothing to sample until another layout pass.
    val drawCapturedContent: ContentDrawScope.() -> Unit = remember {
        {
            drawRect(currentBackgroundColor.value)
            drawContent()
        }
    }
    val backdrop = rememberLayerBackdrop(drawCapturedContent)
    val normalizedBlurIntensity = normalizeLiquidGlassBlurIntensity(blurIntensity)
    return remember(enabled, normalizedBlurIntensity, backdrop) {
        LazerLiquidGlass(
            backdrop = if (enabled) backdrop else null,
            blurIntensity = normalizedBlurIntensity,
        )
    }
}

/** Scales an individual surface's tuned blur radius without flattening its visual hierarchy. */
fun LazerLiquidGlass.scaledBlurRadius(baseRadius: Dp): Dp =
    baseRadius * liquidGlassBlurScale(blurIntensity)

/** Captures this composable's rendered content into the glass backdrop for downstream sampling. */
fun Modifier.captureLiquidGlass(glass: LazerLiquidGlass): Modifier =
    glass.backdrop?.let { layerBackdrop(it) } ?: this

/**
 * Applies a restrained glass surface that samples the captured content behind it. The lens is
 * intentionally shallow and colour-neutral so text stays readable and stacked glass does not
 * become a collection of bright, expensive distortion layers.
 */
@Composable
fun Modifier.liquidGlassSurface(
    glass: LazerLiquidGlass,
    shape: Shape,
    surfaceColor: Color,
    blurRadius: Dp = 8.dp,
): Modifier {
    if (!glass.isEnabled) return this
    val backdrop = glass.backdrop ?: return this
    val uiAlpha = LocalLazerUiAlpha.current
    val effectiveBlurRadius = glass.scaledBlurRadius(blurRadius)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            colorControls(brightness = 0.02f, saturation = 1.08f)
            blur(effectiveBlurRadius.toPx())
            lens(
                refractionHeight = 14.dp.toPx(),
                refractionAmount = 24.dp.toPx(),
                depthEffect = true,
                chromaticAberration = false,
            )
        },
        highlight = { Highlight.Plain.copy(alpha = 0.62f) },
        innerShadow = { InnerShadow(radius = 8.dp, color = Color.Black.copy(alpha = 0.06f)) },
        onDrawSurface = { drawRect(surfaceColor.copy(alpha = lazerSurfaceAlpha(0.18f * surfaceColor.alpha, uiAlpha))) },
    )
}

/**
 * Crisp, responsive glass for buttons and other top-level controls. At rest it stays quiet; while
 * pressed it gains a small amount of depth without adding chromatic shimmer.
 */
@Composable
fun Modifier.liquidGlassControlSurface(
    glass: LazerLiquidGlass,
    shape: Shape,
    surfaceColor: Color,
    tint: Color = Color.Unspecified,
    blurRadius: Dp = 3.dp,
    pressProgress: Float = 0f,
): Modifier {
    if (!glass.isEnabled) return this
    val backdrop = glass.backdrop ?: return this
    val pressed = pressProgress.coerceIn(0f, 1f)
    val uiAlpha = LocalLazerUiAlpha.current
    val effectiveBlurRadius = glass.scaledBlurRadius(blurRadius)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            vibrancy()
            colorControls(brightness = 0.02f, saturation = 1.06f)
            blur(effectiveBlurRadius.toPx())
            lens(
                refractionHeight = (10.dp + 3.dp * pressed).toPx(),
                refractionAmount = (18.dp + 6.dp * pressed).toPx(),
                depthEffect = pressed > 0.2f,
                chromaticAberration = false,
            )
        },
        highlight = { Highlight.Plain.copy(alpha = 0.66f + 0.22f * pressed) },
        innerShadow = {
            InnerShadow(
                radius = 6.dp + 2.dp * pressed,
                color = Color.Black.copy(alpha = 0.06f + 0.03f * pressed),
            )
        },
        layerBlock = {
            val scale = 1f + 0.02f * pressed
            scaleX = scale
            scaleY = scale
        },
        onDrawSurface = {
            if (tint.isSpecified) {
                drawRect(tint.copy(alpha = lazerSurfaceAlpha((0.38f + 0.08f * pressed) * tint.alpha, uiAlpha)))
            } else {
                drawRect(surfaceColor.copy(alpha = lazerSurfaceAlpha((0.16f + 0.05f * pressed) * surfaceColor.alpha, uiAlpha)))
            }
        },
    )
}

/**
 * Lean frosted material for a control that moves with scrolling content. It deliberately avoids
 * lens and vibrancy passes so the control remains smooth while its backdrop is invalidated.
 */
@Composable
fun Modifier.liquidGlassFrostedSurface(
    glass: LazerLiquidGlass,
    shape: Shape,
    surfaceColor: Color,
    blurRadius: Dp = 6.dp,
): Modifier {
    if (!glass.isEnabled) return this
    val backdrop = glass.backdrop ?: return this
    val uiAlpha = LocalLazerUiAlpha.current
    val effectiveBlurRadius = glass.scaledBlurRadius(blurRadius)
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            colorControls(brightness = 0.04f, saturation = 1.16f)
            blur(effectiveBlurRadius.toPx())
        },
        highlight = { Highlight.Plain.copy(alpha = 0.58f) },
        onDrawSurface = { drawRect(surfaceColor.copy(alpha = lazerSurfaceAlpha(0.18f * surfaceColor.alpha, uiAlpha))) },
    )
}
