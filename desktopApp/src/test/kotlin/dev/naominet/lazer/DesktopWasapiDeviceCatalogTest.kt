package dev.naominet.lazer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test

class DesktopWasapiDeviceCatalogTest {
    @Test
    fun `saved identity resolves to its current endpoint ID`() {
        val device = DesktopWasapiDevice(
            endpointId = "{current-endpoint-id}",
            identityKey = "{stable-id}",
            friendlyName = "USB DAC",
            endpointState = WASAPI_DEVICE_STATE_ACTIVE,
            defaultRoleMask = 0,
            stableIdentity = true,
        )

        assertEquals(
            DesktopWasapiOutputSelection(endpointId = device.endpointId, unavailable = false),
            resolveDesktopWasapiOutputSelection(device.identityKey, listOf(device)),
        )
    }

    @Test
    fun `a missing saved identity never silently selects the default device`() {
        val otherDevice = DesktopWasapiDevice(
            endpointId = "{default-endpoint}",
            identityKey = "{different-stable-id}",
            friendlyName = "Default output",
            endpointState = WASAPI_DEVICE_STATE_ACTIVE,
            defaultRoleMask = 7,
            stableIdentity = true,
        )

        assertEquals(
            DesktopWasapiOutputSelection(endpointId = null, unavailable = true),
            resolveDesktopWasapiOutputSelection("{removed-dac}", listOf(otherDevice)),
        )
        assertEquals(
            DesktopWasapiOutputSelection(endpointId = null, unavailable = false),
            resolveDesktopWasapiOutputSelection(null, listOf(otherDevice)),
        )
    }

    @Test
    fun `hardware endpoint volume flag is used only when windows query succeeded`() {
        val supported = testDevice(
            endpointVolumeQueryHresult = 0,
            endpointVolumeHardwareSupportFlags = WASAPI_ENDPOINT_HARDWARE_SUPPORT_VOLUME,
        )
        val unsupported = testDevice(endpointVolumeQueryHresult = 0)
        val unknown = testDevice(
            endpointVolumeQueryHresult = -1,
            endpointVolumeHardwareSupportFlags = WASAPI_ENDPOINT_HARDWARE_SUPPORT_VOLUME,
        )

        assertTrue(supported.reportsHardwareEndpointVolume)
        assertFalse(unsupported.reportsHardwareEndpointVolume)
        assertFalse(unknown.endpointVolumeQuerySucceeded)
        assertFalse(unknown.reportsHardwareEndpointVolume)
    }

    @Test
    fun `device info jna layout matches append-only abi v4`() {
        val info = LazerAudioDeviceInfo()

        assertEquals(
            listOf(
                "structSize", "endpointState", "defaultRoleMask", "identityKind",
                "endpointVolumeQueryHresult", "endpointVolumeHardwareSupportFlags",
            ),
            LazerAudioDeviceInfo::class.java
                .getAnnotation(com.sun.jna.Structure.FieldOrder::class.java).value.toList(),
        )
        assertEquals(24, info.size())
    }

    @Test
    fun `optional native catalog returns usable endpoint identities`() {
        assumeTrue("WASAPI catalog test requires Windows", System.getProperty("os.name").startsWith("Windows"))
        assumeTrue("native audio library is optional", LazerAudioLoader.isAvailable)

        val devices = try {
            DesktopWasapiDeviceCatalog.enumerate()
        } catch (error: DesktopWasapiCatalogUnavailableException) {
            assumeNoException("optional catalog exports are absent", error)
            return
        }

        assertEquals(devices.size, devices.map(DesktopWasapiDevice::endpointId).distinct().size)
        devices.forEach { device ->
            assertFalse(device.endpointId.isBlank())
            assertFalse(device.identityKey.isBlank())
            assertFalse(device.friendlyName.isBlank())
            if (!device.endpointVolumeQuerySucceeded) {
                assertEquals(0, device.endpointVolumeHardwareSupportFlags)
            }
            if (!device.stableIdentity) assertEquals(device.endpointId, device.identityKey)
        }
    }

    private fun testDevice(
        endpointVolumeQueryHresult: Int,
        endpointVolumeHardwareSupportFlags: Int = 0,
    ) = DesktopWasapiDevice(
        endpointId = "{test-endpoint}",
        identityKey = "{test-endpoint}",
        friendlyName = "Test output",
        endpointState = WASAPI_DEVICE_STATE_ACTIVE,
        defaultRoleMask = 0,
        stableIdentity = false,
        endpointVolumeQueryHresult = endpointVolumeQueryHresult,
        endpointVolumeHardwareSupportFlags = endpointVolumeHardwareSupportFlags,
    )
}
