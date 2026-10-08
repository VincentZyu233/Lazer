package dev.naominet.lazer

import kotlin.test.Test
import kotlin.test.assertEquals

class LazerArtworkUrlTest {
    @Test
    fun `local embedded artwork uri remains usable for the full player`() {
        val uri = "file:///data/user/0/dev.naominet.lazer/files/local-artwork/cover.png"

        assertEquals(uri, enlargedArtworkUrl(uri))
    }

    @Test
    fun `remote artwork urls keep their existing normalization`() {
        assertEquals(
            "https://music.126.net/image/cover.jpg?param=1024y1024",
            enlargedArtworkUrl("http://music.126.net/image/cover.jpg", sizePx = 1024),
        )
    }
}
