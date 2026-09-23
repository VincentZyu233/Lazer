package dev.naominet.lazer

typealias AndroidTimedLyricLine = TimedLyricLine

/** Compatibility names for Android callers; timeline logic lives in commonMain. */
internal fun parseAndroidLrc(lrc: String?): List<AndroidTimedLyricLine> = parseTimedLrc(lrc)

internal fun parseAndroidWordLyrics(yrc: String?): List<AndroidTimedLyricLine> =
    parseTimedWordLyrics(yrc)

internal fun mergeAndroidLyrics(
    lyrics: List<AndroidTimedLyricLine>,
    translations: List<AndroidTimedLyricLine>,
): List<AndroidTimedLyricLine> = mergeTimedLyrics(lyrics, translations)

internal fun activeAndroidLyricIndex(
    lines: List<AndroidTimedLyricLine>,
    positionMillis: Long,
): Int = findCurrentLyricIndex(lines, positionMillis)

internal fun androidLyricsWithInterludes(
    lines: List<AndroidTimedLyricLine>,
    durationMillis: Long,
): List<AndroidTimedLyricLine> = timedLyricsWithInterludes(lines, durationMillis)

internal fun activeAndroidInterlude(
    lines: List<AndroidTimedLyricLine>,
    positionMillis: Long,
): AndroidTimedLyricLine? = activeTimedInterlude(lines, positionMillis)

internal fun androidLyricDisplayLines(
    timeline: List<AndroidTimedLyricLine>,
    interlude: AndroidTimedLyricLine?,
): List<AndroidTimedLyricLine> = displayTimedLyricLines(timeline, interlude)
