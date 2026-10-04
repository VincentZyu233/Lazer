package dev.naominet.lazer

import javax.sound.sampled.AudioFormat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * In-process equalizer for the Java Sound path. The native engine applies its own EQ in C++, but the
 * shared/software output has no such stage, so the same curve is realised here as an RBJ biquad
 * cascade. It is deliberately only ever handed signed 16-bit PCM, which is what JavaMP3 decodes to.
 */
internal class PcmEqualizer {
    private var appliedState: LazerEqualizerState? = null
    private var appliedSampleRate = 0
    private var appliedChannels = 0
    private var preamp = 1.0
    private var active = false
    private var coefficients: List<BiquadCoefficients> = emptyList()
    private var states: List<BiquadState> = emptyList()

    fun process(buffer: ByteArray, byteCount: Int, format: AudioFormat, state: LazerEqualizerState) {
        if (format.encoding != AudioFormat.Encoding.PCM_SIGNED || format.sampleSizeInBits != 16) return
        configure(
            state = state,
            sampleRate = format.sampleRate.toInt().coerceAtLeast(1),
            channels = format.channels.coerceAtLeast(1),
        )
        if (!active) return
        val channels = format.channels.coerceAtLeast(1)
        val frameSize = format.frameSize
        if (frameSize < channels * 2) return
        val safeCount = byteCount.coerceIn(0, buffer.size)
        val frames = safeCount / frameSize
        if (frames <= 0) return
        val bigEndian = format.isBigEndian
        for (frame in 0 until frames) {
            val base = frame * frameSize
            for (channel in 0 until channels) {
                val offset = base + channel * 2
                val first = buffer[offset].toInt() and 0xFF
                val second = buffer[offset + 1].toInt() and 0xFF
                val raw = if (bigEndian) (first shl 8) or second else (second shl 8) or first
                var sample = raw.toShort().toInt() / 32768.0 * preamp
                for (index in coefficients.indices) {
                    sample = states[index].process(coefficients[index], sample, channel)
                }
                val clamped = (sample * 32767.0)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                if (bigEndian) {
                    buffer[offset] = (clamped shr 8).toByte()
                    buffer[offset + 1] = clamped.toByte()
                } else {
                    buffer[offset] = clamped.toByte()
                    buffer[offset + 1] = (clamped shr 8).toByte()
                }
            }
        }
    }

    private fun configure(state: LazerEqualizerState, sampleRate: Int, channels: Int) {
        if (appliedState == state && appliedSampleRate == sampleRate && appliedChannels == channels) return
        appliedState = state
        appliedSampleRate = sampleRate
        appliedChannels = channels
        val bands = if (state.enabled) state.bands.filter(LazerEqBand::enabled) else emptyList()
        coefficients = bands.mapNotNull { BiquadCoefficients.forBand(it, sampleRate) }
        states = coefficients.map { BiquadState(channels) }
        preamp = if (state.enabled) 10.0.pow(state.preampDb / 20.0) else 1.0
        active = coefficients.isNotEmpty() || (state.enabled && preamp != 1.0)
    }
}

/** Normalised biquad coefficients, `a0` already divided out. */
private class BiquadCoefficients(
    val b0: Double,
    val b1: Double,
    val b2: Double,
    val a1: Double,
    val a2: Double,
) {
    companion object {
        fun forBand(band: LazerEqBand, sampleRate: Int): BiquadCoefficients? {
            if (sampleRate <= 0 || band.frequencyHz <= 0.0) return null
            val nyquist = sampleRate / 2.0
            val frequency = band.frequencyHz.coerceIn(10.0, nyquist * 0.99)
            val q = band.q.coerceIn(0.1, 10.0)
            val w0 = 2.0 * PI * frequency / sampleRate
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2.0 * q)
            val a = 10.0.pow(band.gainDb / 40.0)
            val raw = when (band.kind) {
                LazerEqBandKind.Peak -> doubleArrayOf(
                    1.0 + alpha * a,
                    -2.0 * cosW0,
                    1.0 - alpha * a,
                    1.0 + alpha / a,
                    -2.0 * cosW0,
                    1.0 - alpha / a,
                )
                LazerEqBandKind.LowShelf -> {
                    val sqrtA = sqrt(a)
                    val shelfAlpha = sinW0 / 2.0 * sqrt(2.0)
                    doubleArrayOf(
                        a * ((a + 1.0) - (a - 1.0) * cosW0 + 2.0 * sqrtA * shelfAlpha),
                        2.0 * a * ((a - 1.0) - (a + 1.0) * cosW0),
                        a * ((a + 1.0) - (a - 1.0) * cosW0 - 2.0 * sqrtA * shelfAlpha),
                        (a + 1.0) + (a - 1.0) * cosW0 + 2.0 * sqrtA * shelfAlpha,
                        -2.0 * ((a - 1.0) + (a + 1.0) * cosW0),
                        (a + 1.0) + (a - 1.0) * cosW0 - 2.0 * sqrtA * shelfAlpha,
                    )
                }
                LazerEqBandKind.HighShelf -> {
                    val sqrtA = sqrt(a)
                    val shelfAlpha = sinW0 / 2.0 * sqrt(2.0)
                    doubleArrayOf(
                        a * ((a + 1.0) + (a - 1.0) * cosW0 + 2.0 * sqrtA * shelfAlpha),
                        -2.0 * a * ((a - 1.0) + (a + 1.0) * cosW0),
                        a * ((a + 1.0) + (a - 1.0) * cosW0 - 2.0 * sqrtA * shelfAlpha),
                        (a + 1.0) - (a - 1.0) * cosW0 + 2.0 * sqrtA * shelfAlpha,
                        2.0 * ((a - 1.0) - (a + 1.0) * cosW0),
                        (a + 1.0) - (a - 1.0) * cosW0 - 2.0 * sqrtA * shelfAlpha,
                    )
                }
                LazerEqBandKind.LowPass -> doubleArrayOf(
                    (1.0 - cosW0) / 2.0,
                    1.0 - cosW0,
                    (1.0 - cosW0) / 2.0,
                    1.0 + alpha,
                    -2.0 * cosW0,
                    1.0 - alpha,
                )
                LazerEqBandKind.HighPass -> doubleArrayOf(
                    (1.0 + cosW0) / 2.0,
                    -(1.0 + cosW0),
                    (1.0 + cosW0) / 2.0,
                    1.0 + alpha,
                    -2.0 * cosW0,
                    1.0 - alpha,
                )
                LazerEqBandKind.Notch -> doubleArrayOf(
                    1.0,
                    -2.0 * cosW0,
                    1.0,
                    1.0 + alpha,
                    -2.0 * cosW0,
                    1.0 - alpha,
                )
                LazerEqBandKind.AllPass -> doubleArrayOf(
                    1.0 - alpha,
                    -2.0 * cosW0,
                    1.0 + alpha,
                    1.0 + alpha,
                    -2.0 * cosW0,
                    1.0 - alpha,
                )
            }
            val a0 = raw[3]
            if (a0 == 0.0) return null
            return BiquadCoefficients(
                b0 = raw[0] / a0,
                b1 = raw[1] / a0,
                b2 = raw[2] / a0,
                a1 = raw[4] / a0,
                a2 = raw[5] / a0,
            )
        }
    }
}

/** Direct-form-I history, one slot set per channel. */
private class BiquadState(private val channels: Int) {
    private val x1 = DoubleArray(channels)
    private val x2 = DoubleArray(channels)
    private val y1 = DoubleArray(channels)
    private val y2 = DoubleArray(channels)

    fun process(coefficients: BiquadCoefficients, input: Double, channel: Int): Double {
        val index = channel.coerceIn(0, channels - 1)
        val output = coefficients.b0 * input +
            coefficients.b1 * x1[index] +
            coefficients.b2 * x2[index] -
            coefficients.a1 * y1[index] -
            coefficients.a2 * y2[index]
        x2[index] = x1[index]
        x1[index] = input
        y2[index] = y1[index]
        y1[index] = output
        return output
    }
}
