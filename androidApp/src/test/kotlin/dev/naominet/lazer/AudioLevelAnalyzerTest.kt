package dev.naominet.lazer

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class AudioLevelAnalyzerTest {
    @Test
    fun fullSpectrumAudioRaisesEveryBar() {
        val levels = repeatAnalyze(loudWaveform(), fullSpectrum(), times = 24)

        assertEquals(AUDIO_LEVEL_BAND_COUNT, levels.size)
        levels.forEach { level -> assertTrue(level > 0.9f, "expected a raised bar, got $level") }
    }

    @Test
    fun silencePullsTheBarsBackDown() {
        val analyzer = analyzer()
        repeat(24) { analyzer.analyze(loudWaveform(), fullSpectrum()) }

        var levels = emptyList<Float>()
        repeat(60) { levels = analyzer.analyze(silentWaveform(), ByteArray(CAPTURE_SIZE)) }

        levels.forEach { level -> assertTrue(level < 0.05f, "expected a settled bar, got $level") }
    }

    @Test
    fun lowFrequencyEnergyLeansOnTheFirstBar() {
        val fft = ByteArray(CAPTURE_SIZE)
        // Bins 1..5 are inside the lowest band; the packed layout puts bin k at 2k and 2k + 1.
        for (bin in 1..5) {
            fft[2 * bin] = 255.toByte()
            fft[2 * bin + 1] = 255.toByte()
        }

        val levels = repeatAnalyze(loudWaveform(), fft, times = 24)

        assertTrue(levels.first() > levels.last() * 1.5f, "expected a bass-weighted shape, got $levels")
    }

    @Test
    fun barsStayWithinTheirRangeWhateverArrives() {
        val analyzer = analyzer()
        val quiet = ByteArray(CAPTURE_SIZE) { 130.toByte() }

        var levels = emptyList<Float>()
        repeat(40) { index ->
            val waveform = if (index % 3 == 0) quiet else loudWaveform()
            levels = analyzer.analyze(waveform, fullSpectrum())
        }

        levels.forEach { level -> assertTrue(level in 0f..1f, "expected 0..1, got $level") }
    }

    private fun repeatAnalyze(waveform: ByteArray, fft: ByteArray, times: Int): List<Float> {
        val analyzer = analyzer()
        var levels = emptyList<Float>()
        repeat(times) { levels = analyzer.analyze(waveform, fft) }
        return levels
    }

    private fun analyzer() = AudioLevelAnalyzer(sampleRateHz = SAMPLE_RATE_HZ, captureSize = CAPTURE_SIZE)

    /** Unsigned 8-bit PCM centred on 128 is silence; alternating 0/255 is a full-scale square. */
    private fun silentWaveform() = ByteArray(CAPTURE_SIZE) { 128.toByte() }

    private fun loudWaveform() = ByteArray(CAPTURE_SIZE) { index -> if (index % 2 == 0) 0 else 255.toByte() }

    private fun fullSpectrum() = ByteArray(CAPTURE_SIZE) { 255.toByte() }

    private companion object {
        const val SAMPLE_RATE_HZ = 44_100
        const val CAPTURE_SIZE = 1024
    }
}
