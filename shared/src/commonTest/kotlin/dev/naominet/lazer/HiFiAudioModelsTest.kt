package dev.naominet.lazer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class HiFiAudioModelsTest {
    @Test
    fun `PCM models valid precision separately from its physical container`() {
        val pcm = AudioFormat.Pcm(
            sampleRateHz = 96_000,
            validBitsPerSample = 24,
            containerBitsPerSample = 32,
            byteOrder = AudioByteOrder.LittleEndian,
            channelLayout = AudioChannelLayout.Stereo,
        )

        assertEquals(24, pcm.validBitsPerSample)
        assertEquals(32, pcm.containerBitsPerSample)
        assertFailsWith<IllegalArgumentException> {
            pcm.copy(validBitsPerSample = 33)
        }
    }

    @Test
    fun `DSD and DoP retain DSD rate without pretending it is a PCM sample rate`() {
        val dsd = AudioFormat.Dsd(DsdRate.Dsd128, AudioChannelLayout.Stereo)
        val dop = AudioFormat.DoP(
            rate = DsdRate.Dsd128,
            carrierSampleRateHz = 352_800,
            channelLayout = AudioChannelLayout.Stereo,
        )

        assertEquals(5_644_800, dsd.rate.bitsPerSecondPerChannel)
        assertEquals(24, dop.containerBitsPerSample)
        assertFailsWith<IllegalArgumentException> {
            dop.copy(carrierSampleRateHz = 192_000)
        }
    }

    @Test
    fun `accepted format negotiation does not imply digital bit perfect verification`() {
        val format = AudioFormat.Pcm(
            sampleRateHz = 44_100,
            validBitsPerSample = 16,
            containerBitsPerSample = 16,
            byteOrder = AudioByteOrder.LittleEndian,
            channelLayout = AudioChannelLayout.Stereo,
        )
        val snapshot = SignalPathSnapshot(
            negotiation = OutputNegotiationSnapshot(
                status = OutputNegotiationStatus.Accepted,
                requestedFormat = format,
                negotiatedFormat = format,
                backend = AudioBackend.WasapiExclusive,
            ),
            directPath = DirectPathSnapshot(
                status = DirectPathStatus.Negotiated,
                reason = DirectPathReason.DigitalCaptureNotVerified,
            ),
        )

        assertEquals(OutputNegotiationStatus.Accepted, snapshot.negotiation.status)
        assertEquals(BitPerfectVerificationStatus.NotRun, snapshot.verification.status)
        assertEquals(DirectPathStatus.Negotiated, snapshot.directPath.status)
        assertEquals(DirectPathReason.DigitalCaptureNotVerified, snapshot.directPath.reason)
        assertNull(snapshot.verification.method)
    }

    @Test
    fun `output telemetry validates measurements and is retained by signal path`() {
        val telemetry = OutputTelemetrySnapshot(
            samplePeakDbfs = -0.2,
            limiterGainReductionDb = -1.5,
            clippedIntegerSampleCount = 3L,
        )
        val silentTelemetry = OutputTelemetrySnapshot(
            samplePeakDbfs = Double.NEGATIVE_INFINITY,
            limiterGainReductionDb = 0.0,
        )

        assertEquals(telemetry, SignalPathSnapshot(outputTelemetry = telemetry).outputTelemetry)
        assertEquals(-1.5, telemetry.limiterGainReductionDb)
        assertEquals(3L, telemetry.clippedIntegerSampleCount)
        assertEquals(Double.NEGATIVE_INFINITY, silentTelemetry.samplePeakDbfs)
        assertEquals(null, silentTelemetry.clippedIntegerSampleCount)
        assertFailsWith<IllegalArgumentException> {
            OutputTelemetrySnapshot(Double.NaN, 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            OutputTelemetrySnapshot(Double.POSITIVE_INFINITY, 0.0)
        }
        assertFailsWith<IllegalArgumentException> {
            OutputTelemetrySnapshot(0.0, Double.NaN)
        }
        assertFailsWith<IllegalArgumentException> {
            OutputTelemetrySnapshot(0.0, Double.POSITIVE_INFINITY)
        }
        assertFailsWith<IllegalArgumentException> {
            OutputTelemetrySnapshot(0.0, 0.1)
        }
        assertFailsWith<IllegalArgumentException> {
            OutputTelemetrySnapshot(0.0, 0.0, clippedIntegerSampleCount = -1L)
        }
    }

    @Test
    fun `failed direct path requires a reason and strict policy forbids fallback`() {
        assertFailsWith<IllegalArgumentException> {
            DirectPathSnapshot(status = DirectPathStatus.Rejected, reason = null)
        }
        assertFailsWith<IllegalArgumentException> {
            PlaybackPolicy(
                directness = DirectnessPreference.RequireBitPerfect,
                fallback = OutputFallbackPolicy.ConvertToDeviceFormat,
            )
        }
    }

    @Test
    fun `desktop PCM settings resolve to explicit strict or conversion policies`() {
        val strict = resolvePcmPlaybackPolicy(
            bitPerfectRequested = true,
            exclusiveRequested = true,
            processingActive = false,
            bufferDurationMillis = 120,
        )
        assertEquals(DirectnessPreference.RequireBitPerfect, strict.directness)
        assertEquals(OutputFallbackPolicy.Fail, strict.fallback)
        assertEquals(120, strict.bufferDurationMillis)
        assertEquals(1, strict.toNativePcmBitPerfectFlag())

        val exclusivePreferred = resolvePcmPlaybackPolicy(
            bitPerfectRequested = false,
            exclusiveRequested = true,
            processingActive = false,
            bufferDurationMillis = 240,
        )
        assertEquals(DirectnessPreference.PreferBitPerfect, exclusivePreferred.directness)
        assertEquals(OutputFallbackPolicy.ConvertToDeviceFormat, exclusivePreferred.fallback)
        assertEquals(240, exclusivePreferred.bufferDurationMillis)
        assertEquals(0, exclusivePreferred.toNativePcmBitPerfectFlag())

        val sharedCompatible = resolvePcmPlaybackPolicy(
            bitPerfectRequested = false,
            exclusiveRequested = false,
            processingActive = false,
            bufferDurationMillis = 300,
        )
        assertEquals(DirectnessPreference.PreferCompatibility, sharedCompatible.directness)
        assertEquals(OutputFallbackPolicy.ConvertToDeviceFormat, sharedCompatible.fallback)
        assertEquals(0, sharedCompatible.toNativePcmBitPerfectFlag())
    }

    @Test
    fun `active processing downgrades bitperfect request to the selected output preference`() {
        val policy = resolvePcmPlaybackPolicy(
            bitPerfectRequested = true,
            exclusiveRequested = true,
            processingActive = true,
            bufferDurationMillis = 120,
        )

        assertEquals(DirectnessPreference.PreferBitPerfect, policy.directness)
        assertEquals(OutputFallbackPolicy.ConvertToDeviceFormat, policy.fallback)
        assertEquals(0, policy.toNativePcmBitPerfectFlag())
    }

    @Test
    fun `desktop PCM ABI mapping rejects policy values it cannot represent`() {
        val compatible = resolvePcmPlaybackPolicy(
            bitPerfectRequested = false,
            exclusiveRequested = true,
            processingActive = false,
            bufferDurationMillis = 120,
        )
        assertFailsWith<IllegalArgumentException> {
            compatible.copy(fallback = OutputFallbackPolicy.TryOtherBitDepth).toNativePcmBitPerfectFlag()
        }
        assertFailsWith<IllegalArgumentException> {
            compatible.copy(fallback = OutputFallbackPolicy.SystemDefault).toNativePcmBitPerfectFlag()
        }
        assertFailsWith<IllegalArgumentException> {
            compatible.copy(dsdOutput = DsdOutputPreference.PreferNative).toNativePcmBitPerfectFlag()
        }
        assertFailsWith<IllegalArgumentException> {
            compatible.copy(volume = PlaybackVolumePolicy.AllowSoftware).toNativePcmBitPerfectFlag()
        }
    }
}
