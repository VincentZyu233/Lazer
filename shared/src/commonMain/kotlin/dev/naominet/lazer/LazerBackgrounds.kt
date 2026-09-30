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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
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

/** What one cover gives the interface: the accent seed screens tint with, and the flow palette. */
private class ArtworkColors(val seed: Color, val palette: List<Color>)

/** Keeps the last few covers so scrolling back over a list does not re-download artwork. */
private class LazerArtworkCache(private val capacity: Int) {
    private val entries = LinkedHashMap<String, ArtworkColors>()

    fun get(key: String): ArtworkColors? = entries.remove(key)?.also { entries[key] = it }

    fun put(key: String, value: ArtworkColors) {
        entries[key] = value
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }
}

private val ArtworkColorsCache = LazerArtworkCache(capacity = 24)
private val ArtworkColorsMutex = Mutex()
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

/**
 * One cover is downloaded, decoded and measured once, and both extractions come out of that pass.
 */
private suspend fun artworkColors(host: LazerPlatformHost, coverUrl: String?): ArtworkColors? {
    if (coverUrl.isNullOrBlank()) return null
    val artworkUrl = coverUrl.toLazerPaletteArtworkUrl()
    return withContext(Dispatchers.Default) {
        ArtworkColorsMutex.withLock {
            ArtworkColorsCache.get(artworkUrl)?.let { return@withLock it }
            runCatching {
                val bytes = host.downloadFile(artworkUrl) ?: return@runCatching null
                val artwork = host.decodeImageBytes(bytes) ?: return@runCatching null
                ArtworkColors(
                    seed = seedFromArtwork(artwork),
                    palette = flowPaletteFromDominant(paletteFromArtwork(artwork)),
                ).also { ArtworkColorsCache.put(artworkUrl, it) }
            }.getOrNull()
        }
    }
}

private suspend fun extractLazerFlowPalette(host: LazerPlatformHost, coverUrl: String?): List<Color> =
    artworkColors(host, coverUrl)?.palette ?: flowColorsFromSeed(DefaultArtworkSeed)

private suspend fun extractLazerArtworkSeed(host: LazerPlatformHost, coverUrl: String?): Color? =
    artworkColors(host, coverUrl)?.seed

/**
 * AMLL paints its gradient from the four colours the cover is actually made of. The fifth field this
 * renderer orbits takes the darkest of them, which is where upstream's palette sits in practice.
 */
private fun flowPaletteFromDominant(dominant: List<Color>): List<Color> {
    val darkest = dominant.minByOrNull { it.luminance() }
        ?: return flowColorsFromSeed(DefaultArtworkSeed)
    return dominant + lerp(darkest, Color.Black, 0.35f)
}

/**
 * AMLL's `buildColorHistogram` and its dominant-colour pick, without a canvas: the artwork is
 * reduced to AMLL's 64 px longest edge, every pixel of that is counted under its exact RGB key,
 * near-white pixels are dropped the way upstream's octree drops them, and the most counted colours
 * win. Upstream clusters with k-means or an octree once a cover has more distinct colours than its
 * bucket limit, which a 64 px thumbnail rarely reaches; counting the thumbnail directly is the same
 * answer for the artwork sizes this app downloads.
 */
internal fun lazerDominantPalette(
    argbPixels: IntArray,
    width: Int,
    height: Int,
    colorCount: Int = 4,
): List<Color> {
    if (width <= 0 || height <= 0 || argbPixels.size < width * height) return emptyList()
    val scale = min(1f, PaletteSampleSize / max(width, height).toFloat())
    val sampleWidth = max(1, (width * scale).roundToInt())
    val sampleHeight = max(1, (height * scale).roundToInt())
    val counts = HashMap<Int, Int>(sampleWidth * sampleHeight)
    for (y in 0 until sampleHeight) {
        val sourceY = (y * height / sampleHeight).coerceIn(0, height - 1)
        for (x in 0 until sampleWidth) {
            val sourceX = (x * width / sampleWidth).coerceIn(0, width - 1)
            val argb = argbPixels[sourceY * width + sourceX]
            val alpha = (argb ushr 24) and 0xFF
            if (alpha == 0) continue
            val red = (argb ushr 16) and 0xFF
            val green = (argb ushr 8) and 0xFF
            val blue = argb and 0xFF
            if (red > 250 && green > 250 && blue > 250) continue
            val key = (red shl 16) or (green shl 8) or blue
            counts[key] = (counts[key] ?: 0) + 1
        }
    }
    if (counts.isEmpty()) return emptyList()
    val dominant = counts.entries
        .sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }
            .thenByDescending { it.key })
        .take(colorCount)
        .map { Color(0xFF000000L or it.key.toLong()) }
    // AMLL pads a short palette by cycling what it found, rather than inventing a neutral.
    return List(colorCount) { dominant[it % dominant.size] }
}

private const val PaletteSampleSize = 64

@OptIn(ExperimentalComposeUiApi::class)
private fun paletteFromArtwork(artwork: ImageBitmap): List<Color> {
    val pixelMap = artwork.toPixelMap()
    val pixels = IntArray(pixelMap.width * pixelMap.height)
    var index = 0
    for (y in 0 until pixelMap.height) {
        for (x in 0 until pixelMap.width) {
            val pixel = pixelMap[x, y]
            pixels[index] = (pixel.alpha.toArgbChannel() shl 24) or
                (pixel.red.toArgbChannel() shl 16) or
                (pixel.green.toArgbChannel() shl 8) or
                pixel.blue.toArgbChannel()
            index++
        }
    }
    return lazerDominantPalette(pixels, pixelMap.width, pixelMap.height)
}

private fun Float.toArgbChannel(): Int = (this * 255f).roundToInt().coerceIn(0, 255)

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
    val secure = trim().replaceFirst("http://", "https://")
        .let { if (it.startsWith("//")) "https:$it" else it }
    if (lazerArtworkSizeParameter.containsMatchIn(secure)) {
        return secure.replace(lazerArtworkSizeParameter) { match -> match.groupValues[1] + "96y96" }
    }
    return secure + if ('?' in secure) "&param=96y96" else "?param=96y96"
}
