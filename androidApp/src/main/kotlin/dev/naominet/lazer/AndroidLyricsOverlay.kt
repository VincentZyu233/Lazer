package dev.naominet.lazer

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/** How long a released long press keeps the next drag reserved for extending the selection. */
private const val SelectionDragWindowMillis = 900L

/** Temporary bottom safe area the sheet gains while the selection bar is up. */
private val LyricSelectionSafeArea = 86.dp

/** One height for every control in the selection bar, and one width for its readout. */
private val LyricSelectionControlHeight = 44.dp
private val LyricSelectionStatusWidth = 108.dp

/**
 * Selected lines, the confirmation, and where a bulk change radiated from. It lives with the page
 * rather than inside the sheet because the pill refracts the lyric layer, so it cannot be part of
 * the layer it samples.
 */
private class AndroidLyricSelection {
    var state by mutableStateOf(LyricSelectionState())
    var copied by mutableStateOf(false)

    /** Row a bulk change radiates from, so select-all lights up as a wave instead of a flash. */
    var waveOrigin by mutableIntStateOf(-1)

    /** Until when a released long press hands the next drag to selection instead of scrolling. */
    var dragArmedAt by mutableLongStateOf(0L)
}

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
    glass: LazerLiquidGlass,
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
    val colors = MaterialTheme.colorScheme
    val clipboard = LocalClipboard.current
    val answerTap = rememberTapAnswer()
    val selectionScope = rememberCoroutineScope()
    val selection = remember(track?.id) { AndroidLyricSelection() }
    val selectionKeys = remember(displayLines) { lyricLineKeys(displayLines) }
    // A second, separate backdrop: the pill sits outside the sheet's own layer, and a layer cannot
    // sample itself without feeding its own previous frame back through the glass.
    val sheetGlass = rememberLazerLiquidGlass(
        enabled = glass.isEnabled,
        backgroundColor = colors.background,
        blurIntensity = glass.blurIntensity,
    )

    LaunchedEffect(selection.state.isActive) {
        if (!selection.state.isActive) selection.copied = false
    }

    fun copySelectedLyrics() {
        val text = buildLyricClipboardText(displayLines, selection.state.selectedKeys)
        if (text.isBlank()) return
        selectionScope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(tr("lyrics.select.copy"), text)))
            selection.copied = true
            answerTap()
            delay(1_400L)
            selection.copied = false
        }
    }

    when {
        track == null -> LyricEmpty(tr("lyrics.empty"), modifier)
        isLoading -> LyricEmpty(tr("lyrics.loading"), modifier)
        displayLines.isEmpty() -> LyricEmpty(message ?: tr("lyrics.none"), modifier)
        else -> Box(modifier) {
            AnimatedLyricsViewport(
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
                selection = selection,
                onSeek = onSeek,
                // Only while a passage is being picked: capturing a sheet that animates every frame
                // would cost a full-screen layer copy for a pill that is not on screen.
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (selection.state.isActive) Modifier.captureLiquidGlass(sheetGlass) else Modifier,
                    ),
            )
            AnimatedVisibility(
                visible = selection.state.isActive,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                enter = fadeIn(tween(220, easing = LazerTokens.Motion.pageEasing)) +
                    slideInVertically(tween(340, easing = LazerTokens.Motion.pageEasing)) { it + 28 } +
                    scaleIn(tween(340, easing = LazerTokens.Motion.pageEasing), 0.9f),
                exit = fadeOut(tween(160, easing = LazerTokens.Motion.pageEasing)) +
                    slideOutVertically(tween(240, easing = LazerTokens.Motion.pageEasing)) { it + 28 } +
                    scaleOut(tween(240, easing = LazerTokens.Motion.pageEasing), 0.94f),
                label = "lyric-selection-pill",
            ) {
                LyricSelectionPill(
                    glass = glass,
                    sheetGlass = sheetGlass,
                    selectedCount = selection.state.selectedCount,
                    copied = selection.copied,
                    onSelectAll = {
                        selection.waveOrigin = selectionKeys.indexOf(selection.state.anchorKey)
                            .takeIf { it >= 0 }
                            ?: selectionKeys.indexOfFirst { it != null }
                                .coerceAtLeast(0)
                        selection.state = selection.state.selectAll(selectionKeys)
                    },
                    onCopy = ::copySelectedLyrics,
                    onDismiss = {
                        selection.state = selection.state.clear()
                        selection.waveOrigin = -1
                        selection.dragArmedAt = 0L
                    },
                )
            }
        }
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
    selection: AndroidLyricSelection,
    onSeek: (Long) -> Unit,
    modifier: Modifier,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val answerTap = rememberTapAnswer()
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
    val clickGlowScope = rememberCoroutineScope()
    val clickGlowTokens = remember(trackId, lyricGlowEnabled) {
        mutableStateMapOf<AndroidTimedLyricLine, Any>()
    }
    var isExtendingSelection by remember(trackId) { mutableStateOf(false) }
    val selectionKeys = remember(lines) { lyricLineKeys(lines) }
    val currentSelectionKeys by rememberUpdatedState(selectionKeys)
    val selectionModeLight = animatedLyricSelectionPresence(
        selected = selection.state.isActive,
        speed = animationSpeed,
    )
    // The sheet gains a temporary safe area while the pill is up, so a marked line can rest clear of
    // the glass rather than underneath it. It animates on the pill's own tempo so the two move as
    // one gesture.
    val selectionInsetPx by animateFloatAsState(
        targetValue = if (selection.state.isActive) with(density) { LyricSelectionSafeArea.toPx() } else 0f,
        animationSpec = tween(340, easing = LazerTokens.Motion.pageEasing),
        label = "lyric selection safe area",
    )

    // Freeze the sheet where it is painted; a selection must not slide out from under the finger.
    fun holdSheetForSelection() {
        // Only while following does the motion field hold the rendered position; a paused (manual)
        // scroll is already the source of truth, so adopting the field there would snap the view
        // back to the active line on the next touch.
        if (followPlayback && currentActiveIndex >= 0) {
            lyricScroll = lyricLineMotion.positionFor(currentActiveIndex).coerceIn(0f, currentMaxScroll)
        }
        lyricLineMotion.snapTo(lyricScroll)
        followPlayback = false
        isDragging = false
        flingVelocity = 0f
        manualAtMillis = System.currentTimeMillis()
    }

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
                if (!followPlayback && !isDragging && !selection.state.isActive &&
                    kotlin.math.abs(flingVelocity) < 8f &&
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
                /**
                 * Row whose painted centre sits nearest to [y], or null while the finger is over the
                 * transient interlude row. Centres rather than bounds keep a held drag free of dead
                 * zones between lines, and pulling past either end holds the edge line.
                 */
                fun rowNearestTo(y: Float): Int? {
                    val centers = currentLineCentersPx
                    if (centers.isEmpty()) return null
                    val sheetCenterY = size.height * 0.5f
                    var nearest = 0
                    var nearestDistance = Float.MAX_VALUE
                    for (index in centers.indices) {
                        val distance = abs(sheetCenterY + centers[index] - lyricScroll - y)
                        if (distance < nearestDistance) {
                            nearestDistance = distance
                            nearest = index
                        }
                    }
                    return nearest.takeIf { currentSelectionKeys.getOrElse(index = it) { null } != null }
                }
                detectVerticalDragGestures(
                    onDragStart = {
                        if (selection.state.isActive &&
                            System.currentTimeMillis() - selection.dragArmedAt < SelectionDragWindowMillis
                        ) {
                            isExtendingSelection = true
                        } else {
                            holdSheetForSelection()
                            isDragging = true
                            lastDragNanos = 0L
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                        isExtendingSelection = false
                        manualAtMillis = System.currentTimeMillis()
                    },
                    onDragCancel = {
                        isDragging = false
                        isExtendingSelection = false
                        flingVelocity = 0f
                        manualAtMillis = System.currentTimeMillis()
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        if (isExtendingSelection) {
                            val row = rowNearestTo(change.position.y)
                            if (row != null) selection.state = selection.state.extendTo(currentSelectionKeys, row)
                        } else {
                            val now = change.uptimeMillis * 1_000_000L
                            if (lastDragNanos != 0L) {
                                val deltaSeconds = ((now - lastDragNanos) / 1_000_000_000f).coerceAtLeast(0.001f)
                                val measuredVelocity = -dragAmount / deltaSeconds
                                flingVelocity = flingVelocity * 0.65f + measuredVelocity * 0.35f
                            }
                            lastDragNanos = now
                            manualAtMillis = System.currentTimeMillis()
                            lyricScroll = (lyricScroll - dragAmount).coerceIn(0f, currentMaxScroll)
                        }
                    },
                )
            },
    ) {
        // Keep the active lyric centered in the available lyrics viewport, less the space the
        // selection pill is borrowing at the bottom.
        val centerYPx = with(density) { (maxHeight * 0.5f).toPx() } - selectionInsetPx / 2f
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
            val rowLight = if (line.text.isBlank()) {
                0f
            } else {
                animatedLyricSelectionPresence(
                    selected = selection.state.isSelected(selectionKeys, index),
                    speed = animationSpeed,
                    cascadeDelayMillis = if (selection.waveOrigin >= 0) {
                        lyricSelectionCascadeDelay(index, selection.waveOrigin)
                    } else {
                        0
                    },
                )
            }
            // A marked line borrows the singing line's focus, so the highlight is the same lighting
            // the sheet already knows how to animate rather than a second way of painting text.
            val highlight = lyricSelectionHighlight(focus, rowLight)
            val scale = amllLyricLineScale(highlight)
            val blurRadiusDp = amllLyricBlurRadiusDp(
                distance = distance,
                focus = highlight,
                narrowViewport = maxWidth <= 1024.dp,
                interactionSuspended = !followPlayback,
            )
            val alpha = lyricSelectionRowAlpha(
                baseAlpha = (0.24f + ambient * 0.20f) * (1f - highlight) + highlight * LyricActiveLineAlpha,
                modePresence = selectionModeLight,
                rowPresence = rowLight,
            )
            val lineColor = lyricSelectionColor(colors.onBackground, rowLight, colors.primary)
            val translationColor = lyricSelectionColor(colors.onSurfaceVariant, rowLight, colors.primary)
            val clickGlowActive = clickGlowTokens.containsKey(line)
            val contentBlurRadiusPx = with(density) { blurRadiusDp.dp.toPx() }
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
                        clip = false
                    }
                    .onSizeChanged { size ->
                        if (measuredRowHeightsPx[line] != size.height) {
                            measuredRowHeightsPx[line] = size.height
                        }
                    }
                    .combinedClickable(
                        interactionSource = null,
                        indication = null,
                        onClick = tapFeedback {
                            if (selection.state.isActive) {
                                if (line.text.isNotBlank()) {
                                    selection.waveOrigin = -1
                                    selection.state = selection.state.toggle(selectionKeys, index)
                                }
                            } else {
                                val token = Any()
                                clickGlowTokens[line] = token
                                clickGlowScope.launch {
                                    delay(500L)
                                    if (clickGlowTokens[line] === token) {
                                        clickGlowTokens.remove(line)
                                    }
                                }
                                lyricLineMotion.snapTo(lyricScroll)
                                followPlayback = true
                                onSeek(line.timeMillis)
                            }
                        },
                        onLongClick = {
                            if (line.text.isNotBlank()) {
                                if (!selection.state.isActive) {
                                    answerTap()
                                }
                                selection.waveOrigin = -1
                                selection.dragArmedAt = System.currentTimeMillis()
                                // The sheet holds still the moment the marking starts; otherwise the
                                // line under the finger would drift away mid-gesture.
                                holdSheetForSelection()
                                selection.state = if (selection.state.isActive) {
                                    selection.state.extendTo(selectionKeys, index)
                                } else {
                                    selection.state.begin(selectionKeys, index)
                                }
                            }
                        },
                    ),
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
                            glowEnabled = lyricGlowEnabled || clickGlowActive,
                            dotDiameter = (lyricFontSizeSp * 0.3f).dp,
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
                            color = lineColor,
                            shadowColor = if (isDark) Color.White else Color.Black,
                            glowEnabled = lyricGlowEnabled,
                            temporaryGlow = clickGlowActive,
                            contentBlurRadiusPixels = contentBlurRadiusPx,
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
                                renderEffect = if (contentBlurRadiusPx > 0.01f) {
                                    BlurEffect(contentBlurRadiusPx, contentBlurRadiusPx, TileMode.Decal)
                                } else {
                                    null
                                }
                                clip = false
                                transformOrigin = TransformOrigin.Center
                            },
                            color = translationColor,
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

/**
 * Selection's one control: a full-width glass bar on the same geometry as the transport card, so it
 * reads as part of the page rather than a floating chip. It states how much is marked and what
 * copying does, and it rides the page's own easing.
 */
@Composable
private fun LyricSelectionPill(
    glass: LazerLiquidGlass,
    sheetGlass: LazerLiquidGlass,
    selectedCount: Int,
    copied: Boolean,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp)
            .shadow(12.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(colors.surface.copy(alpha = if (glass.isEnabled) 0.66f else 0.97f), shape)
            .liquidGlassSurface(glass, shape, colors.surface, blurRadius = 12.dp)
            // Second sample of its own backdrop: the lyric sheet. A layer cannot sample itself, so
            // the sheet is captured into a separate glass and refracted over the cover-art one;
            // without it the lines the bar actually covers vanish underneath.
            .liquidGlassSurface(sheetGlass, shape, Color.Transparent, blurRadius = 12.dp)
            .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // What is marked keeps a fixed box so the bar never twitches as the count grows.
        Box(
            modifier = Modifier.size(
                width = LyricSelectionStatusWidth,
                height = LyricSelectionControlHeight,
            ),
            contentAlignment = Alignment.Center,
        ) {
            Crossfade(
                targetState = if (copied) {
                    tr("lyrics.select.copied")
                } else {
                    tr("lyrics.select.count", selectedCount)
                },
                animationSpec = tween(220, easing = LazerTokens.Motion.pageEasing),
                label = "lyric-selection-status",
            ) { status ->
                Text(
                    status,
                    style = MaterialTheme.typography.labelLarge,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
        Spacer(Modifier.weight(1f))
        LiquidGlassIconButton(
            onClick = onSelectAll,
            contentDescription = tr("lyrics.select.all"),
            glass = glass,
            size = LyricSelectionControlHeight,
            tint = colors.onSurfaceVariant,
        ) {
            Icon(Icons.Outlined.SelectAll, null, Modifier.size(22.dp))
        }
        // The action is an icon like its neighbours: a text capsule has to be measured before the
        // close control gets its turn, and at large font scales it ate the space the icons needed.
        LiquidGlassIconButton(
            onClick = onCopy,
            contentDescription = tr("lyrics.select.copy"),
            glass = glass,
            size = LyricSelectionControlHeight,
            tint = colors.primary,
        ) {
            Crossfade(
                targetState = copied,
                animationSpec = tween(220, easing = LazerTokens.Motion.pageEasing),
                label = "lyric-selection-copy-icon",
            ) { done ->
                Icon(
                    if (done) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                    null,
                    Modifier.size(22.dp),
                )
            }
        }
        LiquidGlassIconButton(
            onClick = onDismiss,
            contentDescription = tr("lyrics.select.close"),
            glass = glass,
            size = LyricSelectionControlHeight,
            tint = colors.onSurfaceVariant,
        ) {
            Icon(Icons.Outlined.Close, null, Modifier.size(22.dp))
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
