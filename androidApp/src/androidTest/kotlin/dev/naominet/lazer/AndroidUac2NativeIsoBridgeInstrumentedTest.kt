package dev.naominet.lazer

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidUac2NativeIsoBridgeInstrumentedTest {
    @Test
    fun loadsPinnedLibusbAndRejectsAnInvalidBorrowedDescriptor() {
        val bridge = AndroidUac2NativeIsoBridge()

        assertEquals("1.0.30", bridge.versionForInstrumentationTest())
        assertTrue("USB 2.0 frame/feedback packetizer self-test failed", bridge.packetizerSelfTestForInstrumentationTest())
        try {
            bridge.rejectInvalidFileDescriptorForInstrumentationTest()
            throw AssertionError("An invalid UsbDeviceConnection FD must be rejected")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("invalid file descriptor", ignoreCase = true))
        }
    }
}
