package dev.naominet.lazer

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

private val supportedLocalAudioExtensions = setOf("wav", "flac", "dsf", "dff")
private val supportedDsdLocalAudioExtensions = setOf("dsf", "dff")

internal data class DesktopLocalAudioMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMillis: Long? = null,
    val embeddedArtwork: DesktopEmbeddedArtwork? = null,
    val replayGain: DesktopReplayGainTags? = null,
)

data class DesktopReplayGainTags(
    val trackGainDb: Double?,
    val trackPeak: Double?,
    val albumGainDb: Double?,
    val albumPeak: Double?,
)

internal data class DesktopEmbeddedArtwork(
    val mimeType: String,
    val pictureType: Long,
    val data: ByteArray,
)

private const val MAX_LOCAL_TAG_BYTES = 1_048_576
private const val MAX_LOCAL_ARTWORK_BYTES = 12 * 1_048_576
private const val MAX_LOCAL_ID3_TAG_BYTES = MAX_LOCAL_ARTWORK_BYTES + 4 * MAX_LOCAL_TAG_BYTES + 64 * 1_024
private const val MAX_LOCAL_APE_TAG_BYTES = MAX_LOCAL_ARTWORK_BYTES + 4 * MAX_LOCAL_TAG_BYTES + 64 * 1_024
private const val MAX_LOCAL_APE_ITEM_COUNT = 10_000L
private const val MAX_LOCAL_ARTWORK_CACHE_BYTES = 256L * 1_048_576
private const val APE_FOOTER_SIZE = 32L
private const val APE_FLAG_HEADER_PRESENT = 0x8000_0000L
private const val APE_FLAG_NO_FOOTER = 0x4000_0000L
private const val APE_FLAG_IS_HEADER = 0x2000_0000L

internal fun isSupportedLocalAudioFile(file: File): Boolean =
    file.extension.lowercase() in supportedLocalAudioExtensions

internal fun isDsdLocalAudioFile(file: File): Boolean = file.extension.lowercase() in supportedDsdLocalAudioExtensions

/** Opens a local file as an absolute-position reader for native queued-source preparation. */
internal fun openLocalSeekableAudioSource(file: File): DesktopSeekableAudioSource {
    require(file.isFile && file.canRead()) { "本地音频文件不存在或不可读取：${file.name}" }
    return LocalFileSeekableAudioSource(RandomAccessFile(file, "r"))
}

private class LocalFileSeekableAudioSource(
    private val input: RandomAccessFile,
) : DesktopSeekableAudioSource {
    private var closed = false

    @Synchronized
    override fun read(buffer: ByteArray, length: Int): Int {
        if (closed) return -1
        require(length in 0..buffer.size)
        if (length == 0) return 0
        return input.read(buffer, 0, length)
    }

    @Synchronized
    override fun seek(offset: Long): Long {
        if (closed || offset < 0L || offset > input.length()) return -1L
        input.seek(offset)
        return offset
    }

    @Synchronized
    override fun length(): Long = if (closed) -1L else input.length()

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        input.close()
    }
}

/** Reads basic display tags and duration without decoding the audio payload. */
internal fun readLocalAudioMetadata(file: File): DesktopLocalAudioMetadata? = runCatching {
    RandomAccessFile(file, "r").use { input ->
        val container = when (file.extension.lowercase()) {
            "wav" -> readWaveMetadata(input)
            "flac" -> readFlacMetadata(input)
            "dsf" -> readDsfMetadata(input)
            "dff" -> readDffMetadata(input)
            else -> null
        }
        container?.withApeV2Fallback(readApeV2Tags(input))
    }
}.getOrNull()

private fun DesktopLocalAudioMetadata.withApeV2Fallback(ape: ParsedApeV2Tag?): DesktopLocalAudioMetadata {
    if (ape == null) return this
    val currentGain = replayGain
    val apeGain = ape.replayGain
    val mergedGain = if (currentGain == null && apeGain == null) {
        null
    } else {
        DesktopReplayGainTags(
            trackGainDb = currentGain?.trackGainDb ?: apeGain?.trackGainDb,
            trackPeak = currentGain?.trackPeak ?: apeGain?.trackPeak,
            albumGainDb = currentGain?.albumGainDb ?: apeGain?.albumGainDb,
            albumPeak = currentGain?.albumPeak ?: apeGain?.albumPeak,
        )
    }
    return copy(
        title = title?.takeIf(String::isNotBlank) ?: ape.title,
        artist = artist?.takeIf(String::isNotBlank) ?: ape.artist,
        album = album?.takeIf(String::isNotBlank) ?: ape.album,
        embeddedArtwork = embeddedArtwork ?: ape.artwork,
        replayGain = mergedGain,
    )
}

private data class ParsedApeV2Tag(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val artwork: DesktopEmbeddedArtwork? = null,
    val replayGain: DesktopReplayGainTags? = null,
)

/** Reads only a bounded APEv2 footer at EOF or immediately before an ID3v1 trailer. */
private fun readApeV2Tags(input: RandomAccessFile): ParsedApeV2Tag? = runCatching {
    val fileLength = input.length()
    if (fileLength < APE_FOOTER_SIZE) return@runCatching null

    val footerOffsets = buildList {
        add(fileLength - APE_FOOTER_SIZE)
        if (fileLength >= 128L + APE_FOOTER_SIZE) {
            input.seek(fileLength - 128L)
            if (input.readAscii(3) == "TAG") add(fileLength - 128L - APE_FOOTER_SIZE)
        }
    }
    footerOffsets.firstNotNullOfOrNull { footerOffset ->
        runCatching { readApeV2TagAtFooter(input, fileLength, footerOffset) }.getOrNull()
    }
}.getOrNull()

private fun readApeV2TagAtFooter(
    input: RandomAccessFile,
    fileLength: Long,
    footerOffset: Long,
): ParsedApeV2Tag? {
    if (footerOffset < 0L || footerOffset > fileLength - APE_FOOTER_SIZE) return null
    input.seek(footerOffset)
    if (input.readAscii(8) != "APETAGEX") return null
    val version = input.readUInt32LittleEndian()
    val tagSize = input.readUInt32LittleEndian()
    val itemCount = input.readUInt32LittleEndian()
    val footerFlags = input.readUInt32LittleEndian()
    input.readUInt64LittleEndian() // Reserved.
    val headerPresent = footerFlags and APE_FLAG_HEADER_PRESENT != 0L
    if (version != 2_000L || tagSize !in APE_FOOTER_SIZE..MAX_LOCAL_APE_TAG_BYTES.toLong() ||
        itemCount !in 1L..MAX_LOCAL_APE_ITEM_COUNT || footerFlags and APE_FLAG_NO_FOOTER != 0L ||
        footerFlags and APE_FLAG_IS_HEADER != 0L
    ) {
        return null
    }

    // APEv2 tagSize includes item bytes and the footer, but excludes the optional header.
    val itemStart = footerOffset + APE_FOOTER_SIZE - tagSize
    val itemEnd = footerOffset
    if (itemStart < 0L || itemStart > itemEnd) return null
    if (headerPresent) {
        val headerOffset = itemStart - APE_FOOTER_SIZE
        if (headerOffset < 0L) return null
        input.seek(headerOffset)
        if (input.readAscii(8) != "APETAGEX" || input.readUInt32LittleEndian() != version ||
            input.readUInt32LittleEndian() != tagSize || input.readUInt32LittleEndian() != itemCount
        ) {
            return null
        }
        val headerFlags = input.readUInt32LittleEndian()
        if (headerFlags and (APE_FLAG_HEADER_PRESENT or APE_FLAG_NO_FOOTER or APE_FLAG_IS_HEADER) !=
            (APE_FLAG_HEADER_PRESENT or APE_FLAG_IS_HEADER)
        ) {
            return null
        }
        input.readUInt64LittleEndian() // Reserved.
    }

    val displayTags = linkedMapOf<String, String>()
    val replayGainValues = linkedMapOf<String, Double>()
    var artwork: DesktopEmbeddedArtwork? = null
    var cursor = itemStart
    repeat(itemCount.toInt()) {
        if (itemEnd - cursor < 11L) return null
        input.seek(cursor)
        val valueLength = input.readUInt32LittleEndian()
        val itemFlags = input.readUInt32LittleEndian()
        val keyStart = cursor + 8L
        val keyLimit = keyStart + minOf(itemEnd - keyStart, 256L)
        val keyBytes = ByteArray(255)
        var keyLength = 0
        var hasKeyTerminator = false
        while (input.filePointer < keyLimit) {
            val next = input.readUnsignedByte()
            if (next == 0) {
                hasKeyTerminator = true
                break
            }
            if (next !in 0x20..0x7e) return null
            if (keyLength == keyBytes.size) return null
            keyBytes[keyLength++] = next.toByte()
        }
        if (!hasKeyTerminator || keyLength !in 2..255) return null
        val key = String(keyBytes, 0, keyLength, Charsets.US_ASCII).uppercase()
        val valueStart = input.filePointer
        if (valueLength > itemEnd - valueStart) return null
        val itemType = (itemFlags ushr 1) and 0x3L
        val textKey = key in apeDisplayTagKeys || key in flacReplayGainTagKeys
        if (itemType == 0L && textKey && valueLength in 1L..MAX_LOCAL_TAG_BYTES.toLong()) {
            val valueBytes = ByteArray(valueLength.toInt())
            input.readFully(valueBytes)
            val value = decodeApeTextValue(valueBytes)
            if (value != null) {
                when (key) {
                    "TITLE" -> displayTags.putIfAbsent(key, value)
                    "ARTIST" -> displayTags.putIfAbsent(key, value)
                    "ALBUM" -> displayTags.putIfAbsent(key, value)
                    else -> if (key !in replayGainValues) {
                        parseReplayGainValue(key, value)?.let { replayGainValues[key] = it }
                    }
                }
            }
        } else if (key == "COVER ART (FRONT)" && itemType == 1L && artwork == null &&
            valueLength in 2L..(MAX_LOCAL_ARTWORK_BYTES + MAX_LOCAL_TAG_BYTES).toLong()
        ) {
            val valueBytes = ByteArray(valueLength.toInt())
            input.readFully(valueBytes)
            artwork = readApeFrontArtwork(valueBytes)
        }
        cursor = valueStart + valueLength
    }

    val replayGain = replayGainValues.toDesktopReplayGainTags()
    return ParsedApeV2Tag(
        title = displayTags["TITLE"],
        artist = displayTags["ARTIST"],
        album = displayTags["ALBUM"],
        artwork = artwork,
        replayGain = replayGain,
    ).takeIf { it.title != null || it.artist != null || it.album != null || it.artwork != null || it.replayGain != null }
}

private val apeDisplayTagKeys = setOf("TITLE", "ARTIST", "ALBUM")

private fun decodeApeTextValue(bytes: ByteArray): String? {
    var start = 0
    while (start <= bytes.size) {
        val end = bytes.indexOfZero(start, bytes.size) ?: bytes.size
        if (end > start) {
            val decoded = runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, end - start))
                    .toString()
                    .trim()
            }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }
        if (end == bytes.size) return null
        start = end + 1
    }
    return null
}

private fun readApeFrontArtwork(value: ByteArray): DesktopEmbeddedArtwork? {
    val filenameEnd = value.indexOfZero(0, value.size) ?: return null
    if (filenameEnd !in 1..MAX_LOCAL_TAG_BYTES) return null
    val imageOffset = filenameEnd + 1
    val imageSize = value.size - imageOffset
    if (imageSize !in 1..MAX_LOCAL_ARTWORK_BYTES) return null
    val mimeType = when {
        imageSize >= 8 && value.copyOfRange(imageOffset, imageOffset + 8)
            .contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) -> "image/png"
        imageSize >= 3 && value[imageOffset] == 0xff.toByte() && value[imageOffset + 1] == 0xd8.toByte() &&
            value[imageOffset + 2] == 0xff.toByte() -> "image/jpeg"
        imageSize >= 6 && String(value, imageOffset, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
        imageSize >= 12 && String(value, imageOffset + 8, 4, Charsets.US_ASCII) == "WEBP" &&
            String(value, imageOffset, 4, Charsets.US_ASCII) == "RIFF" -> "image/webp"
        else -> return null
    }
    if (mimeType !in supportedFlacArtworkMimeTypes) return null
    return DesktopEmbeddedArtwork(mimeType, 3L, value.copyOfRange(imageOffset, value.size))
}

/** Kept as a small helper for callers that only need a duration. */
internal fun readLocalAudioDurationMillis(file: File): Long? =
    readLocalAudioMetadata(file)?.durationMillis?.takeIf { it > 0L }

private fun readWaveMetadata(input: RandomAccessFile): DesktopLocalAudioMetadata? {
    if (input.length() < 12L) return null
    input.seek(0)
    if (input.readAscii(4) != "RIFF") return null
    val riffSize = input.readUInt32LittleEndian()
    if (riffSize < 4L || riffSize > input.length() - 8L) return null
    val riffEnd = 8L + riffSize
    if (input.readAscii(4) != "WAVE") return null

    var byteRate: Long? = null
    var dataBytes: Long? = null
    val tags = linkedMapOf<String, String>()
    var id3Tags: ParsedId3Tag? = null
    var offset = 12L
    while (offset + 8L <= riffEnd) {
        input.seek(offset)
        val chunkId = input.readAscii(4)
        val chunkSize = input.readUInt32LittleEndian()
        val dataOffset = offset + 8L
        val paddedChunkSize = chunkSize + (chunkSize and 1L)
        if (paddedChunkSize > riffEnd - dataOffset) return null
        when (chunkId) {
            "fmt " -> if (chunkSize >= 12L) {
                input.seek(dataOffset + 8L)
                byteRate = input.readUInt32LittleEndian().takeIf { it > 0L }
            }
            "data" -> dataBytes = chunkSize
            "LIST" -> readWaveInfoTags(input, dataOffset, chunkSize)?.forEach { (key, value) ->
                tags.putIfAbsent(key, value)
            }
            // RIFF/WAVE files commonly store a complete ID3v2 tag in an `ID3 ` chunk.
            // Some taggers write the chunk identifier in lowercase, so accept both spellings.
            "ID3 ", "id3 " -> if (id3Tags == null) {
                id3Tags = readId3v2Tags(input, dataOffset, chunkSize)
            }
        }
        offset = dataOffset + paddedChunkSize
    }
    if (offset != riffEnd) return null
    val duration = byteRate?.let { rate -> dataBytes?.let { bytes -> bytes * 1_000L / rate } }
        ?.takeIf { it > 0L }
    return DesktopLocalAudioMetadata(
        title = id3Tags?.displayTags?.get("TITLE") ?: tags["INAM"],
        artist = id3Tags?.displayTags?.get("ARTIST") ?: tags["IART"],
        album = id3Tags?.displayTags?.get("ALBUM") ?: tags["IPRD"],
        durationMillis = duration,
        embeddedArtwork = id3Tags?.artwork,
        replayGain = id3Tags?.replayGain,
    )
}

private fun readDsfMetadata(input: RandomAccessFile): DesktopLocalAudioMetadata? {
    if (input.length() < 92L) return null
    input.seek(0)
    if (input.readAscii(4) != "DSD ") return null
    val headerChunkSize = input.readUInt64LittleEndian()
    val declaredFileSize = input.readUInt64LittleEndian()
    val id3Offset = input.readUInt64LittleEndian()
    if (headerChunkSize != 28L || declaredFileSize !in 92L..input.length() || headerChunkSize > declaredFileSize) {
        return null
    }

    if (input.readAscii(4) != "fmt ") return null
    val formatChunkSize = input.readUInt64LittleEndian()
    if (formatChunkSize < 52L || formatChunkSize > declaredFileSize - 28L) return null
    val version = input.readUInt32LittleEndian()
    val formatId = input.readUInt32LittleEndian()
    input.readUInt32LittleEndian() // Channel type.
    val channels = input.readUInt32LittleEndian()
    val sampleRate = input.readUInt32LittleEndian()
    input.readUInt32LittleEndian() // Bits per sample.
    val sampleCount = input.readUInt64LittleEndian()
    if (version != 1L || formatId != 0L || channels !in 1L..2L ||
        !isSupportedDsdBitRate(sampleRate) || sampleCount <= 0L
    ) {
        return null
    }

    val dataChunkOffset = 28L + formatChunkSize
    if (dataChunkOffset > declaredFileSize - 12L) return null
    input.seek(dataChunkOffset)
    if (input.readAscii(4) != "data") return null
    val dataChunkSize = input.readUInt64LittleEndian()
    if (dataChunkSize < 12L || dataChunkSize > declaredFileSize - dataChunkOffset) return null
    val minimumPayloadBytes = (sampleCount / 8L + if (sampleCount % 8L == 0L) 0L else 1L) * channels
    if (dataChunkSize - 12L < minimumPayloadBytes) return null
    val durationMillis = durationMillis(sampleCount, sampleRate) ?: return null
    val audioEndOffset = dataChunkOffset + dataChunkSize
    val tags = if (id3Offset >= audioEndOffset && id3Offset <= declaredFileSize - 10L) {
        readId3v2Tags(input, id3Offset, declaredFileSize - id3Offset)
    } else null
    return DesktopLocalAudioMetadata(
        title = tags?.displayTags?.get("TITLE"),
        artist = tags?.displayTags?.get("ARTIST"),
        album = tags?.displayTags?.get("ALBUM"),
        durationMillis = durationMillis,
        embeddedArtwork = tags?.artwork,
    )
}

private fun readDffMetadata(input: RandomAccessFile): DesktopLocalAudioMetadata? {
    if (input.length() < 16L) return null
    input.seek(0)
    if (input.readAscii(4) != "FRM8") return null
    val formSize = input.readUInt64BigEndian()
    if (formSize < 4L || formSize > input.length() - 12L) return null
    val formEnd = 12L + formSize
    if (input.readAscii(4) != "DSD ") return null

    var version: Long? = null
    var bitRate: Long? = null
    var channels: Long? = null
    var compressionId: String? = null
    var audioDuration: Long? = null
    var id3Tags: ParsedId3Tag? = null
    var position = 16L
    while (position < formEnd) {
        val chunk = readDffChunk(input, position, formEnd) ?: return null
        when (chunk.id) {
            "FVER" -> {
                if (version != null || chunk.size != 4L) return null
                input.seek(chunk.payloadOffset)
                version = input.readUInt32BigEndian()
            }
            "ID3 " -> if (id3Tags == null) {
                id3Tags = readId3v2Tags(input, chunk.payloadOffset, chunk.size)
            }
            "PROP" -> {
                if (bitRate != null || audioDuration != null || chunk.size < 4L) return null
                input.seek(chunk.payloadOffset)
                if (input.readAscii(4) != "SND ") return null
                var propertyPosition = chunk.payloadOffset + 4L
                while (propertyPosition < chunk.endOffset) {
                    val property = readDffChunk(input, propertyPosition, chunk.endOffset) ?: return null
                    when (property.id) {
                        "FS  " -> {
                            if (bitRate != null || property.size != 4L) return null
                            input.seek(property.payloadOffset)
                            bitRate = input.readUInt32BigEndian()
                        }
                        "CHNL" -> {
                            if (channels != null || property.size < 2L) return null
                            input.seek(property.payloadOffset)
                            val count = input.readUInt16BigEndian()
                            val channelBytes = property.size - 2L
                            if (count !in 1..2 || channelBytes != count.toLong() * 4L) return null
                            val ids = input.readAscii(channelBytes.toInt())
                            if ((count == 1 && ids != "C   ") || (count == 2 && ids != "SLFTSRGT")) return null
                            channels = count.toLong()
                        }
                        "CMPR" -> {
                            if (compressionId != null || property.size < 5L) return null
                            input.seek(property.payloadOffset)
                            compressionId = input.readAscii(4)
                        }
                    }
                    propertyPosition = property.nextOffset
                }
            }
            "DSD ", "DST " -> {
                if (audioDuration != null || bitRate == null || channels == null || compressionId == null) return null
                if (!isSupportedDsdBitRate(bitRate) || channels !in 1L..2L) return null
                if (chunk.id == "DSD ") {
                    if (compressionId != "DSD " || version != 0x01050000L || chunk.size == 0L ||
                        chunk.size % channels != 0L
                    ) return null
                    audioDuration = dsdBytesToDurationMillis(chunk.size / channels, bitRate)
                } else {
                    if (compressionId != "DST " || version !in setOf(0x01040000L, 0x01050000L)) return null
                    val frameRate = readDffDstFrameRate(input, chunk) ?: return null
                    audioDuration = durationMillis(frameRate.first, frameRate.second)
                }
            }
        }
        position = chunk.nextOffset
    }
    if (position != formEnd || version == null || bitRate == null || channels == null || audioDuration == null) {
        return null
    }
    return DesktopLocalAudioMetadata(durationMillis = audioDuration)
        .copy(
            title = id3Tags?.displayTags?.get("TITLE"),
            artist = id3Tags?.displayTags?.get("ARTIST"),
            album = id3Tags?.displayTags?.get("ALBUM"),
            embeddedArtwork = id3Tags?.artwork,
        )
}

private data class ParsedId3Tag(
    val displayTags: Map<String, String>,
    val artwork: DesktopEmbeddedArtwork?,
    val replayGain: DesktopReplayGainTags?,
)

/** Reads bounded display text, ReplayGain TXXX fields, and a supported embedded picture from ID3 metadata. */
private fun readId3v2Tags(
    input: RandomAccessFile,
    offset: Long,
    availableLength: Long,
): ParsedId3Tag? = runCatching {
    if (offset < 0L || availableLength < 10L || offset > input.length() - availableLength) return@runCatching null
    input.seek(offset)
    if (input.readAscii(3) != "ID3") return@runCatching null
    val majorVersion = input.readUnsignedByte()
    val revision = input.readUnsignedByte()
    val flags = input.readUnsignedByte()
    if (majorVersion !in 2..4 || revision == 0xff) return@runCatching null
    val allowedTagFlags = when (majorVersion) {
        2 -> 0xc0
        3 -> 0xe0
        else -> 0xf0
    }
    if (flags and allowedTagFlags.inv() != 0) return@runCatching null
    val tagBodySize = input.readSynchsafeInt() ?: return@runCatching null
    val tagUnsynchronised = flags and 0x80 != 0
    // ID3v2.2/2.3 unsynchronise the complete body; ID3v2.4 does so per frame.
    if (majorVersion == 2 && flags and 0x40 != 0) return@runCatching null
    val footerSize = if (majorVersion == 4 && flags and 0x10 != 0) 10L else 0L
    val totalSize = 10L + tagBodySize + footerSize
    if (tagBodySize > MAX_LOCAL_ID3_TAG_BYTES || totalSize > availableLength) return@runCatching null
    val encodedBody = ByteArray(tagBodySize)
    input.readFully(encodedBody)
    val body = if (tagUnsynchronised && majorVersion < 4) {
        removeId3Unsynchronization(encodedBody)
    } else {
        encodedBody
    }

    var cursor = 0
    if (flags and 0x40 != 0) {
        cursor = readId3ExtendedHeaderSize(body, majorVersion) ?: return@runCatching null
    }

    val displayTags = linkedMapOf<String, String>()
    val replayGainValues = linkedMapOf<String, Double>()
    var artwork: DesktopEmbeddedArtwork? = null
    val frameHeaderSize = if (majorVersion == 2) 6 else 10
    var frameCount = 0
    while (cursor + frameHeaderSize <= body.size && frameCount < 10_000) {
        val idLength = if (majorVersion == 2) 3 else 4
        if (body[cursor] == 0.toByte()) break // Padding begins; the rest of the tag is zero-filled.
        val frameId = body.copyOfRange(cursor, cursor + idLength).toString(Charsets.US_ASCII)
        if (!frameId.all { it in 'A'..'Z' || it in '0'..'9' }) break
        val frameSize = when (majorVersion) {
            2 -> readUInt24BigEndian(body, cursor + 3)
            3 -> readUInt32BigEndian(body, cursor + 4)
            else -> readSynchsafeInt(body, cursor + 4)
        } ?: break
        val frameFlags = if (majorVersion == 2) 0 else
            ((body[cursor + 8].toInt() and 0xff) shl 8) or (body[cursor + 9].toInt() and 0xff)
        val frameOffset = cursor + frameHeaderSize
        if (frameSize > body.size - frameOffset) break
        val tagKey = when (frameId) {
            "TIT2", "TT2" -> "TITLE"
            "TPE1", "TP1" -> "ARTIST"
            "TALB", "TAL" -> "ALBUM"
            else -> null
        }
        // Compressed/encrypted/grouped frames are not plain fields; read-only flags are safe.
        val unsupportedFormatFlags = if (majorVersion == 3) frameFlags and 0x00e0
            else if (majorVersion == 4) frameFlags and 0x004d else 0
        val frameUnsynchronised = majorVersion == 4 &&
            (tagUnsynchronised || frameFlags and 0x0002 != 0)
        val hasReadablePayload = unsupportedFormatFlags == 0 &&
            (frameId == "APIC" || frameId == "PIC" || tagKey != null || frameId == "TXXX")
        val readablePayload = if (hasReadablePayload) {
            val encodedPayload = body.copyOfRange(frameOffset, frameOffset + frameSize)
            if (frameUnsynchronised) removeId3Unsynchronization(encodedPayload) else encodedPayload
        } else {
            null
        }
        if ((frameId == "APIC" || frameId == "PIC") && readablePayload != null) {
            val picture = readId3Picture(readablePayload, 0, readablePayload.size, majorVersion)
            if (picture != null &&
                (artwork == null || (picture.pictureType == 3L && artwork.pictureType != 3L))
            ) artwork = picture
        }
        if (tagKey != null && readablePayload != null && readablePayload.size in 2..MAX_LOCAL_TAG_BYTES) {
            val value = decodeId3Text(readablePayload, 0, readablePayload.size)
            if (!value.isNullOrBlank()) displayTags.putIfAbsent(tagKey, value)
        }
        if (frameId == "TXXX" && readablePayload != null && readablePayload.size in 2..MAX_LOCAL_TAG_BYTES) {
            decodeId3UserText(readablePayload, 0, readablePayload.size)?.let { (description, value) ->
                val key = description.trim().uppercase()
                if (key in flacReplayGainTagKeys && key !in replayGainValues) {
                    parseReplayGainValue(key, value)?.let { replayGainValues[key] = it }
                }
            }
        }
        cursor = frameOffset + frameSize
        frameCount++
    }
    ParsedId3Tag(displayTags, artwork, replayGainValues.toDesktopReplayGainTags())
}.getOrNull()

/** Validates version-specific extended-header structure and returns its total byte length. */
private fun readId3ExtendedHeaderSize(body: ByteArray, majorVersion: Int): Int? {
    if (majorVersion == 3) {
        if (body.size < 10) return null
        val sizeExcludingSizeField = readUInt32BigEndian(body, 0) ?: return null
        if (sizeExcludingSizeField != 6 && sizeExcludingSizeField != 10) return null
        val totalSize = 4 + sizeExcludingSizeField
        if (totalSize > body.size) return null
        val extendedFlags = ((body[4].toInt() and 0xff) shl 8) or (body[5].toInt() and 0xff)
        val hasCrc = extendedFlags and 0x8000 != 0
        if (extendedFlags and 0x7fff != 0 || hasCrc != (sizeExcludingSizeField == 10)) return null
        val paddingSize = readUInt32BigEndian(body, 6) ?: return null
        if (paddingSize < 0 || paddingSize > body.size - totalSize) return null
        return totalSize
    }

    if (majorVersion == 4) {
        val totalSize = readSynchsafeInt(body, 0) ?: return null
        if (totalSize < 6 || totalSize > body.size) return null
        if (body[4].toInt() != 1) return null // Number of flag bytes.
        val extendedFlags = body[5].toInt() and 0xff
        if (extendedFlags and 0x8f != 0) return null
        var cursor = 6
        if (extendedFlags and 0x40 != 0) {
            if (cursor >= totalSize || body[cursor++].toInt() != 0) return null
        }
        if (extendedFlags and 0x20 != 0) {
            if (cursor >= totalSize || body[cursor++].toInt() != 5 || cursor + 5 > totalSize) return null
            repeat(5) {
                if (body[cursor++].toInt() and 0x80 != 0) return null
            }
        }
        if (extendedFlags and 0x10 != 0) {
            if (cursor + 2 > totalSize || body[cursor++].toInt() != 1) return null
            cursor++ // Tag restrictions byte.
        }
        if (cursor != totalSize) return null
        return totalSize
    }

    return null
}

/** Removes only the zero bytes inserted by the ID3 unsynchronisation scheme. */
private fun removeId3Unsynchronization(encoded: ByteArray): ByteArray {
    val decoded = ByteArray(encoded.size)
    var readOffset = 0
    var writeOffset = 0
    while (readOffset < encoded.size) {
        val value = encoded[readOffset].toInt() and 0xff
        decoded[writeOffset++] = encoded[readOffset]
        if (value == 0xff && readOffset + 1 < encoded.size && encoded[readOffset + 1] == 0.toByte()) {
            val afterInsertedZero = readOffset + 2
            val nextValue = encoded.getOrNull(afterInsertedZero)?.toInt()?.and(0xff)
            if (nextValue == null || nextValue == 0 || nextValue >= 0xe0) {
                readOffset++
            }
        }
        readOffset++
    }
    return if (writeOffset == encoded.size) encoded else decoded.copyOf(writeOffset)
}

private fun decodeId3Text(body: ByteArray, offset: Int, size: Int): String? = runCatching {
    if (size < 2) return@runCatching null
    val encoding = body[offset].toInt() and 0xff
    val charset = when (encoding) {
        0 -> Charsets.ISO_8859_1
        1 -> Charsets.UTF_16 // Honors the BOM, with big-endian as the specified fallback.
        2 -> Charsets.UTF_16BE
        3 -> Charsets.UTF_8
        else -> return@runCatching null
    }
    String(body, offset + 1, size - 1, charset).substringBefore('\u0000').trim()
}.getOrNull()

/** Decodes an ID3v2.3/v2.4 TXXX frame as its encoding-aware description/value pair. */
private fun decodeId3UserText(body: ByteArray, offset: Int, size: Int): Pair<String, String>? = runCatching {
    val end = offset + size
    if (size < 2 || offset < 0 || end > body.size) return@runCatching null
    val encoding = body[offset].toInt() and 0xff
    if (encoding !in 0..3) return@runCatching null
    val descriptionStart = offset + 1
    val descriptionEnd = findId3DescriptionEnd(body, descriptionStart, end, encoding) ?: return@runCatching null
    val separatorSize = if (encoding == 1 || encoding == 2) 2 else 1
    val valueStart = descriptionEnd + separatorSize
    if (valueStart > end) return@runCatching null

    val littleEndianUtf16 = encoding == 1 && descriptionStart + 1 < descriptionEnd &&
        body[descriptionStart] == 0xff.toByte() && body[descriptionStart + 1] == 0xfe.toByte()
    fun decode(start: Int, segmentEnd: Int): String? {
        if (start > segmentEnd) return null
        val charset = when (encoding) {
            0 -> Charsets.ISO_8859_1
            1 -> when {
                segmentEnd - start >= 2 && body[start] == 0xff.toByte() && body[start + 1] == 0xfe.toByte() -> Charsets.UTF_16LE
                segmentEnd - start >= 2 && body[start] == 0xfe.toByte() && body[start + 1] == 0xff.toByte() -> Charsets.UTF_16BE
                littleEndianUtf16 -> Charsets.UTF_16LE
                else -> Charsets.UTF_16BE
            }
            2 -> Charsets.UTF_16BE
            else -> Charsets.UTF_8
        }
        return String(body, start, segmentEnd - start, charset).removePrefix("\uFEFF")
    }

    val description = decode(descriptionStart, descriptionEnd)?.trim() ?: return@runCatching null
    // ID3v2.4 permits multiple NUL-separated values; ReplayGain is a scalar, so use the first.
    val valueEnd = findId3DescriptionEnd(body, valueStart, end, encoding) ?: end
    val value = decode(valueStart, valueEnd)?.substringBefore('\u0000')?.trim() ?: return@runCatching null
    description to value
}.getOrNull()

private fun readId3Picture(
    body: ByteArray,
    offset: Int,
    size: Int,
    majorVersion: Int,
): DesktopEmbeddedArtwork? = runCatching {
    val end = offset + size
    if (size < 5 || offset < 0 || end > body.size) return@runCatching null
    val encoding = body[offset].toInt() and 0xff
    if (encoding !in 0..3) return@runCatching null
    var cursor = offset + 1
    val mimeType = if (majorVersion == 2) {
        if (cursor > end - 3) return@runCatching null
        val imageFormat = String(body, cursor, 3, Charsets.US_ASCII).uppercase()
        cursor += 3
        when (imageFormat) {
            "PNG" -> "image/png"
            "JPG" -> "image/jpeg"
            else -> return@runCatching null // Includes the URL-only "-->" marker.
        }
    } else {
        val mimeEnd = body.indexOfZero(cursor, end) ?: return@runCatching null
        val mime = String(body, cursor, mimeEnd - cursor, Charsets.ISO_8859_1).lowercase()
        cursor = mimeEnd + 1
        mime.takeIf { it in supportedFlacArtworkMimeTypes } ?: return@runCatching null
    }
    if (cursor >= end) return@runCatching null
    val pictureType = body[cursor++].toLong() and 0xff
    val descriptionEnd = findId3DescriptionEnd(body, cursor, end, encoding) ?: return@runCatching null
    if (descriptionEnd - cursor > MAX_LOCAL_TAG_BYTES) return@runCatching null
    val terminatorBytes = if (encoding == 1 || encoding == 2) 2 else 1
    val imageOffset = descriptionEnd + terminatorBytes
    val imageLength = end - imageOffset
    if (imageOffset > end || imageLength !in 1..MAX_LOCAL_ARTWORK_BYTES) return@runCatching null
    DesktopEmbeddedArtwork(
        mimeType = mimeType,
        pictureType = pictureType,
        data = body.copyOfRange(imageOffset, end),
    )
}.getOrNull()

private fun findId3DescriptionEnd(body: ByteArray, start: Int, end: Int, encoding: Int): Int? {
    if (start < 0 || start > end || end > body.size) return null
    if (encoding == 1 || encoding == 2) {
        var index = start
        while (index + 1 < end) {
            if (body[index] == 0.toByte() && body[index + 1] == 0.toByte()) return index
            index += 2
        }
    } else {
        return body.indexOfZero(start, end)
    }
    return null
}

private fun ByteArray.indexOfZero(start: Int, end: Int): Int? {
    if (start < 0 || start > end || end > size) return null
    for (index in start until end) if (this[index] == 0.toByte()) return index
    return null
}

private fun RandomAccessFile.readSynchsafeInt(): Int? {
    val bytes = IntArray(4) { readUnsignedByte() }
    if (bytes.any { it and 0x80 != 0 }) return null
    return (bytes[0] shl 21) or (bytes[1] shl 14) or (bytes[2] shl 7) or bytes[3]
}

private fun readSynchsafeInt(bytes: ByteArray, offset: Int): Int? {
    if (offset < 0 || offset > bytes.size - 4) return null
    val parts = IntArray(4) { bytes[offset + it].toInt() and 0xff }
    if (parts.any { it and 0x80 != 0 }) return null
    return (parts[0] shl 21) or (parts[1] shl 14) or (parts[2] shl 7) or parts[3]
}

private fun readUInt24BigEndian(bytes: ByteArray, offset: Int): Int? {
    if (offset < 0 || offset > bytes.size - 3) return null
    return ((bytes[offset].toInt() and 0xff) shl 16) or
        ((bytes[offset + 1].toInt() and 0xff) shl 8) or
        (bytes[offset + 2].toInt() and 0xff)
}

private fun readUInt32BigEndian(bytes: ByteArray, offset: Int): Int? {
    if (offset < 0 || offset > bytes.size - 4) return null
    val value = ((bytes[offset].toLong() and 0xff) shl 24) or
        ((bytes[offset + 1].toLong() and 0xff) shl 16) or
        ((bytes[offset + 2].toLong() and 0xff) shl 8) or
        (bytes[offset + 3].toLong() and 0xff)
    return value.takeIf { it <= Int.MAX_VALUE }?.toInt()
}

private data class DffMetadataChunk(
    val id: String,
    val size: Long,
    val payloadOffset: Long,
    val endOffset: Long,
    val nextOffset: Long,
)

private fun readDffChunk(input: RandomAccessFile, offset: Long, parentEnd: Long): DffMetadataChunk? {
    if (offset < 0L || offset > parentEnd - 12L) return null
    input.seek(offset)
    val id = input.readAscii(4)
    val size = input.readUInt64BigEndian()
    if (size < 0L) return null
    val payloadOffset = offset + 12L
    if (size > parentEnd - payloadOffset) return null
    val endOffset = payloadOffset + size
    val nextOffset = endOffset + (size and 1L)
    if (nextOffset > parentEnd || (size and 1L != 0L && endOffset >= parentEnd)) return null
    return DffMetadataChunk(id, size, payloadOffset, endOffset, nextOffset)
}

private fun readDffDstFrameRate(input: RandomAccessFile, chunk: DffMetadataChunk): Pair<Long, Long>? {
    if (chunk.size < 18L) return null
    val frameRateChunk = readDffChunk(input, chunk.payloadOffset, chunk.endOffset) ?: return null
    if (frameRateChunk.id != "FRTE" || frameRateChunk.size != 6L) return null
    input.seek(frameRateChunk.payloadOffset)
    val frames = input.readUInt32BigEndian()
    val rate = input.readUInt16BigEndian().toLong()
    if (frames <= 0L || rate != 75L) return null
    return frames to rate
}

private fun isSupportedDsdBitRate(sampleRate: Long): Boolean =
    sampleRate % 44_100L == 0L && sampleRate / 44_100L in setOf(64L, 128L, 256L, 512L, 1024L)

private fun durationMillis(samples: Long, sampleRate: Long): Long? {
    if (samples <= 0L || sampleRate <= 0L) return null
    val wholeSeconds = samples / sampleRate
    val remainder = samples % sampleRate
    return runCatching { Math.addExact(Math.multiplyExact(wholeSeconds, 1_000L), remainder * 1_000L / sampleRate) }
        .getOrNull()?.takeIf { it > 0L }
}

private fun dsdBytesToDurationMillis(bytesPerChannel: Long, bitRate: Long): Long? {
    val bytesPerSecond = bitRate / 8L
    if (bytesPerChannel <= 0L || bytesPerSecond <= 0L) return null
    val wholeSeconds = bytesPerChannel / bytesPerSecond
    val remainder = bytesPerChannel % bytesPerSecond
    return runCatching {
        Math.addExact(Math.multiplyExact(wholeSeconds, 1_000L), remainder * 1_000L / bytesPerSecond)
    }.getOrNull()?.takeIf { it > 0L }
}

private fun readWaveInfoTags(input: RandomAccessFile, offset: Long, chunkSize: Long): Map<String, String>? =
    runCatching {
        if (chunkSize < 4L) return@runCatching null
        input.seek(offset)
        if (input.readAscii(4) != "INFO") return@runCatching null
        val end = offset + chunkSize
        val tags = linkedMapOf<String, String>()
        var subOffset = offset + 4L
        while (subOffset + 8L <= end) {
            input.seek(subOffset)
            val id = input.readAscii(4)
            val size = input.readUInt32LittleEndian()
            val valueOffset = subOffset + 8L
            if (size > end - valueOffset) break
            if (id in waveInfoTagIds && size in 1L..MAX_LOCAL_TAG_BYTES.toLong()) {
                input.seek(valueOffset)
                val value = input.readUtf8(size.toInt())
                if (value.isNotBlank()) tags.putIfAbsent(id, value)
            }
            subOffset = valueOffset + size + (size and 1L)
        }
        tags
    }.getOrNull()

private val waveInfoTagIds = setOf("INAM", "IART", "IPRD")

private fun readFlacMetadata(input: RandomAccessFile): DesktopLocalAudioMetadata? {
    if (input.length() < 42L) return null
    input.seek(0)
    if (input.readAscii(4) != "fLaC") return null
    var offset = 4L
    var durationMillis: Long? = null
    var embeddedArtwork: DesktopEmbeddedArtwork? = null
    val tags = linkedMapOf<String, String>()
    val replayGainValues = linkedMapOf<String, Double>()
    while (offset + 4L <= input.length()) {
        input.seek(offset)
        val first = input.readUnsignedByte()
        val type = first and 0x7f
        val blockSize = (input.readUnsignedByte() shl 16) or
            (input.readUnsignedByte() shl 8) or input.readUnsignedByte()
        val blockOffset = offset + 4L
        if (blockSize.toLong() > input.length() - blockOffset) return null
        if (type == 0 && blockSize >= 34) {
            input.seek(blockOffset + 10L)
            var packed = 0L
            repeat(8) { packed = (packed shl 8) or input.readUnsignedByte().toLong() }
            val sampleRate = (packed ushr 44).toInt()
            val totalSamples = packed and 0x0fffffffffL
            if (sampleRate > 0 && totalSamples > 0L) {
                durationMillis = totalSamples * 1_000L / sampleRate
            }
        } else if (type == 4) {
            val comments = readFlacVorbisComments(input, blockOffset, blockSize)
            comments?.displayTags?.forEach { (key, value) -> tags.putIfAbsent(key, value) }
            comments?.replayGainValues?.forEach { (key, value) -> replayGainValues.putIfAbsent(key, value) }
        } else if (type == 6) {
            val picture = readFlacPicture(input, blockOffset, blockSize)
            if (picture != null &&
                (embeddedArtwork == null || (picture.pictureType == 3L && embeddedArtwork.pictureType != 3L))
            ) {
                embeddedArtwork = picture
            }
        }
        offset = blockOffset + blockSize
        if (first and 0x80 != 0) break
    }
    return DesktopLocalAudioMetadata(
        title = tags["TITLE"],
        artist = tags["ARTIST"],
        album = tags["ALBUM"],
        durationMillis = durationMillis,
        embeddedArtwork = embeddedArtwork,
        replayGain = replayGainValues.toDesktopReplayGainTags(),
    )
}

private fun readFlacPicture(
    input: RandomAccessFile,
    offset: Long,
    blockSize: Int,
): DesktopEmbeddedArtwork? = runCatching {
    if (blockSize < 32) return@runCatching null
    val end = offset + blockSize
    input.seek(offset)
    val pictureType = input.readUInt32BigEndian()
    var cursor = offset + 4L

    fun readLengthPrefixedText(): String? {
        if (cursor + 4L > end) return null
        input.seek(cursor)
        val length = input.readUInt32BigEndian()
        cursor += 4L
        if (length > end - cursor) return null
        val value = if (length <= 256L) {
            input.seek(cursor)
            input.readUtf8(length.toInt())
        } else ""
        cursor += length
        return value
    }

    val mimeType = readLengthPrefixedText()?.lowercase() ?: return@runCatching null
    if (mimeType !in supportedFlacArtworkMimeTypes) return@runCatching null
    if (readLengthPrefixedText() == null) return@runCatching null // Description.
    // Width, height, color depth, and palette count precede the binary image data.
    if (cursor + 16L > end) return@runCatching null
    cursor += 16L
    input.seek(cursor)
    val dataLength = input.readUInt32BigEndian()
    cursor += 4L
    if (dataLength !in 1L..MAX_LOCAL_ARTWORK_BYTES.toLong() || dataLength > end - cursor) {
        return@runCatching null
    }
    input.seek(cursor)
    DesktopEmbeddedArtwork(mimeType, pictureType, ByteArray(dataLength.toInt()).also(input::readFully))
}.getOrNull()

private val supportedFlacArtworkMimeTypes = setOf("image/jpeg", "image/png", "image/webp", "image/gif")

/** Saves bounded embedded art in the user's cache and returns a Coil-readable file URI. */
internal fun writeLocalAudioArtwork(
    artwork: DesktopEmbeddedArtwork,
    cacheDirectory: File = desktopArtworkCacheDirectory(),
): String? = runCatching {
    if (artwork.data.isEmpty() || artwork.data.size > MAX_LOCAL_ARTWORK_BYTES) return@runCatching null
    val extension = when (artwork.mimeType.lowercase()) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/gif" -> "gif"
        else -> return@runCatching null
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(artwork.data)
        .joinToString("") { byte -> "%02x".format(byte) }
    if (!cacheDirectory.isDirectory && !cacheDirectory.mkdirs()) return@runCatching null
    val target = File(cacheDirectory, "$digest.$extension")
    if (!target.isFile || target.length() != artwork.data.size.toLong()) {
        val temporary = Files.createTempFile(cacheDirectory.toPath(), "$digest-", ".tmp")
        try {
            Files.write(temporary, artwork.data)
            try {
                Files.move(temporary, target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: Exception) {
                Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
    target.setLastModified(System.currentTimeMillis())
    pruneLocalArtworkCache(cacheDirectory, target)
    // Path.toUri emits file:///C:/... on Windows; File.toURI emits file:/C:/..., which Coil 3's
    // multiplatform URI parser interprets as a scheme containing "/C" and cannot fetch.
    target.toPath().toUri().toString()
}.getOrNull()

private fun pruneLocalArtworkCache(cacheDirectory: File, preserve: File) {
    val files = cacheDirectory.listFiles()?.filter(File::isFile) ?: return
    var totalBytes = files.sumOf(File::length)
    if (totalBytes <= MAX_LOCAL_ARTWORK_CACHE_BYTES) return
    files.filter { it != preserve }.sortedBy(File::lastModified).forEach { file ->
        if (totalBytes <= MAX_LOCAL_ARTWORK_CACHE_BYTES) return
        val size = file.length()
        if (file.delete()) totalBytes -= size
    }
}

private fun desktopArtworkCacheDirectory(): File {
    val home = System.getProperty("user.home").orEmpty()
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    val base = when {
        osName.contains("win") -> System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)
            ?.let(::File) ?: File(home, "AppData/Local")
        osName.contains("mac") || osName.contains("darwin") -> File(home, "Library/Caches")
        else -> System.getenv("XDG_CACHE_HOME")?.takeIf(String::isNotBlank)
            ?.let(::File) ?: File(home, ".cache")
    }
    return File(base, "Lazer/artwork")
}

private fun readFlacVorbisComments(
    input: RandomAccessFile,
    offset: Long,
    blockSize: Int,
): ParsedFlacVorbisComments? = runCatching {
    if (blockSize < 8) return@runCatching null
    val end = offset + blockSize
    input.seek(offset)
    val vendorLength = input.readUInt32LittleEndian()
    var cursor = offset + 4L
    if (vendorLength > end - cursor) return@runCatching null
    cursor += vendorLength
    input.seek(cursor)
    val count = input.readUInt32LittleEndian()
    cursor += 4L
    if (count > 100_000L) return@runCatching null
    val displayTags = linkedMapOf<String, String>()
    val replayGainValues = linkedMapOf<String, Double>()
    repeat(count.toInt()) {
        if (cursor + 4L > end) return@runCatching ParsedFlacVorbisComments(displayTags, replayGainValues)
        input.seek(cursor)
        val length = input.readUInt32LittleEndian()
        cursor += 4L
        if (length > end - cursor) return@runCatching ParsedFlacVorbisComments(displayTags, replayGainValues)
        if (length in 1L..MAX_LOCAL_TAG_BYTES.toLong()) {
            input.seek(cursor)
            val field = input.readUtf8(length.toInt())
            val separator = field.indexOf('=')
            if (separator > 0) {
                val key = field.substring(0, separator).trim().uppercase()
                val value = field.substring(separator + 1).trim()
                if (key in flacDisplayTagKeys && value.isNotBlank()) {
                    displayTags.putIfAbsent(key, value)
                } else if (key in flacReplayGainTagKeys && key !in replayGainValues) {
                    parseReplayGainValue(key, value)?.let { replayGainValues[key] = it }
                }
            }
        }
        cursor += length
    }
    ParsedFlacVorbisComments(displayTags, replayGainValues)
}.getOrNull()

private val flacDisplayTagKeys = setOf("TITLE", "ARTIST", "ALBUM")

private data class ParsedFlacVorbisComments(
    val displayTags: Map<String, String>,
    val replayGainValues: Map<String, Double>,
)

private const val REPLAYGAIN_TRACK_GAIN = "REPLAYGAIN_TRACK_GAIN"
private const val REPLAYGAIN_TRACK_PEAK = "REPLAYGAIN_TRACK_PEAK"
private const val REPLAYGAIN_ALBUM_GAIN = "REPLAYGAIN_ALBUM_GAIN"
private const val REPLAYGAIN_ALBUM_PEAK = "REPLAYGAIN_ALBUM_PEAK"

private val flacReplayGainTagKeys = setOf(
    REPLAYGAIN_TRACK_GAIN,
    REPLAYGAIN_TRACK_PEAK,
    REPLAYGAIN_ALBUM_GAIN,
    REPLAYGAIN_ALBUM_PEAK,
)

private val replayGainDecimalPattern = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
private val replayGainGainPattern = Regex("([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)(?:[ \\t]*dB)?", RegexOption.IGNORE_CASE)

private fun parseReplayGainValue(key: String, rawValue: String): Double? {
    if (rawValue.length !in 1..64) return null
    val text = rawValue.trim()
    val numeric = if (key.endsWith("_GAIN")) {
        replayGainGainPattern.matchEntire(text)?.groupValues?.getOrNull(1)
    } else {
        text.takeIf { replayGainDecimalPattern.matches(it) }
    } ?: return null
    val value = numeric.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
    return when {
        key.endsWith("_GAIN") && value in -60.0..24.0 -> value
        key.endsWith("_PEAK") && value > 0.0 && value <= 16.0 -> value
        else -> null
    }
}

private fun DesktopReplayGainTags.hasAnyValue(): Boolean =
    trackGainDb != null || trackPeak != null || albumGainDb != null || albumPeak != null

private fun Map<String, Double>.toDesktopReplayGainTags(): DesktopReplayGainTags? = DesktopReplayGainTags(
    trackGainDb = get(REPLAYGAIN_TRACK_GAIN),
    trackPeak = get(REPLAYGAIN_TRACK_PEAK),
    albumGainDb = get(REPLAYGAIN_ALBUM_GAIN),
    albumPeak = get(REPLAYGAIN_ALBUM_PEAK),
).takeIf { it.hasAnyValue() }

private fun RandomAccessFile.readAscii(count: Int): String =
    ByteArray(count).also(::readFully).toString(Charsets.US_ASCII)

private fun RandomAccessFile.readUInt32LittleEndian(): Long =
    readUnsignedByte().toLong() or
        (readUnsignedByte().toLong() shl 8) or
        (readUnsignedByte().toLong() shl 16) or
    (readUnsignedByte().toLong() shl 24)

private fun RandomAccessFile.readUInt32BigEndian(): Long =
    (readUnsignedByte().toLong() shl 24) or
        (readUnsignedByte().toLong() shl 16) or
        (readUnsignedByte().toLong() shl 8) or
        readUnsignedByte().toLong()

private fun RandomAccessFile.readUInt16BigEndian(): Int =
    (readUnsignedByte() shl 8) or readUnsignedByte()

private fun RandomAccessFile.readUInt64LittleEndian(): Long {
    var value = 0L
    repeat(8) { index -> value = value or (readUnsignedByte().toLong() shl (index * 8)) }
    return value
}

private fun RandomAccessFile.readUInt64BigEndian(): Long {
    var value = 0L
    repeat(8) { value = (value shl 8) or readUnsignedByte().toLong() }
    return value
}

private fun RandomAccessFile.readUtf8(length: Int): String =
    ByteArray(length).also(::readFully).toString(Charsets.UTF_8).trimEnd('\u0000').trim()
