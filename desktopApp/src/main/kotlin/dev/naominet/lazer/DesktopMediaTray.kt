package dev.naominet.lazer

import java.awt.Image
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.TrayIcon
import java.awt.Toolkit
import java.io.Closeable
import javax.imageio.ImageIO

/**
 * 系统托盘图标 + 右键媒体控制菜单(上一首 / 播放·暂停 / 下一首)。
 *
 * 跨平台:基于 AWT [SystemTray],Windows 与 Linux(KDE/GNOME/LXQt)通用。菜单项顺序与文案来自
 * 共享的 [mediaControlItems] 蓝图,保证与其它平台控件一致。播放/暂停项随状态刷新标签。
 *
 * 集成失败(平台不支持托盘、图标缺失等)绝不能中断播放:全程 [runCatching] 兜底。
 */
internal class DesktopMediaTray(
    private val onAction: (MediaControlAction) -> Unit,
    private val onShowWindow: () -> Unit,
    private val onQuit: () -> Unit,
) : Closeable {
    private var trayIcon: TrayIcon? = null
    private var playPauseItem: MenuItem? = null
    private var lastIsPlaying: Boolean? = null

    fun start() {
        if (trayIcon != null) return
        runCatching {
            if (!SystemTray.isSupported()) {
                PlaybackDebugLog.event("tray-unsupported")
                return
            }
            val image = loadTrayImage() ?: run {
                PlaybackDebugLog.event("tray-icon-missing")
                return
            }
            val menu = buildMenu()
            val icon = TrayIcon(image, "Lazer", menu).apply {
                isImageAutoSize = true
                // 双击/主点击托盘图标:唤起窗口
                addActionListener { runCatching { onShowWindow() } }
            }
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            PlaybackDebugLog.event("tray-attached")
        }.onFailure { error ->
            PlaybackDebugLog.event("tray-attach-error", error.playbackDebugSummary())
        }
    }

    /**
     * 刷新播放/暂停菜单项文案。仅在状态变化时改动,避免无谓刷新。
     * @param isPlaying 当前是否正在播放
     */
    fun update(isPlaying: Boolean) {
        if (trayIcon == null || lastIsPlaying == isPlaying) return
        runCatching {
            val items = mediaControlItems(isPlaying, ::tr)
            val playPause = items.first { it.action == MediaControlAction.PlayPause }
            playPauseItem?.label = playPause.label
            lastIsPlaying = isPlaying
        }.onFailure { error ->
            PlaybackDebugLog.event("tray-update-error", error.playbackDebugSummary())
        }
    }

    private fun buildMenu(): PopupMenu {
        val menu = PopupMenu()
        val open = MenuItem(tr("tray.open"))
        open.addActionListener { runCatching { onShowWindow() } }
        menu.add(open)
        menu.addSeparator()

        mediaControlItems(isPlaying = false, tr = ::tr).forEach { item ->
            val menuItem = MenuItem(item.label)
            menuItem.addActionListener { runCatching { onAction(item.action) } }
            if (item.action == MediaControlAction.PlayPause) playPauseItem = menuItem
            menu.add(menuItem)
        }
        menu.addSeparator()
        val quit = MenuItem(tr("tray.quit"))
        quit.addActionListener { runCatching { onQuit() } }
        menu.add(quit)
        return menu
    }

    private fun loadTrayImage(): Image? = runCatching {
        val stream = javaClass.classLoader.getResourceAsStream("icon.png") ?: return null
        stream.use { ImageIO.read(it) }
    }.getOrNull()

    override fun close() {
        val icon = trayIcon ?: return
        runCatching { SystemTray.getSystemTray().remove(icon) }
        trayIcon = null
        playPauseItem = null
        lastIsPlaying = null
    }
}
