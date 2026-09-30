package dev.naominet.lazer

import android.content.Context
import dev.naominet.lazer.gateway.model.UserProfile
import kotlinx.coroutines.flow.StateFlow

// Android's answers to the seams the shared app is written against. The cache and the media session
// stay here because they are Android's own; everything above them is the shared code iOS reuses.

/** Restores playlists, tracks and the signed-in identity from Android's shared preferences. */
class AndroidLibraryCache(context: Context) : LazerLibraryCache {
    private val delegate = AndroidPlaylistCache(context)

    override fun loadFeaturedPlaylists(): List<LazerPlaylist> = delegate.loadFeaturedPlaylists()

    override fun saveFeaturedPlaylists(playlists: List<LazerPlaylist>) =
        delegate.saveFeaturedPlaylists(playlists)

    override fun loadCurrentUser(): UserProfile? = delegate.loadCurrentUser()

    override fun saveCurrentUser(profile: UserProfile) = delegate.saveCurrentUser(profile)

    override fun clearCurrentUser() = delegate.clearCurrentUser()

    override fun loadUserPlaylists(userId: Long): List<LazerPlaylist> =
        delegate.loadUserPlaylists(userId)

    override fun saveUserPlaylists(userId: Long, playlists: List<LazerPlaylist>) =
        delegate.saveUserPlaylists(userId, playlists)

    override fun loadLikedSongIds(userId: Long): Set<Long> = delegate.loadLikedSongIds(userId)

    override fun saveLikedSongIds(userId: Long, songIds: Set<Long>) =
        delegate.saveLikedSongIds(userId, songIds)

    override fun loadTracks(playlistId: Long): List<LazerTrack> = delegate.loadTracks(playlistId)

    override fun peekTracks(playlistId: Long): List<LazerTrack>? = delegate.peekTracks(playlistId)

    override fun saveTracks(playlistId: Long, tracks: List<LazerTrack>) =
        delegate.saveTracks(playlistId, tracks)

    override fun clearPlaylistData(): Int = delegate.clearPlaylistData()

    override fun close() = delegate.close()
}

/** Drives the foreground media session the way the Compose controls and the notification do today. */
class AndroidPlayer(private val context: Context) : LazerPlayer {
    override val snapshot: StateFlow<LazerPlaybackSnapshot> = AndroidPlaybackConnection.snapshot
    override val queue: StateFlow<LazerPlaybackQueueSnapshot> = AndroidPlaybackConnection.queue

    override fun currentQueue(): List<LazerTrack> = AndroidPlaybackConnection.currentQueue()

    override fun play(
        queue: List<LazerTrack>,
        track: LazerTrack,
        startPlaying: Boolean,
        positionMillis: Long,
    ) = AndroidPlaybackConnection.play(context, queue, track, startPlaying, positionMillis)

    override fun playAt(position: Int) = AndroidPlaybackConnection.playAt(context, position)

    override fun removeAt(position: Int) = AndroidPlaybackConnection.removeAt(context, position)

    override fun moveTrack(from: Int, to: Int) = AndroidPlaybackConnection.moveTrack(from, to)

    override fun setPlayMode(mode: LazerPlayMode) = AndroidPlaybackConnection.setPlayMode(context, mode)

    override fun toggle() = AndroidPlaybackConnection.toggle(context)

    override fun resume() = AndroidPlaybackConnection.resume(context)

    override fun pause() = AndroidPlaybackConnection.pause(context)

    override fun next() = AndroidPlaybackConnection.next(context)

    override fun previous() = AndroidPlaybackConnection.previous(context)

    override fun seekTo(positionMillis: Long) = AndroidPlaybackConnection.seekTo(context, positionMillis)

    override fun updateExclusiveAudio() = AndroidPlaybackConnection.updateExclusiveAudio(context)

    override fun updatePlaybackInterface() = AndroidPlaybackConnection.updatePlaybackInterface(context)

    override fun updateAudioLevels() = AndroidPlaybackConnection.updateAudioLevels(context)

    override fun stopAndClearSession() = AndroidPlaybackConnection.stopAndClearSession(context)
}
