package dev.naominet.lazer

import java.io.IOException
import kotlinx.coroutines.CancellationException

/** One local queue entry prepared for insertion into an OpenHome Playlist. */
internal data class DesktopOpenHomeQueueCandidate(
    val localIndex: Int,
    val uri: String,
    val metadata: String,
)

/** Stable view of an OpenHome queue, with opaque renderer IDs kept separate from local indices. */
internal data class DesktopOpenHomeQueueSnapshot(
    val tracksMax: Long,
    val token: Long,
    val ids: List<Long>,
    val currentId: Long,
    val transportState: String,
    val tracksById: Map<Long, DesktopOpenHomePlaylistTrack>,
    /** Null when Id is zero, otherwise populated only when ReadList could resolve that ID. */
    val currentTrack: DesktopOpenHomePlaylistTrack?,
    val currentTrackKnown: Boolean,
)

internal enum class DesktopOpenHomeQueueStage {
    VALIDATE_CANDIDATES,
    VERIFY_PRECONDITION,
    DELETE_ALL,
    VERIFY_EMPTY,
    INSERT,
    VERIFY_FINAL_QUEUE,
}

/**
 * A cancelled replacement carries an explicit retain-leases signal. Cancellation can happen after
 * an HTTP request reached the renderer, so callers must not release these URIs in a `finally`
 * block; reconcile the queue later, or retain the leases until the renderer session is closed.
 */
internal class DesktopOpenHomeQueueMutationCancelled(
    val stage: DesktopOpenHomeQueueStage,
    candidateUris: List<String>,
    cause: CancellationException,
) : CancellationException("OpenHome queue replacement was cancelled during $stage; candidate media must be retained.") {
    val candidateUris: List<String> = candidateUris.toList()
    val candidateReferenceStatus = DesktopOpenHomeCandidateReferenceStatus.UNKNOWN
    val candidateMediaMayBeReferenced = this.candidateUris.isNotEmpty()
    val safeToReleaseCandidateMedia = this.candidateUris.isEmpty()

    init {
        initCause(cause)
    }
}

/**
 * Cleanup is allowed only for VERIFIED_NOT_REFERENCED. UNKNOWN deliberately fails closed too.
 */
internal enum class DesktopOpenHomeCandidateReferenceStatus {
    REFERENCED,
    VERIFIED_NOT_REFERENCED,
    UNKNOWN,
}

internal sealed interface DesktopOpenHomeQueueReplacement {
    data class Replaced(
        val idToLocalIndex: Map<Long, Int>,
        val token: Long,
        val currentId: Long,
        val transportState: String,
    ) : DesktopOpenHomeQueueReplacement {
        val candidateMediaMayBeReferenced: Boolean get() = idToLocalIndex.isNotEmpty()
        val safeToReleaseCandidateMedia: Boolean get() = idToLocalIndex.isEmpty()
    }

    data class Failed(
        val stage: DesktopOpenHomeQueueStage,
        val message: String,
        val candidateReferenceStatus: DesktopOpenHomeCandidateReferenceStatus,
        val cause: Throwable? = null,
    ) : DesktopOpenHomeQueueReplacement {
        /** True for both known references and inconclusive evidence. */
        val candidateMediaMayBeReferenced: Boolean
            get() = candidateReferenceStatus != DesktopOpenHomeCandidateReferenceStatus.VERIFIED_NOT_REFERENCED

        val safeToReleaseCandidateMedia: Boolean
            get() = candidateReferenceStatus == DesktopOpenHomeCandidateReferenceStatus.VERIFIED_NOT_REFERENCED
    }
}

/** Narrow port makes the transaction independently testable while production uses the typed client. */
internal interface DesktopOpenHomePlaylistQueueService {
    suspend fun tracksMax(device: DesktopUpnpRendererDevice): Long
    suspend fun idArray(device: DesktopUpnpRendererDevice): DesktopOpenHomePlaylistIdArray
    suspend fun idArrayChanged(device: DesktopUpnpRendererDevice, token: Long): Boolean
    suspend fun id(device: DesktopUpnpRendererDevice): Long
    suspend fun transportState(device: DesktopUpnpRendererDevice): String
    suspend fun readList(device: DesktopUpnpRendererDevice, ids: List<Long>): List<DesktopOpenHomePlaylistTrack>
    suspend fun deleteAll(device: DesktopUpnpRendererDevice)
    suspend fun insert(device: DesktopUpnpRendererDevice, afterId: Long, uri: String, metadata: String): Long
}

/**
 * Coordinates destructive queue replacement. Mutating SOAP calls are never retried: after a lost
 * response the remote outcome is reconciled by reading the queue, and candidate media is retained
 * unless a stable queue and its current track prove that none of the candidate URIs is referenced.
 */
internal class DesktopOpenHomePlaylistQueueCoordinator(
    private val playlist: DesktopOpenHomePlaylistQueueService,
    private val readListPageSize: Int = DEFAULT_READ_LIST_PAGE_SIZE,
) {
    constructor(client: DesktopOpenHomePlaylistClient) : this(desktopOpenHomeQueueService(client))

    init {
        require(readListPageSize in 1..MAX_READ_LIST_PAGE_SIZE) {
            "OpenHome ReadList page size must be between 1 and $MAX_READ_LIST_PAGE_SIZE."
        }
    }

    /** Reads a token-stable queue snapshot. ReadList pages are deliberately small and bounded. */
    suspend fun readSnapshot(device: DesktopUpnpRendererDevice): DesktopOpenHomeQueueSnapshot {
        val tracksMax = checkedTracksMax(playlist.tracksMax(device))
        val idArray = playlist.idArray(device)
        if (idArray.ids.size.toLong() > tracksMax) {
            throw IOException("OpenHome IdArray contains more tracks than TracksMax allows.")
        }
        checkedUint(idArray.token, "OpenHome IdArray token")
        if (idArray.ids.any { it !in 1L..UINT32_MAX } || idArray.ids.distinct().size != idArray.ids.size) {
            throw IOException("OpenHome IdArray contains invalid or duplicate track IDs.")
        }
        val tracks = readQueueEntries(device, idArray.ids)
        val currentId = checkedUint(playlist.id(device), "OpenHome current ID")
        val state = playlist.transportState(device).takeIf { it.isNotBlank() }
            ?: throw IOException("OpenHome TransportState is empty.")

        val currentTrack: DesktopOpenHomePlaylistTrack?
        val currentTrackKnown: Boolean
        if (currentId == 0L) {
            currentTrack = null
            currentTrackKnown = true
        } else if (currentId in tracks) {
            currentTrack = tracks.getValue(currentId)
            currentTrackKnown = true
        } else {
            // The current ID can outlive queue membership. It is safe to clean candidate media only
            // if this independent ReadList query identifies the track (or the ID is zero).
            val resolved = try {
                playlist.readList(device, listOf(currentId)).singleOrNull()?.takeIf { it.id == currentId }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            currentTrack = resolved
            currentTrackKnown = resolved != null
        }

        if (playlist.idArrayChanged(device, idArray.token)) {
            throw IOException("OpenHome queue changed while its snapshot was being read.")
        }
        return DesktopOpenHomeQueueSnapshot(
            tracksMax = tracksMax,
            token = idArray.token,
            ids = idArray.ids.toList(),
            currentId = currentId,
            transportState = state,
            tracksById = tracks,
            currentTrack = currentTrack,
            currentTrackKnown = currentTrackKnown,
        )
    }

    /**
     * Confirms both playlist membership and the current (possibly detached) ID still match a
     * previously read snapshot. IdArrayChanged does not cover transport ID changes, so check both.
     */
    suspend fun snapshotStillCurrent(
        device: DesktopUpnpRendererDevice,
        snapshot: DesktopOpenHomeQueueSnapshot,
    ): Boolean {
        if (playlist.idArrayChanged(device, snapshot.token)) return false
        if (playlist.id(device) != snapshot.currentId) return false
        return !playlist.idArrayChanged(device, snapshot.token)
    }

    /**
     * Reads only the current ID/state and current item while the queue token stays unchanged.
     * Reusing the last complete queue avoids paging and parsing the entire playlist every poll.
     * A null result means the queue changed during the light read; callers should take a full
     * snapshot and reconcile that result instead.
     */
    suspend fun readCurrentState(
        device: DesktopUpnpRendererDevice,
        previous: DesktopOpenHomeQueueSnapshot,
    ): DesktopOpenHomeQueueSnapshot? {
        if (playlist.idArrayChanged(device, previous.token)) return null

        val currentId = checkedUint(playlist.id(device), "OpenHome current ID")
        val state = playlist.transportState(device).takeIf { it.isNotBlank() }
            ?: throw IOException("OpenHome TransportState is empty.")
        val currentTrack = if (currentId == 0L) {
            null
        } else {
            try {
                playlist.readList(device, listOf(currentId)).singleOrNull()?.takeIf {
                    it.id == currentId && it.uri.isNotBlank()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
        }
        if (playlist.idArrayChanged(device, previous.token)) return null
        if (playlist.id(device) != currentId) return null
        if (playlist.idArrayChanged(device, previous.token)) return null

        val refreshedTracks = if (currentTrack != null && currentId in previous.tracksById) {
            previous.tracksById + (currentId to currentTrack)
        } else {
            previous.tracksById
        }
        return previous.copy(
            currentId = currentId,
            transportState = state,
            tracksById = refreshedTracks,
            currentTrack = currentTrack,
            currentTrackKnown = currentId == 0L || currentTrack != null,
        )
    }

    /**
     * Replaces the playlist only if [expected] still names the queue observed by readSnapshot.
     * Insert is serialized as AfterId=0 followed by each returned opaque ID. No Insert is retried.
     */
    suspend fun replace(
        device: DesktopUpnpRendererDevice,
        expected: DesktopOpenHomeQueueSnapshot,
        candidates: List<DesktopOpenHomeQueueCandidate>,
    ): DesktopOpenHomeQueueReplacement {
        try {
            validateCandidates(candidates)
        } catch (error: Exception) {
            return failed(device, candidates, expected, DesktopOpenHomeQueueStage.VALIDATE_CANDIDATES, error)
        }

        try {
            val capacity = checkedTracksMax(playlist.tracksMax(device))
            if (candidates.size.toLong() > capacity) {
                throw IOException("OpenHome queue capacity is $capacity tracks, but ${candidates.size} were requested.")
            }
            val now = playlist.idArray(device)
            val changed = playlist.idArrayChanged(device, expected.token)
            val snapshotChanged = !snapshotStillCurrent(device, expected)
            if (changed || now.token != expected.token || now.ids != expected.ids || snapshotChanged) {
                throw IOException("OpenHome queue changed after the replacement snapshot was prepared.")
            }
        } catch (error: CancellationException) {
            throw mutationCancelled(DesktopOpenHomeQueueStage.VERIFY_PRECONDITION, candidates, error)
        } catch (error: Exception) {
            return failed(
                device,
                candidates,
                expected,
                DesktopOpenHomeQueueStage.VERIFY_PRECONDITION,
                error,
            )
        }

        try {
            playlist.deleteAll(device)
        } catch (error: CancellationException) {
            throw mutationCancelled(DesktopOpenHomeQueueStage.DELETE_ALL, candidates, error)
        } catch (error: Exception) {
            return failed(device, candidates, expected, DesktopOpenHomeQueueStage.DELETE_ALL, error)
        }

        try {
            readSnapshot(device).also { snapshot ->
                if (snapshot.ids.isNotEmpty() || snapshot.tracksById.isNotEmpty()) {
                    throw IOException("OpenHome DeleteAll returned but the remote queue is not empty.")
                }
            }
        } catch (error: CancellationException) {
            throw mutationCancelled(DesktopOpenHomeQueueStage.VERIFY_EMPTY, candidates, error)
        } catch (error: Exception) {
            return failed(device, candidates, expected, DesktopOpenHomeQueueStage.VERIFY_EMPTY, error)
        }

        val insertedIds = ArrayList<Long>(candidates.size)
        var afterId = 0L
        try {
            for (candidate in candidates) {
                val newId = checkedNonZeroUint(
                    playlist.insert(device, afterId, candidate.uri, candidate.metadata),
                    "OpenHome Insert NewId",
                )
                if (newId in insertedIds) throw IOException("OpenHome Insert returned a duplicate track ID.")
                insertedIds += newId
                afterId = newId
            }
        } catch (error: CancellationException) {
            throw mutationCancelled(DesktopOpenHomeQueueStage.INSERT, candidates, error)
        } catch (error: Exception) {
            return failed(device, candidates, expected, DesktopOpenHomeQueueStage.INSERT, error)
        }

        return try {
            val finalSnapshot = readSnapshot(device)
            if (finalSnapshot.ids != insertedIds || finalSnapshot.ids.size != candidates.size) {
                throw IOException("OpenHome queue IDs differ from the acknowledged Insert sequence.")
            }
            val idToLocalIndex = linkedMapOf<Long, Int>()
            candidates.forEachIndexed { index, candidate ->
                val remoteId = finalSnapshot.ids[index]
                val remoteTrack = finalSnapshot.tracksById[remoteId]
                    ?: throw IOException("OpenHome ReadList omitted inserted ID $remoteId.")
                if (remoteTrack.uri != candidate.uri || remoteTrack.metadata != candidate.metadata) {
                    throw IOException("OpenHome ReadList does not match the requested URI and metadata order.")
                }
                idToLocalIndex[remoteId] = candidate.localIndex
            }
            DesktopOpenHomeQueueReplacement.Replaced(
                idToLocalIndex = idToLocalIndex,
                token = finalSnapshot.token,
                currentId = finalSnapshot.currentId,
                transportState = finalSnapshot.transportState,
            )
        } catch (error: CancellationException) {
            throw mutationCancelled(DesktopOpenHomeQueueStage.VERIFY_FINAL_QUEUE, candidates, error)
        } catch (error: Exception) {
            failed(device, candidates, expected, DesktopOpenHomeQueueStage.VERIFY_FINAL_QUEUE, error)
        }
    }

    private suspend fun failed(
        device: DesktopUpnpRendererDevice,
        candidates: List<DesktopOpenHomeQueueCandidate>,
        expected: DesktopOpenHomeQueueSnapshot,
        stage: DesktopOpenHomeQueueStage,
        error: Exception,
    ): DesktopOpenHomeQueueReplacement.Failed {
        val referenceStatus = try {
            assessCandidateReferences(device, candidates, expected)
        } catch (error: CancellationException) {
            throw mutationCancelled(stage, candidates, error)
        }
        return DesktopOpenHomeQueueReplacement.Failed(
            stage = stage,
            message = error.message ?: "OpenHome queue replacement failed.",
            candidateReferenceStatus = referenceStatus,
            cause = error,
        )
    }

    private suspend fun assessCandidateReferences(
        device: DesktopUpnpRendererDevice,
        candidates: List<DesktopOpenHomeQueueCandidate>,
        expected: DesktopOpenHomeQueueSnapshot,
    ): DesktopOpenHomeCandidateReferenceStatus {
        val candidateUris = candidates.mapTo(hashSetOf()) { it.uri }
        if (candidateUris.isEmpty()) return DesktopOpenHomeCandidateReferenceStatus.VERIFIED_NOT_REFERENCED
        return try {
            val current = readSnapshot(device)
            if (!snapshotStillCurrent(device, current)) return DesktopOpenHomeCandidateReferenceStatus.UNKNOWN
            if (current.ids.any { id -> current.tracksById[id]?.uri?.let(candidateUris::contains) == true }) {
                return DesktopOpenHomeCandidateReferenceStatus.REFERENCED
            }
            val activeTrack = if (current.currentTrackKnown) {
                current.currentTrack
            } else if (current.currentId == expected.currentId && expected.currentTrackKnown) {
                expected.currentTrack
            } else {
                null
            }
            if (activeTrack?.uri?.let(candidateUris::contains) == true) {
                DesktopOpenHomeCandidateReferenceStatus.REFERENCED
            } else if (current.currentTrackKnown ||
                (current.currentId == expected.currentId && expected.currentTrackKnown)
            ) {
                DesktopOpenHomeCandidateReferenceStatus.VERIFIED_NOT_REFERENCED
            } else {
                DesktopOpenHomeCandidateReferenceStatus.UNKNOWN
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            DesktopOpenHomeCandidateReferenceStatus.UNKNOWN
        }
    }

    private suspend fun readQueueEntries(
        device: DesktopUpnpRendererDevice,
        ids: List<Long>,
    ): Map<Long, DesktopOpenHomePlaylistTrack> {
        val tracks = linkedMapOf<Long, DesktopOpenHomePlaylistTrack>()
        ids.chunked(readListPageSize).forEach { page ->
            val entries = readPageWithinResponseLimit(device, page)
            entries.forEach { track ->
                if (tracks.putIfAbsent(track.id, track) != null) {
                    throw IOException("OpenHome ReadList returned a duplicate queue ID.")
                }
            }
        }
        return tracks
    }

    /** Retry only an explicitly oversized, side-effect-free ReadList page by splitting its IDs. */
    private suspend fun readPageWithinResponseLimit(
        device: DesktopUpnpRendererDevice,
        ids: List<Long>,
    ): List<DesktopOpenHomePlaylistTrack> {
        try {
            val entries = playlist.readList(device, ids)
            if (entries.size != ids.size || entries.map { it.id }.toSet() != ids.toSet()) {
                throw IOException("OpenHome ReadList omitted or changed one or more requested IDs.")
            }
            return entries
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            val isBoundedResponseFailure = error.message.orEmpty().let { message ->
                message.contains("response exceeds the size limit", ignoreCase = true) ||
                    message.contains("ReadList response is too large", ignoreCase = true)
            }
            if (!isBoundedResponseFailure || ids.size <= 1) throw error
            val middle = ids.size / 2
            return readPageWithinResponseLimit(device, ids.subList(0, middle)) +
                readPageWithinResponseLimit(device, ids.subList(middle, ids.size))
        }
    }

    private fun validateCandidates(candidates: List<DesktopOpenHomeQueueCandidate>) {
        if (candidates.size > MAX_QUEUE_TRACKS) {
            throw IllegalArgumentException("OpenHome queue replacement exceeds $MAX_QUEUE_TRACKS tracks.")
        }
        if (candidates.map { it.localIndex }.distinct().size != candidates.size) {
            throw IllegalArgumentException("OpenHome candidates contain duplicate local indices.")
        }
        candidates.forEach { candidate ->
            if (candidate.localIndex < 0) throw IllegalArgumentException("A local track index cannot be negative.")
            if (candidate.uri.isBlank()) throw IllegalArgumentException("A candidate URI cannot be blank.")
            if (candidate.uri.length > MAX_CANDIDATE_URI_CHARS) {
                throw IllegalArgumentException("Candidate URI exceeds the OpenHome client limit.")
            }
            if (candidate.metadata.length > MAX_CANDIDATE_METADATA_CHARS) {
                throw IllegalArgumentException("Candidate metadata exceeds the OpenHome client limit.")
            }
        }
    }

    private fun checkedTracksMax(value: Long): Long = checkedUint(value, "OpenHome TracksMax")

    private fun mutationCancelled(
        stage: DesktopOpenHomeQueueStage,
        candidates: List<DesktopOpenHomeQueueCandidate>,
        cause: CancellationException,
    ): DesktopOpenHomeQueueMutationCancelled =
        DesktopOpenHomeQueueMutationCancelled(stage, candidates.map { it.uri }, cause)

    private fun checkedUint(value: Long, label: String): Long {
        if (value !in 0L..UINT32_MAX) throw IOException("$label is outside the unsigned 32-bit range.")
        return value
    }

    private fun checkedNonZeroUint(value: Long, label: String): Long = checkedUint(value, label).also {
        if (it == 0L) throw IOException("$label uses the reserved zero ID.")
    }

    private companion object {
        const val DEFAULT_READ_LIST_PAGE_SIZE = 8
        const val MAX_READ_LIST_PAGE_SIZE = 512
        const val MAX_QUEUE_TRACKS = 10_000
        const val MAX_CANDIDATE_URI_CHARS = 8_192
        const val MAX_CANDIDATE_METADATA_CHARS = 64 * 1024
        const val UINT32_MAX = 4_294_967_295L
    }
}

private fun desktopOpenHomeQueueService(client: DesktopOpenHomePlaylistClient): DesktopOpenHomePlaylistQueueService =
    object : DesktopOpenHomePlaylistQueueService {
        override suspend fun tracksMax(device: DesktopUpnpRendererDevice) = client.tracksMax(device)
        override suspend fun idArray(device: DesktopUpnpRendererDevice) = client.idArray(device)
        override suspend fun idArrayChanged(device: DesktopUpnpRendererDevice, token: Long) =
            client.idArrayChanged(device, token)
        override suspend fun id(device: DesktopUpnpRendererDevice) = client.id(device)
        override suspend fun transportState(device: DesktopUpnpRendererDevice) = client.transportState(device)
        override suspend fun readList(device: DesktopUpnpRendererDevice, ids: List<Long>) =
            client.readList(device, ids)
        override suspend fun deleteAll(device: DesktopUpnpRendererDevice) = client.deleteAll(device)
        override suspend fun insert(device: DesktopUpnpRendererDevice, afterId: Long, uri: String, metadata: String) =
            client.insert(device, afterId, uri, metadata)
    }
