package dev.naominet.lazer

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DesktopWasapiEndpointVolumeTest {
    @Test
    fun `optional native endpoint volume getter reads only a reported hardware endpoint`() {
        assumeTrue("endpoint volume test requires Windows", System.getProperty("os.name").startsWith("Windows"))
        assumeTrue("native audio library is optional", LazerAudioLoader.isAvailable)

        val device = DesktopWasapiDeviceCatalog.enumerate()
            .firstOrNull(DesktopWasapiDevice::reportsHardwareEndpointVolume)
        assumeTrue("no active endpoint reports hardware volume support", device != null)

        val state = DesktopWasapiEndpointVolume.read(requireNotNull(device).endpointId)

        assertTrue(state.scalar.isFinite() && state.scalar in 0f..1f)
        assertTrue(state.hardwareSupportFlags and WASAPI_ENDPOINT_HARDWARE_SUPPORT_VOLUME != 0)
    }
}
