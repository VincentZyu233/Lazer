package dev.naominet.lazer

/** Selects a rediscovered OpenHome renderer by stable UDN identity, never its display name or IP. */
internal fun selectDesktopOpenHomeRecoveryDevice(
    expectedIdentity: String,
    discoveredDevices: List<DesktopUpnpRendererDevice>,
): DesktopUpnpRendererDevice? = discoveredDevices.firstOrNull { device ->
    device.identity == expectedIdentity && device.hasOpenHomePlaylist
}

internal fun desktopOpenHomePlaybackIntentFor(
    command: DesktopUpnpTransportCommand,
): DesktopOpenHomePlaybackIntent = when (command) {
    DesktopUpnpTransportCommand.PLAY -> DesktopOpenHomePlaybackIntent.PLAY
    DesktopUpnpTransportCommand.PAUSE -> DesktopOpenHomePlaybackIntent.PAUSE
    DesktopUpnpTransportCommand.STOP -> DesktopOpenHomePlaybackIntent.STOP
}

internal fun desktopOpenHomeCommandMatches(
    command: DesktopUpnpTransportCommand,
    status: DesktopUpnpRendererStatus,
): Boolean = when (command) {
    DesktopUpnpTransportCommand.PLAY -> status.transportState in setOf("PLAYING", "TRANSITIONING")
    DesktopUpnpTransportCommand.PAUSE -> status.transportState == "PAUSED_PLAYBACK"
    DesktopUpnpTransportCommand.STOP -> status.transportState == "STOPPED"
}

internal data class DesktopOpenHomeGenaDetached<R : Any, D : Any, A : Any, L : Any>(
    val receiver: R?,
    val device: D,
    val rendererAddress: A?,
    val lease: L?,
)

/** Serializes receiver installation, SID installation, and detach so a late SUBSCRIBE cannot leak. */
internal class DesktopOpenHomeGenaBindingState<R : Any, D : Any, A : Any, L : Any> {
    private var activeReceiver: R? = null
    private var activeDevice: D? = null
    private var activeRendererAddress: A? = null
    private var activeLease: L? = null

    @Synchronized
    fun receiver(): R? = activeReceiver

    @Synchronized
    fun lease(): L? = activeLease

    @Synchronized
    fun attach(receiver: R, device: D, rendererAddress: A): Boolean {
        if (activeReceiver != null) return false
        activeReceiver = receiver
        activeDevice = device
        activeRendererAddress = rendererAddress
        activeLease = null
        return true
    }

    @Synchronized
    fun installLease(receiver: R, lease: L): Boolean {
        if (activeReceiver !== receiver) return false
        activeLease = lease
        return true
    }

    @Synchronized
    fun updateLease(receiver: R, lease: L?) {
        if (activeReceiver === receiver) activeLease = lease
    }

    @Synchronized
    fun clearLease(receiver: R): Boolean {
        if (activeReceiver !== receiver) return false
        activeLease = null
        return true
    }

    @Synchronized
    fun detach(fallbackDevice: D): DesktopOpenHomeGenaDetached<R, D, A, L> {
        val detached = DesktopOpenHomeGenaDetached(
            receiver = activeReceiver,
            device = activeDevice ?: fallbackDevice,
            rendererAddress = activeRendererAddress,
            lease = activeLease,
        )
        activeReceiver = null
        activeDevice = null
        activeRendererAddress = null
        activeLease = null
        return detached
    }
}

/** Bounded exponential retry schedule for OpenHome renderer rediscovery. */
internal class DesktopOpenHomeReconnectBackoff {
    private var failureCount = 0
    private var retryAfterNanos = 0L

    fun isAttemptDue(nowNanos: Long): Boolean = nowNanos - retryAfterNanos >= 0L

    fun recordFailure(nowNanos: Long) {
        failureCount = (failureCount + 1).coerceAtMost(5)
        retryAfterNanos = nowNanos + desktopUpnpAdvanceRetryDelayMillis(failureCount) * 1_000_000L
    }

    fun reset() {
        failureCount = 0
        retryAfterNanos = 0L
    }
}
