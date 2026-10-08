package dev.naominet.lazer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class DesktopUpnpQueueTest {
    @Test
    fun `renderer queue is the contiguous supported block containing current track`() {
        assertEquals(
            DesktopUpnpQueueSegment(startIndex = 1, endExclusive = 4, currentIndex = 1),
            desktopUpnpQueueSegment(
                supported = listOf(false, true, true, true, false, true),
                currentIndex = 2,
            ),
        )
        assertNull(desktopUpnpQueueSegment(listOf(true, false), currentIndex = 1))
        assertEquals(
            DesktopUpnpQueueSegment(startIndex = 1, endExclusive = 2, currentIndex = 0),
            desktopUpnpQueueSegment(listOf(false, true, false), currentIndex = 1),
        )
    }

    @Test
    fun `next and previous queue positions respect renderer playback mode`() {
        assertEquals(2, desktopUpnpNextQueueIndex(3, 1, DesktopPlayMode.Sequential))
        assertNull(desktopUpnpNextQueueIndex(3, 2, DesktopPlayMode.Sequential))
        assertEquals(0, desktopUpnpNextQueueIndex(3, 2, DesktopPlayMode.ListLoop))
        assertEquals(2, desktopUpnpNextQueueIndex(3, 1, DesktopPlayMode.SingleLoop))
        assertEquals(1, desktopUpnpNextQueueIndex(3, 1, DesktopPlayMode.SingleLoop, naturalEnd = true))
        assertEquals(2, desktopUpnpNextQueueIndex(3, 1, DesktopPlayMode.Shuffle, shuffleIndex = 2))
        assertEquals(2, desktopUpnpPreviousQueueIndex(3, 0))
        assertNull(desktopUpnpPreviousQueueIndex(0, 0))
        assertNotNull(desktopUpnpNextQueueIndex(1, 0, DesktopPlayMode.ListLoop))
    }

    @Test
    fun `natural end retries use bounded backoff instead of disabling queue advance`() {
        assertEquals(1_000L, desktopUpnpAdvanceRetryDelayMillis(1))
        assertEquals(3_000L, desktopUpnpAdvanceRetryDelayMillis(2))
        assertEquals(30_000L, desktopUpnpAdvanceRetryDelayMillis(5))
        assertEquals(30_000L, desktopUpnpAdvanceRetryDelayMillis(20))
    }

    @Test
    fun `media lease renewal cadence resets after a resource swap and survives nano time wrap`() {
        val startedAt = Long.MAX_VALUE - 50L
        val schedule = DesktopUpnpMediaLeaseRenewalSchedule(intervalNanos = 100L, nowNanos = startedAt)
        assertFalse(schedule.isDue(startedAt + 99L))
        assertTrue(schedule.isDue(startedAt + 100L))

        val resourcesSwappedAt = startedAt + 130L
        schedule.reset(resourcesSwappedAt)
        assertFalse(schedule.isDue(resourcesSwappedAt + 99L))
        assertTrue(schedule.isDue(resourcesSwappedAt + 100L))
    }

    @Test
    fun `a successful poll restores only the current uri and playing intent`() = runBlocking {
        val state = DesktopUpnpRecoveryState()
        assertFalse(state.observeConnection(DesktopUpnpConnectionState.DISCONNECTED))
        assertFalse(state.observeConnection(DesktopUpnpConnectionState.DISCONNECTED))
        assertTrue(state.observeConnection(DesktopUpnpConnectionState.CONNECTED))

        val token = state.beginRecovery() ?: error("Recovery should be claimable once.")
        val plan = state.plan(
            expectedUri = "http://renderer/current.wav",
            observedUri = null,
            observedState = "STOPPED",
        ) ?: error("A pending reconnect should produce a recovery plan.")
        assertEquals(
            DesktopUpnpRecoveryPlan(setMediaUri = true, command = DesktopUpnpTransportCommand.PLAY),
            plan,
        )
        val actions = mutableListOf<String>()
        executeDesktopUpnpRecoveryPlan(
            plan = plan,
            isCancelled = { !state.isCurrentRecovery(token) },
            setMediaUri = { actions += "uri" },
            control = { actions += it.name },
        )
        assertEquals(listOf("uri", "PLAY"), actions)
        state.finishRecovery(token, succeeded = true)
        assertFalse(state.observeConnection(DesktopUpnpConnectionState.CONNECTED))
        assertNull(state.plan("http://renderer/current.wav", "http://renderer/current.wav", "PLAYING"))
    }

    @Test
    fun `a failed recovery remains pending through repeated disconnects`() {
        val state = DesktopUpnpRecoveryState()
        state.observeConnection(DesktopUpnpConnectionState.DISCONNECTED)
        assertTrue(state.observeConnection(DesktopUpnpConnectionState.CONNECTED))
        val failedToken = state.beginRecovery() ?: error("Recovery should be claimable.")
        state.finishRecovery(failedToken, succeeded = false)

        assertFalse(state.observeConnection(DesktopUpnpConnectionState.DISCONNECTED))
        assertTrue(state.observeConnection(DesktopUpnpConnectionState.CONNECTED))
        val retryToken = state.beginRecovery() ?: error("A later poll should retry.")
        val plan = state.plan("http://renderer/current.wav", "http://renderer/current.wav", "STOPPED")
        assertEquals(DesktopUpnpRecoveryPlan(setMediaUri = false, command = DesktopUpnpTransportCommand.PLAY), plan)
        state.finishRecovery(retryToken, succeeded = true)
        assertFalse(state.isPending)
    }

    @Test
    fun `a lost uri with paused intent reaches paused state across a stopped-state fault`() = runBlocking {
        val state = DesktopUpnpRecoveryState()
        state.requestPause()
        state.observeConnection(DesktopUpnpConnectionState.DISCONNECTED)
        assertTrue(state.observeConnection(DesktopUpnpConnectionState.CONNECTED))
        val token = state.beginRecovery() ?: error("Recovery should be claimable.")
        assertEquals(
            DesktopUpnpRecoveryPlan(
                setMediaUri = true,
                command = null,
                pauseFromStopped = true,
                intent = DesktopUpnpTransportIntent.PAUSED,
            ),
            state.plan("http://renderer/current.wav", null, "STOPPED"),
        )
        assertEquals(
            DesktopUpnpRecoveryPlan(
                setMediaUri = false,
                command = DesktopUpnpTransportCommand.PAUSE,
                intent = DesktopUpnpTransportIntent.PAUSED,
            ),
            state.plan("http://renderer/current.wav", "http://renderer/current.wav", "PLAYING"),
        )
        val actions = mutableListOf<String>()
        var pauseAttempts = 0
        executeDesktopUpnpRecoveryPlan(
            plan = state.plan("http://renderer/current.wav", null, "STOPPED")!!,
            isCancelled = { !state.isCurrentRecovery(token) },
            setMediaUri = { actions += "URI" },
            control = { command ->
                actions += command.name
                if (command == DesktopUpnpTransportCommand.PAUSE && pauseAttempts++ == 0) {
                    throw DesktopUpnpSoapFaultException("Pause", 701, "Transition not available")
                }
            },
        )
        assertEquals(listOf("URI", "PAUSE", "PLAY", "PAUSE"), actions)
        assertFalse(
            desktopUpnpRecoveryConfirmed(
                DesktopUpnpTransportIntent.PAUSED,
                "http://renderer/current.wav",
                DesktopUpnpConnectionState.CONNECTED,
                "http://renderer/current.wav",
                "STOPPED",
            ),
        )
        assertTrue(
            desktopUpnpRecoveryConfirmed(
                DesktopUpnpTransportIntent.PAUSED,
                "http://renderer/current.wav",
                DesktopUpnpConnectionState.CONNECTED,
                "http://renderer/current.wav",
                "PAUSED_PLAYBACK",
            ),
        )
        assertFalse(
            desktopUpnpRecoveryConfirmed(
                DesktopUpnpTransportIntent.PLAYING,
                "http://renderer/current.wav",
                DesktopUpnpConnectionState.CONNECTED,
                "http://renderer/current.wav",
                "PLAYING",
                "ERROR_OCCURRED",
            ),
        )
        state.finishRecovery(token, succeeded = true)
    }

    @Test
    fun `explicit stop invalidates an in flight recovery before play`() = runBlocking {
        val state = DesktopUpnpRecoveryState()
        state.observeConnection(DesktopUpnpConnectionState.DISCONNECTED)
        state.observeConnection(DesktopUpnpConnectionState.CONNECTED)
        val token = state.beginRecovery() ?: error("Recovery should be claimable.")
        val plan = state.plan("http://renderer/current.wav", null, "STOPPED")!!
        val actions = mutableListOf<String>()
        executeDesktopUpnpRecoveryPlan(
            plan = plan,
            isCancelled = { !state.isCurrentRecovery(token) },
            setMediaUri = {
                actions += "uri"
                state.cancel()
            },
            control = { actions += it.name },
        )
        state.finishRecovery(token, succeeded = true)
        assertEquals(listOf("uri"), actions)
        assertFalse(state.observeConnection(DesktopUpnpConnectionState.CONNECTED))
        assertNull(state.plan("http://renderer/current.wav", null, "STOPPED"))
    }

    @Test
    fun `position checkpoint tracks playing paused and successful user seek samples`() {
        val state = DesktopUpnpRecoveryState()
        val uri = "http://renderer/current.flac"
        state.observePlaybackPosition(uri, uri, "PLAYING", 20_000L, 180_000L, 1_000_000_000L)
        state.observePlaybackPosition(uri, uri, "PAUSED_PLAYBACK", 35_000L, 180_000L, 2_000_000_000L)
        state.observeConnection(DesktopUpnpConnectionState.DISCONNECTED, 2_100_000_000L)

        // Reconnect samples cannot replace the position frozen at the first disconnect.
        state.observePlaybackPosition(uri, "http://renderer/other.flac", "PLAYING", 1_000L, 60_000L, 2_200_000_000L)
        assertEquals(35_000L, state.positionCheckpointForRecovery(uri)?.positionMillis)

        state.recordUserSeek(uri, uri, "PAUSED_PLAYBACK", 70_000L, 180_000L, 2_300_000_000L)
        assertEquals(70_000L, state.positionCheckpointForRecovery(uri)?.positionMillis)
    }

    @Test
    fun `a route change arms one recovery and freezes the latest position checkpoint`() {
        val oldUri = "http://renderer/old-interface/current.flac"
        val state = DesktopUpnpRecoveryState()
        state.observePlaybackPosition(oldUri, oldUri, "PLAYING", 42_000L, 180_000L, 1_000_000_000L)

        state.requestRecovery(1_500_000_000L)
        state.requestRecovery(2_000_000_000L)
        state.observePlaybackPosition(oldUri, oldUri, "PLAYING", 45_000L, 180_000L, 2_100_000_000L)

        assertTrue(state.isPending)
        val frozen = state.positionCheckpointForRecovery(oldUri)
        assertEquals(42_000L, frozen?.positionMillis)
        val newUri = "http://renderer/new-interface/current.flac"
        val checkpoint = desktopUpnpRebindPositionCheckpoint(frozen, oldUri, newUri)
        assertEquals(newUri, checkpoint?.trackUri)
        assertEquals(42_000L, checkpoint?.positionMillis)
        assertNull(desktopUpnpRebindPositionCheckpoint(frozen, "http://renderer/unrelated.wav", newUri))
    }

    @Test
    fun `route rebind seeks from the newer old uri position without trusting unrelated uri time`() {
        val oldUri = "http://renderer/old-interface/current.flac"
        val checkpoint = desktopUpnpPositionCheckpoint(
            oldUri,
            oldUri,
            "PLAYING",
            42_000L,
            180_000L,
            1_000_000_000L,
        )

        assertEquals(
            45_000L,
            desktopUpnpRouteRebindSeekTarget(
                checkpoint,
                oldUri,
                oldUri,
                "PLAYING",
                45_000L,
                180_000L,
                180_000L,
                2_000_000_000L,
            ),
        )
        assertEquals(
            42_000L,
            desktopUpnpRouteRebindSeekTarget(
                checkpoint,
                oldUri,
                "http://renderer/unrelated.flac",
                "PLAYING",
                90_000L,
                180_000L,
                180_000L,
                2_000_000_000L,
            ),
        )
        assertEquals(
            65_000L,
            desktopUpnpRouteRebindSeekTarget(
                checkpoint = null,
                previousUri = oldUri,
                observedUri = oldUri,
                observedState = "PAUSED_PLAYBACK",
                observedPositionMillis = 65_000L,
                observedDurationMillis = 180_000L,
                trackDurationMillis = 180_000L,
                observedAtNanos = 2_000_000_000L,
            ),
        )
    }

    @Test
    fun `old or invalid disconnect checkpoints are not restored`() {
        val uri = "http://renderer/current.flac"
        val state = DesktopUpnpRecoveryState()
        state.observePlaybackPosition(uri, uri, "PLAYING", 40_000L, 180_000L, 1_000_000_000L)
        state.observeConnection(DesktopUpnpConnectionState.DISCONNECTED, 12_000_000_001L)
        assertNull(state.positionCheckpointForRecovery(uri))

        state.recordUserSeek(uri, uri, "PAUSED_PLAYBACK", 180_000L, 180_000L)
        assertNull(state.positionCheckpointForRecovery(uri))

        assertNull(desktopUpnpPositionCheckpoint(uri, "http://renderer/other.flac", "PLAYING", 40_000L, 180_000L))
        assertNull(desktopUpnpPositionCheckpoint(uri, uri, "PLAYING", 180_000L, 180_000L))
        assertNull(desktopUpnpPositionCheckpoint(uri, uri, "UNAVAILABLE", 40_000L, 180_000L))
    }

    @Test
    fun `recovery seek restores a lost position without rewinding a continuing renderer`() {
        val uri = "http://renderer/current.flac"
        val checkpoint = desktopUpnpPositionCheckpoint(uri, uri, "PAUSED_PLAYBACK", 60_000L, 180_000L)
        assertEquals(
            60_000L,
            desktopUpnpRecoverySeekTarget(checkpoint, uri, null, "STOPPED", 0L, 180_000L),
        )
        assertEquals(
            60_000L,
            desktopUpnpRecoverySeekTarget(checkpoint, uri, uri, "STOPPED", 0L, 180_000L),
        )
        assertNull(desktopUpnpRecoverySeekTarget(checkpoint, uri, uri, "PLAYING", 63_000L, 180_000L))
        assertNull(desktopUpnpRecoverySeekTarget(checkpoint, "http://renderer/other.flac", null, "STOPPED", null, 180_000L))

        val nearEnd = desktopUpnpPositionCheckpoint(uri, uri, "PLAYING", 179_900L, 180_000L)
        assertEquals(
            179_750L,
            desktopUpnpRecoverySeekTarget(nearEnd, uri, null, "STOPPED", null, 180_000L),
        )
        assertTrue(desktopUpnpRecoveryPositionConfirmed(60_000L, 62_500L))
        assertFalse(desktopUpnpRecoveryPositionConfirmed(60_000L, 64_000L))
        assertTrue(desktopUpnpRecoveryPositionConfirmed(60_000L, null))
    }

    @Test
    fun `recovery seeks between uri replacement and transport restoration`() = runBlocking {
        val actions = mutableListOf<String>()
        val seekApplied = executeDesktopUpnpRecoveryPlan(
            plan = DesktopUpnpRecoveryPlan(
                setMediaUri = true,
                command = DesktopUpnpTransportCommand.PLAY,
                intent = DesktopUpnpTransportIntent.PLAYING,
                seekPositionMillis = 45_000L,
            ),
            isCancelled = { false },
            setMediaUri = { actions += "URI" },
            control = { actions += it.name },
            isSeekEnabled = { true },
            seek = { actions += "SEEK:$it" },
        )
        assertTrue(seekApplied)
        assertEquals(listOf("URI", "SEEK:45000", "PLAY"), actions)
    }

    @Test
    fun `seek transition fault retries once through play and restores paused intent`() = runBlocking {
        val actions = mutableListOf<String>()
        var seekAttempts = 0
        executeDesktopUpnpRecoveryPlan(
            plan = DesktopUpnpRecoveryPlan(
                setMediaUri = true,
                command = null,
                pauseFromStopped = true,
                intent = DesktopUpnpTransportIntent.PAUSED,
                seekPositionMillis = 45_000L,
            ),
            isCancelled = { false },
            setMediaUri = { actions += "URI" },
            control = { actions += it.name },
            isSeekEnabled = { true },
            seek = {
                actions += "SEEK:$it"
                if (seekAttempts++ == 0) throw DesktopUpnpSoapFaultException("Seek", 701, "Transition not available")
            },
        )
        assertEquals(listOf("URI", "SEEK:45000", "PLAY", "SEEK:45000", "PAUSE"), actions)
    }

    @Test
    fun `recovery can seek after play when seek is unavailable in stopped state`() = runBlocking {
        val actions = mutableListOf<String>()
        var readinessChecks = 0
        executeDesktopUpnpRecoveryPlan(
            plan = DesktopUpnpRecoveryPlan(
                setMediaUri = true,
                command = DesktopUpnpTransportCommand.PLAY,
                seekPositionMillis = 45_000L,
            ),
            isCancelled = { false },
            setMediaUri = { actions += "URI" },
            control = { actions += it.name },
            isSeekEnabled = { ++readinessChecks > 1 },
            seek = { actions += "SEEK:$it" },
        )
        assertEquals(listOf("URI", "PLAY", "SEEK:45000"), actions)
    }

    @Test
    fun `cancellation after uri prevents recovery seek and play`() = runBlocking {
        var cancelled = false
        val actions = mutableListOf<String>()
        executeDesktopUpnpRecoveryPlan(
            plan = DesktopUpnpRecoveryPlan(
                setMediaUri = true,
                command = DesktopUpnpTransportCommand.PLAY,
                seekPositionMillis = 45_000L,
            ),
            isCancelled = { cancelled },
            setMediaUri = { actions += "URI"; cancelled = true },
            control = { actions += it.name },
            seek = { actions += "SEEK:$it" },
        )
        assertEquals(listOf("URI"), actions)
    }

    @Test
    fun `only a healthy stopped state at the matching track end can advance`() {
        fun isNatural(
            state: String? = "STOPPED",
            uri: String? = "http://renderer/current.flac",
            status: String? = "OK",
            position: Long? = 10_000L,
            duration: Long? = 10_000L,
            previousState: String? = "PLAYING",
            previousStatus: String? = "OK",
            previousPosition: Long? = 9_500L,
            previousDuration: Long? = 10_000L,
            previousFresh: Boolean = true,
            observedPlaying: Boolean = true,
            explicitStop: Boolean = false,
        ) = desktopUpnpIsNaturalEnd(
            transportState = state,
            trackUri = uri,
            expectedTrackUri = "http://renderer/current.flac",
            transportStatus = status,
            positionMillis = position,
            durationMillis = duration,
            previousTransportState = previousState,
            previousTransportStatus = previousStatus,
            previousPositionMillis = previousPosition,
            previousDurationMillis = previousDuration,
            previousSampleIsFresh = previousFresh,
            observedPlayingForCurrentTrack = observedPlaying,
            explicitStopRequested = explicitStop,
        )

        assertTrue(isNatural())
        assertFalse(isNatural(observedPlaying = false)) // Initial STOPPED must never advance.
        assertFalse(isNatural(explicitStop = true))
        assertFalse(isNatural(status = "ERROR_OCCURRED"))
        assertFalse(isNatural(uri = "http://renderer/another.flac"))
        assertFalse(isNatural(position = 2_000L, previousPosition = null, previousState = "STOPPED"))
        assertFalse(isNatural(previousFresh = false, position = 0L))
        assertFalse(isNatural(status = null, previousStatus = null))
    }

    @Test
    fun `a renderer position reset at end may use a fresh playing sample`() {
        assertTrue(
            desktopUpnpIsNaturalEnd(
                transportState = "STOPPED",
                trackUri = "http://renderer/current.flac",
                expectedTrackUri = "http://renderer/current.flac",
                transportStatus = "OK",
                positionMillis = 0L,
                durationMillis = 10_000L,
                previousTransportState = "PLAYING",
                previousTransportStatus = "OK",
                previousPositionMillis = 9_900L,
                previousDurationMillis = 10_000L,
                previousSampleIsFresh = true,
                observedPlayingForCurrentTrack = true,
                explicitStopRequested = false,
            ),
        )
    }
}
