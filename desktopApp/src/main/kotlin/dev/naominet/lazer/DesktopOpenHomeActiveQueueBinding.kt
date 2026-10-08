package dev.naominet.lazer

private const val OPENHOME_ACTIVE_QUEUE_UINT32_MAX = 4_294_967_295L

/**
 * Binds opaque OpenHome Playlist IDs to local queue indices for one confirmed handoff. The
 * binding is deliberately immutable: callers discard it when [observe] says it was invalidated.
 */
internal class DesktopOpenHomeActiveQueueBinding private constructor(
    val deviceIdentity: String,
    val generation: Long,
    val token: Long,
    val ids: List<Long>,
    private val idToLocalIndex: Map<Long, Int>,
    private val expectedUriById: Map<Long, String>,
) {
    /**
     * Resolves the renderer's current ID only while the same stable queue and session are active.
     * A failed confirmation or incomplete snapshot never produces a local index.
     */
    fun observe(
        deviceIdentity: String,
        generation: Long,
        snapshot: DesktopOpenHomeQueueSnapshot,
        snapshotStillCurrent: Boolean,
    ): DesktopOpenHomeActiveQueueObservation {
        if (!snapshotStillCurrent) {
            return DesktopOpenHomeActiveQueueObservation.Unavailable(
                DesktopOpenHomeActiveQueueUnavailableReason.UNSTABLE_SNAPSHOT,
            )
        }
        if (!snapshot.hasCompleteQueueData()) {
            return DesktopOpenHomeActiveQueueObservation.Unavailable(
                DesktopOpenHomeActiveQueueUnavailableReason.INCOMPLETE_SNAPSHOT,
            )
        }

        if (deviceIdentity != this.deviceIdentity) {
            return snapshot.invalidated(DesktopOpenHomeActiveQueueInvalidationReason.DEVICE_CHANGED)
        }
        if (generation != this.generation) {
            return snapshot.invalidated(DesktopOpenHomeActiveQueueInvalidationReason.SESSION_CHANGED)
        }
        if (snapshot.token != token || snapshot.ids != ids) {
            return snapshot.invalidated(DesktopOpenHomeActiveQueueInvalidationReason.QUEUE_CHANGED)
        }
        if (ids.any { id -> snapshot.tracksById[id]?.uri != expectedUriById[id] }) {
            return snapshot.invalidated(DesktopOpenHomeActiveQueueInvalidationReason.QUEUE_CONTENT_CHANGED)
        }

        if (!snapshot.hasKnownCurrentTrack()) {
            return DesktopOpenHomeActiveQueueObservation.Unavailable(
                if (snapshot.currentId == 0L) {
                    DesktopOpenHomeActiveQueueUnavailableReason.NO_CURRENT_TRACK
                } else {
                    DesktopOpenHomeActiveQueueUnavailableReason.UNKNOWN_CURRENT_TRACK
                },
            )
        }

        val track = checkNotNull(snapshot.currentTrack)
        val localIndex = idToLocalIndex[snapshot.currentId]
            ?: return snapshot.invalidated(DesktopOpenHomeActiveQueueInvalidationReason.UNKNOWN_CURRENT_ID)
        return DesktopOpenHomeActiveQueueObservation.Mapped(
            currentId = snapshot.currentId,
            localIndex = localIndex,
            currentTrack = track,
        )
    }

    companion object {
        /** Creates a binding only from a complete, already-confirmed snapshot of the handoff. */
        fun create(
            deviceIdentity: String,
            generation: Long,
            snapshot: DesktopOpenHomeQueueSnapshot,
            idToLocalIndex: Map<Long, Int>,
            expectedUriById: Map<Long, String>,
            snapshotStillCurrent: Boolean,
        ): DesktopOpenHomeActiveQueueBinding? {
            if (deviceIdentity.isBlank() || !snapshotStillCurrent || !snapshot.isCompleteForBinding()) return null
            if (idToLocalIndex.isEmpty() || idToLocalIndex.keys.toList() != snapshot.ids) return null
            if (expectedUriById.keys.toList() != snapshot.ids || expectedUriById.values.any(String::isBlank)) return null
            if (snapshot.ids.any { id -> snapshot.tracksById[id]?.uri != expectedUriById[id] }) return null
            if (idToLocalIndex.values.any { it < 0 } || idToLocalIndex.values.distinct().size != idToLocalIndex.size) {
                return null
            }
            if (snapshot.currentId !in idToLocalIndex) return null
            return DesktopOpenHomeActiveQueueBinding(
                deviceIdentity = deviceIdentity,
                generation = generation,
                token = snapshot.token,
                ids = snapshot.ids.toList(),
                idToLocalIndex = idToLocalIndex.toMap(),
                expectedUriById = expectedUriById.toMap(),
            )
        }
    }
}

internal sealed interface DesktopOpenHomeActiveQueueObservation {
    data class Mapped(
        val currentId: Long,
        val localIndex: Int,
        val currentTrack: DesktopOpenHomePlaylistTrack,
    ) : DesktopOpenHomeActiveQueueObservation

    /** The snapshot is valid remote state, but no local handoff mapping may be used anymore. */
    data class Invalidated(
        val reason: DesktopOpenHomeActiveQueueInvalidationReason,
        val currentId: Long,
        val currentTrack: DesktopOpenHomePlaylistTrack?,
    ) : DesktopOpenHomeActiveQueueObservation

    /** No safe local mapping decision can be made from this observation. */
    data class Unavailable(
        val reason: DesktopOpenHomeActiveQueueUnavailableReason,
    ) : DesktopOpenHomeActiveQueueObservation
}

internal enum class DesktopOpenHomeActiveQueueInvalidationReason {
    DEVICE_CHANGED,
    SESSION_CHANGED,
    QUEUE_CHANGED,
    QUEUE_CONTENT_CHANGED,
    UNKNOWN_CURRENT_ID,
}

internal enum class DesktopOpenHomeActiveQueueUnavailableReason {
    UNSTABLE_SNAPSHOT,
    INCOMPLETE_SNAPSHOT,
    NO_CURRENT_TRACK,
    UNKNOWN_CURRENT_TRACK,
}

private fun DesktopOpenHomeQueueSnapshot.invalidated(
    reason: DesktopOpenHomeActiveQueueInvalidationReason,
) = DesktopOpenHomeActiveQueueObservation.Invalidated(
    reason = reason,
    currentId = currentId,
    currentTrack = currentTrack.takeIf { currentTrackKnown && it?.id == currentId },
)

private fun DesktopOpenHomeQueueSnapshot.hasCompleteQueueData(): Boolean {
    if (tracksMax !in 1L..OPENHOME_ACTIVE_QUEUE_UINT32_MAX || ids.size.toLong() > tracksMax) return false
    if (token !in 0L..OPENHOME_ACTIVE_QUEUE_UINT32_MAX || ids.any { it !in 1L..OPENHOME_ACTIVE_QUEUE_UINT32_MAX }) return false
    if (ids.distinct().size != ids.size || tracksById.keys != ids.toSet()) return false
    if (ids.any { id -> tracksById[id]?.id != id }) return false
    if (transportState.isBlank() || currentId !in 0L..OPENHOME_ACTIVE_QUEUE_UINT32_MAX) return false
    if (currentId == 0L) return currentTrackKnown && currentTrack == null
    return currentTrack == null || currentTrack.id == currentId
}

private fun DesktopOpenHomeQueueSnapshot.hasKnownCurrentTrack(): Boolean =
    currentId != 0L && currentTrackKnown && currentTrack?.id == currentId

private fun DesktopOpenHomeQueueSnapshot.isCompleteForBinding(): Boolean =
    hasCompleteQueueData() && hasKnownCurrentTrack()
