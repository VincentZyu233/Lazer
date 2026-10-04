package dev.naominet.lazer

import java.util.Collections
import java.util.concurrent.TimeUnit

/** Opaque handle for one registered media-resource/URI group that may need lease renewal. */
internal class DesktopOpenHomeMediaRenewalGroup internal constructor(
    val deviceIdentity: String,
    internal val registrationId: Long,
    val uris: Set<String>,
)

internal enum class DesktopOpenHomeMediaReconcileBlock {
    NONE,
    DEVICE_MISMATCH,
    UNSTABLE_SNAPSHOT,
    READ_ERROR_OR_MISSING_SNAPSHOT,
    UNKNOWN_CURRENT_TRACK,
    INVALID_SNAPSHOT,
}

internal data class DesktopOpenHomeMediaReconcileResult(
    val releasedGroups: Int,
    val retainedGroups: Int,
    val blockedBy: DesktopOpenHomeMediaReconcileBlock,
)

/**
 * Owns local media-server resources while an OpenHome renderer might still reference their URLs.
 * It performs no I/O: callers provide a snapshot and confirm that it is still current immediately
 * before reconciliation (for example by rechecking both the Playlist token and current ID).
 *
 * A registration is released only when the supplied, stable snapshot is structurally complete,
 * its detached current track is known, and none of the registration's exact URI strings appear in
 * either the queue entries or the current track.
 */
internal class DesktopOpenHomeMediaResourceRetention(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val renewalIntervalMillis: Long = DEFAULT_RENEWAL_INTERVAL_MILLIS,
) : AutoCloseable {
    private data class Registration(
        val id: Long,
        val deviceIdentity: String,
        val uris: Set<String>,
        val close: () -> Unit,
        val renew: (suspend () -> Unit)?,
        var nextRenewalMillis: Long?,
        var renewalInFlight: Boolean = false,
    )

    private val lock = Any()
    private val registrations = linkedMapOf<Long, Registration>()
    private var nextRegistrationId = 1L
    private var disposed = false

    init {
        require(renewalIntervalMillis > 0L) { "Lease renewal interval must be positive." }
    }

    /**
     * Registers a group of media URLs and its owner cleanup callback. [renew] is optional; when
     * supplied, it first becomes due one renewal interval after registration and remains due until
     * [renew] completes successfully.
     */
    fun register(
        deviceIdentity: String,
        uris: Set<String>,
        close: () -> Unit,
        renew: (suspend () -> Unit)? = null,
    ): Long {
        require(deviceIdentity.isNotBlank()) { "Device identity cannot be blank." }
        require(uris.isNotEmpty()) { "At least one media URI must be registered." }
        require(uris.all { it.isNotBlank() }) { "Media URIs cannot be blank." }
        val ownedUris = immutableCopy(uris)
        val registeredAt = nowMillis()
        synchronized(lock) {
            check(!disposed) { "Media resource retention is already closed." }
            check(nextRegistrationId != Long.MAX_VALUE) { "Media resource registration IDs are exhausted." }
            val id = nextRegistrationId++
            registrations[id] = Registration(
                id = id,
                deviceIdentity = deviceIdentity,
                uris = ownedUris,
                close = close,
                renew = renew,
                nextRenewalMillis = renew?.let { safeAdd(registeredAt, renewalIntervalMillis) },
            )
            return id
        }
    }

    /** Convenience overload for a single AutoCloseable lease owner. */
    fun register(
        deviceIdentity: String,
        uris: Set<String>,
        resource: AutoCloseable,
        renew: (suspend () -> Unit)? = null,
    ): Long = register(deviceIdentity, uris, close = { resource.close() }, renew = renew)

    /** Returns one due renewal group per registered resource group, scoped to its device. */
    fun dueRenewalGroups(atMillis: Long = nowMillis()): List<DesktopOpenHomeMediaRenewalGroup> =
        synchronized(lock) {
            if (disposed) return@synchronized emptyList()
            registrations.values.asSequence()
                .filter { it.renew != null && !it.renewalInFlight && it.nextRenewalMillis?.let { due -> atMillis >= due } == true }
                .map { registration ->
                    DesktopOpenHomeMediaRenewalGroup(
                        deviceIdentity = registration.deviceIdentity,
                        registrationId = registration.id,
                        uris = registration.uris,
                    )
                }
                .toList()
        }

    /**
     * Renews one previously queried group. On callback failure it remains due and the exception is
     * propagated to the caller. A stale group (released or closed since it was queried) is ignored.
     */
    suspend fun renew(group: DesktopOpenHomeMediaRenewalGroup, atMillis: Long = nowMillis()) {
        val registration = synchronized(lock) {
            if (disposed) return
            registrations[group.registrationId]?.takeIf {
                it.deviceIdentity == group.deviceIdentity && it.uris == group.uris && it.renew != null &&
                    !it.renewalInFlight && it.nextRenewalMillis?.let { due -> atMillis >= due } == true
            }?.also { it.renewalInFlight = true }
        } ?: return

        var renewed = false
        try {
            registration.renew?.invoke()
            renewed = true
        } finally {
            synchronized(lock) {
                val current = registrations[registration.id]
                if (current === registration) {
                    current.renewalInFlight = false
                    if (renewed) {
                        current.nextRenewalMillis = safeAdd(nowMillis(), renewalIntervalMillis)
                    }
                }
            }
        }
    }

    /**
     * Reconciles only registrations for [deviceIdentity]. [snapshotDeviceIdentity] is the identity
     * actually associated with the read; a mismatch, missing/error snapshot, unstable result, or
     * unknown current track retains every registration for the requested device.
     */
    fun reconcile(
        deviceIdentity: String,
        snapshotDeviceIdentity: String,
        snapshot: DesktopOpenHomeQueueSnapshot?,
        snapshotStillCurrent: Boolean,
        readError: Throwable? = null,
    ): DesktopOpenHomeMediaReconcileResult {
        val blockedBy = when {
            deviceIdentity != snapshotDeviceIdentity -> DesktopOpenHomeMediaReconcileBlock.DEVICE_MISMATCH
            !snapshotStillCurrent -> DesktopOpenHomeMediaReconcileBlock.UNSTABLE_SNAPSHOT
            readError != null || snapshot == null -> DesktopOpenHomeMediaReconcileBlock.READ_ERROR_OR_MISSING_SNAPSHOT
            !snapshot.currentTrackKnown -> DesktopOpenHomeMediaReconcileBlock.UNKNOWN_CURRENT_TRACK
            else -> validateAndCollectReferences(snapshot)?.let { return releaseUnreferenced(deviceIdentity, it) }
                ?: DesktopOpenHomeMediaReconcileBlock.INVALID_SNAPSHOT
        }
        val retained = synchronized(lock) {
            registrations.values.count { it.deviceIdentity == deviceIdentity }
        }
        return DesktopOpenHomeMediaReconcileResult(
            releasedGroups = 0,
            retainedGroups = retained,
            blockedBy = blockedBy,
        )
    }

    /** Closes all still-owned resources exactly once and rejects future registrations. */
    override fun close() {
        val toClose = synchronized(lock) {
            if (disposed) return
            disposed = true
            registrations.values.toList().also { registrations.clear() }
        }
        closeAll(toClose.map { it.close })
    }

    private fun releaseUnreferenced(
        deviceIdentity: String,
        referencedUris: Set<String>,
    ): DesktopOpenHomeMediaReconcileResult {
        val (released, retained) = synchronized(lock) {
            if (disposed) return DesktopOpenHomeMediaReconcileResult(
                releasedGroups = 0,
                retainedGroups = 0,
                blockedBy = DesktopOpenHomeMediaReconcileBlock.NONE,
            )
            val matching = registrations.values.filter { it.deviceIdentity == deviceIdentity }
            val released = matching.filter { registration -> registration.uris.none(referencedUris::contains) }
            released.forEach { registrations.remove(it.id) }
            released to (matching.size - released.size)
        }
        closeAll(released.map { it.close })
        return DesktopOpenHomeMediaReconcileResult(
            releasedGroups = released.size,
            retainedGroups = retained,
            blockedBy = DesktopOpenHomeMediaReconcileBlock.NONE,
        )
    }

    /** Null means the snapshot cannot prove complete queue/current-track URI coverage. */
    private fun validateAndCollectReferences(snapshot: DesktopOpenHomeQueueSnapshot): Set<String>? {
        if (snapshot.tracksMax !in 0L..UINT32_MAX || snapshot.token !in 0L..UINT32_MAX) return null
        if (snapshot.ids.size.toLong() > snapshot.tracksMax) return null
        if (snapshot.ids.size != snapshot.ids.distinct().size) return null
        if (snapshot.ids.any { it !in 1L..UINT32_MAX }) return null
        if (snapshot.currentId !in 0L..UINT32_MAX) return null
        val queueIds = snapshot.ids.toSet()
        if (snapshot.tracksById.keys != queueIds) return null
        if (snapshot.tracksById.values.any { it.id !in queueIds || it.uri.isBlank() }) return null

        val current = snapshot.currentTrack
        if (snapshot.currentId == 0L) {
            if (current != null) return null
        } else {
            if (current == null || current.id != snapshot.currentId || current.uri.isBlank()) return null
            val queueCurrent = snapshot.tracksById[snapshot.currentId]
            if (queueCurrent != null && queueCurrent.uri != current.uri) return null
        }

        return buildSet {
            snapshot.tracksById.values.forEach { add(it.uri) }
            current?.let { add(it.uri) }
        }
    }

    private fun closeAll(callbacks: List<() -> Unit>) {
        var failure: Throwable? = null
        callbacks.forEach { callback ->
            try {
                callback()
            } catch (error: Throwable) {
                val previousFailure = failure
                if (previousFailure == null) failure = error else previousFailure.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right > 0L && left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun immutableCopy(values: Set<String>): Set<String> =
        Collections.unmodifiableSet(LinkedHashSet(values))

    private companion object {
        val DEFAULT_RENEWAL_INTERVAL_MILLIS: Long = TimeUnit.HOURS.toMillis(6)
        const val UINT32_MAX = 4_294_967_295L
    }
}
