package dev.naominet.lazer

import java.io.Closeable
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem

/** The small output surface needed by the decoder, independent of shared or exclusive mode. */
internal interface DesktopPcmAudioOutput : Closeable {
    val isOpen: Boolean
    val isRunning: Boolean
    val availableBytes: Int
    val bufferSizeBytes: Int
    val longFramePosition: Long
    val usesSoftwareVolume: Boolean

    fun start()
    fun stop()
    fun flush()
    fun write(buffer: ByteArray, offset: Int, length: Int): Int
    fun setVolume(value: Float)
}

/** Existing Java Sound path used whenever exclusive output is disabled. */
internal class JavaSoundPcmAudioOutput(format: AudioFormat) : DesktopPcmAudioOutput {
    private val line = AudioSystem.getSourceDataLine(format).apply { open(format) }

    override val isOpen: Boolean
        get() = line.isOpen
    override val isRunning: Boolean
        get() = line.isRunning
    override val availableBytes: Int
        get() = line.available()
    override val bufferSizeBytes: Int
        get() = line.bufferSize
    override val longFramePosition: Long
        get() = line.longFramePosition
    // MASTER_GAIN is optional on Windows SourceDataLine implementations. Applying volume to PCM
    // keeps the control functional for both shared Java Sound and exclusive WASAPI output.
    override val usesSoftwareVolume: Boolean = true

    override fun start() = line.start()
    override fun stop() = line.stop()
    override fun flush() = line.flush()
    override fun write(buffer: ByteArray, offset: Int, length: Int): Int = line.write(buffer, offset, length)

    override fun setVolume(value: Float) = Unit

    override fun close() = line.close()
}

internal fun isWindowsDesktop(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    osName.contains("windows", ignoreCase = true)

