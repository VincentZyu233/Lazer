package dev.naominet.lazer

import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPcmTestWaveTest {
    @Test
    fun `writes valid RIFF headers and exact two second lengths for supported formats`() {
        for (sampleRate in DesktopPcmTestWave.supportedSampleRatesHz) {
            for (channels in DesktopPcmTestWave.supportedChannelCounts) {
                for (bitDepth in DesktopPcmTestWave.supportedBitDepths) {
                    val wav = DesktopPcmTestWave.create(sampleRate, channels, bitDepth)
                    val bytesPerSample = bitDepth / 8
                    val blockAlign = channels * bytesPerSample
                    val frames = sampleRate * DesktopPcmTestWave.DURATION_MILLISECONDS / 1_000
                    val dataSize = frames * blockAlign

                    assertEquals("RIFF", ascii(wav, 0, 4))
                    assertEquals(wav.size - 8, uint32(wav, 4))
                    assertEquals("WAVE", ascii(wav, 8, 4))
                    assertEquals("fmt ", ascii(wav, 12, 4))
                    assertEquals(16, uint32(wav, 16))
                    assertEquals(1, uint16(wav, 20))
                    assertEquals(channels, uint16(wav, 22))
                    assertEquals(sampleRate, uint32(wav, 24))
                    assertEquals(sampleRate * blockAlign, uint32(wav, 28))
                    assertEquals(blockAlign, uint16(wav, 32))
                    assertEquals(bitDepth, uint16(wav, 34))
                    assertEquals("data", ascii(wav, 36, 4))
                    assertEquals(dataSize, uint32(wav, 40))
                    assertEquals(44 + dataSize, wav.size)
                    assertEquals(frames.toDouble() / sampleRate, 2.0, 1e-12)
                }
            }
        }
    }

    @Test
    fun `sine tone is deterministic low amplitude and faded at both ends`() {
        val sampleRate = 48_000
        val channels = 2
        for (bitDepth in DesktopPcmTestWave.supportedBitDepths) {
            val wav = DesktopPcmTestWave.create(sampleRateHz = sampleRate, channels = channels, bitDepth = bitDepth)
            val bytesPerSample = bitDepth / 8
            val frames = sampleRate * DesktopPcmTestWave.DURATION_MILLISECONDS / 1_000
            val fadeFrames = sampleRate * DesktopPcmTestWave.FADE_MILLISECONDS / 1_000
            val positiveFullScale = (1L shl (bitDepth - 1)) - 1L

            assertArrayEquals(wav, DesktopPcmTestWave.create(sampleRate, channels, bitDepth))
            assertEquals(0, sample(wav, 0, 0, channels, bytesPerSample, bitDepth))
            assertEquals(0, sample(wav, frames - 1, 0, channels, bytesPerSample, bitDepth))

            // The opening fade attenuates an early near-peak sample; an equivalent
            // sample after the fade reaches the requested -40 dBFS level.
            val earlyPeakFrame = (sampleRate / (4.0 * DesktopPcmTestWave.TONE_FREQUENCY_HZ)).toInt()
            val laterPeakFrame = (earlyPeakFrame + sampleRate / DesktopPcmTestWave.TONE_FREQUENCY_HZ * 12)
                .coerceAtLeast(fadeFrames + 1)
            val earlySample = sample(wav, earlyPeakFrame, 0, channels, bytesPerSample, bitDepth)
            val laterSample = sample(wav, laterPeakFrame, 0, channels, bytesPerSample, bitDepth)
            assertTrue("bitDepth=$bitDepth early fade was not quiet", kotlin.math.abs(earlySample) < kotlin.math.abs(laterSample) / 10)
            assertEquals(
                "stereo channels should carry the same test tone",
                laterSample,
                sample(wav, laterPeakFrame, 1, channels, bytesPerSample, bitDepth),
            )

            var peak = 0L
            var risingZeroCrossings = 0
            var previous = sample(wav, fadeFrames, 0, channels, bytesPerSample, bitDepth)
            for (frame in fadeFrames + 1 until frames - fadeFrames) {
                val value = sample(wav, frame, 0, channels, bytesPerSample, bitDepth)
                peak = maxOf(peak, kotlin.math.abs(value.toLong()))
                if (previous <= 0 && value > 0) risingZeroCrossings++
                previous = value
            }
            val normalizedPeak = peak / positiveFullScale.toDouble()
            assertTrue("expected about -40 dBFS; got peak $normalizedPeak", normalizedPeak in 0.0098..0.0101)

            val expectedCrossings = DesktopPcmTestWave.TONE_FREQUENCY_HZ.toDouble() *
                (frames - 2 * fadeFrames).toDouble() / sampleRate
            assertTrue(
                "expected about $expectedCrossings rising crossings, got $risingZeroCrossings",
                kotlin.math.abs(risingZeroCrossings - expectedCrossings) <= 2.0,
            )
        }
    }

    @Test
    fun `writes the deterministic fixture to the requested path`() {
        val directory = Files.createTempDirectory("lazer-pcm-fixture-test")
        val path = directory.resolve("tone-96k-stereo-24.wav")
        try {
            val written = DesktopPcmTestWave.write(path, 96_000, 2, 24)
            assertEquals(path, written)
            assertArrayEquals(DesktopPcmTestWave.create(96_000, 2, 24), Files.readAllBytes(path))
        } finally {
            Files.deleteIfExists(path)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `rejects unsupported format parameters`() {
        assertThrows(IllegalArgumentException::class.java) { DesktopPcmTestWave.create(44_000, 1, 16) }
        assertThrows(IllegalArgumentException::class.java) { DesktopPcmTestWave.create(48_000, 3, 16) }
        assertThrows(IllegalArgumentException::class.java) { DesktopPcmTestWave.create(48_000, 2, 20) }
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        bytes.decodeToString(offset, offset + length)

    private fun uint16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun uint32(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16) or
            ((bytes[offset + 3].toInt() and 0xff) shl 24)

    private fun sample(
        bytes: ByteArray,
        frame: Int,
        channel: Int,
        channels: Int,
        bytesPerSample: Int,
        bitDepth: Int,
    ): Int {
        val offset = 44 + (frame * channels + channel) * bytesPerSample
        var value = 0
        repeat(bytesPerSample) { byteIndex ->
            value = value or ((bytes[offset + byteIndex].toInt() and 0xff) shl (byteIndex * 8))
        }
        if (bitDepth == Int.SIZE_BITS) return value
        val signBit = 1 shl (bitDepth - 1)
        return if (value and signBit != 0) value or (-1 shl bitDepth) else value
    }
}
