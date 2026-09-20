package dev.naominet.lazer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import kotlin.math.roundToInt

@Composable
internal fun AndroidLyricsViewport(
    track: AndroidTrack?,
    lines: List<AndroidTimedLyricLine>,
    isLoading: Boolean,
    message: String?,
    positionMillis: Long,
    followDelayMillis: Long,
    animationSpeed: LyricAnimationSpeed,
    wordLyricsEnabled: Boolean,
    lyricGlowEnabled: Boolean,
    lyricFontSizeSp: Int,
    showFullLyrics: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val timeline = remember(lines, track?.durationMillis) {
        androidLyricsWithInterludes(lines, track?.durationMillis ?: 0L)
    }
    val targetInterlude = activeAndroidInterlude(timeline, positionMillis)
    var renderedInterlude by remember(track?.id, timeline) { mutableStateOf<AndroidTimedLyricLine?>(null) }
    val interludePresence = remember(track?.id, timeline) { Animatable(0f) }
    LaunchedEffect(targetInterlude) {
        if (targetInterlude == renderedInterlude) return@LaunchedEffect
        if (renderedInterlude != null) {
            interludePresence.animateTo(0f, tween(560, easing = FastOutSlowInEasing))
        }
        renderedInterlude = targetInterlude
        if (targetInterlude != null) {
            interludePresence.snapTo(0f)
            interludePresence.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
        }
    }
    val displayLines = remember(timeline, renderedInterlude) {
        androidLyricDisplayLines(timeline, renderedInterlude)
    }
    when {
        track == null -> LyricEmpty(tr("lyrics.empty"), modifier)
        isLoading -> LyricEmpty(tr("lyrics.loading"), modifier)
        displayLines.isEmpty() -> LyricEmpty(message ?: tr("lyrics.none"), modifier)
        else -> AnimatedLyricsViewport(
            trackId = track.id,
            lines = displayLines,
            interludePresence = interludePresence.value,
            positionMillis = positionMillis,
            followDelayMillis = followDelayMillis,
            animationSpeed = animationSpeed,
            wordLyricsEnabled = wordLyricsEnabled,
            lyricGlowEnabled = lyricGlowEnabled,
            lyricFontSizeSp = lyricFontSizeSp,
            showFullLyrics = showFullLyrics,
            onSeek = onSeek,
            modifier = modifier,
        )
    }
}

@Composable
private fun AnimatedLyricsViewport(
    trackId: Long,
    lines: List<AndroidTimedLyricLine>,
    interludePresence: Float,
    positionMillis: Long,
    followDelayMillis: Long,
    animationSpeed: LyricAnimationSpeed,
    wordLyricsEnabled: Boolean,
    lyricGlowEnabled: Boolean,
    lyricFontSizeSp: Int,
    showFullLyrics: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val isDark = colors.background.luminance() < 0.5f
    // This is a value parameter, not snapshot state. Recompute from each playback update rather
    // than remembering a derived-state lambda that captures the first position for these lines.
    val activeIndexValue = activeAndroidLyricIndex(lines, positionMillis)
    val currentActiveIndex by rememberUpdatedState(activeIndexValue)
    val currentAnimationSpeed by rememberUpdatedState(animationSpeed)
    val currentLines by rememberUpdatedState(lines)
    val currentFollowDelayMillis by rememberUpdatedState(followDelayMillis)
    val mainFontSp = lyricFontSizeSp.sp
    val mainLineHeightSp = (lyricFontSizeSp * 1.38f).sp
    val translationFontSp = (lyricFontSizeSp * 0.50f).coerceIn(12f, 20f).sp
    val translationLineHeightSp = (translationFontSp.value * 1.38f).sp
    val lyricMaxLines = if (showFullLyrics) Int.MAX_VALUE else 2
    val measuredRowHeightsPx = remember(trackId, lyricFontSizeSp, showFullLyrics) {
        mutableStateMapOf<AndroidTimedLyricLine, Int>()
    }
    val measuredMainHeightsPx = remember(trackId, lyricFontSizeSp, showFullLyrics) {
        mutableStateMapOf<AndroidTimedLyricLine, Int>()
    }
    val estimatedMainHeightPx = with(density) { mainLineHeightSp.toPx() }
    val estimatedTranslationHeightPx = with(density) { translationLineHeightSp.toPx() }
    // Spacing scales with the rendered lyric line height, so it follows the font-size setting.
    val spacing = lyricSpacing(estimatedMainHeightPx)
    val minimumRowGapPx = spacing.minimumRowGapPx
    val maximumRowGapPx = spacing.maximumRowGapPx
    val minimumTranslationGapPx = spacing.minimumTranslationGapPx
    val maximumTranslationGapPx = spacing.maximumTranslationGapPx
    val rowHeightsPx = lines.map { line ->
        measuredRowHeightsPx[line]?.toFloat() ?: (
            estimatedMainHeightPx + if (line.translation.isNullOrBlank()) {
                0f
            } else {
                lyricTranslationGapPx(
                    estimatedMainHeightPx,
                    minimumTranslationGapPx,
                    maximumTranslationGapPx,
                ) + estimatedTranslationHeightPx
            }
        )
    }
    val transientIndex = lines.indexOfFirst { it.text.isBlank() }
    val lineCentersPx = lyricLineCentersWithTransientRow(
        rowHeightsPx = rowHeightsPx,
        transientIndex = transientIndex,
        presence = interludePresence,
        minimumGapPx = minimumRowGapPx,
        maximumGapPx = maximumRowGapPx,
    )
    val maxScroll = lineCentersPx.lastOrNull() ?: 0f
    val currentLineCentersPx by rememberUpdatedState(lineCentersPx)
    val currentMaxScroll by rememberUpdatedState(maxScroll)
    var followPlayback by remember { mutableStateOf(true) }
    var isDragging by remember { mutableStateOf(false) }
    var manualAtMillis by remember { mutableLongStateOf(0L) }
    var lyricScroll by remember { mutableFloatStateOf(0f) }
    var flingVelocity by remember { mutableFloatStateOf(0f) }
    var lastDragNanos by remember { mutableLongStateOf(0L) }
    var lastFrameNanos by remember { mutableLongStateOf(0L) }
    var motionFrame by remember { mutableLongStateOf(0L) }
    val lyricLineMotion = remember { LyricLineMotionField() }

    LaunchedEffect(trackId, lyricFontSizeSp, showFullLyrics) {
        followPlayback = true
        isDragging = false
        flingVelocity = 0f
        val initialIndex = activeAndroidLyricIndex(lines, positionMillis).coerceAtLeast(0)
        lyricScroll = lineCentersPx.getOrElse(initialIndex) { 0f }
        lyricLineMotion.reset(lines.size, lyricScroll)
        lastFrameNanos = 0L
    }
    var previousLines by remember(trackId) { mutableStateOf(lines) }
    LaunchedEffect(lines) {
        // Inserting/removing a dot row must not reset manual browsing or existing row springs.
        lyricLineMotion.remap(lines.map { previousLines.indexOf(it) }, lyricScroll)
        previousLines = lines
        motionFrame++
    }
    LaunchedEffect(Unit) {
        var moving = true
        while (isActive) {
            if (!moving && followPlayback) {
                // No frame callbacks or spring integration while the lyrics are at rest.
                snapshotFlow {
                    !followPlayback || kotlin.math.abs(
                        currentLineCentersPx.getOrElse(currentActiveIndex.coerceAtLeast(0)) { 0f } - lyricScroll
                    ) > 0.02f
                }.first { it }
                lastFrameNanos = 0L
            }
            withFrameNanos { now ->
                val deltaSeconds = if (lastFrameNanos == 0L) {
                    1f / 60f
                } else {
                    ((now - lastFrameNanos) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
                }
                lastFrameNanos = now
                if (!followPlayback && !isDragging && kotlin.math.abs(flingVelocity) < 8f &&
                    System.currentTimeMillis() - manualAtMillis > currentFollowDelayMillis
                ) {
                    lyricLineMotion.snapTo(lyricScroll)
                    followPlayback = true
                }
                if (followPlayback) {
                    flingVelocity = 0f
                    val liveIndex = currentActiveIndex
                    if (liveIndex >= 0) {
                        val target = currentLineCentersPx.getOrElse(liveIndex) { currentMaxScroll }
                            .coerceIn(0f, currentMaxScroll)
                        val intervalMillis = currentLines.getOrNull(liveIndex - 1)?.let { previous ->
                            (currentLines[liveIndex].timeMillis - previous.timeMillis).coerceAtLeast(0L)
                        }
                        moving = lyricLineMotion.advance(
                                target = target,
                                activeIndex = liveIndex,
                                seconds = deltaSeconds,
                                intervalMillis = intervalMillis,
                                speed = currentAnimationSpeed,
                            )
                        motionFrame++
                        lyricScroll = lyricLineMotion.positionFor(liveIndex).coerceIn(0f, currentMaxScroll)
                    } else {
                        moving = false
                    }
                } else if (!isDragging && kotlin.math.abs(flingVelocity) >= 8f) {
                    val nextScroll = (lyricScroll + flingVelocity * deltaSeconds).coerceIn(0f, currentMaxScroll)
                    if (nextScroll == 0f || nextScroll == currentMaxScroll) flingVelocity = 0f
                    lyricScroll = nextScroll
                    flingVelocity *= kotlin.math.exp((-5.2f * deltaSeconds).toDouble()).toFloat()
                    manualAtMillis = System.currentTimeMillis()
                }
            }
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .clipToBounds()
            // A transient dot row must not cancel an ongoing drag. Read changing bounds through
            // rememberUpdatedState and restart the gesture detector only when the track changes.
            .pointerInput(trackId) {
                detectVerticalDragGestures(
                    onDragStart = {
                        // Only while following does the motion field hold the rendered position; a
                        // paused (manual) scroll is already the source of truth, so adopting the
                        // field there would snap the view back to the active line on the next touch.
                        if (followPlayback && currentActiveIndex >= 0) {
                            lyricScroll = lyricLineMotion.positionFor(currentActiveIndex)
                                .coerceIn(0f, currentMaxScroll)
                        }
                        lyricLineMotion.snapTo(lyricScroll)
                        followPlayback = false
                        isDragging = true
                        flingVelocity = 0f
                        lastDragNanos = 0L
                        manualAtMillis = System.currentTimeMillis()
                    },
                    onDragEnd = {
                        isDragging = false
                        manualAtMillis = System.currentTimeMillis()
                    },
                    onDragCancel = {
                        isDragging = false
                        flingVelocity = 0f
                        manualAtMillis = System.currentTimeMillis()
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        val now = change.uptimeMillis * 1_000_000L
                        if (lastDragNanos != 0L) {
                            val deltaSeconds = ((now - lastDragNanos) / 1_000_000_000f).coerceAtLeast(0.001f)
                            val measuredVelocity = -dragAmount / deltaSeconds
                            flingVelocity = flingVelocity * 0.65f + measuredVelocity * 0.35f
                        }
                        lastDragNanos = now
                        manualAtMillis = System.currentTimeMillis()
                        lyricScroll = (lyricScroll - dragAmount).coerceIn(0f, currentMaxScroll)
                    },
                )
            },
    ) {
        val centerYPx = with(density) { (maxHeight / 2).toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val rowHeightMarginPx = with(density) { 160.dp.toPx() }
        // lyricScroll is written every frame while dragging, flinging, or following. The offset
        // lambda below reads it during layout only, so per-frame updates re-place rows without
        // recomposing them; the composition-phase readers here (visual index and the integer
        // visibility range) change a few times across a whole swipe instead of once per pixel.
        val visualIndex by remember(lineCentersPx) {
            derivedStateOf {
                lyricVisualIndex(lineCentersPx, lyricScroll)
            }
        }
        val visibleRange by remember(lineCentersPx, rowHeightsPx, rowHeightMarginPx, centerYPx, heightPx) {
            derivedStateOf {
                var first = lines.lastIndex
                var last = 0
                for (index in lines.indices) {
                    val top = centerYPx + lineCentersPx[index] - lyricScroll - rowHeightsPx[index]
                    if (top < heightPx + rowHeightMarginPx) {
                        last = index
                    }
                    val bottom = top + rowHeightsPx[index]
                    if (bottom > -rowHeightMarginPx) {
                        first = minOf(first, index)
                    }
                }
                if (first > last) 0..lines.lastIndex else first..last
            }
        }
        val firstVisibleRow = visibleRange.first
        val lastVisibleRow = visibleRange.last
        lines.forEachIndexed { index, line ->
            if (index < firstVisibleRow || index > lastVisibleRow) {
                return@forEachIndexed
            }
            val distance = kotlin.math.abs(index - visualIndex)
            val focus = androidx.compose.runtime.key(trackId, line) {
                animatedLyricFocus(index == activeIndexValue, animationSpeed)
            }
            val ambient = (1f - distance / 4f).coerceAtLeast(0f)
            val scale = 0.96f + focus * 0.08f
            val alpha = (0.24f + ambient * 0.20f) * (1f - focus) + focus
            val hasTranslation = !line.translation.isNullOrBlank()
            val textWidthFraction = 1f / 1.04f
            val mainHeightPx = measuredMainHeightsPx[line]?.toFloat() ?: estimatedMainHeightPx
            val translationGap = with(density) {
                lyricTranslationGapPx(
                    mainHeightPx,
                    minimumTranslationGapPx,
                    maximumTranslationGapPx,
                ).toDp()
            }
            val rowHeightPx = rowHeightsPx[index]
            val lineCenterOffsetPx = lineCentersPx[index] - rowHeightPx / 2f
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset {
                        motionFrame // Also place trailing rows after the focused spring settles.
                        // In follow mode each row rides its own staggered spring. The motion field
                        // is a plain array, so reading it alone would never invalidate layout and
                        // the cascade would freeze; anchoring on the lyricScroll state read makes
                        // every frame re-run this lambda, and the motion delta restores each row's
                        // stagger. A paused or manual scroll rides the same state read directly.
                        val rowScroll = if (followPlayback && activeIndexValue >= 0) {
                            val anchor = lyricLineMotion.positionFor(activeIndexValue)
                            lyricScroll + (lyricLineMotion.positionFor(index) - anchor)
                        } else {
                            lyricScroll
                        }
                        IntOffset(
                            x = 0,
                            y = (centerYPx + lineCenterOffsetPx - rowScroll).roundToInt(),
                        )
                    }
                    .padding(horizontal = 26.dp)
                    .graphicsLayer {
                        if (line.text.isBlank()) {
                            // The row geometry already collapses with interludePresence. Keep the
                            // content almost full-sized while its three dots fade in sequence, so
                            // the next lyric and the interlude read as one continuous movement.
                            scaleX = 0.94f + interludePresence * 0.06f
                            scaleY = 0.92f + interludePresence * 0.08f
                            transformOrigin = TransformOrigin.Center
                        }
                    }
                    .onSizeChanged { size ->
                        if (measuredRowHeightsPx[line] != size.height) {
                            measuredRowHeightsPx[line] = size.height
                        }
                    }
                    .clickable {
                        lyricLineMotion.snapTo(lyricScroll)
                        followPlayback = true
                        onSeek(line.timeMillis)
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (line.text.isBlank()) {
                        val interludeEndMillis = line.endTimeMillis
                            ?: lines.getOrNull(index + 1)?.timeMillis
                            ?: line.timeMillis + 5_000L
                        LyricInterludeDots(
                            startMillis = line.timeMillis,
                            endMillis = interludeEndMillis,
                            // Once the next lyric becomes active, retain the fully revealed dot
                            // state while the row exits. Resetting to startMillis here made the
                            // reveal factor hit zero in one frame and caused the visible hard cut.
                            positionMillis = if (index == activeIndexValue) {
                                positionMillis
                            } else {
                                interludeEndMillis
                            },
                            visibility = interludePresence,
                            glowEnabled = lyricGlowEnabled,
                        )
                    } else {
                    androidx.compose.runtime.key(trackId, line) {
                        AmllLyricText(
                            text = line.text,
                            words = line.words,
                            // Only the active line advances its per-word mask; every other row
                            // takes a stable timestamp so the 100 ms playback ticks recompose it
                            // without re-running its mask or smoothing animation.
                            positionMillis = if (index == activeIndexValue) positionMillis else line.timeMillis,
                            active = wordLyricsEnabled && index == activeIndexValue,
                            currentLine = index == activeIndexValue,
                            color = colors.onBackground,
                            shadowColor = if (isDark) Color.White else Color.Black,
                            glowEnabled = lyricGlowEnabled,
                            speed = animationSpeed,
                            modifier = Modifier
                                .fillMaxWidth(textWidthFraction)
                                .onSizeChanged { size ->
                                    if (measuredMainHeightsPx[line] != size.height) {
                                        measuredMainHeightsPx[line] = size.height
                                    }
                                }
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    this.alpha = alpha
                                    // Per-draw alpha avoids allocating a temporary layer with the
                                    // lyric's exact bounds, which clipped the blur during focus.
                                    compositingStrategy = CompositingStrategy.ModulateAlpha
                                    clip = false
                                    transformOrigin = TransformOrigin.Center
                                },
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontSize = mainFontSp,
                                lineHeight = mainLineHeightSp,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            textAlign = TextAlign.Center,
                            maxLines = lyricMaxLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    }
                    if (hasTranslation) {
                        Spacer(Modifier.height(translationGap))
                        Text(
                            text = line.translation.orEmpty(),
                            modifier = Modifier.fillMaxWidth(textWidthFraction).graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                                this.alpha = alpha
                                transformOrigin = TransformOrigin.Center
                            },
                            color = colors.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontSize = translationFontSp,
                                lineHeight = translationLineHeightSp,
                            ),
                            textAlign = TextAlign.Center,
                            maxLines = lyricMaxLines,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LyricEmpty(text: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text,
            modifier = Modifier
                .clip(RoundedCornerShape(18.dp))
                .background(colors.background.copy(alpha = 0.42f))
                .padding(horizontal = 20.dp, vertical = 14.dp),
            color = colors.onBackground,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}
