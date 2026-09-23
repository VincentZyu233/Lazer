package dev.naominet.lazer

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import java.awt.EventQueue
import java.awt.Window
import java.io.File

internal const val THUMBAR_CMD_PREVIOUS = 40001
internal const val THUMBAR_CMD_PLAYPAUSE = 40002
internal const val THUMBAR_CMD_NEXT = 40003

internal fun thumbarActionFromCommand(commandId: Int): MediaControlAction? = when (commandId) {
    THUMBAR_CMD_PREVIOUS -> MediaControlAction.Previous
    THUMBAR_CMD_PLAYPAUSE -> MediaControlAction.PlayPause
    THUMBAR_CMD_NEXT -> MediaControlAction.Next
    else -> null
}

/**
 * Windows thumbnail-toolbar integration backed by the x64 native bridge.
 *
 * The C++ bridge installs its native subclass from the HWND owner thread. That preserves AWT's
 * procedure chain while giving the bridge a stable taskbar-command entry point after Compose and
 * Skiko have finished creating the window peer.
 */
internal class WindowsThumbar(
    private val onAction: (MediaControlAction) -> Unit,
) {
    private var hwnd: Pointer? = null
    private var bridge: WindowsTaskbarBridge? = null
    private var installed = false
    private var isPlaying = false

    // A native DLL never retains a JVM callback automatically.
    private val callback = object : WindowsTaskbarBridge.ActionCallback {
        override fun callback(action: Int) {
            when (action) {
                ACTION_PREVIOUS -> dispatchAction(MediaControlAction.Previous)
                ACTION_PLAY_PAUSE -> dispatchAction(MediaControlAction.PlayPause)
                ACTION_NEXT -> dispatchAction(MediaControlAction.Next)
                ACTION_THEME_CHANGED -> EventQueue.invokeLater(::refreshTheme)
                ACTION_BUTTONS_APPLIED -> PlaybackDebugLog.event("thumbar-buttons-applied")
                ACTION_BUTTONS_APPLY_FAILED -> PlaybackDebugLog.event("thumbar-buttons-apply-failed")
                ACTION_SUBCLASS_INSTALLED -> PlaybackDebugLog.event("thumbar-subclass-installed")
                ACTION_SUBCLASS_INSTALL_FAILED -> PlaybackDebugLog.event("thumbar-subclass-install-failed")
                else -> {
                    when (action and COMMAND_SOURCE_MASK) {
                        ACTION_PEER_WINDOWS_ATTACHED -> PlaybackDebugLog.event(
                            "thumbar-peer-windows-attached",
                            (action and COMMAND_ID_MASK).toString(),
                        )
                        else -> reportObservedCommand(action)
                    }
                }
            }
        }
    }

    fun install(window: Window, playing: Boolean) {
        if (!isWindowsDesktop()) return
        isPlaying = playing
        runCatching {
            val handle = Native.getWindowPointer(window) ?: return
            val loadedBridge = WindowsTaskbarBridgeLoader.load() ?: run {
                PlaybackDebugLog.event("thumbar-bridge-load-failed")
                return
            }
            hwnd = handle
            bridge = loadedBridge
            installed = loadedBridge.lazer_taskbar_install(
                handle,
                iconPath("previous"),
                iconPath("play"),
                iconPath("pause"),
                iconPath("next"),
                tooltip("media_control.previous"),
                tooltip("media_control.play"),
                tooltip("media_control.pause"),
                tooltip("media_control.next"),
                if (isPlaying) 1 else 0,
                callback,
            ) != 0
            PlaybackDebugLog.event(if (installed) "thumbar-installed" else "thumbar-install-failed")
        }.onFailure { error ->
            PlaybackDebugLog.event("thumbar-install-error", error.playbackDebugSummary())
        }
    }

    fun updatePlayState(playing: Boolean) {
        if (!installed || isPlaying == playing) return
        isPlaying = playing
        updateButtons()
    }

    fun close() {
        val handle = hwnd
        if (handle != null) runCatching { bridge?.lazer_taskbar_remove(handle) }
        hwnd = null
        bridge = null
        installed = false
    }

    private fun refreshTheme() {
        if (!installed) return
        updateButtons()
        WindowsJumpList.install(isPlaying)
        PlaybackDebugLog.event("taskbar-media-icons-theme-refreshed")
    }

    /**
     * Explorer sends thumbnail-button commands through AWT's native window thread, not Compose's
     * event queue. Mutating the player state there races Compose and made clicks appear to do
     * nothing. Queue the action onto the same AWT event queue used by the desktop UI.
     */
    private fun dispatchAction(action: MediaControlAction) {
        PlaybackDebugLog.event("thumbar-action-received", action.name)
        EventQueue.invokeLater {
            runCatching { onAction(action) }
                .onSuccess { PlaybackDebugLog.event("thumbar-action-dispatched", action.name) }
                .onFailure { error ->
                    PlaybackDebugLog.event("thumbar-action-error", error.playbackDebugSummary())
                }
        }
    }

    private fun reportObservedCommand(action: Int) {
        val source = action and COMMAND_SOURCE_MASK
        val command = action and COMMAND_ID_MASK
        when (source) {
            ACTION_CALL_WINDOW_COMMAND_OBSERVED ->
                PlaybackDebugLog.event("thumbar-command-call-window", command.toString())
            ACTION_QUEUED_COMMAND_OBSERVED ->
                PlaybackDebugLog.event("thumbar-command-queued", command.toString())
            ACTION_SUBCLASS_COMMAND_OBSERVED ->
                PlaybackDebugLog.event("thumbar-command-subclass", command.toString())
        }
    }

    private fun updateButtons() {
        val handle = hwnd ?: return
        val updated = runCatching {
            bridge?.lazer_taskbar_update(
                handle,
                iconPath("previous"),
                iconPath("play"),
                iconPath("pause"),
                iconPath("next"),
                tooltip("media_control.previous"),
                tooltip("media_control.play"),
                tooltip("media_control.pause"),
                tooltip("media_control.next"),
                if (isPlaying) 1 else 0,
            ) != 0
        }.getOrElse {
            PlaybackDebugLog.event("thumbar-update-error", it.playbackDebugSummary())
            false
        }
        if (!updated) PlaybackDebugLog.event("thumbar-update-failed")
    }

    private fun iconPath(name: String): WString =
        WString(WindowsMediaControlIcons.iconFile(name)?.absolutePath.orEmpty())

    private fun tooltip(key: String): WString = WString(tr(key))
}

private interface WindowsTaskbarBridge : Library {
    interface ActionCallback : Callback {
        fun callback(action: Int)
    }

    fun lazer_taskbar_install(
        hwnd: Pointer,
        previous: WString,
        play: WString,
        pause: WString,
        next: WString,
        previousTooltip: WString,
        playTooltip: WString,
        pauseTooltip: WString,
        nextTooltip: WString,
        isPlaying: Int,
        callback: ActionCallback,
    ): Int

    fun lazer_taskbar_update(
        hwnd: Pointer,
        previous: WString,
        play: WString,
        pause: WString,
        next: WString,
        previousTooltip: WString,
        playTooltip: WString,
        pauseTooltip: WString,
        nextTooltip: WString,
        isPlaying: Int,
    ): Int

    fun lazer_taskbar_remove(hwnd: Pointer)
}

private object WindowsTaskbarBridgeLoader {
    private const val RESOURCE_PATH = "native/windows-x64/lazer-taskbar-bridge.dll"

    fun load(): WindowsTaskbarBridge? = runCatching {
        val file = bridgeFile() ?: return null
        Native.load(file.absolutePath, WindowsTaskbarBridge::class.java)
    }.getOrNull()

    private fun bridgeFile(): File? {
        System.getProperty("lazer.taskbar.bridge")
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?.let { return it }
        return WindowsMediaControlIcons.appResourceFile(RESOURCE_PATH)
            ?: WindowsMediaControlIcons.classpathResourceFile(RESOURCE_PATH)
    }
}

private const val ACTION_PREVIOUS = 1
private const val ACTION_PLAY_PAUSE = 2
private const val ACTION_NEXT = 3
private const val ACTION_THEME_CHANGED = 4
private const val ACTION_BUTTONS_APPLIED = 5
private const val ACTION_BUTTONS_APPLY_FAILED = 6
private const val ACTION_SUBCLASS_INSTALLED = 7
private const val ACTION_SUBCLASS_INSTALL_FAILED = 8
private const val ACTION_PEER_WINDOWS_ATTACHED = 0x40000000
private const val ACTION_CALL_WINDOW_COMMAND_OBSERVED = 0x10000000
private const val ACTION_QUEUED_COMMAND_OBSERVED = 0x20000000
private const val ACTION_SUBCLASS_COMMAND_OBSERVED = 0x30000000
private const val COMMAND_SOURCE_MASK = 0xF0000000.toInt()
private const val COMMAND_ID_MASK = 0x0000FFFF
