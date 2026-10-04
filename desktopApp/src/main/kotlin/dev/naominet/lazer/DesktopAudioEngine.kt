package dev.naominet.lazer

import java.io.Closeable

/**
 * The playback surface the desktop controller depends on. There are two implementations: the Java
 * Sound / JavaMP3 path, and the native `lazer-audio` engine (FFmpeg decode plus WASAPI output).
 * Both publish through the same callbacks, so switching engines never reaches the rest of the app.
 */
internal interface DesktopAudioEngine : Closeable {
    fun play(
        url: String,
        trackId: Long,
        cacheVariant: String,
        expectedBytes: Long?,
        durationMillis: Long,
        fromProgress: Float = 0f,
        volume: Float = DEFAULT_DESKTOP_VOLUME,
        playWhenReady: Boolean = true,
    ): Long

    fun pause()

    fun resume()

    fun setVolume(value: Float)

    fun setUiForeground(foreground: Boolean)

    suspend fun setExclusiveAudio(enabled: Boolean)

    fun setEqualizer(state: LazerEqualizerState)

    /** Best-effort successor preparation; the native engine keeps one source in the active session. */
    fun queueNext(playback: DesktopQueuedPlayback) = Unit

    fun clearQueuedNext() = Unit

    fun stop()

    suspend fun clearCache(): Int
}

internal data class DesktopQueuedPlayback(
    val track: TrackItem,
    val afterTrackId: Long,
    val url: String?,
    val cacheVariant: String,
    val expectedBytes: Long?,
    val bitrate: Int?,
    val replayGainDb: Double = 0.0,
)
