package dev.naominet.lazer

/** A parsed, single-file CUE sheet for formats supported by the desktop local-file path. */
internal data class DesktopCueSheetDocument(
    val fileName: String,
    val fileType: String,
    val title: String?,
    val performer: String?,
    val tracks: List<DesktopCueTrack>,
)

/** Track title and effective performer plus its exact INDEX 01 CUE-frame offset. */
internal data class DesktopCueTrack(
    val number: Int,
    val title: String?,
    val performer: String?,
    val index01CueFrames: Long,
)

/**
 * Parses the intentionally narrow CUE subset used by the desktop WAV/FLAC library.
 *
 * The parser is strict about structure that changes playback. It leaves path resolution and file
 * ownership to the caller, which must validate [DesktopCueSheetDocument.fileName] against the
 * selected library root before using the sheet.
 */
internal fun parseDesktopCueSheet(text: String): DesktopCueSheetDocument? {
    val lines = text.removePrefix("\uFEFF").lineSequence()
    val tracks = mutableListOf<DesktopCueTrack>()
    var fileName: String? = null
    var fileType: String? = null
    var sheetTitle: String? = null
    var sheetPerformer: String? = null
    var sheetTitleSeen = false
    var sheetPerformerSeen = false
    var previousTrackNumber = 0
    var previousIndex01 = -1L
    var currentTrack: CueTrackBuilder? = null

    fun finishTrack(): Boolean {
        val track = currentTrack ?: return true
        val index = track.index01CueFrames ?: return false
        if (index <= previousIndex01) return false
        tracks += DesktopCueTrack(
            number = track.number,
            title = track.title,
            performer = track.performer ?: sheetPerformer,
            index01CueFrames = index,
        )
        previousIndex01 = index
        currentTrack = null
        return true
    }

    for (line in lines) {
        val directiveLine = line.trim()
        if (directiveLine.isEmpty()) continue
        val splitAt = directiveLine.indexOfFirst(Char::isWhitespace)
        val directive = if (splitAt < 0) directiveLine else directiveLine.substring(0, splitAt)
        val argument = if (splitAt < 0) "" else directiveLine.substring(splitAt).trim()

        when (directive.uppercase()) {
            "REM" -> Unit // REM payload is comment metadata, including vendor-specific tags.

            "FILE" -> {
                if (fileName != null || currentTrack != null || tracks.isNotEmpty()) return null
                val (parsedName, remainder) = parseCueToken(argument) ?: return null
                if (parsedName.isBlank() || '\u0000' in parsedName) return null
                val tokens = remainder.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
                if (tokens.size != 1) return null
                val parsedType = tokens.single().uppercase()
                // The current desktop local-file decoder supports WAV and FLAC containers only.
                if (parsedType !in SUPPORTED_DESKTOP_CUE_FILE_TYPES) return null
                fileName = parsedName
                fileType = parsedType
            }

            "TRACK" -> {
                if (fileName == null || !finishTrack()) return null
                val tokens = argument.split(Regex("\\s+")).filter(String::isNotEmpty)
                if (tokens.size != 2 || tokens[1].uppercase() != "AUDIO") return null
                val number = tokens[0].toIntOrNull()?.takeIf { it in 1..99 } ?: return null
                if (number <= previousTrackNumber) return null
                previousTrackNumber = number
                currentTrack = CueTrackBuilder(number)
            }

            "TITLE", "PERFORMER" -> {
                val value = parseCueQuotedValue(argument) ?: return null
                val track = currentTrack
                if (track == null) {
                    if (directive.equals("TITLE", ignoreCase = true)) {
                        if (sheetTitleSeen) return null
                        sheetTitleSeen = true
                        sheetTitle = value
                    } else {
                        if (sheetPerformerSeen) return null
                        sheetPerformerSeen = true
                        sheetPerformer = value
                    }
                } else if (directive.equals("TITLE", ignoreCase = true)) {
                    if (track.titleSeen) return null
                    track.titleSeen = true
                    track.title = value
                } else {
                    if (track.performerSeen) return null
                    track.performerSeen = true
                    track.performer = value
                }
            }

            "INDEX" -> {
                val track = currentTrack ?: return null
                val tokens = argument.split(Regex("\\s+")).filter(String::isNotEmpty)
                // INDEX 00 and later indexes require pregap/program semantics this slice does not
                // implement, so reject them rather than silently changing the disc timeline.
                if (tokens.size != 2 || tokens[0] != "01" || track.index01CueFrames != null) return null
                val index = parseCueTime(tokens[1]) ?: return null
                if (index <= previousIndex01) return null
                track.index01CueFrames = index
            }

            "PREGAP", "POSTGAP", "FLAGS" -> return null

            // These directives carry descriptive/CD-text metadata and do not affect PCM playback.
            "CATALOG", "ISRC", "SONGWRITER", "COMPOSER", "ARRANGER", "MESSAGE",
            "DISC_ID", "GENRE", "DATE", "COMMENT", "CDTEXTFILE" -> Unit

            // Unknown directives are rejected because the parser cannot know whether they alter
            // the program layout or decoding behavior.
            else -> return null
        }
    }

    if (fileName == null || fileType == null || !finishTrack() || tracks.isEmpty()) return null
    return DesktopCueSheetDocument(
        fileName = fileName,
        fileType = fileType,
        title = sheetTitle,
        performer = sheetPerformer,
        tracks = tracks.toList(),
    )
}

private data class CueTrackBuilder(
    val number: Int,
    var title: String? = null,
    var performer: String? = null,
    var titleSeen: Boolean = false,
    var performerSeen: Boolean = false,
    var index01CueFrames: Long? = null,
)

private val SUPPORTED_DESKTOP_CUE_FILE_TYPES = setOf("WAVE", "FLAC")
private val CUE_TIME_PATTERN = Regex("([0-9]{2,}):([0-9]{2}):([0-9]{2})")

/** Parses a quoted CUE token and returns its value plus the untouched remaining text. */
private fun parseCueToken(text: String): Pair<String, String>? {
    val source = text.trimStart()
    if (source.isEmpty()) return null
    if (source.first() != '"') {
        val end = source.indexOfFirst(Char::isWhitespace).let { if (it < 0) source.length else it }
        return source.substring(0, end) to source.substring(end)
    }

    val value = StringBuilder()
    var index = 1
    while (index < source.length) {
        val character = source[index]
        if (character == '"') {
            return value.toString() to source.substring(index + 1)
        }
        if (character == '\\' && index + 1 < source.length && source[index + 1] == '"') {
            value.append('"')
            index += 2
        } else {
            value.append(character)
            index += 1
        }
    }
    return null
}

private fun parseCueQuotedValue(text: String): String? {
    val (value, remainder) = parseCueToken(text) ?: return null
    if (remainder.isNotBlank()) return null
    return value.takeIf(String::isNotBlank)
}

private fun parseCueTime(value: String): Long? {
    val match = CUE_TIME_PATTERN.matchEntire(value) ?: return null
    val minutes = match.groupValues[1].toLongOrNull() ?: return null
    val seconds = match.groupValues[2].toLongOrNull()?.takeIf { it in 0..59 } ?: return null
    val frames = match.groupValues[3].toLongOrNull()?.takeIf { it in 0..74 } ?: return null
    val remainder = seconds * CUE_TICKS_PER_SECOND + frames
    if (minutes > (Long.MAX_VALUE - remainder) / CUE_TICKS_PER_MINUTE) return null
    return minutes * CUE_TICKS_PER_MINUTE + remainder
}

private const val CUE_TICKS_PER_SECOND = 75L
private const val CUE_TICKS_PER_MINUTE = 60L * CUE_TICKS_PER_SECOND
