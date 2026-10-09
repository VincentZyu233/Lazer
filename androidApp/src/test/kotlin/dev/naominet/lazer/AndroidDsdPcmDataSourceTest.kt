package dev.naominet.lazer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.wav.WavExtractor
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class AndroidDsdPcmDataSourceTest {
    @Test
    fun `float WAV header describes interleaved PCM exactly`() {
        val header = buildAndroidDsdWavHeader(
            sampleRate = 176_400,
            channels = 2,
            totalFrames = 10_000,
            sampleDataBytes = 80_000,
        )

        assertEquals(44, header.size)
        assertEquals("RIFF", fourCc(header, 0))
        assertEquals(36L + 80_000, le32(header, 4))
        assertEquals("WAVE", fourCc(header, 8))
        assertEquals(3, le16(header, 20)) // IEEE float
        assertEquals(2, le16(header, 22))
        assertEquals(176_400L, le32(header, 24))
        assertEquals(1_411_200L, le32(header, 28))
        assertEquals(8, le16(header, 32))
        assertEquals(32, le16(header, 34))
        assertEquals("data", fourCc(header, 36))
        assertEquals(80_000L, le32(header, 40))
    }

    @Test
    fun `DoP WAV header advertises packed integer PCM24 carrier words`() {
        val header = buildAndroidDsdWavHeader(
            sampleRate = 176_400,
            channels = 2,
            totalFrames = 10,
            sampleDataBytes = 60,
            pcmEncoding = C.ENCODING_PCM_24BIT,
        )

        assertEquals(44, header.size)
        assertEquals(1, le16(header, 20)) // WAVE_FORMAT_PCM, not IEEE float
        assertEquals(2, le16(header, 22))
        assertEquals(176_400L, le32(header, 24))
        assertEquals(1_058_400L, le32(header, 28))
        assertEquals(6, le16(header, 32))
        assertEquals(24, le16(header, 34))
        assertEquals(60L, le32(header, 40))
    }

    @Test
    fun `large float WAV header uses RF64 ds64 lengths`() {
        val dataBytes = 0x1_0000_0000L
        val frameCount = dataBytes / 8
        val header = buildAndroidDsdWavHeader(
            sampleRate = 192_000,
            channels = 2,
            totalFrames = frameCount,
            sampleDataBytes = dataBytes,
        )

        assertEquals(80, header.size)
        assertEquals("RF64", fourCc(header, 0))
        assertEquals(0xffff_ffffL, le32(header, 4))
        assertEquals("ds64", fourCc(header, 12))
        assertEquals(28L, le32(header, 16))
        assertEquals(header.size.toLong() + dataBytes - 8L, le64(header, 20))
        assertEquals(dataBytes, le64(header, 28))
        assertEquals(frameCount, le64(header, 36))
        assertEquals("fmt ", fourCc(header, 48))
        assertEquals("data", fourCc(header, 72))
        assertEquals(0xffff_ffffL, le32(header, 76))
    }

    @Test
    fun `PCM header rejects inconsistent frame counts and invalid rates`() {
        assertFailsWith<IllegalArgumentException> {
            buildAndroidDsdWavHeader(176_400, 2, 10, 79)
        }
        assertFailsWith<IllegalArgumentException> {
            buildAndroidDsdWavHeader(0, 2, 10, 80)
        }
    }

    @Test
    fun `Media3 WavExtractor reads virtual DSD PCM DataSource samples`() {
        val pcm = floatBytes(-1.0f, -0.25f, 0.5f, 0.999f)
        val source = AndroidFloatPcmWavDataSource(
            FakeFloatPcmDecoder(176_400, 2, 2, pcm),
            null,
        )
        val length = source.openVirtualStream()
        val input = DefaultExtractorInput(source, 0L, length)
        val extractor = WavExtractor()
        val output = RecordingExtractorOutput()
        try {
            assertTrue(extractor.sniff(input), "Media3 did not recognize the virtual WAV")
            input.resetPeekPosition()
            extractor.init(output)
            val position = PositionHolder()
            var result = Extractor.RESULT_CONTINUE
            var reads = 0
            while (result == Extractor.RESULT_CONTINUE && reads < 16) {
                result = extractor.read(input, position)
                reads++
                if (result != Extractor.RESULT_END_OF_INPUT) {
                    assertEquals(Extractor.RESULT_CONTINUE, result, "Unexpected extractor seek request")
                }
            }

            assertEquals(Extractor.RESULT_END_OF_INPUT, result)
            assertEquals(176_400, output.track.format?.sampleRate)
            assertEquals(2, output.track.format?.channelCount)
            assertEquals(C.ENCODING_PCM_FLOAT, output.track.format?.pcmEncoding)
            assertEquals(pcm.toList(), output.track.sampleBytes.toByteArray().toList())
            assertEquals(pcm.size, output.track.lastSampleSize)
        } finally {
            extractor.release()
            source.close()
        }
    }

    @Test
    fun `Media3 WavExtractor reads local DSD through the application DataSource factory`() {
        val pcm = floatBytes(-1.0f, -0.25f, 0.5f, 0.999f)
        val file = File.createTempFile("lazer-dsd-", ".dsf").apply {
            writeBytes(byteArrayOf(0x44, 0x53, 0x44, 0x20))
        }
        val decoder = FakeFloatPcmDecoder(176_400, 2, 2, pcm)
        val source = AndroidDsdPcmDataSourceFactory(
            context = RuntimeEnvironment.getApplication(),
            upstreamFactory = DataSource.Factory {
                error("A local DSF URI should not be delegated to the upstream source.")
            },
            decoderFactory = AndroidDsdPcmDecoderFactory { _, _, _, outputMode ->
                assertEquals(AndroidDsdOutputMode.Pcm, outputMode)
                decoder
            },
        ).createDataSource()
        val extractor = WavExtractor()
        val output = RecordingExtractorOutput()

        try {
            val inputLength = source.open(DataSpec.Builder().setUri(Uri.fromFile(file)).build())
            val input = DefaultExtractorInput(source, 0L, inputLength)
            assertTrue(extractor.sniff(input), "Media3 did not recognize the factory's virtual WAV")
            input.resetPeekPosition()
            extractor.init(output)
            readToEnd(extractor, input)

            assertEquals(176_400, output.track.format?.sampleRate)
            assertEquals(2, output.track.format?.channelCount)
            assertEquals(C.ENCODING_PCM_FLOAT, output.track.format?.pcmEncoding)
            assertEquals(pcm.toList(), output.track.sampleBytes.toByteArray().toList())
        } finally {
            extractor.release()
            source.close()
            file.delete()
        }
    }

    @Test
    fun `selected DoP mode preserves packed PCM24 through the virtual WAV extractor`() {
        val carrier = byteArrayOf(
            0x12, 0x34, 0x05, 0x56, 0x78, 0x05,
            0x9a.toByte(), 0xbc.toByte(), 0xfa.toByte(), 0xde.toByte(), 0xf0.toByte(), 0xfa.toByte(),
        )
        val file = File.createTempFile("lazer-dsd-", ".dsf").apply {
            writeBytes(byteArrayOf(0x44, 0x53, 0x44, 0x20))
        }
        val decoder = FakeFloatPcmDecoder(
            sampleRateHz = 176_400,
            channelCount = 2,
            totalFrames = 2,
            pcm = carrier,
            pcmEncoding = C.ENCODING_PCM_24BIT,
        )
        val source = AndroidDsdPcmDataSourceFactory(
            context = RuntimeEnvironment.getApplication(),
            upstreamFactory = DataSource.Factory {
                error("A local DSF URI should not be delegated to the upstream source.")
            },
            decoderFactory = AndroidDsdPcmDecoderFactory { _, _, _, outputMode ->
                assertEquals(AndroidDsdOutputMode.DoP, outputMode)
                decoder
            },
            outputMode = AndroidDsdOutputMode.DoP,
        ).createDataSource()
        val extractor = WavExtractor()
        val output = RecordingExtractorOutput()

        try {
            val inputLength = source.open(DataSpec.Builder().setUri(Uri.fromFile(file)).build())
            val input = DefaultExtractorInput(source, 0L, inputLength)
            assertTrue(extractor.sniff(input), "Media3 did not recognize the virtual DoP WAV")
            input.resetPeekPosition()
            extractor.init(output)
            readToEnd(extractor, input)

            assertEquals(176_400, output.track.format?.sampleRate)
            assertEquals(2, output.track.format?.channelCount)
            assertEquals(C.ENCODING_PCM_24BIT, output.track.format?.pcmEncoding)
            assertEquals(carrier.toList(), output.track.sampleBytes.toByteArray().toList())
        } finally {
            extractor.release()
            source.close()
            file.delete()
        }
    }

    @Test
    fun `Media3 WAV seek reopens the virtual stream at the exact PCM byte`() {
        val sampleRate = 192_000
        val channelCount = 2
        val frameCount = 4_096
        val pcm = floatRampBytes(frameCount, channelCount)
        val firstDecoder = FakeFloatPcmDecoder(sampleRate, channelCount, frameCount.toLong(), pcm)
        val firstSource = AndroidFloatPcmWavDataSource(firstDecoder, null)
        val streamLength = firstSource.openVirtualStream()
        val firstInput = DefaultExtractorInput(firstSource, 0L, streamLength)
        val extractor = WavExtractor()
        val output = RecordingExtractorOutput()

        try {
            assertTrue(extractor.sniff(firstInput), "Media3 did not recognize the virtual WAV")
            firstInput.resetPeekPosition()
            extractor.init(output)
            readToEnd(extractor, firstInput)

            val seekMap = output.seekMap ?: error("Media3 did not publish a WAV seek map")
            assertTrue(seekMap.isSeekable)
            val requestedTimeUs = 9_999L
            val seekPoint = seekMap.getSeekPoints(requestedTimeUs).first
            val sampleDataOffset = seekPoint.position - 44L
            val frameBytes = channelCount * Float.SIZE_BYTES
            assertTrue(sampleDataOffset >= 0L)
            assertEquals(0L, sampleDataOffset % frameBytes)
            assertTrue(sampleDataOffset < pcm.size)

            val seekingDecoder = FakeFloatPcmDecoder(
                sampleRate,
                channelCount,
                frameCount.toLong(),
                pcm,
            )
            val seekSource = AndroidFloatPcmWavDataSource(seekingDecoder, null)
            val remainingLength = seekSource.openVirtualStream(seekPoint.position)
            val seekInput = DefaultExtractorInput(seekSource, seekPoint.position, streamLength)
            output.track.sampleBytes.reset()
            extractor.seek(seekPoint.position, seekPoint.timeUs)
            try {
                readToEnd(extractor, seekInput)

                assertEquals(
                    pcm.copyOfRange(sampleDataOffset.toInt(), pcm.size).toList(),
                    output.track.sampleBytes.toByteArray().toList(),
                )
                val expectedSeekMillis =
                    (sampleDataOffset / frameBytes * 1_000L) / sampleRate
                assertEquals(expectedSeekMillis, seekingDecoder.lastSeekMillis)
                assertEquals(pcm.size.toLong() - sampleDataOffset, remainingLength)
            } finally {
                seekSource.close()
            }
        } finally {
            extractor.release()
            firstSource.close()
        }
    }

    @Test
    fun `Media3 WavExtractor recognizes the virtual RF64 header`() {
        val dataBytes = 0x1_0000_0000L
        val frames = dataBytes / (2L * Float.SIZE_BYTES)
        val source = AndroidFloatPcmWavDataSource(
            FakeFloatPcmDecoder(192_000, 2, frames, byteArrayOf()),
            null,
        )
        val length = source.openVirtualStream()
        val input = DefaultExtractorInput(source, 0L, length)
        try {
            assertTrue(WavExtractor().sniff(input), "Media3 did not recognize the RF64 container")
        } finally {
            source.close()
        }
    }

    @Test
    fun `virtual WAV source delivers every declared byte to reads spanning several PCM fills`() {
        val frameCount = 8_192
        val pcm = floatRampBytes(frameCount, 2)
        val source = AndroidFloatPcmWavDataSource(
            FakeFloatPcmDecoder(176_400, 2, frameCount.toLong(), pcm),
            null,
        )
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        val length = source.openVirtualStream()

        try {
            assertEquals(44L + pcm.size, length)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                assertTrue(count > 0, "The virtual PCM source stopped making progress.")
                output.write(buffer, 0, count)
            }
        } finally {
            source.close()
        }

        val bytes = output.toByteArray()
        assertEquals(length, bytes.size.toLong())
        assertContentEquals(pcm, bytes.copyOfRange(44, bytes.size))
    }

    private fun fourCc(bytes: ByteArray, offset: Int): String =
        bytes.copyOfRange(offset, offset + 4).decodeToString()

    private fun le16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun le32(bytes: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { value, index ->
            value or ((bytes[offset + index].toLong() and 0xffL) shl (index * 8))
        }

    private fun le64(bytes: ByteArray, offset: Int): Long =
        (0 until 8).fold(0L) { value, index ->
            value or ((bytes[offset + index].toLong() and 0xffL) shl (index * 8))
        }

    private fun floatBytes(vararg values: Float): ByteArray = ByteBuffer
        .allocate(values.size * Float.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply { values.forEach(::putFloat) }
        .array()

    private fun floatRampBytes(frameCount: Int, channelCount: Int): ByteArray = ByteBuffer
        .allocate(frameCount * channelCount * Float.SIZE_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            repeat(frameCount * channelCount) { index ->
                putFloat(index.toFloat() / (frameCount * channelCount))
            }
        }
        .array()

    private class FakeFloatPcmDecoder(
        override val sampleRateHz: Int,
        override val channelCount: Int,
        override val totalFrames: Long,
        private val pcm: ByteArray,
        override val pcmEncoding: Int = C.ENCODING_PCM_FLOAT,
    ) : AndroidFloatPcmDecoder {
        private var offset = 0
        var lastSeekMillis: Long? = null
            private set

        override fun read(destination: ByteArray, capacityFrames: Int): Int {
            val bytesPerSample = if (pcmEncoding == C.ENCODING_PCM_24BIT) 3 else Float.SIZE_BYTES
            val frameBytes = channelCount * bytesPerSample
            val count = minOf(capacityFrames * frameBytes, pcm.size - offset)
            if (count <= 0) return 0
            pcm.copyInto(destination, 0, offset, offset + count)
            offset += count
            return count / frameBytes
        }

        override fun seekToMillis(positionMillis: Long): Int {
            if (positionMillis < 0L) return -1
            val frame = (positionMillis * sampleRateHz + 500L) / 1_000L
            val bytesPerSample = if (pcmEncoding == C.ENCODING_PCM_24BIT) 3 else Float.SIZE_BYTES
            val byteOffset = frame * channelCount * bytesPerSample
            if (byteOffset > pcm.size) return -1
            offset = byteOffset.toInt()
            lastSeekMillis = positionMillis
            return 0
        }

        override fun lastError(): String = "Fake decoder does not seek."

        override fun close() = Unit
    }

    private class RecordingExtractorOutput : ExtractorOutput {
        val track = RecordingTrackOutput()
        var seekMap: SeekMap? = null

        override fun track(id: Int, type: Int): TrackOutput = track

        override fun endTracks() = Unit

        override fun seekMap(seekMap: SeekMap) {
            this.seekMap = seekMap
        }
    }

    private class RecordingTrackOutput : TrackOutput {
        var format: Format? = null
        val sampleBytes = ByteArrayOutputStream()
        var lastSampleSize = 0

        override fun format(format: Format) {
            this.format = format
        }

        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int {
            val bytes = ByteArray(length)
            val count = input.read(bytes, 0, length)
            if (count == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT
                throw EOFException("Unexpected end while Media3 was reading a sample.")
            }
            if (count > 0) sampleBytes.write(bytes, 0, count)
            return count
        }

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) {
            val bytes = ByteArray(length)
            data.readBytes(bytes, 0, length)
            sampleBytes.write(bytes)
        }

        override fun sampleMetadata(
            timeUs: Long,
            flags: Int,
            size: Int,
            offset: Int,
            cryptoData: TrackOutput.CryptoData?,
        ) {
            lastSampleSize = size
        }
    }

    private fun readToEnd(extractor: WavExtractor, input: DefaultExtractorInput) {
        var result = Extractor.RESULT_CONTINUE
        var reads = 0
        while (result == Extractor.RESULT_CONTINUE && reads < 64) {
            result = extractor.read(input, PositionHolder())
            reads++
            if (result != Extractor.RESULT_END_OF_INPUT) {
                assertEquals(Extractor.RESULT_CONTINUE, result, "Unexpected extractor seek request")
            }
        }
        assertEquals(Extractor.RESULT_END_OF_INPUT, result)
    }

}
