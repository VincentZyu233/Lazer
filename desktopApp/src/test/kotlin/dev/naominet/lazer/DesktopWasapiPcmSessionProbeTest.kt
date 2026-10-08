package dev.naominet.lazer

import com.sun.jna.Pointer
import com.sun.jna.WString
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopWasapiPcmSessionProbeTest {
    @Test
    fun existingCandidateMatrixHasFortyUniqueFormats() {
        val candidates = DesktopWasapiPcmFormatProbe.matrix

        assertEquals(40, candidates.size)
        assertEquals(40, candidates.distinct().size)
        assertEquals(10, candidates.map { it.sampleRateHz }.distinct().size)
        assertTrue(candidates.all { it.channels == 2 })
        assertEquals(
            setOf(16 to 16, 24 to 24, 32 to 24, 32 to 32),
            candidates.map { it.containerBits to it.validBits }.toSet(),
        )
    }

    @Test
    fun wavHeaderUsesCandidateFormatAndContainsOnlySilence() {
        val candidate = DesktopWasapiPcmFormatCandidate(
            sampleRateHz = 96_000,
            channels = 2,
            containerBits = 32,
            validBits = 24,
        )

        val wav = DesktopWasapiPcmSessionProbeWav.create(candidate)
        val blockAlign = 2 * 3
        val dataSize = DesktopWasapiPcmSessionProbeWav.SILENT_FRAME_COUNT * blockAlign

        assertEquals("RIFF", wav.ascii(0, 4))
        assertEquals(wav.size - 8, wav.u32le(4))
        assertEquals("WAVE", wav.ascii(8, 4))
        assertEquals("fmt ", wav.ascii(12, 4))
        assertEquals(16, wav.u32le(16))
        assertEquals(1, wav.u16le(20))
        assertEquals(2, wav.u16le(22))
        assertEquals(96_000, wav.u32le(24))
        assertEquals(96_000 * blockAlign, wav.u32le(28))
        assertEquals(blockAlign, wav.u16le(32))
        assertEquals(24, wav.u16le(34))
        assertEquals("data", wav.ascii(36, 4))
        assertEquals(dataSize, wav.u32le(40))
        assertEquals(44 + dataSize, wav.size)
        assertArrayEquals(ByteArray(dataSize), wav.copyOfRange(44, wav.size))
    }

    @Test
    fun wavHeaderSupports32BitSourcePcm() {
        val wav = DesktopWasapiPcmSessionProbeWav.create(
            DesktopWasapiPcmFormatCandidate(
                sampleRateHz = 768_000,
                containerBits = 32,
                validBits = 32,
            ),
        )

        assertEquals(768_000, wav.u32le(24))
        assertEquals(32, wav.u16le(34))
        assertEquals(4, wav.u16le(32) / 2)
    }

    @Test
    fun exactInitializedSessionIsReportedWithoutClaimingPlayback() {
        val candidate = DesktopWasapiPcmFormatCandidate(
            sampleRateHz = 192_000,
            containerBits = 32,
            validBits = 24,
        )

        val result = DesktopWasapiPcmSessionProbe.classify(
            candidate = candidate,
            openStatus = LAZER_AUDIO_OK,
            streamInfoStatus = LAZER_AUDIO_OK,
            info = exactSessionInfo(candidate),
        )

        assertEquals(DesktopWasapiPcmSessionProbeStatus.Initialized, result.status)
        assertNull(result.failure)
        assertEquals(
            DesktopWasapiPcmSessionFormat(
                sampleRateHz = 192_000,
                channels = 2,
                validBits = 24,
                containerBits = 32,
                isFloat = false,
                exclusive = true,
            ),
            result.actualSessionFormat,
        )
        assertTrue(result.actualSessionFormat!!.exclusive)
        // The result API intentionally contains no played/presented/DAC-lock claim.
    }

    @Test
    fun unsupportedCandidateIsDistinctFromDeviceFailure() {
        val candidate = DesktopWasapiPcmFormatProbe.matrix.first()

        val unsupported = DesktopWasapiPcmSessionProbe.classify(
            candidate,
            LAZER_AUDIO_ERROR_UNSUPPORTED,
            streamInfoStatus = null,
            info = null,
        )
        val deviceError = DesktopWasapiPcmSessionProbe.classify(
            candidate,
            LAZER_AUDIO_ERROR_DEVICE,
            streamInfoStatus = null,
            info = null,
        )

        assertEquals(DesktopWasapiPcmSessionProbeStatus.Unsupported, unsupported.status)
        assertEquals(DesktopWasapiPcmSessionProbeFailure.UnsupportedFormat, unsupported.failure)
        assertEquals(DesktopWasapiPcmSessionProbeStatus.Error, deviceError.status)
        assertEquals(DesktopWasapiPcmSessionProbeFailure.DeviceOrRuntime, deviceError.failure)
    }

    @Test
    fun mismatchedSessionAndMissingStreamInfoCannotPass() {
        val candidate = DesktopWasapiPcmFormatProbe.matrix.first()
        val wrongRate = exactSessionInfo(candidate).apply { outputSampleRate *= 2 }
        val mismatch = DesktopWasapiPcmSessionProbe.classify(
            candidate,
            LAZER_AUDIO_OK,
            LAZER_AUDIO_OK,
            wrongRate,
        )
        val noInfo = DesktopWasapiPcmSessionProbe.classify(
            candidate,
            LAZER_AUDIO_OK,
            LAZER_AUDIO_ERROR_DEVICE,
            info = null,
        )

        assertEquals(DesktopWasapiPcmSessionProbeStatus.Error, mismatch.status)
        assertEquals(DesktopWasapiPcmSessionProbeFailure.SessionFormatMismatch, mismatch.failure)
        assertEquals(candidate.sampleRateHz * 2, mismatch.actualSessionFormat?.sampleRateHz)
        assertEquals(DesktopWasapiPcmSessionProbeStatus.Error, noInfo.status)
        assertEquals(DesktopWasapiPcmSessionProbeFailure.StreamInfoUnavailable, noInfo.failure)
        assertNull(noInfo.actualSessionFormat)
        assertFalse(mismatch.actualSessionFormat!!.isFloat)
    }

    @Test
    fun initializationFlagAndBitPerfectEligibilityAreBothRequired() {
        val candidate = DesktopWasapiPcmFormatProbe.matrix.first()
        val noInitialization = exactSessionInfo(candidate).apply { outputFormatInitialized = 0 }
        val noBitPerfectEligibility = exactSessionInfo(candidate).apply { bitPerfectActive = 0 }

        listOf(noInitialization, noBitPerfectEligibility).forEach { info ->
            val result = DesktopWasapiPcmSessionProbe.classify(
                candidate,
                LAZER_AUDIO_OK,
                LAZER_AUDIO_OK,
                info,
            )
            assertEquals(DesktopWasapiPcmSessionProbeStatus.Error, result.status)
            assertEquals(DesktopWasapiPcmSessionProbeFailure.SessionFormatMismatch, result.failure)
        }
    }

    @Test
    fun probeOpensExactEndpointWithoutPlayingAndDeletesItsTemporaryWav() {
        val candidate = DesktopWasapiPcmFormatProbe.matrix.first { it.validBits == 24 && it.containerBits == 32 }
        val endpointId = "USB DAC endpoint"
        val engine = Pointer.createConstant(0x1234)
        var destroyed = 0
        var played = false
        var temporaryPath: Path? = null

        val api = fakeLibrary { name, args ->
            when (name) {
                "lazer_audio_create" -> {
                    val config = args!![0] as LazerAudioEngineConfig
                    assertEquals(endpointId, config.device.deviceId.toString())
                    assertEquals(1, config.device.exclusive)
                    assertEquals(1, config.device.bitPerfect)
                    engine
                }
                "lazer_audio_set_volume" -> {
                    assertEquals(engine, args!![0])
                    assertEquals(1.0, args[1])
                    LAZER_AUDIO_OK
                }
                "lazer_audio_open_file" -> {
                    assertEquals(engine, args!![0])
                    val path = Path.of((args[1] as WString).toString())
                    temporaryPath = path
                    val wav = Files.readAllBytes(path)
                    assertEquals(candidate.sampleRateHz, wav.u32le(24))
                    assertEquals(24, wav.u16le(34))
                    LAZER_AUDIO_OK
                }
                "lazer_audio_stream_info" -> {
                    assertEquals(engine, args!![0])
                    val info = args[1] as LazerAudioStreamInfo
                    exactSessionInfo(candidate).let { exact ->
                        info.sourceSampleRate = exact.sourceSampleRate
                        info.sourceChannels = exact.sourceChannels
                        info.sourceBitsPerSample = exact.sourceBitsPerSample
                        info.lossless = exact.lossless
                        info.outputSampleRate = exact.outputSampleRate
                        info.outputChannels = exact.outputChannels
                        info.outputBitsPerSample = exact.outputBitsPerSample
                        info.outputExclusive = exact.outputExclusive
                        info.bitPerfectActive = exact.bitPerfectActive
                        info.outputContainerBitsPerSample = exact.outputContainerBitsPerSample
                        info.outputIsFloat = exact.outputIsFloat
                        info.outputFormatInitialized = exact.outputFormatInitialized
                        info.sourceFormatKind = exact.sourceFormatKind
                    }
                    info.write()
                    LAZER_AUDIO_OK
                }
                "lazer_audio_destroy" -> {
                    assertEquals(engine, args!![0])
                    destroyed += 1
                    null
                }
                "lazer_audio_play" -> {
                    played = true
                    error("The session probe must never start playback")
                }
                else -> error("Unexpected native call: $name")
            }
        }

        val result = DesktopWasapiPcmSessionProbe.probeWithApi(api, endpointId, listOf(candidate)).single()

        assertEquals(DesktopWasapiPcmSessionProbeStatus.Initialized, result.status)
        assertEquals(candidate.sampleRateHz, result.actualSessionFormat?.sampleRateHz)
        assertEquals(candidate.containerBits, result.actualSessionFormat?.containerBits)
        assertEquals(1, destroyed)
        assertFalse(played)
        assertFalse(Files.exists(checkNotNull(temporaryPath)))
    }

    @Test
    fun busyEndpointFailureIsPerCandidateAndStillDestroysEngineAndWav() {
        val candidate = DesktopWasapiPcmFormatProbe.matrix.first()
        val engine = Pointer.createConstant(0x4321)
        var destroyed = 0
        var temporaryPath: Path? = null
        val api = fakeLibrary { name, args ->
            when (name) {
                "lazer_audio_create" -> engine
                "lazer_audio_set_volume" -> LAZER_AUDIO_OK
                "lazer_audio_open_file" -> {
                    temporaryPath = Path.of((args!![1] as WString).toString())
                    LAZER_AUDIO_ERROR_DEVICE
                }
                "lazer_audio_destroy" -> {
                    destroyed += 1
                    null
                }
                else -> error("Unexpected native call: $name")
            }
        }

        val result = DesktopWasapiPcmSessionProbe.probeWithApi(api, "USB DAC endpoint", listOf(candidate)).single()

        assertEquals(DesktopWasapiPcmSessionProbeStatus.Error, result.status)
        assertEquals(DesktopWasapiPcmSessionProbeFailure.DeviceOrRuntime, result.failure)
        assertEquals(LAZER_AUDIO_ERROR_DEVICE, result.openStatus)
        assertEquals(1, destroyed)
        assertFalse(Files.exists(checkNotNull(temporaryPath)))
    }

    private fun exactSessionInfo(candidate: DesktopWasapiPcmFormatCandidate) = LazerAudioStreamInfo().apply {
        sourceSampleRate = candidate.sampleRateHz
        sourceChannels = candidate.channels
        sourceBitsPerSample = candidate.validBits
        lossless = 1
        outputSampleRate = candidate.sampleRateHz
        outputChannels = candidate.channels
        outputBitsPerSample = candidate.validBits
        outputExclusive = 1
        bitPerfectActive = 1
        outputContainerBitsPerSample = candidate.containerBits
        outputIsFloat = 0
        outputFormatInitialized = 1
        sourceFormatKind = LAZER_AUDIO_SOURCE_FORMAT_PCM
    }

    private fun ByteArray.ascii(offset: Int, count: Int): String =
        String(this, offset, count, Charsets.US_ASCII)

    private fun fakeLibrary(onCall: (String, Array<out Any?>?) -> Any?): LazerAudioLibrary {
        val handler = InvocationHandler { _, method, args -> onCall(method.name, args) }
        return Proxy.newProxyInstance(
            LazerAudioLibrary::class.java.classLoader,
            arrayOf(LazerAudioLibrary::class.java),
            handler,
        ) as LazerAudioLibrary
    }

    private fun ByteArray.u16le(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.u32le(offset: Int): Int =
        u16le(offset) or (u16le(offset + 2) shl 16)
}
