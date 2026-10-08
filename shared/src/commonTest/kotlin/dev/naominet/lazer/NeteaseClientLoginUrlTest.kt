package dev.naominet.lazer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NeteaseClientLoginUrlTest {
    @Test
    fun `official client login url is normalized without losing its chain`() {
        assertEquals(
            "https://music.163.com/st/platform/scanlogin?codekey=test-key&chainId=test-chain",
            parseNeteaseClientLoginUrl(
                "https://music.163.com/login?codekey=test-key&chainId=test-chain",
            ),
        )
        assertNull(parseNeteaseClientLoginUrl("https://example.com/login?codekey=test-key"))
        assertNull(parseNeteaseClientLoginUrl("https://music.163.com/song?id=1"))
    }
}
