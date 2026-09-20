package dev.naominet.lazer

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.awt.Window

/**
 * Windows-only native backdrop for desktop Liquid Glass.
 *
 * Uses the undocumented `user32!SetWindowCompositionAttribute` with an acrylic accent policy, which
 * asks DWM to blur the live operating-system content behind the window. This is the efficient,
 * real-time replacement for screen-capture-based approaches.
 */

private const val WCA_ACCENT_POLICY = 19
private const val ACCENT_DISABLED = 0
private const val ACCENT_ENABLE_ACRYLICBLURBEHIND = 4

/** Uses the resolved theme paper, retaining the tuned dark/light native tint opacity.
 * The native gradient format is AABBGGRR, unlike Compose's ARGB colour values.
 */
internal fun windowsAcrylicTint(backgroundArgb: Int, isDark: Boolean): Int =
    ((if (isDark) 0x66 else 0xCC) shl 24) or
        ((backgroundArgb and 0xFF) shl 16) or
        (backgroundArgb and 0xFF00) or
        ((backgroundArgb ushr 16) and 0xFF)

private interface User32Accent : Library {
    fun SetWindowCompositionAttribute(hwnd: Pointer, data: WindowCompositionAttributeData): Int
}

private interface DwmApi : Library {
    fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: Pointer, size: Int): Int
}

private const val DWMWA_SYSTEMBACKDROP_TYPE = 38
private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
private const val DWMSBT_NONE = 1

private val dwmApi: DwmApi? by lazy {
    if (!isWindowsDesktop()) null else runCatching { Native.load("dwmapi", DwmApi::class.java) }.getOrNull()
}

private fun Pointer.putInt(attribute: Int, value: Int) {
    val dwm = dwmApi ?: return
    com.sun.jna.Memory(4).use { buffer ->
        buffer.setInt(0, value)
        runCatching { dwm.DwmSetWindowAttribute(this, attribute, buffer, 4) }
    }
}

// JNA accesses these fields reflectively; their declaring classes must be JVM-public.
internal class AccentPolicy : Structure() {
    @JvmField var accentState: Int = 0
    @JvmField var accentFlags: Int = 0
    @JvmField var gradientColor: Int = 0
    @JvmField var animationId: Int = 0

    override fun getFieldOrder(): List<String> =
        listOf("accentState", "accentFlags", "gradientColor", "animationId")
}

internal class WindowsSizeT(value: Long = 0) : com.sun.jna.IntegerType(Native.SIZE_T_SIZE, value, true) {
    override fun toByte(): Byte = toLong().toByte()
    override fun toShort(): Short = toLong().toShort()
}

internal class WindowCompositionAttributeData : Structure() {
    @JvmField var attribute: Int = 0
    @JvmField var data: Pointer? = null
    @JvmField var sizeOfData: WindowsSizeT = WindowsSizeT()

    override fun getFieldOrder(): List<String> = listOf("attribute", "data", "sizeOfData")
}

private val user32Accent: User32Accent? by lazy {
    if (!isWindowsDesktop()) {
        null
    } else {
        runCatching { Native.load("user32", User32Accent::class.java) }.getOrNull()
    }
}

/** Returns true only when the requested native policy was applied successfully. */
internal fun applyWindowsAcrylic(
    window: Window,
    enabled: Boolean,
    isDark: Boolean,
    backgroundArgb: Int,
): Boolean {
    if (!isWindowsDesktop()) return false
    return runCatching {
        val user32 = user32Accent ?: return false
        val hwnd: Pointer = Native.getWindowPointer(window) ?: return false
        // Do not stack the system's own (untinted) backdrop under the app-tinted accent.
        hwnd.putInt(DWMWA_SYSTEMBACKDROP_TYPE, DWMSBT_NONE)
        // DWMWCP_DONOTROUND = 1: keep the frame square on desktop.
        hwnd.putInt(DWMWA_WINDOW_CORNER_PREFERENCE, 1)
        hwnd.putInt(DWMWA_USE_IMMERSIVE_DARK_MODE, if (enabled && isDark) 1 else 0)

        val accent = AccentPolicy().apply {
            accentState = if (enabled) ACCENT_ENABLE_ACRYLICBLURBEHIND else ACCENT_DISABLED
            accentFlags = if (enabled) 2 else 0
            gradientColor = if (enabled) windowsAcrylicTint(backgroundArgb, isDark) else 0
        }
        accent.write()
        val data = WindowCompositionAttributeData().apply {
            attribute = WCA_ACCENT_POLICY
            this.data = accent.pointer
            sizeOfData = WindowsSizeT(accent.size().toLong())
        }
        data.write()
        user32.SetWindowCompositionAttribute(hwnd, data) != 0
    }.getOrDefault(false)
}
