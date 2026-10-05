package dev.naominet.lazer

import android.media.AudioFormat
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioOutputProvider
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class AndroidMedia3OutputStateTest {
    @Test
    fun `direct path rejects active app DSP even when the HAL accepts mixer preference`() {
        val assessment = assessAndroidMedia3DirectPath(
            routedDeviceKnown = true,
            outputFormat = testPcmOutputFormat(),
            audioTrackFormatMatchesRequested = true,
            appDspMayModifySamples = true,
            mixerAdvertisesExactFormat = true,
            mixerAdvertisesBitPerfectBehavior = true,
            mixerPreferenceAccepted = true,
        )

        assertEquals(DirectPathStatus.Rejected, assessment.status)
        assertEquals(DirectPathReason.DspEnabled, assessment.reason)
    }

    @Test
    fun `matching Android and HAL formats remain unknown while source and decoder are unverified`() {
        val assessment = assessAndroidMedia3DirectPath(
            routedDeviceKnown = true,
            outputFormat = testPcmOutputFormat(),
            audioTrackFormatMatchesRequested = true,
            appDspMayModifySamples = false,
            mixerAdvertisesExactFormat = true,
            mixerAdvertisesBitPerfectBehavior = true,
            mixerPreferenceAccepted = true,
        )

        assertEquals(DirectPathStatus.Unknown, assessment.status)
        assertEquals(DirectPathReason.SourceFormatUnknown, assessment.reason)
    }

    @Test
    fun `fully described source and output are eligible but still require digital capture`() {
        val assessment = assessAndroidMedia3DirectPath(
            routedDeviceKnown = true,
            outputFormat = testPcmOutputFormat(),
            audioTrackFormatMatchesRequested = true,
            appDspMayModifySamples = false,
            mixerAdvertisesExactFormat = true,
            mixerAdvertisesBitPerfectBehavior = true,
            mixerPreferenceAccepted = true,
            sourceKnownLossless = true,
            decoderPreservesSamples = true,
            softwareVolumeAtUnity = true,
        )

        assertEquals(DirectPathStatus.Eligible, assessment.status)
        assertEquals(DirectPathReason.DigitalCaptureNotVerified, assessment.reason)
    }

    @Test
    fun `direct path reports precise output and HAL failures`() {
        val mismatch = assessAndroidMedia3DirectPath(
            routedDeviceKnown = true,
            outputFormat = testPcmOutputFormat(),
            audioTrackFormatMatchesRequested = false,
            appDspMayModifySamples = false,
            mixerAdvertisesExactFormat = true,
            mixerAdvertisesBitPerfectBehavior = true,
            mixerPreferenceAccepted = true,
        )
        val unavailableBehavior = assessAndroidMedia3DirectPath(
            routedDeviceKnown = true,
            outputFormat = testPcmOutputFormat(),
            audioTrackFormatMatchesRequested = true,
            appDspMayModifySamples = false,
            mixerAdvertisesExactFormat = true,
            mixerAdvertisesBitPerfectBehavior = false,
            mixerPreferenceAccepted = false,
        )

        assertEquals(DirectPathReason.OutputFormatMismatch, mismatch.reason)
        assertEquals(DirectPathReason.MixerBehaviorNotAdvertised, unavailableBehavior.reason)
    }

    @Test
    fun `offload is not presented as a linear PCM direct path`() {
        val assessment = assessAndroidMedia3DirectPath(
            routedDeviceKnown = true,
            outputFormat = testPcmOutputFormat().copy(offload = true),
            audioTrackFormatMatchesRequested = true,
            appDspMayModifySamples = false,
            mixerAdvertisesExactFormat = true,
            mixerAdvertisesBitPerfectBehavior = true,
            mixerPreferenceAccepted = true,
        )

        assertEquals(DirectPathStatus.Rejected, assessment.status)
        assertEquals(DirectPathReason.UnsupportedEncoding, assessment.reason)
    }

    @Test
    fun `explicit USB target wins over another exact bit perfect candidate and preserves its evidence`() {
        val choice = chooseAndroidUsbRoute(
            candidates = listOf(
                AndroidUsbRouteCandidate(11, "dac-a", exactFormat = true, bitPerfectBehavior = true),
                AndroidUsbRouteCandidate(22, "dac-b", exactFormat = false, bitPerfectBehavior = false),
            ),
            savedTargetIdentity = "dac-b",
        )

        assertEquals(AndroidUsbRouteChoice(22, AndroidMedia3UsbRouteSelection.USER_SELECTED), choice)
    }

    @Test
    fun `disconnected saved USB target does not fall back to another connected DAC`() {
        val choice = chooseAndroidUsbRoute(
            candidates = listOf(
                AndroidUsbRouteCandidate(11, "other-dac", exactFormat = true, bitPerfectBehavior = true),
            ),
            savedTargetIdentity = "saved-dac",
        )

        assertEquals(AndroidUsbRouteChoice(null, AndroidMedia3UsbRouteSelection.SAVED_TARGET_UNAVAILABLE), choice)
    }

    @Test
    fun `duplicate saved USB identities remain ambiguous rather than selecting the first match`() {
        val choice = chooseAndroidUsbRoute(
            candidates = listOf(
                AndroidUsbRouteCandidate(11, "same-model", exactFormat = true, bitPerfectBehavior = true),
                AndroidUsbRouteCandidate(22, "same-model", exactFormat = true, bitPerfectBehavior = true),
            ),
            savedTargetIdentity = "same-model",
        )

        assertEquals(AndroidUsbRouteChoice(null, AndroidMedia3UsbRouteSelection.AMBIGUOUS), choice)
    }

    @Test
    fun `automatic USB routing uses a unique exact bit perfect format and stays ambiguous across exact ties`() {
        val exact = AndroidUsbRouteCandidate(11, "dac-a", exactFormat = true, bitPerfectBehavior = true)
        val incompatible = AndroidUsbRouteCandidate(22, "dac-b", exactFormat = false, bitPerfectBehavior = false)

        assertEquals(
            AndroidUsbRouteChoice(11, AndroidMedia3UsbRouteSelection.UNIQUE_EXACT_FORMAT),
            chooseAndroidUsbRoute(listOf(exact, incompatible), savedTargetIdentity = null),
        )
        assertEquals(
            AndroidUsbRouteChoice(null, AndroidMedia3UsbRouteSelection.AMBIGUOUS),
            chooseAndroidUsbRoute(
                listOf(exact, exact.copy(deviceId = 22, stableIdentity = "dac-b")),
                savedTargetIdentity = null,
            ),
        )
    }

    @Test
    fun `route evidence for explicit target does not borrow capabilities from another DAC`() {
        val candidates = listOf(
            AndroidUsbRouteCandidate(11, "dac-a", exactFormat = true, bitPerfectBehavior = true),
            AndroidUsbRouteCandidate(22, "dac-b", exactFormat = false, bitPerfectBehavior = false),
        )
        val choice = chooseAndroidUsbRoute(candidates, savedTargetIdentity = "dac-b")
        val selected = candidates.single { it.deviceId == choice.deviceId }

        assertEquals(false, selected.exactFormat)
        assertEquals(false, selected.bitPerfectBehavior)
    }

    @Test
    fun `stable USB identity prefers the address and only falls back to a named type`() {
        assertEquals(
            "type:11/address:2-1.4/name:DAC",
            stableUsbAudioTargetIdentity(11, " 2-1.4 ", "DAC"),
        )
        assertEquals("type:11/name:DAC", stableUsbAudioTargetIdentity(11, "", "DAC"))
        assertNull(stableUsbAudioTargetIdentity(11, "", "  "))
    }

    @Test
    fun `linear PCM output config is reported separately from AudioTrack readback`() {
        val config = AudioOutputProvider.OutputConfig.Builder()
            .setSampleRate(96_000)
            .setEncoding(C.ENCODING_PCM_24BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        assertEquals(
            PlaybackAudioOutputDataSnapshot(
                sampleRateHz = 96_000,
                encodingLabel = "PCM 24-bit",
                channelCount = 2,
                isLinearPcm = true,
                offload = false,
                tunneling = false,
            ),
            config.toPlaybackAudioOutputDataSnapshot(),
        )
    }

    @Test
    fun `encoded offload config is never presented as decoded PCM`() {
        val config = AudioOutputProvider.OutputConfig.Builder()
            .setSampleRate(48_000)
            .setEncoding(C.ENCODING_AC3)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .setIsOffload(true)
            .build()

        assertEquals(
            PlaybackAudioOutputDataSnapshot(
                sampleRateHz = 48_000,
                encodingLabel = null,
                channelCount = 2,
                isLinearPcm = false,
                offload = true,
                tunneling = false,
            ),
            config.toPlaybackAudioOutputDataSnapshot(),
        )
    }

    @Test
    fun `newer output snapshot invalidates a route callback that is still queued`() {
        val gate = AndroidMedia3SnapshotPublicationGate()
        val stale = gate.reserve()
        val current = gate.reserve()

        assertFalse(gate.isCurrent(stale))
        assertTrue(gate.isCurrent(current))
    }

    @Test
    fun `mixer ownership distinguishes unchanged preference external edits and read failures`() {
        val same = { current: String?, applied: String -> current == applied }

        assertEquals(
            MixerPreferenceOwnership.CURRENTLY_OWNED,
            mixerPreferenceOwnership(Result.success("player value"), "player value", same),
        )
        assertEquals(
            MixerPreferenceOwnership.CHANGED_EXTERNALLY,
            mixerPreferenceOwnership(Result.success("user value"), "player value", same),
        )
        assertEquals(
            MixerPreferenceOwnership.CHANGED_EXTERNALLY,
            mixerPreferenceOwnership(Result.success(null), "player value", same),
        )
        assertEquals(
            MixerPreferenceOwnership.UNKNOWN,
            mixerPreferenceOwnership(Result.failure(IllegalStateException()), "player value", same),
        )
    }

    @Test
    fun `USB evidence is removed when AudioTrack moves to the built in route`() {
        val evidence = reconcileAndroidMedia3ActiveRoute(
            routedDeviceId = 1,
            selectedUsbDeviceId = 42,
            selection = AndroidMedia3UsbRouteSelection.UNIQUE_EXACT_FORMAT,
            preferredDeviceAccepted = true,
            mixerAdvertisesExactFormat = true,
            mixerAdvertisesBitPerfectBehavior = true,
            mixerPreferenceAccepted = true,
        )

        assertEquals(AndroidMedia3UsbRouteSelection.NOT_CURRENTLY_ROUTED, evidence.selection)
        assertNull(evidence.selectedUsbDeviceId)
        assertNull(evidence.preferredDeviceAccepted)
        assertNull(evidence.mixerAdvertisesExactFormat)
        assertNull(evidence.mixerAdvertisesBitPerfectBehavior)
        assertNull(evidence.mixerPreferenceAccepted)
    }

    @Test
    fun `USB A evidence is removed when AudioTrack routes to USB B`() {
        val evidence = reconcileAndroidMedia3ActiveRoute(
            routedDeviceId = 84,
            selectedUsbDeviceId = 42,
            selection = AndroidMedia3UsbRouteSelection.UNIQUE_EXACT_FORMAT,
            preferredDeviceAccepted = true,
            mixerAdvertisesExactFormat = true,
            mixerAdvertisesBitPerfectBehavior = true,
            mixerPreferenceAccepted = true,
        )

        assertEquals(AndroidMedia3UsbRouteSelection.NOT_CURRENTLY_ROUTED, evidence.selection)
        assertNull(evidence.selectedUsbDeviceId)
        assertNull(evidence.mixerAdvertisesBitPerfectBehavior)
        assertNull(evidence.mixerPreferenceAccepted)
    }

    @Test
    fun `route evidence is retained only for the currently routed selected USB device`() {
        val evidence = reconcileAndroidMedia3ActiveRoute(
            routedDeviceId = 42,
            selectedUsbDeviceId = 42,
            selection = AndroidMedia3UsbRouteSelection.UNIQUE_EXACT_FORMAT,
            preferredDeviceAccepted = true,
            mixerAdvertisesExactFormat = true,
            mixerAdvertisesBitPerfectBehavior = true,
            mixerPreferenceAccepted = true,
        )

        assertEquals(42, evidence.selectedUsbDeviceId)
        assertEquals(AndroidMedia3UsbRouteSelection.UNIQUE_EXACT_FORMAT, evidence.selection)
        assertTrue(evidence.mixerAdvertisesBitPerfectBehavior == true)
        assertTrue(evidence.mixerPreferenceAccepted == true)
    }

    @Test
    fun `ambiguous connected USB devices remain unknown without borrowing route evidence`() {
        val evidence = reconcileAndroidMedia3ActiveRoute(
            routedDeviceId = 42,
            selectedUsbDeviceId = null,
            selection = AndroidMedia3UsbRouteSelection.AMBIGUOUS,
            preferredDeviceAccepted = null,
            mixerAdvertisesExactFormat = null,
            mixerAdvertisesBitPerfectBehavior = null,
            mixerPreferenceAccepted = null,
        )

        assertEquals(AndroidMedia3UsbRouteSelection.AMBIGUOUS, evidence.selection)
        assertNull(evidence.selectedUsbDeviceId)
        assertNull(evidence.mixerPreferenceAccepted)
    }

    @Test
    fun `failure reading existing mixer preference never applies an override`() {
        var attemptedWrites = 0
        val result = captureAndApplyMixerPreference(
            readPreviousPreference = { error("system preference is temporarily unavailable") },
            applyDesiredPreference = {
                attemptedWrites += 1
                true
            },
        )

        assertSame(MixerPreferenceApplyResult.PreviousPreferenceUnavailable, result)
        assertEquals(0, attemptedWrites)
    }

    @Test
    fun `successful null read is preserved as the previous no preference value`() {
        var attemptedWrites = 0
        val result = captureAndApplyMixerPreference<String>(
            readPreviousPreference = { null },
            applyDesiredPreference = {
                attemptedWrites += 1
                true
            },
        )

        assertEquals(MixerPreferenceApplyResult.Applied<String>(null), result)
        assertEquals(1, attemptedWrites)
    }

    @Test
    fun `rejected system override does not create a saved preference transaction`() {
        val result = captureAndApplyMixerPreference(
            readPreviousPreference = { "user preference" },
            applyDesiredPreference = { false },
        )

        assertFalse(result is MixerPreferenceApplyResult.Applied)
        assertSame(MixerPreferenceApplyResult.Rejected, result)
    }

    @Test
    fun `rejected clear keeps the mixer preference transaction eligible for retry`() {
        var clearAttempts = 0
        val result = restoreMixerPreferenceIfOwned(
            currentPreference = Result.success("player preference"),
            appliedPreference = "player preference",
            previousPreference = null,
            samePreference = { current, applied -> current == applied },
            clearPreference = {
                clearAttempts += 1
                false
            },
            setPreviousPreference = { error("no previous preference should be set") },
        )

        assertEquals(MixerPreferenceRestoreResult.REJECTED, result)
        assertEquals(1, clearAttempts)
    }

    @Test
    fun `mixer restore retries an unavailable read once before restoring owned preference`() {
        var reads = 0
        var clears = 0
        val result = restoreMixerPreferenceWithRetry(
            readCurrentPreference = {
                reads += 1
                if (reads == 1) Result.failure(IllegalStateException("temporary read failure"))
                else Result.success("player preference")
            },
            appliedPreference = "player preference",
            previousPreference = null,
            samePreference = { current, applied -> current == applied },
            clearPreference = {
                clears += 1
                true
            },
            setPreviousPreference = { error("no previous preference should be set") },
        )

        assertEquals(MixerPreferenceRestoreResult.RESTORED, result)
        assertEquals(2, reads)
        assertEquals(1, clears)
    }

    @Test
    fun `mixer restore retries one rejected restore operation`() {
        var clears = 0
        val result = restoreMixerPreferenceWithRetry(
            readCurrentPreference = { Result.success("player preference") },
            appliedPreference = "player preference",
            previousPreference = null,
            samePreference = { current, applied -> current == applied },
            clearPreference = {
                clears += 1
                clears > 1
            },
            setPreviousPreference = { error("no previous preference should be set") },
        )

        assertEquals(MixerPreferenceRestoreResult.RESTORED, result)
        assertEquals(2, clears)
    }

    @Test
    fun `mixer restore leaves an externally changed preference untouched`() {
        var writes = 0
        val result = restoreMixerPreferenceWithRetry(
            readCurrentPreference = { Result.success("external preference") },
            appliedPreference = "player preference",
            previousPreference = "original preference",
            samePreference = { current, applied -> current == applied },
            clearPreference = {
                writes += 1
                true
            },
            setPreviousPreference = {
                writes += 1
                true
            },
        )

        assertEquals(MixerPreferenceRestoreResult.CHANGED_EXTERNALLY, result)
        assertEquals(0, writes)
    }
}

private fun testPcmOutputFormat() = PlaybackAudioOutputDataSnapshot(
    sampleRateHz = 96_000,
    encodingLabel = "PCM 24-bit",
    channelCount = 2,
    isLinearPcm = true,
    offload = false,
    tunneling = false,
)
