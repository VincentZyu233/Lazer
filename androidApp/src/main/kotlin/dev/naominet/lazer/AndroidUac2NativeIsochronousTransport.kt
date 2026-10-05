package dev.naominet.lazer

import android.hardware.usb.UsbDeviceConnection
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** Native libusb USB 2.0 isochronous PCM transport for an Android session that has already
 * selected its configuration, claimed AudioControl/AudioStreaming, set the UAC2 Clock Source,
 * and selected the plan's streaming alternate setting. This class owns that configured session:
 * every exit closes the native libusb wrapper first and the Java connection second.
 *
 * The implementation uses a bounded native PCM queue and whole-frame, nonblocking writes. Startup
 * is paused; queued writes remain buffered until [play]. Pausing sends paced zero packets and
 * retains queued PCM. [flush] waits for in-flight packets, discards the queue, resets the playhead,
 * and leaves the endpoint paused. This is an async USB transport foundation; it makes no claim of
 * DAC compatibility, bit-perfect playback, or verified physical output.
 */
internal class AndroidUac2NativeIsochronousTransport(
    private val playbackSession: AndroidUac2PlaybackSession,
    private val plan: AndroidUac2PlaybackStreamPlan,
) : AndroidUac2PcmTransport {
    private val lock = Any()
    private val bridge = AndroidUac2NativeIsoBridge()
    private val monitor: ScheduledExecutorService
    private var listener: AndroidUac2PcmTransport.Listener? = null
    private var closed = false
    private var playing = false
    private var eosStopped = false
    private var failureDelivered = false
    private var positionAdvancingDelivered = false
    private var lastUnderrunPackets = 0
    private var lastPlayedFrames = 0L

    override val sampleRateHz: Int = Math.toIntExact(plan.sampleRateHz)
    override val channelCount: Int = plan.channelCount
    override val bytesPerSample: Int = plan.subslotSizeBytes
    override val bufferSizeInFrames: Long
    override val supportsHardwareVolume: Boolean = false

    init {
        require(sampleRateHz in 8000..768000) { "UAC2 sample rate is outside the supported USB 2.0 range" }
        require(channelCount > 0 && channelCount <= 32) { "UAC2 channel count is unsupported" }
        require(bytesPerSample in 2..4 && plan.validBitResolution == bytesPerSample * 8) {
            "This transport accepts packed 16/24/32-bit PCM matching the endpoint subslot width"
        }
        require(plan.dataEndpoint.synchronizationType in 1..3) {
            "UAC2 endpoint synchronization type is unsupported"
        }
        require(
            plan.dataEndpoint.synchronizationType != 1 || plan.feedbackEndpoint != null,
        ) { "Asynchronous UAC2 playback requires an explicit feedback endpoint" }
        require(playbackSession.state == AndroidUac2PlaybackSessionState.ConfiguredNotStreaming) {
            "Configure and claim the Android UAC2 session before creating the native transport"
        }

        try {
            val connection: UsbDeviceConnection = playbackSession.requireConfiguredConnection()
            bridge.open(connection)
            bridge.start(plan)
            bufferSizeInFrames = bridge.bufferSizeInFrames()
            check(bufferSizeInFrames > 0L) { "Native UAC2 queue was not initialized" }
        } catch (error: Throwable) {
            runCatching { bridge.close() }.exceptionOrNull()?.let(error::addSuppressed)
            runCatching { playbackSession.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }

        monitor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "lazer-uac2-status").apply { isDaemon = true }
        }
        monitor.scheduleWithFixedDelay(::pollStatus, 100, 100, TimeUnit.MILLISECONDS)
    }

    override fun setListener(listener: AndroidUac2PcmTransport.Listener?) {
        synchronized(lock) {
            ensureOpen()
            this.listener = listener
        }
    }

    override fun play() {
        synchronized(lock) {
            ensureOpen()
            ensureHealthy()
            check(!eosStopped) { "A drained UAC2 session must be flushed before it can resume" }
            bridge.setPlaying(true)
            playing = true
        }
    }

    override fun pause() {
        synchronized(lock) {
            ensureOpen()
            if (eosStopped) return
            bridge.setPlaying(false)
            playing = false
        }
    }

    override fun writeFrames(pcm: ByteBuffer, frameCount: Int): Int {
        if (frameCount == 0) return 0
        require(frameCount > 0) { "PCM frame count must be positive" }
        require(pcm.isDirect) { "The native USB transport requires a direct PCM buffer" }
        val bytesPerFrame = Math.multiplyExact(channelCount, bytesPerSample)
        val byteLength = Math.multiplyExact(frameCount, bytesPerFrame)
        require(byteLength <= pcm.remaining()) { "PCM slice is shorter than the requested frame count" }
        val acceptedBytes = synchronized(lock) {
            ensureOpen()
            ensureHealthy()
            check(!eosStopped) { "PCM cannot be written after end-of-stream" }
            try {
                bridge.write(pcm, pcm.position(), byteLength, timeoutMs = 0)
            } catch (error: Exception) {
                throw transportFailure(error)
            }
        }
        if (acceptedBytes < 0 || acceptedBytes % bytesPerFrame != 0 || acceptedBytes > byteLength) {
            throw AndroidUac2PcmTransportException(
                NATIVE_TRANSPORT_IO_ERROR,
                false,
                "Native UAC2 queue returned an invalid byte count $acceptedBytes",
            )
        }
        return acceptedBytes / bytesPerFrame
    }

    override fun flush() {
        synchronized(lock) {
            ensureOpen()
            ensureHealthy()
            try {
                if (eosStopped) {
                    bridge.start(plan)
                    eosStopped = false
                }
                check(bridge.flush(FLUSH_TIMEOUT_MS)) { "Native UAC2 flush timed out or failed" }
                bridge.setPlaying(false)
                playing = false
                positionAdvancingDelivered = false
                lastPlayedFrames = 0L
            } catch (error: Exception) {
                throw transportFailure(error)
            }
        }
    }

    override fun stopAfterDrain() {
        synchronized(lock) {
            ensureOpen()
            ensureHealthy()
            if (eosStopped) return
            try {
                check(bridge.stop(drain = true, timeoutMs = DRAIN_TIMEOUT_MS)) {
                    "Native UAC2 end-of-stream drain timed out or failed"
                }
                playing = false
                eosStopped = true
            } catch (error: Exception) {
                throw transportFailure(error)
            }
        }
    }

    override fun playedFramesSinceFlush(): Long = synchronized(lock) {
        if (closed) return@synchronized 0L
        bridge.playedFramesSinceFlush().coerceAtLeast(0L)
    }

    override fun isStalled(): Boolean = synchronized(lock) {
        !closed && bridge.isStalled()
    }

    override fun setHardwareVolume(volume: Float) {
        require(volume.isFinite() && volume in 0f..1f) { "Hardware volume must be between zero and one" }
        if (volume != 1f) {
            throw AndroidUac2PcmTransportException(
                NATIVE_TRANSPORT_NOT_SUPPORTED,
                false,
                "This UAC2 session has no verified Feature Unit hardware-volume control; digital gain is disabled",
            )
        }
    }

    /** Device-detach owners must call this before AndroidUac2PlaybackSession.onDeviceDetached(). */
    fun onDeviceDetached() = close(detached = true)

    override fun close() = close(detached = false)

    private fun close(detached: Boolean) {
        val closeFailure = synchronized(lock) {
            if (closed) return
            closed = true
            monitor.shutdownNow()
            var failure: Throwable? = null
            try {
                // Native close performs stop(false), cancel/drain callbacks, joins the event thread,
                // and closes libusb before the playback session releases the UsbDeviceConnection FD.
                bridge.close()
            } catch (error: Throwable) {
                failure = error
            }
            try {
                if (detached) playbackSession.onDeviceDetached() else playbackSession.close()
            } catch (error: Throwable) {
                val prior = failure
                if (prior == null) failure = error else prior.addSuppressed(error)
            }
            failure
        }
        closeFailure?.let { throw it }
    }

    private fun pollStatus() {
        var underrunListener: AndroidUac2PcmTransport.Listener? = null
        var failureListener: AndroidUac2PcmTransport.Listener? = null
        var transportError: AndroidUac2PcmTransportException? = null
        var positionListener: AndroidUac2PcmTransport.Listener? = null
        var positionTimeMs = 0L
        synchronized(lock) {
            if (closed) return
            val errorCode = runCatching { bridge.errorCode() }.getOrElse {
                NATIVE_TRANSPORT_IO_ERROR
            }
            val errorMessage = runCatching { bridge.lastError() }.getOrDefault("")
            if (!failureDelivered && (errorCode < 0 || errorMessage.isNotBlank())) {
                failureDelivered = true
                failureListener = listener
                transportError = AndroidUac2PcmTransportException(
                    errorCode.takeIf { it < 0 } ?: NATIVE_TRANSPORT_IO_ERROR,
                    false,
                    errorMessage.ifBlank { "Native UAC2 transfer failed" },
                )
            }

            val underruns = runCatching { bridge.underrunPackets() }.getOrDefault(lastUnderrunPackets)
            if (underruns > lastUnderrunPackets) underrunListener = listener
            lastUnderrunPackets = maxOf(lastUnderrunPackets, underruns)

            val playedFrames = runCatching { bridge.playedFramesSinceFlush() }.getOrDefault(lastPlayedFrames)
            if (playing && playedFrames > lastPlayedFrames && !positionAdvancingDelivered) {
                positionAdvancingDelivered = true
                positionListener = listener
                positionTimeMs = SystemClock.elapsedRealtime()
            }
            lastPlayedFrames = playedFrames
        }
        transportError?.let { failureListener?.onFailure(it) }
        underrunListener?.onUnderrun()
        if (positionListener != null) positionListener?.onPositionAdvancing(positionTimeMs)
    }

    private fun ensureOpen() = check(!closed) { "Native UAC2 transport is closed" }

    private fun ensureHealthy() {
        if (failureDelivered) throw AndroidUac2PcmTransportException(
            NATIVE_TRANSPORT_IO_ERROR,
            false,
            "Native UAC2 transport has failed",
        )
        val errorCode = bridge.errorCode()
        if (errorCode < 0) {
            throw AndroidUac2PcmTransportException(errorCode, false, bridge.lastError())
        }
    }

    private fun transportFailure(error: Exception) = when (error) {
        is AndroidUac2PcmTransportException -> error
        else -> AndroidUac2PcmTransportException(
            NATIVE_TRANSPORT_IO_ERROR,
            false,
            error.message ?: "Native UAC2 operation failed",
            error,
        )
    }

    private companion object {
        const val FLUSH_TIMEOUT_MS = 5_000
        const val DRAIN_TIMEOUT_MS = 10_000
        const val NATIVE_TRANSPORT_IO_ERROR = -1
        const val NATIVE_TRANSPORT_NOT_SUPPORTED = -12
    }
}
