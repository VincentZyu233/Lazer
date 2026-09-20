package dev.naominet.lazer

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp

/** Driven by playback time, so seeking is deterministic and pausing stops the dots. */
@Composable
fun LyricInterludeDots(
    startMillis: Long,
    endMillis: Long,
    positionMillis: Long,
    visibility: Float = 1f,
    glowEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val progress by animateFloatAsState(
        ((positionMillis - startMillis).toFloat() / (endMillis - startMillis).coerceAtLeast(1L)).coerceIn(0f, 1f),
        tween(100, easing = LinearEasing), label = "lyric interlude",
    )
    val color = MaterialTheme.colorScheme.onBackground
    val description = tr("lyrics.interlude")
    val durationMillis = (endMillis - startMillis).coerceAtLeast(1L)
    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .semantics { contentDescription = description },
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
                drawInterludeDots(color, progress, durationMillis, visibility)
            }
        }
        Canvas(Modifier.matchParentSize()) {
            drawInterludeDots(color, progress, durationMillis, visibility)
        }
    }
}

private fun DrawScope.drawInterludeDots(
    color: Color,
    progress: Float,
    durationMillis: Long,
    visibility: Float,
) {
    val radius = 5.dp.toPx()
    val elapsed = progress * durationMillis
    val reveal = (elapsed / 400f).coerceIn(0f, 1f)
    val breath = 1f + 0.05f * kotlin.math.sin(elapsed / 4500f * 2f * kotlin.math.PI).toFloat()
    repeat(3) { index ->
        val fill = (progress * 3f - index).coerceIn(0f, 1f)
        val dotVisibility = lyricInterludeDotVisibility(visibility, index)
        drawCircle(
            color.copy(alpha = (0.28f + fill * 0.72f) * reveal * dotVisibility),
            radius * breath * (0.85f + fill * 0.15f) * (0.82f + dotVisibility * 0.18f),
            Offset(center.x + (index - 1) * 22.dp.toPx(), center.y),
        )
    }
}

/** Slight left-to-right stagger shared by insertion and removal; every dot still ends together. */
internal fun lyricInterludeDotVisibility(visibility: Float, index: Int): Float {
    val delay = index.coerceIn(0, 2) * 0.07f
    val local = ((visibility.coerceIn(0f, 1f) - delay) / (1f - delay)).coerceIn(0f, 1f)
    return local * local * (3f - 2f * local)
}
