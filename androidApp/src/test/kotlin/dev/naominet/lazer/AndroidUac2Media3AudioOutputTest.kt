package dev.naominet.lazer

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class AndroidUac2Media3AudioOutputTest {
    @Test
    fun `partial frame acceptance advances source buffer and retries the same buffer`() {
        val transport = FakeTransport(acceptFramesPerWrite = ArrayDeque(listOf(2, 2)))
        val output = output(transport)
        val buffer = pcmFrames(4)
        val originalBytes = bytes(buffer)

        assertFalse(output.write(buffer, 1, 1_000L))
        assertEquals(8, buffer.position())
        assertEquals(4, transport.writeCalls[0].frameCount)
        assertTrue(transport.writeCalls[0].buffer.isReadOnly)
        assertTrue(transport.writeCalls[0].buffer.isDirect)
        assertEquals(ByteOrder.nativeOrder(), transport.writeCalls[0].buffer.order())

        assertTrue(output.write(buffer, 1, 1_000L))
        assertEquals(buffer.limit(), buffer.position())
        assertEquals(2, transport.writeCalls[1].frameCount)
        assertArrayEquals(originalBytes, bytes(buffer))
        assertEquals(0L, output.positionUs) // Media3 maps output frames to the source PTS itself.
    }

    @Test
    fun `zero frame acceptance is backpressure and does not advance the buffer`() {
        val transport = FakeTransport(acceptFramesPerWrite = ArrayDeque(listOf(0, 2)))
        val output = output(transport)
        val buffer = pcmFrames(2)

        assertFalse(output.write(buffer, 1, 10L))
        assertEquals(0, buffer.position())
        assertTrue(output.write(buffer, 1, 10L))
        assertEquals(buffer.limit(), buffer.position())
    }

    @Test
    fun `unconsumed input cannot be replaced or change its Media3 metadata`() {
        val output = output(FakeTransport(acceptFramesPerWrite = ArrayDeque(listOf(1, 0))))
        val buffer = pcmFrames(3)
        assertFalse(output.write(buffer, 1, 20L))

        assertThrows(IllegalStateException::class.java) { output.write(pcmFrames(2), 1, 20L) }
        assertThrows(IllegalStateException::class.java) { output.write(buffer, 1, 21L) }
        assertThrows(IllegalArgumentException::class.java) { output.write(buffer, 2, 20L) }
    }

    @Test
    fun `write validates direct native ordered complete PCM frames`() {
        val output = output(FakeTransport())

        val heapBuffer = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        assertThrows(IllegalArgumentException::class.java) { output.write(heapBuffer, 1, 0L) }

        val nonNative = ByteBuffer.allocateDirect(8)
            .order(if (ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        assertThrows(IllegalArgumentException::class.java) { output.write(nonNative, 1, 0L) }

        assertThrows(IllegalArgumentException::class.java) { output.write(pcmFrames(1, extraBytes = 1), 1, 0L) }
        assertThrows(IllegalArgumentException::class.java) { output.write(pcmFrames(1), 0, 0L) }
    }

    @Test
    fun `flush discards backpressured data resets the position epoch and allows a new buffer`() {
        val transport = FakeTransport(acceptFramesPerWrite = ArrayDeque(listOf(1, 2)))
        val output = output(transport)
        val first = pcmFrames(3)
        assertFalse(output.write(first, 1, 50_000L))
        transport.playedFrames = 1
        assertEquals(20L, output.positionUs)

        output.flush()
        assertEquals(1, transport.flushCalls)
        assertEquals(0L, output.positionUs)
        assertTrue(output.write(pcmFrames(2), 1, 90_000L))
        transport.playedFrames = 1
        assertEquals(20L, output.positionUs)
    }

    @Test
    fun `position maps transport played frames to submitted presentation timestamp`() {
        val transport = FakeTransport(acceptFramesPerWrite = ArrayDeque(listOf(4)))
        val output = output(transport)

        assertEquals(0L, output.positionUs)
        assertTrue(output.write(pcmFrames(4), 1, 2_000_000L))
        transport.playedFrames = 2
        assertEquals(41L, output.positionUs)
        transport.playedFrames = 99 // A faulty transport cannot report past the submitted audio.
        assertEquals(83L, output.positionUs)
        assertEquals(8L, output.bufferSizeInFrames)
        assertEquals(48_000, output.sampleRate)
        assertEquals(C.AUDIO_SESSION_ID_UNSET, output.audioSessionId)
    }

    @Test
    fun `stop signals EOS once rejects writes and flush reopens the stream`() {
        val transport = FakeTransport()
        val output = output(transport)

        output.stop()
        output.stop()
        assertEquals(1, transport.stopAfterDrainCalls)
        assertThrows(IllegalStateException::class.java) { output.write(pcmFrames(1), 1, 0L) }
        output.flush()
        assertTrue(output.write(pcmFrames(1), 1, 0L))
    }

    @Test
    fun `hardware volume is forwarded without changing PCM and digital volume is rejected`() {
        val transport = FakeTransport()
        val output = output(transport)
        val pcm = pcmFrames(2)
        val expected = bytes(pcm)
        assertTrue(output.write(pcm, 1, 0L))

        output.setVolume(0.35f)
        assertEquals(listOf(0.35f), transport.hardwareVolumes)
        assertArrayEquals(expected, transport.acceptedBytes.toByteArray())

        val noHardwareVolume = FakeTransport(supportsHardwareVolume = false)
        val noVolumeOutput = output(noHardwareVolume)
        noVolumeOutput.setVolume(1f)
        assertThrows(IllegalStateException::class.java) { noVolumeOutput.setVolume(0.5f) }
        assertTrue(noHardwareVolume.hardwareVolumes.isEmpty())
    }

    @Test
    fun `playback rate and pitch must remain at unity`() {
        val output = output(FakeTransport())
        output.setPlaybackParameters(PlaybackParameters.DEFAULT)

        assertThrows(IllegalArgumentException::class.java) {
            output.setPlaybackParameters(PlaybackParameters(1.25f, 1f))
        }
        assertThrows(IllegalArgumentException::class.java) {
            output.setPlaybackParameters(PlaybackParameters(1f, 0.9f))
        }
        assertEquals(PlaybackParameters.DEFAULT, output.playbackParameters)
    }

    @Test
    fun `offload tunneling mismatched formats are rejected and float PCM is dithered to packed USB PCM`() {
        val transport = FakeTransport()

        assertThrows(IllegalArgumentException::class.java) {
            output(transport, config(isOffload = true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            output(transport, config(isTunneling = true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            output(transport, config(sampleRate = 44_100))
        }
        assertThrows(IllegalArgumentException::class.java) {
            output(transport, config(encoding = C.ENCODING_PCM_24BIT))
        }
        assertThrows(IllegalArgumentException::class.java) {
            output(transport, config(usePlaybackParameters = true))
        }

        val floatSource = ByteBuffer.allocateDirect(2 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        floatSource.putFloat(0.5f).putFloat(-0.25f)
        floatSource.flip()
        val packed24 = FakeTransport(bytesPerSample = 3)
        val floatOutput = output(packed24, config(encoding = C.ENCODING_PCM_FLOAT))
        assertTrue(floatOutput.write(floatSource, 1, 0L))
        val written = packed24.acceptedBytes.toByteArray()
        assertEquals(6, written.size)
        assertTrue(abs(readSigned24LittleEndian(written, 0) - 4_194_304) <= 1)
        assertTrue(abs(readSigned24LittleEndian(written, 3) + 2_097_152) <= 1)
    }

    @Test
    fun `16 24 packed and 32 bit integer PCM use complete interleaved frames`() {
        for ((encoding, bytesPerSample) in listOf(
            C.ENCODING_PCM_16BIT to 2,
            C.ENCODING_PCM_24BIT to 3,
            C.ENCODING_PCM_32BIT to 4,
        )) {
            val transport = FakeTransport(bytesPerSample = bytesPerSample)
            val output = output(transport, config(encoding = encoding))
            val buffer = pcmFrames(frameCount = 3, bytesPerFrame = bytesPerSample * 2)

            assertTrue(output.write(buffer, 1, 0L))
            assertEquals(3 * bytesPerSample * 2, transport.acceptedBytes.size)
        }
    }

    @Test
    fun `format support rejects speed processing tunneling offload and non raw encodings`() {
        assertEquals(
            AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY,
            androidUac2PcmFormatSupport(formatConfig()).supportLevel,
        )
        assertEquals(
            AudioOutputProvider.FORMAT_UNSUPPORTED,
            androidUac2PcmFormatSupport(formatConfig(enablePlaybackParameters = true)).supportLevel,
        )
        assertEquals(
            AudioOutputProvider.FORMAT_UNSUPPORTED,
            androidUac2PcmFormatSupport(formatConfig(enableOffload = true)).supportLevel,
        )
        assertEquals(
            AudioOutputProvider.FORMAT_UNSUPPORTED,
            androidUac2PcmFormatSupport(formatConfig(enableTunneling = true)).supportLevel,
        )
        assertEquals(
            AudioOutputProvider.FORMAT_UNSUPPORTED,
            androidUac2PcmFormatSupport(formatConfig(encoding = C.ENCODING_PCM_FLOAT)).supportLevel,
        )
    }

    @Test
    fun `write exceptions preserve error code recoverability and cause and poison output`() {
        val transport = FakeTransport()
        val output = output(transport)
        val cause = AndroidUac2PcmTransportException(73, true, "USB queue failed")
        transport.nextWriteFailure = cause

        val error = assertThrows(AudioOutput.WriteException::class.java) {
            output.write(pcmFrames(1), 1, 0L)
        }
        assertEquals(73, error.errorCode)
        assertTrue(error.isRecoverable)
        assertSame(cause, error.cause)
        val second = assertThrows(AudioOutput.WriteException::class.java) {
            output.write(pcmFrames(1), 1, 0L)
        }
        assertEquals(error.errorCode, second.errorCode)
    }

    @Test
    fun `asynchronous transport failure is propagated on the next write`() {
        val transport = FakeTransport()
        val output = output(transport)
        val error = AndroidUac2PcmTransportException(91, false, "USB transfer failed")
        transport.registeredListener!!.onFailure(error)

        val writeError = assertThrows(AudioOutput.WriteException::class.java) {
            output.write(pcmFrames(1), 1, 0L)
        }
        assertEquals(91, writeError.errorCode)
        assertFalse(writeError.isRecoverable)
        assertSame(error, writeError.cause)
        assertTrue(transport.writeCalls.isEmpty())
    }

    @Test
    fun `unexpected transport write exceptions become nonrecoverable Media3 failures`() {
        val transport = FakeTransport()
        val output = output(transport)
        val cause = IllegalStateException("transport invariant broke")
        transport.nextWriteFailure = cause

        val error = assertThrows(AudioOutput.WriteException::class.java) {
            output.write(pcmFrames(1), 1, 0L)
        }
        assertFalse(error.isRecoverable)
        assertSame(cause, error.cause)
    }

    @Test
    fun `transport events reach listeners and release closes once`() {
        val transport = FakeTransport()
        val output = output(transport)
        val listener = CountingListener()
        output.addListener(listener)
        transport.registeredListener!!.onPositionAdvancing(123L)
        transport.registeredListener!!.onUnderrun()
        assertEquals(123L, listener.positionAdvancingAtMs)
        assertEquals(1, listener.underruns)

        output.release()
        output.release()
        assertNull(transport.registeredListener)
        assertEquals(1, transport.closeCalls)
        assertEquals(1, listener.releasedCalls)
        assertEquals(C.TIME_UNSET, output.positionUs)
        assertThrows(IllegalStateException::class.java) { output.play() }

        val lateListener = CountingListener()
        output.addListener(lateListener)
        assertEquals(1, lateListener.releasedCalls)
    }

    @Test
    fun `offload APIs and unsupported routing or effects fail closed`() {
        val output = output(FakeTransport())
        assertFalse(output.isOffloadedPlayback)
        assertThrows(UnsupportedOperationException::class.java) { output.setOffloadDelayPadding(0, 0) }
        assertThrows(UnsupportedOperationException::class.java) { output.setOffloadEndOfStream() }
        assertThrows(IllegalArgumentException::class.java) { output.attachAuxEffect(2) }
        assertThrows(IllegalArgumentException::class.java) { output.setAuxEffectSendLevel(0.5f) }
    }

    private fun output(
        transport: FakeTransport,
        config: AudioOutputProvider.OutputConfig = config(),
    ) = AndroidUac2Media3AudioOutput(config, transport)

    private fun config(
        encoding: Int = C.ENCODING_PCM_16BIT,
        sampleRate: Int = 48_000,
        isOffload: Boolean = false,
        isTunneling: Boolean = false,
        usePlaybackParameters: Boolean = false,
    ) = AudioOutputProvider.OutputConfig.Builder()
        .setEncoding(encoding)
        .setSampleRate(sampleRate)
        .setChannelMask(CHANNEL_MASK_STEREO)
        .setIsOffload(isOffload)
        .setIsTunneling(isTunneling)
        .setUsePlaybackParameters(usePlaybackParameters)
        .build()

    private fun formatConfig(
        encoding: Int = C.ENCODING_PCM_16BIT,
        enablePlaybackParameters: Boolean = false,
        enableOffload: Boolean = false,
        enableTunneling: Boolean = false,
    ) = AudioOutputProvider.FormatConfig.Builder(
        Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_RAW)
            .setPcmEncoding(encoding)
            .setSampleRate(48_000)
            .setChannelCount(2)
            .build(),
    )
        .setEnablePlaybackParameters(enablePlaybackParameters)
        .setEnableOffload(enableOffload)
        .setEnableTunneling(enableTunneling)
        .build()

    private fun pcmFrames(
        frameCount: Int,
        bytesPerFrame: Int = FRAME_BYTES,
        extraBytes: Int = 0,
    ): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frameCount * bytesPerFrame + extraBytes).order(ByteOrder.nativeOrder())
        repeat(buffer.capacity()) { index -> buffer.put((index * 13).toByte()) }
        buffer.flip()
        return buffer
    }

    private fun bytes(buffer: ByteBuffer): ByteArray {
        val duplicate = buffer.duplicate()
        duplicate.clear()
        return ByteArray(duplicate.remaining()).also(duplicate::get)
    }

    private fun readSigned24LittleEndian(bytes: ByteArray, offset: Int): Int {
        val unsigned = (bytes[offset].toInt() and 0xff) or
            ((bytes[offset + 1].toInt() and 0xff) shl 8) or
            ((bytes[offset + 2].toInt() and 0xff) shl 16)
        return if (unsigned and 0x0080_0000 != 0) unsigned - 0x0100_0000 else unsigned
    }

    private class FakeTransport(
        private val acceptFramesPerWrite: ArrayDeque<Int> = ArrayDeque(),
        override val supportsHardwareVolume: Boolean = true,
        override val bytesPerSample: Int = 2,
    ) : AndroidUac2PcmTransport {
        override val sampleRateHz = 48_000
        override val channelCount = 2
        override val bufferSizeInFrames = 8L
        override var audioSessionId = C.AUDIO_SESSION_ID_UNSET
        var registeredListener: AndroidUac2PcmTransport.Listener? = null
        var playedFrames = 0L
        var flushCalls = 0
        var stopAfterDrainCalls = 0
        var closeCalls = 0
        var nextWriteFailure: Exception? = null
        val hardwareVolumes = mutableListOf<Float>()
        val acceptedBytes = ArrayList<Byte>()
        val writeCalls = mutableListOf<WriteCall>()

        override fun setListener(listener: AndroidUac2PcmTransport.Listener?) {
            registeredListener = listener
        }

        override fun play() = Unit
        override fun pause() = Unit

        override fun writeFrames(pcm: ByteBuffer, frameCount: Int): Int {
            nextWriteFailure?.let { error ->
                nextWriteFailure = null
                throw error
            }
            val acceptedFrames = acceptFramesPerWrite.removeFirstOrNull()?.coerceIn(0, frameCount) ?: frameCount
            val acceptedByteCount = acceptedFrames * bytesPerSample * channelCount
            val bytes = ByteArray(acceptedByteCount)
            pcm.duplicate().also { it.limit(it.position() + acceptedByteCount) }.get(bytes)
            acceptedBytes += bytes.toList()
            writeCalls += WriteCall(pcm.remaining() / (bytesPerSample * channelCount), pcm)
            return acceptedFrames
        }

        override fun flush() {
            flushCalls++
            playedFrames = 0L
        }

        override fun stopAfterDrain() {
            stopAfterDrainCalls++
        }

        override fun playedFramesSinceFlush() = playedFrames
        override fun isStalled() = false
        override fun setHardwareVolume(volume: Float) {
            hardwareVolumes += volume
        }

        override fun close() {
            closeCalls++
        }
    }

    private data class WriteCall(
        val frameCount: Int,
        val buffer: ByteBuffer,
    )

    private class CountingListener : AudioOutput.Listener {
        var positionAdvancingAtMs: Long? = null
        var underruns = 0
        var releasedCalls = 0
        override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) {
            positionAdvancingAtMs = playoutStartSystemTimeMs
        }

        override fun onOffloadDataRequest() = Unit
        override fun onOffloadPresentationEnded() = Unit
        override fun onUnderrun() {
            underruns++
        }

        override fun onReleased() {
            releasedCalls++
        }
    }

    private companion object {
        const val CHANNEL_MASK_STEREO = 12
        const val FRAME_BYTES = 4
    }
}
