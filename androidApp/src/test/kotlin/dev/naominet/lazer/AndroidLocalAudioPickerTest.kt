package dev.naominet.lazer

import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class AndroidLocalAudioPickerTest {
    @Test
    fun acceptsWavFlacAndDsdByExtensionOrKnownMimeType() {
        assertTrue(isSupportedAndroidLocalAudio("album track.WAV", null))
        assertTrue(isSupportedAndroidLocalAudio("disc.WaVe", "application/octet-stream"))
        assertTrue(isSupportedAndroidLocalAudio("disc.flac", "application/octet-stream"))
        assertTrue(isSupportedAndroidLocalAudio("track", "audio/x-flac"))
        assertTrue(isSupportedAndroidLocalAudio("album track.DSF", null))
        assertTrue(isSupportedAndroidLocalAudio("disc.dff", "application/octet-stream"))
        assertTrue(isSupportedAndroidLocalAudio("track", "audio/x-dsf"))
        assertTrue(isSupportedAndroidLocalAudio("track", "audio/x-dff"))
        assertTrue(isSupportedAndroidLocalAudio("track", "audio/x-dsd"))
    }

    @Test
    fun identifiesDsdInputsForNativeDurationFallback() {
        assertTrue(isAndroidDsdAudio("album.dsf", null))
        assertTrue(isAndroidDsdAudio("disc.DFF", "application/octet-stream"))
        assertTrue(isAndroidDsdAudio("track", "audio/x-dsd"))
        assertFalse(isAndroidDsdAudio("album.flac", "audio/flac"))
        assertFalse(isAndroidDsdAudio("track", "audio/x-sacd"))
    }

    @Test
    fun routesDsfAndDffReplayGainFormatsAndDetectsGenericDsdMime() {
        assertEquals("dsf", androidLocalAudioContainer("album.DSF", null))
        assertEquals("dff", androidLocalAudioContainer("disc.dff", "application/octet-stream"))
        assertEquals("dsf", androidLocalAudioContainer("track", "audio/x-dsf"))
        assertEquals("dff", androidLocalAudioContainer("track", "audio/x-dff"))
        assertEquals("dsd", androidLocalAudioContainer("track", "audio/x-dsd"))
    }

    @Test
    fun rejectsFormatsOutsideTheFirstLocalPlaybackSlice() {
        assertFalse(isSupportedAndroidLocalAudio("track.mp3", "audio/mpeg"))
        assertFalse(isSupportedAndroidLocalAudio("track", "audio/x-sacd"))
    }

    @Test
    fun readsWavId3ReplayGainWithoutReadingAudioPayload() {
        val tag = id3v23(
            txxx("REPLAYGAIN_TRACK_GAIN", "-7.25 dB"),
            txxx("REPLAYGAIN_TRACK_PEAK", "1.25"),
            txxx("REPLAYGAIN_ALBUM_GAIN", "+2.5DB"),
            txxx("REPLAYGAIN_ALBUM_PEAK", "0.9"),
        )
        val bytes = wav(
            chunk("data", ByteArray(512 * 1024)),
            chunk("ID3 ", tag),
        )
        val input = CountingSource(bytes)

        assertEquals(
            LazerReplayGainTags(-7.25, 1.25, 2.5, 0.9),
            AndroidLocalReplayGainReader.read(input, "wav"),
        )
        assertTrue(input.bytesRead < 8 * 1024, "ReplayGain parsing read audio payload bytes")

        assertEquals(
            LazerReplayGainTags(-7.25, 1.25, 2.5, 0.9),
            AndroidLocalReplayGainReader.read(
                CountingSource(wav(chunk("id3 ", tag))),
                "wav",
            ),
        )
    }

    @Test
    fun mergesDisplayTagsAndReplayGainAcrossWavId3Chunks() {
        val bytes = wav(
            chunk("ID3 ", id3v23(id3TextFrameV23("TIT2", "Split metadata title"))),
            chunk("data", ByteArray(256 * 1024)),
            chunk("id3 ", id3v24(txxx("REPLAYGAIN_TRACK_GAIN", "-3.5 dB"))),
        )
        val input = CountingSource(bytes)

        assertEquals(
            AndroidLocalAudioMetadata(
                title = "Split metadata title",
                replayGain = LazerReplayGainTags(trackGainDb = -3.5),
            ),
            AndroidLocalReplayGainReader.readMetadata(input, "wav"),
        )
        assertTrue(input.bytesRead < 8 * 1024, "WAV parsing read audio payload bytes")
    }

    @Test
    fun readsReplayGainFromId3v23AndId3v24UnsynchronisedFrames() {
        val ignoredUtf16Frame = txxxFrameV23(txxxPayload("OTHER", "value", encoding = 1))
        val gainUtf16Frame = txxxFrameV23(
            txxxPayload("REPLAYGAIN_TRACK_GAIN", "-6.0 dB", encoding = 1),
        )
        val peakUtf16Frame = txxxFrameV23(
            txxxPayload("REPLAYGAIN_TRACK_PEAK", "0.5", encoding = 1),
        )
        val id3v23TagWide = id3v23TagWideUnsynchronised(
            ignoredUtf16Frame + gainUtf16Frame + peakUtf16Frame,
        )
        assertEquals(
            LazerReplayGainTags(trackGainDb = -6.0, trackPeak = 0.5),
            AndroidLocalReplayGainReader.read(
                CountingSource(wav(chunk("ID3 ", id3v23TagWide))),
                "wav",
            ),
        )

        val id3v24TagWide = id3v24TxxxUnsynchronised(
            description = "REPLAYGAIN_TRACK_GAIN",
            value = "-7.0 dB",
            tagUnsynchronised = true,
        )
        assertEquals(
            LazerReplayGainTags(trackGainDb = -7.0),
            AndroidLocalReplayGainReader.read(
                CountingSource(wav(chunk("ID3 ", id3v24TagWide))),
                "wav",
            ),
        )

        val id3v24FrameWide = id3v24TxxxUnsynchronised(
            description = "REPLAYGAIN_ALBUM_GAIN",
            value = "-8.0 dB",
            tagUnsynchronised = false,
            frameUnsynchronised = true,
        )
        assertEquals(
            LazerReplayGainTags(albumGainDb = -8.0),
            AndroidLocalReplayGainReader.read(
                CountingSource(wav(chunk("ID3 ", id3v24FrameWide))),
                "wav",
            ),
        )
    }

    @Test
    fun readsDsfId3ReplayGainWithoutReadingDsdPayload() {
        val bytes = dsf(
            id3v23(
                txxx("REPLAYGAIN_TRACK_GAIN", "-5.5 dB"),
                txxx("REPLAYGAIN_TRACK_PEAK", "0.95"),
            ),
            audioPayload = ByteArray(512 * 1024),
        )
        val input = CountingSource(bytes)

        assertEquals(
            LazerReplayGainTags(trackGainDb = -5.5, trackPeak = 0.95),
            AndroidLocalReplayGainReader.read(input, "dsf"),
        )
        assertTrue(input.bytesRead < 8 * 1024, "DSF parsing read audio payload bytes")

        assertEquals(
            LazerReplayGainTags(trackGainDb = -5.5, trackPeak = 0.95),
            AndroidLocalReplayGainReader.read(CountingSource(bytes), "dsd"),
        )
    }

    @Test
    fun readsDsfId3DisplayMetadataAndReplayGainWithoutReadingDsdPayload() {
        val bytes = dsf(
            id3v23(
                id3TextFrameV23("TIT2", "DSF 标题"),
                id3TextFrameV23("TPE1", "艺人甲\u0000艺人乙", encoding = 1),
                id3TextFrameV23("TALB", "Café", encoding = 0),
                txxx("REPLAYGAIN_TRACK_GAIN", "-5.5 dB"),
            ),
            audioPayload = ByteArray(512 * 1024),
        )
        val input = CountingSource(bytes)

        assertEquals(
            AndroidLocalAudioMetadata(
                title = "DSF 标题",
                artist = "艺人甲; 艺人乙",
                album = "Café",
                replayGain = LazerReplayGainTags(trackGainDb = -5.5),
            ),
            AndroidLocalReplayGainReader.readMetadata(input, "dsf"),
        )
        assertTrue(input.bytesRead < 8 * 1024, "DSF metadata parsing read audio payload bytes")
    }

    @Test
    fun readsExtendedId3AndVorbisMetadataWithBoundedTrackAndDiscNumbers() {
        val id3 = wav(
            chunk(
                "ID3 ",
                id3v24(
                    id3TextFrameV24("TPE2", "Album artist", encoding = 1),
                    id3TextFrameV24("TCON", "Progressive rock"),
                    id3TextFrameV24("TYER", "1998"),
                    id3TextFrameV24("TRCK", "03/12"),
                    id3TextFrameV24("TPOS", "2/3"),
                    id3TextFrameV24("TDRC", "2001-04-03"),
                ),
            ),
        )
        assertEquals(
            AndroidLocalAudioMetadata(
                albumArtist = "Album artist",
                genre = "Progressive rock",
                year = 2001,
                trackNumber = 3,
                totalTracks = 12,
                discNumber = 2,
                totalDiscs = 3,
            ),
            AndroidLocalReplayGainReader.readMetadata(CountingSource(id3), "wav"),
        )

        val vorbis = flac(
            block(
                type = 4,
                last = true,
                payload = vorbisComments(
                    "ALBUM ARTIST=FLAC album artist",
                    "GENRE=Jazz",
                    "GENRE=Ignored duplicate",
                    "YEAR=not-a-year",
                    "DATE=1977-02-18",
                    "TRACKNUMBER=0",
                    "TRACKNUMBER=4",
                    "TRACKTOTAL=3",
                    "TRACKTOTAL=9",
                    "DISCNUMBER=0/2",
                    "DISCNUMBER=2/3",
                ),
            ),
        )
        assertEquals(
            AndroidLocalAudioMetadata(
                albumArtist = "FLAC album artist",
                genre = "Jazz",
                year = 1977,
                trackNumber = 4,
                totalTracks = 9,
                discNumber = 2,
                totalDiscs = 3,
            ),
            AndroidLocalReplayGainReader.readMetadata(CountingSource(vorbis), "flac"),
        )
    }

    @Test
    fun readsDffReplayGainAfterRawAndDstAudioChunksWithPadding() {
        val tag = id3v24(
            txxx("REPLAYGAIN_ALBUM_GAIN", "-8.25 dB"),
            txxx("REPLAYGAIN_ALBUM_PEAK", "0.8"),
        )
        for (audioChunkId in listOf("DSD ", "DST ")) {
            val input = CountingSource(
                dff(
                    audioChunkId,
                    ByteArray(512 * 1024 + 1),
                    tag,
                    dffChunk("FVER", byteArrayOf(1, 2, 3)),
                ),
            )
            assertEquals(
                LazerReplayGainTags(albumGainDb = -8.25, albumPeak = 0.8),
                AndroidLocalReplayGainReader.read(input, "dff"),
                "ReplayGain was not read after $audioChunkId audio",
            )
            assertTrue(input.bytesRead < 8 * 1024, "$audioChunkId payload was read into memory")
        }
    }

    @Test
    fun readsDffId3DisplayMetadataAfterLargeDsdAndDstChunks() {
        val tag = id3v24(
            id3TextFrameV24("TIT2", "DFF title"),
            id3TextFrameV24("TPE1", "DFF artist"),
            id3TextFrameV24("TALB", "DFF album"),
        )
        for (audioChunkId in listOf("DSD ", "DST ")) {
            val input = CountingSource(
                dff(
                    audioChunkId,
                    ByteArray(512 * 1024 + 1),
                    tag,
                ),
            )
            assertEquals(
                AndroidLocalAudioMetadata(
                    title = "DFF title",
                    artist = "DFF artist",
                    album = "DFF album",
                ),
                AndroidLocalReplayGainReader.readMetadata(input, "dff"),
                "ID3 metadata was not read after $audioChunkId audio",
            )
            assertTrue(input.bytesRead < 8 * 1024, "$audioChunkId payload was read into memory")
        }
    }

    @Test
    fun rejectsMalformedDsfPointersAndDffChunkBounds() {
        val validDsf = dsf(id3v23(txxx("REPLAYGAIN_TRACK_GAIN", "-4 dB")))
        val dataOffset = 28L + 52L
        val audioEndOffset = dataOffset + 12L + 64L
        assertNull(AndroidLocalReplayGainReader.read(CountingSource(withUInt64Le(validDsf, 20, 0)), "dsf"))
        assertNull(AndroidLocalReplayGainReader.read(CountingSource(withUInt64Le(validDsf, 20, audioEndOffset - 1)), "dsf"))
        assertNull(AndroidLocalReplayGainReader.read(CountingSource(withUInt64Le(validDsf, 20, validDsf.size.toLong() + 10)), "dsf"))

        val invalidTagSize = "ID3".ascii() + byteArrayOf(4, 0, 0) + synchsafe(1_048_577)
        assertNull(AndroidLocalReplayGainReader.read(CountingSource(dsf(invalidTagSize)), "dsf"))

        val validDff = dff("DSD ", ByteArray(64), id3v23(txxx("REPLAYGAIN_TRACK_GAIN", "-4 dB")))
        assertNull(AndroidLocalReplayGainReader.read(CountingSource(withUInt64Be(validDff, 4, Long.MAX_VALUE)), "dff"))
        assertNull(AndroidLocalReplayGainReader.read(CountingSource(validDff.copyOf(validDff.size - 1)), "dff"))
    }

    @Test
    fun readsFlacVorbisCommentReplayGainAndStopsAtMetadataEnd() {
        val comments = vorbisComments(
            "REPLAYGAIN_TRACK_GAIN=-4.0 dB",
            "REPLAYGAIN_TRACK_PEAK=0.5",
            "REPLAYGAIN_ALBUM_GAIN=-8.5 dB",
            "REPLAYGAIN_ALBUM_PEAK=0.75",
        )
        val bytes = flac(
            block(type = 0, last = false, payload = ByteArray(34)),
            block(type = 4, last = true, payload = comments),
            ByteArray(512 * 1024),
        )
        val input = CountingSource(bytes)

        assertEquals(
            LazerReplayGainTags(-4.0, 0.5, -8.5, 0.75),
            AndroidLocalReplayGainReader.read(input, "flac"),
        )
        assertTrue(input.bytesRead < 8 * 1024, "FLAC metadata parsing read audio payload bytes")
    }

    @Test
    fun readsFlacDisplayMetadataAndReplayGainFromVorbisComments() {
        val bytes = flac(
            block(
                type = 4,
                last = true,
                payload = vorbisComments(
                    "TITLE=Flac title",
                    "ARTIST=Flac artist",
                    "ALBUM=Flac album",
                    "REPLAYGAIN_TRACK_GAIN=-4.0 dB",
                ),
            ),
        )

        assertEquals(
            AndroidLocalAudioMetadata(
                title = "Flac title",
                artist = "Flac artist",
                album = "Flac album",
                replayGain = LazerReplayGainTags(trackGainDb = -4.0),
            ),
            AndroidLocalReplayGainReader.readMetadata(CountingSource(bytes), "flac"),
        )
    }

    @Test
    fun readsApeV2AsWavFallbackAndSkipsLargePayloads() {
        val apeTag = apeV2(
            ApeItem.text("TITLE", "APE title must not replace WAV title"),
            ApeItem.text("ARTIST", "APE 艺人"),
            ApeItem.text("ALBUM", "APE album"),
            ApeItem.text("ALBUM ARTIST", "APE album artist"),
            ApeItem.text("GENRE", "Classical"),
            ApeItem.text("YEAR", "1965"),
            ApeItem.text("TRACK", "7/10"),
            ApeItem.text("DISC", "2/2"),
            ApeItem.text("REPLAYGAIN_TRACK_GAIN", "-12 dB"),
            ApeItem.text("REPLAYGAIN_TRACK_PEAK", "0.75"),
            ApeItem.text("REPLAYGAIN_ALBUM_GAIN", "-8.5 dB"),
            ApeItem("COVER ART (FRONT)", ByteArray(512 * 1024), flags = 2L),
            includeHeader = true,
        )
        val primaryId3 = id3v23(
            id3TextFrameV23("TIT2", "WAV title"),
            txxx("REPLAYGAIN_TRACK_GAIN", "-3 dB"),
        )
        val bytes = wav(
            chunk("data", ByteArray(512 * 1024)),
            chunk("ID3 ", primaryId3),
        ) + apeTag + id3v1()
        val input = CountingSource(bytes)

        assertEquals(
            AndroidLocalAudioMetadata(
                title = "WAV title",
                artist = "APE 艺人",
                album = "APE album",
                replayGain = LazerReplayGainTags(-3.0, 0.75, -8.5),
                albumArtist = "APE album artist",
                genre = "Classical",
                year = 1965,
                trackNumber = 7,
                totalTracks = 10,
                discNumber = 2,
                totalDiscs = 2,
            ),
            AndroidLocalReplayGainReader.readMetadata(input, "wav"),
        )
        assertTrue(input.bytesRead < 8 * 1024, "WAV/APE parsing read audio or artwork payload bytes")
    }

    @Test
    fun mergesApeV2FallbackIntoFlacDsfAndDffMetadata() {
        val apeTag = apeV2(
            ApeItem.text("TITLE", "APE title must not replace container title"),
            ApeItem.text("ARTIST", "APE 艺人"),
            ApeItem.text("ALBUM", "APE album"),
            ApeItem.text("REPLAYGAIN_TRACK_GAIN", "-4.5 dB"),
        )
        val inputs = listOf(
            Triple(
                "flac",
                flac(
                    block(
                        type = 4,
                        last = true,
                        payload = vorbisComments("TITLE=FLAC title"),
                    ),
                    ByteArray(256 * 1024),
                ) + apeTag,
                "FLAC title",
            ),
            Triple(
                "dsf",
                dsf(
                    id3v23(id3TextFrameV23("TIT2", "DSF title")),
                    audioPayload = ByteArray(512 * 1024),
                ) + apeTag,
                "DSF title",
            ),
            Triple(
                "dff",
                dff(
                    "DST ",
                    ByteArray(512 * 1024 + 1),
                    id3v24(id3TextFrameV24("TIT2", "DFF title")),
                ) + apeTag,
                "DFF title",
            ),
        )

        for ((format, bytes, containerTitle) in inputs) {
            val input = CountingSource(bytes)
            assertEquals(
                AndroidLocalAudioMetadata(
                    title = containerTitle,
                    artist = "APE 艺人",
                    album = "APE album",
                    replayGain = LazerReplayGainTags(trackGainDb = -4.5),
                ),
                AndroidLocalReplayGainReader.readMetadata(input, format),
                "$format metadata did not preserve container priority and use APE fallback",
            )
            assertTrue(input.bytesRead < 8 * 1024, "$format/APE parsing read audio payload bytes")
        }
    }

    @Test
    fun ignoresMalformedApeV2FootersAndItemLengths() {
        val valid = apeV2(ApeItem.text("TITLE", "APE title"))
        val oversizedTag = withUInt32Le(valid, valid.size - 20, 4L * 1024 * 1024 + 1)
        val oversizedItem = withUInt32Le(valid, 0, 0xffff_ffffL)

        assertNull(AndroidLocalReplayGainReader.readMetadata(CountingSource(oversizedTag), "wav"))
        assertNull(AndroidLocalReplayGainReader.readMetadata(CountingSource(oversizedItem), "wav"))
        assertNull(AndroidLocalReplayGainReader.readMetadata(CountingSource(valid.copyOf(valid.size - 1)), "wav"))
    }

    @Test
    fun ignoresMalformedContainerAndId3Lengths() {
        val malformedRiff = "RIFF".ascii() + uint32Le(12) + "WAVE".ascii() +
            "ID3 ".ascii() + uint32Le(0xffff_ffffL)
        val malformedFrame = "TXXX".ascii() + uint32Be(64) + byteArrayOf(0, 0)
        val malformedId3 = id3v23Body(malformedFrame)
        val malformedFlac = "fLaC".ascii() +
            byteArrayOf(0x84.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte())
        val malformedComments = flac(
            block(type = 4, last = true, payload = uint32Le(0xffff_ffffL)),
        )

        assertNull(AndroidLocalReplayGainReader.read(CountingSource(malformedRiff), "wav"))
        assertNull(AndroidLocalReplayGainReader.read(CountingSource(wav(chunk("ID3 ", malformedId3))), "wav"))
        assertNull(AndroidLocalReplayGainReader.read(CountingSource(malformedFlac), "flac"))
        assertNull(AndroidLocalReplayGainReader.read(CountingSource(malformedComments), "flac"))
    }

    @Test
    fun ignoresInvalidReplayGainValuesAndPreservesPickerMetadataFields() {
        val invalidWav = wav(
            chunk(
                "ID3 ",
                id3v24(
                    txxx("REPLAYGAIN_TRACK_GAIN", "NaN"),
                    txxx("REPLAYGAIN_TRACK_PEAK", "0"),
                    txxx("REPLAYGAIN_ALBUM_GAIN", "24.001 dB"),
                    txxx("REPLAYGAIN_ALBUM_PEAK", "Infinity"),
                ),
            ),
        )
        val invalidFlac = flac(
            block(
                type = 4,
                last = true,
                payload = vorbisComments("REPLAYGAIN_TRACK_GAIN=Infinity", "REPLAYGAIN_TRACK_PEAK=0"),
            ),
        )
        val existingMetadata = LazerPickedAudioFile(
            uri = "content://audio/track.wav",
            title = "Existing title",
            artist = "Existing artist",
            album = "Existing album",
            durationMillis = 12_345L,
        )

        assertNull(AndroidLocalReplayGainReader.read(CountingSource(invalidWav), "wav"))
        assertNull(AndroidLocalReplayGainReader.read(CountingSource(invalidFlac), "flac"))
        val validMetadata = existingMetadata.copy(
            replayGain = AndroidLocalReplayGainReader.read(
                CountingSource(
                    wav(chunk("ID3 ", id3v23(txxx("REPLAYGAIN_TRACK_GAIN", "-6 dB"), txxx("REPLAYGAIN_TRACK_PEAK", "0.5")))),
                ),
                "wav",
            ),
        )
        assertEquals("Existing title", validMetadata.title)
        assertEquals("Existing artist", validMetadata.artist)
        assertEquals("Existing album", validMetadata.album)
        assertEquals(12_345L, validMetadata.durationMillis)
        assertEquals(LazerReplayGainTags(-6.0, 0.5), validMetadata.replayGain)
    }

    private fun txxx(description: String, value: String): ByteArray =
        txxxFrameV23(txxxPayload(description, value))

    private fun txxxPayload(description: String, value: String, encoding: Int = 3): ByteArray {
        val (descriptionBytes, separator, valueBytes) = when (encoding) {
            0 -> Triple(
                description.toByteArray(Charsets.ISO_8859_1),
                byteArrayOf(0),
                value.toByteArray(Charsets.ISO_8859_1),
            )
            1 -> Triple(
                byteArrayOf(0xff.toByte(), 0xfe.toByte()) + description.toByteArray(Charsets.UTF_16LE),
                byteArrayOf(0, 0),
                value.toByteArray(Charsets.UTF_16LE),
            )
            2 -> Triple(
                description.toByteArray(Charsets.UTF_16BE),
                byteArrayOf(0, 0),
                value.toByteArray(Charsets.UTF_16BE),
            )
            else -> Triple(
                description.toByteArray(Charsets.UTF_8),
                byteArrayOf(0),
                value.toByteArray(Charsets.UTF_8),
            )
        }
        return byteArrayOf(encoding.toByte()) + descriptionBytes + separator + valueBytes
    }

    private fun txxxFrameV23(payload: ByteArray): ByteArray {
        return "TXXX".ascii() + uint32Be(payload.size.toLong()) + byteArrayOf(0, 0) + payload
    }

    private fun id3TextFrameV23(id: String, vararg values: String, encoding: Int = 3): ByteArray {
        val payload = id3TextPayload(values.toList(), encoding)
        return id.ascii() + uint32Be(payload.size.toLong()) + byteArrayOf(0, 0) + payload
    }

    private fun id3TextFrameV24(id: String, vararg values: String, encoding: Int = 3): ByteArray {
        val payload = id3TextPayload(values.toList(), encoding)
        return id.ascii() + synchsafe(payload.size) + byteArrayOf(0, 0) + payload
    }

    private fun id3TextPayload(values: List<String>, encoding: Int): ByteArray {
        val text = values.joinToString("\u0000")
        return when (encoding) {
            0 -> byteArrayOf(0) + text.toByteArray(Charsets.ISO_8859_1)
            1 -> byteArrayOf(1, 0xff.toByte(), 0xfe.toByte()) + text.toByteArray(Charsets.UTF_16LE)
            2 -> byteArrayOf(2) + text.toByteArray(Charsets.UTF_16BE)
            else -> byteArrayOf(3) + text.toByteArray(Charsets.UTF_8)
        }
    }

    private fun id3v23(vararg frames: ByteArray): ByteArray = id3v23Body(frames.concat())

    private data class ApeItem(
        val key: String,
        val value: ByteArray,
        val flags: Long = 0L,
    ) {
        companion object {
            fun text(key: String, value: String) = ApeItem(key, value.toByteArray(Charsets.UTF_8))
        }
    }

    private fun apeV2(vararg items: ApeItem, includeHeader: Boolean = false): ByteArray {
        val itemBytes = ByteArrayOutputStream().also { output ->
            items.forEach { item ->
                output.write(uint32Le(item.value.size.toLong()))
                output.write(uint32Le(item.flags))
                output.write(item.key.ascii())
                output.write(0)
                output.write(item.value)
            }
        }.toByteArray()
        val tagSize = itemBytes.size.toLong() + 32L
        fun descriptor(flags: Long) =
            "APETAGEX".ascii() + uint32Le(2_000) + uint32Le(tagSize) +
                uint32Le(items.size.toLong()) + uint32Le(flags) + ByteArray(8)

        val header = if (includeHeader) descriptor(0xa000_0000L) else byteArrayOf()
        val footer = descriptor(if (includeHeader) 0x8000_0000L else 0L)
        return header + itemBytes + footer
    }

    private fun id3v1(): ByteArray = "TAG".ascii() + ByteArray(125)

    private fun id3v24(vararg frames: ByteArray): ByteArray {
        val body = frames.concat()
        return "ID3".ascii() + byteArrayOf(4, 0, 0) + synchsafe(body.size) + body
    }

    private fun id3v23Body(body: ByteArray): ByteArray =
        "ID3".ascii() + byteArrayOf(3, 0, 0) + synchsafe(body.size) + body

    private fun id3v23TagWideUnsynchronised(body: ByteArray): ByteArray {
        val encodedBody = encodeId3Unsynchronization(body)
        return "ID3".ascii() + byteArrayOf(3, 0, 0x80.toByte()) +
            synchsafe(encodedBody.size) + encodedBody
    }

    private fun id3v24TxxxUnsynchronised(
        description: String,
        value: String,
        tagUnsynchronised: Boolean,
        frameUnsynchronised: Boolean = false,
    ): ByteArray {
        val payload = txxxPayload(description, value, encoding = 1)
        val encodedPayload = if (tagUnsynchronised || frameUnsynchronised) {
            encodeId3Unsynchronization(payload)
        } else {
            payload
        }
        val frameFlags = if (frameUnsynchronised) 0x02 else 0
        val frame = "TXXX".ascii() + synchsafe(encodedPayload.size) +
            byteArrayOf(0, frameFlags.toByte()) + encodedPayload
        val tagFlags = if (tagUnsynchronised) 0x80.toByte() else 0
        return "ID3".ascii() + byteArrayOf(4, 0, tagFlags) + synchsafe(frame.size) + frame
    }

    private fun encodeId3Unsynchronization(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        data.forEachIndexed { index, byte ->
            out.write(byte.toInt() and 0xff)
            val next = data.getOrNull(index + 1)?.toInt()?.and(0xff)
            if (byte == 0xff.toByte() && (next == null || next == 0 || next >= 0xe0)) out.write(0)
        }
        return out.toByteArray()
    }

    private fun wav(vararg chunks: ByteArray): ByteArray {
        val waveBody = "WAVE".ascii() + chunks.concat()
        return "RIFF".ascii() + uint32Le(waveBody.size.toLong()) + waveBody
    }

    private fun dsf(id3: ByteArray, audioPayload: ByteArray = ByteArray(64)): ByteArray {
        val formatChunk = "fmt ".ascii() + uint64Le(52) + ByteArray(40)
        val dataChunk = "data".ascii() + uint64Le(12L + audioPayload.size) + audioPayload
        val metadataOffset = 28L + formatChunk.size + dataChunk.size
        val fileSize = metadataOffset + id3.size
        val header = "DSD ".ascii() + uint64Le(28) + uint64Le(fileSize) + uint64Le(metadataOffset)
        return header + formatChunk + dataChunk + id3
    }

    private fun dff(
        audioChunkId: String,
        audioPayload: ByteArray,
        id3: ByteArray,
        prefixChunks: ByteArray = byteArrayOf(),
    ): ByteArray {
        val chunks = prefixChunks + dffChunk(audioChunkId, audioPayload) + dffChunk("ID3 ", id3)
        val form = "DSD ".ascii() + chunks
        return "FRM8".ascii() + uint64Be(form.size.toLong()) + form
    }

    private fun dffChunk(id: String, payload: ByteArray): ByteArray =
        id.ascii() + uint64Be(payload.size.toLong()) + payload +
            if (payload.size % 2 == 0) byteArrayOf() else byteArrayOf(0)

    private fun withUInt64Le(bytes: ByteArray, offset: Int, value: Long): ByteArray =
        bytes.copyOf().also { uint64Le(value).copyInto(it, offset) }

    private fun withUInt32Le(bytes: ByteArray, offset: Int, value: Long): ByteArray =
        bytes.copyOf().also { uint32Le(value).copyInto(it, offset) }

    private fun withUInt64Be(bytes: ByteArray, offset: Int, value: Long): ByteArray =
        bytes.copyOf().also { uint64Be(value).copyInto(it, offset) }

    private fun chunk(id: String, payload: ByteArray): ByteArray =
        id.ascii() + uint32Le(payload.size.toLong()) + payload +
            if (payload.size % 2 == 0) byteArrayOf() else byteArrayOf(0)

    private fun block(type: Int, last: Boolean, payload: ByteArray): ByteArray {
        val header = type or (if (last) 0x80 else 0)
        val size = payload.size
        return byteArrayOf(
            header.toByte(),
            (size ushr 16).toByte(),
            (size ushr 8).toByte(),
            size.toByte(),
        ) + payload
    }

    private fun flac(vararg parts: ByteArray): ByteArray = "fLaC".ascii() + parts.concat()

    private fun vorbisComments(vararg entries: String): ByteArray {
        val vendor = "Lazer test".toByteArray()
        val out = ByteArrayOutputStream()
        out.write(uint32Le(vendor.size.toLong()))
        out.write(vendor)
        out.write(uint32Le(entries.size.toLong()))
        entries.forEach { entry ->
            val bytes = entry.toByteArray()
            out.write(uint32Le(bytes.size.toLong()))
            out.write(bytes)
        }
        return out.toByteArray()
    }

    private fun synchsafe(value: Int): ByteArray = byteArrayOf(
        ((value ushr 21) and 0x7f).toByte(),
        ((value ushr 14) and 0x7f).toByte(),
        ((value ushr 7) and 0x7f).toByte(),
        (value and 0x7f).toByte(),
    )

    private fun uint32Le(value: Long): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
    )

    private fun uint32Be(value: Long): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun uint64Le(value: Long): ByteArray = byteArrayOf(
        value.toByte(),
        (value ushr 8).toByte(),
        (value ushr 16).toByte(),
        (value ushr 24).toByte(),
        (value ushr 32).toByte(),
        (value ushr 40).toByte(),
        (value ushr 48).toByte(),
        (value ushr 56).toByte(),
    )

    private fun uint64Be(value: Long): ByteArray = byteArrayOf(
        (value ushr 56).toByte(),
        (value ushr 48).toByte(),
        (value ushr 40).toByte(),
        (value ushr 32).toByte(),
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun String.ascii(): ByteArray = toByteArray(Charsets.US_ASCII)

    private fun Array<out ByteArray>.concat(): ByteArray {
        val out = ByteArrayOutputStream(sumOf { it.size })
        forEach { out.write(it) }
        return out.toByteArray()
    }

    private class CountingSource(private val bytes: ByteArray) : AndroidLocalAudioReadSource {
        override val length: Long = bytes.size.toLong()
        var bytesRead: Long = 0L
            private set

        override fun readAt(position: Long, destination: ByteArray, offset: Int, byteCount: Int): Int {
            if (position < 0L || position >= length) return -1
            val count = minOf(byteCount.toLong(), length - position).toInt()
            bytes.copyInto(destination, offset, position.toInt(), position.toInt() + count)
            bytesRead += count
            return count
        }
    }
}
