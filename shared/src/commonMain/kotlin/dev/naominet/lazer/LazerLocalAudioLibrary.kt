package dev.naominet.lazer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** A folder whose audio files are part of the platform's browsable local library. */
data class LazerLocalLibraryRoot(
    val uri: String,
    val displayName: String,
    val available: Boolean = true,
)

enum class LazerLocalLibraryIssue { ScanFailed, RootUnavailable }

/** Read-only view of the platform-managed local audio index. */
data class LazerLocalLibrarySnapshot(
    val roots: List<LazerLocalLibraryRoot> = emptyList(),
    val tracks: List<LazerTrack> = emptyList(),
    val isScanning: Boolean = false,
    val scannedFileCount: Int = 0,
    val issue: LazerLocalLibraryIssue? = null,
)

private val emptyLocalLibraryState: StateFlow<LazerLocalLibrarySnapshot> =
    MutableStateFlow(LazerLocalLibrarySnapshot())

/** Stable album/disc/track/title order; missing disc and track numbers sort after numbered items. */
fun orderLazerLocalLibraryTracks(tracks: List<LazerTrack>): List<LazerTrack> =
    tracks.withIndex()
        .sortedWith { left, right ->
            compareText(left.value.album, right.value.album)
                .takeIf { it != 0 }
                ?: compareOptionalNumberLast(left.value.discNumber, right.value.discNumber)
                    .takeIf { it != 0 }
                ?: compareOptionalNumberLast(left.value.trackNumber, right.value.trackNumber)
                    .takeIf { it != 0 }
                ?: compareText(left.value.title, right.value.title)
                    .takeIf { it != 0 }
                ?: left.index.compareTo(right.index)
        }
        .map { it.value }

/** Matches local tracks against title, artist, or album, ignoring letter case. */
fun filterLazerLocalLibraryTracks(tracks: List<LazerTrack>, query: String): List<LazerTrack> {
    val needle = query.trim()
    if (needle.isEmpty()) return tracks
    return tracks.filter { track ->
        track.title.contains(needle, ignoreCase = true) ||
            track.artist.contains(needle, ignoreCase = true) ||
            track.album.contains(needle, ignoreCase = true)
    }
}

private fun compareText(left: String, right: String): Int = left.compareTo(right, ignoreCase = true)

private fun compareOptionalNumberLast(left: Int?, right: Int?): Int = when {
    left == null && right == null -> 0
    left == null -> 1
    right == null -> -1
    else -> left.compareTo(right)
}

/** Default empty state for platforms that do not provide a local library. */
internal val LazerEmptyLocalLibraryState: StateFlow<LazerLocalLibrarySnapshot>
    get() = emptyLocalLibraryState
