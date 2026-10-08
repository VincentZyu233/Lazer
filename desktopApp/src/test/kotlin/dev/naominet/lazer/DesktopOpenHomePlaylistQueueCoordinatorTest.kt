package dev.naominet.lazer

import java.io.IOException
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopOpenHomePlaylistQueueCoordinatorTest {
    @Test
    fun `snapshot reads opaque IDs and ReadList pages with current state`() = runBlocking {
        val service = FakePlaylistService(maximum = 8)
        service.queue += listOf(
            track(101, "u1", "m1"),
            track(7, "u2", "m2"),
            track(4_000_000_001L, "u3", "m3"),
            track(8, "u4", "m4"),
            track(99, "u5", "m5"),
        )
        service.currentId = 4_000_000_001L
        service.state = "Playing"

        val snapshot = DesktopOpenHomePlaylistQueueCoordinator(service, readListPageSize = 2)
            .readSnapshot(device())

        assertEquals(listOf(101L, 7L, 4_000_000_001L, 8L, 99L), snapshot.ids)
        assertEquals("Playing", snapshot.transportState)
        assertEquals(4_000_000_001L, snapshot.currentId)
        assertEquals(track(4_000_000_001L, "u3", "m3"), snapshot.currentTrack)
        assertEquals(listOf(2, 2, 1), service.readListCalls.map { it.size })
        assertEquals(5, snapshot.tracksById.size)
    }

    @Test
    fun `current state poll reuses queue pages and reads only the current track`() = runBlocking {
        val service = FakePlaylistService(maximum = 20)
        service.queue += (1L..12L).map { track(it, "uri-$it", "meta-$it") }
        service.currentId = 8L
        service.state = "Playing"
        val coordinator = DesktopOpenHomePlaylistQueueCoordinator(service, readListPageSize = 2)
        val previous = coordinator.readSnapshot(device())
        service.readListCalls.clear()

        val refreshed = coordinator.readCurrentState(device(), previous)

        assertEquals(8L, refreshed?.currentId)
        assertEquals("Playing", refreshed?.transportState)
        assertEquals(track(8L, "uri-8", "meta-8"), refreshed?.currentTrack)
        assertEquals(listOf(listOf(8L)), service.readListCalls)
    }

    @Test
    fun `current state poll refuses a queue changed during the light read`() = runBlocking {
        val service = FakePlaylistService(maximum = 20)
        service.queue += track(8L, "uri-8", "meta-8")
        service.currentId = 8L
        val coordinator = DesktopOpenHomePlaylistQueueCoordinator(service)
        val previous = coordinator.readSnapshot(device())
        service.mutateDuringNextRead = true

        assertEquals(null, coordinator.readCurrentState(device(), previous))
    }

    @Test
    fun `replacement chains returned IDs and verifies duplicate URI entries by ID and metadata`() = runBlocking {
        val service = FakePlaylistService(maximum = 4, returnedIds = listOf(900, 23, 4_000_000_001L))
        service.queue += track(88, "old", "old-meta")
        val coordinator = DesktopOpenHomePlaylistQueueCoordinator(service, readListPageSize = 2)
        val expected = coordinator.readSnapshot(device())
        val candidates = listOf(
            DesktopOpenHomeQueueCandidate(8, "same-uri", "first metadata"),
            DesktopOpenHomeQueueCandidate(3, "same-uri", "second metadata"),
            DesktopOpenHomeQueueCandidate(14, "other-uri", "third metadata"),
        )

        val outcome = coordinator.replace(device(), expected, candidates)

        assertTrue(outcome is DesktopOpenHomeQueueReplacement.Replaced)
        outcome as DesktopOpenHomeQueueReplacement.Replaced
        assertEquals(mapOf(900L to 8, 23L to 3, 4_000_000_001L to 14), outcome.idToLocalIndex)
        assertEquals(service.token, outcome.token)
        assertEquals(listOf(0L, 900L, 23L), service.insertCalls.map { it.afterId })
        assertEquals(candidates.map { it.uri to it.metadata }, service.queue.map { it.uri to it.metadata })
        assertTrue(outcome.candidateMediaMayBeReferenced)
        assertFalse(outcome.safeToReleaseCandidateMedia)
    }

    @Test
    fun `changed token prevents DeleteAll and proves disjoint candidate URIs safe`() = runBlocking {
        val service = FakePlaylistService(maximum = 10)
        service.queue += track(44, "old-uri", "old")
        val coordinator = DesktopOpenHomePlaylistQueueCoordinator(service)
        val expected = coordinator.readSnapshot(device())
        service.externalAppend(track(600, "externally-added", "external"))

        val outcome = coordinator.replace(device(), expected, listOf(candidate(0, "fresh-uri")))

        assertTrue(outcome is DesktopOpenHomeQueueReplacement.Failed)
        outcome as DesktopOpenHomeQueueReplacement.Failed
        assertEquals(DesktopOpenHomeQueueStage.VERIFY_PRECONDITION, outcome.stage)
        assertEquals(DesktopOpenHomeCandidateReferenceStatus.VERIFIED_NOT_REFERENCED, outcome.candidateReferenceStatus)
        assertEquals(0, service.deleteAllCalls)
        assertTrue(outcome.safeToReleaseCandidateMedia)
        assertFalse(outcome.candidateMediaMayBeReferenced)
    }

    @Test
    fun `current ID change prevents DeleteAll even when queue token is unchanged`() = runBlocking {
        val service = FakePlaylistService(maximum = 10)
        service.queue += track(44, "old-uri", "old")
        service.currentId = 44L
        val coordinator = DesktopOpenHomePlaylistQueueCoordinator(service)
        val expected = coordinator.readSnapshot(device())
        service.currentId = 45L

        val outcome = coordinator.replace(device(), expected, listOf(candidate(0, "fresh-uri")))

        assertTrue(outcome is DesktopOpenHomeQueueReplacement.Failed)
        outcome as DesktopOpenHomeQueueReplacement.Failed
        assertEquals(DesktopOpenHomeQueueStage.VERIFY_PRECONDITION, outcome.stage)
        assertEquals(0, service.deleteAllCalls)
        assertEquals(listOf(44L), service.queue.map { it.id })
    }

    @Test
    fun `Insert response loss is not retried and candidate URI reference is retained`() = runBlocking {
        val service = FakePlaylistService(maximum = 10)
        service.failInsertAfterMutationAt = 2
        val coordinator = DesktopOpenHomePlaylistQueueCoordinator(service)
        val expected = coordinator.readSnapshot(device())
        val candidates = listOf(candidate(2, "new-1"), candidate(5, "new-2"), candidate(9, "new-3"))

        val outcome = coordinator.replace(device(), expected, candidates)

        assertTrue(outcome is DesktopOpenHomeQueueReplacement.Failed)
        outcome as DesktopOpenHomeQueueReplacement.Failed
        assertEquals(DesktopOpenHomeQueueStage.INSERT, outcome.stage)
        assertEquals(DesktopOpenHomeCandidateReferenceStatus.REFERENCED, outcome.candidateReferenceStatus)
        assertEquals(2, service.insertCalls.size)
        assertEquals(listOf(0L, 10_001L), service.insertCalls.map { it.afterId })
        assertTrue(outcome.candidateMediaMayBeReferenced)
        assertFalse(outcome.safeToReleaseCandidateMedia)
    }

    @Test
    fun `DeleteAll response loss reconciles an empty queue before allowing cleanup`() = runBlocking {
        val service = FakePlaylistService(maximum = 10)
        service.queue += track(3, "old-uri", "old")
        service.failDeleteAllAfterMutation = true
        val coordinator = DesktopOpenHomePlaylistQueueCoordinator(service)
        val expected = coordinator.readSnapshot(device())

        val outcome = coordinator.replace(device(), expected, listOf(candidate(0, "new-uri")))

        assertTrue(outcome is DesktopOpenHomeQueueReplacement.Failed)
        outcome as DesktopOpenHomeQueueReplacement.Failed
        assertEquals(DesktopOpenHomeQueueStage.DELETE_ALL, outcome.stage)
        assertEquals(DesktopOpenHomeCandidateReferenceStatus.VERIFIED_NOT_REFERENCED, outcome.candidateReferenceStatus)
        assertTrue(service.queue.isEmpty())
        assertEquals(0, service.insertCalls.size)
        assertTrue(outcome.safeToReleaseCandidateMedia)
    }

    @Test
    fun `empty queue does not permit cleanup while current ID still resolves to candidate URI`() = runBlocking {
        val service = FakePlaylistService(maximum = 10)
        service.queue += track(3, "old-uri", "old")
        service.currentId = 55
        service.state = "Playing"
        service.detachedCurrentTrack = track(55, "candidate-uri", "candidate")
        service.failDeleteAllAfterMutation = true
        val coordinator = DesktopOpenHomePlaylistQueueCoordinator(service)
        val expected = coordinator.readSnapshot(device())

        val outcome = coordinator.replace(device(), expected, listOf(candidate(0, "candidate-uri")))

        assertTrue(outcome is DesktopOpenHomeQueueReplacement.Failed)
        outcome as DesktopOpenHomeQueueReplacement.Failed
        assertTrue(service.queue.isEmpty())
        assertEquals(DesktopOpenHomeCandidateReferenceStatus.REFERENCED, outcome.candidateReferenceStatus)
        assertFalse(outcome.safeToReleaseCandidateMedia)
    }

    @Test
    fun `DeleteAll cancellation carries an unknown retain lease outcome`() = runBlocking {
        val service = FakePlaylistService(maximum = 10)
        service.cancelDeleteAllAfterMutation = true
        val coordinator = DesktopOpenHomePlaylistQueueCoordinator(service)
        val expected = coordinator.readSnapshot(device())
        val candidates = listOf(candidate(0, "candidate-uri"))

        try {
            coordinator.replace(device(), expected, candidates)
            throw AssertionError("A cancelled destructive request must propagate a typed cancellation.")
        } catch (error: DesktopOpenHomeQueueMutationCancelled) {
            assertEquals(DesktopOpenHomeQueueStage.DELETE_ALL, error.stage)
            assertEquals(listOf("candidate-uri"), error.candidateUris)
            assertEquals(DesktopOpenHomeCandidateReferenceStatus.UNKNOWN, error.candidateReferenceStatus)
            assertTrue(error.candidateMediaMayBeReferenced)
            assertFalse(error.safeToReleaseCandidateMedia)
        }
    }

    @Test
    fun `Insert cancellation after remote mutation never permits finally cleanup`() = runBlocking {
        val service = FakePlaylistService(maximum = 10)
        service.cancelInsertAfterMutationAt = 1
        val coordinator = DesktopOpenHomePlaylistQueueCoordinator(service)
        val expected = coordinator.readSnapshot(device())
        val candidates = listOf(candidate(0, "candidate-uri"), candidate(1, "second-candidate"))

        try {
            coordinator.replace(device(), expected, candidates)
            throw AssertionError("A cancelled Insert must propagate a typed retain lease outcome.")
        } catch (error: DesktopOpenHomeQueueMutationCancelled) {
            assertEquals(DesktopOpenHomeQueueStage.INSERT, error.stage)
            assertEquals(listOf("candidate-uri", "second-candidate"), error.candidateUris)
            assertEquals(DesktopOpenHomeCandidateReferenceStatus.UNKNOWN, error.candidateReferenceStatus)
            assertTrue(error.candidateMediaMayBeReferenced)
            assertFalse(error.safeToReleaseCandidateMedia)
            assertEquals(1, service.insertCalls.size)
            assertEquals(listOf("candidate-uri"), service.queue.map { it.uri })
        }
    }

    @Test
    fun `capacity failure leaves queue untouched and does not reference candidates`() = runBlocking {
        val service = FakePlaylistService(maximum = 1)
        service.queue += track(17, "old-uri", "old")
        val coordinator = DesktopOpenHomePlaylistQueueCoordinator(service)
        val expected = coordinator.readSnapshot(device())

        val outcome = coordinator.replace(
            device(),
            expected,
            listOf(candidate(0, "one"), candidate(1, "two")),
        )

        assertTrue(outcome is DesktopOpenHomeQueueReplacement.Failed)
        outcome as DesktopOpenHomeQueueReplacement.Failed
        assertEquals(DesktopOpenHomeQueueStage.VERIFY_PRECONDITION, outcome.stage)
        assertEquals(DesktopOpenHomeCandidateReferenceStatus.VERIFIED_NOT_REFERENCED, outcome.candidateReferenceStatus)
        assertEquals(0, service.deleteAllCalls)
        assertEquals(listOf(17L), service.queue.map { it.id })
    }

    @Test
    fun `ReadList response loss may split only the read request to satisfy response bound`() = runBlocking {
        val service = FakePlaylistService(maximum = 8)
        service.queue += (1L..4L).map { track(it, "uri-$it", "metadata-$it") }
        service.rejectReadPagesLargerThan = 2

        val snapshot = DesktopOpenHomePlaylistQueueCoordinator(service, readListPageSize = 4)
            .readSnapshot(device())

        assertEquals(listOf(1L, 2L, 3L, 4L), snapshot.ids)
        assertTrue(service.readListCalls.any { it.size == 4 })
        assertTrue(service.readListCalls.any { it.size == 2 })
        assertEquals(4, snapshot.tracksById.size)
    }

    @Test
    fun `missing final ReadList entry fails closed and does not permit candidate cleanup`() = runBlocking {
        val service = FakePlaylistService(maximum = 10)
        service.omitInsertedTracksFromReadList = true
        val coordinator = DesktopOpenHomePlaylistQueueCoordinator(service)
        val expected = coordinator.readSnapshot(device())

        val outcome = coordinator.replace(device(), expected, listOf(candidate(0, "candidate-uri")))

        assertTrue(outcome is DesktopOpenHomeQueueReplacement.Failed)
        outcome as DesktopOpenHomeQueueReplacement.Failed
        assertEquals(DesktopOpenHomeQueueStage.VERIFY_FINAL_QUEUE, outcome.stage)
        assertEquals(DesktopOpenHomeCandidateReferenceStatus.UNKNOWN, outcome.candidateReferenceStatus)
        assertTrue(outcome.candidateMediaMayBeReferenced)
        assertFalse(outcome.safeToReleaseCandidateMedia)
        assertEquals(1, service.insertCalls.size)
    }

    @Test
    fun `queue mutation during snapshot invalidates snapshot`() = runBlocking {
        val service = FakePlaylistService(maximum = 10)
        service.queue += track(27, "old", "old")
        service.mutateDuringNextRead = true

        try {
            DesktopOpenHomePlaylistQueueCoordinator(service).readSnapshot(device())
            throw AssertionError("A token change during paginated ReadList must invalidate the snapshot.")
        } catch (error: IOException) {
            assertTrue(error.message.orEmpty().contains("changed"))
        }
    }

    @Test
    fun `current ID change invalidates snapshot even when queue token is unchanged`() = runBlocking {
        val service = FakePlaylistService(maximum = 10)
        service.queue += track(27, "old", "old")
        service.currentId = 27L
        val coordinator = DesktopOpenHomePlaylistQueueCoordinator(service)
        val snapshot = coordinator.readSnapshot(device())

        service.currentId = 88L

        assertFalse(coordinator.snapshotStillCurrent(device(), snapshot))
        assertEquals(snapshot.token, service.token)
    }

    @Test
    fun `empty queue replacement returns an empty mapping and permits media cleanup`() = runBlocking {
        val service = FakePlaylistService(maximum = 4)
        val coordinator = DesktopOpenHomePlaylistQueueCoordinator(service)
        val expected = coordinator.readSnapshot(device())

        val outcome = coordinator.replace(device(), expected, emptyList())

        assertTrue(outcome is DesktopOpenHomeQueueReplacement.Replaced)
        outcome as DesktopOpenHomeQueueReplacement.Replaced
        assertTrue(outcome.idToLocalIndex.isEmpty())
        assertTrue(outcome.safeToReleaseCandidateMedia)
        assertFalse(outcome.candidateMediaMayBeReferenced)
    }

    private fun candidate(index: Int, uri: String) = DesktopOpenHomeQueueCandidate(index, uri, "meta-$index")

    private fun track(id: Long, uri: String, metadata: String) = DesktopOpenHomePlaylistTrack(id, uri, metadata)
}

private class FakePlaylistService(
    private val maximum: Long,
    private val returnedIds: List<Long> = listOf(10_001L, 83L, 2_000_000_001L, 19L),
) : DesktopOpenHomePlaylistQueueService {
    val queue = mutableListOf<DesktopOpenHomePlaylistTrack>()
    val insertCalls = mutableListOf<InsertCall>()
    val readListCalls = mutableListOf<List<Long>>()
    var token = 10L
    var currentId = 0L
    var state = "Stopped"
    var detachedCurrentTrack: DesktopOpenHomePlaylistTrack? = null
    var deleteAllCalls = 0
    var failDeleteAllAfterMutation = false
    var cancelDeleteAllAfterMutation = false
    var failInsertAfterMutationAt: Int? = null
    var cancelInsertAfterMutationAt: Int? = null
    var omitInsertedTracksFromReadList = false
    var rejectReadPagesLargerThan: Int? = null
    var mutateDuringNextRead = false
    private var insertedCount = 0

    override suspend fun tracksMax(device: DesktopUpnpRendererDevice): Long = maximum

    override suspend fun idArray(device: DesktopUpnpRendererDevice) = DesktopOpenHomePlaylistIdArray(
        token = token,
        ids = queue.map { it.id },
    )

    override suspend fun idArrayChanged(device: DesktopUpnpRendererDevice, token: Long): Boolean = this.token != token

    override suspend fun id(device: DesktopUpnpRendererDevice): Long = currentId

    override suspend fun transportState(device: DesktopUpnpRendererDevice): String = state

    override suspend fun readList(
        device: DesktopUpnpRendererDevice,
        ids: List<Long>,
    ): List<DesktopOpenHomePlaylistTrack> {
        readListCalls += ids.toList()
        if (rejectReadPagesLargerThan?.let { ids.size > it } == true) {
            throw IOException("UPnP SOAP response exceeds the size limit.")
        }
        if (mutateDuringNextRead) {
            mutateDuringNextRead = false
            externalAppend(track(4_444, "racing", "racing"))
        }
        val allTracks = queue.toMutableList().apply { detachedCurrentTrack?.let(::add) }
        return ids.mapNotNull { id ->
            allTracks.firstOrNull { it.id == id }?.takeUnless {
                omitInsertedTracksFromReadList && it.uri.startsWith("candidate-uri")
            }
        }
    }

    private fun track(id: Long, uri: String, metadata: String) =
        DesktopOpenHomePlaylistTrack(id, uri, metadata)

    override suspend fun deleteAll(device: DesktopUpnpRendererDevice) {
        deleteAllCalls++
        queue.clear()
        token++
        if (cancelDeleteAllAfterMutation) throw CancellationException("DeleteAll was cancelled after send.")
        if (failDeleteAllAfterMutation) throw IOException("DeleteAll response was lost.")
    }

    override suspend fun insert(
        device: DesktopUpnpRendererDevice,
        afterId: Long,
        uri: String,
        metadata: String,
    ): Long {
        insertCalls += InsertCall(afterId, uri, metadata)
        val id = returnedIds.getOrElse(insertedCount) { 90_000L + insertedCount }
        insertedCount++
        val insertionIndex = if (afterId == 0L) 0 else queue.indexOfFirst { it.id == afterId }
            .takeIf { it >= 0 }?.plus(1) ?: throw IOException("AfterId does not name a queue entry.")
        queue.add(insertionIndex, track(id, uri, metadata))
        token++
        if (cancelInsertAfterMutationAt == insertedCount) throw CancellationException("Insert was cancelled after send.")
        if (failInsertAfterMutationAt == insertedCount) throw IOException("Insert response was lost.")
        return id
    }

    fun externalAppend(track: DesktopOpenHomePlaylistTrack) {
        queue += track
        token++
    }

    data class InsertCall(val afterId: Long, val uri: String, val metadata: String)
}

private fun device() = DesktopUpnpRendererDevice(
    udn = "uuid:openhome-queue-test",
    friendlyName = "Test renderer",
    manufacturer = "Test",
    modelName = "Queue fixture",
    modelNumber = null,
    descriptionUri = URI("http://127.0.0.1/device.xml"),
    services = emptyMap(),
)
