package dev.naominet.lazer

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException

/** A renderer can only follow a contiguous, advertised portion of the desktop queue. */
internal data class DesktopUpnpQueueSegment(
    val startIndex: Int,
    val endExclusive: Int,
    val currentIndex: Int,
) {
    val size: Int get() = endExclusive - startIndex
}

internal fun desktopUpnpQueueSegment(
    supported: List<Boolean>,
    currentIndex: Int,
): DesktopUpnpQueueSegment? {
    if (currentIndex !in supported.indices || !supported[currentIndex]) return null
    var start = currentIndex
    while (start > 0 && supported[start - 1]) start--
    var end = currentIndex + 1
    while (end < supported.size && supported[end]) end++
    return DesktopUpnpQueueSegment(start, end, currentIndex - start)
}

/**
 * A STOPPED report is treated as a natural end only when the renderer still points at the
 * active lease and its position reached the end. Some renderers reset position to zero at EOF,
 * so the immediately preceding PLAYING sample is also considered.
 */
internal fun desktopUpnpIsNaturalEnd(
    transportState: String?,
    trackUri: String?,
    expectedTrackUri: String,
    transportStatus: String?,
    positionMillis: Long?,
    durationMillis: Long?,
    previousTransportState: String?,
    previousTransportStatus: String?,
    previousPositionMillis: Long?,
    previousDurationMillis: Long?,
    previousSampleIsFresh: Boolean,
    observedPlayingForCurrentTrack: Boolean,
    explicitStopRequested: Boolean,
): Boolean {
    if (explicitStopRequested || !observedPlayingForCurrentTrack ||
        !transportState.equals("STOPPED", ignoreCase = true)
    ) return false
    if (trackUri != expectedTrackUri) return false
    val transportIsHealthy = if (transportStatus != null) {
        transportStatus.equals("OK", ignoreCase = true)
    } else {
        previousTransportStatus.equals("OK", ignoreCase = true)
    }
    if (!transportIsHealthy) return false

    fun reachedEnd(position: Long?, duration: Long?): Boolean {
        if (position == null || duration == null || duration <= 0L || position < 0L) return false
        val tolerance = maxOf(250L, duration / 50L).coerceAtMost(3_000L).coerceAtMost(duration / 2L)
        return position >= duration - tolerance
    }

    return reachedEnd(positionMillis, durationMillis) ||
        (previousSampleIsFresh && previousTransportState.equals("PLAYING", ignoreCase = true) &&
            reachedEnd(previousPositionMillis, previousDurationMillis))
}

internal fun desktopUpnpNextQueueIndex(
    queueSize: Int,
    currentIndex: Int,
    playMode: DesktopPlayMode,
    shuffleIndex: Int? = null,
    naturalEnd: Boolean = false,
): Int? {
    if (queueSize <= 0 || currentIndex !in 0 until queueSize) return null
    return when (playMode) {
        DesktopPlayMode.Sequential -> (currentIndex + 1).takeIf { it < queueSize }
        DesktopPlayMode.ListLoop -> (currentIndex + 1) % queueSize
        DesktopPlayMode.SingleLoop -> if (naturalEnd) currentIndex else (currentIndex + 1) % queueSize
        DesktopPlayMode.Shuffle -> if (queueSize == 1) currentIndex
            else shuffleIndex?.takeIf { it in 0 until queueSize && it != currentIndex }
    }
}

internal fun desktopUpnpAdvanceRetryDelayMillis(failureCount: Int): Long =
    listOf(1_000L, 3_000L, 6_000L, 12_000L, 30_000L)
        .getOrElse((failureCount - 1).coerceAtLeast(0)) { 30_000L }

/** Schedules active media lease renewal with wrap-safe monotonic-clock comparisons. */
internal class DesktopUpnpMediaLeaseRenewalSchedule(
    intervalNanos: Long = DEFAULT_INTERVAL_NANOS,
    nowNanos: Long = System.nanoTime(),
) {
    private val intervalNanos = intervalNanos.also {
        require(it > 0L) { "The media lease renewal interval must be positive." }
    }

    @Volatile
    private var nextRenewalNanos = nowNanos + this.intervalNanos

    fun isDue(nowNanos: Long): Boolean = nowNanos - nextRenewalNanos >= 0L

    fun reset(nowNanos: Long = System.nanoTime()) {
        nextRenewalNanos = nowNanos + intervalNanos
    }

    private companion object {
        const val DEFAULT_INTERVAL_NANOS = 6L * 60L * 60L * 1_000_000_000L
    }
}

internal fun desktopUpnpPreviousQueueIndex(queueSize: Int, currentIndex: Int): Int? =
    if (queueSize <= 0 || currentIndex !in 0 until queueSize) null
    else (currentIndex - 1 + queueSize) % queueSize

internal enum class DesktopUpnpTransportIntent {
    PLAYING,
    PAUSED,
}

internal data class DesktopUpnpPositionCheckpoint(
    val trackUri: String,
    val positionMillis: Long,
    val durationMillis: Long,
    val transportState: String,
    val observedAtNanos: Long,
)

/** Carries a saved offset to a replacement media lease for the same queue track. */
internal fun desktopUpnpRebindPositionCheckpoint(
    checkpoint: DesktopUpnpPositionCheckpoint?,
    previousUri: String,
    reboundUri: String,
): DesktopUpnpPositionCheckpoint? = checkpoint
    ?.takeIf { it.trackUri == previousUri }
    ?.copy(trackUri = reboundUri)

/** Preserves the later of the frozen point and a fresh position read for the old lease. */
internal fun desktopUpnpRouteRebindSeekTarget(
    checkpoint: DesktopUpnpPositionCheckpoint?,
    previousUri: String,
    observedUri: String?,
    observedState: String?,
    observedPositionMillis: Long?,
    observedDurationMillis: Long?,
    trackDurationMillis: Long,
    observedAtNanos: Long = System.nanoTime(),
): Long? {
    if (previousUri.isBlank()) return null
    val frozen = checkpoint?.takeIf {
        it.trackUri == previousUri && it.positionMillis >= 0L &&
            it.durationMillis > 0L && it.positionMillis < it.durationMillis
    }
    val fresh = desktopUpnpPositionCheckpoint(
        expectedUri = previousUri,
        observedUri = observedUri,
        transportState = observedState,
        positionMillis = observedPositionMillis,
        durationMillis = observedDurationMillis,
        observedAtNanos = observedAtNanos,
    )
    val position = listOfNotNull(frozen?.positionMillis, fresh?.positionMillis).maxOrNull() ?: return null
    val duration = listOfNotNull(frozen?.durationMillis, fresh?.durationMillis, trackDurationMillis)
        .filter { it > 0L }
        .minOrNull() ?: return null
    return position.coerceAtMost((duration - DESKTOP_UPNP_RECOVERY_SEEK_END_MARGIN_MILLIS).coerceAtLeast(0L))
}

private const val DESKTOP_UPNP_POSITION_CHECKPOINT_MAX_AGE_NANOS = 10_000_000_000L
private const val DESKTOP_UPNP_RECOVERY_SEEK_TOLERANCE_MILLIS = 1_500L
private const val DESKTOP_UPNP_RECOVERY_SEEK_END_MARGIN_MILLIS = 250L

internal fun desktopUpnpPositionCheckpoint(
    expectedUri: String,
    observedUri: String?,
    transportState: String?,
    positionMillis: Long?,
    durationMillis: Long?,
    observedAtNanos: Long = System.nanoTime(),
): DesktopUpnpPositionCheckpoint? {
    if (expectedUri.isBlank() || observedUri != expectedUri) return null
    val normalizedState = when {
        transportState.equals("PLAYING", ignoreCase = true) -> "PLAYING"
        transportState.equals("PAUSED_PLAYBACK", ignoreCase = true) ||
            transportState.equals("PAUSED", ignoreCase = true) -> "PAUSED_PLAYBACK"
        transportState.equals("STOPPED", ignoreCase = true) -> "STOPPED"
        else -> return null
    }
    val position = positionMillis ?: return null
    val duration = durationMillis ?: return null
    if (position < 0L || duration <= 0L || position >= duration) return null
    return DesktopUpnpPositionCheckpoint(expectedUri, position, duration, normalizedState, observedAtNanos)
}

/** Returns null when the renderer is already at or past the saved point and must not be rewound. */
internal fun desktopUpnpRecoverySeekTarget(
    checkpoint: DesktopUpnpPositionCheckpoint?,
    expectedUri: String,
    observedUri: String?,
    observedState: String?,
    observedPositionMillis: Long?,
    trackDurationMillis: Long,
): Long? {
    checkpoint ?: return null
    if (checkpoint.trackUri != expectedUri || checkpoint.positionMillis < 0L || checkpoint.durationMillis <= 0L) {
        return null
    }
    val normalizedState = when {
        observedState.equals("PLAYING", ignoreCase = true) -> "PLAYING"
        observedState.equals("PAUSED_PLAYBACK", ignoreCase = true) ||
            observedState.equals("PAUSED", ignoreCase = true) -> "PAUSED_PLAYBACK"
        observedState.equals("STOPPED", ignoreCase = true) -> "STOPPED"
        else -> null
    }
    if (observedUri == expectedUri && normalizedState == null) return null
    if (observedUri == expectedUri && observedPositionMillis != null &&
        observedPositionMillis >= checkpoint.positionMillis - DESKTOP_UPNP_RECOVERY_SEEK_TOLERANCE_MILLIS
    ) return null

    val knownDurations = listOf(checkpoint.durationMillis, trackDurationMillis).filter { it > 0L }
    val duration = knownDurations.minOrNull() ?: return null
    val latestSafePosition = (duration - DESKTOP_UPNP_RECOVERY_SEEK_END_MARGIN_MILLIS).coerceAtLeast(0L)
    return checkpoint.positionMillis.coerceAtMost(latestSafePosition)
}

internal fun desktopUpnpRecoveryPositionConfirmed(
    targetPositionMillis: Long,
    observedPositionMillis: Long?,
    toleranceMillis: Long = 3_000L,
): Boolean = observedPositionMillis == null ||
    kotlin.math.abs(observedPositionMillis - targetPositionMillis) <= toleranceMillis

internal data class DesktopUpnpRecoveryPlan(
    val setMediaUri: Boolean,
    val command: DesktopUpnpTransportCommand?,
    val pauseFromStopped: Boolean = false,
    val intent: DesktopUpnpTransportIntent = DesktopUpnpTransportIntent.PLAYING,
    val seekPositionMillis: Long? = null,
)

/** Keeps the queue session and its last requested transport intent alive across a renderer outage. */
internal class DesktopUpnpRecoveryState(
    initialIntent: DesktopUpnpTransportIntent = DesktopUpnpTransportIntent.PLAYING,
) {
    private val reconnectPending = AtomicBoolean(false)
    private val recoveryInProgress = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val generation = AtomicLong(0L)
    @Volatile private var latestPositionCheckpoint: DesktopUpnpPositionCheckpoint? = null
    @Volatile private var reconnectPositionCheckpoint: DesktopUpnpPositionCheckpoint? = null

    @Volatile
    var intent: DesktopUpnpTransportIntent = initialIntent
        private set

    val isCancelled: Boolean get() = cancelled.get()
    val isPending: Boolean get() = reconnectPending.get()
    val isInProgress: Boolean get() = recoveryInProgress.get()

    /** Returns true only for a successful poll after at least one connection failure. */
    fun observeConnection(
        state: DesktopUpnpConnectionState,
        nowNanos: Long = System.nanoTime(),
    ): Boolean {
        if (cancelled.get()) return false
        if (state == DesktopUpnpConnectionState.DISCONNECTED) {
            requestRecovery(nowNanos)
            return false
        }
        return state == DesktopUpnpConnectionState.CONNECTED && reconnectPending.get()
    }

    /** Freezes the current checkpoint when local network routing changes under an active session. */
    fun requestRecovery(nowNanos: Long = System.nanoTime()) {
        if (cancelled.get() || !reconnectPending.compareAndSet(false, true)) return
        val latest = latestPositionCheckpoint
        reconnectPositionCheckpoint = latest?.takeIf {
            nowNanos >= it.observedAtNanos &&
                nowNanos - it.observedAtNanos <= DESKTOP_UPNP_POSITION_CHECKPOINT_MAX_AGE_NANOS
        }
    }

    fun observePlaybackPosition(
        expectedUri: String,
        observedUri: String?,
        transportState: String?,
        positionMillis: Long?,
        durationMillis: Long?,
        observedAtNanos: Long = System.nanoTime(),
    ) {
        if (reconnectPending.get() || cancelled.get()) return
        if (!observedUri.isNullOrBlank() && observedUri != expectedUri) {
            latestPositionCheckpoint = null
            return
        }
        if (observedUri == expectedUri && positionMillis != null && durationMillis != null &&
            durationMillis > 0L && positionMillis >= durationMillis
        ) {
            latestPositionCheckpoint = null
            return
        }
        desktopUpnpPositionCheckpoint(
            expectedUri,
            observedUri,
            transportState,
            positionMillis,
            durationMillis,
            observedAtNanos,
        )?.let { latestPositionCheckpoint = it }
    }

    /** A successful user seek replaces a frozen reconnect point as well as the live checkpoint. */
    fun recordUserSeek(
        expectedUri: String,
        observedUri: String?,
        transportState: String?,
        positionMillis: Long,
        durationMillis: Long?,
        observedAtNanos: Long = System.nanoTime(),
    ) {
        val checkpoint = desktopUpnpPositionCheckpoint(
            expectedUri,
            observedUri,
            transportState,
            positionMillis,
            durationMillis,
            observedAtNanos,
        )
        if (checkpoint == null) {
            if (observedUri == expectedUri && durationMillis != null && durationMillis > 0L &&
                positionMillis >= durationMillis
            ) {
                latestPositionCheckpoint = null
                if (reconnectPending.get()) reconnectPositionCheckpoint = null
            }
            return
        }
        latestPositionCheckpoint = checkpoint
        if (reconnectPending.get()) reconnectPositionCheckpoint = checkpoint
    }

    fun positionCheckpointForRecovery(expectedUri: String): DesktopUpnpPositionCheckpoint? =
        reconnectPositionCheckpoint?.takeIf { it.trackUri == expectedUri }

    fun beginRecovery(): Long? {
        if (cancelled.get() || !reconnectPending.get() || !recoveryInProgress.compareAndSet(false, true)) return null
        return generation.get()
    }

    fun isCurrentRecovery(token: Long): Boolean = generation.get() == token && !cancelled.get()

    fun finishRecovery(token: Long, succeeded: Boolean) {
        if (succeeded && isCurrentRecovery(token)) {
            reconnectPending.set(false)
            latestPositionCheckpoint = null
            reconnectPositionCheckpoint = null
        }
        recoveryInProgress.set(false)
    }

    fun acknowledgeRestoredSession(intent: DesktopUpnpTransportIntent = DesktopUpnpTransportIntent.PLAYING) {
        generation.incrementAndGet()
        this.intent = intent
        if (!cancelled.get()) reconnectPending.set(false)
        latestPositionCheckpoint = null
        reconnectPositionCheckpoint = null
    }

    fun cancel() {
        generation.incrementAndGet()
        cancelled.set(true)
        reconnectPending.set(false)
        latestPositionCheckpoint = null
        reconnectPositionCheckpoint = null
    }

    /** A deliberate Play is the only user action that re-arms a stopped session. */
    fun requestPlay() {
        generation.incrementAndGet()
        intent = DesktopUpnpTransportIntent.PLAYING
        cancelled.set(false)
        if (reconnectPending.compareAndSet(false, true)) {
            val latest = latestPositionCheckpoint
            reconnectPositionCheckpoint = latest?.takeIf {
                val nowNanos = System.nanoTime()
                nowNanos >= it.observedAtNanos &&
                    nowNanos - it.observedAtNanos <= DESKTOP_UPNP_POSITION_CHECKPOINT_MAX_AGE_NANOS
            }
        }
    }

    fun requestPause() {
        generation.incrementAndGet()
        intent = DesktopUpnpTransportIntent.PAUSED
    }

    fun observeTransportState(state: String?) {
        if (cancelled.get() || reconnectPending.get()) return
        intent = when {
            state.equals("PLAYING", ignoreCase = true) -> DesktopUpnpTransportIntent.PLAYING
            state.equals("PAUSED_PLAYBACK", ignoreCase = true) ||
                state.equals("PAUSED", ignoreCase = true) -> DesktopUpnpTransportIntent.PAUSED
            else -> intent
        }
    }

    fun plan(expectedUri: String, observedUri: String?, observedState: String?): DesktopUpnpRecoveryPlan? {
        if (!reconnectPending.get() || cancelled.get()) return null
        val replaceUri = observedUri != expectedUri
        val command = when (intent) {
            DesktopUpnpTransportIntent.PLAYING ->
                if (!replaceUri && (observedState.equals("PLAYING", ignoreCase = true) ||
                        observedState.equals("TRANSITIONING", ignoreCase = true))) null
                else DesktopUpnpTransportCommand.PLAY
            DesktopUpnpTransportIntent.PAUSED ->
                if (!replaceUri && (observedState.equals("PAUSED_PLAYBACK", ignoreCase = true) ||
                    observedState.equals("PAUSED", ignoreCase = true))
                ) null
                else if (replaceUri || observedState.equals("STOPPED", ignoreCase = true)) null
                else DesktopUpnpTransportCommand.PAUSE
        }
        val pauseFromStopped = intent == DesktopUpnpTransportIntent.PAUSED &&
            (replaceUri || observedState.equals("STOPPED", ignoreCase = true))
        return DesktopUpnpRecoveryPlan(replaceUri, command, pauseFromStopped, intent)
    }
}

internal fun desktopUpnpRecoveryConfirmed(
    intent: DesktopUpnpTransportIntent,
    expectedUri: String,
    connectionState: DesktopUpnpConnectionState,
    observedUri: String?,
    observedState: String?,
    observedTransportStatus: String? = null,
): Boolean = connectionState == DesktopUpnpConnectionState.CONNECTED && observedUri == expectedUri &&
    !observedTransportStatus.equals("ERROR_OCCURRED", ignoreCase = true) && when (intent) {
        DesktopUpnpTransportIntent.PLAYING -> observedState.equals("PLAYING", ignoreCase = true)
        DesktopUpnpTransportIntent.PAUSED ->
            observedState.equals("PAUSED_PLAYBACK", ignoreCase = true) ||
                observedState.equals("PAUSED", ignoreCase = true)
    }

/** Applies a recovery plan with a cancellation check between URI replacement and transport action. */
internal suspend fun executeDesktopUpnpRecoveryPlan(
    plan: DesktopUpnpRecoveryPlan,
    isCancelled: () -> Boolean,
    setMediaUri: suspend () -> Unit,
    control: suspend (DesktopUpnpTransportCommand) -> Unit,
    isSeekEnabled: suspend () -> Boolean = { true },
    seek: suspend (Long) -> Unit = {},
): Boolean {
    if (isCancelled()) return false
    if (plan.setMediaUri) {
        setMediaUri()
        if (isCancelled()) return false
    }
    var seekApplied = false
    var intentAppliedBySeekFallback = false
    suspend fun attemptRecoverySeek(): Boolean {
        val target = plan.seekPositionMillis ?: return false
        if (isCancelled() || !isSeekEnabled() || isCancelled()) return false
        try {
            seek(target)
            seekApplied = true
            return true
        } catch (error: CancellationException) {
            throw error
        } catch (error: DesktopUpnpSoapFaultException) {
            if (error.errorCode != UPnP_TRANSITION_NOT_AVAILABLE_ERROR_CODE || isCancelled()) return false
            control(DesktopUpnpTransportCommand.PLAY)
            intentAppliedBySeekFallback = true
            if (isCancelled()) return false
            try {
                seek(target)
                seekApplied = true
            } catch (retryError: CancellationException) {
                throw retryError
            } catch (_: DesktopUpnpSoapFaultException) {
                // Position restoration is optional when a renderer advertises but rejects Seek.
            }
            if (plan.intent == DesktopUpnpTransportIntent.PAUSED && !isCancelled()) {
                control(DesktopUpnpTransportCommand.PAUSE)
            }
            return seekApplied
        } catch (_: DesktopUpnpSoapFaultException) {
            // A renderer may advertise REL_TIME yet reject a particular seek target.
            return false
        }
    }

    if (plan.seekPositionMillis != null) attemptRecoverySeek()
    if (isCancelled()) return seekApplied
    if (intentAppliedBySeekFallback) {
        // The fallback already established the requested play/pause state.
    } else if (plan.pauseFromStopped) {
        try {
            control(DesktopUpnpTransportCommand.PAUSE)
        } catch (error: CancellationException) {
            throw error
        } catch (error: DesktopUpnpSoapFaultException) {
            if (error.errorCode != UPnP_TRANSITION_NOT_AVAILABLE_ERROR_CODE) throw error
            if (isCancelled()) return seekApplied
            control(DesktopUpnpTransportCommand.PLAY)
            if (!isCancelled()) control(DesktopUpnpTransportCommand.PAUSE)
        }
    } else {
        plan.command?.let { command ->
            if (!isCancelled()) control(command)
        }
    }
    if (!seekApplied && plan.seekPositionMillis != null && !isCancelled()) {
        attemptRecoverySeek()
    }
    return seekApplied
}

private const val UPnP_TRANSITION_NOT_AVAILABLE_ERROR_CODE = 701
