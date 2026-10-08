package dev.naominet.lazer

import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioOutput
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.lang.reflect.Proxy
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertContentEquals
import kotlin.math.pow
import org.junit.Test

class AndroidPcmEqualizerTest {
    @Test
    fun `DSP assessment includes the default limiter even when EQ is off`() {
        assertTrue(
            androidPcmDspMayModifySamples(
                LazerEqualizerState(enabled = false),
                sampleRateHz = 96_000,
                channelCount = 2,
                encoding = C.ENCODING_PCM_24BIT,
            ),
        )
    }

    @Test
    fun `DSP assessment reports full bypass only when EQ and limiter are both inactive`() {
        val bypassed = LazerEqualizerState(enabled = false, limiterEnabled = false)

        assertFalse(androidPcmDspMayModifySamples(bypassed, 44_100, 2, C.ENCODING_PCM_16BIT))
        assertTrue(
            androidPcmDspMayModifySamples(
                bypassed.copy(enabled = true, bands = listOf(LazerEqBand(1_000.0, gainDb = 3.0))),
                44_100,
                2,
                C.ENCODING_PCM_16BIT,
            ),
        )
    }

    @Test
    fun `DSP assessment follows the formats accepted by the Media3 wrapper`() {
        val state = LazerEqualizerState(enabled = false, limiterEnabled = true)

        assertFalse(androidPcmDspMayModifySamples(state, 48_000, 6, C.ENCODING_PCM_FLOAT))
        assertFalse(androidPcmDspMayModifySamples(state, 48_000, 2, C.ENCODING_MP3))
        assertFalse(androidPcmDspMayModifySamples(state, 48_000, 2, C.ENCODING_PCM_FLOAT, offload = true))
        assertFalse(androidPcmDspMayModifySamples(state, 48_000, 2, C.ENCODING_PCM_FLOAT, tunneling = true))
    }

    @Test
    fun `multichannel output reports ReplayGain without claiming stereo EQ or limiter`() {
        val state = LazerEqualizerState(enabled = true, limiterEnabled = true)

        assertFalse(androidPcmDspMayModifySamples(state, 48_000, 6, C.ENCODING_PCM_FLOAT))
        assertTrue(
            androidPcmDspMayModifySamples(
                state,
                48_000,
                6,
                C.ENCODING_PCM_FLOAT,
                replayGainDb = -6.0,
            ),
        )
        assertFalse(
            androidPcmDspMayModifySamples(
                state,
                48_000,
                9,
                C.ENCODING_PCM_FLOAT,
                replayGainDb = -6.0,
            ),
        )
    }

    @Test
    fun `wrapper accepts float and integer pcm16 24 and 32 only`() {
        assertTrue(isPcmEqualizerSupportedEncoding(C.ENCODING_PCM_FLOAT))
        assertTrue(isPcmEqualizerSupportedEncoding(C.ENCODING_PCM_16BIT))
        assertTrue(isPcmEqualizerSupportedEncoding(C.ENCODING_PCM_24BIT))
        assertTrue(isPcmEqualizerSupportedEncoding(C.ENCODING_PCM_32BIT))
        assertFalse(isPcmEqualizerSupportedEncoding(C.ENCODING_PCM_8BIT))
        assertFalse(isPcmEqualizerSupportedEncoding(C.ENCODING_MP3))
    }

    @Test
    fun `disabled equalizer returns no copy for transparent forwarding`() {
        val equalizer = AndroidPcmEqualizer(LazerEqualizerState(limiterEnabled = false))
        val input = floatBuffer(0.25f, -0.5f)

        assertNull(equalizer.processCopy(input, 48_000, 2, C.ENCODING_PCM_FLOAT))
        assertEquals(0, input.position())
    }

    @Test
    fun `ReplayGain processes float PCM with EQ and limiter bypassed`() {
        val gainDb = -6.0
        val gain = 10.0.pow(gainDb / 20.0)
        val equalizer = AndroidPcmEqualizer(
            LazerEqualizerState(enabled = false, limiterEnabled = false),
            initialReplayGainDb = gainDb,
        )
        val input = floatBuffer(0.25f, -0.5f)

        assertTrue(equalizer.mayModifySamples(48_000, 2, C.ENCODING_PCM_FLOAT, false, false))
        val output = assertNotNull(equalizer.processCopy(input, 48_000, 2, C.ENCODING_PCM_FLOAT))
            .order(ByteOrder.nativeOrder())

        assertEquals((0.25 * gain).toFloat(), output.getFloat(0), 1e-6f)
        assertEquals((-0.5 * gain).toFloat(), output.getFloat(Float.SIZE_BYTES), 1e-6f)
        assertEquals(0.25f, input.getFloat(0))
        assertEquals(-0.5f, input.getFloat(Float.SIZE_BYTES))
    }

    @Test
    fun `ReplayGain zero remains transparent and updates affect following buffers`() {
        val equalizer = AndroidPcmEqualizer(
            LazerEqualizerState(enabled = false, limiterEnabled = false),
        )
        assertNull(equalizer.processCopy(floatBuffer(0.5f), 48_000, 1, C.ENCODING_PCM_FLOAT))

        equalizer.updateReplayGainDb(-6.0)
        val first = assertNotNull(
            equalizer.processCopy(floatBuffer(0.5f), 48_000, 1, C.ENCODING_PCM_FLOAT),
        ).order(ByteOrder.nativeOrder())
        assertEquals((0.5 * 10.0.pow(-6.0 / 20.0)).toFloat(), first.getFloat(0), 1e-6f)

        equalizer.updateReplayGainDb(-12.0)
        val second = assertNotNull(
            equalizer.processCopy(floatBuffer(0.5f), 48_000, 1, C.ENCODING_PCM_FLOAT),
        ).order(ByteOrder.nativeOrder())
        assertEquals((0.5 * 10.0.pow(-12.0 / 20.0)).toFloat(), second.getFloat(0), 1e-6f)
    }

    @Test
    fun `multichannel float ReplayGain applies to every channel while stereo DSP stays bypassed`() {
        val gainDb = -6.0
        val gain = 10.0.pow(gainDb / 20.0)
        val equalizer = AndroidPcmEqualizer(
            LazerEqualizerState(
                enabled = true,
                preampDb = 12.0,
                limiterEnabled = true,
                bands = listOf(LazerEqBand(1_000.0, gainDb = 6.0)),
            ),
            initialReplayGainDb = gainDb,
        )
        val input = floatBuffer(0.1f, -0.2f, 0.3f, -0.4f, 0.5f, -0.6f)
        val originalBytes = ByteArray(input.remaining()).also { input.duplicate().get(it) }

        assertTrue(equalizer.mayModifySamples(48_000, 6, C.ENCODING_PCM_FLOAT, false, false))
        val output = assertNotNull(equalizer.processCopy(input, 48_000, 6, C.ENCODING_PCM_FLOAT))
            .order(ByteOrder.nativeOrder())

        for (channel in 0 until 6) {
            assertEquals(input.getFloat(channel * Float.SIZE_BYTES) * gain.toFloat(),
                output.getFloat(channel * Float.SIZE_BYTES), 1e-6f)
        }
        assertContentEquals(originalBytes, ByteArray(input.remaining()).also { input.duplicate().get(it) })
    }

    @Test
    fun `ReplayGain applies to pcm16 packed pcm24 and pcm32 integer output`() {
        val gain = 10.0.pow(-6.0 / 20.0)
        val bypassedState = LazerEqualizerState(enabled = false, limiterEnabled = false)

        val output16 = assertNotNull(
            AndroidPcmEqualizer(bypassedState, -6.0).processCopy(
                ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
                    .putShort(16_384).putShort((-16_384).toShort()).flip() as ByteBuffer,
                48_000,
                2,
                C.ENCODING_PCM_16BIT,
            ),
        ).order(ByteOrder.nativeOrder())
        assertTrue(kotlin.math.abs(output16.getShort(0).toInt() - (16_384 * gain).toInt()) <= 1)
        assertTrue(kotlin.math.abs(output16.getShort(2).toInt() - (-16_384 * gain).toInt()) <= 1)

        val output24 = assertNotNull(
            AndroidPcmEqualizer(bypassedState, -6.0).processCopy(
                pcm24Buffer(4_194_304, -4_194_304),
                48_000,
                2,
                C.ENCODING_PCM_24BIT,
            ),
        )
        assertTrue(kotlin.math.abs(readPcm24(output24, 0) - (4_194_304 * gain).toInt()) <= 1)
        assertTrue(kotlin.math.abs(readPcm24(output24, 3) - (-4_194_304 * gain).toInt()) <= 1)

        val output32 = assertNotNull(
            AndroidPcmEqualizer(bypassedState, -6.0).processCopy(
                ByteBuffer.allocateDirect(2 * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
                    .putInt(1_073_741_824).putInt(-1_073_741_824).flip() as ByteBuffer,
                48_000,
                2,
                C.ENCODING_PCM_32BIT,
            ),
        ).order(ByteOrder.nativeOrder())
        assertTrue(kotlin.math.abs(output32.getInt(0).toLong() - (1_073_741_824 * gain).toLong()) <= 1L)
        assertTrue(kotlin.math.abs(output32.getInt(Int.SIZE_BYTES).toLong() - (-1_073_741_824 * gain).toLong()) <= 1L)
    }

    @Test
    fun `disabled equalizer and limiter pass integer pcm through without dither`() {
        val equalizer = AndroidPcmEqualizer(
            LazerEqualizerState(enabled = false, limiterEnabled = false),
        )
        val inputs = listOf(
            C.ENCODING_PCM_16BIT to ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
                .putShort(123).putShort((-456).toShort()).flip() as ByteBuffer,
            C.ENCODING_PCM_24BIT to pcm24Buffer(123_456, -654_321),
            C.ENCODING_PCM_32BIT to ByteBuffer.allocateDirect(2 * Int.SIZE_BYTES)
                .order(ByteOrder.nativeOrder()).putInt(123_456).putInt(-654_321).flip() as ByteBuffer,
        )

        for ((encoding, input) in inputs) {
            val originalBytes = ByteArray(input.remaining()).also { input.duplicate().get(it) }
            val originalPosition = input.position()

            assertNull(equalizer.processCopy(input, 48_000, 2, encoding))

            assertEquals(originalPosition, input.position())
            assertContentEquals(originalBytes, ByteArray(input.remaining()).also { input.duplicate().get(it) })
        }
    }

    @Test
    fun `limiter has immediate stereo linked attack and gradual release while equalizer is bypassed`() {
        val equalizer = AndroidPcmEqualizer(
            LazerEqualizerState(enabled = false, limiterEnabled = true, bands = emptyList()),
        )
        val sampleRate = 48_000
        val releaseFrames = (sampleRate * 0.12).toInt()
        val input = ByteBuffer.allocateDirect((releaseFrames + 1) * 2 * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply {
                putFloat(1.5f)
                putFloat(0.75f)
                repeat(releaseFrames) {
                    putFloat(0.1f)
                    putFloat(0.1f)
                }
                flip() as ByteBuffer
            }
        val threshold = 10.0.pow(-1.0 / 20.0).toFloat()

        val output = assertNotNull(equalizer.processCopy(input, sampleRate, 2, C.ENCODING_PCM_FLOAT))
            .order(ByteOrder.nativeOrder())

        val firstLeft = output.getFloat(0)
        val firstRight = output.getFloat(Float.SIZE_BYTES)
        assertTrue(kotlin.math.abs(firstLeft - threshold) < 1e-6f)
        assertTrue(kotlin.math.abs(firstRight - threshold / 2.0f) < 1e-6f)
        assertEquals(1.5f, input.getFloat(0))

        val firstReleaseLeft = output.getFloat(2 * Float.SIZE_BYTES)
        var previous = firstReleaseLeft
        for (frame in 2..releaseFrames) {
            val current = output.getFloat(frame * 2 * Float.SIZE_BYTES)
            assertTrue(current >= previous, "limiter gain should recover monotonically")
            previous = current
        }
        assertTrue(previous > firstReleaseLeft, "limiter should release toward unity")
        assertTrue(previous < 0.1f, "release should remain gradual after 120 ms")
    }

    @Test
    fun `limiter release envelope continues across output buffers`() {
        val equalizer = AndroidPcmEqualizer(
            LazerEqualizerState(enabled = false, limiterEnabled = true, bands = emptyList()),
        )
        val sampleRate = 48_000
        val spike = assertNotNull(
            equalizer.processCopy(floatBuffer(1.5f, 0.75f), sampleRate, 2, C.ENCODING_PCM_FLOAT),
        ).order(ByteOrder.nativeOrder())
        val limitedSpike = spike.getFloat(0)
        val following = assertNotNull(
            equalizer.processCopy(floatBuffer(0.1f, 0.1f), sampleRate, 2, C.ENCODING_PCM_FLOAT),
        ).order(ByteOrder.nativeOrder())
        val threshold = 10.0.pow(-1.0 / 20.0).toFloat()

        assertTrue(kotlin.math.abs(limitedSpike - threshold) < 1e-6f)
        assertTrue(following.getFloat(0) < 0.1f, "release state should carry into the next buffer")
        assertEquals(following.getFloat(0), following.getFloat(Float.SIZE_BYTES))
    }

    @Test
    fun `float preamp processes a copy and leaves the source bytes untouched`() {
        val equalizer = AndroidPcmEqualizer(
            LazerEqualizerState(enabled = true, preampDb = 6.0, limiterEnabled = false, bands = emptyList()),
        )
        val input = floatBuffer(0.25f, -0.25f)
        val original = input.duplicate().order(ByteOrder.nativeOrder())

        val output = assertNotNull(equalizer.processCopy(input, 48_000, 2, C.ENCODING_PCM_FLOAT))
        assertEquals(0, input.position())
        assertTrue(kotlin.math.abs(output.order(ByteOrder.nativeOrder()).getFloat(0) - 0.4988f) < 0.002f)
        assertEquals(original.getFloat(0), input.getFloat(0))
    }

    @Test
    fun `processed pcm16 is quantized with headroom protection`() {
        val equalizer = AndroidPcmEqualizer(
            LazerEqualizerState(enabled = true, preampDb = 12.0, limiterEnabled = true, bands = emptyList()),
        )
        val input = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder())
            .putShort(29_000)
            .putShort((-29_000).toShort())
            .flip() as ByteBuffer

        val output = assertNotNull(equalizer.processCopy(input, 48_000, 2, C.ENCODING_PCM_16BIT))
            .order(ByteOrder.nativeOrder())
        assertTrue(output.getShort(0).toInt() in 29_100..29_300)
        assertTrue(output.getShort(2).toInt() in -29_300..-29_100)
    }

    @Test
    fun `pcm16 quantization adds low level tpdf dither`() {
        val equalizer = AndroidPcmEqualizer(
            LazerEqualizerState(enabled = true, preampDb = 0.1, limiterEnabled = false, bands = emptyList()),
        )
        val input = ByteBuffer.allocateDirect(2 * 1_024).order(ByteOrder.nativeOrder())
            .apply { repeat(1_024) { putShort(0) }; flip() as ByteBuffer }

        val output = assertNotNull(equalizer.processCopy(input, 48_000, 1, C.ENCODING_PCM_16BIT))
            .order(ByteOrder.nativeOrder())
        val values = IntArray(1_024) { output.getShort(it * Short.SIZE_BYTES).toInt() }
        val nonZeroSamples = values.count { it != 0 }
        assertTrue(nonZeroSamples in 80..600)
        assertTrue(kotlin.math.abs(values.average()) < 0.1)
    }

    @Test
    fun `packed pcm24 preamp processes signed samples and leaves the source untouched`() {
        val equalizer = AndroidPcmEqualizer(
            LazerEqualizerState(enabled = true, preampDb = 6.0, limiterEnabled = false, bands = emptyList()),
        )
        val input = pcm24Buffer(4_194_304, -4_194_304)
        val original = input.duplicate()

        val output = assertNotNull(equalizer.processCopy(input, 48_000, 2, C.ENCODING_PCM_24BIT))

        assertTrue(readPcm24(output, 0) in 8_300_000..8_388_607)
        assertTrue(readPcm24(output, 3) in -8_388_608..-8_300_000)
        assertEquals(0, input.position())
        assertEquals(original.get(0), input.get(0))
    }

    @Test
    fun `pcm32 preamp quantizes within signed 32 bit range`() {
        val equalizer = AndroidPcmEqualizer(
            LazerEqualizerState(enabled = true, preampDb = 6.0, limiterEnabled = false, bands = emptyList()),
        )
        val input = ByteBuffer.allocateDirect(2 * Int.SIZE_BYTES).order(ByteOrder.nativeOrder())
            .putInt(1_073_741_824)
            .putInt(-1_073_741_824)
            .flip() as ByteBuffer
        val originalPositive = input.getInt(0)

        val output = assertNotNull(equalizer.processCopy(input, 48_000, 2, C.ENCODING_PCM_32BIT))
            .order(ByteOrder.nativeOrder())

        assertTrue(output.getInt(0) in 2_130_000_000..Int.MAX_VALUE)
        assertTrue(output.getInt(Int.SIZE_BYTES) in Int.MIN_VALUE..-2_130_000_000)
        assertEquals(0, input.position())
        assertEquals(originalPositive, input.getInt(0))
    }

    @Test
    fun `24 and 32 bit integer output add tpdf dither at their own target depth`() {
        val equalizer24 = AndroidPcmEqualizer(
            LazerEqualizerState(enabled = true, preampDb = 0.1, limiterEnabled = false, bands = emptyList()),
        )
        val input24 = pcm24Buffer(*IntArray(1_024))
        val output24 = assertNotNull(equalizer24.processCopy(input24, 48_000, 1, C.ENCODING_PCM_24BIT))
        val samples24 = IntArray(1_024) { readPcm24(output24, it * 3) }

        val equalizer32 = AndroidPcmEqualizer(
            LazerEqualizerState(enabled = true, preampDb = 0.1, limiterEnabled = false, bands = emptyList()),
        )
        val input32 = ByteBuffer.allocateDirect(1_024 * Int.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply { repeat(1_024) { putInt(0) }; flip() as ByteBuffer }
        val output32 = assertNotNull(equalizer32.processCopy(input32, 48_000, 1, C.ENCODING_PCM_32BIT))
            .order(ByteOrder.nativeOrder())
        val samples32 = IntArray(1_024) { output32.getInt(it * Int.SIZE_BYTES) }

        assertTrue(samples24.count { it != 0 } in 80..600)
        assertTrue(samples32.count { it != 0 } in 80..600)
        assertTrue(kotlin.math.abs(samples24.average()) < 0.1)
        assertTrue(kotlin.math.abs(samples32.average()) < 0.1)
    }

    @Test
    fun `unsupported channel layouts are bypassed`() {
        val equalizer = AndroidPcmEqualizer(
            LazerEqualizerState(enabled = true, preampDb = 6.0, bands = emptyList()),
        )

        assertNull(equalizer.processCopy(floatBuffer(0.1f, 0.2f, 0.3f), 48_000, 3, C.ENCODING_PCM_FLOAT))
    }

    @Test
    fun `partial AudioOutput writes reuse the processed buffer exactly once`() {
        val equalizer = AndroidPcmEqualizer(
            LazerEqualizerState(enabled = true, preampDb = 6.0, limiterEnabled = false, bands = emptyList()),
        )
        val observed = mutableListOf<Float>()
        var writeCalls = 0
        val delegate = Proxy.newProxyInstance(
            AudioOutput::class.java.classLoader,
            arrayOf(AudioOutput::class.java),
        ) { _, method, arguments ->
            if (method.name != "write") return@newProxyInstance null
            val output = arguments!![0] as ByteBuffer
            val consumedBytes = minOf(if (writeCalls == 0) 8 else output.remaining(), output.remaining())
            val captured = output.duplicate().order(ByteOrder.nativeOrder())
            captured.limit(captured.position() + consumedBytes)
            while (captured.hasRemaining()) observed += captured.getFloat()
            output.position(output.position() + consumedBytes)
            writeCalls += 1
            writeCalls > 1
        } as AudioOutput
        val forwarding = AndroidEqualizingAudioOutput(
            delegate,
            equalizer,
            sampleRateHz = 48_000,
            channelCount = 2,
            encoding = C.ENCODING_PCM_FLOAT,
        )
        val input = floatBuffer(0.25f, -0.25f, 0.25f, -0.25f)

        assertFalse(forwarding.write(input, encodedAccessUnitCount = 1, presentationTimeUs = 0L))
        assertEquals(8, input.position())
        assertTrue(forwarding.write(input, encodedAccessUnitCount = 1, presentationTimeUs = 0L))
        assertEquals(input.limit(), input.position())
        assertEquals(4, observed.size)
        assertTrue(observed.all { kotlin.math.abs(kotlin.math.abs(it) - 0.4988f) < 0.002f })
    }

    private fun floatBuffer(vararg samples: Float): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .apply { samples.forEach(::putFloat); flip() as ByteBuffer }

    private fun pcm24Buffer(vararg samples: Int): ByteBuffer =
        ByteBuffer.allocateDirect(samples.size * 3)
            .order(ByteOrder.nativeOrder())
            .apply {
                samples.forEach { sample ->
                    val packed = sample and 0x00ff_ffff
                    if (order() == ByteOrder.LITTLE_ENDIAN) {
                        put(packed.toByte())
                        put((packed ushr 8).toByte())
                        put((packed ushr 16).toByte())
                    } else {
                        put((packed ushr 16).toByte())
                        put((packed ushr 8).toByte())
                        put(packed.toByte())
                    }
                }
                flip() as ByteBuffer
            }

    private fun readPcm24(buffer: ByteBuffer, index: Int): Int {
        val first = buffer.get(index).toInt() and 0xff
        val second = buffer.get(index + 1).toInt() and 0xff
        val third = buffer.get(index + 2).toInt() and 0xff
        val unsigned = if (buffer.order() == ByteOrder.LITTLE_ENDIAN) {
            first or (second shl 8) or (third shl 16)
        } else {
            (first shl 16) or (second shl 8) or third
        }
        return (unsigned shl 8) shr 8
    }
}
