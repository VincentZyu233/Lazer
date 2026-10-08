package dev.naominet.lazer

import java.nio.file.Files
import java.nio.file.Path

/** Builds small, repeatable PCM fixtures for desktop audio-path tests. */
internal object DesktopPcmTestWave {
    const val DURATION_MILLISECONDS = LazerPcmTestTone.DURATION_MILLISECONDS
    const val TONE_FREQUENCY_HZ = LazerPcmTestTone.FREQUENCY_HZ
    const val PEAK_AMPLITUDE_DBFS = LazerPcmTestTone.PEAK_AMPLITUDE_DBFS
    const val FADE_MILLISECONDS = LazerPcmTestTone.FADE_MILLISECONDS

    val supportedSampleRatesHz: List<Int> = LazerPcmTestTone.supportedSampleRatesHz
    val supportedChannelCounts: List<Int> = listOf(1, 2)
    val supportedBitDepths: List<Int> = LazerPcmTestTone.supportedBitDepths

    private const val RIFF_HEADER_BYTES = 44

    /**
     * Returns a 2 s, signed integer PCM RIFF/WAVE test tone at about -40 dBFS.
     * A 10 ms linear fade at both ends prevents abrupt playback edges.
     */
    fun create(sampleRateHz: Int, channels: Int, bitDepth: Int): ByteArray {
        validateFormat(sampleRateHz, channels, bitDepth)

        val bytesPerSample = bitDepth / Byte.SIZE_BITS
        val blockAlign = channels * bytesPerSample
        val frameCount = sampleRateHz * DURATION_MILLISECONDS / 1_000
        val dataSize = frameCount * blockAlign
        val riffSize = RIFF_HEADER_BYTES - 8 + dataSize
        val byteRate = sampleRateHz * blockAlign
        val result = ByteArray(RIFF_HEADER_BYTES + dataSize)
        var offset = 0

        fun writeAscii(value: String) {
            value.forEach { result[offset++] = it.code.toByte() }
        }

        fun writeUInt16LE(value: Int) {
            result[offset++] = value.toByte()
            result[offset++] = (value ushr 8).toByte()
        }

        fun writeUInt32LE(value: Int) {
            result[offset++] = value.toByte()
            result[offset++] = (value ushr 8).toByte()
            result[offset++] = (value ushr 16).toByte()
            result[offset++] = (value ushr 24).toByte()
        }

        writeAscii("RIFF")
        writeUInt32LE(riffSize)
        writeAscii("WAVE")
        writeAscii("fmt ")
        writeUInt32LE(16) // PCM fmt chunk has no extension.
        writeUInt16LE(1) // WAVE_FORMAT_PCM
        writeUInt16LE(channels)
        writeUInt32LE(sampleRateHz)
        writeUInt32LE(byteRate)
        writeUInt16LE(blockAlign)
        writeUInt16LE(bitDepth)
        writeAscii("data")
        writeUInt32LE(dataSize)
        val pcm = LazerPcmTestTone.createPcm(sampleRateHz, channels, bitDepth)
        pcm.copyInto(destination = result, destinationOffset = offset)
        offset += pcm.size
        check(offset == result.size) { "WAV fixture size calculation did not match written data" }
        return result
    }

    /** Writes [create] output to [path], replacing an existing fixture file. */
    fun write(path: Path, sampleRateHz: Int, channels: Int, bitDepth: Int): Path {
        Files.write(path, create(sampleRateHz, channels, bitDepth))
        return path
    }

    private fun validateFormat(sampleRateHz: Int, channels: Int, bitDepth: Int) {
        require(sampleRateHz in supportedSampleRatesHz) {
            "Unsupported PCM fixture sample rate: $sampleRateHz Hz"
        }
        require(channels in supportedChannelCounts) {
            "PCM fixture must be mono or stereo; got $channels channels"
        }
        require(bitDepth in supportedBitDepths) {
            "PCM fixture bit depth must be 16, 24, or 32; got $bitDepth"
        }
    }
}
