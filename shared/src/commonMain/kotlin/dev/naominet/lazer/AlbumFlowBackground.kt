package dev.naominet.lazer

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.isActive
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

private const val FlowFrameIntervalNanos = 33_333_333L

private val FluidPaletteEasing = Easing { fraction ->
    ((1.0 - cos(PI * fraction.coerceIn(0f, 1f))) * 0.5).toFloat()
}

/** Shared animated album-color renderer. Platform code only supplies the extracted colors. */
@Composable
fun LazerAlbumFlowBackground(
    colors: List<Color>,
    modifier: Modifier = Modifier,
    cornerRadius: Dp,
    veil: Color,
    animated: Boolean = true,
    paletteAnimated: Boolean = true,
    solid: Boolean = false,
) {
    val palette = remember(colors) { normalizeFlowPalette(colors) }
    var phaseSeconds by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(animated) {
        if (!animated) return@LaunchedEffect
        var lastPublishedNs = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (lastPublishedNs == 0L) {
                    lastPublishedNs = now
                } else if (now - lastPublishedNs >= FlowFrameIntervalNanos) {
                    val elapsedSeconds = ((now - lastPublishedNs) / 1_000_000_000.0)
                        .toFloat()
                        .coerceAtMost(0.1f)
                    lastPublishedNs = now
                    phaseSeconds = (phaseSeconds + elapsedSeconds) % 10_000f
                }
            }
        }
    }

    Box(
        modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(palette[4]),
    ) {
        Crossfade(
            targetState = palette,
            animationSpec = if (paletteAnimated) {
                tween(durationMillis = 650, easing = FluidPaletteEasing)
            } else {
                snap()
            },
            label = if (solid) "artwork-solid-palette" else "lyric-flow-palette",
        ) { activePalette ->
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (solid) Modifier.background(activePalette[4]) else Modifier),
            ) {
                if (!solid) FlowPaletteLayers(activePalette) { phaseSeconds }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            veil.copy(alpha = veil.alpha * 0.28f),
                            veil.copy(alpha = veil.alpha * 0.72f),
                        ),
                    ),
                ),
        )
        Box(Modifier.fillMaxSize().background(veil.copy(alpha = veil.alpha * 0.72f)))
    }
}

@Composable
private fun BoxScope.FlowPaletteLayers(
    palette: List<Color>,
    phaseSeconds: () -> Float,
) {
    FlowLayer(palette[0], phaseSeconds, 0.2f, 0.19f, 0.30f, 0.24f, 1.68f, 1.18f, 8f)
    FlowLayer(palette[1], phaseSeconds, 2.1f, 0.145f, 0.25f, 0.34f, 1.34f, 1.58f, -10f)
    FlowLayer(palette[2], phaseSeconds, 4.0f, 0.17f, 0.37f, 0.20f, 1.52f, 1.26f, 7f)
    FlowLayer(palette[3], phaseSeconds, 5.35f, 0.12f, 0.20f, 0.38f, 1.28f, 1.62f, -6f)
    FlowLayer(palette[4], phaseSeconds, 1.25f, 0.105f, 0.16f, 0.18f, 1.85f, 1.12f, 5f, 0.52f)
}

@Composable
private fun BoxScope.FlowLayer(
    color: Color,
    phaseSeconds: () -> Float,
    phaseOffset: Float,
    speed: Float,
    orbitX: Float,
    orbitY: Float,
    scaleX: Float,
    scaleY: Float,
    rotationRange: Float,
    colorAlpha: Float = 0.68f,
) {
    val brush = remember(color, colorAlpha) {
        Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = colorAlpha),
                color.copy(alpha = colorAlpha * 0.56f),
                color.copy(alpha = colorAlpha * 0.16f),
                Color.Transparent,
            ),
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val time = phaseSeconds() * speed + phaseOffset
                val secondaryTime = phaseSeconds() * speed * 0.73f + phaseOffset * 1.37f
                translationX = sin(time.toDouble()).toFloat() * size.width * orbitX
                translationY = cos(secondaryTime.toDouble()).toFloat() * size.height * orbitY
                val pulse = sin((time * 0.61f).toDouble()).toFloat() * 0.075f
                this.scaleX = scaleX + pulse
                this.scaleY = scaleY - pulse * 0.72f
                rotationZ = sin((time * 0.43f).toDouble()).toFloat() * rotationRange
            }
            .background(brush),
    )
}

fun flowColorsFromSeed(seed: Color): List<Color> {
    val hsl = rgbToHsl(seed.red, seed.green, seed.blue)
    val primaryChroma = (hsl[1] * 0.72f + 0.12f).coerceIn(0.20f, 0.58f)
    return listOf(
        hslToColor(hsl[0], primaryChroma * 0.55f, 0.90f),
        hslToColor(hsl[0], primaryChroma * 0.72f, 0.76f),
        hslToColor(hsl[0], primaryChroma, 0.58f),
        hslToColor(hsl[0], primaryChroma * 0.86f, 0.43f),
        hslToColor(hsl[0], primaryChroma * 0.74f, 0.31f),
    )
}

private fun normalizeFlowPalette(colors: List<Color>): List<Color> {
    val source = colors.ifEmpty {
        listOf(LazerTokens.MistBlue, LazerTokens.LakeBlue, LazerTokens.DeepBlue)
    }
    return List(5) { index -> source[index % source.size] }
}

private fun rgbToHsl(red: Float, green: Float, blue: Float): FloatArray {
    val maximum = max(red, max(green, blue))
    val minimum = min(red, min(green, blue))
    val lightness = (maximum + minimum) / 2f
    val delta = maximum - minimum
    if (delta <= 1e-4f) return floatArrayOf(0f, 0f, lightness)
    val saturation = if (lightness > 0.5f) {
        delta / (2f - maximum - minimum)
    } else {
        delta / (maximum + minimum)
    }
    val hue = when (maximum) {
        red -> (green - blue) / delta + if (green < blue) 6f else 0f
        green -> (blue - red) / delta + 2f
        else -> (red - green) / delta + 4f
    } * 60f
    return floatArrayOf(hue, saturation, lightness)
}
