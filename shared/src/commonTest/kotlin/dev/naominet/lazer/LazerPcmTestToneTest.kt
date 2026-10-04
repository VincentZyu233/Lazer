package dev.naominet.lazer

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LazerPcmTestToneTest {
    @Test
    fun `formats cover the planned pcm rate and integer depth range`() {
        assertEquals(listOf(16, 24, 32), LazerPcmTestTone.supportedBitDepths)
        assertEquals(44_100, LazerPcmTestTone.supportedSampleRatesHz.first())
        assertEquals(768_000, LazerPcmTestTone.supportedSampleRatesHz.last())
        assertEquals(30, LazerPcmTestTone.supportedFormats.size)
    }

    @Test
    fun `tone is deterministic stereo integer pcm with two second duration and fades`() {
        val cases = listOf(
            Triple(44_100, 16, 2),
            Triple(96_000, 24, 2),
            Triple(192_000, 32, 2),
            Triple(768_000, 32, 1),
        )
        for ((sampleRate, bitDepth, channels) in cases) {
            val pcm = LazerPcmTestTone.createPcm(sampleRate, channels, bitDepth)
            val bytesPerSample = bitDepth / 8
            val frameCount = sampleRate * LazerPcmTestTone.DURATION_MILLISECONDS / 1_000
            assertEquals(frameCount * channels * bytesPerSample, pcm.size)
            assertContentEquals(pcm, LazerPcmTestTone.createPcm(sampleRate, channels, bitDepth))
            assertEquals(0, sample(pcm, 0, 0, channels, bytesPerSample, bitDepth))
            assertEquals(0, sample(pcm, frameCount - 1, 0, channels, bytesPerSample, bitDepth))

            val fadeFrames = sampleRate * LazerPcmTestTone.FADE_MILLISECONDS / 1_000
            val peakFrame = (sampleRate / (4.0 * LazerPcmTestTone.FREQUENCY_HZ)).toInt()
            val stablePeakFrame = (peakFrame + sampleRate / LazerPcmTestTone.FREQUENCY_HZ * 12)
                .coerceAtLeast(fadeFrames + 1)
            val early = sample(pcm, peakFrame, 0, channels, bytesPerSample, bitDepth)
            val stable = sample(pcm, stablePeakFrame, 0, channels, bytesPerSample, bitDepth)
            assertTrue(abs(early.toLong()) < abs(stable.toLong()) / 10L)
            if (channels == 2) {
                assertEquals(stable, sample(pcm, stablePeakFrame, 1, channels, bytesPerSample, bitDepth))
            }

            val positiveFullScale = ((1L shl (bitDepth - 1)) - 1L).toDouble()
            var peak = 0L
            for (frame in fadeFrames until frameCount - fadeFrames) {
                peak = maxOf(peak, abs(sample(pcm, frame, 0, channels, bytesPerSample, bitDepth).toLong()))
            }
            val normalizedPeak = peak / positiveFullScale
            assertTrue(normalizedPeak in 0.0098..0.0101, "expected about -40 dBFS; got $normalizedPeak")
        }
    }

    @Test
    fun `rejects unsupported test tone format`() {
        assertFailsWith<IllegalArgumentException> { LazerPcmTestFormat(44_000, 16) }
        assertFailsWith<IllegalArgumentException> { LazerPcmTestTone.createPcm(48_000, 3, 16) }
        assertFailsWith<IllegalArgumentException> { LazerPcmTestFormat(48_000, 20) }
    }

    private fun sample(
        bytes: ByteArray,
        frame: Int,
        channel: Int,
        channels: Int,
        bytesPerSample: Int,
        bitDepth: Int,
    ): Int {
        val offset = (frame * channels + channel) * bytesPerSample
        var value = 0
        repeat(bytesPerSample) { index ->
            value = value or ((bytes[offset + index].toInt() and 0xff) shl (index * 8))
        }
        if (bitDepth == Int.SIZE_BITS) return value
        val signBit = 1 shl (bitDepth - 1)
        return if (value and signBit != 0) value or (-1 shl bitDepth) else value
    }
}
