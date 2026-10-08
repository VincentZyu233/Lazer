package dev.naominet.lazer

import android.hardware.usb.UsbDeviceConnection
import java.nio.ByteBuffer

/** JNI boundary for the native UAC2 isochronous pump. It borrows the file descriptor owned by
 * [UsbDeviceConnection]; callers must close this bridge before closing the Android playback
 * session that owns that connection. Native code never configures USB, changes the clock, claims
 * interfaces, or closes the borrowed descriptor.
 */
internal class AndroidUac2NativeIsoBridge {
    private var handle = 0L

    init {
        loadLibrary()
    }

    /** Wraps the already configured connection. The connection must outlive [close]. */
    @Synchronized
    fun open(connection: UsbDeviceConnection) {
        check(handle == 0L) { "Native UAC2 bridge is already open" }
        handle = openNative(connection.fileDescriptor)
        check(handle != 0L) { "Native UAC2 bridge returned an empty handle" }
    }

    /** Starts transfers for the descriptor-backed plan already applied by Android's USB session. */
    @Synchronized
    fun start(plan: AndroidUac2PlaybackStreamPlan, doP: Boolean = false) {
        nativeHandle()
        val sampleRate = Math.toIntExact(plan.sampleRateHz)
        val feedback = plan.feedbackEndpoint
        val config = intArrayOf(
            plan.configurationValue,
            plan.interfaceNumber,
            plan.alternateSetting,
            sampleRate,
            plan.channelCount,
            plan.channelConfig.toInt(),
            plan.subslotSizeBytes,
            plan.validBitResolution,
            plan.dataEndpoint.address,
            plan.dataEndpoint.synchronizationType,
            plan.dataEndpoint.maximumPacketSizeBytes,
            plan.dataEndpoint.transactionsPerMicroframe,
            plan.dataEndpoint.interval,
            feedback?.address ?: 0,
            feedback?.maximumPacketSizeBytes ?: 0,
            feedback?.transactionsPerMicroframe ?: 0,
            feedback?.interval ?: 0,
            if (doP) 1 else 0,
        )
        check(startNative(handle, config) == 0) { "Native UAC2 transfer start failed" }
    }

    /** Copies whole PCM frames into the bounded native queue. Returns accepted bytes, or zero when
     * backpressured. A positive timeout waits for at least one frame of free space, then accepts
     * as much as fits; a zero timeout is nonblocking. The buffer must be direct.
     */
    @Synchronized
    fun write(
        pcm: ByteBuffer,
        byteOffset: Int,
        byteLength: Int,
        timeoutMs: Int = 0,
    ): Int = writeNative(nativeHandle(), pcm, byteOffset, byteLength, timeoutMs)

    @Synchronized
    fun setPlaying(playing: Boolean) = setPlayingNative(nativeHandle(), playing)

    @Synchronized
    fun flush(timeoutMs: Int): Boolean = flushNative(nativeHandle(), timeoutMs)

    @Synchronized
    fun stop(drain: Boolean, timeoutMs: Int): Boolean =
        stopNative(nativeHandle(), drain, timeoutMs)

    @Synchronized
    fun underrunPackets(): Int = underrunPacketsNative(nativeHandle())

    @Synchronized
    fun errorCode(): Int = errorCodeNative(nativeHandle())

    @Synchronized
    fun playedFramesSinceFlush(): Long = playedFramesNative(nativeHandle())

    @Synchronized
    fun bufferSizeInFrames(): Long = bufferSizeFramesNative(nativeHandle())

    @Synchronized
    fun isStalled(): Boolean = isStalledNative(nativeHandle())

    @Synchronized
    fun lastError(): String = lastErrorNative(nativeHandle()).orEmpty()

    /** Cancels and drains transfers, joins the libusb event thread, then closes libusb. The
     * UsbDeviceConnection remains open and must be closed by its Java owner after this returns.
     */
    @Synchronized
    fun close() {
        val activeHandle = handle
        if (activeHandle == 0L) return
        handle = 0L
        closeNative(activeHandle)
    }

    private fun nativeHandle(): Long = checkNotNull(handle.takeIf { it != 0L }) {
        "Native UAC2 bridge is not open"
    }

    private external fun openNative(usbFileDescriptor: Int): Long
    private external fun libusbVersion(): String
    private external fun startNative(handle: Long, config: IntArray): Int
    private external fun writeNative(
        handle: Long,
        pcm: ByteBuffer,
        byteOffset: Int,
        byteLength: Int,
        timeoutMs: Int,
    ): Int
    private external fun flushNative(handle: Long, timeoutMs: Int): Boolean
    private external fun stopNative(handle: Long, drain: Boolean, timeoutMs: Int): Boolean
    private external fun setPlayingNative(handle: Long, playing: Boolean)
    private external fun underrunPacketsNative(handle: Long): Int
    private external fun errorCodeNative(handle: Long): Int
    private external fun playedFramesNative(handle: Long): Long
    private external fun bufferSizeFramesNative(handle: Long): Long
    private external fun isStalledNative(handle: Long): Boolean
    private external fun lastErrorNative(handle: Long): String?
    private external fun closeNative(handle: Long)

    internal fun versionForInstrumentationTest(): String = libusbVersion()

    internal fun rejectInvalidFileDescriptorForInstrumentationTest() = openNative(-1)

    internal fun packetizerSelfTestForInstrumentationTest(): Boolean = packetizerSelfTest()

    private external fun packetizerSelfTest(): Boolean

    companion object {
        @Volatile
        private var libraryLoaded = false

        @Synchronized
        private fun loadLibrary() {
            if (libraryLoaded) return
            System.loadLibrary("lazer-uac2")
            libraryLoaded = true
        }
    }
}
