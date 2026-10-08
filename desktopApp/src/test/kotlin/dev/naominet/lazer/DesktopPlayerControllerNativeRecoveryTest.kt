package dev.naominet.lazer

import com.sun.jna.Pointer
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopPlayerControllerNativeRecoveryTest {
    @Test
    fun `device loss reopens the same stable endpoint and resumes native position and play intent`() = runBlocking {
        val directory = Files.createTempDirectory("controller-native-recovery")
        val audioFile = Files.createFile(directory.resolve("fixture.wav"))
        val previousIdentity = DesktopSettings.hifiDeviceIdentity
        DesktopSettings.hifiDeviceIdentity = DEVICE.identityKey
        val enumerations = AtomicInteger()
        val library = FakeNativeAudioLibrary()
        val controller = DesktopPlayerController(
            DesktopPlayerControllerTestOverrides(
                nativeAudioLibrary = library.api,
                enumerateOutputDevices = {
                    when (enumerations.incrementAndGet()) {
                        1 -> listOf(DEVICE)
                        2 -> emptyList()
                        else -> listOf(DEVICE)
                    }
                },
                awaitRecoveryRetry = { yield() },
                localPlaybackQueueStore = DesktopLocalPlaybackQueueStore(directory.resolve("queue.json")),
            ),
        )

        try {
            onUi { controller.refreshHifiOutputDevices() }
            awaitCondition { !controller.hifiOutputDevicesLoading && controller.selectedHifiOutputDevice == DEVICE }

            val track = localTrack(audioFile.toString())
            onUi { controller.playTrack(track) }
            awaitCondition { library.opened.size == 1 && controller.isPlaying }

            assertEquals(listOf(DEVICE.endpointId), library.outputDeviceIds.toList())
            val callback = requireNotNull(library.eventCallback.get())
            callback.callback(null, LAZER_AUDIO_EVENT_DEVICE_LOST, 0, RECOVERY_POSITION_MILLIS)

            awaitCondition { library.opened.size == 2 && library.playCount.get() == 2 }

            assertEquals(3, enumerations.get()) // initial refresh, missing endpoint, same endpoint returned
            assertEquals(listOf(DEVICE.endpointId, DEVICE.endpointId), library.outputDeviceIds.toList())
            assertEquals(RECOVERY_POSITION_MILLIS, library.opened.last().startMillis)
            onUi {
                assertEquals(DEVICE.identityKey, controller.hifiOutputDeviceIdentity)
                assertEquals(track.id, controller.nowPlaying?.id)
                assertTrue(controller.isPlaying)
            }

            // The reopened stream has not advanced beyond the recovery checkpoint. A second
            // loss must leave the circuit breaker armed instead of entering another reopen loop.
            callback.callback(null, LAZER_AUDIO_EVENT_DEVICE_LOST, 0, RECOVERY_POSITION_MILLIS)
            awaitCondition { !controller.isPlaying }
            delay(100L)
            assertEquals(3, enumerations.get())
            assertEquals(2, library.opened.size)
        } finally {
            onUi { controller.dispose() }
            DesktopSettings.hifiDeviceIdentity = previousIdentity
            Files.deleteIfExists(audioFile)
            Files.deleteIfExists(directory.resolve("queue.json"))
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `seek cancels recovery while device enumeration is in flight`() = runBlocking {
        val directory = Files.createTempDirectory("controller-native-recovery-cancel")
        val audioFile = Files.createFile(directory.resolve("fixture.wav"))
        val previousIdentity = DesktopSettings.hifiDeviceIdentity
        DesktopSettings.hifiDeviceIdentity = DEVICE.identityKey
        val enumerations = AtomicInteger()
        val enumerationStarted = CompletableDeferred<Unit>()
        val releaseEnumeration = CompletableDeferred<List<DesktopAudioOutputDevice>>()
        val library = FakeNativeAudioLibrary()
        val controller = DesktopPlayerController(
            DesktopPlayerControllerTestOverrides(
                nativeAudioLibrary = library.api,
                enumerateOutputDevices = {
                    if (enumerations.incrementAndGet() == 1) {
                        listOf(DEVICE)
                    } else {
                        enumerationStarted.complete(Unit)
                        releaseEnumeration.await()
                    }
                },
                awaitRecoveryRetry = { yield() },
                localPlaybackQueueStore = DesktopLocalPlaybackQueueStore(directory.resolve("queue.json")),
            ),
        )

        try {
            onUi { controller.refreshHifiOutputDevices() }
            awaitCondition { !controller.hifiOutputDevicesLoading && controller.selectedHifiOutputDevice == DEVICE }
            onUi { controller.playTrack(localTrack(audioFile.toString())) }
            awaitCondition { library.opened.size == 1 && controller.isPlaying }

            requireNotNull(library.eventCallback.get()).callback(
                null,
                LAZER_AUDIO_EVENT_DEVICE_LOST,
                0,
                RECOVERY_POSITION_MILLIS,
            )
            withTimeout(3_000L) { enumerationStarted.await() }

            onUi { controller.seekTo(0.5f) }
            releaseEnumeration.complete(listOf(DEVICE))
            delay(100L)

            onUi { assertTrue(controller.isSeeking) }
            assertEquals(2, enumerations.get())
            assertEquals(1, library.opened.size)
            assertEquals(listOf(DEVICE.endpointId), library.outputDeviceIds.toList())
        } finally {
            releaseEnumeration.complete(listOf(DEVICE))
            onUi { controller.dispose() }
            DesktopSettings.hifiDeviceIdentity = previousIdentity
            Files.deleteIfExists(audioFile)
            Files.deleteIfExists(directory.resolve("queue.json"))
            Files.deleteIfExists(directory)
        }
    }

    private suspend fun awaitCondition(condition: () -> Boolean) {
        withTimeout(5_000L) {
            while (!onUi(condition)) delay(10L)
        }
    }

    private suspend fun <T> onUi(block: () -> T): T = withContext(Dispatchers.Swing) { block() }

    private fun localTrack(path: String) = TrackItem(
        id = 918_273L,
        title = "Recovery fixture",
        artist = "Test",
        album = "Controller integration",
        durationMillis = TRACK_DURATION_MILLIS,
        coverUrl = null,
        playbackSource = DesktopTrackSource.LocalFile(path),
    )

    private data class OpenedFile(val path: String, val startMillis: Long)

    private class FakeNativeAudioLibrary {
        val eventCallback = AtomicReference<LazerAudioEventCallback?>()
        val opened = CopyOnWriteArrayList<OpenedFile>()
        val outputDeviceIds = CopyOnWriteArrayList<String>()
        val playCount = AtomicInteger()

        val api: LazerAudioLibrary = Proxy.newProxyInstance(
            LazerAudioLibrary::class.java.classLoader,
            arrayOf(LazerAudioLibrary::class.java),
        ) { _, method, arguments ->
            val args = arguments.orEmpty()
            when (method.name) {
                "lazer_audio_create" -> {
                    val config = args[0] as LazerAudioEngineConfig
                    eventCallback.set(config.events.onEvent)
                    Pointer(1L)
                }

                "lazer_audio_set_device" -> {
                    val config = args[1] as LazerAudioDeviceConfig
                    config.deviceId?.toString()?.let(outputDeviceIds::add)
                    LAZER_AUDIO_OK
                }

                "lazer_audio_open_file_utf8" -> {
                    val path = (args[1] as ByteArray)
                        .takeWhile { it.toInt() != 0 }
                        .toByteArray()
                        .toString(Charsets.UTF_8)
                    val params = args[2] as LazerAudioOpenParams
                    opened += OpenedFile(path, params.startMillis)
                    LAZER_AUDIO_OK
                }

                "lazer_audio_play" -> {
                    playCount.incrementAndGet()
                    LAZER_AUDIO_OK
                }

                "lazer_audio_stream_info" -> {
                    val info = args[1] as LazerAudioStreamInfo
                    info.codec = "fixture".toByteArray(Charsets.US_ASCII).copyOf(64)
                    info.sourceSampleRate = 48_000
                    info.sourceChannels = 2
                    info.sourceBitsPerSample = 16
                    info.lossless = 1
                    info.outputSampleRate = 48_000
                    info.outputChannels = 2
                    info.outputBitsPerSample = 16
                    info.outputContainerBitsPerSample = 16
                    info.outputFormatInitialized = 1
                    LAZER_AUDIO_OK
                }

                "lazer_audio_snapshot" -> {
                    val snapshot = args[1] as LazerAudioSnapshot
                    snapshot.state = LAZER_AUDIO_STATE_PLAYING
                    snapshot.positionMillis = RECOVERY_POSITION_MILLIS
                    snapshot.durationMillis = TRACK_DURATION_MILLIS
                    snapshot.bufferedPercent = 100
                    LAZER_AUDIO_OK
                }

                "lazer_audio_last_error" -> "simulated USB output disconnected"

                else -> when (method.returnType) {
                    Integer.TYPE -> LAZER_AUDIO_OK
                    java.lang.Long.TYPE -> 0L
                    java.lang.Boolean.TYPE -> false
                    else -> null
                }
            }
        } as LazerAudioLibrary
    }

    private companion object {
        const val TRACK_DURATION_MILLIS = 120_000L
        const val RECOVERY_POSITION_MILLIS = 54_000L
        val DEVICE = DesktopWasapiDevice(
            endpointId = "{fake-wasapi-endpoint}",
            identityKey = "stable-test-dac",
            friendlyName = "Fake stable DAC",
            endpointState = WASAPI_DEVICE_STATE_ACTIVE,
            defaultRoleMask = 0,
            stableIdentity = true,
        )
    }
}
