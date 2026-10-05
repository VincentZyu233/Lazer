package dev.naominet.lazer

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Path

/**
 * A queued CUE segment is safe to join only when it directly follows the active segment in the
 * same parsed sheet and both segments read the same physical lossless source.
 */
internal fun isContiguousDesktopCueSuccessor(current: TrackItem, successor: TrackItem): Boolean {
    if (current.id == successor.id) return false
    val currentSource = current.playbackSource as? DesktopTrackSource.LocalFile ?: return false
    val nextSource = successor.playbackSource as? DesktopTrackSource.LocalFile ?: return false
    val currentSheet = currentSource.cueSheetPath ?: return false
    val nextSheet = nextSource.cueSheetPath ?: return false
    if (currentSource.cueTrackNumber == null || nextSource.cueTrackNumber == null ||
        nextSource.cueTrackNumber <= currentSource.cueTrackNumber
    ) return false

    val sameSource = runCatching {
        Path.of(currentSource.absolutePath).toAbsolutePath().normalize() ==
            Path.of(nextSource.absolutePath).toAbsolutePath().normalize()
    }.getOrDefault(false)
    val sameSheet = runCatching {
        Path.of(currentSheet).toAbsolutePath().normalize() ==
            Path.of(nextSheet).toAbsolutePath().normalize()
    }.getOrDefault(false)
    val end = currentSource.cueEndFrame75
    val nextEnd = nextSource.cueEndFrame75
    return sameSource && sameSheet && end >= 0L && end == nextSource.cueStartFrame75 &&
        currentSource.cueStartFrame75 < end &&
        (nextEnd == -1L || nextEnd > nextSource.cueStartFrame75)
}

/**
 * Local files can share one native output session when the successor is either an adjacent CUE
 * segment, another ordinary WAV/FLAC file, a DSD file while an actual DoP stream is active, or a
 * raw DSF/DFF file while an actual Native DSD stream is active. Native DSD excludes CUE segments
 * and DST-compressed DFF because those sources cannot be passed through as raw DSD.
 */
internal fun canQueueDesktopLocalGaplessSuccessor(
    current: TrackItem,
    successor: TrackItem,
    doPOutputActive: Boolean = false,
    nativeDsdOutputActive: Boolean = false,
): Boolean {
    if (current.id == successor.id) return false
    if (nativeDsdOutputActive) {
        return isUnsegmentedNativeDsdLocalAudio(current) && isUnsegmentedNativeDsdLocalAudio(successor)
    }
    if (doPOutputActive) return isUnsegmentedDsdLocalAudio(current) && isUnsegmentedDsdLocalAudio(successor)
    if (isContiguousDesktopCueSuccessor(current, successor)) return true
    return isOrdinaryDesktopLocalAudio(current) && isOrdinaryDesktopLocalAudio(successor)
}

private fun isUnsegmentedNativeDsdLocalAudio(track: TrackItem): Boolean {
    val source = track.playbackSource as? DesktopTrackSource.LocalFile ?: return false
    if (source.cueSheetPath != null || source.cueTrackNumber != null ||
        source.cueStartFrame75 != 0L || source.cueEndFrame75 != 0L
    ) return false
    val file = File(source.absolutePath)
    return when (file.extension.lowercase()) {
        "dsf" -> true
        "dff" -> isRawDffAudio(file)
        else -> false
    }
}

private fun isUnsegmentedDsdLocalAudio(track: TrackItem): Boolean {
    val source = track.playbackSource as? DesktopTrackSource.LocalFile ?: return false
    if (source.cueSheetPath != null || source.cueTrackNumber != null ||
        source.cueStartFrame75 != 0L || source.cueEndFrame75 != 0L
    ) return false
    return isDsdLocalAudioFile(File(source.absolutePath))
}

private fun isOrdinaryDesktopLocalAudio(track: TrackItem): Boolean {
    val source = track.playbackSource as? DesktopTrackSource.LocalFile ?: return false
    if (source.cueSheetPath != null || source.cueTrackNumber != null ||
        source.cueStartFrame75 != 0L || source.cueEndFrame75 != 0L
    ) return false
    return File(source.absolutePath).extension.lowercase() in setOf("wav", "flac")
}

/** Native DSD can pass through only a DFF whose declared compression is raw DSD, never DST. */
private fun isRawDffAudio(file: File): Boolean = runCatching {
    RandomAccessFile(file, "r").use { input ->
        val length = input.length()
        if (length < 16L) return@use false
        input.seek(0L)
        if (input.readAscii(4) != "FRM8") return@use false
        val formSize = input.readDffUInt64() ?: return@use false
        if (formSize < 4L || formSize > length - 12L) return@use false
        val formEnd = 12L + formSize
        if (input.readAscii(4) != "DSD ") return@use false

        var compression: String? = null
        var offset = 16L
        while (offset < formEnd) {
            val top = input.readDffChunk(offset, formEnd) ?: return@use false
            if (top.id == "PROP") {
                if (top.size < 4L) return@use false
                input.seek(top.payloadOffset)
                if (input.readAscii(4) != "SND ") return@use false
                var propertyOffset = top.payloadOffset + 4L
                while (propertyOffset < top.endOffset) {
                    val property = input.readDffChunk(propertyOffset, top.endOffset) ?: return@use false
                    if (property.id == "CMPR") {
                        if (compression != null || property.size < 5L) return@use false
                        input.seek(property.payloadOffset)
                        compression = input.readAscii(4)
                    }
                    propertyOffset = property.nextOffset
                }
                if (propertyOffset != top.endOffset) return@use false
            }
            offset = top.nextOffset
        }
        offset == formEnd && compression == "DSD "
    }
}.getOrDefault(false)

private data class DffChunk(
    val id: String,
    val size: Long,
    val payloadOffset: Long,
    val endOffset: Long,
    val nextOffset: Long,
)

private fun RandomAccessFile.readDffUInt64(): Long? = runCatching { readLong() }
    .getOrNull()?.takeIf { it >= 0L }

private fun RandomAccessFile.readDffChunk(offset: Long, parentEnd: Long): DffChunk? {
    if (offset < 0L || offset > parentEnd - 12L) return null
    seek(offset)
    val id = readAscii(4)
    val size = readDffUInt64() ?: return null
    val payloadOffset = offset + 12L
    if (size > parentEnd - payloadOffset) return null
    val endOffset = payloadOffset + size
    val nextOffset = endOffset + (size and 1L)
    if (nextOffset > parentEnd || (size and 1L != 0L && endOffset >= parentEnd)) return null
    return DffChunk(id, size, payloadOffset, endOffset, nextOffset)
}

private fun RandomAccessFile.readAscii(length: Int): String {
    val bytes = ByteArray(length)
    readFully(bytes)
    return String(bytes, Charsets.US_ASCII)
}
