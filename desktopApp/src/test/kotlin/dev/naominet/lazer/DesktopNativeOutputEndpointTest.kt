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
