package dev.naominet.lazer

import dev.naominet.lazer.gateway.NeteaseMusicGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.absoluteValue

/**
 * Drives playback through the Swift side of the bridge and mirrors what it hears into the shared
 * snapshot, so every screen reads the same state the Android media session publishes. The queue, the
 * advance rule and the repeat modes stay in shared code; Swift only ever answers "play this".
 */
internal class IosPlayer(
    private val gateway: NeteaseMusicGateway,
    private val bridge: IosShellBridge,
    private val queueStore: IosPlaybackQueueStore = IosPlaybackQueueStore(),
) : LazerPlayer, IosPlayerCommands, IosAudioSessionSink, IosPlaybackFailureSink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    override val snapshot: StateFlow<LazerPlaybackSnapshot> = LazerPlaybackStateStore.snapshot
    override val queue: StateFlow<LazerPlaybackQueueSnapshot> = LazerPlaybackQueue.snapshot

    private var exclusive = false
    private var systemMedia = true
    private var didAttachCommands = false
    private var playbackGeneration = 0L
    private var queuePersistenceReady = false
    private var currentItemLoaded = false
    private var pendingPositionTrackId: Long? = null
    private var pendingPositionMillis = 0L
    private var lastCheckpointTrackId: Long? = null
    private var lastCheckpointPositionMillis = 0L
    private val playbackFailureGate = PlaybackFailureGenerationGate()

    init {
        if (!processQueueInitialized) {
            queuePersistenceReady = restorePersistedQueue()
            processQueuePersistenceReady = queuePersistenceReady
            processQueueInitialized = true
        } else {
            queuePersistenceReady = processQueuePersistenceReady
        }
        scope.launch { queue.collect(::persistQueue) }

        bridge.playerAttachAudioSessionSink(this)
        bridge.playerSetEndedHandler {
            scope.launch {
                if (LazerPlaybackQueue.mode == LazerPlayMode.SingleLoop) {
                    bridge.playerSeekTo(0L)
                    bridge.playerPlay()
                } else {
                    LazerPlaybackQueue.next()?.let { play(LazerPlaybackQueue.tracks, it) }
                }
            }
        }
        bridge.playerAttachFailureSink(this)
    }

    override fun currentQueue(): List<LazerTrack> = LazerPlaybackQueue.tracks

    override fun play(
        queue: List<LazerTrack>,
        track: LazerTrack,
        startPlaying: Boolean,
        positionMillis: Long,
    ) {
        // The system's transport is only worth asking for once there is something to transport.
        if (!didAttachCommands) {
            didAttachCommands = true
            bridge.playerAttachCommands(this)
        }
        LazerPlaybackQueue.replace(queue, track)
        // Commit queue references before Swift is allowed to reclaim imported files.
        queuePersistenceReady = true
        processQueuePersistenceReady = true
        persistQueue(LazerPlaybackQueue.snapshot.value)
        currentItemLoaded = false
        pendingPositionTrackId = track.id
        pendingPositionMillis = positionMillis.coerceAtLeast(0L)
        val generation = ++playbackGeneration
        playbackFailureGate.begin(generation)
        LazerPlaybackStateStore.update(
            LazerPlaybackSnapshot(
                track = track,
                isPreparing = true,
                positionMillis = positionMillis.coerceAtLeast(0L),
                durationMillis = track.durationMillis,
            ),
        )
        scope.launch { start(track, startPlaying, positionMillis, generation) }
    }

    override fun playAt(position: Int) {
        val track = LazerPlaybackQueue.jumpTo(position) ?: return
        play(LazerPlaybackQueue.tracks, track)
    }

    override fun removeAt(position: Int) {
        val edit = LazerPlaybackQueue.removeAt(position)
        persistQueue(LazerPlaybackQueue.snapshot.value)
        when (edit) {
            LazerQueueEdit.Kept -> Unit
            LazerQueueEdit.Switched -> playAt(LazerPlaybackQueue.index)
            LazerQueueEdit.Emptied -> {
                bridge.playerPause()
                bridge.playerRelease()
                currentItemLoaded = false
                ticker?.cancel()
                LazerPlaybackStateStore.update(LazerPlaybackSnapshot())
            }
        }
    }

    override fun moveTrack(from: Int, to: Int) {
        LazerPlaybackQueue.move(from, to)
        persistQueue(LazerPlaybackQueue.snapshot.value)
    }

    override fun setPlayMode(mode: LazerPlayMode) {
        LazerPlaybackQueue.setMode(mode)
        persistQueue(LazerPlaybackQueue.snapshot.value)
    }

    override fun toggle() {
        if (snapshot.value.isPlaying) pause() else resume()
    }

    override fun resume() {
        if (snapshot.value.track == null) {
            LazerPlaybackQueue.current()?.let { play(LazerPlaybackQueue.tracks, it) }
            return
        }
        if (!currentItemLoaded) {
            val state = snapshot.value
            state.track?.let {
                play(LazerPlaybackQueue.tracks, it, startPlaying = true, positionMillis = state.positionMillis)
            }
            return
        }
        bridge.playerPlay()
        startTicker()
        publish()
    }

    override fun pause() {
        bridge.playerPause()
        publish()
        persistPosition(force = true, requestedPositionMillis = snapshot.value.positionMillis)
    }

    override fun next() {
        LazerPlaybackQueue.next()?.let { play(LazerPlaybackQueue.tracks, it) }
    }

    override fun previous() {
        LazerPlaybackQueue.previous()?.let { play(LazerPlaybackQueue.tracks, it) }
    }

    override fun seekTo(positionMillis: Long) {
        bridge.playerSeekTo(positionMillis)
        val requested = positionMillis.coerceAtLeast(0L)
        pendingPositionTrackId = snapshot.value.track?.id
        pendingPositionMillis = requested
        LazerPlaybackStateStore.update { it.copy(positionMillis = requested) }
        persistPosition(force = true, requestedPositionMillis = positionMillis)
    }

    override fun updateExclusiveAudio(exclusive: Boolean) {
        this.exclusive = exclusive
        bridge.playerSetAudioMode(exclusive, systemMedia)
    }

    override fun updatePlaybackInterface(systemMedia: Boolean) {
        this.systemMedia = systemMedia
        bridge.playerSetAudioMode(exclusive, systemMedia)
    }

    /**
     * Nothing captures the playing audio here, so the indicator keeps the motion the shared screens
     * synthesize; the switch that would ask for a capture is hidden with [IosScreenHost].
     */
    override fun updateAudioLevels(enabled: Boolean) = Unit

    override fun stopAndClearSession() {
        persistPosition(force = true, requestedPositionMillis = snapshot.value.positionMillis)
        playbackGeneration += 1
        playbackFailureGate.begin(playbackGeneration)
        bridge.playerPause()
        bridge.playerRelease()
        currentItemLoaded = false
        ticker?.cancel()
        LazerPlaybackStateStore.update(LazerPlaybackSnapshot())
    }

    override fun didChangeAudioSession(
        sourceTrackSampleRateHz: Double,
        preferredSampleRateHz: Double,
        sampleRateHz: Double,
        outputChannelCount: Int,
        outputRouteName: String,
        outputPortTypes: String,
        ioBufferDurationSeconds: Double,
        active: Boolean,
        interrupted: Boolean,
        configurationError: String,
    ) {
        PlaybackAudioSessionStateStore.publish(
            PlaybackAudioSessionSnapshot.fromSystemReadback(
                sampleRateHz = sampleRateHz,
                outputChannelCount = outputChannelCount,
                outputRouteName = outputRouteName,
                outputPortTypes = outputPortTypes,
                ioBufferDurationSeconds = ioBufferDurationSeconds,
                active = active,
                interrupted = interrupted,
                configurationError = configurationError,
                sourceTrackSampleRateHz = sourceTrackSampleRateHz,
                preferredSampleRateHz = preferredSampleRateHz,
            ),
        )
    }

    override fun didFailPlayback(generation: Long, detail: String) {
        scope.launch {
            if (generation != playbackGeneration || !playbackFailureGate.accept(generation)) return@launch
            val state = snapshot.value
            if (state.track == null || LazerPlaybackQueue.current() != state.track) return@launch
            currentItemLoaded = false
            bridge.playerPause()
            val reason = detail.trim().ifEmpty { tr("status.track_unplayable") }
            LazerPlaybackStateStore.update(
                state.copy(
                    isPreparing = false,
                    isPlaying = false,
                    message = "${tr("status.playback_failed")}: $reason",
                ),
            )
            publish()
        }
    }

    private suspend fun start(
        track: LazerTrack,
        startPlaying: Boolean,
        positionMillis: Long,
        generation: Long,
    ) {
        val url = when (val source = track.source) {
            LazerTrackSource.GatewaySong -> withContext(Dispatchers.Default) {
                runCatching { gateway.songUrls(listOf(track.id)).data.firstOrNull()?.url }.getOrNull()
            }
            is LazerTrackSource.LocalFile -> source.uri
        }
        if (generation != playbackGeneration) return
        if (url.isNullOrBlank()) {
            LazerPlaybackStateStore.update(
                LazerPlaybackSnapshot(
                    track = track,
                    message = tr("status.track_unplayable"),
                ),
            )
            return
        }
        if (track.source is LazerTrackSource.LocalFile && !bridge.playerLocalAudioFileExists(url)) {
            currentItemLoaded = false
            LazerPlaybackStateStore.update(
                LazerPlaybackSnapshot(track = track, message = tr("status.track_unplayable")),
            )
            return
        }
        bridge.playerLoad(url, startPlaying, positionMillis.coerceAtLeast(0L), generation)
        currentItemLoaded = true
        if (startPlaying) startTicker()
        publish()
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                publish()
                delay(250L)
            }
        }
    }

    private fun publish() {
        val state = snapshot.value
        val track = state.track ?: return
        val reportedPosition = bridge.playerPositionMillis()
        val duration = bridge.playerDurationMillis().takeIf { it > 0L } ?: track.durationMillis
        val playing = bridge.playerIsPlaying()
        val isPendingPosition = pendingPositionTrackId == track.id
        val position = if (isPendingPosition &&
            (reportedPosition - pendingPositionMillis).absoluteValue > SEEK_COMPLETION_TOLERANCE_MILLIS
        ) {
            pendingPositionMillis
        } else {
            if (isPendingPosition) pendingPositionTrackId = null
            reportedPosition
        }
        // The same share of the track the seek bar paints behind the playhead on Android.
        val buffered = if (duration > 0L) {
            ((bridge.playerBufferedMillis() - position).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        LazerPlaybackStateStore.update(
            state.copy(
                isPlaying = playing,
                isPreparing = false,
                positionMillis = position,
                durationMillis = duration,
                bufferedFraction = buffered,
            ),
        )
        persistPosition(requestedPositionMillis = position)
        if (!systemMedia) return
        bridge.playerUpdateNowPlaying(
            title = track.title,
            artist = track.artist,
            album = track.album,
            coverUrl = track.coverUrl,
            positionMillis = position,
            durationMillis = duration,
            isPlaying = playing,
        )
    }

    /* The system asked for these through the lock screen, a headset or Control Centre. The rest of
       the commands share a name and a meaning with the on-screen controls, so those serve both. */
    override fun play() = resume()

    override fun seekToMillis(millis: Long) = seekTo(millis)

    /** Restores the last committed queue without resuming audio during app launch. */
    private fun restorePersistedQueue(): Boolean {
        return when (val stored = queueStore.loadQueue()) {
            IosStoredQueue.Missing -> {
                LazerPlaybackQueue.restoreSnapshot(LazerPlaybackQueueSnapshot())
                bridge.playerUpdateLocalAudioQueue(emptyList(), queueStateAvailable = true, canPruneOrphans = true)
                true
            }
            IosStoredQueue.Invalid -> {
                bridge.playerUpdateLocalAudioQueue(emptyList(), queueStateAvailable = false, canPruneOrphans = false)
                LazerPlaybackStateStore.update(
                    LazerPlaybackSnapshot(message = tr("status.playback_queue.restore_failed")),
                )
                false
            }
            is IosStoredQueue.Valid -> {
                val original = stored.snapshot
                bridge.playerUpdateLocalAudioQueue(
                    uris = original.tracks.mapNotNull { (it.source as? LazerTrackSource.LocalFile)?.uri }.distinct(),
                    queueStateAvailable = true,
                    canPruneOrphans = true,
                )
                val originalCurrent = original.tracks.getOrNull(original.index)
                val retained = original.tracks.filter { track ->
                    val local = track.source as? LazerTrackSource.LocalFile
                    local == null || bridge.playerLocalAudioFileExists(local.uri)
                }
                val retainedCurrentIndex = originalCurrent?.let { current ->
                    retained.indexOfFirst { it.id == current.id }.takeIf { it >= 0 }
                }
                val index = when {
                    retained.isEmpty() -> -1
                    retainedCurrentIndex != null -> retainedCurrentIndex
                    else -> original.index.coerceIn(retained.indices)
                }
                val restored = LazerPlaybackQueueSnapshot(retained, index, original.mode)
                if (!LazerPlaybackQueue.restoreSnapshot(restored)) {
                    bridge.playerUpdateLocalAudioQueue(emptyList(), queueStateAvailable = false, canPruneOrphans = false)
                    return false
                }
                val current = restored.tracks.getOrNull(restored.index)
                val savedPosition = queueStore.loadPosition()
                val position = LazerPlaybackQueueCodec.restoredPositionMillis(
                    current,
                    savedPosition?.first,
                    savedPosition?.second,
                )
                lastCheckpointTrackId = savedPosition?.first
                lastCheckpointPositionMillis = savedPosition?.second ?: 0L
                val skipped = original.tracks.size - retained.size
                LazerPlaybackStateStore.update(
                    LazerPlaybackSnapshot(
                        track = current,
                        positionMillis = position,
                        durationMillis = current?.durationMillis ?: 0L,
                        message = if (skipped > 0) tr("status.local_audio.restore_missing", skipped) else null,
                    ),
                )
                true
            }
        }
    }

    private fun persistQueue(value: LazerPlaybackQueueSnapshot) {
        if (!queuePersistenceReady) {
            bridge.playerUpdateLocalAudioQueue(emptyList(), queueStateAvailable = false, canPruneOrphans = false)
            return
        }
        val saved = queueStore.saveQueue(value)
        if (saved && value.tracks.isEmpty()) queueStore.clearPosition()
        if (saved) processQueuePersistenceReady = true
        bridge.playerUpdateLocalAudioQueue(
            uris = if (saved) value.tracks.mapNotNull { (it.source as? LazerTrackSource.LocalFile)?.uri }.distinct() else emptyList(),
            queueStateAvailable = saved,
            canPruneOrphans = false,
        )
    }

    private fun persistPosition(
        force: Boolean = false,
        requestedPositionMillis: Long? = null,
    ) {
        val track = snapshot.value.track ?: return
        val position = requestedPositionMillis?.coerceAtLeast(0L) ?: bridge.playerPositionMillis().coerceAtLeast(0L)
        if (!force && lastCheckpointTrackId == track.id &&
            (position - lastCheckpointPositionMillis).absoluteValue < POSITION_CHECKPOINT_INTERVAL_MILLIS
        ) return
        if (queueStore.savePosition(track.id, position)) {
            lastCheckpointTrackId = track.id
            lastCheckpointPositionMillis = position
        }
    }

    private companion object {
        const val POSITION_CHECKPOINT_INTERVAL_MILLIS = 5_000L
        const val SEEK_COMPLETION_TOLERANCE_MILLIS = 250L

        var processQueueInitialized = false
        var processQueuePersistenceReady = false
    }
}
