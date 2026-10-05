package dev.naominet.lazer

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLocalAudioLibraryTest {
    @Test
    fun `loads schema one entries and forces metadata reread before writing current schema`() {
        val directory = Files.createTempDirectory("local-library-schema-migration")
        try {
            val root = Files.createDirectory(directory.resolve("music"))
            val audio = Files.write(root.resolve("legacy.wav"), byteArrayOf(1, 2, 3))
            val index = directory.resolve("index.jsonl")
            val legacyHeader = buildJsonObject { put("schemaVersion", 1) }.toString()
            val legacyEntry = buildJsonObject {
                put("path", audio.toAbsolutePath().normalize().toString())
                put("size", Files.size(audio))
                put("modified", Files.getLastModifiedTime(audio).toMillis())
                put("title", "Legacy title")
                put("artist", "Legacy artist")
                put("album", "Legacy album")
                put("duration", 1_000L)
                put("cover", "")
            }.toString()
            Files.writeString(index, "$legacyHeader\n$legacyEntry\n")

            val tags = DesktopReplayGainTags(-5.5, 0.75, -6.0, 0.8)
            val metadataReads = AtomicInteger()
            val store = DesktopLocalAudioLibraryStore(
                indexPath = index,
                metadataReader = {
                    metadataReads.incrementAndGet()
                    DesktopLocalAudioMetadata(title = "Refreshed title", replayGain = tags)
                },
                artworkWriter = { null },
            )
            val legacy = store.load().single()
            assertTrue(legacy.metadataNeedsRefresh)
            assertEquals("Legacy title", legacy.title)
            assertEquals(null, legacy.replayGain)

            val refreshed = store.scanAndCommit(listOf(root), previousEntries = listOf(legacy))
            assertEquals(1, metadataReads.get())
            assertEquals(0, refreshed.reusedMetadata)
            assertEquals(tags, refreshed.entries.single().replayGain)
            assertFalse(refreshed.entries.single().metadataNeedsRefresh)

            val header = Files.readAllLines(index).first()
            assertTrue(header.contains("\"schemaVersion\":9"))
            assertEquals(tags, DesktopLocalAudioLibraryStore(index).load().single().replayGain)
            assertFalse(DesktopLocalAudioLibraryStore(index).load().single().metadataNeedsRefresh)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `schema two rereads APE-capable metadata and preserves parsed ReplayGain`() {
        val directory = Files.createTempDirectory("local-library-schema-two")
        try {
            val root = Files.createDirectory(directory.resolve("music"))
            val audio = Files.write(root.resolve("album.flac"), byteArrayOf(1, 2, 3))
            val index = directory.resolve("index.jsonl")
            val oldHeader = buildJsonObject { put("schemaVersion", 2) }.toString()
            val oldEntry = buildJsonObject {
                put("path", audio.toAbsolutePath().normalize().toString())
                put("size", Files.size(audio))
                put("modified", Files.getLastModifiedTime(audio).toMillis())
                put("title", "Album")
                put("artist", "Artist")
                put("album", "Record")
                put("duration", 60_000L)
                put("cover", "")
                put("replayGainTrackGainDb", -5.0)
                put("replayGainTrackPeak", 0.9)
                put("replayGainAlbumGainDb", -6.0)
                put("replayGainAlbumPeak", 0.95)
            }.toString()
            Files.writeString(index, "$oldHeader\n$oldEntry\n")
            val metadataReads = AtomicInteger()
            val tags = DesktopReplayGainTags(-5.0, 0.9, -6.0, 0.95)
            val store = DesktopLocalAudioLibraryStore(
                indexPath = index,
                metadataReader = {
                    metadataReads.incrementAndGet()
                    DesktopLocalAudioMetadata(title = "Refreshed FLAC", replayGain = tags)
                },
                artworkWriter = { null },
            )

            val loaded = store.load().single()
            assertEquals(DesktopReplayGainTags(-5.0, 0.9, -6.0, 0.95), loaded.replayGain)
            assertTrue(loaded.metadataNeedsRefresh)
            val migrated = store.scanAndCommit(listOf(root), previousEntries = listOf(loaded))
            assertEquals(0, migrated.reusedMetadata)
            assertEquals(1, metadataReads.get())
            assertEquals("Refreshed FLAC", migrated.entries.single().title)
            assertEquals(tags, migrated.entries.single().replayGain)
            assertTrue(Files.readAllLines(index).first().contains("\"schemaVersion\":9"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `schema four rereads every APE-capable format before writing current schema`() {
        val directory = Files.createTempDirectory("local-library-dsd-id3-migration")
        try {
            val root = Files.createDirectory(directory.resolve("music"))
            val dsd = Files.write(root.resolve("album.dsf"), byteArrayOf(1, 2, 3))
            val dff = Files.write(root.resolve("album.dff"), byteArrayOf(7, 8, 9))
            val pcm = Files.write(root.resolve("track.flac"), byteArrayOf(4, 5, 6))
            val index = directory.resolve("index.jsonl")
            fun entry(path: java.nio.file.Path, title: String) = buildJsonObject {
                put("path", path.toAbsolutePath().normalize().toString())
                put("size", Files.size(path))
                put("modified", Files.getLastModifiedTime(path).toMillis())
                put("title", title)
                put("artist", "Artist")
                put("album", "Album")
                put("duration", 1_000L)
                put("cover", "")
                put("replayGainTrackGainDb", -5.0)
            }.toString()
            Files.writeString(
                index,
                "${buildJsonObject { put("schemaVersion", 4) }}\n" +
                    entry(dsd, "Old DSF title") + "\n" + entry(dff, "Old DFF title") + "\n" +
                    entry(pcm, "Cached PCM title") + "\n",
            )

            val metadataReads = AtomicInteger()
            val store = DesktopLocalAudioLibraryStore(
                indexPath = index,
                metadataReader = { file ->
                    metadataReads.incrementAndGet()
                    DesktopLocalAudioMetadata(title = "Refreshed ${file.extension}")
                },
                artworkWriter = { null },
            )
            val loaded = store.load()
            assertTrue(loaded.single { it.absolutePath.endsWith(".dsf") }.metadataNeedsRefresh)
            assertTrue(loaded.single { it.absolutePath.endsWith(".dff") }.metadataNeedsRefresh)
            assertTrue(loaded.single { it.absolutePath.endsWith(".flac") }.metadataNeedsRefresh)

            val migrated = store.scanAndCommit(listOf(root), previousEntries = loaded)
            assertEquals(3, metadataReads.get())
            assertEquals(0, migrated.reusedMetadata)
            assertEquals("Refreshed dsf", migrated.entries.single { it.absolutePath.endsWith(".dsf") }.title)
            assertEquals("Refreshed dff", migrated.entries.single { it.absolutePath.endsWith(".dff") }.title)
            assertEquals("Refreshed flac", migrated.entries.single { it.absolutePath.endsWith(".flac") }.title)
            assertTrue(Files.readAllLines(index).first().contains("\"schemaVersion\":9"))

            val migratedEntries = store.load()
            assertTrue(migratedEntries.none { it.metadataNeedsRefresh })
            store.scanAndCommit(listOf(root), previousEntries = migratedEntries)
            assertEquals(3, metadataReads.get())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `schema five rereads every APE-capable format before writing current schema`() {
        val directory = Files.createTempDirectory("local-library-wave-id3-migration")
        try {
            val root = Files.createDirectory(directory.resolve("music"))
            val wav = Files.write(root.resolve("track.wav"), byteArrayOf(1, 2, 3))
            val flac = Files.write(root.resolve("track.flac"), byteArrayOf(4, 5, 6))
            val dsd = Files.write(root.resolve("album.dsf"), byteArrayOf(7, 8, 9))
            val index = directory.resolve("index.jsonl")
            fun entry(path: java.nio.file.Path, title: String) = buildJsonObject {
                put("path", path.toAbsolutePath().normalize().toString())
                put("size", Files.size(path))
                put("modified", Files.getLastModifiedTime(path).toMillis())
                put("title", title)
                put("artist", "Artist")
                put("album", "Album")
                put("duration", 1_000L)
                put("cover", "")
            }.toString()
            Files.writeString(
                index,
                "${buildJsonObject { put("schemaVersion", 5) }}\n" +
                    entry(wav, "Old WAV title") + "\n" + entry(flac, "Cached FLAC title") + "\n" +
                    entry(dsd, "Cached DSD title") + "\n",
            )

            val metadataReads = AtomicInteger()
            val store = DesktopLocalAudioLibraryStore(
                indexPath = index,
                metadataReader = { file ->
                    metadataReads.incrementAndGet()
                    DesktopLocalAudioMetadata(title = "Refreshed ${file.extension}")
                },
                artworkWriter = { null },
            )
            val loaded = store.load()
            assertTrue(loaded.single { it.absolutePath.endsWith(".wav") }.metadataNeedsRefresh)
            assertTrue(loaded.single { it.absolutePath.endsWith(".flac") }.metadataNeedsRefresh)
            assertTrue(loaded.single { it.absolutePath.endsWith(".dsf") }.metadataNeedsRefresh)

            val migrated = store.scanAndCommit(listOf(root), previousEntries = loaded)
            assertEquals(3, metadataReads.get())
            assertEquals(0, migrated.reusedMetadata)
            assertEquals("Refreshed wav", migrated.entries.single { it.absolutePath.endsWith(".wav") }.title)
            assertEquals("Refreshed flac", migrated.entries.single { it.absolutePath.endsWith(".flac") }.title)
            assertEquals("Refreshed dsf", migrated.entries.single { it.absolutePath.endsWith(".dsf") }.title)
            assertTrue(Files.readAllLines(index).first().contains("\"schemaVersion\":9"))

            val migratedEntries = store.load()
            assertTrue(migratedEntries.none { it.metadataNeedsRefresh })
            store.scanAndCommit(listOf(root), previousEntries = migratedEntries)
            assertEquals(3, metadataReads.get())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `schema six rereads every APE-capable format and saves refreshed ReplayGain`() {
        val directory = Files.createTempDirectory("local-library-wave-replaygain-migration")
        try {
            val root = Files.createDirectory(directory.resolve("music"))
            val wav = Files.write(root.resolve("track.wav"), byteArrayOf(1, 2, 3))
            val flac = Files.write(root.resolve("track.flac"), byteArrayOf(4, 5, 6))
            val dsd = Files.write(root.resolve("album.dsf"), byteArrayOf(7, 8, 9))
            val index = directory.resolve("index.jsonl")
            fun entry(path: java.nio.file.Path, title: String, gain: Double) = buildJsonObject {
                put("path", path.toAbsolutePath().normalize().toString())
                put("size", Files.size(path))
                put("modified", Files.getLastModifiedTime(path).toMillis())
                put("title", title)
                put("artist", "Cached artist")
                put("album", "Cached album")
                put("duration", 1_000L)
                put("cover", "cached:$title")
                put("replayGainTrackGainDb", gain)
                put("replayGainTrackPeak", 0.8)
                put("replayGainAlbumGainDb", gain - 1.0)
                put("replayGainAlbumPeak", 0.9)
                put("cueSheetPath", "")
                put("cueTrackNumber", kotlinx.serialization.json.JsonNull)
                put("cueStartFrame75", kotlinx.serialization.json.JsonNull)
                put("cueEndFrame75", kotlinx.serialization.json.JsonNull)
            }.toString()
            Files.writeString(
                index,
                "${buildJsonObject { put("schemaVersion", 6) }}\n" +
                    entry(wav, "Old WAV title", -1.0) + "\n" +
                    entry(flac, "Cached FLAC title", -2.0) + "\n" +
                    entry(dsd, "Cached DSD title", -3.0) + "\n",
            )

            val metadataReads = mutableListOf<String>()
            val refreshedTags = DesktopReplayGainTags(-7.0, 0.7, -8.0, 0.8)
            val store = DesktopLocalAudioLibraryStore(
                indexPath = index,
                metadataReader = { file ->
                    metadataReads += file.extension.lowercase()
                    DesktopLocalAudioMetadata(
                        title = "Refreshed WAV title",
                        artist = "WAV artist",
                        album = "WAV album",
                        replayGain = refreshedTags,
                    )
                },
                artworkWriter = { null },
            )
            val loaded = store.load()
            assertTrue(loaded.single { it.absolutePath.endsWith(".wav") }.metadataNeedsRefresh)
            assertTrue(loaded.single { it.absolutePath.endsWith(".flac") }.metadataNeedsRefresh)
            assertTrue(loaded.single { it.absolutePath.endsWith(".dsf") }.metadataNeedsRefresh)

            val migrated = store.scanAndCommit(listOf(root), previousEntries = loaded)
            assertEquals(listOf("dsf", "flac", "wav"), metadataReads.sorted())
            assertEquals(0, migrated.reusedMetadata)
            val refreshedWav = migrated.entries.single { it.absolutePath.endsWith(".wav") }
            val cachedFlac = migrated.entries.single { it.absolutePath.endsWith(".flac") }
            val cachedDsd = migrated.entries.single { it.absolutePath.endsWith(".dsf") }
            assertEquals("Refreshed WAV title", refreshedWav.title)
            assertEquals(refreshedTags, refreshedWav.replayGain)
            assertEquals("Refreshed WAV title", cachedFlac.title)
            assertEquals("Refreshed WAV title", cachedDsd.title)
            assertEquals(refreshedTags, cachedFlac.replayGain)
            assertEquals(refreshedTags, cachedDsd.replayGain)
            assertTrue(Files.readAllLines(index).first().contains("\"schemaVersion\":9"))

            val migratedEntries = store.load()
            assertTrue(migratedEntries.none { it.metadataNeedsRefresh })
            store.scanAndCommit(listOf(root), previousEntries = migratedEntries)
            assertEquals(listOf("dsf", "flac", "wav"), metadataReads.sorted())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `schema seven refreshes cached metadata once for every APEv2-supported format`() {
        val directory = Files.createTempDirectory("local-library-ape-v2-migration")
        try {
            val root = Files.createDirectory(directory.resolve("music"))
            val paths = listOf("wav", "flac", "dsf", "dff").map { extension ->
                Files.write(root.resolve("track.$extension"), byteArrayOf(1, 2, 3))
            }
            val index = directory.resolve("index.jsonl")
            val oldEntries = paths.mapIndexed { position, path ->
                buildJsonObject {
                    put("path", path.toAbsolutePath().normalize().toString())
                    put("size", Files.size(path))
                    put("modified", Files.getLastModifiedTime(path).toMillis())
                    put("title", "Cached $position")
                    put("artist", "Cached artist")
                    put("album", "Cached album")
                    put("duration", 1_000L)
                    put("cover", "")
                    put("cueSheetPath", "")
                    put("cueTrackNumber", kotlinx.serialization.json.JsonNull)
                    put("cueStartFrame75", kotlinx.serialization.json.JsonNull)
                    put("cueEndFrame75", kotlinx.serialization.json.JsonNull)
                }.toString()
            }
            Files.writeString(
                index,
                "${buildJsonObject { put("schemaVersion", 7) }}\n${oldEntries.joinToString("\n")}\n",
            )

            val metadataReads = mutableListOf<String>()
            val store = DesktopLocalAudioLibraryStore(
                indexPath = index,
                metadataReader = { file ->
                    metadataReads += file.extension.lowercase()
                    DesktopLocalAudioMetadata(title = "APE-aware ${file.extension.lowercase()}")
                },
                artworkWriter = { null },
            )
            val loaded = store.load()
            assertTrue(loaded.all { it.metadataNeedsRefresh })

            val migrated = store.scanAndCommit(listOf(root), previousEntries = loaded)
            assertEquals(listOf("dff", "dsf", "flac", "wav"), metadataReads.sorted())
            assertEquals(0, migrated.reusedMetadata)
            assertTrue(migrated.entries.all { it.title == "APE-aware ${it.absolutePath.substringAfterLast('.')}" })
            assertTrue(Files.readAllLines(index).first().contains("\"schemaVersion\":9"))

            val migratedEntries = store.load()
            assertTrue(migratedEntries.none { it.metadataNeedsRefresh })
            store.scanAndCommit(listOf(root), previousEntries = migratedEntries)
            assertEquals(4, metadataReads.size)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `schema eight refreshes ID3-capable metadata once for unsynchronization`() {
        val directory = Files.createTempDirectory("local-library-id3-unsync-migration")
        try {
            val root = Files.createDirectory(directory.resolve("music"))
            val paths = listOf("wav", "flac", "dsf", "dff").associateWith { extension ->
                Files.write(root.resolve("track.$extension"), byteArrayOf(1, 2, 3))
            }
            val index = directory.resolve("index.jsonl")
            val entries = paths.map { (extension, path) ->
                buildJsonObject {
                    put("path", path.toAbsolutePath().normalize().toString())
                    put("size", Files.size(path))
                    put("modified", Files.getLastModifiedTime(path).toMillis())
                    put("title", "Cached $extension")
                    put("artist", "Cached artist")
                    put("album", "Cached album")
                    put("duration", 1_000L)
                    put("cover", "")
                }.toString()
            }
            Files.writeString(
                index,
                "${buildJsonObject { put("schemaVersion", 8) }}\n${entries.joinToString("\n")}\n",
            )

            val metadataReads = mutableListOf<String>()
            val store = DesktopLocalAudioLibraryStore(
                indexPath = index,
                metadataReader = { file ->
                    metadataReads += file.extension.lowercase()
                    DesktopLocalAudioMetadata(title = "Unsync-aware ${file.extension.lowercase()}")
                },
                artworkWriter = { null },
            )
            val loaded = store.load()
            assertTrue(loaded.filter { it.absolutePath.endsWith(".flac") }.none { it.metadataNeedsRefresh })
            assertEquals(
                setOf("wav", "dsf", "dff"),
                loaded.filter { it.metadataNeedsRefresh }.map { it.absolutePath.substringAfterLast('.') }.toSet(),
            )

            val migrated = store.scanAndCommit(listOf(root), previousEntries = loaded)
            assertEquals(listOf("dff", "dsf", "wav"), metadataReads.sorted())
            assertEquals(1, migrated.reusedMetadata)
            assertEquals("Cached flac", migrated.entries.single { it.absolutePath.endsWith(".flac") }.title)
            assertEquals("Unsync-aware wav", migrated.entries.single { it.absolutePath.endsWith(".wav") }.title)
            assertEquals("Unsync-aware dsf", migrated.entries.single { it.absolutePath.endsWith(".dsf") }.title)
            assertEquals("Unsync-aware dff", migrated.entries.single { it.absolutePath.endsWith(".dff") }.title)
            assertTrue(Files.readAllLines(index).first().contains("\"schemaVersion\":9"))

            val migratedEntries = store.load()
            assertTrue(migratedEntries.none { it.metadataNeedsRefresh })
            store.scanAndCommit(listOf(root), previousEntries = migratedEntries)
            assertEquals(listOf("dff", "dsf", "wav"), metadataReads.sorted())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `expands a valid single file cue into persisted virtual tracks`() {
        val directory = Files.createTempDirectory("local-library-cue")
        try {
            val root = Files.createDirectory(directory.resolve("music"))
            val audio = Files.write(root.resolve("album.wav"), byteArrayOf(1, 2, 3))
            val cue = Files.writeString(
                root.resolve("album.cue"),
                """
                FILE "album.wav" WAVE
                TITLE "Cue Album"
                PERFORMER "Disc Artist"
                TRACK 01 AUDIO
                  TITLE "First Song"
                  INDEX 01 00:00:00
                TRACK 02 AUDIO
                  TITLE "Second Song"
                  PERFORMER "Guest Artist"
                  INDEX 01 00:01:00
                TRACK 03 AUDIO
                  INDEX 01 00:03:00
                """.trimIndent(),
            )
            val tags = DesktopReplayGainTags(-5.0, 0.9, -6.0, 0.95)
            val reads = AtomicInteger()
            val index = directory.resolve("index.jsonl")
            val store = DesktopLocalAudioLibraryStore(
                indexPath = index,
                metadataReader = {
                    reads.incrementAndGet()
                    DesktopLocalAudioMetadata(
                        title = "Image title",
                        artist = "Image artist",
                        album = "Image album",
                        durationMillis = 60_000L,
                        replayGain = tags,
                    )
                },
                artworkWriter = { null },
            )

            val result = store.scanAndCommit(listOf(root))
            assertEquals(1, result.scannedFiles)
            assertEquals(1, reads.get())
            assertEquals(3, result.entries.size)
            assertEquals(listOf("First Song", "Second Song", ""), result.entries.map { it.title })
            assertEquals(listOf("Disc Artist", "Guest Artist", "Disc Artist"), result.entries.map { it.artist })
            assertEquals(listOf("Cue Album", "Cue Album", "Cue Album"), result.entries.map { it.album })
            assertEquals(listOf(1_000L, 2_000L, 57_000L), result.entries.map { it.durationMillis })
            assertEquals(listOf(0L, 75L, 225L), result.entries.map { it.cueStartFrame75 })
            assertEquals(listOf(75L, 225L, -1L), result.entries.map { it.cueEndFrame75 })
            assertTrue(result.entries.all { it.absolutePath == audio.toAbsolutePath().normalize().toString() })
            assertTrue(result.entries.all { it.cueSheetPath == cue.toAbsolutePath().normalize().toString() })
            assertTrue(result.entries.all { it.replayGain == null })
            assertEquals(result.entries, store.load())

            val rescanned = store.scanAndCommit(listOf(root))
            assertEquals(0, rescanned.reusedMetadata)
            assertEquals(2, reads.get())
            assertEquals(result.entries, rescanned.entries)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `expands a multi-file cue in sheet order and uses each source timeline`() {
        val directory = Files.createTempDirectory("local-library-multi-file-cue")
        try {
            val root = Files.createDirectory(directory.resolve("music"))
            val laterNamedSource = Files.write(root.resolve("z-track.wav"), byteArrayOf(1, 2, 3))
            val earlierNamedSource = Files.write(root.resolve("a-track.wav"), byteArrayOf(4, 5, 6))
            val cue = Files.writeString(
                root.resolve("album.cue"),
                """
                TITLE "Cue Album"
                FILE "z-track.wav" WAVE
                TRACK 01 AUDIO
                  TITLE "First Song"
                  INDEX 01 00:00:00
                TRACK 02 AUDIO
                  TITLE "Second Song"
                  PERFORMER "Guest Artist"
                  INDEX 01 00:01:00
                FILE "a-track.wav" WAVE
                TRACK 03 AUDIO
                  TITLE "Third Song"
                  INDEX 01 00:00:00
                TRACK 04 AUDIO
                  INDEX 01 00:02:00
                """.trimIndent(),
            )
            val index = directory.resolve("index.jsonl")
            val store = DesktopLocalAudioLibraryStore(
                indexPath = index,
                metadataReader = { file ->
                    val source = file.nameWithoutExtension
                    DesktopLocalAudioMetadata(
                        title = "Metadata $source",
                        artist = "Source Artist $source",
                        album = "Source Album $source",
                        durationMillis = if (source == "z-track") 50_000L else 40_000L,
                    )
                },
                artworkWriter = { null },
            )

            val result = store.scanAndCommit(listOf(root))
            assertEquals(2, result.scannedFiles)
            assertEquals(
                listOf("First Song", "Second Song", "Third Song", ""),
                result.entries.map { it.title },
            )
            assertEquals(
                listOf("Source Artist z-track", "Guest Artist", "Source Artist a-track", "Source Artist a-track"),
                result.entries.map { it.artist },
            )
            assertEquals(List(4) { "Cue Album" }, result.entries.map { it.album })
            assertEquals(listOf(1_000L, 49_000L, 2_000L, 38_000L), result.entries.map { it.durationMillis })
            assertEquals(listOf(0L, 75L, 0L, 150L), result.entries.map { it.cueStartFrame75 })
            assertEquals(listOf(75L, -1L, 150L, -1L), result.entries.map { it.cueEndFrame75 })
            assertEquals(
                listOf(laterNamedSource, laterNamedSource, earlierNamedSource, earlierNamedSource)
                    .map { it.toAbsolutePath().normalize().toString() },
                result.entries.map { it.absolutePath },
            )
            assertTrue(result.entries.all { it.cueSheetPath == cue.toAbsolutePath().normalize().toString() })
            assertTrue(result.entries.all { it.replayGain == null })
            assertEquals(result.entries, store.load())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `rejects a multi-file cue as a whole when any referenced source is unavailable`() {
        val directory = Files.createTempDirectory("local-library-multi-file-cue-invalid")
        try {
            val root = Files.createDirectory(directory.resolve("music"))
            Files.write(root.resolve("present.wav"), byteArrayOf(1, 2, 3))
            Files.writeString(
                root.resolve("album.cue"),
                """
                FILE "present.wav" WAVE
                TRACK 01 AUDIO
                INDEX 01 00:00:00
                FILE "missing.wav" WAVE
                TRACK 02 AUDIO
                INDEX 01 00:00:00
                """.trimIndent(),
            )
            val store = DesktopLocalAudioLibraryStore(
                indexPath = directory.resolve("index.jsonl"),
                metadataReader = { DesktopLocalAudioMetadata(title = "Whole file", durationMillis = 30_000L) },
                artworkWriter = { null },
            )

            val result = store.scanAndCommit(listOf(root))
            assertEquals(1, result.entries.size)
            assertEquals("Whole file", result.entries.single().title)
            assertNull(result.entries.single().cueSheetPath)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `keeps physical audio when cue is out of root or ownership is ambiguous`() {
        val directory = Files.createTempDirectory("local-library-cue-fallback")
        try {
            val root = Files.createDirectory(directory.resolve("music"))
            Files.write(root.resolve("album.wav"), byteArrayOf(1, 2, 3))
            Files.write(directory.resolve("outside.wav"), byteArrayOf(4, 5, 6))
            val escapeCue = "FILE \"../outside.wav\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\n"
            Files.writeString(root.resolve("escape.cue"), escapeCue)

            val store = DesktopLocalAudioLibraryStore(
                indexPath = directory.resolve("index.jsonl"),
                metadataReader = {
                    DesktopLocalAudioMetadata(title = "Whole album", durationMillis = 30_000L)
                },
                artworkWriter = { null },
            )
            val outOfRoot = store.scanAndCommit(listOf(root))
            assertEquals(1, outOfRoot.entries.size)
            assertEquals("Whole album", outOfRoot.entries.single().title)
            assertEquals(null, outOfRoot.entries.single().cueSheetPath)

            Files.delete(root.resolve("escape.cue"))
            val validCue = "FILE \"album.wav\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\n"
            Files.writeString(root.resolve("one.cue"), validCue)
            Files.writeString(root.resolve("two.cue"), validCue)
            val ambiguous = store.scanAndCommit(listOf(root))
            assertEquals(1, ambiguous.entries.size)
            assertEquals("Whole album", ambiguous.entries.single().title)
            assertEquals(null, ambiguous.entries.single().cueSheetPath)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scans incrementally and restores the committed index`() {
        val directory = Files.createTempDirectory("local-library-incremental")
        try {
            val root = Files.createDirectory(directory.resolve("music"))
            val audio = Files.write(root.resolve("曲目.wav"), byteArrayOf(1, 2, 3))
            val metadataReads = AtomicInteger()
            val index = directory.resolve("index.jsonl")
            val store = testStore(index, metadataReads)

            val initial = store.scanAndCommit(listOf(root))
            assertEquals(1, initial.entries.size)
            assertEquals(1, metadataReads.get())
            assertEquals("曲目", initial.entries.single().title)

            val restored = testStore(index, metadataReads)
            assertEquals(initial.entries, restored.load())
            val unchanged = restored.scanAndCommit(listOf(root))
            assertEquals(1, unchanged.reusedMetadata)
            assertEquals(1, metadataReads.get())

            Files.write(audio, byteArrayOf(1, 2, 3, 4))
            val changed = restored.scanAndCommit(listOf(root))
            assertEquals(0, changed.reusedMetadata)
            assertEquals(2, metadataReads.get())

            Files.delete(audio)
            val afterDelete = restored.scanAndCommit(listOf(root))
            assertTrue(afterDelete.entries.isEmpty())
            assertTrue(restored.load().isEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `failed or cancelled scans leave the previous snapshot intact`() {
        val directory = Files.createTempDirectory("local-library-safe-commit")
        try {
            val root = Files.createDirectory(directory.resolve("music"))
            Files.write(root.resolve("one.wav"), byteArrayOf(1))
            Files.write(root.resolve("two.flac"), byteArrayOf(2))
            val index = directory.resolve("index.jsonl")
            val store = testStore(index)
            val committed = store.scanAndCommit(listOf(root)).entries

            val missingRoot = directory.resolve("offline")
            assertFalse(runCatching { store.scanAndCommit(listOf(missingRoot)) }.isSuccess)
            assertEquals(committed, store.load())

            var checks = 0
            val cancellation = runCatching {
                store.scanAndCommit(listOf(root), checkActive = {
                    checks += 1
                    if (checks >= 4) throw CancellationException("test cancellation")
                })
            }
            assertTrue(cancellation.exceptionOrNull() is CancellationException)
            assertEquals(committed, store.load())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scans and persists raw DSD container files`() {
        val directory = Files.createTempDirectory("local-library-dsd")
        try {
            val root = Files.createDirectory(directory.resolve("music"))
            Files.write(root.resolve("album-track.dsf"), byteArrayOf(1, 2, 3))
            Files.write(root.resolve("album-track.dff"), byteArrayOf(4, 5, 6))
            Files.write(root.resolve("cover.jpg"), byteArrayOf(7, 8, 9))
            val index = directory.resolve("index.jsonl")

            val result = testStore(index).scanAndCommit(listOf(root))
            val restored = testStore(index).load()

            assertEquals(listOf("album-track.dff", "album-track.dsf"), result.entries.map {
                java.nio.file.Path.of(it.absolutePath).fileName.toString()
            }.sorted())
            assertEquals(2, restored.size)
            assertTrue(restored.all { it.title == "album-track" })
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `scans and persists ten thousand audio entries`() {
        val directory = Files.createTempDirectory("local-library-large")
        try {
            val root = Files.createDirectory(directory.resolve("music"))
            repeat(10_000) { index ->
                Files.createFile(root.resolve("track-${index.toString().padStart(5, '0')}.wav"))
            }
            val store = testStore(directory.resolve("index.jsonl"))
            val result = store.scanAndCommit(listOf(root))

            assertEquals(10_000, result.entries.size)
            assertEquals(10_000, testStore(directory.resolve("index.jsonl")).load().size)
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `does not follow directory symlinks`() {
        val directory = Files.createTempDirectory("local-library-symlink")
        try {
            val root = Files.createDirectory(directory.resolve("music"))
            val outside = Files.createDirectory(directory.resolve("outside"))
            Files.write(outside.resolve("outside.wav"), byteArrayOf(1))
            val link = root.resolve("linked")
            try {
                Files.createSymbolicLink(link, outside)
            } catch (_: UnsupportedOperationException) {
                return
            } catch (_: IOException) {
                return
            } catch (_: SecurityException) {
                return
            }

            val result = testStore(directory.resolve("index.jsonl")).scanAndCommit(listOf(root))
            assertTrue(result.entries.isEmpty())
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun testStore(
        index: java.nio.file.Path,
        metadataReads: AtomicInteger = AtomicInteger(),
    ) = DesktopLocalAudioLibraryStore(
        indexPath = index,
        metadataReader = { file ->
            metadataReads.incrementAndGet()
            DesktopLocalAudioMetadata(title = file.nameWithoutExtension)
        },
        artworkWriter = { null },
    )
}
