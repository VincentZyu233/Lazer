package dev.naominet.lazer

enum class DesktopReplayGainMode { Off, Track, Album }

enum class DesktopReplayGainSource { None, Track, Album, TrackFallback }

data class DesktopReplayGainResolution(
    val appliedGainDb: Double = 0.0,
    val source: DesktopReplayGainSource = DesktopReplayGainSource.None,
    val boostBlocked: Boolean = false,
    val boostLimited: Boolean = false,
)

/** Resolves tagged ReplayGain without applying any stateful playback or DSP behavior. */
fun resolveDesktopReplayGain(
    tags: DesktopReplayGainTags?,
    mode: DesktopReplayGainMode,
): DesktopReplayGainResolution {
    if (mode == DesktopReplayGainMode.Off || tags == null) return DesktopReplayGainResolution()

    val selected = when (mode) {
        DesktopReplayGainMode.Off -> return DesktopReplayGainResolution()
        DesktopReplayGainMode.Track -> Candidate(
            gainDb = tags.trackGainDb.validReplayGainGain(),
            peak = tags.trackPeak.validReplayGainPeak(),
            source = DesktopReplayGainSource.Track,
        )
        DesktopReplayGainMode.Album -> {
            val albumGain = tags.albumGainDb.validReplayGainGain()
            if (albumGain != null) {
                Candidate(albumGain, tags.albumPeak.validReplayGainPeak(), DesktopReplayGainSource.Album)
            } else {
                Candidate(
                    gainDb = tags.trackGainDb.validReplayGainGain(),
                    peak = tags.trackPeak.validReplayGainPeak(),
                    source = DesktopReplayGainSource.TrackFallback,
                )
            }
        }
    }

    val gainDb = selected.gainDb ?: return DesktopReplayGainResolution()
    if (gainDb <= 0.0) return DesktopReplayGainResolution(gainDb, selected.source)

    val peak = selected.peak
        ?: return DesktopReplayGainResolution(
            source = selected.source,
            boostBlocked = true,
            boostLimited = true,
        )
    val maxGainForHeadroom = -SAMPLE_PEAK_HEADROOM_DB - 20.0 * kotlin.math.log10(peak)
    val appliedGainDb = minOf(gainDb, maxGainForHeadroom).coerceAtLeast(0.0)
    return DesktopReplayGainResolution(
        appliedGainDb = appliedGainDb,
        source = selected.source,
        boostBlocked = appliedGainDb == 0.0,
        boostLimited = appliedGainDb < gainDb,
    )
}

private data class Candidate(
    val gainDb: Double?,
    val peak: Double?,
    val source: DesktopReplayGainSource,
)

private fun Double?.validReplayGainGain(): Double? =
    this?.takeIf { it.isFinite() && it in MIN_REPLAYGAIN_GAIN_DB..MAX_REPLAYGAIN_GAIN_DB }

private fun Double?.validReplayGainPeak(): Double? =
    this?.takeIf { it.isFinite() && it > 0.0 && it <= MAX_REPLAYGAIN_PEAK }

private const val MIN_REPLAYGAIN_GAIN_DB = -60.0
private const val MAX_REPLAYGAIN_GAIN_DB = 24.0
private const val MAX_REPLAYGAIN_PEAK = 16.0
private const val SAMPLE_PEAK_HEADROOM_DB = 1.0
