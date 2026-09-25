package dev.naominet.lazer

import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Commands used by Windows Jump List tasks. */
internal enum class WindowsMediaCommand(val argument: String, val action: MediaControlAction) {
    Previous("previous", MediaControlAction.Previous),
    PlayPause("play-pause", MediaControlAction.PlayPause),
    Next("next", MediaControlAction.Next),
}

internal const val MEDIA_COMMAND_ARGUMENT = "--lazer-media-command="
internal const val TASKBAR_DEBUG_LOG_ARGUMENT = "--debug-log"
private const val MEDIA_COMMAND_PORT = 52473

internal fun windowsMediaCommandFromArgs(args: Array<String>): WindowsMediaCommand? =
    args.firstOrNull { it.startsWith(MEDIA_COMMAND_ARGUMENT) }
        ?.removePrefix(MEDIA_COMMAND_ARGUMENT)
        ?.let { value -> WindowsMediaCommand.entries.firstOrNull { it.argument == value } }

internal fun windowsTaskbarDebugLoggingFromArgs(args: Array<String>): Boolean =
    args.any { it == TASKBAR_DEBUG_LOG_ARGUMENT }

internal fun windowsTaskbarNativeLogPath(home: Path): Path =
    home.resolve(".lazer").resolve("logs").resolve("taskbar-native.log")

/** Opt-in diagnostics for the native Windows thumbnail-toolbar bridge. */
internal object WindowsTaskbarDebugLog {
    @Volatile
    var enabled: Boolean = false
        private set

    val nativeLogPath: Path
        get() = windowsTaskbarNativeLogPath(Path.of(System.getProperty("user.home")))

    fun configure(args: Array<String>) {
        enabled = windowsTaskbarDebugLoggingFromArgs(args)
        if (enabled) PlaybackDebugLog.event("taskbar-debug-enabled", "native-log=$nativeLogPath")
    }

    fun event(name: String, details: String = "") {
        if (enabled) PlaybackDebugLog.event("taskbar-$name", details)
    }
}

/**
 * A loopback-only bridge lets Jump List tasks control the already-running player.
 * A task starts another launcher process; that process forwards its command here and exits.
 */
internal object WindowsMediaCommandBridge {
    fun forwardToRunning(command: WindowsMediaCommand): Boolean {
        if (!isWindowsDesktop()) return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), MEDIA_COMMAND_PORT), 300)
                socket.soTimeout = 500
                socket.getOutputStream().bufferedWriter().use { writer ->
                    writer.appendLine(command.argument)
                    writer.flush()
                }
            }
            true
        }.getOrDefault(false)
    }
}

internal class WindowsMediaCommandServer(
    private val onCommand: (MediaControlAction) -> Unit,
) : Closeable {
    private var server: ServerSocket? = null
    private var executor: ExecutorService? = null

    fun start() {
        if (!isWindowsDesktop() || server != null) return
        runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getLoopbackAddress(), MEDIA_COMMAND_PORT))
            }
        }.onSuccess { socket ->
            server = socket
            executor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "Lazer Windows media command").apply { isDaemon = true }
            }.also { worker ->
                worker.execute {
                    while (!socket.isClosed) {
                        runCatching {
                            socket.accept().use { client ->
                                val command = client.getInputStream().bufferedReader().readLine()
                                WindowsMediaCommand.entries
                                    .firstOrNull { it.argument == command }
                                    ?.let {
                                        PlaybackDebugLog.event("jump-list-command-received", it.argument)
                                        onCommand(it.action)
                                    }
                            }
                        }
                    }
                }
            }
            PlaybackDebugLog.event("jump-list-command-server-attached")
        }.onFailure { error ->
            PlaybackDebugLog.event("jump-list-command-server-error", error.playbackDebugSummary())
        }
    }

    override fun close() {
        runCatching { server?.close() }
        executor?.shutdownNow()
        server = null
        executor = null
    }
}
