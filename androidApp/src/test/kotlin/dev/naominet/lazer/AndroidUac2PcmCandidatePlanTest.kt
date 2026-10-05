package dev.naominet.lazer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class AndroidUac2PcmCandidatePlanTest {
    @Test
    fun `resolves a unique stereo PCM tuple and preserves exact stream descriptors`() {
        val alternate = alternate(subslotSizeBytes = 3, validBitResolution = 24)

        val plan = resolveAndroidUac2PcmCandidatePlan(
            alternateSettings = listOf(alternate),
            sampleRateHz = 192_000,
            channelCount = 2,
            bytesPerSample = 3,
            validBitResolution = 24,
        )

        assertNotNull(plan)
        assertEquals(192_000L, plan?.sampleRateHz)
        assertEquals(alternate.configurationValue, plan?.configurationValue)
        assertEquals(alternate.interfaceNumber, plan?.interfaceNumber)
        assertEquals(alternate.alternateSetting, plan?.alternateSetting)
        assertEquals(alternate.dataEndpoint, plan?.dataEndpoint)
        assertEquals(alternate.feedbackEndpoint, plan?.feedbackEndpoint)
    }

    @Test
    fun `ambiguous matching alternates fail closed`() {
        assertNull(
            resolveAndroidUac2PcmCandidatePlan(
                alternateSettings = listOf(alternate(), alternate(interfaceNumber = 4)),
                sampleRateHz = 48_000,
                channelCount = 2,
                bytesPerSample = 2,
                validBitResolution = 16,
            ),
        )
    }

    @Test
    fun `rejects unsupported channel layouts and PCM packing`() {
        assertNull(resolveAndroidUac2PcmCandidatePlan(listOf(alternate(channelConfig = 0x0f)), 48_000, 2, 2, 16))
        assertNull(resolveAndroidUac2PcmCandidatePlan(listOf(alternate(channelCount = 1)), 48_000, 1, 2, 16))
        assertNull(resolveAndroidUac2PcmCandidatePlan(listOf(alternate(subslotSizeBytes = 4)), 48_000, 2, 3, 24))
        assertNull(resolveAndroidUac2PcmCandidatePlan(listOf(alternate(validBitResolution = 20)), 48_000, 2, 3, 24))
    }

    @Test
    fun `rejects invalid rates and asynchronous streams without explicit feedback`() {
        assertNull(resolveAndroidUac2PcmCandidatePlan(listOf(alternate()), 0, 2, 2, 16))
        assertNull(resolveAndroidUac2PcmCandidatePlan(listOf(alternate()), 768_001, 2, 2, 16))
        assertNull(resolveAndroidUac2PcmCandidatePlan(listOf(alternate(feedbackEndpoint = null)), 48_000, 2, 2, 16))
    }

    private fun alternate(
        interfaceNumber: Int = 3,
        channelCount: Int = 2,
        channelConfig: Long = 0x0000_0003L,
        subslotSizeBytes: Int = 2,
        validBitResolution: Int = 16,
        feedbackEndpoint: AndroidUac2IsochronousEndpoint? = defaultFeedbackEndpoint(),
    ) = AndroidUac2PlaybackAltSetting(
        configurationValue = 1,
        controlInterfaceNumber = 1,
        interfaceNumber = interfaceNumber,
        alternateSetting = 1,
        terminalLink = 4,
        clockSourceId = 8,
        channelCount = channelCount,
        channelConfig = channelConfig,
        clockFrequencyAccess = AndroidUac2ClockFrequencyAccess.HostProgrammable,
        subslotSizeBytes = subslotSizeBytes,
        validBitResolution = validBitResolution,
        dataEndpoint = AndroidUac2IsochronousEndpoint(
            address = 0x01,
            synchronizationType = 1,
            usageType = 0,
            maximumPacketSizeBytes = 1024,
            transactionsPerMicroframe = 1,
            interval = 1,
        ),
        feedbackEndpoint = feedbackEndpoint,
    )

    private fun defaultFeedbackEndpoint() = AndroidUac2IsochronousEndpoint(
        address = 0x81,
        synchronizationType = 0,
        usageType = 1,
        maximumPacketSizeBytes = 4,
        transactionsPerMicroframe = 1,
        interval = 1,
    )
}
