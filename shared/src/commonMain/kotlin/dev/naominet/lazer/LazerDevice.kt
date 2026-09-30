package dev.naominet.lazer

import androidx.compose.ui.graphics.ImageBitmap
import dev.naominet.lazer.gateway.GatewaySessionStore
import dev.naominet.lazer.gateway.model.UserProfile
import kotlinx.coroutines.flow.StateFlow

/**
 * Restored metadata and complete track lists, so a screen shows what the listener saw last time
 * before the network answers. Both platforms keep the same entries under the same names.
 */
interface LazerLibraryCache {
    fun loadFeaturedPlaylists(): List<LazerPlaylist>

    fun saveFeaturedPlaylists(playlists: List<LazerPlaylist>)

    fun loadCurrentUser(): UserProfile?

    fun saveCurrentUser(profile: UserProfile)

    fun clearCurrentUser()

    fun loadUserPlaylists(userId: Long): List<LazerPlaylist>

    fun saveUserPlaylists(userId: Long, playlists: List<LazerPlaylist>)

    fun loadLikedSongIds(userId: Long): Set<Long>

    fun saveLikedSongIds(userId: Long, songIds: Set<Long>)

    fun loadTracks(playlistId: Long): List<LazerTrack>

    /** Already-decoded data only, so a click handler never waits on disk or JSON. */
    fun peekTracks(playlistId: Long): List<LazerTrack>?

    fun saveTracks(playlistId: Long, tracks: List<LazerTrack>)

    /** Drops playlist and track data while keeping the cached signed-in identity. Returns the count. */
    fun clearPlaylistData(): Int

    fun close()
}

/**
 * The audio the platform actually plays. Everything above this interface - the queue, the snapshot,
 * what a tap on a row does - is shared, so the player behaves the same everywhere and only the
 * output differs.
 */
interface LazerPlayer {
    val snapshot: StateFlow<LazerPlaybackSnapshot>
    val queue: StateFlow<LazerPlaybackQueueSnapshot>

    fun currentQueue(): List<LazerTrack>

    fun play(
        queue: List<LazerTrack>,
        track: LazerTrack,
        startPlaying: Boolean = true,
        positionMillis: Long = 0L,
    )

    fun playAt(position: Int)

    fun removeAt(position: Int)

    fun moveTrack(from: Int, to: Int)

    fun setPlayMode(mode: LazerPlayMode)

    fun toggle()

    fun resume()

    fun pause()

    fun next()

    fun previous()

    fun seekTo(positionMillis: Long)

    fun updateExclusiveAudio()

    fun updatePlaybackInterface()

    fun updateAudioLevels()

    fun stopAndClearSession()
}

/**
 * The file, cache and diagnostics work the shared state layer cannot do itself. Everything here is
 * decided by the platform's storage rules; the listener never sees it, so the screens stay identical.
 */
interface LazerPlatformHost {
    /** Decodes an image the platform can read from disk, or null when it no longer exists. */
    fun decodeImageFile(path: String): ImageBitmap?

    /** Copies a picked image into app storage so it survives the picker going away. */
    fun importImageFile(source: String, name: String): String?

    fun deleteImportedImage(path: String)

    fun writeExportedFile(target: String, bytes: ByteArray): Boolean

    fun deleteExportedFile(target: String)

    /** Fetches a cover at full size so the listener can keep it outside the app. */
    fun downloadFile(url: String): ByteArray?

    /** Turns encoded image bytes into something the screens can draw. */
    fun decodeImageBytes(bytes: ByteArray): ImageBitmap?

    /** Drops the platform's own downloaded media cache. Returns how many files went away. */
    fun clearPlatformCache(): Int

    /** Hands the loaded lyric lines to whatever lyric surface the platform owns. */
    fun publishLyrics(trackId: Long, lines: List<TimedLyricLine>)

    fun log(message: String, error: Throwable)
}

/** Everything a platform contributes to the shared app, in one object the UI is handed. */
class LazerDevice(
    val preferences: LazerPreferences,
    val sessionStore: GatewaySessionStore,
    val cache: LazerLibraryCache,
    val player: LazerPlayer,
    val host: LazerPlatformHost,
) {
    val settings: LazerSettingsStore = LazerSettingsStore(preferences)
}
