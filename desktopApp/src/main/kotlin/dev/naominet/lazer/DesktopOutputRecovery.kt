package dev.naominet.lazer

import java.io.IOException
import kotlinx.coroutines.CancellationException

/** A native output loss carries a position that is more current than the UI polling snapshot. */
internal class DesktopAudioDeviceLostException(
    val positionMillis: Long,
    detail: String,
) : IOException(detail.ifBlank { "HiFi output device was lost" })

/** Maps an ABI terminal record to the same result used by the native event callback. */
internal fun desktopTerminalError(event: Int, positionMillis: Long, detail: String): Throwable? = when (event) {
    LAZER_AUDIO_EVENT_DEVICE_LOST -> DesktopAudioDeviceLostException(positionMillis, detail)
    LAZER_AUDIO_EVENT_FAILED -> IOException("原生音频引擎失败：$detail")
    LAZER_AUDIO_EVENT_ENDED -> null
    else -> null
}

/** Playback state kept while the user reconnects and refreshes the same selected output. */
internal data class DesktopOutputRecoveryIntent(
    val trackId: Long,
    val positionMillis: Long,
    val wasPlaying: Boolean,
    val outputIdentity: String?,
    val outputIdentityStable: Boolean,
)

internal const val DESKTOP_OUTPUT_RECOVERY_MAX_ATTEMPTS = 24
private const val DESKTOP_OUTPUT_RECOVERY_PROGRESS_EPSILON = 0.0001f

/** A newly reopened stream is healthy only after its position moves beyond the resume checkpoint. */
internal fun desktopOutputRecoveryProgressAdvanced(
    checkpointProgress: Float,
    observedProgress: Float,
): Boolean = checkpointProgress.isFinite() && observedProgress.isFinite() &&
    observedProgress > checkpointProgress + DESKTOP_OUTPUT_RECOVERY_PROGRESS_EPSILON

/** Circuit breaker for a reopened stream; an initial position snapshot is not proof of health. */
internal class DesktopOutputRecoveryGuard {
    var awaitingConfirmation: Boolean = false
        private set

    private var checkpointProgress: Float? = null

    fun begin(checkpointProgress: Float) {
        this.checkpointProgress = checkpointProgress.takeIf(Float::isFinite)
        awaitingConfirmation = true
    }

    /** Returns true only once the stream has advanced past the checkpoint. */
    fun observeProgress(progress: Float): Boolean {
        val checkpoint = checkpointProgress ?: return false
        if (!awaitingConfirmation || !desktopOutputRecoveryProgressAdvanced(checkpoint, progress)) return false
        clear()
        return true
    }

    fun clear() {
        awaitingConfirmation = false
        checkpointProgress = null
    }
}

/** Runs the same refresh lifecycle used by the controller while keeping enumeration injectable. */
internal suspend fun refreshHifiOutputSnapshot(
    enumerate: suspend () -> List<DesktopAudioOutputDevice>,
    applySnapshot: (List<DesktopAudioOutputDevice>) -> Unit,
    onFailure: (Throwable) -> Unit,
    onFinished: (successful: Boolean) -> Unit,
) {
    var successful = false
    try {
        applySnapshot(enumerate())
        successful = true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        onFailure(error)
    } finally {
        onFinished(successful)
    }
}

/** Exponential backoff capped at five seconds; callers bound the total retry window. */
internal fun desktopOutputRecoveryRetryDelayMillis(attempt: Int): Long = when {
    attempt <= 0 -> 500L
    attempt == 1 -> 1_000L
    attempt == 2 -> 2_000L
    attempt == 3 -> 4_000L
    else -> 5_000L
}

/** Auto-recovery must never choose a system default or a different physical output. */
internal fun canAutomaticallyRecoverOutput(
    intent: DesktopOutputRecoveryIntent?,
    currentTrackId: Long?,
    selectedOutputIdentity: String?,
    automaticResumeAwaitingConfirmation: Boolean = false,
): Boolean = intent != null &&
    !automaticResumeAwaitingConfirmation &&
    currentTrackId == intent.trackId &&
    intent.outputIdentityStable &&
    !intent.outputIdentity.isNullOrBlank() &&
    selectedOutputIdentity == intent.outputIdentity

/**
 * The exact stable device to reopen, or null when the saved identity is absent or the matching
 * hardware endpoint no longer reports a stable identity. Automatic recovery never substitutes a
 * different device or the system default.
 */
internal fun desktopRecoveryDeviceToken(
    intent: DesktopOutputRecoveryIntent,
    devices: List<DesktopAudioOutputDevice>,
): String? {
    val selection = resolveDesktopAudioOutputSelection(intent.outputIdentity, devices)
    if (selection.unavailable) return null
    val stable = devices.firstOrNull { it.identityKey == intent.outputIdentity }?.stableIdentity == true
    return selection.deviceToken?.takeIf { stable }
}

/**
 * Injected side effects for [runDesktopOutputRecovery]. The controller supplies live
 * implementations; tests supply fakes that drive the exact retry/match transaction.
 */
internal interface DesktopOutputRecoveryDriver {
    /** Waits before the zero-based [attempt]. */
    suspend fun awaitRetry(attempt: Int)

    /** True while this request is still the live one for the still-selected track and output. */
    fun isRequestCurrent(): Boolean

    /** True while an ordinary device refresh owns enumeration; the retry waits instead of racing it. */
    fun isSnapshotLoading(): Boolean

    suspend fun enumerateDevices(): List<DesktopAudioOutputDevice>

    /** Applies the refreshed catalogue and clears its last error before matching. */
    fun applySnapshot(devices: List<DesktopAudioOutputDevice>)

    fun switchNativeOutput(deviceToken: String)

    fun refreshEndpointVolume()

    /** Hands the still-current intent back to the resume path. */
    fun resume(intent: DesktopOutputRecoveryIntent)

    fun onAttemptsExhausted()
}

/**
 * The production automatic-recovery transaction: wait, enumerate, require the same stable device,
 * reopen it and then resume. Every side effect is injected so tests drive this exact loop. It stops
 * as soon as the request is superseded or the resume consumes it, and gives up after
 * [DESKTOP_OUTPUT_RECOVERY_MAX_ATTEMPTS].
 */
internal suspend fun runDesktopOutputRecovery(
    intent: DesktopOutputRecoveryIntent,
    driver: DesktopOutputRecoveryDriver,
) {
    var attempt = 0
    while (attempt < DESKTOP_OUTPUT_RECOVERY_MAX_ATTEMPTS) {
        driver.awaitRetry(attempt)
        if (!driver.isRequestCurrent()) return
        if (driver.isSnapshotLoading()) continue
        attempt += 1

        val devices = try {
            driver.enumerateDevices()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            null
        }
        if (!driver.isRequestCurrent()) return
        if (devices == null) continue

        driver.applySnapshot(devices)
        val deviceToken = desktopRecoveryDeviceToken(intent, devices) ?: continue
        driver.switchNativeOutput(deviceToken)
        driver.refreshEndpointVolume()
        driver.resume(intent)
        if (!driver.isRequestCurrent()) return
    }
    if (driver.isRequestCurrent()) driver.onAttemptsExhausted()
}

/** Resume automatically only when the exact saved, stable device identity has returned. */
internal fun canResumeAfterOutputRecovery(
    intent: DesktopOutputRecoveryIntent?,
    currentTrackId: Long?,
    selectedOutputIdentity: String?,
    selectedOutputAvailable: Boolean,
    selectedOutputIdentityStable: Boolean,
): Boolean = intent != null &&
    currentTrackId == intent.trackId &&
    intent.outputIdentityStable &&
    !intent.outputIdentity.isNullOrBlank() &&
    selectedOutputIdentity == intent.outputIdentity &&
    selectedOutputAvailable &&
    selectedOutputIdentityStable

/** Converts the native event position back to the controller's normalized seek position. */
internal fun DesktopOutputRecoveryIntent.progressFor(durationMillis: Long): Float = when {
    durationMillis <= 0L -> 0f
    else -> (positionMillis.coerceIn(0L, durationMillis - 1L).toFloat() / durationMillis.toFloat())
        .coerceIn(0f, 0.999f)
}
