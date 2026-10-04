package dev.naominet.lazer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopDigitalVolumePolicyTest {
    @Test
    fun `DoP preference alone does not lock software volume for PCM`() {
        assertFalse(
            shouldBypassDesktopDigitalVolume(
                nativePlayback = true,
                bitPerfectActive = false,
                doPActive = false,
                bitPerfectOpening = false,
                doPOpening = false,
            ),
        )
    }

    @Test
    fun `active bit-perfect and DoP streams bypass software volume`() {
        assertTrue(shouldBypass(bitPerfectActive = true))
        assertTrue(shouldBypass(doPActive = true))
    }

    @Test
    fun `opening bit-perfect and DoP streams bypass software volume`() {
        assertTrue(shouldBypass(bitPerfectOpening = true))
        assertTrue(shouldBypass(doPOpening = true))
    }

    @Test
    fun `standard player ignores native signal flags`() {
        assertFalse(shouldBypassDesktopDigitalVolume(
            nativePlayback = false,
            bitPerfectActive = true,
            doPActive = true,
            bitPerfectOpening = true,
            doPOpening = true,
        ))
    }

    private fun shouldBypass(
        bitPerfectActive: Boolean = false,
        doPActive: Boolean = false,
        bitPerfectOpening: Boolean = false,
        doPOpening: Boolean = false,
    ) = shouldBypassDesktopDigitalVolume(
        nativePlayback = true,
        bitPerfectActive = bitPerfectActive,
        doPActive = doPActive,
        bitPerfectOpening = bitPerfectOpening,
        doPOpening = doPOpening,
    )
}
