package dev.naominet.lazer

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class AndroidUacVolumeTest {
    @Test
    fun `USB permission result must match the pending generation and selected device`() {
        assertTrue(permissionCallbackMatches())
        assertFalse(permissionCallbackMatches(currentGeneration = 42L))
        assertFalse(permissionCallbackMatches(callbackGeneration = null))
        assertFalse(permissionCallbackMatches(selectedDeviceId = "/dev/bus/usb/001/003"))
        assertFalse(permissionCallbackMatches(callbackDeviceId = "/dev/bus/usb/001/003"))
        assertFalse(permissionCallbackMatches(callbackVendorId = 0x4321))
        assertFalse(permissionCallbackMatches(callbackProductId = 0x4321))
    }

    @Test
    fun `UAC1 master volume is found with one and two byte control bitmaps`() {
        assertEquals(
            AndroidUacVolumeControl(AndroidUacVersion.Uac1, controlInterfaceNumber = 1, unitId = 5),
            findAndroidUacPlaybackVolumeControl(uac1Descriptors(controlSize = 1)),
        )
        assertEquals(
            AndroidUacVolumeControl(AndroidUacVersion.Uac1, controlInterfaceNumber = 1, unitId = 5),
            findAndroidUacPlaybackVolumeControl(uac1Descriptors(controlSize = 2)),
        )
    }

    @Test
    fun `UAC1 volume behind a mixer must cover every playback source`() {
        val descriptors = uac1Configuration(
            inputTerminalUac1(2),
            inputTerminalUac1(3),
            mixerUac1(unitId = 4, sourceIds = listOf(2, 3)),
            featureUac1(unitId = 5, sourceId = 4),
            outputTerminalUac1(terminalId = 7, sourceId = 5),
        )

        assertEquals(
            AndroidUacVolumeControl(AndroidUacVersion.Uac1, controlInterfaceNumber = 1, unitId = 5),
            findAndroidUacPlaybackVolumeControl(descriptors),
        )
    }

    @Test
    fun `mixers may reference the same source entity from multiple input pins`() {
        val uac1Descriptors = uac1Configuration(
            inputTerminalUac1(2),
            mixerUac1(unitId = 4, sourceIds = listOf(2, 2)),
            featureUac1(unitId = 5, sourceId = 4),
            outputTerminalUac1(terminalId = 7, sourceId = 5),
        )
        val uac2Descriptors = uac2Configuration(
            inputTerminalUac2(2),
            mixerUac2(unitId = 4, sourceIds = listOf(2, 2)),
            featureUac2(unitId = 5, sourceId = 4, masterControls = 0x0c),
            outputTerminalUac2(terminalId = 7, sourceId = 5),
        )

        assertEquals(
            AndroidUacVolumeControl(AndroidUacVersion.Uac1, controlInterfaceNumber = 1, unitId = 5),
            findAndroidUacPlaybackVolumeControl(uac1Descriptors),
        )
        assertEquals(
            AndroidUacVolumeControl(AndroidUacVersion.Uac2, controlInterfaceNumber = 1, unitId = 5),
            findAndroidUacPlaybackVolumeControl(uac2Descriptors),
        )
    }

    @Test
    fun `UAC1 processing and extension units preserve the Feature Unit source path`() {
        val descriptors = uac1Configuration(
            inputTerminalUac1(2),
            processingUac1(unitId = 3, sourceId = 2),
            extensionUac1(unitId = 4, sourceId = 3),
            featureUac1(unitId = 5, sourceId = 4),
            outputTerminalUac1(terminalId = 7, sourceId = 5),
        )

        assertEquals(
            AndroidUacVolumeControl(AndroidUacVersion.Uac1, controlInterfaceNumber = 1, unitId = 5),
            findAndroidUacPlaybackVolumeControl(descriptors),
        )
    }

    @Test
    fun `UAC2 volume behind a selector is found on every playback path`() {
        val descriptors = uac2Configuration(
            inputTerminalUac2(2),
            inputTerminalUac2(3),
            selectorUac2(unitId = 4, sourceIds = listOf(2, 3)),
            featureUac2(unitId = 5, sourceId = 4, masterControls = 0x0c),
            outputTerminalUac2(terminalId = 7, sourceId = 5),
        )

        assertEquals(
            AndroidUacVolumeControl(AndroidUacVersion.Uac2, controlInterfaceNumber = 1, unitId = 5),
            findAndroidUacPlaybackVolumeControl(descriptors),
        )
    }

    @Test
    fun `UAC2 volume after a mixer controls every input branch`() {
        val descriptors = uac2Configuration(
            inputTerminalUac2(2),
            inputTerminalUac2(3),
            mixerUac2(unitId = 4, sourceIds = listOf(2, 3)),
            featureUac2(unitId = 5, sourceId = 4, masterControls = 0x0c),
            outputTerminalUac2(terminalId = 7, sourceId = 5),
        )

        assertEquals(
            AndroidUacVolumeControl(AndroidUacVersion.Uac2, controlInterfaceNumber = 1, unitId = 5),
            findAndroidUacPlaybackVolumeControl(descriptors),
        )
    }

    @Test
    fun `UAC2 processing and extension units preserve the Feature Unit source path`() {
        val descriptors = uac2Configuration(
            inputTerminalUac2(2),
            processingUac2(unitId = 3, sourceId = 2),
            extensionUac2(unitId = 4, sourceId = 3),
            featureUac2(unitId = 5, sourceId = 4, masterControls = 0x0c),
            outputTerminalUac2(terminalId = 7, sourceId = 5),
        )

        assertEquals(
            AndroidUacVolumeControl(AndroidUacVersion.Uac2, controlInterfaceNumber = 1, unitId = 5),
            findAndroidUacPlaybackVolumeControl(descriptors),
        )
    }

    @Test
    fun `UAC2 effect and sample rate converter units preserve the Feature Unit source path`() {
        val effectDescriptors = uac2Configuration(
            inputTerminalUac2(2),
            effectUac2(unitId = 3, sourceId = 2),
            featureUac2(unitId = 4, sourceId = 3, masterControls = 0x0c),
            outputTerminalUac2(terminalId = 7, sourceId = 4),
        )
        val sampleRateConverterDescriptors = uac2Configuration(
            inputTerminalUac2(2),
            sampleRateConverterUac2(unitId = 3, sourceId = 2),
            featureUac2(unitId = 4, sourceId = 3, masterControls = 0x0c),
            outputTerminalUac2(terminalId = 7, sourceId = 4),
        )

        assertEquals(
            AndroidUacVolumeControl(AndroidUacVersion.Uac2, controlInterfaceNumber = 1, unitId = 4),
            findAndroidUacPlaybackVolumeControl(effectDescriptors),
        )
        assertEquals(
            AndroidUacVolumeControl(AndroidUacVersion.Uac2, controlInterfaceNumber = 1, unitId = 4),
            findAndroidUacPlaybackVolumeControl(sampleRateConverterDescriptors),
        )
        val truncatedEffect = effectUac2(unitId = 3, sourceId = 2).copyOf(19).apply { this[0] = 19 }
        assertNull(
            findAndroidUacPlaybackVolumeControl(
                uac2Configuration(
                    inputTerminalUac2(2),
                    truncatedEffect,
                    featureUac2(unitId = 4, sourceId = 3, masterControls = 0x0c),
                    outputTerminalUac2(terminalId = 7, sourceId = 4),
                ),
            ),
        )
    }

    @Test
    fun `UAC volume parser rejects branch local controls cycles missing sources and duplicate units`() {
        val branchLocalControl = uac1Configuration(
            inputTerminalUac1(2),
            inputTerminalUac1(3),
            featureUac1(unitId = 5, sourceId = 2),
            mixerUac1(unitId = 6, sourceIds = listOf(5, 3)),
            outputTerminalUac1(terminalId = 7, sourceId = 6),
        )
        assertNull(findAndroidUacPlaybackVolumeControl(branchLocalControl))

        val cycle = uac1Configuration(
            inputTerminalUac1(2),
            mixerUac1(unitId = 4, sourceIds = listOf(5)),
            featureUac1(unitId = 5, sourceId = 4),
            outputTerminalUac1(terminalId = 7, sourceId = 5),
        )
        assertNull(findAndroidUacPlaybackVolumeControl(cycle))

        val missingSource = uac1Configuration(
            featureUac1(unitId = 5, sourceId = 99),
            outputTerminalUac1(terminalId = 7, sourceId = 5),
        )
        assertNull(findAndroidUacPlaybackVolumeControl(missingSource))

        val duplicateUnitId = uac1Configuration(
            inputTerminalUac1(2),
            featureUac1(unitId = 5, sourceId = 2),
            mixerUac1(unitId = 5, sourceIds = listOf(2)),
            outputTerminalUac1(terminalId = 7, sourceId = 5),
        )
        assertNull(findAndroidUacPlaybackVolumeControl(duplicateUnitId))
    }

    @Test
    fun `multiple master controls on a playback graph are ambiguous`() {
        val descriptors = uac2Configuration(
            inputTerminalUac2(2),
            featureUac2(unitId = 4, sourceId = 2, masterControls = 0x0c),
            featureUac2(unitId = 5, sourceId = 4, masterControls = 0x0c),
            outputTerminalUac2(terminalId = 7, sourceId = 5),
        )
        assertNull(findAndroidUacPlaybackVolumeControl(descriptors))
    }

    @Test
    fun `UAC2 master volume requires host programmable descriptor bits`() {
        assertEquals(
            AndroidUacVolumeControl(AndroidUacVersion.Uac2, controlInterfaceNumber = 1, unitId = 5),
            findAndroidUacPlaybackVolumeControl(uac2Descriptors(masterControls = 0x0c)),
        )
        assertNull(findAndroidUacPlaybackVolumeControl(uac2Descriptors(masterControls = 0x04)))
        assertNull(findAndroidUacPlaybackVolumeControl(uac2Descriptors(masterControls = 0x08)))
        assertNull(findAndroidUacPlaybackVolumeControl(uac2Descriptors(masterControls = 0x00)))
        assertEquals(
            AndroidUacVolumeControl(
                AndroidUacVersion.Uac2,
                controlInterfaceNumber = 1,
                unitId = 5,
                hasMasterMuteControl = true,
            ),
            findAndroidUacPlaybackVolumeControl(uac2Descriptors(masterControls = 0x0f)),
        )
    }

    @Test
    fun `parser rejects malformed descriptors and volume controls outside a physical playback path`() {
        assertNull(findAndroidUacPlaybackVolumeControl(byteArrayOf(9, 4, 1)))
        assertNull(findAndroidUacPlaybackVolumeControl(byteArrayOf(1, 4)))
        assertNull(findAndroidUacPlaybackVolumeControl(uac1Descriptors(controlSize = 0)))
        assertNull(
            findAndroidUacPlaybackVolumeControl(
                uac1Descriptors(controlSize = 1, terminalSource = 6),
            ),
        )
        assertNull(
            findAndroidUacPlaybackVolumeControl(
                uac1Descriptors(controlSize = 1, outputTerminalType = 0x0101),
            ),
        )
    }

    @Test
    fun `parser verifies configuration and AudioControl descriptor bounds`() {
        val descriptor = uac1Descriptors(controlSize = 1)
        val truncatedControlBlock = descriptor.copyOf().apply {
            // The AudioControl header's wTotalLength must include every entity descriptor.
            this[23] = 0x10
            this[24] = 0
        }
        assertNull(findAndroidUacPlaybackVolumeControl(truncatedControlBlock))
        assertNull(findAndroidUacPlaybackVolumeControl(descriptor + descriptor))

        val truncatedClassSpecificDescriptor = (descriptor + byteArrayOf(2, 0x24)).apply {
            this[2] = (this[2].toInt() + 2).toByte()
            this[23] = (this[23].toInt() + 2).toByte()
        }
        assertNull(findAndroidUacPlaybackVolumeControl(truncatedClassSpecificDescriptor))
    }

    @Test
    fun `ambiguous feature units fail closed`() {
        val descriptor = uac1Descriptors(controlSize = 1)
        assertNull(findAndroidUacPlaybackVolumeControl(descriptor + descriptor))
    }

    @Test
    fun `UAC1 and UAC2 volume requests encode version specific GET and signed little endian SET`() {
        val control1 = AndroidUacVolumeControl(AndroidUacVersion.Uac1, 4, 5)
        val get1 = androidUacGetCurrentVolumeRequest(control1)
        assertEquals(0xa1, get1.requestType)
        assertEquals(0x81, get1.request)
        assertEquals(0x0200, get1.value)
        assertEquals(0x0504, get1.index)
        assertEquals(2, get1.length)

        val control2 = control1.copy(version = AndroidUacVersion.Uac2)
        val get2 = androidUacGetCurrentVolumeRequest(control2)
        assertEquals(0x01, get2.request)
        assertEquals(0xa1, get2.requestType)

        val set = androidUacSetCurrentVolumeRequest(control1, volumeDb256 = -12 * 256)
        assertEquals(0x21, set.requestType)
        assertEquals(0x01, set.request)
        assertEquals(0x0200, set.value)
        assertEquals(0x0504, set.index)
        assertContentEquals(byteArrayOf(0x00, 0xf4.toByte()), set.data)
        assertFailsWith<IllegalArgumentException> {
            androidUacSetCurrentVolumeRequest(control1, Short.MAX_VALUE + 1)
        }
    }

    @Test
    fun `hardware volume accepts SET only after matching GET_CUR readback`() {
        val transport = FakeTransfer(readBackDb256 = -12 * 256)
        val volume = AndroidUacHardwareVolume(
            AndroidUacVolumeControl(AndroidUacVersion.Uac2, controlInterfaceNumber = 4, unitId = 5),
            transport,
        )

        assertEquals(AndroidUacVolumeValue.Finite(-12 * 256), volume.setAndReadBackDb256(-12 * 256))
        assertEquals(listOf(0x02, 0x02, 0x01, 0x01), transport.requests.map { it.request })
        assertEquals(2, transport.requests[0].length)
        assertEquals(8, transport.requests[1].length)
        assertEquals(0x21, transport.requests[2].requestType)
        assertEquals(0xa1, transport.requests[3].requestType)
    }

    @Test
    fun `hardware volume rejects SET when GET_CUR does not match`() {
        val volume = AndroidUacHardwareVolume(
            AndroidUacVolumeControl(AndroidUacVersion.Uac2, controlInterfaceNumber = 4, unitId = 5),
            FakeTransfer(readBackDb256 = -11 * 256),
        )
        assertFailsWith<IllegalStateException> { volume.setAndReadBackDb256(-12 * 256) }
    }

    @Test
    fun `failed SET_CUR does not report a value`() {
        val transport = FakeTransfer(readBackDb256 = -12 * 256, setResult = -1)
        val volume = AndroidUacHardwareVolume(
            AndroidUacVolumeControl(AndroidUacVersion.Uac1, controlInterfaceNumber = 1, unitId = 5),
            transport,
        )

        assertFailsWith<IllegalStateException> { volume.setAndReadBackDb256(-12 * 256) }
        assertEquals(listOf(0x82, 0x83, 0x84, 0x01), transport.requests.map { it.request })
    }

    @Test
    fun `linear player volume maps to nearest advertised decibel step and zero requires mute`() {
        val oneDbSteps = listOf(AndroidUacVolumeRange(-60 * 256, 0, 256))
        assertEquals(0, androidUacVolumeDb256ForLinearVolume(1f, oneDbSteps))
        assertEquals(-6 * 256, androidUacVolumeDb256ForLinearVolume(0.5f, oneDbSteps))
        assertNull(androidUacVolumeDb256ForLinearVolume(0f, oneDbSteps))
        assertNull(
            androidUacVolumeDb256ForLinearVolume(
                0.0001f,
                listOf(AndroidUacVolumeRange(-20 * 256, 0, 256)),
            ),
        )
    }

    @Test
    fun `UAC mute requests require a descriptor-advertised master mute control and verify readback`() {
        val control = AndroidUacVolumeControl(
            AndroidUacVersion.Uac2,
            controlInterfaceNumber = 4,
            unitId = 5,
            hasMasterMuteControl = true,
        )
        val get = androidUacGetCurrentMuteRequest(control)
        assertEquals(0xa1, get.requestType)
        assertEquals(0x01, get.request)
        assertEquals(0x0100, get.value)
        assertEquals(0x0504, get.index)
        assertEquals(1, get.length)

        val transport = FakeTransfer(readBackDb256 = 0, setResult = 1, readBackMute = true)
        val volume = AndroidUacHardwareVolume(control, transport)
        volume.setMuteAndReadBack(true)
        assertEquals(listOf(0x21, 0xa1), transport.requests.map { it.requestType })
        assertEquals(0x0100, transport.requests[0].value)
        assertContentEquals(byteArrayOf(1), transport.requests[0].data)
        assertFailsWith<IllegalArgumentException> {
            androidUacSetCurrentMuteRequest(control.copy(hasMasterMuteControl = false), true)
        }
    }

    @Test
    fun `UAC volume ranges are queried and device resolution bounds adjustments`() {
        val control = AndroidUacVolumeControl(AndroidUacVersion.Uac1, controlInterfaceNumber = 1, unitId = 5)
        val transport = FakeTransfer(
            readBackDb256 = -6 * 256,
            uac1Range = AndroidUacVolumeRange(-12 * 256, 0, 256),
        )
        val volume = AndroidUacHardwareVolume(control, transport)

        assertEquals(listOf(0x82, 0x83, 0x84), volume.readVolumeRanges().let {
            transport.requests.map(AndroidUsbControlRequest::request)
        })
        assertEquals(
            -5 * 256,
            nextAndroidUacVolumeValue(
                AndroidUacVolumeValue.Finite(-6 * 256),
                volume.readVolumeRanges(),
                AndroidUacVolumeDirection.Up,
            ),
        )
        assertNull(
            nextAndroidUacVolumeValue(
                AndroidUacVolumeValue.Finite(0),
                volume.readVolumeRanges(),
                AndroidUacVolumeDirection.Up,
            ),
        )
    }

    @Test
    fun `UAC2 volume handles multiple advertised subranges and mute sentinel`() {
        val ranges = listOf(
            AndroidUacVolumeRange(-60 * 256, -1 * 256, 256),
            AndroidUacVolumeRange(0, 6 * 256, 128),
        )
        val bytes = uac2RangeBytes(ranges)
        assertEquals(ranges, parseAndroidUac2VolumeRanges(bytes, 2))
        assertEquals(
            0,
            nextAndroidUacVolumeValue(
                AndroidUacVolumeValue.Finite(-256),
                ranges,
                AndroidUacVolumeDirection.Up,
            ),
        )
        assertEquals(
            -60 * 256,
            nextAndroidUacVolumeValue(AndroidUacVolumeValue.Muted, ranges, AndroidUacVolumeDirection.Up),
        )
        assertNull(nextAndroidUacVolumeValue(AndroidUacVolumeValue.Muted, ranges, AndroidUacVolumeDirection.Down))
        assertEquals(
            AndroidUacVolumeValue.Muted,
            AndroidUacHardwareVolume(
                AndroidUacVolumeControl(AndroidUacVersion.Uac2, 1, 5),
                FakeTransfer(readBackDb256 = Short.MIN_VALUE.toInt(), uac2Ranges = ranges),
            ).readCurrentVolume(),
        )
    }

    @Test
    fun `invalid or unaligned range values are rejected before SET_CUR`() {
        assertFailsWith<IllegalArgumentException> {
            AndroidUacVolumeRange(Short.MIN_VALUE.toInt(), 0, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            AndroidUacVolumeRange(-10, 0, 3)
        }
        val transport = FakeTransfer(readBackDb256 = -12 * 256)
        val volume = AndroidUacHardwareVolume(AndroidUacVolumeControl(AndroidUacVersion.Uac2, 1, 5), transport)
        assertFailsWith<IllegalArgumentException> {
            volume.setAndReadBackDb256(Short.MIN_VALUE.toInt(), listOf(AndroidUacVolumeRange(-256, 0, 256)))
        }
        assertFalse(transport.requests.any { it.requestType == 0x21 })
    }

    private fun permissionCallbackMatches(
        requestGeneration: Long = 41L,
        currentGeneration: Long = 41L,
        selectedDeviceId: String? = UAC_TEST_DEVICE,
        callbackDeviceId: String? = UAC_TEST_DEVICE,
        callbackVendorId: Int = UAC_TEST_VENDOR_ID,
        callbackProductId: Int = UAC_TEST_PRODUCT_ID,
        callbackGeneration: Long? = 41L,
    ): Boolean = androidUacPermissionCallbackMatches(
        requestDeviceId = UAC_TEST_DEVICE,
        requestVendorId = UAC_TEST_VENDOR_ID,
        requestProductId = UAC_TEST_PRODUCT_ID,
        requestGeneration = requestGeneration,
        currentGeneration = currentGeneration,
        selectedDeviceId = selectedDeviceId,
        callbackDeviceId = callbackDeviceId,
        callbackVendorId = callbackVendorId,
        callbackProductId = callbackProductId,
        callbackGeneration = callbackGeneration,
    )

    private inner class FakeTransfer(
        private val readBackDb256: Int,
        private val setResult: Int = 2,
        private val readBackMute: Boolean = false,
        private val uac1Range: AndroidUacVolumeRange = AndroidUacVolumeRange(-60 * 256, 0, 256),
        private val uac2Ranges: List<AndroidUacVolumeRange> = listOf(AndroidUacVolumeRange(-60 * 256, 0, 256)),
    ) : AndroidUsbControlTransfer {
        val requests = mutableListOf<AndroidUsbControlRequest>()

        override fun transfer(request: AndroidUsbControlRequest, timeoutMillis: Int): Int {
            requests += request.copy(data = request.data.copyOf())
            if (request.requestType == 0x21) return setResult
            if (request.value ushr 8 == 0x01 && request.length == 1) {
                request.data[0] = if (readBackMute) 1 else 0
                return request.length
            }
            when (request.request) {
                0x82 -> writeSigned16(request.data, uac1Range.minimumDb256)
                0x83 -> writeSigned16(request.data, uac1Range.maximumDb256)
                0x84 -> writeSigned16(request.data, uac1Range.resolutionDb256)
                0x01 -> writeSigned16(request.data, readBackDb256)
                0x02 -> {
                    val bytes = uac2RangeBytes(uac2Ranges)
                    bytes.copyInto(request.data, endIndex = minOf(bytes.size, request.data.size))
                }
            }
            return request.length
        }
    }

    private fun uac1Descriptors(
        controlSize: Int,
        terminalSource: Int = 5,
        outputTerminalType: Int = 0x0302,
    ): ByteArray {
        val feature = featureUac1(unitId = 5, sourceId = 1, controlSize = controlSize)
        val terminal = outputTerminalUac1(7, terminalSource, outputTerminalType)
        return uac1Configuration(inputTerminalUac1(1), feature, terminal)
    }

    private fun uac1Configuration(vararg entities: ByteArray): ByteArray {
        val entityBytes = entities.fold(ByteArray(0), ByteArray::plus)
        val controlTotalLength = 9 + entityBytes.size
        val interfaceDescriptor = byteArrayOf(9, 4, 1, 0, 0, 1, 1, 0, 0)
        val header = byteArrayOf(9, 0x24, 1, 0, 1, controlTotalLength.toByte(), 0, 1, 2)
        return usbConfiguration(interfaceDescriptor + header + entityBytes)
    }

    private fun uac2Descriptors(masterControls: Int): ByteArray {
        val feature = featureUac2(unitId = 5, sourceId = 1, masterControls = masterControls)
        val terminal = outputTerminalUac2(terminalId = 7, sourceId = 5)
        return uac2Configuration(inputTerminalUac2(1), feature, terminal)
    }

    private fun uac2Configuration(vararg entities: ByteArray): ByteArray {
        val entityBytes = entities.fold(ByteArray(0), ByteArray::plus)
        val controlTotalLength = 9 + entityBytes.size
        val interfaceDescriptor = byteArrayOf(9, 4, 1, 0, 0, 1, 1, 0x20, 0)
        val header = byteArrayOf(
            9, 0x24, 1, 0, 2, 0x30,
            controlTotalLength.toByte(), (controlTotalLength ushr 8).toByte(), 0,
        )
        return usbConfiguration(interfaceDescriptor + header + entityBytes)
    }

    private fun inputTerminalUac1(terminalId: Int): ByteArray = byteArrayOf(
        12, 0x24, 0x02, terminalId.toByte(), 0x01, 0x01, 0, 1, 0, 0, 0, 0,
    )

    private fun inputTerminalUac2(terminalId: Int): ByteArray = byteArrayOf(
        17, 0x24, 0x02, terminalId.toByte(), 0x01, 0x01, 0, 1,
        0, 0, 0, 0, 0, 1, 0, 0, 0,
    )

    private fun featureUac1(
        unitId: Int,
        sourceId: Int,
        controlSize: Int = 1,
        hasMasterVolume: Boolean = true,
    ): ByteArray {
        val controls = ByteArray(controlSize).apply {
            if (hasMasterVolume && isNotEmpty()) this[0] = 0x02
        }
        // One mono channel contributes a master and a channel control bitmap.
        val channelControls = ByteArray(controlSize)
        return byteArrayOf((7 + controlSize * 2).toByte(), 0x24, 0x06, unitId.toByte(), sourceId.toByte(),
            controlSize.toByte()) + controls + channelControls + byteArrayOf(0)
    }

    private fun featureUac2(unitId: Int, sourceId: Int, masterControls: Int): ByteArray = byteArrayOf(
        14, 0x24, 0x06, unitId.toByte(), sourceId.toByte(),
        masterControls.toByte(), 0, 0, 0,
        0, 0, 0, 0,
        0,
    )

    private fun mixerUac1(unitId: Int, sourceIds: List<Int>): ByteArray {
        val controlSize = (sourceIds.size * 2 + 7) / 8
        val length = 10 + sourceIds.size + controlSize
        return byteArrayOf(length.toByte(), 0x24, 0x04, unitId.toByte(), sourceIds.size.toByte()) +
            sourceIds.map(Int::toByte).toByteArray() +
            byteArrayOf(2, 3, 0, 0) + ByteArray(controlSize) + byteArrayOf(0)
    }

    private fun mixerUac2(unitId: Int, sourceIds: List<Int>): ByteArray = byteArrayOf(
        (14 + sourceIds.size).toByte(), 0x24, 0x04, unitId.toByte(), sourceIds.size.toByte(),
    ) + sourceIds.map { it.toByte() }.toByteArray() + byteArrayOf(
        2, 3, 0, 0, 0, 0, 0, 0, 0,
    )

    private fun processingUac1(unitId: Int, sourceId: Int): ByteArray = byteArrayOf(
        15, 0x24, 0x07, unitId.toByte(), 0, 0, 1, sourceId.toByte(),
        1, 0, 0, 0, 1, 0, 0,
    )

    private fun extensionUac1(unitId: Int, sourceId: Int): ByteArray = byteArrayOf(
        15, 0x24, 0x08, unitId.toByte(), 0, 0, 1, sourceId.toByte(),
        1, 0, 0, 0, 1, 1, 0,
    )

    private fun processingUac2(unitId: Int, sourceId: Int): ByteArray = byteArrayOf(
        18, 0x24, 0x08, unitId.toByte(), 0, 0, 1, sourceId.toByte(),
        1, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    )

    private fun extensionUac2(unitId: Int, sourceId: Int): ByteArray = byteArrayOf(
        16, 0x24, 0x09, unitId.toByte(), 0, 0, 1, sourceId.toByte(),
        1, 0, 0, 0, 0, 0, 1, 0,
    )

    private fun effectUac2(unitId: Int, sourceId: Int): ByteArray = byteArrayOf(
        20, 0x24, 0x07, unitId.toByte(), 1, 0, sourceId.toByte(),
    ) + ByteArray(13)

    private fun sampleRateConverterUac2(unitId: Int, sourceId: Int): ByteArray = byteArrayOf(
        8, 0x24, 0x0d, unitId.toByte(), sourceId.toByte(), 1, 1, 0,
    )

    private fun selectorUac2(unitId: Int, sourceIds: List<Int>): ByteArray = byteArrayOf(
        (7 + sourceIds.size).toByte(), 0x24, 0x05, unitId.toByte(), sourceIds.size.toByte(),
    ) + sourceIds.map(Int::toByte).toByteArray() + byteArrayOf(0, 0)

    private fun outputTerminalUac1(
        terminalId: Int,
        sourceId: Int,
        terminalType: Int = 0x0302,
    ): ByteArray = byteArrayOf(
        9, 0x24, 0x03, terminalId.toByte(), terminalType.toByte(), (terminalType ushr 8).toByte(),
        0, sourceId.toByte(), 0,
    )

    private fun outputTerminalUac2(terminalId: Int, sourceId: Int): ByteArray = byteArrayOf(
        12, 0x24, 0x03, terminalId.toByte(), 0x02, 0x03, 0, sourceId.toByte(), 1, 0, 0, 0,
    )

    private fun usbConfiguration(body: ByteArray): ByteArray {
        val totalLength = 9 + body.size
        return byteArrayOf(
            9, 2, totalLength.toByte(), (totalLength ushr 8).toByte(), 1, 1, 0, 0x80.toByte(), 50,
        ) + body
    }

    private fun uac2RangeBytes(ranges: List<AndroidUacVolumeRange>): ByteArray {
        val result = ByteArray(2 + ranges.size * 6)
        result[0] = ranges.size.toByte()
        ranges.forEachIndexed { index, range ->
            val offset = 2 + index * 6
            writeSigned16(result, range.minimumDb256, offset)
            writeSigned16(result, range.maximumDb256, offset + 2)
            writeSigned16(result, range.resolutionDb256, offset + 4)
        }
        return result
    }

    private fun writeSigned16(destination: ByteArray, value: Int, offset: Int = 0) {
        destination[offset] = value.toByte()
        destination[offset + 1] = (value ushr 8).toByte()
    }
}

private const val UAC_TEST_DEVICE = "/dev/bus/usb/001/002"
private const val UAC_TEST_VENDOR_ID = 0x1234
private const val UAC_TEST_PRODUCT_ID = 0xabcd
