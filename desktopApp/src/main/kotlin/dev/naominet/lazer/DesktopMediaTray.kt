package dev.naominet.lazer

import java.awt.GraphicsConfiguration
import java.awt.GraphicsDevice
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.awt.Point
import java.awt.Rectangle
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.Closeable
import javax.imageio.ImageIO

/**
 * 托盘右键请求落点:光标所在的屏幕坐标(像素)与该屏幕去掉任务栏后的可用区域,
 * 供自绘菜单把自身摆在光标左上方且不越界。
 */
internal data class DesktopTrayMenuAnchor(
    val point: Point,
    val workArea: Rectangle,
)

/**
 * 系统托盘图标。左键唤起主窗口;右键只上报一次菜单请求,菜单本身由应用自绘,
 * 与顶栏账号菜单共用同一套纸面样式,不再使用 AWT 原生 PopupMenu。
 *
 * 跨平台:基于 AWT [SystemTray],Windows 与 Linux(KDE/GNOME/LXQt)通用。
 * 集成失败(平台不支持托盘、图标缺失等)绝不能中断播放:全程 [runCatching] 兜底。
 */
internal class DesktopMediaTray(
    private val onMenuRequest: (DesktopTrayMenuAnchor) -> Unit,
    private val onShowWindow: () -> Unit,
) : Closeable {
    private var trayIcon: TrayIcon? = null
    private var lastMenuRequestAt = 0L

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
            val icon = TrayIcon(image, "Lazer").apply {
                isImageAutoSize = true
                addMouseListener(mouseHandler)
            }
            SystemTray.getSystemTray().add(icon)
            trayIcon = icon
            PlaybackDebugLog.event("tray-attached")
        }.onFailure { error ->
            PlaybackDebugLog.event("tray-attach-error", error.playbackDebugSummary())
        }
    }

    fun showNotification(title: String, message: String) {
        runCatching { trayIcon?.displayMessage(title, message, TrayIcon.MessageType.WARNING) }
            .onFailure { error -> PlaybackDebugLog.event("tray-notification-error", error.playbackDebugSummary()) }
    }

    override fun close() {
        val icon = trayIcon ?: return
        runCatching { SystemTray.getSystemTray().remove(icon) }
        trayIcon = null
    }

    // Windows 把 popup trigger 放在 press 上,GTK/macOS 放在 release 上,两处都要接住。
    private val mouseHandler = object : MouseAdapter() {
        override fun mousePressed(event: MouseEvent) = handle(event)

        override fun mouseReleased(event: MouseEvent) = handle(event)
    }

    private fun handle(event: MouseEvent) {
        if (event.isPopupTrigger) {
            requestMenu(event.locationOnScreen)
        } else if (event.id == MouseEvent.MOUSE_RELEASED && event.button == MouseEvent.BUTTON1) {
            runCatching(onShowWindow)
        }
    }

    // 有的平台 press 与 release 都算 popup trigger,一次右键会上报两回;按下节流才敢让菜单做开合。
    private fun requestMenu(point: Point) {
        val now = System.currentTimeMillis()
        if (now - lastMenuRequestAt < MENU_REQUEST_DEBOUNCE_MILLIS) return
        lastMenuRequestAt = now
        PlaybackDebugLog.event("tray-menu-request", "${point.x},${point.y}")
        menuAnchor(point)?.let { onMenuRequest(it) }
    }

    private fun menuAnchor(point: Point): DesktopTrayMenuAnchor? = runCatching {
        val screen = screenContaining(point)
        DesktopTrayMenuAnchor(
            point = point,
            workArea = workAreaBounds(screen.bounds, Toolkit.getDefaultToolkit().getScreenInsets(screen)),
        )
    }.getOrNull()

    /** 光标可能不在应用所在的屏幕上,按坐标找到它真正落在哪块屏幕。 */
    private fun screenContaining(point: Point): GraphicsConfiguration {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        return environment.screenDevices
            .map(GraphicsDevice::getDefaultConfiguration)
            .firstOrNull { it.bounds.contains(point) }
            ?: environment.defaultScreenDevice.defaultConfiguration
    }

    private fun loadTrayImage(): Image? = runCatching {
        val stream = javaClass.classLoader.getResourceAsStream("icon.png") ?: return null
        stream.use { ImageIO.read(it) }
    }.getOrNull()
}

private const val MENU_REQUEST_DEBOUNCE_MILLIS = 250L
