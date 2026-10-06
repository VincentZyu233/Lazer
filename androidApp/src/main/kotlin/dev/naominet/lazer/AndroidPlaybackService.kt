package dev.naominet.lazer

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat as PlatformAudioFormat
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.audiofx.Visualizer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.AudioAttributes as Media3AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dev.naominet.lazer.gateway.AudioQuality
import dev.naominet.lazer.gateway.NeteaseMusicGateway
import dev.naominet.lazer.gateway.model.SongUrl
import dev.naominet.lazer.PlaybackSourceStatus
import dev.naominet.lazer.PlaybackAudioRendererInputFormatSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

/** Entry point used by Compose controls. System-media mode calls back into the same service. */
object AndroidPlaybackConnection {
    val snapshot: StateFlow<LazerPlaybackSnapshot> = LazerPlaybackStateStore.snapshot
    @Volatile private var selectedUacDirectOutputDeviceId: String? = null

    private var didRestorePersistedQueue = false
    private var restoredLocalTracksWithoutPermission: Set<Long> = emptySet()

    fun currentQueue(): List<LazerTrack> = LazerPlaybackQueue.tracks.toList()

    /** The queue and its advance rule, observable so the player card redraws after an edit. */
    val queue: StateFlow<LazerPlaybackQueueSnapshot> = LazerPlaybackQueue.snapshot

    /** Restores the last queue before screens or a media action can observe the empty singleton. */
    @Synchronized
    fun restorePersistedQueue(context: Context) {
        if (didRestorePersistedQueue) return
        didRestorePersistedQueue = true
        val settings = AndroidSettingsStore(context.applicationContext)
        val store = AndroidPlaybackQueueStore(context)
        val persisted = store.loadQueue()
        if (persisted == null || !LazerPlaybackQueue.restoreSnapshot(persisted)) {
            LazerPlaybackQueue.restoreMode(settings.playMode)
            return
        }

        settings.playMode = persisted.mode
        val track = LazerPlaybackQueue.current()
        val storedPosition = store.loadPosition()
        val position = LazerPlaybackQueueCodec.restoredPositionMillis(
            track = track,
            savedTrackId = storedPosition?.trackId,
            savedPositionMillis = storedPosition?.positionMillis,
        )
        val localSource = track?.source as? LazerTrackSource.LocalFile
        val missingPermission = localSource != null && !hasPersistedReadPermission(context, localSource.uri)
        restoredLocalTracksWithoutPermission = persisted.tracks.asSequence()
            .mapNotNull { item ->
                val local = item.source as? LazerTrackSource.LocalFile ?: return@mapNotNull null
                item.id.takeIf { !hasPersistedReadPermission(context, local.uri) }
            }
            .toSet()
        LazerPlaybackStateStore.update(
            LazerPlaybackSnapshot(
                track = track,
                isPlaying = false,
                positionMillis = position,
                durationMillis = track?.durationMillis ?: 0L,
                message = if (missingPermission) tr("status.local_file_permission_lost") else null,
            ),
        )
    }

    fun setPlayMode(context: Context, mode: LazerPlayMode) {
        LazerPlaybackQueue.setMode(mode)
        AndroidSettingsStore(context).playMode = mode
        saveQueue(context)
    }

    /** Makes the item at [position] audible, reusing the hand-off that a list tap already takes. */
    fun playAt(context: Context, position: Int) {
        if (LazerPlaybackQueue.jumpTo(position) == null) return
        val store = AndroidPlaybackQueueStore(context)
        store.saveQueue(LazerPlaybackQueue.snapshot.value)
        LazerPlaybackQueue.current()?.let { store.savePosition(it.id, 0L, commit = true) }
        dispatch(
            context,
            Intent(context, AndroidPlaybackService::class.java)
                .setAction(AndroidPlaybackService.ACTION_PLAY_TRACK)
                .putExtra(AndroidPlaybackService.EXTRA_AUTOPLAY, true),
        )
    }

    fun removeAt(context: Context, position: Int) {
        val edit = LazerPlaybackQueue.removeAt(position)
        saveQueue(context)
        when (edit) {
            LazerQueueEdit.Kept -> Unit
            // The queue has already settled on its successor; asking for the removed index again
            // would fall off the end when the last track is the one that was audible.
            LazerQueueEdit.Switched -> playAt(context, LazerPlaybackQueue.index)
            LazerQueueEdit.Emptied -> dispatch(context, AndroidPlaybackService.ACTION_STOP)
        }
    }

    /** Reordering needs no round trip: the service reads the same in-process queue. */
    fun moveTrack(context: Context, from: Int, to: Int) {
        LazerPlaybackQueue.move(from, to)
        saveQueue(context)
    }

    fun play(
        context: Context,
        queue: List<LazerTrack>,
        track: LazerTrack,
        startPlaying: Boolean = true,
        positionMillis: Long = 0L,
    ) {
        LazerPlaybackQueue.replace(queue.ifEmpty { listOf(track) }, track)
        AndroidPlaybackQueueStore(context).apply {
            saveQueue(LazerPlaybackQueue.snapshot.value)
            savePosition(track.id, positionMillis, commit = true)
        }
        restoredLocalTracksWithoutPermission = emptySet()
        LazerPlaybackStateStore.update(
            LazerPlaybackSnapshot(
                track = track,
                isPreparing = true,
                positionMillis = positionMillis.coerceAtLeast(0L),
                durationMillis = track.durationMillis,
            ),
        )
        dispatch(
            context,
            Intent(context, AndroidPlaybackService::class.java)
                .setAction(AndroidPlaybackService.ACTION_PLAY_TRACK)
                .putExtra(AndroidPlaybackService.EXTRA_AUTOPLAY, startPlaying)
                .putExtra(AndroidPlaybackService.EXTRA_POSITION, positionMillis.coerceAtLeast(0L)),
        )
    }

    fun toggle(context: Context) = dispatch(context, AndroidPlaybackService.ACTION_TOGGLE)

    fun resume(context: Context) = dispatch(context, AndroidPlaybackService.ACTION_RESUME)

    fun pause(context: Context) = dispatch(context, AndroidPlaybackService.ACTION_PAUSE)

    fun next(context: Context) = dispatch(context, AndroidPlaybackService.ACTION_NEXT)

    fun previous(context: Context) = dispatch(context, AndroidPlaybackService.ACTION_PREVIOUS)

    fun seekTo(context: Context, positionMillis: Long) = dispatch(
        context,
        AndroidPlaybackService.ACTION_SEEK,
        AndroidPlaybackService.EXTRA_POSITION to positionMillis.coerceAtLeast(0L),
    )

    fun updateExclusiveAudio(context: Context) {
        if (snapshot.value.track == null) return
        dispatch(context, AndroidPlaybackService.ACTION_EXCLUSIVE_AUDIO_CHANGED)
    }

    fun updatePlaybackInterface(context: Context) {
        if (snapshot.value.track == null) return
        dispatch(context, AndroidPlaybackService.ACTION_PLAYBACK_INTERFACE_CHANGED)
    }

    fun updateUsbAudioTarget(context: Context, identity: String?) {
        val testToneStatus = LazerPcmTestToneStateStore.state.value.status
        val testToneActive = testToneStatus == LazerPcmTestToneStatus.Preparing ||
            testToneStatus == LazerPcmTestToneStatus.Playing
        if (snapshot.value.track == null && !testToneActive) return
        dispatch(
            context,
            Intent(context, AndroidPlaybackService::class.java)
                .setAction(AndroidPlaybackService.ACTION_USB_AUDIO_TARGET_CHANGED)
                .putExtra(AndroidPlaybackService.EXTRA_USB_TARGET_IDENTITY, identity)
                .putExtra(AndroidPlaybackService.EXTRA_USB_TARGET_USER_CHANGE, true),
        )
    }

    fun refreshUsbAudioTarget(context: Context, identity: String?) {
        if (snapshot.value.track == null) return
        dispatch(
            context,
            Intent(context, AndroidPlaybackService::class.java)
                .setAction(AndroidPlaybackService.ACTION_USB_AUDIO_TARGET_CHANGED)
                .putExtra(AndroidPlaybackService.EXTRA_USB_TARGET_IDENTITY, identity),
        )
    }

    /** Re-attaches or drops the spectrum capture after the audio-reactive setting changed. */
    fun updateAudioLevels(context: Context) {
        if (snapshot.value.track == null) return
        dispatch(context, AndroidPlaybackService.ACTION_AUDIO_LEVELS_CHANGED)
    }

    fun updateEqualizer(context: Context, state: LazerEqualizerState) {
        if (snapshot.value.track == null) return
        dispatch(
            context,
            Intent(context, AndroidPlaybackService::class.java)
                .setAction(AndroidPlaybackService.ACTION_EQUALIZER_CHANGED)
                .putExtra(AndroidPlaybackService.EXTRA_EQUALIZER, state.serialize()),
        )
    }

    fun updateReplayGainMode(context: Context, mode: LazerReplayGainMode) {
        if (snapshot.value.track == null) return
        dispatch(
            context,
            Intent(context, AndroidPlaybackService::class.java)
                .setAction(AndroidPlaybackService.ACTION_REPLAY_GAIN_CHANGED)
                .putExtra(AndroidPlaybackService.EXTRA_REPLAY_GAIN_MODE, mode.name),
        )
    }

    /** Sets explicit raw USB UAC output intent. The selected USB host device ID is not an
     * AudioManager route ID; the service resolves it through UsbManager after permission grant.
     */
    fun setUsbUacDirectOutput(context: Context, enabled: Boolean, deviceId: String) {
        require(deviceId.isNotBlank()) { "A selected USB UAC device is required" }
        if (enabled) {
            selectedUacDirectOutputDeviceId = deviceId
        } else if (selectedUacDirectOutputDeviceId == deviceId) {
            selectedUacDirectOutputDeviceId = null
        }
        if (snapshot.value.track == null) return
        dispatch(
            context,
            Intent(context, AndroidPlaybackService::class.java)
                .setAction(AndroidPlaybackService.ACTION_USB_UAC_DIRECT_OUTPUT_CHANGED)
                .putExtra(AndroidPlaybackService.EXTRA_USB_UAC_DIRECT_ENABLED, enabled)
                .putExtra(AndroidPlaybackService.EXTRA_USB_UAC_DIRECT_DEVICE_ID, deviceId),
        )
    }

    internal fun currentUsbUacDirectOutputDeviceId(): String? = selectedUacDirectOutputDeviceId

    internal fun clearUsbUacDirectOutputIfSelected(deviceId: String) {
        if (selectedUacDirectOutputDeviceId == deviceId) selectedUacDirectOutputDeviceId = null
    }

    fun stopAndClearSession(context: Context) = dispatch(context, AndroidPlaybackService.ACTION_STOP_AND_CLEAR_SESSION)

    fun playPcmTestTone(context: Context, format: LazerPcmTestFormat) = dispatch(
        context,
        Intent(context, AndroidPlaybackService::class.java)
            .setAction(AndroidPlaybackService.ACTION_PCM_TEST_TONE)
            .putExtra(AndroidPlaybackService.EXTRA_PCM_SAMPLE_RATE, format.sampleRateHz)
            .putExtra(AndroidPlaybackService.EXTRA_PCM_BIT_DEPTH, format.bitDepth),
    )

    fun stopPcmTestTone(context: Context) = dispatch(context, AndroidPlaybackService.ACTION_PCM_TEST_TONE_STOP)

    private fun dispatch(context: Context, action: String, extra: Pair<String, Long>? = null) = dispatch(
        context,
        Intent(context, AndroidPlaybackService::class.java).setAction(action).apply {
            extra?.let { putExtra(it.first, it.second) }
        },
    )

    private fun dispatch(context: Context, intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    private fun saveQueue(context: Context) {
        AndroidPlaybackQueueStore(context).saveQueue(LazerPlaybackQueue.snapshot.value)
    }

    private fun hasPersistedReadPermission(context: Context, uriValue: String): Boolean {
        val uri = runCatching { Uri.parse(uriValue) }.getOrNull() ?: return false
        if (uri.scheme != "content") return true
        return context.contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission
        }
    }

    internal fun restoredLocalTrackNeedsPermission(trackId: Long): Boolean =
        trackId in restoredLocalTracksWithoutPermission
}

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class AndroidPlaybackService : Service(), AudioManager.OnAudioFocusChangeListener {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val handler = Handler(Looper.getMainLooper())
    private var mediaSession: MediaSession? = null
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private lateinit var gatewaySettings: AndroidSettingsStore
    private lateinit var gatewaySessionStore: AndroidGatewaySessionStore
    private lateinit var gateway: NeteaseMusicGateway
    private var player: ExoPlayer? = null
    private var outputProvider: AndroidMedia3AudioOutputProvider? = null
    private var directOutputProvider: AndroidUac2DirectAudioOutputProvider? = null
    private var directUacDeviceId: String? = null
    private var equalizerState = LazerEqualizerState()
    private var replayGainMode = LazerReplayGainMode.Off
    private var preparedGeneration: Long? = null
    private var completedGeneration: Long? = null
    /** User intent is kept separately because ExoPlayer can be buffering while it is not isPlaying. */
    private var requestedPlayWhenReady = false
    private var loadingGeneration = 0L
    private var preparingGeneration: Long? = null
    private var preparationWakeLock: PowerManager.WakeLock? = null
    private var artworkGeneration = 0L
    private var artworkTrackId: Long? = null
    private var artworkBitmap: Bitmap? = null
    /** What the media session already holds, so a progress tick does not re-send unchanged state. */
    private var sessionMetadataKey: String? = null
    private var sessionStateValue: Int? = null
    private var sessionStatePositionMillis = -1L
    private lateinit var playbackQueueStore: AndroidPlaybackQueueStore
    private var lastPositionCheckpointAtMillis = 0L
    private var wasPlayingBeforeFocusLoss = false
    private var foregroundStarted = false
    private var audioVisualizer: Visualizer? = null
    private var audioLevelAnalyzer: AudioLevelAnalyzer? = null
    private var audioLevelSessionId = 0
    private var latestWaveform: ByteArray? = null
    private var audioLevelCaptureUnavailable = false
    @Volatile private var pcmTestToneGeneration = 0L
    private var pcmTestToneJob: Job? = null
    private var pcmTestToneOutput: AndroidPcmTestToneOutput? = null
    private var pcmTestToneResumeState: PcmTestToneResumeState? = null
    private val outputDeviceCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out android.media.AudioDeviceInfo>) {
            val targetId = pcmTestToneOutput?.selectedUsbDeviceId ?: return
            if (removedDevices.any { it.id == targetId }) {
                cancelPcmTestTone(
                    restoreMusic = false,
                    status = LazerPcmTestToneStatus.Failed,
                    detailKey = "settings.hifi.test_tone.device_removed",
                    stopServiceWhenIdle = true,
                )
            }
        }
    }
    private val usbDetachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            } ?: return
            handleDirectUsbDeviceDetached(device.deviceName)
        }
    }

    /**
     * The two halves of a capture arrive as separate callbacks; the spectrum is analysed against
     * the waveform that came with it, which is one capture old at most.
     */
    private val audioLevelListener = object : Visualizer.OnDataCaptureListener {
        override fun onWaveFormDataCapture(visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
            if (waveform != null) latestWaveform = waveform
        }

        override fun onFftDataCapture(visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int) {
            val analyzer = audioLevelAnalyzer ?: return
            val waveform = latestWaveform ?: return
            LazerAudioLevels.publish(analyzer.analyze(waveform, fft ?: return))
        }
    }

    private val streamUrls = object : LinkedHashMap<AndroidStreamCacheKey, AndroidResolvedAudioStream>(
        STREAM_URL_CACHE_SIZE,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<AndroidStreamCacheKey, AndroidResolvedAudioStream>?): Boolean =
            size > STREAM_URL_CACHE_SIZE
    }
    private val streamUrlPrefetches = mutableSetOf<AndroidStreamCacheKey>()
    private val mediaRequestHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android ${Build.VERSION.RELEASE}) Lazer/1.2",
        "Referer" to "https://music.163.com/",
    )

    private val progressReporter = object : Runnable {
        override fun run() {
            val currentPlayer = player ?: return
            val playing = runCatching { currentPlayer.isPlaying }.getOrDefault(false)
            if (playing) {
                publishCurrentState(isPreparing = false, isPlaying = true)
                handler.postDelayed(this, PROGRESS_UPDATE_MILLIS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        gatewaySettings = AndroidSettingsStore(applicationContext)
        AndroidPlaybackConnection.restorePersistedQueue(applicationContext)
        playbackQueueStore = AndroidPlaybackQueueStore(applicationContext)
        equalizerState = gatewaySettings.equalizer
        replayGainMode = gatewaySettings.replayGainMode
        gatewaySessionStore = AndroidGatewaySessionStore(applicationContext)
        gateway = NeteaseMusicGateway(sessionStore = gatewaySessionStore)
        directUacDeviceId = AndroidPlaybackConnection.currentUsbUacDirectOutputDeviceId()
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        audioManager.registerAudioDeviceCallback(outputDeviceCallback, handler)
        val usbDetachFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbDetachReceiver, usbDetachFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(usbDetachReceiver, usbDetachFilter)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) createNotificationChannel()
        if (gatewaySettings.playbackInterface.usesSystemMediaControls()) ensureMediaSession()
        SuperLyricPublisher.ensureRegistered()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PCM_TEST_TONE -> {
                ensureTestToneForeground()
                startPcmTestTone(
                    sampleRateHz = intent.getIntExtra(EXTRA_PCM_SAMPLE_RATE, 0),
                    bitDepth = intent.getIntExtra(EXTRA_PCM_BIT_DEPTH, 0),
                )
            }
            ACTION_PCM_TEST_TONE_STOP -> {
                if (!foregroundStarted) ensureTestToneForeground()
                cancelPcmTestTone(restoreMusic = true, stopServiceWhenIdle = true)
                stopToneForegroundIfIdle()
            }
            ACTION_PLAY_TRACK -> {
                cancelPcmTestTone(restoreMusic = false)
                directUacDeviceId = AndroidPlaybackConnection.currentUsbUacDirectOutputDeviceId()
                LazerPlaybackQueue.current()?.let { track ->
                    resolveAndPlay(
                        track = track,
                        autoplay = intent.getBooleanExtra(EXTRA_AUTOPLAY, true),
                        startPositionMillis = intent.getLongExtra(EXTRA_POSITION, 0L),
                    )
                }
                    ?: publishError(tr("status.audio_queue_end"))
            }
            ACTION_USB_UAC_DIRECT_OUTPUT_CHANGED -> handleDirectOutputModeChanged(
                enabled = intent.getBooleanExtra(EXTRA_USB_UAC_DIRECT_ENABLED, false),
                deviceId = intent.getStringExtra(EXTRA_USB_UAC_DIRECT_DEVICE_ID),
            )
            ACTION_TOGGLE -> {
                cancelPcmTestTone(restoreMusic = false)
                if (requestedPlayWhenReady) pauseCurrent() else resumeCurrent()
            }
            ACTION_RESUME -> { cancelPcmTestTone(restoreMusic = false); resumeCurrent() }
            ACTION_PAUSE -> { cancelPcmTestTone(restoreMusic = false); pauseCurrent() }
            ACTION_NEXT -> { cancelPcmTestTone(restoreMusic = false); playNext() }
            ACTION_PREVIOUS -> { cancelPcmTestTone(restoreMusic = false); playPrevious() }
            ACTION_SEEK -> { cancelPcmTestTone(restoreMusic = false); seekTo(intent.getLongExtra(EXTRA_POSITION, 0L)) }
            ACTION_EXCLUSIVE_AUDIO_CHANGED -> refreshAudioFocusMode()
            ACTION_PLAYBACK_INTERFACE_CHANGED -> refreshPlaybackInterface()
            ACTION_USB_AUDIO_TARGET_CHANGED -> {
                if (intent.getBooleanExtra(EXTRA_USB_TARGET_USER_CHANGE, false)) {
                    cancelPcmTestTone(restoreMusic = true)
                }
                outputProvider?.updateUsbAudioTarget(intent.getStringExtra(EXTRA_USB_TARGET_IDENTITY))
            }
            ACTION_AUDIO_LEVELS_CHANGED -> refreshAudioLevelCapture()
            ACTION_EQUALIZER_CHANGED -> {
                equalizerState = parseLazerEqualizer(intent.getStringExtra(EXTRA_EQUALIZER))
                outputProvider?.updateEqualizer(equalizerState)
            }
            ACTION_REPLAY_GAIN_CHANGED -> {
                replayGainMode = LazerReplayGainMode.entries.firstOrNull {
                    it.name == intent.getStringExtra(EXTRA_REPLAY_GAIN_MODE)
                } ?: LazerReplayGainMode.Off
                outputProvider?.updateReplayGainDb(activeReplayGainResolution().appliedGainDb)
            }
            ACTION_STOP -> stopPlayback()
            ACTION_STOP_AND_CLEAR_SESSION -> stopPlayback(clearSession = true)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onAudioFocusChange(focusChange: Int) {
        if (!gatewaySettings.playbackInterface.usesSystemMediaControls()) return
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> if (wasPlayingBeforeFocusLoss) {
                wasPlayingBeforeFocusLoss = false
                resumeCurrent(requestFocus = false)
            }
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                cancelPcmTestTone(restoreMusic = false, stopServiceWhenIdle = true)
                wasPlayingBeforeFocusLoss = requestedPlayWhenReady
                pauseCurrent(abandonExclusiveFocus = false)
            }
        }
    }

    private fun startPcmTestTone(sampleRateHz: Int, bitDepth: Int) {
        val format = runCatching { LazerPcmTestFormat(sampleRateHz, bitDepth) }.getOrNull()
        if (format == null) {
            publishPcmTestToneFailure("settings.hifi.test_tone.failed")
            return
        }
        cancelPcmTestTone(restoreMusic = true)
        val before = LazerPlaybackStateStore.snapshot.value
        if (before.isPreparing && player == null) {
            publishPcmTestToneFailure("settings.hifi.test_tone.wait_for_track")
            return
        }
        if (!requestAudioFocus()) {
            publishPcmTestToneFailure("settings.hifi.test_tone.focus_failed")
            return
        }

        val currentPlayer = player
        val wasPlaying = requestedPlayWhenReady
        val resumeState = PcmTestToneResumeState(
            trackId = before.track?.id,
            wasPlaying = wasPlaying,
            playbackGeneration = loadingGeneration,
        )
        pcmTestToneResumeState = resumeState
        if (currentPlayer != null && wasPlaying) {
            pauseCurrent(abandonExclusiveFocus = false, cancelTone = false)
        }

        val initial = LazerPcmTestToneStateStore.state.value
        LazerPcmTestToneStateStore.publish(
            initial.copy(
                status = LazerPcmTestToneStatus.Preparing,
                format = format,
                route = LazerPcmTestToneRoute.Unknown,
                mixerPreference = LazerMixerPreferenceStatus.NotAvailable,
                mixerReportsBitPerfectBehavior = null,
                detailKey = null,
            ),
        )
        val generation = ++pcmTestToneGeneration
        val output = AndroidPcmTestToneOutput(this)
        pcmTestToneOutput = output
        pcmTestToneJob = scope.launch {
            var finalStatus = LazerPcmTestToneStatus.Completed
            var detailKey: String? = null
            var finalResult: AndroidPcmTestToneOutput.Result? = null
            try {
                finalResult = withContext(Dispatchers.IO) {
                    output.play(format) { route, preference, bitPerfect ->
                        if (generation == pcmTestToneGeneration) {
                            LazerPcmTestToneStateStore.publish(
                                LazerPcmTestToneStateStore.state.value.copy(
                                    status = LazerPcmTestToneStatus.Playing,
                                    format = format,
                                    route = route,
                                    mixerPreference = preference,
                                    mixerReportsBitPerfectBehavior = bitPerfect,
                                    detailKey = null,
                                ),
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                finalStatus = LazerPcmTestToneStatus.Failed
                detailKey = "settings.hifi.test_tone.failed"
            } finally {
                output.close()
                if (generation == pcmTestToneGeneration) {
                    pcmTestToneOutput = null
                    pcmTestToneJob = null
                    pcmTestToneResumeState = null
                    val old = LazerPcmTestToneStateStore.state.value
                    LazerPcmTestToneStateStore.publish(
                        old.copy(
                            status = finalStatus,
                            format = format,
                            route = finalResult?.route ?: old.route,
                            mixerPreference = finalResult?.mixerPreference ?: old.mixerPreference,
                            mixerReportsBitPerfectBehavior = finalResult?.mixerReportsBitPerfectBehavior
                                ?: old.mixerReportsBitPerfectBehavior,
                            detailKey = detailKey,
                        ),
                    )
                    restoreMusicAfterTest(resumeState)
                    LazerPlaybackStateStore.snapshot.value.track?.let { track ->
                        ensureForeground(track, preparing = LazerPlaybackStateStore.snapshot.value.isPreparing)
                    }
                    stopToneForegroundIfIdle()
                }
            }
        }
    }

    private fun cancelPcmTestTone(
        restoreMusic: Boolean,
        status: LazerPcmTestToneStatus = LazerPcmTestToneStatus.Idle,
        detailKey: String? = null,
        stopServiceWhenIdle: Boolean = false,
    ) {
        val oldState = LazerPcmTestToneStateStore.state.value
        val isActive = pcmTestToneOutput != null || pcmTestToneJob != null ||
            oldState.status == LazerPcmTestToneStatus.Preparing || oldState.status == LazerPcmTestToneStatus.Playing
        if (!isActive) return

        ++pcmTestToneGeneration
        val resumeState = pcmTestToneResumeState
        pcmTestToneResumeState = null
        pcmTestToneOutput?.close()
        pcmTestToneOutput = null
        pcmTestToneJob?.cancel()
        pcmTestToneJob = null
        LazerPcmTestToneStateStore.publish(
            oldState.copy(status = status, detailKey = detailKey),
        )
        if (restoreMusic) restoreMusicAfterTest(resumeState)
        if (stopServiceWhenIdle) stopToneForegroundIfIdle()
        LazerPlaybackStateStore.snapshot.value.track?.let { track ->
            ensureForeground(track, preparing = LazerPlaybackStateStore.snapshot.value.isPreparing)
        }
    }

    private fun publishPcmTestToneFailure(detailKey: String) {
        val old = LazerPcmTestToneStateStore.state.value
        LazerPcmTestToneStateStore.publish(
            old.copy(status = LazerPcmTestToneStatus.Failed, detailKey = detailKey),
        )
        LazerPlaybackStateStore.snapshot.value.let { current ->
            current.track?.let { ensureForeground(it, preparing = current.isPreparing) }
        }
        stopToneForegroundIfIdle()
    }

    private fun restoreMusicAfterTest(resumeState: PcmTestToneResumeState?) {
        resumeState ?: return
        if (!resumeState.wasPlaying || resumeState.playbackGeneration != loadingGeneration) return
        val current = LazerPlaybackStateStore.snapshot.value
        if (current.track?.id != resumeState.trackId) return
        val currentPlayer = player ?: return
        if (!requestAudioFocus()) {
            LazerPlaybackStateStore.update(current.copy(message = audioFocusFailureMessage()))
            return
        }
        requestedPlayWhenReady = true
        runCatching { currentPlayer.play() }.onSuccess {
            publishCurrentState(isPreparing = current.isPreparing, isPlaying = currentPlayer.isPlaying)
            current.track?.let { ensureForeground(it, preparing = current.isPreparing) }
            handler.removeCallbacks(progressReporter)
            if (currentPlayer.isPlaying) handler.post(progressReporter)
        }
    }

    private fun ensureTestToneForeground() {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(tr("settings.hifi.test_tone.playing"))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent())
            .build()
        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, notification)
            foregroundStarted = true
        } else {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
        }
    }

    private fun stopToneForegroundIfIdle() {
        if (LazerPlaybackStateStore.snapshot.value.track != null) return
        if (foregroundStarted) stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun resolveAndPlay(
        track: LazerTrack,
        autoplay: Boolean = true,
        startPositionMillis: Long = 0L,
    ) {
        cancelPcmTestTone(restoreMusic = false)
        if (AndroidPlaybackConnection.restoredLocalTrackNeedsPermission(track.id)) {
            requestedPlayWhenReady = false
            ++loadingGeneration
            ++artworkGeneration
            releasePreparationWakeLock()
            releasePlayer()
            abandonAudioFocus()
            artworkTrackId = null
            artworkBitmap = null
            val position = startPositionMillis.coerceAtLeast(0L)
                .let { value -> if (track.durationMillis > 0L) value.coerceAtMost(track.durationMillis) else value }
            playbackQueueStore.saveQueue(LazerPlaybackQueue.snapshot.value)
            playbackQueueStore.savePosition(track.id, position, commit = true)
            lastPositionCheckpointAtMillis = SystemClock.elapsedRealtime()
            LazerPlaybackStateStore.update(
                LazerPlaybackSnapshot(
                    track = track,
                    isPreparing = false,
                    isPlaying = false,
                    positionMillis = position,
                    durationMillis = track.durationMillis,
                    message = tr("status.local_file_permission_lost"),
                ),
            )
            updateSession(track, isPlaying = false, positionMillis = position)
            SuperLyricPublisher.stop()
            ensureForeground(track, preparing = false)
            return
        }
        requestedPlayWhenReady = autoplay
        val generation = ++loadingGeneration
        acquirePreparationWakeLock(generation)
        releasePlayer()
        abandonAudioFocus()
        requestArtwork(track)
        ensureForeground(track, preparing = true)
        LazerPlaybackStateStore.update(
            LazerPlaybackSnapshot(
                track = track,
                isPreparing = true,
                positionMillis = startPositionMillis.coerceAtLeast(0L),
                durationMillis = track.durationMillis,
            ),
        )
        playbackQueueStore.saveQueue(LazerPlaybackQueue.snapshot.value)
        playbackQueueStore.savePosition(track.id, startPositionMillis, commit = true)
        lastPositionCheckpointAtMillis = SystemClock.elapsedRealtime()
        updateSession(
            track,
            isPlaying = false,
            positionMillis = startPositionMillis.coerceAtLeast(0L),
            isPreparing = true,
        )
        scope.launch {
            val playbackSource = androidAudioPlaybackSource(track)
            val resolvedStream = when (playbackSource) {
                is AndroidAudioPlaybackSource.LocalUri -> null
                is AndroidAudioPlaybackSource.GatewaySong -> runCatching {
                    resolveStreamUrl(playbackSource.trackId)
                }.onFailure { error ->
                    Log.e(TAG, "Gateway failed to resolve audio URL for ${playbackSource.trackId}", error)
                }.getOrNull()
            }
            if (generation != loadingGeneration) return@launch
            val mediaUri = when (playbackSource) {
                is AndroidAudioPlaybackSource.LocalUri -> playbackSource.uri.takeIf(String::isNotBlank)
                is AndroidAudioPlaybackSource.GatewaySong -> resolvedStream?.url
            }
            if (mediaUri == null) {
                publishError(tr("status.track_unplayable"))
                return@launch
            }
            LazerPlaybackStateStore.update(
                LazerPlaybackStateStore.snapshot.value.copy(source = resolvedStream?.source),
            )
            preparePlayer(track, mediaUri, generation, startPositionMillis)
            if (playbackSource is AndroidAudioPlaybackSource.GatewaySong) prefetchAdjacentStreamUrls()
        }
    }

    private fun preparePlayer(
        track: LazerTrack,
        url: String,
        generation: Long,
        startPositionMillis: Long,
    ) {
        preparedGeneration = null
        completedGeneration = null
        val replayGainResolution = if (track.source is LazerTrackSource.LocalFile) {
            resolveLazerReplayGain(track.replayGain, replayGainMode)
        } else {
            LazerReplayGainResolution(0.0, LazerReplayGainSource.None)
        }
        val directDeviceId = directUacDeviceId
        val doPOutput = directDeviceId != null &&
            track.source is LazerTrackSource.LocalFile &&
            isLocalDsdUri(this, Uri.parse(url))
        val audioOutputProvider: AudioOutputProvider = if (directDeviceId != null) {
            AndroidUac2DirectAudioOutputProvider(
                context = this,
                deviceId = directDeviceId,
                doP = doPOutput,
                onOutputConfigured = { config, deviceName, usbBitDepth ->
                    if (generation == loadingGeneration) {
                        val inputIsFloat = config.encoding == C.ENCODING_PCM_FLOAT
                        val encodingLabel = if (doPOutput) "DoP (PCM24 carrier)" else "PCM $usbBitDepth-bit"
                        val outputData = PlaybackAudioOutputDataSnapshot(
                            sampleRateHz = config.sampleRate,
                            encodingLabel = encodingLabel,
                            channelCount = Integer.bitCount(config.channelMask),
                            isLinearPcm = !doPOutput,
                            offload = false,
                            tunneling = false,
                        )
                        LazerPlaybackStateStore.update { state ->
                            if (generation != loadingGeneration || state.track?.id != track.id) {
                                state
                            } else {
                                state.copy(
                                    output = PlaybackOutputSnapshot(
                                        requestedSampleRateHz = config.sampleRate,
                                        audioTrackSampleRateHz = null,
                                        encodingLabel = encodingLabel,
                                        channelCount = Integer.bitCount(config.channelMask),
                                        routedDeviceName = deviceName,
                                        mixerAdvertisesBitPerfectBehavior = null,
                                        mixerPreferenceAccepted = null,
                                        appDspMayModifySamples = false,
                                        directPath = DirectPathSnapshot(
                                            status = if (inputIsFloat) DirectPathStatus.Unknown else DirectPathStatus.Negotiated,
                                            reason = if (inputIsFloat) {
                                                DirectPathReason.BitDepthConversion
                                            } else {
                                                DirectPathReason.DigitalCaptureNotVerified
                                            },
                                            detail = if (doPOutput) {
                                                "Raw DSD is packed into PCM24 DoP carriers; DAC lock and digital capture are not verified"
                                            } else if (inputIsFloat) {
                                                "Media3 high-resolution float PCM is dithered to the USB integer format"
                                            } else null,
                                        ),
                                        outputDataFormat = outputData,
                                    ),
                                )
                            }
                        }
                    }
                },
                onOutputFailed = { error -> reportDirectOutputFailure(directDeviceId, error) },
            ).also { directOutputProvider = it }.provider
        } else {
            AndroidMedia3AudioOutputProvider(
                context = this,
                initialEqualizer = equalizerState,
                initialReplayGainDb = replayGainResolution.appliedGainDb,
            ) { observed ->
                if (generation != loadingGeneration) return@AndroidMedia3AudioOutputProvider
                val format = observed.actualTrackFormat ?: observed.requestedTrackFormat
                LazerPlaybackStateStore.update { state ->
                    if (generation != loadingGeneration || state.track?.id != track.id) {
                        state
                    } else {
                        state.copy(
                            output = PlaybackOutputSnapshot(
                                requestedSampleRateHz = observed.requestedTrackFormat.sampleRateHz,
                                audioTrackSampleRateHz = observed.actualTrackFormat?.sampleRateHz,
                                encodingLabel = androidAudioEncodingLabel(format.encoding),
                                channelCount = androidAudioChannelCount(format),
                                routedDeviceName = observed.routedDevice?.productName,
                                audioTrackFormatMatchesRequested = observed.audioTrackFormatMatchesRequested,
                                mixerAdvertisesExactFormat = observed.mixerAdvertisesExactFormat,
                                mixerAdvertisesBitPerfectBehavior = observed.mixerAdvertisesBitPerfectBehavior,
                                mixerPreferenceAccepted = observed.mixerPreferenceAccepted,
                                appDspMayModifySamples = observed.appDspMayModifySamples,
                                replayGainAppliedDb = observed.replayGainAppliedDb,
                                directPath = observed.directPath,
                                outputDataFormat = observed.outputDataFormat,
                            ),
                        )
                    }
                }
            }.also { outputProvider = it }.provider
        }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(mediaRequestHeaders.getValue("User-Agent"))
            .setDefaultRequestProperties(mediaRequestHeaders)
        val upstreamDataSourceFactory = DefaultDataSource.Factory(this, httpFactory)
        val dataSourceFactory = AndroidDsdPcmDataSourceFactory(
            this,
            upstreamDataSourceFactory,
            outputMode = if (doPOutput) AndroidDsdOutputMode.DoP else AndroidDsdOutputMode.Pcm,
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory)
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableAudioFloatOutput(true)
            .setEnableAudioTrackPlaybackParams(directDeviceId == null)
            .setEnableAudioOutputPlaybackParameters(directDeviceId == null)
        val newPlayer = ExoPlayer.Builder(this)
            .setMediaSourceFactory(mediaSourceFactory)
            .setRenderersFactory(renderersFactory)
            .setAudioOutputProvider(audioOutputProvider)
            .build()
        newPlayer.setAudioAttributes(
            Media3AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            false, // The service owns focus changes so exclusiveAudio never requests focus twice.
        )
        player = newPlayer
        newPlayer.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioInputFormatChanged(
                eventTime: AnalyticsListener.EventTime,
                format: Format,
                decoderReuseEvaluation: DecoderReuseEvaluation?,
            ) {
                if (generation != loadingGeneration || player !== newPlayer) return
                LazerPlaybackStateStore.update { state ->
                    if (generation != loadingGeneration || state.track?.id != track.id) {
                        state
                    } else {
                        state.copy(
                            audioRendererInputFormat = PlaybackAudioRendererInputFormatSnapshot(
                                sampleRateHz = format.sampleRate.takeIf { it > 0 },
                                encodingLabel = media3PcmEncodingLabel(format.pcmEncoding),
                                channelCount = format.channelCount.takeIf { it > 0 },
                                sampleMimeType = format.sampleMimeType?.takeIf(String::isNotBlank),
                            ),
                        )
                    }
                }
            }
        })
        newPlayer.addListener(object : Player.Listener {
            private fun isCurrent(): Boolean = generation == loadingGeneration && player === newPlayer

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (!isCurrent()) return
                when (playbackState) {
                    Player.STATE_BUFFERING -> publishBufferedFraction(newPlayer, track)
                    Player.STATE_READY -> {
                        publishBufferedFraction(newPlayer, track)
                        if (preparedGeneration == generation) return
                        preparedGeneration = generation
                        LazerPlaybackStateStore.snapshot.value.source?.let { source ->
                            LazerPlaybackStateStore.update(
                                LazerPlaybackStateStore.snapshot.value.copy(
                                    source = source.copy(status = PlaybackSourceStatus.Prepared),
                                ),
                            )
                        }
                        // The prepared player now owns the hand-off; media controls can operate
                        // while later network buffering continues without holding this lock.
                        releasePreparationWakeLock(generation)
                        retargetAudioLevelCapture(newPlayer.audioSessionId)
                        val boundedStart = startPositionMillis.coerceIn(
                            0L,
                            newPlayer.duration.takeIf { it >= 0L } ?: startPositionMillis,
                        )
                        if (boundedStart > 0L) newPlayer.seekTo(boundedStart)
                        if (requestedPlayWhenReady && !requestAudioFocus()) {
                            publishError(audioFocusFailureMessage())
                            return
                        }
                        if (requestedPlayWhenReady) newPlayer.play()
                        publishCurrentState(isPreparing = false, isPlaying = newPlayer.isPlaying)
                        ensureForeground(track, preparing = false)
                        handler.removeCallbacks(progressReporter)
                        if (newPlayer.isPlaying) handler.post(progressReporter)
                    }
                    Player.STATE_ENDED -> if (completedGeneration != generation) {
                        completedGeneration = generation
                        playOnCompletion()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isCurrent() || preparedGeneration != generation) return
                publishCurrentState(isPreparing = false, isPlaying = isPlaying)
                handler.removeCallbacks(progressReporter)
                if (isPlaying) handler.post(progressReporter)
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int,
            ) {
                if (isCurrent() && preparedGeneration == generation) {
                    publishCurrentState(isPreparing = false, isPlaying = newPlayer.isPlaying)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Media3 failed for ${track.id}: code=${error.errorCodeName}")
                if (isCurrent()) publishError(tr("status.track_unplayable"))
            }
        })
        refreshAudioLevelCapture()
        runCatching {
            newPlayer.setMediaItem(MediaItem.fromUri(url))
            newPlayer.prepare()
        }.onFailure { error ->
            Log.e(TAG, "Media3 could not open stream for ${track.id}", error)
            if (generation == loadingGeneration) publishError(tr("status.track_unplayable"))
        }
    }

    private fun publishBufferedFraction(currentPlayer: ExoPlayer, track: LazerTrack) {
        val duration = currentPlayer.duration.takeIf { it > 0L } ?: track.durationMillis
        val fraction = if (duration > 0L) {
            (currentPlayer.bufferedPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }
        LazerPlaybackStateStore.update(
            LazerPlaybackStateStore.snapshot.value.copy(bufferedFraction = fraction),
        )
    }

    private fun androidAudioEncodingLabel(encoding: Int): String = when (encoding) {
        PlatformAudioFormat.ENCODING_PCM_8BIT -> "PCM 8-bit"
        PlatformAudioFormat.ENCODING_PCM_16BIT -> "PCM 16-bit"
        PlatformAudioFormat.ENCODING_PCM_24BIT_PACKED -> "PCM 24-bit"
        PlatformAudioFormat.ENCODING_PCM_32BIT -> "PCM 32-bit"
        PlatformAudioFormat.ENCODING_PCM_FLOAT -> "PCM float"
        else -> "Android encoding $encoding"
    }

    private fun androidAudioChannelCount(format: AndroidMedia3TrackFormat): Int =
        Integer.bitCount(format.channelMask).takeIf { it > 0 }
            ?: Integer.bitCount(format.channelIndexMask).takeIf { it > 0 }
            ?: 0

    private suspend fun resolveStreamUrl(
        trackId: Long,
        preferredQuality: AudioQuality = gatewaySettings.audioQuality,
    ): AndroidResolvedAudioStream? {
        val cacheKey = AndroidStreamCacheKey(trackId, preferredQuality)
        val now = System.currentTimeMillis()
        streamUrls[cacheKey]
            ?.takeIf { it.expiresAtMillis > now }
            ?.let { return it }
        streamUrls.remove(cacheKey)

        val attempts = androidAudioQualityAttempts(cacheKey.quality)
        attempts.forEach { (quality, unblock) ->
            val songUrl = withContext(Dispatchers.IO) {
                runCatching {
                    val streams = gateway.songUrls(listOf(trackId), quality = quality, unblock = unblock).data
                    androidSelectSongUrl(streams, trackId)
                }.onFailure { error ->
                    Log.w(TAG, "Audio URL attempt failed for $trackId at ${quality.label}", error)
                }.getOrNull()
            }
            androidResolvedAudioStream(
                songUrl = songUrl,
                requestedQuality = cacheKey.quality,
                resolvedQualityAttempt = quality,
                usedUnblockFallback = unblock,
                expiresAtMillis = System.currentTimeMillis() + STREAM_URL_CACHE_TTL_MILLIS,
            )?.let { resolved ->
                streamUrls[cacheKey] = resolved
                return resolved
            }
        }
        return null
    }

    /** Resolve nearby URLs while the current player is buffering, so next/previous skips avoid a round trip. */
    private fun prefetchAdjacentStreamUrls() {
        val preferredQuality = gatewaySettings.audioQuality
        LazerPlaybackQueue.adjacent()
            .filter { it.source == LazerTrackSource.GatewaySong }
            .forEach { track ->
            val cacheKey = AndroidStreamCacheKey(track.id, preferredQuality)
            val usableCachedUrl = streamUrls[cacheKey]?.expiresAtMillis ?: 0L
            if (usableCachedUrl > System.currentTimeMillis() || !streamUrlPrefetches.add(cacheKey)) return@forEach
            scope.launch {
                try {
                    resolveStreamUrl(track.id, preferredQuality)
                } finally {
                    streamUrlPrefetches.remove(cacheKey)
                }
            }
            }
    }

    private fun resumeCurrent(requestFocus: Boolean = true) {
        cancelPcmTestTone(restoreMusic = false)
        requestedPlayWhenReady = true
        val currentPlayer = player
        if (currentPlayer == null) {
            if (LazerPlaybackStateStore.snapshot.value.isPreparing) return
            val snapshot = LazerPlaybackStateStore.snapshot.value
            LazerPlaybackQueue.current()?.let {
                resolveAndPlay(it, startPositionMillis = snapshot.positionMillis)
            }
            return
        }
        // Setting play intent while buffering is enough; STATE_READY will start the stream after
        // acquiring focus. This avoids a second focus request when the decoder becomes ready.
        if (preparedGeneration != loadingGeneration) return
        if (requestFocus && !requestAudioFocus()) {
            requestedPlayWhenReady = false
            publishError(audioFocusFailureMessage())
            return
        }
        runCatching { currentPlayer.play() }.onSuccess {
            publishCurrentState(isPreparing = false, isPlaying = true)
            LazerPlaybackStateStore.snapshot.value.track?.let { ensureForeground(it, preparing = false) }
            handler.removeCallbacks(progressReporter)
            handler.post(progressReporter)
        }
    }

    private fun pauseCurrent(
        abandonExclusiveFocus: Boolean = true,
        cancelTone: Boolean = true,
    ) {
        if (cancelTone) cancelPcmTestTone(restoreMusic = false)
        requestedPlayWhenReady = false
        player?.let { currentPlayer -> runCatching { currentPlayer.pause() } }
        val snapshot = LazerPlaybackStateStore.snapshot.value
        publishCurrentState(isPreparing = snapshot.isPreparing, isPlaying = false)
        snapshot.track?.let { ensureForeground(it, preparing = snapshot.isPreparing) }
        SuperLyricPublisher.stop()
        if (gatewaySettings.exclusiveAudio && abandonExclusiveFocus) abandonAudioFocus()
        stopToneForegroundIfIdle()
    }

    private fun seekTo(positionMillis: Long) {
        cancelPcmTestTone(restoreMusic = false)
        val snapshot = LazerPlaybackStateStore.snapshot.value
        val track = snapshot.track
        if (track != null) {
            val currentPlayer = player
            val upperBound = runCatching { currentPlayer?.duration }.getOrNull()
                ?.takeIf { it > 0L }
                ?: snapshot.durationMillis.takeIf { it > 0L }
                ?: positionMillis
            val bounded = positionMillis.coerceIn(0L, upperBound)
            runCatching { currentPlayer?.seekTo(bounded) }
            publishCurrentState(
                isPreparing = snapshot.isPreparing,
                isPlaying = runCatching { currentPlayer?.isPlaying }.getOrNull() ?: snapshot.isPlaying,
                positionOverride = bounded,
            )
        }
        stopToneForegroundIfIdle()
    }

    private fun playNext() {
        cancelPcmTestTone(restoreMusic = false)
        LazerPlaybackQueue.next()?.let { resolveAndPlay(it) } ?: stopToneForegroundIfIdle()
    }

    /**
     * Only a track that ran to its end obeys the mode. The next button always advances, so single
     * loop never traps a listener who is asking to move on.
     */
    private fun playOnCompletion() {
        if (LazerPlaybackQueue.mode == LazerPlayMode.SingleLoop) replayCurrent() else playNext()
    }

    /** Restarting the prepared player skips a second stream lookup for the same track. */
    private fun replayCurrent() {
        val currentPlayer = player
        val restarted = currentPlayer?.let {
            runCatching {
                it.seekTo(0)
                it.play()
            }.isSuccess
        } ?: false
        if (!restarted) {
            playNext()
            return
        }
        requestedPlayWhenReady = true
        publishCurrentState(isPreparing = false, isPlaying = true, positionOverride = 0L)
        handler.removeCallbacks(progressReporter)
        handler.post(progressReporter)
    }

    private fun playPrevious() {
        cancelPcmTestTone(restoreMusic = false)
        LazerPlaybackQueue.previous()?.let { resolveAndPlay(it) } ?: stopToneForegroundIfIdle()
    }

    private fun stopPlayback(clearSession: Boolean = false) {
        cancelPcmTestTone(restoreMusic = false)
        persistCurrentPosition(force = true)
        requestedPlayWhenReady = false
        ++loadingGeneration
        releasePreparationWakeLock()
        ++artworkGeneration
        releasePlayer()
        artworkTrackId = null
        artworkBitmap = null
        streamUrls.clear()
        streamUrlPrefetches.clear()
        abandonAudioFocus()
        SuperLyricPublisher.stop()
        if (clearSession) gateway.clearSession()
        LazerPlaybackStateStore.update(LazerPlaybackSnapshot())
        mediaSession?.setPlaybackState(
            PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0L, 0f).build(),
        )
        // Published outside the throttled path, so the cache must not remember the old state.
        sessionStateValue = null
        sessionStatePositionMillis = -1L
        stopForeground(STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun publishCurrentState(
        isPreparing: Boolean,
        isPlaying: Boolean,
        positionOverride: Long? = null,
    ) {
        val previous = LazerPlaybackStateStore.snapshot.value
        val track = previous.track ?: return
        val currentPlayer = player
        val duration = runCatching { currentPlayer?.duration }.getOrNull()
            ?.takeIf { it > 0L }
            ?: track.durationMillis
        val position = positionOverride ?: (runCatching { currentPlayer?.currentPosition }.getOrNull()
            ?: previous.positionMillis)
        LazerPlaybackStateStore.update(
            previous.copy(
                track = track,
                isPreparing = isPreparing,
                isPlaying = isPlaying,
                positionMillis = position.coerceAtLeast(0L),
                durationMillis = duration,
                message = null,
            ),
        )
        persistPosition(track.id, position, force = !isPlaying || positionOverride != null)
        updateSession(track, isPlaying, position)
        if (isPlaying) SuperLyricPublisher.onPosition(track, position.coerceAtLeast(0L))
    }

    private fun persistCurrentPosition(force: Boolean) {
        val state = LazerPlaybackStateStore.snapshot.value
        val track = state.track ?: return
        val position = runCatching { player?.currentPosition }.getOrNull() ?: state.positionMillis
        persistPosition(track.id, position, force)
    }

    private fun persistPosition(trackId: Long, positionMillis: Long, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastPositionCheckpointAtMillis < POSITION_CHECKPOINT_INTERVAL_MILLIS) return
        playbackQueueStore.savePosition(trackId, positionMillis, commit = force)
        lastPositionCheckpointAtMillis = now
    }

    private fun publishError(message: String) {
        requestedPlayWhenReady = false
        ++loadingGeneration
        releasePreparationWakeLock()
        releasePlayer()
        abandonAudioFocus()
        val previous = LazerPlaybackStateStore.snapshot.value
        LazerPlaybackStateStore.update(previous.copy(isPreparing = false, isPlaying = false, message = message))
        previous.track?.let {
            persistPosition(it.id, previous.positionMillis, force = true)
            updateSession(it, isPlaying = false, positionMillis = previous.positionMillis)
            ensureForeground(it, preparing = false)
        }
    }

    private fun updateSession(
        track: LazerTrack,
        isPlaying: Boolean,
        positionMillis: Long,
        isPreparing: Boolean = false,
    ) {
        if (!gatewaySettings.playbackInterface.usesSystemMediaControls()) return
        val session = ensureMediaSession()
        publishSessionMetadata(session, track)
        publishSessionState(session, isPlaying, positionMillis, isPreparing)
    }

    /**
     * Metadata only moves with the track, and again when its artwork arrives some ticks later.
     * Rebuilding it on every progress tick parcelled the album bitmap across binder about 31 times
     * a second, which is the kind of work that heats a phone without ever dropping a frame.
     */
    private fun publishSessionMetadata(session: MediaSession, track: LazerTrack) {
        val hasArtwork = artworkBitmap != null && artworkTrackId == track.id
        val key = "${track.id}|${if (hasArtwork) "art" else "plain"}"
        if (key == sessionMetadataKey) return
        sessionMetadataKey = key

        val metadata = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, track.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
            .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, track.artist)
            .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
            .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, track.coverUrl)
            .putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMillis)
        if (hasArtwork) {
            val bitmap = artworkBitmap
            if (bitmap != null) {
                metadata.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, bitmap)
                metadata.putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap)
            }
        }
        session.setMetadata(metadata.build())
    }

    /** Controllers poll position far more slowly than the UI needs it; 250 ms keeps the lock
     * screen smooth without a binder call every 32 ms. State changes always go through. */
    private fun publishSessionState(
        session: MediaSession,
        isPlaying: Boolean,
        positionMillis: Long,
        isPreparing: Boolean,
    ) {
        val state = when {
            isPreparing -> PlaybackState.STATE_BUFFERING
            isPlaying -> PlaybackState.STATE_PLAYING
            else -> PlaybackState.STATE_PAUSED
        }
        val position = positionMillis.coerceAtLeast(0L)
        val moved = kotlin.math.abs(position - sessionStatePositionMillis)
        if (state == sessionStateValue && moved < SESSION_STATE_MIN_STEP_MILLIS) return
        sessionStateValue = state
        sessionStatePositionMillis = position

        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_STOP,
                )
                .setState(state, position, if (isPlaying) 1f else 0f)
                .build(),
        )
    }

    private fun requestArtwork(track: LazerTrack) {
        if (artworkTrackId == track.id && artworkBitmap != null) return
        val url = notificationArtworkUrl(track.coverUrl) ?: return
        val generation = ++artworkGeneration
        artworkTrackId = track.id
        artworkBitmap = null
        scope.launch(Dispatchers.IO) {
            val bitmap = runCatching { downloadArtwork(url) }
                .onFailure { error -> Log.w(TAG, "Artwork failed for ${track.id}", error) }
                .getOrNull()
            withContext(Dispatchers.Main.immediate) {
                val snapshot = LazerPlaybackStateStore.snapshot.value
                if (generation != artworkGeneration || snapshot.track?.id != track.id || bitmap == null) {
                    return@withContext
                }
                artworkBitmap = bitmap
                updateSession(
                    track = track,
                    isPlaying = snapshot.isPlaying,
                    positionMillis = snapshot.positionMillis,
                    isPreparing = snapshot.isPreparing,
                )
                ensureForeground(track, preparing = snapshot.isPreparing)
            }
        }
    }

    private fun downloadArtwork(url: String): Bitmap? {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
            connection.readTimeout = NETWORK_TIMEOUT_MILLIS
            connection.instanceFollowRedirects = true
            mediaRequestHeaders.forEach(connection::setRequestProperty)
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val decoded = connection.inputStream.use { input -> BitmapFactory.decodeStream(input) }
                ?: return null
            decoded.fitInsideNotificationArtwork()
        } finally {
            connection.disconnect()
        }
    }

    private fun ensureForeground(track: LazerTrack, preparing: Boolean) {
        val notification = notification(track, preparing)
        if (!foregroundStarted) {
            startForeground(NOTIFICATION_ID, notification)
            foregroundStarted = true
        } else {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notification)
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            tr("notification.playing"),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = tr("notification.controls")
            setShowBadge(false)
        }
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
    }

    private fun notification(track: LazerTrack, preparing: Boolean): Notification {
        val isPlaying = LazerPlaybackStateStore.snapshot.value.isPlaying
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
            .setContentTitle(track.title)
            .setContentText(if (preparing) tr("notification.preparing") else track.artist)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying || preparing)
            .setContentIntent(contentIntent())
        if (gatewaySettings.playbackInterface.usesSystemMediaControls()) {
            val playIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
            val playLabel = if (isPlaying) tr("player.pause") else tr("player.play")
            builder
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .addAction(notificationAction(android.R.drawable.ic_media_previous, tr("player.previous"), ACTION_PREVIOUS))
                .addAction(notificationAction(playIcon, playLabel, ACTION_TOGGLE))
                .addAction(notificationAction(android.R.drawable.ic_media_next, tr("player.next"), ACTION_NEXT))
                .setStyle(
                    Notification.MediaStyle()
                        .setMediaSession(ensureMediaSession().sessionToken)
                        .setShowActionsInCompactView(0, 1, 2),
                )
        } else {
            builder
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
        }
        artworkBitmap.takeIf { artworkTrackId == track.id }?.let(builder::setLargeIcon)
        return builder.build()
    }

    private fun notificationAction(icon: Int, label: String, action: String): Notification.Action =
        Notification.Action.Builder(icon, label, actionIntent(action)).build()

    private fun actionIntent(action: String): PendingIntent = PendingIntent.getService(
        this,
        action.hashCode(),
        Intent(this, AndroidPlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun requestAudioFocus(): Boolean {
        if (!gatewaySettings.playbackInterface.usesSystemMediaControls()) {
            abandonAudioFocus()
            return true
        }
        abandonAudioFocus()
        val focusGain = androidAudioFocusGain(gatewaySettings.exclusiveAudio)
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(focusGain)
                .setAudioAttributes(playbackAudioAttributes())
                .setOnAudioFocusChangeListener(this, handler)
                .setWillPauseWhenDucked(true)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, focusGain)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let(audioManager::abandonAudioFocusRequest)
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(this)
        }
    }

    private fun refreshAudioFocusMode() {
        cancelPcmTestTone(restoreMusic = false)
        if (!gatewaySettings.playbackInterface.usesSystemMediaControls()) {
            abandonAudioFocus()
            return
        }
        val isPlaying = requestedPlayWhenReady
        abandonAudioFocus()
        if (isPlaying && !requestAudioFocus()) publishError(audioFocusFailureMessage())
    }

    private fun refreshPlaybackInterface() {
        cancelPcmTestTone(restoreMusic = false)
        val snapshot = LazerPlaybackStateStore.snapshot.value
        if (!gatewaySettings.playbackInterface.usesSystemMediaControls()) {
            abandonAudioFocus()
            wasPlayingBeforeFocusLoss = false
            releaseMediaSession()
        } else {
            ensureMediaSession()
            snapshot.track?.let { track ->
                updateSession(
                    track = track,
                    isPlaying = snapshot.isPlaying,
                    positionMillis = snapshot.positionMillis,
                    isPreparing = snapshot.isPreparing,
                )
            }
            if (requestedPlayWhenReady && !requestAudioFocus()) {
                requestedPlayWhenReady = false
                runCatching { player?.pause() }
                publishCurrentState(isPreparing = false, isPlaying = false)
                SuperLyricPublisher.stop()
                LazerPlaybackStateStore.update(
                    LazerPlaybackStateStore.snapshot.value.copy(message = audioFocusFailureMessage()),
                )
            }
        }
        LazerPlaybackStateStore.snapshot.value.let { current ->
            current.track?.let { ensureForeground(it, preparing = current.isPreparing) }
        }
    }

    private fun ensureMediaSession(): MediaSession {
        mediaSession?.let { return it }
        return MediaSession(this, "Lazer playback").apply {
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = resumeCurrent()
                    override fun onPause() = pauseCurrent()
                    override fun onSkipToNext() = playNext()
                    override fun onSkipToPrevious() = playPrevious()
                    override fun onSeekTo(position: Long) = seekTo(position)
                    override fun onStop() = stopPlayback()
                },
            )
            setSessionActivity(contentIntent())
            isActive = true
        }.also { mediaSession = it }
    }

    private fun releaseMediaSession() {
        mediaSession?.let { session ->
            session.isActive = false
            session.release()
        }
        mediaSession = null
        // A replacement session starts empty, so nothing may be assumed already published.
        sessionMetadataKey = null
        sessionStateValue = null
        sessionStatePositionMillis = -1L
    }

    private fun playbackAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private fun handleDirectOutputModeChanged(enabled: Boolean, deviceId: String?) {
        val selectedDeviceId = deviceId?.takeIf(String::isNotBlank) ?: return
        if (enabled) {
            // Permission callbacks and device changes are asynchronous; ignore stale intents.
            if (AndroidPlaybackConnection.currentUsbUacDirectOutputDeviceId() != selectedDeviceId) return
            if (directUacDeviceId == selectedDeviceId) return
            directUacDeviceId = selectedDeviceId
        } else {
            if (directUacDeviceId != selectedDeviceId) return
            directUacDeviceId = null
        }
        restartCurrentTrackForOutputModeChange()
    }

    private fun restartCurrentTrackForOutputModeChange() {
        val current = LazerPlaybackStateStore.snapshot.value
        val track = current.track ?: return
        val positionMillis = player?.let { runCatching { it.currentPosition }.getOrNull() }
            ?.takeIf { it >= 0L } ?: current.positionMillis
        resolveAndPlay(track, autoplay = requestedPlayWhenReady, startPositionMillis = positionMillis)
    }

    private fun handleDirectUsbDeviceDetached(deviceId: String) {
        directOutputProvider?.onDeviceDetached(deviceId)
        if (directUacDeviceId != deviceId) return
        AndroidPlaybackConnection.clearUsbUacDirectOutputIfSelected(deviceId)
        directUacDeviceId = null
        val track = LazerPlaybackStateStore.snapshot.value.track
        requestedPlayWhenReady = false
        releasePlayer()
        if (track != null) {
            LazerPlaybackStateStore.update { state ->
                if (state.track?.id != track.id) state else state.copy(
                    isPreparing = false,
                    isPlaying = false,
                    message = tr("settings.hifi.usb_uac.direct_output.device_changed"),
                )
            }
        }
        LazerUsbUacDirectOutputStateStore.publish(
            LazerUsbUacDirectOutputSnapshot(
                deviceId = deviceId,
                status = LazerUsbUacDirectOutputStatus.Failed,
                detail = "settings.hifi.usb_uac.direct_output.device_changed",
            ),
        )
    }

    private fun reportDirectOutputFailure(deviceId: String, error: Throwable) {
        AndroidPlaybackConnection.clearUsbUacDirectOutputIfSelected(deviceId)
        if (directUacDeviceId == deviceId) directUacDeviceId = null
        val status = LazerUsbUacDirectOutputStateStore.state.value
        if (status.deviceId == deviceId) {
            LazerUsbUacDirectOutputStateStore.publish(
                status.copy(
                    enabled = false,
                    status = LazerUsbUacDirectOutputStatus.Failed,
                    detail = "settings.hifi.usb_uac.direct_output.enable_failed",
                ),
            )
        }
        val message = error.message?.takeIf(String::isNotBlank)
            ?: tr("settings.hifi.usb_uac.direct_output.failed")
        LazerPlaybackStateStore.update { state ->
            if (state.track == null) state else state.copy(
                isPreparing = false,
                isPlaying = false,
                message = message,
            )
        }
    }

    private fun audioFocusFailureMessage(): String = if (gatewaySettings.exclusiveAudio) {
        tr("status.exclusive_fail")
    } else {
        tr("status.audio_fail")
    }

    private fun releasePlayer() {
        handler.removeCallbacks(progressReporter)
        releaseAudioLevelCapture()
        player?.let { currentPlayer ->
            runCatching { currentPlayer.release() }
        }
        player = null
        preparedGeneration = null
        completedGeneration = null
        outputProvider?.let { provider -> runCatching { provider.close() } }
        outputProvider = null
        directOutputProvider?.let { provider -> runCatching { provider.close() } }
        directOutputProvider = null
    }

    private fun activeReplayGainResolution(): LazerReplayGainResolution {
        val track = LazerPlaybackStateStore.snapshot.value.track
        return if (track?.source is LazerTrackSource.LocalFile) {
            resolveLazerReplayGain(track.replayGain, replayGainMode)
        } else {
            LazerReplayGainResolution(0.0, LazerReplayGainSource.None)
        }
    }

    /**
     * Captures the spectrum of our own playback for the playlist indicator. Optional, and gated on
     * the setting plus the microphone permission the platform demands for [Visualizer] even though
     * it only reads back this app's session.
     */
    private fun refreshAudioLevelCapture() {
        releaseAudioLevelCapture()
        if (!gatewaySettings.audioReactiveLevels) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        val currentPlayer = player ?: return
        // Session 0 is the global output mix rather than this player, and the platform refuses it
        // without MODIFY_AUDIO_SETTINGS anyway.
        val sessionId = currentPlayer.audioSessionId
        if (sessionId <= 0) return
        val captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(AUDIO_LEVEL_CAPTURE_SIZE)
        audioLevelAnalyzer = AudioLevelAnalyzer(outputSampleRateHz(), captureSize)
        audioVisualizer = runCatching {
            Visualizer(sessionId).apply {
                setCaptureSize(captureSize)
                setDataCaptureListener(audioLevelListener, AUDIO_LEVEL_CAPTURE_RATE_MILLIHERTZ, true, true)
                enabled = true
            }
        }.onFailure { error ->
            if (!audioLevelCaptureUnavailable) {
                audioLevelCaptureUnavailable = true
                Log.w(TAG, "Audio level capture is unavailable on this device", error)
            }
        }.getOrNull()
        audioLevelSessionId = sessionId
    }

    /** Re-points the capture if the session turns out to differ once playback is actually ready. */
    private fun retargetAudioLevelCapture(sessionId: Int) {
        if (audioVisualizer != null && audioLevelSessionId == sessionId) return
        refreshAudioLevelCapture()
    }

    private fun releaseAudioLevelCapture() {
        audioVisualizer?.let { visualizer ->
            runCatching { visualizer.enabled = false }
            runCatching { visualizer.release() }
        }
        audioVisualizer = null
        audioLevelAnalyzer = null
        audioLevelSessionId = 0
        latestWaveform = null
        LazerAudioLevels.clear()
    }

    private fun outputSampleRateHz(): Int =
        audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull()
            ?: FALLBACK_OUTPUT_SAMPLE_RATE_HZ

    /**
     * A foreground service alone does not keep the CPU awake while a new stream is being resolved
     * and prepared. Keep a short, bounded wake lock across that hand-off so background next/skip
     * cannot suspend the process before Media3 has buffered enough to begin playback.
     */
    private fun acquirePreparationWakeLock(generation: Long) {
        preparingGeneration = generation
        val wakeLock = preparationWakeLock ?: (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:track-preparation")
            .also { preparationWakeLock = it }
        // A fresh song switch gets its own bounded window even when the prior switch was still
        // preparing. This avoids an old timeout ending a newer switch early.
        if (wakeLock.isHeld) wakeLock.release()
        wakeLock.acquire(PREPARATION_WAKE_LOCK_TIMEOUT_MILLIS)
    }

    private fun releasePreparationWakeLock(generation: Long? = null) {
        if (generation != null && preparingGeneration != generation) return
        preparingGeneration = null
        preparationWakeLock?.let { wakeLock ->
            if (wakeLock.isHeld) runCatching { wakeLock.release() }
        }
    }

    override fun onDestroy() {
        cancelPcmTestTone(restoreMusic = false)
        persistCurrentPosition(force = true)
        audioManager.unregisterAudioDeviceCallback(outputDeviceCallback)
        runCatching { unregisterReceiver(usbDetachReceiver) }
        ++artworkGeneration
        releasePreparationWakeLock()
        artworkTrackId = null
        artworkBitmap = null
        releasePlayer()
        abandonAudioFocus()
        releaseMediaSession()
        SuperLyricPublisher.release()
        scope.cancel()
        gateway.close()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY_TRACK = "dev.naominet.lazer.action.PLAY_TRACK"
        const val ACTION_TOGGLE = "dev.naominet.lazer.action.TOGGLE"
        const val ACTION_RESUME = "dev.naominet.lazer.action.RESUME"
        const val ACTION_PAUSE = "dev.naominet.lazer.action.PAUSE"
        const val ACTION_NEXT = "dev.naominet.lazer.action.NEXT"
        const val ACTION_PREVIOUS = "dev.naominet.lazer.action.PREVIOUS"
        const val ACTION_SEEK = "dev.naominet.lazer.action.SEEK"
        const val ACTION_EXCLUSIVE_AUDIO_CHANGED = "dev.naominet.lazer.action.EXCLUSIVE_AUDIO_CHANGED"
        const val ACTION_PLAYBACK_INTERFACE_CHANGED = "dev.naominet.lazer.action.PLAYBACK_INTERFACE_CHANGED"
        const val ACTION_USB_AUDIO_TARGET_CHANGED = "dev.naominet.lazer.action.USB_AUDIO_TARGET_CHANGED"
        const val ACTION_USB_UAC_DIRECT_OUTPUT_CHANGED = "dev.naominet.lazer.action.USB_UAC_DIRECT_OUTPUT_CHANGED"
        const val ACTION_AUDIO_LEVELS_CHANGED = "dev.naominet.lazer.action.AUDIO_LEVELS_CHANGED"
        const val ACTION_EQUALIZER_CHANGED = "dev.naominet.lazer.action.EQUALIZER_CHANGED"
        const val ACTION_REPLAY_GAIN_CHANGED = "dev.naominet.lazer.action.REPLAY_GAIN_CHANGED"
        const val ACTION_PCM_TEST_TONE = "dev.naominet.lazer.action.PCM_TEST_TONE"
        const val ACTION_PCM_TEST_TONE_STOP = "dev.naominet.lazer.action.PCM_TEST_TONE_STOP"
        const val ACTION_STOP = "dev.naominet.lazer.action.STOP"
        const val ACTION_STOP_AND_CLEAR_SESSION = "dev.naominet.lazer.action.STOP_AND_CLEAR_SESSION"
        const val EXTRA_POSITION = "position_millis"
        const val EXTRA_AUTOPLAY = "autoplay"
        const val EXTRA_PCM_SAMPLE_RATE = "pcm_sample_rate_hz"
        const val EXTRA_PCM_BIT_DEPTH = "pcm_bit_depth"
        const val EXTRA_EQUALIZER = "equalizer_state"
        const val EXTRA_REPLAY_GAIN_MODE = "replay_gain_mode"
        const val EXTRA_USB_TARGET_IDENTITY = "usb_target_identity"
        const val EXTRA_USB_TARGET_USER_CHANGE = "usb_target_user_change"
        const val EXTRA_USB_UAC_DIRECT_ENABLED = "usb_uac_direct_enabled"
        const val EXTRA_USB_UAC_DIRECT_DEVICE_ID = "usb_uac_direct_device_id"

        private const val CHANNEL_ID = "lazer.playback"
        private const val NOTIFICATION_ID = 2036
        private const val PROGRESS_UPDATE_MILLIS = 32L
        private const val POSITION_CHECKPOINT_INTERVAL_MILLIS = 2_000L
        private const val SESSION_STATE_MIN_STEP_MILLIS = 250L
        private const val NETWORK_TIMEOUT_MILLIS = 10_000
        private const val PREPARATION_WAKE_LOCK_TIMEOUT_MILLIS = 45_000L
        private const val STREAM_URL_CACHE_SIZE = 6
        private const val STREAM_URL_CACHE_TTL_MILLIS = 4 * 60_000L
        private const val FALLBACK_OUTPUT_SAMPLE_RATE_HZ = 44_100
        private const val TAG = "LazerPlayback"
    }
}

private data class PcmTestToneResumeState(
    val trackId: Long?,
    val wasPlaying: Boolean,
    val playbackGeneration: Long,
)

private data class AndroidStreamCacheKey(
    val trackId: Long,
    val quality: AudioQuality,
)

internal data class AndroidResolvedAudioStream(
    val url: String,
    val source: PlaybackSourceSnapshot,
    val expiresAtMillis: Long,
)

internal sealed interface AndroidAudioPlaybackSource {
    data class GatewaySong(val trackId: Long) : AndroidAudioPlaybackSource
    data class LocalUri(val uri: String) : AndroidAudioPlaybackSource
}

internal fun androidAudioPlaybackSource(track: LazerTrack): AndroidAudioPlaybackSource =
    when (val source = track.source) {
        LazerTrackSource.GatewaySong -> AndroidAudioPlaybackSource.GatewaySong(track.id)
        is LazerTrackSource.LocalFile -> AndroidAudioPlaybackSource.LocalUri(source.uri)
    }

internal fun androidAudioFocusGain(exclusiveAudio: Boolean): Int = if (exclusiveAudio) {
    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE
} else {
    AudioManager.AUDIOFOCUS_GAIN
}

internal fun AndroidPlaybackInterface.usesSystemMediaControls(): Boolean =
    this == AndroidPlaybackInterface.SYSTEM_MEDIA

internal fun androidAudioQualityAttempts(preferred: AudioQuality): List<Pair<AudioQuality, Boolean>> {
    val preferredIndex = ANDROID_AUDIO_QUALITY_OPTIONS.indexOf(preferred)
        .takeIf { it >= 0 }
        ?: ANDROID_AUDIO_QUALITY_OPTIONS.indexOf(AudioQuality.EXHIGH)
    val normalAttempts = ANDROID_AUDIO_QUALITY_OPTIONS
        .subList(0, preferredIndex + 1)
        .asReversed()
        .map { it to false }
    return normalAttempts + (preferred to true)
}

internal fun androidResolvedAudioStream(
    songUrl: SongUrl?,
    requestedQuality: AudioQuality,
    resolvedQualityAttempt: AudioQuality,
    usedUnblockFallback: Boolean,
    expiresAtMillis: Long,
): AndroidResolvedAudioStream? {
    val url = normalizedPlaybackUrl(songUrl?.url) ?: return null
    val metadata = songUrl ?: return null
    return AndroidResolvedAudioStream(
        url = url,
        source = PlaybackSourceSnapshot(
            status = PlaybackSourceStatus.Resolved,
            requestedQuality = requestedQuality,
            resolvedQualityAttempt = resolvedQualityAttempt,
            usedUnblockFallback = usedUnblockFallback,
            reportedType = metadata.type?.takeIf(String::isNotBlank),
            reportedBitrate = metadata.br?.takeIf { it > 0 },
            reportedSizeBytes = metadata.size?.takeIf { it > 0L },
        ),
        expiresAtMillis = expiresAtMillis,
    )
}

internal fun androidSelectSongUrl(streams: List<SongUrl>, trackId: Long): SongUrl? =
    streams.firstOrNull { it.id == trackId }
        ?: streams.singleOrNull()?.takeIf { it.id == 0L }

internal fun normalizedPlaybackUrl(raw: String?): String? {
    val value = raw?.trim()?.takeIf(String::isNotBlank) ?: return null
    return when {
        value.startsWith("//") -> "https:$value"
        value.startsWith("http://", ignoreCase = true) -> "https://${value.substringAfter("://")}"
        value.startsWith("https://", ignoreCase = true) -> value
        else -> null
    }
}

private fun notificationArtworkUrl(raw: String?): String? = normalizedArtworkUrl(raw)?.let { url ->
    if (url.contains("music.126.net") && !url.contains("param=")) {
        "$url${if (url.contains('?')) '&' else '?'}param=320y320"
    } else {
        url
    }
}

private fun Bitmap.fitInsideNotificationArtwork(maxSide: Int = 320): Bitmap {
    val longestSide = maxOf(width, height)
    if (longestSide <= maxSide) return this
    val scale = maxSide.toFloat() / longestSide.toFloat()
    val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
    val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(this, targetWidth, targetHeight, true).also { scaled ->
        if (scaled !== this) recycle()
    }
}
