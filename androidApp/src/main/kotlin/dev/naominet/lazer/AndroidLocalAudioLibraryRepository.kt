package dev.naominet.lazer

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.AtomicFile
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/** One document returned by a SAF document provider. [documentId] is scoped to its provider. */
internal data class AndroidLocalAudioDocument(
    val documentId: String,
    val identity: String,
    val uri: String,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long?,
    val lastModifiedMillis: Long?,
) {
    val isDirectory: Boolean get() = mimeType == DocumentsContract.Document.MIME_TYPE_DIR
}

/** The narrow SAF surface used by the scanner, replaceable by an in-memory document tree in tests. */
internal interface AndroidLocalAudioDocumentSource {
    fun queryTreeRoot(treeUri: Uri): AndroidLocalAudioDocument

    /** Calls [consume] for each child row without materializing the full directory. */
    fun forEachChild(
        treeUri: Uri,
        parentDocumentId: String,
        consume: (AndroidLocalAudioDocument) -> Unit,
    )
}

/**
 * Android's persistent folder-backed local library. Scans and catalog IO stay on [dispatcher],
 * while the tree plus track metadata is replaced atomically in one app-private JSON file.
 */
class AndroidLocalAudioLibraryRepository internal constructor(
    context: Context,
    private val documents: AndroidLocalAudioDocumentSource,
    private val readMetadata: (Uri) -> LazerPickedAudioFile?,
    private val dispatcher: CoroutineDispatcher,
    private val storage: AndroidLocalAudioLibraryStorage =
        AndroidAtomicLocalAudioLibraryStorage(context.applicationContext),
) {
    constructor(context: Context) : this(
        context = context.applicationContext,
        documents = AndroidSafLocalAudioDocumentSource(context.applicationContext),
        readMetadata = { uri -> readAndroidLocalAudioFile(context.applicationContext, uri) },
        dispatcher = Dispatchers.IO,
        storage = AndroidAtomicLocalAudioLibraryStorage(context.applicationContext),
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val catalogMutex = Mutex()
    private val ready = CompletableDeferred<Unit>()
    private val stateFlow = MutableStateFlow(emptyLibrarySnapshot())
    val state: StateFlow<LazerLocalLibrarySnapshot> = stateFlow.asStateFlow()

    internal suspend fun awaitInitialized() {
        ready.await()
    }

    private val initLock = Any()
    private var initializationStarted = false
    private var catalogEntries: List<AndroidLocalAudioCatalogEntry> = emptyList()
    private var pendingScan: Job? = null
    @Volatile
    private var scanGeneration = 0L

    /** Loads the saved snapshot on IO, publishes it, then refreshes roots on IO. */
    fun initialize() {
        synchronized(initLock) {
            if (initializationStarted) return
            initializationStarted = true
            scope.launch {
                var issue: LazerLocalLibraryIssue? = null
                val saved = withContext(dispatcher) {
                    runCatching(::readCatalog).getOrElse { failure ->
                        issue = LazerLocalLibraryIssue.ScanFailed
                        Log.w(TAG, "Could not read Android local audio catalog", failure)
                        AndroidLocalAudioCatalog(emptyList(), emptyList())
                    }
                }
                catalogMutex.withLock {
                    catalogEntries = saved.entries
                    reserveIds(saved.entries)
                    stateFlow.value = LazerLocalLibrarySnapshot(
                        roots = saved.roots,
                        tracks = saved.entries.map(AndroidLocalAudioCatalogEntry::track),
                        isScanning = false,
                        scannedFileCount = saved.entries.size,
                        issue = issue,
                    )
                }
                ready.complete(Unit)
                if (saved.roots.isNotEmpty()) enqueueScan()
            }
        }
    }

    /** The host persists the OpenDocumentTree read grant before passing this URI here. */
    fun addRoot(uri: Uri, onAdded: (Boolean) -> Unit = {}) {
        initialize()
        scope.launch {
            ready.await()
            var added = false
            try {
                catalogMutex.withLock {
                    val current = stateFlow.value
                    val rootUri = uri.toString().trim().takeIf(String::isNotEmpty)
                        ?: return@withLock
                    if (current.roots.any { it.uri == rootUri }) {
                        added = true
                        return@withLock
                    }
                    val root = LazerLocalLibraryRoot(
                        uri = rootUri,
                        displayName = uri.lastPathSegment?.substringAfterLast(':')?.ifBlank { null }
                            ?: uri.lastPathSegment?.ifBlank { null }
                            ?: rootUri,
                        available = true,
                    )
                    val roots = current.roots + root
                    val entries = catalogEntries
                    if (!withContext(dispatcher) { writeCatalog(roots, entries) }) {
                        stateFlow.value = current.copy(issue = LazerLocalLibraryIssue.ScanFailed)
                        return@withLock
                    }
                    stateFlow.value = current.copy(roots = roots, issue = null)
                    added = true
                }
                if (added) enqueueScan()
            } catch (cancelled: CancellationException) {
                runCatching { onAdded(false) }
                    .onFailure { Log.w(TAG, "Local audio root completion callback failed", it) }
                throw cancelled
            } catch (failure: Throwable) {
                stateFlow.value = stateFlow.value.copy(issue = LazerLocalLibraryIssue.ScanFailed)
                Log.w(TAG, "Could not add Android local audio library root", failure)
            }
            runCatching { onAdded(added) }
                .onFailure { Log.w(TAG, "Local audio root completion callback failed", it) }
        }
    }

    /** Removes entries owned by the root immediately; the host releases its read grant separately. */
    fun removeRoot(uri: String, onRemoved: (Boolean) -> Unit = {}) {
        initialize()
        scope.launch {
            ready.await()
            catalogMutex.withLock {
                val current = stateFlow.value
                val roots = current.roots.filterNot { it.uri == uri }
                if (roots.size == current.roots.size) {
                    safelyReportRemoval(onRemoved, true)
                    return@withLock
                }
                val remainingRootUris = roots.mapTo(mutableSetOf(), LazerLocalLibraryRoot::uri)
                val entries = catalogEntries.mapNotNull { entry ->
                    val owners = (listOf(entry.ownerRootUri) + entry.alsoPresentInRoots)
                        .filter { it in remainingRootUris }
                    if (owners.isEmpty()) null else {
                        val newOwner = owners.first()
                        val newUri = if (entry.ownerRootUri != newOwner) {
                            treeScopedDocumentUri(newOwner, entry.document.documentId)
                        } else {
                            entry.document.uri
                        }
                        val updatedTrack = if (entry.document.uri != newUri) {
                            entry.track.copy(source = LazerTrackSource.LocalFile(newUri))
                        } else {
                            entry.track
                        }
                        entry.copy(
                            document = entry.document.copy(uri = newUri),
                            track = updatedTrack,
                            ownerRootUri = newOwner,
                            alsoPresentInRoots = owners.drop(1),
                        )
                    }
                }
                if (!withContext(dispatcher) { writeCatalog(roots, entries) }) {
                    stateFlow.value = current.copy(issue = LazerLocalLibraryIssue.ScanFailed)
                    safelyReportRemoval(onRemoved, false)
                    return@withLock
                }
                catalogEntries = entries
                stateFlow.value = current.copy(
                    roots = roots,
                    tracks = entries.map(AndroidLocalAudioCatalogEntry::track),
                    scannedFileCount = entries.size,
                    issue = null,
                )
                safelyReportRemoval(onRemoved, true)
            }
            enqueueScan()
        }
    }

    /** Enqueues a serialized refresh. An interrupted provider query never publishes partial tracks. */
    fun rescan() {
        initialize()
        enqueueScan()
    }

    /** Reports a tree whose read permission could not be persisted; it must not enter the catalog. */
    fun reportFolderAccessUnavailable() {
        initialize()
        scope.launch {
            ready.await()
            stateFlow.value = stateFlow.value.copy(issue = LazerLocalLibraryIssue.RootUnavailable)
        }
    }

    private fun enqueueScan() {
        synchronized(initLock) {
            val generation = ++scanGeneration
            pendingScan?.cancel()
            pendingScan = scope.launch {
                ready.await()
                catalogMutex.withLock { scanLocked(generation) }
            }
        }
    }

    private suspend fun scanLocked(generation: Long) {
        currentCoroutineContext().ensureActive()
        val initial = stateFlow.value
        stateFlow.value = initial.copy(isScanning = true, scannedFileCount = 0, issue = null)
        var fileCount = 0
        try {
            val scanContext = currentCoroutineContext()
            val oldEntries = catalogEntries.associateBy { it.document.identity }
            val roots = mutableListOf<LazerLocalLibraryRoot>()
            val entries = linkedMapOf<String, AndroidLocalAudioCatalogEntry>()

            for (root in initial.roots) {
                currentCoroutineContext().ensureActive()
                val treeUri = Uri.parse(root.uri)
                val rootDocument = try {
                    documents.queryTreeRoot(treeUri)
                } catch (denied: SecurityException) {
                    throw AndroidLocalAudioRootUnavailable(root.uri, denied)
                } catch (invalid: IllegalArgumentException) {
                    throw AndroidLocalAudioRootUnavailable(root.uri, invalid)
                }
                if (!rootDocument.isDirectory) throw AndroidLocalAudioRootUnavailable(root.uri)
                roots += root.copy(
                    displayName = rootDocument.displayName.ifBlank { root.displayName },
                    available = true,
                )

                val pending = ArrayDeque<String>()
                val visitedDirectories = mutableSetOf(rootDocument.documentId)
                pending.addLast(rootDocument.documentId)
                while (pending.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val parentId = pending.removeLast()
                    try {
                        documents.forEachChild(treeUri, parentId) { child ->
                            scanContext.ensureActive()
                            if (child.isDirectory) {
                                if (visitedDirectories.add(child.documentId)) pending.addLast(child.documentId)
                                return@forEachChild
                            }
                            if (!isSupportedAndroidLocalAudio(child.displayName, child.mimeType)) return@forEachChild
                            fileCount += 1
                            val alreadyFound = entries[child.identity]
                            if (alreadyFound != null) {
                                if (alreadyFound.ownerRootUri != root.uri && root.uri !in alreadyFound.alsoPresentInRoots) {
                                    entries[child.identity] = alreadyFound.copy(
                                        alsoPresentInRoots = alreadyFound.alsoPresentInRoots + root.uri,
                                    )
                                }
                                return@forEachChild
                            }

                            val previous = oldEntries[child.identity]
                            val reusable = previous?.takeIf {
                                child.sizeBytes != null && child.lastModifiedMillis != null &&
                                    it.document.sizeBytes == child.sizeBytes &&
                                    it.document.lastModifiedMillis == child.lastModifiedMillis
                            }
                            val entry = reusable?.copy(
                                document = child,
                                track = reusable.track.copy(source = LazerTrackSource.LocalFile(child.uri)),
                                ownerRootUri = root.uri,
                                alsoPresentInRoots = emptyList(),
                            ) ?: run {
                                val picked = readMetadata(Uri.parse(child.uri))
                                    ?: throw IOException("Metadata reader rejected supported URI ${child.uri}")
                                val id = previous?.track?.id ?: nextLocalTrackId()
                                AndroidLocalAudioCatalogEntry(
                                    document = child,
                                    track = picked.toCatalogTrack(id, child.uri),
                                    ownerRootUri = root.uri,
                                )
                            }
                            entries[child.identity] = entry
                            if (fileCount % PROGRESS_UPDATE_INTERVAL == 0) {
                                stateFlow.value = stateFlow.value.copy(scannedFileCount = fileCount)
                            }
                        }
                    } catch (denied: SecurityException) {
                        throw AndroidLocalAudioRootUnavailable(root.uri, denied)
                    } catch (invalid: IllegalArgumentException) {
                        throw AndroidLocalAudioRootUnavailable(root.uri, invalid)
                    }
                }
            }

            currentCoroutineContext().ensureActive()
            if (!withContext(dispatcher) { writeCatalog(roots, entries.values.toList()) }) {
                throw IOException("Could not atomically save Android local library catalog")
            }
            catalogEntries = entries.values.toList()
            stateFlow.value = LazerLocalLibrarySnapshot(
                roots = roots,
                tracks = catalogEntries.map(AndroidLocalAudioCatalogEntry::track),
                isScanning = false,
                scannedFileCount = fileCount,
                issue = null,
            )
        } catch (cancelled: CancellationException) {
            if (generation == scanGeneration) {
                withContext(kotlinx.coroutines.NonCancellable) {
                    stateFlow.value = stateFlow.value.copy(
                        isScanning = false,
                        scannedFileCount = fileCount,
                    )
                }
            }
            throw cancelled
        } catch (unavailable: AndroidLocalAudioRootUnavailable) {
            stateFlow.value = stateFlow.value.copy(
                roots = stateFlow.value.roots.map { root ->
                    if (root.uri == unavailable.rootUri) root.copy(available = false) else root
                },
                isScanning = false,
                scannedFileCount = fileCount,
                issue = LazerLocalLibraryIssue.RootUnavailable,
            )
            Log.i(TAG, "Local library root is unavailable", unavailable.cause)
        } catch (failure: Throwable) {
            stateFlow.value = stateFlow.value.copy(
                isScanning = false,
                scannedFileCount = fileCount,
                issue = LazerLocalLibraryIssue.ScanFailed,
            )
            Log.w(TAG, "Could not scan Android local audio library", failure)
        }
    }

    private fun nextLocalTrackId(): Long = LazerLocalTrackIdentity.nextId()

    private fun reserveIds(entries: List<AndroidLocalAudioCatalogEntry>) {
        LazerLocalTrackIdentity.reserve(entries.map { it.track.id })
    }

    private fun safelyReportRemoval(callback: (Boolean) -> Unit, removed: Boolean) {
        runCatching { callback(removed) }
            .onFailure { Log.w(TAG, "Local library removal callback failed", it) }
    }

    private fun readCatalog(): AndroidLocalAudioCatalog {
        val serialized = storage.read() ?: return AndroidLocalAudioCatalog(emptyList(), emptyList())
        val root = JSONObject(serialized)
        require(root.optInt("schemaVersion") == CATALOG_SCHEMA_VERSION) { "Unknown catalog schema" }
        val rootsJson = root.optJSONArray("roots") ?: JSONArray()
        val roots = (0 until rootsJson.length()).mapNotNull { index ->
            rootsJson.optJSONObject(index)?.let { json ->
                val uri = json.optString("uri").takeIf(String::isNotBlank) ?: return@let null
                LazerLocalLibraryRoot(
                    uri = uri,
                    displayName = json.optString("displayName", uri),
                    available = json.optBoolean("available", true),
                )
            }
        }
        val trackJson = root.optJSONArray("tracks") ?: JSONArray()
        val entries = (0 until trackJson.length()).mapNotNull { index ->
            runCatching { trackJson.getJSONObject(index).toCatalogEntry() }.getOrNull()
            }.distinctBy { it.document.identity }
        return AndroidLocalAudioCatalog(roots, entries)
    }

    private fun writeCatalog(
        roots: List<LazerLocalLibraryRoot>,
        entries: List<AndroidLocalAudioCatalogEntry>,
    ): Boolean {
        val root = JSONObject()
            .put("schemaVersion", CATALOG_SCHEMA_VERSION)
            .put("roots", JSONArray().apply {
                roots.forEach { item ->
                    put(JSONObject()
                        .put("uri", item.uri)
                        .put("displayName", item.displayName)
                        .put("available", item.available))
                }
            })
            .put("tracks", JSONArray().apply { entries.forEach { put(it.toJson()) } })
        return storage.write(root.toString())
    }

    private fun JSONObject.toCatalogEntry(): AndroidLocalAudioCatalogEntry {
        val uri = getString("uri").takeIf(String::isNotBlank) ?: error("Empty document URI")
        val identity = getString("identity").takeIf(String::isNotBlank) ?: error("Empty document identity")
        val track = getJSONObject("track").toLocalTrack(uri)
        val owner = getString("ownerRootUri")
        val duplicates = optJSONArray("alsoPresentInRoots")?.let { array ->
            (0 until array.length()).mapNotNull { index -> array.optString(index).takeIf(String::isNotBlank) }
        }.orEmpty()
        return AndroidLocalAudioCatalogEntry(
            document = AndroidLocalAudioDocument(
                documentId = getString("documentId"),
                identity = identity,
                uri = uri,
                displayName = track.title,
                mimeType = null,
                sizeBytes = optLongOrNull("sizeBytes"),
                lastModifiedMillis = optLongOrNull("lastModifiedMillis"),
            ),
            track = track,
            ownerRootUri = owner,
            alsoPresentInRoots = duplicates,
        )
    }

    private fun AndroidLocalAudioCatalogEntry.toJson(): JSONObject = JSONObject()
        .put("uri", document.uri)
        .put("documentId", document.documentId)
        .put("identity", document.identity)
        .putNullableLong("sizeBytes", document.sizeBytes)
        .putNullableLong("lastModifiedMillis", document.lastModifiedMillis)
        .put("ownerRootUri", ownerRootUri)
        .put("alsoPresentInRoots", JSONArray(alsoPresentInRoots))
        .put("track", track.toJson())

    private fun LazerTrack.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("durationMillis", durationMillis)
        .putNullableString("coverUrl", coverUrl)
        .putNullableString("albumArtist", albumArtist)
        .putNullableString("genre", genre)
        .putNullableInt("year", year)
        .putNullableInt("trackNumber", trackNumber)
        .putNullableInt("totalTracks", totalTracks)
        .putNullableInt("discNumber", discNumber)
        .putNullableInt("totalDiscs", totalDiscs)
        .put("replayGain", replayGain?.let { gain ->
            JSONObject()
                .putNullableDouble("trackGainDb", gain.trackGainDb)
                .putNullableDouble("trackPeak", gain.trackPeak)
                .putNullableDouble("albumGainDb", gain.albumGainDb)
                .putNullableDouble("albumPeak", gain.albumPeak)
        } ?: JSONObject.NULL)

    private fun JSONObject.toLocalTrack(uri: String): LazerTrack {
        val id = getLong("id")
        require(id < 0L)
        val gainJson = optJSONObject("replayGain")
        return LazerTrack(
            id = id,
            title = getString("title"),
            artist = optString("artist"),
            album = optString("album"),
            durationMillis = getLong("durationMillis").coerceAtLeast(0L),
            coverUrl = optStringOrNull("coverUrl"),
            source = LazerTrackSource.LocalFile(uri),
            replayGain = gainJson?.let {
                LazerReplayGainTags(
                    trackGainDb = it.optDoubleOrNull("trackGainDb"),
                    trackPeak = it.optDoubleOrNull("trackPeak"),
                    albumGainDb = it.optDoubleOrNull("albumGainDb"),
                    albumPeak = it.optDoubleOrNull("albumPeak"),
                )
            },
            albumArtist = optStringOrNull("albumArtist"),
            genre = optStringOrNull("genre"),
            year = optIntOrNull("year"),
            trackNumber = optIntOrNull("trackNumber"),
            totalTracks = optIntOrNull("totalTracks"),
            discNumber = optIntOrNull("discNumber"),
            totalDiscs = optIntOrNull("totalDiscs"),
        )
    }

    private fun LazerPickedAudioFile.toCatalogTrack(id: Long, uri: String) = LazerTrack(
        id = id,
        title = title,
        artist = artist,
        album = album,
        durationMillis = durationMillis.coerceAtLeast(0L),
        coverUrl = coverUrl,
        source = LazerTrackSource.LocalFile(uri),
        replayGain = replayGain,
        albumArtist = albumArtist,
        genre = genre,
        year = year,
        trackNumber = trackNumber,
        totalTracks = totalTracks,
        discNumber = discNumber,
        totalDiscs = totalDiscs,
    )

    private fun JSONObject.putNullableString(key: String, value: String?) = apply { put(key, value ?: JSONObject.NULL) }
    private fun JSONObject.putNullableLong(key: String, value: Long?) = apply { put(key, value ?: JSONObject.NULL) }
    private fun JSONObject.putNullableInt(key: String, value: Int?) = apply { put(key, value ?: JSONObject.NULL) }
    private fun JSONObject.putNullableDouble(key: String, value: Double?) = apply { put(key, value ?: JSONObject.NULL) }
    private fun JSONObject.optStringOrNull(key: String): String? = if (!has(key) || isNull(key)) null else optString(key)
    private fun JSONObject.optLongOrNull(key: String): Long? = if (!has(key) || isNull(key)) null else optLong(key)
    private fun JSONObject.optIntOrNull(key: String): Int? = if (!has(key) || isNull(key)) null else optInt(key)
    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (!has(key) || isNull(key)) null else optDouble(key)

    private companion object {
        const val TAG = "AndroidLocalLibrary"
        const val CATALOG_SCHEMA_VERSION = 1
        const val PROGRESS_UPDATE_INTERVAL = 100

        fun emptyLibrarySnapshot() = LazerLocalLibrarySnapshot(
            roots = emptyList(),
            tracks = emptyList(),
            isScanning = false,
            scannedFileCount = 0,
            issue = null,
        )
    }
}

/** Activity recreation reuses the application-scoped repository and its single catalog writer. */
object AndroidLocalAudioLibraryRegistry {
    @Volatile
    private var sharedInstance: AndroidLocalAudioLibraryRepository? = null

    fun get(context: Context): AndroidLocalAudioLibraryRepository = synchronized(this) {
        (sharedInstance ?: AndroidLocalAudioLibraryRepository(context.applicationContext).also {
            sharedInstance = it
        }).also(AndroidLocalAudioLibraryRepository::initialize)
    }
}

internal data class AndroidLocalAudioCatalog(
    val roots: List<LazerLocalLibraryRoot>,
    val entries: List<AndroidLocalAudioCatalogEntry>,
)

internal data class AndroidLocalAudioCatalogEntry(
    val document: AndroidLocalAudioDocument,
    val track: LazerTrack,
    val ownerRootUri: String,
    val alsoPresentInRoots: List<String> = emptyList(),
)

private class AndroidLocalAudioRootUnavailable(
    val rootUri: String,
    cause: Throwable? = null,
) : IOException("Selected local audio folder is unavailable", cause)

private class AndroidSafLocalAudioDocumentSource(context: Context) : AndroidLocalAudioDocumentSource {
    private val resolver = context.contentResolver

    override fun queryTreeRoot(treeUri: Uri): AndroidLocalAudioDocument {
        if (!DocumentsContract.isTreeUri(treeUri)) {
            throw IllegalArgumentException("Expected a persisted OpenDocumentTree URI")
        }
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        return queryDocument(
            treeUri,
            DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId),
            documentId,
        )
    }

    override fun forEachChild(
        treeUri: Uri,
        parentDocumentId: String,
        consume: (AndroidLocalAudioDocument) -> Unit,
    ) {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val cursor = resolver.query(uri, PROJECTION, null, null, null)
            ?: throw IOException("Document provider returned no children cursor")
        cursor.use { rows ->
            while (rows.moveToNext()) {
                val id = rows.stringOrNull(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    ?: throw IOException("Document provider returned a child without an ID")
                val document = rows.toDocument(
                    treeUri,
                    id,
                    DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                )
                consume(document)
            }
        }
    }

    private fun queryDocument(treeUri: Uri, uri: Uri, documentId: String): AndroidLocalAudioDocument {
        val cursor = resolver.query(uri, PROJECTION, null, null, null)
            ?: throw IOException("Document provider returned no document cursor")
        return cursor.use { rows ->
            if (!rows.moveToFirst()) throw IOException("Selected folder no longer exists")
            rows.toDocument(treeUri, documentId, uri.toString())
        }
    }

    private fun Cursor.toDocument(treeUri: Uri, documentId: String, uri: String) = AndroidLocalAudioDocument(
        documentId = documentId,
        identity = documentIdentity(treeUri.authority.orEmpty(), documentId),
        uri = uri,
        displayName = stringOrNull(OpenableColumns.DISPLAY_NAME).orEmpty(),
        mimeType = stringOrNull(DocumentsContract.Document.COLUMN_MIME_TYPE),
        sizeBytes = longOrNull(DocumentsContract.Document.COLUMN_SIZE),
        lastModifiedMillis = longOrNull(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
    )

    private fun Cursor.stringOrNull(column: String): String? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getString(index)
    }

    private fun Cursor.longOrNull(column: String): Long? {
        val index = getColumnIndex(column)
        return if (index < 0 || isNull(index)) null else getLong(index)
    }

    private companion object {
        val PROJECTION = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            OpenableColumns.DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
    }
}

private fun documentIdentity(authority: String, documentId: String): String = "$authority\u0000$documentId"

private fun treeScopedDocumentUri(treeUri: String, documentId: String): String = runCatching {
    DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeUri), documentId).toString()
}.getOrElse { treeUri }

internal interface AndroidLocalAudioLibraryStorage {
    fun read(): String?
    fun write(serializedCatalog: String): Boolean
}

internal class AndroidAtomicLocalAudioLibraryStorage(context: Context) : AndroidLocalAudioLibraryStorage {
    private val file = AtomicFile(File(context.filesDir, CATALOG_FILE_NAME))

    override fun read(): String? = if (!file.baseFile.exists()) null else
        file.openRead().bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

    override fun write(serializedCatalog: String): Boolean {
        var output: FileOutputStream? = null
        return runCatching {
            file.baseFile.parentFile?.mkdirs()
            val stream = file.startWrite()
            output = stream
            stream.write(serializedCatalog.toByteArray(StandardCharsets.UTF_8))
            stream.flush()
            file.finishWrite(stream)
            output = null
            if (File(file.baseFile.path + ".new").exists()) {
                throw IOException("AtomicFile did not commit the Android local library catalog")
            }
            true
        }.getOrElse { failure ->
            output?.let { stream -> runCatching { file.failWrite(stream) } }
            Log.w(TAG, "Could not persist Android local library catalog", failure)
            false
        }
    }

    private companion object {
        const val TAG = "AndroidLocalLibrary"
        const val CATALOG_FILE_NAME = "lazer-local-library-catalog.json"
    }
}
