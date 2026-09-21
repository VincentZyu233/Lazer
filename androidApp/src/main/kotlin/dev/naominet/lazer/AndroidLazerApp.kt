package dev.naominet.lazer

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.util.Base64
import androidx.activity.BackEventCompat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.animation.core.CubicBezierEasing
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
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil3.compose.AsyncImage
import com.kashif_e.backdrop.backdrops.LayerBackdrop
import com.kashif_e.backdrop.backdrops.layerBackdrop
import com.kashif_e.backdrop.backdrops.rememberCombinedBackdrop
import com.kashif_e.backdrop.backdrops.rememberLayerBackdrop
import com.kashif_e.backdrop.drawBackdrop
import com.kashif_e.backdrop.drawPlainBackdrop
import com.kashif_e.backdrop.effects.blur
import com.kashif_e.backdrop.effects.lens
import com.kashif_e.backdrop.effects.vibrancy
import com.kashif_e.backdrop.highlight.Highlight
import com.kashif_e.backdrop.shadow.InnerShadow
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.material.icons.outlined.Headphones
import dev.naominet.lazer.gateway.AudioQuality
import dev.naominet.lazer.gateway.model.Artist
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
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
private val LazerMotionEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

// Extra bottom content padding for scrollable pages so their last rows stay reachable behind the
// floating liquid-glass bottom controls.
private val LocalAndroidContentBottomInset = compositionLocalOf { 0.dp }

private data class AndroidCoverSaveRequest(val url: String, val title: String)

private val LocalAndroidOpenArtists = androidx.compose.runtime.staticCompositionLocalOf<(List<Artist>) -> Unit> { {} }
private val LocalAndroidRequestCoverSave = androidx.compose.runtime.staticCompositionLocalOf<(AndroidCoverSaveRequest) -> Unit> { {} }

private enum class AndroidMainPageKind(val depth: Int) {
    ROOT(0),
    PLAYLIST(1),
    SETTINGS(1),
    ARTIST(2),
}

private data class AndroidMainPage(
    val kind: AndroidMainPageKind,
    val playlist: AndroidPlaylist? = null,
    val artist: Artist? = null,
    val tracks: List<AndroidTrack> = emptyList(),
    val isLoading: Boolean = false,
) {
    val contentKey: Any
        get() = when (kind) {
            AndroidMainPageKind.PLAYLIST -> kind to playlist?.id
            AndroidMainPageKind.ARTIST -> kind to artist?.id
            else -> kind
        }
}

private enum class AndroidBackLayer {
    ARTIST,
    PLAYLIST,
    SETTINGS,
    PLAYER,
}

private fun Modifier.predictiveBackTransform(
    enabled: Boolean,
    progress: Float,
    swipeEdge: Int,
): Modifier = if (!enabled) {
    this
} else {
    graphicsLayer {
        val fraction = progress.coerceIn(0f, 1f)
        val direction = if (swipeEdge == BackEventCompat.EDGE_RIGHT) -1f else 1f
        translationX = size.width * 0.16f * fraction * direction
        val scale = 1f - 0.09f * fraction
        scaleX = scale
        scaleY = scale
        alpha = 1f - 0.22f * fraction
    }
}

/** Paints the fixed wallpaper canvas inside a moving page, keeping transition seams hidden. */
private fun Modifier.capturedPageBackground(backdrop: LayerBackdrop): Modifier =
    drawPlainBackdrop(
        backdrop = backdrop,
        shape = { RectangleShape },
        effects = {},
    )

@Composable
private fun isLandscapeLayout(): Boolean =
    LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }

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
private fun LiquidGlassIconButton(
    onClick: () -> Unit,
    contentDescription: String,
    glass: LazerLiquidGlass,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.Unspecified,
    size: Dp = 48.dp,
    content: @Composable () -> Unit,
) {
    if (!glass.isEnabled) {
        IconButton(
            onClick = onClick,
            modifier = modifier.size(size).semantics { this.contentDescription = contentDescription },
            enabled = enabled,
            content = content,
        )
        return
    }

    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressProgress by animateFloatAsState(
        targetValue = if (isPressed && enabled) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "glass-icon-press",
    )
    var focused by remember { mutableStateOf(false) }
    val contentColor = when {
        !enabled -> colors.onSurface.copy(alpha = 0.38f)
        tint.isSpecified -> colors.onPrimary
        else -> colors.onSurface
    }
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Box(
            modifier
                .liquidGlassControlSurface(
                    glass = glass,
                    shape = CircleShape,
                    surfaceColor = colors.surface,
                    tint = tint,
                    pressProgress = pressProgress,
                )
                .size(size)
                .then(
                    if (focused) Modifier.border(2.dp, colors.primary, CircleShape) else Modifier,
                )
                .clip(CircleShape)
                .onFocusChanged { focused = it.isFocused }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .semantics { this.contentDescription = contentDescription },
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@Composable
private fun LiquidGlassPillButton(
    onClick: () -> Unit,
    glass: LazerLiquidGlass,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.primary,
    content: @Composable RowScope.() -> Unit,
) {
    if (!glass.isEnabled) {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(100.dp),
            content = content,
        )
        return
    }

    val colors = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressProgress by animateFloatAsState(
        targetValue = if (isPressed && enabled) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium,
        ),
        label = "glass-pill-press",
    )
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(100.dp)
    val contentColor = if (enabled) colors.onPrimary else colors.onPrimary.copy(alpha = 0.45f)
    CompositionLocalProvider(LocalContentColor provides contentColor) {
        Row(
            modifier
                .liquidGlassControlSurface(
                    glass = glass,
                    shape = shape,
                    surfaceColor = colors.surface,
                    tint = tint,
                    pressProgress = pressProgress,
                )
                .heightIn(min = 50.dp)
                .then(
                    if (focused) Modifier.border(2.dp, colors.onPrimary, shape) else Modifier,
                )
                .clip(shape)
                .onFocusChanged { focused = it.isFocused }
                .clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                )
                .padding(horizontal = 20.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
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
    if (LocalLazerThemeEngine.current == LazerThemeEngine.MIUIX) {
        val colors = MiuixButtonDefaults.buttonColorsPrimary()
        CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides
                if (enabled) colors.contentColor else colors.disabledContentColor,
        ) {
            MiuixButton(
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                cornerRadius = cornerRadius ?: MiuixButtonDefaults.CornerRadius,
                colors = colors,
                content = content,
            )
        }
    } else if (cornerRadius != null) {
        Button(
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            shape = RoundedCornerShape(cornerRadius),
            content = content,
        )
    } else {
        Button(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}

@Composable
private fun ThemeTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
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
                onClick = onClick,
                modifier = modifier,
                enabled = enabled,
                colors = colors,
                content = content,
            )
        }
    } else {
        TextButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
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

@Composable
private fun ExperimentalBadge() {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
    ) {
        Text(
            tr("settings.glass.experimental"),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
fun AndroidLazerApp(initialListenTogetherInvitation: String? = null) {
    val context = LocalContext.current
    val controller = remember(context.applicationContext) { AndroidGatewayController(context.applicationContext) }
    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let(controller::joinListenTogether)
    }
    // Library/navigation only need transport changes; position ticks belong to the visible player.
    val playback by remember {
        AndroidPlaybackConnection.snapshot.map { it.copy(positionMillis = 0L, bufferedFraction = 0f) }
            .distinctUntilChanged()
    }.collectAsState(initial = AndroidPlaybackConnection.snapshot.value)
    var playerVisible by remember { mutableStateOf(false) }
    var artistChoices by remember { mutableStateOf<List<Artist>>(emptyList()) }
    var coverSaveRequest by remember { mutableStateOf<AndroidCoverSaveRequest?>(null) }
    var coverSaveTarget by remember { mutableStateOf<AndroidCoverSaveRequest?>(null) }
    val coverDocumentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        val request = coverSaveTarget
        coverSaveTarget = null
        if (uri != null && request != null) controller.saveArtwork(request.url, uri, request.title)
    }
    var requestedBackProgress by remember { mutableFloatStateOf(0f) }
    var isPredictiveBackRunning by remember { mutableStateOf(false) }
    var backSwipeEdge by remember { mutableStateOf(BackEventCompat.EDGE_LEFT) }
    var transformedBackLayer by remember { mutableStateOf<AndroidBackLayer?>(null) }

    val activeBackLayer = when {
        playerVisible -> AndroidBackLayer.PLAYER
        controller.activeArtist != null -> AndroidBackLayer.ARTIST
        controller.isSettingsVisible -> AndroidBackLayer.SETTINGS
        controller.activePlaylist != null -> AndroidBackLayer.PLAYLIST
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
    LaunchedEffect(playback.track?.id) { playback.track?.let(controller::loadLyrics) }

    // The currently visible top layer owns back. Gesture progress drives the same page that a
    // normal back press closes; cancelling the gesture eases that page back into place.
    PredictiveBackHandler(enabled = !controller.isLoginVisible && activeBackLayer != null) { events ->
        val layer = activeBackLayer ?: return@PredictiveBackHandler
        transformedBackLayer = layer
        isPredictiveBackRunning = true
        try {
            events.collect { event ->
                requestedBackProgress = event.progress
                backSwipeEdge = event.swipeEdge
            }
            when (layer) {
                AndroidBackLayer.PLAYER -> playerVisible = false
                AndroidBackLayer.ARTIST -> controller.closeArtist()
                AndroidBackLayer.SETTINGS -> controller.closeSettings()
                AndroidBackLayer.PLAYLIST -> controller.closePlaylist()
            }
        } finally {
            isPredictiveBackRunning = false
            requestedBackProgress = 0f
        }
    }

    val mainPage = when {
        controller.isSettingsVisible -> AndroidMainPage(AndroidMainPageKind.SETTINGS)
        controller.activeArtist != null -> AndroidMainPage(
            kind = AndroidMainPageKind.ARTIST,
            artist = controller.activeArtist,
            tracks = controller.activeArtistTracks,
            isLoading = controller.isArtistLoading,
        )
        controller.activePlaylist != null -> AndroidMainPage(
            kind = AndroidMainPageKind.PLAYLIST,
            playlist = controller.activePlaylist,
            tracks = controller.activePlaylistTracks,
            isLoading = controller.isPlaylistLoading,
        )
        else -> AndroidMainPage(AndroidMainPageKind.ROOT)
    }
    val systemConfiguration = LocalConfiguration.current
    val nowPlayingPaletteSeed = rememberAndroidArtworkSeed(
        playback.track.takeIf { controller.palette == LazerPalette.NowPlaying },
    )
    val paletteColorScheme = remember(
        controller.palette,
        controller.isDark,
        systemConfiguration,
        nowPlayingPaletteSeed,
    ) {
        when (val palette = controller.palette) {
            LazerPalette.Default -> null
            LazerPalette.System -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (controller.isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                null
            }
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
            LocalAndroidOpenArtists provides { artists ->
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
            LocalAndroidRequestCoverSave provides { coverSaveRequest = it },
        ) {
        val colors = MaterialTheme.colorScheme
        val view = LocalView.current
        val playFromQueue: (List<AndroidTrack>, AndroidTrack) -> Unit = { queue, track ->
            controller.play(queue, track)
            playerVisible = true
        }
        val shareListenTogether: (String) -> Unit = { url ->
            context.startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, url)
                    },
                    tr("listen_together.share"),
                ),
            )
        }
        if (!view.isInEditMode) {
            SideEffect {
                (view.context as? Activity)?.window?.let { window ->
                    WindowCompat.getInsetsController(window, view).apply {
                        isAppearanceLightStatusBars = !controller.isDark
                        isAppearanceLightNavigationBars = !controller.isDark
                    }
                }
            }
        }
        val wallpaper = controller.backgroundImage.takeIf {
            controller.backgroundMode == AndroidBackgroundMode.IMAGE
        }
        val usesNowPlayingPalette = controller.backgroundMode == AndroidBackgroundMode.NOW_PLAYING_DYNAMIC ||
            controller.backgroundMode == AndroidBackgroundMode.NOW_PLAYING_STATIC
        val hasVisualBackground = wallpaper != null || usesNowPlayingPalette
        // The slider controls the opacity of app surfaces above visual backgrounds. The image or
        // artwork palette itself stays opaque, so navigation transitions never expose another page.
        val uiAlpha = resolveLazerUiAlpha(hasVisualBackground, controller.backgroundAlpha)
        val pageBackgroundBackdrop = rememberLayerBackdrop()
        val liquidGlass = rememberLazerLiquidGlass(
            enabled = controller.liquidGlassEnabled,
            backgroundColor = colors.background,
            blurIntensity = controller.liquidGlassBlurIntensity,
        )
        val landscape = isLandscapeLayout()
        val floatingControlsInset = if (landscape) {
            if (playback.track != null) 60.dp + navigationBarBottomInset() else 0.dp
        } else if (liquidGlass.isEnabled) {
            navigationBarBottomInset() + if (playback.track != null) 176.dp else 104.dp
        } else {
            0.dp
        }
        // The Liquid Glass playlist owns the status-bar backdrop. Do not leave the global
        // wallpaper exposed above its artwork-derived background.
        val playlistOwnsStatusBarBackdrop = liquidGlass.isEnabled && mainPage.kind == AndroidMainPageKind.PLAYLIST
        Box(Modifier.fillMaxSize().background(colors.background)) {
            // One opaque, full-window sampling plane: visual background first, page content next.
            // Floating glass controls stay outside this box, so they never sample themselves.
            Box(Modifier.fillMaxSize().captureLiquidGlass(liquidGlass)) {
            // Keep the wallpaper and its scrim in one fixed, capturable canvas. Animated pages
            // reuse this exact canvas, so their interiors and any exposed transition gaps match.
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (hasVisualBackground) {
                            Modifier.layerBackdrop(pageBackgroundBackdrop)
                        } else {
                            Modifier
                        },
                    ),
            ) {
                // Include the opaque app base in the recorded canvas so transparent PNG
                // wallpapers cannot reveal another page underneath during overlap.
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
                    AndroidAlbumFlowBackground(
                        track = playback.track,
                        modifier = Modifier.fillMaxSize(),
                        cornerRadius = 0.dp,
                        veil = Color.Transparent,
                        animated = controller.backgroundMode == AndroidBackgroundMode.NOW_PLAYING_DYNAMIC &&
                            !playerVisible,
                        solid = controller.backgroundMode == AndroidBackgroundMode.NOW_PLAYING_STATIC,
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colors.background.copy(alpha = uiAlpha)),
                )
            }
            // Android 16 forces edge-to-edge. Keep the visual canvas under the status bar, while
            // placing every interactive root-page element below its dynamic inset.
            CompositionLocalProvider(
                LocalAndroidContentBottomInset provides floatingControlsInset,
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
                        .height(if (landscape || playlistOwnsStatusBarBackdrop) 0.dp else statusBarTopInset()),
                )
                Box(Modifier.weight(1f)) {
                    // Keep the root page mounted. Detail pages animate above it, so returning from
                    // a playlist cannot replay the root page or bottom-edge entrance animation.
                    AndroidRootContent(
                        controller = controller,
                        currentTrackId = playback.track?.id,
                        onPlay = playFromQueue,
                        onListenTogether = controller::openListenTogether,
                        showHeaderControls = !liquidGlass.isEnabled || landscape,
                        modifier = Modifier.fillMaxSize(),
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
                        contentKey = AndroidMainPage::contentKey,
                        label = "android-main-page",
                    ) { page ->
                        if (page.kind == AndroidMainPageKind.ROOT) {
                            Box(Modifier.fillMaxSize())
                            return@AnimatedContent
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .predictiveBackTransform(
                                    enabled = when (page.kind) {
                                        AndroidMainPageKind.ARTIST -> transformedBackLayer == AndroidBackLayer.ARTIST
                                        AndroidMainPageKind.PLAYLIST -> transformedBackLayer == AndroidBackLayer.PLAYLIST
                                        AndroidMainPageKind.SETTINGS -> transformedBackLayer == AndroidBackLayer.SETTINGS
                                        AndroidMainPageKind.ROOT -> false
                                    },
                                    progress = renderedBackProgress,
                                    swipeEdge = backSwipeEdge,
                                )
                                .then(
                                    if (hasVisualBackground) {
                                        Modifier.capturedPageBackground(pageBackgroundBackdrop)
                                    } else {
                                        Modifier
                                    },
                                ),
                            // With wallpaper, the captured canvas is an opaque visual page plane:
                            // it hides sibling content without adding a second translucent scrim.
                            color = if (hasVisualBackground) Color.Transparent else colors.background,
                            contentColor = colors.onBackground,
                        ) {
                            when (page.kind) {
                                AndroidMainPageKind.SETTINGS -> SettingsPage(controller)
                                AndroidMainPageKind.ARTIST -> page.artist?.let { artist ->
                                    ArtistPage(
                                        artist = artist,
                                        tracks = page.tracks,
                                        isLoading = page.isLoading,
                                        currentId = playback.track?.id,
                                        onBack = controller::closeArtist,
                                        onPlay = playFromQueue,
                                    )
                                }
                                AndroidMainPageKind.PLAYLIST -> page.playlist?.let { playlist ->
                                    PlaylistDetail(
                                        playlist = playlist,
                                        tracks = page.tracks,
                                        isLoading = page.isLoading,
                                        currentId = playback.track?.id,
                                        isPlaying = playback.isPlaying && !playerVisible,
                                        liquidGlassEnabled = controller.liquidGlassEnabled,
                                        liquidGlassBlurIntensity = controller.liquidGlassBlurIntensity,
                                        onBack = controller::closePlaylist,
                                        onPlay = playFromQueue,
                                    )
                                }
                                AndroidMainPageKind.ROOT -> Unit
                            }
                        }
                    }
                }
                if (!liquidGlass.isEnabled && !landscape) {
                    playback.track?.let { track ->
                        MiniPlayer(track, playback.isPlaying, playback.isPreparing, { playerVisible = true }) {
                            AndroidPlaybackConnection.toggle(context)
                        }
                    }
                    BottomDock(
                        selected = controller.destination,
                        onSelect = controller::selectDestination,
                    )
                }
            }
            }
            }

            if (liquidGlass.isEnabled && !landscape && mainPage.kind == AndroidMainPageKind.ROOT) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 12.dp, end = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LiquidGlassIconButton(
                        onClick = controller::openListenTogether,
                        contentDescription = tr("listen_together.open"),
                        glass = liquidGlass,
                        tint = if (controller.listenTogether != null) colors.primary else Color.Unspecified,
                    ) {
                        Icon(Icons.Outlined.Headphones, null)
                    }
                    LiquidGlassIconButton(
                        onClick = controller::toggleTheme,
                        contentDescription = tr("player.toggle_theme"),
                        glass = liquidGlass,
                    ) {
                        Icon(
                            if (controller.isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                            null,
                        )
                    }
                    LiquidGlassIconButton(
                        onClick = controller::openSettings,
                        contentDescription = tr("player.open_settings"),
                        glass = liquidGlass,
                    ) {
                        Icon(Icons.Outlined.Settings, null)
                    }
                }
            }

            if (landscape) {
                LandscapeNavigationRail(
                    controller = controller,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
            }
            if (liquidGlass.isEnabled || landscape) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(start = if (landscape) 64.dp else 0.dp)
                    .then(if (landscape) Modifier.navigationBarsPadding() else Modifier)) {
                    playback.track?.let { track ->
                        MiniPlayer(
                            track = track,
                            isPlaying = playback.isPlaying,
                            isPreparing = playback.isPreparing,
                            onOpen = { playerVisible = true },
                            glass = liquidGlass,
                            compact = landscape,
                            onToggle = { AndroidPlaybackConnection.toggle(context) },
                        )
                    }
                    if (!landscape) LiquidGlassBottomDock(
                        selected = controller.destination,
                        onSelect = controller::selectDestination,
                        glass = liquidGlass,
                    )
                }
            }

            controller.message?.let { text ->
                MessageBanner(
                    text = text,
                    modifier = Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(16.dp),
                    glass = liquidGlass,
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
                    snapshot = AndroidPlaybackConnection.snapshot.collectAsState().value,
                    lyricLines = controller.lyrics,
                    lyricsLoading = controller.lyricsLoading,
                    lyricsMessage = controller.lyricsMessage,
                    lyricFollowDelayMillis = controller.lyricFollowDelayMillis,
                    lyricAnimationSpeed = controller.lyricAnimationSpeed,
                    wordLyricsEnabled = controller.wordLyricsEnabled,
                    lyricGlowEnabled = controller.lyricGlowEnabled,
                    liquidGlassEnabled = controller.liquidGlassEnabled,
                    liquidGlassBlurIntensity = controller.liquidGlassBlurIntensity,
                    lyricFontSizeSp = controller.lyricFontSizeSp,
                    showFullLyrics = controller.showFullLyrics,
                    animateAlbumBackground = controller.backgroundMode == AndroidBackgroundMode.NOW_PLAYING_DYNAMIC,
                    solidAlbumBackground = controller.backgroundMode == AndroidBackgroundMode.NOW_PLAYING_STATIC,
                    isLiked = playback.track?.let { controller.isSongLiked(it.id) } == true,
                    onToggleLiked = { playback.track?.let(controller::toggleSongLiked) },
                    onDismiss = { playerVisible = false },
                    onToggle = { AndroidPlaybackConnection.toggle(context) },
                    onPrevious = { AndroidPlaybackConnection.previous(context) },
                    onNext = { AndroidPlaybackConnection.next(context) },
                    onSeek = controller::seekTo,
                    modifier = Modifier
                        .fillMaxSize()
                        .predictiveBackTransform(
                            enabled = transformedBackLayer == AndroidBackLayer.PLAYER,
                            progress = renderedBackProgress,
                            swipeEdge = backSwipeEdge,
                        ),
                )
            }
            if (controller.isListenTogetherVisible) {
                ListenTogetherSheet(
                    controller = controller,
                    onScan = {
                        scanLauncher.launch(
                            ScanOptions().apply {
                                setBeepEnabled(false)
                                setPrompt(tr("listen_together.scan_prompt"))
                            },
                        )
                    },
                    onShare = shareListenTogether,
                )
            }
            if (controller.isLoginVisible) LoginSheet(controller, liquidGlass)
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
                    coverDocumentLauncher.launch(androidCoverFileName(request.title))
                },
            )
            if ((context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
                AndroidDebugWatermark(Modifier.fillMaxSize())
            }
        }
    }
}
}

@Composable
private fun AndroidDebugWatermark(modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val watermarkText = remember {
        val model = Build.MODEL.orEmpty().ifBlank { "Unknown device" }
        val fingerprint = Build.FINGERPRINT.orEmpty().ifBlank { "Unknown fingerprint" }
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
private fun AndroidRootContent(
    controller: AndroidGatewayController,
    currentTrackId: Long?,
    onPlay: (List<AndroidTrack>, AndroidTrack) -> Unit,
    onListenTogether: () -> Unit,
    showHeaderControls: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        if (!isLandscapeLayout()) {
            MobileHeader(
                controller = controller,
                showControls = showHeaderControls,
                onListenTogether = onListenTogether,
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
            label = "android-root",
        ) { destination ->
            when (destination) {
                AndroidRootDestination.HOME -> HomePage(controller, currentTrackId) { track ->
                    onPlay(controller.homeTracks, track)
                }
                AndroidRootDestination.SEARCH -> SearchPage(controller, currentTrackId) { track ->
                    onPlay(controller.searchResults, track)
                }
                AndroidRootDestination.LIBRARY -> LibraryPage(controller, currentTrackId) { track ->
                    onPlay(controller.homeTracks, track)
                }
                AndroidRootDestination.ME -> MePage(controller)
            }
        }
    }
}

@Composable
private fun LandscapeNavigationRail(
    controller: AndroidGatewayController,
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
                AndroidRootDestination.entries.forEach { destination ->
                    val selectedDestination = !controller.isSettingsVisible &&
                        controller.activeArtist == null && controller.activePlaylist == null &&
                        controller.destination == destination
                    IconButton(
                        onClick = { controller.selectDestination(destination) },
                        modifier = Modifier.size(48.dp).semantics { selected = selectedDestination },
                        colors = IconButtonDefaults.iconButtonColors(
                            containerColor = if (selectedDestination) colors.primaryContainer else Color.Transparent,
                            contentColor = if (selectedDestination) colors.onPrimaryContainer else colors.onSurfaceVariant,
                        ),
                    ) { Icon(destination.icon(), destination.label) }
                }
            }
            IconButton(
                onClick = controller::openListenTogether,
                modifier = Modifier.size(48.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    contentColor = if (controller.listenTogether != null) colors.primary else colors.onSurfaceVariant,
                ),
            ) { Icon(Icons.Outlined.Headphones, tr("listen_together.open")) }
            IconButton(
                onClick = controller::openSettings,
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
private fun HomePage(controller: AndroidGatewayController, currentId: Long?, onPlay: (AndroidTrack) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 12.dp + LocalAndroidContentBottomInset.current),
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
            else -> items(controller.homeTracks, key = AndroidTrack::id) { TrackRow(it, it.id == currentId) { onPlay(it) } }
        }
    }
}

@Composable
private fun SearchPage(controller: AndroidGatewayController, currentId: Long?, onPlay: (AndroidTrack) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val searchGlass = rememberLazerLiquidGlass(
        enabled = controller.liquidGlassEnabled,
        backgroundColor = colors.background,
        blurIntensity = controller.liquidGlassBlurIntensity,
    )
    val searchShape = RoundedCornerShape(28.dp)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().captureLiquidGlass(searchGlass),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 12.dp + LocalAndroidContentBottomInset.current),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Text(tr("search.title"), style = MaterialTheme.typography.headlineMedium) }
            if (searchGlass.isEnabled) {
                // The fixed search control floats above this reserved space and samples the list.
                item { Spacer(Modifier.height(58.dp)) }
            } else {
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
            }
            when {
                controller.searchQuery.isBlank() -> item { QuietState(tr("search.empty")) }
                controller.isSearching -> item { QuietState(tr("search.searching")) }
                controller.searchResults.isEmpty() -> item { QuietState(tr("search.no_results")) }
                else -> items(controller.searchResults, key = AndroidTrack::id) {
                    TrackRow(it, it.id == currentId) { onPlay(it) }
                }
            }
        }
        if (searchGlass.isEnabled) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 48.dp, end = 16.dp),
            ) {
                OutlinedTextField(
                    value = controller.searchQuery,
                    onValueChange = controller::updateSearchQuery,
                    modifier = Modifier
                        .liquidGlassFrostedSurface(
                            glass = searchGlass,
                            shape = searchShape,
                            surfaceColor = colors.surface,
                            blurRadius = 6.dp,
                        )
                        .fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    placeholder = { Text(tr("search.hint")) },
                    singleLine = true,
                    shape = searchShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedBorderColor = colors.primary.copy(alpha = 0.62f),
                        unfocusedBorderColor = Color.Transparent,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
            }
        }
    }
}

@Composable
private fun LibraryPage(controller: AndroidGatewayController, currentId: Long?, onPlay: (AndroidTrack) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 12.dp + LocalAndroidContentBottomInset.current),
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
            else -> items(controller.userPlaylists, key = AndroidPlaylist::id) { PlaylistListRow(it, controller::openPlaylist) }
        }
        if (controller.homeTracks.isNotEmpty()) {
            item { SectionTitle(tr("library.continue")) }
            items(controller.homeTracks.take(5), key = AndroidTrack::id) { TrackRow(it, it.id == currentId) { onPlay(it) } }
        }
    }
}

@Composable
private fun MePage(controller: AndroidGatewayController) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 12.dp + LocalAndroidContentBottomInset.current),
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
                else -> items(controller.userPlaylists.take(3), key = AndroidPlaylist::id) { PlaylistListRow(it, controller::openPlaylist) }
            }
            item {
                ThemeTextButton(onClick = { controller.selectDestination(AndroidRootDestination.LIBRARY) }) {
                    Text(tr("me.view_library"))
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(controller: AndroidGatewayController, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val systemMonetAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val context = LocalContext.current
    val backgroundPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(controller::setBackgroundImage) }
    // The platform asks for the microphone even though the capture only reads back our own session,
    // so the switch stays off until the user grants it.
    var microphoneGranted by remember { mutableStateOf(context.hasRecordAudioPermission()) }
    val microphoneRequest = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        microphoneGranted = granted
        if (granted) {
            controller.updateAudioReactiveLevels(true)
        } else {
            controller.reportAudioLevelsPermissionDenied()
        }
    }
    val audioLevelsEnabled = controller.audioReactiveLevels && microphoneGranted
    var isAudioQualitySheetVisible by remember { mutableStateOf(false) }
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
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 18.dp + LocalAndroidContentBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            if (isLandscapeLayout()) {
                Text(tr("settings.title"), style = MaterialTheme.typography.headlineSmall)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = controller::closeSettings) {
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
        if (controller.liquidGlassEnabled) {
            item {
                SettingsCard {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    tr("settings.glass.blur.title"),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    tr("settings.glass.blur.hint"),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "${(controller.liquidGlassBlurIntensity * 100).roundToInt()}%",
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.primary,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        LazerSlider(
                            engine = controller.themeEngine,
                            value = controller.liquidGlassBlurIntensity,
                            onValueChange = controller::updateLiquidGlassBlurIntensity,
                            modifier = Modifier.fillMaxWidth(),
                            valueRange = 0f..1f,
                        )
                    }
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
                            options = AndroidBackgroundMode.entries,
                            selected = controller.backgroundMode,
                            label = ::backgroundModeLabel,
                            onSelected = { mode ->
                                controller.updateBackgroundMode(mode)
                                if (mode == AndroidBackgroundMode.IMAGE && controller.backgroundImage == null) {
                                    backgroundPicker.launch("image/*")
                                }
                            },
                        )
                    }

                    if (controller.backgroundMode == AndroidBackgroundMode.IMAGE) {
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
                            ThemeTextButton(onClick = { backgroundPicker.launch("image/*") }) {
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
                        AndroidBackgroundMode.SOLID -> false
                        AndroidBackgroundMode.IMAGE -> controller.backgroundImage != null
                        AndroidBackgroundMode.NOW_PLAYING_DYNAMIC,
                        AndroidBackgroundMode.NOW_PLAYING_STATIC -> true
                    }
                    if (hasSelectedVisualBackground) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("settings.background.surface_alpha"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            LazerSlider(
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

                    if (controller.backgroundMode == AndroidBackgroundMode.IMAGE && controller.backgroundImage != null) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Switch) {
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
                                LazerSlider(
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
                        .clickable(role = Role.Button) { isAudioQualitySheetVisible = true }
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
                        .clickable(role = Role.Switch) {
                            controller.updateIndependentPlayback(!controller.independentPlayback)
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.independent.title"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (controller.independentPlayback) {
                                tr("settings.independent.on")
                            } else {
                                tr("settings.independent.off")
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
                        .clickable(
                            enabled = !controller.independentPlayback,
                            role = Role.Switch,
                        ) { controller.updateExclusiveAudio(!controller.exclusiveAudio) }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.exclusive.title"), style = MaterialTheme.typography.titleSmall)
                        Text(
                            if (controller.independentPlayback) {
                                tr("settings.exclusive.independent")
                            } else if (controller.exclusiveAudio) {
                                tr("settings.exclusive.on")
                            } else {
                                tr("settings.exclusive.off.android")
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
        item {
            SettingsCard {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Switch) {
                            when {
                                audioLevelsEnabled -> controller.updateAudioReactiveLevels(false)
                                microphoneGranted -> controller.updateAudioReactiveLevels(true)
                                else -> microphoneRequest.launch(Manifest.permission.RECORD_AUDIO)
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
        item { SectionTitle(tr("settings.lyrics")) }
        item {
            SettingsCard(
                modifier = Modifier.clickable(role = Role.Switch) {
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
                modifier = Modifier.clickable(role = Role.Switch) {
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
                    LazerSlider(
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
                    LazerSlider(
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
                modifier = Modifier.clickable(role = Role.Switch) {
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
                    LazerSlider(
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
                        .clickable(role = Role.Button) { isCacheSheetVisible = true }
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
                modifier = Modifier.clickable(role = Role.Button) {
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
                    clipboard.setClipEntry(
                        ClipEntry(ClipData.newPlainText(tr("login.cookie.label"), cookie)),
                    )
                    cookieCopied = true
                }
            },
            onDismiss = { isCookieSheetVisible = false },
        )
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
                DropdownMenuItem(
                    text = {
                        Text(
                            label(option),
                            color = if (option == selected) colors.primary else colors.onSurface,
                        )
                    },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
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

private fun backgroundModeLabel(mode: AndroidBackgroundMode): String = when (mode) {
    AndroidBackgroundMode.SOLID -> tr("settings.background.mode.solid")
    AndroidBackgroundMode.IMAGE -> tr("settings.background.mode.image")
    AndroidBackgroundMode.NOW_PLAYING_DYNAMIC -> tr("settings.background.mode.now_playing_dynamic")
    AndroidBackgroundMode.NOW_PLAYING_STATIC -> tr("settings.background.mode.now_playing_static")
}

private fun backgroundModeHint(mode: AndroidBackgroundMode): String = when (mode) {
    AndroidBackgroundMode.SOLID -> tr("settings.background.hint.solid")
    AndroidBackgroundMode.IMAGE -> tr("settings.background.hint.image")
    AndroidBackgroundMode.NOW_PLAYING_DYNAMIC -> tr("settings.background.hint.now_playing_dynamic")
    AndroidBackgroundMode.NOW_PLAYING_STATIC -> tr("settings.background.hint.now_playing_static")
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
            ANDROID_AUDIO_QUALITY_OPTIONS.forEach { quality ->
                val isSelected = quality == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .selectable(
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

@Composable
private fun ArtistPage(
    artist: Artist,
    tracks: List<AndroidTrack>,
    isLoading: Boolean,
    currentId: Long?,
    onBack: () -> Unit,
    onPlay: (List<AndroidTrack>, AndroidTrack) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp + LocalAndroidContentBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("common.back")) }
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
            else -> items(tracks, key = AndroidTrack::id) { track ->
                TrackRow(track, track.id == currentId) { onPlay(tracks, track) }
            }
        }
    }
}

@Composable
private fun PlaylistDetail(
    playlist: AndroidPlaylist,
    tracks: List<AndroidTrack>,
    isLoading: Boolean,
    currentId: Long?,
    isPlaying: Boolean,
    liquidGlassEnabled: Boolean,
    liquidGlassBlurIntensity: Float,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onPlay: (List<AndroidTrack>, AndroidTrack) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pageGlass = rememberLazerLiquidGlass(
        enabled = liquidGlassEnabled,
        backgroundColor = colors.background,
        blurIntensity = liquidGlassBlurIntensity,
    )
    if (pageGlass.isEnabled) {
        LiquidGlassPlaylistDetail(
            playlist = playlist,
            tracks = tracks,
            isLoading = isLoading,
            currentId = currentId,
            isPlaying = isPlaying,
            glass = pageGlass,
            modifier = modifier,
            onBack = onBack,
            onPlay = onPlay,
        )
    } else {
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
}

@Composable
private fun StandardPlaylistDetail(
    playlist: AndroidPlaylist,
    tracks: List<AndroidTrack>,
    isLoading: Boolean,
    currentId: Long?,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onPlay: (List<AndroidTrack>, AndroidTrack) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentTrackIndex = tracks.indexOfFirst { it.id == currentId }
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, (if (currentTrackIndex >= 0) 96.dp else 20.dp) + LocalAndroidContentBottomInset.current),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("playlist.back")) }
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
            items(tracks, key = AndroidTrack::id) { track ->
                TrackRow(track, track.id == currentId) { onPlay(tracks, track) }
            }
        }
        if (currentTrackIndex >= 0) {
            val locateCurrent: () -> Unit = {
                scope.launch { listState.animateScrollToItem(currentTrackIndex + 2) }
            }
            ExtendedFloatingActionButton(
                onClick = locateCurrent,
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

@Composable
private fun LiquidGlassPlaylistDetail(
    playlist: AndroidPlaylist,
    tracks: List<AndroidTrack>,
    isLoading: Boolean,
    currentId: Long?,
    isPlaying: Boolean,
    glass: LazerLiquidGlass,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onPlay: (List<AndroidTrack>, AndroidTrack) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val currentTrackIndex = tracks.indexOfFirst { it.id == currentId }
    // Keep the cover as the visual anchor without making it dominate the song list.
    val artworkSize = if (isLandscapeLayout()) 112.dp else (LocalConfiguration.current.screenWidthDp.dp * 0.44f).coerceIn(140.dp, 200.dp)
    val primaryText = Color(0xFFF4FAFD)
    val secondaryText = primaryText.copy(alpha = 0.78f)
    val prominentInk = Color(0xFF183246)
    val artworkShape = RoundedCornerShape(28.dp)
    val hasTracks = tracks.isNotEmpty()
    // Two backdrops, kept apart on purpose. The hero controls sit inside the list and sample the
    // flow background; the back button sits outside the list and samples the pair. Neither samples
    // a layer that contains it, which is what keeps the capture acyclic.
    val backGlass = rememberLazerLiquidGlass(
        enabled = glass.isEnabled,
        backgroundColor = colors.background,
        blurIntensity = glass.blurIntensity,
    )

    Box(modifier.fillMaxSize()) {
        // Everything the back button samples: the flow background plus the list that scrolls under
        // it.
        Box(Modifier.fillMaxSize().captureLiquidGlass(backGlass)) {
            AndroidPlaylistFlowBackground(
                playlist = playlist,
                modifier = Modifier.fillMaxSize().captureLiquidGlass(glass),
                cornerRadius = 0.dp,
                veil = Color(0xFF0B2637).copy(alpha = 0.82f),
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().then(if (isLandscapeLayout()) Modifier else Modifier.statusBarsPadding()),
                contentPadding = PaddingValues(
                    top = 39.dp,
                    bottom = 28.dp + LocalAndroidContentBottomInset.current,
                ),
            ) {
                item(key = "playlist-hero") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        AdaptiveDetailHeader(artwork = {
                        MobileArtwork(
                            url = playlist.coverUrl,
                            label = playlist.title,
                            modifier = Modifier
                                .size(artworkSize)
                                .border(1.dp, Color.White.copy(alpha = 0.20f), artworkShape),
                            cornerRadius = 28.dp,
                        )
                        }) {
                        Text(
                            text = playlist.title,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = primaryText,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (playlist.subtitle.isNotBlank()) {
                            Spacer(Modifier.height(7.dp))
                            Text(
                                text = playlist.subtitle,
                                style = MaterialTheme.typography.titleMedium,
                                color = primaryText.copy(alpha = 0.88f),
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = tr("playlist.tracks", tracks.size.takeIf { it > 0 } ?: playlist.trackCount),
                            style = MaterialTheme.typography.labelMedium,
                            color = secondaryText,
                        )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LiquidGlassIconButton(
                                onClick = {
                                    val shuffled = tracks.shuffled()
                                    shuffled.firstOrNull()?.let { onPlay(shuffled, it) }
                                },
                                contentDescription = tr("player.shuffle"),
                                glass = glass,
                                enabled = hasTracks,
                                size = 56.dp,
                            ) {
                                Icon(
                                    Icons.Filled.Shuffle,
                                    null,
                                    Modifier.size(24.dp),
                                    tint = primaryText.copy(alpha = if (hasTracks) 1f else 0.38f),
                                )
                            }
                            LiquidGlassPillButton(
                                onClick = { tracks.firstOrNull()?.let { onPlay(tracks, it) } },
                                glass = glass,
                                modifier = Modifier.widthIn(min = 152.dp, max = 210.dp),
                                enabled = hasTracks,
                                tint = Color.White.copy(alpha = if (hasTracks) 1f else 0.42f),
                            ) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    null,
                                    Modifier.size(22.dp),
                                    tint = prominentInk.copy(alpha = if (hasTracks) 1f else 0.44f),
                                )
                                Text(
                                    tr("playlist.play_all"),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = prominentInk.copy(alpha = if (hasTracks) 1f else 0.44f),
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            if (currentTrackIndex >= 0) {
                                LiquidGlassIconButton(
                                    onClick = {
                                        scope.launch { listState.animateScrollToItem(currentTrackIndex + 1) }
                                    },
                                    contentDescription = tr("playlist.locate"),
                                    glass = glass,
                                    size = 56.dp,
                                ) {
                                    Icon(Icons.Outlined.MyLocation, null, Modifier.size(23.dp), tint = primaryText)
                                }
                            } else {
                                Spacer(Modifier.size(56.dp))
                            }
                        }
                        Spacer(Modifier.height(28.dp))
                    }
                }

                if (tracks.isEmpty()) {
                    item(key = "playlist-state") {
                        Text(
                            text = if (isLoading) tr("playlist.opening") else tr("playlist.empty"),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 34.dp),
                            style = MaterialTheme.typography.bodyLarge,
                            color = secondaryText,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                        LiquidGlassPlaylistTrackRow(
                            index = index,
                            track = track,
                            current = track.id == currentId,
                            isPlaying = isPlaying,
                            primaryText = primaryText,
                            secondaryText = secondaryText,
                            onClick = { onPlay(tracks, track) },
                        )
                    }
                }
            }
        }

        LiquidGlassIconButton(
            onClick = onBack,
            contentDescription = tr("playlist.back"),
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(start = 18.dp, top = 6.dp),
            glass = backGlass,
            size = 56.dp,
        ) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, null, Modifier.size(27.dp), tint = primaryText)
        }
    }
}

@Composable
private fun LiquidGlassPlaylistTrackRow(
    index: Int,
    track: AndroidTrack,
    current: Boolean,
    isPlaying: Boolean,
    primaryText: Color,
    secondaryText: Color,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(if (current) Color.White.copy(alpha = 0.09f) else Color.Transparent)
                .clickable(role = Role.Button, onClick = onClick)
                .padding(start = 20.dp, end = 22.dp, top = 13.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(36.dp), contentAlignment = Alignment.CenterStart) {
                if (current) {
                    NowPlayingBars(color = primaryText, isPlaying = isPlaying)
                } else {
                    Text(
                        text = (index + 1).toString(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = secondaryText,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                TranslatedTrackTitle(track.translatedTitle, secondaryText)
                if (track.artist.isNotBlank()) {
                    AndroidArtistNames(
                        artists = track.artists,
                        fallback = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = secondaryText,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                text = track.durationLabel,
                style = MaterialTheme.typography.labelSmall,
                color = secondaryText,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 56.dp, end = 20.dp)
                .height(1.dp)
                .background(Color.White.copy(alpha = 0.12f)),
        )
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
    val liveLevels = if (isPlaying) AndroidAudioLevels.levels.collectAsState().value else null
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
    controller: AndroidGatewayController,
    showControls: Boolean,
    onListenTogether: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Lazer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        }
        if (showControls) {
            IconButton(onClick = onListenTogether) {
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
            IconButton(onClick = controller::toggleTheme) {
                Icon(
                    if (controller.isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                    tr("player.toggle_theme"),
                )
            }
            IconButton(onClick = controller::openSettings) {
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
private fun PlaylistStrip(playlists: List<AndroidPlaylist>, onOpen: (AndroidPlaylist) -> Unit) {
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
            items(playlists, key = AndroidPlaylist::id) { playlist ->
                Column(
                    Modifier
                        .width(158.dp)
                        .clip(tileShape)
                        .clickable(
                            interactionSource = null,
                            indication = tileRipple,
                            role = Role.Button,
                            onClick = { onOpen(playlist) },
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
private fun PlaylistListRow(playlist: AndroidPlaylist, onOpen: (AndroidPlaylist) -> Unit) {
    // The stock bounded ripple dies at the row's half diagonal; the oversized radius lets a tap
    // anywhere light the full row width, matching the playlist strip tiles.
    val rowRipple = ripple(bounded = true, radius = 260.dp)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(
            interactionSource = null,
            indication = rowRipple,
            role = Role.Button,
            onClick = { onOpen(playlist) },
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
private fun TrackRow(track: AndroidTrack, current: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(15.dp))
            .background(if (current) colors.primaryContainer.copy(alpha = 0.58f) else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp),
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
                AndroidArtistNames(
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
    val imageContext = LocalContext.current
    val imageModel = remember(url, decodeSizePx, imageContext) {
        if (decodeSizePx == null) url else coil3.request.ImageRequest.Builder(imageContext)
            .data(url)
            .size(decodeSizePx, decodeSizePx)
            .build()
    }
    val colors = MaterialTheme.colorScheme
    val requestSave = LocalAndroidRequestCoverSave.current
    val onLongPress: (() -> Unit)? = if (saveOnLongPress && !url.isNullOrBlank()) {
        { requestSave(AndroidCoverSaveRequest(url, label)) }
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
private fun artworkGestures(
    url: String?,
    label: String,
    onClick: (() -> Unit)?,
    onLongPress: (() -> Unit)?,
): Modifier = when {
    onClick != null -> Modifier.combinedClickable(
        role = Role.Button,
        onLongClick = onLongPress,
        onClick = onClick,
    )
    onLongPress != null -> Modifier.pointerInput(url, label) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (awaitLongPressOrCancellation(down.id) == null) return@awaitEachGesture
            onLongPress()
            // Consume through the release: the clickable around the artwork would otherwise start
            // playing the track as the finger leaves the screen.
            var pressed = true
            while (pressed) {
                val event = awaitPointerEvent()
                pressed = event.changes.any { it.pressed }
                event.changes.forEach { it.consume() }
            }
        }
    }
    else -> Modifier
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
    track: AndroidTrack,
    isPlaying: Boolean,
    isPreparing: Boolean,
    onOpen: () -> Unit,
    glass: LazerLiquidGlass = LazerLiquidGlass.Disabled,
    compact: Boolean = false,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val content: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (compact) 12.dp else if (glass.isEnabled) 14.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MobileArtwork(
                track.coverUrl,
                track.title,
                Modifier.size(if (compact) 40.dp else if (glass.isEnabled) 44.dp else 48.dp),
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
                    AndroidArtistNames(track.artists, track.artist, MaterialTheme.typography.labelSmall, colors.onSurfaceVariant)
                }
            }
            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(42.dp),
                colors = IconButtonDefaults.iconButtonColors(
                    containerColor = if (glass.isEnabled) Color.Transparent else colors.primaryContainer,
                    contentColor = if (glass.isEnabled) colors.onSurface else colors.onPrimaryContainer,
                ),
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (isPlaying) tr("player.pause") else tr("player.play"),
                )
            }
        }
    }

    if (glass.isEnabled) {
        val shape = RoundedCornerShape(if (compact) 24.dp else 36.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = if (compact) 8.dp else 16.dp, end = if (compact) 8.dp else 16.dp, bottom = if (compact) 0.dp else 2.dp)
                .height(if (compact) 60.dp else 72.dp)
                .liquidGlassSurface(glass, shape, colors.surface, blurRadius = 10.dp)
                .clip(shape)
                .clickable(role = Role.Button, onClick = onOpen)
                .semantics { contentDescription = tr("player.now_playing") },
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    } else {
        Surface(
            modifier = Modifier.fillMaxWidth().height(if (compact) 60.dp else 76.dp).clickable(role = Role.Button, onClick = onOpen),
            color = colors.surface.copy(alpha = 0.98f * LocalLazerUiAlpha.current),
            border = BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.78f)),
            content = content,
        )
    }
}

@Composable
private fun BottomDock(
    selected: AndroidRootDestination,
    onSelect: (AndroidRootDestination) -> Unit,
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
            AndroidRootDestination.entries.forEach { destination ->
                MiuixNavigationBarItem(
                    selected = selected == destination,
                    onClick = { onSelect(destination) },
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
        AndroidRootDestination.entries.forEach { destination ->
            NavigationBarItem(
                selected = selected == destination,
                onClick = { onSelect(destination) },
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
private fun LiquidGlassBottomDock(
    selected: AndroidRootDestination,
    onSelect: (AndroidRootDestination) -> Unit,
    glass: LazerLiquidGlass,
) {
    val colors = MaterialTheme.colorScheme
    val pageBackdrop = glass.backdrop ?: return
    val isDark = colors.background.luminance() < 0.5f
    val searchDestination = AndroidRootDestination.SEARCH
    val destinations = AndroidRootDestination.entries.filterNot { it == searchDestination }
    val barShape = RoundedCornerShape(30.dp)
    val selectorShape = RoundedCornerShape(22.dp)
    val density = androidx.compose.ui.platform.LocalDensity.current
    // While a long press is active the droplet follows the finger; otherwise `pressCenter` is null
    // and the droplet rests under the selected tab.
    var pressCenter by remember { mutableStateOf<Offset?>(null) }
    var pressedDestination by remember { mutableStateOf<AndroidRootDestination?>(null) }
    // Set briefly on a tap while the droplet slides to the tapped tab. During the slide every icon
    // except the target drops behind the droplet, so only the target stays on top.
    var switchingTo by remember { mutableStateOf<AndroidRootDestination?>(null) }
    var keyboardFocusedDestination by remember { mutableStateOf<AndroidRootDestination?>(null) }
    var lastMainDestination by remember {
        mutableStateOf(selected.takeUnless { it == searchDestination } ?: destinations.first())
    }
    LaunchedEffect(selected) {
        if (selected != searchDestination) lastMainDestination = selected
    }
    val activeDestination = pressedDestination ?: lastMainDestination
    val activeIndex = destinations.indexOf(activeDestination).coerceAtLeast(0)
    // Captures the bar glass only, so the droplet can refract it as a second nested lens. The
    // droplet is a sibling of this layer, so there is no cycle.
    val dockBackdrop = rememberLayerBackdrop()
    val nestedGlass = rememberCombinedBackdrop(pageBackdrop, dockBackdrop)

    // Manual bottom gesture-bar inset instead of navigationBarsPadding, so the floating glass dock
    // and a custom wallpaper do not fight over the system nav-bar region.
    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 8.dp + navigationBarBottomInset()),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(end = 72.dp)) {
        val slotWidth = maxWidth / destinations.size
        val slotWidthPx = with(density) { slotWidth.toPx() }
        val selectorWidth = slotWidth - 10.dp
        val isDragging = pressCenter != null
        val selectorExpansion by animateDpAsState(
            targetValue = if (isDragging) 6.dp else 0.dp,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
            label = "selector-expansion",
        )
        val selectorLift by animateDpAsState(
            targetValue = if (isDragging) 2.dp else 0.dp,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
            label = "selector-lift",
        )
        val pressedIconScale by animateFloatAsState(
            targetValue = if (isDragging) 1.08f else 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
            label = "pressed-icon-scale",
        )
        val renderedSelectorWidth = selectorWidth + selectorExpansion
        val renderedSelectorHeight = 50.dp + selectorExpansion
        val selectorCenterX by animateDpAsState(
            targetValue = with(density) {
                val halfSelectorWidth = renderedSelectorWidth.toPx() / 2f
                val rawCenter = pressCenter?.x ?: (slotWidthPx * activeIndex + slotWidthPx / 2f)
                rawCenter.coerceIn(
                    minimumValue = halfSelectorWidth,
                    maximumValue = maxWidth.toPx() - halfSelectorWidth,
                ).toDp()
            },
            animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
            label = "selector-x",
        )

        // Render priority: 0 = above the glass, 1 = behind it (captured into dockBackdrop so the
        // droplet refracts it). At rest every icon is priority 0. While a long press is active, or
        // during a tap-to-switch, non-target icons become priority 1.
        LaunchedEffect(switchingTo) {
            if (switchingTo != null) {
                kotlinx.coroutines.delay(420)
                switchingTo = null
            }
        }
        val renderIcons: @Composable (visible: (AndroidRootDestination) -> Boolean) -> Unit = { visible ->
            Row(Modifier.fillMaxSize().clearAndSetSemantics { }) {
                destinations.forEach { destination ->
                    // Always keep the slot so both layers stay aligned; only the content toggles.
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (visible(destination)) {
                            val active = destination == activeDestination &&
                                (isDragging || selected != searchDestination)
                            val isPressedTarget = isDragging && active
                            val activeColor = if (isPressedTarget) colors.primary else colors.onSurface
                            Column(
                                modifier = Modifier.graphicsLayer {
                                    val scale = if (isPressedTarget) pressedIconScale else 1f
                                    scaleX = scale
                                    scaleY = scale
                                },
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(
                                    destination.icon(),
                                    null,
                                    Modifier.size(20.dp),
                                    tint = if (active) activeColor else colors.onSurfaceVariant,
                                )
                                Text(
                                    destination.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (active) activeColor else colors.onSurfaceVariant,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
        val priorityOne: (AndroidRootDestination) -> Boolean = when {
            pressCenter != null -> { destination -> destination != activeDestination }
            switchingTo != null -> { destination -> destination != switchingTo }
            else -> { _ -> false }
        }
        val priorityZero: (AndroidRootDestination) -> Boolean = { destination -> !priorityOne(destination) }

        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                // One gesture handler for the whole bar: quick release selects a tab, holding ~0.5s
                // starts the finger-following droplet. Per-item clickable is intentionally omitted
                // so it cannot swallow the long press.
                .pointerInput(slotWidthPx) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val downIndex = (down.position.x / slotWidthPx).toInt()
                            .coerceIn(0, destinations.lastIndex)
                        val longPress = awaitLongPressOrCancellation(down.id)
                        if (longPress == null) {
                            val target = destinations[downIndex]
                            switchingTo = target
                            onSelect(target)
                            return@awaitEachGesture
                        }
                        pressCenter = longPress.position
                        pressedDestination = destinations.getOrNull(
                            (longPress.position.x / slotWidthPx).toInt(),
                        ) ?: destinations[downIndex]
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            pressCenter = change.position
                            pressedDestination = destinations.getOrNull(
                                (change.position.x / slotWidthPx).toInt(),
                            ) ?: pressedDestination
                            change.consume()
                        }
                        pressedDestination?.let(onSelect)
                        pressCenter = null
                        pressedDestination = null
                    }
                },
        ) {
            // Layer 1: the thick glass bar. Captured into dockBackdrop for the droplet to sample.
            // While pressing, the icons live here too (priority 1) so the droplet can refract them.
            Box(Modifier.fillMaxSize().layerBackdrop(dockBackdrop)) {
                // Near-colourless thick glass: strong lens refraction + chromatic dispersion, a
                // very faint milky surface, ambient edge highlight and inner shadow for depth.
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawBackdrop(
                            backdrop = pageBackdrop,
                            shape = { barShape },
                            effects = {
                                vibrancy()
                                blur(glass.scaledBlurRadius(8.dp).toPx())
                                lens(
                                    refractionHeight = 16.dp.toPx(),
                                    refractionAmount = 28.dp.toPx(),
                                    depthEffect = true,
                                    chromaticAberration = false,
                                )
                            },
                            highlight = { Highlight.Plain.copy(alpha = 0.58f) },
                            innerShadow = { InnerShadow(radius = 8.dp, color = Color.Black.copy(alpha = 0.06f)) },
                            onDrawSurface = {
                                drawRect(colors.surface.copy(alpha = if (isDark) 0.20f else 0.28f))
                            },
                        ),
                )
                renderIcons(priorityOne)
            }

            // Layer 2: the selected droplet. Always an empty lens; it never draws an icon, so there
            // is nothing to fly in when it returns to its slot.
            Box(
                Modifier
                    .graphicsLayer {
                        alpha = if (selected == searchDestination && !isDragging) 0f else 1f
                    }
                    .offset {
                        IntOffset(
                            x = (selectorCenterX.toPx() - renderedSelectorWidth.toPx() / 2f).roundToInt(),
                            y = (((64.dp - renderedSelectorHeight) / 2f) - selectorLift).toPx().roundToInt(),
                        )
                    }
                    .size(renderedSelectorWidth, renderedSelectorHeight)
                    .drawBackdrop(
                        backdrop = nestedGlass,
                        shape = { selectorShape },
                        effects = {
                            vibrancy()
                            blur(
                                glass.scaledBlurRadius(
                                    if (isDragging) 4.dp else 2.5.dp,
                                ).toPx(),
                            )
                            lens(
                                refractionHeight = 14.dp.toPx(),
                                refractionAmount = 32.dp.toPx(),
                                depthEffect = true,
                                chromaticAberration = false,
                            )
                        },
                        highlight = { Highlight.Plain.copy(alpha = 0.76f) },
                        innerShadow = { InnerShadow(radius = 7.dp, color = Color.Black.copy(alpha = 0.06f)) },
                        onDrawSurface = {
                            drawRect(
                                colors.primaryContainer.copy(
                                    alpha = when {
                                        isDragging && isDark -> 0.34f
                                        isDragging -> 0.42f
                                        isDark -> 0.24f
                                        else -> 0.32f
                                    },
                                ),
                            )
                        },
                    ),
            )

            // Layer 3 (priority 0): icons above the glass. During a press/switch only the target
            // remains crisp and readable while neighboring icons are refracted underneath.
            renderIcons(priorityZero)

            // A separate, visually transparent interaction layer keeps the custom drag gesture
            // intact while exposing the main-tab actions to accessibility services and
            // keyboards. Search is the independent circular action beside this pill.
            Row(Modifier.fillMaxSize()) {
                destinations.forEach { destination ->
                    val focused = keyboardFocusedDestination == destination
                    val destinationSelected = destination == selected
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(4.dp)
                            .then(
                                if (focused) {
                                    Modifier.border(2.dp, colors.primary, RoundedCornerShape(16.dp))
                                } else {
                                    Modifier
                                },
                            )
                            .semantics {
                                contentDescription = destination.label
                                role = Role.Tab
                                this.selected = destinationSelected
                                onClick {
                                    switchingTo = destination
                                    onSelect(destination)
                                    true
                                }
                            }
                            .onFocusChanged { state ->
                                if (state.isFocused) {
                                    keyboardFocusedDestination = destination
                                } else if (keyboardFocusedDestination == destination) {
                                    keyboardFocusedDestination = null
                                }
                            }
                            .onKeyEvent { event ->
                                val activates = event.type == KeyEventType.KeyUp &&
                                    (event.key == Key.Enter || event.key == Key.DirectionCenter)
                                if (activates) {
                                    switchingTo = destination
                                    onSelect(destination)
                                }
                                activates
                            }
                            .focusable(),
                    )
                }
            }
        }
        }
        LiquidGlassIconButton(
            onClick = { onSelect(searchDestination) },
            contentDescription = searchDestination.label,
            modifier = Modifier.align(Alignment.TopEnd),
            glass = glass,
            tint = if (selected == searchDestination) colors.primary else Color.Unspecified,
            size = 64.dp,
        ) {
            Icon(searchDestination.icon(), null, Modifier.size(26.dp))
        }
    }
}

@Composable
private fun AndroidArtistNames(
    artists: List<Artist>,
    fallback: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val available = artists.filter { it.id > 0L && it.name.isNotBlank() }
    val openArtists = LocalAndroidOpenArtists.current
    Text(
        text = available.joinToString(" / ") { it.name }.ifBlank { fallback },
        modifier = modifier.then(if (available.isNotEmpty()) Modifier.clickable { openArtists(available) } else Modifier),
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun NowPlayingPage(
    snapshot: AndroidPlaybackSnapshot,
    lyricLines: List<AndroidTimedLyricLine>,
    lyricsLoading: Boolean,
    lyricsMessage: String?,
    lyricFollowDelayMillis: Long,
    lyricAnimationSpeed: LyricAnimationSpeed,
    wordLyricsEnabled: Boolean,
    lyricGlowEnabled: Boolean,
    liquidGlassEnabled: Boolean,
    liquidGlassBlurIntensity: Float,
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
    modifier: Modifier = Modifier,
) {
    val track = snapshot.track ?: return
    val colors = MaterialTheme.colorScheme
    val glass = rememberLazerLiquidGlass(
        enabled = liquidGlassEnabled,
        backgroundColor = colors.background,
        blurIntensity = liquidGlassBlurIntensity,
    )
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
                AndroidAlbumFlowBackground(
                    track = track,
                    modifier = Modifier
                        .fillMaxSize()
                        .captureLiquidGlass(glass),
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
                            LiquidGlassIconButton(
                                onClick = onDismiss,
                                contentDescription = tr("player.collapse"),
                                glass = glass,
                                size = 40.dp,
                            ) { Icon(Icons.Filled.Close, null, tint = colors.onSurface) }
                            Spacer(Modifier.width(8.dp))
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
                            color = colors.onBackground,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                        )
                        TranslatedTrackTitle(track.translatedTitle)
                        AndroidArtistNames(track.artists, track.artist, MaterialTheme.typography.bodyMedium, colors.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        Column(
                            Modifier.fillMaxWidth()
                                .liquidGlassSurface(glass, RoundedCornerShape(24.dp), colors.surface, blurRadius = 8.dp)
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        ) {
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
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                IconButton(onClick = onPrevious, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Filled.SkipPrevious, tr("player.previous"), Modifier.size(27.dp))
                                }
                                IconButton(
                                    onClick = onToggle,
                                    modifier = Modifier.size(52.dp),
                                    colors = IconButtonDefaults.iconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                                ) {
                                    Icon(
                                        if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        if (snapshot.isPlaying) tr("player.pause") else tr("player.play"),
                                        Modifier.size(29.dp),
                                    )
                                }
                                IconButton(onClick = onNext, modifier = Modifier.size(44.dp)) {
                                    Icon(Icons.Filled.SkipNext, tr("player.next"), Modifier.size(27.dp))
                                }
                                IconButton(onClick = onToggleLiked, modifier = Modifier.size(44.dp)) {
                                    Icon(
                                        if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                        if (isLiked) tr("player.like.remove") else tr("player.like.add"),
                                        tint = if (isLiked) colors.primary else colors.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    AndroidLyricsViewport(
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
            val coverProgress = remember { Animatable(0f) }
            LaunchedEffect(coverExpanded) {
                coverProgress.animateTo(
                    if (coverExpanded) 1f else 0f,
                    tween(durationMillis = 420, easing = LazerMotionEasing),
                )
            }
            val coverDensity = LocalDensity.current
            Box(Modifier.fillMaxSize().onGloballyPositioned { coverCoordinates = it }) {
                AndroidAlbumFlowBackground(
                    track = track,
                    modifier = Modifier.fillMaxSize().captureLiquidGlass(glass),
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
                        LiquidGlassIconButton(
                            onClick = onDismiss,
                            contentDescription = tr("player.collapse"),
                            glass = glass,
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
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            TranslatedTrackTitle(track.translatedTitle)
                            AndroidArtistNames(
                                track.artists, track.artist,
                                MaterialTheme.typography.bodySmall, colors.onSurfaceVariant,
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
                            AndroidLyricsViewport(
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
                                onSeek = { if (!coverExpanded) onSeek(it) },
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
                            .liquidGlassSurface(glass, RoundedCornerShape(28.dp), colors.surface, blurRadius = 12.dp)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
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
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.SkipPrevious, tr("player.previous"), Modifier.size(30.dp))
                            }
                            IconButton(
                                onClick = onToggle,
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
                            IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) {
                                Icon(Icons.Filled.SkipNext, tr("player.next"), Modifier.size(30.dp))
                            }
                            IconButton(onClick = onToggleLiked, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    if (isLiked) tr("player.like.remove") else tr("player.like.add"),
                                    tint = if (isLiked) colors.primary else colors.onSurfaceVariant,
                                )
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
                    val requestSave = LocalAndroidRequestCoverSave.current

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
                                onClickLabel = tr(if (coverExpanded) "player.cover.collapse" else "player.cover.expand"),
                                onClick = { coverExpanded = !coverExpanded },
                                onLongClick = {
                                    val url = track.coverUrl
                                    if (!url.isNullOrBlank()) {
                                        requestSave(AndroidCoverSaveRequest(url, track.title))
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
    BoxWithConstraints(
        Modifier.height(48.dp).fillMaxWidth().semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
            setProgress { value ->
                latestSeek(value.coerceIn(0f, 1f))
                latestFinished()
                true
            }
        }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val width = size.width.coerceAtLeast(1)
                latestSeek((down.position.x / width).coerceIn(0f, 1f)); down.consume()
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    latestSeek((change.position.x / width).coerceIn(0f, 1f)); change.consume()
                    if (!change.pressed) break
                }
                latestFinished()
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
    // Kept off the glass material on purpose: a bottom sheet is hosted in its own window, where the
    // page backdrop it would sample holds the page *under* the now-playing overlay rather than what
    // the sheet actually covers. Plain surface, like every other sheet in the app.
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
                    modifier = Modifier.fillMaxWidth().clickable { onChoose(artist) },
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
    request: AndroidCoverSaveRequest?,
    onDismiss: () -> Unit,
    onConfirm: (AndroidCoverSaveRequest) -> Unit,
) {
    request ?: return
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    // Same reason as the artist picker: a sheet window cannot sample the backdrop the sheet's own
    // content is drawn over, so the glass material is skipped here.
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

private fun androidCoverFileName(title: String): String {
    val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Lazer cover" }
    return "$safeTitle.jpg"
}

private fun Context.hasRecordAudioPermission(): Boolean =
    checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

@Composable
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
private fun LoginSheet(
    controller: AndroidGatewayController,
    glass: LazerLiquidGlass = LazerLiquidGlass.Disabled,
) {
    val colors = MaterialTheme.colorScheme
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val contentScrollState = rememberScrollState()
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ModalBottomSheet(
        onDismissRequest = controller::closeLogin,
        sheetState = sheetState,
        modifier = if (glass.isEnabled) {
            Modifier.liquidGlassSurface(glass, shape, colors.surface, blurRadius = 14.dp)
        } else {
            Modifier
        },
        shape = shape,
        containerColor = if (glass.isEnabled) Color.Transparent else colors.surface,
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
                AndroidLoginMethod.entries.forEach { method ->
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
                AndroidLoginMethod.CAPTCHA -> CaptchaLogin(controller)
                AndroidLoginMethod.PASSWORD -> PasswordLogin(controller)
                AndroidLoginMethod.QR_CODE -> QrLogin(controller)
                AndroidLoginMethod.COOKIE -> CookieLogin(controller)
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
private fun CaptchaLogin(controller: AndroidGatewayController) {
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
private fun PasswordLogin(controller: AndroidGatewayController) {
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
private fun CookieLogin(controller: AndroidGatewayController) {
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
private fun QrLogin(controller: AndroidGatewayController) {
    val image = remember(controller.qrImageData) { decodeQrImage(controller.qrImageData) }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (image != null) {
            androidx.compose.foundation.Image(image, tr("login.artwork.qr"), Modifier.size(208.dp).clip(RoundedCornerShape(18.dp)))
        } else {
            Box(Modifier.size(208.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text(if (controller.qrState == AndroidQrLoginState.CREATING) tr("login.qr.creating") else tr("login.qr.unavailable"), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        Text(
            when (controller.qrState) {
                AndroidQrLoginState.WAITING_FOR_SCAN -> tr("login.qr.scan.mobile")
                AndroidQrLoginState.WAITING_FOR_CONFIRMATION -> tr("login.qr.confirm.mobile")
                AndroidQrLoginState.EXPIRED -> tr("login.qr.expired")
                AndroidQrLoginState.ERROR -> tr("login.qr.error.mobile")
                else -> ""
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (controller.qrState == AndroidQrLoginState.EXPIRED || controller.qrState == AndroidQrLoginState.ERROR) {
            ThemeTextButton(controller::startQrLogin) { Text(tr("login.qr.regenerate")) }
        }
    }
}

private fun decodeQrImage(data: String?): androidx.compose.ui.graphics.ImageBitmap? = runCatching {
    val encoded = data?.substringAfter("base64,", data).orEmpty()
    val bytes = Base64.decode(encoded, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

@Composable
private fun QuietState(text: String) {
    Text(text, Modifier.fillMaxWidth().padding(vertical = 14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
}

@Composable
private fun MessageBanner(
    text: String,
    modifier: Modifier,
    glass: LazerLiquidGlass = LazerLiquidGlass.Disabled,
) {
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    if (glass.isEnabled) {
        Box(
            modifier
                .liquidGlassSurface(glass, shape, colors.surface, blurRadius = 10.dp)
                .clip(shape),
        ) {
            Text(
                text,
                Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurface,
            )
        }
    } else {
        Surface(
            modifier,
            shape = RoundedCornerShape(14.dp),
            color = colors.surfaceContainerHigh,
            shadowElevation = 5.dp,
        ) {
            Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun AndroidRootDestination.icon() = when (this) {
    AndroidRootDestination.HOME -> Icons.Outlined.Home
    AndroidRootDestination.SEARCH -> Icons.Outlined.Search
    AndroidRootDestination.LIBRARY -> Icons.Outlined.LibraryMusic
    AndroidRootDestination.ME -> Icons.Outlined.Person
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun AndroidLazerPreview() = AndroidLazerApp()
