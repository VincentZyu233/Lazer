package dev.naominet.lazer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopOpenHomeMediaResourceRetentionTest {
    @Test
    fun `retains resource while a queue entry references its URI`() {
        var closed = 0
        val retainer = DesktopOpenHomeMediaResourceRetention()
        retainer.register("device-a", setOf(URI_A), close = { closed++ })

        val result = retainer.reconcile("device-a", "device-a", snapshot(URI_A), snapshotStillCurrent = true)

        assertEquals(0, result.releasedGroups)
        assertEquals(1, result.retainedGroups)
        assertEquals(DesktopOpenHomeMediaReconcileBlock.NONE, result.blockedBy)
        assertEquals(0, closed)
    }

    @Test
    fun `retains resource referenced only by a detached current track`() {
        var closed = 0
        val retainer = DesktopOpenHomeMediaResourceRetention()
        retainer.register("device-a", setOf(URI_A), close = { closed++ })
        val detachedCurrent = snapshot(
            ids = emptyList(),
            tracks = emptyMap(),
            currentId = 90L,
            currentTrack = DesktopOpenHomePlaylistTrack(90L, URI_A, "<item />"),
        )

        val result = retainer.reconcile("device-a", "device-a", detachedCurrent, snapshotStillCurrent = true)

        assertEquals(0, result.releasedGroups)
        assertEquals(1, result.retainedGroups)
        assertEquals(0, closed)
    }

    @Test
    fun `unknown current track blocks release even when queue does not contain URI`() {
        var closed = 0
        val retainer = DesktopOpenHomeMediaResourceRetention()
        retainer.register("device-a", setOf(URI_A), close = { closed++ })
        val unknownCurrent = snapshot(
            ids = emptyList(),
            tracks = emptyMap(),
            currentId = 90L,
            currentTrack = null,
            currentTrackKnown = false,
        )

        val result = retainer.reconcile("device-a", "device-a", unknownCurrent, snapshotStillCurrent = true)

        assertEquals(DesktopOpenHomeMediaReconcileBlock.UNKNOWN_CURRENT_TRACK, result.blockedBy)
        assertEquals(1, result.retainedGroups)
        assertEquals(0, closed)
    }

    @Test
    fun `releases resources after a stable complete empty snapshot`() {
        var closed = 0
        val retainer = DesktopOpenHomeMediaResourceRetention()
        retainer.register("device-a", setOf(URI_A), close = { closed++ })

        val result = retainer.reconcile("device-a", "device-a", snapshot(), snapshotStillCurrent = true)

        assertEquals(1, result.releasedGroups)
        assertEquals(0, result.retainedGroups)
        assertEquals(DesktopOpenHomeMediaReconcileBlock.NONE, result.blockedBy)
        assertEquals(1, closed)
        retainer.close()
        assertEquals("released resource closes only once", 1, closed)
    }

    @Test
    fun `unstable snapshot retains every resource for that device`() {
        var closed = 0
        val retainer = DesktopOpenHomeMediaResourceRetention()
        retainer.register("device-a", setOf(URI_A), close = { closed++ })

        val result = retainer.reconcile("device-a", "device-a", snapshot(), snapshotStillCurrent = false)

        assertEquals(DesktopOpenHomeMediaReconcileBlock.UNSTABLE_SNAPSHOT, result.blockedBy)
        assertEquals(1, result.retainedGroups)
        assertEquals(0, closed)
    }

    @Test
    fun `read error retains resources despite an otherwise empty snapshot`() {
        var closed = 0
        val retainer = DesktopOpenHomeMediaResourceRetention()
        retainer.register("device-a", setOf(URI_A), close = { closed++ })

        val result = retainer.reconcile(
            "device-a",
            "device-a",
            snapshot(),
            snapshotStillCurrent = true,
            readError = IllegalStateException("device read failed"),
        )

        assertEquals(DesktopOpenHomeMediaReconcileBlock.READ_ERROR_OR_MISSING_SNAPSHOT, result.blockedBy)
        assertEquals(1, result.retainedGroups)
        assertEquals(0, closed)
    }

    @Test
    fun `device mismatch retains and matching snapshot releases only its own device groups`() {
        var firstDeviceCloses = 0
        var secondDeviceCloses = 0
        val retainer = DesktopOpenHomeMediaResourceRetention()
        retainer.register("device-a", setOf(URI_A), close = { firstDeviceCloses++ })
        retainer.register("device-b", setOf(URI_A), close = { secondDeviceCloses++ })

        val mismatch = retainer.reconcile("device-a", "device-b", snapshot(), snapshotStillCurrent = true)
        assertEquals(DesktopOpenHomeMediaReconcileBlock.DEVICE_MISMATCH, mismatch.blockedBy)
        assertEquals(0, firstDeviceCloses)
        assertEquals(0, secondDeviceCloses)

        val matching = retainer.reconcile("device-a", "device-a", snapshot(), snapshotStillCurrent = true)
        assertEquals(1, matching.releasedGroups)
        assertEquals(0, matching.retainedGroups)
        assertEquals(1, firstDeviceCloses)
        assertEquals(0, secondDeviceCloses)

        retainer.close()
        assertEquals(1, firstDeviceCloses)
        assertEquals(1, secondDeviceCloses)
    }

    @Test
    fun `due renewal groups are device scoped and successful renew resets cadence`() = runBlocking {
        var now = 0L
        var renewals = 0
        val retainer = DesktopOpenHomeMediaResourceRetention(
            nowMillis = { now },
            renewalIntervalMillis = 100L,
        )
        retainer.register("device-a", setOf(URI_A), close = {}, renew = { renewals++ })
        retainer.register("device-b", setOf(URI_B), close = {}, renew = { renewals++ })

        assertTrue(retainer.dueRenewalGroups(atMillis = 99L).isEmpty())
        val due = retainer.dueRenewalGroups(atMillis = 100L)
        assertEquals(setOf("device-a", "device-b"), due.map { it.deviceIdentity }.toSet())
        assertTrue(due.all { it.uris.size == 1 })

        now = 100L
        retainer.renew(due.first(), atMillis = 100L)
        assertEquals(1, renewals)
        assertEquals(1, retainer.dueRenewalGroups(atMillis = 100L).size)

        now = 200L
        assertEquals(2, retainer.dueRenewalGroups().size)
        retainer.close()
        assertFalse(retainer.dueRenewalGroups().isNotEmpty())
    }

    @Test
    fun `failed renewal remains due until a callback succeeds`() = runBlocking {
        var now = 0L
        var shouldFail = true
        var attempts = 0
        val retainer = DesktopOpenHomeMediaResourceRetention(
            nowMillis = { now },
            renewalIntervalMillis = 100L,
        )
        retainer.register("device-a", setOf(URI_A), close = {}, renew = {
            attempts++
            if (shouldFail) throw IllegalStateException("renewal failed")
        })
        now = 100L
        val group = retainer.dueRenewalGroups().single()

        try {
            retainer.renew(group)
            throw AssertionError("A failed lease renewal must be reported.")
        } catch (error: IllegalStateException) {
            assertEquals("renewal failed", error.message)
        }
        assertEquals(1, retainer.dueRenewalGroups().size)

        shouldFail = false
        retainer.renew(retainer.dueRenewalGroups().single())

        assertEquals(2, attempts)
        assertTrue(retainer.dueRenewalGroups(atMillis = now).isEmpty())
        retainer.close()
    }

    @Test
    fun `close callback failure does not prevent closing remaining owners`() {
        var closeAttempts = 0
        val retainer = DesktopOpenHomeMediaResourceRetention()
        retainer.register("device-a", setOf(URI_A), close = {
            closeAttempts++
            throw IllegalStateException("first owner failed")
        })
        retainer.register("device-a", setOf(URI_B), close = {
            closeAttempts++
            throw IllegalArgumentException("second owner failed")
        })

        var failure: Throwable? = null
        try {
            retainer.close()
        } catch (error: Throwable) {
            failure = error
        }

        assertEquals(2, closeAttempts)
        assertTrue(failure is IllegalStateException)
        assertEquals(1, failure?.suppressed?.size)
        retainer.close()
        assertEquals(2, closeAttempts)
    }

    private fun snapshot(
        uri: String? = null,
        ids: List<Long> = if (uri == null) emptyList() else listOf(1L),
        tracks: Map<Long, DesktopOpenHomePlaylistTrack> = if (uri == null) emptyMap() else mapOf(
            1L to DesktopOpenHomePlaylistTrack(1L, uri, "<item />"),
        ),
        currentId: Long = if (uri == null) 0L else 1L,
        currentTrack: DesktopOpenHomePlaylistTrack? = if (uri == null) null else tracks[1L],
        currentTrackKnown: Boolean = true,
    ) = DesktopOpenHomeQueueSnapshot(
        tracksMax = 100L,
        token = 4L,
        ids = ids,
        currentId = currentId,
        transportState = "Stopped",
        tracksById = tracks,
        currentTrack = currentTrack,
        currentTrackKnown = currentTrackKnown,
    )

    private companion object {
        const val URI_A = "http://127.0.0.1:9000/media/a.flac"
        const val URI_B = "http://127.0.0.1:9000/media/b.flac"
    }
}
