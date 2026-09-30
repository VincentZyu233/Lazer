package dev.naominet.lazer

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.naominet.lazer.gateway.model.Playlist
import dev.naominet.lazer.gateway.model.Song
import kotlinx.coroutines.delay

private const val IOS_COMPOSE_READY_MARKER = "LAZER_IOS_COMPOSE_READY"
private const val IOS_COMPOSE_STAGE_PREFIX = "LAZER_IOS_COMPOSE_STAGE:"
private var didReportIOSComposeReady = false

private fun reportIOSComposeStage(stage: String) {
    println("$IOS_COMPOSE_STAGE_PREFIX$stage")
}

@Composable
internal fun IOSLazerApp() {
    reportIOSComposeStage("enter")
    val controller = remember {
        reportIOSComposeStage("controller:create")
        IOSGatewayController().also { reportIOSComposeStage("controller:ready") }
    }
    val settings = controller.settings
    reportIOSComposeStage("effects")

    LaunchedEffect(controller) {
        controller.bootstrap()
        while (true) {
            delay(250L)
            settings.syncFromNativeShell()
        }
    }
    LaunchedEffect(controller.message) {
        if (controller.message != null) {
            delay(4_000L)
            controller.clearMessage()
        }
    }
    DisposableEffect(controller) {
        onDispose(controller::close)
    }

    LazerTheme(
        isDark = settings.isDark,
        engine = settings.style.themeEngine,
    ) {
        reportIOSComposeStage("theme")
        val colors = MaterialTheme.colorScheme
        val nativeGlass = settings.style.usesLiquidGlass && settings.usesNativeLiquidGlass
        reportIOSComposeStage("glass:create")
        val composeGlass = rememberLazerLiquidGlass(
            enabled = settings.style.usesLiquidGlass && !nativeGlass,
            backgroundColor = colors.background,
        )
        reportIOSComposeStage("glass:ready")

        Box(
            Modifier
                .fillMaxSize()
                .background(colors.background)
                .onSizeChanged { reportIOSComposeStage("size:${it.width}x${it.height}") }
                .drawFirstIOSFrame(),
        ) {
            reportIOSComposeStage("content")
            Box(
                Modifier
                    .fillMaxSize()
                    .captureLiquidGlass(composeGlass),
            ) {
                if (!controller.isReady) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                } else {
                    IOSAppContent(
                        controller = controller,
                        nativeGlass = nativeGlass,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (!nativeGlass && controller.isReady) {
                IOSComposeNavigation(
                    selected = settings.destination,
                    glass = composeGlass,
                    onSelected = settings::setDestination,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .widthIn(max = 640.dp),
                )
            }

            controller.message?.let { message ->
                MessageBanner(
                    message = message,
                    onDismiss = controller::clearMessage,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 20.dp, vertical = 12.dp)
                        .widthIn(max = 560.dp),
                )
            }
        }
    }
    reportIOSComposeStage("theme:ready")
}

/**
 * The launch smoke test needs a signal that pixels reached the screen, not merely that composition
 * ran: a Compose root that never gets a frame from the display link leaves a live but blank shell.
 */
private fun Modifier.drawFirstIOSFrame(): Modifier = drawWithContent {
    content.draw(this)
    if (!didReportIOSComposeReady) {
        didReportIOSComposeReady = true
        println(IOS_COMPOSE_READY_MARKER)
    }
}

@Composable
private fun IOSAppContent(
    controller: IOSGatewayController,
    nativeGlass: Boolean,
    modifier: Modifier = Modifier,
) {
    val bottomInset = when {
        nativeGlass && controller.nowPlaying != null -> 160.dp
        nativeGlass -> 96.dp
        controller.nowPlaying != null -> 152.dp
        else -> 88.dp
    }
    Column(
        modifier
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(bottom = bottomInset),
    ) {
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AnimatedContent(
                targetState = controller.activePlaylist,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "ios-content",
            ) { playlist ->
                if (playlist != null) {
                    IOSPlaylistScreen(controller, playlist)
                } else {
                    when (controller.settings.destination) {
                        IOSDestination.HOME -> IOSHomeScreen(controller)
                        IOSDestination.SEARCH -> IOSSearchScreen(controller)
                        IOSDestination.LIBRARY -> IOSLibraryScreen(controller)
                        IOSDestination.SETTINGS -> IOSSettingsScreen(controller)
                    }
                }
            }
        }
        controller.nowPlaying?.let { track ->
            IOSMiniPlayer(
                track = track,
                isPlaying = controller.isPlaying,
                isLoading = controller.isPlaybackLoading,
                onPrevious = controller::playPrevious,
                onToggle = controller::togglePlayback,
                onNext = controller::playNext,
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .widthIn(max = 720.dp)
                    .align(Alignment.CenterHorizontally),
            )
        }
    }
}

@Composable
private fun IOSHomeScreen(controller: IOSGatewayController) {
    PageList(
        title = tr("home.section.title"),
        subtitle = tr(if (controller.currentProfile == null) "home.section.anon" else "home.section.signed"),
        loading = controller.isHomeLoading,
        isEmpty = controller.featuredPlaylists.isEmpty(),
        emptyText = tr("home.empty"),
        onRefresh = controller::refreshHome,
    ) {
        items(controller.featuredPlaylists, key = Playlist::id) { playlist ->
            PlaylistRow(playlist, onClick = { controller.openPlaylist(playlist) })
        }
    }
}

@Composable
private fun IOSSearchScreen(controller: IOSGatewayController) {
    var query by remember { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp),
    ) {
        PageHeader(tr("search.title"), tr("search.hint"))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text(tr("search.placeholder")) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    controller.search(query)
                    keyboard?.hide()
                },
            ),
            trailingIcon = {
                TextButton(onClick = { controller.search(query); keyboard?.hide() }) {
                    Text(tr("nav.search"))
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        when {
            controller.isSearchLoading -> LoadingRow(tr("search.searching"))
            query.isBlank() && controller.searchResults.isEmpty() -> QuietEmptyState(tr("search.empty"))
            controller.searchResults.isEmpty() -> QuietEmptyState(tr("search.no_results"))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
            ) {
                items(controller.searchResults, key = Song::id) { song ->
                    SongRow(song, onClick = { controller.play(song, controller.searchResults) })
                }
            }
        }
    }
}

@Composable
private fun IOSLibraryScreen(controller: IOSGatewayController) {
    PageList(
        title = tr("library.title"),
        subtitle = controller.currentProfile?.nickname?.takeIf(String::isNotBlank)
            ?: tr("library.sub.anon"),
        loading = controller.isLibraryLoading,
        isEmpty = controller.userPlaylists.isEmpty(),
        emptyText = if (controller.currentProfile == null) tr("nav.login_hint") else tr("library.no_playlists"),
        onRefresh = controller::refreshLibrary,
    ) {
        items(controller.userPlaylists, key = Playlist::id) { playlist ->
            PlaylistRow(playlist, onClick = { controller.openPlaylist(playlist) })
        }
    }
}

@Composable
private fun IOSPlaylistScreen(controller: IOSGatewayController, playlist: Playlist) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = controller::closePlaylist) { Text("‹ ${tr("common.back")}") }
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    playlist.name.ifBlank { tr("playlist.title") },
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tr("playlist.tracks", controller.activePlaylistTracks.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            controller.isPlaylistLoading && controller.activePlaylistTracks.isEmpty() ->
                LoadingRow(tr("playlist.opening"))
            controller.activePlaylistTracks.isEmpty() -> QuietEmptyState(tr("playlist.empty"))
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            ) {
                items(controller.activePlaylistTracks, key = Song::id) { song ->
                    SongRow(song, onClick = { controller.play(song, controller.activePlaylistTracks) })
                }
            }
        }
    }
}

@Composable
private fun IOSSettingsScreen(controller: IOSGatewayController) {
    val settings = controller.settings
    var cookie by remember { mutableStateOf("") }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PageHeader(tr("settings.title"), tr("settings.subtitle")) }
        item { SectionLabel(tr("settings.appearance")) }
        item {
            SettingSwitchRow(
                title = tr("settings.interface.dark"),
                subtitle = if (settings.isDark) tr("settings.interface.dark") else tr("settings.interface.light"),
                checked = settings.isDark,
                engine = settings.style.themeEngine,
                onCheckedChange = settings::setDark,
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("界面风格", style = MaterialTheme.typography.titleMedium)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LazerStyle.entries.forEach { style ->
                        val selected = settings.style == style
                        OutlinedButton(
                            onClick = { settings.setStyle(style) },
                            modifier = Modifier.weight(1f),
                            colors = if (selected) {
                                ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                )
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 10.dp),
                        ) {
                            Text(style.iosLabel(), maxLines = 1)
                        }
                    }
                }
            }
        }
        if (settings.style.usesLiquidGlass) {
            item {
                SettingSwitchRow(
                    title = "使用原生 SwiftUI Liquid Glass",
                    subtitle = "由系统绘制底部导航，页面内容仍由 Compose 统一呈现。",
                    checked = settings.usesNativeLiquidGlass,
                    engine = settings.style.themeEngine,
                    onCheckedChange = settings::setNativeLiquidGlass,
                )
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
        item { SectionLabel(tr("settings.account")) }
        if (controller.currentProfile == null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("粘贴登录 Cookie", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "登录信息只保存在这台设备上，用于同步你的歌单。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = cookie,
                        onValueChange = { cookie = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                    Button(
                        onClick = { controller.loginWithCookie(cookie) },
                        enabled = cookie.isNotBlank() && !controller.isLibraryLoading,
                    ) {
                        Text(if (controller.isLibraryLoading) "正在连接…" else "登录并同步")
                    }
                }
            }
        } else {
            item {
                SettingActionRow(
                    title = controller.currentProfile?.nickname ?: tr("settings.account.signed.fallback"),
                    subtitle = tr("settings.account.signed"),
                    action = tr("settings.account.logout"),
                    onClick = controller::logout,
                )
            }
        }
        item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }
        item {
            Text(
                "Lazer ${LazerRelease.versionName} · iOS Compose Multiplatform",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 20.dp),
            )
        }
    }
}

@Composable
private fun PageList(
    title: String,
    subtitle: String,
    loading: Boolean,
    isEmpty: Boolean,
    emptyText: String,
    onRefresh: () -> Unit,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 4.dp),
    ) {
        item { PageHeader(title, subtitle, onRefresh, loading) }
        if (loading) item { LoadingRow(tr("home.preparing")) }
        if (!loading && isEmpty) item { QuietEmptyState(emptyText) }
        content()
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun PageHeader(
    title: String,
    subtitle: String,
    onRefresh: (() -> Unit)? = null,
    refreshing: Boolean = false,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 18.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onRefresh != null) {
            TextButton(onClick = onRefresh, enabled = !refreshing) {
                Text(if (refreshing) "…" else tr("nav.sync"))
            }
        }
    }
}

@Composable
private fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkPlaceholder(playlist.name, Modifier.size(58.dp), RoundedCornerShape(14.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                playlist.name.ifBlank { tr("playlist.title") },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                playlist.creator?.nickname?.takeIf(String::isNotBlank)
                    ?: tr("playlist.tracks", playlist.trackCount ?: 0),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SongRow(song: Song, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkPlaceholder(song.name, Modifier.size(48.dp), RoundedCornerShape(12.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.name.ifBlank { tr("track.unknown_song") },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                song.artistLine(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("▶", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ArtworkPlaceholder(
    label: String,
    modifier: Modifier,
    shape: RoundedCornerShape,
) {
    Surface(
        modifier = modifier,
        shape = shape,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label.trim().take(1).ifEmpty { "♪" }, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun IOSMiniPlayer(
    track: Song,
    isPlaying: Boolean,
    isLoading: Boolean,
    onPrevious: () -> Unit,
    onToggle: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkPlaceholder(track.name, Modifier.size(44.dp), RoundedCornerShape(11.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(track.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                Text(
                    track.artistLine(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            MiniPlayerButton("‹‹", onPrevious)
            if (isLoading) {
                CircularProgressIndicator(Modifier.padding(10.dp).size(20.dp), strokeWidth = 2.dp)
            } else {
                MiniPlayerButton(if (isPlaying) "Ⅱ" else "▶", onToggle)
            }
            MiniPlayerButton("››", onNext)
        }
    }
}

@Composable
private fun MiniPlayerButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun IOSComposeNavigation(
    selected: IOSDestination,
    glass: LazerLiquidGlass,
    onSelected: (IOSDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier
            .then(
                if (glass.isEnabled) {
                    Modifier.liquidGlassControlSurface(
                        glass = glass,
                        shape = shape,
                        surfaceColor = MaterialTheme.colorScheme.surface,
                        blurRadius = 8.dp,
                    )
                } else {
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh, shape)
                },
            )
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IOSDestination.entries.forEach { destination ->
            val active = destination == selected
            Column(
                Modifier
                    .weight(1f)
                    .background(
                        if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                        RoundedCornerShape(22.dp),
                    )
                    .clickable { onSelected(destination) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(destination.symbol, style = MaterialTheme.typography.titleMedium)
                Text(
                    destination.label,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    engine: LazerThemeEngine,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        LazerSwitch(engine = engine, checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingActionRow(
    title: String,
    subtitle: String,
    action: String,
    onClick: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun LoadingRow(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuietEmptyState(text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 42.dp, horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MessageBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.clickable(onClick = onDismiss),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.inverseSurface,
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
        shadowElevation = 8.dp,
    ) {
        Text(message, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

private val IOSDestination.label: String
    get() = when (this) {
        IOSDestination.HOME -> tr("nav.home")
        IOSDestination.SEARCH -> tr("nav.search")
        IOSDestination.LIBRARY -> tr("nav.library")
        IOSDestination.SETTINGS -> tr("settings.title")
    }

private val IOSDestination.symbol: String
    get() = when (this) {
        IOSDestination.HOME -> "⌂"
        IOSDestination.SEARCH -> "⌕"
        IOSDestination.LIBRARY -> "♫"
        IOSDestination.SETTINGS -> "⚙"
    }

private fun Song.artistLine(): String =
    artists.joinToString(" / ") { it.name }.ifBlank { tr("track.unknown_artist.android") }

private fun LazerStyle.iosLabel(): String = when (this) {
    LazerStyle.MATERIAL -> "Material"
    LazerStyle.MIUIX -> "Miuix"
    LazerStyle.LIQUID_GLASS -> "Liquid Glass"
}
