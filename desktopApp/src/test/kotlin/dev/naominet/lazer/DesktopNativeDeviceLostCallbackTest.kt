package dev.naominet.lazer

import com.sun.jna.Pointer
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopNativeDeviceLostCallbackTest {
    @Test
    fun `native device loss callback preserves position and is delivered only once`() {
        val nativeCallback = AtomicReference<LazerAudioEventCallback?>()
        val errors = CopyOnWriteArrayList<Pair<Long, Throwable>>()
        val errorDelivered = CountDownLatch(1)
        val player = DesktopNativeAudioPlayer(
            onProgress = { _, _ -> },
            onBuffered = { _, _ -> },
            onCompleted = {},
            onError = { token, error ->
                errors += token to error
                errorDelivered.countDown()
            },
            onTrackChanged = { _, _ -> },
            onStreamInfo = {},
            apiOverride = fakeAudioLibrary(nativeCallback),
        )
        val file = Files.createTempFile("native-device-lost", ".wav").toFile()

        try {
            val token = player.playLocalFile(
                file = file,
                trackId = 42L,
                durationMillis = 100_000L,
                fromProgress = 0.5f,
                volume = 1f,
                playWhenReady = false,
            )
            val callback = requireNotNull(nativeCallback.get()) {
                "the engine must receive the player's retained native event callback"
            }
            callback.callback(null, LAZER_AUDIO_EVENT_DEVICE_LOST, 0, 53_210L)
            assertTrue("device loss was not forwarded", errorDelivered.await(2, TimeUnit.SECONDS))

            callback.callback(null, LAZER_AUDIO_EVENT_DEVICE_LOST, 0, 99_999L)

            assertEquals(1, errors.size)
            assertEquals(token, errors.single().first)
            val error = errors.single().second
            assertTrue(error is DesktopAudioDeviceLostException)
            assertEquals(53_210L, (error as DesktopAudioDeviceLostException).positionMillis)
            assertEquals("fake USB output disconnected", error.message)
        } finally {
            player.close()
            Files.deleteIfExists(file.toPath())
        }
    }

    private fun fakeAudioLibrary(
        nativeCallback: AtomicReference<LazerAudioEventCallback?>,
    ): LazerAudioLibrary = Proxy.newProxyInstance(
        LazerAudioLibrary::class.java.classLoader,
        arrayOf(LazerAudioLibrary::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "lazer_audio_create" -> {
                val config = requireNotNull(arguments).get(0) as LazerAudioEngineConfig
                nativeCallback.set(config.events.onEvent)
                Pointer(1L)
            }
            "lazer_audio_open_file_utf8" -> LAZER_AUDIO_OK
            "lazer_audio_stream_info" -> {
                val info = requireNotNull(arguments).get(1) as LazerAudioStreamInfo
                info.codec = "fixture".toByteArray(Charsets.US_ASCII).copyOf(64)
                LAZER_AUDIO_OK
            }
            "lazer_audio_snapshot" -> LAZER_AUDIO_ERROR_STATE
            "lazer_audio_last_error" -> "fake USB output disconnected"
            else -> when (method.returnType) {
                Integer.TYPE -> LAZER_AUDIO_OK
                java.lang.Long.TYPE -> 0L
                java.lang.Boolean.TYPE -> false
                else -> null
            }
        }
    } as LazerAudioLibrary
}
