package dev.naominet.lazer.gateway

import dev.naominet.lazer.gateway.model.SongCommentResponse
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongCommentTest {

    @Test
    fun `song comments address the R_SO thread and page with offset`() {
        val request = neteaseRequest("/comment/music", mapOf("id" to "1372188635", "offset" to "20"))

        assertEquals("/api/v1/resource/comments/R_SO_4_1372188635", request.path)
        assertEquals(NeteaseEncoding.WEAPI, request.encoding)
        assertEquals("1372188635", request.payload["rid"]?.jsonPrimitive?.content)
        assertEquals("20", request.payload["offset"]?.jsonPrimitive?.content)
        assertEquals("0", request.payload["beforeTime"]?.jsonPrimitive?.content)
    }

    @Test
    fun `song comments reject a non-positive song id`() {
        assertTrue(
            kotlin.runCatching { neteaseRequest("/comment/music", mapOf("id" to "0")) }.isFailure,
        )
    }

    @Test
    fun `liking a comment addresses the like and unlike endpoints on the song thread`() {
        val like = neteaseRequest("/comment/like", mapOf("id" to "137", "commentId" to "9646640244"))
        val unlike = neteaseRequest("/comment/unlike", mapOf("id" to "137", "commentId" to "9646640244"))

        assertEquals("/api/v1/comment/like", like.path)
        assertEquals(NeteaseEncoding.WEAPI, like.encoding)
        assertEquals("R_SO_4_137", like.payload["threadId"]?.jsonPrimitive?.content)
        assertEquals("9646640244", like.payload["commentId"]?.jsonPrimitive?.content)
        assertEquals("/api/v1/comment/unlike", unlike.path)
    }

    @Test
    fun `replying builds the thread itself and carries the parent and body`() {
        val request = neteaseRequest(
            "/comment/reply",
            mapOf("id" to "137", "commentId" to "9646640244", "content" to "写点什么"),
        )

        assertEquals("/api/resource/comments/reply", request.path)
        // The reply endpoint is an eapi route, unlike the weapi read and like paths.
        assertEquals(NeteaseEncoding.EAPI, request.encoding)
        assertEquals("R_SO_4_137", request.payload["threadId"]?.jsonPrimitive?.content)
        assertEquals("9646640244", request.payload["commentId"]?.jsonPrimitive?.content)
        assertEquals("写点什么", request.payload["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a caller cannot point a comment write at another resource's thread`() {
        val request = neteaseRequest(
            "/comment/like",
            mapOf("id" to "137", "commentId" to "9", "threadId" to "A_PL_0_1"),
        )

        assertEquals("R_SO_4_137", request.payload["threadId"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the recorded upstream payload decodes into readable comments`() {
        // Trimmed from a live anonymous response. The keys dropped here are the reason the test
        // decodes with the production parser instead of a hand-written one.
        val response = gatewayJson.decodeFromString<SongCommentResponse>(
            """
            {
              "code": 200,
              "total": 285985,
              "more": true,
              "isMusician": false,
              "userId": -1,
              "commentBanner": null,
              "hotComments": [{
                "commentId": 9646640244,
                "content": "蹲",
                "time": 1790073592757,
                "timeStr": null,
                "likedCount": 0,
                "liked": false,
                "owner": false,
                "ipLocation": {"ip": null, "location": "江苏", "userId": 1803371888},
                "beReplied": [],
                "grade": 0,
                "status": 0,
                "user": {
                  "userId": 1803371888,
                  "nickname": "奈琦imo",
                  "avatarUrl": "http://p2.music.126.net/Fh81lGITZlmbJK68oIkhEA==/109951173967830786.jpg",
                  "avatarDetail": null,
                  "authStatus": 0,
                  "expertTags": null,
                  "vipType": 0
                }
              }],
              "comments": [{
                "commentId": 9646640245,
                "content": "好听",
                "time": 1790073592758,
                "likedCount": 12,
                "liked": true,
                "owner": true,
                "ipLocation": null,
                "user": {"userId": 7, "nickname": "Lazer", "avatarUrl": "//p1.music.126.net/a.jpg"}
              }]
            }
            """.trimIndent(),
        )

        assertEquals(285985, response.total)
        assertTrue(response.more)

        val hot = response.hotComments.single()
        assertEquals(9646640244L, hot.commentId)
        assertEquals("蹲", hot.content)
        assertEquals(1790073592757L, hot.time)
        assertFalse(hot.liked)
        assertEquals("江苏", hot.ipLocation?.location)
        assertEquals("奈琦imo", hot.user?.nickname)
        // Upstream really does serve the avatar over http; the UI layer owns the upgrade.
        assertEquals(
            "http://p2.music.126.net/Fh81lGITZlmbJK68oIkhEA==/109951173967830786.jpg",
            hot.user?.avatarUrl,
        )

        val comment = response.comments.single()
        assertTrue(comment.liked)
        assertEquals(12, comment.likedCount)
        assertNull(comment.ipLocation)
        assertEquals("//p1.music.126.net/a.jpg", comment.user?.avatarUrl)
    }
}
