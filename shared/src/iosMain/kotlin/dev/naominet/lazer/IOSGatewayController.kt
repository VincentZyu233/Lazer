@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.naominet.lazer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.naominet.lazer.gateway.NeteaseMusicGateway
import dev.naominet.lazer.gateway.model.Playlist
import dev.naominet.lazer.gateway.model.Song
import dev.naominet.lazer.gateway.model.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import platform.Foundation.NSNotificationCenter

internal const val IOS_PLAY_URL_NOTIFICATION = "dev.naominet.lazer.play-url"
internal const val IOS_PAUSE_NOTIFICATION = "dev.naominet.lazer.pause"
internal const val IOS_RESUME_NOTIFICATION = "dev.naominet.lazer.resume"

/** iOS state holder. Rendering stays in Compose while AVPlayer remains a native shell concern. */
internal class IOSGatewayController {
    init {
        println("LAZER_IOS_CONTROLLER_STAGE:start")
    }

    val settings = IOSSettingsStore().also {
        println("LAZER_IOS_CONTROLLER_STAGE:settings")
    }

    // HttpClient engine discovery can touch native networking code. Keep it out of the first
    // composition so an engine/setup failure becomes a recoverable page error instead of aborting
    // the Compose root before iOS can draw its first frame.
    private val gatewayDelegate = lazy(LazyThreadSafetyMode.NONE) {
        println("LAZER_IOS_CONTROLLER_STAGE:gateway:create")
        try {
            NeteaseMusicGateway(sessionStore = IOSGatewaySessionStore()).also {
                println("LAZER_IOS_CONTROLLER_STAGE:gateway:ready")
            }
        } catch (error: Throwable) {
            println("LAZER_IOS_CONTROLLER_GATEWAY_ERROR:$error")
            throw error
        }
    }
    private val gateway: NeteaseMusicGateway
        get() = gatewayDelegate.value

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main).also {
        println("LAZER_IOS_CONTROLLER_STAGE:scope")
    }
    private var searchJob: Job? = null
    private var didBootstrap = false
    private var queue: List<Song> = emptyList()

    var isReady by mutableStateOf(false)
        private set
    var isHomeLoading by mutableStateOf(false)
        private set
    var isLibraryLoading by mutableStateOf(false)
        private set
    var isPlaylistLoading by mutableStateOf(false)
        private set
    var isSearchLoading by mutableStateOf(false)
        private set
    var isPlaybackLoading by mutableStateOf(false)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    var featuredPlaylists by mutableStateOf<List<Playlist>>(emptyList())
        private set
    var userPlaylists by mutableStateOf<List<Playlist>>(emptyList())
        private set
    var searchResults by mutableStateOf<List<Song>>(emptyList())
        private set
    var currentProfile by mutableStateOf<UserProfile?>(null)
        private set
    var activePlaylist by mutableStateOf<Playlist?>(null)
        private set
    var activePlaylistTracks by mutableStateOf<List<Song>>(emptyList())
        private set
    var nowPlaying by mutableStateOf<Song?>(null)
        private set

    fun bootstrap() {
        if (didBootstrap) return
        didBootstrap = true
        scope.launch {
            runCatching { loadLazerTranslations() }
            isReady = true
            refreshHome()
            refreshLibrary()
        }
    }

    fun refreshHome() {
        if (isHomeLoading) return
        scope.launch {
            isHomeLoading = true
            try {
                featuredPlaylists = gateway.topPlaylists(limit = 24, forceRefresh = true).playlists
            } catch (error: Throwable) {
                handleFailure(error, "暂时无法整理推荐内容，请稍后再试。")
            } finally {
                isHomeLoading = false
            }
        }
    }

    fun refreshLibrary() {
        if (isLibraryLoading) return
        scope.launch {
            isLibraryLoading = true
            try {
                val profile = gateway.loginStatus().data?.profile
                currentProfile = profile
                userPlaylists = if (profile == null || profile.userId <= 0L) {
                    emptyList()
                } else {
                    gateway.userPlaylists(profile.userId, limit = 100, forceRefresh = true).playlist
                }
            } catch (error: Throwable) {
                currentProfile = null
                userPlaylists = emptyList()
                if (gateway.sessionCookie != null) {
                    handleFailure(error, "暂时无法同步音乐库，请稍后再试。")
                }
            } finally {
                isLibraryLoading = false
            }
        }
    }

    fun loginWithCookie(cookie: String) {
        if (cookie.isBlank()) return
        scope.launch {
            isLibraryLoading = true
            try {
                val response = gateway.loginWithCookie(cookie)
                val profile = response.data?.profile
                if (profile == null || profile.userId <= 0L) {
                    message = "这个登录信息已失效，请重新获取。"
                } else {
                    currentProfile = profile
                    userPlaylists = gateway.userPlaylists(profile.userId, limit = 100, forceRefresh = true).playlist
                    settings.setDestination(IOSDestination.LIBRARY)
                }
            } catch (error: Throwable) {
                handleFailure(error, "登录没有完成，请检查网络或登录信息。")
            } finally {
                isLibraryLoading = false
            }
        }
    }

    fun logout() {
        scope.launch {
            runCatching { gateway.logoutSession() }
            gateway.clearSession()
            currentProfile = null
            userPlaylists = emptyList()
        }
    }

    fun search(query: String) {
        val normalized = query.trim()
        searchJob?.cancel()
        if (normalized.isEmpty()) {
            searchResults = emptyList()
            isSearchLoading = false
            return
        }
        searchJob = scope.launch {
            isSearchLoading = true
            try {
                searchResults = gateway.search(normalized, limit = 50).result?.songs.orEmpty()
            } catch (error: Throwable) {
                handleFailure(error, "这次搜索没有完成，请稍后再试。")
            } finally {
                isSearchLoading = false
            }
        }
    }

    fun openPlaylist(playlist: Playlist) {
        activePlaylist = playlist
        activePlaylistTracks = playlist.tracks
        scope.launch {
            isPlaylistLoading = true
            try {
                activePlaylistTracks = gateway.playlistTracks(
                    id = playlist.id,
                    limit = 500,
                    forceRefresh = true,
                ).songs
            } catch (error: Throwable) {
                handleFailure(error, "暂时无法打开这个歌单，请稍后再试。")
            } finally {
                isPlaylistLoading = false
            }
        }
    }

    fun closePlaylist() {
        activePlaylist = null
        activePlaylistTracks = emptyList()
    }

    fun play(song: Song, from: List<Song>) {
        queue = from.ifEmpty { listOf(song) }
        nowPlaying = song
        scope.launch {
            isPlaybackLoading = true
            try {
                val url = gateway.songUrls(listOf(song.id)).data.firstOrNull()?.url
                if (url.isNullOrBlank()) {
                    message = "这首歌现在无法播放，可以试试其他歌曲。"
                    isPlaying = false
                } else {
                    NSNotificationCenter.defaultCenter.postNotificationName(
                        IOS_PLAY_URL_NOTIFICATION,
                        url,
                    )
                    isPlaying = true
                }
            } catch (error: Throwable) {
                isPlaying = false
                handleFailure(error, "播放没有开始，请稍后再试。")
            } finally {
                isPlaybackLoading = false
            }
        }
    }

    fun togglePlayback() {
        if (nowPlaying == null || isPlaybackLoading) return
        isPlaying = !isPlaying
        NSNotificationCenter.defaultCenter.postNotificationName(
            if (isPlaying) IOS_RESUME_NOTIFICATION else IOS_PAUSE_NOTIFICATION,
            null,
        )
    }

    fun playNext() = moveInQueue(1)

    fun playPrevious() = moveInQueue(-1)

    private fun moveInQueue(delta: Int) {
        val current = nowPlaying ?: return
        if (queue.isEmpty()) return
        val index = queue.indexOfFirst { it.id == current.id }.takeIf { it >= 0 } ?: 0
        val target = queue[(index + delta + queue.size) % queue.size]
        play(target, queue)
    }

    fun clearMessage() {
        message = null
    }

    fun close() {
        searchJob?.cancel()
        scope.cancel()
        if (gatewayDelegate.isInitialized()) {
            gateway.close()
        }
    }

    private fun handleFailure(error: Throwable, userMessage: String) {
        if (error is CancellationException) throw error
        message = userMessage
    }
}
