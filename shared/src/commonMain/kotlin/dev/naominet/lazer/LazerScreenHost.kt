package dev.naominet.lazer

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap

/** The colours the shared scan screen hands to the platform's own camera view. */
data class LazerScanTheme(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val primary: Color,
    val primaryContainer: Color,
    val onBackground: Color,
    val onSurfaceVariant: Color,
    val onPrimaryContainer: Color,
    val title: String,
    val description: String,
    val prompt: String,
    val backLabel: String,
)

/**
 * What the screens ask the platform for: a camera, a browser, a share sheet, a picker, the system
 * palette. Every layout decision stays in the shared screens; only these answers differ.
 */
interface LazerScreenHost {
    /** Whether the platform can colour itself from the wallpaper, which decides if the switch shows. */
    val supportsSystemPalette: Boolean

    fun dynamicColorScheme(isDark: Boolean): ColorScheme?

    /** Radius of the display's own corners, so sheets meet them instead of clipping. */
    val screenCornerRadiusPx: Float

    /** The system's own tap feedback switch; off here means off everywhere in the app. */
    val systemHapticsEnabled: Boolean

    val isDebugBuild: Boolean

    val deviceLabel: String

    val deviceFingerprint: String

    fun vibrate(durationMillis: Long)

    /**
     * Runs the platform's back gesture for the layer that currently owns back. [onProgress] reports
     * how far the swipe has travelled so the shared page can move with it, and [onConfirmed] fires
     * only when the gesture actually completes.
     */
    @Composable
    fun BackGesture(
        enabled: Boolean,
        onProgress: (progress: Float, edge: LazerSwipeEdge) -> Unit,
        onConfirmed: () -> Unit,
    )

    fun shareText(text: String, title: String)

    fun openSystemSoundSettings()

    fun requestMicrophonePermission(onResult: (granted: Boolean) -> Unit)

    /** Whether the microphone permission the audio-reactive switch needs is already held. */
    val microphoneGranted: Boolean

    fun pickBackgroundImage(onPicked: (source: String?) -> Unit)

    fun pickExportDestination(suggestedName: String, onPicked: (target: String?) -> Unit)

    fun scanCode(theme: LazerScanTheme, onResult: (text: String?) -> Unit)

    /** Hides the system bars while the big artwork is on screen. */
    fun setImmersive(immersive: Boolean)

    /** Matches the system bars to the theme, so icons stay readable on paper or on dark paper. */
    fun setStatusBarAppearance(isDark: Boolean)

    fun decodeImageBytes(bytes: ByteArray): ImageBitmap?

    /** Renders the current cover behind the launcher's own wallpaper, where the platform allows it. */
    fun setAlbumFlowBackground(enabled: Boolean, artwork: ImageBitmap?)
}

/**
 * The browser a QR sign-in needs, hosted by the platform but placed and sized by the shared sheet.
 * It stays on the login hosts and hands the session cookie to them, so the page can see the reader
 * is already signed in without ever leaving the app.
 */
typealias LazerAuthWebView = @Composable (
    url: String,
    sessionCookie: String,
    modifier: Modifier,
) -> Unit

/** Which edge a back gesture came from. */
enum class LazerSwipeEdge { Left, Right }

/** Reached from anywhere in the tree so a deep control can answer in the platform's own way. */
val LocalLazerScreenHost = androidx.compose.runtime.staticCompositionLocalOf<LazerScreenHost> {
    error("LazerApp must provide its platform host")
}

/** The platform's own browser view, placed and sized by the shared sign-in sheet. */
val LocalLazerAuthWebView = androidx.compose.runtime.staticCompositionLocalOf<LazerAuthWebView> {
    error("LazerApp must provide an auth web view")
}
