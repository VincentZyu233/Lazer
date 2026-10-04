package dev.naominet.lazer

import dev.naominet.lazer.gateway.NeteaseMusicGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Drives playback through the Swift side of the bridge and mirrors what it hears into the shared
 * snapshot, so every screen reads the same state the Android media session publishes. The queue, the
 * advance rule and the repeat modes stay in shared code; Swift only ever answers "play this".
 */
internal class IosPlayer(
    private val gateway: NeteaseMusicGateway,
    private val bridge: IosShellBridge,
) : LazerPlayer, IosPlayerCommands, IosAudioSessionSink, IosPlaybackFailureSink {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    override val snapshot: StateFlow<LazerPlaybackSnapshot> = LazerPlaybackStateStore.snapshot
    override val queue: StateFlow<LazerPlaybackQueueSnapshot> = LazerPlaybackQueue.snapshot

    private var exclusive = false
    private var systemMedia = true
    private var didAttachCommands = false
    private var playbackGeneration = 0L
    private val playbackFailureGate = PlaybackFailureGenerationGate()

    init {
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
        when (LazerPlaybackQueue.removeAt(position)) {
            LazerQueueEdit.Kept -> Unit
            LazerQueueEdit.Switched -> playAt(LazerPlaybackQueue.index)
            LazerQueueEdit.Emptied -> pause()
        }
    }

    override fun moveTrack(from: Int, to: Int) = LazerPlaybackQueue.move(from, to)

    override fun setPlayMode(mode: LazerPlayMode) = LazerPlaybackQueue.setMode(mode)

    override fun toggle() {
        if (snapshot.value.isPlaying) pause() else resume()
    }

    override fun resume() {
        if (snapshot.value.track == null) {
            LazerPlaybackQueue.current()?.let { play(LazerPlaybackQueue.tracks, it) }
            return
        }
        bridge.playerPlay()
        startTicker()
        publish()
    }

    override fun pause() {
        bridge.playerPause()
        publish()
    }

    override fun next() {
        LazerPlaybackQueue.next()?.let { play(LazerPlaybackQueue.tracks, it) }
    }

    override fun previous() {
        LazerPlaybackQueue.previous()?.let { play(LazerPlaybackQueue.tracks, it) }
    }

    override fun seekTo(positionMillis: Long) {
        bridge.playerSeekTo(positionMillis)
        publish()
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
        playbackGeneration += 1
        playbackFailureGate.begin(playbackGeneration)
        bridge.playerPause()
        bridge.playerRelease()
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
        bridge.playerLoad(url, startPlaying, positionMillis.coerceAtLeast(0L), generation)
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
        val position = bridge.playerPositionMillis()
        val duration = bridge.playerDurationMillis().takeIf { it > 0L } ?: track.durationMillis
        val playing = bridge.playerIsPlaying()
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
}
