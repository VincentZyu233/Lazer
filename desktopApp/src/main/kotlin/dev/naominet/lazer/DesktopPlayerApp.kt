package dev.naominet.lazer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.rememberDialogState
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import dev.naominet.lazer.gateway.AudioQuality
import dev.naominet.lazer.gateway.SONG_COMMENT_CONTENT_LIMIT
import dev.naominet.lazer.gateway.model.Artist
import dev.naominet.lazer.gateway.model.SongComment
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.Frame
import java.awt.Point
import java.awt.datatransfer.StringSelection
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.absoluteValue
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import org.jetbrains.skia.Image as SkiaImage

private val LocalScrollInertia = compositionLocalOf<ScrollInertiaController> {
    error("ScrollInertiaController missing")
}

/** True while the native Windows acrylic backdrop is showing, so chrome can go translucent. */
private val LocalOsGlassActive = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Expensive decorative frame loops sleep while the window is minimized or behind another app. */
internal val LocalDesktopWindowForeground = staticCompositionLocalOf { true }

private enum class DesktopDestination(
    private val labelKey: String,
    val icon: ImageVector,
) {
    HOME("nav.home", Icons.Outlined.Home),
    LIBRARY("nav.library", Icons.Outlined.LibraryMusic),
    LIKED("nav.liked", Icons.Outlined.FavoriteBorder);

    val label: String get() = tr(labelKey)
}

private data class CoverSaveRequest(val url: String, val title: String)

private val LocalOpenArtists = staticCompositionLocalOf<(List<Artist>) -> Unit> { {} }
private val LocalRequestCoverSave = staticCompositionLocalOf<(CoverSaveRequest) -> Unit> { {} }

private val calmArtwork = listOf(
    listOf(Color(0xFF9FC6D8), Color(0xFF527D91)),
    listOf(Color(0xFFC5D5CE), Color(0xFF718D83)),
    listOf(Color(0xFFD9C4B5), Color(0xFFA1745B)),
    listOf(Color(0xFFBAC7DB), Color(0xFF697D9A)),
    listOf(Color(0xFFD2D4C2), Color(0xFF898A6A)),
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun WindowScope.DesktopPlayerApp(
    controller: DesktopPlayerController = remember {
        DesktopPlayerController().also { it.start() }
    },
    isWindowMaximized: Boolean = false,
    debugBuild: Boolean = System.getProperty("lazer.debug") == "true",
    onMinimizeWindow: () -> Unit = {},
    onToggleMaximizeWindow: () -> Unit = {},
    onCloseWindow: () -> Unit = {},
) {
    DisposableEffect(controller) {
        onDispose { controller.dispose() }
    }
    var windowForeground by remember(window) { mutableStateOf(window.isDesktopForeground()) }
    DisposableEffect(window, controller) {
        fun updateForegroundState() {
            windowForeground = window.isDesktopForeground()
            controller.setUiForeground(windowForeground)
        }
        val listener = object : WindowAdapter() {
            override fun windowOpened(event: WindowEvent) = updateForegroundState()
            override fun windowGainedFocus(event: WindowEvent) = updateForegroundState()
            override fun windowLostFocus(event: WindowEvent) = updateForegroundState()
            override fun windowIconified(event: WindowEvent) = updateForegroundState()
            override fun windowDeiconified(event: WindowEvent) = updateForegroundState()
            override fun windowStateChanged(event: WindowEvent) = updateForegroundState()
        }
        window.addWindowListener(listener)
        window.addWindowFocusListener(listener)
        window.addWindowStateListener(listener)
        updateForegroundState()
        onDispose {
            window.removeWindowStateListener(listener)
            window.removeWindowFocusListener(listener)
            window.removeWindowListener(listener)
            controller.setUiForeground(false)
        }
    }
    // 系统托盘图标(跨平台)。生命周期跟随本组件;右键菜单交给下面的自绘浮层。
    // WindowScope 只交出 java.awt.Window,而最小化状态记在 Frame 上。
    val mainWindow = window as Frame
    val showMainWindow: () -> Unit = {
        mainWindow.isVisible = true
        mainWindow.extendedState = Frame.NORMAL
        mainWindow.toFront()
        mainWindow.requestFocus()
    }
    var trayMenuAnchor by remember { mutableStateOf<DesktopTrayMenuAnchor?>(null) }
    val mediaTray = remember(mainWindow) {
        DesktopMediaTray(
            onMenuRequest = { anchor ->
                // 再右键一次就收起,和原生托盘菜单一致。
                trayMenuAnchor = if (trayMenuAnchor == null) anchor else null
                // 浮层依附主窗口显示,窗口最小化时先把它带回来,否则菜单点了没反应。
                if (trayMenuAnchor != null && mainWindow.extendedState and Frame.ICONIFIED != 0) showMainWindow()
            },
            onShowWindow = showMainWindow,
        )
    }
    DisposableEffect(mediaTray) {
        mediaTray.start()
        onDispose { mediaTray.close() }
    }
    var taskbarAdminWarningVisible by remember { mutableStateOf(false) }
    var destination by remember { mutableStateOf(DesktopDestination.HOME) }
    var settingsVisible by remember { mutableStateOf(false) }
    var nowPlayingVisible by remember { mutableStateOf(false) }
    var artistChoices by remember { mutableStateOf<List<Artist>>(emptyList()) }
    var coverSaveRequest by remember { mutableStateOf<CoverSaveRequest?>(null) }
    val scrollInertia = rememberScrollInertiaController()
    val windowTitle = controller.nowPlaying
        ?.takeIf { controller.isPlaying }
        ?.let { "Lazer - ${it.title}" }
        ?: "Lazer"
    // The liquid-glass style is gone, and with it the only switch that ever asked Windows for its
    // native DWM acrylic backdrop. The rest of the acrylic plumbing stays in place, simply unasked.
    val osGlassRequested = false

    val paletteColorScheme = remember(controller.palette, controller.isDark, controller.nowPlayingArtworkSeed) {
        when (val palette = controller.palette) {
            LazerPalette.Default -> null
            LazerPalette.System -> null
            LazerPalette.NowPlaying -> seedColorScheme(controller.nowPlayingArtworkSeed, controller.isDark)
            is LazerPalette.Custom -> seedColorScheme(palette.seed, controller.isDark)
        }
    }
    val wallpaper = controller.backgroundImage.takeIf {
        controller.backgroundMode == DesktopBackgroundMode.IMAGE && controller.backgroundImageEnabled
    }
    val usesNowPlayingBackground = controller.backgroundMode == DesktopBackgroundMode.NOW_PLAYING_DYNAMIC ||
        controller.backgroundMode == DesktopBackgroundMode.NOW_PLAYING_STATIC
    val hasVisualBackground = wallpaper != null || usesNowPlayingBackground
    val uiAlpha = resolveLazerUiAlpha(hasVisualBackground, controller.backgroundAlpha)
    LazerTheme(
        isDark = controller.isDark,
        colorScheme = paletteColorScheme,
        engine = controller.themeEngine,
    ) {
        val backgroundArgb = MaterialTheme.colorScheme.background.toArgb()
        // Compose interpolates its palette frame by frame. Keep the native backdrop in sync at
        // the beginning and end of that transition instead of issuing DWM calls every frame.
        val latestBackgroundArgb by rememberUpdatedState(backgroundArgb)
        var nativeGlassApplied by remember(window, osGlassRequested) {
            mutableStateOf(false)
        }
        LaunchedEffect(
            window,
            osGlassRequested,
            controller.isDark,
            controller.palette,
            controller.nowPlayingArtworkSeed,
        ) {
            nativeGlassApplied = false
            val attempts = if (osGlassRequested) 8 else 1
            repeat(attempts) { attempt ->
                val applied = applyWindowsAcrylic(window, osGlassRequested, controller.isDark, latestBackgroundArgb)
                nativeGlassApplied = osGlassRequested && applied
                if (!osGlassRequested || applied || attempt == attempts - 1) return@LaunchedEffect
                // The undecorated AWT window may not have an HWND during the first composition.
                // Keep retrying briefly so acrylic does not stay disabled for the whole session.
                delay(120L)
            }
            // Match the end of the Compose color interpolation without issuing DWM calls per frame.
            delay(340L)
            nativeGlassApplied = osGlassRequested && applyWindowsAcrylic(
                window,
                osGlassRequested,
                controller.isDark,
                latestBackgroundArgb,
            )
        }
        // Windows 任务栏缩略图工具栏(上一首/播放暂停/下一首)。安装在原生窗口句柄就绪后。
        val thumbar = remember {
            WindowsThumbar(
                onAction = controller::dispatchMediaControlAction,
                onBlockedByIntegrity = {
                    taskbarAdminWarningVisible = true
                    mediaTray.showNotification(
                        tr("taskbar.admin_warning.title"),
                        tr("taskbar.admin_warning.body"),
                    )
                },
            )
        }
        DisposableEffect(thumbar) {
            onDispose { thumbar.close() }
        }
        LaunchedEffect(window) {
            // Compose creates the taskbar-visible peer after its first frame. Attaching before
            // that peer is stable succeeds at the COM layer but is not rendered by Explorer.
            delay(300)
            thumbar.install(window, controller.isPlaying)
            WindowsJumpList.install(controller.isPlaying)
        }
        LaunchedEffect(window, controller.isPlaying) {
            thumbar.updatePlayState(controller.isPlaying)
            WindowsJumpList.install(controller.isPlaying)
        }
        val osGlassActive = osGlassRequested && nativeGlassApplied
        val frameShape = RoundedCornerShape(0.dp)
        CompositionLocalProvider(
            LocalScrollInertia provides scrollInertia,
            LocalOsGlassActive provides osGlassActive,
            LocalDesktopWindowForeground provides windowForeground,
            LocalLazerUiAlpha provides uiAlpha,
            LocalOpenArtists provides { artists ->
                val available = artists.filter { it.id > 0L && it.name.isNotBlank() }.distinctBy(Artist::id)
                when (available.size) {
                    0 -> Unit
                    1 -> controller.openArtist(available.single())
                    else -> artistChoices = available
                }
            },
            LocalRequestCoverSave provides { coverSaveRequest = it },
        ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = frameShape,
            color = if (osGlassActive) Color.Transparent else MaterialTheme.colorScheme.background,
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(Modifier.fillMaxSize()) {
            val visualBackgroundAlpha = windowsVisualBackgroundAlpha(osGlassActive, uiAlpha)
            if (wallpaper != null) {
                Image(
                    bitmap = wallpaper,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = visualBackgroundAlpha },
                )
            }
            if (usesNowPlayingBackground) {
                AlbumFlowBackground(
                    colors = controller.lyricFlowColors,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = visualBackgroundAlpha },
                    cornerRadius = 0.dp,
                    veil = Color.Transparent,
                    animated = controller.backgroundMode == DesktopBackgroundMode.NOW_PLAYING_DYNAMIC,
                    solid = controller.backgroundMode == DesktopBackgroundMode.NOW_PLAYING_STATIC,
                )
            }
            if (hasVisualBackground) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(
                            MaterialTheme.colorScheme.background.copy(
                                alpha = if (osGlassActive) uiAlpha * uiAlpha else uiAlpha,
                            ),
                        ),
                )
            }
            PaperBackground(transparent = osGlassActive || hasVisualBackground) {
                Column(Modifier.fillMaxSize()) {
                    WindowTitleBar(
                        title = windowTitle,
                        maximized = isWindowMaximized,
                        onMinimize = onMinimizeWindow,
                        onToggleMaximize = onToggleMaximizeWindow,
                        onClose = onCloseWindow,
                    )
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        // The queue card hangs above the transport bar, so its list can only take the room
                        // this column really has. A fixed height here spills past the window edge on a short
                        // screen and the rows under it can never be reached.
                        val queueListHeight =
                            (maxHeight - DesktopPanelChromeHeight).coerceAtMost(DesktopPanelListHeight)
                                .coerceAtLeast(120.dp)
                        Column(Modifier.fillMaxSize()) {
                            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                                val compactNavigation = maxWidth < 1100.dp
                                Row(Modifier.fillMaxSize()) {
                                    NavigationPanel(
                                        controller = controller,
                                        selectedDestination = destination,
                                        settingsSelected = settingsVisible,
                                        compact = compactNavigation,
                                        onDestinationSelected = {
                                            settingsVisible = false
                                            controller.closeArtist()
                                            controller.closeCommentPanel()
                                            destination = it
                                        },
                                        onPlaylistSelected = {
                                            settingsVisible = false
                                            controller.closeArtist()
                                            controller.closeCommentPanel()
                                            destination = DesktopDestination.LIBRARY
                                            controller.openPlaylist(it)
                                        },
                                        onOpenSettings = {
                                            controller.closeArtist()
                                            controller.closeCommentPanel()
                                            settingsVisible = true
                                        },
                                    )
                                    MainContent(
                                        controller = controller,
                                        destination = destination,
                                        settingsVisible = settingsVisible,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            PlayerBar(
                                controller,
                                queueListHeight = queueListHeight,
                                onOpenNowPlaying = { nowPlayingVisible = true },
                            )
                        }
                    }
                }

                if (controller.isLoginVisible) {
                    LoginOverlay(controller)
                }
                if (controller.isListenTogetherVisible) {
                    ListenTogetherOverlay(controller)
                }
                AnimatedVisibility(
                    visible = nowPlayingVisible && controller.nowPlaying != null,
                    enter = fadeIn(tween(220)) +
                        scaleIn(tween(320, easing = FastOutSlowInEasing), initialScale = 0.985f) +
                        slideInVertically(
                            animationSpec = tween(320, easing = FastOutSlowInEasing),
                            initialOffsetY = { height -> height / 18 },
                        ),
                    exit = fadeOut(tween(170)) +
                        scaleOut(tween(220, easing = FastOutSlowInEasing), targetScale = 0.99f) +
                        slideOutVertically(
                            animationSpec = tween(220, easing = FastOutSlowInEasing),
                            targetOffsetY = { height -> height / 24 },
                        ),
                    label = "desktop-now-playing-page",
                ) {
                    DesktopNowPlayingPage(
                        controller = controller,
                        onDismiss = { nowPlayingVisible = false },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 42.dp),
                    )
                }
                ArtistChoiceSheet(
                    artists = artistChoices,
                    onDismiss = { artistChoices = emptyList() },
                    onChoose = { artist ->
                        artistChoices = emptyList()
                        controller.openArtist(artist)
                    },
                )
                CoverSaveSheet(
                    request = coverSaveRequest,
                    onDismiss = { coverSaveRequest = null },
                    onConfirm = { request ->
                        coverSaveRequest = null
                        chooseCoverDestination(request.title)?.let { file ->
                            controller.saveArtwork(request.url, request.title, file)
                        }
                    },
                )
                trayMenuAnchor?.let { anchor ->
                    TrayMenuDialog(
                        controller = controller,
                        anchor = anchor,
                        onDismiss = { trayMenuAnchor = null },
                        onShowWindow = showMainWindow,
                        onQuit = onCloseWindow,
                    )
                }
                if (taskbarAdminWarningVisible) {
                    AlertDialog(
                        onDismissRequest = { taskbarAdminWarningVisible = false },
                        title = { Text(tr("taskbar.admin_warning.title")) },
                        text = { Text(tr("taskbar.admin_warning.body")) },
                        confirmButton = {
                            TextButton(onClick = { taskbarAdminWarningVisible = false }) {
                                Text(tr("taskbar.admin_warning.dismiss"))
                            }
                        },
                    )
                }
                if (debugBuild) {
                    // Top-end, below the window title bar, clear of the window controls.
                    DebugWatermark(
                        enabled = true,
                        modifier = Modifier.align(Alignment.TopEnd).padding(top = 50.dp, end = 14.dp),
                    )
                }
            }
            }
        }
        }
    }
}

@Composable
private fun WindowScope.WindowTitleBar(
    title: String,
    maximized: Boolean,
    onMinimize: () -> Unit,
    onToggleMaximize: () -> Unit,
    onClose: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val glass = LocalOsGlassActive.current
    val uiAlpha = LocalLazerUiAlpha.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .background(colors.surface.copy(alpha = if (glass) 0f else 0.9f * uiAlpha)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val titleModifier = Modifier.weight(1f).fillMaxHeight()
        if (maximized) {
            Box(titleModifier) { WindowTitleIdentity(title) }
        } else {
            WindowDraggableArea(titleModifier) { WindowTitleIdentity(title) }
        }
        WindowControlButton(Icons.Outlined.Remove, tr("window.minimize"), onMinimize)
        WindowControlButton(if (maximized) Icons.Outlined.FilterNone else Icons.Outlined.CropSquare, if (maximized) tr("window.restore") else tr("window.maximize"), onToggleMaximize)
        WindowControlButton(Icons.Outlined.Close, tr("window.close"), onClose, close = true)
    }
}

@Composable
private fun WindowTitleIdentity(title: String) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxSize().padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(20.dp).clip(RoundedCornerShape(7.dp)).background(colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.LibraryMusic,
                contentDescription = "Lazer",
                modifier = Modifier.size(14.dp),
                tint = colors.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 12.dp),
        )
    }
}

@Composable
private fun WindowControlButton(icon: ImageVector, description: String, onClick: () -> Unit, close: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        modifier = Modifier.width(46.dp).fillMaxHeight(),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (close) colors.error else colors.onSurfaceVariant,
        ),
    ) {
        Icon(icon, description, Modifier.size(if (description == tr("window.close")) 17.dp else 15.dp))
    }
}

@Composable
private fun PaperBackground(transparent: Boolean = false, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    // Calm paper sheet — no fiber dots / dashed noise. Transparent under the OS glass layer; when a
    // custom wallpaper is active the paper fades by LocalLazerUiAlpha so the wallpaper shows through.
    val uiAlpha = LocalLazerUiAlpha.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (transparent) Modifier else Modifier.background(colors.background.copy(alpha = uiAlpha))),
    ) {
        content()
    }
}

private fun Color.luminanceValue(): Float = (red + green + blue) / 3f

@Composable
private fun NavigationPanel(
    controller: DesktopPlayerController,
    selectedDestination: DesktopDestination,
    settingsSelected: Boolean,
    compact: Boolean,
    onDestinationSelected: (DesktopDestination) -> Unit,
    onPlaylistSelected: (PlaylistItem) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val glass = LocalOsGlassActive.current
    val uiAlpha = LocalLazerUiAlpha.current
    val playlistScrollState = rememberLazyListState()
    val inertia = LocalScrollInertia.current
    // width() (not requiredWidth) so a narrow window can still shrink the rail.
    val railWidth = if (compact) 72.dp else 208.dp
    Surface(
        modifier = Modifier
            .fillMaxHeight()
            .width(railWidth)
            .widthIn(max = railWidth),
        color = colors.surface.copy(alpha = if (glass) 0f else 0.86f * uiAlpha),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = 18.dp),
            horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            BrandMark(compact)
            Spacer(Modifier.height(if (compact) 22.dp else 28.dp))

            DesktopDestination.entries.forEach { destination ->
                NavigationEntry(
                    destination = destination,
                    selected = !settingsSelected && destination == selectedDestination,
                    compact = compact,
                    themeEngine = controller.themeEngine,
                    onClick = { onDestinationSelected(destination) },
                )
                Spacer(Modifier.height(4.dp))
            }

            Spacer(Modifier.height(if (compact) 14.dp else 20.dp))
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth(),
                thickness = 1.dp,
                color = colors.outlineVariant.copy(alpha = if (compact) 0.85f else 0.7f),
            )
            if (!compact) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("nav.your_playlists"), style = MaterialTheme.typography.titleSmall, color = colors.onSurface)
                    Spacer(Modifier.weight(1f))
                    if (controller.isSignedIn) {
                        IconButton(onClick = controller::syncLibrary, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Outlined.Sync, tr("nav.sync"), Modifier.size(16.dp), tint = colors.primary)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            } else {
                Spacer(Modifier.height(12.dp))
            }

            val playlists = controller.browsePlaylists()
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.TopStart,
            ) {
                when {
                    playlists.isEmpty() && !compact -> {
                        Text(
                            if (controller.isSignedIn) tr("nav.no_playlists") else tr("nav.login_hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                        )
                    }
                    playlists.isNotEmpty() -> {
                        LazyColumn(
                            state = playlistScrollState,
                            modifier = Modifier
                                .fillMaxSize()
                                .scrollInertia(playlistScrollState, inertia),
                            horizontalAlignment = if (compact) Alignment.CenterHorizontally else Alignment.Start,
                            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 0.dp),
                        ) {
                            items(playlists, key = { it.id }) { playlist ->
                                SidebarPlaylistRow(
                                    playlist = playlist,
                                    selected = !settingsSelected && controller.activePlaylist?.id == playlist.id,
                                    compact = compact,
                                    onClick = { onPlaylistSelected(playlist) },
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            NavigationUtilityEntry(
                icon = Icons.Outlined.Settings,
                label = tr("settings.title"),
                compact = compact,
                selected = settingsSelected,
                themeEngine = controller.themeEngine,
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun NavigationUtilityEntry(
    icon: ImageVector,
    label: String,
    compact: Boolean,
    selected: Boolean,
    themeEngine: LazerThemeEngine,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val selectedIconColor = if (themeEngine == LazerThemeEngine.MIUIX) Color.White else colors.primary
    Row(
        modifier = Modifier
            .then(if (compact) Modifier.size(48.dp) else Modifier.fillMaxWidth())
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 0.dp else 11.dp, vertical = if (compact) 0.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start,
    ) {
        Icon(
            icon,
            label,
            Modifier.size(19.dp),
            tint = if (selected) selectedIconColor else colors.onSurfaceVariant,
        )
        if (!compact) {
            Spacer(Modifier.width(11.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun SidebarPlaylistRow(
    playlist: PlaylistItem,
    selected: Boolean,
    compact: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    if (compact) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(11.dp))
                .background(if (selected) colors.primaryContainer.copy(alpha = 0.8f) else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Artwork(
                id = playlist.id,
                title = playlist.title,
                coverUrl = playlist.coverUrl,
                modifier = Modifier.size(36.dp),
                cornerRadius = 9.dp,
            )
        }
        return
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) colors.primaryContainer.copy(alpha = 0.72f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(
            id = playlist.id,
            title = playlist.title,
            coverUrl = playlist.coverUrl,
            modifier = Modifier.size(32.dp),
            cornerRadius = 8.dp,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            playlist.title,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun BrandMark(compact: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.LibraryMusic,
                contentDescription = "Lazer",
                modifier = Modifier.size(23.dp),
                tint = colors.onPrimaryContainer,
            )
        }
        if (!compact) {
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Lazer", style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

@Composable
private fun NavigationEntry(
    destination: DesktopDestination,
    selected: Boolean,
    compact: Boolean,
    themeEngine: LazerThemeEngine,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val selectedIconColor = if (themeEngine == LazerThemeEngine.MIUIX) Color.White else colors.primary
    Row(
        modifier = Modifier
            .then(if (compact) Modifier.size(48.dp) else Modifier.fillMaxWidth())
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) colors.primaryContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = if (compact) 0.dp else 11.dp, vertical = if (compact) 0.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (compact) Arrangement.Center else Arrangement.Start,
    ) {
        Icon(
            destination.icon,
            contentDescription = destination.label,
            modifier = Modifier.size(19.dp),
            tint = if (selected) selectedIconColor else colors.onSurfaceVariant,
        )
        if (!compact) {
            Spacer(Modifier.width(11.dp))
            Text(
                destination.label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            )
        }
    }
}

@Composable
private fun MainContent(
    controller: DesktopPlayerController,
    destination: DesktopDestination,
    settingsVisible: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxHeight().padding(horizontal = 28.dp)) {
        if (controller.isCommentPanelVisible) {
            SongCommentPage(controller, Modifier.weight(1f))
        } else if (settingsVisible) {
            DesktopSettingsPage(controller, Modifier.weight(1f))
        } else {
            TopBar(controller)
            when {
                controller.activeArtist != null -> ArtistPage(controller, Modifier.weight(1f))
                controller.searchQuery.isNotBlank() -> SearchPage(controller, Modifier.weight(1f))
                destination == DesktopDestination.HOME -> HomePage(controller, Modifier.weight(1f))
                destination == DesktopDestination.LIBRARY -> LibraryPage(controller, Modifier.weight(1f))
                else -> LikedPage(controller, Modifier.weight(1f))
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun DesktopSettingsPage(
    controller: DesktopPlayerController,
    modifier: Modifier = Modifier,
) {
    var cacheDialogVisible by remember { mutableStateOf(false) }
    var cookieDialogVisible by remember { mutableStateOf(false) }
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
    val scrollState = rememberScrollState()
    val inertia = LocalScrollInertia.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .scrollInertia(scrollState, inertia)
            .padding(top = 22.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
                PageHeading(tr("settings.title"), tr("settings.subtitle"))
                Text(tr("settings.appearance"), style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.style"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            desktopStyleLabel(controller.style),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    DesktopSettingsDropdown(
                        // Acrylic is Windows-only; it is not offered on other platforms.
                        options = if (isWindowsDesktop()) LazerStyle.entries else DesktopStyleOptions,
                        selected = controller.style,
                        label = ::desktopStyleLabel,
                        onSelected = controller::updateStyle,
                    )
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("settings.palette"), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                paletteLabel(controller.palette),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DesktopSettingsDropdown(
                            options = listOf(
                                LazerPalette.Default,
                                LazerPalette.System,
                                LazerPalette.NowPlaying,
                                LazerPalette.Custom(LazerSeedSwatches.first()),
                            ),
                            selected = controller.palette,
                            label = ::paletteLabel,
                            onSelected = controller::updatePalette,
                        )
                    }
                    val custom = controller.palette
                    if (custom is LazerPalette.Custom) {
                        SeedColorPicker(
                            seed = custom.seed,
                            onSeedChange = { controller.updatePalette(LazerPalette.Custom(it)) },
                            modifier = Modifier.padding(horizontal = 14.dp),
                        )
                    }
                }
                HorizontalDivider()
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("settings.background"), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                desktopBackgroundModeHint(controller.backgroundMode),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DesktopSettingsDropdown(
                            options = DesktopBackgroundMode.entries,
                            selected = controller.backgroundMode,
                            label = ::desktopBackgroundModeLabel,
                            onSelected = { mode ->
                                controller.updateBackgroundMode(mode)
                                if (mode == DesktopBackgroundMode.IMAGE && !controller.hasBackgroundImage) {
                                    pickBackgroundImage(controller)
                                }
                            },
                        )
                    }
                    if (controller.backgroundMode == DesktopBackgroundMode.IMAGE) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (controller.hasBackgroundImage) {
                                    tr("settings.background.image.ready")
                                } else {
                                    tr("settings.background.none")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { pickBackgroundImage(controller) }) {
                                Text(tr("settings.background.pick"))
                            }
                            if (controller.hasBackgroundImage) {
                                TextButton(onClick = controller::clearBackgroundImage) {
                                    Text(tr("settings.background.clear"), color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    if (controller.backgroundMode != DesktopBackgroundMode.SOLID) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(tr("settings.background.surface_alpha"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(12.dp))
                            Slider(
                                value = controller.backgroundAlpha,
                                onValueChange = controller::updateBackgroundAlpha,
                                valueRange = 0f..1f,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text("${(controller.backgroundAlpha * 100).roundToInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(tr("settings.language"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    DesktopSettingsDropdown(
                        options = LazerLanguage.entries,
                        selected = controller.language,
                        label = LazerLanguage::displayName,
                        onSelected = controller::updateLanguage,
                    )
                }
                HorizontalDivider()
                if (isWindowsDesktop()) {
                    Text(tr("settings.playback"), style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable(role = androidx.compose.ui.semantics.Role.Switch) {
                                controller.updateExclusiveAudio(!controller.exclusiveAudio)
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tr("settings.exclusive.title"), style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (controller.exclusiveAudio) {
                                    tr("settings.exclusive.on.desktop")
                                } else {
                                    tr("settings.exclusive.off")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        LazerSwitch(
                            engine = controller.themeEngine,
                            checked = controller.exclusiveAudio,
                            onCheckedChange = null,
                        )
                    }
                    HorizontalDivider()
                }
                Text(tr("settings.equalizer.title"), style = MaterialTheme.typography.titleSmall)
                DesktopEqualizerSection(controller)
                HorizontalDivider()
                Text(tr("settings.hifi.title"), style = MaterialTheme.typography.titleSmall)
                DesktopHiFiSection(controller)
                HorizontalDivider()
                Text(tr("settings.lyrics"), style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(role = androidx.compose.ui.semantics.Role.Switch) {
                            controller.updateWordLyricsEnabled(!controller.wordLyricsEnabled)
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.word.title"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (controller.wordLyricsEnabled) tr("settings.word.on") else tr("settings.word.off"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    LazerSwitch(
                        engine = controller.themeEngine,
                        checked = controller.wordLyricsEnabled,
                        onCheckedChange = null,
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(role = androidx.compose.ui.semantics.Role.Switch) {
                            controller.updateLyricGlowEnabled(!controller.lyricGlowEnabled)
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.lyric.glow.title"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (controller.lyricGlowEnabled) tr("settings.lyric.glow.on") else tr("settings.lyric.glow.off"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    LazerSwitch(
                        engine = controller.themeEngine,
                        checked = controller.lyricGlowEnabled,
                        onCheckedChange = null,
                    )
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.lyric.font.title"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            tr("settings.lyric.font.hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        lyricFontSizeLabel(displayedLyricFontSize),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        tr("settings.lyric.font.large"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(role = androidx.compose.ui.semantics.Role.Switch) {
                            controller.updateShowFullLyrics(!controller.showFullLyrics)
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.lyric.full.title"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (controller.showFullLyrics) {
                                tr("settings.lyric.full.on")
                            } else {
                                tr("settings.lyric.full.off")
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    LazerSwitch(
                        engine = controller.themeEngine,
                        checked = controller.showFullLyrics,
                        onCheckedChange = null,
                    )
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.lyric.speed.title"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            tr("settings.lyric.speed.hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        controller.lyricAnimationSpeed.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        LyricAnimationSpeed.STANDARD.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        animationSpeedOptions.last().label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    tr("settings.lyric.follow.desktop.hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("settings.lyric.follow.desktop.title"), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.weight(1f))
                    Text(
                        lyricFollowDelayLabel(displayedFollowDelay),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        lyricFollowDelayLabel(MAX_LYRIC_FOLLOW_DELAY_MILLIS),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                Text(tr("settings.storage"), style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.cache.title"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            tr("settings.cache.hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { cacheDialogVisible = true }) { Text(tr("settings.cache.select")) }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.resync.title"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            tr("settings.resync.hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(onClick = controller::forceResync, enabled = !controller.isLoading) {
                        Text(if (controller.isLoading) tr("settings.resync.doing") else tr("settings.resync.action"))
                    }
                }
                HorizontalDivider()
                Text(tr("settings.account"), style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.cookie.title"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            tr("settings.cookie.hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(
                        onClick = {
                            cookieCopied = false
                            cookieDialogVisible = true
                        },
                    ) {
                        Text(tr("settings.cookie.read"))
                    }
                }
                // The same sheet the phone shows, ending the settings column instead of pushing a
                // page: on a desktop window there is nothing to navigate back from.
                LazerAboutSection(heading = tr("about.title"))
        }
    }
    if (cacheDialogVisible) {
        AlertDialog(
            onDismissRequest = { cacheDialogVisible = false },
            title = { Text(tr("settings.cache.dialog.title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        tr("settings.cache.dialog.body"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            controller.clearSongCache()
                            cacheDialogVisible = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr("settings.cache.clear.songs"), color = MaterialTheme.colorScheme.error) }
                    TextButton(
                        onClick = {
                            controller.clearPlaylistCache()
                            cacheDialogVisible = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr("settings.cache.clear.playlists"), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { cacheDialogVisible = false }) { Text(tr("settings.cancel")) }
            },
        )
    }
    if (cookieDialogVisible) {
        val cookie = controller.currentSessionCookie
        AlertDialog(
            onDismissRequest = { cookieDialogVisible = false },
            title = { Text(tr("settings.cookie.title")) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        tr("settings.cookie.warning"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = cookie ?: tr("settings.cookie.empty"),
                        onValueChange = {},
                        modifier = Modifier.fillMaxWidth(),
                        readOnly = true,
                        minLines = 3,
                        maxLines = 6,
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                    if (cookieCopied) {
                        Text(
                            tr("settings.cookie.copied"),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                if (cookie != null) {
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                clipboard.setClipEntry(ClipEntry(StringSelection(cookie)))
                                cookieCopied = true
                            }
                        },
                    ) { Text(tr("settings.cookie.copy")) }
                }
            },
            dismissButton = {
                TextButton(onClick = { cookieDialogVisible = false }) { Text(tr("login.close")) }
            },
        )
    }
}

@Composable
private fun TopBar(controller: DesktopPlayerController) {
    var accountMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NaturalLanguageField(
            value = controller.searchQuery,
            onValueChange = controller::updateSearchQuery,
            modifier = Modifier.weight(1f).widthIn(max = 650.dp),
        )
        Spacer(Modifier.weight(0.18f))
        GatewayStatus(controller)
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = { chooseDesktopAudioFiles()?.let(controller::openLocalAudioFiles) },
            modifier = Modifier.size(40.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Icon(Icons.Outlined.FolderOpen, tr("player.open_audio_files"), Modifier.size(18.dp))
        }
        Spacer(Modifier.width(8.dp))
        IconButton(
            onClick = controller::toggleTheme,
            modifier = Modifier.size(40.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Icon(
                if (controller.isDark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                tr("topbar.toggle_theme"),
                Modifier.size(18.dp),
            )
        }
        Spacer(Modifier.width(8.dp))
        Box {
            IconButton(
                onClick = {
                    if (controller.isSignedIn) accountMenuOpen = true else controller.openLogin()
                },
                modifier = Modifier.size(40.dp),
                colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                if (controller.isSignedIn) {
                    UserAvatar(
                        controller.currentUser?.nickname ?: tr("topbar.you"),
                        controller.currentUser?.avatarUrl,
                        Modifier.size(32.dp),
                    )
                } else {
                    Icon(Icons.Outlined.Person, tr("topbar.login"), Modifier.size(18.dp))
                }
            }
            if (accountMenuOpen && controller.isSignedIn) {
                Popup(
                    alignment = Alignment.TopEnd,
                    offset = IntOffset(0, 48),
                    onDismissRequest = { accountMenuOpen = false },
                    properties = PopupProperties(focusable = true),
                ) {
                    PaperMenuCard(width = 188.dp) {
                        Text(
                            controller.currentUser?.nickname.orEmpty(),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                        PaperMenuRow(Icons.Outlined.Sync, tr("nav.sync")) {
                            accountMenuOpen = false
                            controller.syncLibrary()
                        }
                        PaperMenuRow(
                            Icons.AutoMirrored.Outlined.Logout,
                            tr("settings.account.logout"),
                            destructive = true,
                        ) {
                            accountMenuOpen = false
                            controller.logout()
                        }
                    }
                }
            }
        }
    }
}

/**
 * 托盘右键菜单:浮在任务栏旁的自绘纸面菜单,样式与顶栏账号菜单同一套实例。
 *
 * 用 AWT 对话框而不是 Popup:菜单要落在主窗口之外的屏幕坐标上,又不想在任务栏多出一个窗口按钮。
 * 替代品 DialogWindow 只能在 application 顶层作用域创建,而菜单需要依附主窗口,所以仍用已标废弃的 Dialog。
 * 媒体控制顺序来自 [mediaControlItems];播放方式与播放栏共用一个循环按钮。
 */
@Suppress("DEPRECATION")
@Composable
private fun TrayMenuDialog(
    controller: DesktopPlayerController,
    anchor: DesktopTrayMenuAnchor,
    onDismiss: () -> Unit,
    onShowWindow: () -> Unit,
    onQuit: () -> Unit,
) {
    val density = LocalDensity.current
    val ringPx = with(density) { TrayMenuRing.roundToPx() }
    // 首帧先按占位高度摆到光标左上方,量出真实高度后再收紧,免得闪在屏幕角落。
    val placeholder = IntSize(
        with(density) { TrayMenuCardWidth.roundToPx() } + ringPx * 2,
        with(density) { TrayMenuPlaceholderHeight.roundToPx() } + ringPx * 2,
    )
    val state = rememberDialogState(
        size = with(density) { DpSize(placeholder.width.toDp(), placeholder.height.toDp()) },
        position = trayMenuPosition(anchor, placeholder, density),
    )
    var cardSize by remember { mutableStateOf(IntSize.Zero) }
    Dialog(
        onCloseRequest = onDismiss,
        state = state,
        title = "Lazer",
        undecorated = true,
        transparent = true,
        onPreviewKeyEvent = { event ->
            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown) {
                onDismiss()
                true
            } else {
                false
            }
        },
    ) {
        // 菜单高度要等首个布局量出来才知道;尺寸和位置都写回窗口状态,直接 setBounds 会被 Compose 按状态改回去。
        LaunchedEffect(anchor, cardSize) {
            if (cardSize == IntSize.Zero) return@LaunchedEffect
            val menuSize = IntSize(cardSize.width + ringPx * 2, cardSize.height + ringPx * 2)
            state.size = with(density) { DpSize(menuSize.width.toDp(), menuSize.height.toDp()) }
            state.position = trayMenuPosition(anchor, menuSize, density)
            window.isAlwaysOnTop = true
            window.requestFocus()
        }
        DisposableEffect(window) {
            // 无边框透明窗口在不同机器上可能带回一块纸面底色,显式清掉,菜单外就不会出现一圈白边。
            window.background = java.awt.Color(0, 0, 0, 0)
            windowsClearPopupFrame(window)
            // 焦点一离开就收起,和原生菜单一致;先等到真正拿到焦点,免得刚弹出就被抢走而闪掉。
            var gainedFocus = false
            val listener = object : WindowAdapter() {
                override fun windowGainedFocus(event: WindowEvent) {
                    gainedFocus = true
                }

                override fun windowLostFocus(event: WindowEvent) {
                    if (gainedFocus) onDismiss()
                }
            }
            window.addWindowFocusListener(listener)
            onDispose { window.removeWindowFocusListener(listener) }
        }
        // 卡片外留一圈透明边,投影才有余地画开。量的必须是卡片本身:外层 wrapContentSize 会把
        // 自己的尺寸压回窗口当前大小,在它下面量只会永远量到旧尺寸,菜单就再也长不开。
        Box(
            Modifier
                .wrapContentSize()
                .padding(TrayMenuRing),
        ) {
            PaperMenuCard(
                width = TrayMenuCardWidth,
                elevation = 0.dp,
                modifier = Modifier.onSizeChanged { cardSize = it },
            ) {
                PaperMenuRow(Icons.Outlined.Monitor, tr("tray.open")) {
                    onShowWindow()
                    onDismiss()
                }
                PaperMenuDivider()
                mediaControlItems(controller.isPlaying, ::tr).forEach { item ->
                    PaperMenuRow(trayMediaIcon(item.iconResource), item.label) {
                        controller.dispatchMediaControlAction(item.action)
                        onDismiss()
                    }
                }
                PaperMenuDivider()
                val playMode = controller.playMode
                PaperMenuRow(
                    icon = desktopPlayModeIcon(playMode),
                    label = tr("player.mode.now", tr(playMode.labelKey)),
                ) {
                    controller.cyclePlayMode()
                    onDismiss()
                }
                PaperMenuDivider()
                PaperMenuRow(Icons.Outlined.PowerSettingsNew, tr("tray.quit"), destructive = true) {
                    onQuit()
                    onDismiss()
                }
            }
        }
    }
}

/** 菜单卡片贴在光标左上方,并完整留在所在屏幕的工作区里。 */
private fun trayMenuOrigin(anchor: DesktopTrayMenuAnchor, menuSize: IntSize): Point {
    val work = anchor.workArea
    val x = (anchor.point.x - menuSize.width - TRAY_MENU_GAP_PX)
        .coerceIn(work.x, (work.x + work.width - menuSize.width).coerceAtLeast(work.x))
    val y = (anchor.point.y - menuSize.height - TRAY_MENU_GAP_PX)
        .coerceIn(work.y, (work.y + work.height - menuSize.height).coerceAtLeast(work.y))
    return Point(x, y)
}

/** 光标坐标是像素,窗口尺寸与位置走 Compose 状态,这里统一做一次 px -> dp。 */
private fun trayMenuPosition(
    anchor: DesktopTrayMenuAnchor,
    menuSize: IntSize,
    density: Density,
): WindowPosition = with(density) {
    val origin = trayMenuOrigin(anchor, menuSize)
    WindowPosition.Absolute(origin.x.toDp(), origin.y.toDp())
}

private val TrayMenuRing = 0.dp
private val TrayMenuCardWidth = 200.dp
private val TrayMenuPlaceholderHeight = 220.dp
private const val TRAY_MENU_GAP_PX = 6

/** 托盘媒体项沿用播放条里同一组图标。 */
private fun trayMediaIcon(iconResource: String): ImageVector = when (iconResource) {
    "previous" -> Icons.Filled.SkipPrevious
    "pause" -> Icons.Filled.Pause
    "next" -> Icons.Filled.SkipNext
    else -> Icons.Filled.PlayArrow
}

private fun desktopPlayModeIcon(mode: DesktopPlayMode): ImageVector = when (mode) {
    DesktopPlayMode.Sequential -> Icons.AutoMirrored.Outlined.PlaylistPlay
    DesktopPlayMode.ListLoop -> Icons.Outlined.Repeat
    DesktopPlayMode.SingleLoop -> Icons.Filled.RepeatOne
    DesktopPlayMode.Shuffle -> Icons.Outlined.Shuffle
}

@Composable
private fun NaturalLanguageField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.height(50.dp),
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            color = colors.onSurface,
            lineHeight = 22.sp,
        ),
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { innerTextField ->
            Row(
                Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(15.dp))
                    .background(colors.surface.copy(alpha = 0.86f))
                    .border(1.dp, colors.outlineVariant, RoundedCornerShape(15.dp))
                    .padding(horizontal = 15.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, Modifier.size(19.dp), tint = colors.primary)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            tr("search.placeholder"),
                            style = MaterialTheme.typography.bodyMedium,
                            color = colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    innerTextField()
                }
                if (value.isNotEmpty()) {
                    IconButton(onClick = { onValueChange("") }, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Outlined.Close, tr("search.clear"), Modifier.size(16.dp), tint = colors.onSurfaceVariant)
                    }
                }
            }
        },
    )
}

/** A fixed-size status slot prevents Gateway activity from shifting x/y positions in the top bar. */
@Composable
private fun GatewayStatus(controller: DesktopPlayerController) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.width(196.dp).height(40.dp),
        shape = RoundedCornerShape(12.dp),
        color = colors.surface.copy(alpha = 0.62f),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.75f)),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                if (controller.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 1.7.dp,
                        color = colors.primary,
                    )
                } else {
                    Icon(Icons.Outlined.CloudDone, null, Modifier.size(17.dp), tint = colors.primary)
                }
            }
            Spacer(Modifier.width(7.dp))
            Text(
                controller.statusMessage ?: if (controller.isSignedIn) tr("topbar.synced") else tr("topbar.connected"),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun HomePage(controller: DesktopPlayerController, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val inertia = LocalScrollInertia.current
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxWidth()
            .scrollInertia(listState, inertia),
        contentPadding = PaddingValues(top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        item {
            Column {
                SectionHeading(
                    if (controller.isSignedIn) tr("home.desktop.signed") else tr("home.desktop.anon"),
                    if (controller.isSignedIn) tr("home.desktop.signed.sub") else tr("home.desktop.anon.sub"),
                )
                Spacer(Modifier.height(12.dp))
                PlaylistStrip(controller.featuredPlaylists.take(8), controller::openPlaylist)
            }
        }
        item {
            val visibleTracks = visiblePlaylistTracks(
                controller.activePlaylist,
                controller.activePlaylistTracks,
                controller.recentTracks,
            )
            TrackSection(
                title = controller.activePlaylistTitle ?: tr("home.continue"),
                tracks = visibleTracks.take(8),
                onPlay = controller::playTrack,
            )
        }
    }
}

@Composable
private fun IntentSuggestions(controller: DesktopPlayerController) {
    val suggestions = listOf(
        tr("search.suggestion.focus"),
        tr("search.suggestion.walk"),
        tr("search.suggestion.familiar"),
        tr("search.suggestion.rain"),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        suggestions.forEach { suggestion ->
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.74f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.clickable { controller.useIntentSuggestion(suggestion) },
            ) {
                Text(
                    suggestion,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                )
            }
        }
    }
}

@Composable
private fun ArtistPage(controller: DesktopPlayerController, modifier: Modifier = Modifier) {
    val artist = controller.activeArtist ?: return
    val tracks = controller.activeArtistTracks
    val colors = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    val inertia = LocalScrollInertia.current
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().scrollInertia(listState, inertia),
        contentPadding = PaddingValues(top = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            TextButton(onClick = controller::closeArtist, shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("common.back"), Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("common.back"))
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Artwork(
                    id = artist.id,
                    title = artist.name,
                    coverUrl = sequenceOf(artist.cover, artist.picUrl, artist.avatar)
                        .mapNotNull { it?.trim()?.takeIf(String::isNotBlank) }
                        .firstOrNull(),
                    modifier = Modifier.size(156.dp),
                    cornerRadius = 28.dp,
                )
                Spacer(Modifier.width(28.dp))
                Column(Modifier.weight(1f).widthIn(max = 620.dp)) {
                    Text(artist.name, style = MaterialTheme.typography.displaySmall)
                    if (artist.alias.isNotEmpty()) {
                        Spacer(Modifier.height(5.dp))
                        Text(artist.alias.joinToString(" / "), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    artist.briefDesc?.takeIf(String::isNotBlank)?.let { description ->
                        Spacer(Modifier.height(12.dp))
                        Text(description, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        artist.musicSize?.let { Text(tr("artist.music_count", it), style = MaterialTheme.typography.labelMedium, color = colors.primary) }
                        artist.albumSize?.let { Text(tr("artist.album_count", it), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant) }
                    }
                    if (tracks.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(onClick = { controller.playTrack(tracks.first()) }, shape = RoundedCornerShape(12.dp)) {
                                Icon(Icons.Filled.PlayArrow, null, Modifier.size(19.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr("artist.play_all"))
                            }
                            OutlinedButton(
                                onClick = { controller.shufflePlay(tracks) },
                                shape = RoundedCornerShape(12.dp),
                            ) {
                                Icon(Icons.Outlined.Shuffle, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr("player.shuffle"))
                            }
                        }
                    }
                }
            }
        }
        item { SectionHeading(tr("artist.popular"), tr("tracks.count", tracks.size)) }
        when {
            controller.isArtistLoading && tracks.isEmpty() -> item { QuietEmptyState(tr("artist.loading"), tr("artist.loading_hint")) }
            tracks.isEmpty() -> item { QuietEmptyState(tr("artist.empty"), tr("artist.empty_hint")) }
            else -> itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                TrackRow(track, controller.nowPlaying?.id == track.id, { controller.playTrack(track) }, index + 1)
            }
        }
    }
}

@Composable
private fun LibraryPage(controller: DesktopPlayerController, modifier: Modifier = Modifier) {
    val active = controller.activePlaylist
    val browsing = controller.browsePlaylists()
    val listState = rememberLazyListState()
    val inertia = LocalScrollInertia.current
    val currentTrackIndex = if (controller.nowPlaying?.isLocalFile == true) {
        controller.localLibraryTracks.indexOfFirst { it.id == controller.nowPlaying?.id }
    } else if (active?.isLikedCollection == false) {
        controller.activePlaylistTracks.indexOfFirst { it.id == controller.nowPlaying?.id }
    } else {
        -1
    }
    val scope = rememberCoroutineScope()
    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().scrollInertia(listState, inertia),
            contentPadding = PaddingValues(
                top = 22.dp,
                bottom = if (currentTrackIndex >= 0) 96.dp else 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        PageHeading(
                            tr("library.local.title"),
                            tr("library.local.count", controller.localLibraryTracks.size),
                        )
                        Spacer(Modifier.weight(1f))
                        TextButton(
                            enabled = controller.localLibraryReady && !controller.localLibraryScanning,
                            onClick = { chooseDesktopAudioDirectory()?.let(controller::addLocalLibraryRoot) },
                        ) {
                            Icon(Icons.Outlined.FolderOpen, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text(tr("library.local.add_folder"))
                        }
                        TextButton(
                            enabled = controller.localLibraryReady && !controller.localLibraryScanning &&
                                controller.localLibraryRoots.isNotEmpty(),
                            onClick = controller::rescanLocalLibrary,
                        ) {
                            if (controller.localLibraryScanning) {
                                CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                            }
                            Spacer(Modifier.width(5.dp))
                            Text(tr("library.local.scan"))
                        }
                    }
                    if (controller.localLibraryRoots.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            controller.localLibraryRoots.forEach { root ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        root,
                                        modifier = Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    IconButton(
                                        enabled = !controller.localLibraryScanning,
                                        onClick = { controller.removeLocalLibraryRoot(root) },
                                        modifier = Modifier.size(28.dp),
                                    ) {
                                        Icon(
                                            Icons.Outlined.Close,
                                            tr("library.local.remove_folder"),
                                            Modifier.size(15.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (controller.localLibraryScanning) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            tr("library.local.scanning"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    controller.localLibraryScanError?.let { error ->
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                    if (!controller.localLibraryScanning && controller.localLibraryTracks.isEmpty()) {
                        QuietEmptyState(tr("library.local.empty"), tr("library.local.empty.hint"))
                    }
                }
            }
            if (controller.localLibraryTracks.isNotEmpty()) {
                itemsIndexed(controller.localLibraryTracks, key = { _, track -> track.id }) { index, track ->
                    TrackRow(
                        track,
                        track.id == controller.nowPlaying?.id,
                        { controller.playLocalLibraryTrack(track) },
                        index + 1,
                    )
                }
            }
            item { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)) }
            when {
                !controller.isSignedIn -> {
                    item { PageHeading(tr("library.title"), tr("library.sub.desktop.anon")) }
                    item { SignInInvitation(controller::openLogin) }
                }
                browsing.isEmpty() && active == null -> {
                    item {
                        Row(verticalAlignment = Alignment.Bottom) {
                            PageHeading(tr("library.title"), tr("library.sub.desktop.signed"))
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = controller::syncLibrary) {
                                Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr("nav.sync"))
                            }
                        }
                    }
                    item { QuietEmptyState(tr("library.no_playlists"), tr("library.no_playlists.hint")) }
                }
                active != null && (active.isLikedCollection.not()) -> {
                    item { PlaylistDetailHeader(active, onPlayAll = { active.let { controller.activePlaylistTracks.firstOrNull()?.let(controller::playTrack) } }, onShuffleAll = { controller.shufflePlay(controller.activePlaylistTracks) }) }
                    if (controller.activePlaylistTracks.isEmpty()) {
                        item {
                            QuietEmptyState(
                                if (controller.isLoading) tr("library.opening") else tr("library.playlist_empty"),
                                if (controller.isLoading) tr("library.sync.cover") else tr("library.try_other"),
                            )
                        }
                    } else {
                        itemsIndexed(controller.activePlaylistTracks, key = { _, track -> track.id }) { index, track ->
                            TrackRow(track, track.id == controller.nowPlaying?.id, { controller.playTrack(track) }, index + 1)
                        }
                    }
                }
                else -> {
                    item {
                        Row(verticalAlignment = Alignment.Bottom) {
                            PageHeading(tr("library.title"), tr("library.pick"))
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = controller::syncLibrary) {
                                Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(tr("nav.sync"))
                            }
                        }
                    }
                    item { PlaylistStrip(browsing, controller::openPlaylist) }
                }
            }
        }
        if (currentTrackIndex >= 0) {
            NowPlayingLocatorButton(
                onClick = {
                    inertia.stop()
                    scope.launch { listState.animateScrollToItem(currentTrackIndex + 1) }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 22.dp),
            )
        }
    }
}

@Composable
private fun LikedPage(controller: DesktopPlayerController, modifier: Modifier = Modifier) {
    LaunchedEffect(controller.isSignedIn, controller.likedPlaylist()?.id) {
        if (controller.isSignedIn) controller.openLikedCollection()
    }
    val playlist = controller.activePlaylist?.takeIf { it.isLikedCollection }
        ?: controller.likedPlaylist()
        ?: PlaylistItem(
            id = -5L,
            title = tr("liked.title"),
            subtitle = tr("liked.private"),
            coverUrl = controller.likedTracks.firstOrNull()?.coverUrl,
            trackCount = controller.likedTracks.size,
            creatorName = controller.currentUser?.nickname,
            isLikedCollection = true,
        )
    val tracks = if (controller.activePlaylist?.isLikedCollection == true) {
        controller.activePlaylistTracks
    } else {
        controller.likedTracks
    }
    val listState = rememberLazyListState()
    val inertia = LocalScrollInertia.current
    val currentTrackIndex = tracks.indexOfFirst { it.id == controller.nowPlaying?.id }
    val scope = rememberCoroutineScope()
    Box(modifier = modifier.fillMaxWidth()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().scrollInertia(listState, inertia),
            contentPadding = PaddingValues(
                top = 22.dp,
                bottom = if (currentTrackIndex >= 0) 96.dp else 28.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            when {
                !controller.isSignedIn -> {
                    item { PageHeading(tr("liked.title"), tr("liked.sub.anon")) }
                    item { SignInInvitation(controller::openLogin) }
                }
                else -> {
                    item {
                        PlaylistDetailHeader(
                            playlist = playlist.copy(
                                title = tr("liked.title"),
                                trackCount = tracks.size.takeIf { it > 0 } ?: playlist.trackCount,
                                coverUrl = playlist.coverUrl ?: tracks.firstOrNull()?.coverUrl,
                            ),
                            onPlayAll = { tracks.firstOrNull()?.let(controller::playTrack) },
                            onShuffleAll = { controller.shufflePlay(tracks) },
                        )
                    }
                    if (tracks.isEmpty()) {
                        item {
                            QuietEmptyState(
                                if (controller.isLoading) tr("liked.preparing") else tr("liked.empty"),
                                if (controller.isLoading) tr("liked.sync") else tr("liked.hint"),
                            )
                        }
                    } else {
                        itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                            TrackRow(track, track.id == controller.nowPlaying?.id, { controller.playTrack(track) }, index + 1)
                        }
                    }
                }
            }
        }
        if (controller.isSignedIn && currentTrackIndex >= 0) {
            NowPlayingLocatorButton(
                onClick = {
                    inertia.stop()
                    scope.launch { listState.animateScrollToItem(currentTrackIndex + 1) }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 22.dp),
            )
        }
    }
}

@Composable
private fun NowPlayingLocatorButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    ExtendedFloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.primary,
        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp, pressedElevation = 2.dp),
        icon = { Icon(Icons.Outlined.MyLocation, null, Modifier.size(18.dp)) },
        text = { Text(tr("playlist.locate"), style = MaterialTheme.typography.labelLarge) },
    )
}

@Composable
private fun PlaylistDetailHeader(playlist: PlaylistItem, onPlayAll: () -> Unit, onShuffleAll: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Artwork(
            id = playlist.id,
            title = playlist.title,
            coverUrl = playlist.coverUrl,
            modifier = Modifier.size(148.dp),
            cornerRadius = 18.dp,
        )
        Spacer(Modifier.width(22.dp))
        Column(
            modifier = Modifier.weight(1f).padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (playlist.isLikedCollection) tr("liked.title") else playlist.title,
                style = MaterialTheme.typography.headlineMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val creator = playlist.creatorName?.takeIf { it.isNotBlank() }
            if (creator != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(colors.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            creator.first().toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onPrimaryContainer,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(creator, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            Text(
                buildString {
                    val count = playlist.trackCount
                    if (count > 0) append(tr("playlist.tracks", count)) else append(tr("playlist.organizing"))
                    playlist.creatorName?.takeIf { it.isNotBlank() }?.let {
                        // creator already shown above; keep the meta line minimal
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onPlayAll,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("playlist.play_all"))
                }
                OutlinedButton(
                    onClick = onShuffleAll,
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Outlined.Shuffle, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("player.shuffle"))
                }
            }
        }
    }
}

@Composable
private fun SearchPage(controller: DesktopPlayerController, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    val inertia = LocalScrollInertia.current
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().scrollInertia(listState, inertia),
        contentPadding = PaddingValues(top = 22.dp, bottom = 28.dp),
    ) {
        item {
            PageHeading(tr("search.heading", controller.searchQuery), tr("search.heading.sub"))
            Spacer(Modifier.height(22.dp))
        }
        if (controller.searchResults.isEmpty() && !controller.isLoading) {
            item { QuietEmptyState(tr("search.no_match"), tr("search.no_match.hint")) }
        } else {
            items(controller.searchResults, key = { it.id }) { track ->
                TrackRow(track, track.id == controller.nowPlaying?.id, { controller.playTrack(track) })
            }
        }
    }
}

@Composable
private fun PageHeading(title: String, subtitle: String) {
    Column(Modifier.widthIn(max = 720.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(5.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DesktopHiFiSection(controller: DesktopPlayerController) {
    val colors = MaterialTheme.colorScheme
    val available = controller.hifiEngineAvailable
    val bitPerfectAvailable = available && supportsDesktopBitPerfectOutput()
    val nativeDsdAvailable = available && supportsDesktopNativeDsdOutput()
    var pcmTestSampleRate by remember { mutableIntStateOf(48_000) }
    var pcmTestBitDepth by remember { mutableIntStateOf(16) }
    LaunchedEffect(available) {
        if (available) controller.refreshHifiOutputDevices()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = available, role = androidx.compose.ui.semantics.Role.Switch) {
                controller.updateHifiEngine(!controller.hifiEngineEnabled)
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tr("settings.hifi.engine"), style = MaterialTheme.typography.bodyMedium)
            Text(
                when {
                    !available -> tr("settings.hifi.engine.unavailable")
                    controller.hifiEngineEnabled -> tr("settings.hifi.engine.on")
                    controller.nowPlaying?.isLocalFile == true -> tr("settings.hifi.engine.local_file")
                    else -> tr("settings.hifi.engine.off")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (available) colors.onSurfaceVariant else colors.error,
            )
        }
        Spacer(Modifier.width(12.dp))
        LazerSwitch(
            engine = controller.themeEngine,
            checked = controller.hifiEngineEnabled,
            onCheckedChange = if (available) {
                ({ enabled -> controller.updateHifiEngine(enabled) })
            } else {
                null
            },
            enabled = available,
        )
    }
    if (isMacOSDesktop()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(enabled = available, role = androidx.compose.ui.semantics.Role.Switch) {
                    controller.updateExclusiveAudio(!controller.exclusiveAudio)
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("settings.hifi.hog_mode.title"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    tr("settings.hifi.hog_mode.hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            LazerSwitch(
                engine = controller.themeEngine,
                checked = controller.exclusiveAudio,
                onCheckedChange = null,
                enabled = available,
            )
        }
    }
    if (controller.hifiEngineEnabled || controller.nowPlaying?.isLocalFile == true) {
        Text(
            tr("settings.hifi.engine.hint"),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 14.dp),
        )
        val selectedDevice = controller.selectedHifiOutputDevice
        val endpointVolumeDevice = controller.selectedHifiEndpointVolumeDevice
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("settings.hifi.device.title"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    when {
                        controller.hifiOutputDeviceUnavailable -> tr("settings.hifi.device.unavailable")
                        selectedDevice == null -> tr(controller.hifiDefaultOutputLabelKey)
                        selectedDevice.isDefault ->
                            "${selectedDevice.displayName} · ${tr("settings.hifi.device.default_tag")}"
                        else -> selectedDevice.displayName
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (controller.hifiOutputDeviceUnavailable) colors.error else colors.onSurfaceVariant,
                )
                if (selectedDevice != null && !selectedDevice.stableIdentity) {
                    Text(
                        tr("settings.hifi.device.weak_identity"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                endpointVolumeDevice?.let { device ->
                    val supportText = when {
                        !device.endpointVolumeQuerySucceeded -> "settings.hifi.device.volume.unknown"
                        device.reportsHardwareEndpointVolume -> "settings.hifi.device.volume.hardware"
                        else -> "settings.hifi.device.volume.not_reported"
                    }
                    Text(
                        tr(supportText),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                controller.hifiOutputDevicesError?.let { error ->
                    Text(
                        "${tr("settings.hifi.device.list_fail")}: $error",
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                    )
                }
            }
            if (controller.hifiOutputDevicesLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
            DesktopSettingsDropdown(
                options = listOf<DesktopAudioOutputDevice?>(null) + controller.hifiOutputDevices.filter {
                    it.active
                },
                selected = controller.selectedHifiOutputDevice,
                label = { device ->
                    when {
                        device == null && controller.hifiOutputDeviceUnavailable -> tr("settings.hifi.device.unavailable")
                        device == null -> tr(controller.hifiDefaultOutputLabelKey)
                        else -> device.displayName
                    }
                },
                onSelected = controller::selectHifiOutputDevice,
            )
            TextButton(onClick = controller::refreshHifiOutputDevices) {
                Text(tr("settings.hifi.device.refresh"))
            }
        }
        if (endpointVolumeDevice?.reportsHardwareEndpointVolume == true) {
            val scalar = controller.hifiEndpointVolumeScalar
            var localVolume by remember(endpointVolumeDevice.identityKey, scalar) {
                mutableFloatStateOf(scalar ?: 0f)
            }
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("settings.hifi.device.volume.control"), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            tr("settings.hifi.device.volume.hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    if (controller.hifiEndpointVolumeLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else if (scalar != null) {
                        Text(
                            "${(localVolume * 100f).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = colors.primary,
                        )
                    }
                }
                if (scalar != null) {
                    LazerSlider(
                        engine = controller.themeEngine,
                        value = localVolume,
                        onValueChange = { localVolume = it },
                        enabled = !controller.hifiEndpointVolumeLoading,
                        valueRange = 0f..1f,
                        onValueChangeFinished = { controller.setHifiEndpointVolume(localVolume) },
                    )
                } else if (controller.hifiEndpointVolumeError) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            tr("settings.hifi.device.volume.read_error"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.error,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = controller::refreshHifiEndpointVolume,
                            enabled = !controller.hifiEndpointVolumeLoading,
                        ) {
                            Text(tr("settings.hifi.device.refresh"))
                        }
                    }
                }
            }
        }
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("settings.hifi.formats.title"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        tr("settings.hifi.formats.hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (controller.hifiPcmFormatProbeLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                TextButton(
                    onClick = controller::probeHifiOutputFormats,
                    enabled = controller.hifiPcmFormatProbeDevice != null &&
                        !controller.hifiOutputDeviceUnavailable &&
                        !controller.hifiOutputDevicesLoading &&
                        !controller.hifiPcmFormatProbeLoading &&
                        !controller.hifiPcmSessionProbeLoading,
                ) {
                    Text(
                        if (controller.hifiPcmFormatProbeLoading) {
                            tr("settings.hifi.formats.loading")
                        } else {
                            tr("settings.hifi.formats.probe")
                        },
                    )
                }
            }
            controller.hifiPcmFormatProbeError?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                )
            }
            val results = controller.hifiPcmFormatProbeResults
            val target = controller.hifiPcmFormatProbeTarget
            if (results.isNotEmpty() && target?.identityKey == controller.hifiPcmFormatProbeDevice?.identityKey) {
                Column(Modifier.padding(top = 4.dp)) {
                    results.groupBy { it.candidate.sampleRateHz }.toSortedMap().forEach { (rate, candidates) ->
                        val supported = candidates.filter {
                            it.status == DesktopWasapiPcmFormatProbeStatus.Supported
                        }
                        val hasErrors = candidates.any { it.status == DesktopWasapiPcmFormatProbeStatus.Error }
                        val formatLabels = supported.map { result ->
                            val candidate = result.candidate
                            if (candidate.validBits == candidate.containerBits) {
                                "${candidate.validBits} bit"
                            } else {
                                tr(
                                    "settings.hifi.formats.valid_in_container",
                                    candidate.validBits,
                                    candidate.containerBits,
                                )
                            }
                        } + if (hasErrors) listOf(tr("settings.hifi.formats.unknown")) else emptyList()
                        val formats = formatLabels.takeIf { it.isNotEmpty() }?.joinToString(" · ")
                            ?: tr("settings.hifi.formats.none")
                        Text(
                            "${wasapiPcmRateLabel(rate)} kHz · $formats",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("settings.hifi.session_probe.title"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        tr("settings.hifi.session_probe.hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (controller.hifiPcmSessionProbeLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
                TextButton(
                    onClick = controller::probeHifiOutputSessions,
                    enabled = controller.hifiPcmFormatProbeDevice != null &&
                        !controller.hifiOutputDeviceUnavailable &&
                        !controller.hifiOutputDevicesLoading &&
                        !controller.hifiPcmSessionProbeLoading &&
                        !controller.hifiPcmFormatProbeLoading,
                ) {
                    Text(
                        if (controller.hifiPcmSessionProbeLoading) {
                            tr("settings.hifi.session_probe.loading")
                        } else {
                            tr("settings.hifi.session_probe.probe")
                        },
                    )
                }
            }
            controller.hifiPcmSessionProbeError?.let { error ->
                Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error)
            }
            val sessionResults = controller.hifiPcmSessionProbeResults
            val sessionTarget = controller.hifiPcmSessionProbeTarget
            if (sessionResults.isNotEmpty() &&
                sessionTarget?.identityKey == controller.hifiPcmFormatProbeDevice?.identityKey
            ) {
                Column(Modifier.padding(top = 4.dp)) {
                    sessionResults.groupBy { it.candidate.sampleRateHz }.toSortedMap().forEach { (rate, candidates) ->
                        Text(
                            "${wasapiPcmRateLabel(rate)} kHz",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        candidates.forEach { result ->
                            val candidate = result.candidate
                            val format = if (candidate.validBits == candidate.containerBits) {
                                "${candidate.validBits} bit"
                            } else {
                                tr(
                                    "settings.hifi.formats.valid_in_container",
                                    candidate.validBits,
                                    candidate.containerBits,
                                )
                            }
                            val status = when (result.status) {
                                DesktopWasapiPcmSessionProbeStatus.Initialized -> tr("settings.hifi.session_probe.initialized")
                                DesktopWasapiPcmSessionProbeStatus.Unsupported -> tr("settings.hifi.session_probe.unsupported")
                                DesktopWasapiPcmSessionProbeStatus.Error -> tr("settings.hifi.session_probe.error")
                            }
                            val actual = result.actualSessionFormat?.let {
                                tr(
                                    "settings.hifi.session_probe.actual_format",
                                    wasapiPcmRateLabel(it.sampleRateHz),
                                    it.channels,
                                    it.validBits,
                                    it.containerBits,
                                    if (it.isFloat) tr("settings.hifi.session_probe.float_pcm") else tr("settings.hifi.session_probe.integer_pcm"),
                                    if (it.exclusive) tr("settings.hifi.session_probe.exclusive") else tr("settings.hifi.session_probe.shared"),
                                )
                            }
                            val failure = result.failure?.let { tr(it.messageKey) }
                            Text(
                                buildString {
                                    append("$format: $status")
                                    if (actual != null) append(" · $actual")
                                    if (failure != null && result.status == DesktopWasapiPcmSessionProbeStatus.Error) {
                                        append(" · $failure")
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (result.status == DesktopWasapiPcmSessionProbeStatus.Error) {
                                    colors.error
                                } else {
                                    colors.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("settings.hifi.buffer"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    tr("settings.hifi.buffer.hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            DesktopSettingsDropdown(
                options = HiFiBufferOptions,
                selected = nearestHiFiBufferOption(controller.hifiBufferMillis),
                label = { option -> "$option ms" },
                onSelected = controller::updateHifiBufferMillis,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("settings.hifi.replaygain"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    tr("settings.hifi.replaygain.hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            DesktopSettingsDropdown(
                options = DesktopReplayGainMode.entries,
                selected = controller.replayGainMode,
                label = { mode ->
                    tr(
                        when (mode) {
                            DesktopReplayGainMode.Off -> "settings.hifi.replaygain.off"
                            DesktopReplayGainMode.Track -> "settings.hifi.replaygain.track"
                            DesktopReplayGainMode.Album -> "settings.hifi.replaygain.album"
                        },
                    )
                },
                onSelected = controller::updateReplayGainMode,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(enabled = bitPerfectAvailable, role = androidx.compose.ui.semantics.Role.Switch) {
                    controller.updateHifiBitPerfect(!controller.hifiBitPerfect)
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("settings.hifi.bit_perfect"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    tr("settings.hifi.bit_perfect.hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            LazerSwitch(
                engine = controller.themeEngine,
                checked = controller.hifiBitPerfect,
                onCheckedChange = null,
                enabled = bitPerfectAvailable,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(enabled = available, role = androidx.compose.ui.semantics.Role.Switch) {
                    controller.updateHifiDoPOutput(!controller.hifiDoPOutput)
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("settings.hifi.dop"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    tr("settings.hifi.dop.hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            LazerSwitch(
                engine = controller.themeEngine,
                checked = controller.hifiDoPOutput,
                onCheckedChange = null,
                enabled = available,
            )
        }
        if (nativeDsdAvailable) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(enabled = nativeDsdAvailable, role = androidx.compose.ui.semantics.Role.Switch) {
                        controller.updateHifiNativeDsdOutput(!controller.hifiNativeDsdOutput)
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(tr("settings.hifi.native_dsd"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        tr("settings.hifi.native_dsd.hint"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                LazerSwitch(
                    engine = controller.themeEngine,
                    checked = controller.hifiNativeDsdOutput,
                    onCheckedChange = null,
                    enabled = nativeDsdAvailable,
                )
            }
        }
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(
                tr("settings.hifi.stream"),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            val info = controller.hifiStreamInfo
            val negotiatedDoP = info?.let {
                (it.signalPath.negotiation.negotiatedFormat as? AudioFormat.DoP)
                    ?.takeIf { format ->
                        it.isDoPOutput && it.outputFormatInitialized &&
                            it.signalPath.negotiation.status == OutputNegotiationStatus.Accepted
                    }
            }
            val negotiatedNativeDsd = info?.let {
                (it.signalPath.negotiation.negotiatedFormat as? AudioFormat.Dsd)
                    ?.takeIf { format ->
                        it.isNativeDsdOutput && it.outputFormatInitialized &&
                            it.signalPath.negotiation.status == OutputNegotiationStatus.Accepted
                    }
            }
            Text(
                if (info == null) {
                    tr("settings.hifi.stream.none")
                } else {
                    buildString {
                        if (negotiatedDoP != null) {
                            append(
                                tr(
                                    "settings.hifi.stream.format.dop",
                                    info.sourceDsdRateMultiplier.takeIf { multiplier -> multiplier > 0 }
                                        ?.toString() ?: "?",
                                    negotiatedDoP.rate.multiplier,
                                    negotiatedDoP.carrierSampleRateHz,
                                ),
                            )
                        } else if (negotiatedNativeDsd != null) {
                            append(
                                tr(
                                    "settings.hifi.stream.format.native_dsd",
                                    info.sourceDsdRateMultiplier.takeIf { multiplier -> multiplier > 0 }
                                        ?.toString() ?: "?",
                                    info.nativeDsdAlsaFormat ?: "ALSA Native DSD",
                                    info.sampleRate,
                                ),
                            )
                        } else if (info.isNativeDsdOutput) {
                            append(
                                tr(
                                    "settings.hifi.stream.format.native_dsd_unknown",
                                    info.sourceDsdRateMultiplier.takeIf { multiplier -> multiplier > 0 }
                                        ?.toString() ?: "?",
                                ),
                            )
                        } else if (info.isDoPOutput) {
                            append(
                                tr(
                                    "settings.hifi.stream.format.dop_unknown",
                                    info.sourceDsdRateMultiplier.takeIf { multiplier -> multiplier > 0 }
                                        ?.toString() ?: "?",
                                ),
                            )
                        } else if (info.hasDsdSource) {
                            append(tr(
                                "settings.hifi.stream.format.dsd",
                                info.sourceDsdRateMultiplier.takeIf { it > 0 }?.toString() ?: "?",
                                info.sampleRate,
                                info.bitsPerSample,
                            ))
                        } else {
                            append(tr("settings.hifi.stream.format", info.codec, info.sampleRate, info.bitsPerSample))
                        }
                        if (info.lossless) append(" · ").append(tr("settings.hifi.stream.lossless"))
                        if (isMacOSDesktop()) {
                            append(" · ").append(
                                tr(if (info.exclusive) "settings.hifi.hog_mode.active" else "settings.hifi.hog_mode.shared"),
                            )
                        }
                        if (info.bitPerfectActive) append(" · ").append(tr("settings.hifi.stream.direct"))
                        if (info.signalPath.verification.status == BitPerfectVerificationStatus.NotRun &&
                            info.bitPerfectActive
                        ) {
                            append(" · ").append(tr("settings.hifi.stream.verification_pending"))
                        }
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = colors.primary,
            )
            controller.activeReplayGainResolution?.let { resolution ->
                val sourceLabel = tr(
                    when (resolution.source) {
                        DesktopReplayGainSource.None -> "settings.hifi.replaygain.source.none"
                        DesktopReplayGainSource.Track -> "settings.hifi.replaygain.source.track"
                        DesktopReplayGainSource.Album -> "settings.hifi.replaygain.source.album"
                        DesktopReplayGainSource.TrackFallback -> "settings.hifi.replaygain.source.track_fallback"
                    },
                )
                val gain = formatTelemetryDecibels(resolution.appliedGainDb)
                Text(
                    if (resolution.source == DesktopReplayGainSource.None) {
                        tr("settings.hifi.replaygain.no_tag")
                    } else {
                        tr("settings.hifi.replaygain.applied", if (resolution.appliedGainDb > 0.0) "+$gain" else gain, sourceLabel)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                if (resolution.boostLimited) {
                    Text(
                        tr("settings.hifi.replaygain.boost_limited"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                if (resolution.appliedGainDb != 0.0 && controller.hifiBitPerfect) {
                    Text(
                        tr("settings.hifi.replaygain.bit_perfect_disabled"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                    )
                }
            }
            info?.takeIf { it.underrunActive || it.underrunFrames > 0L }?.let { stream ->
                val estimatedMillis = stream.estimatedUnderrunMillis
                Text(
                    if (stream.underrunActive) {
                        tr(
                            "settings.hifi.stream.underrun.active",
                            stream.underrunFrames,
                            estimatedMillis?.toString() ?: "?",
                        )
                    } else {
                        tr(
                            "settings.hifi.stream.underrun.history",
                            stream.underrunFrames,
                            estimatedMillis?.toString() ?: "?",
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stream.underrunActive) colors.error else colors.onSurfaceVariant,
                )
                Text(
                    tr("settings.hifi.stream.underrun.note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            info?.signalPath?.outputTelemetry?.let { telemetry ->
                val telemetryColor = if (
                    telemetry.samplePeakDbfs > 0.0 || (telemetry.clippedIntegerSampleCount ?: 0L) > 0L
                ) {
                    colors.error
                } else {
                    colors.onSurfaceVariant
                }
                Text(
                    buildString {
                        append(
                            tr(
                                "settings.hifi.telemetry.summary",
                                formatTelemetryDecibels(telemetry.samplePeakDbfs),
                                formatTelemetryDecibels(telemetry.limiterGainReductionDb),
                            ),
                        )
                        telemetry.clippedIntegerSampleCount?.let { count ->
                            append(" · ").append(tr("settings.hifi.telemetry.clipped", count))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = telemetryColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tr("settings.hifi.telemetry.note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            info?.let {
                Text(
                    if (negotiatedNativeDsd != null) {
                        tr(
                            "settings.hifi.stream.path.native_dsd",
                            it.sourceDsdRateMultiplier.takeIf { multiplier -> multiplier > 0 }?.toString() ?: "?",
                            it.sourceChannels,
                            it.nativeDsdAlsaFormat ?: "ALSA Native DSD",
                            it.sampleRate,
                            it.channels,
                        )
                    } else if (it.isNativeDsdOutput) {
                        tr("settings.hifi.stream.path.native_dsd_unknown")
                    } else if (negotiatedDoP != null) {
                        tr(
                            "settings.hifi.stream.path.dop",
                            it.sourceDsdRateMultiplier.takeIf { multiplier -> multiplier > 0 }
                                ?.toString() ?: "?",
                            negotiatedDoP.rate.multiplier,
                            it.sourceChannels,
                            negotiatedDoP.carrierSampleRateHz,
                            negotiatedDoP.channelLayout.channelCount,
                            if (it.exclusive) tr("settings.hifi.stream.exclusive") else tr("settings.hifi.stream.shared"),
                        )
                    } else if (it.isDoPOutput) {
                        tr("settings.hifi.stream.path.dop_unknown")
                    } else if (it.hasDsdSource) {
                        tr(
                            "settings.hifi.stream.path.dsd",
                            it.sourceDsdRateMultiplier.takeIf { multiplier -> multiplier > 0 }?.toString() ?: "?",
                            it.sourceChannels,
                            it.sampleRate,
                            it.bitsPerSample,
                            it.channels,
                            if (it.exclusive) tr("settings.hifi.stream.exclusive") else tr("settings.hifi.stream.shared"),
                        )
                    } else {
                        tr(
                            "settings.hifi.stream.path",
                            it.sourceSampleRate.takeIf { rate -> rate > 0 }?.toString() ?: "?",
                            it.sourceBitsPerSample.takeIf { bits -> bits > 0 }?.toString() ?: "?",
                            it.sourceChannels,
                            it.sampleRate,
                            it.bitsPerSample,
                            it.channels,
                            if (it.exclusive) tr("settings.hifi.stream.exclusive") else tr("settings.hifi.stream.shared"),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                if (negotiatedNativeDsd != null) {
                    Text(
                        tr(
                            "settings.hifi.stream.session.native_dsd",
                            it.nativeDsdAlsaFormat ?: "ALSA Native DSD",
                            it.sampleRate,
                            it.channels,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                } else if (it.isNativeDsdOutput) {
                    Text(
                        tr("settings.hifi.stream.session.native_dsd_unknown"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                } else if (negotiatedDoP != null) {
                    Text(
                        tr(
                            "settings.hifi.stream.session.dop",
                            negotiatedDoP.carrierSampleRateHz,
                            negotiatedDoP.channelLayout.channelCount,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                } else if (it.isDoPOutput) {
                    Text(
                        tr("settings.hifi.stream.session.dop_unknown"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                } else if (it.signalPath.negotiation.status == OutputNegotiationStatus.Unknown) {
                    Text(
                        tr("settings.hifi.stream.session_unknown"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                } else {
                    val encoding = when {
                        it.outputIsFloat -> tr("settings.hifi.stream.session.float", it.bitsPerSample)
                        it.bitsPerSample != it.outputContainerBitsPerSample -> tr(
                            "settings.hifi.stream.session.integer_container",
                            it.bitsPerSample,
                            it.outputContainerBitsPerSample,
                        )
                        else -> tr("settings.hifi.stream.session.integer", it.bitsPerSample)
                    }
                    Text(
                        tr("settings.hifi.stream.session_initialized", encoding),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                it.outputNonMixable?.let { nonMixable ->
                    Text(
                        tr(
                            if (nonMixable) "settings.hifi.stream.coreaudio.non_mixable"
                            else "settings.hifi.stream.coreaudio.mixable",
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                val selectionKey = when (it.formatSelection) {
                    OutputFormatSelection.SharedMix -> "settings.hifi.stream.selection.shared_mix"
                    OutputFormatSelection.ExclusiveSource -> "settings.hifi.stream.selection.exclusive_source"
                    OutputFormatSelection.ExclusiveSameRateAlternate ->
                        "settings.hifi.stream.selection.same_rate_alternate"
                    OutputFormatSelection.ExclusiveMonoToStereo ->
                        "settings.hifi.stream.selection.mono_to_stereo"
                    OutputFormatSelection.ExclusiveMixFallback ->
                        "settings.hifi.stream.selection.mix_fallback"
                    OutputFormatSelection.ExclusiveCommonRateFallback ->
                        "settings.hifi.stream.selection.common_rate_fallback"
                    OutputFormatSelection.DoPCarrier -> "settings.hifi.stream.selection.dop_carrier"
                    OutputFormatSelection.NativeDsdU8,
                    OutputFormatSelection.NativeDsdU16Le,
                    OutputFormatSelection.NativeDsdU16Be,
                    OutputFormatSelection.NativeDsdU32Le,
                    OutputFormatSelection.NativeDsdU32Be -> "settings.hifi.stream.selection.native_dsd_format"
                    OutputFormatSelection.Unknown -> null
                }
                selectionKey?.let { key ->
                    Text(
                        if (key == "settings.hifi.stream.selection.native_dsd_format") {
                            tr(key, it.nativeDsdAlsaFormat ?: "ALSA Native DSD")
                        } else {
                            tr(key)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        HorizontalDivider()
        Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tr("settings.hifi.test_tone.title"), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        when (controller.hifiPcmTestToneStatus) {
                            DesktopHiFiTestToneStatus.Idle -> tr("settings.hifi.test_tone.hint")
                            DesktopHiFiTestToneStatus.Preparing -> tr("settings.hifi.test_tone.preparing")
                            DesktopHiFiTestToneStatus.Playing -> tr("settings.hifi.test_tone.playing")
                            DesktopHiFiTestToneStatus.Completed -> tr("settings.hifi.test_tone.completed")
                            DesktopHiFiTestToneStatus.Failed -> tr("settings.hifi.test_tone.failed")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (controller.hifiPcmTestToneStatus == DesktopHiFiTestToneStatus.Failed) {
                            colors.error
                        } else {
                            colors.onSurfaceVariant
                        },
                    )
                }
                DesktopSettingsDropdown(
                    options = DesktopPcmTestWave.supportedSampleRatesHz,
                    selected = pcmTestSampleRate,
                    label = { "${wasapiPcmRateLabel(it)} kHz" },
                    onSelected = { pcmTestSampleRate = it },
                )
                DesktopSettingsDropdown(
                    options = DesktopPcmTestWave.supportedBitDepths,
                    selected = pcmTestBitDepth,
                    label = { "$it bit" },
                    onSelected = { pcmTestBitDepth = it },
                )
                val testToneBusy = controller.hifiPcmTestToneStatus == DesktopHiFiTestToneStatus.Preparing ||
                    controller.hifiPcmTestToneStatus == DesktopHiFiTestToneStatus.Playing
                TextButton(
                    onClick = if (testToneBusy) {
                        ({ controller.stopHiFiPcmTestTone(restorePlayback = true) })
                    } else {
                        ({ controller.playHiFiPcmTestTone(pcmTestSampleRate, pcmTestBitDepth) })
                    },
                    enabled = testToneBusy || (available && controller.hifiEngineEnabled &&
                        !controller.hifiOutputDeviceUnavailable),
                ) {
                    Text(
                        if (testToneBusy) tr("settings.hifi.test_tone.stop")
                        else tr("settings.hifi.test_tone.play"),
                    )
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
        HorizontalDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("settings.hifi.diagnostics.title"), style = MaterialTheme.typography.bodyMedium)
                Text(
                    tr("settings.hifi.diagnostics.privacy_note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
            }
            TextButton(
                onClick = {
                    chooseHiFiDiagnosticsDestination()?.let(controller::exportHifiDiagnostics)
                },
                enabled = controller.hifiDiagnosticsExportStatus != DesktopHiFiDiagnosticsExportStatus.Exporting,
            ) {
                Text(
                    if (controller.hifiDiagnosticsExportStatus == DesktopHiFiDiagnosticsExportStatus.Exporting) {
                        tr("settings.hifi.diagnostics.exporting")
                    } else {
                        tr("settings.hifi.diagnostics.export")
                    },
                )
            }
        }
        when (controller.hifiDiagnosticsExportStatus) {
            DesktopHiFiDiagnosticsExportStatus.Saved -> Text(
                tr("settings.hifi.diagnostics.saved"),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            DesktopHiFiDiagnosticsExportStatus.Failed -> Text(
                tr("settings.hifi.diagnostics.failed"),
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
            )
            DesktopHiFiDiagnosticsExportStatus.Idle,
            DesktopHiFiDiagnosticsExportStatus.Exporting -> Unit
        }
    }
    DesktopNetworkRendererSection(controller)
    DesktopMpdSection(controller)
}

private val HiFiBufferOptions = listOf(60, 120, 240, 480)

@Composable
private fun DesktopMpdSection(controller: DesktopPlayerController) {
    val colors = MaterialTheme.colorScheme
    val status = controller.mpdStatus
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(tr("settings.mpd.title"), style = MaterialTheme.typography.bodyMedium)
        Text(
            tr("settings.mpd.hint"),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = controller.mpdHost,
                onValueChange = controller::updateMpdHost,
                modifier = Modifier.weight(1f),
                label = { Text(tr("settings.mpd.host")) },
                singleLine = true,
            )
            OutlinedTextField(
                value = controller.mpdPort,
                onValueChange = controller::updateMpdPort,
                modifier = Modifier.width(110.dp),
                label = { Text(tr("settings.mpd.port")) },
                singleLine = true,
            )
        }
        OutlinedTextField(
            value = controller.mpdPassword,
            onValueChange = controller::updateMpdPassword,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(tr("settings.mpd.password")) },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
        )
        Text(
            tr("settings.mpd.password.hint"),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = controller::connectMpd, enabled = !controller.mpdLoading) {
                Text(tr(if (controller.mpdLoading) "settings.mpd.connecting" else "settings.mpd.connect"))
            }
            if (controller.mpdLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
        controller.mpdError?.let { error ->
            Text(
                tr("settings.mpd.failed", error),
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
            )
        }
        status?.let { playback ->
            Text(tr("settings.mpd.status.state", playback.state), style = MaterialTheme.typography.bodySmall)
            Text(
                playback.title ?: tr("settings.mpd.status.no_track"),
                style = MaterialTheme.typography.bodyMedium,
            )
            val details = listOfNotNull(
                playback.artists.takeIf(List<String>::isNotEmpty)?.joinToString(", "),
                playback.album,
            ).joinToString(" · ")
            if (details.isNotBlank()) {
                Text(details, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            val duration = playback.durationSeconds?.takeIf { it > 0.0 }
            val elapsed = playback.elapsedSeconds?.coerceAtLeast(0.0) ?: 0.0
            if (duration != null) {
                var seekPosition by remember(playback.durationSeconds) {
                    mutableFloatStateOf(elapsed.toFloat().coerceIn(0f, duration.toFloat()))
                }
                var isSeeking by remember(playback.durationSeconds) { mutableStateOf(false) }
                LaunchedEffect(playback.elapsedSeconds, playback.durationSeconds) {
                    if (!isSeeking) {
                        seekPosition = (playback.elapsedSeconds ?: 0.0)
                            .toFloat()
                            .coerceIn(0f, duration.toFloat())
                    }
                }
                Slider(
                    value = seekPosition,
                    onValueChange = {
                        seekPosition = it
                        isSeeking = true
                    },
                    valueRange = 0f..duration.toFloat().coerceAtLeast(0.001f),
                    enabled = !controller.mpdLoading,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr(
                            "settings.mpd.status.position",
                            formatDuration((seekPosition * 1000.0).toLong()),
                            formatDuration((duration * 1000.0).toLong()),
                        ),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            controller.seekMpd(seekPosition.toDouble())
                            isSeeking = false
                        },
                        enabled = !controller.mpdLoading,
                    ) { Text(tr("settings.mpd.seek")) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    playback.volumePercent?.let { tr("settings.mpd.volume.current", it) }
                        ?: tr("settings.mpd.volume.unknown"),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                TextButton(
                    onClick = { controller.adjustMpdVolume(-5) },
                    enabled = !controller.mpdLoading && (playback.volumePercent ?: 0) >= 5,
                ) { Text(tr("settings.mpd.volume.down")) }
                TextButton(
                    onClick = { controller.adjustMpdVolume(5) },
                    enabled = !controller.mpdLoading && (playback.volumePercent ?: 100) <= 95,
                ) { Text(tr("settings.mpd.volume.up")) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = controller::playMpd, enabled = !controller.mpdLoading) {
                    Text(tr("settings.network_renderer.transport.play"))
                }
                TextButton(onClick = controller::pauseMpd, enabled = !controller.mpdLoading) {
                    Text(tr("settings.network_renderer.transport.pause"))
                }
                TextButton(onClick = controller::stopMpd, enabled = !controller.mpdLoading) {
                    Text(tr("settings.network_renderer.transport.stop"))
                }
            }
        }
        HorizontalDivider()
        Text(tr("settings.mpd.library.title"), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = controller.mpdSearchQuery,
                onValueChange = controller::updateMpdSearchQuery,
                modifier = Modifier.weight(1f),
                label = { Text(tr("settings.mpd.search.label")) },
                singleLine = true,
            )
            TextButton(onClick = controller::searchMpdLibrary, enabled = !controller.mpdLoading) {
                Text(tr("settings.mpd.search.action"))
            }
        }
        if (controller.mpdSearchHasSearched && controller.mpdSearchResults.isEmpty() && !controller.mpdLoading) {
            Text(tr("settings.mpd.search.empty"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            controller.mpdSearchResults.forEach { track ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
                        Text(
                            track.title ?: track.uri.substringAfterLast('/'),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val detail = listOfNotNull(track.artists.joinToString(", ").takeIf(String::isNotBlank), track.album)
                            .joinToString(" · ")
                        if (detail.isNotBlank()) {
                            Text(detail, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                    }
                    TextButton(
                        onClick = { controller.addMpdTrackToQueue(track) },
                        enabled = !controller.mpdLoading,
                    ) { Text(tr("settings.mpd.queue.add")) }
                    TextButton(
                        onClick = { controller.playMpdTrackNow(track) },
                        enabled = !controller.mpdLoading,
                    ) { Text(tr("settings.mpd.track.play_now")) }
                }
            }
        }
        if (controller.mpdSearchTotalCount > 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (controller.mpdSearchResults.isEmpty()) tr("settings.mpd.search.empty")
                    else tr(
                        "settings.mpd.search.page",
                        controller.mpdSearchOffset + 1,
                        controller.mpdSearchOffset + controller.mpdSearchResults.size,
                        controller.mpdSearchTotalCount,
                    ),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                TextButton(
                    onClick = { controller.searchMpdLibrary(controller.mpdSearchOffset - DESKTOP_MPD_PAGE_SIZE) },
                    enabled = !controller.mpdLoading && controller.mpdSearchOffset > 0,
                ) { Text(tr("settings.mpd.queue.previous")) }
                TextButton(
                    onClick = { controller.searchMpdLibrary(controller.mpdSearchOffset + DESKTOP_MPD_PAGE_SIZE) },
                    enabled = !controller.mpdLoading && controller.mpdSearchHasMore,
                ) { Text(tr("settings.mpd.queue.next")) }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("settings.mpd.queue.title", controller.mpdQueuePage?.totalCount ?: 0),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = { controller.refreshMpdQueue(0) }, enabled = !controller.mpdLoading) {
                Text(tr("settings.mpd.queue.refresh"))
            }
        }
        controller.mpdQueuePage?.let { queue ->
            if (queue.totalCount == 0) {
                Text(tr("settings.mpd.queue.empty"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        tr("settings.mpd.queue.page", queue.offset + 1, queue.offset + queue.entries.size, queue.totalCount),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = { controller.refreshMpdQueue((queue.offset - DESKTOP_MPD_PAGE_SIZE).coerceAtLeast(0)) },
                        enabled = !controller.mpdLoading && queue.offset > 0,
                    ) { Text(tr("settings.mpd.queue.previous")) }
                    TextButton(
                        onClick = { controller.refreshMpdQueue(queue.offset + DESKTOP_MPD_PAGE_SIZE) },
                        enabled = !controller.mpdLoading && queue.offset + queue.entries.size < queue.totalCount,
                    ) { Text(tr("settings.mpd.queue.next")) }
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                queue.entries.forEach { track ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
                            val title = track.title ?: track.uri.substringAfterLast('/')
                            val current = track.queueId != null && track.queueId == status?.currentSongId
                            Text(
                                (if (current) "▶ " else "") + title,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(track.artists.joinToString(", ").takeIf(String::isNotBlank), track.album)
                                    .joinToString(" · "),
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        track.queueId?.let { id ->
                            TextButton(onClick = { controller.playMpdQueueItem(id) }, enabled = !controller.mpdLoading) {
                                Text(tr("settings.mpd.track.play_now"))
                            }
                            TextButton(onClick = { controller.removeMpdQueueItem(id) }, enabled = !controller.mpdLoading) {
                                Text(tr("settings.mpd.queue.remove"))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopNetworkRendererSection(controller: DesktopPlayerController) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(tr("settings.network_renderer.title"), style = MaterialTheme.typography.bodyMedium)
        Text(
            tr("settings.network_renderer.hint"),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = controller::discoverNetworkRenderers,
                enabled = !controller.networkRendererDiscoveryLoading,
            ) {
                Text(
                    tr(
                        if (controller.networkRendererDiscoveryLoading) {
                            "settings.network_renderer.discovering"
                        } else {
                            "settings.network_renderer.discover"
                        },
                    ),
                )
            }
            if (controller.networkRendererDiscoveryLoading) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
        controller.networkRendererDiscoveryError?.let { error ->
            Text(
                tr("settings.network_renderer.failed", error),
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
            )
        }
        if (controller.networkRendererDiscoveryHasSearched &&
            !controller.networkRendererDiscoveryLoading &&
            controller.networkRendererDiscoveryError == null &&
            controller.networkRendererDevices.isEmpty()
        ) {
            Text(
                tr("settings.network_renderer.empty"),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        controller.networkRendererDevices.forEach { device ->
            val rendererStatus = controller.networkRendererStatusByIdentity[device.identity]
            val useOpenHomePlaylistControls = controller.usesOpenHomePlaylistForControls(device)
            val description = listOfNotNull(device.manufacturer, device.modelName, device.modelNumber)
                .distinct()
                .joinToString(" · ")
            val port = device.descriptionUri.port.takeIf { it >= 0 }
                ?: if (device.descriptionUri.scheme.equals("https", ignoreCase = true)) 443 else 80
            val services = buildList {
                if (device.supportsAvTransport) add(tr("settings.network_renderer.service.transport"))
                if (device.hasOpenHomePlaylist) add(tr("settings.network_renderer.service.openhome_playlist"))
                if (DesktopUpnpRendererServiceKind.RENDERING_CONTROL in device.services) {
                    add(tr("settings.network_renderer.service.rendering"))
                }
                if (DesktopUpnpRendererServiceKind.CONNECTION_MANAGER in device.services) {
                    add(tr("settings.network_renderer.service.connection"))
                }
            }.joinToString(" · ")
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(device.friendlyName, style = MaterialTheme.typography.bodyMedium)
                if (description.isNotBlank()) {
                    Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                Text(
                    listOfNotNull(
                        "${device.descriptionUri.host}:$port",
                        services.takeIf(String::isNotBlank),
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                val busy = controller.networkRendererOperationLoadingIdentity == device.identity
                val anyRendererOperationBusy = controller.networkRendererOperationLoadingIdentity != null
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = { controller.refreshNetworkRendererStatus(device) },
                        enabled = !anyRendererOperationBusy,
                    ) { Text(tr(if (busy) "settings.network_renderer.working" else "settings.network_renderer.status.refresh")) }
                    TextButton(
                        onClick = { controller.controlNetworkRenderer(device, DesktopUpnpTransportCommand.PLAY) },
                        enabled = !anyRendererOperationBusy && (useOpenHomePlaylistControls ||
                            (device.supportsAvTransport && desktopUpnpActionEnabled(rendererStatus, "Play"))),
                    ) { Text(tr("settings.network_renderer.transport.play")) }
                    TextButton(
                        onClick = { controller.controlNetworkRenderer(device, DesktopUpnpTransportCommand.PAUSE) },
                        enabled = !anyRendererOperationBusy && (useOpenHomePlaylistControls ||
                            (device.supportsAvTransport && desktopUpnpActionEnabled(rendererStatus, "Pause"))),
                    ) { Text(tr("settings.network_renderer.transport.pause")) }
                    TextButton(
                        onClick = { controller.controlNetworkRenderer(device, DesktopUpnpTransportCommand.STOP) },
                        enabled = !anyRendererOperationBusy && (useOpenHomePlaylistControls ||
                            (device.supportsAvTransport && desktopUpnpActionEnabled(rendererStatus, "Stop"))),
                    ) { Text(tr("settings.network_renderer.transport.stop")) }
                }
                if (useOpenHomePlaylistControls) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { controller.skipNetworkRendererPlaylist(device, previous = true) },
                            enabled = !anyRendererOperationBusy,
                        ) { Text(tr("settings.network_renderer.playlist.previous")) }
                        TextButton(
                            onClick = { controller.skipNetworkRendererPlaylist(device, previous = false) },
                            enabled = !anyRendererOperationBusy,
                        ) { Text(tr("settings.network_renderer.playlist.next")) }
                    }
                }
                if (DesktopUpnpRendererServiceKind.RENDERING_CONTROL in device.services) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(tr("settings.network_renderer.volume.title"), style = MaterialTheme.typography.bodySmall)
                        Text(
                            tr("settings.network_renderer.volume.hint"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        val volumeActions = rendererStatus?.renderingControlActions
                        val currentRendererVolume = rendererStatus?.rendererVolume
                        val currentRendererVolumeMaximum = rendererStatus?.rendererVolumeMaximum
                        when {
                            volumeActions == null -> Text(
                                tr("settings.network_renderer.volume.unknown"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                            "GetVolume" !in volumeActions -> Text(
                                tr("settings.network_renderer.volume.unsupported"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                            currentRendererVolume == null -> Text(
                                tr("settings.network_renderer.volume.read_failed"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                            else -> {
                                val rendererVolume = currentRendererVolume
                                val rendererVolumeMaximum = currentRendererVolumeMaximum
                                if (rendererVolumeMaximum != null) {
                                    Text(
                                        tr("settings.network_renderer.volume.current", rendererVolume, rendererVolumeMaximum),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                } else {
                                    Text(
                                        tr("settings.network_renderer.volume.current_unknown_scale", rendererVolume),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                                when {
                                    "SetVolume" !in volumeActions -> Text(
                                        tr("settings.network_renderer.volume.read_only"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                    rendererVolumeMaximum != null && rendererVolumeMaximum > 0 -> {
                                        var sliderVolume by remember(
                                            device.identity,
                                            rendererVolume,
                                            rendererVolumeMaximum,
                                            controller.networkRendererOperationErrors[device.identity],
                                        ) { mutableFloatStateOf(rendererVolume.toFloat()) }
                                        Slider(
                                            value = sliderVolume.coerceIn(0f, rendererVolumeMaximum.toFloat()),
                                            onValueChange = { sliderVolume = it },
                                            onValueChangeFinished = {
                                                controller.setNetworkRendererVolume(
                                                    device,
                                                    sliderVolume.roundToInt().coerceIn(0, rendererVolumeMaximum),
                                                )
                                            },
                                            enabled = !anyRendererOperationBusy &&
                                                desktopUpnpRendererVolumeSliderEnabled(rendererStatus),
                                        )
                                    }
                                    rendererVolumeMaximum == null -> {
                                        Text(
                                            tr("settings.network_renderer.volume.scale_unknown"),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = colors.onSurfaceVariant,
                                        )
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(
                                                onClick = { controller.adjustNetworkRendererVolume(device, -1) },
                                                enabled = !anyRendererOperationBusy &&
                                                    desktopUpnpRendererVolumeStepEnabled(rendererStatus, -1),
                                            ) { Text(tr("settings.network_renderer.volume.down")) }
                                            TextButton(
                                                onClick = { controller.adjustNetworkRendererVolume(device, 1) },
                                                enabled = !anyRendererOperationBusy &&
                                                    desktopUpnpRendererVolumeStepEnabled(rendererStatus, 1),
                                            ) { Text(tr("settings.network_renderer.volume.up")) }
                                        }
                                    }
                                    else -> Text(
                                        tr("settings.network_renderer.volume.fixed"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                val canSendLocalQueue = device.hasOpenHomePlaylist ||
                    (device.supportsAvTransport &&
                        desktopUpnpActionAdvertised(rendererStatus, "SetAVTransportURI") &&
                        desktopUpnpActionAdvertised(rendererStatus, "Play"))
                if (device.supportsAvTransport || device.hasOpenHomePlaylist) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(
                            onClick = { controller.sendCurrentLocalTrackToNetworkRenderer(device) },
                            enabled = !anyRendererOperationBusy && controller.canSendCurrentLocalTrackToNetworkRenderer &&
                                canSendLocalQueue,
                        ) { Text(tr("settings.network_renderer.local.send_current")) }
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        }
                    }
                    if (controller.currentLocalTrackIsCue) {
                        Text(
                            tr("settings.network_renderer.local.cue_unsupported"),
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    if (controller.networkRendererPlayingIdentity == device.identity) {
                        controller.networkRendererPlayingTrackTitle?.let { title ->
                            Text(
                                tr("settings.network_renderer.local.current_track", title),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        controller.networkRendererQueuePosition?.takeIf {
                            controller.networkRendererQueueIdentity == device.identity
                        }?.let { position ->
                            Text(
                                tr("settings.network_renderer.local.queue_position", position),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        if (controller.networkRendererQueueIdentity == device.identity) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(
                                    onClick = controller::playPrevious,
                                    enabled = !anyRendererOperationBusy,
                                ) { Text(tr("player.previous")) }
                                TextButton(
                                    onClick = controller::playNext,
                                    enabled = !anyRendererOperationBusy,
                                ) { Text(tr("player.next")) }
                            }
                            if (controller.networkRendererQueueExcludedItems > 0) {
                                Text(
                                    tr(
                                        "settings.network_renderer.local.queue_partial",
                                        controller.networkRendererQueueExcludedItems,
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                        if (controller.networkRendererQueueIsLocal) {
                            Text(
                                tr("settings.network_renderer.local.streaming_hint"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
                rendererStatus?.let { status ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (status.connectionState == DesktopUpnpConnectionState.DISCONNECTED) {
                            Text(
                                tr("settings.network_renderer.status.disconnected"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        } else {
                            status.transportState?.let { state ->
                                Text(
                                    tr("settings.network_renderer.status.state", state),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                            status.transportStatus?.let { state ->
                                Text(
                                    tr("settings.network_renderer.status.transport", state),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                        val supportedActions = status.avTransportActions
                        Text(
                            if (supportedActions == null) {
                                tr("settings.network_renderer.status.actions_unknown")
                            } else {
                                tr(
                                    "settings.network_renderer.status.actions",
                                    supportedActions.sorted().joinToString(", ").ifBlank { "—" },
                                )
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                        status.sinkProtocolInfo?.let { protocolInfo ->
                            val formats = summarizeDesktopUpnpSinkFormats(protocolInfo)
                            if (formats.isNotEmpty()) {
                                Text(
                                    tr("settings.network_renderer.status.formats", formats),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                        if (status.positionMillis != null || status.durationMillis != null) {
                            Text(
                                tr(
                                    "settings.network_renderer.status.position",
                                    status.positionMillis?.let(::formatDuration) ?: "—",
                                    status.durationMillis?.let(::formatDuration) ?: "—",
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        val seekDuration = status.durationMillis?.takeIf { it > 0L }
                        if (seekDuration != null && desktopUpnpRelativeTimeSeekEnabled(status)) {
                            var seekProgress by remember(device.identity, status.positionMillis, seekDuration) {
                                mutableFloatStateOf(
                                    ((status.positionMillis ?: 0L).toDouble() / seekDuration)
                                        .toFloat()
                                        .coerceIn(0f, 1f),
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    formatDuration((seekProgress * seekDuration).roundToLong()),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                )
                                Slider(
                                    value = seekProgress,
                                    onValueChange = { seekProgress = it },
                                    onValueChangeFinished = {
                                        controller.seekNetworkRenderer(
                                            device,
                                            (seekProgress * seekDuration).roundToLong().coerceIn(0L, seekDuration),
                                        )
                                    },
                                    enabled = !anyRendererOperationBusy,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    formatDuration(seekDuration),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                        status.supplementalErrors.forEach { error ->
                            Text(
                                tr("settings.network_renderer.status.detail_failed", error),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.error,
                            )
                        }
                    }
                }
                controller.networkRendererOperationErrors[device.identity]?.let { error ->
                    Text(
                        tr("settings.network_renderer.action_failed", error),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.error,
                    )
                }
            }
        }
    }
}

private fun summarizeDesktopUpnpSinkFormats(sinkProtocolInfo: String): String =
    sinkProtocolInfo.split(',')
        .mapNotNull { entry -> entry.trim().split(':', limit = 4).getOrNull(2)?.takeIf(String::isNotBlank) }
        .distinct()
        .take(8)
        .joinToString(", ")

private fun formatTelemetryDecibels(value: Double): String =
    if (value == Double.NEGATIVE_INFINITY) "−∞" else String.format(Locale.ROOT, "%.1f", value)

private fun nearestHiFiBufferOption(value: Int): Int =
    HiFiBufferOptions.minByOrNull { kotlin.math.abs(it - value) } ?: 120

private fun wasapiPcmRateLabel(sampleRateHz: Int): String =
    if (sampleRateHz % 1_000 == 0) {
        (sampleRateHz / 1_000).toString()
    } else {
        "${sampleRateHz / 1_000}.${(sampleRateHz % 1_000) / 100}"
    }

@Composable
private fun DesktopEqualizerSection(controller: DesktopPlayerController) {
    val equalizer = controller.equalizer
    val colors = MaterialTheme.colorScheme
    var editorOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(role = androidx.compose.ui.semantics.Role.Switch) {
                controller.updateEqualizer(equalizer.copy(enabled = !equalizer.enabled))
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tr("settings.equalizer.enable"), style = MaterialTheme.typography.bodyMedium)
            Text(
                when {
                    equalizer.enabled && controller.hifiStreamInfo?.bitPerfectActive == true ->
                        tr("settings.equalizer.bypassed_bit_perfect")
                    equalizer.enabled -> tr("settings.equalizer.on")
                    else -> tr("settings.equalizer.off")
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(12.dp))
        LazerSwitch(engine = controller.themeEngine, checked = equalizer.enabled, onCheckedChange = null)
    }
    HorizontalDivider()
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            tr("settings.equalizer.preset"),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        DesktopSettingsDropdown(
            options = listOf<String?>(null) + LazerEqualizerPresets.map { it.id },
            selected = equalizer.matchingPresetId(),
            label = ::equalizerPresetLabel,
            onSelected = { id ->
                LazerEqualizerPresets.firstOrNull { it.id == id }?.let { preset ->
                    controller.updateEqualizer(equalizer.activatePreset(preset))
                }
            },
        )
        TextButton(onClick = { editorOpen = true }) { Text(tr("settings.equalizer.edit")) }
    }
    if (editorOpen) {
        DesktopEqualizerDialog(controller = controller, onDismiss = { editorOpen = false })
    }
}

private fun equalizerPresetLabel(id: String?): String = when (id) {
    "flat" -> tr("settings.equalizer.preset.flat")
    "pop" -> tr("settings.equalizer.preset.pop")
    "rock" -> tr("settings.equalizer.preset.rock")
    "classical" -> tr("settings.equalizer.preset.classical")
    "jazz" -> tr("settings.equalizer.preset.jazz")
    "bass" -> tr("settings.equalizer.preset.bass")
    "vocal" -> tr("settings.equalizer.preset.vocal")
    "treble" -> tr("settings.equalizer.preset.treble")
    else -> tr("settings.equalizer.custom")
}

private fun eqFrequencyLabel(frequencyHz: Double): String =
    if (frequencyHz >= 1000.0) {
        val kHz = frequencyHz / 1000.0
        if (kHz % 1.0 == 0.0) "${kHz.toInt()} kHz" else "$kHz kHz"
    } else {
        "${frequencyHz.toInt()} Hz"
    }

private fun formatEqGain(gainDb: Double): String {
    val rounded = (gainDb * 10).roundToInt() / 10.0
    val sign = if (rounded > 0) "+" else ""
    return "$sign$rounded dB"
}

@Composable
private fun DesktopEqualizerDialog(controller: DesktopPlayerController, onDismiss: () -> Unit) {
    val equalizer = controller.equalizer
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("settings.equalizer.title")) },
        text = {
            Column(
                modifier = Modifier.width(440.dp).heightIn(max = 540.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    tr("settings.equalizer.hint"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                EqSliderRow(
                    label = tr("settings.equalizer.preamp"),
                    gainDb = equalizer.preampDb,
                    range = MIN_LAZER_EQ_PREAMP_DB.toFloat()..MAX_LAZER_EQ_PREAMP_DB.toFloat(),
                    engine = controller.themeEngine,
                    onPreview = { controller.previewEqualizer(equalizer.copy(preampDb = clampLazerEqPreampDb(it))) },
                    onCommit = { controller.updateEqualizer(equalizer.copy(preampDb = clampLazerEqPreampDb(it))) },
                )
                HorizontalDivider()
                equalizer.bands.forEachIndexed { index, band ->
                    EqSliderRow(
                        label = eqFrequencyLabel(band.frequencyHz),
                        gainDb = band.gainDb,
                        range = MIN_LAZER_EQ_GAIN_DB.toFloat()..MAX_LAZER_EQ_GAIN_DB.toFloat(),
                        engine = controller.themeEngine,
                        onPreview = { gain ->
                            val bands = equalizer.bands.toMutableList()
                            bands[index] = band.copy(gainDb = clampLazerEqGainDb(gain))
                            controller.previewEqualizer(equalizer.copy(bands = bands))
                        },
                        onCommit = { gain ->
                            val bands = equalizer.bands.toMutableList()
                            bands[index] = band.copy(gainDb = clampLazerEqGainDb(gain))
                            controller.updateEqualizer(equalizer.copy(bands = bands))
                        },
                    )
                }
                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable(role = androidx.compose.ui.semantics.Role.Switch) {
                            controller.updateEqualizer(equalizer.copy(limiterEnabled = !equalizer.limiterEnabled))
                        }
                        .padding(vertical = 8.dp),
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
                        checked = equalizer.limiterEnabled,
                        onCheckedChange = null,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(tr("settings.equalizer.close")) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    controller.updateEqualizer(LazerEqualizerState(enabled = equalizer.enabled))
                },
            ) { Text(tr("settings.equalizer.reset")) }
        },
    )
}

@Composable
private fun EqSliderRow(
    label: String,
    gainDb: Double,
    range: ClosedFloatingPointRange<Float>,
    engine: LazerThemeEngine,
    onPreview: (Double) -> Unit,
    onCommit: (Double) -> Unit,
) {
    var local by remember(gainDb) { mutableFloatStateOf(gainDb.toFloat()) }
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(72.dp),
        )
        LazerSlider(
            engine = engine,
            value = local,
            onValueChange = {
                local = it
                onPreview(it.toDouble())
            },
            onValueChangeFinished = { onCommit(local.toDouble()) },
            valueRange = range,
            modifier = Modifier.weight(1f),
        )
        Text(
            formatEqGain(local.toDouble()),
            style = MaterialTheme.typography.labelMedium,
            color = colors.primary,
            textAlign = TextAlign.End,
            modifier = Modifier.width(64.dp),
        )
    }
}

@Composable
private fun <T> DesktopSettingsDropdown(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(label(selected), color = colors.primary)
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

private val DesktopStyleOptions = LazerStyle.entries

private fun desktopStyleLabel(style: LazerStyle): String = style.label

private fun paletteLabel(palette: LazerPalette): String = when (palette) {
    LazerPalette.Default -> tr("settings.palette.default")
    LazerPalette.System -> tr("settings.palette.system")
    LazerPalette.NowPlaying -> tr("settings.palette.now_playing")
    is LazerPalette.Custom -> tr("settings.palette.custom")
}

private fun desktopBackgroundModeLabel(mode: DesktopBackgroundMode): String = when (mode) {
    DesktopBackgroundMode.SOLID -> tr("settings.background.mode.solid")
    DesktopBackgroundMode.IMAGE -> tr("settings.background.mode.image")
    DesktopBackgroundMode.NOW_PLAYING_DYNAMIC -> tr("settings.background.mode.now_playing_dynamic")
    DesktopBackgroundMode.NOW_PLAYING_STATIC -> tr("settings.background.mode.now_playing_static")
}

private fun desktopBackgroundModeHint(mode: DesktopBackgroundMode): String = when (mode) {
    DesktopBackgroundMode.SOLID -> tr("settings.background.hint.solid")
    DesktopBackgroundMode.IMAGE -> tr("settings.background.hint.image")
    DesktopBackgroundMode.NOW_PLAYING_DYNAMIC -> tr("settings.background.hint.now_playing_dynamic")
    DesktopBackgroundMode.NOW_PLAYING_STATIC -> tr("settings.background.hint.now_playing_static")
}

private fun pickBackgroundImage(controller: DesktopPlayerController) {
    val chooser = javax.swing.JFileChooser().apply {
        dialogTitle = tr("settings.background.pick")
        fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
            "Images",
            "png", "jpg", "jpeg", "webp", "bmp", "gif",
        )
    }
    if (chooser.showOpenDialog(null) == javax.swing.JFileChooser.APPROVE_OPTION) {
        chooser.selectedFile?.let(controller::setBackgroundImage)
    }
}

private fun chooseDesktopAudioFiles(): List<java.io.File>? {
    val chooser = javax.swing.JFileChooser().apply {
        dialogTitle = tr("player.open_audio_files")
        fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
        isMultiSelectionEnabled = true
        fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
            "FLAC / WAV / DSF / DFF audio",
            "flac",
            "wav",
            "dsf",
            "dff",
        )
    }
    if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFiles.takeIf { it.isNotEmpty() }?.toList()
        ?: chooser.selectedFile?.let(::listOf)
}

private fun chooseDesktopAudioDirectory(): java.io.File? {
    val chooser = javax.swing.JFileChooser().apply {
        dialogTitle = tr("library.local.add_folder")
        fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
    }
    if (chooser.showOpenDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return null
    return chooser.selectedFile
}

private fun chooseHiFiDiagnosticsDestination(): java.io.File? {
    val chooser = javax.swing.JFileChooser().apply {
        dialogTitle = tr("settings.hifi.diagnostics.choose_file")
        selectedFile = java.io.File("lazer-hifi-diagnostics.json")
        fileFilter = javax.swing.filechooser.FileNameExtensionFilter(
            tr("settings.hifi.diagnostics.file_type"),
            "json",
        )
    }
    if (chooser.showSaveDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return null

    val selected = chooser.selectedFile
    val jsonFile = if (selected.extension.equals("json", ignoreCase = true)) {
        selected
    } else {
        java.io.File(selected.parentFile, "${selected.nameWithoutExtension.ifBlank { selected.name }}.json")
    }
    if (!jsonFile.exists()) return jsonFile

    val baseName = jsonFile.nameWithoutExtension
    return generateSequence(1) { it + 1 }
        .map { suffix -> java.io.File(jsonFile.parentFile, "$baseName ($suffix).json") }
        .first { !it.exists() }
}

@Composable
private fun SectionHeading(title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.width(12.dp))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlaylistStrip(playlists: List<PlaylistItem>, onPlaylistClick: (PlaylistItem) -> Unit) {
    if (playlists.isEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(4) { index -> PlaylistPlaceholder(index) }
        }
        return
    }
    val listState = rememberLazyListState()
    val inertia = LocalScrollInertia.current
    val scope = rememberCoroutineScope()
    var stripHovered by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .onPointerEvent(PointerEventType.Enter) { stripHovered = true }
            .onPointerEvent(PointerEventType.Exit) { stripHovered = false },
    ) {
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth().scrollInertia(listState, inertia, Orientation.Horizontal),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(end = 12.dp),
        ) {
            items(playlists, key = { it.id }) { playlist ->
                PlaylistTile(playlist, onClick = { onPlaylistClick(playlist) })
            }
        }
        AnimatedVisibility(
            visible = stripHovered && (listState.canScrollBackward || listState.canScrollForward),
            modifier = Modifier.matchParentSize(),
            enter = fadeIn(tween(durationMillis = 220, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(durationMillis = 140, easing = FastOutSlowInEasing)),
        ) {
            Box(Modifier.fillMaxSize()) {
                if (listState.canScrollBackward) {
                    PlaylistStripScrollButton(
                        icon = Icons.AutoMirrored.Outlined.ArrowBack,
                        description = tr("strip.prev"),
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 4.dp),
                        onClick = {
                            scope.launch {
                                listState.animateScrollToItem(
                                    (listState.firstVisibleItemIndex - 3).coerceAtLeast(0),
                                )
                            }
                        },
                    )
                }
                if (listState.canScrollForward) {
                    PlaylistStripScrollButton(
                        icon = Icons.AutoMirrored.Outlined.ArrowForward,
                        description = tr("strip.next"),
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 4.dp),
                        onClick = {
                            scope.launch {
                                listState.animateScrollToItem(
                                    (listState.firstVisibleItemIndex + 3).coerceAtMost(playlists.lastIndex),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistStripScrollButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(colors.surface.copy(alpha = 0.96f))
            .border(1.dp, colors.outlineVariant.copy(alpha = 0.78f), CircleShape),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = colors.onSurface,
        ),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(19.dp))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlaylistTile(playlist: PlaylistItem, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    var hovered by remember { mutableStateOf(false) }
    val maskAlpha by animateFloatAsState(
        targetValue = if (hovered) 0.18f else 0f,
        animationSpec = tween(durationMillis = if (hovered) 180 else 130, easing = FastOutSlowInEasing),
        label = "playlist-tile-mask",
    )
    val coverShape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .width(148.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .clickable(onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(148.dp)
                .clip(coverShape),
        ) {
            Artwork(
                playlist.id,
                playlist.title,
                playlist.coverUrl,
                Modifier.matchParentSize(),
                cornerRadius = 16.dp,
            )
            Box(
                Modifier
                    .matchParentSize()
                    .background(colors.scrim.copy(alpha = maskAlpha)),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(playlist.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(2.dp))
        Text(
            playlist.subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlaylistPlaceholder(index: Int) {
    Column(Modifier.width(148.dp)) {
        Box(
            Modifier.fillMaxWidth().height(148.dp).clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.66f)),
        )
        Spacer(Modifier.height(10.dp))
        Box(Modifier.width((100 + index * 7).dp).height(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant))
    }
}

@Composable
private fun Artwork(
    id: Long,
    title: String,
    coverUrl: String?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 18.dp,
    saveOnLongPress: Boolean = false,
    requestSizePixels: Int = 256,
) {
    val gradient = calmArtwork[(id.hashCode().absoluteValue) % calmArtwork.size]
    val shape = RoundedCornerShape(cornerRadius)
    val sizedUrl = remember(coverUrl, requestSizePixels) {
        coverUrl?.toArtworkUrl(requestSizePixels)
    }
    val requestSave = LocalRequestCoverSave.current
    // The long press owns the whole gesture: the release has to be consumed, or the clickable
    // around the artwork (the track row, the player bar) reads it as a tap.
    val saveModifier = if (saveOnLongPress && !coverUrl.isNullOrBlank()) {
        Modifier.pointerInput(coverUrl, title) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                if (awaitLongPressOrCancellation(down.id) == null) return@awaitEachGesture
                requestSave(CoverSaveRequest(coverUrl, title))
                var pressed = true
                while (pressed) {
                    val event = awaitPointerEvent()
                    pressed = event.changes.any { it.pressed }
                    event.changes.forEach { it.consume() }
                }
            }
        }
    } else {
        Modifier
    }
    Box(
        modifier.then(saveModifier).clip(shape).background(Brush.linearGradient(gradient)),
        contentAlignment = Alignment.Center,
    ) {
        ArtworkFallback(title, gradient)
        if (sizedUrl != null) {
            AsyncImage(
                model = sizedUrl,
                contentDescription = tr("artwork.cover", title),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun ArtworkFallback(title: String, gradient: List<Color>) {
    Box(
        Modifier.fillMaxSize().background(Brush.linearGradient(gradient)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.fillMaxSize(0.52f).clip(CircleShape).border(1.dp, Color.White.copy(alpha = 0.62f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(title.firstOrNull()?.toString() ?: "L", color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun UserAvatar(name: String, avatarUrl: String?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val sizedUrl = remember(avatarUrl) { avatarUrl?.toArtworkUrl() }
    Box(
        modifier.clip(CircleShape).background(colors.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        AvatarFallback(name)
        if (sizedUrl != null) {
            AsyncImage(
                model = sizedUrl,
                contentDescription = tr("artwork.avatar", name),
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

@Composable
private fun AvatarFallback(name: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            name.firstOrNull()?.toString() ?: tr("topbar.you"),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

private val ArtworkRequestSizeParameter = Regex("([?&]param=)\\d+y\\d+", RegexOption.IGNORE_CASE)

internal fun String.toArtworkUrl(requestSizePixels: Int = 256): String {
    val trimmed = trim()
    if (trimmed.isEmpty()) return trimmed
    if (trimmed.startsWith("file:", ignoreCase = true)) return trimmed
    val withScheme = when {
        trimmed.startsWith("//") -> "https:$trimmed"
        trimmed.startsWith("http://") -> "https://" + trimmed.removePrefix("http://")
        else -> trimmed
    }
    val size = requestSizePixels.coerceIn(32, 2048)
    val parameter = "${size}y$size"
    if (ArtworkRequestSizeParameter.containsMatchIn(withScheme)) {
        return withScheme.replace(ArtworkRequestSizeParameter) { match ->
            match.groupValues[1] + parameter
        }
    }
    return withScheme + if ('?' in withScheme) "&param=$parameter" else "?param=$parameter"
}

@Composable
private fun TrackSection(title: String, tracks: List<TrackItem>, onPlay: (TrackItem) -> Unit) {
    Column {
        SectionHeading(title, if (tracks.isEmpty()) tr("tracks.organizing") else tr("tracks.count", tracks.size))
        Spacer(Modifier.height(10.dp))
        if (tracks.isEmpty()) {
            QuietEmptyState(tr("tracks.loading"), tr("tracks.loading.hint"))
        } else {
            tracks.forEachIndexed { index, track ->
                TrackRow(track, false, { onPlay(track) }, index + 1)
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: TrackItem,
    selected: Boolean,
    onClick: () -> Unit,
    index: Int? = null,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) colors.primaryContainer.copy(alpha = 0.68f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = index?.toString() ?: "♪",
            modifier = Modifier.width(28.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) colors.primary else colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.width(8.dp))
        Artwork(
            track.id,
            track.title,
            track.coverUrl,
            Modifier.size(44.dp),
            cornerRadius = 10.dp,
            saveOnLongPress = true,
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1.2f)) {
            Text(track.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TranslatedTrackTitle(track.translatedTitle, style = MaterialTheme.typography.labelSmall)
            ArtistNames(
                artists = track.artists,
                fallback = track.artist,
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
        }
        Text(
            track.album,
            modifier = Modifier.weight(0.8f),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(track.durationLabel, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, modifier = Modifier.width(48.dp))
        IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
            Icon(Icons.Filled.PlayArrow, tr("tracks.play", track.title), Modifier.size(17.dp), tint = colors.primary)
        }
    }
}

@Composable
private fun SignInInvitation(onLogin: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(22.dp), color = colors.primaryContainer.copy(alpha = 0.74f)) {
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(colors.surface), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Person, null, tint = colors.primary)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("signin.continue"), style = MaterialTheme.typography.titleLarge)
                Text(tr("signin.desktop.hint"), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
            Button(onClick = onLogin, shape = RoundedCornerShape(11.dp)) { Text(tr("signin.qr")) }
        }
    }
}

@Composable
private fun QuietEmptyState(title: String, body: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.72f)),
    ) {
        Column(Modifier.padding(22.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(3.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DesktopNowPlayingPage(
    controller: DesktopPlayerController,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val track = controller.nowPlaying ?: return
    val colors = MaterialTheme.colorScheme
    val glass = LocalOsGlassActive.current
    val displayProgress by animateFloatAsState(
        targetValue = controller.progress.coerceIn(0f, 1f),
        animationSpec = if (controller.isSeeking || !controller.isPlaying) snap() else spring(stiffness = Spring.StiffnessHigh),
        label = "now-playing-progress",
    )
    val seekProgress = if (controller.isSeeking) controller.progress else displayProgress
    Box(modifier.background(colors.background.copy(alpha = if (glass) 0.22f else 1f))) {
        AlbumFlowBackground(
            colors = controller.lyricFlowColors,
            modifier = Modifier.fillMaxSize(),
            cornerRadius = 0.dp,
            veil = colors.background.copy(alpha = if (glass) 0.22f else 0.38f),
        )
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 18.dp)) {
            // Preserve the mobile landscape information structure, but scale its hero artwork for
            // a desktop canvas. Copying the phone's 148 dp ceiling left most of this pane empty.
            val coverSide = minOf(maxHeight * 0.38f, maxWidth * 0.24f).coerceIn(140.dp, 420.dp)
            Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                Column(
                    Modifier.weight(0.44f).fillMaxHeight().padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        Modifier.fillMaxWidth().height(52.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, tr("player.collapse")) }
                    }
                    Box(
                        Modifier.weight(1f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Artwork(
                                track.id,
                                track.title,
                                track.coverUrl,
                                Modifier
                                    .size(coverSide)
                                    .shadow(
                                        elevation = 26.dp,
                                        shape = RoundedCornerShape(24.dp),
                                        ambientColor = Color.Black.copy(alpha = 0.34f),
                                        spotColor = Color.Black.copy(alpha = 0.46f),
                                    ),
                                cornerRadius = 24.dp,
                                saveOnLongPress = true,
                                requestSizePixels = 1024,
                            )
                            Spacer(Modifier.height(14.dp))
                            Text(
                                track.title,
                                style = MaterialTheme.typography.headlineSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            TranslatedTrackTitle(
                                title = track.translatedTitle,
                                modifier = Modifier.widthIn(max = 600.dp),
                                textAlign = TextAlign.Center,
                            )
                            Spacer(Modifier.height(2.dp))
                            ArtistNames(
                                track.artists,
                                track.artist,
                                MaterialTheme.typography.bodyLarge,
                                colors.onSurfaceVariant,
                            )
                            if (track.isLocalFile) {
                                Text(
                                    tr("player.local_file"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = colors.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Box(
                        Modifier.fillMaxWidth().padding(bottom = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                    Column(
                        Modifier.widthIn(max = 600.dp).fillMaxWidth()
                            .background(
                                colors.surface.copy(alpha = if (glass) 0.42f else 0.86f),
                                RoundedCornerShape(26.dp),
                            )
                            .border(
                                1.dp,
                                colors.outlineVariant.copy(alpha = if (glass) 0.48f else 0.72f),
                                RoundedCornerShape(26.dp),
                            )
                            .padding(horizontal = 18.dp, vertical = 10.dp),
                    ) {
                        ThinSeekBar(
                            progress = seekProgress,
                            bufferedProgress = controller.bufferedProgress,
                            onSeek = controller::seekTo,
                            onSeekFinished = controller::commitSeek,
                        )
                        Row(Modifier.fillMaxWidth()) {
                            Text(formatDuration((track.durationMillis * seekProgress).roundToInt().toLong()), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                            Spacer(Modifier.weight(1f))
                            Text(track.durationLabel, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconButton(
                                onClick = controller::toggleLiked,
                                enabled = !track.isLocalFile,
                                modifier = Modifier.size(44.dp),
                            ) {
                                Icon(
                                    if (controller.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                                    tr(if (controller.isLiked) "player.like.remove" else "player.like.add"),
                                    tint = if (controller.isLiked) colors.primary else colors.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = controller::playPrevious, modifier = Modifier.size(44.dp)) {
                                Icon(Icons.Filled.SkipPrevious, tr("player.previous"), Modifier.size(27.dp))
                            }
                            IconButton(onClick = controller::togglePlayPause, modifier = Modifier.size(52.dp), colors = IconButtonDefaults.iconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary)) {
                                Icon(if (controller.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, tr(if (controller.isPlaying) "player.pause" else "player.play"), Modifier.size(29.dp))
                            }
                            IconButton(onClick = controller::playNext, modifier = Modifier.size(44.dp)) {
                                Icon(Icons.Filled.SkipNext, tr("player.next"), Modifier.size(27.dp))
                            }
                            val playMode = controller.playMode
                            IconButton(onClick = controller::cyclePlayMode, modifier = Modifier.size(44.dp)) {
                                Icon(
                                    imageVector = desktopPlayModeIcon(playMode),
                                    contentDescription = tr(playMode.labelKey),
                                    tint = if (playMode == DesktopPlayMode.Sequential) colors.onSurfaceVariant else colors.primary,
                                )
                            }
                        }
                    }
                    }
                }
                Spacer(Modifier.width(18.dp))
                DesktopLyricsViewport(
                    controller,
                    Modifier.weight(0.56f).fillMaxHeight().padding(horizontal = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun TranslatedTrackTitle(
    title: String?,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodySmall,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    textAlign: TextAlign? = null,
) {
    if (!title.isNullOrBlank()) {
        Text(
            text = title,
            modifier = modifier,
            style = style,
            color = color,
            textAlign = textAlign,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}


@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlayerBar(
    controller: DesktopPlayerController,
    queueListHeight: Dp = DesktopPanelListHeight,
    onOpenNowPlaying: () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    val displayProgress by animateFloatAsState(
        targetValue = controller.progress.coerceIn(0f, 1f),
        animationSpec = when {
            controller.isSeeking || !controller.isPlaying -> snap()
            else -> spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessHigh,
                visibilityThreshold = 0.0001f,
            )
        },
        label = "playback-progress",
    )
    val glass = LocalOsGlassActive.current
    val uiAlpha = LocalLazerUiAlpha.current
    Surface(
        modifier = Modifier.fillMaxWidth().height(84.dp),
        color = colors.surface.copy(alpha = if (glass) 0f else 0.96f * uiAlpha),
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.width(250.dp), verticalAlignment = Alignment.CenterVertically) {
                var nowPlayingHovered by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            if (nowPlayingHovered) {
                                colors.surfaceVariant.copy(alpha = 0.55f)
                            } else {
                                Color.Transparent
                            },
                        )
                        .clickable(onClick = onOpenNowPlaying)
                        .onPointerEvent(PointerEventType.Enter) { nowPlayingHovered = true }
                        .onPointerEvent(PointerEventType.Exit) { nowPlayingHovered = false }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Artwork(
                        controller.nowPlaying?.id ?: 0,
                        controller.nowPlaying?.title ?: "L",
                        controller.nowPlaying?.coverUrl,
                        Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)),
                        cornerRadius = 12.dp,
                        saveOnLongPress = controller.nowPlaying != null,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            controller.nowPlaying?.title ?: tr("player.choose"),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        TranslatedTrackTitle(
                            title = controller.nowPlaying?.translatedTitle,
                            style = MaterialTheme.typography.labelSmall,
                        )
                        ArtistNames(
                            artists = controller.nowPlaying?.artists.orEmpty(),
                            fallback = controller.nowPlaying?.artist?.takeIf(String::isNotBlank)
                                ?: if (controller.nowPlaying?.isLocalFile == true) tr("player.local_file") else "Lazer",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }

            Column(Modifier.weight(1f).padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    CompactIconButton(
                        if (controller.isLiked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        controller::toggleLiked,
                        tr("player.like"),
                        controller.isLiked,
                        enabled = controller.nowPlaying?.isLocalFile != true,
                    )
                    CompactIconButton(Icons.Filled.SkipPrevious, controller::playPrevious, tr("player.previous"))
                    IconButton(
                        onClick = controller::togglePlayPause,
                        modifier = Modifier.size(38.dp),
                        colors = IconButtonDefaults.iconButtonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
                    ) {
                        Icon(if (controller.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (controller.isPlaying) tr("player.pause") else tr("player.play"), Modifier.size(21.dp))
                    }
                    CompactIconButton(Icons.Filled.SkipNext, controller::playNext, tr("player.next"))
                    val playMode = controller.playMode
                    CompactIconButton(
                        desktopPlayModeIcon(playMode),
                        controller::cyclePlayMode,
                        tr(playMode.labelKey),
                        playMode != DesktopPlayMode.Sequential,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val total = controller.nowPlaying?.durationLabel ?: "00:00"
                    val elapsed = controller.nowPlaying?.let {
                        formatDuration((it.durationMillis * displayProgress).roundToInt().toLong())
                    } ?: "00:00"
                    Text(elapsed, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    ThinSeekBar(
                        progress = if (controller.isSeeking) controller.progress else displayProgress,
                        bufferedProgress = controller.bufferedProgress,
                        onSeek = controller::seekTo,
                        onSeekFinished = controller::commitSeek,
                        modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
                    )
                    Text(total, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }

            Row(
                Modifier.widthIn(min = 140.dp).width(168.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                VolumePanelControl(
                    volume = controller.volume,
                    onVolumeChange = controller::updateVolume,
                    enabled = !controller.hifiDigitalVolumeBypassed,
                )
                Spacer(Modifier.width(2.dp))
                QualityControl(
                    quality = controller.audioQuality,
                    onQualityChange = controller::updateAudioQuality,
                    bitrate = controller.streamBitrate,
                )
                Spacer(Modifier.width(2.dp))
                CompactIconButton(
                    Icons.Outlined.Headphones,
                    controller::openListenTogether,
                    tr("listen_together.open"),
                    selected = controller.listenTogether != null,
                )
                Spacer(Modifier.width(2.dp))
                PlayerPanelControl(
                    icon = Icons.AutoMirrored.Outlined.QueueMusic,
                    label = tr("player.queue"),
                ) { PlayQueuePanel(controller, queueListHeight) }
                Spacer(Modifier.width(2.dp))
                CompactIconButton(
                    Icons.Outlined.ModeComment,
                    controller::openSongComments,
                    tr("comment.open"),
                    selected = controller.isCommentPanelVisible,
                )
            }
        }
    }
}

@Composable
private fun ThinSeekBar(
    progress: Float,
    bufferedProgress: Float = progress,
    onSeek: (Float) -> Unit,
    onSeekFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val fraction = progress.coerceIn(0f, 1f)
    val bufferedFraction = maxOf(fraction, bufferedProgress.coerceIn(0f, 1f))
    val currentOnSeek by rememberUpdatedState(onSeek)
    val currentOnSeekFinished by rememberUpdatedState(onSeekFinished)
    BoxWithConstraints(
        modifier
            .height(14.dp)
            .fillMaxWidth()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val width = size.width.coerceAtLeast(1)
                    currentOnSeek((down.position.x / width).coerceIn(0f, 1f))
                    down.consume()

                    var finished = false
                    while (!finished) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) {
                            currentOnSeekFinished()
                            finished = true
                        } else {
                            currentOnSeek((change.position.x / width).coerceIn(0f, 1f))
                            change.consume()
                            if (!change.pressed) {
                                currentOnSeekFinished()
                                finished = true
                            }
                        }
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val thumbTravel = (maxWidth - 10.dp).coerceAtLeast(0.dp)
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(CircleShape)
                .background(colors.surfaceVariant.copy(alpha = 0.95f)),
        )
        Box(
            Modifier
                .fillMaxWidth(bufferedFraction)
                .height(3.dp)
                .clip(CircleShape)
                .background(colors.onSurfaceVariant.copy(alpha = 0.34f)),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(3.dp)
                .clip(CircleShape)
                .background(colors.primary),
        )
        Box(
            Modifier
                .offset(x = thumbTravel * fraction)
                .size(10.dp)
                .clip(CircleShape)
                .background(colors.primary),
        )
    }
}

@Composable
private fun CompactIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    description: String,
    selected: Boolean = false,
    enabled: Boolean = true,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(30.dp)) {
        Icon(
            icon,
            description,
            Modifier.size(18.dp),
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun VolumePanelControl(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    enabled: Boolean,
) {
    val colors = MaterialTheme.colorScheme
    var iconHovered by remember { mutableStateOf(false) }
    var panelHovered by remember { mutableStateOf(false) }
    var showPanel by remember { mutableStateOf(false) }
    LaunchedEffect(iconHovered, panelHovered) {
        if (iconHovered || panelHovered) {
            showPanel = true
        } else {
            delay(160)
            if (!iconHovered && !panelHovered) showPanel = false
        }
    }
    Box(
        Modifier
            .size(30.dp)
            .onPointerEvent(PointerEventType.Enter) { iconHovered = true }
            .onPointerEvent(PointerEventType.Exit) { iconHovered = false },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (volume <= 0.001f) Icons.AutoMirrored.Outlined.VolumeOff else Icons.AutoMirrored.Outlined.VolumeUp,
            tr("player.volume"),
            Modifier.size(18.dp),
            tint = if (showPanel) colors.primary else colors.onSurfaceVariant,
        )
        if (showPanel) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, -12),
                properties = PopupProperties(focusable = false),
            ) {
                Surface(
                    modifier = Modifier
                        .width(196.dp)
                        .onPointerEvent(PointerEventType.Enter) { panelHovered = true }
                        .onPointerEvent(PointerEventType.Exit) { panelHovered = false },
                    shape = RoundedCornerShape(16.dp),
                    color = colors.surface.copy(alpha = 0.98f),
                    shadowElevation = 8.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant.copy(alpha = 0.85f)),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        if (enabled) {
                            Text(
                                tr("player.volume.pct", (volume * 100).roundToInt()),
                                style = MaterialTheme.typography.labelMedium,
                                color = colors.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            ThinSeekBar(
                                progress = volume,
                                onSeek = onVolumeChange,
                                onSeekFinished = {},
                                modifier = Modifier.fillMaxWidth(),
                            )
                        } else {
                            Text(
                                tr("player.volume.external"),
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QualityControl(
    quality: AudioQuality,
    onQualityChange: (AudioQuality) -> Unit,
    bitrate: Int?,
) {
    val colors = MaterialTheme.colorScheme
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                Icons.Outlined.HighQuality,
                tr("player.quality"),
                Modifier.size(18.dp),
                tint = if (menuOpen) colors.primary else colors.onSurfaceVariant,
            )
        }
        if (menuOpen) {
            Popup(
                alignment = Alignment.TopEnd,
                offset = IntOffset(0, -8),
                onDismissRequest = { menuOpen = false },
                properties = PopupProperties(focusable = true),
            ) {
                PaperMenuCard(width = 168.dp, cornerRadius = 16.dp) {
                    PaperMenuTitle(tr("player.quality"))
                    listOf(
                        AudioQuality.STANDARD,
                        AudioQuality.HIGHER,
                        AudioQuality.EXHIGH,
                        AudioQuality.LOSSLESS,
                        AudioQuality.HI_RES,
                        AudioQuality.JYMASTER,
                    ).forEach { option ->
                        val selected = option == quality
                        TextButton(
                            onClick = {
                                onQualityChange(option)
                                menuOpen = false
                            },
                            modifier = Modifier.fillMaxWidth().height(32.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp),
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = if (selected) colors.primary else colors.onSurfaceVariant,
                                containerColor = if (selected) colors.primaryContainer else Color.Transparent,
                            ),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(
                                option.label,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Start,
                            )
                            if (selected) {
                                Icon(Icons.Outlined.CheckCircle, null, Modifier.size(14.dp), tint = colors.primary)
                            }
                        }
                    }
                    bitrate?.let {
                        Text(
                            tr("player.quality.current", it / 1000),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}

private val DesktopQueueRowHeight = 46.dp
private val DesktopPanelWidth = 400.dp
private val DesktopPanelListHeight = 336.dp

/** What the queue card wears around its list: header, the mode row, and their paddings. */
private val DesktopPanelChromeHeight = 170.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PlayerPanelControl(
    icon: ImageVector,
    label: String,
    content: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { open = !open },
            modifier = Modifier.size(30.dp),
        ) {
            Icon(
                icon,
                label,
                Modifier.size(18.dp),
                tint = if (open) colors.primary else colors.onSurfaceVariant,
            )
        }
        if (open) {
            // These cards hang off the transport bar at the bottom of the window, so they have to
            // grow upward; anchored to the top they would spill past the window edge. The lift
            // clears the 30.dp button plus a gap.
            val lift = with(density) { (-44.dp).roundToPx() }
            Popup(
                alignment = Alignment.BottomEnd,
                offset = IntOffset(0, lift),
                onDismissRequest = { open = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    modifier = Modifier.width(DesktopPanelWidth),
                    shape = RoundedCornerShape(16.dp),
                    color = colors.surface,
                    shadowElevation = 12.dp,
                    border = BorderStroke(1.dp, colors.outlineVariant),
                ) {
                    content()
                }
            }
        }
    }
}

/** Title, one line of context, and a rule that keeps the header off the scrolling body. */
@Composable
private fun PlayerPanelHeader(
    title: String,
    subtitle: String?,
) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
}

@Composable
private fun PlayQueuePanel(controller: DesktopPlayerController, listHeight: Dp) {
    val inRoom = controller.listenTogether != null
    val tracks = if (inRoom) controller.roomQueue else controller.queue
    val currentIndex = tracks.indexOfFirst { it.id == controller.nowPlaying?.id }
    Column {
        PlayerPanelHeader(
            title = tr(if (inRoom) "player.queue.room_title" else "player.queue"),
            subtitle = when {
                inRoom && tracks.isEmpty() -> tr("player.queue.room_empty")
                inRoom -> tr("player.queue.room_hint", tracks.size)
                currentIndex >= 0 -> tr("player.queue.position", currentIndex + 1, tracks.size)
                else -> tr("player.queue.empty_hint")
            },
        )
        if (!inRoom && tracks.isNotEmpty()) {
            // The three modes are mutually exclusive, so they share the row width evenly instead of
            // crowding three chips plus a label into one line.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DesktopPlayMode.entries.forEach { mode ->
                    FilterChip(
                        selected = controller.playMode == mode,
                        onClick = { controller.setPlayMode(mode) },
                        label = {
                            Text(
                                tr(mode.labelKey),
                                style = MaterialTheme.typography.labelMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        modifier = Modifier.weight(1f).height(30.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = null,
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }
        }
        if (tracks.isEmpty()) {
            Text(
                tr("player.queue.empty_hint"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
            )
        } else if (inRoom) {
            LazyColumn(
                Modifier.fillMaxWidth().height(listHeight).padding(horizontal = 8.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(tracks, key = { _, track -> track.id }) { index, track ->
                    Box(Modifier.fillMaxWidth().height(DesktopQueueRowHeight)) {
                        DesktopQueueRow(
                            index = index,
                            track = track,
                            current = index == currentIndex,
                            isPlaying = controller.isPlaying,
                        )
                    }
                }
            }
        } else {
            ReorderableDesktopQueue(
                tracks = tracks,
                currentIndex = currentIndex,
                isPlaying = controller.isPlaying,
                listHeight = listHeight,
                onPlayAt = controller::playQueueAt,
                onRemoveAt = controller::removeFromQueue,
                onMove = controller::moveInQueue,
            )
        }
    }
}

@Composable
private fun ReorderableDesktopQueue(
    tracks: List<TrackItem>,
    currentIndex: Int,
    isPlaying: Boolean,
    listHeight: Dp,
    onPlayAt: (Int) -> Unit,
    onRemoveAt: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val rowHeightPx = with(LocalDensity.current) { DesktopQueueRowHeight.toPx() }
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

    LazyColumn(
        Modifier.fillMaxWidth().height(listHeight).padding(horizontal = 8.dp),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
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
                    .height(DesktopQueueRowHeight)
                    .zIndex(if (lifted) 1f else 0f)
                    .graphicsLayer {
                        translationY = translation
                        if (lifted) {
                            clip = true
                            shape = RoundedCornerShape(10.dp)
                            shadowElevation = 8f
                        }
                    },
            ) {
                DesktopQueueRow(
                    index = index,
                    track = track,
                    current = index == currentIndex,
                    isPlaying = isPlaying,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlayAt(index) }
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
                                .size(26.dp)
                                .pointerInput(tracks.size) {
                                    detectDragGestures(
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
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.DragHandle,
                                tr("player.queue.drag"),
                                Modifier.size(15.dp),
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
@OptIn(ExperimentalComposeUiApi::class)
private fun DesktopQueueRow(
    index: Int,
    track: TrackItem,
    current: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null,
    handle: @Composable (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    var hovered by remember { mutableStateOf(false) }
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                when {
                    current -> colors.primary.copy(alpha = 0.10f)
                    hovered -> colors.surfaceVariant.copy(alpha = 0.45f)
                    else -> Color.Transparent
                },
            )
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.width(22.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (current) {
                Icon(
                    if (isPlaying) Icons.Filled.PlayArrow else Icons.Outlined.Pause,
                    null,
                    Modifier.size(14.dp),
                    tint = colors.primary,
                )
            } else {
                Text(
                    (index + 1).toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (current) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (track.artist.isNotBlank()) {
                Text(
                    track.artist,
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        handle?.invoke()
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(26.dp)) {
                Icon(
                    Icons.Outlined.Close,
                    tr("player.queue.remove"),
                    Modifier.size(14.dp),
                    tint = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** Comments for the song being played. A page rather than a popover: the list is the subject. */
@Composable
private fun SongCommentPage(controller: DesktopPlayerController, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val state = controller.comments
    val scrollState = rememberScrollState()
    val inertia = LocalScrollInertia.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .scrollInertia(scrollState, inertia)
            .padding(top = 18.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TextButton(onClick = controller::closeCommentPanel, shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("common.back"), Modifier.size(17.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("common.back"))
            }
            PageHeading(
                title = tr("comment.title"),
                subtitle = listOfNotNull(
                    controller.nowPlaying?.title,
                    tr("comment.count", state.total).takeIf { state.total > 0 },
                ).joinToString(" · "),
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
                state.failed && state.comments.isEmpty() -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        tr("comment.load_fail"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                    TextButton(onClick = controller::retrySongComments) { Text(tr("comment.retry")) }
                }
                state.loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Text(
                        tr("comment.loading"),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                    )
                }
                state.comments.isEmpty() && state.hotComments.isEmpty() -> Text(
                    tr("comment.empty"),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                )
                else -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (state.hotComments.isNotEmpty()) {
                        CommentSectionLabel(tr("comment.hot"))
                        state.hotComments.forEach {
                            DesktopCommentRow(
                                comment = it,
                                onLike = { controller.toggleCommentLiked(it) },
                                onReply = { controller.startReply(it) },
                            )
                        }
                        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.6f))
                    }
                    CommentSectionLabel(tr("comment.latest"))
                    state.comments.forEach {
                        DesktopCommentRow(
                            comment = it,
                            onLike = { controller.toggleCommentLiked(it) },
                            onReply = { controller.startReply(it) },
                        )
                    }
                    when {
                        state.loadingMore -> CircularProgressIndicator(
                            Modifier.size(14.dp).align(Alignment.CenterHorizontally),
                            strokeWidth = 2.dp,
                        )
                        state.hasMore -> TextButton(
                            onClick = controller::loadMoreSongComments,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) { Text(tr("comment.more")) }
                        else -> Text(
                            tr("comment.end"),
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CommentSectionLabel(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DesktopCommentRow(
    comment: SongComment,
    onLike: () -> Unit,
    onReply: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val nickname = comment.user?.nickname.orEmpty()
    val avatarUrl = comment.user?.avatarUrl?.toArtworkUrl(96)
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            Modifier
                .size(30.dp)
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
                    contentScale = ContentScale.Crop,
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                nickname,
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(comment.content, style = MaterialTheme.typography.bodySmall)
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val stamp = relativeCommentTime(comment.time)
                if (stamp.isNotBlank()) {
                    Text(stamp, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                comment.ipLocation?.location?.takeIf(String::isNotBlank)?.let { location ->
                    Text(location, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                CommentAction(
                    label = if (comment.likedCount > 0) comment.likedCount.toString() else "",
                    onClick = onLike,
                ) {
                    Icon(
                        if (comment.liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
                        tr("comment.like"),
                        Modifier.size(13.dp),
                        tint = if (comment.liked) colors.error else colors.onSurfaceVariant,
                    )
                }
                CommentAction(label = tr("comment.reply"), onClick = onReply) {
                    Icon(
                        Icons.AutoMirrored.Outlined.Reply,
                        null,
                        Modifier.size(13.dp),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentAction(
    label: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon()
        if (label.isNotBlank()) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
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
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surfaceVariant.copy(alpha = 0.4f))
            .padding(10.dp),
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
            placeholder = { Text(tr("comment.reply_hint"), style = MaterialTheme.typography.bodySmall) },
            isError = error != null,
            minLines = 2,
            singleLine = false,
        )
        error?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = colors.error)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tr("comment.reply_limit", draft.length, SONG_COMMENT_CONTENT_LIMIT),
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onCancel, enabled = !sending) { Text(tr("comment.reply_cancel")) }
            Button(onClick = onSend, enabled = draft.isNotBlank() && !sending) {
                if (sending) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Text(tr("comment.reply_send"))
                }
            }
        }
    }
}

private fun relativeCommentTime(
    epochMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
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
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(epochMillis))
    }
}

/** Temporary top safe area the sheet gains while the selection bar is up. */
private val LyricSelectionSafeArea = 68.dp

/** One control size and one readout width, so the selection bar never resizes itself. */
private val LyricSelectionControlSize = 36.dp
private val LyricSelectionStatusWidth = 132.dp

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DesktopLyricsViewport(
    controller: DesktopPlayerController,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val clickGlowScope = rememberCoroutineScope()
    val clickGlowTokens = remember(controller.nowPlaying?.id) {
        mutableStateMapOf<TimedLyricLine, Any>()
    }
    val lines = controller.lyrics
    val timeline = remember(lines, controller.nowPlaying?.durationMillis) {
        desktopLyricsWithInterludes(lines, controller.nowPlaying?.durationMillis ?: 0L)
    }
    val clipboard = LocalClipboard.current
    var lyricSelection by remember(controller.nowPlaying?.id) {
        mutableStateOf(LyricSelectionState())
    }
    var lyricSelectionCopied by remember(controller.nowPlaying?.id) {
        mutableStateOf(false)
    }
    // Row a bulk change radiates from, so select-all lights up as a wave instead of a flash.
    var lyricSelectionWaveOrigin by remember(controller.nowPlaying?.id) { mutableIntStateOf(-1) }
    val targetInterlude = activeDesktopInterlude(timeline, controller.positionMillis)
    var renderedInterlude by remember(controller.nowPlaying?.id, timeline) {
        mutableStateOf<TimedLyricLine?>(null)
    }
    val interludePresence = remember(controller.nowPlaying?.id, timeline) { Animatable(0f) }
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
        desktopLyricDisplayLines(timeline, renderedInterlude)
    }
    val selectionKeys = remember(displayLines) { lyricLineKeys(displayLines) }
    val selectionModeLight = animatedLyricSelectionPresence(
        selected = lyricSelection.isActive,
        speed = controller.lyricAnimationSpeed,
    )
    // The sheet gains a temporary safe area while the bar is up, so a marked line can rest clear of
    // it rather than underneath it. It animates on the bar's own tempo so the two move as one.
    val selectionInsetPx by animateFloatAsState(
        targetValue = if (lyricSelection.isActive) with(density) { LyricSelectionSafeArea.toPx() } else 0f,
        animationSpec = tween(340, easing = LazerTokens.Motion.pageEasing),
        label = "lyric selection safe area",
    )
    val activeIndex by remember(displayLines, controller) {
        derivedStateOf { findCurrentLyricIndex(displayLines, controller.positionMillis) }
    }

    fun copySelectedLyrics() {
        val text = buildLyricClipboardText(displayLines, lyricSelection.selectedKeys)
        if (text.isBlank()) return
        clickGlowScope.launch {
            clipboard.setClipEntry(ClipEntry(StringSelection(text)))
            lyricSelectionCopied = true
            delay(1_400L)
            lyricSelectionCopied = false
        }
    }

    val baseFontSp = controller.lyricFontSizeSp.sp
    val baseLineHeightSp = (controller.lyricFontSizeSp * 1.38f).sp
    val translationFontSp = (controller.lyricFontSizeSp * 0.48f).coerceIn(12f, 20f).sp
    val translationLineHeightSp = (translationFontSp.value * 1.36f).sp
    val lyricMaxLines = if (controller.showFullLyrics) Int.MAX_VALUE else 2
    val measuredRowHeightsPx = remember(lines, controller.lyricFontSizeSp, controller.showFullLyrics) {
        mutableStateMapOf<TimedLyricLine, Int>()
    }
    val measuredMainHeightsPx = remember(lines, controller.lyricFontSizeSp, controller.showFullLyrics) {
        mutableStateMapOf<TimedLyricLine, Int>()
    }
    val estimatedMainHeightPx = with(density) { baseLineHeightSp.toPx() }
    val estimatedTranslationHeightPx = with(density) { translationLineHeightSp.toPx() }
    // Spacing scales with the rendered lyric line height, so it follows the font-size setting.
    val spacing = lyricSpacing(estimatedMainHeightPx)
    val minimumRowGapPx = spacing.minimumRowGapPx
    val maximumRowGapPx = spacing.maximumRowGapPx
    val minimumTranslationGapPx = spacing.minimumTranslationGapPx
    val maximumTranslationGapPx = spacing.maximumTranslationGapPx
    val rowHeightsPx = displayLines.map { line ->
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
    val transientIndex = displayLines.indexOfFirst { it.text.isBlank() }
    val lineCentersPx = lyricLineCentersWithTransientRow(
        rowHeightsPx = rowHeightsPx,
        transientIndex = transientIndex,
        presence = interludePresence.value,
        minimumGapPx = minimumRowGapPx,
        maximumGapPx = maximumRowGapPx,
    )
    val maxScroll = lineCentersPx.lastOrNull() ?: 0f
    val currentLineCentersPx by rememberUpdatedState(lineCentersPx)
    val currentMaxScroll by rememberUpdatedState(maxScroll)
    val currentDisplayLines by rememberUpdatedState(displayLines)

    var followPlayback by remember { mutableStateOf(true) }
    var manualAtMs by remember { mutableLongStateOf(0L) }
    var lyricScroll by remember { mutableFloatStateOf(0f) }
    val lyricWheelInertia = remember { WheelInertiaMotion() }
    val lyricDragVelocity = remember { DragVelocityTracker() }
    val lyricLineMotion = remember { LyricLineMotionField() }
    var lyricMotionRevision by remember { mutableIntStateOf(0) }
    var lyricMotionAtNs by remember { mutableLongStateOf(0L) }

    LaunchedEffect(
        controller.nowPlaying?.id,
        controller.lyricFontSizeSp,
        controller.showFullLyrics,
    ) {
        followPlayback = true
        lyricWheelInertia.stop()
        val idx = findCurrentLyricIndex(displayLines, controller.positionMillis).coerceAtLeast(0)
        lyricScroll = lineCentersPx.getOrElse(idx) { 0f }
        lyricLineMotion.reset(displayLines.size, lyricScroll)
        lyricMotionRevision++
        lyricMotionAtNs = 0L
    }
    var previousDisplayLines by remember(controller.nowPlaying?.id) { mutableStateOf(displayLines) }
    LaunchedEffect(displayLines) {
        // Hot insertion/removal of the interlude row must preserve every existing row spring.
        lyricLineMotion.remap(displayLines.map { previousDisplayLines.indexOf(it) }, lyricScroll)
        previousDisplayLines = displayLines
        lyricMotionRevision++
    }

    // One persistent frame loop handles both follow motion and wheel inertia. Each lyric row keeps
    // its own velocity and short cascade delay, matching AMLL's non-linear landing.
    val windowForeground = LocalDesktopWindowForeground.current
    LaunchedEffect(windowForeground) {
        if (!windowForeground) return@LaunchedEffect
        while (true) {
            withFrameNanos { now ->
                val dt = if (lyricMotionAtNs == 0L) {
                    1f / 60f
                } else {
                    ((now - lyricMotionAtNs) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
                }
                lyricMotionAtNs = now

                if (!followPlayback && !lyricSelection.isActive &&
                    System.currentTimeMillis() - manualAtMs > controller.lyricFollowDelayMillis
                ) {
                    lyricLineMotion.snapTo(lyricScroll)
                    followPlayback = true
                    lyricWheelInertia.stop()
                }

                if (followPlayback) {
                    val liveLines = currentDisplayLines
                    val liveIndex = findCurrentLyricIndex(liveLines, controller.positionMillis)
                    if (liveIndex >= 0 && liveLines.isNotEmpty()) {
                        val liveMax = currentMaxScroll
                        val target = currentLineCentersPx.getOrElse(liveIndex) { liveMax }
                            .coerceIn(0f, liveMax)
                        val intervalMillis = if (liveIndex > 0) {
                            liveLines[liveIndex].timeMs - liveLines[liveIndex - 1].timeMs
                        } else {
                            null
                        }
                        val moving = lyricLineMotion.advance(
                            target = target,
                            activeIndex = liveIndex,
                            seconds = dt,
                            intervalMillis = intervalMillis,
                            speed = controller.lyricAnimationSpeed,
                        )
                        lyricScroll = lyricLineMotion.positionFor(liveIndex).coerceIn(0f, liveMax)
                        if (moving) lyricMotionRevision++
                    }
                } else {
                    val movement = lyricWheelInertia.advance(dt)
                    if (movement != 0f) {
                        val next = lyricScroll + movement
                        val clamped = next.coerceIn(0f, currentMaxScroll)
                        lyricScroll = clamped
                        if (clamped != next) lyricWheelInertia.stop()
                    }
                }
            }
        }
    }

    fun markManualScroll() {
        lyricLineMotion.snapTo(lyricScroll)
        lyricMotionRevision++
        followPlayback = false
        manualAtMs = System.currentTimeMillis()
    }

    fun onWheel(deltaY: Float) {
        if (lines.isEmpty() || deltaY == 0f) return
        markManualScroll()
        lyricScroll = (lyricScroll + lyricWheelInertia.impulse(deltaY)).coerceIn(0f, currentMaxScroll)
    }

    fun onDragStart() {
        if (lines.isEmpty()) return
        markManualScroll()
        lyricWheelInertia.stop()
        lyricDragVelocity.reset()
    }

    fun onDrag(dy: Float, elapsedMillis: Long) {
        if (lines.isEmpty()) return
        followPlayback = false
        manualAtMs = System.currentTimeMillis()
        val contentDelta = -dy
        lyricDragVelocity.addDelta(contentDelta, elapsedMillis)
        lyricScroll = (lyricScroll + contentDelta).coerceIn(0f, currentMaxScroll)
    }

    fun onDragEnd() {
        manualAtMs = System.currentTimeMillis()
        lyricWheelInertia.fling(lyricDragVelocity.releaseVelocity())
    }

    fun onDragCancel() {
        lyricDragVelocity.reset()
        lyricWheelInertia.stop()
    }

    // The lyrics half of the landscape now-playing screen. The page owns no background or transport
    // chrome of its own; the player already shows both.
    Box(modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clipToBounds()
                        .onPointerEvent(PointerEventType.Scroll) { event ->
                            val dy = event.changes.fold(0f) { acc, c -> acc + c.scrollDelta.y }
                            if (dy != 0f) {
                                event.changes.forEach { it.consume() }
                                onWheel(dy)
                            }
                        }
                        .pointerInput(controller.nowPlaying?.id) {
                            detectDragGestures(
                                onDragStart = { onDragStart() },
                                onDragEnd = { onDragEnd() },
                                onDragCancel = { onDragCancel() },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    onDrag(
                                        dy = dragAmount.y,
                                        elapsedMillis = change.uptimeMillis - change.previousUptimeMillis,
                                    )
                                },
                            )
                        },
                ) {
                    // Keep the active lyric centered in the available lyrics viewport.
                    val centerYPx = with(density) { (maxHeight * 0.5f).toPx() } + selectionInsetPx / 2f
                    val heightPx = with(density) { maxHeight.toPx() }

                    when {
                        controller.nowPlaying == null -> {
                            Text(
                                tr("lyrics.empty"),
                                modifier = Modifier.align(Alignment.Center),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        controller.lyricsLoading -> {
                            Column(
                                Modifier.align(Alignment.Center),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp, color = colors.primary)
                                Spacer(Modifier.height(12.dp))
                                Text(tr("lyrics.loading"), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            }
                        }
                        displayLines.isEmpty() -> {
                            Text(
                                controller.lyricsError ?: tr("lyrics.none"),
                                modifier = Modifier.align(Alignment.Center),
                                style = MaterialTheme.typography.bodyLarge,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        else -> {
                            val motionRevision = lyricMotionRevision
                            val visualIndex = lyricVisualIndex(lineCentersPx, lyricScroll)
                            displayLines.forEachIndexed { index, line ->
                                val lineScroll = if (followPlayback && motionRevision >= 0) {
                                    lyricLineMotion.positionFor(index)
                                } else {
                                    lyricScroll
                                }
                                val rowHeightPx = rowHeightsPx[index]
                                val lineCenterPx = centerYPx + lineCentersPx[index] - lineScroll
                                if (lineCenterPx < -rowHeightPx || lineCenterPx > heightPx + rowHeightPx) {
                                    return@forEachIndexed
                                }

                                val distance = kotlin.math.abs(index - visualIndex)
                                val focus = androidx.compose.runtime.key(controller.nowPlaying?.id, line) {
                                    animatedLyricFocus(index == activeIndex, controller.lyricAnimationSpeed)
                                }
                                val ambient = (1f - distance / 4f).coerceAtLeast(0f)
                                // Blur supplies depth; keep inactive rows readable instead of
                                // multiplying a heavy blur by near-transparent text.
                                val inactiveAlpha = 0.62f + ambient * 0.18f
                                val rowLight = if (line.text.isBlank()) {
                                    0f
                                } else {
                                    animatedLyricSelectionPresence(
                                        selected = lyricSelection.isSelected(selectionKeys, index),
                                        speed = controller.lyricAnimationSpeed,
                                        cascadeDelayMillis = if (lyricSelectionWaveOrigin >= 0) {
                                            lyricSelectionCascadeDelay(index, lyricSelectionWaveOrigin)
                                        } else {
                                            0
                                        },
                                    )
                                }
                                // A marked line borrows the singing line's focus, so the highlight is
                                // the same lighting the sheet already knows how to animate.
                                val highlight = lyricSelectionHighlight(focus, rowLight)
                                val scale = amllLyricLineScale(highlight)
                                val blurRadiusDp = amllLyricBlurRadiusDp(
                                    distance = distance,
                                    focus = highlight,
                                    narrowViewport = maxWidth <= 1024.dp,
                                    interactionSuspended = !followPlayback,
                                    // The desktop sheet is far larger than the phone one, so the
                                    // same depth reads as almost no blur at all.
                                    maxBlurDp = 9f,
                                )
                                val alpha = lyricSelectionRowAlpha(
                                    baseAlpha = inactiveAlpha * (1f - highlight) + highlight * LyricActiveLineAlpha,
                                    modePresence = selectionModeLight,
                                    rowPresence = rowLight,
                                )
                                val color = lyricSelectionColor(
                                    base = lerpColor(colors.onSurfaceVariant, colors.onSurface, highlight),
                                    presence = rowLight,
                                    selection = colors.primary,
                                )
                                val clickGlowActive = clickGlowTokens.containsKey(line)
                                // AMLL blur values are CSS pixels and map directly to desktop
                                // render-effect pixels; the foreground owns this effect, not the
                                // row that also contains the unbounded glow sibling.
                                val contentBlurRadiusPx = blurRadiusDp
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
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .offset {
                                            IntOffset(
                                                x = 0,
                                                y = (lineCenterPx - rowHeightPx / 2f).roundToInt(),
                                            )
                                        }
                                        .padding(horizontal = 20.dp)
                                        .graphicsLayer {
                                            if (line.text.isBlank()) {
                                                scaleX = 0.94f + interludePresence.value * 0.06f
                                                scaleY = 0.92f + interludePresence.value * 0.08f
                                                transformOrigin = TransformOrigin.Center
                                            } else {
                                                clip = false
                                            }
                                        }
                                        .onSizeChanged { size ->
                                            if (measuredRowHeightsPx[line] != size.height) {
                                                measuredRowHeightsPx[line] = size.height
                                            }
                                        }
                                        .combinedClickable(
                                            onClick = {
                                                if (lyricSelection.isActive) {
                                                    lyricSelectionWaveOrigin = -1
                                                    lyricSelection = lyricSelection.toggle(selectionKeys, index)
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
                                                    lyricWheelInertia.stop()
                                                    controller.seekToLyricTime(line.timeMs)
                                                }
                                            },
                                            onLongClick = {
                                                if (line.text.isNotBlank()) {
                                                    lyricSelectionWaveOrigin = -1
                                                    // The sheet holds still while a passage is marked,
                                                    // so the line under the pointer cannot drift off.
                                                    markManualScroll()
                                                    lyricWheelInertia.stop()
                                                    lyricSelection = if (lyricSelection.isActive) {
                                                        lyricSelection.extendTo(selectionKeys, index)
                                                    } else {
                                                        lyricSelection.begin(selectionKeys, index)
                                                    }
                                                }
                                            },
                                        ),
                                    contentAlignment = Alignment.TopCenter,
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        if (line.text.isBlank()) {
                                            LyricInterludeDots(
                                                startMillis = line.timeMs,
                                                endMillis = line.endTimeMs ?: line.timeMs + 5_000L,
                                                positionMillis = if (index == activeIndex) controller.positionMillis else line.endTimeMs ?: line.timeMs,
                                                visibility = interludePresence.value,
                                                glowEnabled = controller.lyricGlowEnabled || clickGlowActive,
                                                dotDiameter = (controller.lyricFontSizeSp * 0.3f).dp,
                                            )
                                        } else {
                                            androidx.compose.runtime.key(controller.nowPlaying?.id, line) {
                                            AmllLyricText(
                                                text = line.text,
                                                words = line.words,
                                                positionMillis = controller.positionMillis,
                                                active = controller.wordLyricsEnabled && index == activeIndex,
                                                currentLine = index == activeIndex,
                                                color = color,
                                                shadowColor = if (controller.isDark) Color.White else Color.Black,
                                                glowEnabled = controller.lyricGlowEnabled,
                                                temporaryGlow = clickGlowActive,
                                                contentBlurRadiusPixels = contentBlurRadiusPx,
                                                speed = controller.lyricAnimationSpeed,
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
                                                        // Modulate alpha per draw so Compose does not
                                                        // allocate another exact-bounds clipping layer.
                                                        compositingStrategy = CompositingStrategy.ModulateAlpha
                                                        clip = false
                                                        transformOrigin = TransformOrigin.Center
                                                    },
                                                style = MaterialTheme.typography.bodyLarge.copy(
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = baseFontSp,
                                                    lineHeight = baseLineHeightSp,
                                                ),
                                                textAlign = TextAlign.Center,
                                                maxLines = lyricMaxLines,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                        if (hasTranslation) {
                                            Spacer(Modifier.height(translationGap))
                                            Text(
                                                text = line.translation.orEmpty(),
                                                modifier = Modifier
                                                    .fillMaxWidth(textWidthFraction)
                                                    .graphicsLayer {
                                                        scaleX = scale
                                                        scaleY = scale
                                                        this.alpha = alpha
                                                        renderEffect = if (contentBlurRadiusPx > 0.01f) {
                                                            BlurEffect(
                                                                contentBlurRadiusPx,
                                                                contentBlurRadiusPx,
                                                                TileMode.Decal,
                                                            )
                                                        } else {
                                                            null
                                                        }
                                                        transformOrigin = TransformOrigin.Center
                                                    },
                                                style = MaterialTheme.typography.bodyMedium.copy(
                                                    fontSize = translationFontSp,
                                                    lineHeight = translationLineHeightSp,
                                                    fontWeight = FontWeight.Normal,
                                                ),
                                                color = lyricSelectionColor(
                                                    base = colors.onSurfaceVariant.copy(alpha = 0.96f),
                                                    presence = rowLight,
                                                    selection = colors.primary,
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
                }

            }
        }
        AnimatedVisibility(
            visible = lyricSelection.isActive,
            modifier = Modifier.align(Alignment.TopCenter),
            enter = fadeIn(tween(220, easing = LazerTokens.Motion.pageEasing)) +
                slideInVertically(tween(340, easing = LazerTokens.Motion.pageEasing), initialOffsetY = { -it / 2 }) +
                scaleIn(tween(340, easing = LazerTokens.Motion.pageEasing), 0.92f),
            exit = fadeOut(tween(160, easing = LazerTokens.Motion.pageEasing)) +
                slideOutVertically(tween(240, easing = LazerTokens.Motion.pageEasing), targetOffsetY = { -it / 3 }) +
                scaleOut(tween(240, easing = LazerTokens.Motion.pageEasing), 0.95f),
            label = "lyric-selection-toolbar",
        ) {
            LyricSelectionToolbar(
                selectedCount = lyricSelection.selectedCount,
                copied = lyricSelectionCopied,
                onSelectAll = {
                    lyricSelectionWaveOrigin = selectionKeys.indexOf(lyricSelection.anchorKey)
                        .takeIf { it >= 0 }
                        ?: selectionKeys.indexOfFirst { it != null }
                            .coerceAtLeast(0)
                    lyricSelection = lyricSelection.selectAll(selectionKeys)
                },
                onCopy = ::copySelectedLyrics,
                onDismiss = {
                    lyricSelection = lyricSelection.clear()
                    lyricSelectionWaveOrigin = -1
                },
            )
        }
    }
}

@Composable
private fun LyricSelectionToolbar(
    selectedCount: Int,
    copied: Boolean,
    onSelectAll: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val glass = LocalOsGlassActive.current
    Surface(
        modifier = Modifier.padding(top = 12.dp),
        shape = RoundedCornerShape(18.dp),
        color = colors.surface.copy(alpha = if (glass) 0.82f else 0.97f),
        // Depth instead of a hairline: the bar floats over an animated sheet, and an outline reads
        // as a table cell there.
        shadowElevation = 12.dp,
    ) {
        Row(
            Modifier.padding(start = 10.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // What is marked is a readout, not a control: fixed size, centred, no container that
            // would make it look pressable, and it never resizes as the count grows.
            Box(
                modifier = Modifier.size(
                    width = LyricSelectionStatusWidth,
                    height = LyricSelectionControlSize,
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
            IconButton(onClick = onSelectAll, modifier = Modifier.size(LyricSelectionControlSize)) {
                Icon(Icons.Outlined.SelectAll, tr("lyrics.select.all"), Modifier.size(18.dp))
            }
            IconButton(onClick = onCopy, modifier = Modifier.size(LyricSelectionControlSize)) {
                Icon(Icons.Outlined.ContentCopy, tr("lyrics.select.copy"), Modifier.size(18.dp), tint = colors.primary)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(LyricSelectionControlSize)) {
                Icon(Icons.Outlined.Close, tr("lyrics.select.close"), Modifier.size(18.dp), tint = colors.onSurfaceVariant)
            }
        }
    }
}

private fun java.awt.Window.isDesktopForeground(): Boolean =
    isShowing && isActive && (this !is Frame || extendedState and Frame.ICONIFIED == 0)


private fun lerpColor(from: Color, to: Color, t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * f,
        green = from.green + (to.green - from.green) * f,
        blue = from.blue + (to.blue - from.blue) * f,
        alpha = from.alpha + (to.alpha - from.alpha) * f,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ArtistChoiceSheet(
    artists: List<Artist>,
    onDismiss: () -> Unit,
    onChoose: (Artist) -> Unit,
) {
    if (artists.isEmpty()) return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.widthIn(max = 520.dp),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(tr("artist.choose.title"), style = MaterialTheme.typography.headlineSmall)
            Text(tr("artist.choose.hint"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            artists.forEach { artist ->
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onChoose(artist) },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                ) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Person, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(artist.name, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CoverSaveSheet(
    request: CoverSaveRequest?,
    onDismiss: () -> Unit,
    onConfirm: (CoverSaveRequest) -> Unit,
) {
    request ?: return
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.widthIn(max = 520.dp),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(tr("cover.save.title"), style = MaterialTheme.typography.headlineSmall)
            Text(tr("cover.save.hint", request.title), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                TextButton(onClick = onDismiss) { Text(tr("cover.save.cancel")) }
                Button(onClick = { onConfirm(request) }, shape = RoundedCornerShape(12.dp)) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(tr("cover.save.confirm"))
                }
            }
        }
    }
}

private fun chooseCoverDestination(title: String): java.io.File? {
    val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifBlank { "Lazer cover" }
    val chooser = javax.swing.JFileChooser().apply {
        dialogTitle = tr("cover.save.title")
        selectedFile = java.io.File("$safeTitle.jpg")
        fileFilter = javax.swing.filechooser.FileNameExtensionFilter("JPEG image", "jpg", "jpeg")
    }
    if (chooser.showSaveDialog(null) != javax.swing.JFileChooser.APPROVE_OPTION) return null
    val selected = chooser.selectedFile
    val requested = if (selected.extension.isBlank()) java.io.File(selected.parentFile, "${selected.name}.jpg") else selected
    if (!requested.exists()) return requested
    val baseName = requested.nameWithoutExtension
    val extension = requested.extension.takeIf(String::isNotBlank)?.let { ".$it" }.orEmpty()
    return generateSequence(1) { it + 1 }
        .map { index -> java.io.File(requested.parentFile, "$baseName ($index)$extension") }
        .first { !it.exists() }
}

@Composable
private fun LoginOverlay(controller: DesktopPlayerController) {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.38f))
            .pointerInput(Unit) { detectTapGestures { controller.closeLogin() } },
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .width(760.dp)
                .height(520.dp)
                .pointerInput(Unit) { detectTapGestures { } },
            shape = RoundedCornerShape(28.dp),
            color = colors.surface,
            shadowElevation = 18.dp,
        ) {
            Row(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.width(250.dp).fillMaxHeight().background(colors.primaryContainer).padding(26.dp),
                ) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(colors.surface), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Rounded.LibraryMusic,
                            contentDescription = "Lazer",
                            modifier = Modifier.size(25.dp),
                            tint = colors.primary,
                        )
                    }
                    Spacer(Modifier.height(34.dp))
                    Text(tr("login.side.heading"), style = MaterialTheme.typography.headlineMedium, color = colors.onPrimaryContainer)
                    Spacer(Modifier.height(10.dp))
                    Text(
                        tr("login.sub.desktop"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colors.onPrimaryContainer.copy(alpha = 0.72f),
                    )
                    Spacer(Modifier.weight(1f))
                }

                Column(Modifier.weight(1f).padding(horizontal = 32.dp, vertical = 24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tr("login.title"), style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = controller::closeLogin) { Icon(Icons.Outlined.Close, tr("login.close")) }
                    }
                    Spacer(Modifier.height(14.dp))
                    LoginMethodSwitch(controller)
                    Spacer(Modifier.height(22.dp))
                    when (controller.loginMethod) {
                        LoginMethod.QR_CODE -> QrLoginContent(controller)
                        LoginMethod.PASSWORD -> PasswordLoginContent(controller)
                        LoginMethod.COOKIE -> CookieLoginContent(controller)
                    }
                }
            }
        }
    }
}

@Composable
private fun LoginMethodSwitch(controller: DesktopPlayerController) {
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(11.dp)).background(colors.surfaceVariant).padding(3.dp),
    ) {
        LoginMethod.entries.forEach { method ->
            val selected = controller.loginMethod == method
            Box(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(9.dp))
                    .background(if (selected) colors.surface else Color.Transparent)
                    .clickable { controller.selectLoginMethod(method) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    method.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) colors.onSurface else colors.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun QrLoginContent(controller: DesktopPlayerController) {
    val colors = MaterialTheme.colorScheme
    val qrBitmap = remember(controller.qrFallbackUrl, controller.qrImageData) {
        controller.qrFallbackUrl?.let(::generateQrCodeBitmap)
            ?: decodeQrImage(controller.qrImageData)
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(216.dp).clip(RoundedCornerShape(20.dp)).background(Color.White).border(1.dp, colors.outlineVariant, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when {
                qrBitmap != null -> Image(qrBitmap, tr("login.artwork.qr"), Modifier.size(188.dp))
                controller.qrLoginState == QrLoginState.CREATING -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                else -> Icon(Icons.Outlined.MusicNote, null, Modifier.size(42.dp), tint = colors.primary)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            when (controller.qrLoginState) {
                QrLoginState.CREATING -> tr("login.qr.creating")
                QrLoginState.WAITING_FOR_SCAN -> tr("login.qr.scan")
                QrLoginState.WAITING_FOR_CONFIRMATION -> tr("login.qr.confirm")
                QrLoginState.EXPIRED -> tr("login.qr.expired")
                QrLoginState.AUTHORIZED -> tr("login.qr.success")
                QrLoginState.ERROR -> tr("login.qr.error")
                else -> tr("login.qr.ready")
            },
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
        )
        controller.loginError?.let {
            Spacer(Modifier.height(5.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = colors.error)
        }
        if (controller.qrLoginState in setOf(QrLoginState.EXPIRED, QrLoginState.ERROR)) {
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = controller::startQrLogin) {
                Icon(Icons.Outlined.Refresh, null, Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("login.qr.regenerate"))
            }
        }
    }
}

@Composable
private fun PasswordLoginContent(controller: DesktopPlayerController) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(tr("login.pw.title"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(5.dp))
        Text(tr("login.pw.hint"), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = controller.loginIdentifier,
            onValueChange = controller::updateLoginIdentifier,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(tr("login.pw.identifier")) },
            singleLine = true,
            shape = RoundedCornerShape(13.dp),
            colors = quietTextFieldColors(),
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = controller.loginPassword,
            onValueChange = controller::updateLoginPassword,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(tr("login.pw.password")) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(13.dp),
            colors = quietTextFieldColors(),
        )
        controller.loginError?.let {
            Spacer(Modifier.height(9.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = controller::submitPasswordLogin,
            enabled = !controller.isSubmittingLogin,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (controller.isSubmittingLogin) {
                CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (controller.isSubmittingLogin) tr("login.submitting") else tr("login.submit"))
        }
    }
}

@Composable
private fun ArtistNames(
    artists: List<Artist>,
    fallback: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val available = artists.filter { it.id > 0L && it.name.isNotBlank() }
    val openArtists = LocalOpenArtists.current
    Text(
        text = available.joinToString(" / ") { it.name }.ifBlank { fallback },
        modifier = modifier.then(
            if (available.isNotEmpty()) Modifier.clickable { openArtists(available) } else Modifier,
        ),
        style = style,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun CookieLoginContent(controller: DesktopPlayerController) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(tr("login.cookie.title"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(5.dp))
        Text(
            tr("login.cookie.hint"),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = controller.loginCookie,
            onValueChange = controller::updateLoginCookie,
            modifier = Modifier.fillMaxWidth(),
            label = { Text(tr("login.cookie.label")) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(13.dp),
            colors = quietTextFieldColors(),
        )
        controller.loginError?.let {
            Spacer(Modifier.height(9.dp))
            Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error)
        }
        Spacer(Modifier.height(20.dp))
        Button(
            onClick = controller::submitCookieLogin,
            enabled = !controller.isSubmittingLogin,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            if (controller.isSubmittingLogin) {
                CircularProgressIndicator(Modifier.size(17.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (controller.isSubmittingLogin) tr("login.submitting") else tr("login.cookie.submit"))
        }
    }
}

@Composable
private fun quietTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surface,
    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
)

private fun decodeQrImage(dataUri: String?): ImageBitmap? {
    if (dataUri.isNullOrBlank()) return null
    return runCatching {
        val encoded = dataUri.substringAfter("base64,", dataUri)
        SkiaImage.makeFromEncoded(Base64.getDecoder().decode(encoded)).toComposeImageBitmap()
    }.getOrNull()
}
