package dev.naominet.lazer

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the packaged Android JNI decoder without depending on an audio device. */
@RunWith(AndroidJUnit4::class)
class AndroidDsdPcmNativeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext = instrumentation.targetContext

    @Test
    fun productionJniDecodesDsfRawDffAndVerbatimDstAndSeeksOnPcmFrameGrid() {
        val expectedFrames = mapOf(
            "dsd64_test.dsf" to 44_100L,
            "dff64_test.dff" to 44_100L,
            "dst64_verbatim.dff" to 176_400L,
        )
        val decoded = expectedFrames.mapValues { (name, frames) ->
            val stream = readVirtualWav(name, position = 0L)
            val parsed = parseFloatWav(stream.bytes)
            assertEquals(176_400, parsed.sampleRate, "$name PCM rate")
            assertEquals(2, parsed.channels, "$name channel count")
            assertEquals(frames, parsed.frameCount, "$name decoded frame count")
            assertPcmIsFiniteAndAudible(name, parsed.pcm)
            assertSeekMatchesFullDecode(name, parsed)
            parsed
        }

        assertPcmClose(
            "DSF and raw DFF carry the same DSD channel bits",
            decoded.getValue("dsd64_test.dsf").pcm,
            decoded.getValue("dff64_test.dff").pcm,
        )
    }

    @Test
    fun productionJniReadsSeekableAndPipeBackedContentProviderUris() {
        val cases = listOf(
            "seekable" to "dsd64_test.dsf",
            "pipe" to "dff64_test.dff",
        )
        for ((mode, name) in cases) {
            val uri = Uri.parse("content://dev.naominet.lazer.androidtest.dsd/$mode/$name")
            val parsed = parseFloatWav(readVirtualWav(uri, position = 0L).bytes)
            assertEquals(176_400, parsed.sampleRate, "$mode $name PCM rate")
            assertEquals(2, parsed.channels, "$mode $name channel count")
            assertEquals(44_100L, parsed.frameCount, "$mode $name decoded frame count")
            assertPcmIsFiniteAndAudible("$mode $name", parsed.pcm)
            assertSeekMatchesFullDecode(name, uri, parsed)
        }
    }

    @Test
    fun productionJniRejectsMalformedPipeBackedDsdAndDeletesTemporaryCopy() {
        val uri = Uri.parse(
            "content://dev.naominet.lazer.androidtest.dsd/pipe/malformed_test.dsf",
        )
        val cachedSourcesBefore = temporaryDsdCacheFiles()
        val source = AndroidDsdPcmDataSourceFactory(
            context = targetContext,
            upstreamFactory = DataSource.Factory { error("Local DSD must use the native decoder.") },
        ).createDataSource()
        try {
            assertFailsWith<IOException> {
                source.open(DataSpec.Builder().setUri(uri).build())
            }
        } finally {
            source.close()
        }
        assertEquals(cachedSourcesBefore, temporaryDsdCacheFiles(),
            "a rejected pipe-backed DSD file does not leave a cache copy")
    }

    private fun assertSeekMatchesFullDecode(name: String, full: DecodedWav) {
        assertSeekMatchesFullDecode(name, { position -> readVirtualWav(name, position) }, full)
    }

    private fun assertSeekMatchesFullDecode(name: String, uri: Uri, full: DecodedWav) {
        assertSeekMatchesFullDecode(name, { position -> readVirtualWav(uri, position) }, full)
    }

    private fun assertSeekMatchesFullDecode(
        name: String,
        readAtPosition: (Long) -> ReadResult,
        full: DecodedWav,
    ) {
        val frameBytes = full.channels * Float.SIZE_BYTES
        val targetFrame = full.sampleRate.toLong() * 103L / 1_000L + 7L
        val pcmByteOffset = Math.multiplyExact(targetFrame, frameBytes.toLong())
        val sourcePosition = full.headerBytes + pcmByteOffset
        val actual = readAtPosition(sourcePosition).bytes
        val expected = full.pcm.copyOfRange(pcmByteOffset.toInt(), full.pcm.size)
        assertEquals(expected.size, actual.size, "$name seek suffix byte count")
        assertPcmClose("$name seek suffix", expected, actual)
    }

    private fun assertPcmIsFiniteAndAudible(name: String, pcm: ByteArray) {
        assertTrue(pcm.isNotEmpty() && pcm.size % Float.SIZE_BYTES == 0, "$name has float PCM")
        val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        var peak = 0.0f
        while (samples.hasRemaining()) {
            val sample = samples.get()
            assertTrue(sample.isFinite(), "$name contains only finite PCM samples")
            peak = maxOf(peak, abs(sample))
        }
        assertTrue(peak > 0.001f, "$name decodes to non-silent PCM")
    }

    private fun assertPcmClose(label: String, expected: ByteArray, actual: ByteArray) {
        assertEquals(expected.size, actual.size, "$label byte count")
        assertEquals(0, expected.size % Float.SIZE_BYTES, "$label float alignment")
        val expectedSamples = ByteBuffer.wrap(expected).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val actualSamples = ByteBuffer.wrap(actual).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        var maximumDifference = 0.0f
        while (expectedSamples.hasRemaining()) {
            val left = expectedSamples.get()
            val right = actualSamples.get()
            assertTrue(left.isFinite() && right.isFinite(), "$label contains finite samples")
            maximumDifference = maxOf(maximumDifference, abs(left - right))
        }
        assertTrue(maximumDifference <= MAX_SEEK_SAMPLE_DIFFERENCE,
            "$label differs by $maximumDifference")
    }

    private fun parseFloatWav(wav: ByteArray): DecodedWav {
        assertTrue(wav.size >= 44, "virtual WAV contains a complete header")
        assertEquals("RIFF", fourCc(wav, 0))
        assertEquals("WAVE", fourCc(wav, 8))
        assertEquals("fmt ", fourCc(wav, 12))
        assertEquals(16L, readLe32(wav, 16))
        assertEquals(3, readLe16(wav, 20), "IEEE float WAVE format")
        val channels = readLe16(wav, 22)
        val sampleRate = readLe32(wav, 24).toInt()
        assertEquals(32, readLe16(wav, 34), "float sample width")
        assertEquals("data", fourCc(wav, 36))
        val dataBytes = readLe32(wav, 40).toInt()
        assertEquals(dataBytes, wav.size - 44, "WAVE data chunk length")
        val frameBytes = channels * Float.SIZE_BYTES
        assertEquals(0, dataBytes % frameBytes, "PCM ends on a complete frame")
        return DecodedWav(
            headerBytes = 44L,
            sampleRate = sampleRate,
            channels = channels,
            frameCount = dataBytes.toLong() / frameBytes,
            pcm = wav.copyOfRange(44, wav.size),
        )
    }

    private fun readVirtualWav(name: String, position: Long): ReadResult {
        val fixture = File(targetContext.cacheDir, name)
        instrumentation.context.assets.open(name).use { input ->
            fixture.outputStream().use { output -> input.copyTo(output) }
        }
        return try {
            readVirtualWav(Uri.fromFile(fixture), position)
        } finally {
            fixture.delete()
        }
    }

    private fun readVirtualWav(uri: Uri, position: Long): ReadResult {
        val name = uri.lastPathSegment ?: uri.toString()
        val source = AndroidDsdPcmDataSourceFactory(
            context = targetContext,
            upstreamFactory = DataSource.Factory { error("Local DSD must use the native decoder.") },
        ).createDataSource()
        try {
            val expectedLength = source.open(
                DataSpec.Builder()
                    .setUri(uri)
                    .setPosition(position)
                    .build(),
            )
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = source.read(buffer, 0, buffer.size)
                if (count == C.RESULT_END_OF_INPUT) break
                assertTrue(count > 0, "$name native DataSource made read progress")
                output.write(buffer, 0, count)
            }
            val bytes = output.toByteArray()
            assertEquals(expectedLength, bytes.size.toLong(), "$name DataSource length")
            return ReadResult(bytes)
        } finally {
            source.close()
        }
    }

    private fun temporaryDsdCacheFiles(): Set<String> = targetContext.cacheDir.listFiles()
        ?.filter { it.name.startsWith("lazer-dsd-") && it.name.endsWith(".cache") }
        ?.map(File::getAbsolutePath)
        ?.toSet()
        .orEmpty()

    private fun fourCc(bytes: ByteArray, offset: Int): String =
        bytes.copyOfRange(offset, offset + 4).decodeToString()

    private fun readLe16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readLe32(bytes: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { value, index ->
            value or ((bytes[offset + index].toLong() and 0xffL) shl (index * 8))
        }

    private data class DecodedWav(
        val headerBytes: Long,
        val sampleRate: Int,
        val channels: Int,
        val frameCount: Long,
        val pcm: ByteArray,
    )

    private data class ReadResult(val bytes: ByteArray)

    private companion object {
        const val MAX_SEEK_SAMPLE_DIFFERENCE = 0.00001f
    }
}
