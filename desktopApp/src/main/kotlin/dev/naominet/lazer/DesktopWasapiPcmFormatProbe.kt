package dev.naominet.lazer

import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.Structure.FieldOrder
import com.sun.jna.WString

/** One exact PCM format candidate passed to WASAPI's exclusive-mode format query. */
internal data class DesktopWasapiPcmFormatCandidate(
    val sampleRateHz: Int,
    val channels: Int = 2,
    val containerBits: Int,
    val validBits: Int,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(channels in 1..8) { "channels must be between 1 and 8" }
        require(containerBits in setOf(8, 16, 24, 32)) { "unsupported PCM container width" }
        require(validBits in 1..containerBits) { "validBits must fit in the PCM container" }
    }
}

internal data class DesktopWasapiPcmFormatMode(
    val containerBits: Int,
    val validBits: Int,
)

internal enum class DesktopWasapiPcmFormatProbeStatus {
    Supported,
    Unsupported,
    Error,
}

/**
 * Result of the driver's exact exclusive-format capability query. This does not prove that a
 * stream can be initialized or that samples reach a physical DAC unchanged.
 */
internal data class DesktopWasapiPcmFormatProbeResult(
    val candidate: DesktopWasapiPcmFormatCandidate,
    val status: DesktopWasapiPcmFormatProbeStatus,
    /** Raw native per-candidate HRESULT/status, retained for diagnostics. */
    val nativeStatus: Int,
)

/**
 * Performs an additive WASAPI exclusive-mode PCM capability query. The deterministic default
 * matrix covers common 44.1–768 kHz rates and 16/16, 24/24, 24/32, and 32/32 PCM layouts.
 */
internal object DesktopWasapiPcmFormatProbe {
    private const val SUPPORTED = 0
    private const val UNSUPPORTED = 1
    private const val ERROR = 2
    private const val CANDIDATE_SIZE = 12
    private const val RESULT_SIZE = 8

    val rates: List<Int> = listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000, 705_600, 768_000)
    val formats: List<DesktopWasapiPcmFormatMode> = listOf(
        DesktopWasapiPcmFormatMode(containerBits = 16, validBits = 16),
        DesktopWasapiPcmFormatMode(containerBits = 24, validBits = 24),
        DesktopWasapiPcmFormatMode(containerBits = 32, validBits = 24),
        DesktopWasapiPcmFormatMode(containerBits = 32, validBits = 32),
    )

    val matrix: List<DesktopWasapiPcmFormatCandidate> = rates.flatMap { rate ->
        formats.map { mode ->
            DesktopWasapiPcmFormatCandidate(
                sampleRateHz = rate,
                channels = 2,
                containerBits = mode.containerBits,
                validBits = mode.validBits,
            )
        }
    }

    fun probe(
        endpointId: String,
        candidates: List<DesktopWasapiPcmFormatCandidate> = matrix,
    ): List<DesktopWasapiPcmFormatProbeResult> {
        val api = LazerAudioLoader.library ?: throw DesktopWasapiPcmFormatProbeUnavailableException()
        return probeWithApi(api, endpointId, candidates)
    }

    internal fun probeWithApi(
        api: LazerAudioLibrary,
        endpointId: String,
        candidates: List<DesktopWasapiPcmFormatCandidate>,
    ): List<DesktopWasapiPcmFormatProbeResult> {
        require(endpointId.isNotBlank()) { "endpointId must not be blank" }
        require(candidates.isNotEmpty()) { "at least one PCM candidate is required" }

        val candidateStructSize = LazerAudioPcmFormatCandidate().size()
        val resultStructSize = LazerAudioPcmFormatProbeResultStruct().size()
        check(candidateStructSize == CANDIDATE_SIZE) { "unexpected PCM candidate ABI size: $candidateStructSize" }
        check(resultStructSize == RESULT_SIZE) { "unexpected PCM result ABI size: $resultStructSize" }

        val candidateMemory = Memory(candidateStructSize.toLong() * candidates.size)
        val resultMemory = Memory(resultStructSize.toLong() * candidates.size)
        try {
            candidates.forEachIndexed { index, candidate ->
                LazerAudioPcmFormatCandidate(candidateMemory.share(index.toLong() * candidateStructSize)).apply {
                    sampleRate = candidate.sampleRateHz
                    channels = candidate.channels.toShort()
                    containerBits = candidate.containerBits.toShort()
                    validBits = candidate.validBits.toShort()
                    reserved = 0
                    write()
                }
            }

            val status = try {
                api.lazer_audio_device_probe_pcm_formats(
                    WString(endpointId),
                    candidateMemory,
                    candidates.size,
                    resultMemory,
                )
            } catch (error: UnsatisfiedLinkError) {
                throw DesktopWasapiPcmFormatProbeUnavailableException(error)
            }
            if (status != LAZER_AUDIO_OK) {
                throw DesktopWasapiPcmFormatProbeException(status)
            }

            return candidates.mapIndexed { index, candidate ->
                val native = LazerAudioPcmFormatProbeResultStruct(
                    resultMemory.share(index.toLong() * resultStructSize),
                ).apply { read() }
                DesktopWasapiPcmFormatProbeResult(
                    candidate = candidate,
                    status = decodeSupport(native.status, index),
                    nativeStatus = native.nativeStatus,
                )
            }
        } finally {
            candidateMemory.close()
            resultMemory.close()
        }
    }

    private fun decodeSupport(status: Int, index: Int): DesktopWasapiPcmFormatProbeStatus = when (status) {
        SUPPORTED -> DesktopWasapiPcmFormatProbeStatus.Supported
        UNSUPPORTED -> DesktopWasapiPcmFormatProbeStatus.Unsupported
        ERROR -> DesktopWasapiPcmFormatProbeStatus.Error
        else -> throw DesktopWasapiPcmFormatProbeException(
            errorCode = LAZER_AUDIO_ERROR_DEVICE,
            detail = "unknown result status $status at candidate $index",
        )
    }
}

@FieldOrder("sampleRate", "channels", "containerBits", "validBits", "reserved")
internal open class LazerAudioPcmFormatCandidate : Structure {
    @JvmField var sampleRate: Int = 0
    @JvmField var channels: Short = 0
    @JvmField var containerBits: Short = 0
    @JvmField var validBits: Short = 0
    @JvmField var reserved: Short = 0

    constructor() : super()
    constructor(pointer: Pointer) : super(pointer)
}

@FieldOrder("status", "nativeStatus")
internal open class LazerAudioPcmFormatProbeResultStruct : Structure {
    @JvmField var status: Int = 0
    @JvmField var nativeStatus: Int = 0

    constructor() : super()
    constructor(pointer: Pointer) : super(pointer)
}

internal class DesktopWasapiPcmFormatProbeUnavailableException(cause: Throwable? = null) :
    IllegalStateException("The loaded audio DLL does not provide the optional WASAPI PCM format probe API.", cause)

internal class DesktopWasapiPcmFormatProbeException(
    val errorCode: Int,
    detail: String? = null,
) : IllegalStateException(
    buildString {
        append("WASAPI PCM format probe failed (error ")
        append(errorCode)
        append(')')
        if (detail != null) {
            append(": ")
            append(detail)
        }
    },
)
