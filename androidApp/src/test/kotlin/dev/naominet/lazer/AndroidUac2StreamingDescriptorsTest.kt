package dev.naominet.lazer

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class AndroidUac2StreamingDescriptorsTest {
    @Test
    fun `UAC2 PCM alt settings link the terminal clock and isochronous endpoints`() {
        val parsed = parseAndroidUac2PlaybackAltSettings(
            playbackConfiguration(
                altSettings = listOf(
                    playbackAltSetting(alt = 1, subslotSize = 3, validBits = 24, feedback = true),
                    playbackAltSetting(alt = 2, subslotSize = 4, validBits = 32, feedback = false),
                ),
            ),
        )

        assertNotNull(parsed)
        assertEquals(2, parsed.size)
        val first = parsed[0]
        assertEquals(1, first.configurationValue)
        assertEquals(1, first.controlInterfaceNumber)
        assertEquals(2, first.interfaceNumber)
        assertEquals(1, first.alternateSetting)
        assertEquals(5, first.terminalLink)
        assertEquals(3, first.clockSourceId)
        assertEquals(2, first.channelCount)
        assertEquals(3L, first.channelConfig)
        assertEquals(3, first.subslotSizeBytes)
        assertEquals(24, first.validBitResolution)
        assertEquals(0x01, first.dataEndpoint.address)
        assertEquals(1, first.dataEndpoint.synchronizationType)
        assertEquals(288, first.dataEndpoint.maximumPacketSizeBytes)
        assertEquals(1, first.dataEndpoint.transactionsPerMicroframe)
        assertEquals(1, first.dataEndpoint.interval)
        assertEquals(0x81, first.feedbackEndpoint?.address)
        assertEquals(0, first.feedbackEndpoint?.synchronizationType)
        assertEquals(1, first.feedbackEndpoint?.usageType)
        assertEquals(AndroidUac2ClockFrequencyAccess.ReadOnly, first.clockFrequencyAccess)
        assertEquals(4, parsed[1].subslotSizeBytes)
        assertEquals(32, parsed[1].validBitResolution)
        assertNull(parsed[1].feedbackEndpoint)
    }

    @Test
    fun `capture alternate settings are ignored while playback is returned`() {
        val parsed = parseAndroidUac2PlaybackAltSettings(
            playbackConfiguration(
                altSettings = listOf(playbackAltSetting(alt = 1, feedback = false)),
                includeCaptureAlt = true,
            ),
        )

        assertNotNull(parsed)
        assertEquals(listOf(1), parsed.map { it.alternateSetting })
    }

    @Test
    fun `raw device descriptors may precede the configuration descriptor`() {
        val rawDeviceDescriptor = bytes(
            18, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 64, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 1,
        )
        val rawDescriptors = rawDeviceDescriptor + playbackConfiguration(
            altSettings = listOf(playbackAltSetting(alt = 1, feedback = false)),
        )

        val parsed = parseAndroidUac2PlaybackAltSettings(rawDescriptors)

        assertNotNull(parsed)
        assertEquals(1, parsed.size)
        assertEquals(16, parsed.single().validBitResolution)
    }

    @Test
    fun `multiple raw configurations retain configuration identity`() {
        val rawDeviceDescriptor = bytes(
            18, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 64, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 2,
        )
        val rawDescriptors = rawDeviceDescriptor +
            playbackConfiguration(altSettings = listOf(playbackAltSetting(alt = 1, feedback = false)), configurationValue = 1) +
            playbackConfiguration(altSettings = listOf(playbackAltSetting(alt = 2, feedback = true)), configurationValue = 2)

        val parsed = parseAndroidUac2PlaybackAltSettings(rawDescriptors)

        assertNotNull(parsed)
        assertEquals(listOf(1, 2), parsed.map { it.configurationValue })
        assertEquals(listOf(1, 2), parsed.map { it.alternateSetting })
    }

    @Test
    fun `unrelated bulk endpoint with zero interval does not reject playback`() {
        val configuration = playbackConfiguration(
            altSettings = listOf(playbackAltSetting(alt = 1, feedback = false)),
            includeBulkInterface = true,
        )

        val parsed = parseAndroidUac2PlaybackAltSettings(configuration)

        assertNotNull(parsed)
        assertEquals(1, parsed.size)
    }

    @Test
    fun `direct clock source request addresses sampling frequency range`() {
        val source = AndroidUac2ClockSource(
            controlInterfaceNumber = 1,
            clockSourceId = 3,
            frequencyAccess = AndroidUac2ClockFrequencyAccess.ReadOnly,
        )
        val header = androidUac2ClockSourceGetRangeHeaderRequest(source)
        val complete = androidUac2ClockSourceGetRangeRequest(source, subRangeCount = 2)

        assertEquals(0xa1, header.requestType)
        assertEquals(0x02, header.request)
        assertEquals(0x0100, header.value)
        assertEquals(0x0301, header.index)
        assertEquals(2, header.length)
        assertEquals(2, header.data.size)
        assertEquals(2 + 2 * 12, complete.length)
        assertEquals(complete.length, complete.data.size)
        assertFailsWith<IllegalArgumentException> {
            androidUac2ClockSourceGetRangeRequest(source, subRangeCount = 0)
        }
    }

    @Test
    fun `clock source current frequency requests and programmable rate writes are exact`() {
        val readOnly = AndroidUac2ClockSource(1, 3, AndroidUac2ClockFrequencyAccess.ReadOnly)
        val programmable = AndroidUac2ClockSource(1, 3, AndroidUac2ClockFrequencyAccess.HostProgrammable)
        val currentRequest = androidUac2ClockSourceGetCurrentFrequencyRequest(readOnly)

        assertEquals(0xa1, currentRequest.requestType)
        assertEquals(0x01, currentRequest.request)
        assertEquals(0x0100, currentRequest.value)
        assertEquals(0x0301, currentRequest.index)
        assertEquals(4, currentRequest.length)
        assertEquals(96_000L, parseAndroidUac2ClockSourceCurrentFrequencyHz(byteArrayOf(0x00, 0x77, 0x01, 0x00)))
        assertFailsWith<IllegalArgumentException> {
            parseAndroidUac2ClockSourceCurrentFrequencyHz(byteArrayOf(1, 2, 3))
        }

        val ranges = listOf(AndroidUac2ClockFrequencyRange(44_100, 192_000, 1))
        val setRequest = androidUac2ClockSourceSetCurrentFrequencyRequest(programmable, 96_000, ranges)
        assertEquals(0x21, setRequest.requestType)
        assertEquals(0x01, setRequest.request)
        assertEquals(0x0100, setRequest.value)
        assertEquals(0x0301, setRequest.index)
        assertEquals(listOf(0, 0x77, 1, 0), setRequest.data.map { it.toInt() and 0xff })
        assertFailsWith<IllegalArgumentException> {
            androidUac2ClockSourceSetCurrentFrequencyRequest(readOnly, 96_000, ranges)
        }
        assertFailsWith<IllegalArgumentException> {
            androidUac2ClockSourceSetCurrentFrequencyRequest(programmable, 48_000, listOf(
                AndroidUac2ClockFrequencyRange(44_100, 44_100, 0),
                AndroidUac2ClockFrequencyRange(96_000, 96_000, 0),
            ))
        }
    }

    @Test
    fun `playback plan binds selected rate to the exact configuration and endpoints`() {
        val alternate = assertNotNull(
            parseAndroidUac2PlaybackAltSettings(
                playbackConfiguration(
                    altSettings = listOf(playbackAltSetting(alt = 1, subslotSize = 4, validBits = 24, feedback = true)),
                    configurationValue = 7,
                    clockEntity = clockSource(id = 3, frequencyControl = 3),
                ),
            ),
        ).single()
        val ranges = listOf(
            AndroidUac2ClockFrequencyRange(44_100, 44_100, 0),
            AndroidUac2ClockFrequencyRange(96_000, 96_000, 0),
        )

        val plan = planAndroidUac2PcmPlayback(alternate, 96_000, ranges)

        assertNotNull(plan)
        assertEquals(7, plan.configurationValue)
        assertEquals(96_000, plan.sampleRateHz)
        assertEquals(AndroidUac2ClockFrequencyAccess.HostProgrammable, plan.clockFrequencyAccess)
        assertEquals(4, plan.subslotSizeBytes)
        assertEquals(24, plan.validBitResolution)
        assertEquals(0x01, plan.dataEndpoint.address)
        assertEquals(0x81, plan.feedbackEndpoint?.address)
        assertNull(planAndroidUac2PcmPlayback(alternate, 48_000, ranges))

        val asyncWithoutFeedback = playbackConfiguration(
            altSettings = listOf(playbackAltSetting(alt = 1, feedback = false)),
            clockEntity = clockSource(id = 3, frequencyControl = 3),
        ).apply {
            val endpointOffset = indexOfDescriptor(this, descriptorType = 0x05, descriptorSubtype = null, occurrence = 0)
            this[endpointOffset + 3] = 0x05 // Async OUT needs explicit feedback until implicit discovery is supported.
        }
        val asyncAlternate = assertNotNull(parseAndroidUac2PlaybackAltSettings(asyncWithoutFeedback)).single()
        assertNull(planAndroidUac2PcmPlayback(asyncAlternate, 96_000, ranges))
    }

    @Test
    fun `direct clock selector path retains ordered pins and source controls`() {
        val parsed = assertNotNull(
            parseAndroidUac2PlaybackAltSettings(
                playbackConfiguration(
                    altSettings = listOf(playbackAltSetting(alt = 1, feedback = false)),
                    clockEntity = clockSource(3) + clockSource(4, frequencyControl = 3) +
                        clockSelector(id = 6, sourceIds = listOf(3, 4), selectionControl = 3),
                    terminalClockId = 6,
                ),
            ),
        ).single()

        assertEquals(6, parsed.clockSelector?.clockSelectorId)
        assertEquals(AndroidUac2ClockFrequencyAccess.HostProgrammable, parsed.clockSelector?.selectionAccess)
        assertEquals(listOf(1, 2), parsed.clockSourceCandidates.map { it.pin })
        assertEquals(listOf(3, 4), parsed.clockSourceCandidates.map { it.clockSource.clockSourceId })
        assertEquals(1, parsed.clockSourceCandidates.first().clockSource.controlInterfaceNumber)
        val plan = assertNotNull(resolveAndroidUac2PcmCandidatePlan(listOf(parsed), 96_000, 2, 2, 16))
        assertEquals(parsed.clockSelector, plan.clockSelector)
        assertEquals(parsed.clockSourceCandidates, plan.clockSourceCandidates)
        assertNull(
            planAndroidUac2PcmPlayback(
                parsed,
                96_000,
                listOf(AndroidUac2ClockFrequencyRange(96_000, 96_000, 0)),
            ),
        )
    }

    @Test
    fun `clock selector requests expose one byte selection control and planner fails closed`() {
        val selector = AndroidUac2ClockSelector(
            controlInterfaceNumber = 1,
            clockSelectorId = 6,
            selectionAccess = AndroidUac2ClockFrequencyAccess.HostProgrammable,
        )
        val candidates = listOf(
            AndroidUac2ClockSelectorCandidate(1, AndroidUac2ClockSource(1, 3, AndroidUac2ClockFrequencyAccess.ReadOnly)),
            AndroidUac2ClockSelectorCandidate(2, AndroidUac2ClockSource(1, 4, AndroidUac2ClockFrequencyAccess.ReadOnly)),
        )
        val only44k1 = listOf(AndroidUac2ClockFrequencyRange(44_100, 44_100, 0))
        val only96k = listOf(AndroidUac2ClockFrequencyRange(96_000, 96_000, 0))
        val get = androidUac2ClockSelectorGetCurrentPinRequest(selector)
        val set = androidUac2ClockSelectorSetCurrentPinRequest(selector, 2, candidates.size)

        assertEquals(0xa1, get.requestType)
        assertEquals(0x01, get.request)
        assertEquals(0x0100, get.value)
        assertEquals(0x0601, get.index)
        assertEquals(1, get.length)
        assertEquals(0x21, set.requestType)
        assertEquals(0x01, set.request)
        assertEquals(0x0100, set.value)
        assertEquals(0x0601, set.index)
        assertEquals(listOf(2), set.data.map { it.toInt() and 0xff })
        assertEquals(2, parseAndroidUac2ClockSelectorCurrentPin(byteArrayOf(2), 2))
        assertFailsWith<IllegalArgumentException> { parseAndroidUac2ClockSelectorCurrentPin(byteArrayOf(0), 2) }

        val current = planAndroidUac2ClockSelectorSelection(
            selector, candidates, currentPin = 2,
            supportedRangesByPin = mapOf(1 to only44k1, 2 to only96k), sampleRateHz = 96_000,
        )
        assertEquals(2, current?.pin)
        assertEquals(4, current?.clockSource?.clockSourceId)
        val switched = planAndroidUac2ClockSelectorSelection(
            selector, candidates, currentPin = 1,
            supportedRangesByPin = mapOf(1 to only44k1, 2 to only96k), sampleRateHz = 96_000,
        )
        assertEquals(2, switched?.pin)

        val readOnlySelector = selector.copy(selectionAccess = AndroidUac2ClockFrequencyAccess.ReadOnly)
        assertNull(
            planAndroidUac2ClockSelectorSelection(
                readOnlySelector,
                candidates,
                currentPin = 1,
                supportedRangesByPin = mapOf(1 to only44k1, 2 to only96k),
                sampleRateHz = 96_000,
            ),
        )
        assertFailsWith<IllegalArgumentException> {
            androidUac2ClockSelectorSetCurrentPinRequest(readOnlySelector, 2, candidates.size)
        }
    }

    @Test
    fun `clock source range parses 44 point 1 and 96 kilohertz discrete subranges`() {
        val response = clockRangeResponse(
            Triple(44_100L, 44_100L, 0L),
            Triple(96_000L, 96_000L, 0L),
        )

        assertEquals(2, parseAndroidUac2ClockSourceGetRangeSubRangeCount(response.copyOfRange(0, 2)))
        val ranges = parseAndroidUac2ClockSourceFrequencyRanges(response, expectedSubRangeCount = 2)
        assertEquals(
            listOf(
                AndroidUac2ClockFrequencyRange(44_100, 44_100, 0),
                AndroidUac2ClockFrequencyRange(96_000, 96_000, 0),
            ),
            ranges,
        )
        assertTrue(ranges[0].contains(44_100))
        assertTrue(ranges[1].contains(96_000))
        assertTrue(!ranges[0].contains(48_000))
    }

    @Test
    fun `clock source range rejects truncated inconsistent zero and overlapping data`() {
        val valid = clockRangeResponse(
            Triple(44_100L, 44_100L, 0L),
            Triple(96_000L, 96_000L, 0L),
        )

        assertFailsWith<IllegalArgumentException> {
            parseAndroidUac2ClockSourceGetRangeSubRangeCount(byteArrayOf(2))
        }
        assertFailsWith<IllegalArgumentException> {
            parseAndroidUac2ClockSourceGetRangeSubRangeCount(byteArrayOf(0, 0))
        }
        assertFailsWith<IllegalArgumentException> {
            parseAndroidUac2ClockSourceFrequencyRanges(valid.copyOf(valid.size - 1))
        }
        assertFailsWith<IllegalArgumentException> {
            parseAndroidUac2ClockSourceFrequencyRanges(valid, expectedSubRangeCount = 1)
        }
        assertFailsWith<IllegalArgumentException> {
            parseAndroidUac2ClockSourceFrequencyRanges(
                clockRangeResponse(Triple(44_100, 48_000, 0)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            parseAndroidUac2ClockSourceFrequencyRanges(
                clockRangeResponse(
                    Triple(44_100, 96_000, 1),
                    Triple(96_000, 192_000, 1),
                ),
            )
        }
    }

    @Test
    fun `streaming parser rejects truncated malformed and ambiguous descriptors`() {
        val valid = playbackConfiguration(altSettings = listOf(playbackAltSetting(alt = 1, feedback = true)))
        assertNull(parseAndroidUac2PlaybackAltSettings(valid.copyOf(valid.size - 1)))

        val malformedEndpoint = valid.copyOf().apply {
            val endpointOffset = indexOfDescriptor(this, descriptorType = 0x05, descriptorSubtype = null, occurrence = 1)
            this[endpointOffset] = 6
        }
        assertNull(parseAndroidUac2PlaybackAltSettings(malformedEndpoint))

        val extendedEndpoint = valid.copyOf(valid.size + 1).apply {
            val endpointOffset = indexOfDescriptor(valid, descriptorType = 0x05, descriptorSubtype = null, occurrence = 0)
            System.arraycopy(valid, endpointOffset + 7, this, endpointOffset + 8, valid.size - endpointOffset - 7)
            this[endpointOffset] = 8
            this[endpointOffset + 7] = 0
            val totalLength = valid.size + 1
            this[2] = totalLength.toByte()
            this[3] = (totalLength ushr 8).toByte()
        }
        assertNull(parseAndroidUac2PlaybackAltSettings(extendedEndpoint))

        val malformedAsGeneral = valid.copyOf().apply {
            val generalOffset = indexOfDescriptor(this, descriptorType = 0x24, descriptorSubtype = 0x01, occurrence = 1)
            this[generalOffset] = 15
        }
        assertNull(parseAndroidUac2PlaybackAltSettings(malformedAsGeneral))

        val oversizedIsochronousPacket = valid.copyOf().apply {
            val endpointOffset = indexOfDescriptor(this, descriptorType = 0x05, descriptorSubtype = null, occurrence = 0)
            this[endpointOffset + 4] = 0x01
            this[endpointOffset + 5] = 0x04 // 1025 bytes exceeds the USB 2.0 high-speed transaction limit.
        }
        assertNull(parseAndroidUac2PlaybackAltSettings(oversizedIsochronousPacket))

        val synchronousIsochronousEndpoint = playbackConfiguration(
            altSettings = listOf(playbackAltSetting(alt = 1, feedback = false)),
        ).apply {
            val endpointOffset = indexOfDescriptor(this, descriptorType = 0x05, descriptorSubtype = null, occurrence = 0)
            this[endpointOffset + 3] = 0x0d // Isochronous, synchronous.
        }
        assertEquals(
            3,
            assertNotNull(parseAndroidUac2PlaybackAltSettings(synchronousIsochronousEndpoint))
                .single().dataEndpoint.synchronizationType,
        )

        val unsynchronizedIsochronousEndpoint = synchronousIsochronousEndpoint.copyOf().apply {
            val endpointOffset = indexOfDescriptor(this, descriptorType = 0x05, descriptorSubtype = null, occurrence = 0)
            this[endpointOffset + 3] = 0x01 // Isochronous with reserved synchronization type 00.
        }
        assertNull(parseAndroidUac2PlaybackAltSettings(unsynchronizedIsochronousEndpoint))

        assertNull(
            parseAndroidUac2PlaybackAltSettings(
                playbackConfiguration(
                    altSettings = listOf(
                        playbackAltSetting(alt = 1, feedback = false, extraDataEndpoints = 1),
                    ),
                ),
            ),
        )
        assertNull(
            parseAndroidUac2PlaybackAltSettings(
                playbackConfiguration(
                    altSettings = listOf(
                        playbackAltSetting(alt = 1, feedback = true, extraFeedbackEndpoints = 1),
                    ),
                ),
            ),
        )
        assertNull(
            parseAndroidUac2PlaybackAltSettings(
                playbackConfiguration(
                    altSettings = listOf(
                        playbackAltSetting(alt = 1, feedback = false),
                        playbackAltSetting(alt = 1, feedback = false),
                    ),
                ),
            ),
        )
    }

    @Test
    fun `streaming parser fails closed for malformed cyclic missing and nested clock selector paths`() {
        val altSettings = listOf(playbackAltSetting(alt = 1, feedback = false))
        assertNull(parseAndroidUac2PlaybackAltSettings(playbackConfiguration(
            altSettings = altSettings,
            clockEntity = clockSource(3) + clockSelector(id = 6, sourceIds = listOf(3, 9)),
            terminalClockId = 6,
        )))
        assertNull(parseAndroidUac2PlaybackAltSettings(playbackConfiguration(
            altSettings = altSettings,
            clockEntity = clockSource(3) + clockSelector(id = 6, sourceIds = listOf(6)),
            terminalClockId = 6,
        )))
        assertNull(parseAndroidUac2PlaybackAltSettings(playbackConfiguration(
            altSettings = altSettings,
            clockEntity = clockSource(3) + clockSelector(id = 6, sourceIds = listOf(3), selectionControl = 0),
            terminalClockId = 6,
        )))
        assertNull(parseAndroidUac2PlaybackAltSettings(playbackConfiguration(
            altSettings = altSettings,
            clockEntity = clockSource(3) + clockSelector(id = 6, sourceIds = listOf(3), selectionControl = 0x05),
            terminalClockId = 6,
        )))
        val truncatedSelector = clockSelector(id = 6, sourceIds = listOf(3)).copyOf(7)
        assertNull(parseAndroidUac2PlaybackAltSettings(playbackConfiguration(
            altSettings = altSettings,
            clockEntity = clockSource(3) + truncatedSelector,
            terminalClockId = 6,
        )))
        assertNull(parseAndroidUac2PlaybackAltSettings(playbackConfiguration(
            altSettings = altSettings,
            clockEntity = clockSource(3) + clockSelector(id = 6, sourceIds = listOf(3, 7)) +
                clockSelector(id = 7, sourceIds = listOf(3)),
            terminalClockId = 6,
        )))
        assertNull(
            parseAndroidUac2PlaybackAltSettings(
                playbackConfiguration(
                    altSettings = listOf(
                        playbackAltSetting(alt = 1, subslotSize = 2, validBits = 24, feedback = false),
                    ),
                ),
            ),
        )
        assertNull(
            parseAndroidUac2PlaybackAltSettings(
                playbackConfiguration(
                    altSettings = listOf(playbackAltSetting(alt = 1, feedback = false)),
                    clockEntity = clockSource(id = 3, frequencyControl = 0),
                ),
            ),
        )
    }

    private fun playbackConfiguration(
        altSettings: List<ByteArray>,
        configurationValue: Int = 1,
        includeCaptureAlt: Boolean = false,
        includeBulkInterface: Boolean = false,
        clockEntity: ByteArray = clockSource(id = 3),
        terminalClockId: Int = 3,
    ): ByteArray {
        val audioControlEntities = clockEntity + inputTerminal(id = 5, clockId = terminalClockId)
        val controlTotalLength = 9 + audioControlEntities.size
        val audioControlInterface = bytes(9, 4, 1, 0, 0, 1, 1, 0x20, 0)
        val audioControlHeader = bytes(
            9, 0x24, 1, 0x00, 0x02, 0x01,
            controlTotalLength and 0xff,
            controlTotalLength ushr 8,
            0,
        )
        val iad = bytes(8, 0x0b, 1, if (includeCaptureAlt) 3 else 2, 1, 0, 0x20, 0)
        val playbackZero = bytes(9, 4, 2, 0, 0, 1, 2, 0x20, 0)
        val playback = altSettings.fold(playbackZero) { all, alt -> all + alt }
        val capture = if (includeCaptureAlt) captureAltInterface() else ByteArray(0)
        val bulk = if (includeBulkInterface) {
            bytes(9, 4, 3, 0, 1, 0xff, 0, 0, 0) +
                standardEndpoint(address = 0x03, attributes = 0x02, maxPacketSize = 64, interval = 0)
        } else ByteArray(0)
        val body = iad + audioControlInterface + audioControlHeader + audioControlEntities + playback + capture + bulk
        val interfaceCount = 2 + (if (includeCaptureAlt) 1 else 0) + (if (includeBulkInterface) 1 else 0)
        val totalLength = 9 + body.size
        val configuration = bytes(
            9, 2,
            totalLength and 0xff,
            totalLength ushr 8,
            interfaceCount,
            configurationValue, 0, 0x80, 50,
        ) + body
        return configuration
    }

    private fun playbackAltSetting(
        alt: Int,
        subslotSize: Int = 2,
        validBits: Int = 16,
        feedback: Boolean,
        extraDataEndpoints: Int = 0,
        extraFeedbackEndpoints: Int = 0,
    ): ByteArray {
        val dataEndpoints = listOf(
            standardEndpoint(
                address = 0x01,
                attributes = if (feedback) 0x05 else 0x09,
                maxPacketSize = 288,
            ),
        ) + List(extraDataEndpoints) { index ->
            standardEndpoint(address = 0x02 + index, attributes = 0x09, maxPacketSize = 288)
        }
        val feedbackEndpoints = if (!feedback && extraFeedbackEndpoints == 0) {
            emptyList()
        } else {
            listOf(standardEndpoint(address = 0x81, attributes = 0x11, maxPacketSize = 3)) +
                List(extraFeedbackEndpoints) { index ->
                    standardEndpoint(address = 0x82 + index, attributes = 0x11, maxPacketSize = 3)
                }
        }
        val endpoints = dataEndpoints + feedbackEndpoints
        val standardInterface = bytes(9, 4, 2, alt, endpoints.size, 1, 2, 0x20, 0)
        val general = bytes(16, 0x24, 1, 5, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0)
        val format = bytes(6, 0x24, 2, 1, subslotSize, validBits)
        return standardInterface + general + format + endpoints.fold(ByteArray(0), ByteArray::plus)
    }

    private fun captureAltInterface(): ByteArray {
        val standardInterface = bytes(9, 4, 3, 0, 0, 1, 2, 0x20, 0) +
            bytes(9, 4, 3, 1, 1, 1, 2, 0x20, 0)
        val general = bytes(16, 0x24, 1, 6, 0, 1, 1, 0, 0, 0, 2, 3, 0, 0, 0, 0)
        val format = bytes(6, 0x24, 2, 1, 2, 16)
        return standardInterface + general + format + standardEndpoint(
            address = 0x82,
            attributes = 0x05,
            maxPacketSize = 192,
        )
    }

    private fun clockSource(
        id: Int,
        frequencyControl: Int = 1,
    ): ByteArray = bytes(8, 0x24, 0x0a, id, 0x03, frequencyControl, 0, 0)

    private fun clockSelector(
        id: Int,
        sourceIds: List<Int>,
        selectionControl: Int = 1,
    ): ByteArray = bytes(
        7 + sourceIds.size, 0x24, 0x0b, id, sourceIds.size,
        *sourceIds.toIntArray(), selectionControl, 0,
    )

    private fun inputTerminal(id: Int, clockId: Int): ByteArray = bytes(
        17, 0x24, 0x02, id, 0x01, 0x01, 0, clockId, 2,
        3, 0, 0, 0, 0, 0, 0, 0,
    )

    private fun standardEndpoint(address: Int, attributes: Int, maxPacketSize: Int, interval: Int = 1): ByteArray = bytes(
        7, 0x05, address, attributes, maxPacketSize and 0xff, maxPacketSize ushr 8, interval,
    )

    private fun clockRangeResponse(vararg ranges: Triple<Long, Long, Long>): ByteArray {
        val result = mutableListOf(ranges.size and 0xff, ranges.size ushr 8)
        ranges.forEach { (minimum, maximum, resolution) ->
            listOf(minimum, maximum, resolution).forEach { value ->
                repeat(4) { shift -> result += ((value ushr (shift * 8)) and 0xff).toInt() }
            }
        }
        return result.map(Int::toByte).toByteArray()
    }

    private fun indexOfDescriptor(
        data: ByteArray,
        descriptorType: Int,
        descriptorSubtype: Int?,
        occurrence: Int,
    ): Int {
        var offset = 0
        var matchingDescriptor = 0
        while (offset < data.size) {
            val length = data[offset].toInt() and 0xff
            if (data[offset + 1].toInt() and 0xff == descriptorType &&
                (descriptorSubtype == null || (data[offset + 2].toInt() and 0xff) == descriptorSubtype)
            ) {
                if (matchingDescriptor++ == occurrence) return offset
            }
            offset += length
        }
        error("Descriptor not found")
    }

    private fun bytes(vararg values: Int): ByteArray = values.map(Int::toByte).toByteArray()
}
