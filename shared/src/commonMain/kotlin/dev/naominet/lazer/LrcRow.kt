package dev.naominet.lazer

/**
 * NetEase writes the fractional second of a lyric stamp with either separator: `[00:12.950]` in
 * some releases and `[00:12:95]` in others. Accepting only the dotted form silently drops every
 * sung line of such songs, leaving just the un-timed credit header on screen.
 */
private val LrcStampPattern = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

/**
 * Reads one LRC row and returns its start offsets with the row text, or `null` when the row is
 * not timed. Callers decide whether an empty text still deserves a line.
 */
fun parseLrcRow(row: String): Pair<List<Long>, String>? {
    val stamps = mutableListOf<Long>()
    var lastStampEnd = 0
    for (match in LrcStampPattern.findAll(row)) {
        val minute = match.groupValues[1].toLongOrNull() ?: continue
        val second = match.groupValues[2].toLongOrNull() ?: continue
        stamps += minute * 60_000L + second * 1_000L + lrcFractionMillis(match.groupValues[3])
        lastStampEnd = match.range.last + 1
    }
    if (stamps.isEmpty()) return null
    return stamps to row.substring(lastStampEnd).trim()
}

private fun lrcFractionMillis(fraction: String): Long = when (fraction.length) {
    0 -> 0L
    1 -> fraction.toLong() * 100L
    2 -> fraction.toLong() * 10L
    else -> fraction.take(3).padEnd(3, '0').toLong()
}
