package dev.naominet.lazer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.os.Build
import android.provider.Settings
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.BackEventCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Android's answers to the screen-level asks of the shared interface. The registry callbacks are
 * registered once for the activity's life and hand their result to whichever request is waiting,
 * so the shared sheets never learn that a launcher exists.
 */
class AndroidScreenHost(private val activity: ComponentActivity) : LazerScreenHost {
    private val registry = activity.activityResultRegistry
    private val imageLauncher = registry.register(KEY_BACKGROUND, ActivityResultContracts.GetContent()) { uri ->
        pendingImage?.invoke(uri?.toString())
        pendingImage = null
    }
    private val exportLauncher = registry.register(KEY_EXPORT, ActivityResultContracts.CreateDocument("image/jpeg")) { uri ->
        pendingTarget?.invoke(uri?.toString())
        pendingTarget = null
    }
    private val scanLauncher = registry.register(KEY_SCAN, ScanContract()) { result ->
        pendingScan?.invoke(result.contents)
        pendingScan = null
    }
    private val microphoneLauncher = registry.register(KEY_MICROPHONE, ActivityResultContracts.RequestPermission()) { granted ->
        pendingMicrophone?.invoke(granted)
        pendingMicrophone = null
    }
    private var pendingImage: ((String?) -> Unit)? = null
    private var pendingTarget: ((String?) -> Unit)? = null
    private var pendingScan: ((String?) -> Unit)? = null
    private var pendingMicrophone: ((Boolean) -> Unit)? = null

    override val supportsSystemPalette: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    override fun dynamicColorScheme(isDark: Boolean): ColorScheme? =
        if (supportsSystemPalette) {
            if (isDark) dynamicDarkColorScheme(activity) else dynamicLightColorScheme(activity)
        } else {
            null
        }

    // Insets only exist once the window has been laid out; the caller recomposes with the window.
    override val screenCornerRadiusPx: Float
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val insets = activity.window.decorView.rootWindowInsets
            intArrayOf(
                android.view.RoundedCorner.POSITION_TOP_LEFT,
                android.view.RoundedCorner.POSITION_TOP_RIGHT,
                android.view.RoundedCorner.POSITION_BOTTOM_LEFT,
                android.view.RoundedCorner.POSITION_BOTTOM_RIGHT,
            ).maxOfOrNull { position -> insets?.getRoundedCorner(position)?.radius?.toFloat() ?: 0f }
                ?: 0f
        } else {
            0f
        }

    @Suppress("DEPRECATION")
    override val systemHapticsEnabled: Boolean
        get() = Settings.System.getInt(
            activity.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1,
        ) != 0

    override val isDebugBuild: Boolean
        get() = (activity.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    override val deviceLabel: String get() = Build.MODEL.orEmpty()

    override val deviceFingerprint: String get() = Build.FINGERPRINT.orEmpty()

    override val supportsAudioSpectrum: Boolean get() = true

    override val usesSystemAudioFocus: Boolean get() = true

    override val microphoneGranted: Boolean
        get() = activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    @Composable
    override fun BackGesture(
        enabled: Boolean,
        onProgress: (progress: Float, edge: LazerSwipeEdge) -> Unit,
        onConfirmed: () -> Unit,
    ) {
        PredictiveBackHandler(enabled = enabled) { events ->
            try {
                events.collect { event ->
                    onProgress(
                        event.progress,
                        if (event.swipeEdge == BackEventCompat.EDGE_RIGHT) {
                            LazerSwipeEdge.Right
                        } else {
                            LazerSwipeEdge.Left
                        },
                    )
                }
                onConfirmed()
            } finally {
                onProgress(0f, LazerSwipeEdge.Left)
            }
        }
    }

    override fun shareText(text: String, title: String) {
        activity.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                },
                title,
            ),
        )
    }

    override fun requestMicrophonePermission(onResult: (granted: Boolean) -> Unit) {
        pendingMicrophone = onResult
        microphoneLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun pickBackgroundImage(onPicked: (source: String?) -> Unit) {
        pendingImage = onPicked
        imageLauncher.launch("image/*")
    }

    override fun pickExportDestination(suggestedName: String, onPicked: (target: String?) -> Unit) {
        pendingTarget = onPicked
        exportLauncher.launch(suggestedName)
    }

    override fun scanCode(theme: LazerScanTheme, onResult: (text: String?) -> Unit) {
        pendingScan = onResult
        scanLauncher.launch(
            ScanOptions().apply {
                setBeepEnabled(false)
                setCaptureActivity(LazerScanActivity::class.java)
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setOrientationLocked(false)
                setPrompt(theme.prompt)
                addExtra(LazerScanActivity.EXTRA_TITLE, theme.title)
                addExtra(LazerScanActivity.EXTRA_DESCRIPTION, theme.description)
                addExtra(LazerScanActivity.EXTRA_PROMPT, theme.prompt)
                addExtra(LazerScanActivity.EXTRA_BACK_DESCRIPTION, theme.backLabel)
                addExtra(LazerScanActivity.EXTRA_DARK_THEME, theme.isDark)
                addExtra(LazerScanActivity.EXTRA_BACKGROUND_COLOR, theme.background.toArgb())
                addExtra(LazerScanActivity.EXTRA_SURFACE_COLOR, theme.surface.toArgb())
                addExtra(LazerScanActivity.EXTRA_PRIMARY_COLOR, theme.primary.toArgb())
                addExtra(LazerScanActivity.EXTRA_PRIMARY_CONTAINER_COLOR, theme.primaryContainer.toArgb())
                addExtra(LazerScanActivity.EXTRA_ON_BACKGROUND_COLOR, theme.onBackground.toArgb())
                addExtra(LazerScanActivity.EXTRA_ON_SURFACE_VARIANT_COLOR, theme.onSurfaceVariant.toArgb())
                addExtra(LazerScanActivity.EXTRA_ON_PRIMARY_CONTAINER_COLOR, theme.onPrimaryContainer.toArgb())
            },
        )
    }

    override fun setStatusBarAppearance(isDark: Boolean) {
        val view = activity.window.decorView
        WindowCompat.getInsetsController(activity.window, view).apply {
            isAppearanceLightStatusBars = !isDark
            isAppearanceLightNavigationBars = !isDark
        }
    }

    override fun decodeImageBytes(bytes: ByteArray): ImageBitmap? =
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()

    private companion object {
        const val KEY_BACKGROUND = "lazer.background"
        const val KEY_EXPORT = "lazer.export"
        const val KEY_SCAN = "lazer.scan"
        const val KEY_MICROPHONE = "lazer.microphone"
    }
}

/** The sign-in browser: a WebView pinned to the login hosts, with the session carried into it. */
@Composable
fun AndroidNeteaseAuthWebView(
    url: String,
    sessionCookie: String,
    modifier: Modifier,
) {
    val context = LocalView.current.context
    androidx.compose.ui.viewinterop.AndroidView(
        factory = {
            WebView(context).apply {
                val authorizationWebView = this
                setBackgroundColor(android.graphics.Color.WHITE)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    setAcceptThirdPartyCookies(authorizationWebView, false)
                    installNeteaseSessionCookies(sessionCookie)
                    flush()
                }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean = !isAllowedNeteaseWebHost(request.url.host)
                }
                loadUrl(url)
            }
        },
        onRelease = { view ->
            view.stopLoading()
            view.destroy()
        },
        modifier = modifier,
    )
}

private fun CookieManager.installNeteaseSessionCookies(sessionCookie: String) {
    val allowed = setOf("MUSIC_U", "MUSIC_A", "NMTID", "deviceId", "__csrf")
    sessionCookie.split(';').forEach { field ->
        val name = field.substringBefore('=').trim()
        val value = field.substringAfter('=', "").trim()
        if (name in allowed && value.isNotBlank()) {
            setCookie(
                "https://music.163.com",
                "$name=$value; Domain=.music.163.com; Path=/; Secure; SameSite=Lax",
            )
        }
    }
}

private fun isAllowedNeteaseWebHost(host: String?): Boolean =
    host.equals("music.163.com", ignoreCase = true) ||
        host.equals("st.music.163.com", ignoreCase = true)

