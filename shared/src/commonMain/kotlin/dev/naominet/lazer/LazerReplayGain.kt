package dev.naominet.lazer

import kotlin.math.log10
import kotlin.math.min

/** Track and album ReplayGain values, with peaks kept paired with their corresponding gain. */
data class LazerReplayGainTags(
    val trackGainDb: Double? = null,
    val trackPeak: Double? = null,
    val albumGainDb: Double? = null,
    val albumPeak: Double? = null,
)

enum class LazerReplayGainMode {
    Off,
    Track,
    Album,
}

enum class LazerReplayGainSource {
    None,
    Track,
    Album,
}

data class LazerReplayGainResolution(
    val appliedGainDb: Double,
    val source: LazerReplayGainSource,
    val requestedGainDb: Double? = null,
    val peak: Double? = null,
    val peakLimited: Boolean = false,
)

/**
 * Resolves the gain to apply. Album mode uses album gain when valid, otherwise it falls back to the
 * track pair. Positive gain requires its matching valid sample peak and is capped to leave 1 dBFS
 * of headroom; a negative headroom cap is retained as attenuation so the peak limit still holds.
 */
fun resolveLazerReplayGain(tags: LazerReplayGainTags?, mode: LazerReplayGainMode): LazerReplayGainResolution {
    if (tags == null || mode == LazerReplayGainMode.Off) {
        return LazerReplayGainResolution(0.0, LazerReplayGainSource.None)
    }

    val selected = when (mode) {
        LazerReplayGainMode.Off -> return LazerReplayGainResolution(0.0, LazerReplayGainSource.None)
        LazerReplayGainMode.Track -> Triple(tags.trackGainDb, tags.trackPeak, LazerReplayGainSource.Track)
        LazerReplayGainMode.Album -> {
            if (tags.albumGainDb?.isValidLazerReplayGainDb() == true) {
                Triple(tags.albumGainDb, tags.albumPeak, LazerReplayGainSource.Album)
            } else {
                Triple(tags.trackGainDb, tags.trackPeak, LazerReplayGainSource.Track)
            }
        }
    }
    val gainDb = selected.first?.takeIf(Double::isValidLazerReplayGainDb)
        ?: return LazerReplayGainResolution(0.0, LazerReplayGainSource.None)
    val source = selected.third
    val peak = selected.second?.takeIf(Double::isValidLazerReplayGainPeak)
    if (gainDb <= 0.0) {
        return LazerReplayGainResolution(
            appliedGainDb = gainDb,
            source = source,
            requestedGainDb = gainDb,
            peak = peak,
        )
    }

    if (peak == null) {
        return LazerReplayGainResolution(
            appliedGainDb = 0.0,
            source = source,
            requestedGainDb = gainDb,
            peakLimited = true,
        )
    }
    val maxGainForHeadroomDb = -1.0 - 20.0 * log10(peak)
    val appliedGainDb = min(gainDb, maxGainForHeadroomDb)
    return LazerReplayGainResolution(
        appliedGainDb = appliedGainDb,
        source = source,
        requestedGainDb = gainDb,
        peak = peak,
        peakLimited = appliedGainDb < gainDb,
    )
}

internal fun Double.isValidLazerReplayGainDb(): Boolean =
    isFinite() && this in MIN_REPLAY_GAIN_DB..MAX_REPLAY_GAIN_DB

internal fun Double.isValidLazerReplayGainPeak(): Boolean =
    isFinite() && this > 0.0 && this <= MAX_REPLAY_GAIN_PEAK

internal const val MIN_REPLAY_GAIN_DB = -60.0
internal const val MAX_REPLAY_GAIN_DB = 24.0
internal const val MAX_REPLAY_GAIN_PEAK = 16.0
