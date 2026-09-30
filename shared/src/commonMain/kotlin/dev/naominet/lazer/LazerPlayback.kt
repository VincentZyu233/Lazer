package dev.naominet.lazer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LazerPlaybackSnapshot(
    val track: LazerTrack? = null,
    val isPreparing: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L,
    val bufferedFraction: Float = 0f,
    val message: String? = null,
)

/** How the queue advances. Single loop only re-plays a track that finished on its own. */
enum class LazerPlayMode {
    ListLoop,
    SingleLoop,
    Shuffle,
    ;

    companion object {
        fun parse(value: String?): LazerPlayMode = entries.firstOrNull { it.name == value } ?: ListLoop
    }
}

data class LazerPlaybackQueueSnapshot(
    val tracks: List<LazerTrack> = emptyList(),
    val index: Int = -1,
    val mode: LazerPlayMode = LazerPlayMode.ListLoop,
)

/** What an edit to the queue means for the track that is currently audible. */
enum class LazerQueueEdit {
    Kept,
    Switched,
    Emptied,
}

/**
 * The one place playback state is published, so a screen, a notification and a system media control
 * all read the same values regardless of which audio engine wrote them.
 */
object LazerPlaybackStateStore {
    private val mutableSnapshot = MutableStateFlow(LazerPlaybackSnapshot())
    val snapshot: StateFlow<LazerPlaybackSnapshot> = mutableSnapshot.asStateFlow()

    fun update(value: LazerPlaybackSnapshot) {
        mutableSnapshot.value = value
    }
}

/**
 * The queue the audio engine is working through, shared by the screens and the platform player.
 * Owning it here means reordering, jumping and removing behave identically everywhere; the engine
 * only reports where it actually is.
 */
object LazerPlaybackQueue {
    private val mutableSnapshot = MutableStateFlow(LazerPlaybackQueueSnapshot())
    val snapshot: StateFlow<LazerPlaybackQueueSnapshot> = mutableSnapshot.asStateFlow()

    val tracks: List<LazerTrack> get() = mutableSnapshot.value.tracks
    val index: Int get() = mutableSnapshot.value.index
    val mode: LazerPlayMode get() = mutableSnapshot.value.mode

    /** Loads the stored mode before the first track can advance. */
    fun restoreMode(value: LazerPlayMode) {
        mutableSnapshot.update { it.copy(mode = value) }
    }

    fun setMode(value: LazerPlayMode) {
        mutableSnapshot.update { it.copy(mode = value) }
    }

    fun replace(queue: List<LazerTrack>, track: LazerTrack) {
        val distinct = queue.distinctBy(LazerTrack::id)
        val next = if (distinct.any { it.id == track.id }) {
            distinct
        } else {
            listOf(track) + distinct
        }
        mutableSnapshot.update {
            it.copy(tracks = next, index = next.indexOfFirst { item -> item.id == track.id })
        }
    }

    fun current(): LazerTrack? = tracks.getOrNull(index)

    fun next(): LazerTrack? = advance(1)

    fun previous(): LazerTrack? = advance(-1)

    /** Points the queue at [position] and returns what should now be audible. */
    fun jumpTo(position: Int): LazerTrack? {
        val state = mutableSnapshot.value
        val track = state.tracks.getOrNull(position) ?: return null
        mutableSnapshot.update { it.copy(index = position) }
        return track
    }

    /** Reorders without touching playback, keeping the index pointed at the same track. */
    fun move(from: Int, to: Int) {
        val state = mutableSnapshot.value
        if (from == to || from !in state.tracks.indices || to !in state.tracks.indices) return
        val reordered = state.tracks.toMutableList().apply { add(to, removeAt(from)) }
        val shifted = when {
            state.index == from -> to
            from < state.index && to >= state.index -> state.index - 1
            from > state.index && to <= state.index -> state.index + 1
            else -> state.index
        }
        mutableSnapshot.update { it.copy(tracks = reordered, index = shifted) }
    }

    /** Dropping the audible track is the caller's problem, so the edit reports what to play next. */
    fun removeAt(position: Int): LazerQueueEdit {
        val state = mutableSnapshot.value
        if (position !in state.tracks.indices) return LazerQueueEdit.Kept
        val remaining = state.tracks.toMutableList().apply { removeAt(position) }
        if (position != state.index) {
            val shifted = if (position < state.index) state.index - 1 else state.index
            mutableSnapshot.update { it.copy(tracks = remaining, index = shifted) }
            return LazerQueueEdit.Kept
        }
        if (remaining.isEmpty()) {
            mutableSnapshot.update { it.copy(tracks = remaining, index = -1) }
            return LazerQueueEdit.Emptied
        }
        val successorIndex = position.coerceAtMost(remaining.lastIndex)
        mutableSnapshot.update { it.copy(tracks = remaining, index = successorIndex) }
        return LazerQueueEdit.Switched
    }

    fun adjacent(): List<LazerTrack> {
        if (tracks.size < 2 || index !in tracks.indices) return emptyList()
        return listOf(
            tracks[(index + 1) % tracks.size],
            tracks[(index - 1 + tracks.size) % tracks.size],
        ).distinctBy(LazerTrack::id)
    }

    private fun advance(step: Int): LazerTrack? {
        val state = mutableSnapshot.value
        if (state.tracks.isEmpty()) return null
        val target = when {
            state.tracks.size == 1 -> state.index.coerceAtLeast(0)
            state.mode == LazerPlayMode.Shuffle -> randomOtherIndex(state)
            else -> (state.index + step + state.tracks.size) % state.tracks.size
        }
        mutableSnapshot.update { it.copy(index = target) }
        return state.tracks.getOrNull(target)
    }

    private fun randomOtherIndex(state: LazerPlaybackQueueSnapshot): Int {
        val candidates = state.tracks.indices.filter { it != state.index }
        return candidates.randomOrNull() ?: state.index.coerceAtLeast(0)
    }
}
