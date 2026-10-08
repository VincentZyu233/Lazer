package dev.naominet.lazer

import com.sun.jna.Pointer
import com.sun.jna.WString
import java.nio.file.Files
import java.nio.file.Path

/**
 * Outcome of opening one exact PCM format in a WASAPI exclusive session.
 *
 * [Initialized] means only that WASAPI successfully initialized a session whose reported format
 * matches the candidate and whose native engine reports its PCM bit-perfect eligibility flag. This
 * probe never starts playback and proves neither DAC lock nor bit-perfect samples at the DAC.
 */
internal enum class DesktopWasapiPcmSessionProbeStatus {
    Initialized,
    Unsupported,
    Error,
}

/** Stable, user-safe categories suitable for mapping to localized UI strings. */
internal enum class DesktopWasapiPcmSessionProbeFailure(val messageKey: String) {
    UnsupportedFormat("hifi.session_probe.failure.unsupported"),
    DeviceOrRuntime("hifi.session_probe.failure.device_or_runtime"),
    EngineUnavailable("hifi.session_probe.failure.engine_unavailable"),
    SourceFixture("hifi.session_probe.failure.source_fixture"),
    StreamInfoUnavailable("hifi.session_probe.failure.stream_info"),
    SessionFormatMismatch("hifi.session_probe.failure.session_mismatch"),
    NativeCallFailed("hifi.session_probe.failure.native_call"),
}

/** The format returned by the successfully initialized WASAPI session, when present. */
internal data class DesktopWasapiPcmSessionFormat(
    val sampleRateHz: Int,
    val channels: Int,
    val validBits: Int,
    val containerBits: Int,
    val isFloat: Boolean,
    val exclusive: Boolean,
)

internal data class DesktopWasapiPcmSessionProbeResult(
    val candidate: DesktopWasapiPcmFormatCandidate,
    val status: DesktopWasapiPcmSessionProbeStatus,
    val actualSessionFormat: DesktopWasapiPcmSessionFormat? = null,
    val failure: DesktopWasapiPcmSessionProbeFailure? = null,
    /** Native engine volume-setup return code, if setup failed before opening the WAV. */
    val setupStatus: Int? = null,
    /** Native open return code, retained for diagnostics. Null means open was not attempted. */
    val openStatus: Int? = null,
    /** Native stream-info return code, retained for diagnostics. */
    val streamInfoStatus: Int? = null,
)

/**
 * Opens a temporary silent PCM WAV through the production native engine, one candidate at a time.
 * It uses the existing 40-format matrix, requests the exact endpoint in WASAPI exclusive bit-perfect
 * mode, reads the initialized session snapshot, and destroys every engine and temporary file.
 * No playback call is made. Callers should stop this application's active output first; an endpoint
 * held by this app or another process can make exclusive initialization fail, which is returned as
 * a per-candidate device/runtime error rather than aborting the batch.
 */
internal object DesktopWasapiPcmSessionProbe {
    fun probe(
        endpointId: String,
        candidates: List<DesktopWasapiPcmFormatCandidate> = DesktopWasapiPcmFormatProbe.matrix,
    ): List<DesktopWasapiPcmSessionProbeResult> {
        require(endpointId.isNotBlank()) { "endpointId must not be blank" }
        require(candidates.isNotEmpty()) { "at least one PCM candidate is required" }
        val api = LazerAudioLoader.library ?: throw DesktopWasapiPcmSessionProbeUnavailableException()
        return probeWithApi(api, endpointId, candidates)
    }

    internal fun probeWithApi(
        api: LazerAudioLibrary,
        endpointId: String,
        candidates: List<DesktopWasapiPcmFormatCandidate>,
    ): List<DesktopWasapiPcmSessionProbeResult> {
        require(endpointId.isNotBlank()) { "endpointId must not be blank" }
        require(candidates.isNotEmpty()) { "at least one PCM candidate is required" }
        return candidates.map { candidate -> probeCandidate(api, endpointId, candidate) }
    }

    internal fun classify(
        candidate: DesktopWasapiPcmFormatCandidate,
        openStatus: Int,
        streamInfoStatus: Int?,
        info: LazerAudioStreamInfo?,
    ): DesktopWasapiPcmSessionProbeResult {
        if (openStatus == LAZER_AUDIO_ERROR_UNSUPPORTED) {
            return DesktopWasapiPcmSessionProbeResult(
                candidate = candidate,
                status = DesktopWasapiPcmSessionProbeStatus.Unsupported,
                failure = DesktopWasapiPcmSessionProbeFailure.UnsupportedFormat,
                openStatus = openStatus,
                streamInfoStatus = streamInfoStatus,
            )
        }
        if (openStatus != LAZER_AUDIO_OK) {
            return DesktopWasapiPcmSessionProbeResult(
                candidate = candidate,
                status = DesktopWasapiPcmSessionProbeStatus.Error,
                failure = failureForNativeStatus(openStatus),
                openStatus = openStatus,
                streamInfoStatus = streamInfoStatus,
            )
        }
        if (streamInfoStatus != LAZER_AUDIO_OK || info == null) {
            return DesktopWasapiPcmSessionProbeResult(
                candidate = candidate,
                status = DesktopWasapiPcmSessionProbeStatus.Error,
                failure = DesktopWasapiPcmSessionProbeFailure.StreamInfoUnavailable,
                openStatus = openStatus,
                streamInfoStatus = streamInfoStatus,
            )
        }

        val sessionFormat = info.toSessionFormatOrNull()
        val exact = info.sourceFormatKind == LAZER_AUDIO_SOURCE_FORMAT_PCM &&
            info.sourceSampleRate == candidate.sampleRateHz &&
            info.sourceChannels == candidate.channels &&
            info.sourceBitsPerSample == candidate.validBits &&
            info.lossless != 0 &&
            info.outputFormatInitialized != 0 &&
            info.outputExclusive != 0 &&
            info.outputSampleRate == candidate.sampleRateHz &&
            info.outputChannels == candidate.channels &&
            info.outputBitsPerSample == candidate.validBits &&
            info.outputContainerBitsPerSample == candidate.containerBits &&
            info.outputIsFloat == 0 &&
            info.bitPerfectActive != 0

        return if (exact) {
            DesktopWasapiPcmSessionProbeResult(
                candidate = candidate,
                status = DesktopWasapiPcmSessionProbeStatus.Initialized,
                actualSessionFormat = sessionFormat,
                openStatus = openStatus,
                streamInfoStatus = streamInfoStatus,
            )
        } else {
            DesktopWasapiPcmSessionProbeResult(
                candidate = candidate,
                status = DesktopWasapiPcmSessionProbeStatus.Error,
                actualSessionFormat = sessionFormat,
                failure = DesktopWasapiPcmSessionProbeFailure.SessionFormatMismatch,
                openStatus = openStatus,
                streamInfoStatus = streamInfoStatus,
            )
        }
    }

    private fun probeCandidate(
        api: LazerAudioLibrary,
        endpointId: String,
        candidate: DesktopWasapiPcmFormatCandidate,
    ): DesktopWasapiPcmSessionProbeResult {
        var wavPath: Path? = null
        var engine: Pointer? = null
        try {
            wavPath = Files.createTempFile(
                "lazer-wasapi-${candidate.sampleRateHz}-${candidate.validBits}-",
                ".wav",
            )
            Files.write(wavPath, DesktopWasapiPcmSessionProbeWav.create(candidate))
        } catch (_: Exception) {
            wavPath?.let { runCatching { Files.deleteIfExists(it) } }
            return DesktopWasapiPcmSessionProbeResult(
                candidate = candidate,
                status = DesktopWasapiPcmSessionProbeStatus.Error,
                failure = DesktopWasapiPcmSessionProbeFailure.SourceFixture,
            )
        }

        try {
            val deviceId = WString(endpointId)
            val config = LazerAudioEngineConfig().apply {
                structSize = size()
                device = LazerAudioDeviceConfig().apply {
                    this.deviceId = deviceId
                    exclusive = 1
                    resampleMode = LAZER_AUDIO_RESAMPLE_NATIVE
                    targetSampleRate = 0
                    bufferMillis = 120
                    bitPerfect = 1
                }
            }
            engine = try {
                api.lazer_audio_create(config)
            } catch (_: UnsatisfiedLinkError) {
                null
            } ?: return DesktopWasapiPcmSessionProbeResult(
                candidate = candidate,
                status = DesktopWasapiPcmSessionProbeStatus.Error,
                failure = DesktopWasapiPcmSessionProbeFailure.EngineUnavailable,
            )

            val volumeStatus = api.lazer_audio_set_volume(engine, 1.0)
            if (volumeStatus != LAZER_AUDIO_OK) {
                return DesktopWasapiPcmSessionProbeResult(
                    candidate = candidate,
                    status = DesktopWasapiPcmSessionProbeStatus.Error,
                    failure = if (volumeStatus == LAZER_AUDIO_ERROR_DEVICE) {
                        DesktopWasapiPcmSessionProbeFailure.DeviceOrRuntime
                    } else {
                        DesktopWasapiPcmSessionProbeFailure.NativeCallFailed
                    },
                    setupStatus = volumeStatus,
                )
            }

            val params = LazerAudioOpenParams().apply {
                structSize = size()
                startMillis = 0L
                durationHintMillis = 0L
            }
            val openStatus = api.lazer_audio_open_file(engine, WString(wavPath.toAbsolutePath().toString()), params)
            if (openStatus != LAZER_AUDIO_OK) {
                return classify(
                    candidate = candidate,
                    openStatus = openStatus,
                    streamInfoStatus = null,
                    info = null,
                )
            }

            val info = LazerAudioStreamInfo()
            val infoStatus = api.lazer_audio_stream_info(engine, info)
            if (infoStatus == LAZER_AUDIO_OK) info.read()
            return classify(
                candidate = candidate,
                openStatus = openStatus,
                streamInfoStatus = infoStatus,
                info = info.takeIf { infoStatus == LAZER_AUDIO_OK },
            )
        } catch (_: UnsatisfiedLinkError) {
            return DesktopWasapiPcmSessionProbeResult(
                candidate = candidate,
                status = DesktopWasapiPcmSessionProbeStatus.Error,
                failure = DesktopWasapiPcmSessionProbeFailure.NativeCallFailed,
            )
        } catch (_: Exception) {
            return DesktopWasapiPcmSessionProbeResult(
                candidate = candidate,
                status = DesktopWasapiPcmSessionProbeStatus.Error,
                failure = DesktopWasapiPcmSessionProbeFailure.NativeCallFailed,
            )
        } finally {
            if (engine != null) runCatching { api.lazer_audio_destroy(engine) }
            runCatching { Files.deleteIfExists(wavPath) }
        }
    }

    private fun failureForNativeStatus(status: Int): DesktopWasapiPcmSessionProbeFailure = when (status) {
        LAZER_AUDIO_ERROR_UNSUPPORTED -> DesktopWasapiPcmSessionProbeFailure.UnsupportedFormat
        LAZER_AUDIO_ERROR_DEVICE -> DesktopWasapiPcmSessionProbeFailure.DeviceOrRuntime
        LAZER_AUDIO_ERROR_SOURCE, LAZER_AUDIO_ERROR_DECODE -> DesktopWasapiPcmSessionProbeFailure.SourceFixture
        else -> DesktopWasapiPcmSessionProbeFailure.NativeCallFailed
    }

    private fun LazerAudioStreamInfo.toSessionFormatOrNull(): DesktopWasapiPcmSessionFormat? {
        if (outputFormatInitialized == 0 || outputSampleRate <= 0 || outputChannels <= 0) return null
        return DesktopWasapiPcmSessionFormat(
            sampleRateHz = outputSampleRate,
            channels = outputChannels,
            validBits = outputBitsPerSample,
            containerBits = outputContainerBitsPerSample,
            isFloat = outputIsFloat != 0,
            exclusive = outputExclusive != 0,
        )
    }
}

internal class DesktopWasapiPcmSessionProbeUnavailableException(cause: Throwable? = null) :
    IllegalStateException("The native audio engine is unavailable for a WASAPI PCM session probe.", cause)

/** Tiny, silent, packed-PCM WAV source matching the candidate's valid source depth. */
internal object DesktopWasapiPcmSessionProbeWav {
    const val SILENT_FRAME_COUNT = 64
    private const val HEADER_BYTES = 44

    fun create(candidate: DesktopWasapiPcmFormatCandidate): ByteArray {
        require(candidate.channels in 1..2) { "The session probe supports mono or stereo PCM candidates" }
        require(candidate.validBits in setOf(16, 24, 32)) { "Unsupported WAV valid bit depth" }
        require(candidate.containerBits == candidate.validBits ||
            (candidate.validBits == 24 && candidate.containerBits == 32)
        ) { "Unsupported source/container bit-depth combination" }

        val bytesPerSample = candidate.validBits / 8
        val blockAlign = candidate.channels * bytesPerSample
        val dataSize = SILENT_FRAME_COUNT * blockAlign
        val byteRate = candidate.sampleRateHz * blockAlign
        val wave = ByteArray(HEADER_BYTES + dataSize)
        var offset = 0

        fun ascii(value: String) {
            value.forEach { wave[offset++] = it.code.toByte() }
        }

        fun u16(value: Int) {
            wave[offset++] = value.toByte()
            wave[offset++] = (value ushr 8).toByte()
        }

        fun u32(value: Int) {
            wave[offset++] = value.toByte()
            wave[offset++] = (value ushr 8).toByte()
            wave[offset++] = (value ushr 16).toByte()
            wave[offset++] = (value ushr 24).toByte()
        }

        ascii("RIFF")
        u32(wave.size - 8)
        ascii("WAVE")
        ascii("fmt ")
        u32(16) // WAVE_FORMAT_PCM fmt chunk.
        u16(1)
        u16(candidate.channels)
        u32(candidate.sampleRateHz)
        u32(byteRate)
        u16(blockAlign)
        u16(candidate.validBits)
        ascii("data")
        u32(dataSize)
        // ByteArray is zero-filled: all PCM frames are digital silence.
        check(offset == HEADER_BYTES) { "WAV header layout mismatch" }
        return wave
    }
}
