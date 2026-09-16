package dev.naominet.lazer

import android.app.Activity
import android.content.ClipData
import android.content.res.Configuration
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
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Lyrics
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
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
import dev.naominet.lazer.gateway.AudioQuality
import dev.naominet.lazer.gateway.DEFAULT_GATEWAY_BASE_URL
import dev.naominet.lazer.gateway.normalizeGatewayBaseUrl
import dev.naominet.lazer.gateway.model.Artist
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
    LYRICS,
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
        translationX = size.width * 0.08f * fraction * direction
        val scale = 1f - 0.035f * fraction
        scaleX = scale
        scaleY = scale
        alpha = 1f - 0.08f * fraction
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
    LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

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
fun AndroidLazerApp() {
    val context = LocalContext.current
    val controller = remember(context.applicationContext) { AndroidGatewayController(context.applicationContext) }
    val playback by AndroidPlaybackConnection.snapshot.collectAsState()
    var playerVisible by remember { mutableStateOf(false) }
    var lyricsVisible by remember { mutableStateOf(false) }
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
        lyricsVisible -> AndroidBackLayer.LYRICS
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
    LaunchedEffect(playback.track?.id) { playback.track?.id?.let(controller::loadLyrics) }

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
                AndroidBackLayer.LYRICS -> lyricsVisible = false
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
    val paletteColorScheme = remember(
        controller.palette,
        controller.isDark,
        systemConfiguration,
    ) {
        when (val palette = controller.palette) {
            LazerPalette.Default -> null
            LazerPalette.System -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (controller.isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                null
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
                        lyricsVisible = false
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
            AndroidPlaybackConnection.play(context, queue, track)
            playerVisible = true
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
        // A custom background wallpaper is drawn fully opaque; the slider fades the app's own
        // surfaces above it (see LocalLazerUiAlpha), never the wallpaper.
        val wallpaper = controller.backgroundImage.takeIf { controller.backgroundImageEnabled }
        val hasWallpaper = wallpaper != null
        val uiAlpha = if (hasWallpaper) controller.backgroundAlpha else 1f
        // Preserve the previous steady-state appearance, where equal global and page scrims were
        // composited, while baking that result into one transition-safe background canvas.
        val backgroundCanvasAlpha = if (hasWallpaper) {
            1f - (1f - uiAlpha) * (1f - uiAlpha)
        } else {
            1f
        }
        val pageBackgroundBackdrop = rememberLayerBackdrop()
        Box(Modifier.fillMaxSize().background(colors.background)) {
            // Keep the wallpaper and its scrim in one fixed, capturable canvas. Animated pages
            // reuse this exact canvas, so their interiors and any exposed transition gaps match.
            Box(
                Modifier
                    .fillMaxSize()
                    .then(
                        if (hasWallpaper) {
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
                    Image(
                        bitmap = image,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colors.background.copy(alpha = backgroundCanvasAlpha)),
                )
            }
            val liquidGlass = rememberLazerLiquidGlass(
                enabled = controller.liquidGlassEnabled,
                backgroundColor = colors.background,
                blurIntensity = controller.liquidGlassBlurIntensity,
            )
            val floatingControlsInset = if (liquidGlass.isEnabled) {
                navigationBarBottomInset() + if (playback.track != null) 176.dp else 104.dp
            } else {
                0.dp
            }
            // The Liquid Glass playlist owns the status-bar backdrop. Do not leave the global
            // wallpaper exposed above its artwork-derived background.
            val playlistOwnsStatusBarBackdrop = liquidGlass.isEnabled && mainPage.kind == AndroidMainPageKind.PLAYLIST
            // Android 16 forces edge-to-edge. Keep the visual canvas under the status bar, while
            // placing every interactive root-page element below its dynamic inset.
            CompositionLocalProvider(
                LocalAndroidContentBottomInset provides floatingControlsInset,
                LocalLazerUiAlpha provides uiAlpha,
            ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .captureLiquidGlass(liquidGlass),
            ) {
                // Manual status-bar inset. The fixed background canvas already paints this area.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(if (playlistOwnsStatusBarBackdrop) 0.dp else statusBarTopInset()),
                )
                Box(Modifier.weight(1f)) {
                    if (
                        transformedBackLayer == AndroidBackLayer.PLAYLIST ||
                        transformedBackLayer == AndroidBackLayer.SETTINGS
                    ) {
                        AndroidRootContent(
                            controller = controller,
                            currentTrackId = playback.track?.id,
                            onPlay = playFromQueue,
                            showHeaderControls = !liquidGlass.isEnabled,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
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
                                    if (hasWallpaper) {
                                        Modifier.capturedPageBackground(pageBackgroundBackdrop)
                                    } else {
                                        Modifier
                                    },
                                ),
                            // With wallpaper, the captured canvas is an opaque visual page plane:
                            // it hides sibling content without adding a second translucent scrim.
                            color = if (hasWallpaper) Color.Transparent else colors.background,
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
                                        liquidGlassEnabled = controller.liquidGlassEnabled,
                                        liquidGlassBlurIntensity = controller.liquidGlassBlurIntensity,
                                        onBack = controller::closePlaylist,
                                        onPlay = playFromQueue,
                                    )
                                }
                                AndroidMainPageKind.ROOT -> AndroidRootContent(
                                    controller = controller,
                                    currentTrackId = playback.track?.id,
                                    onPlay = playFromQueue,
                                    showHeaderControls = !liquidGlass.isEnabled,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                }
                if (!liquidGlass.isEnabled) {
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

            if (liquidGlass.isEnabled && mainPage.kind == AndroidMainPageKind.ROOT) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 12.dp, end = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
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

            if (liquidGlass.isEnabled) {
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    playback.track?.let { track ->
                        MiniPlayer(
                            track = track,
                            isPlaying = playback.isPlaying,
                            isPreparing = playback.isPreparing,
                            onOpen = { playerVisible = true },
                            glass = liquidGlass,
                            onToggle = { AndroidPlaybackConnection.toggle(context) },
                        )
                    }
                    LiquidGlassBottomDock(
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
                    snapshot = playback,
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
                    isLiked = playback.track?.let { controller.isSongLiked(it.id) } == true,
                    onToggleLiked = { playback.track?.let(controller::toggleSongLiked) },
                    onDismiss = { playerVisible = false },
                    onToggle = { AndroidPlaybackConnection.toggle(context) },
                    onPrevious = { AndroidPlaybackConnection.previous(context) },
                    onNext = { AndroidPlaybackConnection.next(context) },
                    onSeek = { AndroidPlaybackConnection.seekTo(context, it) },
                    onLyrics = { lyricsVisible = true },
                    modifier = Modifier
                        .fillMaxSize()
                        .predictiveBackTransform(
                            enabled = transformedBackLayer == AndroidBackLayer.PLAYER,
                            progress = renderedBackProgress,
                            swipeEdge = backSwipeEdge,
                        ),
                )
            }
            AnimatedVisibility(
                visible = lyricsVisible && playback.track != null,
                modifier = Modifier.fillMaxSize(),
                enter = slideInHorizontally(
                    animationSpec = tween(PAGE_TRANSITION_MILLIS, easing = LazerMotionEasing),
                    initialOffsetX = { width -> width / 5 },
                ) + fadeIn(tween(PAGE_TRANSITION_MILLIS, easing = LazerMotionEasing)),
                exit = slideOutHorizontally(
                    animationSpec = tween(PAGE_TRANSITION_MILLIS, easing = LazerMotionEasing),
                    targetOffsetX = { width -> width / 5 },
                ) + fadeOut(tween(PAGE_TRANSITION_MILLIS, easing = LazerMotionEasing)),
                label = "lyrics-page",
            ) {
                AndroidLyricsPage(
                    track = playback.track,
                    lines = controller.lyrics,
                    isLoading = controller.lyricsLoading,
                    message = controller.lyricsMessage,
                    positionMillis = playback.positionMillis,
                    followDelayMillis = controller.lyricFollowDelayMillis,
                    animationSpeed = controller.lyricAnimationSpeed,
                    wordLyricsEnabled = controller.wordLyricsEnabled,
                    lyricGlowEnabled = controller.lyricGlowEnabled,
                    lyricFontSizeSp = controller.lyricFontSizeSp,
                    showFullLyrics = controller.showFullLyrics,
                    onBack = { lyricsVisible = false },
                    onSeek = { AndroidPlaybackConnection.seekTo(context, it) },
                    modifier = Modifier
                        .fillMaxSize()
                        .predictiveBackTransform(
                            enabled = transformedBackLayer == AndroidBackLayer.LYRICS,
                            progress = renderedBackProgress,
                            swipeEdge = backSwipeEdge,
                        ),
                )
            }
            if (controller.isLoginVisible) LoginSheet(controller, liquidGlass)
            ArtistChoiceSheet(
                artists = artistChoices,
                glass = liquidGlass,
                onDismiss = { artistChoices = emptyList() },
                onChoose = { artist ->
                    artistChoices = emptyList()
                    playerVisible = false
                    lyricsVisible = false
                    controller.openArtist(artist)
                },
            )
            CoverSaveSheet(
                request = coverSaveRequest,
                glass = liquidGlass,
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
    showHeaderControls: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        MobileHeader(
            controller = controller,
            showControls = showHeaderControls,
            modifier = Modifier.padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 6.dp),
        )
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
                AndroidRootDestination.DISCOVER -> DiscoverPage(controller, currentTrackId) { track ->
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
private fun HomePage(controller: AndroidGatewayController, currentId: Long?, onPlay: (AndroidTrack) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 18.dp + LocalAndroidContentBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(22.dp),
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
private fun DiscoverPage(controller: AndroidGatewayController, currentId: Long?, onPlay: (AndroidTrack) -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 18.dp + LocalAndroidContentBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Column(Modifier.widthIn(max = 470.dp)) {
                Text(tr("discover.title"), style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(6.dp))
                Text(tr("discover.sub"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item { PlaylistStrip(controller.featuredPlaylists, controller::openPlaylist) }
        item { SectionTitle(tr("discover.playing")) }
        items(controller.homeTracks.take(12), key = AndroidTrack::id) { TrackRow(it, it.id == currentId) { onPlay(it) } }
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
            contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 18.dp + LocalAndroidContentBottomInset.current),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item { Text(tr("search.title"), style = MaterialTheme.typography.displaySmall) }
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
                    .padding(start = 20.dp, top = 62.dp, end = 20.dp),
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
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 18.dp + LocalAndroidContentBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Text(tr("library.title"), style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(6.dp))
            Text(
                if (controller.isSignedIn) tr("library.sub.signed") else tr("library.sub.anon"),
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
        contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 18.dp + LocalAndroidContentBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        if (!controller.isSignedIn) {
            item {
                Text(tr("me.title"), style = MaterialTheme.typography.displaySmall)
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
    val backgroundPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(controller::setBackgroundImage) }
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
    var gatewayBaseUrlDraft by remember(controller.gatewayBaseUrl) {
        mutableStateOf(controller.gatewayBaseUrl)
    }
    val displayedFollowDelay = normalizeLyricFollowDelayMillis(followDelaySliderValue.roundToLong())
    val displayedLyricFontSize = normalizeLyricFontSizeSp(lyricFontSizeSliderValue.roundToInt())
    val animationSpeedOptions = LyricAnimationSpeed.entries
    val normalizedGatewayBaseUrl = normalizeGatewayBaseUrl(gatewayBaseUrlDraft)
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp, 10.dp, 20.dp, 18.dp + LocalAndroidContentBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = controller::closeSettings) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("common.back"))
                }
                Spacer(Modifier.width(4.dp))
                Text(tr("settings.title"), style = MaterialTheme.typography.headlineSmall)
            }
        }
        item { SectionTitle(tr("settings.appearance")) }
        item {
            SettingsCard {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
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
                            .padding(horizontal = 18.dp, vertical = 14.dp),
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
                add(LazerPalette.Custom(LazerSeedSwatches.first()))
            }
            SettingsCard {
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
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
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
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
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("settings.background"), style = MaterialTheme.typography.titleSmall)
                            Text(
                                if (controller.backgroundImage != null) tr("settings.background.change") else tr("settings.background.none"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        if (controller.backgroundImage != null) {
                            LazerSwitch(
                                engine = controller.themeEngine,
                                checked = controller.backgroundImageEnabled,
                                onCheckedChange = controller::updateBackgroundImageEnabled,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        ThemeTextButton(onClick = { backgroundPicker.launch("image/*") }) {
                            Text(tr("settings.background.pick"))
                        }
                    }
                    if (controller.backgroundImage != null) {
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("settings.background.alpha"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
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
                        ThemeTextButton(onClick = controller::clearBackgroundImage) {
                            Text(tr("settings.background.clear"), color = colors.error)
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
                        .padding(horizontal = 18.dp, vertical = 14.dp),
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
                        .padding(horizontal = 18.dp, vertical = 14.dp),
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
                        .padding(horizontal = 18.dp, vertical = 14.dp),
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
        item { SectionTitle(tr("settings.lyrics")) }
        item {
            SettingsCard(
                modifier = Modifier.clickable(role = Role.Switch) {
                    controller.updateWordLyricsEnabled(!controller.wordLyricsEnabled)
                },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
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
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
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
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
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
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
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
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp),
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
                Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp)) {
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
                        .padding(horizontal = 18.dp, vertical = 14.dp),
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
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
        item { SectionTitle(tr("settings.service")) }
        item {
            SettingsCard {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text(tr("settings.service.title"), style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(3.dp))
                    Text(
                        tr("settings.service.hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = gatewayBaseUrlDraft,
                        onValueChange = { gatewayBaseUrlDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(tr("settings.service.address")) },
                        placeholder = { Text(DEFAULT_GATEWAY_BASE_URL) },
                        supportingText = if (gatewayBaseUrlDraft.isNotBlank() && normalizedGatewayBaseUrl == null) {
                            { Text(tr("settings.service.invalid")) }
                        } else {
                            null
                        },
                        isError = gatewayBaseUrlDraft.isNotBlank() && normalizedGatewayBaseUrl == null,
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        ThemeTextButton(onClick = { gatewayBaseUrlDraft = DEFAULT_GATEWAY_BASE_URL }) {
                            Text(tr("settings.service.reset"))
                        }
                        Spacer(Modifier.width(8.dp))
                        ThemeButton(
                            onClick = {
                                normalizedGatewayBaseUrl?.let { controller.updateGatewayBaseUrl(it) }
                            },
                            enabled = normalizedGatewayBaseUrl != null && normalizedGatewayBaseUrl != controller.gatewayBaseUrl,
                        ) {
                            Text(tr("settings.save"))
                        }
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
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
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
    is LazerPalette.Custom -> tr("settings.palette.custom")
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
        Column(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp)) {
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
                .padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
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
        contentPadding = PaddingValues(20.dp, 10.dp, 20.dp, 24.dp + LocalAndroidContentBottomInset.current),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("common.back")) }
                Text(tr("artist.title"), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
            }
        }
        item {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                MobileArtwork(
                    url = sequenceOf(artist.cover, artist.picUrl, artist.avatar)
                        .mapNotNull(::normalizedArtworkUrl)
                        .firstOrNull(),
                    label = artist.name,
                    modifier = Modifier.size(184.dp),
                    cornerRadius = 32.dp,
                )
                Spacer(Modifier.height(20.dp))
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
            contentPadding = PaddingValues(20.dp, 10.dp, 20.dp, (if (currentTrackIndex >= 0) 96.dp else 20.dp) + LocalAndroidContentBottomInset.current),
            verticalArrangement = Arrangement.spacedBy(16.dp),
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
    glass: LazerLiquidGlass,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onPlay: (List<AndroidTrack>, AndroidTrack) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val currentTrackIndex = tracks.indexOfFirst { it.id == currentId }
    // Keep the cover as the visual anchor without making it dominate the song list.
    val artworkSize = (LocalConfiguration.current.screenWidthDp.dp * 0.48f).coerceIn(150.dp, 220.dp)
    val primaryText = Color(0xFFF4FAFD)
    val secondaryText = primaryText.copy(alpha = 0.78f)
    val prominentInk = Color(0xFF183246)
    val artworkShape = RoundedCornerShape(28.dp)
    val hasTracks = tracks.isNotEmpty()

    Box(modifier.fillMaxSize()) {
        AndroidPlaylistFlowBackground(
            playlist = playlist,
            modifier = Modifier
                .fillMaxSize()
                .captureLiquidGlass(glass),
            cornerRadius = 0.dp,
            veil = Color(0xFF0B2637).copy(alpha = 0.82f),
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(
                top = 39.dp,
                bottom = 28.dp + LocalAndroidContentBottomInset.current,
            ),
        ) {
            item(key = "playlist-hero") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    MobileArtwork(
                        url = playlist.coverUrl,
                        label = playlist.title,
                        modifier = Modifier
                            .size(artworkSize)
                            .border(1.dp, Color.White.copy(alpha = 0.20f), artworkShape),
                        cornerRadius = 28.dp,
                    )
                    Spacer(Modifier.height(24.dp))
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
                    Spacer(Modifier.height(22.dp))
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
                        primaryText = primaryText,
                        secondaryText = secondaryText,
                        onClick = { onPlay(tracks, track) },
                    )
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
            glass = glass,
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
                    NowPlayingBars(color = primaryText)
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

@Composable
private fun NowPlayingBars(color: Color) {
    Canvas(Modifier.size(width = 18.dp, height = 16.dp)) {
        val barWidth = 2.5.dp.toPx()
        val gap = (size.width - barWidth * 4f) / 3f
        val heights = listOf(0.48f, 0.86f, 0.64f, 1f)
        heights.forEachIndexed { index, heightFraction ->
            val left = index * (barWidth + gap)
            val barHeight = size.height * heightFraction
            drawRoundRect(
                color = color,
                topLeft = Offset(left, size.height - barHeight),
                size = androidx.compose.ui.geometry.Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2f),
            )
        }
    }
}

@Composable
private fun MobileHeader(
    controller: AndroidGatewayController,
    showControls: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Lazer", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        }
        if (showControls) {
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
        LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            items(playlists, key = AndroidPlaylist::id) { playlist ->
                Column(
                    Modifier.width(158.dp).clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button) { onOpen(playlist) }.padding(bottom = 4.dp),
                ) {
                    MobileArtwork(playlist.coverUrl, playlist.title, Modifier.size(158.dp), 18.dp)
                    Spacer(Modifier.height(10.dp))
                    Text(playlist.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(playlist.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun PlaylistListRow(playlist: AndroidPlaylist, onOpen: (AndroidPlaylist) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(role = Role.Button) { onOpen(playlist) }.padding(vertical = 6.dp),
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                AndroidArtistNames(
                    artists = track.artists,
                    fallback = track.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (track.album.isNotBlank()) {
                    Text(" · ${track.album}", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(track.durationLabel, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
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
) {
    val colors = MaterialTheme.colorScheme
    val requestSave = LocalAndroidRequestCoverSave.current
    val longPressModifier = if (saveOnLongPress && !url.isNullOrBlank()) {
        Modifier.pointerInput(url, label) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                awaitLongPressOrCancellation(down.id)?.let { change ->
                    change.consume()
                    requestSave(AndroidCoverSaveRequest(url, label))
                }
            }
        }
    } else {
        Modifier
    }
    Box(
        modifier
            .then(longPressModifier)
            .clip(RoundedCornerShape(cornerRadius))
            .background(Brush.linearGradient(listOf(colors.primaryContainer, colors.secondaryContainer)))
            // Clickable inside the clip so the ripple is confined to the rounded artwork.
            .then(
                if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label.firstOrNull()?.toString().orEmpty(), style = MaterialTheme.typography.titleMedium, color = colors.onPrimaryContainer)
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = tr("artwork.cover", label),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
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
    track: AndroidTrack,
    isPlaying: Boolean,
    isPreparing: Boolean,
    onOpen: () -> Unit,
    glass: LazerLiquidGlass = LazerLiquidGlass.Disabled,
    onToggle: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val content: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = if (glass.isEnabled) 14.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MobileArtwork(
                track.coverUrl,
                track.title,
                Modifier.size(if (glass.isEnabled) 44.dp else 48.dp),
                12.dp,
                saveOnLongPress = true,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (isPreparing) {
                    Text(tr("player.preparing"), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                } else {
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
        val shape = RoundedCornerShape(36.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 2.dp)
                .height(72.dp)
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
            modifier = Modifier.fillMaxWidth().height(76.dp).clickable(role = Role.Button, onClick = onOpen),
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
            modifier = Modifier.fillMaxWidth(),
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
    // The gesture bar area is painted by this Surface itself (a manual Box, not navigationBarsPadding)
    // so a custom background wallpaper behind the app never clashes with a system nav-bar scrim.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.background.copy(alpha = LocalLazerUiAlpha.current),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), horizontalArrangement = Arrangement.SpaceAround) {
                AndroidRootDestination.entries.forEach { destination ->
                    val active = selected == destination
                    Column(
                        Modifier.clip(RoundedCornerShape(13.dp)).clickable(role = Role.Tab) { onSelect(destination) }
                            .padding(horizontal = 14.dp, vertical = 6.dp).semantics { contentDescription = destination.label },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(destination.icon(), null, Modifier.size(20.dp), tint = if (active) colors.primary else colors.onSurfaceVariant)
                        Text(destination.label, style = MaterialTheme.typography.labelSmall, color = if (active) colors.primary else colors.onSurfaceVariant, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal)
                    }
                }
            }
            // Manual bottom gesture-bar inset, painted by the same surface.
            Box(Modifier.fillMaxWidth().height(navigationBarBottomInset()))
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
    val barShape = RoundedCornerShape(36.dp)
    val selectorShape = RoundedCornerShape(28.dp)
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
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp + navigationBarBottomInset()),
    ) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(end = 82.dp)) {
        val slotWidth = maxWidth / destinations.size
        val slotWidthPx = with(density) { slotWidth.toPx() }
        val selectorWidth = slotWidth - 14.dp
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
        val renderedSelectorHeight = 56.dp + selectorExpansion
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
                .height(72.dp)
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
                                blur(glass.scaledBlurRadius(10.dp).toPx())
                                lens(
                                    refractionHeight = 26.dp.toPx(),
                                    refractionAmount = 52.dp.toPx(),
                                    depthEffect = true,
                                    chromaticAberration = true,
                                )
                            },
                            highlight = { Highlight.Ambient },
                            innerShadow = { InnerShadow(radius = 14.dp, color = Color.Black.copy(alpha = 0.10f)) },
                            onDrawSurface = { drawRect(Color.White.copy(alpha = if (isDark) 0.06f else 0.10f)) },
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
                            y = (((72.dp - renderedSelectorHeight) / 2f) - selectorLift).toPx().roundToInt(),
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
                                refractionHeight = 22.dp.toPx(),
                                refractionAmount = 64.dp.toPx(),
                                depthEffect = true,
                                chromaticAberration = true,
                            )
                        },
                        highlight = { Highlight.Default },
                        innerShadow = { InnerShadow(radius = 10.dp, color = Color.Black.copy(alpha = 0.08f)) },
                        onDrawSurface = {
                            drawRect(
                                Color.White.copy(
                                    alpha = when {
                                        isDragging && isDark -> 0.09f
                                        isDragging -> 0.13f
                                        isDark -> 0.05f
                                        else -> 0.09f
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
            // intact while exposing four real main-tab actions to accessibility services and
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
            size = 72.dp,
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
    isLiked: Boolean,
    onToggleLiked: () -> Unit,
    onDismiss: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onLyrics: () -> Unit,
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
    val display by animateFloatAsState(
        target.coerceIn(0f, 1f),
        if (snapshot.isPlaying) spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh) else snap(),
        label = "android-playback-progress",
    )
    var seeking by remember(track.id) { mutableStateOf(false) }
    var seekProgress by remember(track.id) { mutableFloatStateOf(display) }
    LaunchedEffect(display, seeking) { if (!seeking) seekProgress = display }

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
                )
                Row(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 36.dp, vertical = 0.dp),
                ) {
                    Column(
                        Modifier
                            .widthIn(min = 230.dp, max = 320.dp)
                            .fillMaxSize()
                            .padding(10.dp)
                            .liquidGlassSurface(glass, RoundedCornerShape(32.dp), colors.surface)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, tr("player.collapse"), tint = colors.onSurfaceVariant) }
                            Text(tr("player.now_playing"), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                        }
                        MobileArtwork(
                            track.coverUrl,
                            track.title,
                            Modifier.size(132.dp).align(Alignment.CenterHorizontally),
                            20.dp,
                            onClick = onLyrics,
                            saveOnLongPress = true,
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(track.title, color = colors.onBackground, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        AndroidArtistNames(track.artists, track.artist, MaterialTheme.typography.bodyMedium, colors.onSurfaceVariant)
                        Spacer(Modifier.weight(1f))
                        ThinSeekBar(
                            progress = seekProgress,
                            bufferedProgress = snapshot.bufferedFraction,
                            onSeek = { seeking = true; seekProgress = it },
                            onFinished = { seeking = false; onSeek((duration * seekProgress).toLong()) },
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Text(formatPlaybackTime((duration * seekProgress).toLong()), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Text(formatPlaybackTime(duration), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onPrevious, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.SkipPrevious, tr("player.previous"), Modifier.size(30.dp), tint = colors.onBackground) }
                            IconButton(onClick = onToggle, modifier = Modifier.size(60.dp), colors = IconButtonDefaults.iconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary)) {
                                Icon(if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (snapshot.isPlaying) tr("player.pause") else tr("player.play"), Modifier.size(32.dp))
                            }
                            IconButton(onClick = onNext, modifier = Modifier.size(48.dp)) { Icon(Icons.Filled.SkipNext, tr("player.next"), Modifier.size(30.dp), tint = colors.onBackground) }
                            IconButton(onClick = onToggleLiked, modifier = Modifier.size(48.dp)) {
                                Icon(if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (isLiked) tr("player.like.remove") else tr("player.like.add"), tint = if (isLiked) colors.primary else colors.onBackground)
                            }
                        }
                    }
                    Spacer(Modifier.width(24.dp))
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
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                }
            }
        } else if (glass.isEnabled) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                AndroidAlbumFlowBackground(
                    track = track,
                    modifier = Modifier
                        .fillMaxSize()
                        .captureLiquidGlass(glass),
                    cornerRadius = 0.dp,
                    veil = colors.background.copy(alpha = 0.34f),
                )
                val artworkSize = minOf(maxWidth - 48.dp, maxHeight * 0.32f)
                    .coerceIn(168.dp, 328.dp)
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        LiquidGlassIconButton(
                            onClick = onDismiss,
                            contentDescription = tr("player.collapse"),
                            glass = glass,
                        ) {
                            Icon(Icons.Filled.Close, null, tint = colors.onSurface)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                tr("player.now_playing"),
                                style = MaterialTheme.typography.labelLarge,
                                color = colors.onSurface,
                            )
                            AndroidArtistNames(
                                artists = track.artists,
                                fallback = track.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    MobileArtwork(
                        track.coverUrl,
                        track.title,
                        Modifier.size(artworkSize),
                        28.dp,
                        onClick = onLyrics,
                        saveOnLongPress = true,
                    )
                    Spacer(Modifier.height(20.dp))
                    val panelShape = RoundedCornerShape(36.dp)
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .liquidGlassSurface(glass, panelShape, colors.surface, blurRadius = 12.dp)
                            .clip(panelShape)
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    track.title,
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                AndroidArtistNames(
                                    artists = track.artists,
                                    fallback = track.artist,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = onToggleLiked, modifier = Modifier.size(48.dp)) {
                                Icon(
                                    if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    if (isLiked) tr("player.like.remove") else tr("player.like.add"),
                                    tint = if (isLiked) colors.primary else colors.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        ThinSeekBar(
                            progress = seekProgress,
                            bufferedProgress = snapshot.bufferedFraction,
                            onSeek = { seeking = true; seekProgress = it },
                            onFinished = { seeking = false; onSeek((duration * seekProgress).toLong()) },
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
                            IconButton(onClick = onPrevious, modifier = Modifier.size(50.dp)) {
                                Icon(Icons.Filled.SkipPrevious, tr("player.previous"), Modifier.size(31.dp))
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
                            IconButton(onClick = onNext, modifier = Modifier.size(50.dp)) {
                                Icon(Icons.Filled.SkipNext, tr("player.next"), Modifier.size(31.dp))
                            }
                        }
                        ThemeTextButton(
                            onClick = onLyrics,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Icon(Icons.Outlined.Lyrics, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(7.dp))
                            Text(tr("player.lyrics"))
                        }
                        snapshot.message?.let { message ->
                            Text(
                                message,
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.error,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, tr("player.collapse"), tint = colors.onSurfaceVariant) }
                Text(tr("player.now_playing"), Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.weight(0.4f))
            MobileArtwork(
                track.coverUrl,
                track.title,
                Modifier.fillMaxWidth().heightIn(max = 390.dp).height(320.dp),
                30.dp,
                onClick = onLyrics,
                saveOnLongPress = true,
            )
            Spacer(Modifier.height(32.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    AndroidArtistNames(track.artists, track.artist, MaterialTheme.typography.bodyLarge, colors.onSurfaceVariant)
                }
                IconButton(onClick = onToggleLiked, modifier = Modifier.size(48.dp)) {
                    Icon(if (isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, if (isLiked) tr("player.like.remove") else tr("player.like.add"), tint = if (isLiked) colors.primary else colors.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(28.dp))
            ThinSeekBar(
                progress = seekProgress,
                bufferedProgress = snapshot.bufferedFraction,
                onSeek = { seeking = true; seekProgress = it },
                onFinished = { seeking = false; onSeek((duration * seekProgress).toLong()) },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(formatPlaybackTime((duration * seekProgress).toLong()), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                Text(formatPlaybackTime(duration), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(50.dp)) { Icon(Icons.Filled.SkipPrevious, tr("player.previous"), Modifier.size(31.dp)) }
                IconButton(onClick = onToggle, modifier = Modifier.size(68.dp), colors = IconButtonDefaults.iconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary)) {
                    Icon(if (snapshot.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (snapshot.isPlaying) tr("player.pause") else tr("player.play"), Modifier.size(35.dp))
                }
                IconButton(onClick = onNext, modifier = Modifier.size(50.dp)) { Icon(Icons.Filled.SkipNext, tr("player.next"), Modifier.size(31.dp)) }
            }
            Spacer(Modifier.weight(1f))
            ThemeTextButton(onClick = onLyrics, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Outlined.Lyrics, null, Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text(tr("player.lyrics"))
            }
            snapshot.message?.let { Text(it, Modifier.fillMaxWidth().padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall, color = colors.error, textAlign = TextAlign.Center) }
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
    BoxWithConstraints(
        Modifier.height(20.dp).fillMaxWidth().pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val width = size.width.coerceAtLeast(1)
                onSeek((down.position.x / width).coerceIn(0f, 1f)); down.consume()
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                    onSeek((change.position.x / width).coerceIn(0f, 1f)); change.consume()
                    if (!change.pressed) break
                }
                onFinished()
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
    glass: LazerLiquidGlass = LazerLiquidGlass.Disabled,
    onDismiss: () -> Unit,
    onChoose: (Artist) -> Unit,
) {
    if (artists.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = if (glass.isEnabled) Modifier.liquidGlassSurface(glass, shape, colors.surface, blurRadius = 14.dp) else Modifier,
        shape = shape,
        containerColor = if (glass.isEnabled) Color.Transparent else colors.surface,
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
    glass: LazerLiquidGlass = LazerLiquidGlass.Disabled,
    onDismiss: () -> Unit,
    onConfirm: (AndroidCoverSaveRequest) -> Unit,
) {
    request ?: return
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = if (glass.isEnabled) Modifier.liquidGlassSurface(glass, shape, colors.surface, blurRadius = 14.dp) else Modifier,
        shape = shape,
        containerColor = if (glass.isEnabled) Color.Transparent else colors.surface,
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
    AndroidRootDestination.DISCOVER -> Icons.Outlined.Explore
    AndroidRootDestination.SEARCH -> Icons.Outlined.Search
    AndroidRootDestination.LIBRARY -> Icons.Outlined.LibraryMusic
    AndroidRootDestination.ME -> Icons.Outlined.Person
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852)
@Composable
private fun AndroidLazerPreview() = AndroidLazerApp()
