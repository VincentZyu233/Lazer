package dev.naominet.lazer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopHiFiDiagnosticsTest {
    @Test
    fun reportContainsUsefulSignalStateAndProbeResults() {
        val report = DesktopHiFiDiagnostics.toJson(snapshot())
        val json = Json.parseToJsonElement(report).jsonObject

        assertEquals(3, json.getValue("schema_version").jsonPrimitive.int)
        assertEquals("Windows", json.getValue("runtime").jsonObject.getValue("os_name").jsonPrimitive.content)
        assertEquals("Explicit", json.getValue("output_device").jsonObject.getValue("selection").jsonPrimitive.content)
        assertEquals(1, json.getValue("pcm_format_probe").jsonObject.getValue("supported_count").jsonPrimitive.int)
        assertEquals(
            "WASAPI_EXCLUSIVE",
            json.getValue("stream").jsonObject.getValue("output_session").jsonObject
                .getValue("backend").jsonPrimitive.content,
        )
        assertEquals(
            50L,
            json.getValue("stream").jsonObject.getValue("output_session").jsonObject
                .getValue("underrun_estimated_milliseconds").jsonPrimitive.content.toLong(),
        )
        assertEquals(
            4_800L,
            json.getValue("stream").jsonObject.getValue("output_session").jsonObject
                .getValue("underrun_padded_frames").jsonPrimitive.content.toLong(),
        )
        assertEquals(
            true,
            json.getValue("stream").jsonObject.getValue("output_session").jsonObject
                .getValue("coreaudio_virtual_asbd_non_mixable").jsonPrimitive.content.toBoolean(),
        )
        assertEquals(
            "Negotiated",
            json.getValue("stream").jsonObject.getValue("signal_path").jsonObject
                .getValue("direct_path_status").jsonPrimitive.content,
        )
        assertEquals(
            7L,
            json.getValue("stream").jsonObject.getValue("output_telemetry").jsonObject
                .getValue("out_of_range_integer_samples").jsonPrimitive.content.toLong(),
        )
        assertTrue(
            json.getValue("pcm_format_probe").jsonObject.getValue("results").jsonArray.isNotEmpty(),
        )
    }

    @Test
    fun reportOmitsDeviceIdentifiersNamesAndFreeFormDetails() {
        val report = DesktopHiFiDiagnostics.toJson(snapshot())

        listOf(
            "PRIVATE_ENDPOINT_ID_7462",
            "PRIVATE_STABLE_ID_1395",
            "Private Headphones - Alice",
            "Private Title: Moonlight",
            "C:\\Users\\Alice\\Music\\secret.flac",
            "https://stream.example/private-token",
            "session-cookie-should-never-appear",
            "raw backend error at C:\\Users\\Alice",
        ).forEach { secret -> assertFalse("Report unexpectedly contained $secret", report.contains(secret)) }

        assertTrue(report.contains("\"device_name_redacted\": true"))
        assertTrue(report.contains("\"device_id_redacted\": true"))
        assertTrue(report.contains("\"file_paths_included\": false"))
    }

    @Test
    fun reportSupportsIdleAndUnavailableDeviceStates() {
        val report = DesktopHiFiDiagnostics.toJson(
            DesktopHiFiDiagnosticsSnapshot(
                nativeEngineAvailable = false,
                nativeEngineSelected = false,
                bitPerfectRequested = false,
                bufferMillis = 120,
                equalizerEnabled = false,
                deviceSelection = DesktopHiFiDeviceSelection.SavedDeviceUnavailable,
                activeOutputDeviceCount = 0,
                deviceCatalogLoading = false,
                deviceCatalogFailed = true,
                pcmFormatProbeFailed = true,
            ),
        )
        val json = Json.parseToJsonElement(report).jsonObject

        assertEquals("SavedDeviceUnavailable", json.getValue("output_device").jsonObject
            .getValue("selection").jsonPrimitive.content)
        assertEquals("failed", json.getValue("output_device").jsonObject
            .getValue("catalog_status").jsonPrimitive.content)
        assertEquals("failed_or_partial", json.getValue("pcm_format_probe").jsonObject
            .getValue("status").jsonPrimitive.content)
        assertEquals("null", json.getValue("stream").toString())
    }

    private fun snapshot(): DesktopHiFiDiagnosticsSnapshot {
        val privateDevice = DesktopWasapiDevice(
            endpointId = "PRIVATE_ENDPOINT_ID_7462",
            identityKey = "PRIVATE_STABLE_ID_1395",
            friendlyName = "Private Headphones - Alice",
            endpointState = WASAPI_DEVICE_STATE_ACTIVE,
            defaultRoleMask = 1 shl 1,
            stableIdentity = true,
            endpointVolumeQueryHresult = 0,
            endpointVolumeHardwareSupportFlags = WASAPI_ENDPOINT_HARDWARE_SUPPORT_VOLUME,
        )
        val format = AudioFormat.Pcm(
            sampleRateHz = 96_000,
            validBitsPerSample = 24,
            containerBitsPerSample = 32,
            byteOrder = AudioByteOrder.LittleEndian,
            channelLayout = AudioChannelLayout.Stereo,
        )
        val signalPath = SignalPathSnapshot(
            stages = listOf(
                SignalPathStageSnapshot(
                    stage = SignalPathStage.Source,
                    status = SignalPathStageStatus.Active,
                    detail = "Private Title: Moonlight at C:\\Users\\Alice\\Music\\secret.flac",
                ),
            ),
            negotiation = OutputNegotiationSnapshot(
                status = OutputNegotiationStatus.Accepted,
                negotiatedFormat = format,
                backend = AudioBackend.WasapiExclusive,
                detail = "raw backend error at C:\\Users\\Alice",
            ),
            verification = BitPerfectVerificationSnapshot(
                status = BitPerfectVerificationStatus.NotRun,
                detail = "https://stream.example/private-token session-cookie-should-never-appear",
            ),
            directPath = DirectPathSnapshot(
                status = DirectPathStatus.Negotiated,
                reason = DirectPathReason.DigitalCaptureNotVerified,
            ),
            device = OutputCapabilities(
                deviceId = privateDevice.identityKey,
                displayName = privateDevice.friendlyName,
                backend = AudioBackend.WasapiExclusive,
            ),
            outputTelemetry = OutputTelemetrySnapshot(
                samplePeakDbfs = -1.0,
                limiterGainReductionDb = -0.5,
                clippedIntegerSampleCount = 7,
            ),
        )
        return DesktopHiFiDiagnosticsSnapshot(
            exportedAtUtc = "2026-10-02T00:00:00Z",
            osName = "Windows",
            osVersion = "11",
            osArchitecture = "amd64",
            javaVersion = "test-runtime",
            nativeEngineAvailable = true,
            nativeEngineSelected = true,
            bitPerfectRequested = true,
            bufferMillis = 120,
            equalizerEnabled = true,
            deviceSelection = DesktopHiFiDeviceSelection.Explicit,
            activeOutputDeviceCount = 1,
            deviceCatalogLoading = false,
            deviceCatalogFailed = false,
            activeOutputDevice = privateDevice,
            endpointVolumeReadSucceeded = true,
            pcmFormatProbeLoading = false,
            pcmFormatProbeFailed = false,
            pcmFormatProbeResults = listOf(
                DesktopWasapiPcmFormatProbeResult(
                    candidate = DesktopWasapiPcmFormatCandidate(
                        sampleRateHz = 96_000,
                        containerBits = 32,
                        validBits = 24,
                    ),
                    status = DesktopWasapiPcmFormatProbeStatus.Supported,
                    nativeStatus = 0,
                ),
            ),
            stream = LazerHiFiStreamInfo(
                codec = "flac",
                sourceSampleRate = 96_000,
                sourceChannels = 2,
                sourceBitsPerSample = 24,
                sampleRate = 96_000,
                channels = 2,
                bitsPerSample = 24,
                exclusive = true,
                lossless = true,
                bitPerfectActive = true,
                signalPath = signalPath,
                outputContainerBitsPerSample = 32,
                outputFormatInitialized = true,
                outputNonMixable = true,
                formatSelection = OutputFormatSelection.ExclusiveSource,
                underrunActive = true,
                underrunFrames = 4_800L,
            ),
        )
    }
}
