package dev.naominet.lazer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopOpenHomeActiveQueueBindingTest {
    @Test
    fun `stable queue resolves opaque current ID to its bound local index`() {
        val snapshot = snapshot(
            token = 12L,
            ids = listOf(4_000_000_001L, 71L),
            currentId = 71L,
        )
        val binding = binding(snapshot)

        val result = binding.observe(DEVICE, GENERATION, snapshot, snapshotStillCurrent = true)

        assertEquals(
            DesktopOpenHomeActiveQueueObservation.Mapped(
                currentId = 71L,
                localIndex = 4,
                currentTrack = checkNotNull(snapshot.currentTrack),
            ),
            result,
        )
    }

    @Test
    fun `token change invalidates binding and preserves remote track details`() {
        val initial = snapshot(token = 12L, ids = listOf(71L, 72L), currentId = 71L)
        val changed = snapshot(token = 13L, ids = initial.ids, currentId = 72L)
        val binding = binding(initial)

        val result = binding.observe(DEVICE, GENERATION, changed, snapshotStillCurrent = true)

        assertEquals(
            DesktopOpenHomeActiveQueueObservation.Invalidated(
                DesktopOpenHomeActiveQueueInvalidationReason.QUEUE_CHANGED,
                currentId = 72L,
                currentTrack = changed.currentTrack,
            ),
            result,
        )
    }

    @Test
    fun `ID sequence change invalidates even when token happens to match`() {
        val initial = snapshot(token = 12L, ids = listOf(71L, 72L), currentId = 71L)
        val changed = snapshot(token = 12L, ids = listOf(71L, 99L), currentId = 99L)

        val result = binding(initial).observe(DEVICE, GENERATION, changed, snapshotStillCurrent = true)

        assertEquals(
            DesktopOpenHomeActiveQueueInvalidationReason.QUEUE_CHANGED,
            (result as DesktopOpenHomeActiveQueueObservation.Invalidated).reason,
        )
        assertEquals(99L, result.currentId)
        assertEquals("http://renderer/99.flac", result.currentTrack?.uri)
    }

    @Test
    fun `URI change invalidates the binding even when token and IDs are unchanged`() {
        val initial = snapshot(token = 12L, ids = listOf(71L, 72L), currentId = 71L)
        val changedTracks = initial.tracksById.toMutableMap().apply {
            this[72L] = checkNotNull(this[72L]).copy(uri = "http://renderer/replaced.flac")
        }
        val changed = initial.copy(tracksById = changedTracks)

        val result = binding(initial).observe(DEVICE, GENERATION, changed, snapshotStillCurrent = true)

        assertEquals(
            DesktopOpenHomeActiveQueueInvalidationReason.QUEUE_CONTENT_CHANGED,
            (result as DesktopOpenHomeActiveQueueObservation.Invalidated).reason,
        )
        assertEquals(71L, result.currentId)
        assertEquals(initial.currentTrack, result.currentTrack)
    }

    @Test
    fun `known current ID absent from unchanged queue invalidates without guessing an index`() {
        val queue = snapshot(token = 12L, ids = listOf(71L, 72L), currentId = 71L)
        val detached = snapshot(token = 12L, ids = queue.ids, currentId = 99L)

        val result = binding(queue).observe(DEVICE, GENERATION, detached, snapshotStillCurrent = true)

        assertEquals(
            DesktopOpenHomeActiveQueueObservation.Invalidated(
                DesktopOpenHomeActiveQueueInvalidationReason.UNKNOWN_CURRENT_ID,
                currentId = 99L,
                currentTrack = detached.currentTrack,
            ),
            result,
        )
    }

    @Test
    fun `device and session changes invalidate the local mapping`() {
        val snapshot = snapshot(token = 12L, ids = listOf(71L), currentId = 71L)
        val binding = binding(snapshot)

        assertEquals(
            DesktopOpenHomeActiveQueueInvalidationReason.DEVICE_CHANGED,
            (binding.observe("upnp:other", GENERATION, snapshot, true)
                as DesktopOpenHomeActiveQueueObservation.Invalidated).reason,
        )
        assertEquals(
            DesktopOpenHomeActiveQueueInvalidationReason.SESSION_CHANGED,
            (binding.observe(DEVICE, GENERATION + 1, snapshot, true)
                as DesktopOpenHomeActiveQueueObservation.Invalidated).reason,
        )
    }

    @Test
    fun `unstable queue and incomplete snapshots never resolve local indices`() {
        val initial = snapshot(token = 12L, ids = listOf(71L, 72L), currentId = 71L)
        val binding = binding(initial)

        assertEquals(
            DesktopOpenHomeActiveQueueObservation.Unavailable(
                DesktopOpenHomeActiveQueueUnavailableReason.UNSTABLE_SNAPSHOT,
            ),
            binding.observe(DEVICE, GENERATION, initial, snapshotStillCurrent = false),
        )
        val incomplete = initial.copy(tracksById = mapOf(71L to checkNotNull(initial.tracksById[71L])))
        assertEquals(
            DesktopOpenHomeActiveQueueObservation.Unavailable(
                DesktopOpenHomeActiveQueueUnavailableReason.INCOMPLETE_SNAPSHOT,
            ),
            binding.observe(DEVICE, GENERATION, incomplete, snapshotStillCurrent = true),
        )
    }

    @Test
    fun `zero or unknown current ID stays fail closed`() {
        val initial = snapshot(token = 12L, ids = listOf(71L), currentId = 71L)
        val binding = binding(initial)
        val stopped = snapshot(token = 12L, ids = initial.ids, currentId = 0L, state = "Stopped")
        val unknown = snapshot(
            token = 12L,
            ids = initial.ids,
            currentId = 72L,
            knownCurrentTrack = false,
        )

        assertEquals(
            DesktopOpenHomeActiveQueueObservation.Unavailable(
                DesktopOpenHomeActiveQueueUnavailableReason.NO_CURRENT_TRACK,
            ),
            binding.observe(DEVICE, GENERATION, stopped, snapshotStillCurrent = true),
        )
        assertEquals(
            DesktopOpenHomeActiveQueueObservation.Unavailable(
                DesktopOpenHomeActiveQueueUnavailableReason.UNKNOWN_CURRENT_TRACK,
            ),
            binding.observe(DEVICE, GENERATION, unknown, snapshotStillCurrent = true),
        )
    }

    @Test
    fun `binding creation requires a confirmed snapshot and exact queue ID order`() {
        val snapshot = snapshot(token = 12L, ids = listOf(71L, 72L), currentId = 71L)
        val expectedUris = expectedUriMap(snapshot)

        assertNull(
            DesktopOpenHomeActiveQueueBinding.create(
                DEVICE,
                GENERATION,
                snapshot,
                linkedMapOf(72L to 4, 71L to 3),
                expectedUris,
                snapshotStillCurrent = true,
            ),
        )
        assertNull(
            DesktopOpenHomeActiveQueueBinding.create(
                DEVICE,
                GENERATION,
                snapshot,
                linkedMapOf(71L to 3, 72L to 4),
                expectedUris,
                snapshotStillCurrent = false,
            ),
        )
        assertNull(
            DesktopOpenHomeActiveQueueBinding.create(
                DEVICE,
                GENERATION,
                snapshot.copy(currentId = 0L, currentTrack = null),
                linkedMapOf(71L to 3, 72L to 4),
                expectedUris,
                snapshotStillCurrent = true,
            ),
        )
        assertNull(
            DesktopOpenHomeActiveQueueBinding.create(
                DEVICE,
                GENERATION,
                snapshot,
                linkedMapOf(71L to 3, 72L to 4),
                linkedMapOf(72L to "http://renderer/72.flac", 71L to "http://renderer/71.flac"),
                snapshotStillCurrent = true,
            ),
        )
        assertNull(
            DesktopOpenHomeActiveQueueBinding.create(
                DEVICE,
                GENERATION,
                snapshot,
                linkedMapOf(71L to 3, 72L to 4),
                linkedMapOf(71L to "http://renderer/71.flac"),
                snapshotStillCurrent = true,
            ),
        )
        assertNull(
            DesktopOpenHomeActiveQueueBinding.create(
                DEVICE,
                GENERATION,
                snapshot,
                linkedMapOf(71L to 3, 72L to 4),
                linkedMapOf(71L to "http://renderer/wrong.flac", 72L to "http://renderer/72.flac"),
                snapshotStillCurrent = true,
            ),
        )

        val created = DesktopOpenHomeActiveQueueBinding.create(
            DEVICE,
            GENERATION,
            snapshot,
            linkedMapOf(71L to 3, 72L to 4),
            expectedUris,
            snapshotStillCurrent = true,
        )
        assertNotNull(created)
        assertTrue(checkNotNull(created).ids == snapshot.ids)
    }

    private fun binding(snapshot: DesktopOpenHomeQueueSnapshot) = checkNotNull(
        DesktopOpenHomeActiveQueueBinding.create(
            deviceIdentity = DEVICE,
            generation = GENERATION,
            snapshot = snapshot,
            idToLocalIndex = snapshot.ids.withIndex().associate { (index, id) -> id to (index + 3) },
            expectedUriById = expectedUriMap(snapshot),
            snapshotStillCurrent = true,
        ),
    )

    private fun expectedUriMap(snapshot: DesktopOpenHomeQueueSnapshot) = snapshot.ids.associateWith { id ->
        checkNotNull(snapshot.tracksById[id]).uri
    }

    private fun snapshot(
        token: Long,
        ids: List<Long>,
        currentId: Long,
        state: String = "Playing",
        knownCurrentTrack: Boolean = true,
    ): DesktopOpenHomeQueueSnapshot {
        val tracks = ids.associateWith { id -> track(id) }
        val current = if (currentId == 0L || !knownCurrentTrack) {
            null
        } else {
            tracks[currentId] ?: track(currentId)
        }
        return DesktopOpenHomeQueueSnapshot(
            tracksMax = 100L,
            token = token,
            ids = ids,
            currentId = currentId,
            transportState = state,
            tracksById = tracks,
            currentTrack = current,
            currentTrackKnown = currentId == 0L || knownCurrentTrack,
        )
    }

    private fun track(id: Long) = DesktopOpenHomePlaylistTrack(
        id = id,
        uri = "http://renderer/$id.flac",
        metadata = "<item id=\"$id\" />",
    )

    private companion object {
        const val DEVICE = "upnp:renderer-a"
        const val GENERATION = 4L
    }
}
