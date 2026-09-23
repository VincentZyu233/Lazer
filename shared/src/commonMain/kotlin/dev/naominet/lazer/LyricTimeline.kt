package dev.naominet.lazer

/** A lyric row shared by every player surface. Times are absolute offsets within the track. */
data class TimedLyricLine(
    val timeMs: Long,
    val text: String,
    val translation: String? = null,
    val words: List<TimedLyricWord> = emptyList(),
    val endTimeMs: Long? = null,
) {
    /** Android used the longer name before lyric timelines moved to common. */
    val timeMillis: Long get() = timeMs
    val endTimeMillis: Long? get() = endTimeMs
}

/** Parses standard LRC rows, including rows carrying more than one timestamp. */
fun parseTimedLrc(lrc: String?): List<TimedLyricLine> {
    if (lrc.isNullOrBlank()) return emptyList()
    val lines = mutableListOf<TimedLyricLine>()
    for (raw in lrc.split('\n', '\r')) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) continue
        val (stamps, text) = parseLrcRow(trimmed) ?: continue
        for (time in stamps) {
            lines += TimedLyricLine(time, text)
        }
    }
    return lines.sortedBy(TimedLyricLine::timeMs)
}

/** Converts YRC word timings into the same timeline used by both player surfaces. */
fun parseTimedWordLyrics(yrc: String?): List<TimedLyricLine> =
    parseWordLyrics(yrc).map { line ->
        TimedLyricLine(
            timeMs = line.timeMillis,
            text = line.text,
            words = line.words,
            endTimeMs = line.timeMillis + line.durationMillis,
        )
    }

/** Merges the nearest translation row when it is within one second of the source row. */
fun mergeTimedLyrics(
    lyrics: List<TimedLyricLine>,
    translatedLyrics: List<TimedLyricLine>,
): List<TimedLyricLine> {
    if (lyrics.isEmpty() || translatedLyrics.isEmpty()) return lyrics
    val sortedTranslations = translatedLyrics.sortedBy(TimedLyricLine::timeMs)
    return lyrics.map { line ->
        val translation = sortedTranslations
            .minByOrNull { kotlin.math.abs(it.timeMs - line.timeMs) }
            ?.takeIf { kotlin.math.abs(it.timeMs - line.timeMs) <= 1_000L }
            ?.text
        line.copy(translation = translation)
    }
}

/** Adds a short instrumental row only when a long gap makes it useful. */
fun timedLyricsWithInterludes(
    lines: List<TimedLyricLine>,
    durationMs: Long,
): List<TimedLyricLine> = buildList {
    val first = lines.firstOrNull() ?: return@buildList
    if (first.timeMs >= 5_000L && first.text.isNotBlank()) {
        add(TimedLyricLine(0L, "", endTimeMs = first.timeMs))
    }
    lines.forEachIndexed { index, line ->
        val next = lines.getOrNull(index + 1)?.timeMs ?: durationMs
        add(if (line.text.isBlank()) line.copy(endTimeMs = next) else line)
        if (line.text.isNotBlank()) {
            val end = line.endTimeMs ?: line.words.maxOfOrNull { it.startTimeMillis + it.durationMillis }
            lyricInterludeStart(line.timeMs, end, next)?.let { start ->
                add(TimedLyricLine(start, "", endTimeMs = next))
            }
        }
    }
}

/** Returns the active row, or -1 before the first timed row. */
fun findCurrentLyricIndex(lines: List<TimedLyricLine>, positionMs: Long): Int {
    if (lines.isEmpty()) return -1
    var low = 0
    var high = lines.lastIndex
    var result = -1
    while (low <= high) {
        val middle = (low + high) ushr 1
        if (lines[middle].timeMs <= positionMs) {
            result = middle
            low = middle + 1
        } else {
            high = middle - 1
        }
    }
    return result
}

fun activeTimedInterlude(
    lines: List<TimedLyricLine>,
    positionMs: Long,
): TimedLyricLine? = lines.getOrNull(findCurrentLyricIndex(lines, positionMs))?.takeIf {
    it.text.isBlank() && positionMs < (it.endTimeMs ?: Long.MAX_VALUE)
}

fun displayTimedLyricLines(
    timeline: List<TimedLyricLine>,
    interlude: TimedLyricLine?,
): List<TimedLyricLine> = timeline.filter { it.text.isNotBlank() || it === interlude }
