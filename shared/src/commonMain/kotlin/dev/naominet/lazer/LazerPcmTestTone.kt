package dev.naominet.lazer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin

/** Integer PCM formats used by the application's quiet output-path test tone. */
data class LazerPcmTestFormat(
    val sampleRateHz: Int,
    val bitDepth: Int,
) {
    init {
        require(sampleRateHz in LazerPcmTestTone.supportedSampleRatesHz) {
            "Unsupported PCM test tone sample rate: $sampleRateHz Hz"
        }
        require(bitDepth in LazerPcmTestTone.supportedBitDepths) {
            "Unsupported PCM test tone bit depth: $bitDepth"
        }
    }
}

/** The generated source is deterministic and deliberately quiet so devices can be checked safely. */
object LazerPcmTestTone {
    const val DURATION_MILLISECONDS = 2_000
    const val FREQUENCY_HZ = 997
    const val PEAK_AMPLITUDE_DBFS = -40.0
    const val FADE_MILLISECONDS = 10

    val supportedSampleRatesHz = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000, 705_600, 768_000)
    val supportedBitDepths = listOf(16, 24, 32)
    val supportedFormats = supportedSampleRatesHz.flatMap { sampleRate ->
        supportedBitDepths.map { bitDepth -> LazerPcmTestFormat(sampleRate, bitDepth) }
    }

    /** Returns interleaved signed PCM samples in little-endian byte order. */
    fun createPcm(sampleRateHz: Int, channels: Int, bitDepth: Int): ByteArray {
        val format = LazerPcmTestFormat(sampleRateHz, bitDepth)
        require(channels in 1..2) { "PCM test tone must be mono or stereo; got $channels channels" }

        val bytesPerSample = format.bitDepth / 8
        val frameCount = format.sampleRateHz * DURATION_MILLISECONDS / 1_000
        val fadeFrames = format.sampleRateHz * FADE_MILLISECONDS / 1_000
        val output = ByteArray(frameCount * channels * bytesPerSample)
        val positiveFullScale = (1L shl (format.bitDepth - 1)) - 1L
        val peakAmplitude = 10.0.pow(PEAK_AMPLITUDE_DBFS / 20.0)
        var offset = 0

        for (frame in 0 until frameCount) {
            val samplesRemaining = frameCount - 1 - frame
            val fadeGain = when {
                frame < fadeFrames -> frame.toDouble() / fadeFrames
                samplesRemaining < fadeFrames -> samplesRemaining.toDouble() / fadeFrames
                else -> 1.0
            }
            val phase = 2.0 * PI * FREQUENCY_HZ * frame / format.sampleRateHz
            val sample = (positiveFullScale * peakAmplitude * fadeGain * sin(phase)).roundToLong().toInt()
            repeat(channels) {
                repeat(bytesPerSample) { byteIndex ->
                    output[offset++] = (sample ushr (byteIndex * 8)).toByte()
                }
            }
        }
        check(offset == output.size) { "PCM test tone size calculation did not match written data" }
        return output
    }
}

enum class LazerPcmTestToneStatus { Idle, Preparing, Playing, Completed, Failed }

enum class LazerPcmTestToneRoute { Unknown, Usb, Wired, Bluetooth, BuiltIn, Hdmi, Other }

enum class LazerMixerPreferenceStatus { NotAvailable, NoExactMatch, NotBitPerfect, Accepted, Rejected }

/** App and HAL observations for a one-shot PCM test; these are not DAC readback or loopback proof. */
data class LazerPcmTestToneSnapshot(
    val status: LazerPcmTestToneStatus = LazerPcmTestToneStatus.Idle,
    val format: LazerPcmTestFormat? = null,
    val route: LazerPcmTestToneRoute = LazerPcmTestToneRoute.Unknown,
    val mixerPreference: LazerMixerPreferenceStatus = LazerMixerPreferenceStatus.NotAvailable,
    val mixerReportsBitPerfectBehavior: Boolean? = null,
    val detailKey: String? = null,
    val availableFormats: List<LazerPcmTestFormat> = listOf(
        LazerPcmTestFormat(44_100, 16),
        LazerPcmTestFormat(48_000, 16),
    ),
    val connectedUsbOutputs: Int = 0,
)

object LazerPcmTestToneStateStore {
    private val mutableState = MutableStateFlow(LazerPcmTestToneSnapshot())
    val state: StateFlow<LazerPcmTestToneSnapshot> = mutableState.asStateFlow()

    fun publish(snapshot: LazerPcmTestToneSnapshot) {
        mutableState.value = snapshot
    }
}
