package dev.naominet.lazer.gateway

import dev.naominet.lazer.gateway.model.ListenTogetherInvite
import dev.naominet.lazer.gateway.model.listenTogetherCreatedRoomId
import dev.naominet.lazer.gateway.model.listenTogetherPlaybackState
import dev.naominet.lazer.gateway.model.listenTogetherRoomStatus
import dev.naominet.lazer.gateway.model.parseListenTogetherInvite
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ListenTogetherTest {
    @Test
    fun `official and twice encoded invitations expose room identifiers`() {
        val invite = ListenTogetherInvite(roomId = "123456", inviterId = 7788L)
        assertEquals(invite, parseListenTogetherInvite(invite.shareUrl(songId = 42L)))
        assertEquals(
            invite,
            parseListenTogetherInvite(
                "orpheus%253A%252F%252FlistenTogether%253FroomId%253D123456%2526inviterId%253D7788",
            ),
        )
        assertEquals(invite, parseListenTogetherInvite("123456 7788"))
        assertNull(parseListenTogetherInvite("https://music.163.com/song?id=42"))
    }

    @Test
    fun `current opaque room ids parse from real share links and create responses`() {
        val roomId = "0f826405fdb26d20100741f54e6d6863_1789994780"
        val invite = parseListenTogetherInvite(
            "https://st.music.163.com/listen-together/share/" +
                "?roomId=$roomId&songId=3402236035&inviterId=544390660",
        )

        assertEquals(ListenTogetherInvite(roomId, 544390660L), invite)
        assertEquals(
            ListenTogetherInvite(roomId, 544390660L),
            parseListenTogetherInvite(
                "https://st.music.163.com/listen-together/share/?roomId=" +
                    "0f826405fdb26d20100741f54e6d6863\\_1789994780\\&songId=3402236035\\&inviterId=544390660",
            ),
        )
        assertEquals(
            roomId,
            listenTogetherCreatedRoomId(json("""{"code":200,"data":{"roomInfo":{"roomId":"$roomId"}}}""")),
        )
    }

    @Test
    fun `room status keeps participants and detects a closed room`() {
        val active = json(
            """{
              "code":200,
              "data":{"inRoom":true,"roomInfo":{"roomId":"123","roomUsers":[
                {"userId":"7","nickname":"Lazer","avatarUrl":"https://example/avatar.jpg"}
              ]}}
            }""",
        )
        val status = assertNotNull(listenTogetherRoomStatus(active))
        assertEquals("123", status.roomId)
        assertEquals("Lazer", status.participants.single().nickname)

        val closed = assertNotNull(listenTogetherRoomStatus(json("""{"code":200,"data":{"inRoom":false}}""")))
        assertFalse(closed.inRoom)
    }

    @Test
    fun `playlist state accepts object and scalar song identifiers`() {
        val response = json(
            """{
              "code":200,
              "data":{
                "playCommand":{
                  "commandType":"GOTO","progress":"3210","playStatus":"PLAY",
                  "formerSongId":"11","targetSongId":"22","clientSeq":"9"
                },
                "playlist":{
                  "playMode":"ORDER_LOOP",
                  "version":[{"userId":"7","version":"3"}],
                  "displayList":{"result":[{"songId":"11"},"22"]}
                }
              }
            }""",
        )
        val state = assertNotNull(listenTogetherPlaybackState(response))
        assertEquals(listOf(11L, 22L), state.trackIds)
        assertEquals(22L, state.targetSongId)
        assertEquals(3210L, state.progressMillis)
        assertEquals(9L, state.clientSequence)
        assertEquals(3L, state.versions.single().version)
    }

    @Test
    fun `command routes preserve nested serialized payloads`() {
        val roomId = "0f826405fdb26d20100741f54e6d6863_1789994780"
        val command = """{"commandType":"GOTO","targetSongId":22,"clientSeq":9}"""
        val request = neteaseRequest(
            "/listentogether/play/command",
            mapOf("roomId" to roomId, "commandInfo" to command),
        )
        assertEquals("/api/listen/together/play/command/report", request.path)
        assertEquals(command, request.payload["commandInfo"]?.jsonPrimitive?.content)

        val playlist = """{"commandType":"REPLACE","displayList":["11","22"]}"""
        val playlistRequest = neteaseRequest(
            "/listentogether/sync/list/command",
            mapOf("roomId" to roomId, "playlistParam" to playlist),
        )
        assertEquals(playlist, playlistRequest.payload["playlistParam"]?.jsonPrimitive?.content)
    }

    @Test
    fun `listen together routes declare the mobile client that the room version gate expects`() {
        val created = neteaseRequest("/listentogether/room/create", emptyMap())
        assertEquals(NeteaseClient.Mobile, created.client)
        assertEquals("android", NeteaseClient.Mobile.os)
        assertEquals("9.5.95", NeteaseClient.Mobile.appver)

        val checked = neteaseRequest("/listentogether/room/check", mapOf("roomId" to "room_1"))
        assertEquals(NeteaseClient.Mobile, checked.client)

        val status = neteaseRequest("/listentogether/status", emptyMap())
        assertEquals(NeteaseClient.Mobile, status.client)

        val detail = neteaseRequest("/song/detail", mapOf("ids" to "1"))
        assertEquals(NeteaseClient.Desktop, detail.client)
    }

    @Test
    fun `multi-person room create seeds the room the way the official client does`() {
        val request = neteaseRequest(
            "/listentogether/multi/room/create",
            mapOf("id" to "42", "playedTime" to "1500", "nextSongIds" to "7,8"),
        )
        val payload = request.payload

        assertEquals("/api/listen/together/multi/room/create", request.path)
        assertEquals(NeteaseClient.Mobile, request.client)
        assertEquals("42", payload["songId"]?.jsonPrimitive?.content)
        assertEquals("CREATE", payload["from"]?.jsonPrimitive?.content)
        assertEquals("1500", payload["playedTime"]?.jsonPrimitive?.content)
        assertEquals("[]", payload["groupIds"]?.jsonPrimitive?.content)
        assertEquals("[]", payload["inviteUids"]?.jsonPrimitive?.content)
        assertEquals("[7,8]", payload["nextSongIds"]?.jsonPrimitive?.content)
        assertEquals("", payload["checkToken"]?.jsonPrimitive?.content)
    }

    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
}
