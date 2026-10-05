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
    val source: LazerTrackSource = LazerTrackSource.GatewaySong,
    val replayGain: LazerReplayGainTags? = null,
) {
    val durationLabel: String get() = formatPlaybackTime(durationMillis)
}

/** Describes where audio bytes come from; track IDs remain queue tokens, not source locators. */
sealed interface LazerTrackSource {
    data object GatewaySong : LazerTrackSource

    data class LocalFile(val uri: String) : LazerTrackSource
}

/** Basic metadata returned by a platform audio picker before a queue entry is created. */
data class LazerPickedAudioFile(
    val uri: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMillis: Long = 0L,
    val replayGain: LazerReplayGainTags? = null,
)

data class LazerLocalAudioPickerResult(
    val files: List<LazerPickedAudioFile> = emptyList(),
    val unsupportedFileCount: Int = 0,
    val failedFileCount: Int = 0,
)

/** Allocates IDs outside the positive Gateway song-ID space for local queue entries. */
object LazerLocalTrackIdentity {
    private var nextId = Long.MIN_VALUE

    fun nextId(): Long {
        check(nextId < 0L) { "Local track identity space exhausted" }
        return nextId++
    }

    /** Keeps newly picked local tracks unique after queue IDs have been restored from disk. */
    fun reserve(ids: Collection<Long>) {
        val highestUsedId = ids.asSequence().filter { it < 0L }.maxOrNull() ?: return
        val firstFreeId = if (highestUsedId == -1L) 0L else highestUsedId + 1L
        if (nextId < firstFreeId) nextId = firstFreeId
    }
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
    val remainder = seconds % 60
    return if (remainder < 10) "${seconds / 60}:0$remainder" else "${seconds / 60}:$remainder"
}

/**
 * Netease cover URLs come back from different endpoints in several shapes. Every platform's image
 * loader is handed the same absolute https form, including values cached before this existed.
 */
fun normalizedArtworkUrl(raw: String?): String? {
    val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
    return when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://", ignoreCase = true) -> "https://${value.substringAfter("://")}"
        value.startsWith("https://", ignoreCase = true) -> value
        else -> null
    }
}

private val ArtworkSizeParameter = Regex("([?&]param=)\\d+y\\d+", RegexOption.IGNORE_CASE)

/** The same CDN sizing parameter, shared by the artwork helpers and the background palette. */
internal val lazerArtworkSizeParameter = ArtworkSizeParameter

/**
 * The CDN resizes on request, so asking for the size actually displayed keeps the palette thumbnail
 * and the full player artwork on one cached image instead of two.
 */
fun enlargedArtworkUrl(raw: String?, sizePx: Int = 1024): String? {
    require(sizePx > 0)
    val normalized = normalizedArtworkUrl(raw) ?: return null
    val host = normalized.substringAfter("://").substringBefore('/')
        .substringAfterLast('@').substringBefore(':')
    if (!host.equals("music.126.net", ignoreCase = true) &&
        !host.endsWith(".music.126.net", ignoreCase = true)
    ) return normalized
    if (ArtworkSizeParameter.containsMatchIn(normalized)) {
        return normalized.replace(ArtworkSizeParameter) { match ->
            "${match.groupValues[1]}${sizePx}y$sizePx"
        }
    }
    if (normalized.contains("param=", ignoreCase = true)) return normalized
    val base = normalized.substringBefore('#')
    val fragment = normalized.substringAfter('#', "").let { if ('#' in normalized) "#$it" else "" }
    return base + (if ('?' in base) "&" else "?") + "param=${sizePx}y$sizePx" + fragment
}
