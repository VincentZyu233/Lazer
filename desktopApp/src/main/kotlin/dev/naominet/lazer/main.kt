package dev.naominet.lazer

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.compose.setSingletonImageLoaderFactory
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.request.crossfade
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.UserAgent
import java.awt.Dimension
import java.awt.Insets
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.roundToInt

/**
 * Desktop entry.
 *
 * On Windows ARM64, do NOT use the green gutter Run on this function — IDEA creates a
 * "desktopApp [jvm]" config with ComposeJvmRunConfigurationExtension, which crashes with
 * "Unknown host target: windows aarch64".
 *
 * Use run configuration **Desktop App** (`:desktopApp:runDesktop`) or:
 *   gradlew :desktopApp:runDesktop
 *   run-desktop.bat
 */
fun main(args: Array<String>) {
    WindowsTaskbarDebugLog.configure(args)
    val startupCommand = windowsMediaCommandFromArgs(args)
    if (startupCommand != null && WindowsMediaCommandBridge.forwardToRunning(startupCommand)) return
    WindowsJumpList.prepareAppUserModelId()

    application {
    setSingletonImageLoaderFactory { context ->
        createDesktopImageLoader(context)
    }
    val windowState = rememberWindowState(
        position = WindowPosition.Aligned(Alignment.Center),
        size = DpSize(1280.dp, 820.dp),
    )
    val controller = remember { DesktopPlayerController().also { it.start() } }
    val commandServer = remember { WindowsMediaCommandServer(controller::dispatchMediaControlAction) }
    DisposableEffect(commandServer) {
        commandServer.start()
        startupCommand?.let { controller.dispatchMediaControlAction(it.action) }
        onDispose { commandServer.close() }
    }
    val nowPlaying = controller.nowPlaying
    val windowTitle = if (controller.isPlaying && nowPlaying != null) {
        "Lazer - ${nowPlaying.title}"
    } else {
        "Lazer"
    }
    // Compose's maximized placement can cover the Windows taskbar for undecorated windows.
    // Store native pixel bounds so maximize/restore also stays correct on mixed-DPI monitors.
    var restoreBounds by remember { mutableStateOf<Rectangle?>(null) }

    Window(
        onCloseRequest = ::exitApplication,
        title = "Lazer",
        icon = painterResource("icon.png"),
        state = windowState,
        undecorated = true,
        // Native acrylic is disabled; a layered transparent window can stay entirely invisible
        // when Skiko falls back from DirectX on Windows, even though the process is healthy.
        transparent = false,
    ) {
        // 窗口尺寸是有下限的：布局在更小的画面上会挤坏；同时不允许大过当前屏幕的工作区，
        // 否则在 1366x768 这类小屏上标题栏会被推到屏幕外，自绘的关闭按钮就点不到了。
        LaunchedEffect(Unit) {
            val graphics = window.graphicsConfiguration
            val insets = Toolkit.getDefaultToolkit().getScreenInsets(graphics)
            val work = workAreaBounds(graphics.bounds, insets)
            val scale = graphics.defaultTransform.scaleX.toDouble()
            val minimumWidth = (DESKTOP_MINIMUM_WINDOW_WIDTH_DP * scale).roundToInt()
                .coerceAtMost(work.width)
            val minimumHeight = (DESKTOP_MINIMUM_WINDOW_HEIGHT_DP * scale).roundToInt()
                .coerceAtMost(work.height)
            window.minimumSize = Dimension(minimumWidth, minimumHeight)
            window.maximumSize = Dimension(work.width, work.height)

            val bounds = window.bounds
            val width = bounds.width.coerceIn(minimumWidth, work.width)
            val height = bounds.height.coerceIn(minimumHeight, work.height)
            window.setBounds(
                work.x + (work.width - width) / 2,
                work.y + (work.height - height) / 2,
                width,
                height,
            )
        }
        DesktopPlayerApp(
            controller = controller,
            isWindowMaximized = restoreBounds != null,
            onMinimizeWindow = { windowState.isMinimized = true },
            onToggleMaximizeWindow = {
                val savedBounds = restoreBounds
                if (savedBounds != null) {
                    window.bounds = Rectangle(savedBounds)
                    restoreBounds = null
                } else {
                    val gc = window.graphicsConfiguration
                    val insets = Toolkit.getDefaultToolkit().getScreenInsets(gc)
                    restoreBounds = Rectangle(window.bounds)
                    window.bounds = workAreaBounds(gc.bounds, insets)
                }
            },
            onCloseWindow = ::exitApplication,
        )
    }
    }
}

internal fun createDesktopImageLoader(context: PlatformContext): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(
                KtorNetworkFetcherFactory(
                    httpClient = {
                        HttpClient(CIO) {
                            install(UserAgent) {
                                agent = "LazerDesktop/1.0"
                            }
                        }
                    },
                ),
            )
        }
        .memoryCache {
            MemoryCache.Builder()
                .maxSizeBytes(16L * 1024L * 1024L)
                .weakReferencesEnabled(false)
                .build()
        }
        .crossfade(false)
        .build()

internal fun workAreaBounds(screen: Rectangle, insets: Insets): Rectangle = Rectangle(
    screen.x + insets.left,
    screen.y + insets.top,
    (screen.width - insets.left - insets.right).coerceAtLeast(400),
    (screen.height - insets.top - insets.bottom).coerceAtLeast(300),
)

/** Below this the lyrics column and the queue collapse into each other, so the window stops there. */
internal const val DESKTOP_MINIMUM_WINDOW_WIDTH_DP = 960f
internal const val DESKTOP_MINIMUM_WINDOW_HEIGHT_DP = 620f
