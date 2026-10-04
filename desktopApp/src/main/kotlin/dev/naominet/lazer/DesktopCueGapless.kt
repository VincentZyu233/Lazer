package dev.naominet.lazer

import java.io.File
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
 * segment, another ordinary WAV/FLAC file, or a DSD file while an actual DoP stream is active.
 * The native engine remains responsible for rejecting incompatible DSD rates/channels and DST.
 */
internal fun canQueueDesktopLocalGaplessSuccessor(
    current: TrackItem,
    successor: TrackItem,
    doPOutputActive: Boolean = false,
): Boolean {
    if (current.id == successor.id) return false
    if (doPOutputActive) return isUnsegmentedDsdLocalAudio(current) && isUnsegmentedDsdLocalAudio(successor)
    if (isContiguousDesktopCueSuccessor(current, successor)) return true
    return isOrdinaryDesktopLocalAudio(current) && isOrdinaryDesktopLocalAudio(successor)
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
