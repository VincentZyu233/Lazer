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
import kotlin.test.assertContentEquals
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
        for (rate in DSD_RATES) {
            val expectedSampleRate = if (rate == 64) 176_400 else 192_000
            val expectedFrames = expectedSampleRate / 10L
            for (name in listOf("dsd${rate}_test.dsf", "dff${rate}_test.dff")) {
                assertPcmFixture(name, expectedSampleRate, expectedFrames)
            }
        }

        assertPcmFixture("dst64_verbatim.dff", sampleRate = 176_400, expectedFrames = 176_400L)

        val dsf64 = parseFloatWav(readVirtualWav("dsd64_test.dsf", position = 0L).bytes)
        val dff64 = parseFloatWav(readVirtualWav("dff64_test.dff", position = 0L).bytes)
        assertPcmClose(
            "DSF and raw DFF carry the same DSD channel bits",
            dsf64.pcm,
            dff64.pcm,
        )
    }

    private fun assertPcmFixture(name: String, sampleRate: Int, expectedFrames: Long) {
        val stream = readVirtualWav(name, position = 0L)
        val parsed = parseFloatWav(stream.bytes)
        assertEquals(sampleRate, parsed.sampleRate, "$name PCM rate")
        assertEquals(2, parsed.channels, "$name channel count")
        assertEquals(expectedFrames, parsed.frameCount, "$name decoded frame count")
        assertPcmIsFiniteAndAudible(name, parsed.pcm)
        assertSeekMatchesFullDecode(name, parsed)
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
            assertEquals(17_640L, parsed.frameCount, "$mode $name decoded frame count")
            assertPcmIsFiniteAndAudible("$mode $name", parsed.pcm)
            assertSeekMatchesFullDecode(name, uri, parsed)
        }
    }

    @Test
    fun productionJniEmitsRawDsdAsDoPCarriersAndRejectsDst() {
        for ((rate, carrierRate) in DOP_RATES) {
            var referenceCarrier: ByteArray? = null
            for (name in listOf("dsd${rate}_test.dsf", "dff${rate}_test.dff")) {
                val full = parsePcm24Wav(
                    readVirtualWav(name, position = 0L, outputMode = AndroidDsdOutputMode.DoP).bytes,
                )
                assertEquals(carrierRate, full.sampleRate, "$name DoP carrier rate")
                assertEquals(2, full.channels, "$name DoP channel count")
                assertEquals(carrierRate / 10L, full.frameCount, "$name DoP frame count")
                assertDoPMarkers(name, full.pcm, firstMarker = 0x05)
                assertDoPSeekMatchesFullDecode(name, full)
                referenceCarrier?.let {
                    assertContentEquals(it, full.pcm, "DSF and raw DFF $rate DoP carriers")
                } ?: run { referenceCarrier = full.pcm }
            }
        }

        for (unsupported in listOf(
            "dst64_verbatim.dff",
            "dsd512_test.dsf", "dff512_test.dff",
            "dsd1024_test.dsf", "dff1024_test.dff",
        )) {
            assertFailsWith<IOException>("$unsupported must not be exposed as DoP") {
                readVirtualWav(unsupported, 0L, AndroidDsdOutputMode.DoP)
            }
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
        val targetFrame = full.sampleRate.toLong() * 23L / 1_000L + 7L
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

    private fun parsePcm24Wav(wav: ByteArray): DecodedWav {
        assertTrue(wav.size >= 44, "virtual WAV contains a complete header")
        assertEquals("RIFF", fourCc(wav, 0))
        assertEquals("WAVE", fourCc(wav, 8))
        assertEquals("fmt ", fourCc(wav, 12))
        assertEquals(16L, readLe32(wav, 16))
        assertEquals(1, readLe16(wav, 20), "integer PCM WAVE format")
        val channels = readLe16(wav, 22)
        val sampleRate = readLe32(wav, 24).toInt()
        assertEquals(24, readLe16(wav, 34), "packed PCM24 carrier width")
        assertEquals("data", fourCc(wav, 36))
        val dataBytes = readLe32(wav, 40).toInt()
        assertEquals(dataBytes, wav.size - 44, "WAVE data chunk length")
        val frameBytes = channels * 3
        assertEquals(0, dataBytes % frameBytes, "DoP carrier ends on a complete frame")
        return DecodedWav(
            headerBytes = 44L,
            sampleRate = sampleRate,
            channels = channels,
            frameCount = dataBytes.toLong() / frameBytes,
            pcm = wav.copyOfRange(44, wav.size),
        )
    }

    private fun assertDoPSeekMatchesFullDecode(name: String, full: DecodedWav) {
        val targetFrame = full.sampleRate.toLong() * 23L / 1_000L + 7L
        val carrierByteOffset = Math.multiplyExact(targetFrame, 6L)
        val actual = readVirtualWav(
            name,
            full.headerBytes + carrierByteOffset,
            AndroidDsdOutputMode.DoP,
        ).bytes
        val expected = full.pcm.copyOfRange(carrierByteOffset.toInt(), full.pcm.size)
        assertEquals(expected.size, actual.size, "$name DoP seek suffix byte count")
        assertDoPMarkers(name, actual, firstMarker = 0x05)
        for (offset in actual.indices) {
            if (offset % 3 != 2) {
                assertEquals(expected[offset].toInt() and 0xff, actual[offset].toInt() and 0xff,
                    "$name DoP seek DSD payload byte $offset")
            }
        }
    }

    private fun assertDoPMarkers(name: String, carrier: ByteArray, firstMarker: Int) {
        assertEquals(0, carrier.size % 6, "$name stereo DoP frame alignment")
        var marker = firstMarker
        for (frameOffset in carrier.indices step 6) {
            val leftMarker = carrier[frameOffset + 2].toInt() and 0xff
            val rightMarker = carrier[frameOffset + 5].toInt() and 0xff
            assertEquals(marker, leftMarker, "$name left marker at frame ${frameOffset / 6}")
            assertEquals(marker, rightMarker, "$name right marker at frame ${frameOffset / 6}")
            marker = if (marker == 0x05) 0xFA else 0x05
        }
    }

    private fun readVirtualWav(
        name: String,
        position: Long,
        outputMode: AndroidDsdOutputMode = AndroidDsdOutputMode.Pcm,
    ): ReadResult {
        val fixture = File(targetContext.cacheDir, name)
        instrumentation.context.assets.open(name).use { input ->
            fixture.outputStream().use { output -> input.copyTo(output) }
        }
        return try {
            readVirtualWav(Uri.fromFile(fixture), position, outputMode)
        } finally {
            fixture.delete()
        }
    }

    private fun readVirtualWav(
        uri: Uri,
        position: Long,
        outputMode: AndroidDsdOutputMode = AndroidDsdOutputMode.Pcm,
    ): ReadResult {
        val name = uri.lastPathSegment ?: uri.toString()
        val source = AndroidDsdPcmDataSourceFactory(
            context = targetContext,
            upstreamFactory = DataSource.Factory { error("Local DSD must use the native decoder.") },
            outputMode = outputMode,
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
        val DSD_RATES = listOf(64, 128, 256, 512, 1024)
        val DOP_RATES = listOf(64 to 176_400, 128 to 352_800, 256 to 705_600)
        const val MAX_SEEK_SAMPLE_DIFFERENCE = 0.00001f
    }
}
