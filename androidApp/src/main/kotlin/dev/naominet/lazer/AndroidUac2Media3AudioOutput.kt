package dev.naominet.lazer

import android.media.AudioDeviceInfo
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.ThreadLocalRandom
import kotlin.math.floor

/**
 * Format support to use from `AudioOutputProvider.getFormatSupport` for the UAC2 PCM path.
 *
 * Media3 exposes playback-parameter, offload, and tunneling requests on [AudioOutputProvider.FormatConfig].
 * Reject those requests before output creation; the output constructor repeats the final config
 * checks because `OutputConfig` is the last boundary before a transport is used.
 */
@UnstableApi
internal fun androidUac2PcmFormatSupport(
    config: AudioOutputProvider.FormatConfig,
): AudioOutputProvider.FormatSupport {
    val format = config.format
    val supported = format.sampleMimeType == MimeTypes.AUDIO_RAW &&
        format.pcmEncoding in ANDROID_UAC2_PCM_BYTES_PER_SAMPLE &&
        format.sampleRate > 0 && format.channelCount > 0 &&
        !config.enablePlaybackParameters && !config.enableOffload && !config.enableTunneling
    return if (supported) {
        AudioOutputProvider.FormatSupport.Builder()
            .setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY)
            .build()
    } else {
        AudioOutputProvider.FormatSupport.UNSUPPORTED
    }
}

private val ANDROID_UAC2_PCM_BYTES_PER_SAMPLE = mapOf(
    C.ENCODING_PCM_16BIT to 2,
    C.ENCODING_PCM_24BIT to 3,
    C.ENCODING_PCM_32BIT to 4,
)

private val ANDROID_UAC2_PCM_SOURCE_BYTES_PER_SAMPLE = ANDROID_UAC2_PCM_BYTES_PER_SAMPLE +
    (C.ENCODING_PCM_FLOAT to 4)

/**
 * Transport boundary for an already negotiated UAC2 PCM stream.
 *
 * Implementations own the device-side queue. [writeFrames] must synchronously copy the accepted
 * bytes into a bounded queue before returning and must not retain [pcm]. It returns the number of
 * complete interleaved PCM frames accepted (zero means backpressure). [playedFramesSinceFlush]
 * reports the monotonically increasing number of frames actually played in the current flush
 * epoch, and must never exceed the number of frames accepted in that epoch.
 *
 * This interface is only an injection seam. It does not itself open USB, perform isochronous
 * transfers, or establish bit-perfect playback.
 */
internal interface AndroidUac2PcmTransport {
    val sampleRateHz: Int
    val channelCount: Int
    val bytesPerSample: Int
    val bufferSizeInFrames: Long
    val audioSessionId: Int get() = C.AUDIO_SESSION_ID_UNSET
    val supportsHardwareVolume: Boolean

    fun setListener(listener: Listener?)
    fun play()
    fun pause()

    /** Returns the count of complete PCM frames copied/enqueued from the read-only view. */
    fun writeFrames(pcm: ByteBuffer, frameCount: Int): Int

    fun flush()

    /** Signals EOS and drains accepted frames before stopping the device stream. */
    fun stopAfterDrain()

    /** Frame playhead in the current epoch, reset to zero by [flush]. */
    fun playedFramesSinceFlush(): Long
    fun isStalled(): Boolean

    /** Must change device/hardware volume only; implementations must never scale PCM samples. */
    fun setHardwareVolume(volume: Float)

    /** Cancels and joins any transport work before returning. */
    fun close()

    interface Listener {
        fun onPositionAdvancing(playoutStartSystemTimeMs: Long)
        fun onUnderrun()
        fun onFailure(error: AndroidUac2PcmTransportException)
    }
}

internal class AndroidUac2PcmTransportException(
    val errorCode: Int,
    val isRecoverable: Boolean,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * PCM-only Media3 [AudioOutput] for the direct UAC2 USB transport. All volume requests go to the
 * transport's verified hardware-volume API; no digital gain is applied here. Float PCM conversion
 * and any enabled DSP may still alter source samples, so this class alone does not prove bit-perfect
 * delivery to a physical DAC.
 */
internal class AndroidUac2Media3AudioOutput(
    config: AudioOutputProvider.OutputConfig,
    private val transport: AndroidUac2PcmTransport,
) : AudioOutput {
    private data class PendingBuffer(
        val buffer: ByteBuffer,
        val encodedAccessUnitCount: Int,
        val presentationTimeUs: Long,
    )

    private val listeners = CopyOnWriteArraySet<AudioOutput.Listener>()
    private val lock = Any()
    private val frameSizeBytes: Int
    private val sampleRateHz: Int
    private val inputEncoding = config.encoding
    private var pendingBuffer: PendingBuffer? = null
    private var submittedFrames = 0L
    private var ended = false
    private var released = false
    private var failure: AudioOutput.WriteException? = null
    private var playbackParameters = PlaybackParameters.DEFAULT

    private val transportListener = object : AndroidUac2PcmTransport.Listener {
        override fun onPositionAdvancing(playoutStartSystemTimeMs: Long) {
            listeners.forEach { it.onPositionAdvancing(playoutStartSystemTimeMs) }
        }

        override fun onUnderrun() {
            listeners.forEach(AudioOutput.Listener::onUnderrun)
        }

        override fun onFailure(error: AndroidUac2PcmTransportException) {
            synchronized(lock) {
                if (failure == null && !released) failure = error.toWriteException()
            }
        }
    }

    init {
        require(!config.isOffload) { "UAC2 PCM output does not support offload" }
        require(!config.isTunneling) { "UAC2 PCM output does not support tunneling" }
        require(!config.usePlaybackParameters) {
            "UAC2 PCM output must not enable Media3 playback-parameter processing"
        }
        require(config.sampleRate > 0) { "PCM sample rate must be positive" }
        require(config.encoding in ANDROID_UAC2_PCM_SOURCE_BYTES_PER_SAMPLE) {
            "UAC2 output supports integer or float PCM input only"
        }
        require(!config.useOffloadGapless) { "UAC2 PCM output does not support offload gapless playback" }

        val channels = Integer.bitCount(config.channelMask)
        require(channels > 0) { "PCM output requires a channel mask" }
        require(transport.sampleRateHz == config.sampleRate) {
            "UAC2 transport sample rate ${transport.sampleRateHz} does not match Media3 ${config.sampleRate}"
        }
        require(transport.channelCount == channels) {
            "UAC2 transport channel count ${transport.channelCount} does not match Media3 $channels"
        }
        val sourceBytesPerSample = ANDROID_UAC2_PCM_SOURCE_BYTES_PER_SAMPLE.getValue(config.encoding)
        require(config.encoding == C.ENCODING_PCM_FLOAT || transport.bytesPerSample == sourceBytesPerSample) {
            "Integer PCM must use the same width as the UAC2 endpoint"
        }
        require(transport.bufferSizeInFrames > 0L) { "UAC2 transport buffer size must be positive" }
        frameSizeBytes = Math.multiplyExact(sourceBytesPerSample, channels)
        sampleRateHz = config.sampleRate
        transport.setListener(transportListener)
    }

    override fun play() = synchronized(lock) {
        ensureNotReleased()
        ensureNoFailure()
        check(!ended) { "Cannot resume a UAC2 stream after EOS; flush or create a new output" }
        transport.play()
    }

    override fun pause() = synchronized(lock) {
        ensureNotReleased()
        ensureNoFailure()
        transport.pause()
    }

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean =
        synchronized(lock) {
            ensureNotReleased()
            ensureNoFailure()
            check(!ended) { "Cannot write PCM after EOS" }
            require(buffer.isDirect) { "Media3 PCM buffers must be direct" }
            require(buffer.order() == ByteOrder.nativeOrder()) { "Media3 PCM buffers must use native byte order" }
            require(encodedAccessUnitCount == 1) { "PCM writes must use one access unit" }
            require(buffer.hasRemaining()) { "PCM writes must contain at least one frame" }
            require(buffer.remaining() % frameSizeBytes == 0) { "PCM buffer ends with a partial frame" }

            val pending = pendingBuffer
            if (pending == null) {
                pendingBuffer = PendingBuffer(buffer, encodedAccessUnitCount, presentationTimeUs)
            } else {
                check(pending.buffer === buffer) { "Media3 replaced an unconsumed PCM buffer" }
                check(pending.encodedAccessUnitCount == encodedAccessUnitCount &&
                    pending.presentationTimeUs == presentationTimeUs
                ) { "Media3 changed the metadata for an unconsumed PCM buffer" }
            }

            val offeredFrames = buffer.remaining() / frameSizeBytes
            val sourcePosition = buffer.position()
            val view = if (inputEncoding == C.ENCODING_PCM_FLOAT) {
                floatPcmToEndpointInteger(
                    source = buffer,
                    frameCount = offeredFrames,
                    channels = transport.channelCount,
                    targetBytesPerSample = transport.bytesPerSample,
                )
            } else {
                buffer.asReadOnlyBuffer().order(ByteOrder.nativeOrder()).apply {
                    limit(sourcePosition + offeredFrames * frameSizeBytes)
                }
            }
            val acceptedFrames = try {
                transport.writeFrames(view, offeredFrames)
            } catch (error: Exception) {
                throw latchFailure(error)
            }
            if (acceptedFrames !in 0..offeredFrames) {
                throw latchFailure(
                    IllegalStateException("UAC2 transport accepted $acceptedFrames of $offeredFrames frames"),
                )
            }

            if (acceptedFrames > 0) {
                val acceptedBytes = Math.multiplyExact(acceptedFrames, frameSizeBytes)
                buffer.position(sourcePosition + acceptedBytes)
                submittedFrames = Math.addExact(submittedFrames, acceptedFrames.toLong())
            }

            val fullyConsumed = acceptedFrames == offeredFrames
            if (fullyConsumed) pendingBuffer = null
            fullyConsumed
        }

    override fun flush() = synchronized(lock) {
        ensureNotReleased()
        ensureNoFailure()
        // The transport contract makes flush discard queued frames, reset its playhead to zero,
        // clear EOS, and leave the stream paused until play() is called again.
        transport.flush()
        pendingBuffer = null
        submittedFrames = 0L
        ended = false
    }

    override fun stop() = synchronized(lock) {
        ensureNotReleased()
        ensureNoFailure()
        if (!ended) {
            check(pendingBuffer == null) { "Cannot signal EOS while a PCM buffer is still backpressured" }
            transport.stopAfterDrain()
            ended = true
        }
    }

    override fun release() = synchronized(lock) {
        if (released) return@synchronized
        released = true
        pendingBuffer = null
        try {
            transport.setListener(null)
        } finally {
            try {
                transport.close()
            } finally {
                listeners.forEach(AudioOutput.Listener::onReleased)
                listeners.clear()
            }
        }
    }

    override fun setVolume(volume: Float) = synchronized(lock) {
        ensureNotReleased()
        require(volume.isFinite() && volume in 0f..1f) { "Volume must be finite and between 0 and 1" }
        if (!transport.supportsHardwareVolume) {
            check(volume == 1f) { "This UAC2 device has no hardware volume control; digital gain is disabled" }
            return@synchronized
        }
        try {
            transport.setHardwareVolume(volume)
        } catch (error: Exception) {
            throw IllegalStateException("UAC2 hardware volume request failed", error)
        }
    }

    override fun isOffloadedPlayback(): Boolean = false

    override fun getAudioSessionId(): Int = transport.audioSessionId

    override fun getSampleRate(): Int = sampleRateHz

    override fun getBufferSizeInFrames(): Long = transport.bufferSizeInFrames

    override fun getPositionUs(): Long = synchronized(lock) {
        if (released) return@synchronized C.TIME_UNSET
        val playedFrames = transport.playedFramesSinceFlush().coerceAtLeast(0L).coerceAtMost(submittedFrames)
        // AudioOutput positions are relative to this output's frame epoch. DefaultAudioSink maps
        // them to media timestamps with its start-media-time and position checkpoints; write()'s
        // presentationTimeUs must not be added here a second time.
        addFramesToTimeUs(0L, playedFrames, sampleRateHz)
    }

    override fun getPlaybackParameters(): PlaybackParameters = synchronized(lock) { playbackParameters }

    override fun isStalled(): Boolean = !released && transport.isStalled()

    override fun addListener(listener: AudioOutput.Listener) = synchronized(lock) {
        listeners.add(listener)
        if (released && listeners.remove(listener)) listener.onReleased()
    }

    override fun removeListener(listener: AudioOutput.Listener) = synchronized(lock) {
        listeners.remove(listener)
        Unit
    }

    override fun setPlaybackParameters(playbackParams: PlaybackParameters) = synchronized(lock) {
        ensureNotReleased()
        require(playbackParams.speed == 1f && playbackParams.pitch == 1f) {
            "UAC2 direct PCM output requires unity playback speed and pitch"
        }
        playbackParameters = PlaybackParameters.DEFAULT
    }

    override fun setOffloadDelayPadding(delayInFrames: Int, paddingInFrames: Int) {
        throw UnsupportedOperationException("UAC2 PCM output does not support offload delay or padding")
    }

    override fun setOffloadEndOfStream() {
        throw UnsupportedOperationException("UAC2 PCM output does not support offload EOS")
    }

    override fun attachAuxEffect(effectId: Int) {
        require(effectId == 0) { "Auxiliary effects are not supported by UAC2 direct PCM output" }
    }

    override fun setAuxEffectSendLevel(level: Float) {
        require(level == 0f) { "Auxiliary effects are not supported by UAC2 direct PCM output" }
    }

    override fun setPreferredDevice(preferredDevice: AudioDeviceInfo?) {
        require(preferredDevice == null) {
            "Android AudioDeviceInfo routing does not apply to a claimed UAC2 USB transport"
        }
    }

    private fun ensureNotReleased() {
        check(!released) { "UAC2 audio output has been released" }
    }

    private fun ensureNoFailure() {
        failure?.let { throw it.copyForReuse() }
    }

    private fun latchFailure(error: Exception): AudioOutput.WriteException {
        val mapped = when (error) {
            is AndroidUac2PcmTransportException -> error.toWriteException()
            else -> AudioOutput.WriteException(ANDROID_UAC2_WRITE_ERROR_UNKNOWN, false).apply {
                initCause(error)
            }
        }
        failure = mapped
        return mapped.copyForReuse()
    }

    private fun AndroidUac2PcmTransportException.toWriteException(): AudioOutput.WriteException {
        val mapped = AudioOutput.WriteException(errorCode, isRecoverable)
        mapped.initCause(this)
        return mapped
    }

    private fun AudioOutput.WriteException.copyForReuse(): AudioOutput.WriteException {
        val mapped = AudioOutput.WriteException(errorCode, isRecoverable)
        mapped.initCause(cause ?: this)
        return mapped
    }

    private fun addFramesToTimeUs(anchorUs: Long, frames: Long, sampleRate: Int): Long {
        val wholeSeconds = frames / sampleRate
        val remainingFrames = frames % sampleRate
        val wholeUs = if (wholeSeconds > Long.MAX_VALUE / 1_000_000L) {
            Long.MAX_VALUE
        } else {
            wholeSeconds * 1_000_000L
        }
        val remainderUs = remainingFrames * 1_000_000L / sampleRate
        val deltaUs = if (wholeUs > Long.MAX_VALUE - remainderUs) Long.MAX_VALUE else wholeUs + remainderUs
        return if (anchorUs > Long.MAX_VALUE - deltaUs) Long.MAX_VALUE else anchorUs + deltaUs
    }

    private companion object {
        const val ANDROID_UAC2_WRITE_ERROR_UNKNOWN = -1
    }
}

/** Converts Media3's float PCM to the selected packed integer subslot using TPDF dither. */
internal fun floatPcmToEndpointInteger(
    source: ByteBuffer,
    frameCount: Int,
    channels: Int,
    targetBytesPerSample: Int,
): ByteBuffer {
    require(source.isDirect) { "PCM conversion requires a direct input buffer" }
    require(source.order() == ByteOrder.nativeOrder()) { "PCM input must use native byte order" }
    require(frameCount >= 0 && channels > 0) { "PCM frame dimensions must be positive" }
    require(targetBytesPerSample in 2..4) { "UAC2 output only supports 16/24/32-bit integer PCM" }
    val requiredInputBytes = Math.multiplyExact(Math.multiplyExact(frameCount, channels), Float.SIZE_BYTES)
    require(requiredInputBytes <= source.remaining()) { "Float PCM slice is shorter than the requested frames" }

    val targetFrameBytes = Math.multiplyExact(channels, targetBytesPerSample)
    val output = ByteBuffer.allocateDirect(Math.multiplyExact(frameCount, targetFrameBytes))
        .order(ByteOrder.nativeOrder())
    val input = source.asReadOnlyBuffer().order(ByteOrder.nativeOrder())
    val bits = targetBytesPerSample * 8
    val scale = 1L shl (bits - 1)
    val minimum = -scale
    val maximum = scale - 1L
    repeat(frameCount * channels) {
        val sample = input.float
        val normalized = when {
            sample.isNaN() -> 0.0
            sample <= -1f -> -1.0
            sample >= 1f -> 1.0
            else -> sample.toDouble()
        }
        // Two independent uniforms produce triangular noise one quantization step peak-to-peak.
        val dither = ThreadLocalRandom.current().nextDouble() - ThreadLocalRandom.current().nextDouble()
        val quantized = floor(normalized * scale.toDouble() + dither + 0.5)
            .toLong()
            .coerceIn(minimum, maximum)
        repeat(targetBytesPerSample) { byteIndex ->
            output.put((quantized shr (byteIndex * 8)).toByte())
        }
    }
    output.flip()
    return output
}
