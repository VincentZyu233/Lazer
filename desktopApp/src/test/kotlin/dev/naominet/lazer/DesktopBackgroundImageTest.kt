package dev.naominet.lazer

import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopBackgroundImageTest {
    @Test
    fun `4K stays sharp and 8K is subsampled to 4K`() {
        assertEquals(1, backgroundImageSubsampling(3840, 2160))
        assertEquals(2, backgroundImageSubsampling(7680, 4320))
        assertEquals(2, backgroundImageSubsampling(4320, 7680))
        assertEquals(1, backgroundImageSubsampling(800, 600))
    }

    @Test
    fun `pixel budget also bounds square images and rounded sample dimensions`() {
        assertEquals(2, backgroundImageSubsampling(3840, 3840))
        // sqrt(area / budget) alone gives 2, but rounding both decoded axes up exceeds 9.
        assertEquals(3, backgroundImageSubsampling(7, 5, maxDimension = 10, maxPixels = 9))
        val sample = backgroundImageSubsampling(Int.MAX_VALUE, Int.MAX_VALUE)
        val side = (Int.MAX_VALUE.toLong() + sample - 1) / sample
        assertTrue(side <= BACKGROUND_MAX_DIMENSION)
        assertTrue(side * side <= BACKGROUND_MAX_PIXELS)
    }

    @Test
    fun `PNG reader subsamples odd dimensions while preserving alpha`() {
        val source = BufferedImage(101, 67, BufferedImage.TYPE_INT_ARGB)
        source.setRGB(0, 0, 0x80402010.toInt())
        withImageFile(source, "png") { file ->
            val decoded = requireNotNull(decodeDesktopBackgroundImage(file, 40, 1000))
            try {
                assertEquals(34, decoded.width)
                assertEquals(23, decoded.height)
                assertEquals(0x80402010.toInt(), decoded.getRGB(0, 0))
            } finally {
                decoded.flush()
            }
        }
    }

    @Test
    fun `JPEG reader respects landscape and portrait bounds`() {
        for ((width, height) in listOf(103 to 65, 65 to 103)) {
            withImageFile(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "jpg") { file ->
                val decoded = requireNotNull(decodeDesktopBackgroundImage(file, 40, 1000))
                try {
                    assertEquals((width + 2) / 3, decoded.width)
                    assertEquals((height + 2) / 3, decoded.height)
                    assertTrue(decoded.width.toLong() * decoded.height <= 1000)
                } finally {
                    decoded.flush()
                }
            }
        }
    }

    @Test
    fun `small PNG keeps its original size and pixels`() {
        val source = BufferedImage(5, 3, BufferedImage.TYPE_INT_ARGB)
        source.setRGB(4, 2, 0xFF123456.toInt())
        withImageFile(source, "png") { file ->
            val decoded = requireNotNull(decodeDesktopBackgroundImage(file))
            try {
                assertEquals(5, decoded.width)
                assertEquals(3, decoded.height)
                assertEquals(0xFF123456.toInt(), decoded.getRGB(4, 2))
            } finally {
                decoded.flush()
            }
        }
    }

    @Test
    fun `unsupported file is rejected without a decoded image`() {
        val file = Files.createTempFile("lazer-wallpaper-", ".txt").toFile()
        try {
            file.writeText("not an image")
            assertNull(decodeDesktopBackgroundImage(file))
        } finally {
            Files.deleteIfExists(file.toPath())
        }
    }

    private fun withImageFile(image: BufferedImage, format: String, block: (java.io.File) -> Unit) {
        val file = Files.createTempFile("lazer-wallpaper-", ".$format").toFile()
        try {
            assertTrue(ImageIO.write(image, format, file))
            image.flush()
            block(file)
        } finally {
            image.flush()
            // Also detects leaked image input streams on Windows.
            Files.deleteIfExists(file.toPath())
        }
    }
}
