package dev.naominet.lazer

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioOutputProvider
import java.util.concurrent.atomic.AtomicLong

/** Selection describes the USB target only when it is currently the AudioTrack's routed output. */
internal enum class AndroidMedia3UsbRouteSelection {
    NONE,
    ONLY_CONNECTED_USB,
    UNIQUE_EXACT_FORMAT,
    USER_SELECTED,
    SAVED_TARGET_UNAVAILABLE,
    AMBIGUOUS,
    NOT_CURRENTLY_ROUTED,
}

/** Framework-independent facts used to choose among the currently connected USB outputs. */
internal data class AndroidUsbRouteCandidate(
    val deviceId: Int,
    val stableIdentity: String?,
    val exactFormat: Boolean?,
    val bitPerfectBehavior: Boolean?,
)

internal data class AndroidUsbRouteChoice(
    val deviceId: Int?,
    val selection: AndroidMedia3UsbRouteSelection,
)

/** Maps Media3's post-processing output config without treating compressed encodings as PCM. */
internal fun AudioOutputProvider.OutputConfig.toPlaybackAudioOutputDataSnapshot(): PlaybackAudioOutputDataSnapshot {
    val pcmEncodingLabel = media3PcmEncodingLabel(encoding)
    return PlaybackAudioOutputDataSnapshot(
        sampleRateHz = sampleRate.takeIf { it > 0 },
        encodingLabel = pcmEncodingLabel,
        channelCount = Integer.bitCount(channelMask).takeIf { it > 0 },
        isLinearPcm = pcmEncodingLabel != null,
        offload = isOffload,
        tunneling = isTunneling,
    )
}

internal fun media3PcmEncodingLabel(encoding: Int): String? = when (encoding) {
    C.ENCODING_PCM_8BIT -> "PCM 8-bit"
    C.ENCODING_PCM_16BIT -> "PCM 16-bit"
    C.ENCODING_PCM_24BIT -> "PCM 24-bit"
    C.ENCODING_PCM_32BIT -> "PCM 32-bit"
    C.ENCODING_PCM_FLOAT -> "PCM float"
    else -> null
}

/**
 * Assesses only evidence visible to the Android player. A successful AudioTrack/HAL negotiation
 * cannot establish source/decoder preservation or prove the DAC's digital input.
 */
internal fun assessAndroidMedia3DirectPath(
    routedDeviceKnown: Boolean,
    outputFormat: PlaybackAudioOutputDataSnapshot?,
    audioTrackFormatMatchesRequested: Boolean?,
    appDspMayModifySamples: Boolean,
    mixerAdvertisesExactFormat: Boolean?,
    mixerAdvertisesBitPerfectBehavior: Boolean?,
    mixerPreferenceAccepted: Boolean?,
    sourceKnownLossless: Boolean? = null,
    decoderPreservesSamples: Boolean? = null,
    softwareVolumeAtUnity: Boolean? = null,
): DirectPathSnapshot {
    fun rejected(reason: DirectPathReason) = DirectPathSnapshot(DirectPathStatus.Rejected, reason)
    fun unknown(reason: DirectPathReason) = DirectPathSnapshot(DirectPathStatus.Unknown, reason)

    if (outputFormat?.offload == true || outputFormat?.tunneling == true || outputFormat?.isLinearPcm == false) {
        return rejected(DirectPathReason.UnsupportedEncoding)
    }
    if (sourceKnownLossless == false) return rejected(DirectPathReason.SourceIsLossy)
    if (decoderPreservesSamples == false) return rejected(DirectPathReason.DecoderChangedSamples)
    if (softwareVolumeAtUnity == false) return rejected(DirectPathReason.SoftwareVolume)
    if (appDspMayModifySamples) return rejected(DirectPathReason.DspEnabled)
    if (audioTrackFormatMatchesRequested == false || mixerAdvertisesExactFormat == false) {
        return rejected(DirectPathReason.OutputFormatMismatch)
    }
    if (mixerAdvertisesBitPerfectBehavior == false) {
        return rejected(DirectPathReason.MixerBehaviorNotAdvertised)
    }
    if (mixerPreferenceAccepted == false) {
        return rejected(DirectPathReason.MixerPreferenceRejected)
    }
    if (
        !routedDeviceKnown || outputFormat == null || !outputFormat.isLinearPcm ||
        outputFormat.sampleRateHz == null || outputFormat.channelCount == null ||
        audioTrackFormatMatchesRequested == null || mixerAdvertisesExactFormat == null ||
        mixerAdvertisesBitPerfectBehavior == null || mixerPreferenceAccepted == null
    ) return unknown(DirectPathReason.DeviceOrBackendUnknown)
    if (sourceKnownLossless != true || decoderPreservesSamples != true) {
        return unknown(DirectPathReason.SourceFormatUnknown)
    }
    if (softwareVolumeAtUnity != true) return unknown(DirectPathReason.SoftwareVolumeUnknown)
    return DirectPathSnapshot(DirectPathStatus.Eligible, DirectPathReason.DigitalCaptureNotVerified)
}

/**
 * A saved target is authoritative: if it is missing or no longer uniquely identifies a device,
 * do not silently send playback to some other USB DAC. Automatic selection remains conservative.
 */
internal fun chooseAndroidUsbRoute(
    candidates: List<AndroidUsbRouteCandidate>,
    savedTargetIdentity: String?,
): AndroidUsbRouteChoice {
    if (savedTargetIdentity != null) {
        val matches = candidates.filter { it.stableIdentity == savedTargetIdentity }
        return when (matches.size) {
            0 -> AndroidUsbRouteChoice(null, AndroidMedia3UsbRouteSelection.SAVED_TARGET_UNAVAILABLE)
            1 -> AndroidUsbRouteChoice(matches.single().deviceId, AndroidMedia3UsbRouteSelection.USER_SELECTED)
            else -> AndroidUsbRouteChoice(null, AndroidMedia3UsbRouteSelection.AMBIGUOUS)
        }
    }

    val bitPerfectMatches = candidates.filter {
        it.exactFormat == true && it.bitPerfectBehavior == true
    }
    if (bitPerfectMatches.size == 1) {
        return AndroidUsbRouteChoice(
            bitPerfectMatches.single().deviceId,
            AndroidMedia3UsbRouteSelection.UNIQUE_EXACT_FORMAT,
        )
    }
    if (bitPerfectMatches.size > 1) {
        return AndroidUsbRouteChoice(null, AndroidMedia3UsbRouteSelection.AMBIGUOUS)
    }

    val exactMatches = candidates.filter { it.exactFormat == true }
    if (exactMatches.size == 1) {
        return AndroidUsbRouteChoice(exactMatches.single().deviceId, AndroidMedia3UsbRouteSelection.UNIQUE_EXACT_FORMAT)
    }
    if (exactMatches.size > 1) {
        return AndroidUsbRouteChoice(null, AndroidMedia3UsbRouteSelection.AMBIGUOUS)
    }
    return when (candidates.size) {
        0 -> AndroidUsbRouteChoice(null, AndroidMedia3UsbRouteSelection.NONE)
        1 -> AndroidUsbRouteChoice(candidates.single().deviceId, AndroidMedia3UsbRouteSelection.ONLY_CONNECTED_USB)
        else -> AndroidUsbRouteChoice(null, AndroidMedia3UsbRouteSelection.AMBIGUOUS)
    }
}

internal data class AndroidMedia3ActiveRouteEvidence(
    val selectedUsbDeviceId: Int?,
    val selection: AndroidMedia3UsbRouteSelection,
    val preferredDeviceAccepted: Boolean?,
    val mixerAdvertisesExactFormat: Boolean?,
    val mixerAdvertisesBitPerfectBehavior: Boolean?,
    val mixerPreferenceAccepted: Boolean?,
)

/**
 * Prevent a device's format capabilities or HAL preference result from being paired with another
 * device's route readback after USB hotplug or Android reroutes an existing AudioTrack.
 */
internal fun reconcileAndroidMedia3ActiveRoute(
    routedDeviceId: Int?,
    selectedUsbDeviceId: Int?,
    selection: AndroidMedia3UsbRouteSelection,
    preferredDeviceAccepted: Boolean?,
    mixerAdvertisesExactFormat: Boolean?,
    mixerAdvertisesBitPerfectBehavior: Boolean?,
    mixerPreferenceAccepted: Boolean?,
): AndroidMedia3ActiveRouteEvidence {
    if (selectedUsbDeviceId != null && routedDeviceId == selectedUsbDeviceId) {
        return AndroidMedia3ActiveRouteEvidence(
            selectedUsbDeviceId = selectedUsbDeviceId,
            selection = selection,
            preferredDeviceAccepted = preferredDeviceAccepted,
            mixerAdvertisesExactFormat = mixerAdvertisesExactFormat,
            mixerAdvertisesBitPerfectBehavior = mixerAdvertisesBitPerfectBehavior,
            mixerPreferenceAccepted = mixerPreferenceAccepted,
        )
    }

    val actualSelection = when {
        selection == AndroidMedia3UsbRouteSelection.AMBIGUOUS -> AndroidMedia3UsbRouteSelection.AMBIGUOUS
        selectedUsbDeviceId != null -> AndroidMedia3UsbRouteSelection.NOT_CURRENTLY_ROUTED
        else -> selection
    }
    return AndroidMedia3ActiveRouteEvidence(
        selectedUsbDeviceId = null,
        selection = actualSelection,
        preferredDeviceAccepted = null,
        mixerAdvertisesExactFormat = null,
        mixerAdvertisesBitPerfectBehavior = null,
        mixerPreferenceAccepted = null,
    )
}

/** A newer route snapshot invalidates any older callback that has not reached the UI yet. */
internal class AndroidMedia3SnapshotPublicationGate {
    private val latest = AtomicLong()

    fun reserve(): Long = latest.incrementAndGet()

    fun isCurrent(sequence: Long): Boolean = sequence == latest.get()
}

internal enum class MixerPreferenceOwnership {
    CURRENTLY_OWNED,
    CHANGED_EXTERNALLY,
    UNKNOWN,
}

internal enum class MixerPreferenceRestoreResult {
    RESTORED,
    CHANGED_EXTERNALLY,
    UNAVAILABLE,
    REJECTED,
}

internal fun <T> mixerPreferenceOwnership(
    currentPreference: Result<T?>,
    appliedPreference: T,
    samePreference: (T?, T) -> Boolean,
): MixerPreferenceOwnership = when {
    currentPreference.isFailure -> MixerPreferenceOwnership.UNKNOWN
    samePreference(currentPreference.getOrNull(), appliedPreference) -> MixerPreferenceOwnership.CURRENTLY_OWNED
    else -> MixerPreferenceOwnership.CHANGED_EXTERNALLY
}

/** Restore only while the current preference still matches the value written by this player. */
internal fun <T> restoreMixerPreferenceIfOwned(
    currentPreference: Result<T?>,
    appliedPreference: T,
    previousPreference: T?,
    samePreference: (T?, T) -> Boolean,
    clearPreference: () -> Boolean,
    setPreviousPreference: (T) -> Boolean,
): MixerPreferenceRestoreResult = when (
    mixerPreferenceOwnership(currentPreference, appliedPreference, samePreference)
) {
    MixerPreferenceOwnership.UNKNOWN -> MixerPreferenceRestoreResult.UNAVAILABLE
    MixerPreferenceOwnership.CHANGED_EXTERNALLY -> MixerPreferenceRestoreResult.CHANGED_EXTERNALLY
    MixerPreferenceOwnership.CURRENTLY_OWNED -> {
        val restored = runCatching {
            if (previousPreference == null) clearPreference() else setPreviousPreference(previousPreference)
        }.getOrDefault(false)
        if (restored) MixerPreferenceRestoreResult.RESTORED else MixerPreferenceRestoreResult.REJECTED
    }
}

/** Retry one time when a system read or restore fails; never retry after ownership changed. */
internal fun <T> restoreMixerPreferenceWithRetry(
    readCurrentPreference: () -> Result<T?>,
    appliedPreference: T,
    previousPreference: T?,
    samePreference: (T?, T) -> Boolean,
    clearPreference: () -> Boolean,
    setPreviousPreference: (T) -> Boolean,
): MixerPreferenceRestoreResult {
    fun restoreOnce(): MixerPreferenceRestoreResult {
        val current = try {
            readCurrentPreference()
        } catch (error: Exception) {
            Result.failure(error)
        }
        return restoreMixerPreferenceIfOwned(
            currentPreference = current,
            appliedPreference = appliedPreference,
            previousPreference = previousPreference,
            samePreference = samePreference,
            clearPreference = clearPreference,
            setPreviousPreference = setPreviousPreference,
        )
    }

    val first = restoreOnce()
    return if (first == MixerPreferenceRestoreResult.UNAVAILABLE ||
        first == MixerPreferenceRestoreResult.REJECTED) {
        restoreOnce()
    } else {
        first
    }
}

internal sealed interface MixerPreferenceApplyResult<out T> {
    data class Applied<T>(val previousPreference: T?) : MixerPreferenceApplyResult<T>
    data object PreviousPreferenceUnavailable : MixerPreferenceApplyResult<Nothing>
    data object Rejected : MixerPreferenceApplyResult<Nothing>
}

/**
 * Capture the caller's existing preference before replacing it. An unreadable value is not the same
 * as no preference: in that case leave the system setting untouched.
 */
internal fun <T> captureAndApplyMixerPreference(
    readPreviousPreference: () -> T?,
    applyDesiredPreference: () -> Boolean,
): MixerPreferenceApplyResult<T> {
    val previous = try {
        readPreviousPreference()
    } catch (_: Exception) {
        return MixerPreferenceApplyResult.PreviousPreferenceUnavailable
    }
    val accepted = try {
        applyDesiredPreference()
    } catch (_: Exception) {
        false
    }
    return if (accepted) MixerPreferenceApplyResult.Applied(previous) else MixerPreferenceApplyResult.Rejected
}
