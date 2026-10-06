package dev.naominet.lazer

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AndroidLocalAudioLibraryRepositoryTest {
    private lateinit var context: Context
    private val treeUri = Uri.parse("content://test.provider/tree/library")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        catalogFiles().forEach(File::delete)
    }

    @After
    fun tearDown() {
        catalogFiles().forEach(File::delete)
    }

    @Test
    fun recursivelyIndexesOnlySupportedAudioAndDeduplicatesDirectoriesAndFiles() {
        val tree = FakeDocuments(root()).apply {
            children["root"] = listOf(
                directory("nested", "nested"),
                file("wav", "album.wav", "audio/wav", 100, 10),
                file("unsupported", "cover.jpg", "image/jpeg", 20, 10),
            )
            children["nested"] = listOf(
                file("flac", "track.FLAC", "audio/flac", 200, 20),
                directory("nested", "cycle"),
                file("dsf", "disc.dsf", "application/octet-stream", 300, 30),
                file("same", "duplicate.wav", "audio/wav", 100, 10),
            )
            children["root"] = children.getValue("root") +
                file("same", "duplicate.wav", "audio/wav", 100, 10)
        }
        val reads = mutableListOf<String>()
        val repository = repository(tree, reads)

        repository.addRoot(treeUri)

        assertEquals(4, reads.size)
        assertEquals(4, repository.state.value.tracks.size)
        assertEquals(5, repository.state.value.scannedFileCount)
        assertNull(repository.state.value.issue)
        assertTrue(repository.state.value.tracks.all { it.source is LazerTrackSource.LocalFile })
        assertEquals("A repeated folder ID must not be traversed twice", 1, tree.childQueries.count { it == "nested" })
    }

    @Test
    fun cachedMetadataIsReusedUntilSizeOrModificationChangesAndKeepsTrackId() {
        val tree = FakeDocuments(root()).apply {
            children["root"] = listOf(file("one", "one.flac", "audio/flac", 100, 10))
        }
        val reads = mutableListOf<String>()
        val repository = repository(tree, reads)
        repository.addRoot(treeUri)
        val first = repository.state.value.tracks.single()

        repository.rescan()
        val unchanged = repository.state.value.tracks.single()
        assertEquals(1, reads.size)
        assertEquals(first.id, unchanged.id)
        assertEquals("one", unchanged.title)

        tree.children["root"] = listOf(file("one", "one.flac", "audio/flac", 101, 11))
        repository.rescan()
        val changed = repository.state.value.tracks.single()
        assertEquals(2, reads.size)
        assertEquals(first.id, changed.id)
        assertEquals("updated", changed.title)
    }

    @Test
    fun largeFlatDirectoryStreamsTenThousandTracksAndReusesTheirMetadataOnRescan() {
        val files = (0 until 10_000).map { index ->
            file(
                id = "track-$index",
                name = "track-$index.flac",
                mime = "audio/flac",
                size = 1_000L + index,
                modified = 10_000L + index,
            )
        }
        val tree = FakeDocuments(root()).apply {
            children["root"] = files + files.first()
        }
        val reads = mutableListOf<String>()
        val repository = repository(tree, reads)

        repository.addRoot(treeUri)

        assertEquals(10_000, reads.size)
        assertEquals(10_000, repository.state.value.tracks.size)
        assertEquals(10_001, repository.state.value.scannedFileCount)
        assertNull(repository.state.value.issue)

        repository.rescan()

        assertEquals("Unchanged document metadata should be reused across a large rescan", 10_000, reads.size)
        assertEquals(10_000, repository.state.value.tracks.size)
        assertEquals(10_001, repository.state.value.scannedFileCount)
        assertNull(repository.state.value.issue)
    }

    @Test
    fun successfulRescanDropsDeletedDocumentsAndPersistsTheCatalogForNextInstance() {
        val tree = FakeDocuments(root()).apply {
            children["root"] = listOf(
                file("one", "one.wav", "audio/wav", 100, 10),
                file("two", "two.flac", "audio/flac", 200, 20),
            )
        }
        val storage = MemoryStorage()
        val firstRepository = repository(tree, mutableListOf(), storage)
        firstRepository.addRoot(treeUri)
        val originalId = firstRepository.state.value.tracks.first().id

        tree.children["root"] = listOf(tree.children.getValue("root").first())
        firstRepository.rescan()

        assertEquals(1, firstRepository.state.value.tracks.size)
        assertEquals("one", firstRepository.state.value.tracks.single().title)
        assertNull(firstRepository.state.value.issue)
        val restored = repository(tree, mutableListOf(), storage)
        restored.initialize()
        assertEquals(firstRepository.state.value.tracks, restored.state.value.tracks)
        assertEquals(firstRepository.state.value.roots, restored.state.value.roots)
        assertEquals(originalId, restored.state.value.tracks.single().id)
    }

    @Test
    fun removeRootPrunesItsEntriesAfterSuccessfulScan() {
        val tree = FakeDocuments(root()).apply {
            children["root"] = listOf(file("one", "one.dsf", "application/octet-stream", 100, 10))
        }
        val repository = repository(tree, mutableListOf())
        repository.addRoot(treeUri)
        assertEquals(1, repository.state.value.tracks.size)

        repository.removeRoot(treeUri.toString())

        assertTrue(repository.state.value.roots.isEmpty())
        assertTrue(repository.state.value.tracks.isEmpty())
        assertNull(repository.state.value.issue)
    }

    @Test
    fun failedRootRemovalKeepsCatalogAndReportsFailureToGrantOwner() {
        val tree = FakeDocuments(root()).apply {
            children["root"] = listOf(file("one", "one.flac", "audio/flac", 100, 10))
        }
        val storage = MemoryStorage()
        val repository = repository(tree, mutableListOf(), storage)
        repository.addRoot(treeUri)
        val oldRoots = repository.state.value.roots
        val oldTracks = repository.state.value.tracks
        storage.failWrites = true
        var removed: Boolean? = null

        repository.removeRoot(treeUri.toString()) { removed = it }

        assertEquals(false, removed)
        assertEquals(oldRoots, repository.state.value.roots)
        assertEquals(oldTracks, repository.state.value.tracks)
        assertEquals(LazerLocalLibraryIssue.ScanFailed, repository.state.value.issue)
    }

    @Test
    fun overlappingRootsDeduplicateByProviderDocumentIdentityAndPromoteUriOnRemoval() {
        val firstRootUri = Uri.parse("content://test.provider/tree/first")
        val secondRootUri = Uri.parse("content://test.provider/tree/second")
        val firstRoot = root().copy(displayName = "First")
        val secondRoot = root().copy(displayName = "Second")
        val tree = FakeDocuments(firstRoot).apply {
            roots[secondRootUri.toString()] = secondRoot
            children["root"] = listOf(file("shared-track", "shared.flac", "audio/flac", 100, 10))
        }
        val reads = mutableListOf<String>()
        val repository = repository(tree, reads)
        repository.addRoot(firstRootUri)
        val original = repository.state.value.tracks.single()
        repository.addRoot(secondRootUri)

        assertEquals(1, repository.state.value.tracks.size)
        assertEquals(1, reads.size)
        assertEquals(original.id, repository.state.value.tracks.single().id)
        val secondTreeUri = (repository.state.value.tracks.single().source as LazerTrackSource.LocalFile).uri
        assertTrue(secondTreeUri.contains("tree/first"))

        repository.removeRoot(firstRootUri.toString())

        assertEquals(1, repository.state.value.tracks.size)
        assertEquals(original.id, repository.state.value.tracks.single().id)
        val promotedTreeUri = (repository.state.value.tracks.single().source as LazerTrackSource.LocalFile).uri
        assertTrue(promotedTreeUri.contains("tree/second"))
        assertTrue(repository.state.value.roots.single().available)
        assertEquals(1, reads.size)
    }

    @Test
    fun failedRootScanKeepsThePreviousTrackSnapshotAndReportsScanFailure() {
        val tree = FakeDocuments(root()).apply {
            children["root"] = listOf(file("one", "one.flac", "audio/flac", 100, 10))
        }
        val repository = repository(tree, mutableListOf())
        repository.addRoot(treeUri)
        val oldSnapshot = repository.state.value.tracks
        tree.children["root"] = listOf(file("two", "two.flac", "audio/flac", 200, 20))
        tree.failureParents += "root"

        repository.rescan()

        assertEquals(oldSnapshot, repository.state.value.tracks)
        assertEquals(LazerLocalLibraryIssue.ScanFailed, repository.state.value.issue)
        assertFalse(repository.state.value.isScanning)
    }

    @Test
    fun cancellationKeepsThePreviousTrackSnapshotAndDoesNotExposePartialResults() {
        val tree = FakeDocuments(root()).apply {
            children["root"] = listOf(file("one", "one.flac", "audio/flac", 100, 10))
        }
        val reads = mutableListOf<String>()
        val repository = repository(tree, reads)
        repository.addRoot(treeUri)
        val oldSnapshot = repository.state.value.tracks
        tree.children["root"] = listOf(
            file("two", "two.flac", "audio/flac", 200, 20),
            file("three", "three.flac", "audio/flac", 300, 30),
        )
        tree.cancelAfterDocumentId = "two"

        repository.rescan()

        assertEquals(oldSnapshot, repository.state.value.tracks)
        assertEquals("The scan should have processed an item before cancellation", 2, reads.size)
        assertNull(repository.state.value.issue)
        assertFalse(repository.state.value.isScanning)
    }

    @Test
    fun revokedTreeGrantMarksRootUnavailableWithoutDroppingSavedTracks() {
        val tree = FakeDocuments(root()).apply {
            children["root"] = listOf(file("one", "one.flac", "audio/flac", 100, 10))
        }
        val repository = repository(tree, mutableListOf())
        repository.addRoot(treeUri)
        val oldSnapshot = repository.state.value.tracks
        tree.rootFailure = SecurityException("grant revoked")

        repository.rescan()

        assertEquals(oldSnapshot, repository.state.value.tracks)
        assertEquals(LazerLocalLibraryIssue.RootUnavailable, repository.state.value.issue)
        assertFalse(repository.state.value.roots.single().available)
    }

    private fun repository(
        tree: FakeDocuments,
        reads: MutableList<String>,
        storage: AndroidLocalAudioLibraryStorage = MemoryStorage(),
    ): AndroidLocalAudioLibraryRepository {
        val readMetadata: (Uri) -> LazerPickedAudioFile? = { uri ->
            reads += uri.toString()
            val isUpdated = uri.lastPathSegment == "one" && tree.children.values.any { siblings ->
                siblings.any { it.documentId == "one" && it.sizeBytes == 101L }
            }
            LazerPickedAudioFile(
                uri = uri.toString(),
                title = if (isUpdated) "updated" else uri.lastPathSegment.orEmpty().substringAfterLast('/'),
                artist = "Artist",
                album = "Album",
                durationMillis = 12_345L,
                replayGain = LazerReplayGainTags(trackGainDb = -5.0),
            )
        }
        return AndroidLocalAudioLibraryRepository(
            context = context,
            documents = tree,
            readMetadata = readMetadata,
            dispatcher = Dispatchers.Unconfined,
            storage = storage,
        )
    }

    @Test
    fun atomicStorageOnlyReportsSuccessWhenTheNewFileWasCommitted() {
        val storage = AndroidAtomicLocalAudioLibraryStorage(context.applicationContext)

        assertTrue(storage.write("first snapshot"))
        val replaced = storage.write("second snapshot")

        assertEquals(if (replaced) "second snapshot" else "first snapshot", storage.read())
    }

    private class MemoryStorage : AndroidLocalAudioLibraryStorage {
        var failWrites = false
        private var serialized: String? = null

        override fun read(): String? = serialized

        override fun write(serializedCatalog: String): Boolean {
            if (failWrites) return false
            serialized = serializedCatalog
            return true
        }
    }

    private fun root() = AndroidLocalAudioDocument(
        documentId = "root",
        identity = "test.provider\u0000root",
        uri = "content://test.provider/document/root",
        displayName = "Test music",
        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
        sizeBytes = null,
        lastModifiedMillis = null,
    )

    private fun directory(id: String, name: String) = AndroidLocalAudioDocument(
        documentId = id,
        identity = "test.provider\u0000$id",
        uri = "content://test.provider/document/$id",
        displayName = name,
        mimeType = DocumentsContract.Document.MIME_TYPE_DIR,
        sizeBytes = null,
        lastModifiedMillis = null,
    )

    private fun file(
        id: String,
        name: String,
        mime: String,
        size: Long,
        modified: Long,
    ) = AndroidLocalAudioDocument(
        documentId = id,
        identity = "test.provider\u0000$id",
        uri = "content://test.provider/document/$id",
        displayName = name,
        mimeType = mime,
        sizeBytes = size,
        lastModifiedMillis = modified,
    )

    private class FakeDocuments(
        private val root: AndroidLocalAudioDocument,
    ) : AndroidLocalAudioDocumentSource {
        val roots = mutableMapOf<String, AndroidLocalAudioDocument>()
        val children = mutableMapOf<String, List<AndroidLocalAudioDocument>>()
        val childQueries = mutableListOf<String>()
        val failureParents = mutableSetOf<String>()
        var rootFailure: Throwable? = null
        var cancelAfterDocumentId: String? = null

        override fun queryTreeRoot(treeUri: Uri): AndroidLocalAudioDocument {
            rootFailure?.let { throw it }
            return roots[treeUri.toString()] ?: root
        }

        override fun forEachChild(
            treeUri: Uri,
            parentDocumentId: String,
            consume: (AndroidLocalAudioDocument) -> Unit,
        ) {
            childQueries += parentDocumentId
            if (parentDocumentId in failureParents) throw java.io.IOException("provider failed")
            for (child in children[parentDocumentId].orEmpty()) {
                val document = child.copy(uri = "${treeUri}/document/${child.documentId}")
                consume(document)
                if (document.documentId == cancelAfterDocumentId) {
                    throw CancellationException("scan cancelled during child traversal")
                }
            }
        }
    }

    private fun catalogFiles(): List<File> {
        val base = File(context.filesDir, "lazer-local-library-catalog.json")
        return listOf(base, File(base.path + ".bak"), File(base.path + ".new"))
    }
}
