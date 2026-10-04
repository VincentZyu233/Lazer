package dev.naominet.lazer

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

/**
 * An explicit allow-list for support reports. It intentionally accepts no track, URL, path,
 * account, raw error-message, or playback-log fields.
 */
internal data class DesktopHiFiDiagnosticsSnapshot(
    val exportedAtUtc: String = Instant.now().toString(),
    val osName: String = System.getProperty("os.name").orEmpty(),
    val osVersion: String = System.getProperty("os.version").orEmpty(),
    val osArchitecture: String = System.getProperty("os.arch").orEmpty(),
    val javaVersion: String = System.getProperty("java.version").orEmpty(),
    val nativeEngineAvailable: Boolean,
    val nativeEngineSelected: Boolean,
    val bitPerfectRequested: Boolean,
    val bufferMillis: Int,
    val equalizerEnabled: Boolean,
    val deviceSelection: DesktopHiFiDeviceSelection,
    val activeOutputDeviceCount: Int,
    val deviceCatalogLoading: Boolean,
    val deviceCatalogFailed: Boolean,
    val activeOutputDevice: DesktopAudioOutputDevice? = null,
    val endpointVolumeReadSucceeded: Boolean = false,
    val pcmFormatProbeLoading: Boolean = false,
    val pcmFormatProbeFailed: Boolean = false,
    val pcmFormatProbeResults: List<DesktopWasapiPcmFormatProbeResult> = emptyList(),
    val stream: LazerHiFiStreamInfo? = null,
)

internal enum class DesktopHiFiDeviceSelection {
    SystemDefault,
    Explicit,
    SavedDeviceUnavailable,
}

internal enum class DesktopHiFiDiagnosticsExportStatus {
    Idle,
    Exporting,
    Saved,
    Failed,
}

/** Builds a versioned, privacy-conscious JSON report from an allow-listed state snapshot. */
internal object DesktopHiFiDiagnostics {
    private const val SCHEMA_VERSION = 3
    private val json = Json { prettyPrint = true }

    fun toJson(snapshot: DesktopHiFiDiagnosticsSnapshot): String =
        json.encodeToString(JsonObject.serializer(), snapshot.toJsonObject())

    private fun DesktopHiFiDiagnosticsSnapshot.toJsonObject(): JsonObject = buildJsonObject {
        put("schema_version", SCHEMA_VERSION)
        put("generated_at_utc", exportedAtUtc)
        putJsonObject("application") {
            put("name", LazerRelease.name)
            put("version", LazerRelease.versionName)
        }
        putJsonObject("runtime") {
            put("os_name", osName)
            put("os_version", osVersion)
            put("architecture", osArchitecture)
            put("java_version", javaVersion)
        }
        putJsonObject("engine") {
            put("native_available", nativeEngineAvailable)
            put("native_selected", nativeEngineSelected)
            put("native_active", nativeEngineSelected && nativeEngineAvailable)
            put("expected_abi_version", LAZER_AUDIO_ABI_VERSION)
            put("bit_perfect_requested", bitPerfectRequested)
            put("buffer_ms", bufferMillis)
            put("equalizer_enabled", equalizerEnabled)
        }
        putJsonObject("output_device") {
            put("selection", deviceSelection.name)
            put("active_output_device_count", activeOutputDeviceCount)
            put("catalog_status", when {
                deviceCatalogLoading -> "loading"
                deviceCatalogFailed -> "failed"
                else -> "available"
            })
            put("device_present", activeOutputDevice != null)
            put("device_name_redacted", true)
            put("device_id_redacted", true)
            activeOutputDevice?.let { device ->
                put("backend", device.backendName)
                put("active", device.active)
                put("default", device.isDefault)
                put("stable_identity_available", device.stableIdentity)
                val wasapiDevice = device as? DesktopWasapiDevice
                put("volume_query_succeeded", wasapiDevice?.endpointVolumeQuerySucceeded == true)
                put("volume_support_flags", wasapiDevice?.endpointVolumeHardwareSupportFlags ?: 0)
                put("volume_read_succeeded", wasapiDevice != null && endpointVolumeReadSucceeded)
            }
        }
        putJsonObject("pcm_format_probe") {
            put("status", when {
                pcmFormatProbeLoading -> "loading"
                pcmFormatProbeFailed -> "failed_or_partial"
                pcmFormatProbeResults.isNotEmpty() -> "completed"
                else -> "not_run"
            })
            put("candidate_count", pcmFormatProbeResults.size)
            put("supported_count", pcmFormatProbeResults.count {
                it.status == DesktopWasapiPcmFormatProbeStatus.Supported
            })
            put("unsupported_count", pcmFormatProbeResults.count {
                it.status == DesktopWasapiPcmFormatProbeStatus.Unsupported
            })
            put("error_count", pcmFormatProbeResults.count {
                it.status == DesktopWasapiPcmFormatProbeStatus.Error
            })
            putJsonArray("results") {
                pcmFormatProbeResults.forEach { result ->
                    add(buildJsonObject {
                        put("sample_rate_hz", result.candidate.sampleRateHz)
                        put("channels", result.candidate.channels)
                        put("valid_bits", result.candidate.validBits)
                        put("container_bits", result.candidate.containerBits)
                        put("status", result.status.name)
                        put("native_status", result.nativeStatus)
                    })
                }
            }
        }
        put("stream", stream?.toJsonElement() ?: JsonNull)
        putJsonObject("privacy") {
            put("track_metadata_included", false)
            put("playback_urls_included", false)
            put("file_paths_included", false)
            put("account_data_included", false)
            put("raw_logs_or_error_messages_included", false)
            put("device_names_or_ids_included", false)
        }
    }

    private fun LazerHiFiStreamInfo.toJsonElement(): JsonElement = buildJsonObject {
        put("source_kind", if (hasDsdSource) "DSD" else "PCM")
        put("codec", codec)
        put("lossless", lossless)
        putJsonObject("source_format") {
            if (sourceSampleRate > 0) put("sample_rate_hz", sourceSampleRate)
            put("channels", sourceChannels)
            if (sourceBitsPerSample > 0) put("valid_bits", sourceBitsPerSample)
            if (sourceDsdRateMultiplier > 0) put("dsd_rate_multiplier", sourceDsdRateMultiplier)
        }
        putJsonObject("output_session") {
            put("initialized", outputFormatInitialized)
            put("backend", if (exclusive) "WASAPI_EXCLUSIVE" else "WASAPI_SHARED")
            put("sample_rate_hz", sampleRate)
            put("channels", channels)
            put("valid_bits", bitsPerSample)
            put("container_bits", outputContainerBitsPerSample)
            put("encoding", if (outputIsFloat) "float" else "integer")
            put("format_selection", formatSelection.name)
            put("bit_perfect_active", bitPerfectActive)
            put("underrun_padding_active", underrunActive)
            put("underrun_padded_frames", underrunFrames.coerceAtLeast(0L))
            estimatedUnderrunMillis?.let { put("underrun_estimated_milliseconds", it) }
        }
        putJsonObject("signal_path") {
            put("negotiation_status", signalPath.negotiation.status.name)
            put("negotiation_backend", signalPath.negotiation.backend?.name ?: "UNKNOWN")
            put("direct_path_status", signalPath.directPath.status.name)
            put("direct_path_reason", signalPath.directPath.reason?.name ?: "NONE")
            put("digital_verification_status", signalPath.verification.status.name)
            put("digital_verification_method", signalPath.verification.method?.name ?: "NONE")
            putJsonArray("stages") {
                signalPath.stages.forEach { stage ->
                    add(buildJsonObject {
                        put("stage", stage.stage.name)
                        put("status", stage.status.name)
                    })
                }
            }
        }
        put("output_telemetry", signalPath.outputTelemetry?.let { telemetry ->
            buildJsonObject {
                put("sample_peak_dbfs", telemetry.samplePeakDbfs.toJsonNumberOrLabel())
                put("limiter_max_attenuation_db", JsonPrimitive(telemetry.limiterGainReductionDb))
                telemetry.clippedIntegerSampleCount?.let { put("out_of_range_integer_samples", it) }
            }
        } ?: JsonNull)
        put("dac_physical_format_read_back", false)
    }

    private fun Double.toJsonNumberOrLabel(): JsonElement = when {
        isFinite() -> JsonPrimitive(this)
        this == Double.NEGATIVE_INFINITY -> JsonPrimitive("-infinity")
        else -> JsonPrimitive("non-finite")
    }
}
