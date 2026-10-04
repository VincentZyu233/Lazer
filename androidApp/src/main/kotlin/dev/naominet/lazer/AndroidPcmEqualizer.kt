package dev.naominet.lazer

import androidx.media3.common.C
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.roundToInt
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

private const val ANDROID_LIMITER_THRESHOLD_DB = -1.0
private const val ANDROID_LIMITER_RELEASE_SECONDS = 0.12

internal fun isPcmEqualizerSupportedEncoding(encoding: Int): Boolean = when (encoding) {
    C.ENCODING_PCM_FLOAT,
    C.ENCODING_PCM_16BIT,
    C.ENCODING_PCM_24BIT,
    C.ENCODING_PCM_32BIT,
    -> true
    else -> false
}

/** True when the Android output wrapper will process samples for this state and output format. */
internal fun androidPcmDspMayModifySamples(
    state: LazerEqualizerState,
    sampleRateHz: Int,
    channelCount: Int,
    encoding: Int,
    offload: Boolean = false,
    tunneling: Boolean = false,
): Boolean {
    if (
        sampleRateHz <= 0 || channelCount !in 1..2 ||
        !isPcmEqualizerSupportedEncoding(encoding) || offload || tunneling
    ) return false

    val hasApplicableEq = state.enabled && state.bands.any { band ->
        band.enabled && FloatBiquadCoefficients.forBand(band, sampleRateHz) != null
    }
    val hasPreamp = state.enabled && 10.0.pow(state.preampDb / 20.0) != 1.0
    return state.limiterEnabled || hasApplicableEq || hasPreamp
}

/**
 * Float PCM equalizer used at Media3's AudioOutput boundary. It leaves input bytes untouched and
 * returns null when bypassed, so the forwarding output can pass the original buffer through.
 */
internal class AndroidPcmEqualizer(initialState: LazerEqualizerState = LazerEqualizerState()) {
    private val requestedState = AtomicReference(initialState.copy(bands = initialState.bands.toList()))

    // All fields below are confined to the ExoPlayer audio output thread.
    private var activeState: LazerEqualizerState? = null
    private var activeSampleRateHz = 0
    private var activeChannelCount = 0
    private var activeEncoding = -1
    private var coefficients: List<FloatBiquadCoefficients> = emptyList()
    private var states: List<FloatBiquadState> = emptyList()
    private var preamp = 1.0
    private var processingEnabled = false
    private var limiterThreshold = 1.0
    private var limiterReleaseCoefficient = 0.0
    private var limiterEnvelope = 1.0
    private var ditherState = 0x9e3779b97f4a7c15UL.toLong()
    private var copyBuffer: ByteBuffer? = null

    fun update(state: LazerEqualizerState) {
        requestedState.set(state.copy(bands = state.bands.toList()))
    }

    fun mayModifySamples(
        sampleRateHz: Int,
        channelCount: Int,
        encoding: Int,
        offload: Boolean,
        tunneling: Boolean,
    ): Boolean = androidPcmDspMayModifySamples(
        requestedState.get(), sampleRateHz, channelCount, encoding, offload, tunneling,
    )

    /** Returns a writable direct copy with EQ applied, or null when this stream is bypassed. */
    fun processCopy(input: ByteBuffer, sampleRateHz: Int, channelCount: Int, encoding: Int): ByteBuffer? {
        if (!input.isDirect || sampleRateHz <= 0 || channelCount !in 1..2) return null
        if (!isPcmEqualizerSupportedEncoding(encoding)) return null
        val bytesPerSample = when (encoding) {
            C.ENCODING_PCM_FLOAT, C.ENCODING_PCM_32BIT -> Int.SIZE_BYTES
            C.ENCODING_PCM_24BIT -> 3
            C.ENCODING_PCM_16BIT -> Short.SIZE_BYTES
            else -> error("Supported PCM encoding missing byte width: $encoding")
        }
        if (input.remaining() % (bytesPerSample * channelCount) != 0) return null
        configureIfNeeded(requestedState.get(), sampleRateHz, channelCount, encoding)
        if (!processingEnabled) return null

        val source = input.duplicate()
        val byteCount = source.remaining()
        val result = copyBuffer?.takeIf { it.capacity() >= byteCount }
            ?: ByteBuffer.allocateDirect(byteCount).also { copyBuffer = it }
        result.order(ByteOrder.nativeOrder())
        result.clear()
        result.limit(byteCount)
        result.put(source)
        result.flip()

        when (encoding) {
            C.ENCODING_PCM_FLOAT -> {
                val floatSamples = result.asFloatBuffer()
                val frameCount = floatSamples.remaining() / channelCount
                for (frame in 0 until frameCount) {
                    var left = transform(floatSamples.get().toDouble(), 0)
                    var right = if (channelCount == 2) transform(floatSamples.get().toDouble(), 1) else 0.0
                    val gain = limiterGain(left, right)
                    left *= gain
                    right *= gain
                    floatSamples.put(frame * channelCount, left.toFloat())
                    if (channelCount == 2) floatSamples.put(frame * channelCount + 1, right.toFloat())
                }
            }
            C.ENCODING_PCM_16BIT -> {
                val integerSamples = result.asShortBuffer()
                val frameCount = integerSamples.remaining() / channelCount
                for (frame in 0 until frameCount) {
                    val index = frame * channelCount
                    var left = transform(integerSamples.get().toDouble() / 32768.0, 0)
                    var right = if (channelCount == 2) transform(integerSamples.get().toDouble() / 32768.0, 1) else 0.0
                    val gain = limiterGain(left, right)
                    left *= gain
                    right *= gain
                    // TPDF is applied only at this explicit float-to-PCM16 quantization step.
                    val leftDither = (nextDitherUnit() - nextDitherUnit()) / 32768.0
                    val leftQuantized = ((left + leftDither) * 32768.0).roundToInt()
                        .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    integerSamples.put(index, leftQuantized.toShort())
                    if (channelCount == 2) {
                        val rightDither = (nextDitherUnit() - nextDitherUnit()) / 32768.0
                        val rightQuantized = ((right + rightDither) * 32768.0).roundToInt()
                            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                        integerSamples.put(index + 1, rightQuantized.toShort())
                    }
                }
            }
            C.ENCODING_PCM_24BIT -> {
                val sampleCount = byteCount / 3
                val frameCount = sampleCount / channelCount
                for (frame in 0 until frameCount) {
                    val leftIndex = frame * channelCount * 3
                    val rightIndex = leftIndex + 3
                    var left = transform(readPcm24(result, leftIndex) / 8_388_608.0, 0)
                    var right = if (channelCount == 2) transform(readPcm24(result, rightIndex) / 8_388_608.0, 1) else 0.0
                    val gain = limiterGain(left, right)
                    left *= gain
                    right *= gain
                    val leftDither = (nextDitherUnit() - nextDitherUnit()) / 8_388_608.0
                    val leftQuantized = ((left + leftDither) * 8_388_608.0).roundToInt()
                        .coerceIn(-8_388_608, 8_388_607)
                    writePcm24(result, leftIndex, leftQuantized)
                    if (channelCount == 2) {
                        val rightDither = (nextDitherUnit() - nextDitherUnit()) / 8_388_608.0
                        val rightQuantized = ((right + rightDither) * 8_388_608.0).roundToInt()
                            .coerceIn(-8_388_608, 8_388_607)
                        writePcm24(result, rightIndex, rightQuantized)
                    }
                }
            }
            C.ENCODING_PCM_32BIT -> {
                val integerSamples = result.asIntBuffer()
                val frameCount = integerSamples.remaining() / channelCount
                for (frame in 0 until frameCount) {
                    val index = frame * channelCount
                    var left = transform(integerSamples.get(index) / 2_147_483_648.0, 0)
                    var right = if (channelCount == 2) transform(integerSamples.get(index + 1) / 2_147_483_648.0, 1) else 0.0
                    val gain = limiterGain(left, right)
                    left *= gain
                    right *= gain
                    val leftDither = (nextDitherUnit() - nextDitherUnit()) / 2_147_483_648.0
                    val leftQuantized = ((left + leftDither) * 2_147_483_648.0).roundToLong()
                        .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                    integerSamples.put(index, leftQuantized.toInt())
                    if (channelCount == 2) {
                        val rightDither = (nextDitherUnit() - nextDitherUnit()) / 2_147_483_648.0
                        val rightQuantized = ((right + rightDither) * 2_147_483_648.0).roundToLong()
                            .coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong())
                        integerSamples.put(index + 1, rightQuantized.toInt())
                    }
                }
            }
        }
        return result
    }

    private fun readPcm24(buffer: ByteBuffer, index: Int): Int {
        val first = buffer.get(index).toInt() and 0xff
        val second = buffer.get(index + 1).toInt() and 0xff
        val third = buffer.get(index + 2).toInt() and 0xff
        val unsigned = if (buffer.order() == ByteOrder.LITTLE_ENDIAN) {
            first or (second shl 8) or (third shl 16)
        } else {
            (first shl 16) or (second shl 8) or third
        }
        return (unsigned shl 8) shr 8
    }

    private fun writePcm24(buffer: ByteBuffer, index: Int, sample: Int) {
        val packed = sample and 0x00ff_ffff
        if (buffer.order() == ByteOrder.LITTLE_ENDIAN) {
            buffer.put(index, packed.toByte())
            buffer.put(index + 1, (packed ushr 8).toByte())
            buffer.put(index + 2, (packed ushr 16).toByte())
        } else {
            buffer.put(index, (packed ushr 16).toByte())
            buffer.put(index + 1, (packed ushr 8).toByte())
            buffer.put(index + 2, packed.toByte())
        }
    }

    /** Clears filter history at seek, flush, or stream reconfiguration boundaries. */
    fun resetStream() {
        activeState = null
        activeSampleRateHz = 0
        activeChannelCount = 0
        activeEncoding = -1
        coefficients = emptyList()
        states = emptyList()
        processingEnabled = false
        limiterEnvelope = 1.0
    }

    private fun configureIfNeeded(state: LazerEqualizerState, sampleRateHz: Int, channels: Int, encoding: Int) {
        if (
            activeState == state && activeSampleRateHz == sampleRateHz &&
            activeChannelCount == channels && activeEncoding == encoding
        ) return
        activeState = state
        activeSampleRateHz = sampleRateHz
        activeChannelCount = channels
        activeEncoding = encoding
        val enabledBands = if (state.enabled) state.bands.filter(LazerEqBand::enabled) else emptyList()
        coefficients = enabledBands.mapNotNull { FloatBiquadCoefficients.forBand(it, sampleRateHz) }
        states = coefficients.map { FloatBiquadState(channels) }
        preamp = if (state.enabled) 10.0.pow(state.preampDb / 20.0) else 1.0
        processingEnabled = androidPcmDspMayModifySamples(state, sampleRateHz, channels, encoding)
        limiterThreshold = 10.0.pow(ANDROID_LIMITER_THRESHOLD_DB / 20.0)
        limiterReleaseCoefficient = exp(-1.0 / (ANDROID_LIMITER_RELEASE_SECONDS * sampleRateHz))
        limiterEnvelope = 1.0
    }

    private fun transform(input: Double, channel: Int): Double {
        var sample = input * preamp
        for (index in coefficients.indices) {
            sample = states[index].process(coefficients[index], sample, channel)
        }
        if (!sample.isFinite()) sample = 0.0
        return sample
    }

    /** Immediate sample-peak attack, 120 ms exponential release, and one gain linked across channels. */
    private fun limiterGain(left: Double, right: Double): Double {
        if (activeState?.limiterEnabled != true) return 1.0
        val peak = max(abs(left), abs(right))
        val desired = if (peak > limiterThreshold) limiterThreshold / peak else 1.0
        limiterEnvelope = if (desired < limiterEnvelope) {
            // Immediate attack ensures no sample exceeds the -1 dBFS ceiling.
            desired
        } else {
            limiterReleaseCoefficient * limiterEnvelope + (1.0 - limiterReleaseCoefficient) * desired
        }
        return min(min(limiterEnvelope, desired), 1.0)
    }

    private fun nextDitherUnit(): Double {
        var value = ditherState
        value = value xor (value shl 13)
        value = value xor (value ushr 7)
        value = value xor (value shl 17)
        ditherState = value
        return (value ushr 11).toDouble() / 9_007_199_254_740_992.0
    }
}

private data class FloatBiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    companion object {
        fun forBand(band: LazerEqBand, sampleRateHz: Int): FloatBiquadCoefficients? {
            if (sampleRateHz <= 0 || band.frequencyHz <= 0.0) return null
            val frequency = band.frequencyHz.coerceIn(10.0, sampleRateHz * 0.495)
            val q = band.q.coerceIn(0.1, 10.0)
            val omega = 2.0 * PI * frequency / sampleRateHz
            val cosine = cos(omega)
            val sine = sin(omega)
            val alpha = sine / (2.0 * q)
            val amplitude = 10.0.pow(band.gainDb / 40.0)
            val raw = when (band.kind) {
                LazerEqBandKind.Peak -> doubleArrayOf(
                    1.0 + alpha * amplitude,
                    -2.0 * cosine,
                    1.0 - alpha * amplitude,
                    1.0 + alpha / amplitude,
                    -2.0 * cosine,
                    1.0 - alpha / amplitude,
                )
                LazerEqBandKind.LowShelf -> {
                    val sqrtAmplitude = sqrt(amplitude)
                    val shelfAlpha = sine / 2.0 * sqrt(2.0)
                    doubleArrayOf(
                        amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine + 2.0 * sqrtAmplitude * shelfAlpha),
                        2.0 * amplitude * ((amplitude - 1.0) - (amplitude + 1.0) * cosine),
                        amplitude * ((amplitude + 1.0) - (amplitude - 1.0) * cosine - 2.0 * sqrtAmplitude * shelfAlpha),
                        (amplitude + 1.0) + (amplitude - 1.0) * cosine + 2.0 * sqrtAmplitude * shelfAlpha,
                        -2.0 * ((amplitude - 1.0) + (amplitude + 1.0) * cosine),
                        (amplitude + 1.0) + (amplitude - 1.0) * cosine - 2.0 * sqrtAmplitude * shelfAlpha,
                    )
                }
                LazerEqBandKind.HighShelf -> {
                    val sqrtAmplitude = sqrt(amplitude)
                    val shelfAlpha = sine / 2.0 * sqrt(2.0)
                    doubleArrayOf(
                        amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine + 2.0 * sqrtAmplitude * shelfAlpha),
                        -2.0 * amplitude * ((amplitude - 1.0) + (amplitude + 1.0) * cosine),
                        amplitude * ((amplitude + 1.0) + (amplitude - 1.0) * cosine - 2.0 * sqrtAmplitude * shelfAlpha),
                        (amplitude + 1.0) - (amplitude - 1.0) * cosine + 2.0 * sqrtAmplitude * shelfAlpha,
                        2.0 * ((amplitude - 1.0) - (amplitude + 1.0) * cosine),
                        (amplitude + 1.0) - (amplitude - 1.0) * cosine - 2.0 * sqrtAmplitude * shelfAlpha,
                    )
                }
                LazerEqBandKind.LowPass -> doubleArrayOf(
                    (1.0 - cosine) / 2.0,
                    1.0 - cosine,
                    (1.0 - cosine) / 2.0,
                    1.0 + alpha,
                    -2.0 * cosine,
                    1.0 - alpha,
                )
                LazerEqBandKind.HighPass -> doubleArrayOf(
                    (1.0 + cosine) / 2.0,
                    -(1.0 + cosine),
                    (1.0 + cosine) / 2.0,
                    1.0 + alpha,
                    -2.0 * cosine,
                    1.0 - alpha,
                )
                LazerEqBandKind.Notch -> doubleArrayOf(
                    1.0,
                    -2.0 * cosine,
                    1.0,
                    1.0 + alpha,
                    -2.0 * cosine,
                    1.0 - alpha,
                )
                LazerEqBandKind.AllPass -> doubleArrayOf(
                    1.0 - alpha,
                    -2.0 * cosine,
                    1.0 + alpha,
                    1.0 + alpha,
                    -2.0 * cosine,
                    1.0 - alpha,
                )
            }
            val a0 = raw[3]
            if (a0 == 0.0) return null
            return FloatBiquadCoefficients(
                b0 = raw[0] / a0,
                b1 = raw[1] / a0,
                b2 = raw[2] / a0,
                a1 = raw[4] / a0,
                a2 = raw[5] / a0,
            )
        }
    }
}

private class FloatBiquadState(channels: Int) {
    private val x1 = DoubleArray(channels)
    private val x2 = DoubleArray(channels)
    private val y1 = DoubleArray(channels)
    private val y2 = DoubleArray(channels)

    fun process(coefficients: FloatBiquadCoefficients, input: Double, channel: Int): Double {
        val output = coefficients.b0 * input + coefficients.b1 * x1[channel] + coefficients.b2 * x2[channel] -
            coefficients.a1 * y1[channel] - coefficients.a2 * y2[channel]
        x2[channel] = x1[channel]
        x1[channel] = input
        y2[channel] = y1[channel]
        y1[channel] = output
        return output
    }
}
