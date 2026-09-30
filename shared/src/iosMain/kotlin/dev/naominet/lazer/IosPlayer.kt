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
) : LazerPlayer, IosPlayerCommands {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    override val snapshot: StateFlow<LazerPlaybackSnapshot> = LazerPlaybackStateStore.snapshot
    override val queue: StateFlow<LazerPlaybackQueueSnapshot> = LazerPlaybackQueue.snapshot

    private var exclusive = false
    private var systemMedia = true

    init {
        bridge.playerAttachCommands(this)
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
    }

    override fun currentQueue(): List<LazerTrack> = LazerPlaybackQueue.tracks

    override fun play(
        queue: List<LazerTrack>,
        track: LazerTrack,
        startPlaying: Boolean,
        positionMillis: Long,
    ) {
        LazerPlaybackQueue.replace(queue, track)
        LazerPlaybackStateStore.update(
            LazerPlaybackSnapshot(
                track = track,
                isPreparing = true,
                positionMillis = positionMillis.coerceAtLeast(0L),
                durationMillis = track.durationMillis,
            ),
        )
        scope.launch { start(track, startPlaying, positionMillis) }
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
        bridge.playerPause()
        bridge.playerRelease()
        ticker?.cancel()
        LazerPlaybackStateStore.update(LazerPlaybackSnapshot())
    }

    private suspend fun start(track: LazerTrack, startPlaying: Boolean, positionMillis: Long) {
        val url = withContext(Dispatchers.Default) {
            runCatching { gateway.songUrls(listOf(track.id)).data.firstOrNull()?.url }.getOrNull()
        }
        if (url.isNullOrBlank()) {
            LazerPlaybackStateStore.update(
                LazerPlaybackSnapshot(
                    track = track,
                    message = "这首歌现在无法播放，可以试试其他歌曲。",
                ),
            )
            return
        }
        bridge.playerLoad(url, startPlaying, positionMillis.coerceAtLeast(0L))
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
        LazerPlaybackStateStore.update(
            state.copy(
                isPlaying = playing,
                isPreparing = false,
                positionMillis = position,
                durationMillis = duration,
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
