package dev.naominet.lazer

import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

internal interface AndroidLocalAudioReadSource {
    val length: Long

    fun readAt(position: Long, destination: ByteArray, offset: Int, byteCount: Int): Int
}

internal data class AndroidLocalAudioMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val replayGain: LazerReplayGainTags? = null,
    val albumArtist: String? = null,
    val genre: String? = null,
    val year: Int? = null,
    val trackNumber: Int? = null,
    val totalTracks: Int? = null,
    val discNumber: Int? = null,
    val totalDiscs: Int? = null,
) {
    val hasValues: Boolean
        get() = title != null || artist != null || album != null || replayGain != null ||
            albumArtist != null || genre != null || year != null || trackNumber != null ||
            totalTracks != null || discNumber != null || totalDiscs != null
}

/** Reads only bounded WAV/FLAC/DSF/DFF metadata; audio payloads are skipped and never buffered. */
internal object AndroidLocalReplayGainReader {
    private const val MAX_ID3_TAG_BYTES = 1 * 1024 * 1024
    private const val MAX_ID3_FRAME_BYTES = 64 * 1024
    private const val MAX_WAV_METADATA_BYTES = 4 * 1024 * 1024L
    private const val MAX_WAV_CHUNKS = 4_096
    private const val MAX_DFF_METADATA_BYTES = 4 * 1024 * 1024L
    private const val MAX_DFF_CHUNKS = 4_096
    private const val MAX_FLAC_METADATA_BYTES = 16 * 1024 * 1024L
    private const val MAX_FLAC_BLOCK_BYTES = 12 * 1024 * 1024
    private const val MAX_VORBIS_COMMENT_BYTES = 1 * 1024 * 1024
    private const val MAX_VORBIS_COMMENTS = 10_000
    private const val MAX_COMMENT_FIELD_BYTES = 4 * 1024
    private const val MAX_DISPLAY_METADATA_LENGTH = 1_024
    private const val MAX_APE_TAG_BYTES = 4 * 1024 * 1024
    private const val MAX_APE_ITEM_COUNT = 10_000L
    private const val APE_FOOTER_SIZE = 32L
    private const val APE_FLAG_HEADER_PRESENT = 0x8000_0000L
    private const val APE_FLAG_NO_FOOTER = 0x4000_0000L
    private const val APE_FLAG_IS_HEADER = 0x2000_0000L
    private val vorbisDisplayKeys = setOf(
        "TITLE", "ARTIST", "ALBUM", "ALBUMARTIST", "ALBUM ARTIST", "GENRE", "DATE", "YEAR",
        "TRACKNUMBER", "TRACK", "TOTALTRACKS", "TRACKTOTAL", "DISCNUMBER", "DISC", "TOTALDISCS",
    )
    private val apeDisplayKeys = vorbisDisplayKeys
    private val id3DisplayFrames = mapOf(
        "TIT2" to "TITLE",
        "TPE1" to "ARTIST",
        "TALB" to "ALBUM",
        "TPE2" to "ALBUMARTIST",
        "TCON" to "GENRE",
        "TDRC" to "YEAR",
        "TYER" to "YEAR",
        "TRCK" to "TRACKNUMBER",
        "TPOS" to "DISCNUMBER",
    )
    private val replayGainKeys = setOf(
        "REPLAYGAIN_TRACK_GAIN",
        "REPLAYGAIN_TRACK_PEAK",
        "REPLAYGAIN_ALBUM_GAIN",
        "REPLAYGAIN_ALBUM_PEAK",
    )
    private val gainPattern = Regex(
        "([+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?)(?:[ \\t]*dB)?",
        RegexOption.IGNORE_CASE,
    )
    private val peakPattern = Regex("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")

    fun read(channel: SeekableByteChannel, startOffset: Long, length: Long, format: String): LazerReplayGainTags? {
        return readMetadata(channel, startOffset, length, format)?.replayGain
    }

    fun readMetadata(
        channel: SeekableByteChannel,
        startOffset: Long,
        length: Long,
        format: String,
    ): AndroidLocalAudioMetadata? {
        if (startOffset < 0L || length < 0L || startOffset > Long.MAX_VALUE - length) return null
        val sourceLength = length
        val source = object : AndroidLocalAudioReadSource {
            override val length: Long = sourceLength

            override fun readAt(position: Long, destination: ByteArray, offset: Int, byteCount: Int): Int {
                if (position < 0L || position > sourceLength - byteCount) return -1
                channel.position(startOffset + position)
                return channel.read(ByteBuffer.wrap(destination, offset, byteCount))
            }
        }
        return readMetadata(source, format)
    }

    fun read(source: AndroidLocalAudioReadSource, format: String): LazerReplayGainTags? =
        readMetadata(source, format)?.replayGain

    fun readMetadata(source: AndroidLocalAudioReadSource, format: String): AndroidLocalAudioMetadata? = runCatching {
        if (source.length < 0L) return@runCatching null
        val input = BoundedCursor(source)
        val containerMetadata = when (format.lowercase()) {
            "wav", "wave" -> readWave(input)
            "flac" -> readFlac(input)
            "dsf" -> readDsf(input)
            "dff" -> readDff(input)
            "dsd" -> when (input.readAsciiAt(0L, 4)) {
                "DSD " -> readDsf(input)
                "FRM8" -> readDff(input)
                else -> null
            }
            else -> null
        }
        mergeMetadata(containerMetadata, readApeV2(input))
    }.getOrNull()

    /** Reads a bounded APEv2 footer at EOF, or immediately before an optional ID3v1 trailer. */
    private fun readApeV2(input: BoundedCursor): AndroidLocalAudioMetadata? = runCatching {
        if (input.length < APE_FOOTER_SIZE) return@runCatching null
        val footerOffsets = buildList {
            add(input.length - APE_FOOTER_SIZE)
            if (input.length >= 128L + APE_FOOTER_SIZE && input.readAsciiAt(input.length - 128L, 3) == "TAG") {
                add(input.length - 128L - APE_FOOTER_SIZE)
            }
        }
        footerOffsets.firstNotNullOfOrNull { footerOffset ->
            runCatching { readApeV2AtFooter(input, footerOffset) }.getOrNull()
        }
    }.getOrNull()

    private fun readApeV2AtFooter(input: BoundedCursor, footerOffset: Long): AndroidLocalAudioMetadata? {
        if (footerOffset < 0L || footerOffset > input.length - APE_FOOTER_SIZE || !input.seekTo(footerOffset)) {
            return null
        }
        if (input.readAscii(8) != "APETAGEX") return null
        val version = input.readUInt32LittleEndian() ?: return null
        val tagSize = input.readUInt32LittleEndian() ?: return null
        val itemCount = input.readUInt32LittleEndian() ?: return null
        val footerFlags = input.readUInt32LittleEndian() ?: return null
        if (input.readBytesExact(8) == null) return null
        val headerPresent = footerFlags and APE_FLAG_HEADER_PRESENT != 0L
        if (
            version != 2_000L || tagSize !in APE_FOOTER_SIZE..MAX_APE_TAG_BYTES.toLong() ||
            itemCount !in 1L..MAX_APE_ITEM_COUNT ||
            footerFlags and (APE_FLAG_NO_FOOTER or APE_FLAG_IS_HEADER) != 0L
        ) return null

        // The APE tag size includes item bytes and footer, but not the optional header.
        val itemStart = footerOffset + APE_FOOTER_SIZE - tagSize
        val itemEnd = footerOffset
        if (itemStart < 0L || itemStart > itemEnd) return null
        if (headerPresent) {
            val headerOffset = itemStart - APE_FOOTER_SIZE
            if (headerOffset < 0L || !input.seekTo(headerOffset) || input.readAscii(8) != "APETAGEX") {
                return null
            }
            if (
                input.readUInt32LittleEndian() != version || input.readUInt32LittleEndian() != tagSize ||
                input.readUInt32LittleEndian() != itemCount
            ) return null
            val headerFlags = input.readUInt32LittleEndian() ?: return null
            if (
                headerFlags and (APE_FLAG_HEADER_PRESENT or APE_FLAG_NO_FOOTER or APE_FLAG_IS_HEADER) !=
                (APE_FLAG_HEADER_PRESENT or APE_FLAG_IS_HEADER)
            ) return null
            if (input.readBytesExact(8) == null) return null
        }

        val displayValues = linkedMapOf<String, String>()
        val replayGainValues = linkedMapOf<String, Double>()
        var position = itemStart
        repeat(itemCount.toInt()) {
            if (itemEnd - position < 11L || !input.seekTo(position)) return null
            val valueLength = input.readUInt32LittleEndian() ?: return null
            val itemFlags = input.readUInt32LittleEndian() ?: return null
            val keyLimit = input.position + minOf(itemEnd - input.position, 256L)
            val keyBytes = ByteArray(255)
            var keyLength = 0
            var hasKeyTerminator = false
            while (input.position < keyLimit) {
                val next = input.readUnsignedByteOrNull() ?: return null
                if (next == 0) {
                    hasKeyTerminator = true
                    break
                }
                if (next !in 0x20..0x7e || keyLength == keyBytes.size) return null
                keyBytes[keyLength++] = next.toByte()
            }
            if (!hasKeyTerminator || keyLength !in 2..255) return null
            val key = String(keyBytes, 0, keyLength, StandardCharsets.US_ASCII).uppercase()
            val valueStart = input.position
            if (valueLength > itemEnd - valueStart) return null
            val itemType = (itemFlags ushr 1) and 0x3L
            if (
                itemType == 0L && (key in apeDisplayKeys || key in replayGainKeys) &&
                valueLength in 1L..MAX_COMMENT_FIELD_BYTES.toLong()
            ) {
                val valueBytes = input.readBytesExact(valueLength.toInt()) ?: return null
                val value = decodeApeTextValue(valueBytes)
                if (value != null) {
                    if (key in apeDisplayKeys) {
                        val canonicalKey = canonicalDisplayKey(key)
                        if (canonicalKey !in displayValues) {
                            normalizeDisplayText(value)?.let {
                                putFirstValidDisplayValue(displayValues, canonicalKey, it)
                            }
                        }
                    } else if (key in replayGainKeys && key !in replayGainValues) {
                        parseReplayGain(key, value)?.let { replayGainValues[key] = it }
                    }
                }
            }
            position = valueStart + valueLength
        }

        return toAudioMetadata(displayValues, replayGainValues.toTags())
    }

    private fun decodeApeTextValue(bytes: ByteArray): String? {
        var start = 0
        while (start <= bytes.size) {
            var end = start
            while (end < bytes.size && bytes[end] != 0.toByte()) end++
            if (end > start) {
                val decoded = runCatching {
                    StandardCharsets.UTF_8.newDecoder()
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

    private fun readDsf(input: BoundedCursor): AndroidLocalAudioMetadata? {
        if (input.length < 92L || !input.seekTo(0L)) return null
        if (input.readAscii(4) != "DSD ") return null
        val headerChunkSize = input.readUInt64LittleEndian() ?: return null
        val declaredFileSize = input.readUInt64LittleEndian() ?: return null
        val id3Offset = input.readUInt64LittleEndian() ?: return null
        if (
            headerChunkSize != 28L || declaredFileSize !in 92L..input.length ||
            !input.seekTo(28L) || input.readAscii(4) != "fmt "
        ) return null

        val formatChunkSize = input.readUInt64LittleEndian() ?: return null
        if (formatChunkSize < 52L || formatChunkSize > declaredFileSize - 28L) return null
        val dataChunkOffset = 28L + formatChunkSize
        if (dataChunkOffset > declaredFileSize - 12L || !input.seekTo(dataChunkOffset)) return null
        if (input.readAscii(4) != "data") return null
        val dataChunkSize = input.readUInt64LittleEndian() ?: return null
        if (dataChunkSize < 12L || dataChunkSize > declaredFileSize - dataChunkOffset) return null
        val audioEndOffset = dataChunkOffset + dataChunkSize
        if (id3Offset < audioEndOffset || id3Offset > declaredFileSize - 10L) return null
        return readId3At(input, id3Offset, declaredFileSize - id3Offset)
    }

    private fun readDff(input: BoundedCursor): AndroidLocalAudioMetadata? {
        if (input.length < 16L || !input.seekTo(0L)) return null
        if (input.readAscii(4) != "FRM8") return null
        val formSize = input.readUInt64BigEndian() ?: return null
        if (formSize < 4L || formSize > input.length - 12L) return null
        val formEnd = 12L + formSize
        if (input.readAscii(4) != "DSD ") return null

        var position = 16L
        var chunkCount = 0
        var scannedMetadata = 0L
        var metadata: AndroidLocalAudioMetadata? = null
        while (position < formEnd && chunkCount < MAX_DFF_CHUNKS) {
            if (formEnd - position < 12L || !input.seekTo(position)) return null
            val chunkId = input.readAscii(4) ?: return null
            val chunkSize = input.readUInt64BigEndian() ?: return null
            val payloadOffset = position + 12L
            if (chunkSize > formEnd - payloadOffset) return null
            val paddedSize = chunkSize + (chunkSize and 1L)
            if (paddedSize > formEnd - payloadOffset) return null

            when (chunkId) {
                "DSD ", "DST " -> Unit // Audio payloads can be very large; advance by offset only.
                "ID3 " -> {
                    if (chunkSize > MAX_DFF_METADATA_BYTES - scannedMetadata) return null
                    scannedMetadata += chunkSize
                    if (chunkSize in 10L..MAX_ID3_TAG_BYTES.toLong()) {
                        metadata = mergeMetadata(metadata, readId3At(input, payloadOffset, chunkSize))
                    }
                }
                else -> {
                    if (chunkSize > MAX_DFF_METADATA_BYTES - scannedMetadata) return null
                    scannedMetadata += chunkSize
                }
            }
            position = payloadOffset + paddedSize
            chunkCount++
        }
        return metadata
    }

    private fun readId3At(input: BoundedCursor, offset: Long, availableLength: Long): AndroidLocalAudioMetadata? {
        if (
            offset < 0L || availableLength < 10L || offset > input.length - availableLength ||
            !input.seekTo(offset)
        ) return null
        val header = input.readBytesExact(10) ?: return null
        if (header.asciiAt(0, 3) != "ID3") return null
        val majorVersion = header[3].toInt() and 0xff
        if (majorVersion !in 3..4 || (header[4].toInt() and 0xff) == 0xff) return null
        val flags = header[5].toInt() and 0xff
        val bodySize = readSynchsafeInt(header, 6) ?: return null
        val footerSize = if (majorVersion == 4 && flags and 0x10 != 0) 10L else 0L
        val totalSize = 10L + bodySize + footerSize
        if (totalSize > MAX_ID3_TAG_BYTES || totalSize > availableLength || !input.seekTo(offset)) return null
        val tag = input.readBytesExact(totalSize.toInt()) ?: return null
        return readId3Metadata(tag)
    }

    private fun readWave(input: BoundedCursor): AndroidLocalAudioMetadata? {
        if (input.readAscii(4) != "RIFF") return null
        val riffSize = input.readUInt32LittleEndian() ?: return null
        if (input.readAscii(4) != "WAVE" || riffSize < 4L) return null

        val riffEnd = 8L + riffSize
        if (riffEnd > input.length) return null
        var scannedMetadata = 0L
        var chunkCount = 0
        var metadata: AndroidLocalAudioMetadata? = null
        while (input.position <= riffEnd - 8L && chunkCount < MAX_WAV_CHUNKS) {
            val chunkId = input.readAscii(4) ?: return null
            val chunkSize = input.readUInt32LittleEndian() ?: return null
            if (chunkSize > riffEnd - input.position) return null

            when (chunkId) {
                "ID3 ", "id3 " -> {
                    if (chunkSize < 10L || chunkSize > MAX_ID3_TAG_BYTES.toLong()) return null
                    scannedMetadata += chunkSize
                    if (scannedMetadata > MAX_WAV_METADATA_BYTES) return null
                    val tag = input.readBytesExact(chunkSize.toInt()) ?: return null
                    metadata = mergeMetadata(metadata, readId3Metadata(tag))
                }
                "data" -> {
                    if (!input.skipExactly(chunkSize)) return null
                }
                else -> {
                    scannedMetadata += chunkSize
                    if (scannedMetadata > MAX_WAV_METADATA_BYTES || !input.skipExactly(chunkSize)) return null
                }
            }
            if ((chunkSize and 1L) != 0L) {
                if (input.position >= riffEnd || !input.skipExactly(1L)) return null
            }
            chunkCount++
        }
        return metadata
    }

    private fun readFlac(input: BoundedCursor): AndroidLocalAudioMetadata? {
        if (input.readAscii(4) != "fLaC") return null
        var scannedMetadata = 0L
        var blockCount = 0
        while (blockCount < 128) {
            val header = input.readUnsignedByteOrNull() ?: return null
            val blockSize = input.readUInt24BigEndian() ?: return null
            scannedMetadata += 4L + blockSize
            if (blockSize > MAX_FLAC_BLOCK_BYTES || scannedMetadata > MAX_FLAC_METADATA_BYTES) return null

            val blockType = header and 0x7f
            if (blockType == 4) {
                if (blockSize < 8 || blockSize > MAX_VORBIS_COMMENT_BYTES) return null
                val comments = input.readBytesExact(blockSize) ?: return null
                return readVorbisMetadata(comments)
            }
            if (!input.skipExactly(blockSize.toLong())) return null
            blockCount++
            if ((header and 0x80) != 0) return null
        }
        return null
    }

    private fun readId3Metadata(tag: ByteArray): AndroidLocalAudioMetadata? {
        if (tag.size < 10 || tag.asciiAt(0, 3) != "ID3") return null
        val majorVersion = tag[3].toInt() and 0xff
        if (majorVersion !in 3..4 || (tag[4].toInt() and 0xff) == 0xff) return null
        val flags = tag[5].toInt() and 0xff
        val allowedFlags = if (majorVersion == 3) 0xe0 else 0xf0
        if ((flags and allowedFlags.inv()) != 0) return null
        val tagUnsynchronised = flags and 0x80 != 0
        val bodySize = readSynchsafeInt(tag, 6) ?: return null
        val footerSize = if (majorVersion == 4 && flags and 0x10 != 0) 10 else 0
        if (bodySize > tag.size - 10 - footerSize) return null
        val encodedBody = tag.copyOfRange(10, 10 + bodySize)
        val body = if (tagUnsynchronised && majorVersion == 3) {
            removeId3Unsynchronization(encodedBody)
        } else {
            encodedBody
        }
        var cursor = 0
        if (flags and 0x40 != 0) {
            cursor = readId3ExtendedHeaderSize(body, majorVersion) ?: return null
        }

        val values = linkedMapOf<String, Double>()
        val displayValues = linkedMapOf<String, String>()
        var frames = 0
        while (cursor + 10 <= body.size && frames < MAX_WAV_CHUNKS) {
            if (body[cursor] == 0.toByte()) break
            val frameId = body.asciiAt(cursor, 4) ?: break
            if (frameId.length != 4 || !frameId.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val frameSize: Long = if (majorVersion == 3) {
                readUInt32BigEndian(body, cursor + 4)
            } else {
                readSynchsafeInt(body, cursor + 4)?.toLong()
            } ?: break
            val frameFlags =
                ((body[cursor + 8].toInt() and 0xff) shl 8) or (body[cursor + 9].toInt() and 0xff)
            val payloadStart = cursor + 10
            if (frameSize > body.size.toLong() - payloadStart) break
            val unsupportedFormatFlags = if (majorVersion == 3) {
                frameFlags and 0x00e0
            } else {
                frameFlags and 0x004d
            }
            val frameUnsynchronised = majorVersion == 4 &&
                (tagUnsynchronised || frameFlags and 0x0002 != 0)
            if (frameSize in 2L..MAX_ID3_FRAME_BYTES.toLong() && unsupportedFormatFlags == 0) {
                val encodedPayload = body.copyOfRange(payloadStart, payloadStart + frameSize.toInt())
                val payload = if (frameUnsynchronised) {
                    removeId3Unsynchronization(encodedPayload)
                } else {
                    encodedPayload
                }
                if (frameId == "TXXX") {
                    val decoded = decodeUserText(payload)
                    if (decoded != null) {
                        val (description, value) = decoded
                        val key = description.trim().uppercase()
                        if (key in replayGainKeys && key !in values) {
                            parseReplayGain(key, value)?.let { values[key] = it }
                        }
                    }
                } else if (frameId in id3DisplayFrames) {
                    val key = id3DisplayFrames.getValue(frameId)
                    if (key !in displayValues || (frameId == "TDRC" && key == "YEAR")) {
                        decodeId3TextValues(payload)?.let { decoded ->
                            normalizeDisplayText(decoded)?.let {
                                putFirstValidDisplayValue(displayValues, key, it, overwrite = frameId == "TDRC")
                            }
                        }
                    }
                }
            }
            cursor = payloadStart + frameSize.toInt()
            frames++
        }
        return toAudioMetadata(displayValues, values.toTags())
    }

    private fun decodeId3TextValues(payload: ByteArray): String? {
        if (payload.size < 2) return null
        val encoding = payload[0].toInt() and 0xff
        if (encoding !in 0..3) return null
        val littleEndian = encoding == 1 && payload.size >= 3 &&
            payload[1] == 0xff.toByte() && payload[2] == 0xfe.toByte()
        val decoded = decodeText(payload, 1, payload.size, encoding, littleEndian) ?: return null
        val values = decoded.split('\u0000').map(String::trim).filter(String::isNotEmpty)
        if (values.isEmpty()) return null
        return values.joinToString("; ").take(MAX_DISPLAY_METADATA_LENGTH).takeIf(String::isNotBlank)
    }

    /** Removes only zero bytes inserted by the ID3 unsynchronisation scheme. */
    private fun removeId3Unsynchronization(encoded: ByteArray): ByteArray {
        val decoded = ByteArray(encoded.size)
        var readOffset = 0
        var writeOffset = 0
        while (readOffset < encoded.size) {
            val value = encoded[readOffset].toInt() and 0xff
            decoded[writeOffset++] = encoded[readOffset]
            if (
                value == 0xff && readOffset + 1 < encoded.size &&
                encoded[readOffset + 1] == 0.toByte()
            ) {
                val afterInsertedZero = readOffset + 2
                val nextValue = encoded.getOrNull(afterInsertedZero)?.toInt()?.and(0xff)
                if (nextValue == null || nextValue == 0 || nextValue >= 0xe0) readOffset++
            }
            readOffset++
        }
        return if (writeOffset == encoded.size) encoded else decoded.copyOf(writeOffset)
    }

    private fun readId3ExtendedHeaderSize(body: ByteArray, majorVersion: Int): Int? {
        if (majorVersion == 3) {
            val declared = readUInt32BigEndian(body, 0) ?: return null
            if (declared !in 6L..10L) return null
            val total = 4L + declared
            return total.toInt().takeIf { it <= body.size }
        }
        val total = readSynchsafeInt(body, 0) ?: return null
        return total.takeIf { it >= 6 && it <= body.size }
    }

    private fun decodeUserText(payload: ByteArray): Pair<String, String>? {
        if (payload.size < 2) return null
        val encoding = payload[0].toInt() and 0xff
        if (encoding !in 0..3) return null
        val start = 1
        val delimiter = findTextDelimiter(payload, start, encoding) ?: return null
        val width = if (encoding == 1 || encoding == 2) 2 else 1
        val valueStart = delimiter + width
        val littleEndian = encoding == 1 && delimiter >= start + 2 &&
            payload[start] == 0xff.toByte() && payload[start + 1] == 0xfe.toByte()
        val description = decodeText(payload, start, delimiter, encoding, littleEndian)?.trim() ?: return null
        val valueEnd = findTextDelimiter(payload, valueStart, encoding) ?: payload.size
        val value = decodeText(payload, valueStart, valueEnd, encoding, littleEndian)
            ?.substringBefore('\u0000')?.trim() ?: return null
        return description to value
    }

    private fun findTextDelimiter(bytes: ByteArray, start: Int, encoding: Int): Int? {
        if (encoding == 1 || encoding == 2) {
            var index = start
            while (index + 1 < bytes.size) {
                if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) return index
                index += 2
            }
        } else {
            for (index in start until bytes.size) if (bytes[index] == 0.toByte()) return index
        }
        return null
    }

    private fun decodeText(
        bytes: ByteArray,
        start: Int,
        end: Int,
        encoding: Int,
        littleEndianUtf16: Boolean,
    ): String? {
        if (start > end) return null
        val charset = when (encoding) {
            0 -> StandardCharsets.ISO_8859_1
            1 -> if (littleEndianUtf16) StandardCharsets.UTF_16LE else StandardCharsets.UTF_16BE
            2 -> StandardCharsets.UTF_16BE
            3 -> StandardCharsets.UTF_8
            else -> return null
        }
        if ((encoding == 1 || encoding == 2) && (end - start) % 2 != 0) return null
        return runCatching {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(ByteBuffer.wrap(bytes, start, end - start)).toString().removePrefix("\uFEFF")
        }.getOrNull()
    }

    private fun readVorbisMetadata(block: ByteArray): AndroidLocalAudioMetadata? {
        var cursor = 0
        val vendorLength = readUInt32LittleEndian(block, cursor) ?: return null
        cursor += 4
        if (vendorLength > block.size.toLong() - cursor) return null
        cursor += vendorLength.toInt()
        val commentCount = readUInt32LittleEndian(block, cursor) ?: return null
        cursor += 4
        if (commentCount > MAX_VORBIS_COMMENTS || commentCount > (block.size - cursor) / 4L) return null

        val values = linkedMapOf<String, Double>()
        val displayValues = linkedMapOf<String, String>()
        repeat(commentCount.toInt()) {
            val length = readUInt32LittleEndian(block, cursor) ?: return null
            cursor += 4
            if (length > block.size.toLong() - cursor) return null
            if (length in 1L..MAX_COMMENT_FIELD_BYTES.toLong()) {
                val field = decodeUtf8(block, cursor, length.toInt())
                if (field != null) {
                    val separator = field.indexOf('=')
                    if (separator > 0) {
                        val key = field.substring(0, separator).trim().uppercase()
                        if (key in replayGainKeys && key !in values) {
                            parseReplayGain(key, field.substring(separator + 1))?.let { value -> values[key] = value }
                        } else if (key in vorbisDisplayKeys) {
                            val displayKey = canonicalDisplayKey(key)
                            if (displayKey !in displayValues) {
                                normalizeDisplayText(field.substring(separator + 1))?.let {
                                    putFirstValidDisplayValue(displayValues, displayKey, it)
                                }
                            }
                        }
                    }
                }
            }
            cursor += length.toInt()
        }
        return toAudioMetadata(displayValues, values.toTags())
    }

    private fun canonicalDisplayKey(key: String): String = when (key) {
        "ALBUM ARTIST" -> "ALBUMARTIST"
        "TRACK" -> "TRACKNUMBER"
        "TRACKTOTAL" -> "TOTALTRACKS"
        "DISC" -> "DISCNUMBER"
        else -> key
    }

    private fun toAudioMetadata(
        values: Map<String, String>,
        replayGain: LazerReplayGainTags?,
    ): AndroidLocalAudioMetadata? {
        val track = parseIndexTag(values["TRACKNUMBER"])
        val disc = parseIndexTag(values["DISCNUMBER"])
        return AndroidLocalAudioMetadata(
            title = values["TITLE"],
            artist = values["ARTIST"],
            album = values["ALBUM"],
            replayGain = replayGain,
            albumArtist = values["ALBUMARTIST"],
            genre = values["GENRE"],
            year = parseYear(values["YEAR"] ?: values["DATE"]),
            trackNumber = track.number,
            totalTracks = totalNumber(track.number, track.total ?: parsePositiveNumber(values["TOTALTRACKS"])),
            discNumber = disc.number,
            totalDiscs = totalNumber(disc.number, disc.total ?: parsePositiveNumber(values["TOTALDISCS"])),
        ).takeIf(AndroidLocalAudioMetadata::hasValues)
    }

    private fun putFirstValidDisplayValue(
        values: MutableMap<String, String>,
        key: String,
        value: String,
        overwrite: Boolean = false,
    ) {
        if (!overwrite && key in values) return
        val valid = when (key) {
            "YEAR", "DATE" -> parseYear(value) != null
            "TRACKNUMBER", "DISCNUMBER" -> parseIndexTag(value).number != null
            "TOTALTRACKS", "TOTALDISCS" -> parsePositiveNumber(value) != null
            else -> value.isNotBlank()
        }
        if (!valid) return
        val count = parsePositiveNumber(value)
        val index = when (key) {
            "TOTALTRACKS" -> parseIndexTag(values["TRACKNUMBER"]).number
            "TOTALDISCS" -> parseIndexTag(values["DISCNUMBER"]).number
            else -> null
        }
        if (count != null && index != null && count < index) return
        values[key] = value
        when (key) {
            "TRACKNUMBER" -> {
                val number = parseIndexTag(value).number
                if (number != null && parsePositiveNumber(values["TOTALTRACKS"])?.let { it < number } == true) {
                    values.remove("TOTALTRACKS")
                }
            }
            "DISCNUMBER" -> {
                val number = parseIndexTag(value).number
                if (number != null && parsePositiveNumber(values["TOTALDISCS"])?.let { it < number } == true) {
                    values.remove("TOTALDISCS")
                }
            }
        }
    }

    private data class IndexTag(val number: Int?, val total: Int?)

    private fun parseIndexTag(value: String?): IndexTag {
        val parts = value?.substringBefore(';')?.trim()?.split('/', limit = 2) ?: return IndexTag(null, null)
        val number = parsePositiveNumber(parts[0]) ?: return IndexTag(null, null)
        val total = parts.getOrNull(1)?.let(::parsePositiveNumber)?.takeIf { it >= number }
        return IndexTag(number, total)
    }

    private fun totalNumber(number: Int?, total: Int?): Int? =
        total?.takeIf { number == null || it >= number }

    private fun parsePositiveNumber(value: String?): Int? = value
        ?.trim()
        ?.toIntOrNull()
        ?.takeIf { it in 1..99_999 }

    private fun parseYear(value: String?): Int? {
        val text = value?.trim()?.takeIf { it.length >= 4 } ?: return null
        if (!text.take(4).all(Char::isDigit)) return null
        val next = text.getOrNull(4)
        if (next != null && !next.isDigit() && next !in "-/. T") return null
        return text.take(4).toIntOrNull()?.takeIf { it in 1..9_999 }
    }

    private fun normalizeDisplayText(value: String): String? = value
        .replace('\u0000', ' ')
        .trim()
        .take(MAX_DISPLAY_METADATA_LENGTH)
        .takeIf(String::isNotBlank)

    private fun mergeMetadata(
        existing: AndroidLocalAudioMetadata?,
        next: AndroidLocalAudioMetadata?,
    ): AndroidLocalAudioMetadata? {
        if (next == null) return existing
        if (existing == null) return next
        val existingGain = existing.replayGain
        val nextGain = next.replayGain
        val replayGain = when {
            existingGain == null -> nextGain
            nextGain == null -> existingGain
            else -> LazerReplayGainTags(
                trackGainDb = existingGain.trackGainDb ?: nextGain.trackGainDb,
                trackPeak = existingGain.trackPeak ?: nextGain.trackPeak,
                albumGainDb = existingGain.albumGainDb ?: nextGain.albumGainDb,
                albumPeak = existingGain.albumPeak ?: nextGain.albumPeak,
            )
        }
        return AndroidLocalAudioMetadata(
            title = existing.title ?: next.title,
            artist = existing.artist ?: next.artist,
            album = existing.album ?: next.album,
            replayGain = replayGain,
            albumArtist = existing.albumArtist ?: next.albumArtist,
            genre = existing.genre ?: next.genre,
            year = existing.year ?: next.year,
            trackNumber = existing.trackNumber ?: next.trackNumber,
            totalTracks = existing.totalTracks ?: next.totalTracks,
            discNumber = existing.discNumber ?: next.discNumber,
            totalDiscs = existing.totalDiscs ?: next.totalDiscs,
        )
    }

    private fun decodeUtf8(bytes: ByteArray, start: Int, size: Int): String? = runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, start, size))
            .toString()
    }.getOrNull()

    private fun parseReplayGain(key: String, rawValue: String): Double? {
        if (rawValue.length !in 1..64) return null
        val text = rawValue.trim()
        val numeric = if (key.endsWith("_GAIN")) {
            gainPattern.matchEntire(text)?.groupValues?.getOrNull(1)
        } else {
            text.takeIf(peakPattern::matches)
        } ?: return null
        val value = numeric.toDoubleOrNull()?.takeIf(Double::isFinite) ?: return null
        return when {
            key.endsWith("_GAIN") && value in -60.0..24.0 -> value
            key.endsWith("_PEAK") && value > 0.0 && value <= 16.0 -> value
            else -> null
        }
    }

    private fun Map<String, Double>.toTags(): LazerReplayGainTags? = LazerReplayGainTags(
        trackGainDb = get("REPLAYGAIN_TRACK_GAIN"),
        trackPeak = get("REPLAYGAIN_TRACK_PEAK"),
        albumGainDb = get("REPLAYGAIN_ALBUM_GAIN"),
        albumPeak = get("REPLAYGAIN_ALBUM_PEAK"),
    ).takeIf { it.trackGainDb != null || it.trackPeak != null || it.albumGainDb != null || it.albumPeak != null }

    private fun ByteArray.asciiAt(offset: Int, size: Int): String? {
        if (offset < 0 || size < 0 || offset > this.size - size) return null
        return String(this, offset, size, StandardCharsets.US_ASCII)
    }

    private fun readSynchsafeInt(bytes: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset > bytes.size - 4) return null
        var value = 0
        repeat(4) { index ->
            val byte = bytes[offset + index].toInt() and 0xff
            if (byte and 0x80 != 0) return null
            value = (value shl 7) or byte
        }
        return value
    }

    private fun readUInt32BigEndian(bytes: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset > bytes.size - 4) return null
        return ((bytes[offset].toLong() and 0xffL) shl 24) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 8) or
            (bytes[offset + 3].toLong() and 0xffL)
    }

    private fun readUInt32LittleEndian(bytes: ByteArray, offset: Int): Long? {
        if (offset < 0 || offset > bytes.size - 4) return null
        return (bytes[offset].toLong() and 0xffL) or
            ((bytes[offset + 1].toLong() and 0xffL) shl 8) or
            ((bytes[offset + 2].toLong() and 0xffL) shl 16) or
            ((bytes[offset + 3].toLong() and 0xffL) shl 24)
    }

    private class BoundedCursor(private val source: AndroidLocalAudioReadSource) {
        val length: Long = source.length
        var position: Long = 0L
            private set

        fun seekTo(offset: Long): Boolean {
            if (offset < 0L || offset > length) return false
            position = offset
            return true
        }

        fun readAsciiAt(offset: Long, count: Int): String? {
            if (!seekTo(offset)) return null
            return readAscii(count)
        }

        fun readAscii(count: Int): String? =
            readBytesExact(count)?.toString(StandardCharsets.US_ASCII)

        fun readBytesExact(size: Int): ByteArray? {
            if (size < 0 || size.toLong() > length - position) return null
            val bytes = ByteArray(size)
            var offset = 0
            while (offset < size) {
                val read = source.readAt(position, bytes, offset, size - offset)
                if (read <= 0 || read > size - offset) return null
                offset += read
                position += read
            }
            return bytes
        }

        fun skipExactly(size: Long): Boolean {
            if (size < 0L || size > length - position) return false
            position += size
            return true
        }

        fun readUnsignedByteOrNull(): Int? = readBytesExact(1)?.get(0)?.toInt()?.and(0xff)

        fun readUInt32LittleEndian(): Long? = readBytesExact(4)?.let { readUInt32LittleEndian(it, 0) }

        fun readUInt64LittleEndian(): Long? = readBytesExact(8)?.let { bytes ->
            if ((bytes[7].toInt() and 0x80) != 0) return@let null
            var value = 0L
            for (index in 7 downTo 0) {
                value = (value shl 8) or (bytes[index].toLong() and 0xffL)
            }
            value
        }

        fun readUInt64BigEndian(): Long? = readBytesExact(8)?.let { bytes ->
            if ((bytes[0].toInt() and 0x80) != 0) return@let null
            var value = 0L
            for (index in 0..7) {
                value = (value shl 8) or (bytes[index].toLong() and 0xffL)
            }
            value
        }

        fun readUInt24BigEndian(): Int? = readBytesExact(3)?.let {
            ((it[0].toInt() and 0xff) shl 16) or
                ((it[1].toInt() and 0xff) shl 8) or
                (it[2].toInt() and 0xff)
        }
    }
}
