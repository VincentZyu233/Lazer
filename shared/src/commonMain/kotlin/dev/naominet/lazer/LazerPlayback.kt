package dev.naominet.lazer

import dev.naominet.lazer.gateway.AudioQuality
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class PlaybackSourceStatus {
    Resolved,
    Prepared,
}

/** Gateway metadata about the selected online source. It does not describe decoded PCM or DAC output. */
data class PlaybackSourceSnapshot(
    val status: PlaybackSourceStatus,
    val requestedQuality: AudioQuality,
    val resolvedQualityAttempt: AudioQuality,
    val usedUnblockFallback: Boolean,
    val reportedType: String? = null,
    val reportedBitrate: Int? = null,
    val reportedSizeBytes: Long? = null,
)

/** Android AudioTrack format and route readback; it is not a DAC lock or bit-perfect measurement. */
data class PlaybackOutputSnapshot(
    val requestedSampleRateHz: Int,
    val audioTrackSampleRateHz: Int?,
    val encodingLabel: String,
    val channelCount: Int,
    val routedDeviceName: String?,
    val audioTrackFormatMatchesRequested: Boolean? = null,
    val mixerAdvertisesExactFormat: Boolean? = null,
    val mixerAdvertisesBitPerfectBehavior: Boolean?,
    val mixerPreferenceAccepted: Boolean?,
    val appDspMayModifySamples: Boolean? = null,
    val directPath: DirectPathSnapshot = DirectPathSnapshot(),
    val outputDataFormat: PlaybackAudioOutputDataSnapshot? = null,
)

/**
 * Format Media3 configures for data sent to the Android audio output provider. Linear PCM here is
 * post Media3 audio processing; encoded passthrough/offload is explicitly not reported as PCM.
 */
data class PlaybackAudioOutputDataSnapshot(
    val sampleRateHz: Int?,
    val encodingLabel: String?,
    val channelCount: Int?,
    val isLinearPcm: Boolean,
    val offload: Boolean,
    val tunneling: Boolean,
)

/** A currently attached USB audio output that can be selected for Android playback. */
data class LazerUsbAudioTargetOption(
    val id: String,
    val label: String,
)

/** Saved Android USB output preference and the devices currently available in the chooser. */
data class LazerUsbAudioTargetSnapshot(
    val connectedTargets: List<LazerUsbAudioTargetOption> = emptyList(),
    val selectedTargetId: String? = null,
    val selectedTargetLabel: String? = null,
    val selectedTargetAmbiguous: Boolean = false,
)

/** A USB device picked from UsbManager for UAC diagnostics; it is independent of AudioTrack routing. */
data class LazerUsbUacDeviceOption(
    val id: String,
    val label: String,
    val vendorId: Int,
    val productId: Int,
)

data class LazerUsbUacVolumeRange(
    val minimumDb256: Int,
    val maximumDb256: Int,
    val resolutionDb256: Int,
)

enum class LazerUsbUacVolumeStatus {
    Idle,
    AwaitingPermission,
    Reading,
    Ready,
    Unsupported,
    PermissionDenied,
    Failed,
}

/** USB Feature Unit status for the explicitly selected raw USB device, not the active player route. */
data class LazerUsbUacVolumeSnapshot(
    val devices: List<LazerUsbUacDeviceOption> = emptyList(),
    val selectedDeviceId: String? = null,
    val status: LazerUsbUacVolumeStatus = LazerUsbUacVolumeStatus.Idle,
    val currentDb256: Int? = null,
    val muted: Boolean = false,
    val ranges: List<LazerUsbUacVolumeRange> = emptyList(),
    val canDecrease: Boolean = false,
    val canIncrease: Boolean = false,
    val detail: String? = null,
)

object LazerUsbUacVolumeStateStore {
    private val mutableState = MutableStateFlow(LazerUsbUacVolumeSnapshot())
    val state: StateFlow<LazerUsbUacVolumeSnapshot> = mutableState.asStateFlow()

    fun publish(snapshot: LazerUsbUacVolumeSnapshot) {
        mutableState.value = snapshot
    }
}

object LazerUsbAudioTargetStateStore {
    private val mutableState = MutableStateFlow(LazerUsbAudioTargetSnapshot())
    val state: StateFlow<LazerUsbAudioTargetSnapshot> = mutableState.asStateFlow()

    fun publish(snapshot: LazerUsbAudioTargetSnapshot) {
        mutableState.value = snapshot
    }
}

/** iOS AVAudioSession rate/route readback with app-tracked activation state; never the DAC format. */
data class PlaybackAudioSessionSnapshot(
    val sourceTrackSampleRateHz: Double? = null,
    val preferredSampleRateHz: Double? = null,
    val sampleRateHz: Double? = null,
    val outputChannelCount: Int? = null,
    val outputRouteName: String? = null,
    val outputPortTypes: String? = null,
    val ioBufferDurationMillis: Int? = null,
    val active: Boolean = false,
    val interrupted: Boolean = false,
    val configurationError: String? = null,
) {
    init {
        require(sourceTrackSampleRateHz == null || (sourceTrackSampleRateHz.isFinite() && sourceTrackSampleRateHz > 0.0))
        require(preferredSampleRateHz == null || (preferredSampleRateHz.isFinite() && preferredSampleRateHz > 0.0))
        require(sampleRateHz == null || (sampleRateHz.isFinite() && sampleRateHz > 0.0))
        require(outputChannelCount == null || outputChannelCount > 0)
        require(ioBufferDurationMillis == null || ioBufferDurationMillis > 0)
    }

    companion object {
        fun fromSystemReadback(
            sampleRateHz: Double,
            outputChannelCount: Int,
            outputRouteName: String,
            outputPortTypes: String,
            ioBufferDurationSeconds: Double,
            active: Boolean,
            interrupted: Boolean,
            configurationError: String,
            sourceTrackSampleRateHz: Double = Double.NaN,
            preferredSampleRateHz: Double = Double.NaN,
        ): PlaybackAudioSessionSnapshot = PlaybackAudioSessionSnapshot(
            sourceTrackSampleRateHz = sourceTrackSampleRateHz.takeIf { it.isFinite() && it > 0.0 },
            preferredSampleRateHz = preferredSampleRateHz.takeIf { it.isFinite() && it > 0.0 },
            sampleRateHz = sampleRateHz.takeIf { active && it.isFinite() && it > 0.0 },
            outputChannelCount = outputChannelCount.takeIf { active && it > 0 },
            outputRouteName = outputRouteName.trim().takeIf(String::isNotEmpty),
            outputPortTypes = outputPortTypes.trim().takeIf(String::isNotEmpty),
            ioBufferDurationMillis = (ioBufferDurationSeconds * 1_000.0)
                .takeIf { active && it.isFinite() && it >= 1.0 }
                ?.toInt(),
            active = active,
            interrupted = interrupted,
            configurationError = configurationError.trim().takeIf(String::isNotEmpty),
        )
    }
}

object PlaybackAudioSessionStateStore {
    private val mutableSnapshot = MutableStateFlow(PlaybackAudioSessionSnapshot())
    val snapshot: StateFlow<PlaybackAudioSessionSnapshot> = mutableSnapshot.asStateFlow()

    fun publish(value: PlaybackAudioSessionSnapshot) {
        mutableSnapshot.value = value
    }
}

/** Media3 audio renderer input format; it does not prove decoded PCM or source-file bit depth. */
data class PlaybackAudioRendererInputFormatSnapshot(
    val sampleRateHz: Int?,
    val encodingLabel: String?,
    val channelCount: Int?,
    val sampleMimeType: String?,
)

data class LazerPlaybackSnapshot(
    val track: LazerTrack? = null,
    val isPreparing: Boolean = false,
    val isPlaying: Boolean = false,
    val positionMillis: Long = 0L,
    val durationMillis: Long = 0L,
    val bufferedFraction: Float = 0f,
    val message: String? = null,
    val source: PlaybackSourceSnapshot? = null,
    val output: PlaybackOutputSnapshot? = null,
    val audioRendererInputFormat: PlaybackAudioRendererInputFormatSnapshot? = null,
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

    fun update(transform: (LazerPlaybackSnapshot) -> LazerPlaybackSnapshot) {
        mutableSnapshot.update(transform)
    }
}

/**
 * The queue the audio engine is working through, shared by the screens and the platform player.
 * Owning it here means reordering, jumping and removing behave identically everywhere; the engine
 * only reports where it actually is.
 */
object LazerPlaybackQueue {
    /**
     * How many tracks the queue may hold. A playlist is loaded whole before it becomes a queue, so an
     * uncapped one leaves tens of thousands of rows living in a StateFlow that every screen collects,
     * and the preloads and room reports then walk all of them on each advance.
     */
    const val MAX_TRACKS = 1_000

    private val mutableSnapshot = MutableStateFlow(LazerPlaybackQueueSnapshot())
    val snapshot: StateFlow<LazerPlaybackQueueSnapshot> = mutableSnapshot.asStateFlow()

    val tracks: List<LazerTrack> get() = mutableSnapshot.value.tracks
    val index: Int get() = mutableSnapshot.value.index
    val mode: LazerPlayMode get() = mutableSnapshot.value.mode

    /**
     * Shuffle draws without replacement so a pass plays every track once, and keeps a bounded
     * history so “previous” replays what the listener actually heard instead of jumping randomly.
     * Both queues hold track ids, not indices: an edit to the queue may move or drop a row, and an
     * id that is no longer present is simply skipped when it comes up.
     */
    private val shuffleRemaining = ArrayDeque<Long>()
    private val shuffleVisited = ArrayDeque<Long>()

    /** Loads the stored mode before the first track can advance. */
    fun restoreMode(value: LazerPlayMode) {
        mutableSnapshot.update { it.copy(mode = value) }
        resetShuffle(if (value == LazerPlayMode.Shuffle) mutableSnapshot.value.tracks.getOrNull(index)?.id else null)
    }

    /** Restores a validated queue without changing the listener's selected track. */
    fun restoreSnapshot(value: LazerPlaybackQueueSnapshot): Boolean {
        if (value.tracks.size > MAX_TRACKS) return false
        if (value.tracks.map(LazerTrack::id).distinct().size != value.tracks.size) return false
        if (value.index !in -1..value.tracks.lastIndex) return false
        if (value.tracks.isEmpty() && value.index != -1) return false
        if (value.tracks.isNotEmpty() && value.index == -1) return false

        val restored = value.copy(tracks = value.tracks.toList())
        mutableSnapshot.value = restored
        LazerLocalTrackIdentity.reserve(
            restored.tracks.asSequence()
                .filter { it.source is LazerTrackSource.LocalFile }
                .map(LazerTrack::id)
                .toList(),
        )
        resetShuffle(if (restored.mode == LazerPlayMode.Shuffle) current()?.id else null)
        return true
    }

    fun setMode(value: LazerPlayMode) {
        val previous = mutableSnapshot.value.mode
        mutableSnapshot.update { it.copy(mode = value) }
        if (previous != value) {
            resetShuffle(if (value == LazerPlayMode.Shuffle) current()?.id else null)
        }
    }

    fun replace(queue: List<LazerTrack>, track: LazerTrack) {
        val distinct = queue.distinctBy(LazerTrack::id)
        val withCurrent = if (distinct.any { it.id == track.id }) {
            distinct
        } else {
            listOf(track) + distinct
        }
        val next = windowAroundCurrent(withCurrent, track)
        mutableSnapshot.update {
            it.copy(tracks = next, index = next.indexOfFirst { item -> item.id == track.id })
        }
        resetShuffle(if (mutableSnapshot.value.mode == LazerPlayMode.Shuffle) track.id else null)
    }

    fun current(): LazerTrack? = tracks.getOrNull(index)

    fun next(): LazerTrack? = advance(1)

    fun previous(): LazerTrack? = advance(-1)

    /** Points the queue at [position] and returns what should now be audible. */
    fun jumpTo(position: Int): LazerTrack? {
        val state = mutableSnapshot.value
        val track = state.tracks.getOrNull(position) ?: return null
        mutableSnapshot.update { it.copy(index = position) }
        if (state.mode == LazerPlayMode.Shuffle) recordShuffle(track.id)
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

    /**
     * A queue longer than the cap keeps a window around the audible track, weighted forward: what is
     * still to come matters more to a listener than what has already been heard.
     */
    private fun windowAroundCurrent(tracks: List<LazerTrack>, track: LazerTrack): List<LazerTrack> {
        if (tracks.size <= MAX_TRACKS) return tracks
        val around = tracks.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        val ahead = (MAX_TRACKS - 1) * 3 / 4
        val from = (around - (MAX_TRACKS - 1 - ahead)).coerceIn(0..tracks.size - MAX_TRACKS)
        // A view would keep the rows outside the window reachable, which is the memory being capped.
        return tracks.subList(from, from + MAX_TRACKS).toList()
    }

    private fun advance(step: Int): LazerTrack? {
        val state = mutableSnapshot.value
        if (state.tracks.isEmpty()) return null
        val target = when {
            state.tracks.size == 1 -> state.index.coerceAtLeast(0)
            state.mode == LazerPlayMode.Shuffle && step > 0 -> drawFromShuffleBag(state)
            state.mode == LazerPlayMode.Shuffle -> stepShuffleHistoryBack(state)
            else -> (state.index + step + state.tracks.size) % state.tracks.size
        }
        mutableSnapshot.update { it.copy(index = target) }
        val track = state.tracks.getOrNull(target)
        if (state.mode == LazerPlayMode.Shuffle && track != null) recordShuffle(track.id)
        return track
    }

    /**
     * Draws the next shuffle track without replacement, refilling the bag once it runs dry. The
     * refill prefers tracks the listener has not heard yet, then falls back to the whole queue
     * minus the one playing now, so a completed pass can begin again without repeating at the seam.
     */
    private fun drawFromShuffleBag(state: LazerPlaybackQueueSnapshot): Int {
        if (state.tracks.size <= 1) return state.index.coerceAtLeast(0)
        val currentId = state.tracks.getOrNull(state.index)?.id
        if (shuffleRemaining.isEmpty()) {
            state.tracks.asSequence()
                .map(LazerTrack::id)
                .filter { it != currentId && it !in shuffleVisited }
                .shuffled()
                .let(shuffleRemaining::addAll)
            if (shuffleRemaining.isEmpty()) {
                state.tracks.asSequence()
                    .map(LazerTrack::id)
                    .filter { it != currentId }
                    .shuffled()
                    .let(shuffleRemaining::addAll)
            }
        }
        while (true) {
            val id = shuffleRemaining.removeFirstOrNull() ?: break
            val index = state.tracks.indexOfFirst { it.id == id }
            if (index >= 0 && index != state.index) return index
        }
        return randomOtherIndex(state)
    }

    /** Rewinds the drawn path so shuffle's “previous” replays what the listener actually heard. */
    private fun stepShuffleHistoryBack(state: LazerPlaybackQueueSnapshot): Int {
        val currentId = state.tracks.getOrNull(state.index)?.id
        while (shuffleVisited.lastOrNull() == currentId) shuffleVisited.removeLast()
        val previousId = shuffleVisited.lastOrNull()
        val index = if (previousId == null) -1 else state.tracks.indexOfFirst { it.id == previousId }
        if (index >= 0) {
            currentId?.let(shuffleRemaining::addFirst)
            return index
        }
        return randomOtherIndex(state)
    }

    private fun resetShuffle(seedId: Long?) {
        shuffleRemaining.clear()
        shuffleVisited.clear()
        if (seedId != null) shuffleVisited.addLast(seedId)
    }

    /** Keeps the recent path bounded; the oldest ids stop being reachable by “previous”. */
    private fun recordShuffle(id: Long) {
        if (shuffleVisited.lastOrNull() != id) shuffleVisited.addLast(id)
        while (shuffleVisited.size > MAX_SHUFFLE_HISTORY) shuffleVisited.removeFirst()
    }

    private fun randomOtherIndex(state: LazerPlaybackQueueSnapshot): Int {
        val candidates = state.tracks.indices.filter { it != state.index }
        return candidates.randomOrNull() ?: state.index.coerceAtLeast(0)
    }

    private const val MAX_SHUFFLE_HISTORY = 200
}
