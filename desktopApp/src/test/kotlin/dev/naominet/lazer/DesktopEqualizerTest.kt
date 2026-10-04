package dev.naominet.lazer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import javax.sound.sampled.AudioFormat
import kotlin.math.log10

class DesktopEqualizerTest {

    private val format = AudioFormat(44_100f, 16, 2, true, false)

    @Test
    fun `a flat curve leaves the samples untouched`() {
        val equalizer = PcmEqualizer()
        val buffer = byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50, 0x60, 0x70, 0x40)
        val original = buffer.copyOf()

        equalizer.process(buffer, buffer.size, format, LazerEqualizerState(enabled = true, bands = emptyList()))

        assertArrayEquals(original, buffer)
    }

    @Test
    fun `a disabled curve leaves the samples untouched`() {
        val equalizer = PcmEqualizer()
        val buffer = ByteArray(8) { 0x11 }
        val original = buffer.copyOf()

        equalizer.process(
            buffer,
            buffer.size,
            format,
            LazerEqualizerState(enabled = false, bands = listOf(LazerEqBand(1_000.0, 6.0))),
        )

        assertArrayEquals(original, buffer)
    }

    @Test
    fun `preamp gain scales the samples`() {
        val equalizer = PcmEqualizer()
        val buffer = ByteArray(4)
        writeSample(buffer, 0, 1_000)
        writeSample(buffer, 2, -1_000)

        equalizer.process(
            buffer,
            buffer.size,
            format,
            LazerEqualizerState(
                enabled = true,
                bands = emptyList(),
                preampDb = 20.0 * log10(2.0),
            ),
        )

        assertEquals(2_000, readSample(buffer, 0))
        assertEquals(-2_000, readSample(buffer, 2))
    }

    private fun writeSample(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = value.toByte()
        buffer[offset + 1] = (value shr 8).toByte()
    }

    private fun readSample(buffer: ByteArray, offset: Int): Int {
        val low = buffer[offset].toInt() and 0xFF
        val high = buffer[offset + 1].toInt() and 0xFF
        return ((high shl 8) or low).toShort().toInt()
    }
}
