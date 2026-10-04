package dev.naominet.lazer

/** Playback command to restore after a route rebind has installed a new OpenHome queue. */
internal enum class DesktopOpenHomePlaybackIntent {
    PLAY,
    PAUSE,
    STOP,
}

/** A route-recovery plan exists only while the complete local queue mapping is still trustworthy. */
internal data class DesktopOpenHomeRouteRecoveryPlan(
    val currentId: Long,
    val localIndex: Int,
    val intent: DesktopOpenHomePlaybackIntent,
)

internal fun planDesktopOpenHomeRouteRecovery(
    deviceIdentity: String,
    generation: Long,
    binding: DesktopOpenHomeActiveQueueBinding?,
    snapshot: DesktopOpenHomeQueueSnapshot,
    snapshotStillCurrent: Boolean,
    localTrackCount: Int,
    routeChanged: Boolean,
    intentOverride: DesktopOpenHomePlaybackIntent? = null,
): DesktopOpenHomeRouteRecoveryPlan? {
    if (!routeChanged || localTrackCount <= 0) return null
    val activeBinding = binding ?: return null
    val mapped = activeBinding.observe(
        deviceIdentity = deviceIdentity,
        generation = generation,
        snapshot = snapshot,
        snapshotStillCurrent = snapshotStillCurrent,
    ) as? DesktopOpenHomeActiveQueueObservation.Mapped ?: return null
    if (mapped.localIndex !in 0 until localTrackCount) return null

    val intent = intentOverride ?: when (snapshot.transportState.trim().lowercase()) {
        "playing", "buffering", "waiting" -> DesktopOpenHomePlaybackIntent.PLAY
        "paused", "paused_playback" -> DesktopOpenHomePlaybackIntent.PAUSE
        "stopped" -> DesktopOpenHomePlaybackIntent.STOP
        else -> return null
    }
    return DesktopOpenHomeRouteRecoveryPlan(
        currentId = mapped.currentId,
        localIndex = mapped.localIndex,
        intent = intent,
    )
}
