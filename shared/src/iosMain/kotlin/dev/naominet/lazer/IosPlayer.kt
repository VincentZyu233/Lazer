@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.naominet.lazer

import dev.naominet.lazer.gateway.NeteaseMusicGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.CoreMedia.CMTimeMakeWithSeconds

import platform.AVFoundation.AVPlayerTimeControlStatusPaused

import platform.Foundation.NSURL
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.addObserverForName
import kotlinx.coroutines.flow.StateFlow

/**
 * Plays through AVPlayer and mirrors what it does into the shared snapshot, so every screen reads
 * the same state the Android media session publishes. Position is polled rather than observed:
 * the shared player surface only ever needs frame-rate accuracy, and polling keeps the Kotlin side
 * free of key-value observer plumbing.
 */
internal class IosPlayer(private val gateway: NeteaseMusicGateway) : LazerPlayer {
    private var player: AVPlayer? = null
    private var endObserver: Any? = null
    private var ticker: Job? = null
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var pendingTrack: LazerTrack? = null

    override val snapshot: StateFlow<LazerPlaybackSnapshot> = LazerPlaybackStateStore.snapshot
    override val queue: StateFlow<LazerPlaybackQueueSnapshot> = LazerPlaybackQueue.snapshot

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
        val track = pendingTrack ?: snapshot.value.track
        if (player == null && track != null) {
            play(LazerPlaybackQueue.tracks, track)
            return
        }
        player?.play()
        startTicker()
        publish(playing = true)
    }

    override fun pause() {
        player?.pause()
        publish(playing = false)
    }

    override fun next() {
        LazerPlaybackQueue.next()?.let { play(LazerPlaybackQueue.tracks, it) }
    }

    override fun previous() {
        LazerPlaybackQueue.previous()?.let { play(LazerPlaybackQueue.tracks, it) }
    }

    override fun seekTo(positionMillis: Long) {
        player?.seekToTime(CMTimeMakeWithSeconds(positionMillis.toDouble() / 1000.0, 1_000))
        publish(playing = snapshot.value.isPlaying)
    }

    /** Nothing on iOS takes another app's audio, exposes a system transport or captures levels. */
    override fun updateExclusiveAudio() = Unit

    override fun updatePlaybackInterface() = Unit

    override fun updateAudioLevels() = Unit

    override fun stopAndClearSession() {
        pause()
        releasePlayer()
        LazerPlaybackStateStore.update(LazerPlaybackSnapshot())
    }

    private suspend fun start(track: LazerTrack, startPlaying: Boolean, positionMillis: Long) {
        val url = withContext(Dispatchers.Default) {
            runCatching { gateway.songUrls(listOf(track.id)).data.firstOrNull()?.url }.getOrNull()
        }
        if (url.isNullOrBlank()) {
            LazerPlaybackStateStore.update(
                LazerPlaybackSnapshot(track = track, message = "这首歌现在无法播放，可以试试其他歌曲。"),
            )
            return
        }
        pendingTrack = track
        releasePlayer()
        ensureAudioSession()
        val address = NSURL.URLWithString(url) ?: return
        val next = AVPlayer()
        next.replaceCurrentItemWithPlayerItem(AVPlayerItem(address))
        player = next
        watchForEndOfItem(item)
        if (positionMillis > 0L) next.seekToTime(CMTimeMakeWithSeconds(positionMillis / 1000.0, 1_000))
        if (startPlaying) {
            next.play()
            startTicker()
            publish(playing = true, preparing = true)
        } else {
            publish(playing = false, preparing = true)
        }
    }

    private fun ensureAudioSession() {
        runCatching {
            AVAudioSession.sharedInstance().setCategory(AVAudioSessionCategoryPlayback, error = null)
            AVAudioSession.sharedInstance().setActive(true, error = null)
        }
    }

    private fun watchForEndOfItem(item: AVPlayerItem) {
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = NSNotificationCenter.defaultCenter.addObserverForName(
            name = "AVPlayerItemDidPlayToEndTimeNotification",
            `object` = item,
            queue = NSOperationQueue.mainQueue,
        ) { _: NSNotification? ->
            // A single loop repeats the track that just ended; everything else moves on.
            if (LazerPlaybackQueue.mode == LazerPlayMode.SingleLoop) {
                seekTo(0L)
                player?.play()
            } else {
                LazerPlaybackQueue.next()?.let { play(LazerPlaybackQueue.tracks, it) }
            }
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                publish(playing = player?.timeControlStatus != AVPlayerTimeControlStatusPaused)
                delay(250L)
            }
        }
    }

    private fun publish(playing: Boolean, preparing: Boolean = false) {
        val state = snapshot.value
        val track = state.track ?: return
        LazerPlaybackStateStore.update(
            state.copy(
                isPlaying = playing,
                isPreparing = preparing,
                positionMillis = currentMillis(),
                durationMillis = durationMillis() ?: track.durationMillis,
            ),
        )
    }

    private fun currentMillis(): Long = player?.currentTime()?.useContents { seconds }
        ?.let { (it * 1000).toLong() } ?: 0L

    private fun durationMillis(): Long? = player?.currentItem?.duration?.useContents { seconds }
        ?.takeIf { it.isFinite() && it > 0 }?.let { (it * 1000).toLong() }

    private fun releasePlayer() {
        endObserver?.let { NSNotificationCenter.defaultCenter.removeObserver(it) }
        endObserver = null
        player?.pause()
        player = null
    }

    fun close() {
        ticker?.cancel()
        releasePlayer()
        scope.cancel()
    }
}
