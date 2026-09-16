package dev.naominet.lazer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Bars the playlist indicator draws, and therefore the bands the analyzer reports. */
internal const val AUDIO_LEVEL_BAND_COUNT = 4

/** Capture size the visualizer is configured with; roughly 43 Hz per bin at 44.1 kHz. */
internal const val AUDIO_LEVEL_CAPTURE_SIZE = 1024

/** Capture rate in milliHertz, matched to the frame rate the bars are drawn at. */
internal const val AUDIO_LEVEL_CAPTURE_RATE_MILLIHERTZ = 25_000

/** Lowest and highest band edge. Log spacing follows how music is actually balanced. */
private const val BandLowHz = 60f
private const val BandHighHz = 12_000f

/** A band never falls below this share of the overall loudness, so the bars stay a family. */
private const val BandFloor = 0.35f

/** Display gain that puts ordinary music near the top of the bar without pinning it there. */
private const val LoudnessGain = 2.5f

private const val AttackRate = 0.55f
private const val ReleaseRate = 0.18f

/**
 * Latest per-band loudness of the track the playback service is playing, or null while nothing is
 * captured (feature off, microphone permission missing, or playback stopped). Only the row showing
 * the current track collects this, so the rest of the UI is not recomposed at capture rate.
 */
internal object AndroidAudioLevels {
    private val mutableLevels = MutableStateFlow<List<Float>?>(null)

    val levels: StateFlow<List<Float>?> = mutableLevels.asStateFlow()

    fun publish(values: List<Float>) {
        mutableLevels.value = values
    }

    fun clear() {
        mutableLevels.value = null
    }
}

/**
 * Turns one visualizer capture into [AUDIO_LEVEL_BAND_COUNT] bar heights in `0..1`.
 *
 * The two halves of a capture answer different questions. The waveform is unsigned 8-bit PCM, so
 * its RMS is an honest loudness in which silence really is zero; the FFT only decides how that
 * loudness is spread across the bands. Keeping them apart means the indicator needs no per-device
 * calibration of the FFT's 8-bit magnitude scale.
 */
internal class AudioLevelAnalyzer(
    sampleRateHz: Int,
    captureSize: Int,
    private val bandCount: Int = AUDIO_LEVEL_BAND_COUNT,
) {
    // The kth frequency of the capture is k * sampleRate / captureSize, so a frequency maps back
    // to bin k = frequency * captureSize / sampleRate.
    private val binsPerHz = captureSize / sampleRateHz.toFloat().coerceAtLeast(1f)
    private val lastBin = (captureSize / 2).coerceAtLeast(1)
    private val bandBins = List(bandCount) { band ->
        val lowBin = (bandEdge(band) * binsPerHz).roundToInt().coerceIn(0, lastBin)
        val highBin = (bandEdge(band + 1) * binsPerHz).roundToInt().coerceIn(0, lastBin)
        lowBin..highBin
    }
    private val levels = FloatArray(bandCount)

    fun analyze(waveform: ByteArray, fft: ByteArray): List<Float> {
        val loudness = rms(waveform)
        val energies = FloatArray(bandCount) { band -> averageMagnitude(fft, bandBins[band]) }
        val loudest = energies.maxOrNull() ?: 0f
        for (band in 0 until bandCount) {
            val share = if (loudest > 0f) energies[band] / loudest else 0f
            val target = (loudness * (BandFloor + (1f - BandFloor) * share)).coerceIn(0f, 1f)
            // Fast attack, slow release: the bars answer the music but do not flicker.
            val rate = if (target > levels[band]) AttackRate else ReleaseRate
            levels[band] += (target - levels[band]) * rate
        }
        return levels.toList()
    }

    private fun bandEdge(index: Int): Float {
        val fraction = index / bandCount.toFloat()
        return BandLowHz * (BandHighHz / BandLowHz).pow(fraction)
    }

    private fun averageMagnitude(fft: ByteArray, bins: IntRange): Float {
        if (fft.isEmpty() || bins.isEmpty()) return 0f
        var total = 0f
        for (bin in bins) total += magnitude(fft, bin)
        return total / bins.count()
    }

    /**
     * Magnitudes of the packed real FFT: DC first, then the Nyquist bin, then one real/imaginary
     * pair per frequency up to it.
     */
    private fun magnitude(fft: ByteArray, bin: Int): Float = when (bin) {
        0 -> unsigned(fft, 0).toFloat()
        lastBin -> unsigned(fft, 1).toFloat()
        else -> hypot(unsigned(fft, 2 * bin).toFloat(), unsigned(fft, 2 * bin + 1).toFloat())
    }

    private fun unsigned(fft: ByteArray, index: Int): Int =
        if (index in fft.indices) fft[index].toInt() and 0xFF else 0

    /** Loudness of the capture's unsigned 8-bit PCM, 0 for silence. */
    private fun rms(waveform: ByteArray): Float {
        if (waveform.isEmpty()) return 0f
        var squares = 0.0
        for (sample in waveform) {
            // Unsigned 8-bit PCM is centred on 128, so silence sits at zero once it is recentred.
            val value = ((sample.toInt() and 0xFF) - 128) / 128.0
            squares += value * value
        }
        val loudness = sqrt(squares / waveform.size).toFloat() * LoudnessGain
        return loudness.coerceIn(0f, 1f)
    }
}
