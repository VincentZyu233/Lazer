package dev.naominet.lazer

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.Size
import com.journeyapps.barcodescanner.ViewfinderView
import kotlin.math.min
import kotlin.math.roundToInt

/** Landscape QR scanner that carries the active Lazer palette into the camera surface. */
class LazerScanActivity : CaptureActivity() {
    private lateinit var scanner: DecoratedBarcodeView

    override fun initializeContent(): DecoratedBarcodeView {
        setContentView(R.layout.activity_lazer_scan)
        return findViewById<DecoratedBarcodeView>(R.id.zxing_barcode_scanner).also { scanner = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyFullscreenCameraChrome()
        applyCopy()
        applyPalette()
        configureViewfinder()
    }

    private fun applyFullscreenCameraChrome() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        val card = findViewById<View>(R.id.scan_side_panel)
        val prompt = findViewById<View>(R.id.scan_prompt)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.scan_root)) { root, insets ->
            val safe = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val density = resources.displayMetrics.density
            val cardMargin = (18f * density).roundToInt()
            card.layoutParams = (card.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                leftMargin = safe.left + cardMargin
                topMargin = safe.top + cardMargin
                rightMargin = safe.right + cardMargin
            }
            prompt.layoutParams = (prompt.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                leftMargin = safe.left + (20f * density).roundToInt()
                rightMargin = safe.right + (20f * density).roundToInt()
                bottomMargin = safe.bottom + (20f * density).roundToInt()
            }
            root.setPadding(0, 0, 0, 0)
            insets
        }
        ViewCompat.requestApplyInsets(findViewById(R.id.scan_root))
    }

    private fun applyCopy() {
        title = intent.stringExtra(EXTRA_TITLE, "Lazer")
        findViewById<TextView>(R.id.scan_title).text = intent.stringExtra(EXTRA_TITLE, "")
        findViewById<TextView>(R.id.scan_description).text = intent.stringExtra(EXTRA_DESCRIPTION, "")
        findViewById<TextView>(R.id.scan_prompt).text = intent.stringExtra(EXTRA_PROMPT, "")
        findViewById<ImageButton>(R.id.scan_close).apply {
            contentDescription = intent.stringExtra(EXTRA_BACK_DESCRIPTION, "")
            setOnClickListener { finish() }
        }
    }

    private fun applyPalette() {
        val dark = intent.getBooleanExtra(EXTRA_DARK_THEME, false)
        val background = intent.getIntExtra(
            EXTRA_BACKGROUND_COLOR,
            if (dark) 0xFF1D282D.toInt() else 0xFFF7F5EF.toInt(),
        )
        val surface = intent.getIntExtra(
            EXTRA_SURFACE_COLOR,
            if (dark) 0xFF253238.toInt() else 0xFFFCFAF5.toInt(),
        )
        val primary = intent.getIntExtra(
            EXTRA_PRIMARY_COLOR,
            if (dark) 0xFF91BED3.toInt() else 0xFF5F91AC.toInt(),
        )
        val primaryContainer = intent.getIntExtra(
            EXTRA_PRIMARY_CONTAINER_COLOR,
            if (dark) 0xFF365F73.toInt() else 0xFFA9C8D8.toInt(),
        )
        val onBackground = intent.getIntExtra(
            EXTRA_ON_BACKGROUND_COLOR,
            if (dark) 0xFFE7ECEB.toInt() else 0xFF26363D.toInt(),
        )
        val onSurfaceVariant = intent.getIntExtra(
            EXTRA_ON_SURFACE_VARIANT_COLOR,
            if (dark) 0xFFC3CFD1.toInt() else 0xFF627279.toInt(),
        )
        val onPrimaryContainer = intent.getIntExtra(
            EXTRA_ON_PRIMARY_CONTAINER_COLOR,
            if (dark) 0xFFE7ECEB.toInt() else 0xFF26363D.toInt(),
        )

        findViewById<View>(R.id.scan_root).setBackgroundColor(Color.BLACK)
        findViewById<View>(R.id.scan_side_panel).apply {
            this.background = roundedRect(ColorUtils.setAlphaComponent(background, 247), 22f)
            elevation = 12f * resources.displayMetrics.density
        }
        findViewById<TextView>(R.id.scan_title).setTextColor(onBackground)
        findViewById<TextView>(R.id.scan_description).setTextColor(onSurfaceVariant)
        findViewById<ImageView>(R.id.scan_mark).apply {
            imageTintList = ColorStateList.valueOf(onPrimaryContainer)
            this.background = roundedRect(primaryContainer, 15f)
        }
        findViewById<TextView>(R.id.scan_prompt).apply {
            setTextColor(if (ColorUtils.calculateLuminance(primary) > 0.45) Color.BLACK else Color.WHITE)
            this.background = roundedRect(ColorUtils.setAlphaComponent(primary, 232), 100f)
        }
        findViewById<ImageButton>(R.id.scan_close).apply {
            imageTintList = ColorStateList.valueOf(onBackground)
            val resting = roundedRect(surface, 100f)
            val ripple = ColorStateList.valueOf(ColorUtils.setAlphaComponent(primary, 70))
            this.background = RippleDrawable(ripple, resting, null)
        }
        (scanner.viewFinder as? LazerViewfinderView)?.apply {
            cornerColor = primary
            setMaskColor(Color.TRANSPARENT)
            setLaserVisibility(false)
        }
    }

    private fun configureViewfinder() {
        val root = findViewById<View>(R.id.scan_root)
        val card = findViewById<View>(R.id.scan_side_panel)
        root.post {
            val maximumWidth = (440f * resources.displayMetrics.density).roundToInt()
            card.layoutParams = (card.layoutParams as android.widget.FrameLayout.LayoutParams).apply {
                width = min((root.width - leftMargin - rightMargin).coerceAtLeast(1), maximumWidth)
            }
        }
        scanner.barcodeView.post {
            val frame = scanFrameSizePx(
                previewWidth = scanner.barcodeView.width,
                previewHeight = scanner.barcodeView.height,
                density = resources.displayMetrics.density,
            )
            scanner.barcodeView.framingRectSize = Size(frame, frame)
        }
    }

    private fun roundedRect(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radiusDp * resources.displayMetrics.density
    }

    private fun android.content.Intent.stringExtra(key: String, fallback: String): String =
        getStringExtra(key)?.takeIf(String::isNotBlank) ?: fallback

    companion object {
        const val EXTRA_TITLE = "lazer.scan.title"
        const val EXTRA_DESCRIPTION = "lazer.scan.description"
        const val EXTRA_PROMPT = "lazer.scan.prompt"
        const val EXTRA_BACK_DESCRIPTION = "lazer.scan.back_description"
        const val EXTRA_DARK_THEME = "lazer.scan.dark_theme"
        const val EXTRA_BACKGROUND_COLOR = "lazer.scan.background_color"
        const val EXTRA_SURFACE_COLOR = "lazer.scan.surface_color"
        const val EXTRA_PRIMARY_COLOR = "lazer.scan.primary_color"
        const val EXTRA_PRIMARY_CONTAINER_COLOR = "lazer.scan.primary_container_color"
        const val EXTRA_ON_BACKGROUND_COLOR = "lazer.scan.on_background_color"
        const val EXTRA_ON_SURFACE_VARIANT_COLOR = "lazer.scan.on_surface_variant_color"
        const val EXTRA_ON_PRIMARY_CONTAINER_COLOR = "lazer.scan.on_primary_container_color"
    }
}

internal fun scanFrameSizePx(previewWidth: Int, previewHeight: Int, density: Float): Int {
    val shorterSide = min(previewWidth, previewHeight).coerceAtLeast(1)
    val minimum = min(shorterSide, (190f * density).roundToInt())
    val maximum = min(shorterSide, (330f * density).roundToInt()).coerceAtLeast(minimum)
    return (shorterSide * 0.62f).roundToInt().coerceIn(minimum, maximum)
}

/** Draws only the corner guides; the stock mask is transparent to avoid four dark seam lines. */
class LazerViewfinderView(
    context: android.content.Context,
    attrs: android.util.AttributeSet,
) : ViewfinderView(context, attrs) {
    var cornerColor: Int = 0xFF91BED3.toInt()
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frame = framingRect ?: return
        val density = resources.displayMetrics.density
        val stroke = 4f * density
        val length = min(frame.width(), frame.height()) * 0.12f
        val left = frame.left + stroke / 2f
        val top = frame.top + stroke / 2f
        val right = frame.right - stroke / 2f
        val bottom = frame.bottom - stroke / 2f

        paint.color = cornerColor
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.strokeCap = Paint.Cap.ROUND

        canvas.drawLine(left, top, left + length, top, paint)
        canvas.drawLine(left, top, left, top + length, paint)
        canvas.drawLine(right, top, right - length, top, paint)
        canvas.drawLine(right, top, right, top + length, paint)
        canvas.drawLine(left, bottom, left + length, bottom, paint)
        canvas.drawLine(left, bottom, left, bottom - length, paint)
        canvas.drawLine(right, bottom, right - length, bottom, paint)
        canvas.drawLine(right, bottom, right, bottom - length, paint)
    }
}
