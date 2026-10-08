package dev.naominet.lazer

import com.sun.jna.Pointer
import java.lang.reflect.Proxy
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Verifies the controller-to-native half of output recovery: the endpoint chosen by the retry
 * transaction must reach `lazer_audio_create`/`lazer_audio_set_device`, and a blank selection must
 * fall back to the system default. Uses the same dynamic-proxy fake as
 * [DesktopNativeOpenCancellationTest]; the native library is never loaded.
 */
class DesktopNativeOutputEndpointTest {
    private data class NativeDevicePolicySnapshot(
        val exclusive: Int,
        val bufferMillis: Int,
        val bitPerfect: Int,
        val dsdOutputMode: Int,
    )

    @Test
    fun `selected endpoint reaches the native device config on open and reopen`() {
        val createdDeviceIds = mutableListOf<String?>()
        val setDeviceIds = mutableListOf<String?>()
        val player = newPlayer(fakeAudioLibrary(createdDeviceIds, setDeviceIds))
        val file = Files.createTempFile("native-endpoint", ".wav").toFile()

        try {
            // The engine is created in the constructor, before the endpoint is known, so the
            // create config is the system default. Every open applies the selected endpoint.
            player.setOutputDevice("hw:2,0")
            player.playLocalFile(file, trackId = 1L, durationMillis = 1_000L, fromProgress = 0f, volume = 1f, playWhenReady = false)
            assertEquals(listOf<String?>(null), createdDeviceIds)
            assertEquals(listOf("hw:2,0"), setDeviceIds)

            player.setOutputDevice("hw:3,0")
            player.playLocalFile(file, trackId = 1L, durationMillis = 1_000L, fromProgress = 0f, volume = 1f, playWhenReady = false)
            assertEquals(listOf<String?>(null), createdDeviceIds)
            assertEquals(listOf("hw:2,0", "hw:3,0"), setDeviceIds)
        } finally {
            player.close()
            file.delete()
        }
    }

    @Test
    fun `blank or null endpoint selects the default device`() {
        val createdDeviceIds = mutableListOf<String?>()
        val setDeviceIds = mutableListOf<String?>()
        val player = newPlayer(fakeAudioLibrary(createdDeviceIds, setDeviceIds))
        val file = Files.createTempFile("native-default", ".wav").toFile()

        try {
            player.setOutputDevice("   ")
            player.playLocalFile(file, trackId = 1L, durationMillis = 1_000L, fromProgress = 0f, volume = 1f, playWhenReady = false)
            assertNull(createdDeviceIds.single())
            assertNull(setDeviceIds.single())

            player.setOutputDevice("hw:2,0")
            player.setOutputDevice(null)
            player.playLocalFile(file, trackId = 1L, durationMillis = 1_000L, fromProgress = 0f, volume = 1f, playWhenReady = false)
            assertEquals(listOf(null, null), setDeviceIds)
        } finally {
            player.close()
            file.delete()
        }
    }

    @Test
    fun `effective PCM policy reaches the native config and ReplayGain clears strict mode`() {
        val createdDeviceIds = mutableListOf<String?>()
        val setDeviceIds = mutableListOf<String?>()
        val setPolicies = mutableListOf<NativeDevicePolicySnapshot>()
        val player = DesktopNativeAudioPlayer(
            onProgress = { _, _ -> },
            onBuffered = { _, _ -> },
            onCompleted = {},
            onError = { _, _ -> },
            onTrackChanged = { _, _ -> },
            onStreamInfo = {},
            initialExclusiveAudio = true,
            initialBufferMillis = 240,
            initialBitPerfect = true,
            apiOverride = fakeAudioLibrary(createdDeviceIds, setDeviceIds, setPolicies),
        )
        val file = Files.createTempFile("native-policy", ".wav").toFile()

        try {
            player.playLocalFile(
                file,
                trackId = 1L,
                durationMillis = 1_000L,
                fromProgress = 0f,
                volume = 1f,
                replayGainDb = 0.0,
                playWhenReady = false,
            )
            player.playLocalFile(
                file,
                trackId = 2L,
                durationMillis = 1_000L,
                fromProgress = 0f,
                volume = 1f,
                replayGainDb = 2.0,
                playWhenReady = false,
            )

            assertEquals(
                listOf(
                    NativeDevicePolicySnapshot(
                        exclusive = 1,
                        bufferMillis = 240,
                        bitPerfect = 1,
                        dsdOutputMode = LAZER_AUDIO_DSD_OUTPUT_CONVERT_TO_PCM,
                    ),
                    NativeDevicePolicySnapshot(
                        exclusive = 1,
                        bufferMillis = 240,
                        bitPerfect = 0,
                        dsdOutputMode = LAZER_AUDIO_DSD_OUTPUT_CONVERT_TO_PCM,
                    ),
                ),
                setPolicies,
            )
        } finally {
            player.close()
            file.delete()
        }
    }

    private fun newPlayer(api: LazerAudioLibrary): DesktopNativeAudioPlayer = DesktopNativeAudioPlayer(
        onProgress = { _, _ -> },
        onBuffered = { _, _ -> },
        onCompleted = {},
        onError = { _, _ -> },
        onTrackChanged = { _, _ -> },
        onStreamInfo = {},
        apiOverride = api,
    )

    private fun fakeAudioLibrary(
        createdDeviceIds: MutableList<String?>,
        setDeviceIds: MutableList<String?>,
        setPolicies: MutableList<NativeDevicePolicySnapshot> = mutableListOf(),
    ): LazerAudioLibrary = Proxy.newProxyInstance(
        LazerAudioLibrary::class.java.classLoader,
        arrayOf(LazerAudioLibrary::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "lazer_audio_create" -> {
                val config = requireNotNull(arguments).get(0) as LazerAudioEngineConfig
                createdDeviceIds += config.device.deviceId?.toString()
                Pointer(1L)
            }
            "lazer_audio_set_device" -> {
                val device = requireNotNull(arguments).get(1) as LazerAudioDeviceConfig
                setDeviceIds += device.deviceId?.toString()
                setPolicies += NativeDevicePolicySnapshot(
                    exclusive = device.exclusive,
                    bufferMillis = device.bufferMillis,
                    bitPerfect = device.bitPerfect,
                    dsdOutputMode = device.dsdOutputMode,
                )
                LAZER_AUDIO_OK
            }
            "lazer_audio_open_file_utf8" -> LAZER_AUDIO_OK
            "lazer_audio_stream_info" -> {
                val info = requireNotNull(arguments).get(1) as LazerAudioStreamInfo
                info.codec = "fixture".toByteArray(Charsets.US_ASCII).copyOf(64)
                LAZER_AUDIO_OK
            }
            "lazer_audio_play" -> LAZER_AUDIO_OK
            "lazer_audio_stop" -> LAZER_AUDIO_OK
            else -> when (method.returnType) {
                Integer.TYPE -> LAZER_AUDIO_OK
                java.lang.Long.TYPE -> 0L
                java.lang.Boolean.TYPE -> false
                else -> null
            }
        }
    } as LazerAudioLibrary
}
