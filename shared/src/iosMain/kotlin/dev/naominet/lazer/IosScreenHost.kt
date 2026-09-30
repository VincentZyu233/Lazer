@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package dev.naominet.lazer

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.UIKit.UIDevice
import platform.UIKit.UISceneActivationStateForegroundActive
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.Foundation.NSTemporaryDirectory
import platform.UIKit.UIApplication
import platform.UIKit.UIView

/**
 * The sheets iOS only lets a native caller put on screen: a camera, a photo picker, a share panel,
 * a document export. Swift owns them because UIKit expects a real view controller to present from;
 * Kotlin owns every decision about what the listener sees around them.
 */
interface IosShellBridge {
    fun scanCode(onResult: (text: String?) -> Unit)

    fun pickImage(onPicked: (path: String?) -> Unit)

    fun share(text: String, title: String)

    /** Offers an already written file to the listener, who chooses where it goes. */
    fun presentSavedFile(path: String)

    /**
     * A configured browser for the sign-in page. Swift builds the view and Kotlin places it, so the
     * sheet around it stays the same sheet every other platform draws.
     */
    fun makeAuthWebView(url: String, sessionCookie: String): UIView

    /** Downloads into a file UIKit can write without a byte buffer crossing the bridge. */
    fun downloadToFile(url: String, onDone: (path: String?) -> Unit)

    /** Downloads straight into [destination], so no byte buffer has to cross the bridge. */
    fun downloadToDestination(url: String, destination: String, onDone: (written: Boolean) -> Unit)

    /**
     * The audio side. Swift owns AVPlayer because Kotlin's view of its API is a translation, and a
     * translation is exactly what a player should not rest on; every decision about what plays next
     * stays in the shared queue.
     */
    fun playerLoad(url: String, startPlaying: Boolean, positionMillis: Long)

    fun playerPlay()

    fun playerPause()

    fun playerSeekTo(positionMillis: Long)

    fun playerRelease()

    fun playerPositionMillis(): Long

    fun playerDurationMillis(): Long

    fun playerIsPlaying(): Boolean

    fun playerSetEndedHandler(handler: () -> Unit)

    fun requestMicrophoneAccess(onResult: (granted: Boolean) -> Unit)

    fun isMicrophoneGranted(): Boolean

    fun setDarkStatusBar(dark: Boolean)
}

/** iOS's answers to the screen-level asks of the shared interface. */
@OptIn(ExperimentalForeignApi::class)
internal class IosScreenHost(private val bridge: IosShellBridge) : LazerScreenHost {
    override val supportsSystemPalette: Boolean get() = false

    override fun dynamicColorScheme(isDark: Boolean): ColorScheme? = null

    /**
     * iOS exposes no corner radius, but a device whose display curves reports that curve as its
     * bottom safe-area inset, so pages round themselves to match where the display actually bends.
     */
    override val screenCornerRadiusPx: Float
        get() {
            val window = foregroundWindow() ?: return 0f
            val scale = window.screen.nativeScale.toFloat()
            return window.safeAreaInsets.useContents { bottom.toFloat() } * scale
        }

    override val systemHapticsEnabled: Boolean get() = true

    override val isDebugBuild: Boolean get() = kotlin.native.Platform.isDebugBinary

    override val deviceLabel: String get() = UIDevice.currentDevice.name

    override val deviceFingerprint: String
        get() = UIDevice.currentDevice.identifierForVendor?.UUIDString.orEmpty()

    /** iOS has no system back gesture, so the screens keep their own way out of every layer. */
    @Composable
    override fun BackGesture(
        enabled: Boolean,
        onProgress: (progress: Float, edge: LazerSwipeEdge) -> Unit,
        onConfirmed: () -> Unit,
    ) = Unit

    override fun shareText(text: String, title: String) = bridge.share(text, title)

    override fun requestMicrophonePermission(onResult: (granted: Boolean) -> Unit) =
        bridge.requestMicrophoneAccess(onResult)

    override val microphoneGranted: Boolean get() = bridge.isMicrophoneGranted()

    override fun pickBackgroundImage(onPicked: (source: String?) -> Unit) = bridge.pickImage(onPicked)

    override fun pickExportDestination(suggestedName: String, onPicked: (target: String?) -> Unit) {
        // iOS writes into the app's own container first; the listener chooses the destination after.
        onPicked(NSTemporaryDirectory() + suggestedName)
    }

    override fun onFileExported(path: String) = bridge.presentSavedFile(path)

    override fun scanCode(theme: LazerScanTheme, onResult: (text: String?) -> Unit) =
        bridge.scanCode(onResult)

    override fun setStatusBarAppearance(isDark: Boolean) = bridge.setDarkStatusBar(isDark)

    override fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()

    private fun foregroundWindow(): UIWindow? = UIApplication.sharedApplication.connectedScenes
        .filterIsInstance<UIWindowScene>()
        .firstOrNull { it.activationState == UISceneActivationStateForegroundActive }
        ?.windows
        ?.filterIsInstance<UIWindow>()
        ?.firstOrNull { it.isKeyWindow() }
}
