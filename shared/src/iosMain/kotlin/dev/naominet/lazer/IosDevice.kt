@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.naominet.lazer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import dev.naominet.lazer.gateway.GatewaySessionStore
import dev.naominet.lazer.gateway.NeteaseMusicGateway
import dev.naominet.lazer.gateway.model.Artist
import dev.naominet.lazer.gateway.model.UserProfile
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.Foundation.NSUserDefaults
import platform.Foundation.NSURLSession
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataTaskWithRequest
import platform.Foundation.dataWithBytes
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.removeItemAtPath
import platform.posix.memcpy

private val deviceJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

private fun documentDirectory(): String = writableDirectory(NSDocumentDirectory)

private fun writableDirectory(directory: platform.Foundation.NSSearchPathDirectory): String =
    (NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true).firstOrNull() as? String)
        ?: NSTemporaryDirectory()

private fun NSData.toByteArray(): ByteArray {
    val output = ByteArray(length.toInt())
    if (output.isNotEmpty()) {
        bytes()?.let { source -> output.usePinned { pinned -> memcpy(pinned.addressOf(0), source, length) } }
    }
    return output
}

private fun ByteArray.toNSData(): NSData? =
    if (isEmpty()) NSData() else usePinned { NSData.dataWithBytes(it.addressOf(0), size.toULong()) }

/** iOS's answers to the file, download and diagnostics asks of the shared state layer. */
internal class IosPlatformHost : LazerPlatformHost {
    private val manager = NSFileManager.defaultManager

    override fun decodeImageFile(path: String): ImageBitmap? =
        NSData.dataWithContentsOfFile(path)?.toByteArray()?.let(::decodeImageBytes)

    override fun decodeImageBytes(bytes: ByteArray): ImageBitmap? = bytes.let {
        runCatching { org.jetbrains.skia.Image.makeFromEncoded(it).toComposeImageBitmap() }.getOrNull()
    }

    override fun importImageFile(source: String, name: String): String? {
        val from = if (source.startsWith("file://")) source.removePrefix("file://") else source
        val target = documentDirectory() + "/" + name
        manager.removeItemAtPath(target, null)
        if (!manager.copyItemAtPath(from, target, null)) return null
        return target
    }

    override fun deleteImportedImage(path: String) {
        manager.removeItemAtPath(path, null)
    }

    override fun writeExportedFile(target: String, bytes: ByteArray): Boolean =
        bytes.toNSData()?.writeToFile(target, atomically = true) == true

    override fun deleteExportedFile(target: String) {
        manager.removeItemAtPath(target, null)
    }

    override suspend fun downloadFile(url: String): ByteArray? = suspendCancellableCoroutine { continuation ->
        val address = NSURL.URLWithString(url)
        if (address == null) {
            continuation.resume(null)
            return@suspendCancellableCoroutine
        }
        val task = NSURLSession.sharedSession.dataTaskWithRequest(
            NSURLRequest.requestWithURL(address),
        ) { data, _, _ ->
            if (continuation.isActive) continuation.resume(data?.toByteArray())
        }
        task.resume()
    }

    override fun clearPlatformCache(): Int {
        val root = writableDirectory(NSCachesDirectory)
        val contents = manager.contentsOfDirectoryAtPath(root, null) as? List<*> ?: return 0
        return contents.count { entry ->
            entry is String && manager.removeItemAtPath("$root/$entry", null)
        }
    }

    /** iOS has no system lyric surface to feed, so the lines stay inside the app. */
    override fun publishLyrics(trackId: Long, lines: List<TimedLyricLine>) = Unit

    override fun log(message: String, error: Throwable) {
        println("LAZER_IOS $message: $error")
    }
}

/**
 * Restores playlists, tracks and the signed-in identity from the device's own defaults. The shapes
 * mirror what Android caches, so a listener who switches platforms sees the same restored library.
 */
internal class IosLibraryCache(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : LazerLibraryCache {
    private val memory = mutableMapOf<String, Any?>()

    override fun loadFeaturedPlaylists(): List<LazerPlaylist> = readPlaylists(FEATURED)

    override fun saveFeaturedPlaylists(playlists: List<LazerPlaylist>) = writePlaylists(FEATURED, playlists)

    override fun loadCurrentUser(): UserProfile? {
        memory[CURRENT_USER]?.let { return it as UserProfile? }
        val decoded = defaults.stringForKey(CURRENT_USER)?.let { encoded ->
            runCatching { deviceJson.decodeFromString(UserProfile.serializer(), encoded) }.getOrNull()
        }
        memory[CURRENT_USER] = decoded
        return decoded
    }

    override fun saveCurrentUser(profile: UserProfile) {
        memory[CURRENT_USER] = profile
        defaults.setObject(deviceJson.encodeToString(UserProfile.serializer(), profile), CURRENT_USER)
    }

    override fun clearCurrentUser() {
        memory[CURRENT_USER] = null
        defaults.setObject(null, CURRENT_USER)
    }

    override fun loadUserPlaylists(userId: Long): List<LazerPlaylist> =
        readPlaylists("$USER_PLAYLISTS$userId")

    override fun saveUserPlaylists(userId: Long, playlists: List<LazerPlaylist>) =
        writePlaylists("$USER_PLAYLISTS$userId", playlists)

    override fun loadLikedSongIds(userId: Long): Set<Long> {
        val key = "$USER_LIKED$userId"
        memory[key]?.let { return it as Set<Long> }
        val decoded = defaults.stringForKey(key)?.let { encoded ->
            runCatching {
                deviceJson.decodeFromString(SetSerializer(Long.serializer()), encoded)
            }.getOrNull()
        }.orEmpty()
        memory[key] = decoded
        return decoded
    }

    override fun saveLikedSongIds(userId: Long, songIds: Set<Long>) {
        val key = "$USER_LIKED$userId"
        memory[key] = songIds
        defaults.setObject(deviceJson.encodeToString(SetSerializer(Long.serializer()), songIds), key)
    }

    override fun loadTracks(playlistId: Long): List<LazerTrack> {
        val key = "$PLAYLIST_TRACKS$playlistId"
        memory[key]?.let { return it as List<LazerTrack> }
        val decoded = defaults.stringForKey(key)?.let { encoded ->
            runCatching {
                deviceJson.decodeFromString(ListSerializer(StoredTrack.serializer()), encoded)
                    .map(StoredTrack::toTrack)
            }.getOrNull()
        }.orEmpty()
        memory[key] = decoded
        return decoded
    }

    @Suppress("UNCHECKED_CAST")
    override fun peekTracks(playlistId: Long): List<LazerTrack>? =
        memory["$PLAYLIST_TRACKS$playlistId"] as? List<LazerTrack>

    override fun saveTracks(playlistId: Long, tracks: List<LazerTrack>) {
        val key = "$PLAYLIST_TRACKS$playlistId"
        memory[key] = tracks
        defaults.setObject(
            deviceJson.encodeToString(ListSerializer(StoredTrack.serializer()), tracks.map(StoredTrack::from)),
            key,
        )
    }

    override fun clearPlaylistData(): Int {
        val keys = defaults.dictionaryRepresentation().keys.mapNotNull { it as? String }
            .filter {
                it == FEATURED ||
                    it.startsWith(USER_PLAYLISTS) ||
                    it.startsWith(USER_LIKED) ||
                    it.startsWith(PLAYLIST_TRACKS)
            }
        keys.forEach { key ->
            memory.remove(key)
            defaults.setObject(null, key)
        }
        return keys.size
    }

    override fun close() = Unit

    private fun readPlaylists(key: String): List<LazerPlaylist> {
        memory[key]?.let { return it as List<LazerPlaylist> }
        val decoded = defaults.stringForKey(key)?.let { encoded ->
            runCatching {
                deviceJson.decodeFromString(ListSerializer(StoredPlaylist.serializer()), encoded)
                    .map(StoredPlaylist::toPlaylist)
            }.getOrNull()
        }.orEmpty()
        memory[key] = decoded
        return decoded
    }

    private fun writePlaylists(key: String, playlists: List<LazerPlaylist>) {
        memory[key] = playlists
        defaults.setObject(
            deviceJson.encodeToString(ListSerializer(StoredPlaylist.serializer()), playlists.map(StoredPlaylist::from)),
            key,
        )
    }

    private companion object {
        const val FEATURED = "lazer.cache.featured"
        const val CURRENT_USER = "lazer.cache.current_user"
        const val USER_PLAYLISTS = "lazer.cache.user_playlists."
        const val USER_LIKED = "lazer.cache.liked_song_ids."
        const val PLAYLIST_TRACKS = "lazer.cache.playlist_tracks."
    }
}

/** Wire formats for the cache, so the UI models stay free of serialization details. */
@Serializable
private data class StoredTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMillis: Long,
    val coverUrl: String? = null,
    val artists: List<Artist> = emptyList(),
    val translatedTitle: String? = null,
) {
    fun toTrack(): LazerTrack =
        LazerTrack(id, title, artist, album, durationMillis, coverUrl, artists, translatedTitle)

    companion object {
        fun from(track: LazerTrack) = StoredTrack(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMillis = track.durationMillis,
            coverUrl = track.coverUrl,
            artists = track.artists,
            translatedTitle = track.translatedTitle,
        )
    }
}

@Serializable
private data class StoredPlaylist(
    val id: Long,
    val title: String,
    val subtitle: String,
    val coverUrl: String? = null,
    val trackCount: Int = 0,
    val isLikedCollection: Boolean = false,
) {
    fun toPlaylist(): LazerPlaylist = LazerPlaylist(id, title, subtitle, coverUrl, trackCount, isLikedCollection)

    companion object {
        fun from(playlist: LazerPlaylist) = StoredPlaylist(
            id = playlist.id,
            title = playlist.title,
            subtitle = playlist.subtitle,
            coverUrl = playlist.coverUrl,
            trackCount = playlist.trackCount,
            isLikedCollection = playlist.isLikedCollection,
        )
    }
}

/** The Gateway session cookie, kept in the device's own defaults like every other preference. */
internal class IosSessionStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) : GatewaySessionStore {
    override var cookie: String?
        get() = defaults.stringForKey(SESSION_KEY)
        set(value) = defaults.setObject(value, SESSION_KEY)

    private companion object {
        const val SESSION_KEY = "lazer.ios.gatewayCookie"
    }
}

/** Everything the shared app needs from iOS, in one object. */
internal fun iosDevice(bridge: IosShellBridge): LazerDevice {
    val sessionStore = IosSessionStore()
    val gateway = NeteaseMusicGateway(sessionStore = sessionStore)
    return LazerDevice(
        preferences = iosPreferences(),
        sessionStore = sessionStore,
        cache = IosLibraryCache(),
        player = IosPlayer(gateway),
        host = IosPlatformHost(),
        gateway = gateway,
    )
}

internal fun iosScreenHost(bridge: IosShellBridge): LazerScreenHost = IosScreenHost(bridge)
