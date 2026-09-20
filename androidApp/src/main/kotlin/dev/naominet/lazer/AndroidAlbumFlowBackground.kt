package dev.naominet.lazer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val ArtworkSeedCache = android.util.LruCache<String, Color>(24)
private val ArtworkSeedMutex = Mutex()
private val FluidPaletteEasing = Easing { fraction ->
    ((1.0 - cos(PI * fraction.coerceIn(0f, 1f))) * 0.5).toFloat()
}
private val DefaultArtworkSeed = Color(0xFF5F91AC)
private val DefaultFlowPalette = flowColorsFromSeed(DefaultArtworkSeed)

@Composable
internal fun AndroidAlbumFlowBackground(
    track: AndroidTrack?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp,
    veil: Color,
    animated: Boolean = false,
    solid: Boolean = false,
) {
    if (solid) {
        AndroidArtworkSolidBackground(
            track = track,
            modifier = modifier,
            cornerRadius = cornerRadius,
            veil = veil,
        )
    } else {
        AndroidArtworkFlowBackground(
            artworkKey = track?.id,
            coverUrl = track?.coverUrl,
            modifier = modifier,
            cornerRadius = cornerRadius,
            veil = veil,
            animated = animated,
        )
    }
}

/** Keeps the previous artwork seed until the next cover has been decoded, avoiding a theme flash. */
@Composable
internal fun rememberAndroidArtworkSeed(track: AndroidTrack?): Color? {
    var seed by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(track?.id, track?.coverUrl) {
        if (track == null) {
            seed = null
        } else {
            seed = extractAndroidArtworkSeed(track.coverUrl) ?: DefaultArtworkSeed
        }
    }
    return seed
}

@Composable
private fun AndroidArtworkSolidBackground(
    track: AndroidTrack?,
    modifier: Modifier,
    cornerRadius: Dp,
    veil: Color,
) {
    val seed = rememberAndroidArtworkSeed(track) ?: DefaultArtworkSeed
    Crossfade(
        targetState = seed,
        modifier = modifier.clip(RoundedCornerShape(cornerRadius)),
        animationSpec = tween(durationMillis = 650, easing = FluidPaletteEasing),
        label = "android-artwork-solid-color",
    ) { activeSeed ->
        Box(
            Modifier
                .fillMaxSize()
                .background(activeSeed)
                .background(veil),
        )
    }
}

@Composable
internal fun AndroidPlaylistFlowBackground(
    playlist: AndroidPlaylist,
    modifier: Modifier = Modifier,
    cornerRadius: Dp,
    veil: Color,
) {
    AndroidArtworkFlowBackground(
        artworkKey = playlist.id,
        coverUrl = playlist.coverUrl,
        modifier = modifier,
        cornerRadius = cornerRadius,
        veil = veil,
        animated = false,
    )
}

@Composable
private fun AndroidArtworkFlowBackground(
    artworkKey: Long?,
    coverUrl: String?,
    modifier: Modifier,
    cornerRadius: Dp,
    veil: Color,
    animated: Boolean,
) {
    var colors by remember { mutableStateOf(DefaultFlowPalette) }
    var phaseSeconds by remember(artworkKey, animated) { mutableFloatStateOf(0f) }

    LaunchedEffect(artworkKey, coverUrl) {
        colors = extractAndroidFlowPalette(coverUrl)
    }
    // The optional ambient background is deliberately capped at 12 fps. It keeps the palette
    // alive without turning a full-screen decorative layer into a display-refresh-rate workload.
    LaunchedEffect(artworkKey, animated) {
        phaseSeconds = 0f
        if (animated) {
            while (isActive) {
                delay(83L)
                phaseSeconds = (phaseSeconds + 0.083f) % 10_000f
            }
        }
    }

    val palette = remember(colors) { normalizeFlowPalette(colors) }
    Box(
        modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(palette[4]),
    ) {
        Crossfade(
            targetState = palette,
            animationSpec = tween(durationMillis = 650, easing = FluidPaletteEasing),
            label = "android-lyric-flow-palette",
        ) { activePalette ->
            Box(Modifier.fillMaxSize()) {
                FlowPaletteLayers(activePalette) { phaseSeconds }
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            Color.Transparent,
                            veil.copy(alpha = veil.alpha * 0.28f),
                            veil.copy(alpha = veil.alpha * 0.72f),
                        ),
                    ),
                ),
        )
        Box(Modifier.fillMaxSize().background(veil.copy(alpha = veil.alpha * 0.72f)))
    }
}

@Composable
private fun BoxScope.FlowPaletteLayers(palette: List<Color>, phaseSeconds: () -> Float) {
    FlowLayer(palette[0], phaseSeconds, 0.2f, 0.19f, 0.30f, 0.24f, 1.68f, 1.18f, 8f)
    FlowLayer(palette[1], phaseSeconds, 2.1f, 0.145f, 0.25f, 0.34f, 1.34f, 1.58f, -10f)
    FlowLayer(palette[2], phaseSeconds, 4.0f, 0.17f, 0.37f, 0.20f, 1.52f, 1.26f, 7f)
    FlowLayer(palette[3], phaseSeconds, 5.35f, 0.12f, 0.20f, 0.38f, 1.28f, 1.62f, -6f)
    FlowLayer(palette[4], phaseSeconds, 1.25f, 0.105f, 0.16f, 0.18f, 1.85f, 1.12f, 5f, 0.52f)
}

@Composable
private fun BoxScope.FlowLayer(
    color: Color,
    phaseSeconds: () -> Float,
    phaseOffset: Float,
    speed: Float,
    orbitX: Float,
    orbitY: Float,
    scaleX: Float,
    scaleY: Float,
    rotationRange: Float,
    colorAlpha: Float = 0.68f,
) {
    val brush = remember(color, colorAlpha) {
        Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = colorAlpha),
                color.copy(alpha = colorAlpha * 0.56f),
                color.copy(alpha = colorAlpha * 0.16f),
                Color.Transparent,
            ),
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val time = phaseSeconds() * speed + phaseOffset
                val secondaryTime = phaseSeconds() * speed * 0.73f + phaseOffset * 1.37f
                translationX = sin(time.toDouble()).toFloat() * size.width * orbitX
                translationY = cos(secondaryTime.toDouble()).toFloat() * size.height * orbitY
                val pulse = sin((time * 0.61f).toDouble()).toFloat() * 0.075f
                this.scaleX = scaleX + pulse
                this.scaleY = scaleY - pulse * 0.72f
                rotationZ = sin((time * 0.43f).toDouble()).toFloat() * rotationRange
            }
            .background(brush),
    )
}

private suspend fun extractAndroidFlowPalette(coverUrl: String?): List<Color> =
    extractAndroidArtworkSeed(coverUrl)?.let(::flowColorsFromSeed) ?: DefaultFlowPalette

private suspend fun extractAndroidArtworkSeed(coverUrl: String?): Color? = withContext(Dispatchers.IO) {
    if (coverUrl.isNullOrBlank()) return@withContext null
    val artworkUrl = coverUrl.toAndroidPaletteArtworkUrl()
    ArtworkSeedCache.get(artworkUrl)?.let { return@withContext it }
    ArtworkSeedMutex.withLock {
        ArtworkSeedCache.get(artworkUrl)?.let { return@withLock it }
        runCatching {
            val connection = URL(artworkUrl).openConnection() as HttpURLConnection
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.setRequestProperty("User-Agent", "Lazer/1.2")
            try {
                val bitmap = connection.inputStream.use(BitmapFactory::decodeStream)
                    ?: return@runCatching null
                try {
                    seedFromBitmap(bitmap).also { ArtworkSeedCache.put(artworkUrl, it) }
                } finally {
                    bitmap.recycle()
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }
}

private fun seedFromBitmap(bitmap: Bitmap): Color {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return DefaultArtworkSeed
    val stepX = max(1, width / 48)
    val stepY = max(1, height / 48)
    val weightedRed = DoubleArray(24)
    val weightedGreen = DoubleArray(24)
    val weightedBlue = DoubleArray(24)
    val weights = DoubleArray(24)
    var averageRed = 0.0
    var averageGreen = 0.0
    var averageBlue = 0.0
    var averageCount = 0.0
    var y = 0
    while (y < height) {
        var x = 0
        while (x < width) {
            val pixel = bitmap.getPixel(x, y)
            if ((pixel ushr 24) and 0xFF >= 128) {
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                averageRed += red
                averageGreen += green
                averageBlue += blue
                averageCount++
                val hsl = rgbToHsl(red / 255f, green / 255f, blue / 255f)
                val saturation = hsl[1]
                val lightness = hsl[2]
                if (saturation >= 0.15f && lightness in 0.12f..0.92f) {
                    val weight = saturation * max(0.05, 1.0 - abs(lightness - 0.5) * 1.6)
                    val bucket = min(23, (hsl[0] / 360f * 24f).toInt())
                    weightedRed[bucket] += red * weight
                    weightedGreen[bucket] += green * weight
                    weightedBlue[bucket] += blue * weight
                    weights[bucket] += weight
                }
            }
            x += stepX
        }
        y += stepY
    }
    val best = weights.indices.maxByOrNull(weights::get) ?: -1
    if (best >= 0 && weights[best] > 0.5) {
        val seed = Color(
            (weightedRed[best] / weights[best]).roundToInt().coerceIn(0, 255) / 255f,
            (weightedGreen[best] / weights[best]).roundToInt().coerceIn(0, 255) / 255f,
            (weightedBlue[best] / weights[best]).roundToInt().coerceIn(0, 255) / 255f,
        )
        val hsl = rgbToHsl(seed.red, seed.green, seed.blue)
        return hslToColor(hsl[0], min(1f, hsl[1] * 1.25f), hsl[2])
    }
    return if (averageCount > 0) {
        Color(
            (averageRed / averageCount).roundToInt().coerceIn(0, 255) / 255f,
            (averageGreen / averageCount).roundToInt().coerceIn(0, 255) / 255f,
            (averageBlue / averageCount).roundToInt().coerceIn(0, 255) / 255f,
        )
    } else {
        DefaultArtworkSeed
    }
}

private fun flowColorsFromSeed(seed: Color): List<Color> {
    val hsl = rgbToHsl(seed.red, seed.green, seed.blue)
    val primaryChroma = (hsl[1] * 0.72f + 0.12f).coerceIn(0.20f, 0.58f)
    return listOf(
        hslToColor(hsl[0], primaryChroma * 0.55f, 0.90f),
        hslToColor(hsl[0], primaryChroma * 0.72f, 0.76f),
        hslToColor(hsl[0], primaryChroma, 0.58f),
        hslToColor(hsl[0], primaryChroma * 0.86f, 0.43f),
        hslToColor(hsl[0], primaryChroma * 0.74f, 0.31f),
    )
}

private fun rgbToHsl(red: Float, green: Float, blue: Float): FloatArray {
    val maximum = max(red, max(green, blue))
    val minimum = min(red, min(green, blue))
    val lightness = (maximum + minimum) / 2f
    val delta = maximum - minimum
    if (delta <= 1e-4f) return floatArrayOf(0f, 0f, lightness)
    val saturation = if (lightness > 0.5f) delta / (2f - maximum - minimum) else delta / (maximum + minimum)
    val hue = when (maximum) {
        red -> (green - blue) / delta + if (green < blue) 6f else 0f
        green -> (blue - red) / delta + 2f
        else -> (red - green) / delta + 4f
    } * 60f
    return floatArrayOf(hue, saturation, lightness)
}

private fun hslToColor(hue: Float, saturation: Float, lightness: Float): Color {
    val normalizedHue = ((hue % 360f) + 360f) % 360f
    val safeSaturation = saturation.coerceIn(0f, 1f)
    val safeLightness = lightness.coerceIn(0f, 1f)
    val chroma = (1f - abs(2f * safeLightness - 1f)) * safeSaturation
    val huePart = normalizedHue / 60f
    val x = chroma * (1f - abs(huePart % 2f - 1f))
    val (red, green, blue) = when {
        huePart < 1f -> Triple(chroma, x, 0f)
        huePart < 2f -> Triple(x, chroma, 0f)
        huePart < 3f -> Triple(0f, chroma, x)
        huePart < 4f -> Triple(0f, x, chroma)
        huePart < 5f -> Triple(x, 0f, chroma)
        else -> Triple(chroma, 0f, x)
    }
    val match = safeLightness - chroma / 2f
    return Color(red + match, green + match, blue + match)
}

private fun normalizeFlowPalette(colors: List<Color>): List<Color> {
    val source = colors.ifEmpty { DefaultFlowPalette }
    return List(5) { index -> source[index % source.size] }
}

private fun String.toAndroidPaletteArtworkUrl(): String {
    val secure = trim().replaceFirst("http://", "https://")
        .let { if (it.startsWith("//")) "https:$it" else it }
    if (AndroidArtworkSizeParameter.containsMatchIn(secure)) {
        return secure.replace(AndroidArtworkSizeParameter) { match -> match.groupValues[1] + "96y96" }
    }
    return secure + if ('?' in secure) "&param=96y96" else "?param=96y96"
}
