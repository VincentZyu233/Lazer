package dev.naominet.lazer

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

// Enough for a 4K desktop, with a separate pixel budget for square/portrait sources.
internal const val BACKGROUND_MAX_DIMENSION = 3840
internal const val BACKGROUND_MAX_PIXELS = 3840L * 2160L

internal fun backgroundImageSubsampling(
    width: Int,
    height: Int,
    maxDimension: Int = BACKGROUND_MAX_DIMENSION,
    maxPixels: Long = BACKGROUND_MAX_PIXELS,
): Int {
    require(width > 0 && height > 0)
    require(maxDimension > 0 && maxPixels > 0)
    fun sampled(size: Int, sample: Int): Long = (size.toLong() + sample - 1) / sample
    var sample = maxOf(
        1L,
        (maxOf(width, height).toLong() + maxDimension - 1) / maxDimension,
        kotlin.math.ceil(kotlin.math.sqrt(width.toDouble() * height / maxPixels)).toLong(),
    ).toInt()
    while (sampled(width, sample) * sampled(height, sample) > maxPixels) sample++
    return sample
}

/** Subsample in the reader: resizing an ImageIO.read result would already allocate the full image. */
internal fun decodeDesktopBackgroundImage(
    source: File,
    maxDimension: Int = BACKGROUND_MAX_DIMENSION,
    maxPixels: Long = BACKGROUND_MAX_PIXELS,
): BufferedImage? {
    val input = ImageIO.createImageInputStream(source) ?: return null
    return input.use {
        val readers = ImageIO.getImageReaders(input)
        if (!readers.hasNext()) return@use null
        val reader = readers.next()
        try {
            reader.setInput(input, true, true)
            val sample = backgroundImageSubsampling(
                reader.getWidth(0), reader.getHeight(0), maxDimension, maxPixels,
            )
            val params = reader.defaultReadParam.apply {
                setSourceSubsampling(sample, sample, 0, 0)
            }
            reader.read(0, params)
        } finally {
            reader.dispose()
        }
    }
}
