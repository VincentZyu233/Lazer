package dev.naominet.lazer

/** Adapts OpenHome's queue-centric state to the renderer card's shared state model. */
internal fun desktopOpenHomeRendererStatus(snapshot: DesktopOpenHomeQueueSnapshot): DesktopUpnpRendererStatus {
    val state = when (snapshot.transportState.trim().lowercase()) {
        "playing" -> "PLAYING"
        "paused", "paused_playback" -> "PAUSED_PLAYBACK"
        "stopped" -> "STOPPED"
        "buffering", "waiting" -> "TRANSITIONING"
        else -> snapshot.transportState.trim().uppercase().ifBlank { "UNAVAILABLE" }
    }
    return DesktopUpnpRendererStatus(
        transportState = state,
        transportStatus = "OK",
        trackUri = snapshot.currentTrack?.uri,
        trackMetadata = snapshot.currentTrack?.metadata,
        connectionState = DesktopUpnpConnectionState.CONNECTED,
    )
}

/** Keeps discovered device capabilities while replacing stale AVTransport playback details. */
internal fun mergeDesktopOpenHomeRendererStatus(
    current: DesktopUpnpRendererStatus,
    playback: DesktopUpnpRendererStatus,
): DesktopUpnpRendererStatus = current.copy(
    transportState = playback.transportState,
    transportStatus = playback.transportStatus,
    speed = null,
    trackUri = playback.trackUri,
    trackMetadata = playback.trackMetadata,
    positionMillis = null,
    durationMillis = null,
    connectionState = playback.connectionState,
    connectionError = playback.connectionError,
    supplementalErrors = playback.supplementalErrors,
    isPlaybackSnapshot = false,
)

/** A dual-protocol device stays on AVTransport while an app-owned AV session is active. */
internal fun desktopUpnpShouldUseOpenHomePlaylist(
    device: DesktopUpnpRendererDevice,
    activeAvSessionIdentity: String?,
): Boolean = device.hasOpenHomePlaylist && activeAvSessionIdentity != device.identity
