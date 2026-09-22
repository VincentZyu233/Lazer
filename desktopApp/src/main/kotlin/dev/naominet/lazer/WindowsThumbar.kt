package dev.naominet.lazer

import com.sun.jna.CallbackReference
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import java.awt.Window

/**
 * Windows 任务栏缩略图工具栏(Thumbnail Toolbar / Thumbar)。
 *
 * 鼠标悬停任务栏图标弹出缩略图预览时,下方显示"上一首 / 播放·暂停 / 下一首"三枚按钮。
 * 通过 COM 接口 `ITaskbarList3::ThumbBarAddButtons` / `ThumbBarUpdateButtons` 实现,
 * 按钮点击经窗口过程的 `WM_COMMAND` 回调分发到播放器。
 *
 * 仅 Windows 生效;非 Windows 平台或原生调用失败时静默降级,绝不影响播放。
 * 图标资源来自 desktopApp 资源目录 `media-control/<name>.ico`。
 */

private const val WM_COMMAND = 0x0111
private const val THBN_CLICKED = 0x1800
private const val TASKBAR_BUTTON_CREATED = "TaskbarButtonCreated"

// THUMBBUTTON.dwMask 位
private const val THB_BITMAP = 0x1
private const val THB_ICON = 0x2
private const val THB_TOOLTIP = 0x4
private const val THB_FLAGS = 0x8

// THUMBBUTTON.dwFlags 位
private const val THBF_ENABLED = 0x0
private const val THBF_DISMISSONCLICK = 0x2

// 每个按钮的命令 ID(WM_COMMAND 的低位字返回该值)
internal const val THUMBAR_CMD_PREVIOUS = 0x1001
internal const val THUMBAR_CMD_PLAYPAUSE = 0x1002
internal const val THUMBAR_CMD_NEXT = 0x1003

internal fun thumbarActionFromCommand(commandId: Int): MediaControlAction? = when (commandId) {
    THUMBAR_CMD_PREVIOUS -> MediaControlAction.Previous
    THUMBAR_CMD_PLAYPAUSE -> MediaControlAction.PlayPause
    THUMBAR_CMD_NEXT -> MediaControlAction.Next
    else -> null
}

/**
 * 管理某个窗口的 Thumbar。生命周期与窗口一致:[install] 一次,[updatePlayState] 随播放状态刷新。
 */
internal class WindowsThumbar(
    private val onAction: (MediaControlAction) -> Unit,
) {
    private var hwnd: HWND? = null
    private var installed = false
    private var isPlaying = false
    private var wndProcInstalled = false
    private var taskbarButtonCreatedMessage = 0
    private var previousWndProc: Pointer? = null
    private var hookedHwnd: HWND? = null

    /**
     * 在给定窗口上安装 Thumbar 按钮。可安全重复调用(窗口从最小化/托盘恢复后系统会清空按钮,需重装)。
     * @param window Compose 的 AWT 窗口
     * @param playing 当前是否正在播放,决定中间按钮显示播放还是暂停图标
     */
    fun install(window: Window, playing: Boolean) {
        if (!isWindowsDesktop()) return
        isPlaying = playing
        runCatching {
            val pointer: Pointer = Native.getWindowPointer(window) ?: return
            val handle = HWND(pointer)
            hwnd = handle
            if (!wndProcInstalled) {
                hookWndProc(handle)
                wndProcInstalled = true
            }
            installed = WindowsThumbarNative.ensureButtons(handle, isPlaying)
            PlaybackDebugLog.event(if (installed) "thumbar-installed" else "thumbar-install-pending")
        }.onFailure { error ->
            PlaybackDebugLog.event("thumbar-install-error", error.playbackDebugSummary())
        }
    }

    /** Restores the Compose/Skiko window procedure before the window is disposed. */
    fun close() {
        val handle = hookedHwnd ?: return
        val previous = previousWndProc ?: return
        runCatching {
            User32.INSTANCE.SetWindowLongPtr(handle, WinUser.GWL_WNDPROC, previous)
        }.onFailure { error ->
            PlaybackDebugLog.event("thumbar-unhook-error", error.playbackDebugSummary())
        }
        retainedCallback = null
        previousWndProc = null
        hookedHwnd = null
        hwnd = null
        installed = false
        wndProcInstalled = false
    }

    /** 播放状态变化时刷新中间按钮图标(播放 <-> 暂停)。 */
    fun updatePlayState(playing: Boolean) {
        if (!installed || isPlaying == playing) return
        isPlaying = playing
        val handle = hwnd ?: return
        runCatching {
            WindowsThumbarNative.updateButtons(handle, isPlaying)
        }.onFailure { error ->
            PlaybackDebugLog.event("thumbar-update-error", error.playbackDebugSummary())
        }
    }

    private fun hookWndProc(handle: HWND) {
        val user32 = User32.INSTANCE
        // 保存原窗口过程指针,拦截任务栏消息,其余转发原过程。
        val prevProc: Pointer = user32.GetWindowLongPtr(handle, WinUser.GWL_WNDPROC).toPointer()
        previousWndProc = prevProc
        hookedHwnd = handle
        taskbarButtonCreatedMessage = user32.RegisterWindowMessage(TASKBAR_BUTTON_CREATED)
        val callback = object : WinUser.WindowProc {
            override fun callback(hWnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT {
                if (uMsg == taskbarButtonCreatedMessage) {
                    installed = WindowsThumbarNative.ensureButtons(hWnd, isPlaying)
                    PlaybackDebugLog.event(
                        if (installed) "thumbar-taskbar-button-created" else "thumbar-taskbar-button-pending",
                    )
                }
                if (uMsg == WM_COMMAND) {
                    val hiword = (wParam.toInt() ushr 16) and 0xFFFF
                    val loword = wParam.toInt() and 0xFFFF
                    if (hiword == THBN_CLICKED) {
                        thumbarActionFromCommand(loword)?.let { action ->
                            runCatching { onAction(action) }
                            return LRESULT(0)
                        }
                    }
                }
                return user32.CallWindowProc(prevProc, hWnd, uMsg, wParam, lParam)
            }
        }
        // 保持回调强引用,防止被 GC(否则原生回调时崩溃)
        retainedCallback = callback
        val callbackPointer = CallbackReference.getFunctionPointer(callback)
        user32.SetWindowLongPtr(handle, WinUser.GWL_WNDPROC, callbackPointer)
    }

    private var retainedCallback: WinUser.WindowProc? = null
}
