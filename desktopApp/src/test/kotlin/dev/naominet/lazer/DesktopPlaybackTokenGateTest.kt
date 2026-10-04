package dev.naominet.lazer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPlaybackTokenGateTest {
    @Test
    fun `late token activation from a superseded playback request is ignored`() {
        val gate = DesktopPlaybackTokenGate()
        val oldRequest = gate.beginRequest()
        val newRequest = gate.beginRequest()

        assertFalse(gate.activateIfCurrent(oldRequest, 101L))
        assertEquals(0L, gate.get())
        assertTrue(gate.activateIfCurrent(newRequest, 202L))
        assertTrue(gate.isActive(newRequest, 202L))
        assertEquals(202L, gate.get())
    }

    @Test
    fun `beginning a new request invalidates the previous active token`() {
        val gate = DesktopPlaybackTokenGate()
        val request = gate.beginRequest()
        assertTrue(gate.activateIfCurrent(request, 101L))
        assertEquals(101L, gate.get())

        gate.beginRequest()

        assertEquals(0L, gate.get())
        assertFalse(gate.activateIfCurrent(request, 101L))
        assertFalse(gate.isActive(request, 101L))
    }

    @Test
    fun `terminal event before native open returns does not look like active playback`() {
        val gate = DesktopPlaybackTokenGate()
        val request = gate.beginRequest()
        assertTrue(gate.activateIfCurrent(request, 101L))

        assertTrue(gate.compareAndSet(101L, 0L))

        assertFalse(gate.isActive(request, 101L))
        assertEquals(0L, gate.get())
    }
}
