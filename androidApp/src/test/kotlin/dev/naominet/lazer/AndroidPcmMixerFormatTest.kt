package dev.naominet.lazer

import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test

class AndroidPcmMixerFormatTest {
    private val requested = AndroidPcmMixerFormat(
        sampleRateHz = 96_000,
        encoding = 4,
        channelMask = 12,
        channelIndexMask = 0,
    )

    @Test
    fun `mixer match requires exact rate encoding and both channel masks`() {
        val exact = AndroidPcmMixerCandidate(requested, behavior = 1)
        assertSame(exact, exactMixerCandidate(listOf(exact), requested))
        assertNull(
            exactMixerCandidate(
                listOf(exact.copy(format = requested.copy(sampleRateHz = 48_000))),
                requested,
            ),
        )
        assertNull(
            exactMixerCandidate(
                listOf(exact.copy(format = requested.copy(channelIndexMask = 3))),
                requested,
            ),
        )
    }

    @Test
    fun `bit perfect matching requires the exact format and reported behavior`() {
        val defaultMixer = AndroidPcmMixerCandidate(requested, behavior = 0)
        val bitPerfectMixer = AndroidPcmMixerCandidate(requested, behavior = 1)
        assertNull(exactBitPerfectMixerCandidate(listOf(defaultMixer), requested, bitPerfectBehavior = 1))
        assertSame(
            bitPerfectMixer,
            exactBitPerfectMixerCandidate(listOf(defaultMixer, bitPerfectMixer), requested, bitPerfectBehavior = 1),
        )
    }

    @Test
    fun `only supported integer pcm encodings map to the requested depth`() {
        assertEquals(16, pcmBitDepthForAndroidEncoding(2, pcm16 = 2, packedPcm24 = 21, pcm32 = 22))
        assertEquals(24, pcmBitDepthForAndroidEncoding(21, pcm16 = 2, packedPcm24 = 21, pcm32 = 22))
        assertEquals(32, pcmBitDepthForAndroidEncoding(22, pcm16 = 2, packedPcm24 = 21, pcm32 = 22))
        assertNull(pcmBitDepthForAndroidEncoding(4, pcm16 = 2, packedPcm24 = 21, pcm32 = 22))
    }

    @Test
    fun `test tone route candidate maps exact format and HAL behavior without borrowing another device`() {
        val exactBitPerfect = AndroidPcmMixerCandidate(requested, behavior = 1)
        val wrongRateBitPerfect = AndroidPcmMixerCandidate(
            requested.copy(sampleRateHz = 48_000),
            behavior = 1,
        )
        val selectedTarget = androidPcmTestToneRouteCandidate(
            deviceId = 22,
            stableIdentity = "chosen-dac",
            candidates = listOf(wrongRateBitPerfect),
            requested = requested,
            bitPerfectBehavior = 1,
        )
        val automaticExact = androidPcmTestToneRouteCandidate(
            deviceId = 11,
            stableIdentity = "other-dac",
            candidates = listOf(exactBitPerfect),
            requested = requested,
            bitPerfectBehavior = 1,
        )

        assertEquals(false, selectedTarget.exactFormat)
        assertEquals(false, selectedTarget.bitPerfectBehavior)
        assertEquals(AndroidUsbRouteChoice(22, AndroidMedia3UsbRouteSelection.USER_SELECTED), chooseAndroidUsbRoute(
            listOf(automaticExact, selectedTarget),
            savedTargetIdentity = "chosen-dac",
        ))
        assertEquals(AndroidUsbRouteChoice(null, AndroidMedia3UsbRouteSelection.SAVED_TARGET_UNAVAILABLE), chooseAndroidUsbRoute(
            listOf(automaticExact),
            savedTargetIdentity = "chosen-dac",
        ))
        assertEquals(AndroidUsbRouteChoice(11, AndroidMedia3UsbRouteSelection.UNIQUE_EXACT_FORMAT), chooseAndroidUsbRoute(
            listOf(automaticExact, selectedTarget),
            savedTargetIdentity = null,
        ))
    }

    @Test
    fun `pre API 34 test tone candidates retain unknown HAL format evidence`() {
        val candidate = androidPcmTestToneRouteCandidate(
            deviceId = 11,
            stableIdentity = "dac",
            candidates = emptyList(),
            requested = requested,
            bitPerfectBehavior = null,
        )

        assertNull(candidate.exactFormat)
        assertNull(candidate.bitPerfectBehavior)
        assertTrue(chooseAndroidUsbRoute(listOf(candidate), savedTargetIdentity = null).deviceId == 11)
    }
}
