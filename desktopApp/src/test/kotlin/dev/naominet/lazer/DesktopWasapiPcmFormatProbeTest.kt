package dev.naominet.lazer

import com.sun.jna.Pointer
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Assume.assumeTrue
import org.junit.Test

class DesktopWasapiPcmFormatProbeTest {
    @Test
    fun `default matrix is deterministic stereo PCM from 44k1 through 768k`() {
        val matrix = DesktopWasapiPcmFormatProbe.matrix

        assertEquals(
            listOf(44_100, 48_000, 88_200, 96_000, 176_400, 192_000, 352_800, 384_000, 705_600, 768_000),
            DesktopWasapiPcmFormatProbe.rates,
        )
        assertEquals(
            listOf(
                DesktopWasapiPcmFormatMode(16, 16),
                DesktopWasapiPcmFormatMode(24, 24),
                DesktopWasapiPcmFormatMode(32, 24),
                DesktopWasapiPcmFormatMode(32, 32),
            ),
            DesktopWasapiPcmFormatProbe.formats,
        )
        assertEquals(40, matrix.size)
        assertEquals(matrix, DesktopWasapiPcmFormatProbe.matrix)
        assertTrue(matrix.all { it.channels == 2 })
        assertEquals(DesktopWasapiPcmFormatCandidate(44_100, 2, 16, 16), matrix.first())
        assertEquals(DesktopWasapiPcmFormatCandidate(44_100, 2, 32, 32), matrix[3])
        assertEquals(DesktopWasapiPcmFormatCandidate(768_000, 2, 32, 32), matrix.last())
    }

    @Test
    fun `probe marshals candidate array and maps statuses while preserving native status`() {
        val candidates = DesktopWasapiPcmFormatProbe.matrix.take(3)
        val hresults = listOf(0, 0x8889_0008.toInt(), 0x8007_0005.toInt())
        val api = fakeLibrary { endpoint, candidatePointer, count, resultPointer ->
            assertEquals("USB DAC endpoint", endpoint)
            assertEquals(candidates.size, count)

            val expectedCandidateStruct = LazerAudioPcmFormatCandidate().size()
            val nativeCandidates = candidates.indices.map { index ->
                LazerAudioPcmFormatCandidate(candidatePointer.share(index.toLong() * expectedCandidateStruct.toLong()))
                    .apply { read() }
            }
            assertEquals(44_100, nativeCandidates[0].sampleRate)
            assertEquals(2, nativeCandidates[0].channels.toInt())
            assertEquals(16, nativeCandidates[0].containerBits.toInt())
            assertEquals(16, nativeCandidates[0].validBits.toInt())
            assertEquals(24, nativeCandidates[1].containerBits.toInt())
            assertEquals(24, nativeCandidates[1].validBits.toInt())
            assertEquals(32, nativeCandidates[2].containerBits.toInt())
            assertEquals(24, nativeCandidates[2].validBits.toInt())

            val expectedResultStruct = LazerAudioPcmFormatProbeResultStruct().size()
            listOf(0, 1, 2).forEachIndexed { index, resultStatus ->
                LazerAudioPcmFormatProbeResultStruct(
                    resultPointer.share(index.toLong() * expectedResultStruct.toLong()),
                ).apply {
                    status = resultStatus
                    nativeStatus = hresults[index]
                    write()
                }
            }
            0
        }

        val results = DesktopWasapiPcmFormatProbe.probeWithApi(api, "USB DAC endpoint", candidates)

        assertEquals(
            listOf(
                DesktopWasapiPcmFormatProbeStatus.Supported,
                DesktopWasapiPcmFormatProbeStatus.Unsupported,
                DesktopWasapiPcmFormatProbeStatus.Error,
            ),
            results.map(DesktopWasapiPcmFormatProbeResult::status),
        )
        assertEquals(candidates, results.map(DesktopWasapiPcmFormatProbeResult::candidate))
        assertEquals(hresults, results.map(DesktopWasapiPcmFormatProbeResult::nativeStatus))
    }

    @Test
    fun `missing optional probe export has a distinct unavailable exception`() {
        val api = fakeLibrary { _, _, _, _ -> throw UnsatisfiedLinkError("symbol is absent") }

        val error = runCatching {
            DesktopWasapiPcmFormatProbe.probeWithApi(api, "USB DAC endpoint", DesktopWasapiPcmFormatProbe.matrix.take(1))
        }.exceptionOrNull()

        assertTrue(error is DesktopWasapiPcmFormatProbeUnavailableException)
        assertTrue(error?.cause is UnsatisfiedLinkError)
    }

    @Test
    fun `probe rejects empty or blank requests before native call`() {
        val api = fakeLibrary { _, _, _, _ -> error("native call should not happen") }

        assertFalse(runCatching {
            DesktopWasapiPcmFormatProbe.probeWithApi(api, " ", DesktopWasapiPcmFormatProbe.matrix.take(1))
        }.isSuccess)
        assertFalse(runCatching {
            DesktopWasapiPcmFormatProbe.probeWithApi(api, "USB DAC endpoint", emptyList())
        }.isSuccess)
    }

    @Test
    fun `optional native API probes current default endpoint`() {
        assumeTrue("WASAPI format probe requires Windows", System.getProperty("os.name").startsWith("Windows"))
        assumeTrue("native audio library is optional", LazerAudioLoader.isAvailable)

        val devices = try {
            DesktopWasapiDeviceCatalog.enumerate()
        } catch (error: DesktopWasapiCatalogUnavailableException) {
            assumeNoException("optional device catalog exports are absent", error)
            return
        }
        val default = devices.firstOrNull {
            it.defaultRoleMask and (1 shl 1) != 0 &&
                it.endpointState and WASAPI_DEVICE_STATE_ACTIVE != 0
        }
        assumeTrue("Windows has no active multimedia render endpoint", default != null)

        val results = try {
            DesktopWasapiPcmFormatProbe.probe(default!!.endpointId)
        } catch (error: DesktopWasapiPcmFormatProbeUnavailableException) {
            assumeNoException("optional PCM format probe export is absent", error)
            return
        }

        assertEquals(DesktopWasapiPcmFormatProbe.matrix, results.map(DesktopWasapiPcmFormatProbeResult::candidate))
        assertEquals(40, results.size)
        assertTrue(results.all {
            it.status in setOf(
                DesktopWasapiPcmFormatProbeStatus.Supported,
                DesktopWasapiPcmFormatProbeStatus.Unsupported,
                DesktopWasapiPcmFormatProbeStatus.Error,
            )
        })
    }

    private fun fakeLibrary(
        probe: (endpoint: String, candidates: Pointer, count: Int, results: Pointer) -> Int,
    ): LazerAudioLibrary {
        val handler = InvocationHandler { _, method, args ->
            if (method.name != "lazer_audio_device_probe_pcm_formats") {
                throw UnsupportedOperationException("Unexpected fake API call: ${method.name}")
            }
            val endpoint = args!![0].toString()
            val candidates = args[1] as Pointer
            val count = args[2] as Int
            val results = args[3] as Pointer
            probe(endpoint, candidates, count, results)
        }
        return Proxy.newProxyInstance(
            LazerAudioLibrary::class.java.classLoader,
            arrayOf(LazerAudioLibrary::class.java),
            handler,
        ) as LazerAudioLibrary
    }
}
