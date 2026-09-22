package dev.naominet.lazer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LazerScanLayoutTest {
    @Test
    fun `scan frame follows the shorter landscape edge`() {
        assertEquals(397, scanFrameSizePx(previewWidth = 1280, previewHeight = 640, density = 2f))
    }

    @Test
    fun `scan frame stays usable on compact and large screens`() {
        assertEquals(300, scanFrameSizePx(previewWidth = 640, previewHeight = 300, density = 2f))
        assertEquals(990, scanFrameSizePx(previewWidth = 2400, previewHeight = 1600, density = 3f))
    }

    @Test
    fun `official client login QR is normalized without losing its chain`() {
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
