package dev.naominet.lazer

data class AndroidTimedLyricLine(
    val timeMillis: Long,
    val text: String,
    val translation: String? = null,
    val words: List<TimedLyricWord> = emptyList(),
    val endTimeMillis: Long? = null,
)

private val AndroidLrcStampPattern = Regex("""\[(\d{1,2}):(\d{2})(?:\.(\d{1,3}))?]""")

internal fun parseAndroidLrc(lrc: String?): List<AndroidTimedLyricLine> {
    if (lrc.isNullOrBlank()) return emptyList()
    return buildList {
        lrc.split('\n', '\r').forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            var lastStampEnd = 0
            val stamps = AndroidLrcStampPattern.findAll(line).mapNotNull { match ->
                val minute = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val second = match.groupValues[2].toLongOrNull() ?: return@mapNotNull null
                val fraction = match.groupValues[3]
                val millis = when (fraction.length) {
                    0 -> 0L
                    1 -> fraction.toLong() * 100L
                    2 -> fraction.toLong() * 10L
                    else -> fraction.take(3).padEnd(3, '0').toLong()
                }
                lastStampEnd = match.range.last + 1
                minute * 60_000L + second * 1_000L + millis
            }.toList()
            val text = line.substring(lastStampEnd).trim()
            stamps.forEach { add(AndroidTimedLyricLine(it, text)) }
        }
    }.sortedBy(AndroidTimedLyricLine::timeMillis)
}

internal fun parseAndroidWordLyrics(yrc: String?): List<AndroidTimedLyricLine> =
    parseWordLyrics(yrc).map { line ->
        AndroidTimedLyricLine(
            timeMillis = line.timeMillis,
            text = line.text,
            words = line.words,
            endTimeMillis = line.timeMillis + line.durationMillis,
        )
    }

internal fun mergeAndroidLyrics(
    lyrics: List<AndroidTimedLyricLine>,
    translations: List<AndroidTimedLyricLine>,
): List<AndroidTimedLyricLine> {
    if (lyrics.isEmpty() || translations.isEmpty()) return lyrics
    return lyrics.map { line ->
        val translated = translations.minByOrNull { kotlin.math.abs(it.timeMillis - line.timeMillis) }
            ?.takeIf { kotlin.math.abs(it.timeMillis - line.timeMillis) <= 1_000L }
            ?.text
        line.copy(translation = translated)
    }
}

internal fun activeAndroidLyricIndex(lines: List<AndroidTimedLyricLine>, positionMillis: Long): Int {
    var lower = 0
    var upper = lines.lastIndex
    var result = -1
    while (lower <= upper) {
        val middle = (lower + upper) ushr 1
        if (lines[middle].timeMillis <= positionMillis) {
            result = middle
            lower = middle + 1
        } else {
            upper = middle - 1
        }
    }
    return result
}

internal fun androidLyricsWithInterludes(lines: List<AndroidTimedLyricLine>, durationMillis: Long): List<AndroidTimedLyricLine> = buildList {
    val first = lines.firstOrNull() ?: return@buildList
    if (first.timeMillis >= 5_000L && first.text.isNotBlank()) {
        add(AndroidTimedLyricLine(0L, "", endTimeMillis = first.timeMillis))
    }
    lines.forEachIndexed { index, line ->
        val next = lines.getOrNull(index + 1)?.timeMillis ?: durationMillis
        add(if (line.text.isBlank()) line.copy(endTimeMillis = next) else line)
        if (line.text.isNotBlank()) {
            val end = line.endTimeMillis ?: line.words.maxOfOrNull { it.startTimeMillis + it.durationMillis }
            lyricInterludeStart(line.timeMillis, end, next)?.let { start ->
                add(AndroidTimedLyricLine(start, "", endTimeMillis = next))
            }
        }
    }
}

/** Timing metadata is retained, but only the current interlude participates in layout. */
internal fun activeAndroidInterlude(lines: List<AndroidTimedLyricLine>, positionMillis: Long): AndroidTimedLyricLine? =
    lines.getOrNull(activeAndroidLyricIndex(lines, positionMillis))?.takeIf {
        it.text.isBlank() && positionMillis < (it.endTimeMillis ?: Long.MAX_VALUE)
    }

internal fun androidLyricDisplayLines(
    timeline: List<AndroidTimedLyricLine>,
    interlude: AndroidTimedLyricLine?,
): List<AndroidTimedLyricLine> = timeline.filter { it.text.isNotBlank() || it === interlude }
