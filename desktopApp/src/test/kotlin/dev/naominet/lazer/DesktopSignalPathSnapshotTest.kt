package dev.naominet.lazer

import com.sun.jna.Structure
import com.sun.jna.Native
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopSignalPathSnapshotTest {
    @Test
    fun `device config jna layout appends DSD output policy`() {
        val config = LazerAudioDeviceConfig()
        val pointerSize = Native.POINTER_SIZE
        val expectedSize = (pointerSize + 6 * Int.SIZE_BYTES + pointerSize - 1) / pointerSize * pointerSize

        assertEquals(
            listOf(
                "deviceId", "exclusive", "resampleMode", "targetSampleRate", "bufferMillis",
                "bitPerfect", "dsdOutputMode",
            ),
            LazerAudioDeviceConfig::class.java.getAnnotation(Structure.FieldOrder::class.java).value.toList(),
        )
        assertEquals(expectedSize, config.size())
        assertEquals(LAZER_AUDIO_DSD_OUTPUT_CONVERT_TO_PCM, config.dsdOutputMode)
    }

    @Test
    fun `stream info jna layout preserves append-only fields with current abi`() {
        val info = LazerAudioStreamInfo()

        assertEquals(22, LAZER_AUDIO_ABI_VERSION)
        assertEquals(6, LAZER_AUDIO_EVENT_TRACK_CHANGED)
        assertEquals(-2, LAZER_AUDIO_READER_IO_ERROR)
        assertEquals(0, LAZER_AUDIO_DSD_OUTPUT_CONVERT_TO_PCM)
        assertEquals(1, LAZER_AUDIO_DSD_OUTPUT_REQUIRE_DOP)
        assertEquals(2, LAZER_AUDIO_DSD_OUTPUT_REQUIRE_NATIVE)
        assertEquals(0, LAZER_AUDIO_OUTPUT_FORMAT_PCM)
        assertEquals(1, LAZER_AUDIO_OUTPUT_FORMAT_DOP)
        assertEquals(2, LAZER_AUDIO_OUTPUT_FORMAT_NATIVE_DSD)
        assertEquals(7, LAZER_AUDIO_FORMAT_SELECTION_DOP_CARRIER)
        assertEquals(8, LAZER_AUDIO_FORMAT_SELECTION_NATIVE_DSD_U8)
        assertEquals(12, LAZER_AUDIO_FORMAT_SELECTION_NATIVE_DSD_U32_BE)
        assertEquals(
            listOf(
                "codec", "sourceSampleRate", "sourceChannels", "sourceBitsPerSample", "sourceBitrateKbps",
                "lossless", "outputSampleRate", "outputChannels", "outputBitsPerSample", "outputExclusive",
                "bitPerfectActive", "outputContainerBitsPerSample", "outputIsFloat", "outputFormatInitialized",
                "sourceFormatKind", "sourceDsdRateMultiplier", "outputFormatSelection", "outputPeakMilliDbfs",
                "limiterGainReductionMilliDb", "outputClippedSampleCount", "outputTelemetryValid",
                "outputFormatKind", "outputDsdRateMultiplier",
            ),
            LazerAudioStreamInfo::class.java.getAnnotation(Structure.FieldOrder::class.java).value.toList(),
        )
        assertEquals(160, info.size())
    }

    @Test
    fun `open params jna layout matches abi 21 appended fields`() {
        val params = LazerAudioOpenParams()

        assertEquals(
            listOf(
                "structSize", "startMillis", "durationHintMillis", "cueStartFrame75", "cueEndFrame75",
                "replayGainDb",
            ),
            LazerAudioOpenParams::class.java
                .getAnnotation(Structure.FieldOrder::class.java).value.toList(),
        )
        assertEquals(48, params.size())
        params.cueStartFrame75 = 123L
        params.cueEndFrame75 = -1L
        params.replayGainDb = -6.0
        params.write()
        assertEquals(123L, params.cueStartFrame75)
        assertEquals(-1L, params.cueEndFrame75)
        assertEquals(-6.0, params.replayGainDb, 0.0)
    }

    @Test
    fun `snapshot jna layout appends output and terminal telemetry with abi v21`() {
        val snapshot = LazerAudioSnapshot()

        assertEquals(
            listOf(
                "state", "paused", "positionMillis", "durationMillis", "bufferedPercent", "error",
                "underrunActive", "underrunFrames", "terminalEvent", "terminalDetail",
                "terminalPositionMillis",
            ),
            LazerAudioSnapshot::class.java.getAnnotation(Structure.FieldOrder::class.java).value.toList(),
        )
        assertEquals(64, snapshot.size())
        assertEquals(LAZER_AUDIO_EVENT_NONE, snapshot.terminalEvent)
    }

    @Test
    fun `reader jna layout includes concurrent cancellation hook`() {
        val reader = LazerAudioReader()

        assertEquals(
            listOf("read", "seek", "length", "close", "context", "cancel"),
            LazerAudioReader::class.java.getAnnotation(Structure.FieldOrder::class.java).value.toList(),
        )
        assertEquals(48, reader.size())
    }

    @Test
    fun `native output telemetry maps sample peak limiter and integer clipping`() {
        val integer = LazerAudioStreamInfo().apply {
            outputFormatInitialized = 1
            outputTelemetryValid = 1
            outputPeakMilliDbfs = -1_234
            limiterGainReductionMilliDb = -2_750
            outputClippedSampleCount = 7L
        }
        assertEquals(
            OutputTelemetrySnapshot(-1.234, -2.75, clippedIntegerSampleCount = 7L),
            integer.toOutputTelemetrySnapshot(),
        )

        val float = LazerAudioStreamInfo().apply {
            outputFormatInitialized = 1
            outputTelemetryValid = 1
            outputIsFloat = 1
            outputPeakMilliDbfs = 1_000
            limiterGainReductionMilliDb = -500
            outputClippedSampleCount = 99L
        }
        assertEquals(
            OutputTelemetrySnapshot(1.0, -0.5, clippedIntegerSampleCount = null),
            float.toOutputTelemetrySnapshot(),
        )

        assertNull(LazerAudioStreamInfo().apply { outputTelemetryValid = 0 }.toOutputTelemetrySnapshot())
        assertNull(LazerAudioStreamInfo().apply {
            outputFormatInitialized = 1
            outputTelemetryValid = 1
            bitPerfectActive = 1
        }.toOutputTelemetrySnapshot())
        assertNull(LazerAudioStreamInfo().apply {
            outputFormatInitialized = 1
            outputTelemetryValid = 1
            outputFormatKind = LAZER_AUDIO_OUTPUT_FORMAT_NATIVE_DSD
        }.toOutputTelemetrySnapshot())
    }

    @Test
    fun `native format selection branches map to explicit negotiation states`() {
        val cases = listOf(
            LAZER_AUDIO_FORMAT_SELECTION_UNKNOWN to OutputFormatSelection.Unknown,
            LAZER_AUDIO_FORMAT_SELECTION_SHARED_MIX to OutputFormatSelection.SharedMix,
            LAZER_AUDIO_FORMAT_SELECTION_EXCLUSIVE_SOURCE to OutputFormatSelection.ExclusiveSource,
            LAZER_AUDIO_FORMAT_SELECTION_EXCLUSIVE_SAME_RATE_ALTERNATE to
                OutputFormatSelection.ExclusiveSameRateAlternate,
            LAZER_AUDIO_FORMAT_SELECTION_EXCLUSIVE_MONO_TO_STEREO to
                OutputFormatSelection.ExclusiveMonoToStereo,
            LAZER_AUDIO_FORMAT_SELECTION_EXCLUSIVE_MIX_FALLBACK to
                OutputFormatSelection.ExclusiveMixFallback,
            LAZER_AUDIO_FORMAT_SELECTION_EXCLUSIVE_COMMON_RATE_FALLBACK to
                OutputFormatSelection.ExclusiveCommonRateFallback,
            LAZER_AUDIO_FORMAT_SELECTION_DOP_CARRIER to OutputFormatSelection.DoPCarrier,
            LAZER_AUDIO_FORMAT_SELECTION_NATIVE_DSD_U8 to OutputFormatSelection.NativeDsdU8,
            LAZER_AUDIO_FORMAT_SELECTION_NATIVE_DSD_U16_LE to OutputFormatSelection.NativeDsdU16Le,
            LAZER_AUDIO_FORMAT_SELECTION_NATIVE_DSD_U16_BE to OutputFormatSelection.NativeDsdU16Be,
            LAZER_AUDIO_FORMAT_SELECTION_NATIVE_DSD_U32_LE to OutputFormatSelection.NativeDsdU32Le,
            LAZER_AUDIO_FORMAT_SELECTION_NATIVE_DSD_U32_BE to OutputFormatSelection.NativeDsdU32Be,
            99 to OutputFormatSelection.Unknown,
        )

        cases.forEach { (nativeValue, expected) ->
            val snapshot = streamInfo(
                initialized = true,
                nativeFormatSelection = nativeValue,
            ).toSignalPathSnapshot()

            assertEquals(expected, snapshot.negotiation.formatSelection)
        }
    }

    @Test
    fun `DSD source is labeled as DSD and reports conversion to PCM`() {
        val info = streamInfo(
            sourceRate = 0,
            sourceBits = 0,
            sourceFormatKind = LAZER_AUDIO_SOURCE_FORMAT_DSD,
            sourceDsdRateMultiplier = 64,
            rate = 352_800,
            validBits = 32,
            containerBits = 32,
            isFloat = true,
            initialized = true,
        )

        val snapshot = info.toSignalPathSnapshot()

        assertEquals(
            AudioFormat.Dsd(DsdRate.Dsd64, AudioChannelLayout.Stereo),
            snapshot.stages.single { it.stage == SignalPathStage.Source }.format,
        )
        assertEquals(SignalPathStageStatus.Converted,
            snapshot.stages.single { it.stage == SignalPathStage.FormatConversion }.status)
        assertEquals(
            DirectPathStatus.Rejected,
            snapshot.directPath.status,
        )
        assertEquals(DirectPathReason.DsdConvertedToPcm, snapshot.directPath.reason)
        assertEquals(false, info.bitPerfectActive)
        assertEquals(0, info.sourceSampleRate)
        assertEquals(0, info.sourceBitsPerSample)
        assert(snapshot.stages.single { it.stage == SignalPathStage.Source }.detail.orEmpty()
            .contains("DSD64"))
    }

    @Test
    fun `accepted DoP snapshot reports the carrier without claiming DAC recognition`() {
        val info = streamInfo(
            sourceRate = 0,
            sourceBits = 0,
            sourceFormatKind = LAZER_AUDIO_SOURCE_FORMAT_DSD,
            sourceDsdRateMultiplier = 64,
            rate = 176_400,
            validBits = 24,
            containerBits = 24,
            exclusive = true,
            initialized = true,
            nativeFormatSelection = LAZER_AUDIO_FORMAT_SELECTION_DOP_CARRIER,
        ).copy(
            outputFormatKind = LAZER_AUDIO_OUTPUT_FORMAT_DOP,
            outputDsdRateMultiplier = 64,
        )

        val snapshot = info.toSignalPathSnapshot(osName = "Windows 11")

        assertEquals(true, info.isDoPOutput)
        assertEquals(AudioBackend.WasapiExclusive, snapshot.negotiation.backend)
        assertEquals(OutputNegotiationStatus.Accepted, snapshot.negotiation.status)
        assertEquals(OutputFormatSelection.DoPCarrier, snapshot.negotiation.formatSelection)
        assertEquals(
            AudioFormat.DoP(DsdRate.Dsd64, 176_400, AudioChannelLayout.Stereo),
            snapshot.negotiation.negotiatedFormat,
        )
        assertEquals(
            SignalPathStageStatus.Converted,
            snapshot.stages.single { it.stage == SignalPathStage.FormatConversion }.status,
        )
        assertEquals(
            SignalPathStageStatus.Bypassed,
            snapshot.stages.single { it.stage == SignalPathStage.Dsp }.status,
        )
        assertEquals(DirectPathStatus.Negotiated, snapshot.directPath.status)
        assertEquals(DirectPathReason.DigitalCaptureNotVerified, snapshot.directPath.reason)
        assertEquals(BitPerfectVerificationStatus.NotRun, snapshot.verification.status)
        assertEquals(
            SignalPathStageStatus.Unknown,
            snapshot.stages.single { it.stage == SignalPathStage.Device }.status,
        )
        assert(snapshot.negotiation.detail.orEmpty().contains("not read back"))
        assert(snapshot.negotiation.detail.orEmpty().contains("WASAPI accepted"))
        assert(snapshot.directPath.detail.orEmpty().contains("not been verified"))
    }

    @Test
    fun `Linux DoP diagnostics identify ALSA and its hardware configuration semantics`() {
        val info = streamInfo(
            sourceRate = 0,
            sourceBits = 0,
            sourceFormatKind = LAZER_AUDIO_SOURCE_FORMAT_DSD,
            sourceDsdRateMultiplier = 64,
            rate = 176_400,
            validBits = 24,
            containerBits = 24,
            exclusive = true,
            initialized = true,
        ).copy(
            outputFormatKind = LAZER_AUDIO_OUTPUT_FORMAT_DOP,
            outputDsdRateMultiplier = 64,
        )

        val snapshot = info.toSignalPathSnapshot(osName = "Linux")

        assertEquals(AudioBackend.Alsa, snapshot.negotiation.backend)
        assertEquals(
            "DoP over 176400 Hz · 2 ch · 24-bit carrier · ALSA direct hardware PCM",
            snapshot.stages.single { it.stage == SignalPathStage.OutputBackend }.detail,
        )
        assert(snapshot.negotiation.detail.orEmpty().contains("ALSA configured"))
        assert(snapshot.negotiation.detail.orEmpty().contains("direct hardware PCM"))
        assert(snapshot.negotiation.detail.orEmpty().contains("not read back"))
        assertFalse(snapshot.negotiation.detail.orEmpty().contains("WASAPI"))

        val pcmSnapshot = streamInfo(initialized = true).toSignalPathSnapshot(osName = "Linux")
        assertEquals(AudioBackend.Alsa, pcmSnapshot.negotiation.backend)
        assert(pcmSnapshot.negotiation.detail.orEmpty().contains("ALSA configured"))
        assertFalse(pcmSnapshot.negotiation.detail.orEmpty().contains("WASAPI"))
    }

    @Test
    fun `Linux Native DSD diagnostics report exact ALSA format without claiming DAC recognition`() {
        val info = streamInfo(
            sourceRate = 0,
            sourceBits = 0,
            sourceFormatKind = LAZER_AUDIO_SOURCE_FORMAT_DSD,
            sourceDsdRateMultiplier = 64,
            rate = 88_200,
            validBits = 32,
            containerBits = 32,
            exclusive = true,
            initialized = true,
            nativeFormatSelection = LAZER_AUDIO_FORMAT_SELECTION_NATIVE_DSD_U32_BE,
        ).copy(
            outputFormatKind = LAZER_AUDIO_OUTPUT_FORMAT_NATIVE_DSD,
            outputDsdRateMultiplier = 64,
        )

        val snapshot = info.toSignalPathSnapshot(osName = "Linux")

        assertEquals(true, info.isNativeDsdOutput)
        assertEquals("DSD_U32_BE", info.nativeDsdAlsaFormat)
        assertEquals(AudioBackend.Alsa, snapshot.negotiation.backend)
        assertEquals(OutputNegotiationStatus.Accepted, snapshot.negotiation.status)
        assertEquals(OutputFormatSelection.NativeDsdU32Be, snapshot.negotiation.formatSelection)
        assertEquals(AudioFormat.Dsd(DsdRate.Dsd64, AudioChannelLayout.Stereo), snapshot.negotiation.negotiatedFormat)
        assertEquals(
            "Native DSD · 88200 Hz · 2 ch · DSD_U32_BE · ALSA direct hardware PCM",
            snapshot.stages.single { it.stage == SignalPathStage.OutputBackend }.detail,
        )
        assertEquals(SignalPathStageStatus.Bypassed, snapshot.stages.single { it.stage == SignalPathStage.Dsp }.status)
        assertEquals(SignalPathStageStatus.Converted,
            snapshot.stages.single { it.stage == SignalPathStage.FormatConversion }.status)
        assertEquals(DirectPathStatus.Negotiated, snapshot.directPath.status)
        assert(snapshot.negotiation.detail.orEmpty().contains("ALSA configured the exact Native DSD format"))
        assert(snapshot.negotiation.detail.orEmpty().contains("not read back"))
        assert(snapshot.directPath.detail.orEmpty().contains("not been verified"))
        assertEquals(null, snapshot.outputTelemetry)
    }

    @Test
    fun `accepted bit perfect session remains unverified until digital capture`() {
        val info = streamInfo(
            sourceRate = 96_000,
            sourceBits = 24,
            rate = 96_000,
            validBits = 24,
            containerBits = 32,
            exclusive = true,
            lossless = true,
            bitPerfect = true,
            initialized = true,
        )

        val snapshot = info.toSignalPathSnapshot(osName = "Windows 11")

        assertEquals(AudioBackend.WasapiExclusive, snapshot.negotiation.backend)
        assertEquals(OutputNegotiationStatus.Accepted, snapshot.negotiation.status)
        assertNull(snapshot.negotiation.requestedFormat)
        assertEquals(
            AudioFormat.Pcm(
                sampleRateHz = 96_000,
                validBitsPerSample = 24,
                containerBitsPerSample = 32,
                byteOrder = AudioByteOrder.LittleEndian,
                channelLayout = AudioChannelLayout.Stereo,
            ),
            snapshot.negotiation.negotiatedFormat,
        )
        assertEquals(DirectPathStatus.Negotiated, snapshot.directPath.status)
        assertEquals(DirectPathReason.DigitalCaptureNotVerified, snapshot.directPath.reason)
        assertEquals(BitPerfectVerificationStatus.NotRun, snapshot.verification.status)
        assertNull(snapshot.verification.method)
        assertEquals(SignalPathStageStatus.Unknown, snapshot.stages.single {
            it.stage == SignalPathStage.Device
        }.status)
        assertEquals(
            SignalPathStageStatus.Bypassed,
            snapshot.stages.single { it.stage == SignalPathStage.Dsp }.status,
        )
    }

    @Test
    fun `signal path preserves output telemetry`() {
        val telemetry = OutputTelemetrySnapshot(-0.25, -1.75, clippedIntegerSampleCount = 3L)
        val snapshot = streamInfo(initialized = true).copy(outputTelemetry = telemetry).toSignalPathSnapshot()

        assertEquals(telemetry, snapshot.outputTelemetry)
    }

    @Test
    fun `initialized integer pcm reports valid bits separately from container`() {
        val cases = listOf(16 to 16, 24 to 24, 24 to 32, 32 to 32)

        cases.forEach { (validBits, containerBits) ->
            val snapshot = streamInfo(
                validBits = validBits,
                containerBits = containerBits,
                initialized = true,
            ).toSignalPathSnapshot()

            assertEquals(OutputNegotiationStatus.Accepted, snapshot.negotiation.status)
            val pcm = snapshot.negotiation.negotiatedFormat as AudioFormat.Pcm
            assertEquals(validBits, pcm.validBitsPerSample)
            assertEquals(containerBits, pcm.containerBitsPerSample)
            assertEquals(AudioChannelLayout.Stereo, pcm.channelLayout)
            assertEquals(AudioByteOrder.LittleEndian, pcm.byteOrder)
            assertEquals(false, pcm.isFloat)
        }
    }

    @Test
    fun `initialized float32 session is reported as float pcm`() {
        val snapshot = streamInfo(
            validBits = 32,
            containerBits = 32,
            isFloat = true,
            initialized = true,
        ).toSignalPathSnapshot(osName = "Windows 11")

        assertEquals(OutputNegotiationStatus.Accepted, snapshot.negotiation.status)
        assertEquals(
            AudioFormat.Pcm(
                sampleRateHz = 96_000,
                validBitsPerSample = 32,
                containerBitsPerSample = 32,
                byteOrder = AudioByteOrder.LittleEndian,
                channelLayout = AudioChannelLayout.Stereo,
                isFloat = true,
            ),
            snapshot.negotiation.negotiatedFormat,
        )
        assertEquals(BitPerfectVerificationStatus.NotRun, snapshot.verification.status)
    }

    @Test
    fun `uninitialized session does not claim an accepted output format`() {
        val snapshot = streamInfo(initialized = false).toSignalPathSnapshot()

        assertEquals(OutputNegotiationStatus.Unknown, snapshot.negotiation.status)
        assertNull(snapshot.negotiation.negotiatedFormat)
        assertNull(snapshot.negotiation.requestedFormat)
        assertEquals(BitPerfectVerificationStatus.NotRun, snapshot.verification.status)
    }

    @Test
    fun `unsupported layout or invalid pcm fields do not claim an accepted format`() {
        val unsupportedLayout = streamInfo(channels = 6, initialized = true).toSignalPathSnapshot()
        val invalidPrecision = streamInfo(validBits = 24, containerBits = 16, initialized = true)
            .toSignalPathSnapshot()

        listOf(unsupportedLayout, invalidPrecision).forEach { snapshot ->
            assertEquals(OutputNegotiationStatus.Unknown, snapshot.negotiation.status)
            assertNull(snapshot.negotiation.negotiatedFormat)
        }
    }

    @Test
    fun `shared converted playback reports its initialized float session without direct output`() {
        val snapshot = streamInfo(
            sourceRate = 44_100,
            sourceBits = 0,
            rate = 48_000,
            validBits = 32,
            containerBits = 32,
            isFloat = true,
            exclusive = false,
            lossless = false,
            initialized = true,
        ).toSignalPathSnapshot(osName = "Windows 11")

        assertEquals(AudioBackend.WasapiShared, snapshot.negotiation.backend)
        assertEquals(OutputNegotiationStatus.Accepted, snapshot.negotiation.status)
        assertEquals(DirectPathStatus.NotRequested, snapshot.directPath.status)
        assertNull(snapshot.directPath.reason)
        assertEquals(BitPerfectVerificationStatus.NotRun, snapshot.verification.status)
        assertEquals(
            SignalPathStageStatus.Converted,
            snapshot.stages.single { it.stage == SignalPathStage.FormatConversion }.status,
        )
    }

    private fun streamInfo(
        sourceRate: Int = 96_000,
        sourceBits: Int = 24,
        sourceFormatKind: Int = LAZER_AUDIO_SOURCE_FORMAT_PCM,
        sourceDsdRateMultiplier: Int = 0,
        rate: Int = 96_000,
        channels: Int = 2,
        validBits: Int = 24,
        containerBits: Int = 24,
        isFloat: Boolean = false,
        exclusive: Boolean = true,
        lossless: Boolean = true,
        bitPerfect: Boolean = false,
        initialized: Boolean = false,
        nativeFormatSelection: Int = LAZER_AUDIO_FORMAT_SELECTION_UNKNOWN,
    ) = LazerHiFiStreamInfo(
        codec = "flac",
        sourceSampleRate = sourceRate,
        sourceChannels = 2,
        sourceBitsPerSample = sourceBits,
        sourceFormatKind = sourceFormatKind,
        sourceDsdRateMultiplier = sourceDsdRateMultiplier,
        sampleRate = rate,
        channels = channels,
        bitsPerSample = validBits,
        exclusive = exclusive,
        lossless = lossless,
        bitPerfectActive = bitPerfect,
        signalPath = SignalPathSnapshot(),
        outputContainerBitsPerSample = containerBits,
        outputIsFloat = isFloat,
        outputFormatInitialized = initialized,
        formatSelection = nativeFormatSelection.toOutputFormatSelection(),
    )
}
