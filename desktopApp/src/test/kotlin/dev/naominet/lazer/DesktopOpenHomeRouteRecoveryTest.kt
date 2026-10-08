package dev.naominet.lazer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesktopOpenHomeRouteRecoveryTest {
    @Test
    fun `route recovery plans exact mapped current track and playing intent`() {
        val snapshot = snapshot(currentId = 71L, state = "Playing")

        val plan = plan(snapshot, routeChanged = true)

        assertEquals(
            DesktopOpenHomeRouteRecoveryPlan(
                currentId = 71L,
                localIndex = 0,
                intent = DesktopOpenHomePlaybackIntent.PLAY,
            ),
            plan,
        )
    }

    @Test
    fun `route recovery preserves pause and stop intent`() {
        assertEquals(
            DesktopOpenHomePlaybackIntent.PAUSE,
            checkNotNull(plan(snapshot(currentId = 72L, state = "Paused"), routeChanged = true)).intent,
        )
        assertEquals(
            DesktopOpenHomePlaybackIntent.PLAY,
            checkNotNull(plan(snapshot(currentId = 72L, state = "Buffering"), routeChanged = true)).intent,
        )
        assertEquals(
            DesktopOpenHomePlaybackIntent.STOP,
            checkNotNull(plan(snapshot(currentId = 72L, state = "Stopped"), routeChanged = true)).intent,
        )
    }

    @Test
    fun `route recovery refuses unchanged route unstable snapshot and invalid mapping`() {
        val initial = snapshot(currentId = 71L, state = "Playing")
        val activeBinding = binding(initial)
        assertNull(
            planDesktopOpenHomeRouteRecovery(DEVICE, GENERATION, activeBinding, initial, true, 2, false),
        )
        assertNull(
            planDesktopOpenHomeRouteRecovery(DEVICE, GENERATION, activeBinding, initial, false, 2, true),
        )

        val changedQueue = initial.copy(token = initial.token + 1L)
        assertNull(plan(changedQueue, routeChanged = true, binding = activeBinding))

        val changedUri = initial.copy(
            tracksById = initial.tracksById + (72L to checkNotNull(initial.tracksById[72L]).copy(uri = "http://other/72.flac")),
        )
        assertNull(plan(changedUri, routeChanged = true, binding = activeBinding))

        assertNull(plan(initial, routeChanged = true, binding = activeBinding, localTrackCount = 0))
        assertNull(plan(snapshot(currentId = 71L, state = "UnknownState"), routeChanged = true))
    }

    @Test
    fun `explicit user transport intent overrides a stale renderer state during route recovery`() {
        val snapshot = snapshot(currentId = 72L, state = "Playing")
        val plan = planDesktopOpenHomeRouteRecovery(
            deviceIdentity = DEVICE,
            generation = GENERATION,
            binding = binding(snapshot),
            snapshot = snapshot,
            snapshotStillCurrent = true,
            localTrackCount = 2,
            routeChanged = true,
            intentOverride = DesktopOpenHomePlaybackIntent.STOP,
        )

        assertEquals(DesktopOpenHomePlaybackIntent.STOP, checkNotNull(plan).intent)
    }

    private fun plan(
        snapshot: DesktopOpenHomeQueueSnapshot,
        routeChanged: Boolean,
        binding: DesktopOpenHomeActiveQueueBinding? = binding(snapshot),
        localTrackCount: Int = 2,
    ) = planDesktopOpenHomeRouteRecovery(
        deviceIdentity = DEVICE,
        generation = GENERATION,
        binding = binding,
        snapshot = snapshot,
        snapshotStillCurrent = true,
        localTrackCount = localTrackCount,
        routeChanged = routeChanged,
    )

    private fun binding(snapshot: DesktopOpenHomeQueueSnapshot) = checkNotNull(
        DesktopOpenHomeActiveQueueBinding.create(
            deviceIdentity = DEVICE,
            generation = GENERATION,
            snapshot = snapshot,
            idToLocalIndex = linkedMapOf(71L to 0, 72L to 1),
            expectedUriById = snapshot.ids.associateWith { id -> checkNotNull(snapshot.tracksById[id]).uri },
            snapshotStillCurrent = true,
        ),
    )

    private fun snapshot(currentId: Long, state: String): DesktopOpenHomeQueueSnapshot {
        val tracks = listOf(71L, 72L).associateWith { id ->
            DesktopOpenHomePlaylistTrack(id, "http://renderer/$id.flac", "<item id=\"$id\" />")
        }
        return DesktopOpenHomeQueueSnapshot(
            tracksMax = 100L,
            token = 9L,
            ids = listOf(71L, 72L),
            currentId = currentId,
            transportState = state,
            tracksById = tracks,
            currentTrack = tracks[currentId],
            currentTrackKnown = true,
        )
    }

    private companion object {
        const val DEVICE = "upnp:renderer-a"
        const val GENERATION = 4L
    }
}
