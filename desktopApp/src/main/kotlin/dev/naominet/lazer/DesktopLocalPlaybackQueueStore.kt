package dev.naominet.lazer

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class DesktopLocalPlaybackQueueTrack(
    val absolutePath: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMillis: Long,
    val coverUrl: String? = null,
    val replayGain: DesktopReplayGainTags? = null,
    val cueSheetPath: String? = null,
    val cueTrackNumber: Int? = null,
    val cueStartFrame75: Long = 0L,
    val cueEndFrame75: Long = 0L,
    val audioSizeBytes: Long = -1L,
    val audioModifiedMillis: Long = -1L,
    val cueSheetSizeBytes: Long = -1L,
    val cueSheetModifiedMillis: Long = -1L,
)

internal data class DesktopLocalPlaybackQueueSnapshot(
    val tracks: List<DesktopLocalPlaybackQueueTrack>,
    val currentIndex: Int,
    val positionMillis: Long,
    val playMode: DesktopPlayMode,
)

/** Atomic, versioned snapshot for the desktop's local-file playback queue. */
internal class DesktopLocalPlaybackQueueStore(
    private val snapshotPath: Path = defaultSnapshotPath(),
) {
    @Synchronized
    fun load(): DesktopLocalPlaybackQueueSnapshot? = runCatching {
        val stored = readStoredQueue() ?: return@runCatching null
        val state = readProgressState()
        val snapshot = stored.snapshot
        if (state == null || state.queueKey != stored.queueKey || state.currentIndex !in snapshot.tracks.indices) {
            snapshot
        } else {
            snapshot.copy(
                currentIndex = state.currentIndex,
                positionMillis = state.positionMillis,
                playMode = state.playMode,
            )
        }
    }.getOrNull()

    private fun readStoredQueue(): StoredQueue? = runCatching {
        if (!Files.isRegularFile(snapshotPath) || Files.size(snapshotPath) > MAX_SNAPSHOT_BYTES) {
            return@runCatching null
        }
        val root = Json.parseToJsonElement(Files.readString(snapshotPath, StandardCharsets.UTF_8)).jsonObject
        val schemaVersion = root.int("schemaVersion")
        if (schemaVersion != SCHEMA_VERSION) return@runCatching null
        val tracks = root.requiredArray("tracks").map { parseTrack(it.jsonObject) }
        if (tracks.isEmpty() || tracks.size > MAX_TRACKS) return@runCatching null
        val currentIndex = root.int("currentIndex")
        val positionMillis = root.long("positionMillis")
        val playMode = root.string("playMode").let { name ->
            DesktopPlayMode.entries.firstOrNull { it.name == name } ?: return@runCatching null
        }
        if (currentIndex !in tracks.indices || positionMillis < 0L) return@runCatching null
        val snapshot = DesktopLocalPlaybackQueueSnapshot(tracks, currentIndex, positionMillis, playMode)
        StoredQueue(snapshot, root.optionalString("queueKey") ?: queueIdentityKey(tracks))
    }.getOrNull()

    @Synchronized
    fun save(snapshot: DesktopLocalPlaybackQueueSnapshot) {
        require(snapshot.tracks.isNotEmpty() && snapshot.tracks.size <= MAX_TRACKS)
        require(snapshot.currentIndex in snapshot.tracks.indices && snapshot.positionMillis >= 0L)
        val savedSnapshot = snapshot.copy(tracks = snapshot.tracks.map(::captureFileStamps))
        savedSnapshot.tracks.forEach(::validateTrack)
        val queueKey = queueIdentityKey(savedSnapshot.tracks)
        val json = buildJsonObject {
            put("schemaVersion", SCHEMA_VERSION)
            put("queueKey", queueKey)
            put("currentIndex", savedSnapshot.currentIndex)
            put("positionMillis", savedSnapshot.positionMillis)
            put("playMode", savedSnapshot.playMode.name)
            put("tracks", buildJsonArray { savedSnapshot.tracks.forEach { add(it.toJson()) } })
        }.toString()
        val encoded = json.toByteArray(StandardCharsets.UTF_8)
        require(encoded.size <= MAX_SNAPSHOT_BYTES) { "Local playback queue snapshot is too large" }
        writeAtomically(snapshotPath, encoded)
        writeProgressState(queueKey, savedSnapshot)
    }

    @Synchronized
    fun delete() {
        Files.deleteIfExists(snapshotPath)
        Files.deleteIfExists(progressPath())
    }

    /** Refreshes only transport state, retaining queue metadata and file stamps from the last save. */
    @Synchronized
    fun updateProgress(snapshot: DesktopLocalPlaybackQueueSnapshot) {
        require(snapshot.tracks.isNotEmpty() && snapshot.currentIndex in snapshot.tracks.indices && snapshot.positionMillis >= 0L)
        val stored = readStoredQueue()
        val previousIdentity = stored?.snapshot?.tracks?.map(::trackIdentity)
        val requestedIdentity = snapshot.tracks.map(::trackIdentity)
        val metadataChanged = stored?.snapshot?.tracks?.zip(snapshot.tracks)?.any { (old, current) ->
            old.withoutFileStamps() != current.withoutFileStamps()
        } ?: true
        if (stored == null || previousIdentity != requestedIdentity || metadataChanged) {
            save(snapshot)
        } else {
            writeProgressState(stored.queueKey, snapshot)
        }
    }

    private fun readProgressState(): ProgressState? = runCatching {
        val path = progressPath()
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_PROGRESS_BYTES) return@runCatching null
        val root = Json.parseToJsonElement(Files.readString(path, StandardCharsets.UTF_8)).jsonObject
        if (root.int("schemaVersion") != PROGRESS_SCHEMA_VERSION) return@runCatching null
        val positionMillis = root.long("positionMillis")
        if (positionMillis < 0L) return@runCatching null
        val playMode = root.string("playMode").let { name ->
            DesktopPlayMode.entries.firstOrNull { it.name == name } ?: return@runCatching null
        }
        ProgressState(root.string("queueKey"), root.int("currentIndex"), positionMillis, playMode)
    }.getOrNull()

    private fun writeProgressState(queueKey: String, snapshot: DesktopLocalPlaybackQueueSnapshot) {
        val state = buildJsonObject {
            put("schemaVersion", PROGRESS_SCHEMA_VERSION)
            put("queueKey", queueKey)
            put("currentIndex", snapshot.currentIndex)
            put("positionMillis", snapshot.positionMillis)
            put("playMode", snapshot.playMode.name)
        }.toString().toByteArray(StandardCharsets.UTF_8)
        require(state.size <= MAX_PROGRESS_BYTES)
        writeAtomically(progressPath(), state)
    }

    private fun writeAtomically(path: Path, encoded: ByteArray) {
        val target = path.toAbsolutePath().normalize()
        val parent = target.parent ?: throw IOException("Local playback queue has no parent directory")
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, "${target.fileName}-", ".tmp")
        try {
            Files.write(temporary, encoded)
            runCatching {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }.getOrElse {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun progressPath(): Path = snapshotPath.resolveSibling("${snapshotPath.fileName}.state")

    private fun parseTrack(json: JsonObject): DesktopLocalPlaybackQueueTrack {
        val track = DesktopLocalPlaybackQueueTrack(
            absolutePath = json.string("path"),
            title = json.string("title"),
            artist = json.string("artist"),
            album = json.string("album"),
            durationMillis = json.long("durationMillis"),
            coverUrl = json.optionalString("cover"),
            replayGain = json.readReplayGain(),
            cueSheetPath = json.optionalString("cueSheetPath"),
            cueTrackNumber = json["cueTrackNumber"]?.let { it.jsonPrimitive.content.toIntOrNull() },
            cueStartFrame75 = json.optionalLong("cueStartFrame75") ?: 0L,
            cueEndFrame75 = json.optionalLong("cueEndFrame75") ?: 0L,
            audioSizeBytes = json.optionalLong("audioSizeBytes") ?: -1L,
            audioModifiedMillis = json.optionalLong("audioModifiedMillis") ?: -1L,
            cueSheetSizeBytes = json.optionalLong("cueSheetSizeBytes") ?: -1L,
            cueSheetModifiedMillis = json.optionalLong("cueSheetModifiedMillis") ?: -1L,
        )
        validateTrack(track)
        return track
    }

    private fun validateTrack(track: DesktopLocalPlaybackQueueTrack) {
        require(track.absolutePath.length in 1..MAX_PATH_LENGTH && Path.of(track.absolutePath).isAbsolute)
        require(track.title.length <= MAX_TEXT_LENGTH && track.artist.length <= MAX_TEXT_LENGTH &&
            track.album.length <= MAX_TEXT_LENGTH)
        require(track.coverUrl == null || track.coverUrl.length <= MAX_COVER_LENGTH)
        require(track.durationMillis >= 0L)
        require(track.audioSizeBytes >= -1L && track.audioModifiedMillis >= -1L &&
            track.cueSheetSizeBytes >= -1L && track.cueSheetModifiedMillis >= -1L)
        val cueFields = listOf(track.cueSheetPath, track.cueTrackNumber, track.cueStartFrame75, track.cueEndFrame75)
        if (cueFields.any { it != null && it != 0L }) {
            require(track.cueSheetPath?.let { it.length in 1..MAX_PATH_LENGTH && Path.of(it).isAbsolute } == true)
            require(track.cueTrackNumber in 1..99 && track.cueStartFrame75 >= 0L)
            require(track.cueEndFrame75 == -1L || track.cueEndFrame75 > track.cueStartFrame75)
        } else {
            require(track.cueSheetPath == null && track.cueTrackNumber == null &&
                track.cueStartFrame75 == 0L && track.cueEndFrame75 == 0L)
        }
    }

    private fun DesktopLocalPlaybackQueueTrack.toJson() = buildJsonObject {
        put("path", absolutePath)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("durationMillis", durationMillis)
        put("cover", coverUrl ?: "")
        put("cueSheetPath", cueSheetPath ?: "")
        put("cueTrackNumber", cueTrackNumber?.let(::JsonPrimitive) ?: JsonNull)
        put("cueStartFrame75", cueStartFrame75)
        put("cueEndFrame75", cueEndFrame75)
        put("audioSizeBytes", audioSizeBytes)
        put("audioModifiedMillis", audioModifiedMillis)
        put("cueSheetSizeBytes", cueSheetSizeBytes)
        put("cueSheetModifiedMillis", cueSheetModifiedMillis)
        put("replayGainTrackGainDb", replayGain?.trackGainDb?.let(::JsonPrimitive) ?: JsonNull)
        put("replayGainTrackPeak", replayGain?.trackPeak?.let(::JsonPrimitive) ?: JsonNull)
        put("replayGainAlbumGainDb", replayGain?.albumGainDb?.let(::JsonPrimitive) ?: JsonNull)
        put("replayGainAlbumPeak", replayGain?.albumPeak?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun JsonObject.readReplayGain(): DesktopReplayGainTags? {
        val tags = DesktopReplayGainTags(
            trackGainDb = boundedNumber("replayGainTrackGainDb", -60.0, 24.0),
            trackPeak = boundedNumber("replayGainTrackPeak", 0.0000001, 16.0),
            albumGainDb = boundedNumber("replayGainAlbumGainDb", -60.0, 24.0),
            albumPeak = boundedNumber("replayGainAlbumPeak", 0.0000001, 16.0),
        )
        return tags.takeIf {
            it.trackGainDb != null || it.trackPeak != null || it.albumGainDb != null || it.albumPeak != null
        }
    }

    private fun JsonObject.boundedNumber(key: String, minimum: Double, maximum: Double): Double? =
        this[key]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it in minimum..maximum }

    private fun captureFileStamps(track: DesktopLocalPlaybackQueueTrack): DesktopLocalPlaybackQueueTrack {
        val audio = fileStamp(track.absolutePath)
        val cue = track.cueSheetPath?.let(::fileStamp)
        return track.copy(
            audioSizeBytes = track.audioSizeBytes.takeIf { it >= 0L } ?: audio?.first ?: -1L,
            audioModifiedMillis = track.audioModifiedMillis.takeIf { it >= 0L } ?: audio?.second ?: -1L,
            cueSheetSizeBytes = track.cueSheetSizeBytes.takeIf { it >= 0L } ?: cue?.first ?: -1L,
            cueSheetModifiedMillis = track.cueSheetModifiedMillis.takeIf { it >= 0L } ?: cue?.second ?: -1L,
        )
    }

    private fun fileStamp(path: String): Pair<Long, Long>? = runCatching {
        val file = Path.of(path)
        if (!Files.isRegularFile(file)) return@runCatching null
        Files.size(file) to Files.getLastModifiedTime(file).toMillis()
    }.getOrNull()

    private fun trackIdentity(track: DesktopLocalPlaybackQueueTrack): List<Any?> = listOf(
        Path.of(track.absolutePath).toAbsolutePath().normalize().toString(),
        track.cueSheetPath?.let { Path.of(it).toAbsolutePath().normalize().toString() },
        track.cueTrackNumber,
        track.cueStartFrame75,
        track.cueEndFrame75,
    )

    private fun DesktopLocalPlaybackQueueTrack.withoutFileStamps() = copy(
        audioSizeBytes = -1L,
        audioModifiedMillis = -1L,
        cueSheetSizeBytes = -1L,
        cueSheetModifiedMillis = -1L,
    )

    private fun queueIdentityKey(tracks: List<DesktopLocalPlaybackQueueTrack>): String {
        val identity = tracks.joinToString("\n") { track ->
            trackIdentity(track).joinToString("\u0000") { it?.toString().orEmpty() }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private data class StoredQueue(
        val snapshot: DesktopLocalPlaybackQueueSnapshot,
        val queueKey: String,
    )

    private data class ProgressState(
        val queueKey: String,
        val currentIndex: Int,
        val positionMillis: Long,
        val playMode: DesktopPlayMode,
    )

    private fun JsonObject.requiredArray(key: String): JsonArray = this[key]?.jsonArray
        ?: throw IOException("Missing '$key' in local playback queue")

    private fun JsonObject.string(key: String): String = this[key]?.jsonPrimitive?.content
        ?: throw IOException("Missing '$key' in local playback queue")

    private fun JsonObject.optionalString(key: String): String? =
        this[key]?.jsonPrimitive?.content?.takeIf(String::isNotEmpty)

    private fun JsonObject.int(key: String): Int = string(key).toIntOrNull()
        ?: throw IOException("Invalid '$key' in local playback queue")

    private fun JsonObject.long(key: String): Long = string(key).toLongOrNull()
        ?: throw IOException("Invalid '$key' in local playback queue")

    private fun JsonObject.optionalLong(key: String): Long? =
        this[key]?.jsonPrimitive?.content?.toLongOrNull()

    private companion object {
        const val SCHEMA_VERSION = 2
        const val MAX_TRACKS = 20_000
        const val MAX_SNAPSHOT_BYTES = 8L * 1_048_576L
        const val MAX_PROGRESS_BYTES = 16L * 1_024L
        const val PROGRESS_SCHEMA_VERSION = 1
        const val MAX_PATH_LENGTH = 32_768
        const val MAX_TEXT_LENGTH = 16_384
        const val MAX_COVER_LENGTH = 32_768

        fun defaultSnapshotPath(): Path = Path.of(
            System.getProperty("user.home"), ".lazer", "cache", "local-playback", "queue.json",
        )
    }
}
