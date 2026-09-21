package dev.naominet.lazer.gateway.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** The two identifiers carried by an official NetEase listen-together invitation. */
data class ListenTogetherInvite(
    val roomId: String,
    val inviterId: Long,
) {
    fun shareUrl(songId: Long = 0L): String =
        "https://st.music.163.com/listen-together/share/" +
            "?songId=${songId.coerceAtLeast(0L)}&roomId=$roomId&inviterId=$inviterId"
}

/** A two-person room pairs one listener; a multi-person room admits several. */
enum class ListenTogetherRoomKind {
    Duo,
    Multi,
}

/** The official share page wants a song ID even while a freshly created room has no queue yet. */
const val LISTEN_TOGETHER_SHARE_FALLBACK_SONG_ID = 1_372_188_635L

data class ListenTogetherParticipant(
    val userId: Long,
    val nickname: String,
    val avatarUrl: String? = null,
)

data class ListenTogetherRoomStatus(
    val inRoom: Boolean,
    val roomId: String? = null,
    val participants: List<ListenTogetherParticipant> = emptyList(),
)

data class ListenTogetherPlaylistVersion(
    val userId: Long,
    val version: Long,
)

data class ListenTogetherPlaybackState(
    val commandType: String,
    val progressMillis: Long,
    val playStatus: String,
    val formerSongId: Long,
    val targetSongId: Long,
    val clientSequence: Long,
    val trackIds: List<Long>,
    val versions: List<ListenTogetherPlaylistVersion>,
    val playMode: String,
)

/**
 * Parses official share URLs, deep links copied from messages, and their twice-percent-encoded
 * form. NetEase embeds the latter in private-message cards, so decoding only once is insufficient.
 */
fun parseListenTogetherInvite(raw: String): ListenTogetherInvite? {
    var candidate = raw.trim().replace("\\&", "&").replace("\\_", "_")
    repeat(3) { candidate = decodePercentAscii(candidate) }

    fun parameter(name: String): String? = Regex(
        "(?i)(?:^|[?&#])${Regex.escape(name)}=([^&#\\s]+)",
    ).find(candidate)?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)

    val roomId = parameter("roomId")?.takeIf(::isValidListenTogetherRoomId)
    val inviterId = parameter("inviterId")?.toLongOrNull()?.takeIf { it > 0L }
    if (roomId != null && inviterId != null) return ListenTogetherInvite(roomId, inviterId)

    // A compact "roomId inviterId" pair is useful when a share URL cannot be pasted intact.
    val compact = Regex("^\\s*([A-Za-z0-9_-]+)[,\\s:/]+([0-9]+)\\s*$").matchEntire(candidate)
        ?: return null
    val compactRoomId = compact.groupValues[1].takeIf(::isValidListenTogetherRoomId) ?: return null
    val compactInviterId = compact.groupValues[2].toLongOrNull()?.takeIf { it > 0L } ?: return null
    return ListenTogetherInvite(compactRoomId, compactInviterId)
}

fun listenTogetherCreatedRoomId(response: JsonObject): String? = response
    .objectAt("data")
    ?.objectAt("roomInfo")
    ?.stringAt("roomId")
    ?.takeIf(::isValidListenTogetherRoomId)

/** Throws when a room request was answered with a failure code or a rejected payload. */
fun requireListenTogetherSuccess(response: JsonObject) {
    check(response["code"]?.jsonPrimitive?.intOrNull == 200) { "listen together request failed" }
    val data = response.objectAt("data")
    val rejected = listOf("result", "success").any { key ->
        data?.get(key)?.jsonPrimitive?.content?.lowercase() == "false"
    }
    check(!rejected) { "listen together request was rejected" }
}

/** The service retires a room with 488, which every participant sees on its next poll. */
fun isListenTogetherClosed(response: JsonObject): Boolean =
    response["code"]?.jsonPrimitive?.intOrNull == 488

fun incrementListenTogetherVersion(
    versions: List<ListenTogetherPlaylistVersion>,
    userId: Long,
): List<ListenTogetherPlaylistVersion> {
    var found = false
    val updated = versions.map { item ->
        if (item.userId == userId) {
            found = true
            item.copy(version = item.version + 1L)
        } else {
            item
        }
    }
    return if (found) updated else updated + ListenTogetherPlaylistVersion(userId, 1L)
}

fun mergeListenTogetherVersions(
    local: List<ListenTogetherPlaylistVersion>,
    remote: List<ListenTogetherPlaylistVersion>,
): List<ListenTogetherPlaylistVersion> = (local + remote)
    .groupBy(ListenTogetherPlaylistVersion::userId)
    .map { (userId, items) ->
        ListenTogetherPlaylistVersion(userId, items.maxOf(ListenTogetherPlaylistVersion::version))
    }

fun listenTogetherRoomStatus(response: JsonObject): ListenTogetherRoomStatus? {
    val data = response.objectAt("data") ?: return null
    val roomInfo = data.objectAt("roomInfo")
    val participants = roomInfo?.arrayAt("roomUsers").orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val userId = item.longAt("userId")?.takeIf { it > 0L } ?: return@mapNotNull null
        ListenTogetherParticipant(
            userId = userId,
            nickname = item.stringAt("nickname").orEmpty(),
            avatarUrl = item.stringAt("avatarUrl")?.takeIf(String::isNotBlank),
        )
    }
    return ListenTogetherRoomStatus(
        inRoom = data.booleanAt("inRoom") ?: roomInfo?.stringAt("roomId")?.let(::isValidListenTogetherRoomId) ?: false,
        roomId = roomInfo?.stringAt("roomId")?.takeIf(::isValidListenTogetherRoomId),
        participants = participants,
    )
}

fun listenTogetherPlaybackState(response: JsonObject): ListenTogetherPlaybackState? {
    val data = response.objectAt("data") ?: return null
    val command = data.objectAt("playCommand") ?: return null
    val playlist = data.objectAt("playlist") ?: return null
    val displayItems = playlist.objectAt("displayList")?.arrayAt("result")
        ?: playlist.arrayAt("displayList")
        ?: JsonArray(emptyList())
    val trackIds = displayItems.mapNotNull { item ->
        when (item) {
            is JsonObject -> item.longAt("songId") ?: item.longAt("id")
            else -> item.primitiveLongOrNull()
        }?.takeIf { it > 0L }
    }.distinct()
    val versions = playlist.arrayAt("version").orEmpty().mapNotNull { element ->
        val item = element as? JsonObject ?: return@mapNotNull null
        val userId = item.longAt("userId")?.takeIf { it > 0L } ?: return@mapNotNull null
        ListenTogetherPlaylistVersion(userId, item.longAt("version") ?: 0L)
    }
    return ListenTogetherPlaybackState(
        commandType = command.stringAt("commandType").orEmpty(),
        progressMillis = command.longAt("progress")?.coerceAtLeast(0L) ?: 0L,
        playStatus = command.stringAt("playStatus").orEmpty(),
        formerSongId = command.longAt("formerSongId") ?: -1L,
        targetSongId = (command.longAt("targetSongId") ?: command.longAt("formerSongId") ?: -1L),
        clientSequence = command.longAt("clientSeq")?.coerceAtLeast(0L) ?: 0L,
        trackIds = trackIds,
        versions = versions,
        playMode = playlist.stringAt("playMode")?.takeIf(String::isNotBlank) ?: "ORDER_LOOP",
    )
}

private fun JsonObject.objectAt(name: String): JsonObject? = this[name] as? JsonObject
private fun JsonObject.arrayAt(name: String): JsonArray? = this[name] as? JsonArray
private fun JsonObject.stringAt(name: String): String? = this[name]?.jsonPrimitive?.content
private fun JsonObject.longAt(name: String): Long? = this[name]?.primitiveLongOrNull()
private fun JsonObject.booleanAt(name: String): Boolean? = this[name]?.jsonPrimitive?.content?.let {
    when (it.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }
}
private fun JsonElement.primitiveLongOrNull(): Long? = runCatching { jsonPrimitive.longOrNull }.getOrNull()

private fun decodePercentAscii(value: String): String = buildString(value.length) {
    var index = 0
    while (index < value.length) {
        if (value[index] == '%' && index + 2 < value.length) {
            val decoded = value.substring(index + 1, index + 3).toIntOrNull(16)
            if (decoded != null) {
                append(decoded.toChar())
                index += 3
                continue
            }
        }
        append(if (value[index] == '+') ' ' else value[index])
        index += 1
    }
}

private fun isValidListenTogetherRoomId(value: String): Boolean =
    value.length in 1..256 && value.all { it.isLetterOrDigit() || it == '_' || it == '-' }
