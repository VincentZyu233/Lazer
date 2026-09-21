package dev.naominet.lazer.gateway

import kotlinx.serialization.json.*

internal enum class NeteaseEncoding { EAPI, WEAPI }

internal data class NeteaseRequest(
    val path: String,
    val payload: JsonObject,
    val encoding: NeteaseEncoding = NeteaseEncoding.EAPI,
)

/** The finite set of wrapper aliases supported in-process. Never accepts an upstream URL. */
internal fun neteaseRequest(alias: String, parameters: Map<String, String>): NeteaseRequest {
    fun value(key: String, fallback: String = "") = parameters[key] ?: fallback
    fun id(key: String = "id"): String = value(key).also {
        require(it.toLongOrNull()?.let { number -> number > 0 } == true) { "$key must be a positive ID." }
    }
    fun request(path: String, web: Boolean = false, body: JsonObjectBuilder.() -> Unit = {}) =
        NeteaseRequest(path, buildJsonObject(body), if (web) NeteaseEncoding.WEAPI else NeteaseEncoding.EAPI)
    fun JsonObjectBuilder.page(defaultLimit: Int = 30) {
        put("limit", value("limit", defaultLimit.toString()))
        put("offset", value("offset", "0"))
    }
    return when (alias) {
        "/login/cellphone" -> request("/api/w/login/cellphone", web = true) {
            put("type", "1"); put("https", "true"); put("phone", value("phone"))
            put("countrycode", value("countrycode", "86")); put("remember", "true")
            put("secureCaptcha", value("sca"))
            if (value("captcha").isNotEmpty()) put("captcha", value("captcha"))
            else put("password", parameters["md5_password"] ?: NeteaseCrypto.md5(value("password")))
        }
        "/login" -> request("/api/w/login") {
            put("type", "0"); put("https", "true"); put("username", value("email"))
            put("password", parameters["md5_password"] ?: NeteaseCrypto.md5(value("password")))
            put("rememberLogin", "true")
        }
        "/login/qr/key" -> request("/api/login/qrcode/unikey") { put("type", 3) }
        "/login/qr/check" -> request("/api/login/qrcode/client/login") {
            put("key", value("key")); put("type", 3)
        }
        "/login/status" -> request("/api/w/nuser/account/get", web = true)
        "/login/refresh" -> request("/api/login/token/refresh")
        "/logout" -> request("/api/logout")
        "/register/anonimous" -> throw UnsupportedOperationException(
            "Anonymous registration requires an unsupported upstream handshake. Use QR or cookie sign-in.",
        )
        "/captcha/sent/v1" -> request("/api/middle/captcha/sent/v1") {
            put("ctcode", value("ctcode", "86")); put("secrete", "music_middleuser_pclogin")
            put("cellphone", value("phone")); put("scene", "0")
        }
        "/search", "/cloudsearch" -> {
            val voice = alias == "/search" && value("type") == "2000"
            request(when {
                voice -> "/api/search/voice/get"
                alias == "/cloudsearch" -> "/api/cloudsearch/pc"
                else -> "/api/search/get"
            }) {
                put(if (voice) "keyword" else "s", value("keywords"))
                if (voice) put("scene", "normal") else put("type", value("type", "1"))
                page()
                if (alias == "/cloudsearch") put("total", true)
            }
        }
        "/search/default" -> request("/api/search/defaultkeyword/get")
        "/search/hot/detail" -> request("/api/hotsearchlist/get", web = true)
        "/search/suggest" -> request(
            if (value("type") == "mobile") "/api/search/suggest/keyword" else "/api/search/suggest/web",
            web = true,
        ) { put("s", value("keywords")) }
        "/song/detail" -> request("/api/v3/song/detail", web = true) {
            put("c", songIdPayload(value("ids")))
        }
        "/song/url/v1" -> request("/api/song/enhance/player/url", web = true) {
            require(value("unblock", "false") == "false") { "External music matching is not supported." }
            val bitrate = when (value("level", "exhigh")) {
                "standard" -> 128000
                "higher" -> 192000
                "exhigh" -> 320000
                else -> throw UnsupportedOperationException("This audio quality requires an unsupported upstream handshake.")
            }
            put("ids", songIds(value("id")).toString()); put("br", bitrate)
        }
        "/check/music" -> request("/api/song/enhance/player/url", web = true) {
            put("ids", "[${id()}]"); put("br", value("br", "999000").toInt())
        }
        "/lyric" -> request("/api/song/lyric") {
            put("id", id()); put("tv", -1); put("lv", -1); put("rv", -1); put("kv", -1); put("_nmclfl", 1)
        }
        "/lyric/new" -> request("/api/song/lyric/v1") {
            put("id", id()); put("cp", false)
            listOf("tv", "lv", "rv", "kv", "yv", "ytv", "yrv").forEach { put(it, 0) }
        }
        "/playlist/detail" -> request("/api/v6/playlist/detail") {
            put("id", id()); put("n", 100000); put("s", value("s", "8"))
        }
        "/user/playlist" -> request("/api/user/playlist", web = true) {
            put("uid", id("uid")); page(); put("includeVideo", true)
        }
        "/top/playlist" -> request("/api/playlist/list", web = true) {
            put("cat", value("cat", "全部")); put("order", value("order", "hot")); page(50); put("total", true)
        }
        "/user/detail" -> request("/api/v1/user/detail/${id("uid")}", web = true)
        "/album" -> request("/api/v1/album/${id()}", web = true)
        "/artist/detail" -> request("/api/artist/head/info/get") { put("id", id()) }
        "/artist/top/song" -> request("/api/artist/top/song", web = true) { put("id", id()) }
        "/artist/album" -> request("/api/artist/albums/${id()}", web = true) { page(); put("total", true) }
        "/banner" -> request("/api/v2/banner/get") {
            put("clientType", when (value("type")) { "1" -> "android"; "2" -> "iphone"; "3" -> "ipad"; else -> "pc" })
        }
        "/recommend/songs" -> request("/api/v3/discovery/recommend/songs", web = true) {
            parameters["afresh"]?.let { put("afresh", it) }
        }
        "/recommend/resource" -> request("/api/v1/discovery/recommend/resource", web = true)
        "/personal_fm" -> request("/api/v1/radio/get", web = true)
        "/likelist" -> request("/api/song/like/get") { put("uid", id("uid")) }
        "/song/like" -> request("/api/song/like") {
            put("trackId", id()); put("userid", id("uid")); put("like", value("like") != "false")
        }
        "/listentogether/room/create" -> request("/api/listen/together/room/create") {
            put("refer", "songplay_more")
        }
        "/listentogether/accept" -> request("/api/listen/together/play/invitation/accept") {
            put("refer", "inbox_invite"); put("roomId", value("roomId")); put("inviterId", value("inviterId"))
        }
        "/listentogether/room/check" -> request("/api/listen/together/room/check") {
            put("roomId", value("roomId"))
        }
        "/listentogether/status" -> request("/api/listen/together/status/get", web = true)
        "/listentogether/end" -> request("/api/listen/together/end/v2") {
            put("roomId", value("roomId"))
        }
        "/listentogether/heartbeat" -> request("/api/listen/together/heartbeat") {
            put("roomId", value("roomId")); put("songId", value("songId"))
            put("playStatus", value("playStatus")); put("progress", value("progress"))
        }
        "/listentogether/play/command" -> request("/api/listen/together/play/command/report") {
            put("roomId", value("roomId"))
            put("commandInfo", buildJsonObject {
                put("commandType", value("commandType")); put("progress", value("progress", "0"))
                put("playStatus", value("playStatus")); put("formerSongId", value("formerSongId", "-1"))
                put("targetSongId", value("targetSongId")); put("clientSeq", value("clientSeq"))
            }.toString())
        }
        "/listentogether/sync/list/command" -> request("/api/listen/together/sync/list/command/report") {
            put("roomId", value("roomId"))
            put("playlistParam", buildJsonObject {
                put("commandType", value("commandType"))
                put("version", buildJsonArray { add(buildJsonObject { put("userId", value("userId")); put("version", value("version")) }) })
                put("anchorSongId", ""); put("anchorPosition", -1)
                put("randomList", buildJsonArray { value("randomList").split(',').filter(String::isNotBlank).forEach(::add) })
                put("displayList", buildJsonArray { value("displayList").split(',').filter(String::isNotBlank).forEach(::add) })
            }.toString())
        }
        "/listentogether/sync/playlist/get" -> request("/api/listen/together/sync/playlist/get") {
            put("roomId", value("roomId"))
        }
        "/like" -> request("/api/radio/like", web = true) {
            put("alg", "itembased"); put("trackId", id()); put("like", value("like") != "false"); put("time", "3")
        }
        else -> throw IllegalArgumentException("Unsupported built-in Gateway route.")
    }
}

internal fun songIds(value: String): JsonArray = JsonArray(value.split(',').map {
    val id = it.trim().toLongOrNull()
    require(id != null && id > 0) { "Song IDs must be positive integers." }
    JsonPrimitive(id)
})

internal fun songIdPayload(value: String): String = JsonArray(songIds(value).map {
    buildJsonObject { put("id", it) }
}).toString()
