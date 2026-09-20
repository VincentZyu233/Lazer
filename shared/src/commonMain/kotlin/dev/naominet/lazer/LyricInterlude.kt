package dev.naominet.lazer

/** Only infer an LRC ending for a long gap; LRC has no explicit vocal end time. */
fun lyricInterludeStart(startMillis: Long, endMillis: Long?, nextMillis: Long): Long? {
    val end = endMillis ?: (startMillis + 8_000L).takeIf { nextMillis - startMillis >= 12_000L }
    return end?.coerceAtLeast(startMillis)?.takeIf { nextMillis - it >= 5_000L }
}
