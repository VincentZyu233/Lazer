package dev.naominet.lazer

import com.sun.jna.Structure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class DesktopAudioOutputDeviceTest {
    @Test
    fun `saved ALSA identity resolves to direct hardware token`() {
        val device = DesktopAlsaOutputDevice(
            deviceToken = "hw:CARD=HiFi,DEV=0",
            identityKey = "alsa:card:HiFi:pcm:0",
            displayName = "USB Audio DAC",
            flags = ALSA_OUTPUT_DEVICE_ACTIVE,
        )

        assertEquals(
            DesktopAudioOutputSelection(device.deviceToken, unavailable = false),
            resolveDesktopAudioOutputSelection(device.identityKey, listOf(device)),
        )
        assertTrue(device.stableIdentity)
        assertFalse(device.isDefault)
    }

    @Test
    fun `missing saved ALSA device stays unavailable instead of changing hardware`() {
        val current = DesktopAlsaOutputDevice(
            deviceToken = "hw:CARD=Other,DEV=0",
            identityKey = "alsa:card:Other:pcm:0",
            displayName = "Different output",
            flags = ALSA_OUTPUT_DEVICE_ACTIVE,
        )

        assertEquals(
            DesktopAudioOutputSelection(deviceToken = null, unavailable = true),
            resolveDesktopAudioOutputSelection("alsa:card:Removed:pcm:0", listOf(current)),
        )
        assertEquals(
            DesktopAudioOutputSelection(deviceToken = null, unavailable = false),
            resolveDesktopAudioOutputSelection(null, listOf(current)),
        )
    }

    @Test
    fun `card index fallback is identified as ephemeral`() {
        val device = DesktopAlsaOutputDevice(
            deviceToken = "hw:2,0",
            identityKey = "alsa:card-index:2:pcm:0",
            displayName = "USB Audio DAC",
            flags = ALSA_OUTPUT_DEVICE_ACTIVE or ALSA_OUTPUT_DEVICE_IDENTITY_EPHEMERAL,
        )

        assertFalse(device.stableIdentity)
    }

    @Test
    fun `saved CoreAudio UID resolves to a stable native device token`() {
        val device = DesktopCoreAudioOutputDevice(
            deviceToken = "AppleUSBAudioEngine:Vendor:USB DAC:1234",
            identityKey = "coreaudio:uid:AppleUSBAudioEngine:Vendor:USB DAC:1234",
            displayName = "USB DAC",
            flags = COREAUDIO_OUTPUT_DEVICE_ACTIVE or COREAUDIO_OUTPUT_DEVICE_DEFAULT,
        )

        assertEquals(
            DesktopAudioOutputSelection(device.deviceToken, unavailable = false),
            resolveDesktopAudioOutputSelection(device.identityKey, listOf(device)),
        )
        assertTrue(device.stableIdentity)
        assertTrue(device.isDefault)
    }

    @Test
    fun `desktop output backend follows host platform`() {
        assertEquals(DesktopAudioOutputBackend.Wasapi, resolveDesktopAudioOutputBackend("Windows 11"))
        assertEquals(DesktopAudioOutputBackend.Alsa, resolveDesktopAudioOutputBackend("Linux"))
        assertEquals(DesktopAudioOutputBackend.CoreAudio, resolveDesktopAudioOutputBackend("Mac OS X"))
    }

    @Test
    fun `exclusive output request is supported by WASAPI and CoreAudio only`() {
        assertTrue(shouldRequestDesktopExclusiveOutput(true, "Windows 11"))
        assertTrue(shouldRequestDesktopExclusiveOutput(true, "Mac OS X"))
        assertFalse(shouldRequestDesktopExclusiveOutput(true, "Linux"))
        assertFalse(shouldRequestDesktopExclusiveOutput(false, "Mac OS X"))
    }

    @Test
    fun `bit-perfect is exposed only for implemented native PCM backends`() {
        assertTrue(supportsDesktopBitPerfectOutput("Windows 11"))
        assertTrue(supportsDesktopBitPerfectOutput("Mac OS X"))
        assertTrue(supportsDesktopBitPerfectOutput("Linux"))
        assertFalse(supportsDesktopBitPerfectOutput("FreeBSD"))
        assertTrue(desktopBitPerfectRequiresExclusiveRequest("Windows 11"))
        assertTrue(desktopBitPerfectRequiresExclusiveRequest("Mac OS X"))
        assertFalse(desktopBitPerfectRequiresExclusiveRequest("Linux"))
    }

    @Test
    fun `native output device info matches the append-only C ABI`() {
        val info = LazerAudioOutputDeviceInfo()

        assertEquals(8, info.size())
        assertEquals(
            listOf("structSize", "flags"),
            info.javaClass.getAnnotation(Structure.FieldOrder::class.java).value.toList(),
        )
    }

    @Test
    fun `optional Linux native library can enumerate ALSA output devices`() {
        assumeTrue("ALSA native smoke test requires Linux", System.getProperty("os.name").contains("Linux", true))
        assumeTrue("native library path was not supplied", !System.getProperty("lazer.audio.library").isNullOrBlank())
        assertTrue("native audio ABI is unavailable", LazerAudioLoader.isAvailable)

        val devices = DesktopAlsaOutputDeviceCatalog.enumerate()

        assertEquals(devices.size, devices.map(DesktopAlsaOutputDevice::identityKey).distinct().size)
        devices.forEach { device ->
            assertTrue(device.deviceToken.startsWith("hw:"))
            assertTrue(device.identityKey.startsWith("alsa:"))
            assertFalse(device.displayName.isBlank())
        }
    }

    @Test
    fun `optional macOS native library can enumerate CoreAudio output devices`() {
        assumeTrue("CoreAudio native smoke test requires macOS", isMacOSDesktop())
        assumeTrue("native library path was not supplied", !System.getProperty("lazer.audio.library").isNullOrBlank())
        assertTrue("native audio ABI is unavailable", LazerAudioLoader.isAvailable)

        val devices = DesktopCoreAudioOutputDeviceCatalog.enumerate()

        assertEquals(devices.size, devices.map(DesktopCoreAudioOutputDevice::identityKey).distinct().size)
        devices.forEach { device ->
            assertTrue(device.deviceToken.isNotBlank())
            assertTrue(device.identityKey.startsWith("coreaudio:uid:"))
            assertEquals("coreaudio:uid:${device.deviceToken}", device.identityKey)
            assertFalse(device.displayName.isBlank())
            assertTrue(device.stableIdentity)
        }
    }
}
