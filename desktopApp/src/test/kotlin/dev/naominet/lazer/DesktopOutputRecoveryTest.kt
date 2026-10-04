package dev.naominet.lazer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class DesktopOutputRecoveryTest {
    private val intent = DesktopOutputRecoveryIntent(
        trackId = 42L,
        positionMillis = 67_000L,
        wasPlaying = true,
        outputIdentity = "stable-dac-id",
        outputIdentityStable = true,
    )

    @Test
    fun `same stable active output and track can resume`() {
        assertTrue(
            canResumeAfterOutputRecovery(
                intent = intent,
                currentTrackId = 42L,
                selectedOutputIdentity = "stable-dac-id",
                selectedOutputAvailable = true,
                selectedOutputIdentityStable = true,
            ),
        )
    }

    @Test
    fun `different or unavailable output never takes over recovery`() {
        assertFalse(canResume("another-dac", available = true))
        assertFalse(canResume("stable-dac-id", available = false))
        assertFalse(canResume(null, available = true))
    }

    @Test
    fun `changed track and unstable identity invalidate automatic resume`() {
        assertFalse(
            canResumeAfterOutputRecovery(
                intent = intent,
                currentTrackId = 99L,
                selectedOutputIdentity = "stable-dac-id",
                selectedOutputAvailable = true,
                selectedOutputIdentityStable = true,
            ),
        )
        assertFalse(
            canResumeAfterOutputRecovery(
                intent = intent.copy(outputIdentityStable = false),
                currentTrackId = 42L,
                selectedOutputIdentity = "stable-dac-id",
                selectedOutputAvailable = true,
                selectedOutputIdentityStable = false,
            ),
        )
        assertFalse(canAutomaticallyRecoverOutput(intent.copy(outputIdentityStable = false), 42L, "stable-dac-id"))
        assertFalse(canAutomaticallyRecoverOutput(intent, 99L, "stable-dac-id"))
        assertFalse(canAutomaticallyRecoverOutput(intent, 42L, null))
        assertFalse(canAutomaticallyRecoverOutput(intent, 42L, "stable-dac-id", automaticResumeAwaitingConfirmation = true))
    }

    @Test
    fun `retry backoff is capped and bounded`() {
        assertEquals(500L, desktopOutputRecoveryRetryDelayMillis(0))
        assertEquals(1_000L, desktopOutputRecoveryRetryDelayMillis(1))
        assertEquals(2_000L, desktopOutputRecoveryRetryDelayMillis(2))
        assertEquals(4_000L, desktopOutputRecoveryRetryDelayMillis(3))
        assertEquals(5_000L, desktopOutputRecoveryRetryDelayMillis(4))
        assertEquals(5_000L, desktopOutputRecoveryRetryDelayMillis(DESKTOP_OUTPUT_RECOVERY_MAX_ATTEMPTS - 1))
        assertEquals(24, DESKTOP_OUTPUT_RECOVERY_MAX_ATTEMPTS)
    }

    @Test
    fun `native position clamps below track end and handles unknown duration`() {
        assertEquals(0.67f, intent.progressFor(100_000L), 0.0001f)
        assertEquals(0.999f, intent.copy(positionMillis = 120_000L).progressFor(100_000L))
        assertEquals(0f, intent.progressFor(0L))
    }

    @Test
    fun `automatic retry gate requires movement past the reopened checkpoint`() {
        val guard = DesktopOutputRecoveryGuard()
        guard.begin(0.67f)

        assertTrue(guard.awaitingConfirmation)
        assertFalse(
            canAutomaticallyRecoverOutput(
                intent = intent,
                currentTrackId = 42L,
                selectedOutputIdentity = "stable-dac-id",
                automaticResumeAwaitingConfirmation = guard.awaitingConfirmation,
            ),
        )
        assertFalse(guard.observeProgress(0.67f))
        assertTrue(guard.awaitingConfirmation)
        assertFalse(guard.observeProgress(0.67005f))
        assertTrue(guard.awaitingConfirmation)
        assertTrue(guard.observeProgress(0.6702f))
        assertFalse(guard.awaitingConfirmation)
        assertTrue(
            canAutomaticallyRecoverOutput(
                intent = intent,
                currentTrackId = 42L,
                selectedOutputIdentity = "stable-dac-id",
                automaticResumeAwaitingConfirmation = guard.awaitingConfirmation,
            ),
        )
        assertFalse(guard.observeProgress(0.8f))
    }

    @Test
    fun `missing checkpoint keeps automatic retry circuit breaker armed`() {
        val guard = DesktopOutputRecoveryGuard()
        guard.begin(Float.NaN)

        assertTrue(guard.awaitingConfirmation)
        assertFalse(guard.observeProgress(0.8f))
        assertTrue(guard.awaitingConfirmation)

        guard.clear()
        assertFalse(guard.awaitingConfirmation)
    }

    @Test
    fun `refresh started before device loss consumes the new intent after same output appears`() = runBlocking {
        val enumerationStarted = CompletableDeferred<Unit>()
        val releaseEnumeration = CompletableDeferred<List<DesktopAudioOutputDevice>>()
        val events = mutableListOf<String>()
        val guard = DesktopOutputRecoveryGuard()
        var pendingIntent: DesktopOutputRecoveryIntent? = null
        var selectedDevice: DesktopAudioOutputDevice? = null

        val refresh = async {
            refreshHifiOutputSnapshot(
                enumerate = {
                    enumerationStarted.complete(Unit)
                    releaseEnumeration.await()
                },
                applySnapshot = { devices ->
                    selectedDevice = devices.firstOrNull()
                    events += "snapshot"
                },
                onFailure = { throw AssertionError("enumeration unexpectedly failed", it) },
                onFinished = { successful ->
                    events += "finished:$successful"
                    if (!successful) return@refreshHifiOutputSnapshot
                    val intent = pendingIntent ?: return@refreshHifiOutputSnapshot
                    val selected = selectedDevice
                    if (canResumeAfterOutputRecovery(
                            intent = intent,
                            currentTrackId = 42L,
                            selectedOutputIdentity = selected?.identityKey,
                            selectedOutputAvailable = selected?.active == true,
                            selectedOutputIdentityStable = selected?.stableIdentity == true,
                        )
                    ) {
                        pendingIntent = null
                        guard.begin(intent.progressFor(100_000L))
                        events += "reopened"
                    }
                },
            )
        }

        withTimeout(2_000L) { enumerationStarted.await() }
        // DEVICE_LOST arrives while the ordinary user/startup refresh is already suspended.
        pendingIntent = intent
        releaseEnumeration.complete(
            listOf(
                DesktopAlsaOutputDevice(
                    deviceToken = "hw:2,0",
                    identityKey = "stable-dac-id",
                    displayName = "Test DAC",
                    flags = ALSA_OUTPUT_DEVICE_ACTIVE,
                ),
            ),
        )
        withTimeout(2_000L) { refresh.await() }

        assertEquals(listOf("snapshot", "finished:true", "reopened"), events)
        assertNull(pendingIntent)
        assertEquals(0.67f, intent.progressFor(100_000L), 0.0001f)
        assertTrue(guard.awaitingConfirmation)
        assertFalse(
            canAutomaticallyRecoverOutput(
                intent = intent,
                currentTrackId = 42L,
                selectedOutputIdentity = "stable-dac-id",
                automaticResumeAwaitingConfirmation = guard.awaitingConfirmation,
            ),
        )
    }

    @Test
    fun `native terminal metadata preserves device loss position and failure classification`() {
        val deviceLost = desktopTerminalError(
            LAZER_AUDIO_EVENT_DEVICE_LOST,
            12_345L,
            "USB endpoint stopped",
        )
        assertTrue(deviceLost is DesktopAudioDeviceLostException)
        assertEquals(12_345L, (deviceLost as DesktopAudioDeviceLostException).positionMillis)
        assertEquals("USB endpoint stopped", deviceLost.message)

        val failed = desktopTerminalError(LAZER_AUDIO_EVENT_FAILED, 0L, "decode error")
        assertTrue(failed is java.io.IOException)
        assertFalse(failed is DesktopAudioDeviceLostException)
        assertNull(desktopTerminalError(LAZER_AUDIO_EVENT_ENDED, 99_000L, ""))
        assertNull(desktopTerminalError(LAZER_AUDIO_EVENT_NONE, 0L, ""))
    }

    @Test
    fun `automatic recovery reopens the exact stable device then resumes loss position and intent`() = runBlocking {
        val driver = FakeOutputRecoveryDriver()
        driver.enumerations = {
            listOf(
                DesktopAlsaOutputDevice(
                    deviceToken = "hw:2,0",
                    identityKey = "stable-dac-id",
                    displayName = "Test DAC",
                    flags = ALSA_OUTPUT_DEVICE_ACTIVE,
                ),
            )
        }

        runDesktopOutputRecovery(intent.copy(wasPlaying = false), driver)

        assertEquals("hw:2,0", driver.switchedToken)
        assertEquals(
            listOf("await:0", "enumerate", "snapshot", "switch:hw:2,0", "volume", "resume"),
            driver.events,
        )
        val resumed = requireNotNull(driver.resumed)
        assertEquals(67_000L, resumed.positionMillis)
        assertFalse(resumed.wasPlaying)
        assertEquals("stable-dac-id", resumed.outputIdentity)
        assertFalse(driver.exhausted)
    }

    @Test
    fun `a different or unstable device never takes over and retries are exhausted`() = runBlocking {
        val driver = FakeOutputRecoveryDriver()
        val other = DesktopAlsaOutputDevice(
            deviceToken = "hw:3,0",
            identityKey = "another-dac",
            displayName = "Other DAC",
            flags = ALSA_OUTPUT_DEVICE_ACTIVE,
        )
        val unstable = DesktopAlsaOutputDevice(
            deviceToken = "hw:4,0",
            identityKey = "stable-dac-id",
            displayName = "Unstable DAC",
            flags = ALSA_OUTPUT_DEVICE_ACTIVE or ALSA_OUTPUT_DEVICE_IDENTITY_EPHEMERAL,
        )
        var call = 0
        driver.enumerations = { if (call++ == 0) listOf(other) else listOf(unstable) }

        runDesktopOutputRecovery(intent, driver)

        assertNull(driver.switchedToken)
        assertNull(driver.resumed)
        assertFalse(driver.events.any { it.startsWith("resume") })
        assertTrue(driver.exhausted)
        assertEquals(DESKTOP_OUTPUT_RECOVERY_MAX_ATTEMPTS, driver.enumerationCount)
    }

    @Test
    fun `a request superseded during enumeration never opens the returned device`() = runBlocking {
        val enumerationStarted = CompletableDeferred<Unit>()
        val releaseEnumeration = CompletableDeferred<List<DesktopAudioOutputDevice>>()
        val driver = FakeOutputRecoveryDriver()
        driver.enumerations = {
            enumerationStarted.complete(Unit)
            releaseEnumeration.await()
        }

        val job = async { runDesktopOutputRecovery(intent, driver) }
        withTimeout(2_000L) { enumerationStarted.await() }
        // Stop, a track change or a manual device switch arrives while enumeration is suspended.
        driver.current = false
        releaseEnumeration.complete(
            listOf(
                DesktopAlsaOutputDevice(
                    deviceToken = "hw:2,0",
                    identityKey = "stable-dac-id",
                    displayName = "Test DAC",
                    flags = ALSA_OUTPUT_DEVICE_ACTIVE,
                ),
            ),
        )
        withTimeout(2_000L) { job.await() }

        assertNull(driver.switchedToken)
        assertNull(driver.resumed)
        assertFalse(driver.exhausted)
    }

    @Test
    fun `absent device exhausts the bounded retry window and stops`() = runBlocking {
        val driver = FakeOutputRecoveryDriver()
        driver.enumerations = { emptyList() }

        runDesktopOutputRecovery(intent, driver)

        assertTrue(driver.exhausted)
        assertEquals(DESKTOP_OUTPUT_RECOVERY_MAX_ATTEMPTS, driver.enumerationCount)
        assertNull(driver.switchedToken)
        assertNull(driver.resumed)
    }

    @Test
    fun `an in-flight snapshot is awaited without counting an attempt`() = runBlocking {
        val driver = FakeOutputRecoveryDriver()
        var loadingChecks = 0
        driver.loadingProvider = { loadingChecks++ < 2 }
        driver.enumerations = {
            listOf(
                DesktopAlsaOutputDevice(
                    deviceToken = "hw:2,0",
                    identityKey = "stable-dac-id",
                    displayName = "Test DAC",
                    flags = ALSA_OUTPUT_DEVICE_ACTIVE,
                ),
            )
        }

        runDesktopOutputRecovery(intent, driver)

        assertEquals(1, driver.enumerationCount)
        assertEquals("hw:2,0", driver.switchedToken)
        assertEquals("stable-dac-id", requireNotNull(driver.resumed).outputIdentity)
    }

    private class FakeOutputRecoveryDriver : DesktopOutputRecoveryDriver {
        var current: Boolean = true
        var loadingProvider: () -> Boolean = { false }
        var enumerations: suspend () -> List<DesktopAudioOutputDevice> = { emptyList() }
        val events = mutableListOf<String>()
        var enumerationCount = 0
            private set
        var switchedToken: String? = null
            private set
        var resumed: DesktopOutputRecoveryIntent? = null
            private set
        var exhausted = false
            private set

        override suspend fun awaitRetry(attempt: Int) {
            events += "await:$attempt"
        }

        override fun isRequestCurrent(): Boolean = current

        override fun isSnapshotLoading(): Boolean = loadingProvider()

        override suspend fun enumerateDevices(): List<DesktopAudioOutputDevice> {
            events += "enumerate"
            enumerationCount += 1
            return enumerations()
        }

        override fun applySnapshot(devices: List<DesktopAudioOutputDevice>) {
            events += "snapshot"
        }

        override fun switchNativeOutput(deviceToken: String) {
            switchedToken = deviceToken
            events += "switch:$deviceToken"
        }

        override fun refreshEndpointVolume() {
            events += "volume"
        }

        override fun resume(intent: DesktopOutputRecoveryIntent) {
            resumed = intent
            events += "resume"
            // Production consumes the loss intent here, which supersedes the request.
            current = false
        }

        override fun onAttemptsExhausted() {
            exhausted = true
        }
    }

    private fun canResume(identity: String?, available: Boolean): Boolean =
        canResumeAfterOutputRecovery(
            intent = intent,
            currentTrackId = 42L,
            selectedOutputIdentity = identity,
            selectedOutputAvailable = available,
            selectedOutputIdentityStable = identity != null,
        )
}
