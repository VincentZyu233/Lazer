package dev.naominet.lazer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Keeps the last few seeds so scrolling back over a list does not re-download artwork. */
private class LazerArtworkSeedCache(private val capacity: Int) {
    private val entries = LinkedHashMap<String, Color>()

    fun get(key: String): Color? = entries.remove(key)?.also { entries[key] = it }

    fun put(key: String, value: Color) {
        entries[key] = value
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
}

private val ArtworkSeedCache = LazerArtworkSeedCache(capacity = 24)
private val ArtworkSeedMutex = Mutex()
private val DefaultArtworkSeed = Color(0xFF5F91AC)

@Composable
internal fun LazerAlbumFlowBackground(
    track: LazerTrack?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp,
    veil: Color,
    animated: Boolean = false,
    solid: Boolean = false,
) {
    if (solid) {
        LazerArtworkSolidBackground(track, modifier, cornerRadius, veil)
    } else {
        LazerArtworkFlowBackground(
            artworkKey = track?.id,
            coverUrl = track?.coverUrl,
            modifier = modifier,
            cornerRadius = cornerRadius,
            veil = veil,
            animated = animated,
        )
    }
}

@Composable
internal fun LazerPlaylistFlowBackground(
    playlist: LazerPlaylist,
    modifier: Modifier = Modifier,
    cornerRadius: Dp,
    veil: Color,
) {
    LazerArtworkFlowBackground(
        artworkKey = playlist.id,
        coverUrl = playlist.coverUrl,
        modifier = modifier,
        cornerRadius = cornerRadius,
        veil = veil,
        animated = false,
    )
}

/** Keeps the previous artwork seed until the next cover has been decoded. */
@Composable
internal fun rememberLazerArtworkSeed(track: LazerTrack?): Color? {
    val host = LocalLazerPlatformHost.current
    var seed by remember { mutableStateOf<Color?>(null) }
    LaunchedEffect(track?.id, track?.coverUrl) {
        seed = if (track == null || host == null) {
            null
        } else {
            extractLazerArtworkSeed(host, track.coverUrl) ?: DefaultArtworkSeed
        }
    }
    return seed
}

@Composable
private fun LazerArtworkSolidBackground(
    track: LazerTrack?,
    modifier: Modifier,
    cornerRadius: Dp,
    veil: Color,
) {
    val seed = rememberLazerArtworkSeed(track) ?: DefaultArtworkSeed
    LazerAlbumFlowBackground(
        colors = flowColorsFromSeed(seed),
        modifier = modifier,
        cornerRadius = cornerRadius,
        veil = veil,
        animated = false,
        solid = true,
    )
}

@Composable
private fun LazerArtworkFlowBackground(
    artworkKey: Long?,
    coverUrl: String?,
    modifier: Modifier,
    cornerRadius: Dp,
    veil: Color,
    animated: Boolean,
) {
    val host = LocalLazerPlatformHost.current
    var colors by remember { mutableStateOf(flowColorsFromSeed(DefaultArtworkSeed)) }
    LaunchedEffect(artworkKey, coverUrl, host) {
        if (host != null) colors = extractLazerFlowPalette(host, coverUrl)
    }
    LazerAlbumFlowBackground(
        colors = colors,
        modifier = modifier,
        cornerRadius = cornerRadius,
        veil = veil,
        animated = animated,
    )
}

private suspend fun extractLazerFlowPalette(host: LazerPlatformHost, coverUrl: String?): List<Color> =
    extractLazerArtworkSeed(host, coverUrl)?.let(::flowColorsFromSeed)
        ?: flowColorsFromSeed(DefaultArtworkSeed)

private suspend fun extractLazerArtworkSeed(host: LazerPlatformHost, coverUrl: String?): Color? =
    withContext(Dispatchers.Default) {
        if (coverUrl.isNullOrBlank()) return@withContext null
        val artworkUrl = coverUrl.toLazerPaletteArtworkUrl()
        ArtworkSeedCache.get(artworkUrl)?.let { return@withContext it }
        ArtworkSeedMutex.withLock {
            ArtworkSeedCache.get(artworkUrl)?.let { return@withLock it }
            runCatching {
                val bytes = host.downloadFile(artworkUrl) ?: return@runCatching null
                val artwork = host.decodeImageBytes(bytes) ?: return@runCatching null
                seedFromArtwork(artwork).also { ArtworkSeedCache.put(artworkUrl, it) }
            }.getOrNull()
        }
    }

@OptIn(ExperimentalComposeUiApi::class)
private fun seedFromArtwork(artwork: ImageBitmap): Color {
    val pixelMap = artwork.toPixelMap()
    val width = pixelMap.width
    val height = pixelMap.height
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
            val pixel = pixelMap[x, y]
            if (pixel.alpha >= 0.5f) {
                val red = (pixel.red * 255f).roundToInt()
                val green = (pixel.green * 255f).roundToInt()
                val blue = (pixel.blue * 255f).roundToInt()
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

private fun rgbToHsl(red: Float, green: Float, blue: Float): FloatArray {
    val maximum = max(red, max(green, blue))
    val minimum = min(red, min(green, blue))
    val lightness = (maximum + minimum) / 2f
    val delta = maximum - minimum
    if (delta <= 1e-4f) return floatArrayOf(0f, 0f, lightness)
    val saturation = if (lightness > 0.5f) {
        delta / (2f - maximum - minimum)
    } else {
        delta / (maximum + minimum)
    }
    val hue = when (maximum) {
        red -> (green - blue) / delta + if (green < blue) 6f else 0f
        green -> (blue - red) / delta + 2f
        else -> (red - green) / delta + 4f
    } * 60f
    return floatArrayOf(hue, saturation, lightness)
}

private fun String.toLazerPaletteArtworkUrl(): String {
    val value = trim()
    if (!value.startsWith("http://", ignoreCase = true) &&
        !value.startsWith("https://", ignoreCase = true) &&
        !value.startsWith("//")
    ) return value
    val secure = value.replaceFirst("http://", "https://", ignoreCase = true)
        .let { if (it.startsWith("//")) "https:$it" else it }
    if (lazerArtworkSizeParameter.containsMatchIn(secure)) {
        return secure.replace(lazerArtworkSizeParameter) { match -> match.groupValues[1] + "96y96" }
    }
    return secure + if ('?' in secure) "&param=96y96" else "?param=96y96"
}
