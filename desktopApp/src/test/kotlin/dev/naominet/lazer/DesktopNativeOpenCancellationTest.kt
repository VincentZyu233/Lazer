package dev.naominet.lazer

import com.sun.jna.Pointer
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopNativeOpenCancellationTest {
    @Test
    fun `superseded local open cannot publish stream info or start native playback`() {
        val openEntered = CountDownLatch(1)
        val releaseOpen = CountDownLatch(1)
        val playCalls = AtomicInteger()
        val stopCalls = AtomicInteger()
        val api = fakeAudioLibrary(openEntered, releaseOpen, playCalls, stopCalls)
        val streamInfo = mutableListOf<LazerHiFiStreamInfo?>()
        val player = DesktopNativeAudioPlayer(
            onProgress = { _, _ -> },
            onBuffered = { _, _ -> },
            onCompleted = {},
            onError = { _, _ -> },
            onTrackChanged = { _, _ -> },
            onStreamInfo = streamInfo::add,
            apiOverride = api,
        )
        val gate = DesktopPlaybackTokenGate()
        val oldRequest = gate.beginRequest()
        val file = Files.createTempFile("native-open-cancel", ".wav").toFile()
        val executor = Executors.newFixedThreadPool(2)

        try {
            val open = executor.submit<CancellationException?> {
                try {
                    player.playLocalFile(
                        file = file,
                        trackId = 1L,
                        durationMillis = 1_000L,
                        fromProgress = 0f,
                        volume = 1f,
                        playWhenReady = true,
                        onTokenActivated = { token -> gate.activateIfCurrent(oldRequest, token) },
                    )
                    null
                } catch (error: CancellationException) {
                    error
                }
            }

            assertTrue("fake native open did not reach its barrier", openEntered.await(5, TimeUnit.SECONDS))
            val stopEntered = CountDownLatch(1)
            val stop = executor.submit {
                stopEntered.countDown()
                player.stopIfCurrent { gate.isCurrent(oldRequest) }
            }
            assertTrue("stop request did not start", stopEntered.await(5, TimeUnit.SECONDS))
            gate.beginRequest()
            releaseOpen.countDown()

            assertTrue("superseded native open should cancel", open.get(5, TimeUnit.SECONDS) is CancellationException)
            stop.get(5, TimeUnit.SECONDS)
            assertEquals("the stale session must never start", 0, playCalls.get())
            assertFalse("a stale stream snapshot must not be published", streamInfo.any { it != null })
            assertEquals("the rejected session is stopped but its stale Stop cannot affect a later request", 2, stopCalls.get())
        } finally {
            releaseOpen.countDown()
            player.close()
            executor.shutdownNow()
            file.delete()
        }
    }

    private fun fakeAudioLibrary(
        openEntered: CountDownLatch,
        releaseOpen: CountDownLatch,
        playCalls: AtomicInteger,
        stopCalls: AtomicInteger,
    ): LazerAudioLibrary = Proxy.newProxyInstance(
        LazerAudioLibrary::class.java.classLoader,
        arrayOf(LazerAudioLibrary::class.java),
    ) { _, method, arguments ->
        when (method.name) {
            "lazer_audio_create" -> Pointer(1L)
            "lazer_audio_open_file_utf8" -> {
                openEntered.countDown()
                check(releaseOpen.await(5, TimeUnit.SECONDS)) { "test did not release the native open" }
                LAZER_AUDIO_OK
            }
            "lazer_audio_stream_info" -> {
                val info = requireNotNull(arguments).get(1) as LazerAudioStreamInfo
                info.codec = "fixture".toByteArray(Charsets.US_ASCII).copyOf(64)
                LAZER_AUDIO_OK
            }
            "lazer_audio_play" -> {
                playCalls.incrementAndGet()
                LAZER_AUDIO_OK
            }
            "lazer_audio_stop" -> {
                stopCalls.incrementAndGet()
                LAZER_AUDIO_OK
            }
            "lazer_audio_last_error" -> "fake native error"
            else -> when (method.returnType) {
                Integer.TYPE -> LAZER_AUDIO_OK
                java.lang.Long.TYPE -> 0L
                java.lang.Boolean.TYPE -> false
                else -> null
            }
        }
    } as LazerAudioLibrary
}
