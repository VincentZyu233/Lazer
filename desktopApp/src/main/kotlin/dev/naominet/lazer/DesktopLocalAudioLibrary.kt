package dev.naominet.lazer

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal data class DesktopLocalLibraryEntry(
    val absolutePath: String,
    val sizeBytes: Long,
    val modifiedMillis: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMillis: Long,
    val coverUrl: String?,
    val replayGain: DesktopReplayGainTags? = null,
    /** Non-null only for a virtual track cut from a single-file CUE sheet. */
    val cueSheetPath: String? = null,
    val cueTrackNumber: Int? = null,
    val cueStartFrame75: Long? = null,
    /** `-1` means this is the final CUE track and runs through physical EOF. */
    val cueEndFrame75: Long? = null,
    /** True when this cached entry needs a one-time metadata reread for a newer index schema. */
    val metadataNeedsRefresh: Boolean = false,
)

internal data class DesktopLocalLibraryScanResult(
    val entries: List<DesktopLocalLibraryEntry>,
    val scannedFiles: Int,
    val reusedMetadata: Int,
)

/** A versioned, line-oriented snapshot for the desktop local music library. */
internal class DesktopLocalAudioLibraryStore(
    private val indexPath: Path = defaultIndexPath(),
    private val metadataReader: (java.io.File) -> DesktopLocalAudioMetadata? = ::readLocalAudioMetadata,
    private val artworkWriter: (DesktopEmbeddedArtwork) -> String? = ::writeLocalAudioArtwork,
) {
    fun load(): List<DesktopLocalLibraryEntry> = runCatching {
        if (!Files.isRegularFile(indexPath)) return@runCatching emptyList()
        Files.newBufferedReader(indexPath, StandardCharsets.UTF_8).use { reader ->
            val header = reader.readLine()?.let(Json::parseToJsonElement)?.jsonObject
                ?: return@use emptyList()
            val schemaVersion = header["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull()
            if (schemaVersion == null || schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) {
                return@use emptyList()
            }
            val entries = ArrayList<DesktopLocalLibraryEntry>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) continue
                val json = Json.parseToJsonElement(line).jsonObject
                val path = json.requiredString("path")
                val size = json.requiredLong("size")
                val modified = json.requiredLong("modified")
                val title = json.requiredString("title")
                val artist = json.requiredString("artist")
                val album = json.requiredString("album")
                val duration = json.requiredLong("duration")
                val cover = json["cover"]?.jsonPrimitive?.content?.takeIf(String::isNotEmpty)
                val replayGain = if (schemaVersion >= REPLAY_GAIN_SCHEMA_VERSION) json.readReplayGain() else null
                val cueSheetPath = if (schemaVersion >= CUE_SCHEMA_VERSION) {
                    json["cueSheetPath"]?.jsonPrimitive?.content?.takeIf(String::isNotEmpty)
                } else {
                    null
                }
                val cueTrackNumber = if (schemaVersion >= CUE_SCHEMA_VERSION) {
                    json["cueTrackNumber"]?.jsonPrimitive?.content?.toIntOrNull()
                } else {
                    null
                }
                val cueStartFrame75 = if (schemaVersion >= CUE_SCHEMA_VERSION) {
                    json["cueStartFrame75"]?.jsonPrimitive?.content?.toLongOrNull()
                } else {
                    null
                }
                val cueEndFrame75 = if (schemaVersion >= CUE_SCHEMA_VERSION) {
                    json["cueEndFrame75"]?.jsonPrimitive?.content?.toLongOrNull()
                } else {
                    null
                }
                if (size < 0L || modified < 0L || duration < 0L) {
                    throw IOException("Invalid local library entry")
                }
                val cueFields = listOf(cueSheetPath, cueTrackNumber, cueStartFrame75, cueEndFrame75)
                if (cueFields.any { it != null } &&
                    (cueSheetPath.isNullOrBlank() || cueTrackNumber == null || cueTrackNumber !in 1..99 ||
                        cueStartFrame75 == null || cueStartFrame75 < 0L || cueEndFrame75 == null ||
                        (cueEndFrame75 != -1L && cueEndFrame75 <= cueStartFrame75))
                ) {
                    throw IOException("Invalid CUE range in local library index")
                }
                entries += DesktopLocalLibraryEntry(
                    absolutePath = path,
                    sizeBytes = size,
                    modifiedMillis = modified,
                    title = title,
                    artist = artist,
                    album = album,
                    durationMillis = duration,
                    coverUrl = cover,
                    replayGain = replayGain,
                    cueSheetPath = cueSheetPath,
                    cueTrackNumber = cueTrackNumber,
                    cueStartFrame75 = cueStartFrame75,
                    cueEndFrame75 = cueEndFrame75,
                    metadataNeedsRefresh = schemaVersion == LEGACY_SCHEMA_VERSION ||
                        (schemaVersion < DSD_ID3_ARTWORK_SCHEMA_VERSION &&
                            path.substringAfterLast('.', "").lowercase() in setOf("dsf", "dff")) ||
                        (schemaVersion < WAVE_ID3_ARTWORK_SCHEMA_VERSION &&
                            path.substringAfterLast('.', "").lowercase() == "wav") ||
                        (schemaVersion < WAVE_REPLAY_GAIN_SCHEMA_VERSION &&
                            path.substringAfterLast('.', "").lowercase() == "wav"),
                )
            }
            entries
        }
    }.getOrDefault(emptyList())

    /** Scans all roots and replaces the snapshot only after every directory completed successfully. */
    fun scanAndCommit(
        roots: List<Path>,
        previousEntries: List<DesktopLocalLibraryEntry> = load(),
        checkActive: () -> Unit = {},
        onProgress: (Int) -> Unit = {},
    ): DesktopLocalLibraryScanResult {
        // CUE entries share their backing path, but their title and range are virtual metadata.
        // Only whole-file entries can be reused as source-file metadata on a later scan.
        val previousByPath = previousEntries.asSequence()
            .filter { it.cueSheetPath == null }
            .associateBy { pathKey(Path.of(it.absolutePath)) }
        val found = LinkedHashMap<Path, DesktopLocalLibraryEntry>()
        val cueSheets = LinkedHashMap<Path, MutableList<Path>>()
        var scannedFiles = 0
        var reusedMetadata = 0
        val uniqueRoots = roots.map { it.toAbsolutePath().normalize() }.distinct()

        for (root in uniqueRoots) {
            checkActive()
            if (Files.isSymbolicLink(root) || !Files.isDirectory(root)) {
                throw IOException("Local library root is unavailable: $root")
            }
            Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkActive()
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    checkActive()
                    if (!attrs.isRegularFile || Files.isSymbolicLink(file)) {
                        return FileVisitResult.CONTINUE
                    }
                    val absolute = file.toAbsolutePath().normalize()
                    if (file.fileName.toString().endsWith(".cue", ignoreCase = true)) {
                        cueSheets.getOrPut(absolute) { mutableListOf() }.add(root)
                        return FileVisitResult.CONTINUE
                    }
                    if (!isSupportedLocalAudioFile(file.toFile())) return FileVisitResult.CONTINUE
                    val path = absolute.toString()
                    val key = pathKey(absolute)
                    if (key !in found) {
                        val old = previousByPath[key]
                        val modifiedMillis = attrs.lastModifiedTime().toMillis()
                        val entry = if (old != null && !old.metadataNeedsRefresh &&
                            old.sizeBytes == attrs.size() && old.modifiedMillis == modifiedMillis
                        ) {
                            reusedMetadata += 1
                            old.copy(absolutePath = path)
                        } else {
                            val metadata = metadataReader(file.toFile())
                            DesktopLocalLibraryEntry(
                                absolutePath = path,
                                sizeBytes = attrs.size(),
                                modifiedMillis = modifiedMillis,
                                title = metadata?.title?.takeIf(String::isNotBlank)
                                    ?: file.fileName.toString()
                                        .substringBeforeLast('.', file.fileName.toString())
                                        .ifBlank { file.fileName.toString() },
                                artist = metadata?.artist.orEmpty(),
                                album = metadata?.album.orEmpty(),
                                durationMillis = metadata?.durationMillis?.coerceAtLeast(0L) ?: 0L,
                                coverUrl = metadata?.embeddedArtwork?.let(artworkWriter),
                                replayGain = metadata?.replayGain,
                            )
                        }
                        found[key] = entry
                        scannedFiles += 1
                        if (scannedFiles % PROGRESS_INTERVAL == 0) onProgress(scannedFiles)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult {
                    throw IOException("Could not read local library path: $file", exc)
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw IOException("Could not finish local library directory: $dir", exc)
                    checkActive()
                    return FileVisitResult.CONTINUE
                }
            })
        }

        checkActive()
        val cueCandidates = cueSheets.flatMap { (cuePath, rootsContainingCue) ->
            checkActive()
            val document = runCatching {
                if (Files.isSymbolicLink(cuePath) || Files.size(cuePath) > MAX_CUE_SHEET_BYTES) {
                    return@runCatching null
                }
                parseDesktopCueSheet(Files.readString(cuePath, StandardCharsets.UTF_8))
            }.getOrNull() ?: return@flatMap emptyList()
            val sourcePath = resolveCueSourcePath(cuePath, document.fileName, rootsContainingCue)
                ?: return@flatMap emptyList()
            val source = found[pathKey(sourcePath)] ?: return@flatMap emptyList()
            if (!cueTypeMatches(document.fileType, sourcePath) || source.durationMillis <= 0L) {
                return@flatMap emptyList()
            }
            val starts = document.tracks.map { cueFrameToMillis(it.index01CueFrames) ?: return@flatMap emptyList() }
            if (starts.any { it >= source.durationMillis }) return@flatMap emptyList()
            val durations = starts.indices.map { index ->
                val endMillis = starts.getOrNull(index + 1) ?: source.durationMillis
                endMillis - starts[index]
            }
            if (durations.any { it <= 0L }) return@flatMap emptyList()
            listOf(ResolvedCueSheet(cuePath, sourcePath, document, durations))
        }
        val cuesBySource = cueCandidates.groupBy { pathKey(it.sourcePath) }
        val entries = found.flatMap { (sourcePath, source) ->
            val cues = cuesBySource[pathKey(sourcePath)].orEmpty()
            if (cues.size != 1) {
                listOf(source)
            } else {
                val cue = cues.single()
                cue.document.tracks.mapIndexed { index, track ->
                    val nextTrackStart = cue.document.tracks.getOrNull(index + 1)?.index01CueFrames
                    DesktopLocalLibraryEntry(
                        absolutePath = source.absolutePath,
                        sizeBytes = source.sizeBytes,
                        modifiedMillis = source.modifiedMillis,
                        title = track.title.orEmpty(),
                        artist = track.performer ?: cue.document.performer ?: source.artist,
                        album = cue.document.title ?: source.album,
                        durationMillis = cue.durationsMillis[index],
                        coverUrl = source.coverUrl,
                        // Whole-image ReplayGain applies to the image, not independently to each
                        // virtual track, so it is deliberately not copied to the CUE entries.
                        replayGain = null,
                        cueSheetPath = cue.cuePath.toString(),
                        cueTrackNumber = track.number,
                        cueStartFrame75 = track.index01CueFrames,
                        cueEndFrame75 = nextTrackStart ?: -1L,
                    )
                }
            }
        }.sortedWith(
            compareBy<DesktopLocalLibraryEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.absolutePath }
                .thenBy { it.cueTrackNumber ?: 0 },
        )
        if (scannedFiles % PROGRESS_INTERVAL != 0) onProgress(scannedFiles)
        checkActive()
        save(entries)
        return DesktopLocalLibraryScanResult(entries, scannedFiles, reusedMetadata)
    }

    private fun save(entries: List<DesktopLocalLibraryEntry>) {
        Files.createDirectories(indexPath.parent)
        val temporary = indexPath.resolveSibling("${indexPath.fileName}.tmp")
        try {
            Files.newBufferedWriter(temporary, StandardCharsets.UTF_8).use { writer ->
                writer.write(buildJsonObject { put("schemaVersion", SCHEMA_VERSION) }.toString())
                writer.newLine()
                entries.forEach { entry ->
                    writer.write(entry.toJsonLine())
                    writer.newLine()
                }
            }
            runCatching {
                Files.move(
                    temporary,
                    indexPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                Files.move(temporary, indexPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun DesktopLocalLibraryEntry.toJsonLine() = buildJsonObject {
        put("path", absolutePath)
        put("size", sizeBytes)
        put("modified", modifiedMillis)
        put("title", title)
        put("artist", artist)
        put("album", album)
        put("duration", durationMillis)
        put("cover", coverUrl.orEmpty())
        put("replayGainTrackGainDb", replayGain?.trackGainDb?.let { JsonPrimitive(it) } ?: JsonNull)
        put("replayGainTrackPeak", replayGain?.trackPeak?.let { JsonPrimitive(it) } ?: JsonNull)
        put("replayGainAlbumGainDb", replayGain?.albumGainDb?.let { JsonPrimitive(it) } ?: JsonNull)
        put("replayGainAlbumPeak", replayGain?.albumPeak?.let { JsonPrimitive(it) } ?: JsonNull)
        put("cueSheetPath", cueSheetPath.orEmpty())
        put("cueTrackNumber", cueTrackNumber?.let { JsonPrimitive(it) } ?: JsonNull)
        put("cueStartFrame75", cueStartFrame75?.let { JsonPrimitive(it) } ?: JsonNull)
        put("cueEndFrame75", cueEndFrame75?.let { JsonPrimitive(it) } ?: JsonNull)
    }.toString()

    private fun kotlinx.serialization.json.JsonObject.readReplayGain(): DesktopReplayGainTags? {
        val trackGain = optionalReplayGainNumber("replayGainTrackGainDb")?.takeIf { it in -60.0..24.0 }
        val trackPeak = optionalReplayGainNumber("replayGainTrackPeak")?.takeIf { it > 0.0 && it <= 16.0 }
        val albumGain = optionalReplayGainNumber("replayGainAlbumGainDb")?.takeIf { it in -60.0..24.0 }
        val albumPeak = optionalReplayGainNumber("replayGainAlbumPeak")?.takeIf { it > 0.0 && it <= 16.0 }
        return DesktopReplayGainTags(trackGain, trackPeak, albumGain, albumPeak)
            .takeIf { it.trackGainDb != null || it.trackPeak != null || it.albumGainDb != null || it.albumPeak != null }
    }

    private fun kotlinx.serialization.json.JsonObject.optionalReplayGainNumber(key: String): Double? =
        this[key]?.jsonPrimitive?.doubleOrNull?.takeIf(Double::isFinite)

    private fun pathKey(path: Path): Path = path.toAbsolutePath().normalize()

    private data class ResolvedCueSheet(
        val cuePath: Path,
        val sourcePath: Path,
        val document: DesktopCueSheetDocument,
        val durationsMillis: List<Long>,
    )

    private fun resolveCueSourcePath(cuePath: Path, cueFileName: String, roots: List<Path>): Path? {
        if (cueFileName.isBlank() || '\u0000' in cueFileName ||
            cueFileName.startsWith('\\') || cueFileName.startsWith('/') ||
            cueFileName.matches(Regex("^[A-Za-z]:.*"))
        ) return null
        val portableName = cueFileName.replace('\\', java.io.File.separatorChar)
            .replace('/', java.io.File.separatorChar)
        val relative = runCatching { Path.of(portableName) }.getOrNull() ?: return null
        if (relative.isAbsolute) return null
        val resolved = cuePath.parent.resolve(relative).toAbsolutePath().normalize()
        val eligibleRoots = roots.asSequence()
            .map { it.toAbsolutePath().normalize() }
            .filter { cuePath.startsWith(it) && resolved.startsWith(it) }
            .toList()
        if (eligibleRoots.isEmpty() || !Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)) return null
        if (hasSymbolicLinkBetween(cuePath.parent, resolved)) return null
        return resolved
    }

    private fun hasSymbolicLinkBetween(fromDirectory: Path, target: Path): Boolean {
        val relative = runCatching { fromDirectory.relativize(target) }.getOrNull() ?: return true
        var current = fromDirectory
        for (part in relative) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) return true
        }
        return false
    }

    private fun cueTypeMatches(fileType: String, sourcePath: Path): Boolean = when (fileType.uppercase()) {
        "WAVE" -> sourcePath.fileName.toString().endsWith(".wav", ignoreCase = true)
        "FLAC" -> sourcePath.fileName.toString().endsWith(".flac", ignoreCase = true)
        else -> false
    }

    private fun cueFrameToMillis(frames: Long): Long? {
        if (frames < 0L) return null
        val seconds = frames / CUE_FRAMES_PER_SECOND
        if (seconds > Long.MAX_VALUE / 1_000L) return null
        return seconds * 1_000L + (frames % CUE_FRAMES_PER_SECOND) * 1_000L / CUE_FRAMES_PER_SECOND
    }

    private fun kotlinx.serialization.json.JsonObject.requiredString(key: String): String =
        this[key]?.jsonPrimitive?.content ?: throw IOException("Missing '$key' in local library index")

    private fun kotlinx.serialization.json.JsonObject.requiredLong(key: String): Long =
        this[key]?.jsonPrimitive?.content?.toLongOrNull()
            ?: throw IOException("Invalid '$key' in local library index")

    private companion object {
        const val LEGACY_SCHEMA_VERSION = 1
        const val REPLAY_GAIN_SCHEMA_VERSION = 2
        const val CUE_SCHEMA_VERSION = 3
        const val DSD_ID3_SCHEMA_VERSION = 4
        const val DSD_ID3_ARTWORK_SCHEMA_VERSION = 5
        const val WAVE_ID3_ARTWORK_SCHEMA_VERSION = 6
        const val WAVE_REPLAY_GAIN_SCHEMA_VERSION = 7
        const val SCHEMA_VERSION = WAVE_REPLAY_GAIN_SCHEMA_VERSION
        val SUPPORTED_SCHEMA_VERSIONS = setOf(
            LEGACY_SCHEMA_VERSION,
            REPLAY_GAIN_SCHEMA_VERSION,
            CUE_SCHEMA_VERSION,
            DSD_ID3_SCHEMA_VERSION,
            DSD_ID3_ARTWORK_SCHEMA_VERSION,
            WAVE_ID3_ARTWORK_SCHEMA_VERSION,
            WAVE_REPLAY_GAIN_SCHEMA_VERSION,
        )
        const val PROGRESS_INTERVAL = 128
        const val CUE_FRAMES_PER_SECOND = 75L
        const val MAX_CUE_SHEET_BYTES = 4L * 1_048_576L

        fun defaultIndexPath(): Path = Path.of(
            System.getProperty("user.home"), ".lazer", "cache", "local-library", "index.jsonl",
        )
    }
}
