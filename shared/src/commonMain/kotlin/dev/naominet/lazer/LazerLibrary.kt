package dev.naominet.lazer

import dev.naominet.lazer.gateway.model.Artist

/**
 * What the player and the screens need to know about one track. It stays independent from the
 * transport model so cookies and raw API responses never reach a composable, and a queue can be
 * restored without an Activity or a view controller on either platform.
 */
data class LazerTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMillis: Long,
    val coverUrl: String? = null,
    val artists: List<Artist> = emptyList(),
    val translatedTitle: String? = null,
) {
    val durationLabel: String get() = formatPlaybackTime(durationMillis)
}

data class LazerPlaylist(
    val id: Long,
    val title: String,
    val subtitle: String,
    val coverUrl: String? = null,
    val trackCount: Int = 0,
    val isLikedCollection: Boolean = false,
)

fun formatPlaybackTime(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0L) / 1_000L).toInt()
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
