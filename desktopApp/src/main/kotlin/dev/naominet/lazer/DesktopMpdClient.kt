package dev.naominet.lazer

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val DESKTOP_MPD_PAGE_SIZE = 20

internal data class DesktopMpdEndpoint(val host: String, val port: Int) {
    fun validated(): DesktopMpdEndpoint {
        require(host.isNotBlank()) { "Enter an MPD server address." }
        require(port in 1..65535) { "MPD port must be between 1 and 65535." }
        return copy(host = host.trim())
    }
}

internal data class DesktopMpdStatus(
    val state: String,
    val title: String? = null,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val elapsedSeconds: Double? = null,
    val durationSeconds: Double? = null,
    val volumePercent: Int? = null,
    val playlistVersion: Long? = null,
    val playlistLength: Int? = null,
    val currentSongId: Int? = null,
)

internal data class DesktopMpdTrack(
    val uri: String,
    val title: String? = null,
    val artists: List<String> = emptyList(),
    val album: String? = null,
    val durationSeconds: Double? = null,
    val queueId: Int? = null,
    val queuePosition: Int? = null,
)

internal data class DesktopMpdSearchPage(
    val totalCount: Int,
    val offset: Int,
    val entries: List<DesktopMpdTrack>,
)

internal data class DesktopMpdQueuePage(
    val playlistVersion: Long?,
    val totalCount: Int,
    val offset: Int,
    val entries: List<DesktopMpdTrack>,
)

internal class DesktopMpdProtocolException(message: String) : IOException(message)

/** MPD control client. It controls a server that owns playback and never opens local audio output. */
internal class DesktopMpdClient(
    private val connectTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
    private val readTimeoutMillis: Int = DEFAULT_TIMEOUT_MILLIS,
) {
    suspend fun readStatus(endpoint: DesktopMpdEndpoint, password: String? = null): DesktopMpdStatus = withContext(Dispatchers.IO) {
        val checked = endpoint.validated()
        val status = request(checked, "status", password)
        val song = request(checked, "currentsong", password)
        DesktopMpdStatus(
            state = status.lastValue("state") ?: "unknown",
            title = song.lastValue("Title")?.let(::unescapeMpdValue),
            artists = song.values("Artist").map(::unescapeMpdValue),
            album = song.lastValue("Album")?.let(::unescapeMpdValue),
            elapsedSeconds = parseMpdSeconds(status.lastValue("elapsed"))
                ?: parseMpdLegacyTime(status.lastValue("time"), 0),
            durationSeconds = parseMpdSeconds(status.lastValue("duration"))
                ?: parseMpdLegacyTime(status.lastValue("time"), 1),
            volumePercent = status.lastValue("volume")?.toIntOrNull()?.takeIf { it in 0..100 },
            playlistVersion = status.lastValue("playlist")?.toLongOrNull()?.takeIf { it >= 0L },
            playlistLength = status.lastValue("playlistlength")?.toIntOrNull()?.coerceAtLeast(0),
            currentSongId = status.lastValue("songid")?.toIntOrNull()?.takeIf { it >= 0 },
        )
    }

    suspend fun search(
        endpoint: DesktopMpdEndpoint,
        query: String,
        offset: Int = 0,
        limit: Int = DESKTOP_MPD_PAGE_SIZE,
        password: String? = null,
    ): DesktopMpdSearchPage = withContext(Dispatchers.IO) {
        require(query.isNotBlank()) { "Enter a search term." }
        require(offset >= 0 && limit in 1..MAX_PAGE_SIZE) { "MPD search page is out of range." }
        val normalizedQuery = query.trim()
        require(normalizedQuery.length <= MAX_QUERY_LENGTH && normalizedQuery.none(Char::isISOControl)) {
            "MPD search text is too long or contains control characters."
        }
        val expression = searchExpression(normalizedQuery)
        val checked = endpoint.validated()
        val countResponse = request(checked, "searchcount ${quoteArgument(expression)}", password)
        val totalCount = countResponse.lastValue("songs")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val effectiveOffset = if (totalCount == 0) 0 else {
            (offset / limit).coerceAtMost((totalCount - 1) / limit) * limit
        }
        val response = request(
            checked,
            "search ${quoteArgument(expression)} window $effectiveOffset:${effectiveOffset + limit}",
            password,
        )
        DesktopMpdSearchPage(
            totalCount = totalCount,
            offset = effectiveOffset,
            entries = parseTracks(response),
        )
    }

    suspend fun readQueue(
        endpoint: DesktopMpdEndpoint,
        requestedOffset: Int = 0,
        pageSize: Int = DESKTOP_MPD_PAGE_SIZE,
        password: String? = null,
    ): DesktopMpdQueuePage = withContext(Dispatchers.IO) {
        require(requestedOffset >= 0 && pageSize in 1..MAX_PAGE_SIZE) { "MPD queue page is out of range." }
        val checked = endpoint.validated()
        repeat(2) {
            val before = request(checked, "status", password)
            val versionBefore = before.lastValue("playlist")?.toLongOrNull()?.takeIf { it >= 0L }
            val totalCount = before.lastValue("playlistlength")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val offset = if (totalCount == 0) 0 else {
                (requestedOffset / pageSize).coerceAtMost((totalCount - 1) / pageSize) * pageSize
            }
            val end = (offset + pageSize).coerceAtMost(totalCount)
            val response = if (end > offset) {
                request(checked, "playlistinfo $offset:$end", password)
            } else {
                emptyList()
            }
            val after = request(checked, "status", password)
            val versionAfter = after.lastValue("playlist")?.toLongOrNull()?.takeIf { it >= 0L }
            val page = DesktopMpdQueuePage(versionAfter, totalCount, offset, parseTracks(response, inQueue = true))
            if (versionBefore != null && versionBefore == versionAfter) return@withContext page
            if (versionBefore == null && versionAfter == null && totalCount == 0) return@withContext page
        }
        throw IOException("MPD queue changed while it was being read. Refresh the queue and try again.")
    }

    suspend fun addToQueue(endpoint: DesktopMpdEndpoint, uri: String, password: String? = null): Int = withContext(Dispatchers.IO) {
        require(uri.isNotBlank() && uri.none(Char::isISOControl)) { "MPD track URI is invalid." }
        request(endpoint.validated(), "addid ${quoteArgument(uri)}", password)
            .lastValue("Id")?.toIntOrNull()?.takeIf { it >= 0 }
            ?: throw DesktopMpdProtocolException("MPD added the track but did not return its queue id.")
    }

    suspend fun playQueueItem(endpoint: DesktopMpdEndpoint, queueId: Int, password: String? = null) {
        require(queueId >= 0) { "MPD queue id is invalid." }
        command(endpoint, "playid", queueId.toString(), password)
    }

    suspend fun deleteQueueItem(endpoint: DesktopMpdEndpoint, queueId: Int, password: String? = null) {
        require(queueId >= 0) { "MPD queue id is invalid." }
        command(endpoint, "deleteid", queueId.toString(), password)
    }

    suspend fun play(endpoint: DesktopMpdEndpoint, password: String? = null) = command(endpoint, "play", password = password)
    suspend fun pause(endpoint: DesktopMpdEndpoint, password: String? = null) = command(endpoint, "pause", "1", password)
    suspend fun stop(endpoint: DesktopMpdEndpoint, password: String? = null) = command(endpoint, "stop", password = password)

    suspend fun seek(endpoint: DesktopMpdEndpoint, seconds: Double, password: String? = null) {
        require(seconds.isFinite() && seconds >= 0.0) { "Seek position must be a non-negative time." }
        command(endpoint, "seekcur", String.format(Locale.ROOT, "%.3f", seconds), password)
    }

    suspend fun setVolume(endpoint: DesktopMpdEndpoint, volumePercent: Int, password: String? = null) {
        require(volumePercent in 0..100) { "MPD volume must be between 0 and 100." }
        command(endpoint, "setvol", volumePercent.toString(), password)
    }

    private suspend fun command(
        endpoint: DesktopMpdEndpoint,
        name: String,
        argument: String? = null,
        password: String? = null,
    ) {
        withContext(Dispatchers.IO) {
            val checked = endpoint.validated()
            val line = if (argument == null) name else "$name $argument"
            request(checked, line, password)
        }
    }

    private fun request(
        endpoint: DesktopMpdEndpoint,
        commandLine: String,
        password: String? = null,
    ): List<Pair<String, String>> {
        val values = ArrayList<Pair<String, String>>()
        Socket().use { socket ->
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), connectTimeoutMillis)
            socket.soTimeout = readTimeoutMillis
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
            val greeting = readLineBounded(reader)
                ?: throw IOException("MPD closed the connection before its greeting.")
            if (!greeting.startsWith("OK MPD ")) {
                throw DesktopMpdProtocolException("The server did not send an MPD greeting.")
            }
            if (!password.isNullOrEmpty()) {
                writer.write("password ${quoteArgument(password)}")
                writer.newLine()
                writer.flush()
                readResponse(reader)
            }
            writer.write(commandLine)
            writer.newLine()
            writer.flush()
            values += readResponse(reader)
        }
        return values
    }

    private fun readResponse(reader: BufferedReader): List<Pair<String, String>> {
        val values = ArrayList<Pair<String, String>>()
        var lineCount = 0
        var responseCharacters = 0
        while (true) {
            val line = readLineBounded(reader)
                ?: throw IOException("MPD closed the connection before completing its response.")
            lineCount++
            responseCharacters += line.length
            if (lineCount > MAX_RESPONSE_LINES || responseCharacters > MAX_RESPONSE_CHARACTERS) {
                throw DesktopMpdProtocolException("MPD response exceeded the supported size.")
            }
            if (line == "OK") return values
            if (line.startsWith("ACK ")) throw DesktopMpdProtocolException(parseMpdAck(line))
            val separator = line.indexOf(':')
            if (separator > 0) values += line.substring(0, separator) to line.substring(separator + 1).trimStart()
        }
    }

    private fun readLineBounded(reader: BufferedReader): String? {
        val line = StringBuilder()
        while (true) {
            val next = reader.read()
            if (next == -1) return if (line.isEmpty()) null else line.toString()
            if (next == '\n'.code) {
                if (line.isNotEmpty() && line.last() == '\r') line.setLength(line.length - 1)
                return line.toString()
            }
            if (line.length >= MAX_RESPONSE_LINE_LENGTH) {
                throw DesktopMpdProtocolException("MPD response line exceeded the supported size.")
            }
            line.append(next.toChar())
        }
    }

    private fun parseMpdAck(line: String): String {
        val message = line.substringAfter('}', missingDelimiterValue = line).trim()
        return message.ifBlank { "MPD rejected the command." }
    }

    private fun quoteArgument(value: String): String {
        require(value.none(Char::isISOControl)) { "MPD command text contains unsupported control characters." }
        return "\"${value.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }

    private fun searchExpression(query: String): String {
        val filterValue = query.replace("\\", "\\\\").replace("'", "\\'")
        return "(any contains '$filterValue')"
    }

    private fun parseTracks(values: List<Pair<String, String>>, inQueue: Boolean = false): List<DesktopMpdTrack> {
        val records = mutableListOf<MutableList<Pair<String, String>>>()
        var current: MutableList<Pair<String, String>>? = null
        for (value in values) {
            if (value.first == "file") {
                current = mutableListOf(value)
                records += current
            } else {
                current?.add(value)
            }
        }
        return records.mapNotNull { record ->
            val uri = record.firstOrNull { it.first == "file" }?.second?.let(::unescapeMpdValue)
                ?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            fun tag(name: String): String? = record.lastOrNull { it.first.equals(name, ignoreCase = true) }
                ?.second?.let(::unescapeMpdValue)
            DesktopMpdTrack(
                uri = uri,
                title = tag("Title"),
                artists = record.filter { it.first.equals("Artist", ignoreCase = true) }
                    .map { unescapeMpdValue(it.second) },
                album = tag("Album"),
                durationSeconds = tag("duration")?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 },
                queueId = if (inQueue) tag("Id")?.toIntOrNull()?.takeIf { it >= 0 } else null,
                queuePosition = if (inQueue) tag("Pos")?.toIntOrNull()?.takeIf { it >= 0 } else null,
            )
        }
    }

    private fun List<Pair<String, String>>.lastValue(key: String): String? =
        lastOrNull { it.first == key }?.second

    private fun List<Pair<String, String>>.values(key: String): List<String> =
        filter { it.first == key }.map { it.second }

    private fun unescapeMpdValue(value: String): String = buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '\\' && index + 1 < value.length) {
                val escaped = value[index + 1]
                append(when (escaped) {
                    'n' -> '\n'
                    't' -> '\t'
                    else -> escaped
                })
                index += 2
            } else {
                append(character)
                index++
            }
        }
    }

    private fun parseMpdSeconds(value: String?): Double? = value?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0.0 }

    private fun parseMpdLegacyTime(value: String?, component: Int): Double? =
        value?.split(':')?.getOrNull(component)?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 }

    private companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 3_000
        const val MAX_RESPONSE_LINES = 4_096
        const val MAX_RESPONSE_LINE_LENGTH = 16_384
        const val MAX_RESPONSE_CHARACTERS = 256 * 1024
        const val MAX_PAGE_SIZE = 100
        const val MAX_QUERY_LENGTH = 256
    }
}
