package dev.naominet.lazer

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidUac2PlaybackSessionTest {
    @Test
    fun `configures descriptors clock and altsetting then remains explicitly not streaming`() {
        val gateway = FakeGateway(currentClockHz = 44_100)
        val session = newSession(gateway)

        assertEquals(
            AndroidUac2PlaybackSessionState.ConfiguredNotStreaming,
            session.openAndConfigure(),
        )
        assertSame(gateway.connection, session.requireConfiguredConnection())
        assertEquals(96_000, gateway.currentClockHz)
        assertEquals(1, gateway.setClockRequests.size)
        assertTrue(gateway.operations.indexOf("validate-endpoints") < gateway.operations.indexOf("get-configuration"))
        assertTrue(gateway.operations.indexOf("get-configuration") < gateway.operations.indexOf("configure:7"))
        assertTrue(gateway.operations.indexOf("configure:7") < gateway.operations.indexOf("claim:1:1:0"))
        assertTrue(gateway.operations.indexOf("claim:1:1:0") < gateway.operations.indexOf("claim:2:2:0"))
        assertTrue(gateway.operations.indexOf("claim:2:2:0") < gateway.operations.indexOf("range-header"))
        assertTrue(gateway.operations.indexOf("range-full") < gateway.operations.indexOf("clock-current"))
        assertTrue(gateway.operations.indexOf("clock-set:96000") < gateway.operations.indexOf("alt:2:1"))
        assertTrue("playing" !in AndroidUac2PlaybackSessionState.entries.map { it.name.lowercase() })

        session.close()
        val closeCount = gateway.operations.count { it == "close" }
        session.close()

        assertEquals(AndroidUac2PlaybackSessionState.Closed, session.state)
        assertEquals(44_100, gateway.currentClockHz)
        assertEquals(1, closeCount)
        assertEquals(closeCount, gateway.operations.count { it == "close" })
        assertTrue(gateway.operations.contains("alt:2:0"))
        assertTrue(gateway.operations.indexOf("alt:2:0") < gateway.operations.indexOf("release:2"))
        assertTrue(gateway.operations.indexOf("alt:2:0") < gateway.operations.indexOf("clock-set:44100"))
        assertTrue(gateway.operations.indexOf("clock-set:44100") < gateway.operations.indexOf("restore-configuration:3"))
        assertTrue(gateway.operations.indexOf("restore-configuration:3") < gateway.operations.indexOf("release:2"))
        assertTrue(gateway.operations.indexOf("release:2") < gateway.operations.indexOf("release:1"))
        assertEquals("close", gateway.operations.last())
    }

    @Test
    fun `verified Feature Unit uses the retained playback connection for volume and mute`() {
        val gateway = FakeGateway(currentClockHz = 96_000)
        val volumeControl = AndroidUacVolumeControl(
            version = AndroidUacVersion.Uac2,
            controlInterfaceNumber = 1,
            unitId = 8,
            hasMasterMuteControl = true,
        )
        val session = newSession(gateway, volumeControl = volumeControl)
        session.openAndConfigure()

        assertTrue(session.supportsHardwareVolume)
        assertEquals(listOf("volume-range-header", "volume-range-full", "volume-current", "mute-current"),
            gateway.operations.filter { it.startsWith("volume-") || it.startsWith("mute-") })

        session.setHardwareVolume(0.5f)
        assertEquals(-6 * 256, gateway.currentVolumeDb256)
        session.setHardwareVolume(0f)
        assertTrue(gateway.currentMute)
        session.setHardwareVolume(1f)
        assertEquals(0, gateway.currentVolumeDb256)
        assertFalse(gateway.currentMute)
        assertTrue(gateway.operations.filter { it.startsWith("volume-") || it.startsWith("mute-") }
            .all { it != "volume-digital-fallback" })

        session.close()
        assertEquals(AndroidUac2PlaybackSessionState.Closed, session.state)
    }

    @Test
    fun `Feature Unit without Mute rejects zero instead of substituting minimum attenuation`() {
        val gateway = FakeGateway(currentClockHz = 96_000)
        val session = newSession(
            gateway,
            volumeControl = AndroidUacVolumeControl(AndroidUacVersion.Uac2, 1, 8),
        )
        session.openAndConfigure()

        assertTrue(session.supportsHardwareVolume)
        assertFailsWith<IllegalStateException> { session.setHardwareVolume(0f) }
        assertTrue(gateway.operations.none { it.startsWith("mute-") })
        session.close()
    }

    @Test
    fun `detach closes the shared USB session and forbids later hardware volume transfers`() {
        val gateway = FakeGateway(currentClockHz = 96_000)
        val session = newSession(
            gateway,
            volumeControl = AndroidUacVolumeControl(
                AndroidUacVersion.Uac2,
                controlInterfaceNumber = 1,
                unitId = 8,
                hasMasterMuteControl = true,
            ),
        )
        session.openAndConfigure()
        assertTrue(session.supportsHardwareVolume)
        val volumeRequestCount = gateway.operations.count { it.startsWith("volume-") || it.startsWith("mute-") }

        session.onDeviceDetached()

        assertFalse(session.supportsHardwareVolume)
        assertFailsWith<IllegalStateException> { session.setHardwareVolume(0.5f) }
        assertEquals(
            volumeRequestCount,
            gateway.operations.count { it.startsWith("volume-") || it.startsWith("mute-") },
        )
        assertEquals(AndroidUac2PlaybackSessionState.Detached, session.state)
    }

    @Test
    fun `read only clock already at plan rate configures without SET_CUR`() {
        val gateway = FakeGateway(currentClockHz = 96_000)
        val session = newSession(
            gateway = gateway,
            access = AndroidUac2ClockFrequencyAccess.ReadOnly,
        )

        session.openAndConfigure()

        assertEquals(AndroidUac2PlaybackSessionState.ConfiguredNotStreaming, session.state)
        assertTrue(gateway.setClockRequests.isEmpty())
        session.close()
    }

    @Test
    fun `read only clock at a different rate fails closed without SET_CUR`() {
        val gateway = FakeGateway(currentClockHz = 44_100)
        val session = newSession(gateway, AndroidUac2ClockFrequencyAccess.ReadOnly)

        assertFailsWith<IllegalStateException> { session.openAndConfigure() }

        assertEquals(AndroidUac2PlaybackSessionState.Failed, session.state)
        assertTrue(gateway.setClockRequests.isEmpty())
        assertEquals(1, gateway.operations.count { it == "close" })
        assertTrue(gateway.operations.contains("release:2"))
        assertTrue(gateway.operations.contains("release:1"))
    }

    @Test
    fun `selector keeps current pin when its source advertises the requested rate`() {
        val gateway = FakeGateway(currentClockHz = 44_100).apply {
            currentClockSelectorPin = 2
            clockRangesBySourceId = selectorRanges()
            currentClockBySourceId[4] = 96_000
        }
        val session = newSession(gateway, plan = selectorPlan())

        session.openAndConfigure()

        assertEquals(AndroidUac2PlaybackSessionState.ConfiguredNotStreaming, session.state)
        assertEquals(2, gateway.currentClockSelectorPin)
        assertTrue(gateway.setSelectorRequests.isEmpty())
        assertTrue(gateway.operations.contains("range-full:4"))
        session.close()
        assertTrue(gateway.setSelectorRequests.isEmpty())
    }

    @Test
    fun `selector switches only to advertised source and restores source rate then original pin`() {
        val gateway = FakeGateway(currentClockHz = 44_100).apply {
            clockRangesBySourceId = selectorRanges()
            currentClockBySourceId[4] = 44_100
        }
        val session = newSession(gateway, plan = selectorPlan())

        session.openAndConfigure()
        assertEquals(2, gateway.currentClockSelectorPin)
        assertEquals(96_000, gateway.currentClockBySourceId[4])
        session.close()

        assertEquals(listOf(2, 1), gateway.setSelectorRequests)
        assertEquals(1, gateway.currentClockSelectorPin)
        assertEquals(44_100, gateway.currentClockBySourceId[4])
        assertTrue(gateway.operations.indexOf("clock-set:4:44100") < gateway.operations.indexOf("selector-set:1"))
        assertTrue(gateway.operations.indexOf("selector-set:1") < gateway.operations.indexOf("restore-configuration:3"))
    }

    @Test
    fun `selector and selected source are restored after later configuration failure`() {
        val gateway = FakeGateway(currentClockHz = 44_100).apply {
            clockRangesBySourceId = selectorRanges()
            currentClockBySourceId[4] = 44_100
            setAlternateSucceeds = false
        }
        val session = newSession(gateway, plan = selectorPlan())

        assertFailsWith<IllegalStateException> { session.openAndConfigure() }

        assertEquals(AndroidUac2PlaybackSessionState.Failed, session.state)
        assertEquals(listOf(2, 1), gateway.setSelectorRequests)
        assertEquals(1, gateway.currentClockSelectorPin)
        assertEquals(44_100, gateway.currentClockBySourceId[4])
    }

    @Test
    fun `read only selector cannot switch away from a source without requested rate`() {
        val gateway = FakeGateway(currentClockHz = 44_100).apply {
            clockRangesBySourceId = selectorRanges()
        }
        val session = newSession(
            gateway,
            plan = selectorPlan(AndroidUac2ClockFrequencyAccess.ReadOnly),
        )

        assertFailsWith<IllegalStateException> { session.openAndConfigure() }

        assertEquals(1, gateway.currentClockSelectorPin)
        assertTrue(gateway.setSelectorRequests.isEmpty())
        assertTrue(gateway.setClockRequests.isEmpty())
    }

    @Test
    fun `detach does not send selector or clock restoration controls`() {
        val gateway = FakeGateway(currentClockHz = 44_100).apply {
            clockRangesBySourceId = selectorRanges()
        }
        val session = newSession(gateway, plan = selectorPlan())
        session.openAndConfigure()
        val selectorWritesBeforeDetach = gateway.setSelectorRequests.size
        val clockWritesBeforeDetach = gateway.operations.count { it.startsWith("clock-set:") }

        session.onDeviceDetached()

        assertEquals(2, gateway.currentClockSelectorPin)
        assertEquals(selectorWritesBeforeDetach, gateway.setSelectorRequests.size)
        assertEquals(clockWritesBeforeDetach, gateway.operations.count { it.startsWith("clock-set:") })
        assertEquals(AndroidUac2PlaybackSessionState.Detached, session.state)
    }

    @Test
    fun `permission and open failures do not claim interfaces`() {
        val deniedGateway = FakeGateway(currentClockHz = 44_100).apply { permission = false }
        val deniedSession = newSession(deniedGateway)

        assertFailsWith<IllegalStateException> { deniedSession.openAndConfigure() }
        assertEquals(AndroidUac2PlaybackSessionState.Failed, deniedSession.state)
        assertTrue(deniedGateway.operations.none { it.startsWith("open") || it.startsWith("claim") })

        val openGateway = FakeGateway(currentClockHz = 44_100).apply { openSucceeds = false }
        val openSession = newSession(openGateway)
        assertFailsWith<java.io.IOException> { openSession.openAndConfigure() }
        assertTrue(openGateway.operations.none { it.startsWith("claim") })
    }

    @Test
    fun `configuration failure closes without claiming interfaces`() {
        val gateway = FakeGateway(currentClockHz = 44_100).apply {
            configurationSucceeds = false
            failedConfigurationStillChangesDevice = true
        }
        val session = newSession(gateway)

        assertFailsWith<IllegalStateException> { session.openAndConfigure() }

        assertEquals(AndroidUac2PlaybackSessionState.Failed, session.state)
        assertTrue(gateway.operations.contains("configure:7"))
        assertTrue(gateway.operations.none { it.startsWith("claim") })
        assertEquals(3, gateway.currentConfigurationValue)
        assertTrue(gateway.operations.indexOf("restore-configuration:3") < gateway.operations.indexOf("close"))
        assertEquals(1, gateway.operations.count { it == "close" })
    }

    @Test
    fun `GET_CONFIGURATION failure or short response fails before changing configuration`() {
        for (result in listOf(-1, 0)) {
            val gateway = FakeGateway(currentClockHz = 44_100).apply { configurationReadResult = result }
            val session = newSession(gateway)

            assertFailsWith<IllegalStateException> { session.openAndConfigure() }

            assertEquals(AndroidUac2PlaybackSessionState.Failed, session.state)
            assertTrue(gateway.operations.contains("get-configuration"))
            assertTrue(gateway.operations.none { it.startsWith("configure:") || it.startsWith("claim:") })
            assertEquals(3, gateway.currentConfigurationValue)
            assertEquals("close", gateway.operations.last())
        }
    }

    @Test
    fun `restores an originally unconfigured device with standard SET_CONFIGURATION zero`() {
        val gateway = FakeGateway(currentClockHz = 96_000).apply { currentConfigurationValue = 0 }
        val session = newSession(gateway, AndroidUac2ClockFrequencyAccess.ReadOnly)

        session.openAndConfigure()
        session.close()

        assertEquals(0, gateway.currentConfigurationValue)
        assertTrue(gateway.operations.contains("restore-configuration:0"))
        assertTrue(gateway.operations.indexOf("restore-configuration:0") < gateway.operations.indexOf("release:2"))
    }

    @Test
    fun `configuration restore failure is retained and still releases claims before close`() {
        val gateway = FakeGateway(currentClockHz = 96_000).apply { configurationRestoreSucceeds = false }
        val session = newSession(gateway, AndroidUac2ClockFrequencyAccess.ReadOnly)
        session.openAndConfigure()

        session.close()

        assertEquals(7, gateway.currentConfigurationValue)
        assertTrue(session.cleanupFailure is IllegalStateException)
        assertTrue(gateway.operations.indexOf("restore-configuration:3") < gateway.operations.indexOf("release:2"))
        assertEquals("close", gateway.operations.last())
    }

    @Test
    fun `endpoint plan comparison rejects address direction type attributes packet and interval drift`() {
        val plan = testPlan()
        val expected = usbEndpoint(address = 0x01, attributes = 0x05, maxPacketSize = 384, interval = 1)
        assertTrue(androidUac2EndpointSetMatchesPlan(listOf(expected), plan))
        val highSpeedTwoTransactionPlan = plan.copy(
            dataEndpoint = plan.dataEndpoint.copy(transactionsPerMicroframe = 2),
        )
        assertTrue(
            androidUac2EndpointSetMatchesPlan(
                listOf(usbEndpoint(address = 0x01, attributes = 0x05, maxPacketSize = 384 or (1 shl 11), interval = 1)),
                highSpeedTwoTransactionPlan,
            ),
        )

        val mismatches = listOf(
            usbEndpoint(address = 0x02, attributes = 0x05, maxPacketSize = 384, interval = 1),
            usbEndpoint(address = 0x81, attributes = 0x05, maxPacketSize = 384, interval = 1),
            usbEndpoint(address = 0x01, attributes = 0x01, maxPacketSize = 384, interval = 1),
            usbEndpoint(address = 0x01, attributes = 0x15, maxPacketSize = 384, interval = 1),
            usbEndpoint(address = 0x01, attributes = 0x02, maxPacketSize = 384, interval = 1),
            usbEndpoint(address = 0x01, attributes = 0x05, maxPacketSize = 385, interval = 1),
            usbEndpoint(address = 0x01, attributes = 0x05, maxPacketSize = 384, interval = 2),
            usbEndpoint(address = 0x01, attributes = 0x05, maxPacketSize = 384 or (1 shl 11), interval = 1),
        )
        mismatches.forEach { assertTrue(!androidUac2EndpointSetMatchesPlan(listOf(it), plan)) }

        val feedbackPlan = plan.copy(
            feedbackEndpoint = AndroidUac2IsochronousEndpoint(
                address = 0x81,
                synchronizationType = 0,
                usageType = 1,
                maximumPacketSizeBytes = 4,
                transactionsPerMicroframe = 1,
                interval = 1,
            ),
        )
        assertTrue(
            androidUac2EndpointSetMatchesPlan(
                listOf(
                    expected,
                    usbEndpoint(address = 0x81, attributes = 0x11, maxPacketSize = 4, interval = 1),
                ),
                feedbackPlan,
            ),
        )
        assertTrue(!androidUac2EndpointSetMatchesPlan(listOf(expected), feedbackPlan))
    }

    @Test
    fun `endpoint mismatch fails before GET_CONFIGURATION configuration or interface claim`() {
        val gateway = FakeGateway(currentClockHz = 44_100).apply {
            streamEndpoints = listOf(usbEndpoint(address = 0x03, attributes = 0x05, maxPacketSize = 384, interval = 1))
        }
        val session = newSession(gateway)

        assertFailsWith<IllegalStateException> { session.openAndConfigure() }

        assertTrue(gateway.operations.contains("validate-endpoints"))
        assertTrue(gateway.operations.none {
            it == "get-configuration" || it.startsWith("configure:") || it.startsWith("claim:")
        })
        assertEquals("close", gateway.operations.last())
    }

    @Test
    fun `claim failure releases only the interface already claimed`() {
        val gateway = FakeGateway(currentClockHz = 44_100).apply { failClaimFor = 2 }
        val session = newSession(gateway)

        assertFailsWith<IllegalStateException> { session.openAndConfigure() }

        assertTrue(gateway.operations.contains("release:1"))
        assertTrue(gateway.operations.none { it == "release:2" })
        assertEquals(1, gateway.operations.count { it == "close" })
    }

    @Test
    fun `clock readback mismatch restores prior rate and rolls back interface claims`() {
        val gateway = FakeGateway(currentClockHz = 44_100).apply { rejectClockRate = 96_000 }
        val session = newSession(gateway)

        assertFailsWith<IllegalStateException> { session.openAndConfigure() }

        assertEquals(AndroidUac2PlaybackSessionState.Failed, session.state)
        assertEquals(44_100, gateway.currentClockHz)
        assertEquals(listOf(96_000L, 44_100L), gateway.setClockRequests)
        assertTrue(gateway.operations.none { it.startsWith("alt:2:1") })
        assertTrue(gateway.operations.contains("release:2"))
        assertTrue(gateway.operations.contains("release:1"))
        assertEquals(1, gateway.operations.count { it == "close" })
    }

    @Test
    fun `altsetting failure restores clock and returns interface to zero`() {
        val gateway = FakeGateway(currentClockHz = 44_100).apply { setAlternateSucceeds = false }
        val session = newSession(gateway)

        assertFailsWith<IllegalStateException> { session.openAndConfigure() }

        assertEquals(AndroidUac2PlaybackSessionState.Failed, session.state)
        assertEquals(44_100, gateway.currentClockHz)
        assertEquals(listOf(96_000L, 44_100L), gateway.setClockRequests)
        assertTrue(gateway.operations.contains("alt:2:1"))
        assertTrue(gateway.operations.contains("alt:2:0"))
        assertTrue(gateway.operations.indexOf("alt:2:0") < gateway.operations.indexOf("release:2"))
        assertEquals(1, gateway.operations.count { it == "close" })
    }

    @Test
    fun `detach closes retained connection without issuing more device controls`() {
        val gateway = FakeGateway(currentClockHz = 44_100)
        val session = newSession(gateway)
        session.openAndConfigure()
        val controlsBeforeDetach = gateway.operations.count { it.startsWith("range-") || it.startsWith("clock-") }

        session.onDeviceDetached()
        val operationsAfterDetach = gateway.operations.toList()
        session.onDeviceDetached()
        session.close()

        assertEquals(AndroidUac2PlaybackSessionState.Detached, session.state)
        assertEquals(controlsBeforeDetach, operationsAfterDetach.count {
            it.startsWith("range-") || it.startsWith("clock-")
        })
        assertTrue(operationsAfterDetach.none { it == "alt:2:0" })
        assertEquals(1, gateway.operations.count { it == "close" })
    }

    @Test
    fun `closing before open is idempotent and forbids later open`() {
        val gateway = FakeGateway(currentClockHz = 44_100)
        val session = newSession(gateway)

        session.close()
        session.close()

        assertEquals(AndroidUac2PlaybackSessionState.Closed, session.state)
        assertTrue(gateway.operations.isEmpty())
        assertFailsWith<IllegalStateException> { session.openAndConfigure() }
    }

    private fun newSession(
        gateway: FakeGateway,
        access: AndroidUac2ClockFrequencyAccess = AndroidUac2ClockFrequencyAccess.HostProgrammable,
        plan: AndroidUac2PlaybackStreamPlan = testPlan(access),
        volumeControl: AndroidUacVolumeControl? = null,
    ) = AndroidUac2PlaybackSession(
        device = ReflectionHelpers.newInstance(UsbDevice::class.java),
        plan = plan,
        gateway = gateway,
        volumeControl = volumeControl,
    )

    private fun selectorPlan(
        selectionAccess: AndroidUac2ClockFrequencyAccess = AndroidUac2ClockFrequencyAccess.HostProgrammable,
    ) = testPlan().copy(
        clockSelector = AndroidUac2ClockSelector(1, 6, selectionAccess),
        clockSourceCandidates = listOf(
            AndroidUac2ClockSelectorCandidate(
                1,
                AndroidUac2ClockSource(1, 3, AndroidUac2ClockFrequencyAccess.HostProgrammable),
            ),
            AndroidUac2ClockSelectorCandidate(
                2,
                AndroidUac2ClockSource(1, 4, AndroidUac2ClockFrequencyAccess.HostProgrammable),
            ),
        ),
    )

    private fun selectorRanges() = mapOf(
        3 to listOf(Triple(44_100L, 44_100L, 0L)),
        4 to listOf(Triple(44_100L, 44_100L, 0L), Triple(96_000L, 96_000L, 0L)),
    )

    private fun testPlan(
        access: AndroidUac2ClockFrequencyAccess = AndroidUac2ClockFrequencyAccess.HostProgrammable,
    ) = AndroidUac2PlaybackStreamPlan(
            configurationValue = 7,
            controlInterfaceNumber = 1,
            interfaceNumber = 2,
            alternateSetting = 1,
            clockSourceId = 3,
            clockFrequencyAccess = access,
            sampleRateHz = 96_000,
            channelCount = 2,
            channelConfig = 3,
            subslotSizeBytes = 4,
            validBitResolution = 24,
            dataEndpoint = AndroidUac2IsochronousEndpoint(
                address = 0x01,
                synchronizationType = 1,
                usageType = 0,
                maximumPacketSizeBytes = 384,
                transactionsPerMicroframe = 1,
                interval = 1,
            ),
            feedbackEndpoint = null,
        )

    private fun usbEndpoint(address: Int, attributes: Int, maxPacketSize: Int, interval: Int): UsbEndpoint =
        ReflectionHelpers.callConstructor(
            UsbEndpoint::class.java,
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, address),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, attributes),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, maxPacketSize),
            ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, interval),
        )

    private class FakeGateway(
        currentClockHz: Long,
    ) : AndroidUac2PlaybackSessionGateway {
        val connection: UsbDeviceConnection = ReflectionHelpers.newInstance(UsbDeviceConnection::class.java)
        val operations = mutableListOf<String>()
        val setClockRequests = mutableListOf<Long>()
        val setSelectorRequests = mutableListOf<Int>()
        val currentClockBySourceId = mutableMapOf<Int, Long>()
        var clockRangesBySourceId: Map<Int, List<Triple<Long, Long, Long>>> = emptyMap()
        var currentClockSelectorPin = 1
        var permission = true
        var openSucceeds = true
        var configurationSucceeds = true
        var failedConfigurationStillChangesDevice = false
        var configurationReadResult = 1
        var configurationRestoreSucceeds = true
        var setAlternateSucceeds = true
        var failClaimFor: Int? = null
        var rejectClockRate: Long? = null
        var currentConfigurationValue = 3
        var currentVolumeDb256 = 0
        var currentMute = false
        var currentClockHz = currentClockHz
            private set

        override fun hasPermission(device: UsbDevice): Boolean {
            operations += "permission"
            return permission
        }

        override fun openDevice(device: UsbDevice): UsbDeviceConnection? {
            operations += "open"
            return connection.takeIf { openSucceeds }
        }

        override fun setConfiguration(
            connection: UsbDeviceConnection,
            device: UsbDevice,
            configurationValue: Int,
        ): Boolean {
            operations += "configure:$configurationValue"
            if (configurationSucceeds || failedConfigurationStillChangesDevice) {
                currentConfigurationValue = configurationValue
            }
            return configurationSucceeds
        }

        override fun validateStreamEndpoints(
            device: UsbDevice,
            plan: AndroidUac2PlaybackStreamPlan,
        ): Boolean {
            operations += "validate-endpoints"
            return androidUac2EndpointSetMatchesPlan(streamEndpoints, plan)
        }

        var streamEndpoints: List<UsbEndpoint> = listOf(
            ReflectionHelpers.callConstructor(
                UsbEndpoint::class.java,
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0x01),
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 0x05),
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 384),
                ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType!!, 1),
            ),
        )

        override fun claimInterface(
            connection: UsbDeviceConnection,
            device: UsbDevice,
            configurationValue: Int,
            interfaceNumber: Int,
            interfaceSubclass: Int,
            alternateSetting: Int,
        ): Boolean {
            operations += "claim:$interfaceNumber:$interfaceSubclass:$alternateSetting"
            return interfaceNumber != failClaimFor
        }

        override fun transfer(connection: UsbDeviceConnection, request: AndroidUsbControlRequest): Int {
            return when {
                request.requestType == 0x80 && request.request == 0x08 -> {
                    check(request.value == 0 && request.index == 0 && request.length == 1 && request.data.size == 1)
                    operations += "get-configuration"
                    if (configurationReadResult == 1) request.data[0] = currentConfigurationValue.toByte()
                    configurationReadResult
                }

                request.requestType == 0x00 && request.request == 0x09 -> {
                    check(request.index == 0 && request.length == 0 && request.data.isEmpty())
                    val value = request.value
                    operations += "restore-configuration:$value"
                    if (configurationRestoreSucceeds) currentConfigurationValue = value
                    if (configurationRestoreSucceeds) 0 else -1
                }

                request.requestType == 0xa1 && request.request == 0x02 &&
                    request.index ushr 8 == 8 && request.value ushr 8 == 0x02 -> {
                    if (request.length == 2) {
                        operations += "volume-range-header"
                        writeUnsigned(request.data, 0, 1, byteCount = 2)
                    } else {
                        operations += "volume-range-full"
                        writeUnsigned(request.data, 0, 1, byteCount = 2)
                        writeSigned16(request.data, 2, -60 * 256)
                        writeSigned16(request.data, 4, 0)
                        writeSigned16(request.data, 6, 256)
                    }
                    request.length
                }

                request.requestType == 0xa1 && request.request == 0x01 &&
                    request.index ushr 8 == 8 && request.value ushr 8 == 0x02 -> {
                    operations += "volume-current"
                    writeSigned16(request.data, 0, currentVolumeDb256)
                    request.length
                }

                request.requestType == 0xa1 && request.request == 0x01 && request.length == 1 &&
                    request.index ushr 8 == 8 && request.value ushr 8 == 0x01 -> {
                    operations += "mute-current"
                    request.data[0] = if (currentMute) 1 else 0
                    request.length
                }

                request.requestType == 0x21 && request.request == 0x01 &&
                    request.index ushr 8 == 8 && request.value ushr 8 == 0x02 -> {
                    currentVolumeDb256 = readSigned16(request.data, 0)
                    operations += "volume-set:$currentVolumeDb256"
                    request.length
                }

                request.requestType == 0x21 && request.request == 0x01 && request.length == 1 &&
                    request.index ushr 8 == 8 && request.value ushr 8 == 0x01 -> {
                    currentMute = request.data[0].toInt() == 1
                    operations += "mute-set:$currentMute"
                    request.length
                }

                request.requestType == 0xa1 && request.request == 0x01 && request.length == 1 &&
                    request.index ushr 8 == 6 -> {
                    operations += "selector-current"
                    request.data[0] = currentClockSelectorPin.toByte()
                    request.length
                }

                request.requestType == 0x21 && request.request == 0x01 && request.length == 1 &&
                    request.index ushr 8 == 6 -> {
                    val pin = request.data[0].toInt() and 0xff
                    operations += "selector-set:$pin"
                    setSelectorRequests += pin
                    currentClockSelectorPin = pin
                    request.length
                }

                request.requestType == 0xa1 && request.request == 0x02 && request.length == 2 -> {
                    val sourceId = request.index ushr 8
                    operations += if (sourceId == 3) "range-header" else "range-header:$sourceId"
                    val count = clockRangesBySourceId[sourceId]?.size ?: 1
                    writeUnsigned(request.data, 0, count.toLong(), byteCount = 2)
                    request.length
                }

                request.requestType == 0xa1 && request.request == 0x02 -> {
                    val sourceId = request.index ushr 8
                    operations += if (sourceId == 3) "range-full" else "range-full:$sourceId"
                    val ranges = clockRangesBySourceId[sourceId] ?: listOf(Triple(44_100L, 192_000L, 1L))
                    writeUnsigned(request.data, 0, ranges.size.toLong(), byteCount = 2)
                    ranges.forEachIndexed { index, (minimum, maximum, resolution) ->
                        val offset = 2 + index * 12
                        writeUnsigned(request.data, offset, minimum)
                        writeUnsigned(request.data, offset + 4, maximum)
                        writeUnsigned(request.data, offset + 8, resolution)
                    }
                    request.length
                }

                request.requestType == 0xa1 && request.request == 0x01 -> {
                    val sourceId = request.index ushr 8
                    operations += if (sourceId == 3) "clock-current" else "clock-current:$sourceId"
                    writeUnsigned(request.data, 0, currentClockBySourceId[sourceId] ?: currentClockHz)
                    request.length
                }

                request.requestType == 0x21 && request.request == 0x01 -> {
                    val sourceId = request.index ushr 8
                    val requestedHz = readUnsigned(request.data, 0)
                    operations += if (sourceId == 3) "clock-set:$requestedHz" else "clock-set:$sourceId:$requestedHz"
                    setClockRequests += requestedHz
                    val acceptedHz = if (requestedHz == rejectClockRate) 48_000 else requestedHz
                    if (sourceId == 3) currentClockHz = acceptedHz else currentClockBySourceId[sourceId] = acceptedHz
                    request.length
                }

                else -> error("Unexpected USB control transfer: $request")
            }
        }

        override fun setInterface(
            connection: UsbDeviceConnection,
            device: UsbDevice,
            configurationValue: Int,
            interfaceNumber: Int,
            interfaceSubclass: Int,
            alternateSetting: Int,
        ): Boolean {
            operations += "alt:$interfaceNumber:$alternateSetting"
            return alternateSetting == 0 || setAlternateSucceeds
        }

        override fun releaseInterface(
            connection: UsbDeviceConnection,
            device: UsbDevice,
            configurationValue: Int,
            interfaceNumber: Int,
            interfaceSubclass: Int,
        ): Boolean {
            operations += "release:$interfaceNumber"
            return true
        }

        override fun closeConnection(connection: UsbDeviceConnection) {
            operations += "close"
        }

        private fun writeUnsigned(data: ByteArray, offset: Int, value: Long, byteCount: Int = 4) {
            repeat(byteCount) { index -> data[offset + index] = (value ushr (index * 8)).toByte() }
        }

        private fun writeSigned16(data: ByteArray, offset: Int, value: Int) {
            data[offset] = value.toByte()
            data[offset + 1] = (value ushr 8).toByte()
        }

        private fun readSigned16(data: ByteArray, offset: Int): Int =
            ((data[offset + 1].toInt() and 0xff) shl 8 or (data[offset].toInt() and 0xff)).toShort().toInt()

        private fun readUnsigned(data: ByteArray, offset: Int): Long =
            (0 until 4).fold(0L) { result, index ->
                result or ((data[offset + index].toLong() and 0xff) shl (index * 8))
            }
    }
}
