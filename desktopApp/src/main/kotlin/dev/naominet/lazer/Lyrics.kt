package dev.naominet.lazer

/** Compatibility names for the desktop surface; timeline logic lives in commonMain. */
internal fun parseLrc(lrc: String?): List<TimedLyricLine> = parseTimedLrc(lrc)

internal fun parseDesktopWordLyrics(yrc: String?): List<TimedLyricLine> = parseTimedWordLyrics(yrc)

internal fun desktopLyricsWithInterludes(
    lines: List<TimedLyricLine>,
    durationMs: Long,
): List<TimedLyricLine> = timedLyricsWithInterludes(lines, durationMs)

internal fun activeDesktopInterlude(
    lines: List<TimedLyricLine>,
    positionMs: Long,
): TimedLyricLine? = activeTimedInterlude(lines, positionMs)

internal fun desktopLyricDisplayLines(
    timeline: List<TimedLyricLine>,
    interlude: TimedLyricLine?,
): List<TimedLyricLine> = displayTimedLyricLines(timeline, interlude)

internal fun mergeLyrics(
    lyrics: List<TimedLyricLine>,
    translatedLyrics: List<TimedLyricLine>,
): List<TimedLyricLine> = mergeTimedLyrics(lyrics, translatedLyrics)
