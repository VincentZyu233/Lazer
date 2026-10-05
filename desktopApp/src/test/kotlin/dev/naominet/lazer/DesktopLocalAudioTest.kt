package dev.naominet.lazer

import java.nio.file.Files
import java.io.ByteArrayOutputStream
import java.io.File
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import coil3.PlatformContext
import coil3.toUri
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLocalAudioTest {
    @Test
    fun `reads PCM WAV duration from Unicode file paths`() {
        val path = Files.createTempFile("本地音频-测试", ".WAV")
        try {
            Files.write(path, DesktopPcmTestWave.create(96_000, 2, 24))
            val file = path.toFile()

            assertTrue(isSupportedLocalAudioFile(file))
            assertEquals(DesktopPcmTestWave.DURATION_MILLISECONDS.toLong(), readLocalAudioDurationMillis(file))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `reads ID3 text and front cover from RIFF WAVE chunks`() {
        val taggedPath = Files.createTempFile("local-audio-wave-id3", ".wav")
        val lowercasePath = Files.createTempFile("local-audio-wave-lowercase-id3", ".wav")
        val outsideRootPath = Files.createTempFile("local-audio-wave-id3-outside-root", ".wav")
        val oversizedArtworkPath = Files.createTempFile("local-audio-wave-id3-large-art", ".wav")
        try {
            val image = pngFixture()
            val id3 = id3v24WithExtraFrames(
                tags = listOf(
                    "TIT2" to "WAVE ID3 title",
                    "TPE1" to "WAVE ID3 artist",
                    "TALB" to "WAVE ID3 album",
                ),
                extraFrames = listOf("APIC" to id3PictureFrame(3, "image/png", image)),
            )
            Files.write(taggedPath, waveWithId3Tags(id3, includeOddJunk = true))

            val metadata = readLocalAudioMetadata(taggedPath.toFile())
            assertEquals("WAVE ID3 title", metadata?.title)
            assertEquals("WAVE ID3 artist", metadata?.artist)
            assertEquals("WAVE ID3 album", metadata?.album)
            assertEquals(1_000L, metadata?.durationMillis)
            assertEquals("image/png", metadata?.embeddedArtwork?.mimeType)
            assertEquals(3L, metadata?.embeddedArtwork?.pictureType)
            assertTrue(metadata?.embeddedArtwork?.data?.contentEquals(image) == true)

            // Lowercase `id3 ` is used by some WAVE taggers. ID3 wins conflicts; INFO is fallback.
            Files.write(
                lowercasePath,
                waveWithId3Tags(id3, chunkId = "id3 ", includeInfoTags = true, id3AfterData = true),
            )
            val lowercase = readLocalAudioMetadata(lowercasePath.toFile())
            assertEquals("WAVE ID3 title", lowercase?.title)
            assertEquals("WAVE ID3 artist", lowercase?.artist)
            assertEquals("WAVE ID3 album", lowercase?.album)
            assertTrue(lowercase?.embeddedArtwork?.data?.contentEquals(image) == true)
            assertEquals(1_000L, lowercase?.durationMillis)

            // A valid ID3-shaped chunk after the declared RIFF root is not part of the WAVE file.
            val outsideChunk = ByteArrayOutputStream().apply { writeChunk("ID3 ", id3) }.toByteArray()
            Files.write(outsideRootPath, waveWithId3Tags(id3Tag = null) + outsideChunk)
            val outsideRoot = readLocalAudioMetadata(outsideRootPath.toFile())
            assertNull(outsideRoot?.embeddedArtwork)
            assertEquals(1_000L, outsideRoot?.durationMillis)

            val oversizedId3 = id3v24WithExtraFrames(
                tags = listOf("TIT2" to "WAVE text survives oversized art"),
                extraFrames = listOf(
                    "APIC" to id3PictureFrame(3, "image/png", ByteArray(12 * 1_048_576 + 1)),
                ),
            )
            Files.write(oversizedArtworkPath, waveWithId3Tags(oversizedId3))
            val oversized = readLocalAudioMetadata(oversizedArtworkPath.toFile())
            assertEquals("WAVE text survives oversized art", oversized?.title)
            assertNull(oversized?.embeddedArtwork)
            assertEquals(1_000L, oversized?.durationMillis)
        } finally {
            Files.deleteIfExists(taggedPath)
            Files.deleteIfExists(lowercasePath)
            Files.deleteIfExists(outsideRootPath)
            Files.deleteIfExists(oversizedArtworkPath)
        }
    }

    @Test
    fun `validates ID3 v23 and v24 extended headers`() {
        val path = Files.createTempFile("local-audio-id3-extended-header", ".wav")
        try {
            val titleFrameTag = id3Tag(3, listOf("TIT2" to "Extended header title")) { value ->
                byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)
            }
            val frames = titleFrameTag.copyOfRange(10, titleFrameTag.size)
            val v23ExtendedHeader = byteArrayOf(0, 0, 0, 6, 0, 0, 0, 0, 0, 0)
            Files.write(path, waveWithId3Tags(id3TagWithExtendedHeader(3, v23ExtendedHeader, frames)))
            val v23 = readLocalAudioMetadata(path.toFile())
            assertEquals("Extended header title", v23?.title)
            assertEquals(1_000L, v23?.durationMillis)

            val v24ExtendedHeader = byteArrayOf(0, 0, 0, 6, 1, 0)
            Files.write(path, waveWithId3Tags(id3TagWithExtendedHeader(4, v24ExtendedHeader, frames)))
            val v24 = readLocalAudioMetadata(path.toFile())
            assertEquals("Extended header title", v24?.title)
            assertEquals(1_000L, v24?.durationMillis)

            // Malformed extended headers are ignored while container-level duration remains readable.
            val malformedV23 = v23ExtendedHeader.copyOf().also { it[3] = 5 }
            Files.write(path, waveWithId3Tags(id3TagWithExtendedHeader(3, malformedV23, frames)))
            val rejectedV23 = readLocalAudioMetadata(path.toFile())
            assertNull(rejectedV23?.title)
            assertEquals(1_000L, rejectedV23?.durationMillis)

            val malformedV24 = byteArrayOf(0, 0, 0, 6, 0, 0)
            Files.write(path, waveWithId3Tags(id3TagWithExtendedHeader(4, malformedV24, frames)))
            val rejectedV24 = readLocalAudioMetadata(path.toFile())
            assertNull(rejectedV24?.title)
            assertEquals(1_000L, rejectedV24?.durationMillis)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `reads ID3 v22 and v23 tag-wide unsynchronization`() {
        val wavePath = Files.createTempFile("local-audio-id3-v23-unsync", ".wav")
        val dffPath = Files.createTempFile("local-audio-id3-v22-unsync", ".dff")
        try {
            val encodedTitle = byteArrayOf(0, 0xff.toByte(), 0xe1.toByte())
            val v23 = id3UnsynchronizedTag(
                version = 3,
                frames = listOf("TIT2" to encodedTitle),
                tagUnsynchronised = true,
            )
            Files.write(wavePath, waveWithId3Tags(v23))
            val waveMetadata = readLocalAudioMetadata(wavePath.toFile())
            assertEquals("\u00ff\u00e1", waveMetadata?.title)
            assertEquals(1_000L, waveMetadata?.durationMillis)

            val v22 = id3UnsynchronizedTag(
                version = 2,
                frames = listOf("TT2" to encodedTitle),
                tagUnsynchronised = true,
            )
            Files.write(dffPath, dffFixture(dst = false, id3Tag = v22))
            val dffMetadata = readLocalAudioMetadata(dffPath.toFile())
            assertEquals("\u00ff\u00e1", dffMetadata?.title)
            assertEquals(10L, dffMetadata?.durationMillis)
        } finally {
            Files.deleteIfExists(wavePath)
            Files.deleteIfExists(dffPath)
        }
    }

    @Test
    fun `reads ID3 v24 tag-wide and per-frame unsynchronization`() {
        val globalPath = Files.createTempFile("local-audio-id3-v24-global-unsync", ".dff")
        val framePath = Files.createTempFile("local-audio-id3-v24-frame-unsync", ".wav")
        try {
            val image = byteArrayOf(
                0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe0.toByte(), 0x10,
                0xff.toByte(), 0, 0x11, 0xff.toByte(),
            )
            val globalTag = id3UnsynchronizedTag(
                version = 4,
                frames = listOf(
                    "TIT2" to byteArrayOf(3) + "v2.4 tag unsync".toByteArray(Charsets.UTF_8),
                    "APIC" to id3PictureFrame(3, "image/jpeg", image),
                ),
                tagUnsynchronised = true,
            )
            Files.write(globalPath, dffFixture(dst = false, id3Tag = globalTag))
            val globalMetadata = readLocalAudioMetadata(globalPath.toFile())
            assertEquals("v2.4 tag unsync", globalMetadata?.title)
            assertEquals("image/jpeg", globalMetadata?.embeddedArtwork?.mimeType)
            assertTrue(globalMetadata?.embeddedArtwork?.data?.contentEquals(image) == true)
            assertEquals(10L, globalMetadata?.durationMillis)

            val frameTag = id3UnsynchronizedTag(
                version = 4,
                frames = listOf("TIT2" to byteArrayOf(0, 0xff.toByte(), 0xe2.toByte())),
                tagUnsynchronised = false,
                frameUnsynchronised = true,
            )
            Files.write(framePath, waveWithId3Tags(frameTag))
            val frameMetadata = readLocalAudioMetadata(framePath.toFile())
            assertEquals("\u00ff\u00e2", frameMetadata?.title)
            assertEquals(1_000L, frameMetadata?.durationMillis)
        } finally {
            Files.deleteIfExists(globalPath)
            Files.deleteIfExists(framePath)
        }
    }

    @Test
    fun `reads trailing APEv2 tags as metadata fallback for all supported local formats`() {
        val image = pngFixture()
        val apeTag = apeV2Tag(
            items = listOf(
                apeTextItem("TITLE", "APE 标题"),
                apeTextItem("ARTIST", "\u0000APE 艺人"),
                apeTextItem("ALBUM", "APE 专辑"),
                apeTextItem("REPLAYGAIN_TRACK_GAIN", "-7.25 dB"),
                apeTextItem("REPLAYGAIN_TRACK_PEAK", "0.91"),
                apeBinaryItem("Cover Art (Front)", "cover.png".toByteArray() + byteArrayOf(0) + image),
            ),
            headerPresent = true,
        )
        val fixtures = listOf(
            ".wav" to waveWithId3Tags(id3Tag = null),
            ".flac" to flacWithVorbisComments(emptyList()),
            ".dsf" to dsfFixture(sampleRate = 2_822_400, sampleCount = 28_224),
            ".dff" to dffFixture(dst = false),
        )
        val paths = fixtures.map { (extension, bytes) ->
            Files.createTempFile("local-audio-ape-v2", extension).also { path ->
                Files.write(path, bytes + apeTag)
            }
        }
        try {
            paths.forEach { path ->
                val metadata = readLocalAudioMetadata(path.toFile())
                assertEquals("APE 标题", metadata?.title)
                assertEquals("APE 艺人", metadata?.artist)
                assertEquals("APE 专辑", metadata?.album)
                assertEquals(3L, metadata?.embeddedArtwork?.pictureType)
                assertEquals("image/png", metadata?.embeddedArtwork?.mimeType)
                assertTrue(metadata?.embeddedArtwork?.data?.contentEquals(image) == true)
                assertEquals(DesktopReplayGainTags(-7.25, 0.91, null, null), metadata?.replayGain)
                assertTrue((metadata?.durationMillis ?: 0L) > 0L)
            }
        } finally {
            paths.forEach(Files::deleteIfExists)
        }
    }

    @Test
    fun `native metadata wins over APE fallback and APE can follow ID3v1`() {
        val wavePath = Files.createTempFile("local-audio-ape-precedence", ".wav")
        val id3v1Path = Files.createTempFile("local-audio-ape-id3v1", ".wav")
        try {
            val nativeId3 = id3v24WithExtraFrames(
                tags = listOf("TIT2" to "Native ID3 title"),
                extraFrames = listOf("TXXX" to id3UserTextFrame(3, "REPLAYGAIN_TRACK_GAIN", "-2 dB")),
            )
            val apeTag = apeV2Tag(
                items = listOf(
                    apeTextItem("TITLE", "APE title"),
                    apeTextItem("ARTIST", "APE artist"),
                    apeTextItem("REPLAYGAIN_TRACK_GAIN", "-8 dB"),
                    apeTextItem("REPLAYGAIN_TRACK_PEAK", "0.8"),
                ),
            )
            Files.write(wavePath, waveWithId3Tags(nativeId3) + apeTag)
            val metadata = readLocalAudioMetadata(wavePath.toFile())
            assertEquals("Native ID3 title", metadata?.title)
            assertEquals("APE artist", metadata?.artist)
            assertEquals(DesktopReplayGainTags(-2.0, 0.8, null, null), metadata?.replayGain)

            val id3v1 = ByteArray(128).apply { "TAG".toByteArray(Charsets.US_ASCII).copyInto(this) }
            Files.write(id3v1Path, waveWithId3Tags(id3Tag = null) + apeTag + id3v1)
            assertEquals("APE title", readLocalAudioMetadata(id3v1Path.toFile())?.title)
        } finally {
            Files.deleteIfExists(wavePath)
            Files.deleteIfExists(id3v1Path)
        }
    }

    @Test
    fun `invalid APEv2 trailer is ignored without losing container metadata or duration`() {
        val path = Files.createTempFile("local-audio-ape-v2-invalid", ".wav")
        try {
            val validTag = apeV2Tag(listOf(apeTextItem("TITLE", "Must not appear")))
            val badVersion = validTag.copyOf().apply {
                this[size - 32 + 8] = 0xD1.toByte()
            }
            Files.write(path, waveWithId3Tags(id3Tag = null, includeInfoTags = true) + badVersion)
            val metadata = readLocalAudioMetadata(path.toFile())
            assertEquals("WAV Title", metadata?.title)
            assertEquals("WAV Artist", metadata?.artist)
            assertEquals(1_000L, metadata?.durationMillis)

            val badSize = validTag.copyOf().apply {
                val footerOffset = size - 32
                repeat(4) { this[footerOffset + 12 + it] = 0xff.toByte() }
            }
            Files.write(path, waveWithId3Tags(id3Tag = null) + badSize)
            val stillReadable = readLocalAudioMetadata(path.toFile())
            assertNull(stillReadable?.title)
            assertEquals(1_000L, stillReadable?.durationMillis)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `reads FLAC duration from STREAMINFO`() {
        val path = Files.createTempFile("local-audio-streaminfo", ".flac")
        try {
            val streamInfo = ByteArray(42)
            "fLaC".toByteArray(Charsets.US_ASCII).copyInto(streamInfo, 0)
            streamInfo[4] = 0x80.toByte() // Last metadata block, type STREAMINFO.
            streamInfo[7] = 34
            val sampleRate = 96_000L
            val channelsMinusOne = 1L
            val bitsPerSampleMinusOne = 23L
            val sampleCount = 288_000L
            val packed = (sampleRate shl 44) or
                (channelsMinusOne shl 41) or
                (bitsPerSampleMinusOne shl 36) or sampleCount
            for (index in 0 until 8) {
                streamInfo[18 + index] = (packed ushr ((7 - index) * 8)).toByte()
            }
            Files.write(path, streamInfo)

            assertEquals(3_000L, readLocalAudioDurationMillis(path.toFile()))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `reads DSF duration without decoding its DSD payload`() {
        val path = Files.createTempFile("local-audio-dsd", ".dsf")
        try {
            Files.write(path, dsfFixture(sampleRate = 2_822_400, sampleCount = 2_822_400))
            val metadata = readLocalAudioMetadata(path.toFile())

            assertTrue(isSupportedLocalAudioFile(path.toFile()))
            assertEquals(1_000L, metadata?.durationMillis)
            assertNull(metadata?.title) // Display falls back to the file name.
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `reads DSF ID3v23 title artist and album while keeping duration`() {
        val path = Files.createTempFile("local-audio-dsf-tags", ".dsf")
        val cacheDirectory = Files.createTempDirectory("local-audio-dsf-artwork-cache").toFile()
        try {
            val image = pngFixture()
            val id3 = id3v23WithExtraFrames(
                tags = listOf(
                    "TIT2" to "夜曲",
                    "TPE1" to "测试艺人",
                    "TALB" to "试音专辑",
                ),
                extraFrames = listOf(
                    "APIC" to id3PictureFrame(0, "image/png", image),
                    "APIC" to id3PictureFrame(3, "image/png", image, encoding = 1, description = "封面"),
                ),
            )
            Files.write(path, dsfFixture(sampleRate = 2_822_400, sampleCount = 2_822_400, id3Tag = id3))

            val metadata = readLocalAudioMetadata(path.toFile())
            assertEquals("夜曲", metadata?.title)
            assertEquals("测试艺人", metadata?.artist)
            assertEquals("试音专辑", metadata?.album)
            assertEquals(1_000L, metadata?.durationMillis)
            assertEquals("image/png", metadata?.embeddedArtwork?.mimeType)
            assertEquals(3L, metadata?.embeddedArtwork?.pictureType)
            assertTrue(metadata?.embeddedArtwork?.data?.contentEquals(image) == true)
            val artworkUri = writeLocalAudioArtwork(metadata!!.embeddedArtwork!!, cacheDirectory)
            assertTrue(artworkUri?.startsWith("file:") == true)
            assertTrue(File(java.net.URI(artworkUri!!)).readBytes().contentEquals(image))
        } finally {
            Files.deleteIfExists(path)
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun `reads DFF ID3v24 UTF8 tags and ignores malformed bounded tag data`() {
        val taggedPath = Files.createTempFile("local-audio-dff-tags", ".dff")
        val malformedPath = Files.createTempFile("local-audio-dff-bad-tags", ".dff")
        val oversizedArtworkPath = Files.createTempFile("local-audio-dff-large-art", ".dff")
        try {
            val image = pngFixture()
            val id3 = id3v24WithExtraFrames(
                tags = listOf(
                    "TIT2" to "DFF title",
                    "TPE1" to "DFF artist",
                    "TALB" to "DFF album",
                ),
                extraFrames = listOf("APIC" to id3PictureFrame(3, "image/png", image)),
            )
            Files.write(taggedPath, dffFixture(dst = false, id3Tag = id3))
            val metadata = readLocalAudioMetadata(taggedPath.toFile())
            assertEquals("DFF title", metadata?.title)
            assertEquals("DFF artist", metadata?.artist)
            assertEquals("DFF album", metadata?.album)
            assertEquals(10L, metadata?.durationMillis)
            assertTrue(metadata?.embeddedArtwork?.data?.contentEquals(image) == true)

            // Declares an ID3 body larger than the supported bound. The audio chunk remains readable.
            val oversizedTag = byteArrayOf(
                'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0,
                0x08, 0, 0, 0,
            )
            Files.write(malformedPath, dffFixture(dst = true, id3Tag = oversizedTag))
            val malformed = readLocalAudioMetadata(malformedPath.toFile())
            assertNull(malformed?.title)
            assertEquals(1_000L, malformed?.durationMillis)

            val oversizedArtwork = id3v24WithExtraFrames(
                tags = listOf("TIT2" to "Text survives large art"),
                extraFrames = listOf(
                    "APIC" to id3PictureFrame(3, "image/png", ByteArray(12 * 1_048_576 + 1)),
                ),
            )
            Files.write(oversizedArtworkPath, dffFixture(dst = false, id3Tag = oversizedArtwork))
            val boundedArtwork = readLocalAudioMetadata(oversizedArtworkPath.toFile())
            assertEquals("Text survives large art", boundedArtwork?.title)
            assertNull(boundedArtwork?.embeddedArtwork)
            assertEquals(10L, boundedArtwork?.durationMillis)
        } finally {
            Files.deleteIfExists(taggedPath)
            Files.deleteIfExists(malformedPath)
            Files.deleteIfExists(oversizedArtworkPath)
        }
    }

    @Test
    fun `reads ID3v22 PIC frame from DFF metadata`() {
        val path = Files.createTempFile("local-audio-dff-pic-v22", ".dff")
        try {
            val image = pngFixture()
            val id3 = id3v22WithExtraFrames(
                tags = listOf("TT2" to "Legacy title"),
                extraFrames = listOf("PIC" to id3PicFrame(3, "PNG", image)),
            )
            Files.write(path, dffFixture(dst = false, id3Tag = id3))

            val metadata = readLocalAudioMetadata(path.toFile())
            assertEquals("Legacy title", metadata?.title)
            assertEquals("image/png", metadata?.embeddedArtwork?.mimeType)
            assertEquals(3L, metadata?.embeddedArtwork?.pictureType)
            assertTrue(metadata?.embeddedArtwork?.data?.contentEquals(image) == true)
            assertEquals(10L, metadata?.durationMillis)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `reads raw and DST DFF duration from their container timing fields`() {
        val rawPath = Files.createTempFile("local-audio-raw-dsd", ".dff")
        val dstPath = Files.createTempFile("local-audio-dst-dsd", ".DFF")
        try {
            Files.write(rawPath, dffFixture(dst = false))
            Files.write(dstPath, dffFixture(dst = true))

            assertTrue(isSupportedLocalAudioFile(rawPath.toFile()))
            assertTrue(isSupportedLocalAudioFile(dstPath.toFile()))
            assertEquals(10L, readLocalAudioDurationMillis(rawPath.toFile()))
            assertEquals(1_000L, readLocalAudioDurationMillis(dstPath.toFile()))

            Files.write(dstPath, dffFixture(dst = true).copyOfRange(0, 40))
            assertNull(readLocalAudioDurationMillis(dstPath.toFile()))
        } finally {
            Files.deleteIfExists(rawPath)
            Files.deleteIfExists(dstPath)
        }
    }

    @Test
    fun `reads common title artist and album tags from WAV and FLAC`() {
        val wavPath = Files.createTempFile("local-audio-info", ".wav")
        val flacPath = Files.createTempFile("local-audio-comments", ".flac")
        try {
            Files.write(wavPath, waveWithInfoTags())
            val wav = readLocalAudioMetadata(wavPath.toFile())
            assertEquals("WAV Title", wav?.title)
            assertEquals("WAV Artist", wav?.artist)
            assertEquals("WAV Album", wav?.album)

            Files.write(flacPath, flacWithVorbisComments())
            val flac = readLocalAudioMetadata(flacPath.toFile())
            assertEquals("夜曲", flac?.title)
            assertEquals("测试艺人", flac?.artist)
            assertEquals("试音专辑", flac?.album)
            assertEquals(3_000L, flac?.durationMillis)
            assertNull(wav?.replayGain)
        } finally {
            Files.deleteIfExists(wavPath)
            Files.deleteIfExists(flacPath)
        }
    }

    @Test
    fun `reads WAV ReplayGain from ID3v23 UTF16 and ID3v24 UTF8 TXXX frames`() {
        val v23Path = Files.createTempFile("local-audio-wave-replaygain-v23", ".wav")
        val v24Path = Files.createTempFile("local-audio-wave-replaygain-v24", ".wav")
        try {
            val v23 = id3v23WithExtraFrames(
                tags = listOf("TIT2" to "v2.3 ReplayGain"),
                extraFrames = listOf(
                    "TXXX" to id3UserTextFrame(1, "REPLAYGAIN_TRACK_GAIN", "NaN"),
                    "TXXX" to id3UserTextFrame(
                        1,
                        "replaygain_track_gain",
                        "-7.25 dB",
                        littleEndian = true,
                        valueBom = false,
                    ),
                    "TXXX" to id3UserTextFrame(1, "REPLAYGAIN_TRACK_GAIN", "4 dB"),
                    "TXXX" to id3UserTextFrame(1, "REPLAYGAIN_TRACK_PEAK", "0"),
                    "TXXX" to id3UserTextFrame(1, "REPLAYGAIN_TRACK_PEAK", "1.25"),
                    "TXXX" to id3UserTextFrame(1, "REPLAYGAIN_TRACK_PEAK", "1.5"),
                    "TXXX" to id3UserTextFrame(1, "REPLAYGAIN_ALBUM_GAIN", "−3.0 dB"),
                    "TXXX" to id3UserTextFrame(1, "REPLAYGAIN_ALBUM_GAIN", "+2.5DB"),
                    "TXXX" to id3UserTextFrame(1, "REPLAYGAIN_ALBUM_PEAK", "16"),
                ),
            )
            Files.write(v23Path, waveWithId3Tags(v23))

            val v24 = id3v24WithExtraFrames(
                tags = listOf("TIT2" to "v2.4 ReplayGain"),
                extraFrames = listOf(
                    "TXXX" to id3UserTextFrame(3, "REPLAYGAIN_TRACK_GAIN", "-4.0 dB"),
                    "TXXX" to id3UserTextFrame(3, "REPLAYGAIN_TRACK_PEAK", "0.5"),
                    "TXXX" to id3UserTextFrame(3, "REPLAYGAIN_ALBUM_GAIN", "-8.5 dB"),
                    "TXXX" to id3UserTextFrame(3, "REPLAYGAIN_ALBUM_PEAK", "0.75"),
                ),
            )
            Files.write(v24Path, waveWithId3Tags(v24))

            val v23Metadata = readLocalAudioMetadata(v23Path.toFile())
            assertEquals("v2.3 ReplayGain", v23Metadata?.title)
            assertEquals(1_000L, v23Metadata?.durationMillis)
            assertEquals(
                DesktopReplayGainTags(-7.25, 1.25, 2.5, 16.0),
                v23Metadata?.replayGain,
            )
            assertEquals(
                DesktopReplayGainTags(-4.0, 0.5, -8.5, 0.75),
                readLocalAudioMetadata(v24Path.toFile())?.replayGain,
            )
        } finally {
            Files.deleteIfExists(v23Path)
            Files.deleteIfExists(v24Path)
        }
    }

    @Test
    fun `ignores malformed and out of range WAV ID3 ReplayGain values`() {
        val path = Files.createTempFile("local-audio-wave-replaygain-invalid", ".wav")
        try {
            val malformedWithoutDescriptionSeparator = byteArrayOf(0) +
                "REPLAYGAIN_TRACK_GAIN".toByteArray(Charsets.ISO_8859_1) + "-3 dB".toByteArray(Charsets.ISO_8859_1)
            val id3 = id3v24WithExtraFrames(
                tags = emptyList(),
                extraFrames = listOf(
                    "TXXX" to id3UserTextFrame(3, "REPLAYGAIN_TRACK_GAIN", "24.0001 dB"),
                    "TXXX" to id3UserTextFrame(3, "REPLAYGAIN_TRACK_PEAK", "Infinity"),
                    "TXXX" to id3UserTextFrame(3, "REPLAYGAIN_ALBUM_GAIN", "１２.0 dB"),
                    "TXXX" to id3UserTextFrame(3, "REPLAYGAIN_ALBUM_PEAK", "0"),
                    "TXXX" to malformedWithoutDescriptionSeparator,
                    "TXXX" to byteArrayOf(4, 0), // Unsupported text encoding.
                ),
            )
            Files.write(path, waveWithId3Tags(id3))

            assertNull(readLocalAudioMetadata(path.toFile())?.replayGain)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `reads first valid ReplayGain value for each FLAC comment independently`() {
        val path = Files.createTempFile("local-audio-replaygain", ".flac")
        try {
            Files.write(
                path,
                flacWithVorbisComments(
                    listOf(
                        "TITLE=ReplayGain fixture",
                        "REPLAYGAIN_TRACK_GAIN=NaN",
                        "replaygain_track_gain=-7.25 dB",
                        "REPLAYGAIN_TRACK_GAIN=4.0 dB",
                        "REPLAYGAIN_TRACK_PEAK=0",
                        "REPLAYGAIN_TRACK_PEAK=1.25",
                        "REPLAYGAIN_ALBUM_GAIN=−3.0 dB", // Unicode minus is not ASCII decimal syntax.
                        "REPLAYGAIN_ALBUM_GAIN=+2.5DB",
                        "REPLAYGAIN_ALBUM_PEAK=16",
                    ),
                ),
            )

            assertEquals(
                DesktopReplayGainTags(
                    trackGainDb = -7.25,
                    trackPeak = 1.25,
                    albumGainDb = 2.5,
                    albumPeak = 16.0,
                ),
                readLocalAudioMetadata(path.toFile())?.replayGain,
            )
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `ignores invalid ReplayGain decimals and out of range values`() {
        val path = Files.createTempFile("local-audio-replaygain-invalid", ".flac")
        try {
            Files.write(
                path,
                flacWithVorbisComments(
                    listOf(
                        "REPLAYGAIN_TRACK_GAIN=１２.0 dB",
                        "REPLAYGAIN_TRACK_PEAK=Infinity",
                        "REPLAYGAIN_ALBUM_GAIN=24.0001 dB",
                        "REPLAYGAIN_ALBUM_PEAK=16.0001",
                    ),
                ),
            )

            assertNull(readLocalAudioMetadata(path.toFile())?.replayGain)
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `reads FLAC front cover and Coil loads it from the local artwork URI`() = runBlocking {
        val path = Files.createTempFile("local-audio-picture", ".flac")
        val cacheDirectory = Files.createTempDirectory("local-audio-artwork-cache").toFile()
        try {
            Files.write(path, flacWithPicture())
            val artwork = readLocalAudioMetadata(path.toFile())?.embeddedArtwork

            assertEquals("image/png", artwork?.mimeType)
            assertEquals(3L, artwork?.pictureType)
            assertTrue(artwork?.data?.isNotEmpty() == true)
            val localUri = writeLocalAudioArtwork(artwork!!, cacheDirectory)
            assertTrue(localUri?.startsWith("file:") == true)
            assertEquals("file", localUri?.toUri()?.scheme)
            assertEquals(localUri, localUri?.toArtworkUrl(128))
            assertEquals(localUri, localUri?.toPaletteArtworkUrl())
            val cachedArtwork = File(java.net.URI(localUri!!))
            assertTrue(cachedArtwork.isFile)
            val imageLoader = createDesktopImageLoader(PlatformContext.INSTANCE)
            try {
                val result = imageLoader.execute(
                    ImageRequest.Builder(PlatformContext.INSTANCE).data(localUri).build(),
                )
                assertTrue("Coil failed to decode local artwork: $result", result is SuccessResult)
            } finally {
                imageLoader.shutdown()
            }
        } finally {
            Files.deleteIfExists(path)
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun `accepts WAV FLAC DSF and DFF as local file queue sources`() {
        val wav = Files.createTempFile("local-audio", ".wav").toFile()
        val flac = Files.createTempFile("local-audio", ".flac").toFile()
        val dsf = Files.createTempFile("local-audio", ".DSF").toFile()
        val dff = Files.createTempFile("local-audio", ".dff").toFile()
        val mp3 = Files.createTempFile("local-audio", ".mp3").toFile()
        try {
            assertTrue(isSupportedLocalAudioFile(wav))
            assertTrue(isSupportedLocalAudioFile(flac))
            assertTrue(isSupportedLocalAudioFile(dsf))
            assertTrue(isSupportedLocalAudioFile(dff))
            assertFalse(isSupportedLocalAudioFile(mp3))
            assertNull(readLocalAudioDurationMillis(mp3))
        } finally {
            wav.delete()
            flac.delete()
            dsf.delete()
            dff.delete()
            mp3.delete()
        }
    }

    @Test
    fun `local seekable source reads and seeks exact file offsets`() {
        val path = Files.createTempFile("local-seekable", ".wav")
        val bytes = byteArrayOf(11, 22, 33, 44, 55)
        try {
            Files.write(path, bytes)
            val source = openLocalSeekableAudioSource(path.toFile())
            val buffer = ByteArray(3)
            try {
                assertEquals(bytes.size.toLong(), source.length())
                assertEquals(3, source.read(buffer, buffer.size))
                assertTrue(buffer.contentEquals(bytes.copyOfRange(0, 3)))
                assertEquals(1L, source.seek(1))
                assertEquals(3, source.read(buffer, buffer.size))
                assertTrue(buffer.contentEquals(bytes.copyOfRange(1, 4)))
                assertEquals(-1L, source.seek(bytes.size.toLong() + 1))
            } finally {
                source.close()
            }
            assertEquals(-1, source.read(buffer, buffer.size))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    @Test
    fun `local track origin is explicit and separate from its queue token`() {
        val source = DesktopTrackSource.LocalFile("C:/音乐/测试.wav")
        val track = TrackItem(
            id = Long.MIN_VALUE,
            title = "测试",
            artist = "",
            album = "",
            durationMillis = 1_000,
            coverUrl = null,
            playbackSource = source,
        )

        assertTrue(track.isLocalFile)
        assertEquals(source, track.playbackSource)
        assertFalse(track.copy(playbackSource = DesktopTrackSource.Remote).isLocalFile)
    }

    private fun waveWithInfoTags(): ByteArray {
        val info = ByteArrayOutputStream().apply {
            writeAscii("INFO")
            writeInfoTag("INAM", "WAV Title")
            writeInfoTag("IART", "WAV Artist")
            writeInfoTag("IPRD", "WAV Album")
        }.toByteArray()
        val fmt = byteArrayOf(
            1, 0, 2, 0, 0x80.toByte(), 0xbb.toByte(), 0, 0,
            0, 0xee.toByte(), 2, 0, 4, 0, 16, 0,
        )
        val data = byteArrayOf(0, 0, 0, 0)
        val chunks = ByteArrayOutputStream().apply {
            writeChunk("fmt ", fmt)
            writeChunk("LIST", info)
            writeChunk("data", data)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            writeAscii("RIFF")
            writeUInt32LittleEndian(4 + chunks.size)
            writeAscii("WAVE")
            write(chunks)
        }.toByteArray()
    }

    private fun waveWithId3Tags(
        id3Tag: ByteArray?,
        chunkId: String = "ID3 ",
        includeInfoTags: Boolean = false,
        includeOddJunk: Boolean = false,
        id3AfterData: Boolean = false,
    ): ByteArray {
        val fmt = byteArrayOf(
            1, 0, 2, 0, 0x80.toByte(), 0xbb.toByte(), 0, 0,
            0, 0xee.toByte(), 2, 0, 4, 0, 16, 0,
        )
        val info = ByteArrayOutputStream().apply {
            writeAscii("INFO")
            writeInfoTag("INAM", "WAV Title")
            writeInfoTag("IART", "WAV Artist")
            writeInfoTag("IPRD", "WAV Album")
        }.toByteArray()
        val chunks = ByteArrayOutputStream().apply {
            writeChunk("fmt ", fmt)
            if (includeInfoTags) writeChunk("LIST", info)
            if (includeOddJunk) writeChunk("JUNK", byteArrayOf(0x55))
            if (id3Tag != null && !id3AfterData) writeChunk(chunkId, id3Tag)
            writeChunk("data", ByteArray(192_000))
            if (id3Tag != null && id3AfterData) writeChunk(chunkId, id3Tag)
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            writeAscii("RIFF")
            writeUInt32LittleEndian(4 + chunks.size)
            writeAscii("WAVE")
            write(chunks)
        }.toByteArray()
    }

    private fun flacWithVorbisComments(
        comments: List<String> = listOf("TITLE=夜曲", "ARTIST=测试艺人", "ALBUM=试音专辑"),
    ): ByteArray {
        val streamInfo = ByteArray(42).apply {
            "fLaC".toByteArray(Charsets.US_ASCII).copyInto(this)
            this[4] = 0 // STREAMINFO block, not the last metadata block.
            this[7] = 34
            val packed = (96_000L shl 44) or (1L shl 41) or (23L shl 36) or 288_000L
            for (index in 0 until 8) {
                this[18 + index] = (packed ushr ((7 - index) * 8)).toByte()
            }
        }
        val body = ByteArrayOutputStream().apply {
            val vendor = "Fixture generator".toByteArray(Charsets.UTF_8)
            writeUInt32LittleEndian(vendor.size)
            write(vendor)
            writeUInt32LittleEndian(comments.size)
            comments.forEach { comment ->
                val bytes = comment.toByteArray(Charsets.UTF_8)
                writeUInt32LittleEndian(bytes.size)
                write(bytes)
            }
        }.toByteArray()
        val commentBlock = ByteArrayOutputStream().apply {
            write(0x84) // Final metadata block, type VORBIS_COMMENT.
            write((body.size ushr 16) and 0xff)
            write((body.size ushr 8) and 0xff)
            write(body.size and 0xff)
            write(body)
        }.toByteArray()
        return streamInfo + commentBlock
    }

    private fun flacWithPicture(): ByteArray {
        val streamInfo = ByteArray(42).apply {
            "fLaC".toByteArray(Charsets.US_ASCII).copyInto(this)
            this[4] = 0 // STREAMINFO block, not the last metadata block.
            this[7] = 34
        }
        val image = ByteArrayOutputStream().use { bytes ->
            ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes)
            bytes.toByteArray()
        }
        val pictureBody = ByteArrayOutputStream().apply {
            writeUInt32BigEndian(3) // Front cover.
            val mime = "image/png".toByteArray(Charsets.US_ASCII)
            writeUInt32BigEndian(mime.size)
            write(mime)
            writeUInt32BigEndian(0) // Empty description.
            repeat(4) { writeUInt32BigEndian(1) } // Width, height, depth, colors.
            writeUInt32BigEndian(image.size)
            write(image)
        }.toByteArray()
        val pictureBlock = ByteArrayOutputStream().apply {
            write(6) // PICTURE metadata block, followed by the last STREAMINFO marker below.
            write((pictureBody.size ushr 16) and 0xff)
            write((pictureBody.size ushr 8) and 0xff)
            write(pictureBody.size and 0xff)
            write(pictureBody)
        }.toByteArray()
        // This fixture only exercises artwork parsing, so mark PICTURE as the final metadata block.
        pictureBlock[0] = 0x86.toByte()
        return streamInfo + pictureBlock
    }

    private fun dsfFixture(sampleRate: Int, sampleCount: Long, id3Tag: ByteArray? = null): ByteArray {
        val blockBytes = 4096
        val channels = 2
        val bytesPerChannel = ((sampleCount + 7) / 8).toInt()
        val paddedBytesPerChannel = ((bytesPerChannel + blockBytes - 1) / blockBytes) * blockBytes
        val dataChunkSize = 12L + paddedBytesPerChannel.toLong() * channels
        val id3Offset = 28L + 52L + dataChunkSize
        val fileSize = id3Offset + (id3Tag?.size ?: 0)
        return ByteArrayOutputStream().apply {
            writeAscii("DSD ")
            writeUInt64LittleEndian(28)
            writeUInt64LittleEndian(fileSize)
            writeUInt64LittleEndian(if (id3Tag == null) 0L else id3Offset)
            writeAscii("fmt ")
            writeUInt64LittleEndian(52)
            writeUInt32LittleEndian(1)
            writeUInt32LittleEndian(0)
            writeUInt32LittleEndian(2)
            writeUInt32LittleEndian(channels)
            writeUInt32LittleEndian(sampleRate)
            writeUInt32LittleEndian(8)
            writeUInt64LittleEndian(sampleCount)
            writeUInt32LittleEndian(blockBytes)
            writeUInt32LittleEndian(0)
            writeAscii("data")
            writeUInt64LittleEndian(dataChunkSize)
            write(ByteArray(paddedBytesPerChannel * channels))
            id3Tag?.let(::write)
        }.toByteArray()
    }

    private fun dffFixture(dst: Boolean, id3Tag: ByteArray? = null): ByteArray {
        val version = ByteArrayOutputStream().apply { writeUInt32BigEndian(if (dst) 0x01040000 else 0x01050000) }
        val properties = ByteArrayOutputStream().apply {
            writeAscii("SND ")
            writeDffChunk("FS  ", ByteArrayOutputStream().apply { writeUInt32BigEndian(2_822_400) }.toByteArray())
            writeDffChunk("CHNL", byteArrayOf(0, 2) + "SLFTSRGT".toByteArray(Charsets.US_ASCII))
            writeDffChunk("CMPR", (if (dst) "DST " else "DSD ").toByteArray(Charsets.US_ASCII) + byteArrayOf(0))
        }
        val audio = if (dst) {
            ByteArrayOutputStream().apply {
                writeDffChunk("FRTE", ByteArrayOutputStream().apply {
                    writeUInt32BigEndian(75)
                    write(0)
                    write(75)
                }.toByteArray())
                writeDffChunk("DSTF", byteArrayOf(0x55, 0x2a))
            }.toByteArray()
        } else ByteArray(7_056) // 3,528 bytes/channel at 352,800 bytes/s = 10 ms.
        val form = ByteArrayOutputStream().apply {
            writeAscii("DSD ")
            writeDffChunk("FVER", version.toByteArray())
            writeDffChunk("PROP", properties.toByteArray())
            writeDffChunk(if (dst) "DST " else "DSD ", audio)
            id3Tag?.let { writeDffChunk("ID3 ", it) }
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            writeAscii("FRM8")
            writeUInt64BigEndian(form.size.toLong())
            write(form)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeDffChunk(id: String, data: ByteArray) {
        writeAscii(id)
        writeUInt64BigEndian(data.size.toLong())
        write(data)
        if (data.size and 1 != 0) write(0)
    }

    private fun id3v23WithExtraFrames(
        tags: List<Pair<String, String>>,
        extraFrames: List<Pair<String, ByteArray>> = emptyList(),
    ): ByteArray = id3Tag(3, tags, extraFrames) { value ->
        byteArrayOf(1) + byteArrayOf(0xfe.toByte(), 0xff.toByte()) + value.toByteArray(Charsets.UTF_16BE)
    }

    private fun id3v22WithExtraFrames(
        tags: List<Pair<String, String>>,
        extraFrames: List<Pair<String, ByteArray>> = emptyList(),
    ): ByteArray = id3Tag(2, tags, extraFrames) { value ->
        byteArrayOf(1) + byteArrayOf(0xfe.toByte(), 0xff.toByte()) + value.toByteArray(Charsets.UTF_16BE)
    }

    private fun id3v24WithExtraFrames(
        tags: List<Pair<String, String>>,
        extraFrames: List<Pair<String, ByteArray>> = emptyList(),
    ): ByteArray = id3Tag(4, tags, extraFrames) { value ->
        byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)
    }

    private fun id3UserTextFrame(
        encoding: Int,
        description: String,
        value: String,
        littleEndian: Boolean = false,
        valueBom: Boolean = true,
    ): ByteArray {
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> if (littleEndian) Charsets.UTF_16LE else Charsets.UTF_16BE
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> error("Unsupported fixture encoding: $encoding")
        }
        val descriptionBytes = description.toByteArray(charset)
        val valueBytes = value.toByteArray(charset)
        val bom = if (encoding == 1) {
            if (littleEndian) byteArrayOf(0xff.toByte(), 0xfe.toByte()) else byteArrayOf(0xfe.toByte(), 0xff.toByte())
        } else {
            byteArrayOf()
        }
        val terminator = if (encoding == 1 || encoding == 2) byteArrayOf(0, 0) else byteArrayOf(0)
        val valuePrefix = if (encoding == 1 && valueBom) bom else byteArrayOf()
        return byteArrayOf(encoding.toByte()) + bom + descriptionBytes + terminator + valuePrefix + valueBytes
    }

    private fun id3Tag(
        version: Int,
        tags: List<Pair<String, String>>,
        extraFrames: List<Pair<String, ByteArray>> = emptyList(),
        encodeText: (String) -> ByteArray,
    ): ByteArray {
        val body = ByteArrayOutputStream().apply {
            tags.forEach { (id, value) ->
                val frame = encodeText(value)
                writeId3Frame(version, id, frame)
            }
            extraFrames.forEach { (id, frame) -> writeId3Frame(version, id, frame) }
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            writeAscii("ID3")
            write(version)
            write(0)
            write(0)
            writeSynchsafeInt(body.size)
            write(body)
        }.toByteArray()
    }

    private fun id3UnsynchronizedTag(
        version: Int,
        frames: List<Pair<String, ByteArray>>,
        tagUnsynchronised: Boolean,
        frameUnsynchronised: Boolean = false,
    ): ByteArray {
        val frameData = ByteArrayOutputStream().apply {
            frames.forEach { (id, payload) ->
                val encodedPayload = if (version == 4 && (tagUnsynchronised || frameUnsynchronised)) {
                    encodeId3Unsynchronization(payload)
                } else {
                    payload
                }
                writeAscii(id)
                val declaredFrameSize = if (version == 4) encodedPayload.size else payload.size
                when (version) {
                    2 -> {
                        write((declaredFrameSize ushr 16) and 0xff)
                        write((declaredFrameSize ushr 8) and 0xff)
                        write(declaredFrameSize and 0xff)
                    }
                    3 -> writeUInt32BigEndian(declaredFrameSize)
                    else -> writeSynchsafeInt(declaredFrameSize)
                }
                if (version >= 3) {
                    write(0)
                    write(if (frameUnsynchronised) 0x02 else 0)
                }
                write(encodedPayload)
            }
        }.toByteArray()
        val encodedBody = if (tagUnsynchronised && version < 4) {
            encodeId3Unsynchronization(frameData)
        } else {
            frameData
        }
        return ByteArrayOutputStream().apply {
            writeAscii("ID3")
            write(version)
            write(0)
            write(if (tagUnsynchronised) 0x80 else 0)
            writeSynchsafeInt(encodedBody.size)
            write(encodedBody)
        }.toByteArray()
    }

    private fun id3TagWithExtendedHeader(version: Int, extendedHeader: ByteArray, frames: ByteArray): ByteArray {
        val body = extendedHeader + frames
        return ByteArrayOutputStream().apply {
            writeAscii("ID3")
            write(version)
            write(0)
            write(0x40)
            writeSynchsafeInt(body.size)
            write(body)
        }.toByteArray()
    }

    private fun encodeId3Unsynchronization(data: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        data.forEachIndexed { index, byte ->
            write(byte.toInt() and 0xff)
            val next = data.getOrNull(index + 1)?.toInt()?.and(0xff)
            if (byte == 0xff.toByte() && (next == null || next == 0 || next >= 0xe0)) write(0)
        }
    }.toByteArray()

    private fun ByteArrayOutputStream.writeInfoTag(id: String, value: String) {
        val bytes = (value + '\u0000').toByteArray(Charsets.UTF_8)
        writeChunk(id, bytes)
    }

    private fun ByteArrayOutputStream.writeChunk(id: String, data: ByteArray) {
        writeAscii(id)
        writeUInt32LittleEndian(data.size)
        write(data)
        if (data.size and 1 != 0) write(0)
    }

    private fun ByteArrayOutputStream.writeAscii(value: String) = write(value.toByteArray(Charsets.US_ASCII))

    private fun ByteArrayOutputStream.writeUInt16LittleEndian(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.writeUInt32LittleEndian(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }

    private data class ApeFixtureItem(val key: String, val value: ByteArray, val flags: Int)

    private fun apeTextItem(key: String, value: String): ApeFixtureItem =
        ApeFixtureItem(key, value.toByteArray(Charsets.UTF_8), flags = 0)

    private fun apeBinaryItem(key: String, value: ByteArray): ApeFixtureItem =
        ApeFixtureItem(key, value, flags = 2)

    private fun apeV2Tag(items: List<ApeFixtureItem>, headerPresent: Boolean = false): ByteArray {
        val itemData = ByteArrayOutputStream().apply {
            items.forEach { item ->
                val key = item.key.toByteArray(Charsets.US_ASCII)
                writeUInt32LittleEndian(item.value.size)
                writeUInt32LittleEndian(item.flags)
                write(key)
                write(0)
                write(item.value)
            }
        }.toByteArray()
        val tagSize = itemData.size + 32
        fun footer(isHeader: Boolean): ByteArray = ByteArrayOutputStream().apply {
            writeAscii("APETAGEX")
            writeUInt32LittleEndian(2_000)
            writeUInt32LittleEndian(tagSize)
            writeUInt32LittleEndian(items.size)
            val flags = (if (headerPresent) Int.MIN_VALUE else 0) or
                (if (isHeader) 0x2000_0000 else 0)
            writeUInt32LittleEndian(flags)
            write(ByteArray(8))
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            if (headerPresent) write(footer(isHeader = true))
            write(itemData)
            write(footer(isHeader = false))
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeUInt32BigEndian(value: Int) {
        write((value ushr 24) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun ByteArrayOutputStream.writeSynchsafeInt(value: Int) {
        write((value ushr 21) and 0x7f)
        write((value ushr 14) and 0x7f)
        write((value ushr 7) and 0x7f)
        write(value and 0x7f)
    }

    private fun ByteArrayOutputStream.writeId3Frame(version: Int, id: String, data: ByteArray) {
        writeAscii(id)
        if (version == 2) {
            write((data.size ushr 16) and 0xff)
            write((data.size ushr 8) and 0xff)
            write(data.size and 0xff)
        } else {
            if (version == 3) writeUInt32BigEndian(data.size) else writeSynchsafeInt(data.size)
            write(byteArrayOf(0, 0)) // Frame flags.
        }
        write(data)
    }

    private fun id3PictureFrame(
        pictureType: Int,
        mimeType: String,
        image: ByteArray,
        encoding: Int = 3,
        description: String = "fixture",
    ): ByteArray {
        val descriptionBytes = when (encoding) {
            0 -> description.toByteArray(Charsets.ISO_8859_1)
            1 -> byteArrayOf(0xfe.toByte(), 0xff.toByte()) + description.toByteArray(Charsets.UTF_16BE)
            2 -> description.toByteArray(Charsets.UTF_16BE)
            else -> description.toByteArray(Charsets.UTF_8)
        }
        val terminator = if (encoding == 1 || encoding == 2) byteArrayOf(0, 0) else byteArrayOf(0)
        return byteArrayOf(encoding.toByte()) + mimeType.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0, pictureType.toByte()) + descriptionBytes + terminator + image
    }

    private fun id3PicFrame(pictureType: Int, imageFormat: String, image: ByteArray): ByteArray =
        byteArrayOf(3) + imageFormat.toByteArray(Charsets.US_ASCII) + byteArrayOf(pictureType.toByte()) +
            "fixture".toByteArray(Charsets.UTF_8) + byteArrayOf(0) + image

    private fun pngFixture(): ByteArray = ByteArrayOutputStream().use { bytes ->
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", bytes)
        bytes.toByteArray()
    }

    private fun ByteArrayOutputStream.writeUInt64LittleEndian(value: Long) {
        repeat(8) { shift -> write(((value ushr (shift * 8)) and 0xff).toInt()) }
    }

    private fun ByteArrayOutputStream.writeUInt64BigEndian(value: Long) {
        repeat(8) { shift -> write(((value ushr ((7 - shift) * 8)) and 0xff).toInt()) }
    }
}
