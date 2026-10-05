@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package dev.naominet.lazer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
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
    fun scanCode(chrome: IosScanChrome, onResult: (text: String?) -> Unit)

    fun pickImage(onPicked: (path: String?) -> Unit)

    fun pickLocalAudioFiles(onPicked: (LazerLocalAudioPickerResult) -> Unit)

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

    /** Downloads straight into [destination]; the path comes back, or null when nothing was written. */
    fun downloadToDestination(url: String, destination: String, onDone: (writtenPath: String?) -> Unit)

    /**
     * The audio side. Swift owns AVPlayer because Kotlin's view of its API is a translation, and a
     * translation is exactly what a player should not rest on; every decision about what plays next
     * stays in the shared queue.
     */
    fun playerLoad(url: String, startPlaying: Boolean, positionMillis: Long, generation: Long)

    /** Updates Swift's file-retention set; orphan pruning is allowed only for a validated cold-start snapshot. */
    fun playerUpdateLocalAudioQueue(uris: List<String>, queueStateAvailable: Boolean, canPruneOrphans: Boolean)

    /** True only for a readable file owned by the app's local-audio import cache. */
    fun playerLocalAudioFileExists(uri: String): Boolean

    fun playerPlay()

    fun playerPause()

    fun playerSeekTo(positionMillis: Long)

    fun playerRelease()

    fun playerPositionMillis(): Long

    fun playerDurationMillis(): Long

    /** How far the audio has arrived, in milliseconds from the start of the track. */
    fun playerBufferedMillis(): Long

    fun playerIsPlaying(): Boolean

    fun playerSetEndedHandler(handler: () -> Unit)

    /** Reports an AVPlayerItem failure tagged with the queue generation that opened it. */
    fun playerAttachFailureSink(sink: IosPlaybackFailureSink)

    /**
     * Hands Swift the object to call when a lock-screen or headset control is used. An object with
     * methods crosses the bridge cleanly where a callback taking a number does not.
     */
    fun playerAttachCommands(commands: IosPlayerCommands)

    /** Publishes the track to the system, which is what puts art and title on the lock screen. */
    fun playerUpdateNowPlaying(
        title: String,
        artist: String,
        album: String,
        coverUrl: String?,
        positionMillis: Long,
        durationMillis: Long,
        isPlaying: Boolean,
    )

    /**
     * Mirrors the two playback switches the shared settings screen offers: whether the system may
     * interrupt other audio, and whether it owns the transport at all.
     */
    fun playerSetAudioMode(exclusive: Boolean, systemMedia: Boolean)

    /** Sends active AVAudioSession hardware/route readback; it never describes the DAC's input. */
    fun playerAttachAudioSessionSink(sink: IosAudioSessionSink)

    /** Asks UIKit to watch, or stop watching, the system's edge swipe for the layer on top. */
    fun setBackGesture(enabled: Boolean, sink: IosBackGestureSink)

    fun setDarkStatusBar(dark: Boolean)
}

/** Scalar-only bridge callback so AVAudioSession output state can be shown by the shared UI. */
interface IosAudioSessionSink {
    fun didChangeAudioSession(
        sourceTrackSampleRateHz: Double,
        preferredSampleRateHz: Double,
        sampleRateHz: Double,
        outputChannelCount: Int,
        outputRouteName: String,
        outputPortTypes: String,
        ioBufferDurationSeconds: Double,
        active: Boolean,
        interrupted: Boolean,
        configurationError: String,
    )
}

/** Scalar-only callback for AVPlayerItem failures; method parameters bridge as native scalars. */
interface IosPlaybackFailureSink {
    fun didFailPlayback(generation: Long, detail: String)
}

/**
 * What UIKit reports while the system's own edge swipe drags the top layer aside. Progress is the
 * share of the screen the finger has covered, and `confirmed` fires only once the swipe is committed,
 * which is exactly the pair of answers the shared screens already drive their page transform from.
 */
interface IosBackGestureSink {
    fun reportProgress(progress: Float, fromLeftEdge: Boolean)

    fun confirmed()
}

/**
 * The colours the Android scan activity receives as intent extras, flattened to plain integers:
 * an inline [androidx.compose.ui.graphics.Color] has no shape left once it crosses the bridge.
 */
class IosScanChrome(
    val backgroundArgb: Int,
    val surfaceArgb: Int,
    val primaryArgb: Int,
    val primaryContainerArgb: Int,
    val onBackgroundArgb: Int,
    val onSurfaceVariantArgb: Int,
    val onPrimaryContainerArgb: Int,
    val title: String,
    val subtitle: String,
    val prompt: String,
    val backLabel: String,
) {
    internal constructor(theme: LazerScanTheme) : this(
        backgroundArgb = theme.background.toArgb(),
        surfaceArgb = theme.surface.toArgb(),
        primaryArgb = theme.primary.toArgb(),
        primaryContainerArgb = theme.primaryContainer.toArgb(),
        onBackgroundArgb = theme.onBackground.toArgb(),
        onSurfaceVariantArgb = theme.onSurfaceVariant.toArgb(),
        onPrimaryContainerArgb = theme.onPrimaryContainer.toArgb(),
        title = theme.title,
        subtitle = theme.description,
        prompt = theme.prompt,
        backLabel = theme.backLabel,
    )
}

/** The playback actions the system is allowed to ask the shared player for. */
interface IosPlayerCommands {
    fun play()

    fun pause()

    fun next()

    fun previous()

    fun seekToMillis(millis: Long)
}

/** iOS's answers to the screen-level asks of the shared interface. */
@OptIn(ExperimentalForeignApi::class)
internal class IosScreenHost(private val bridge: IosShellBridge) : LazerScreenHost {
    private val backSink = LazerIosBackSink()

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

    /**
     * UIKit's own edge swipe, handed to the same page transform Android drives from its predictive
     * back. Only the layer that owns back asks for it, and the sink is installed once per change of
     * ownership rather than once per recomposition.
     */
    @Composable
    override fun BackGesture(
        enabled: Boolean,
        onProgress: (progress: Float, edge: LazerSwipeEdge) -> Unit,
        onConfirmed: () -> Unit,
    ) {
        val latestProgress = rememberUpdatedState(onProgress)
        val latestConfirmed = rememberUpdatedState(onConfirmed)
        DisposableEffect(enabled) {
            backSink.onProgress = { progress, edge -> latestProgress.value(progress, edge) }
            backSink.onConfirmed = { latestConfirmed.value() }
            bridge.setBackGesture(enabled, backSink)
            onDispose {
                bridge.setBackGesture(false, backSink)
                backSink.onProgress = null
                backSink.onConfirmed = null
            }
        }
    }

    override fun shareText(text: String, title: String) = bridge.share(text, title)

    /**
     * AVPlayer gives back nothing but the file it is playing, so an honest spectrum would mean
     * decoding the same stream a second time while the listener waits for it to buffer. The bars
     * keep moving from the shared synthesizer, which is what Android draws with the switch off.
     */
    override val supportsAudioSpectrum: Boolean get() = false

    /** iOS mixes through an audio session, not a focus stack, so the focus wording would mislead. */
    override val usesSystemAudioFocus: Boolean get() = false

    override val supportsAudioSessionSnapshot: Boolean get() = true

    override val supportsLocalAudioFiles: Boolean get() = true

    override fun requestMicrophonePermission(onResult: (granted: Boolean) -> Unit) = onResult(false)

    override val microphoneGranted: Boolean get() = false

    override fun pickBackgroundImage(onPicked: (source: String?) -> Unit) = bridge.pickImage(onPicked)

    override fun pickLocalAudioFiles(onPicked: (LazerLocalAudioPickerResult) -> Unit) =
        bridge.pickLocalAudioFiles(onPicked)

    override fun pickExportDestination(suggestedName: String, onPicked: (target: String?) -> Unit) {
        // iOS writes into the app's own container first; the listener chooses the destination after.
        onPicked(NSTemporaryDirectory() + suggestedName)
    }

    override fun onFileExported(path: String) = bridge.presentSavedFile(path)

    override fun scanCode(theme: LazerScanTheme, onResult: (text: String?) -> Unit) =
        bridge.scanCode(IosScanChrome(theme), onResult)

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

/** Holds whichever pair of callbacks the layer that currently owns back last supplied. */
private class LazerIosBackSink : IosBackGestureSink {
    var onProgress: ((Float, LazerSwipeEdge) -> Unit)? = null
    var onConfirmed: (() -> Unit)? = null

    override fun reportProgress(progress: Float, fromLeftEdge: Boolean) {
        onProgress?.invoke(
            progress,
            if (fromLeftEdge) LazerSwipeEdge.Left else LazerSwipeEdge.Right,
        )
    }

    override fun confirmed() {
        onConfirmed?.invoke()
    }
}
