@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.naominet.lazer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.naominet.lazer.gateway.GatewaySessionStore
import platform.Foundation.NSUserDefaults

internal const val IOS_STYLE_KEY = "lazer.ios.style"
internal const val IOS_NATIVE_GLASS_KEY = "lazer.ios.nativeLiquidGlass"
internal const val IOS_DESTINATION_KEY = "lazer.ios.destination"
private const val IOS_DARK_MODE_KEY = "lazer.ios.darkMode"
private const val IOS_SESSION_COOKIE_KEY = "lazer.ios.gatewayCookie"

internal enum class IOSDestination {
    HOME,
    SEARCH,
    LIBRARY,
    SETTINGS,
}

/**
 * The SwiftUI shell and Compose content deliberately share UserDefaults keys. Compose owns the
 * screen content and settings; SwiftUI only owns the optional native Liquid Glass navigation.
 */
internal class IOSSettingsStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) {
    var isDark by mutableStateOf(defaults.boolForKey(IOS_DARK_MODE_KEY))
        private set

    var style by mutableStateOf(parseLazerStyle(defaults.stringForKey(IOS_STYLE_KEY)))
        private set

    var usesNativeLiquidGlass by mutableStateOf(defaults.boolForKey(IOS_NATIVE_GLASS_KEY))
        private set

    var destination by mutableStateOf(parseDestination(defaults.stringForKey(IOS_DESTINATION_KEY)))
        private set

    fun setDark(value: Boolean) {
        isDark = value
        defaults.setBool(value, IOS_DARK_MODE_KEY)
    }

    fun setStyle(value: LazerStyle) {
        style = value
        defaults.setObject(value.name, IOS_STYLE_KEY)
    }

    fun setNativeLiquidGlass(value: Boolean) {
        usesNativeLiquidGlass = value
        defaults.setBool(value, IOS_NATIVE_GLASS_KEY)
    }

    fun setDestination(value: IOSDestination) {
        destination = value
        defaults.setObject(value.name, IOS_DESTINATION_KEY)
    }

    /** Pulls changes made by the native SwiftUI navigation back into Compose. */
    fun syncFromNativeShell() {
        destination = parseDestination(defaults.stringForKey(IOS_DESTINATION_KEY))
        usesNativeLiquidGlass = defaults.boolForKey(IOS_NATIVE_GLASS_KEY)
        style = parseLazerStyle(defaults.stringForKey(IOS_STYLE_KEY))
    }
}

internal class IOSGatewaySessionStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : GatewaySessionStore {
    override var cookie: String?
        get() = defaults.stringForKey(IOS_SESSION_COOKIE_KEY)
        set(value) {
            if (value == null) {
                defaults.removeObjectForKey(IOS_SESSION_COOKIE_KEY)
            } else {
                defaults.setObject(value, IOS_SESSION_COOKIE_KEY)
            }
        }
}

private fun parseDestination(value: String?): IOSDestination =
    IOSDestination.entries.firstOrNull { it.name == value } ?: IOSDestination.HOME
