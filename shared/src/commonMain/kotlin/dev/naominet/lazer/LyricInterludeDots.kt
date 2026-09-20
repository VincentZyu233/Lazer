package dev.naominet.lazer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

private const val InterludeDotTrailingMillis = 750L
private const val InterludeExitGrowMillis = 750L
private const val InterludeExitShrinkMillis = 250L
private const val InterludeExitMillis = InterludeExitGrowMillis + InterludeExitShrinkMillis
private const val InterludeExitFadeMillis = 250L
private const val InterludeEnterHoldMillis = 500L
private const val InterludeEnterFadeMillis = 180L
private const val InterludeDotEnterFadeMillis = 750L
private const val InterludeDotEnterStaggerMillis = 80L
private const val InterludeDotEnterTotalMillis =
    InterludeDotEnterStaggerMillis * 2L + InterludeDotEnterFadeMillis
private const val InterludeBreathPeriodMillis = 4_000L
private const val InterludeFallbackThresholdMillis = 3_000L
private const val InterludeMaximumScale = 1.25f
private const val InterludeMinimumScale = 0.40f
private const val InterludeInactiveOpacity = 0.20f
private const val InterludeActiveOpacity = 0.90f

internal data class LyricInterludeVisualSnapshot(
    val dotOpacities: List<Float>,
    val scale: Float,
    val opacity: Float,
)

/**
 * Playback-time implementation of AMLL's interlude choreography. Keeping this calculation pure
 * makes seeking deterministic on both Android and desktop while retaining AMLL's phase lengths,
 * cubic-bezier curves, stagger, breathing curve, and short-gap fallback.
 */
internal fun lyricInterludeVisualSnapshot(
    startMillis: Long,
    endMillis: Long,
    positionMillis: Long,
): LyricInterludeVisualSnapshot {
    val durationMillis = (endMillis - startMillis).coerceAtLeast(0L)
    val enterHoldMillis = if (startMillis <= 0L) 0L else InterludeEnterHoldMillis
    val bodyMillis = durationMillis - enterHoldMillis - InterludeExitMillis
    if (bodyMillis < InterludeDotEnterTotalMillis) {
        return LyricInterludeVisualSnapshot(listOf(0f, 0f, 0f), 1f, 0f)
    }

    val elapsedMillis = (positionMillis - startMillis).coerceIn(0L, durationMillis)
    if (elapsedMillis < enterHoldMillis) {
        return LyricInterludeVisualSnapshot(listOf(0f, 0f, 0f), 1f, 0f)
    }

    val internalMillis = elapsedMillis - enterHoldMillis
    val bodyEndMillis = enterHoldMillis + bodyMillis
    val fallbackHold = bodyMillis < InterludeFallbackThresholdMillis
    val segmentMillis = if (fallbackHold) {
        1L
    } else {
        ((bodyMillis + InterludeDotTrailingMillis).toDouble() / 3.0).roundToInt().toLong()
            .coerceAtLeast(1L)
    }
    val thirdDotDurationMillis = (bodyMillis - segmentMillis * 2L).coerceAtLeast(1L)
    val thirdDotTarget = if (fallbackHold) 1f else {
        (thirdDotDurationMillis.toFloat() / segmentMillis).coerceIn(0f, 1f)
    }
    val enterOpacity = interludeEnterEasing(
        (internalMillis.toFloat() / InterludeEnterFadeMillis).coerceIn(0f, 1f),
    )

    if (elapsedMillis >= bodyEndMillis) {
        val exitMillis = elapsedMillis - bodyEndMillis
        val fadeProgress = (
            (exitMillis - (InterludeExitMillis - InterludeExitFadeMillis)).toFloat() /
                InterludeExitFadeMillis
            ).coerceIn(0f, 1f)
        val opacity = enterOpacity * (1f - interludeExitFadeEasing(fadeProgress))
        val scale = if (exitMillis < InterludeExitGrowMillis) {
            1f + interludeExitGrowEasing(exitMillis.toFloat() / InterludeExitGrowMillis) *
                (InterludeMaximumScale - 1f)
        } else {
            val shrinkProgress = (
                (exitMillis - InterludeExitGrowMillis).toFloat() / InterludeExitShrinkMillis
                ).coerceIn(0f, 1f)
            InterludeMaximumScale - interludeExitShrinkEasing(shrinkProgress) *
                (InterludeMaximumScale - InterludeMinimumScale)
        }
        val trailing = (exitMillis.toFloat() / InterludeDotTrailingMillis).coerceIn(0f, 1f)
        return LyricInterludeVisualSnapshot(
            dotOpacities = interludeDotOpacities(
                internalMillis,
                listOf(1f, 1f, thirdDotTarget + (1f - thirdDotTarget) * trailing),
            ),
            scale = scale,
            opacity = opacity,
        )
    }

    if (fallbackHold) {
        return LyricInterludeVisualSnapshot(
            dotOpacities = interludeDotOpacities(internalMillis, listOf(1f, 1f, 1f)),
            scale = 1f,
            opacity = enterOpacity,
        )
    }

    val cycles = floor(bodyMillis.toDouble() / InterludeBreathPeriodMillis).toInt().coerceAtLeast(1)
    val breathPeriodMillis = bodyMillis.toFloat() / cycles
    val cycle = (internalMillis % breathPeriodMillis.toLong().coerceAtLeast(1L)) / breathPeriodMillis
    val breathProgress = interludeBreathingProgress(cycle)
    val scale = if (breathProgress <= 0.5f) {
        1f + breathProgress / 0.5f * (InterludeMaximumScale - 1f)
    } else {
        InterludeMaximumScale - (breathProgress - 0.5f) / 0.5f *
            (InterludeMaximumScale - 1f)
    }
    return LyricInterludeVisualSnapshot(
        dotOpacities = interludeDotOpacities(
            internalMillis,
            listOf(
                interludeDotFraction(0L, segmentMillis, internalMillis, 1f),
                interludeDotFraction(segmentMillis, segmentMillis, internalMillis, 1f),
                interludeDotFraction(segmentMillis * 2L, thirdDotDurationMillis, internalMillis, thirdDotTarget),
            ),
        ),
        scale = scale,
        opacity = enterOpacity,
    )
}

/** Driven by playback time, so seeking is deterministic and pausing stops the dots. */
@Composable
fun LyricInterludeDots(
    startMillis: Long,
    endMillis: Long,
    positionMillis: Long,
    visibility: Float = 1f,
    glowEnabled: Boolean = true,
    dotDiameter: Dp = 9.dp,
    modifier: Modifier = Modifier,
) {
    // Playback is normally reported every 100 ms. Interpolate only between those reports, while
    // retaining the final in-range clock during the transient row's layout fade-out.
    var retainedPosition by remember(startMillis, endMillis) { mutableLongStateOf(positionMillis) }
    SideEffect {
        if (positionMillis < endMillis) retainedPosition = positionMillis
    }
    val targetPosition = if (positionMillis < endMillis) positionMillis else retainedPosition
    val animatedPosition by animateFloatAsState(
        targetValue = targetPosition.toFloat(),
        animationSpec = tween(100, easing = LinearEasing),
        label = "lyric interlude clock",
    )
    val snapshot = lyricInterludeVisualSnapshot(
        startMillis = startMillis,
        endMillis = endMillis,
        positionMillis = animatedPosition.toLong(),
    )
    val reveal = visibility.coerceIn(0f, 1f)
    val color = MaterialTheme.colorScheme.onBackground
    val description = tr("lyrics.interlude")
    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .semantics { contentDescription = description }
            .graphicsLayer {
                val visualScale = 1f + (snapshot.scale - 1f) * reveal
                scaleX = visualScale
                scaleY = visualScale
                alpha = snapshot.opacity * reveal
            },
    ) {
        if (glowEnabled) {
            Canvas(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        val radius = 11.dp.toPx()
                        compositingStrategy = CompositingStrategy.Offscreen
                        alpha = 0.58f
                        renderEffect = BlurEffect(radius, radius, TileMode.Decal)
                    },
            ) {
                drawInterludeDots(color, snapshot.dotOpacities, dotDiameter)
            }
        }
        Canvas(Modifier.matchParentSize()) {
            drawInterludeDots(color, snapshot.dotOpacities, dotDiameter)
        }
    }
}

private fun DrawScope.drawInterludeDots(
    color: Color,
    opacities: List<Float>,
    diameter: Dp,
) {
    val radius = diameter.toPx() / 2f
    val centerSpacing = diameter.toPx() * 1.6f
    repeat(3) { index ->
        drawCircle(
            color = color.copy(alpha = opacities.getOrElse(index) { 0f }.coerceIn(0f, 1f)),
            radius = radius,
            center = Offset(center.x + (index - 1) * centerSpacing, center.y),
        )
    }
}

private fun interludeDotOpacities(internalMillis: Long, fractions: List<Float>): List<Float> =
    List(3) { index ->
        val targetOpacity = InterludeInactiveOpacity +
            (InterludeActiveOpacity - InterludeInactiveOpacity) * fractions.getOrElse(index) { 0f }.coerceIn(0f, 1f)
        val enter = (
            (internalMillis - index * InterludeDotEnterStaggerMillis).toFloat() /
                InterludeDotEnterFadeMillis
            ).coerceIn(0f, 1f)
        targetOpacity * enter * enter
    }

private fun interludeDotFraction(
    startDelayMillis: Long,
    durationMillis: Long,
    internalMillis: Long,
    target: Float,
): Float {
    if (internalMillis <= startDelayMillis) return 0f
    val progress = ((internalMillis - startDelayMillis).toFloat() / durationMillis.coerceAtLeast(1L))
        .coerceIn(0f, 1f)
    return interludeLightingEasing(progress) * target
}

private fun interludeBreathingProgress(value: Float): Float {
    val progress = value.coerceIn(0f, 1f)
    val angle = 4f * PI.toFloat() * progress
    val sine = sin(angle)
    val cosine = cos(angle)
    return progress - 0.084f * sine + 0.008f * (1f - cosine) + 0.0046f * sine * (cosine - sine)
}

private fun interludeLightingEasing(value: Float): Float =
    interludeCubicBezier(value, 0.56f, 0.01f, 0.45f, 1f)

private fun interludeEnterEasing(value: Float): Float =
    interludeCubicBezier(value, 0.59f, 0.02f, 0.07f, 1f)

private fun interludeExitGrowEasing(value: Float): Float =
    interludeCubicBezier(value, 0.14f, 0.06f, 0.25f, 1f)

private fun interludeExitShrinkEasing(value: Float): Float =
    interludeCubicBezier(value, 0.29f, 0.03f, 1f, 0.38f)

private fun interludeExitFadeEasing(value: Float): Float =
    interludeCubicBezier(value, 0.43f, 0.08f, 0.83f, 0.31f)

private fun interludeCubicBezier(
    value: Float,
    x1: Float,
    y1: Float,
    x2: Float,
    y2: Float,
): Float {
    val x = value.coerceIn(0f, 1f)
    var low = 0f
    var high = 1f
    repeat(14) {
        val t = (low + high) / 2f
        if (interludeBezierCoordinate(t, x1, x2) < x) low = t else high = t
    }
    return interludeBezierCoordinate((low + high) / 2f, y1, y2)
}

private fun interludeBezierCoordinate(t: Float, first: Float, second: Float): Float {
    val inverse = 1f - t
    return 3f * inverse * inverse * t * first + 3f * inverse * t * t * second + t * t * t
}

/** Layout-presence easing retained for the transient row insertion/removal animation. */
internal fun lyricInterludeDotVisibility(visibility: Float, index: Int): Float {
    val delay = index.coerceIn(0, 2) * 0.07f
    val local = ((visibility.coerceIn(0f, 1f) - delay) / (1f - delay)).coerceIn(0f, 1f)
    return local * local * (3f - 2f * local)
}
