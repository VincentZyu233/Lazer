package dev.naominet.lazer.gateway

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GatewayProviderSettingsTest {
    @Test
    fun `session cookie normalization keeps protocol-required session fields`() {
        val cookie = "MUSIC_U=session-token; __csrf=csrf-token; NMTID=00Aabcdefg"
        val normalized = normalizeGatewaySessionCookie(cookie)

        assertEquals(
            "MUSIC_U=session-token; __csrf=csrf-token; NMTID=00Aabcdefg",
            normalized,
        )
    }

    @Test
    fun `session cookie normalization discards attributes and blank input`() {
        assertEquals(
            "MUSIC_U=token",
            normalizeGatewaySessionCookie("MUSIC_U=token; Path=/; HttpOnly; Max-Age=100"),
        )
        assertEquals(null, normalizeGatewaySessionCookie(""))
        assertEquals(null, normalizeGatewaySessionCookie("   "))
    }
}
