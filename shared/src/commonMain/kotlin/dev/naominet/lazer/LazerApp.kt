@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package dev.naominet.lazer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.ModeComment
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import io.ktor.util.date.getTimeMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.material.icons.outlined.Headphones
import dev.naominet.lazer.gateway.AudioQuality
import dev.naominet.lazer.gateway.SONG_COMMENT_CONTENT_LIMIT
import dev.naominet.lazer.gateway.model.Artist
import dev.naominet.lazer.gateway.model.SongComment
import dev.naominet.lazer.gateway.model.parseListenTogetherInvite
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import top.yukonga.miuix.kmp.basic.Button as MiuixButton
import top.yukonga.miuix.kmp.basic.ButtonColors as MiuixButtonColors
import top.yukonga.miuix.kmp.basic.ButtonDefaults as MiuixButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.NavigationBar as MiuixNavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarDisplayMode as MiuixNavigationBarDisplayMode
import top.yukonga.miuix.kmp.basic.NavigationBarItem as MiuixNavigationBarItem
import kotlin.math.roundToLong
import kotlin.math.roundToInt
import kotlin.math.sin

private const val PAGE_TRANSITION_MILLIS = LazerTokens.Motion.pageMillis
private val LazerMotionEasing = LazerTokens.Motion.pageEasing

// A page that is being covered gives way just ahead of the page arriving above it: three quarters
// gone when the newcomer is two thirds in, and gone for good once the newcomer reaches 90%.
private const val COVER_HIDE_LEAD = 0.1f
private const val COVER_HIDE_LEAD_FROM = 0.65f
private const val COVER_HIDE_GONE_AT = 0.9f

// Extra bottom content padding for scrollable pages so their last rows stay reachable behind the
// floating landscape mini player.
private val LocalLazerContentBottomInset = compositionLocalOf { 0.dp }

private data class LazerCoverSaveRequest(val url: String, val title: String)

/** A text field a long press offers to the clipboard. The sheet asks first, as saving a cover does. */
private data class LazerCopyTextRequest(
    val titleKey: String,
    val hintKey: String,
    val value: String,
)

private val LocalLazerOpenArtists = androidx.compose.runtime.staticCompositionLocalOf<(List<Artist>) -> Unit> { {} }
private val LocalLazerRequestCoverSave = androidx.compose.runtime.staticCompositionLocalOf<(LazerCoverSaveRequest) -> Unit> { {} }
private val LocalLazerRequestCopyText = androidx.compose.runtime.staticCompositionLocalOf<(LazerCopyTextRequest) -> Unit> { {} }

private enum class LazerMainPageKind(val depth: Int) {
    ROOT(0),
    PLAYLIST(1),
    SETTINGS(1),
    ARTIST(2),
    ABOUT(2),
}

private data class LazerMainPage(
    val kind: LazerMainPageKind,
    val playlist: LazerPlaylist? = null,
    val artist: Artist? = null,
    val tracks: List<LazerTrack> = emptyList(),
    val isLoading: Boolean = false,
) {
    val contentKey: Any
        get() = when (kind) {
            LazerMainPageKind.PLAYLIST -> kind to playlist?.id
            LazerMainPageKind.ARTIST -> kind to artist?.id
            else -> kind
        }
}

/**
 * Whether a control answers a landed tap at all. Provided once at the root, so the setting reaches
 * every control without threading it through call sites.
 */
internal val LocalTapHapticsEnabled = androidx.compose.runtime.staticCompositionLocalOf { true }

/**
 * Where the reader turned system haptics off, the app stays quiet too. The host reads the platform's
 * own switch, because on some platforms that is the only one that matters.
 */
@Composable
private fun hapticsAllowedBySystem(): Boolean = LocalLazerScreenHost.current.systemHapticsEnabled

// Both answers below go through the platform's own haptic categories rather than a duration and an
// amplitude. Raw values can be graded into strengths, but they bypass the waveform each maker tunes
// for its own motor, and that tuning is the whole of what reads as a click instead of a thud. One
// strength, set by the system, is the honest offer.

/** The click a landed tap gets. */
private fun answerTap(viewHaptics: HapticFeedback) {
    viewHaptics.performHapticFeedback(HapticFeedbackType.VirtualKey)
}

/** One drag notch, lighter than the tap on purpose so a run of them reads as grain. */
private fun answerDetentTick(viewHaptics: HapticFeedback) {
    viewHaptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
}

/**
 * Cells a drag is divided into. The seek rail is divided by the length of what is playing rather
 * than by distance, so a full-travel drag passes under the same number of notches whether the track
 * runs two minutes or twenty.
 */
private const val SEEK_DETENTS = 40
private const val SLIDER_DETENTS = 20

/**
 * Gives a drag the damping of a track of cells. [quantize] maps a 0f..1f position to the cell it
 * sits in, and the answer fires only when the position moves into a different cell — which is the
 * difference between notches under the finger and a rattle, since a drag reports a position at every
 * frame. Reaching for the track answers once when the finger lands away from the last notch it was
 * left in, the way a knob clicks into place; a drag that starts where the last one ended stays quiet.
 */
@Composable
internal fun rememberDetentAnswer(quantize: (Float) -> Int): (Float) -> Unit {
    val viewHaptics = LocalHapticFeedback.current
    val answer = LocalTapHapticsEnabled.current && hapticsAllowedBySystem()
    val latestQuantize by rememberUpdatedState(quantize)
    val lastCell = remember { mutableIntStateOf(Int.MIN_VALUE) }
    return { fraction ->
        val cell = latestQuantize(fraction)
        val previous = lastCell.intValue
        lastCell.intValue = cell
        if (answer && previous != cell && previous != Int.MIN_VALUE) {
            answerDetentTick(viewHaptics)
        }
    }
}

/** Answers a gesture that landed. Silent when the reader asked for no answer. */
@Composable
internal fun rememberTapAnswer(): () -> Unit {
    val viewHaptics = LocalHapticFeedback.current
    val answer = LocalTapHapticsEnabled.current && hapticsAllowedBySystem()
    return { if (answer) answerTap(viewHaptics) }
}

/**
 * Wraps a click so a tap that lands answers with a buzz. A press that turns into a scroll, or that
 * is lifted off the control, never reaches the callback and stays quiet. Applied to the shared
 * controls rather than to every call site, so one change covers the whole app.
 */
@Composable
internal fun tapFeedback(onClick: () -> Unit): () -> Unit {
    val answer = rememberTapAnswer()
    return {
        answer()
        onClick()
    }
}

// The two modifiers below exist so that no call site has to remember the haptic on its own. A raw
// `Modifier.clickable` or `Modifier.selectable` is the one way to miss a control, so the build fails
// on it. Material controls take too many shapes to wrap, so those answer through `tapFeedback`.

/** A clickable surface that answers a landed tap. */
@Composable
internal fun Modifier.tapClickable(
    role: Role? = null,
    onClickLabel: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = clickable(
    enabled = enabled,
    onClickLabel = onClickLabel,
    role = role,
    onClick = tapFeedback(onClick),
)

/** A selectable row that answers the choice it just committed. */
@Composable
internal fun Modifier.tapSelectable(
    selected: Boolean,
    role: Role? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = selectable(
    selected = selected,
    enabled = enabled,
    role = role,
    onClick = tapFeedback(onClick),
)

/**
 * A slider that notches under the finger and answers where the finger left. Buzzing on every frame
 * of [LazerSlider.onValueChange] turns a drag into a rattle, so the travel is answered by cell and
 * the landing by one firmer click.
 */
@Composable
internal fun TapSlider(
    engine: LazerThemeEngine,
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val answer = rememberTapAnswer()
    // A stepped track notches at its own detents, so a tick lands where the knob actually snaps. A
    // continuous one is given a grid, because it would otherwise travel end to end in silence.
    val cells = if (steps > 0) steps + 2 else SLIDER_DETENTS
    val detent = rememberDetentAnswer { fraction ->
        if (steps > 0) (fraction * (cells - 1)).roundToInt()
        else (fraction * cells).toInt().coerceAtMost(cells - 1)
    }
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(1e-4f)
    LazerSlider(  // haptic-raw -- the wrapper above is what answers; every call site goes through it
        engine = engine,
        value = value,
        onValueChange = { next ->
            detent(((next - valueRange.start) / span).coerceIn(0f, 1f))
            onValueChange(next)
        },
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        onValueChangeFinished = {
            answer()
            onValueChangeFinished?.invoke()
        },
    )
}

private enum class LazerBackLayer {
    ARTIST,
    PLAYLIST,
    SETTINGS,
    ABOUT,
    PLAYER,
}

/**
 * The screen's own corner radius. Predictive back scales a page down and shows its corners, and a
 * square there reads as a pasted screenshot instead of a window of the same shape as the display.
 * A device whose corners really are square keeps square pages.
 */
@Composable
private fun rememberScreenCornerRadius(): Dp {
    val host = LocalLazerScreenHost.current
    val density = LocalDensity.current
    return remember(host, density) { with(density) { host.screenCornerRadiusPx.toDp() } }
}

private fun Modifier.predictiveBackTransform(
    enabled: Boolean,
    progress: Float,
    swipeEdge: LazerSwipeEdge,
): Modifier = if (!enabled) {
    this
} else {
    graphicsLayer {
        val fraction = progress.coerceIn(0f, 1f)
        val direction = if (swipeEdge == LazerSwipeEdge.Right) -1f else 1f
        translationX = size.width * 0.16f * fraction * direction
        val scale = 1f - 0.09f * fraction
        scaleX = scale
        scaleY = scale
        alpha = 1f - 0.22f * fraction
    }
}

/**
 * The visual plane a page paints for itself: the app base, the wallpaper or the album flow, and the
 * scrim that lifts text off them.
 *
 * Pages used to borrow a snapshot of the fixed canvas behind the whole stack, which meant a page was
 * only ever as opaque as that snapshot - and when the snapshot came up empty the page underneath it
 * showed straight through. Every page carries its own now, so no sibling can be seen through another.
 */
@Composable
private fun LazerVisualBackground(
    controller: LazerGatewayController,
    track: LazerTrack?,
    colors: ColorScheme,
    uiAlpha: Float,
    wallpaper: ImageBitmap?,
    usesNowPlayingPalette: Boolean,
    animated: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        // The opaque app base belongs inside the plane, so a transparent PNG wallpaper cannot reveal
        // another page underneath wherever it lets light through.
        Box(Modifier.fillMaxSize().background(colors.background))
        wallpaper?.let { image ->
            val imageModifier = if (controller.backgroundImageBlurEnabled) {
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.08f
                        scaleY = 1.08f
                    }
                    .blur(40.dp * controller.backgroundImageBlurIntensity)
            } else {
                Modifier.fillMaxSize()
            }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = imageModifier,
            )
        }
        if (usesNowPlayingPalette) {
            LazerAlbumFlowBackground(
                track = track,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 0.dp,
                veil = Color.Transparent,
                animated = animated,
                solid = controller.backgroundMode == LazerBackgroundMode.NOW_PLAYING_STATIC,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.background.copy(alpha = uiAlpha)),
        )
    }
}

/** How far a covered page has given way, given how far the page above it has arrived. */
private fun coveredPageHideProgress(cover: Float): Float {
    val arrived = cover.coerceIn(0f, 1f)
    return when {
        arrived >= COVER_HIDE_GONE_AT -> 1f
        arrived >= COVER_HIDE_LEAD_FROM -> arrived + COVER_HIDE_LEAD
        else -> arrived * ((COVER_HIDE_LEAD_FROM + COVER_HIDE_LEAD) / COVER_HIDE_LEAD_FROM)
    }
}

/**
 * Keeps a page another one has arrived above out of the way: it fades as the newcomer covers it,
 * stops drawing altogether once there is nothing of it left to see, and answers no taps for as long
 * as either holds. A page under another one must not respond to a finger aimed at the page above
 * it, and it has to stay composed so that returning to it is one frame rather than a page
 * rebuilding its scroll position and replaying its entrance.
 *
 * [hideProgress] is read while drawing rather than while composing, so a transition fades the page
 * out without recomposing it every frame.
 */
private fun Modifier.behindPages(hideProgress: () -> Float): Modifier = this
    .graphicsLayer { alpha = 1f - hideProgress().coerceIn(0f, 1f) }
    .drawWithContent {
        if (hideProgress() < 1f) drawContent()
    }
    .pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (hideProgress() <= 0f) return@awaitEachGesture
            down.consume()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.none { it.pressed }) break
            }
        }
    }

@Composable
private fun isLandscapeLayout(): Boolean =
    LocalWindowInfo.current.containerSize.let { it.width > it.height }

@Composable
private fun AdaptiveDetailHeader(artwork: @Composable () -> Unit, content: @Composable () -> Unit) {
    if (isLandscapeLayout()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            artwork()
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) { content() }
        }
    } else {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            artwork()
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun ThemeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    cornerRadius: Dp? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val tapped = tapFeedback(onClick)
    if (LocalLazerThemeEngine.current == LazerThemeEngine.MIUIX) {
        val colors = MiuixButtonDefaults.buttonColorsPrimary()
        CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides
                if (enabled) colors.contentColor else colors.disabledContentColor,
        ) {
            MiuixButton(
                onClick = tapped,
                modifier = modifier,
                enabled = enabled,
                cornerRadius = cornerRadius ?: MiuixButtonDefaults.CornerRadius,
                colors = colors,
                content = content,
            )
        }
    } else if (cornerRadius != null) {
        Button(
            onClick = tapped,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(cornerRadius),
            content = content,
        )
    } else {
        Button(onClick = tapped, modifier = modifier, enabled = enabled, content = content)
    }
}

@Composable
private fun ThemeTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val tapped = tapFeedback(onClick)
    if (LocalLazerThemeEngine.current == LazerThemeEngine.MIUIX) {
        val textColors = MiuixButtonDefaults.textButtonColors()
        val colors = MiuixButtonColors(
            color = textColors.color,
            disabledColor = textColors.disabledColor,
            contentColor = textColors.textColor,
            disabledContentColor = textColors.disabledTextColor,
        )
        CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides
                if (enabled) colors.contentColor else colors.disabledContentColor,
        ) {
            MiuixButton(
                onClick = tapped,
                modifier = modifier,
                enabled = enabled,
                colors = colors,
                content = content,
            )
        }
    } else {
        TextButton(onClick = tapped, modifier = modifier, enabled = enabled, content = content)
    }
}

@Composable
private fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val uiAlpha = LocalLazerUiAlpha.current
    if (LocalLazerThemeEngine.current == LazerThemeEngine.MIUIX) {
        CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides colors.onSurface) {
            MiuixCard(
                modifier = modifier.fillMaxWidth(),
                cornerRadius = 18.dp,
            ) {
                content()
            }
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = colors.surfaceContainerHigh.copy(alpha = uiAlpha),
        ) {
            content()
        }
    }
}

/**
 * A smoke route that names a page of real data has to wait for that data, which arrives over the
 * network. The wait is bounded so a list that never fills in fails the job instead of hanging it.
 */
private suspend fun <T> awaitLazerSmokeList(read: () -> List<T>): List<T> {
    var waits = 0
    while (read().isEmpty() && waits < 40) {
        kotlinx.coroutines.delay(200L)
        waits++
    }
    return read()
}

@Composable
fun LazerApp(
    controller: LazerGatewayController,
    screen: LazerScreenHost,
    authWebView: LazerAuthWebView,
    initialListenTogetherInvitation: String? = null,
    smokeRoute: String? = null,
) {
    CompositionLocalProvider(
        LocalLazerScreenHost provides screen,
        LocalLazerAuthWebView provides authWebView,
        LocalLazerPlatformHost provides controller.host,
    ) {
        LazerAppContent(controller, initialListenTogetherInvitation, smokeRoute)
    }
}

@Composable
private fun LazerAppContent(
    controller: LazerGatewayController,
    initialListenTogetherInvitation: String?,
    smokeRoute: String?,
) {
    val screen = LocalLazerScreenHost.current
    var pendingQrAuthorizationUrl by remember { mutableStateOf<String?>(null) }
    var rootMessage by remember { mutableStateOf<String?>(null) }
    fun handleScanned(raw: String?) {
        when (val target = raw?.let(::classifyScannedCode)) {
            is ScannedCode.ListenTogether -> controller.joinListenTogether(target.raw)
            is ScannedCode.ClientLogin -> {
                if (controller.currentSessionCookie == null) {
                    rootMessage = tr("scan.login_required")
                    controller.openLogin()
                } else {
                    pendingQrAuthorizationUrl = target.url
                }
            }
            ScannedCode.Unsupported -> rootMessage = tr("scan.unsupported")
            null -> Unit
        }
    }
    // Library/navigation only need transport changes; position ticks belong to the visible player.
    val playback by remember {
        controller.player.snapshot.map { it.copy(positionMillis = 0L, bufferedFraction = 0f) }
            .distinctUntilChanged()
    }.collectAsState(initial = controller.player.snapshot.value)
    var playerVisible by remember { mutableStateOf(false) }
    var artistChoices by remember { mutableStateOf<List<Artist>>(emptyList()) }
    var coverSaveRequest by remember { mutableStateOf<LazerCoverSaveRequest?>(null) }
    var coverSaveTarget by remember { mutableStateOf<LazerCoverSaveRequest?>(null) }
    var copyTextRequest by remember { mutableStateOf<LazerCopyTextRequest?>(null) }
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    var requestedBackProgress by remember { mutableFloatStateOf(0f) }
    var isPredictiveBackRunning by remember { mutableStateOf(false) }
    var backSwipeEdge by remember { mutableStateOf(LazerSwipeEdge.Left) }
    var transformedBackLayer by remember { mutableStateOf<LazerBackLayer?>(null) }
    val screenCornerRadius = rememberScreenCornerRadius()

    val activeBackLayer = when {
        playerVisible -> LazerBackLayer.PLAYER
        controller.isAboutVisible -> LazerBackLayer.ABOUT
        controller.activeArtist != null -> LazerBackLayer.ARTIST
        controller.isSettingsVisible -> LazerBackLayer.SETTINGS
        controller.activePlaylist != null -> LazerBackLayer.PLAYLIST
        else -> null
    }
    val renderedBackProgress by animateFloatAsState(
        targetValue = requestedBackProgress,
        animationSpec = if (isPredictiveBackRunning) {
            snap()
        } else {
            tween(PAGE_TRANSITION_MILLIS, easing = LazerMotionEasing)
        },
        label = "predictive-back-progress",
    )
    LaunchedEffect(isPredictiveBackRunning, renderedBackProgress) {
        if (!isPredictiveBackRunning && renderedBackProgress == 0f) transformedBackLayer = null
    }

    DisposableEffect(controller) {
        onDispose { controller.close() }
    }
    LaunchedEffect(initialListenTogetherInvitation) {
        initialListenTogetherInvitation?.let(controller::joinListenTogether)
    }
    LaunchedEffect(rootMessage) {
        val shown = rootMessage ?: return@LaunchedEffect
        kotlinx.coroutines.delay(3_000L)
        if (rootMessage == shown) rootMessage = null
    }
    LaunchedEffect(playback.track?.id, playback.track?.source) {
        val track = playback.track
        if (track == null || track.source is LazerTrackSource.LocalFile) {
            controller.clearLyrics()
        } else {
            controller.loadLyrics(track.id)
        }
    }
    // Continuous integration has no finger to tap with, so a launch names the screen it wants and
    // the app walks there through the same calls a tap makes. The player route waits for the real
    // recommendation list instead of inventing a track the listener would never see.
    LaunchedEffect(smokeRoute) {
        val route = smokeRoute ?: return@LaunchedEffect
        when (route) {
            "settings" -> controller.openSettings()
            "about" -> controller.openAbout()
            "login" -> controller.openLogin()
            "playlist" -> awaitLazerSmokeList(controller::featuredPlaylists).firstOrNull()
                ?.let(controller::openPlaylist)
            "player", "comments", "queue" -> {
                val tracks = awaitLazerSmokeList(controller::homeTracks)
                tracks.firstOrNull()?.let { controller.play(tracks, it) }
                playerVisible = true
                when (route) {
                    "comments" -> controller.openSongComments()
                    "queue" -> controller.openQueueSheet()
                }
            }
            else -> controller.selectDestination(
                LazerRootDestination.entries
                    .firstOrNull { it.name.equals(route, ignoreCase = true) }
                    ?: LazerRootDestination.HOME,
            )
        }
    }

    // The currently visible top layer owns back. Gesture progress drives the same page that a
    // normal back press closes; cancelling the gesture eases that page back into place.
    screen.BackGesture(
        enabled = !controller.isLoginVisible && activeBackLayer != null,
        onProgress = { progress, edge ->
            transformedBackLayer = activeBackLayer
            // Both platforms end a gesture by reporting no progress, whether it closed the layer or
            // slid back into place, so that zero is the gesture letting go rather than a first frame.
            isPredictiveBackRunning = progress > 0f
            requestedBackProgress = progress
            backSwipeEdge = edge
        },
        onConfirmed = {
            when (activeBackLayer) {
                LazerBackLayer.PLAYER -> playerVisible = false
                LazerBackLayer.ARTIST -> controller.closeArtist()
                LazerBackLayer.SETTINGS -> controller.closeSettings()
                LazerBackLayer.ABOUT -> controller.closeAbout()
                LazerBackLayer.PLAYLIST -> controller.closePlaylist()
                null -> Unit
            }
            isPredictiveBackRunning = false
            requestedBackProgress = 0f
        },
    )

    val mainPage = when {
        controller.isAboutVisible -> LazerMainPage(LazerMainPageKind.ABOUT)
        controller.isSettingsVisible -> LazerMainPage(LazerMainPageKind.SETTINGS)
        controller.activeArtist != null -> LazerMainPage(
            kind = LazerMainPageKind.ARTIST,
            artist = controller.activeArtist,
            tracks = controller.activeArtistTracks,
            isLoading = controller.isArtistLoading,
        )
        controller.activePlaylist != null -> LazerMainPage(
            kind = LazerMainPageKind.PLAYLIST,
            playlist = controller.activePlaylist,
            tracks = controller.activePlaylistTracks,
            isLoading = controller.isPlaylistLoading,
        )
        else -> LazerMainPage(LazerMainPageKind.ROOT)
    }
    val windowSize = LocalWindowInfo.current.containerSize
    val nowPlayingPaletteSeed = rememberLazerArtworkSeed(
        playback.track.takeIf { controller.palette == LazerPalette.NowPlaying },
    )
    val paletteColorScheme = remember(
        controller.palette,
        controller.isDark,
        windowSize,
        nowPlayingPaletteSeed,
    ) {
        when (val palette = controller.palette) {
            LazerPalette.Default -> null
            LazerPalette.System ->
                if (screen.supportsSystemPalette) screen.dynamicColorScheme(controller.isDark) else null
            LazerPalette.NowPlaying -> nowPlayingPaletteSeed?.let {
                seedColorScheme(it, controller.isDark)
            }
            is LazerPalette.Custom -> seedColorScheme(palette.seed, controller.isDark)
        }
    }

    LazerTheme(
        isDark = controller.isDark,
        colorScheme = paletteColorScheme,
        engine = controller.themeEngine,
    ) {
        CompositionLocalProvider(
            LocalLazerOpenArtists provides { artists ->
                val available = artists.filter { it.id > 0L && it.name.isNotBlank() }.distinctBy(Artist::id)
                when (available.size) {
                    0 -> Unit
                    1 -> {
                        playerVisible = false
                        controller.openArtist(available.single())
                    }
                    else -> artistChoices = available
                }
            },
            LocalLazerRequestCoverSave provides { coverSaveRequest = it },
            LocalLazerRequestCopyText provides { copyTextRequest = it },
            LocalTapHapticsEnabled provides controller.hapticsEnabled,
        ) {
        val colors = MaterialTheme.colorScheme
        val launchScanner = {
            screen.scanCode(
                LazerScanTheme(
                    isDark = controller.isDark,
                    background = colors.background,
                    surface = colors.surface,
                    primary = colors.primary,
                    primaryContainer = colors.primaryContainer,
                    onBackground = colors.onBackground,
                    onSurfaceVariant = colors.onSurfaceVariant,
                    onPrimaryContainer = colors.onPrimaryContainer,
                    title = tr("scan.open"),
                    description = tr("scan.description"),
                    prompt = tr("scan.prompt"),
                    backLabel = tr("common.back"),
                ),
                onResult = ::handleScanned,
            )
        }
        // Picking a song is a playback decision, not a navigation one: the mini player already shows
        // what is playing, and the reader opens the full page from there when they want it.
        val playFromQueue: (List<LazerTrack>, LazerTrack) -> Unit = { queue, track ->
            controller.play(queue, track)
        }
        val shareListenTogether: (String) -> Unit = { url ->
            screen.shareText(url, tr("listen_together.share"))
        }
        SideEffect {
            screen.setStatusBarAppearance(controller.isDark)
        }
        val wallpaper = controller.backgroundImage.takeIf {
            controller.backgroundMode == LazerBackgroundMode.IMAGE
        }
        val usesNowPlayingPalette = controller.backgroundMode == LazerBackgroundMode.NOW_PLAYING_DYNAMIC ||
            controller.backgroundMode == LazerBackgroundMode.NOW_PLAYING_STATIC
        val hasVisualBackground = wallpaper != null || usesNowPlayingPalette
        // The slider controls the opacity of app surfaces above visual backgrounds. The image or
        // artwork palette itself stays opaque, so navigation transitions never expose another page.
        val uiAlpha = resolveLazerUiAlpha(hasVisualBackground, controller.backgroundAlpha)
        val landscape = isLandscapeLayout()
        val floatingControlsInset = if (landscape) {
            if (playback.track != null) 60.dp + navigationBarBottomInset() else 0.dp
        } else {
            0.dp
        }
        Box(
            Modifier
                .fillMaxSize()
                .releaseKeyboardOnAnyTap()
                .background(colors.background),
        ) {
            // The tab pages sit directly on this plane; every page stacked above them carries its
            // own copy, so a page is never translucent over its neighbour.
            LazerVisualBackground(
                controller = controller,
                track = playback.track,
                colors = colors,
                uiAlpha = uiAlpha,
                wallpaper = wallpaper,
                usesNowPlayingPalette = usesNowPlayingPalette,
                animated = controller.backgroundMode == LazerBackgroundMode.NOW_PLAYING_DYNAMIC &&
                    !playerVisible,
                modifier = Modifier.fillMaxSize(),
            )
            // Android 16 forces edge-to-edge. Keep the visual canvas under the status bar, while
            // placing every interactive root-page element below its dynamic inset.
            CompositionLocalProvider(
                LocalLazerContentBottomInset provides floatingControlsInset,
                LocalLazerUiAlpha provides uiAlpha,
            ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(start = if (landscape) 64.dp else 0.dp)
                    .then(if (landscape) Modifier.safeDrawingPadding() else Modifier),
            ) {
                // Manual status-bar inset. The fixed background canvas already paints this area.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(if (landscape) 0.dp else statusBarTopInset()),
                )
                Box(Modifier.weight(1f)) {
                    // How far the page above has arrived, which is also how far a back gesture has
                    // lifted it away again. It runs on the transition's own clock, so the pages
                    // below give way with the page arriving over them and come back with the one
                    // leaving - and a finger dragging that page aside pulls them back directly.
                    val rootCover = animateFloatAsState(
                        targetValue = when {
                            mainPage.kind == LazerMainPageKind.ROOT -> 0f
                            isPredictiveBackRunning -> (1f - requestedBackProgress).coerceIn(0f, 1f)
                            else -> 1f
                        },
                        animationSpec = if (isPredictiveBackRunning) {
                            snap()
                        } else {
                            tween(PAGE_TRANSITION_MILLIS, easing = LazerMotionEasing)
                        },
                        label = "lazer-root-cover",
                    )
                    // The root page stays mounted under all of that, so returning from a playlist
                    // cannot replay its scroll position or its bottom-edge entrance.
                    LazerRootContent(
                        controller = controller,
                        currentTrackId = playback.track?.id,
                        onPlay = playFromQueue,
                        onListenTogether = controller::openListenTogether,
                        onScan = launchScanner,
                        showHeaderControls = true,
                        modifier = Modifier
                            .fillMaxSize()
                            .behindPages { coveredPageHideProgress(rootCover.value) },
                    )
                    AnimatedContent(
                        targetState = mainPage,
                        modifier = Modifier.fillMaxSize(),
                        transitionSpec = {
                            val movesForward = targetState.kind.depth > initialState.kind.depth
                            val enter = slideInHorizontally(
                                animationSpec = tween(
                                    durationMillis = PAGE_TRANSITION_MILLIS,
                                    easing = LazerMotionEasing,
                                ),
                                initialOffsetX = { width -> if (movesForward) width / 5 else -width / 5 },
                            ) + fadeIn(tween(PAGE_TRANSITION_MILLIS, easing = LazerMotionEasing))
                            val exit = slideOutHorizontally(
                                animationSpec = tween(PAGE_TRANSITION_MILLIS, easing = LazerMotionEasing),
                                targetOffsetX = { width -> if (movesForward) -width / 8 else width / 8 },
                            ) + fadeOut(tween(PAGE_TRANSITION_MILLIS, easing = LazerMotionEasing))
                            (enter togetherWith exit).apply {
                                targetContentZIndex = if (movesForward) 1f else -1f
                            }
                        },
                        contentKey = LazerMainPage::contentKey,
                        label = "lazer-main-page",
                    ) { page ->
                        if (page.kind == LazerMainPageKind.ROOT) {
                            Box(Modifier.fillMaxSize())
                            return@AnimatedContent
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .predictiveBackTransform(
                                    enabled = when (page.kind) {
                                        LazerMainPageKind.ARTIST -> transformedBackLayer == LazerBackLayer.ARTIST
                                        LazerMainPageKind.PLAYLIST -> transformedBackLayer == LazerBackLayer.PLAYLIST
                                        LazerMainPageKind.SETTINGS -> transformedBackLayer == LazerBackLayer.SETTINGS
                                        LazerMainPageKind.ABOUT -> transformedBackLayer == LazerBackLayer.ABOUT
                                        LazerMainPageKind.ROOT -> false
                                    },
                                    progress = renderedBackProgress,
                                    swipeEdge = backSwipeEdge,
                                ),
                            // The tab pages below stop drawing once a detail page has arrived, so a
                            // translucent page never shows its neighbour through itself.
                            color = if (hasVisualBackground) Color.Transparent else colors.background,
                            contentColor = colors.onBackground,
                            shape = RoundedCornerShape(screenCornerRadius),
                        ) {
                            when (page.kind) {
                                LazerMainPageKind.SETTINGS -> SettingsPage(controller)
                                LazerMainPageKind.ABOUT -> AboutPage(controller::closeAbout)
                                LazerMainPageKind.ARTIST -> page.artist?.let { artist ->
                                    ArtistPage(
                                        artist = artist,
                                        tracks = page.tracks,
                                        isLoading = page.isLoading,
                                        currentId = playback.track?.id,
                                        onBack = controller::closeArtist,
                                        onPlay = playFromQueue,
                                    )
                                }
                                LazerMainPageKind.PLAYLIST -> page.playlist?.let { playlist ->
                                    PlaylistDetail(
                                        playlist = playlist,
                                        tracks = page.tracks,
                                        isLoading = page.isLoading,
                                        currentId = playback.track?.id,
                                        onBack = controller::closePlaylist,
                                        onPlay = playFromQueue,
                                    )
                                }
                                LazerMainPageKind.ROOT -> Unit
                            }
                        }
                    }
                }
                if (!landscape) {
                    playback.track?.let { track ->
                        MiniPlayer(track, playback.isPlaying, playback.isPreparing, { playerVisible = true }) {
                            controller.player.toggle()
                        }
                    }
                    BottomDock(
                        selected = controller.destination,
                        onSelect = controller::selectDestination,
                    )
                }
            }
            }

            if (landscape) {
                LandscapeNavigationRail(
                    controller = controller,
                    onScan = launchScanner,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
            if (landscape) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(start = if (landscape) 64.dp else 0.dp)
                    .then(if (landscape) Modifier.navigationBarsPadding() else Modifier)) {
                    playback.track?.let { track ->
                        MiniPlayer(
                            track = track,
                            isPlaying = playback.isPlaying,
                            isPreparing = playback.isPreparing,
                            onOpen = { playerVisible = true },
                            compact = landscape,
                            onToggle = { controller.player.toggle() },
                        )
                    }
                }
            }

            controller.message?.let { text ->
                MessageBanner(
                    text = text,
                    modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(16.dp),
                )
            }
            rootMessage?.let { text ->
                MessageBanner(
                    text = text,
                    modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(16.dp),
                )
            }
            AnimatedVisibility(
                visible = playerVisible && playback.track != null,
                modifier = Modifier.fillMaxSize(),
                enter = slideInVertically(
                    animationSpec = tween(PAGE_TRANSITION_MILLIS, easing = LazerMotionEasing),
                    initialOffsetY = { height -> height / 8 },
                ) + fadeIn(tween(PAGE_TRANSITION_MILLIS, easing = LazerMotionEasing)),
                exit = slideOutVertically(
                    animationSpec = tween(PAGE_TRANSITION_MILLIS, easing = LazerMotionEasing),
                    targetOffsetY = { height -> height / 8 },
                ) + fadeOut(tween(PAGE_TRANSITION_MILLIS, easing = LazerMotionEasing)),
                label = "now-playing-page",
            ) {
                NowPlayingPage(
                    snapshot = controller.player.snapshot.collectAsState().value,
                    lyricLines = controller.lyrics,
                    lyricsLoading = controller.lyricsLoading,
                    lyricsMessage = controller.lyricsMessage,
                    lyricFollowDelayMillis = controller.lyricFollowDelayMillis,
                    lyricAnimationSpeed = controller.lyricAnimationSpeed,
                    wordLyricsEnabled = controller.wordLyricsEnabled,
                    lyricGlowEnabled = controller.lyricGlowEnabled,
                    lyricFontSizeSp = controller.lyricFontSizeSp,
                    showFullLyrics = controller.showFullLyrics,
                    animateAlbumBackground = controller.backgroundMode == LazerBackgroundMode.NOW_PLAYING_DYNAMIC,
                    solidAlbumBackground = controller.backgroundMode == LazerBackgroundMode.NOW_PLAYING_STATIC,
                    isLiked = playback.track?.let { controller.isSongLiked(it.id) } == true,
                    onToggleLiked = { playback.track?.let(controller::toggleSongLiked) },
                    onDismiss = { playerVisible = false },
                    onToggle = { controller.player.toggle() },
                    onPrevious = { controller.player.previous() },
                    onNext = { controller.player.next() },
                    onSeek = controller::seekTo,
                    onOpenQueue = controller::openQueueSheet,
                    onOpenComments = controller::openSongComments,
                    modifier = Modifier
                        .fillMaxSize()
                        .predictiveBackTransform(
                            enabled = transformedBackLayer == LazerBackLayer.PLAYER,
                            progress = renderedBackProgress,
                            swipeEdge = backSwipeEdge,
                        ),
                )
            }
            if (controller.isListenTogetherVisible) {
                ListenTogetherSheet(
                    controller = controller,
                    onShare = shareListenTogether,
                )
            }
            if (controller.isQueueSheetVisible) {
                PlayQueueSheet(
                    controller = controller,
                    onDismiss = controller::closeQueueSheet,
                )
            }
            if (controller.isCommentSheetVisible) {
                SongCommentSheet(
                    controller = controller,
                    onDismiss = controller::closeSongComments,
                )
            }
            if (controller.isLoginVisible) LoginSheet(controller)
            pendingQrAuthorizationUrl?.let { url ->
                NeteaseQrAuthorizationSheet(
                    url = url,
                    sessionCookie = controller.currentSessionCookie.orEmpty(),
                    onDismiss = { pendingQrAuthorizationUrl = null },
                )
            }
            ArtistChoiceSheet(
                artists = artistChoices,
                onDismiss = { artistChoices = emptyList() },
                onChoose = { artist ->
                    artistChoices = emptyList()
                    playerVisible = false
                    controller.openArtist(artist)
                },
            )
            CoverSaveSheet(
                request = coverSaveRequest,
                onDismiss = { coverSaveRequest = null },
                onConfirm = { request ->
                    coverSaveRequest = null
                    coverSaveTarget = request
                    screen.pickExportDestination(lazerCoverFileName(request.title)) { target ->
                        val pending = coverSaveTarget
                        coverSaveTarget = null
                        if (target != null && pending != null) {
                            controller.saveArtwork(pending.url, target, pending.title) { written ->
                                written?.let(screen::onFileExported)
                            }
                        }
                    }
                },
            )
            CopyTextSheet(
                request = copyTextRequest,
                onDismiss = { copyTextRequest = null },
                onConfirm = { request ->
                    copyTextRequest = null
                    clipboardScope.launch {
                        copyTextToClipboard(clipboard, request.value, tr(request.titleKey))
                        rootMessage = tr("song.copy.done")
                    }
                },
            )
            if (screen.isDebugBuild) {
                LazerDebugWatermark(Modifier.fillMaxSize())
            }
        }
    }
}
}

@Composable
private fun LazerDebugWatermark(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val screen = LocalLazerScreenHost.current
    val watermarkText = remember(screen) {
        val model = screen.deviceLabel.ifBlank { "Unknown device" }
        val fingerprint = screen.deviceFingerprint.ifBlank { "Unknown fingerprint" }
        "DEBUG  ·  $model  ·  $fingerprint"
    }
    val textMeasurer = rememberTextMeasurer()
    val watermarkColor = colors.onBackground.copy(alpha = 0.06f)
    val watermarkLayout = remember(textMeasurer, watermarkText, watermarkColor) {
        textMeasurer.measure(
            text = AnnotatedString(watermarkText),
            style = TextStyle(
                color = watermarkColor,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.35.sp,
            ),
            overflow = TextOverflow.Visible,
            softWrap = false,
            maxLines = 1,
        )
    }
    Canvas(modifier.clearAndSetSemantics { }) {
        val stampWidth = watermarkLayout.size.width.toFloat()
        val horizontalStep = stampWidth + 72.dp.toPx()
        val verticalStep = 88.dp.toPx()
        val overscan = maxOf(size.width, size.height) * 0.45f
        rotate(degrees = -20f, pivot = center) {
            var rowIndex = 0
            var y = -overscan
            while (y <= size.height + overscan) {
                var x = -overscan - if (rowIndex % 2 == 0) 0f else horizontalStep / 2f
                while (x <= size.width + overscan) {
                    drawText(watermarkLayout, topLeft = Offset(x, y))
                    x += horizontalStep
                }
                rowIndex += 1
                y += verticalStep
            }
        }
    }
}

@Composable
private fun LazerRootContent(
    controller: LazerGatewayController,
    currentTrackId: Long?,
    onPlay: (List<LazerTrack>, LazerTrack) -> Unit,
    onListenTogether: () -> Unit,
    onScan: () -> Unit,
    showHeaderControls: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (!isLandscapeLayout()) {
            MobileHeader(
                controller = controller,
                showControls = showHeaderControls,
                onListenTogether = onListenTogether,
                onScan = onScan,
                modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 4.dp),
            )
        }
        AnimatedContent(
            targetState = controller.destination,
            modifier = Modifier.weight(1f),
            transitionSpec = {
                val movesForward = targetState.motionIndex > initialState.motionIndex
                val enter = slideInHorizontally(
                    animationSpec = tween(PAGE_TRANSITION_MILLIS, easing = LazerMotionEasing),
                    initialOffsetX = { width -> if (movesForward) width / 5 else -width / 5 },
                ) + fadeIn(tween(PAGE_TRANSITION_MILLIS, easing = LazerMotionEasing))
                val exit = slideOutHorizontally(
                    animationSpec = tween(PAGE_TRANSITION_MILLIS, easing = LazerMotionEasing),
                    targetOffsetX = { width -> if (movesForward) -width / 8 else width / 8 },
                ) + fadeOut(tween(PAGE_TRANSITION_MILLIS, easing = LazerMotionEasing))
                enter togetherWith exit
            },
            label = "lazer-root",
        ) { destination ->
            when (destination) {
                LazerRootDestination.HOME -> HomePage(controller, currentTrackId) { track ->
                    onPlay(controller.homeTracks, track)
                }
                LazerRootDestination.SEARCH -> SearchPage(controller, currentTrackId) { track ->
                    onPlay(controller.searchResults, track)
                }
                LazerRootDestination.LIBRARY -> LibraryPage(controller, currentTrackId) { track ->
                    onPlay(controller.homeTracks, track)
                }
                LazerRootDestination.ME -> MePage(controller)
            }
        }
    }
}

@Composable
private fun LandscapeNavigationRail(
    controller: LazerGatewayController,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxHeight().width(64.dp),
        color = colors.surface.copy(alpha = 0.90f),
        tonalElevation = 2.dp,
    ) {
        Column(
            Modifier.safeDrawingPadding().padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LazerRootDestination.entries.forEach { destination ->
                    val selectedDestination = !controller.isSettingsVisible &&
                        controller.activeArtist == null && controller.activePlaylist == null &&
                        controller.destination == destination
                    IconButton(
                        onClick = tapFeedback { controller.selectDestination(destination) },
                        modifier = Modifier.size(48.dp).semantics { selected = selectedDestination },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (selectedDestination) colors.primaryContainer else Color.Transparent,
                            contentColor = if (selectedDestination) colors.onPrimaryContainer else colors.onSurfaceVariant,
                        ),
                    ) { Icon(destination.icon(), destination.label) }
                }
            }
            IconButton(
                onClick = tapFeedback(onScan),
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.iconButtonColors(contentColor = colors.onSurfaceVariant),
            ) { Icon(Icons.Outlined.QrCodeScanner, tr("scan.open")) }
            IconButton(
                onClick = tapFeedback(controller::openListenTogether),
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (controller.listenTogether != null) colors.primary else colors.onSurfaceVariant,
                ),
            ) { Icon(Icons.Outlined.Headphones, tr("listen_together.open")) }
            IconButton(
                onClick = tapFeedback(controller::openSettings),
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (controller.isSettingsVisible) colors.primaryContainer else Color.Transparent,
                    contentColor = if (controller.isSettingsVisible) colors.onPrimaryContainer else colors.onSurfaceVariant,
                ),
            ) { Icon(Icons.Outlined.Settings, tr("player.open_settings")) }
        }
    }
}

@Composable
private fun HomePage(controller: LazerGatewayController, currentId: Long?, onPlay: (LazerTrack) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 12.dp + LocalLazerContentBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            SectionTitle(tr("home.section.title"), if (controller.isSignedIn) tr("home.section.signed") else tr("home.section.anon"))
            Spacer(Modifier.height(12.dp))
            PlaylistStrip(controller.featuredPlaylists, controller::openPlaylist)
        }
        item { SectionTitle(if (controller.isSignedIn) tr("home.daily") else tr("home.flowing")) }
        when {
            controller.isLoading && controller.homeTracks.isEmpty() -> item { QuietState(tr("home.preparing")) }
            controller.homeTracks.isEmpty() -> item { QuietState(tr("home.empty")) }
            else -> items(controller.homeTracks, key = LazerTrack::id) { TrackRow(it, it.id == currentId) { onPlay(it) } }
        }
    }
}

@Composable
private fun SearchPage(controller: LazerGatewayController, currentId: Long?, onPlay: (LazerTrack) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 12.dp + LocalLazerContentBottomInset.current),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text(tr("search.title"), style = MaterialTheme.typography.headlineMedium) }
            item {
                OutlinedTextField(
                    value = controller.searchQuery,
                    onValueChange = controller::updateSearchQuery,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    placeholder = { Text(tr("search.hint")) },
                    singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
            }
            when {
                controller.searchQuery.isBlank() -> item { QuietState(tr("search.empty")) }
                controller.isSearching -> item { QuietState(tr("search.searching")) }
                controller.searchResults.isEmpty() -> item { QuietState(tr("search.no_results")) }
                else -> items(controller.searchResults, key = LazerTrack::id) {
                    TrackRow(it, it.id == currentId) { onPlay(it) }
                }
            }
        }
    }
}

@Composable
private fun LibraryPage(controller: LazerGatewayController, currentId: Long?, onPlay: (LazerTrack) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 12.dp + LocalLazerContentBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(tr("library.title"), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                if (controller.isSignedIn) {
                    tr("library.tip.${controller.libraryTipIndex.coerceAtLeast(0) + 1}")
                } else {
                    tr("library.sub.anon")
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            !controller.isSignedIn -> item { SignInInvitation(controller::openLogin) }
            controller.userPlaylists.isEmpty() && controller.isLoading -> item { QuietState(tr("library.syncing")) }
            controller.userPlaylists.isEmpty() -> item { QuietState(tr("library.empty")) }
            else -> items(controller.userPlaylists, key = LazerPlaylist::id) { PlaylistListRow(it, controller::openPlaylist) }
        }
        if (controller.homeTracks.isNotEmpty()) {
            item { SectionTitle(tr("library.continue")) }
            items(controller.homeTracks.take(5), key = LazerTrack::id) { TrackRow(it, it.id == currentId) { onPlay(it) } }
        }
    }
}

@Composable
private fun MePage(controller: LazerGatewayController) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 12.dp + LocalLazerContentBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!controller.isSignedIn) {
            item {
                Text(tr("me.title"), style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(6.dp))
                Text(tr("me.sub"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item { SignInInvitation(controller::openLogin) }
        } else {
            val user = controller.currentUser!!
            item {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.68f)) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        MobileArtwork(normalizedArtworkUrl(user.avatarUrl), user.nickname, Modifier.size(64.dp), 32.dp)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(user.nickname.ifBlank { tr("me.my_music") }, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            user.signature?.takeIf(String::isNotBlank)?.let { signature ->
                                Spacer(Modifier.height(4.dp))
                                Text(signature, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.height(7.dp))
                            Text(tr("me.netease_id", user.userId), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.72f))
                        }
                    }
                }
            }
            item { SectionTitle(tr("me.my_playlists"), tr("me.playlist.sub")) }
            when {
                controller.userPlaylists.isEmpty() && controller.isLoading -> item { QuietState(tr("library.syncing")) }
                controller.userPlaylists.isEmpty() -> item { QuietState(tr("library.empty")) }
                else -> items(controller.userPlaylists.take(3), key = LazerPlaylist::id) { PlaylistListRow(it, controller::openPlaylist) }
            }
            item {
                ThemeTextButton(onClick = { controller.selectDestination(LazerRootDestination.LIBRARY) }) {
                    Text(tr("me.view_library"))
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(controller: LazerGatewayController, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val screen = LocalLazerScreenHost.current
    val toneState = screen.pcmTestToneState.collectAsState().value
    val usbTargetState = screen.usbAudioTargetSelection.collectAsState().value
    val usbUacState = screen.usbUacVolumeState.collectAsState().value
    val playbackSnapshot = screen.playbackSnapshot.collectAsState().value
    val audioSessionSnapshot = screen.audioSessionSnapshot.collectAsState().value
    val playbackOutput = playbackSnapshot.output
    val rendererInputFormat = playbackSnapshot.audioRendererInputFormat
    val toneFormats = toneState.availableFormats.ifEmpty {
        listOf(LazerPcmTestFormat(44_100, 16), LazerPcmTestFormat(48_000, 16))
    }
    var selectedToneFormat by remember(toneFormats) { mutableStateOf(toneFormats.first()) }
    val systemMonetAvailable = screen.supportsSystemPalette
    // Only Android hands mixing to an audio-focus model, and only there does bypassing the system
    // transport also mean playing alongside others. Where the platform works differently, the row
    // says what this switch actually does instead of borrowing another system's vocabulary.
    val platformCopySuffix = if (screen.usesSystemAudioFocus) "" else ".ios"
    // The platform asks for the microphone even though the capture only reads back our own session,
    // so the switch stays off until the user grants it.
    var microphoneGranted by remember { mutableStateOf(screen.microphoneGranted) }
    fun requestMicrophone() = screen.requestMicrophonePermission { granted ->
        microphoneGranted = granted
        if (granted) {
            controller.updateAudioReactiveLevels(true)
        } else {
            controller.reportAudioLevelsPermissionDenied()
        }
    }
    val audioLevelsEnabled = controller.audioReactiveLevels && microphoneGranted
    var isAudioQualitySheetVisible by remember { mutableStateOf(false) }
    var isEqualizerEditorVisible by remember { mutableStateOf(false) }
    var isCacheSheetVisible by remember { mutableStateOf(false) }
    var isCookieSheetVisible by remember { mutableStateOf(false) }
    var cookieCopied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val coroutineScope = rememberCoroutineScope()
    var followDelaySliderValue by remember(controller.lyricFollowDelayMillis) {
        mutableFloatStateOf(controller.lyricFollowDelayMillis.toFloat())
    }
    var lyricFontSizeSliderValue by remember(controller.lyricFontSizeSp) {
        mutableFloatStateOf(controller.lyricFontSizeSp.toFloat())
    }
    val displayedFollowDelay = normalizeLyricFollowDelayMillis(followDelaySliderValue.roundToLong())
    val displayedLyricFontSize = normalizeLyricFontSizeSp(lyricFontSizeSliderValue.roundToInt())
    val animationSpeedOptions = LyricAnimationSpeed.entries
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 18.dp + LocalLazerContentBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            if (isLandscapeLayout()) {
                Text(tr("settings.title"), style = MaterialTheme.typography.headlineSmall)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = tapFeedback(controller::closeSettings)) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("common.back"))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text(tr("settings.title"), style = MaterialTheme.typography.headlineSmall)
                }
            }
        }
        item { SectionTitle(tr("settings.appearance")) }
        item {
            SettingsCard {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.style"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            controller.style.label,
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    StyleDropdown(
                        selected = controller.style,
                        onSelected = controller::updateStyle,
                    )
                }
            }
        }
        item {
            SettingsCard {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.interface.title"), style = MaterialTheme.typography.titleSmall)
                        Text(if (controller.isDark) tr("settings.interface.dark") else tr("settings.interface.light"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    ThemeTextButton(onClick = controller::toggleTheme) {
                        Text(if (controller.isDark) tr("settings.interface.switch_light") else tr("settings.interface.switch_dark"))
                    }
                }
            }
        }
        item {
            val paletteOptions = buildList {
                add(LazerPalette.Default)
                if (systemMonetAvailable) add(LazerPalette.System)
                add(LazerPalette.NowPlaying)
                add(LazerPalette.Custom(LazerSeedSwatches.first()))
            }
            SettingsCard {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("settings.palette"), style = MaterialTheme.typography.titleSmall)
                            Text(
                                paletteLabel(controller.palette),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        SettingsDropdown(
                            options = paletteOptions,
                            selected = controller.palette,
                            label = ::paletteLabel,
                            onSelected = controller::updatePalette,
                        )
                    }
                    val custom = controller.palette
                    if (custom is LazerPalette.Custom) {
                        Spacer(Modifier.height(12.dp))
                        SeedColorPicker(
                            seed = custom.seed,
                            onSeedChange = { controller.updatePalette(LazerPalette.Custom(it)) },
                        )
                    }
                }
            }
        }
        item {
            SettingsCard {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.language"), style = MaterialTheme.typography.titleSmall)
                        Text(controller.language.displayName, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    LanguageDropdown(
                        selected = controller.language,
                        onSelected = controller::updateLanguage,
                    )
                }
            }
        }
        item {
            SettingsCard {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("settings.background"), style = MaterialTheme.typography.titleSmall)
                            Text(
                                backgroundModeHint(controller.backgroundMode),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        SettingsDropdown(
                            options = LazerBackgroundMode.entries,
                            selected = controller.backgroundMode,
                            label = ::backgroundModeLabel,
                            onSelected = { mode ->
                                controller.updateBackgroundMode(mode)
                                if (mode == LazerBackgroundMode.IMAGE && controller.backgroundImage == null) {
                                    screen.pickBackgroundImage { source -> source?.let(controller::setBackgroundImage) }
                                }
                            },
                        )
                    }

                    if (controller.backgroundMode == LazerBackgroundMode.IMAGE) {
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (controller.backgroundImage == null) {
                                    tr("settings.background.none")
                                } else {
                                    tr("settings.background.image.ready")
                                },
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                            ThemeTextButton(onClick = { screen.pickBackgroundImage { source -> source?.let(controller::setBackgroundImage) } }) {
                                Text(
                                    if (controller.backgroundImage == null) {
                                        tr("settings.background.pick")
                                    } else {
                                        tr("settings.background.change")
                                    },
                                )
                            }
                        }
                    }

                    val hasSelectedVisualBackground = when (controller.backgroundMode) {
                        LazerBackgroundMode.SOLID -> false
                        LazerBackgroundMode.IMAGE -> controller.backgroundImage != null
                        LazerBackgroundMode.NOW_PLAYING_DYNAMIC,
                        LazerBackgroundMode.NOW_PLAYING_STATIC -> true
                    }
                    if (hasSelectedVisualBackground) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("settings.background.surface_alpha"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            TapSlider(
                                engine = controller.themeEngine,
                                value = controller.backgroundAlpha,
                                onValueChange = controller::updateBackgroundAlpha,
                                valueRange = 0f..1f,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("${(controller.backgroundAlpha * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = colors.primary)
                        }
                    }

                    if (controller.backgroundMode == LazerBackgroundMode.IMAGE && controller.backgroundImage != null) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .tapClickable(role = Role.Switch) {
                                    controller.updateBackgroundImageBlurEnabled(!controller.backgroundImageBlurEnabled)
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(tr("settings.background.blur"), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    tr("settings.background.blur.hint"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            LazerSwitch(
                                engine = controller.themeEngine,
                                checked = controller.backgroundImageBlurEnabled,
                                onCheckedChange = null,
                            )
                        }
                        if (controller.backgroundImageBlurEnabled) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tr("settings.background.blur.strength"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                                Spacer(Modifier.width(12.dp))
                                TapSlider(
                                    engine = controller.themeEngine,
                                    value = controller.backgroundImageBlurIntensity,
                                    onValueChange = controller::updateBackgroundImageBlurIntensity,
                                    valueRange = 0f..1f,
                                    modifier = Modifier.weight(1f),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${(controller.backgroundImageBlurIntensity * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.primary,
                                )
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            ThemeTextButton(onClick = controller::clearBackgroundImage) {
                                Text(tr("settings.background.clear"), color = colors.error)
                            }
                        }
                    }
                }
            }
        }
        item { SectionTitle(tr("settings.playback")) }
        item {
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tapClickable(role = Role.Button) { isAudioQualitySheetVisible = true }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.quality.title"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            tr("settings.quality.hint", controller.audioQuality.description),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Text(
                        controller.audioQuality.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.primary,
                    )
                }
            }
        }
        item {
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tapClickable(role = Role.Switch) {
                            controller.updateIndependentPlayback(!controller.independentPlayback)
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.independent.title"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (controller.independentPlayback) {
                                tr("settings.independent.on$platformCopySuffix")
                            } else {
                                tr("settings.independent.off$platformCopySuffix")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    LazerSwitch(
                        engine = controller.themeEngine,
                        checked = controller.independentPlayback,
                        onCheckedChange = null,
                    )
                }
            }
        }
        item {
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tapClickable(
                            enabled = !controller.independentPlayback,
                            role = Role.Switch,
                        ) { controller.updateExclusiveAudio(!controller.exclusiveAudio) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            tr("settings.exclusive.title$platformCopySuffix"),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            if (controller.independentPlayback) {
                                tr("settings.exclusive.independent$platformCopySuffix")
                            } else if (controller.exclusiveAudio) {
                                tr("settings.exclusive.on$platformCopySuffix")
                            } else if (screen.usesSystemAudioFocus) {
                                tr("settings.exclusive.off.android")
                            } else {
                                tr("settings.exclusive.off$platformCopySuffix")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    LazerSwitch(
                        engine = controller.themeEngine,
                        checked = controller.exclusiveAudio && !controller.independentPlayback,
                        onCheckedChange = null,
                        enabled = !controller.independentPlayback,
                    )
                }
            }
        }
        if (screen.supportsUsbAudioTargetSelection) {
            item {
                SettingsCard {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(tr("settings.hifi.usb_output.title"), style = MaterialTheme.typography.titleSmall)
                        val connectedTarget = usbTargetState.connectedTargets.singleOrNull {
                            it.id == usbTargetState.selectedTargetId
                        }
                        val unavailableTarget = usbTargetState.selectedTargetId?.let { selectedId ->
                            if (connectedTarget != null) null else {
                                LazerUsbAudioTargetOption(
                                    selectedId,
                                    "${usbTargetState.selectedTargetLabel ?: tr("settings.hifi.usb_output.saved_target")} · ${tr(if (usbTargetState.selectedTargetAmbiguous) "settings.hifi.usb_output.ambiguous" else "settings.hifi.usb_output.disconnected")}",
                                )
                            }
                        }
                        val selectedTarget = connectedTarget ?: unavailableTarget
                        val targetOptions: List<LazerUsbAudioTargetOption?> =
                            listOf(null) + usbTargetState.connectedTargets + listOfNotNull(unavailableTarget)
                        SettingsDropdown(
                            options = targetOptions,
                            selected = selectedTarget,
                            label = { option -> option?.label ?: tr("settings.hifi.usb_output.automatic") },
                            onSelected = { screen.selectUsbAudioTarget(it?.id) },
                        )
                        Text(
                            tr("settings.hifi.usb_output.hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (screen.supportsUsbUacVolumeDiagnostics) {
            item {
                SettingsCard {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(tr("settings.hifi.usb_uac.title"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            tr("settings.hifi.usb_uac.independent_route"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            tr("settings.hifi.usb_uac.shared_device"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        val selectedUacDevice = usbUacState.devices.singleOrNull {
                            it.id == usbUacState.selectedDeviceId
                        }
                        val uacDeviceOptions: List<LazerUsbUacDeviceOption?> =
                            listOf(null) + usbUacState.devices
                        SettingsDropdown(
                            options = uacDeviceOptions,
                            selected = selectedUacDevice,
                            label = { option -> option?.label ?: tr("settings.hifi.usb_uac.choose_device") },
                            onSelected = { screen.selectUsbUacDevice(it?.id) },
                        )
                        val operationRunning = usbUacState.status == LazerUsbUacVolumeStatus.AwaitingPermission ||
                            usbUacState.status == LazerUsbUacVolumeStatus.Reading
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ThemeTextButton(
                                onClick = screen::refreshUsbUacDevices,
                                enabled = !operationRunning,
                            ) { Text(tr("settings.hifi.usb_uac.refresh")) }
                            Spacer(Modifier.weight(1f))
                            ThemeTextButton(
                                onClick = screen::readUsbUacVolume,
                                enabled = selectedUacDevice != null && !operationRunning,
                            ) { Text(tr(if (operationRunning) "settings.hifi.usb_uac.working" else "settings.hifi.usb_uac.read")) }
                        }
                        val statusKey = when (usbUacState.status) {
                            LazerUsbUacVolumeStatus.Idle -> null
                            LazerUsbUacVolumeStatus.AwaitingPermission -> "settings.hifi.usb_uac.permission_prompt"
                            LazerUsbUacVolumeStatus.Reading -> "settings.hifi.usb_uac.reading"
                            LazerUsbUacVolumeStatus.Ready -> "settings.hifi.usb_uac.ready"
                            LazerUsbUacVolumeStatus.Unsupported -> "settings.hifi.usb_uac.unsupported"
                            LazerUsbUacVolumeStatus.PermissionDenied -> "settings.hifi.usb_uac.permission_denied"
                            LazerUsbUacVolumeStatus.Failed -> usbUacState.detail ?: "settings.hifi.usb_uac.transfer_failed"
                        }
                        statusKey?.let {
                            Text(tr(it), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        if (usbUacState.status == LazerUsbUacVolumeStatus.Ready) {
                            val volumeText = if (usbUacState.muted) {
                                tr("settings.hifi.usb_uac.muted")
                            } else {
                                usbUacState.currentDb256?.let(::formatUsbUacDb256)
                                    ?: tr("settings.hifi.usb_uac.unknown")
                            }
                            Text(
                                tr("settings.hifi.usb_uac.current", volumeText),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val rangeLabel = when {
                                usbUacState.ranges.isEmpty() -> tr("settings.hifi.usb_uac.range_unknown")
                                usbUacState.ranges.size == 1 -> {
                                    val range = usbUacState.ranges.single()
                                    tr(
                                        "settings.hifi.usb_uac.range",
                                        formatUsbUacDb256(range.minimumDb256),
                                        formatUsbUacDb256(range.maximumDb256),
                                        formatUsbUacDb256(range.resolutionDb256),
                                    )
                                }
                                else -> tr("settings.hifi.usb_uac.range_count", usbUacState.ranges.size.toString())
                            }
                            Text(rangeLabel, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ThemeTextButton(
                                    onClick = { screen.adjustUsbUacVolume(increase = false) },
                                    enabled = usbUacState.canDecrease && !operationRunning,
                                ) { Text(tr("settings.hifi.usb_uac.down")) }
                                Spacer(Modifier.weight(1f))
                                ThemeTextButton(
                                    onClick = { screen.adjustUsbUacVolume(increase = true) },
                                    enabled = usbUacState.canIncrease && !operationRunning,
                                ) { Text(tr("settings.hifi.usb_uac.up")) }
                            }
                            Text(
                                tr("settings.hifi.usb_uac.step_hint"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
        if (screen.supportsPcmTestTone) {
            item {
                SettingsCard {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (screen.supportsLocalReplayGain) {
                            val replayGainMode = controller.replayGainMode
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(tr("settings.replay_gain.title"), style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        tr("settings.replay_gain.hint"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                                Spacer(Modifier.width(12.dp))
                                SettingsDropdown(
                                    options = LazerReplayGainMode.entries,
                                    selected = replayGainMode,
                                    label = { mode -> tr("settings.replay_gain.mode.${mode.name.lowercase()}") },
                                    onSelected = controller::updateReplayGainMode,
                                )
                            }
                        }
                        val equalizer = controller.equalizer
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .tapClickable(role = Role.Switch) {
                                    controller.updateEqualizer(equalizer.copy(enabled = !equalizer.enabled))
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(tr("settings.equalizer.title"), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (equalizer.enabled) tr("settings.equalizer.on") else tr("settings.equalizer.off"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            LazerSwitch(
                                engine = controller.themeEngine,
                                checked = equalizer.enabled,
                                onCheckedChange = null,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .tapClickable(role = Role.Switch) {
                                    controller.updateEqualizer(equalizer.copy(limiterEnabled = !equalizer.limiterEnabled))
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(tr("settings.equalizer.limiter"), style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    tr("settings.equalizer.limiter_hint"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            LazerSwitch(
                                engine = controller.themeEngine,
                                checked = equalizer.limiterEnabled,
                                onCheckedChange = null,
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                tr("settings.equalizer.preset"),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                            val selectedPreset = LazerEqualizerPresets.firstOrNull {
                                it.id == equalizer.matchingPresetId()
                            }
                            SettingsDropdown(
                                options = listOf<LazerEqualizerPreset?>(null) + LazerEqualizerPresets,
                                selected = selectedPreset,
                                label = { preset ->
                                    preset?.let { tr("settings.equalizer.preset.${it.id}") }
                                        ?: tr("settings.equalizer.custom")
                                },
                                onSelected = { preset ->
                                    preset?.let {
                                        controller.updateEqualizer(equalizer.activatePreset(it))
                                    }
                                },
                            )
                            ThemeTextButton(onClick = { isEqualizerEditorVisible = true }) {
                                Text(tr("settings.equalizer.edit"))
                            }
                        }
                        Text(
                            tr("settings.equalizer.android_output_note"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (screen.supportsPcmTestTone) {
            item {
                SettingsCard {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(tr("settings.hifi.test_tone.title"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            tr("settings.hifi.test_tone.hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SettingsDropdown(
                                options = toneFormats,
                                selected = selectedToneFormat,
                                label = { format ->
                                    tr("settings.hifi.test_tone.format", format.sampleRateHz.toString(), format.bitDepth.toString())
                                },
                                onSelected = { selectedToneFormat = it },
                            )
                            Spacer(Modifier.weight(1f))
                            val toneIsActive = toneState.status == LazerPcmTestToneStatus.Preparing ||
                                toneState.status == LazerPcmTestToneStatus.Playing
                            ThemeTextButton(
                                onClick = {
                                    if (toneIsActive) screen.stopPcmTestTone()
                                    else screen.playPcmTestTone(selectedToneFormat)
                                },
                            ) {
                                Text(tr(if (toneIsActive) "settings.hifi.test_tone.stop" else "settings.hifi.test_tone.play"))
                            }
                        }
                        val statusText = when (toneState.status) {
                            LazerPcmTestToneStatus.Idle -> null
                            LazerPcmTestToneStatus.Preparing -> tr("settings.hifi.test_tone.preparing")
                            LazerPcmTestToneStatus.Playing -> tr("settings.hifi.test_tone.playing")
                            LazerPcmTestToneStatus.Completed -> tr("settings.hifi.test_tone.completed")
                            LazerPcmTestToneStatus.Failed -> tr(toneState.detailKey ?: "settings.hifi.test_tone.failed")
                        }
                        statusText?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        }
                        Text(
                            tr("settings.hifi.test_tone.route", tr(pcmTestToneRouteKey(toneState.route))),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            tr("settings.hifi.test_tone.usb_count", toneState.connectedUsbOutputs.toString()),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            tr(pcmMixerPreferenceKey(toneState.mixerPreference)),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            tr("settings.hifi.test_tone.loopback_note"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (screen.supportsPcmTestTone && rendererInputFormat != null) {
            item {
                SettingsCard {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            tr("settings.hifi.renderer_input.title"),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val inputDetails = buildList {
                            rendererInputFormat.sampleRateHz?.let { add("$it Hz") }
                            rendererInputFormat.encodingLabel?.let(::add)
                            rendererInputFormat.channelCount?.let { add("$it ch") }
                            rendererInputFormat.sampleMimeType?.let { add("MIME: $it") }
                        }
                        Text(
                            inputDetails.takeIf { it.isNotEmpty() }?.joinToString(" · ")
                                ?: tr("settings.hifi.renderer_input.unknown"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            tr("settings.hifi.renderer_input.note"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (screen.supportsPcmTestTone && playbackOutput != null) {
            item {
                SettingsCard {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(tr("settings.hifi.output.title"), style = MaterialTheme.typography.titleSmall)
                        val actualRate = playbackOutput.audioTrackSampleRateHz
                        Text(
                            if (actualRate == null) {
                                tr("settings.hifi.output.format.unknown")
                            } else {
                                tr(
                                    "settings.hifi.output.format",
                                    actualRate.toString(),
                                    playbackOutput.encodingLabel,
                                    playbackOutput.channelCount.toString(),
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            tr("settings.hifi.output.requested", playbackOutput.requestedSampleRateHz.toString()),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        playbackOutput.outputDataFormat?.let { dataFormat ->
                            Text(
                                tr("settings.hifi.output.data.title"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                            val dataDetails = if (dataFormat.isLinearPcm) {
                                tr(
                                    "settings.hifi.output.data.pcm",
                                    dataFormat.sampleRateHz?.toString() ?: "?",
                                    dataFormat.encodingLabel ?: "PCM",
                                    dataFormat.channelCount?.toString() ?: "?",
                                )
                            } else {
                                tr(
                                    "settings.hifi.output.data.non_pcm",
                                    dataFormat.sampleRateHz?.toString() ?: "?",
                                    dataFormat.channelCount?.toString() ?: "?",
                                )
                            }
                            Text(
                                dataDetails,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                            when {
                                dataFormat.offload -> tr("settings.hifi.output.data.offload")
                                dataFormat.tunneling -> tr("settings.hifi.output.data.tunneling")
                                else -> null
                            }?.let { modeDescription ->
                                Text(
                                    modeDescription,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            tr(
                                "settings.hifi.output.route",
                                playbackOutput.routedDeviceName?.takeIf(String::isNotBlank)
                                    ?: tr("settings.hifi.output.route.unknown"),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        val mixerStatus = when {
                            playbackOutput.mixerAdvertisesBitPerfectBehavior == true &&
                                playbackOutput.mixerPreferenceAccepted == true -> "accepted"
                            playbackOutput.mixerAdvertisesBitPerfectBehavior == true &&
                                playbackOutput.mixerPreferenceAccepted == false -> "rejected"
                            playbackOutput.mixerAdvertisesBitPerfectBehavior == false -> "not_advertised"
                            else -> "unavailable"
                        }
                        Text(
                            tr("settings.hifi.output.mixer.$mixerStatus"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        val dspStatus = when (playbackOutput.appDspMayModifySamples) {
                            true -> "active"
                            false -> "bypassed"
                            null -> "unavailable"
                        }
                        Text(
                            tr("settings.hifi.output.dsp.$dspStatus"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        playbackOutput.replayGainAppliedDb?.let { gainDb ->
                            Text(
                                tr("settings.hifi.output.replay_gain", formatEqualizerGain(gainDb)),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        val directPathStatus = when (playbackOutput.directPath.status) {
                            DirectPathStatus.NotRequested -> "not_requested"
                            DirectPathStatus.Eligible -> "eligible"
                            DirectPathStatus.Negotiated -> "negotiated"
                            DirectPathStatus.Rejected -> "rejected"
                            DirectPathStatus.Unknown -> "unknown"
                        }
                        val directPathReason = when (playbackOutput.directPath.reason) {
                            DirectPathReason.DigitalCaptureNotVerified -> "digital_capture"
                            DirectPathReason.SourceIsLossy,
                            DirectPathReason.DecoderChangedSamples,
                            DirectPathReason.SourceFormatUnknown,
                            -> "source_unknown"
                            DirectPathReason.DspEnabled -> "dsp"
                            DirectPathReason.SoftwareVolume,
                            DirectPathReason.SoftwareVolumeUnknown,
                            -> "software_volume"
                            DirectPathReason.SampleRateConversion,
                            DirectPathReason.BitDepthConversion,
                            DirectPathReason.ChannelLayoutConversion,
                            DirectPathReason.OutputFormatMismatch,
                            -> "format_mismatch"
                            DirectPathReason.MixerBehaviorNotAdvertised,
                            DirectPathReason.MixerPreferenceRejected,
                            -> "mixer"
                            DirectPathReason.UnsupportedEncoding,
                            DirectPathReason.DsdConvertedToPcm,
                            DirectPathReason.ExclusiveModeUnavailable,
                            -> "unsupported"
                            DirectPathReason.DeviceOrBackendUnknown,
                            DirectPathReason.Other,
                            null,
                            -> "output_unknown"
                        }
                        Text(
                            tr("settings.hifi.output.direct_path.title"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            tr("settings.hifi.output.direct_path.status.$directPathStatus"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            tr("settings.hifi.output.direct_path.reason.$directPathReason"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            tr("settings.hifi.output.note"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (screen.supportsAudioSessionSnapshot) {
            item {
                SettingsCard {
                    Column(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            tr("settings.hifi.ios_session.title"),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val statusKey = when {
                            audioSessionSnapshot.interrupted -> "interrupted"
                            audioSessionSnapshot.active -> "active"
                            else -> "inactive"
                        }
                        Text(
                            tr("settings.hifi.ios_session.status.$statusKey"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            tr(
                                "settings.hifi.ios_session.source_rate",
                                audioSessionSnapshot.sourceTrackSampleRateHz?.roundToInt()?.toString()
                                    ?: tr("settings.hifi.ios_session.unknown"),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            tr(
                                "settings.hifi.ios_session.preferred_rate",
                                audioSessionSnapshot.preferredSampleRateHz?.roundToInt()?.toString()
                                    ?: tr("settings.hifi.ios_session.unknown"),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            tr(
                                "settings.hifi.ios_session.rate",
                                audioSessionSnapshot.sampleRateHz?.roundToInt()?.toString()
                                    ?: tr("settings.hifi.ios_session.unknown"),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        Text(
                            tr(
                                "settings.hifi.ios_session.route",
                                audioSessionSnapshot.outputRouteName
                                    ?: tr("settings.hifi.ios_session.unknown"),
                                audioSessionSnapshot.outputPortTypes
                                    ?: tr("settings.hifi.ios_session.unknown"),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        audioSessionSnapshot.outputChannelCount?.let { channels ->
                            Text(
                                tr("settings.hifi.ios_session.channels", channels.toString()),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        audioSessionSnapshot.ioBufferDurationMillis?.let { duration ->
                            Text(
                                tr("settings.hifi.ios_session.buffer", duration.toString()),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        audioSessionSnapshot.configurationError?.let { detail ->
                            Text(
                                tr("settings.hifi.ios_session.error", detail),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.error,
                            )
                        }
                        Text(
                            tr("settings.hifi.ios_session.note"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (screen.supportsAudioSpectrum) {
            item {
                SettingsCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .tapClickable(role = Role.Switch) {
                                when {
                                    audioLevelsEnabled -> controller.updateAudioReactiveLevels(false)
                                    microphoneGranted -> controller.updateAudioReactiveLevels(true)
                                    else -> requestMicrophone()
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("settings.audio_levels.title"), style = MaterialTheme.typography.titleSmall)
                            Text(
                                tr("settings.audio_levels.subtitle"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        LazerSwitch(
                            engine = controller.themeEngine,
                            checked = audioLevelsEnabled,
                            onCheckedChange = null,
                        )
                    }
                }
            }
        }
        item {
            SettingsCard(
                modifier = Modifier.tapClickable(role = Role.Switch) {
                    controller.updateHapticsEnabled(!controller.hapticsEnabled)
                },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.haptic.title"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            tr("settings.haptic.hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    LazerSwitch(
                        engine = controller.themeEngine,
                        checked = controller.hapticsEnabled,
                        onCheckedChange = null,
                    )
                }
            }
        }
        item { SectionTitle(tr("settings.lyrics")) }
        item {
            SettingsCard(
                modifier = Modifier.tapClickable(role = Role.Switch) {
                    controller.updateWordLyricsEnabled(!controller.wordLyricsEnabled)
                },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.word.title"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (controller.wordLyricsEnabled) tr("settings.word.on") else tr("settings.word.off"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    LazerSwitch(
                        engine = controller.themeEngine,
                        checked = controller.wordLyricsEnabled,
                        onCheckedChange = null,
                    )
                }
            }
        }
        item {
            SettingsCard(
                modifier = Modifier.tapClickable(role = Role.Switch) {
                    controller.updateLyricGlowEnabled(!controller.lyricGlowEnabled)
                },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.lyric.glow.title"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (controller.lyricGlowEnabled) tr("settings.lyric.glow.on") else tr("settings.lyric.glow.off"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    LazerSwitch(
                        engine = controller.themeEngine,
                        checked = controller.lyricGlowEnabled,
                        onCheckedChange = null,
                    )
                }
            }
        }
        item {
            SettingsCard {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("settings.lyric.speed.title"), style = MaterialTheme.typography.titleSmall)
                            Text(
                                tr("settings.lyric.speed.hint"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Text(
                            controller.lyricAnimationSpeed.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary,
                        )
                    }
                    TapSlider(
                        engine = controller.themeEngine,
                        value = controller.lyricAnimationSpeed.ordinal.toFloat(),
                        onValueChange = { value ->
                            controller.updateLyricAnimationSpeed(
                                animationSpeedOptions[value.roundToInt().coerceIn(animationSpeedOptions.indices)],
                            )
                        },
                        valueRange = 0f..animationSpeedOptions.lastIndex.toFloat(),
                        steps = animationSpeedOptions.size - 2,
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            animationSpeedOptions.first().label,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            LyricAnimationSpeed.STANDARD.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            animationSpeedOptions.last().label,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            SettingsCard {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("settings.lyric.font.title"), style = MaterialTheme.typography.titleSmall)
                            Text(
                                tr("settings.lyric.font.hint"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Text(
                            lyricFontSizeLabel(displayedLyricFontSize),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary,
                        )
                    }
                    TapSlider(
                        engine = controller.themeEngine,
                        value = lyricFontSizeSliderValue,
                        onValueChange = {
                            lyricFontSizeSliderValue = normalizeLyricFontSizeSp(it.roundToInt()).toFloat()
                        },
                        onValueChangeFinished = {
                            controller.updateLyricFontSizeSp(displayedLyricFontSize)
                        },
                        valueRange = MIN_LYRIC_FONT_SIZE_SP.toFloat()..MAX_LYRIC_FONT_SIZE_SP.toFloat(),
                        steps = LYRIC_FONT_SIZE_OPTIONS_SP.size - 2,
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            tr("settings.lyric.font.small"),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            tr("settings.lyric.font.large"),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item {
            SettingsCard(
                modifier = Modifier.tapClickable(role = Role.Switch) {
                    controller.updateShowFullLyrics(!controller.showFullLyrics)
                },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.lyric.full.title"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (controller.showFullLyrics) {
                                tr("settings.lyric.full.on")
                            } else {
                                tr("settings.lyric.full.off")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    LazerSwitch(
                        engine = controller.themeEngine,
                        checked = controller.showFullLyrics,
                        onCheckedChange = null,
                    )
                }
            }
        }
        item {
            SettingsCard {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("settings.lyric.follow.title"), style = MaterialTheme.typography.titleSmall)
                            Text(
                                tr("settings.lyric.follow.hint"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Text(
                            lyricFollowDelayLabel(displayedFollowDelay),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.primary,
                        )
                    }
                    TapSlider(
                        engine = controller.themeEngine,
                        value = followDelaySliderValue,
                        onValueChange = {
                            followDelaySliderValue = normalizeLyricFollowDelayMillis(it.roundToLong()).toFloat()
                        },
                        onValueChangeFinished = {
                            controller.updateLyricFollowDelay(displayedFollowDelay)
                        },
                        valueRange = MIN_LYRIC_FOLLOW_DELAY_MILLIS.toFloat()..MAX_LYRIC_FOLLOW_DELAY_MILLIS.toFloat(),
                        steps = LYRIC_FOLLOW_DELAY_OPTIONS_MILLIS.size - 2,
                    )
                    Row(Modifier.fillMaxWidth()) {
                        Text(
                            lyricFollowDelayLabel(MIN_LYRIC_FOLLOW_DELAY_MILLIS),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            lyricFollowDelayLabel(MAX_LYRIC_FOLLOW_DELAY_MILLIS),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        item { SectionTitle(tr("settings.storage")) }
        item {
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tapClickable(role = Role.Button) { isCacheSheetVisible = true }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.cache.title"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            tr("settings.cache.hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Text(tr("settings.cache.select"), style = MaterialTheme.typography.labelLarge, color = colors.primary)
                }
            }
        }
        item {
            SettingsCard {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.resync.title"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            tr("settings.resync.hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    ThemeButton(onClick = controller::forceResync, enabled = !controller.isLoading) {
                        Text(if (controller.isLoading) tr("settings.resync.doing") else tr("settings.resync.action"))
                    }
                }
            }
        }
        item { SectionTitle(tr("settings.account")) }
        item {
            SettingsCard(
                modifier = Modifier.tapClickable(role = Role.Button) {
                    cookieCopied = false
                    isCookieSheetVisible = true
                },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.cookie.title"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            tr("settings.cookie.hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Text(
                        tr("settings.cookie.read"),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.primary,
                    )
                }
            }
        }
        if (controller.isSignedIn) {
            val user = controller.currentUser!!
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    MobileArtwork(normalizedArtworkUrl(user.avatarUrl), user.nickname, Modifier.size(42.dp), 21.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(user.nickname.ifBlank { tr("settings.account.signed.fallback") }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(tr("settings.account.signed"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            item {
                ThemeTextButton(onClick = controller::logout) {
                    Text(tr("settings.account.logout"), color = colors.error)
                }
            }
        } else {
            item { SignInInvitation(controller::openLogin) }
        }
        item {
            SettingsCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .tapClickable { controller.openAbout() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("about.title"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            tr("about.version", LazerRelease.versionName, LazerRelease.versionCode),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    if (isAudioQualitySheetVisible) {
        AudioQualitySheet(
            selected = controller.audioQuality,
            onSelected = {
                controller.updateAudioQuality(it)
                isAudioQualitySheetVisible = false
            },
            onDismiss = { isAudioQualitySheetVisible = false },
        )
    }
    if (isEqualizerEditorVisible) {
        LazerEqualizerEditorDialog(
            controller = controller,
            initialState = controller.equalizer,
            onDismiss = { isEqualizerEditorVisible = false },
        )
    }
    if (isCacheSheetVisible) {
        CacheChoiceSheet(
            onClearSongs = {
                controller.clearSongCache()
                isCacheSheetVisible = false
            },
            onClearPlaylists = {
                controller.clearPlaylistCache()
                isCacheSheetVisible = false
            },
            onDismiss = { isCacheSheetVisible = false },
        )
    }
    if (isCookieSheetVisible) {
        CurrentCookieSheet(
            cookie = controller.currentSessionCookie,
            copied = cookieCopied,
            onCopy = { cookie ->
                coroutineScope.launch {
                    copyTextToClipboard(clipboard, cookie, tr("login.cookie.label"))
                    cookieCopied = true
                }
            },
            onDismiss = { isCookieSheetVisible = false },
        )
    }
}

@Composable
private fun LazerEqualizerEditorDialog(
    controller: LazerGatewayController,
    initialState: LazerEqualizerState,
    onDismiss: () -> Unit,
) {
    var draft by remember(initialState) { mutableStateOf(initialState) }
    val colors = MaterialTheme.colorScheme

    fun preview(next: LazerEqualizerState) {
        draft = next
        controller.previewEqualizer(next)
    }

    fun commit(next: LazerEqualizerState) {
        draft = next
        controller.updateEqualizer(next)
    }

    fun finishEditing() {
        controller.updateEqualizer(draft)
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::finishEditing,
        title = { Text(tr("settings.equalizer.title")) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp)
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    tr("settings.equalizer.hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tapClickable(role = Role.Switch) {
                            commit(draft.copy(enabled = !draft.enabled))
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.equalizer.enable"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (draft.enabled) tr("settings.equalizer.on") else tr("settings.equalizer.off"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    LazerSwitch(
                        engine = controller.themeEngine,
                        checked = draft.enabled,
                        onCheckedChange = null,
                    )
                }
                HorizontalDivider()
                EqualizerGainSliderRow(
                    label = tr("settings.equalizer.preamp"),
                    gainDb = draft.preampDb,
                    engine = controller.themeEngine,
                    onPreview = { preview(draft.copy(preampDb = clampLazerEqPreampDb(it))) },
                    onCommit = { commit(draft.copy(preampDb = clampLazerEqPreampDb(it))) },
                    valueRange = MIN_LAZER_EQ_PREAMP_DB.toFloat()..MAX_LAZER_EQ_PREAMP_DB.toFloat(),
                )
                HorizontalDivider()
                draft.bands.forEachIndexed { index, band ->
                    EqualizerGainSliderRow(
                        label = lazerEqFrequencyLabel(band.frequencyHz),
                        gainDb = band.gainDb,
                        engine = controller.themeEngine,
                        onPreview = { preview(draft.withBandGain(index, it)) },
                        onCommit = { commit(draft.withBandGain(index, it)) },
                        valueRange = MIN_LAZER_EQ_GAIN_DB.toFloat()..MAX_LAZER_EQ_GAIN_DB.toFloat(),
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .tapClickable(role = Role.Switch) {
                            commit(draft.copy(limiterEnabled = !draft.limiterEnabled))
                        }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.equalizer.limiter"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            tr("settings.equalizer.limiter_hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    LazerSwitch(
                        engine = controller.themeEngine,
                        checked = draft.limiterEnabled,
                        onCheckedChange = null,
                    )
                }
            }
        },
        confirmButton = {
            ThemeTextButton(onClick = ::finishEditing) { Text(tr("settings.equalizer.close")) }
        },
        dismissButton = {
            ThemeTextButton(
                onClick = { commit(LazerEqualizerState(enabled = draft.enabled)) },
            ) { Text(tr("settings.equalizer.reset")) }
        },
    )
}

@Composable
private fun EqualizerGainSliderRow(
    label: String,
    gainDb: Double,
    engine: LazerThemeEngine,
    onPreview: (Double) -> Unit,
    onCommit: (Double) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
) {
    var local by remember(gainDb) { mutableFloatStateOf(gainDb.toFloat()) }
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(52.dp),
        )
        TapSlider(
            engine = engine,
            value = local,
            onValueChange = {
                local = it
                onPreview(it.toDouble())
            },
            onValueChangeFinished = { onCommit(local.toDouble()) },
            valueRange = valueRange,
            modifier = Modifier.weight(1f).semantics { contentDescription = label },
        )
        Text(
            formatEqualizerGain(local.toDouble()),
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(58.dp),
        )
    }
}

private fun lazerEqFrequencyLabel(frequencyHz: Double): String =
    if (frequencyHz >= 1_000.0) "${(frequencyHz / 1_000.0).toInt()} kHz" else "${frequencyHz.toInt()} Hz"

private fun formatEqualizerGain(gainDb: Double): String {
    val rounded = (gainDb * 10.0).roundToInt() / 10.0
    val value = if (rounded == 0.0) 0.0 else rounded
    val sign = if (value > 0.0) "+" else ""
    return "$sign$value dB"
}

/** What this build is, and what it stands on. Reached from the foot of the settings list. */
@Composable
private fun AboutPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 28.dp + LocalLazerContentBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IconButton(onClick = tapFeedback(onBack)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("common.back"))
        }
        LazerAboutSection()
    }
}

@Composable
private fun <T> SettingsDropdown(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    Box {
        ThemeTextButton(onClick = { expanded = true }) {
            Text(label(selected), color = colors.primary)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.ArrowDropDown, null, Modifier.size(18.dp), tint = colors.primary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                val select: () -> Unit = {
                    onSelected(option)
                    expanded = false
                }
                DropdownMenuItem(
                    text = {
                        Text(
                            label(option),
                            color = if (option == selected) colors.primary else colors.onSurface,
                        )
                    },
                    onClick = tapFeedback(select),
                )
            }
        }
    }
}

private fun paletteLabel(palette: LazerPalette): String = when (palette) {
    LazerPalette.Default -> tr("settings.palette.default")
    LazerPalette.System -> tr("settings.palette.system")
    LazerPalette.NowPlaying -> tr("settings.palette.now_playing")
    is LazerPalette.Custom -> tr("settings.palette.custom")
}

private fun pcmTestToneRouteKey(route: LazerPcmTestToneRoute): String = when (route) {
    LazerPcmTestToneRoute.Unknown -> "settings.hifi.test_tone.route.unknown"
    LazerPcmTestToneRoute.Usb -> "settings.hifi.test_tone.route.usb"
    LazerPcmTestToneRoute.Wired -> "settings.hifi.test_tone.route.wired"
    LazerPcmTestToneRoute.Bluetooth -> "settings.hifi.test_tone.route.bluetooth"
    LazerPcmTestToneRoute.BuiltIn -> "settings.hifi.test_tone.route.built_in"
    LazerPcmTestToneRoute.Hdmi -> "settings.hifi.test_tone.route.hdmi"
    LazerPcmTestToneRoute.Other -> "settings.hifi.test_tone.route.other"
}

private fun formatUsbUacDb256(value: Int): String {
    val magnitude = if (value < 0) -value.toLong() else value.toLong()
    val thousandths = (magnitude * 1_000 + 128) / 256
    val whole = thousandths / 1_000
    val fraction = (thousandths % 1_000).toString().padStart(3, '0')
    val sign = if (value < 0) "−" else "+"
    return "$sign$whole.$fraction dB"
}

private fun pcmMixerPreferenceKey(status: LazerMixerPreferenceStatus): String = when (status) {
    LazerMixerPreferenceStatus.NotAvailable -> "settings.hifi.test_tone.mixer.unavailable"
    LazerMixerPreferenceStatus.NoExactMatch -> "settings.hifi.test_tone.mixer.no_exact_format"
    LazerMixerPreferenceStatus.NotBitPerfect -> "settings.hifi.test_tone.mixer.not_bit_perfect"
    LazerMixerPreferenceStatus.Accepted -> "settings.hifi.test_tone.mixer.accepted"
    LazerMixerPreferenceStatus.Rejected -> "settings.hifi.test_tone.mixer.rejected"
}

private fun backgroundModeLabel(mode: LazerBackgroundMode): String = when (mode) {
    LazerBackgroundMode.SOLID -> tr("settings.background.mode.solid")
    LazerBackgroundMode.IMAGE -> tr("settings.background.mode.image")
    LazerBackgroundMode.NOW_PLAYING_DYNAMIC -> tr("settings.background.mode.now_playing_dynamic")
    LazerBackgroundMode.NOW_PLAYING_STATIC -> tr("settings.background.mode.now_playing_static")
}

private fun backgroundModeHint(mode: LazerBackgroundMode): String = when (mode) {
    LazerBackgroundMode.SOLID -> tr("settings.background.hint.solid")
    LazerBackgroundMode.IMAGE -> tr("settings.background.hint.image")
    LazerBackgroundMode.NOW_PLAYING_DYNAMIC -> tr("settings.background.hint.now_playing_dynamic")
    LazerBackgroundMode.NOW_PLAYING_STATIC -> tr("settings.background.hint.now_playing_static")
}

@Composable
private fun StyleDropdown(selected: LazerStyle, onSelected: (LazerStyle) -> Unit) {
    SettingsDropdown(
        options = LazerStyle.entries,
        selected = selected,
        label = LazerStyle::label,
        onSelected = onSelected,
    )
}

@Composable
private fun LanguageDropdown(selected: LazerLanguage, onSelected: (LazerLanguage) -> Unit) {
    SettingsDropdown(
        options = LazerLanguage.entries,
        selected = selected,
        label = LazerLanguage::displayName,
        onSelected = onSelected,
    )
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun CacheChoiceSheet(
    onClearSongs: () -> Unit,
    onClearPlaylists: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
            Text(tr("settings.cache.dialog.title"), style = MaterialTheme.typography.headlineSmall)
            Text(
                tr("settings.cache.dialog.body.mobile"),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
            )
            ThemeTextButton(onClick = onClearSongs, modifier = Modifier.fillMaxWidth()) {
                Text(tr("settings.cache.clear.songs"), color = colors.error)
            }
            ThemeTextButton(onClick = onClearPlaylists, modifier = Modifier.fillMaxWidth()) {
                Text(tr("settings.cache.clear.playlists"), color = colors.error)
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun CurrentCookieSheet(
    cookie: String?,
    copied: Boolean,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(tr("settings.cookie.title"), style = MaterialTheme.typography.headlineSmall)
            Text(
                tr("settings.cookie.warning"),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            OutlinedTextField(
                value = cookie ?: tr("settings.cookie.empty"),
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                minLines = 3,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodySmall,
                shape = RoundedCornerShape(14.dp),
            )
            if (copied) {
                Text(
                    tr("settings.cookie.copied"),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.primary,
                )
            }
            if (cookie != null) {
                ThemeButton(
                    onClick = { onCopy(cookie) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(tr("settings.cookie.copy"))
                }
            }
            ThemeTextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(tr("login.close"))
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun AudioQualitySheet(
    selected: AudioQuality,
    onSelected: (AudioQuality) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.surface,
        contentColor = colors.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Text(
                tr("player.quality"),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
            Text(
                tr("settings.quality.sheet.hint"),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            lazerAudioQualityOptions.forEach { quality ->
                val isSelected = quality == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .tapSelectable(
                            selected = isSelected,
                            role = Role.RadioButton,
                            onClick = { onSelected(quality) },
                        )
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = null,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        quality.label,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isSelected) colors.primary else colors.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        quality.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private val LazerPlayMode.labelKey: String
    get() = when (this) {
        LazerPlayMode.ListLoop -> "player.mode.list_loop"
        LazerPlayMode.SingleLoop -> "player.mode.single_loop"
        LazerPlayMode.Shuffle -> "player.shuffle"
    }

/** Rows are a fixed height so a drag distance maps onto exactly one slot per row. Sized for
 * title + translated title + artist, which is the tallest a queue row can be. */
private val QueueRowHeight = 72.dp

/** The most room the queue sheet ever gives its list, on a screen tall enough to spare it. */
private val QueueListMaxHeight = 340.dp

/**
 * The two actions that sit on either side of the top of the seek bar. They are labelled rather
 * than icon-only because nothing else on the card explains what either one opens.
 */
@Composable
private fun PlayerCardAction(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val tapped = tapFeedback(onClick)
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .tapClickable(role = Role.Button, onClick = tapped)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun PlayerSheetHeader(
    imageVector: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String?,
) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(
            modifier = Modifier.size(44.dp),
            shape = CircleShape,
            color = colors.primaryContainer,
            contentColor = colors.onPrimaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector, null, Modifier.size(22.dp))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun PlayQueueSheet(
    controller: LazerGatewayController,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val screen = LocalLazerScreenHost.current
    val room = controller.listenTogether
    val queue by controller.player.queue.collectAsState()
    val snapshot by controller.player.snapshot.collectAsState()
    val inRoom = room != null
    val tracks = if (inRoom) controller.listenTogetherRoomQueue else queue.tracks
    val currentIndex = if (inRoom) {
        tracks.indexOfFirst { it.id == snapshot.track?.id }
    } else {
        queue.index
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = colors.surface,
        contentColor = colors.onSurface,
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // A list that only answers to a fixed ceiling is a problem on a short screen: the rows under
            // the sheet are laid out but can never be reached, so the list also takes the room the
            // sheet actually has.
            val listMaxHeight = minOf(QueueListMaxHeight, maxHeight * 0.6f).coerceAtLeast(120.dp)
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PlayerSheetHeader(
                    imageVector = Icons.AutoMirrored.Outlined.QueueMusic,
                    title = tr(if (inRoom) "player.queue.room_title" else "player.queue"),
                    subtitle = when {
                        inRoom && tracks.isEmpty() -> tr("player.queue.room_empty")
                        inRoom -> tr("player.queue.room_hint", tracks.size)
                        tracks.isEmpty() -> tr("player.queue.empty")
                        currentIndex >= 0 -> tr("player.queue.position", currentIndex + 1, tracks.size)
                        else -> null
                    },
                )
                if (!inRoom && screen.supportsLocalAudioFiles) {
                    Button(
                        onClick = tapFeedback {
                            screen.pickLocalAudioFiles(controller::handleLocalAudioPickerResult)
                        },
                    ) {
                        Icon(Icons.Outlined.LibraryMusic, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(tr("player.queue.open_local_audio"))
                    }
                }
                if (!inRoom && tracks.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tr("player.mode"),
                            style = MaterialTheme.typography.labelLarge,
                            color = colors.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        LazerPlayMode.entries.forEach { mode ->
                            FilterChip(
                                selected = queue.mode == mode,
                                onClick = tapFeedback { controller.setPlayMode(mode) },
                                label = { Text(tr(mode.labelKey)) },
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                    }
                }
                when {
                    tracks.isEmpty() -> Text(
                        tr("player.queue.empty_hint"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    inRoom -> StaticQueueList(tracks, currentIndex, snapshot.isPlaying, listMaxHeight)
                    else -> ReorderableQueueList(
                        tracks = tracks,
                        currentIndex = currentIndex,
                        isPlaying = snapshot.isPlaying,
                        maxHeight = listMaxHeight,
                        onPlayAt = controller::playQueueAt,
                        onRemoveAt = controller::removeFromQueue,
                        onMove = controller::moveInQueue,
                    )
                }
            }
        }
    }
}

@Composable
private fun StaticQueueList(
    tracks: List<LazerTrack>,
    currentIndex: Int,
    isPlaying: Boolean,
    maxHeight: Dp,
) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            QueueRowBody(
                index = index,
                track = track,
                current = index == currentIndex,
                isPlaying = isPlaying,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Drag is claimed from the handle only. Putting it on the whole row would compete with the tap
 * that jumps to a track, and which one wins depends on nothing the reader can see.
 */
@Composable
private fun ReorderableQueueList(
    tracks: List<LazerTrack>,
    currentIndex: Int,
    isPlaying: Boolean,
    maxHeight: Dp,
    onPlayAt: (Int) -> Unit,
    onRemoveAt: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val rowHeightPx = with(LocalDensity.current) { QueueRowHeight.toPx() }
    var draggedIndex by remember { mutableStateOf<Int?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dragged = draggedIndex
    val target = dragged?.let {
        (it + (dragOffset / rowHeightPx).roundToInt()).coerceIn(tracks.indices)
    }
    fun finishDrag() {
        val from = draggedIndex
        val to = from?.let { (it + (dragOffset / rowHeightPx).roundToInt()).coerceIn(tracks.indices) }
        if (from != null && to != null && from != to) onMove(from, to)
        draggedIndex = null
        dragOffset = 0f
    }

    LazyColumn(Modifier.fillMaxWidth().heightIn(max = maxHeight)) {
        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
            val lifted = index == dragged
            val translation = when {
                dragged == null -> 0f
                lifted -> dragOffset
                target != null && dragged < target && index in (dragged + 1)..target -> -rowHeightPx
                target != null && dragged > target && index in target..<dragged -> rowHeightPx
                else -> 0f
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(QueueRowHeight)
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer {
                        translationY = translation
                        if (lifted) {
                            clip = true
                            shape = RoundedCornerShape(16.dp)
                            shadowElevation = 10f
                        }
                    },
            ) {
                QueueRowBody(
                    index = index,
                    track = track,
                    current = index == currentIndex,
                    isPlaying = isPlaying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .tapClickable(role = Role.Button) { onPlayAt(index) }
                        .semantics {
                            customActions = listOf(
                                CustomAccessibilityAction(tr("player.queue.move_up")) {
                                    onMove(index, index - 1); true
                                },
                                CustomAccessibilityAction(tr("player.queue.move_down")) {
                                    onMove(index, index + 1); true
                                },
                            )
                        },
                    handle = {
                        Box(
                            Modifier
                                .size(40.dp)
                                .pointerInput(tracks.size) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = {
                                            draggedIndex = index
                                            dragOffset = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragOffset += amount.y
                                        },
                                        onDragEnd = ::finishDrag,
                                        onDragCancel = ::finishDrag,
                                    )
                                }
                                .semantics { contentDescription = tr("player.queue.drag") },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.DragHandle,
                                null,
                                Modifier.size(18.dp),
                                tint = colors.onSurfaceVariant,
                            )
                        }
                    },
                    onRemove = { onRemoveAt(index) },
                )
            }
        }
    }
}

@Composable
private fun QueueRowBody(
    index: Int,
    track: LazerTrack,
    current: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    handle: @Composable (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier.padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (current) colors.primary.copy(alpha = 0.10f) else Color.Transparent),
            contentAlignment = Alignment.Center,
        ) {
            if (current) {
                NowPlayingBars(color = colors.primary, isPlaying = isPlaying)
            } else {
                Text(
                    (index + 1).toString(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.titleMedium,
                color = if (current) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TranslatedTrackTitle(track.translatedTitle)
            if (track.artist.isNotBlank()) {
                Text(
                    track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        handle?.invoke()
        if (onRemove != null) {
            IconButton(onClick = tapFeedback(onRemove), modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Filled.Close,
                    tr("player.queue.remove"),
                    Modifier.size(18.dp),
                    tint = colors.onSurfaceVariant,
                )
            }
        } else {
            Text(
                track.durationLabel,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SongCommentSheet(
    controller: LazerGatewayController,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val state = controller.comments
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = colors.surface,
        contentColor = colors.onSurface,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            PlayerSheetHeader(
                imageVector = Icons.Outlined.ModeComment,
                title = tr("comment.title"),
                subtitle = tr("comment.count", state.total).takeIf { state.total > 0 },
            )
            controller.replyTarget?.let { target ->
                CommentReplyComposer(
                    nickname = target.user?.nickname.orEmpty(),
                    draft = controller.replyDraft,
                    sending = controller.isReplySending,
                    error = controller.replyError,
                    onDraftChange = controller::updateReplyDraft,
                    onSend = controller::sendReply,
                    onCancel = controller::cancelReply,
                )
            }
            when {
                state.failed && state.comments.isEmpty() -> {
                    Text(
                        tr("comment.load_fail"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                    TextButton(onClick = tapFeedback(controller::retrySongComments)) { Text(tr("comment.retry")) }
                }
                state.loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(
                        tr("comment.loading"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onSurfaceVariant,
                    )
                }
                state.comments.isEmpty() && state.hotComments.isEmpty() -> Text(
                    tr("comment.empty"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                else -> {
                    if (state.hotComments.isNotEmpty()) {
                        CommentSection(tr("comment.hot"))
                        state.hotComments.forEach {
                            SongCommentRow(
                                comment = it,
                                onLike = { controller.toggleCommentLiked(it) },
                                onReply = { controller.startReply(it) },
                            )
                        }
                    }
                    CommentSection(tr("comment.latest"))
                    state.comments.forEach {
                        SongCommentRow(
                            comment = it,
                            onLike = { controller.toggleCommentLiked(it) },
                            onReply = { controller.startReply(it) },
                        )
                    }
                    when {
                        state.loadingMore -> CircularProgressIndicator(
                            Modifier.size(18.dp).align(Alignment.CenterHorizontally),
                            strokeWidth = 2.dp,
                        )
                        state.hasMore -> TextButton(onClick = tapFeedback(controller::loadMoreSongComments)) {
                            Text(tr("comment.more"))
                        }
                        else -> Text(
                            tr("comment.end"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun SongCommentRow(
    comment: SongComment,
    onLike: () -> Unit,
    onReply: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val nickname = comment.user?.nickname.orEmpty()
    val avatarUrl = normalizedArtworkUrl(comment.user?.avatarUrl)
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(colors.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                nickname.firstOrNull()?.toString().orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSecondaryContainer,
            )
            if (avatarUrl != null) {
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                nickname,
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                comment.content,
                modifier = Modifier.copyOnLongPress("comment.copy.title", "comment.copy.hint", comment.content),
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val stamp = relativeCommentTime(comment.time)
                if (stamp.isNotBlank()) {
                    Text(
                        stamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                comment.ipLocation?.location?.takeIf(String::isNotBlank)?.let { location ->
                    Text(
                        location,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                CommentAction(
                    label = if (comment.likedCount > 0) comment.likedCount.toString() else "",
                    contentDescription = tr("comment.like"),
                    onClick = onLike,
                ) {
                    Icon(
                        if (comment.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        null,
                        Modifier.size(14.dp),
                        tint = if (comment.liked) colors.error else colors.onSurfaceVariant,
                    )
                }
                CommentAction(
                    label = tr("comment.reply"),
                    contentDescription = null,
                    onClick = onReply,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Reply,
                        null,
                        Modifier.size(14.dp),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Icon plus optional count, wide enough to tap without leaning on the neighbouring action. */
@Composable
private fun CommentAction(
    label: String,
    contentDescription: String?,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .tapClickable(role = Role.Button, onClick = onClick)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics { this.contentDescription = contentDescription }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon()
        if (label.isNotBlank()) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun CommentReplyComposer(
    nickname: String,
    draft: String,
    sending: Boolean,
    error: String?,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.45f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            tr("comment.reply_to", nickname),
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(tr("comment.reply_hint"), style = MaterialTheme.typography.bodyMedium) },
            isError = error != null,
            minLines = 2,
        )
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("comment.reply_limit", draft.length, SONG_COMMENT_CONTENT_LIMIT),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = tapFeedback(onCancel), enabled = !sending) { Text(tr("comment.reply_cancel")) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = tapFeedback(onSend), enabled = draft.isNotBlank() && !sending) {
                if (sending) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(tr("comment.reply_send"))
                }
            }
        }
    }
}

private fun relativeCommentTime(
    epochMillis: Long,
    nowMillis: Long = getTimeMillis(),
): String {
    if (epochMillis <= 0L) return ""
    val elapsed = (nowMillis - epochMillis).coerceAtLeast(0L)
    val minutes = elapsed / 60_000L
    val hours = minutes / 60L
    val days = hours / 24L
    return when {
        minutes < 1 -> tr("comment.time.now")
        minutes < 60 -> tr("comment.time.minutes", minutes)
        hours < 24 -> tr("comment.time.hours", hours)
        days < 30 -> tr("comment.time.days", days)
        else -> formatCommentDate(epochMillis)
    }
}

@Composable
private fun ArtistPage(
    artist: Artist,
    tracks: List<LazerTrack>,
    isLoading: Boolean,
    currentId: Long?,
    onBack: () -> Unit,
    onPlay: (List<LazerTrack>, LazerTrack) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp + LocalLazerContentBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = tapFeedback(onBack)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("common.back")) }
                Text(tr("artist.title"), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
        }
        item {
            AdaptiveDetailHeader(artwork = {
                MobileArtwork(
                    url = sequenceOf(artist.cover, artist.picUrl, artist.avatar)
                        .mapNotNull(::normalizedArtworkUrl)
                        .firstOrNull(),
                    label = artist.name,
                    modifier = Modifier.size(if (isLandscapeLayout()) 112.dp else 160.dp),
                    cornerRadius = 32.dp,
                )
            }) {
                Text(artist.name, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                if (artist.alias.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(artist.alias.joinToString(" / "), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                }
                artist.briefDesc?.takeIf(String::isNotBlank)?.let { description ->
                    Spacer(Modifier.height(12.dp))
                    Text(description, modifier = Modifier.widthIn(max = 560.dp), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 5, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    artist.musicSize?.let { Text(tr("artist.music_count", it), style = MaterialTheme.typography.labelMedium, color = colors.primary) }
                    artist.albumSize?.let { Text(tr("artist.album_count", it), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
                }
                if (tracks.isNotEmpty()) {
                    Spacer(Modifier.height(18.dp))
                    ThemeButton(onClick = { onPlay(tracks, tracks.first()) }, cornerRadius = 14.dp) {
                        Icon(Icons.Filled.PlayArrow, null, Modifier.size(20.dp))
                        Spacer(Modifier.width(7.dp))
                        Text(tr("artist.play_all"))
                    }
                }
            }
        }
        item {
            Text(tr("artist.popular"), style = MaterialTheme.typography.titleLarge)
        }
        when {
            isLoading && tracks.isEmpty() -> item { QuietState(tr("artist.loading")) }
            tracks.isEmpty() -> item { QuietState(tr("artist.empty")) }
            else -> items(tracks, key = LazerTrack::id) { track ->
                TrackRow(track, track.id == currentId) { onPlay(tracks, track) }
            }
        }
    }
}

@Composable
private fun PlaylistDetail(
    playlist: LazerPlaylist,
    tracks: List<LazerTrack>,
    isLoading: Boolean,
    currentId: Long?,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onPlay: (List<LazerTrack>, LazerTrack) -> Unit,
) {
    StandardPlaylistDetail(
        playlist = playlist,
        tracks = tracks,
        isLoading = isLoading,
        currentId = currentId,
        modifier = modifier,
        onBack = onBack,
        onPlay = onPlay,
    )
}

@Composable
private fun StandardPlaylistDetail(
    playlist: LazerPlaylist,
    tracks: List<LazerTrack>,
    isLoading: Boolean,
    currentId: Long?,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onPlay: (List<LazerTrack>, LazerTrack) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentTrackIndex = tracks.indexOfFirst { it.id == currentId }
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, (if (currentTrackIndex >= 0) 96.dp else 20.dp) + LocalLazerContentBottomInset.current),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = tapFeedback(onBack)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("playlist.back")) }
                    Text(tr("playlist.title"), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MobileArtwork(playlist.coverUrl, playlist.title, Modifier.size(96.dp), 20.dp)
                    Spacer(Modifier.width(18.dp))
                    Column(Modifier.weight(1f)) {
                        Text(playlist.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(5.dp))
                        Text(playlist.subtitle, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(tr("playlist.tracks", tracks.size.takeIf { it > 0 } ?: playlist.trackCount), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            if (isLoading && tracks.isEmpty()) item { QuietState(tr("playlist.opening")) }
            if (!isLoading && tracks.isEmpty()) item { QuietState(tr("playlist.empty")) }
            items(tracks, key = LazerTrack::id) { track ->
                TrackRow(track, track.id == currentId) { onPlay(tracks, track) }
            }
        }
        if (currentTrackIndex >= 0) {
            val locateCurrent: () -> Unit = {
                scope.launch { listState.animateScrollToItem(currentTrackIndex + 2) }
            }
            ExtendedFloatingActionButton(
                onClick = tapFeedback(locateCurrent),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = 20.dp),
                shape = RoundedCornerShape(16.dp),
                containerColor = colors.surfaceContainerHigh,
                contentColor = colors.primary,
                elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp, pressedElevation = 2.dp),
                icon = { Icon(Icons.Outlined.MyLocation, null, Modifier.size(18.dp)) },
                text = { Text(tr("playlist.locate"), style = MaterialTheme.typography.labelLarge) },
            )
        }
    }
}

// One speed and one head start per bar so the four of them never move in lockstep.
private val NowPlayingBarSpeeds = listOf(3.1f, 4.4f, 2.7f, 3.8f)
private val NowPlayingBarOffsets = listOf(0f, 1.1f, 2.2f, 3.3f)

/** How long the bars take to ease down into dots once playback stops. */
private const val NOW_PLAYING_BAR_DESCENT_MILLIS = 420

/**
 * Playback indicator for the row holding the current track. It draws the captured spectrum of what
 * is actually playing when the audio-reactive setting is on and a synthesized equalizer when it is
 * not. Stopping playback eases the bars down into four dots; starting again interrupts that descent
 * from wherever it reached instead of snapping.
 */
@Composable
private fun NowPlayingBars(color: Color, isPlaying: Boolean) {
    val liveLevels = if (isPlaying) LazerAudioLevels.levels.collectAsState().value else null
    val spectrum = liveLevels?.takeIf { it.size == AUDIO_LEVEL_BAND_COUNT }
    var phaseSeconds by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(isPlaying, spectrum != null) {
        if (!isPlaying || spectrum != null) return@LaunchedEffect
        var lastFrameNanos = 0L
        while (isActive) {
            withFrameNanos { now ->
                if (lastFrameNanos != 0L) {
                    val elapsed = ((now - lastFrameNanos) / 1_000_000_000.0).toFloat().coerceAtMost(0.1f)
                    phaseSeconds = (phaseSeconds + elapsed) % 10_000f
                }
                lastFrameNanos = now
            }
        }
    }
    // Read here rather than inside the draw lambda: recomposing on each frame is what keeps the
    // canvas redrawing as playback advances.
    val seconds = phaseSeconds
    val heights = List(AUDIO_LEVEL_BAND_COUNT) { index ->
        spectrum?.get(index)?.let { 0.12f + 0.88f * it } ?: synthesizedLevel(index, seconds)
    }
    // The heights a pause starts from. The descent follows the easing rather than the silence the
    // capture keeps reporting while the track is stopped, and a resume eases out of them again
    // instead of jumping to whatever the live levels are by then.
    val restingHeights = remember { mutableStateOf(heights) }
    val resting = restingHeights.value
    val currentHeights by rememberUpdatedState(heights)
    // 1 while playing, 0 once the bars have settled into dots.
    val descent = remember { Animatable(if (isPlaying) 1f else 0f) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) {
            descent.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            )
        } else {
            restingHeights.value = currentHeights
            descent.animateTo(
                targetValue = 0f,
                animationSpec = tween(
                    durationMillis = NOW_PLAYING_BAR_DESCENT_MILLIS,
                    easing = LazerMotionEasing,
                ),
            )
        }
    }
    val settled = descent.value
    Canvas(Modifier.size(width = 18.dp, height = 16.dp)) {
        val barWidth = 2.5.dp.toPx()
        val gap = (size.width - barWidth * 4f) / 3f
        // A bar drawn at its own width is a dot, which is where a stopped track comes to rest.
        val dotFraction = barWidth / size.height
        repeat(AUDIO_LEVEL_BAND_COUNT) { index ->
            val restingHeight = resting.getOrElse(index) { heights[index] }
            val playingHeight = if (isPlaying) heights[index] else restingHeight
            val target = restingHeight + (playingHeight - restingHeight) * settled
            val barHeight = size.height * (dotFraction + (target - dotFraction) * settled)
                .coerceIn(dotFraction, 1f)
            drawRoundRect(
                color = color,
                topLeft = Offset(index * (barWidth + gap), size.height - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
            )
        }
    }
}

/** Height of bar [index] as a fraction of the canvas when no spectrum is being captured. */
private fun synthesizedLevel(index: Int, seconds: Float): Float {
    val speed = NowPlayingBarSpeeds[index]
    val offset = NowPlayingBarOffsets[index]
    // A slow wave carrying a faster ripple, so the bars breathe instead of pumping evenly.
    val wave = 0.5f +
        0.34f * sin(seconds * speed + offset) +
        0.16f * sin(seconds * speed * 2.37f + offset * 1.9f)
    return (0.26f + 0.74f * wave).coerceIn(0.12f, 1f)
}

@Composable
private fun MobileHeader(
    controller: LazerGatewayController,
    showControls: Boolean,
    onListenTogether: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Lazer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        }
        if (showControls) {
            IconButton(onClick = tapFeedback(onScan)) {
                Icon(Icons.Outlined.QrCodeScanner, tr("scan.open"))
            }
            IconButton(onClick = tapFeedback(onListenTogether)) {
                Icon(
                    Icons.Outlined.Headphones,
                    tr("listen_together.open"),
                    tint = if (controller.listenTogether != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        LocalContentColor.current
                    },
                )
            }
            IconButton(onClick = tapFeedback(controller::toggleTheme)) {
                Icon(
                    if (controller.isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                    tr("player.toggle_theme"),
                )
            }
            IconButton(onClick = tapFeedback(controller::openSettings)) {
                Icon(Icons.Outlined.Settings, tr("player.open_settings"))
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String? = null) {
    Column {
        Text(title, style = MaterialTheme.typography.titleLarge)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun PlaylistStrip(playlists: List<LazerPlaylist>, onOpen: (LazerPlaylist) -> Unit) {
    if (playlists.isEmpty()) {
        QuietState(tr("strip.empty"))
    } else {
        // The rounded shape belongs to the cover, so only the top corners are clipped: the tile
        // shape bounds the tap ripple, and a radius reaching into the label shaved the first and
        // last characters of the title and subtitle sitting at the bottom of the tile.
        val tileShape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 0.dp, bottomStart = 0.dp)
        // The stock ripple only ends at the tile's half diagonal, which a tap that is over by then
        // never sees; the radius is put well past the tile so the mask arrives at its full width.
        val tileRipple = ripple(bounded = true, radius = 260.dp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(playlists, key = LazerPlaylist::id) { playlist ->
                Column(
                    Modifier
                        .width(158.dp)
                        .clip(tileShape)
                        .clickable(
                            interactionSource = null,
                            indication = tileRipple,
                            role = Role.Button,
                            onClick = tapFeedback { onOpen(playlist) },
                        )
                        .padding(bottom = 4.dp),
                ) {
                    MobileArtwork(playlist.coverUrl, playlist.title, Modifier.size(158.dp), 18.dp)
                    Spacer(Modifier.height(10.dp))
                    // The row is as tall as its tallest visible tile, so both title lines are
                    // reserved: without minLines a single wrapped title scrolling in or out resizes
                    // the strip and shoves every section below it up and down along the way.
                    Text(
                        playlist.title,
                        style = MaterialTheme.typography.titleSmall,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(playlist.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun PlaylistListRow(playlist: LazerPlaylist, onOpen: (LazerPlaylist) -> Unit) {
    val tapped = tapFeedback { onOpen(playlist) }
    // The stock bounded ripple dies at the row's half diagonal; the oversized radius lets a tap
    // anywhere light the full row width, matching the playlist strip tiles.
    val rowRipple = ripple(bounded = true, radius = 260.dp)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(
            interactionSource = null,
            indication = rowRipple,
            role = Role.Button,
            onClick = tapped,
        ).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MobileArtwork(playlist.coverUrl, playlist.title, Modifier.size(56.dp), 14.dp)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(if (playlist.isLikedCollection) tr("liked.title") else playlist.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(playlist.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("${playlist.trackCount}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TrackRow(track: LazerTrack, current: Boolean, onClick: () -> Unit) {
    val tapped = tapFeedback(onClick)
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
            .background(if (current) colors.primaryContainer.copy(alpha = 0.58f) else Color.Transparent)
            .tapClickable(role = Role.Button, onClick = tapped).padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MobileArtwork(track.coverUrl, track.title, Modifier.size(48.dp), 12.dp, saveOnLongPress = true)
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TranslatedTrackTitle(track.translatedTitle)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Artist names size to their content but cap out at roughly a third of the row, so
                // a long list ellipsizes early instead of squeezing the album caption off the card.
                LazerArtistNames(
                    artists = track.artists,
                    fallback = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(0.55f, fill = false),
                )
                if (track.album.isNotBlank()) {
                    Text(" · ${track.album}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
        Text(track.durationLabel, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun TranslatedTrackTitle(title: String?, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    if (!title.isNullOrBlank()) {
        Text(title, color = color, style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun MobileArtwork(
    url: String?,
    label: String,
    modifier: Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp,
    onClick: (() -> Unit)? = null,
    saveOnLongPress: Boolean = false,
    decodeSizePx: Int? = null,
) {
    val imageContext = LocalPlatformContext.current
    val imageModel = remember(url, decodeSizePx, imageContext) {
        if (decodeSizePx == null) url else coil3.request.ImageRequest.Builder(imageContext)
            .data(url)
            .size(decodeSizePx, decodeSizePx)
            .build()
    }
    val colors = MaterialTheme.colorScheme
    val requestSave = LocalLazerRequestCoverSave.current
    val onLongPress: (() -> Unit)? = if (saveOnLongPress && !url.isNullOrBlank()) {
        { requestSave(LazerCoverSaveRequest(url, label)) }
    } else {
        null
    }
    Box(
        modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Brush.linearGradient(listOf(colors.primaryContainer, colors.secondaryContainer)))
            // Gestures sit inside the clip so the ripple is confined to the rounded artwork.
            .then(artworkGestures(url, label, onClick, onLongPress)),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.firstOrNull()?.toString().orEmpty(), style = MaterialTheme.typography.titleMedium, color = colors.onPrimaryContainer)
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = imageModel,
                contentDescription = tr("artwork.cover", label),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/**
 * Gestures for artwork that can be saved with a long press. A tap belongs to [onClick] or, when the
 * artwork has no click action of its own, to the clickable around it (the track row); the long press
 * only fires [onLongPress] and swallows the rest of the gesture so the release is never read as a
 * tap by either.
 */
@Composable
private fun artworkGestures(
    url: String?,
    label: String,
    onClick: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
): Modifier = when {
    onClick != null -> Modifier.combinedClickable(
        role = Role.Button,
        onLongClick = onLongPress,
        onClick = tapFeedback(onClick),
    )
    onLongPress != null -> Modifier.onLongPressOnly(url + label, onLongPress)
    else -> Modifier
}

/**
 * A long press that owns the gesture through the release, so the release is never read as a tap by
 * the clickable around it — which would otherwise start playing the track or open the artist page.
 */
private fun Modifier.onLongPressOnly(key: Any, onLongPress: () -> Unit): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (awaitLongPressOrCancellation(down.id) == null) return@awaitEachGesture
        onLongPress()
        var pressed = true
        while (pressed) {
            val event = awaitPointerEvent()
            pressed = event.changes.any { it.pressed }
            event.changes.forEach { it.consume() }
        }
    }
}

/** Long press on a piece of text worth copying. It asks first, exactly as saving a cover does. */
@Composable
private fun Modifier.copyOnLongPress(titleKey: String, hintKey: String, value: String): Modifier {
    val requestCopy = LocalLazerRequestCopyText.current
    val answer = rememberTapAnswer()
    val request = rememberUpdatedState(LazerCopyTextRequest(titleKey, hintKey, value))
    return onLongPressOnly(value) {
        // The sheet is a response to the hand, so it answers it the way a tap does.
        answer()
        requestCopy(request.value)
    }
}

@Composable
private fun SignInInvitation(onSignIn: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(22.dp), color = colors.primaryContainer.copy(alpha = 0.74f)) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(colors.surface), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Person, null, tint = colors.primary) }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("signin.continue"), style = MaterialTheme.typography.titleLarge, color = colors.onPrimaryContainer)
                    Text(tr("login.sign_in.sub"), style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer.copy(alpha = 0.74f))
                }
            }
            Spacer(Modifier.height(16.dp))
            ThemeButton(onClick = onSignIn, cornerRadius = 11.dp) { Text(tr("login.sign_in")) }
        }
    }
}

@Composable
private fun MiniPlayer(
    track: LazerTrack,
    isPlaying: Boolean,
    isPreparing: Boolean,
    onOpen: () -> Unit,
    compact: Boolean = false,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val content: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MobileArtwork(
                track.coverUrl,
                track.title,
                Modifier.size(if (compact) 40.dp else 48.dp),
                if (compact) 10.dp else 12.dp,
                saveOnLongPress = true,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (compact) {
                    Text(
                        track.translatedTitle ?: track.artist,
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    TranslatedTrackTitle(track.translatedTitle)
                }
                if (!compact && isPreparing) {
                    Text(tr("player.preparing"), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                } else if (!compact) {
                    LazerArtistNames(track.artists, track.artist, MaterialTheme.typography.labelSmall, colors.onSurfaceVariant)
                }
            }
            IconButton(
                onClick = tapFeedback(onToggle),
                modifier = Modifier.size(42.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = colors.primaryContainer,
                    contentColor = colors.onPrimaryContainer,
                ),
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (isPlaying) tr("player.pause") else tr("player.play"),
                )
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth().height(if (compact) 60.dp else 76.dp).tapClickable(role = Role.Button, onClick = onOpen),
        color = colors.surface.copy(alpha = 0.98f * LocalLazerUiAlpha.current),
        border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.78f)),
        content = content,
    )
}

@Composable
private fun BottomDock(
    selected: LazerRootDestination,
    onSelect: (LazerRootDestination) -> Unit,
) {
    if (LocalLazerThemeEngine.current == LazerThemeEngine.MIUIX) {
        MiuixNavigationBar(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp + navigationBarBottomInset()),
            showDivider = true,
            defaultWindowInsetsPadding = true,
            mode = MiuixNavigationBarDisplayMode.IconAndText,
        ) {
            LazerRootDestination.entries.forEach { destination ->
                MiuixNavigationBarItem(
                    selected = selected == destination,
                    onClick = tapFeedback { onSelect(destination) },
                    icon = destination.icon(),
                    label = destination.label,
                )
            }
        }
        return
    }

    val colors = MaterialTheme.colorScheme
    NavigationBar(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp + navigationBarBottomInset()),
        containerColor = colors.surfaceContainer.copy(alpha = LocalLazerUiAlpha.current),
        contentColor = colors.onSurface,
        tonalElevation = 0.dp,
    ) {
        LazerRootDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = selected == destination,
                onClick = tapFeedback { onSelect(destination) },
                icon = { Icon(destination.icon(), contentDescription = destination.label) },
                label = {
                    Text(
                        destination.label,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = colors.onSecondaryContainer,
                    selectedTextColor = colors.onSurface,
                    indicatorColor = colors.secondaryContainer,
                    unselectedIconColor = colors.onSurfaceVariant,
                    unselectedTextColor = colors.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun navigationBarBottomInset(): Dp =
    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

@Composable
private fun statusBarTopInset(): Dp =
    WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

@Composable
private fun LazerArtistNames(
    artists: List<Artist>,
    fallback: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    offerCopy: Boolean = false,
) {
    val available = artists.filter { it.id > 0L && it.name.isNotBlank() }
    val openArtists = LocalLazerOpenArtists.current
    val names = available.joinToString(" / ") { it.name }.ifBlank { fallback }
    Text(
        text = names,
        modifier = modifier
            .then(if (available.isNotEmpty()) Modifier.tapClickable { openArtists(available) } else Modifier)
            .then(
                if (offerCopy) Modifier.copyOnLongPress("song.copy.artist", "song.copy.hint", names) else Modifier,
            ),
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun NowPlayingPage(
    snapshot: LazerPlaybackSnapshot,
    lyricLines: List<TimedLyricLine>,
    lyricsLoading: Boolean,
    lyricsMessage: String?,
    lyricFollowDelayMillis: Long,
    lyricAnimationSpeed: LyricAnimationSpeed,
    wordLyricsEnabled: Boolean,
    lyricGlowEnabled: Boolean,
    lyricFontSizeSp: Int,
    showFullLyrics: Boolean,
    animateAlbumBackground: Boolean,
    solidAlbumBackground: Boolean,
    isLiked: Boolean,
    onToggleLiked: () -> Unit,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenComments: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = snapshot.track ?: return
    val colors = MaterialTheme.colorScheme
    val duration = snapshot.durationMillis.takeIf { it > 0L } ?: track.durationMillis
    val target = if (duration > 0) snapshot.positionMillis.toFloat() / duration else 0f
    // A 100 ms service tick is already finer than this seek rail. Animating this value in the
    // page scope used to recompose the whole player at display refresh rate.
    val display = target.coerceIn(0f, 1f)
    var seeking by remember(track.id) { mutableStateOf(false) }
    var draggedProgress by remember(track.id) { mutableFloatStateOf(display) }
    val seekProgress = if (seeking) draggedProgress else display

    // Keep the visual background edge-to-edge; only the controls need to avoid system bars.
    Surface(modifier.fillMaxSize(), color = colors.background) {
        if (isLandscapeLayout()) {
            Box(Modifier.fillMaxSize()) {
                LazerAlbumFlowBackground(
                    track = track,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 0.dp,
                    veil = colors.background.copy(alpha = 0.38f),
                    animated = animateAlbumBackground,
                    solid = solidAlbumBackground,
                )
                BoxWithConstraints(
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    val artworkSide = (maxHeight * 0.25f).coerceIn(72.dp, 148.dp)
                    Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier
                            .weight(0.44f)
                            .fillMaxHeight()
                            .widthIn(max = 440.dp)
                            .padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().height(44.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = tapFeedback(onDismiss),
                                modifier = Modifier.size(40.dp).semantics { this.contentDescription = tr("player.collapse") },
                            ) { Icon(Icons.Filled.Close, null, tint = colors.onSurface) }
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = tapFeedback(onToggleLiked), modifier = Modifier.size(44.dp)) {
                                Icon(
                                    if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    if (isLiked) tr("player.like.remove") else tr("player.like.add"),
                                    tint = if (isLiked) colors.primary else colors.onSurfaceVariant,
                                )
                            }
                        }
                        MobileArtwork(
                            track.coverUrl,
                            track.title,
                            Modifier.size(artworkSide),
                            18.dp,
                            saveOnLongPress = true,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            track.title,
                            modifier = Modifier.copyOnLongPress("song.copy.title", "song.copy.hint", track.title),
                            color = colors.onBackground,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        TranslatedTrackTitle(track.translatedTitle)
                        LazerArtistNames(
                            track.artists, track.artist,
                            MaterialTheme.typography.bodyMedium, colors.onSurfaceVariant,
                            offerCopy = true,
                        )
                        Spacer(Modifier.weight(1f))
                        Column(
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                PlayerCardAction(
                                    imageVector = Icons.AutoMirrored.Outlined.QueueMusic,
                                    label = tr("player.queue"),
                                    onClick = onOpenQueue,
                                )
                                Spacer(Modifier.weight(1f))
                                PlayerCardAction(
                                    imageVector = Icons.Outlined.ModeComment,
                                    label = tr("comment.open"),
                                    onClick = onOpenComments,
                                )
                            }
                            ThinSeekBar(
                                progress = seekProgress,
                                bufferedProgress = snapshot.bufferedFraction,
                                onSeek = { draggedProgress = it; seeking = true },
                                onFinished = { onSeek((duration * draggedProgress).toLong()); seeking = false },
                            )
                            Row(Modifier.fillMaxWidth()) {
                                Text(formatPlaybackTime((duration * seekProgress).toLong()), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                Spacer(Modifier.weight(1f))
                                Text(formatPlaybackTime(duration), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            }
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(
                                    14.dp,
                                    Alignment.CenterHorizontally,
                                ),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = tapFeedback(onPrevious), modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Filled.SkipPrevious, tr("player.previous"), Modifier.size(27.dp))
                                }
                                IconButton(
                                    onClick = tapFeedback(onToggle),
                                    modifier = Modifier.size(52.dp),
                                    colors = IconButtonDefaults.iconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                                ) {
                                    Icon(
                                        if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        if (snapshot.isPlaying) tr("player.pause") else tr("player.play"),
                                        Modifier.size(29.dp),
                                    )
                                }
                                IconButton(onClick = tapFeedback(onNext), modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Filled.SkipNext, tr("player.next"), Modifier.size(27.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    LazerLyricsViewport(
                        track = track,
                        lines = lyricLines,
                        isLoading = lyricsLoading,
                        message = lyricsMessage,
                        positionMillis = snapshot.positionMillis,
                        followDelayMillis = lyricFollowDelayMillis,
                        animationSpeed = lyricAnimationSpeed,
                        wordLyricsEnabled = wordLyricsEnabled,
                        lyricGlowEnabled = lyricGlowEnabled,
                        lyricFontSizeSp = lyricFontSizeSp,
                        showFullLyrics = showFullLyrics,
                        onSeek = onSeek,
                        modifier = Modifier.weight(0.56f).fillMaxHeight().padding(horizontal = 4.dp),
                    )
                    }
                }
            }
        } else {
            // The portrait cover is one flying node shared by the compact (top-left, next to the
            // close button) and expanded (below the header) layouts. The slots report their root
            // bounds; toggling animates one overlay between them with the page's easing.
            var coverCompactRect by remember { mutableStateOf<Rect?>(null) }
            var coverExpandedRect by remember { mutableStateOf<Rect?>(null) }
            var coverCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
            var coverExpanded by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
            // Once the reader picks a layout for this song, the page stops second-guessing it.
            var coverLayoutPicked by remember(track.id) { mutableStateOf(false) }
            // A song with nothing to sing rests with the cover open instead of a small artwork above
            // an empty pane. This is derived, not written into state: right after a track change the
            // previous song's lines are still in `lyricLines`, and a still-fetching song looks empty
            // too. Deciding from either would fly the cover open and snap it back once the fetch
            // lands, tearing the lyric sheet out of composition and rebuilding it in between.
            val coverOpen = coverExpanded ||
                (!coverLayoutPicked && !lyricsLoading && lyricLines.isEmpty() && lyricsMessage != null)
            val coverProgress = remember { Animatable(0f) }
            LaunchedEffect(coverOpen) {
                coverProgress.animateTo(
                    if (coverOpen) 1f else 0f,
                    tween(durationMillis = 420, easing = LazerMotionEasing),
                )
            }
            val coverDensity = LocalDensity.current
            Box(Modifier.fillMaxSize().onGloballyPositioned { coverCoordinates = it }) {
                LazerAlbumFlowBackground(
                    track = track,
                    modifier = Modifier.fillMaxSize(),
                    cornerRadius = 0.dp,
                    veil = colors.background.copy(alpha = 0.38f),
                    animated = animateAlbumBackground,
                    solid = solidAlbumBackground,
                )
                Column(
                    Modifier
                        .fillMaxSize()
                        .safeDrawingPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = tapFeedback(onDismiss),
                            modifier = Modifier.size(48.dp).semantics { this.contentDescription = tr("player.collapse") },
                        ) {
                            Icon(Icons.Filled.Close, null, tint = colors.onSurface)
                        }
                        Spacer(Modifier.width(12.dp))
                        Box(
                            Modifier
                                .size(52.dp)
                                .onGloballyPositioned { coordinates ->
                                    coverCoordinates?.takeIf { it.isAttached }?.let { parent ->
                                        coverCompactRect = parent.localBoundingBoxOf(coordinates, clipBounds = false)
                                    }
                                },
                        )
                        // The slot keeps its layout size; the info column slides left over the
                        // empty slot as the cover departs. Reading progress inside the layer block
                        // moves only the text per frame, without recomposing the header.
                        Column(
                            Modifier
                                .weight(1f)
                                .padding(start = 12.dp)
                                .graphicsLayer { translationX = -52.dp.toPx() * coverProgress.value },
                        ) {
                            Text(
                                track.title,
                                modifier = Modifier.copyOnLongPress("song.copy.title", "song.copy.hint", track.title),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TranslatedTrackTitle(track.translatedTitle)
                            LazerArtistNames(
                                track.artists, track.artist,
                                MaterialTheme.typography.bodySmall, colors.onSurfaceVariant,
                                offerCopy = true,
                            )
                        }
                        // The liked state belongs to the song, so it stays beside its title
                        // instead of travelling with the transport controls below.
                        IconButton(onClick = tapFeedback(onToggleLiked), modifier = Modifier.size(44.dp)) {
                            Icon(
                                if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                if (isLiked) tr("player.like.remove") else tr("player.like.add"),
                                tint = if (isLiked) colors.primary else colors.onSurfaceVariant,
                            )
                        }
                    }
                    // Both modes share this entire content area; no invisible cover spacer steals
                    // lyric height. The target slot is measured independently of animation progress.
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val artworkSide = minOf(320.dp, maxWidth, maxHeight).coerceAtLeast(0.dp)
                        Box(
                            Modifier.align(Alignment.Center).size(artworkSide)
                                .onGloballyPositioned { coordinates ->
                                    coverCoordinates?.takeIf { it.isAttached }?.let { parent ->
                                        coverExpandedRect = parent.localBoundingBoxOf(coordinates, clipBounds = false)
                                    }
                                },
                        )
                        val lyricsVisible by remember { derivedStateOf { coverProgress.value < 0.999f } }
                        if (lyricsVisible) {
                            LazerLyricsViewport(
                                track = track,
                                lines = lyricLines,
                                isLoading = lyricsLoading,
                                message = lyricsMessage,
                                positionMillis = snapshot.positionMillis,
                                followDelayMillis = lyricFollowDelayMillis,
                                animationSpeed = lyricAnimationSpeed,
                                wordLyricsEnabled = wordLyricsEnabled,
                                lyricGlowEnabled = lyricGlowEnabled,
                                lyricFontSizeSp = lyricFontSizeSp,
                                showFullLyrics = showFullLyrics,
                                onSeek = { if (!coverOpen) onSeek(it) },
                                modifier = Modifier.fillMaxSize()
                                    .padding(top = 15.dp, bottom = 15.dp)
                                    .graphicsLayer {
                                        alpha = 1f - coverProgress.value
                                },
                            )
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PlayerCardAction(
                                imageVector = Icons.AutoMirrored.Outlined.QueueMusic,
                                label = tr("player.queue"),
                                onClick = onOpenQueue,
                            )
                            Spacer(Modifier.weight(1f))
                            PlayerCardAction(
                                imageVector = Icons.Outlined.ModeComment,
                                label = tr("comment.open"),
                                onClick = onOpenComments,
                            )
                        }
                        ThinSeekBar(
                            progress = seekProgress,
                            bufferedProgress = snapshot.bufferedFraction,
                            onSeek = { draggedProgress = it; seeking = true },
                            onFinished = { onSeek((duration * draggedProgress).toLong()); seeking = false },
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Text(
                                formatPlaybackTime((duration * seekProgress).toLong()),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                formatPlaybackTime(duration),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(
                                18.dp,
                                Alignment.CenterHorizontally,
                            ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = tapFeedback(onPrevious), modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.SkipPrevious, tr("player.previous"), Modifier.size(30.dp))
                            }
                            IconButton(
                                onClick = tapFeedback(onToggle),
                                modifier = Modifier.size(64.dp),
                                colors = IconButtonDefaults.iconButtonColors(
                                    containerColor = colors.primary,
                                    contentColor = colors.onPrimary,
                                ),
                            ) {
                                Icon(
                                    if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                    if (snapshot.isPlaying) tr("player.pause") else tr("player.play"),
                                    Modifier.size(34.dp),
                                )
                            }
                            IconButton(onClick = tapFeedback(onNext), modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.SkipNext, tr("player.next"), Modifier.size(30.dp))
                            }
                        }
                        snapshot.message?.let { message ->
                            Text(
                                message,
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.error,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                // The cover is a single overlay placed by measured slot bounds, whether resting or
                // in flight: progress 0 sits in the compact slot, progress 1 in the expanded one,
                // and everything between is the animated flight between them.
                val compactRect = coverCompactRect
                val expandedRect = coverExpandedRect
                if (compactRect != null && expandedRect != null && expandedRect.width > 0f && expandedRect.height > 0f) {
                    // All interpolation parameters are computed once per frame inside graphicsLayer,
                    // avoiding layout pass entirely — only the GPU transform updates.
                    val startLeft = compactRect.left
                    val startTop = compactRect.top
                    val startSize = with(coverDensity) { 52.dp.toPx() }
                    val endLeft = expandedRect.left
                    val endTop = expandedRect.top
                    val endSize = minOf(expandedRect.width, expandedRect.height)
                    val requestSave = LocalLazerRequestCoverSave.current

                    Box(
                        Modifier
                            .offset { IntOffset(startLeft.roundToInt(), startTop.roundToInt()) }
                            .size(with(coverDensity) { endSize.toDp() })
                            .graphicsLayer {
                                val progress = coverProgress.value
                                // Scale to reach target size
                                val scale = androidx.compose.ui.util.lerp(startSize / endSize, 1f, progress)
                                scaleX = scale
                                scaleY = scale
                                // Translate to reach target position (accounting for scale origin)
                                translationX = androidx.compose.ui.util.lerp(0f, endLeft - startLeft, progress)
                                translationY = androidx.compose.ui.util.lerp(0f, endTop - startTop, progress)
                                transformOrigin = TransformOrigin(0f, 0f)
                                val cornerPx = androidx.compose.ui.util.lerp(12.dp.toPx(), 24.dp.toPx(), progress)
                                shadowElevation = 24.dp.toPx() * progress
                                ambientShadowColor = colors.scrim.copy(alpha = 0.24f * progress)
                                spotShadowColor = colors.scrim.copy(alpha = 0.38f * progress)
                                clip = true
                                shape = RoundedCornerShape(cornerPx / scale)
                            }
                            .combinedClickable(
                                role = Role.Button,
                                onClickLabel = tr(if (coverOpen) "player.cover.collapse" else "player.cover.expand"),
                                onClick = tapFeedback {
                                    coverLayoutPicked = true
                                    coverExpanded = !coverOpen
                                },
                                onLongClick = {
                                    val url = track.coverUrl
                                    if (!url.isNullOrBlank()) {
                                        requestSave(LazerCoverSaveRequest(url, track.title))
                                    }
                                },
                            ),
                    ) {
                        MobileArtwork(
                            enlargedArtworkUrl(track.coverUrl, sizePx = 1024),
                            track.title,
                            Modifier.fillMaxSize(),
                            0.dp,
                            decodeSizePx = 1024,
                        )
                    }
                }
            }
        }
    }
}

/** Same visual construction as the desktop control: base rail, buffered rail, then blue playhead. */
@Composable
private fun ThinSeekBar(
    progress: Float,
    bufferedProgress: Float,
    onSeek: (Float) -> Unit,
    onFinished: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val fraction = progress.coerceIn(0f, 1f)
    val buffered = maxOf(fraction, bufferedProgress.coerceIn(0f, 1f))
    val latestSeek by androidx.compose.runtime.rememberUpdatedState(onSeek)
    val latestFinished by androidx.compose.runtime.rememberUpdatedState(onFinished)
    // Divided by the length of the track rather than by distance; see SEEK_DETENTS.
    val detent = rememberDetentAnswer { position ->
        (position * SEEK_DETENTS).toInt().coerceAtMost(SEEK_DETENTS - 1)
    }
    val landedTap = rememberTapAnswer()
    // A gesture that is already running holds on to the callbacks it started with unless these say
    // otherwise, which would leave a drag that outlives a settings change answering at the old level.
    val latestDetent by rememberUpdatedState(detent)
    val latestLanded by rememberUpdatedState(landedTap)
    BoxWithConstraints(
        Modifier.height(48.dp).fillMaxWidth().semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            setProgress { value ->
                latestSeek(value.coerceIn(0f, 1f))
                latestFinished()
                latestLanded()
                true
            }
        }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val width = size.width.coerceAtLeast(1)
                val grabbed = (down.position.x / width).coerceIn(0f, 1f)
                latestSeek(grabbed); latestDetent(grabbed); down.consume()
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    val moved = (change.position.x / width).coerceIn(0f, 1f)
                    latestSeek(moved); latestDetent(moved); change.consume()
                    if (!change.pressed) break
                }
                latestFinished()
                // Letting go is the seek, so it answers with the tap click rather than a notch.
                latestLanded()
            }
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        val travel = (maxWidth - 10.dp).coerceAtLeast(0.dp)
        Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(colors.surfaceVariant.copy(alpha = 0.95f)))
        Box(Modifier.fillMaxWidth(buffered).height(3.dp).clip(CircleShape).background(colors.onSurfaceVariant.copy(alpha = 0.34f)))
        Box(Modifier.fillMaxWidth(fraction).height(3.dp).clip(CircleShape).background(colors.primary))
        Box(Modifier.padding(start = travel * fraction).size(10.dp).clip(CircleShape).background(colors.primary))
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun ArtistChoiceSheet(
    artists: List<Artist>,
    onDismiss: () -> Unit,
    onChoose: (Artist) -> Unit,
) {
    if (artists.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = shape,
        containerColor = colors.surface,
        contentColor = colors.onSurface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(tr("artist.choose.title"), style = MaterialTheme.typography.headlineSmall)
            Text(tr("artist.choose.hint"), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            artists.forEach { artist ->
                Surface(
                    modifier = Modifier.fillMaxWidth().tapClickable { onChoose(artist) },
                    shape = RoundedCornerShape(14.dp),
                    color = colors.surfaceVariant.copy(alpha = 0.48f),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Person, null, tint = colors.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(artist.name, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun CoverSaveSheet(
    request: LazerCoverSaveRequest?,
    onDismiss: () -> Unit,
    onConfirm: (LazerCoverSaveRequest) -> Unit,
) {
    request ?: return
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = shape,
        containerColor = colors.surface,
        contentColor = colors.onSurface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(tr("cover.save.title"), style = MaterialTheme.typography.headlineSmall)
            Text(tr("cover.save.hint", request.title), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                ThemeTextButton(onClick = onDismiss) { Text(tr("cover.save.cancel")) }
                ThemeButton(onClick = { onConfirm(request) }, cornerRadius = 12.dp) {
                    Text(tr("cover.save.confirm"))
                }
            }
        }
    }
}

/** The same question the artwork asks, for the song title and the artist names. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun CopyTextSheet(
    request: LazerCopyTextRequest?,
    onDismiss: () -> Unit,
    onConfirm: (LazerCopyTextRequest) -> Unit,
) {
    request ?: return
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = colors.surface,
        contentColor = colors.onSurface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(tr(request.titleKey), style = MaterialTheme.typography.headlineSmall)
            Text(
                tr(request.hintKey, request.value),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                ThemeTextButton(onClick = onDismiss) { Text(tr("song.copy.cancel")) }
                ThemeButton(onClick = { onConfirm(request) }, cornerRadius = 12.dp) {
                    Text(tr("song.copy.confirm"))
                }
            }
        }
    }
}

private fun lazerCoverFileName(title: String): String {
    val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Lazer cover" }
    return "$safeTitle.jpg"
}

private sealed interface ScannedCode {
    data class ClientLogin(val url: String) : ScannedCode
    data class ListenTogether(val raw: String) : ScannedCode
    data object Unsupported : ScannedCode
}

private fun classifyScannedCode(raw: String): ScannedCode {
    if (parseListenTogetherInvite(raw) != null) return ScannedCode.ListenTogether(raw)
    parseNeteaseClientLoginUrl(raw)?.let { return ScannedCode.ClientLogin(it) }
    return ScannedCode.Unsupported
}

/** Days since the civil epoch, turned into a calendar date without leaning on a platform calendar. */
private fun formatCommentDate(epochMillis: Long): String {
    val z = floorDiv(epochMillis, 86_400_000L) + 719_468L
    val era = floorDiv(z, 146_097L)
    val dayOfEra = (z - era * 146_097L).toInt()
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
    val monthShift = (5 * dayOfYear + 2) / 153
    val day = dayOfYear - (153 * monthShift + 2) / 5 + 1
    val month = if (monthShift < 10) monthShift + 3 else monthShift - 9
    val year = (if (month <= 2) yearOfEra + 1 else yearOfEra) + era * 400
    return "$year" + "-" + month.toString().padStart(2, '0') + "-" + day.toString().padStart(2, '0')
}

private fun floorDiv(value: Long, divisor: Long): Long {
    val quotient = value / divisor
    return if ((value % divisor != 0L) && ((value < 0L) != (divisor < 0L))) quotient - 1 else quotient
}

/** Only official NetEase login QR URLs may receive the user's saved session cookie. */
internal fun parseNeteaseClientLoginUrl(raw: String): String? {
    val trimmed = raw.trim()
    if (!trimmed.startsWith("https://", ignoreCase = true)) return null
    val afterScheme = trimmed.substringAfter("://")
    val hostEnd = afterScheme.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val host = if (hostEnd < 0) afterScheme else afterScheme.substring(0, hostEnd)
    if (!host.equals("music.163.com", ignoreCase = true)) return null
    val remainder = if (hostEnd < 0) "" else afterScheme.substring(hostEnd)
    val path = remainder.substringBefore('?').substringBefore('#')
    val query = remainder.substringAfter('?', "").substringBefore('#')
    if (query.isBlank()) return null
    val hasCodeKey = query.split('&').any { parameter ->
        parameter.substringBefore('=').equals("codekey", ignoreCase = true) &&
            parameter.substringAfter('=', "").isNotBlank()
    }
    if (!hasCodeKey) return null
    return when (path) {
        "/login", "/st/platform/scanlogin" ->
            "https://music.163.com/st/platform/scanlogin?$query"
        else -> null
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun NeteaseQrAuthorizationSheet(
    url: String,
    sessionCookie: String,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = colors.surface,
        contentColor = colors.onSurface,
    ) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(tr("scan.authorize_title"), style = MaterialTheme.typography.headlineSmall)
            Text(
                tr("scan.authorize_hint"),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
            )
            LocalLazerAuthWebView.current(
                url,
                sessionCookie,
                Modifier.fillMaxWidth().heightIn(min = 360.dp, max = 560.dp)
                    .clip(RoundedCornerShape(18.dp)),
            )
            ThemeTextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(tr("login.close"))
            }
        }
    }
}

private fun isAllowedNeteaseWebHost(host: String?): Boolean =
    host.equals("music.163.com", ignoreCase = true) ||
        host.equals("st.music.163.com", ignoreCase = true)

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun LoginSheet(
    controller: LazerGatewayController,
) {
    val colors = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val contentScrollState = rememberScrollState()
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ModalBottomSheet(
        onDismissRequest = controller::closeLogin,
        sheetState = sheetState,
        modifier = Modifier,
        shape = shape,
        containerColor = colors.surface,
        contentColor = colors.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(contentScrollState)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(tr("login.continue"), style = MaterialTheme.typography.headlineSmall)
            Text(tr("login.sub.mobile"), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                LazerLoginMethod.entries.forEach { method ->
                    ThemeTextButton(
                        onClick = { controller.selectLoginMethod(method) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            method.label,
                            color = if (method == controller.loginMethod) colors.primary else colors.onSurfaceVariant,
                            fontWeight = if (method == controller.loginMethod) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                        )
                    }
                }
            }
            when (controller.loginMethod) {
                LazerLoginMethod.CAPTCHA -> CaptchaLogin(controller)
                LazerLoginMethod.PASSWORD -> PasswordLogin(controller)
                LazerLoginMethod.QR_CODE -> QrLogin(controller)
                LazerLoginMethod.COOKIE -> CookieLogin(controller)
            }
            controller.loginMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it == tr("login.captcha.sent")) colors.primary else colors.error,
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CaptchaLogin(controller: LazerGatewayController) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PhoneField(controller.loginPhone, controller::updateLoginPhone)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                controller.loginCaptcha, controller::updateLoginCaptcha, Modifier.weight(1f), label = { Text(tr("login.captcha.code")) }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            )
            Spacer(Modifier.width(10.dp))
            ThemeTextButton(onClick = controller::sendCaptcha, enabled = !controller.isSendingCaptcha) {
                Text(if (controller.isSendingCaptcha) tr("login.captcha.sending") else if (controller.captchaSent) tr("login.captcha.resend") else tr("login.captcha.send"))
            }
        }
        ThemeButton(
            onClick = controller::submitCaptchaLogin,
            modifier = Modifier.fillMaxWidth(),
            enabled = !controller.isSubmittingLogin,
        ) { Text(if (controller.isSubmittingLogin) tr("login.submitting") else tr("login.captcha.submit")) }
    }
}

@Composable
private fun PasswordLogin(controller: LazerGatewayController) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PhoneField(controller.loginPhone, controller::updateLoginPhone)
        OutlinedTextField(
            controller.loginPassword, controller::updateLoginPassword, Modifier.fillMaxWidth(), label = { Text(tr("login.pw.password")) }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        )
        ThemeButton(
            onClick = controller::submitPasswordLogin,
            modifier = Modifier.fillMaxWidth(),
            enabled = !controller.isSubmittingLogin,
        ) { Text(if (controller.isSubmittingLogin) tr("login.submitting") else tr("login.pw.submit")) }
    }
}

@Composable
private fun CookieLogin(controller: LazerGatewayController) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(tr("login.cookie.title"), style = MaterialTheme.typography.titleMedium)
        Text(
            tr("login.cookie.hint"),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        OutlinedTextField(
            value = controller.loginCookie,
            onValueChange = controller::updateLoginCookie,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(tr("login.cookie.label")) },
            singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        )
        ThemeButton(
            onClick = controller::submitCookieLogin,
            modifier = Modifier.fillMaxWidth(),
            enabled = !controller.isSubmittingLogin,
        ) {
            Text(if (controller.isSubmittingLogin) tr("login.submitting") else tr("login.cookie.submit"))
        }
    }
}

@Composable
private fun PhoneField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(tr("login.phone")) }, leadingIcon = { Text("+86", style = MaterialTheme.typography.labelLarge) },
        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Next),
    )
}

@Composable
private fun QrLogin(controller: LazerGatewayController) {
    val image = remember(controller.qrImageData) { decodeQrImage(controller.host, controller.qrImageData) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (image != null) {
            androidx.compose.foundation.Image(image, tr("login.artwork.qr"), Modifier.size(208.dp).clip(RoundedCornerShape(18.dp)))
        } else {
            Box(Modifier.size(208.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text(if (controller.qrState == LazerQrLoginState.CREATING) tr("login.qr.creating") else tr("login.qr.unavailable"), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        Text(
            when (controller.qrState) {
                LazerQrLoginState.WAITING_FOR_SCAN -> tr("login.qr.scan.mobile")
                LazerQrLoginState.WAITING_FOR_CONFIRMATION -> tr("login.qr.confirm.mobile")
                LazerQrLoginState.EXPIRED -> tr("login.qr.expired")
                LazerQrLoginState.ERROR -> tr("login.qr.error.mobile")
                else -> ""
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (controller.qrState == LazerQrLoginState.EXPIRED || controller.qrState == LazerQrLoginState.ERROR) {
            ThemeTextButton(controller::startQrLogin) { Text(tr("login.qr.regenerate")) }
        }
    }
}

@OptIn(ExperimentalStdlibApi::class)
/**
 * Gives the keyboard back when a tap lands anywhere but a text field. The focus is dropped on the way
 * in, before the screens are told about the press, so a field the tap actually hits asks for focus
 * again in the same gesture and keeps the keyboard. That is what saves this from having to know where
 * the fields are, or from a second tap being needed to put the keyboard down.
 */
@Composable
internal fun Modifier.releaseKeyboardOnAnyTap(): Modifier {
    val focusManager = LocalFocusManager.current
    return pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            // Unconditional: with nothing focused this gives up a focus stack that is already empty.
            focusManager.clearFocus()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                if (event.changes.none { it.pressed }) break
            }
        }
    }
}

private fun decodeQrImage(host: LazerPlatformHost, data: String?): ImageBitmap? = runCatching {
    val encoded = data?.substringAfter("base64,", data)?.filterNot(Char::isWhitespace)
        ?: return null
    host.decodeImageBytes(kotlin.io.encoding.Base64.decode(encoded))
}.getOrNull()

@Composable
private fun QuietState(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = 14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
private fun MessageBanner(
    text: String,
    modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier,
        shape = RoundedCornerShape(14.dp),
        color = colors.surfaceContainerHigh,
        shadowElevation = 5.dp,
    ) {
        Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
    }
}

private fun LazerRootDestination.icon() = when (this) {
    LazerRootDestination.HOME -> Icons.Outlined.Home
    LazerRootDestination.SEARCH -> Icons.Outlined.Search
    LazerRootDestination.LIBRARY -> Icons.Outlined.LibraryMusic
    LazerRootDestination.ME -> Icons.Outlined.Person
}
