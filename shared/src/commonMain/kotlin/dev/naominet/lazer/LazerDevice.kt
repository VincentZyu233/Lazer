package dev.naominet.lazer

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
 * The screen-level asks a platform answers in its own way: scan a QR code, authorize a login in a
 * browser, hand a link to another app. Each shared screen calls these instead of a platform API, so
 * the layout and the flow stay identical while the sheet that opens belongs to the host.
 */
interface LazerPlatformHost {
    /** Human-readable device name the About page and the lyric overlay show. */
    val deviceLabel: String

    /** Whether this build was compiled for debugging, which is what the watermark checks. */
    val isDebugBuild: Boolean

    /** Radius of the display's own corners, so sheets can meet them instead of clipping. */
    val screenCornerRadiusPx: Float

    fun startQrLogin(url: String, onComplete: (result: String?) -> Unit)

    fun scanCode(onResult: (text: String?) -> Unit)

    fun shareText(text: String, title: String)

    fun openExternally(url: String): Boolean

    fun openSystemSoundSettings()

    fun pickBackgroundImage(onPicked: (path: String?) -> Unit)

    fun requestMicrophonePermission(onResult: (granted: Boolean) -> Unit)

    fun vibrate(durationMillis: Long)
}

/** Everything a platform contributes to the shared app, in one object the UI is handed. */
class LazerDevice(
    val preferences: LazerPreferences,
    val cache: LazerLibraryCache,
    val player: LazerPlayer,
    val host: LazerPlatformHost,
) {
    val settings: LazerSettingsStore = LazerSettingsStore(preferences)
}
