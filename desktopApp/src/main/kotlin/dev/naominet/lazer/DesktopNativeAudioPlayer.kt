package dev.naominet.lazer

import com.sun.jna.Pointer
import com.sun.jna.WString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.roundToLong

/** Source metadata and the format accepted by an initialized native output session. */
data class LazerHiFiStreamInfo(
    val codec: String,
    val sourceSampleRate: Int,
    val sourceChannels: Int,
    val sourceBitsPerSample: Int,
    val sourceFormatKind: Int = LAZER_AUDIO_SOURCE_FORMAT_PCM,
    val sourceDsdRateMultiplier: Int = 0,
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val exclusive: Boolean,
    val lossless: Boolean,
    val bitPerfectActive: Boolean,
    val signalPath: SignalPathSnapshot,
    val outputContainerBitsPerSample: Int = 0,
    val outputIsFloat: Boolean = false,
    val outputFormatInitialized: Boolean = false,
    val formatSelection: OutputFormatSelection = OutputFormatSelection.Unknown,
    val outputTelemetry: OutputTelemetrySnapshot? = null,
    val outputFormatKind: Int = LAZER_AUDIO_OUTPUT_FORMAT_PCM,
    val outputDsdRateMultiplier: Int = 0,
    /** HAL virtual ASBD's non-mixable flag; null when this backend does not report it. */
    val outputNonMixable: Boolean? = null,
    /** True when the most recently submitted output block contained source-starvation padding. */
    val underrunActive: Boolean = false,
    /** Cumulative device-output frames padded because the source had not reached EOF. */
    val underrunFrames: Long = 0L,
)

internal fun LazerAudioStreamInfo.coreAudioNonMixableOrNull(): Boolean? =
    if (outputCoreAudioMixabilityKnown != 0) outputCoreAudioNonMixable != 0 else null

internal val LazerHiFiStreamInfo.estimatedUnderrunMillis: Long?
    get() = sampleRate.takeIf { it > 0 }?.let {
        (underrunFrames.coerceAtLeast(0L).toDouble() * 1_000.0 / it).roundToLong()
    }

internal enum class DesktopHiFiTestToneStatus {
    Idle,
    Preparing,
    Playing,
    Completed,
    Failed,
}

internal fun Int.toOutputFormatSelection(): OutputFormatSelection = when (this) {
    LAZER_AUDIO_FORMAT_SELECTION_SHARED_MIX -> OutputFormatSelection.SharedMix
    LAZER_AUDIO_FORMAT_SELECTION_EXCLUSIVE_SOURCE -> OutputFormatSelection.ExclusiveSource
    LAZER_AUDIO_FORMAT_SELECTION_EXCLUSIVE_SAME_RATE_ALTERNATE ->
        OutputFormatSelection.ExclusiveSameRateAlternate
    LAZER_AUDIO_FORMAT_SELECTION_EXCLUSIVE_MONO_TO_STEREO -> OutputFormatSelection.ExclusiveMonoToStereo
    LAZER_AUDIO_FORMAT_SELECTION_EXCLUSIVE_MIX_FALLBACK -> OutputFormatSelection.ExclusiveMixFallback
    LAZER_AUDIO_FORMAT_SELECTION_EXCLUSIVE_COMMON_RATE_FALLBACK ->
        OutputFormatSelection.ExclusiveCommonRateFallback
    LAZER_AUDIO_FORMAT_SELECTION_DOP_CARRIER -> OutputFormatSelection.DoPCarrier
    LAZER_AUDIO_FORMAT_SELECTION_NATIVE_DSD_U8 -> OutputFormatSelection.NativeDsdU8
    LAZER_AUDIO_FORMAT_SELECTION_NATIVE_DSD_U16_LE -> OutputFormatSelection.NativeDsdU16Le
    LAZER_AUDIO_FORMAT_SELECTION_NATIVE_DSD_U16_BE -> OutputFormatSelection.NativeDsdU16Be
    LAZER_AUDIO_FORMAT_SELECTION_NATIVE_DSD_U32_LE -> OutputFormatSelection.NativeDsdU32Le
    LAZER_AUDIO_FORMAT_SELECTION_NATIVE_DSD_U32_BE -> OutputFormatSelection.NativeDsdU32Be
    else -> OutputFormatSelection.Unknown
}

internal val LazerHiFiStreamInfo.hasDsdSource: Boolean
    get() = sourceFormatKind == LAZER_AUDIO_SOURCE_FORMAT_DSD || sourceDsdRateMultiplier > 0

internal val LazerHiFiStreamInfo.isDoPOutput: Boolean
    get() = outputFormatKind == LAZER_AUDIO_OUTPUT_FORMAT_DOP

internal val LazerHiFiStreamInfo.isNativeDsdOutput: Boolean
    get() = outputFormatKind == LAZER_AUDIO_OUTPUT_FORMAT_NATIVE_DSD

internal val LazerHiFiStreamInfo.nativeDsdAlsaFormat: String?
    get() = when (formatSelection) {
        OutputFormatSelection.NativeDsdU8 -> "DSD_U8"
        OutputFormatSelection.NativeDsdU16Le -> "DSD_U16_LE"
        OutputFormatSelection.NativeDsdU16Be -> "DSD_U16_BE"
        OutputFormatSelection.NativeDsdU32Le -> "DSD_U32_LE"
        OutputFormatSelection.NativeDsdU32Be -> "DSD_U32_BE"
        else -> null
    }

private fun nativeAudioBackendLabel(osName: String = System.getProperty("os.name").orEmpty()): String =
    when (resolveDesktopAudioOutputBackend(osName)) {
        DesktopAudioOutputBackend.Wasapi -> "WASAPI"
        DesktopAudioOutputBackend.Alsa -> "ALSA"
        DesktopAudioOutputBackend.CoreAudio -> "CoreAudio HAL"
        DesktopAudioOutputBackend.Unsupported -> "native audio"
    }

internal fun LazerAudioStreamInfo.toOutputTelemetrySnapshot(): OutputTelemetrySnapshot? {
    if (outputTelemetryValid == 0 || outputFormatInitialized == 0 || bitPerfectActive != 0 ||
        outputFormatKind == LAZER_AUDIO_OUTPUT_FORMAT_DOP ||
        outputFormatKind == LAZER_AUDIO_OUTPUT_FORMAT_NATIVE_DSD
    ) return null
    return OutputTelemetrySnapshot(
        samplePeakDbfs = outputPeakMilliDbfs / 1_000.0,
        limiterGainReductionDb = limiterGainReductionMilliDb / 1_000.0,
        clippedIntegerSampleCount = if (outputIsFloat == 0) outputClippedSampleCount else null,
    )
}

/** Builds the exact format accepted by a successfully initialized native output session. */
private fun LazerHiFiStreamInfo.initializedSessionFormat(): AudioFormat? {
    if (!outputFormatInitialized || sampleRate <= 0) return null

    val channelLayout = when (channels) {
        1 -> AudioChannelLayout.Mono
        2 -> AudioChannelLayout.Stereo
        else -> return null
    }
    if (isNativeDsdOutput) {
        val rate = DsdRate.entries.firstOrNull { it.multiplier == outputDsdRateMultiplier } ?: return null
        return AudioFormat.Dsd(rate, channelLayout)
    }
    val containerBits = outputContainerBitsPerSample
    val validBits = bitsPerSample
    if (containerBits !in setOf(8, 16, 24, 32) || validBits !in 8..containerBits) return null
    if (outputIsFloat && (containerBits != 32 || validBits != 32)) return null

    return runCatching {
        AudioFormat.Pcm(
            sampleRateHz = sampleRate,
            validBitsPerSample = validBits,
            containerBitsPerSample = containerBits,
            byteOrder = AudioByteOrder.LittleEndian,
            channelLayout = channelLayout,
            isFloat = outputIsFloat,
        )
    }.getOrNull()
}

/** Maps session negotiation separately from the physical DAC format and digital verification. */
internal fun LazerHiFiStreamInfo.toSignalPathSnapshot(
    osName: String = System.getProperty("os.name").orEmpty(),
): SignalPathSnapshot {
    val sourceIsDsd = hasDsdSource
    val dsdLabel = sourceDsdRateMultiplier.takeIf { it > 0 }?.toString() ?: "?"
    val dsdRate = DsdRate.entries.firstOrNull { it.multiplier == sourceDsdRateMultiplier }
    val dsdFormat = dsdRate?.let { rate ->
        val layout = when (sourceChannels) {
            1 -> AudioChannelLayout.Mono
            2 -> AudioChannelLayout.Stereo
            else -> null
        }
        layout?.let { AudioFormat.Dsd(rate, it) }
    }
    val exactFormat = bitPerfectActive
    val doPOutput = isDoPOutput
    val nativeDsdOutput = isNativeDsdOutput
    val initializedFormat = initializedSessionFormat()
    val outputDsdRate = DsdRate.entries.firstOrNull { it.multiplier == outputDsdRateMultiplier }
    val negotiatedFormat: AudioFormat? = when {
        nativeDsdOutput -> {
            val layout = when (channels) {
                1 -> AudioChannelLayout.Mono
                2 -> AudioChannelLayout.Stereo
                else -> null
            }
            if (outputFormatInitialized && outputDsdRate != null) {
                layout?.let { AudioFormat.Dsd(outputDsdRate, it) }
            } else {
                null
            }
        }
        doPOutput && outputDsdRate != null -> {
            val layout = when (channels) {
                1 -> AudioChannelLayout.Mono
                2 -> AudioChannelLayout.Stereo
                else -> null
            }
            layout?.let { AudioFormat.DoP(outputDsdRate, sampleRate, it) }
        }
        else -> initializedFormat
    }
    val knownFormatChange = sourceIsDsd || sourceSampleRate != sampleRate || sourceChannels != channels ||
        (sourceBitsPerSample > 0 && sourceBitsPerSample != bitsPerSample)
    val outputBackend = resolveDesktopAudioOutputBackend(osName)
    val backend = when (outputBackend) {
        DesktopAudioOutputBackend.Wasapi ->
            if (exclusive) AudioBackend.WasapiExclusive else AudioBackend.WasapiShared
        DesktopAudioOutputBackend.Alsa -> AudioBackend.Alsa
        DesktopAudioOutputBackend.CoreAudio -> AudioBackend.CoreAudio
        DesktopAudioOutputBackend.Unsupported -> AudioBackend.Unknown
    }
    val backendLabel = when (outputBackend) {
        DesktopAudioOutputBackend.Wasapi -> "WASAPI"
        DesktopAudioOutputBackend.Alsa -> "ALSA hardware PCM"
        DesktopAudioOutputBackend.CoreAudio -> "CoreAudio HAL"
        DesktopAudioOutputBackend.Unsupported -> "native output"
    }
    val outputOwnership = when (outputBackend) {
        DesktopAudioOutputBackend.Wasapi -> if (exclusive) "WASAPI exclusive" else "WASAPI shared"
        DesktopAudioOutputBackend.Alsa -> "ALSA direct hardware PCM"
        DesktopAudioOutputBackend.CoreAudio -> if (exclusive) "CoreAudio Hog Mode" else "CoreAudio shared HAL"
        DesktopAudioOutputBackend.Unsupported -> "native output"
    }
    val sourceSummary = buildString {
        if (sourceIsDsd) {
            append("DSD").append(dsdLabel)
            append(" · ").append(sourceChannels).append(" ch")
        } else {
            append(codec).append(" · ").append(sourceSampleRate).append(" Hz · ")
            append(sourceChannels).append(" ch")
            if (sourceBitsPerSample > 0) append(" · ").append(sourceBitsPerSample).append(" bit")
        }
        if (lossless) append(" · lossless")
    }
    val outputFormatSummary = initializedFormat?.let { format ->
        when (format) {
            is AudioFormat.Dsd -> nativeDsdAlsaFormat ?: "Native DSD format unknown"
            is AudioFormat.Pcm -> when {
                format.isFloat -> "${format.containerBitsPerSample}-bit float"
                format.validBitsPerSample != format.containerBitsPerSample ->
                    "${format.validBitsPerSample}-bit integer in ${format.containerBitsPerSample}-bit container"
                else -> "${format.validBitsPerSample}-bit integer"
            }
            is AudioFormat.DoP -> "24-bit DoP carrier"
        }
    } ?: "$bitsPerSample bit · format unknown"
    val outputSummary = buildString {
        if (doPOutput) append("DoP over ")
        if (nativeDsdOutput) append("Native DSD · ")
        append(sampleRate).append(" Hz · ").append(channels).append(" ch · ")
        append(if (doPOutput) "24-bit carrier" else outputFormatSummary).append(" · ")
        append(outputOwnership)
    }
    return SignalPathSnapshot(
        stages = listOf(
            SignalPathStageSnapshot(
                SignalPathStage.Source,
                SignalPathStageStatus.Active,
                format = dsdFormat,
                detail = sourceSummary,
            ),
            SignalPathStageSnapshot(
                SignalPathStage.Decoder,
                SignalPathStageStatus.Active,
                detail = when {
                    doPOutput -> "Raw DSD bitstream preserved for DoP transport"
                    nativeDsdOutput -> "Raw DSD bitstream preserved for ALSA Native DSD output"
                    exactFormat -> "Lossless integer samples preserved"
                    sourceIsDsd -> "DSD bitstream decoded to PCM by the resampler"
                    else -> "Decoded PCM"
                },
            ),
            SignalPathStageSnapshot(
                SignalPathStage.Dsp,
                if (exactFormat || doPOutput || nativeDsdOutput) SignalPathStageStatus.Bypassed else SignalPathStageStatus.Unknown,
                detail = when {
                    doPOutput || nativeDsdOutput -> "EQ, limiter, software volume and PCM dither bypassed"
                    exactFormat -> "DSP and software volume bypassed"
                    else -> "DSP state is configured in the player"
                },
            ),
            SignalPathStageSnapshot(
                SignalPathStage.FormatConversion,
                when {
                    doPOutput -> SignalPathStageStatus.Converted
                    nativeDsdOutput -> SignalPathStageStatus.Converted
                    exactFormat -> SignalPathStageStatus.Bypassed
                    knownFormatChange -> SignalPathStageStatus.Converted
                    else -> SignalPathStageStatus.Unknown
                },
                detail = when {
                    doPOutput -> "Packed as 24-bit DoP; no PCM sample-rate conversion"
                    nativeDsdOutput -> "Packed as ${nativeDsdAlsaFormat ?: "ALSA Native DSD"}; no DSD-to-PCM conversion"
                    exactFormat -> "No resampling or bit-depth conversion"
                    sourceIsDsd -> "DSD$dsdLabel converted to PCM at ${sampleRate} Hz"
                    knownFormatChange -> "Source and output format fields differ"
                    else -> "Conversion details not reported by the current ABI"
                },
            ),
            SignalPathStageSnapshot(
                SignalPathStage.OutputBackend,
                SignalPathStageStatus.Active,
                format = negotiatedFormat,
                detail = outputSummary,
            ),
            SignalPathStageSnapshot(SignalPathStage.Device, SignalPathStageStatus.Unknown),
        ),
        negotiation = OutputNegotiationSnapshot(
            status = if (negotiatedFormat != null) OutputNegotiationStatus.Accepted
                else OutputNegotiationStatus.Unknown,
            requestedFormat = null,
            negotiatedFormat = negotiatedFormat,
            backend = backend,
            formatSelection = formatSelection,
            detail = when {
                nativeDsdOutput && negotiatedFormat != null && outputBackend == DesktopAudioOutputBackend.Alsa ->
                    "ALSA configured the exact Native DSD format on a direct hardware PCM; DAC-side Native DSD recognition is not read back"
                nativeDsdOutput -> "Native DSD was requested, but an initialized output format is not reported"
                doPOutput && outputBackend == DesktopAudioOutputBackend.Wasapi ->
                    "WASAPI accepted the exact 24-bit DoP carrier session; DAC-side DoP recognition is not read back"
                doPOutput && outputBackend == DesktopAudioOutputBackend.Alsa ->
                    "ALSA configured the exact 24-bit DoP carrier on a direct hardware PCM; DAC-side DoP recognition is not read back"
                doPOutput -> "The native output session reports a DoP carrier; DAC-side DoP recognition is not read back"
                initializedFormat != null && outputBackend == DesktopAudioOutputBackend.Wasapi ->
                    "WASAPI Initialize accepted this session format; physical DAC format is not read back"
                initializedFormat != null && outputBackend == DesktopAudioOutputBackend.Alsa ->
                    "ALSA configured this direct hardware PCM session format; physical DAC format is not independently read back"
                initializedFormat != null && outputBackend == DesktopAudioOutputBackend.CoreAudio ->
                    "CoreAudio HAL read back this virtual output stream format; physical DAC output is not independently verified"
                initializedFormat != null -> "$backendLabel accepted this session format; physical DAC format is not read back"
                !outputFormatInitialized -> "$backendLabel initialized session format is not reported"
                else -> "$backendLabel session initialized, but its reported format cannot be represented as mono/stereo PCM"
            },
        ),
        directPath = if (nativeDsdOutput && negotiatedFormat != null) {
            DirectPathSnapshot(
                status = DirectPathStatus.Negotiated,
                reason = DirectPathReason.DigitalCaptureNotVerified,
                detail = "Native DSD ALSA session initialized; DAC recognition and digital loopback have not been verified",
            )
        } else if (nativeDsdOutput) {
            DirectPathSnapshot(
                status = DirectPathStatus.Unknown,
                reason = DirectPathReason.DeviceOrBackendUnknown,
                detail = "Native DSD was requested, but no initialized output format is reported",
            )
        } else if (doPOutput) {
            DirectPathSnapshot(
                status = DirectPathStatus.Negotiated,
                reason = DirectPathReason.DigitalCaptureNotVerified,
                detail = "DoP carrier initialized; DAC recognition and digital loopback have not been verified",
            )
        } else if (sourceIsDsd) {
            DirectPathSnapshot(
                status = DirectPathStatus.Rejected,
                reason = DirectPathReason.DsdConvertedToPcm,
                detail = "This desktop path converts DSD to PCM; direct DSD output is not active",
            )
        } else if (exactFormat) {
            DirectPathSnapshot(
                status = DirectPathStatus.Negotiated,
                reason = DirectPathReason.DigitalCaptureNotVerified,
                detail = "Exact source PCM format accepted; DAC digital loopback has not been tested",
            )
        } else {
            DirectPathSnapshot(status = DirectPathStatus.NotRequested, reason = null)
        },
        outputTelemetry = outputTelemetry,
    )
}

/**
 * Playback through the native `lazer-audio` engine: FFmpeg decoding, platform output, and the engine's
 * own resampler, limiter and EQ. It offers FLAC and other lossless codecs the Java Sound path never
 * decoded, plus bit-perfect exclusive output. The callback surface mirrors [DesktopAudioPlayer], so
 * the controller can swap engines without the rest of the app knowing which one is running.
 */
internal class DesktopNativeAudioPlayer(
    private val onProgress: (token: Long, progress: Float) -> Unit,
    private val onBuffered: (trackId: Long, progress: Float) -> Unit,
    private val onCompleted: (token: Long) -> Unit,
    private val onError: (token: Long, error: Throwable) -> Unit,
    private val onTrackChanged: (token: Long, playback: DesktopQueuedPlayback) -> Unit,
    private val onStreamInfo: (LazerHiFiStreamInfo?) -> Unit,
    initialExclusiveAudio: Boolean = false,
    initialEqualizer: LazerEqualizerState = LazerEqualizerState(),
    initialBufferMillis: Int = 120,
    initialBitPerfect: Boolean = false,
    initialDoPOutput: Boolean = false,
    initialNativeDsdOutput: Boolean = false,
    apiOverride: LazerAudioLibrary? = null,
) : DesktopAudioEngine {

    private val api = apiOverride ?: requireNotNull(LazerAudioLoader.library) { "lazer-audio 不可用" }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cache = DesktopAudioCache(onProgress = onBuffered)
    private val generation = AtomicLong(0L)
    private val queueGeneration = AtomicLong(0L)
    private val preparingSource = AtomicReference<Closeable?>(null)
    private val queueCallsLock = ReentrantLock()
    private val queueCallsDrained = queueCallsLock.newCondition()
    private val queueStateLock = Any()
    private var queueCallsInFlight = 0
    private val progressTarget = AtomicReference<PlaybackProgressTarget?>(null)

    @Volatile
    private var activeTrackId: Long? = null

    @Volatile
    private var exclusiveAudio = initialExclusiveAudio

    @Volatile
    private var outputEndpointId: String? = null

    @Volatile
    private var bitPerfect = initialBitPerfect && !initialDoPOutput && !initialNativeDsdOutput

    @Volatile
    private var doPOutput = initialDoPOutput

    @Volatile
    private var nativeDsdOutput = initialNativeDsdOutput && !initialDoPOutput

    @Volatile
    private var bufferMillis = initialBufferMillis

    @Volatile
    private var equalizer = initialEqualizer
    private var activeReplayGainDb = 0.0

    @Volatile
    private var volume = DEFAULT_DESKTOP_VOLUME

    @Volatile
    private var uiForeground = true

    @Volatile
    private var paused = false

    @Volatile
    private var engine: Pointer? = null

    @Volatile
    private var source: DesktopSeekableAudioSource? = null

    @Volatile
    private var reader: LazerAudioReader? = null

    @Volatile
    private var binding: NativeReaderBinding? = null

    @Volatile
    private var queuedSource: DesktopSeekableAudioSource? = null

    @Volatile
    private var queuedReader: LazerAudioReader? = null

    @Volatile
    private var queuedBinding: NativeReaderBinding? = null

    @Volatile
    private var queuedPlayback: DesktopQueuedPlayback? = null

    @Volatile
    private var queuedToken: Long = 0L

    @Volatile
    private var queuedSubmitted = false

    private var queueJob: Job? = null

    /** Temporary deterministic PCM source owned by the diagnostic playback path. */
    @Volatile
    private var temporarySourceFile: File? = null

    @Volatile
    private var testTerminalToken: Long = 0L

    @Volatile
    private var testTerminalCallback: ((Throwable?) -> Unit)? = null

    /** The event callback attributes by this token; zero means "ignore what arrives now". */
    private val activeToken = AtomicLong(0L)

    @Volatile
    private var closing = false

    private var pollJob: Job? = null

    // Both handlers stay referenced for the player's lifetime: the engine keeps the function pointers
    // it was created with, and a collected JNA callback would leave a dangling native pointer.
    private val eventHandler = object : LazerAudioEventCallback {
        override fun callback(context: Pointer?, event: Int, detail: Int, positionMillis: Long) {
            val token = activeToken.get()
            if (token == 0L || closing) return
            when (event) {
                LAZER_AUDIO_EVENT_ENDED -> {
                    handleTerminal(token, null)
                }
                LAZER_AUDIO_EVENT_DEVICE_LOST -> {
                    handleTerminal(token, desktopTerminalError(event, positionMillis, lastError()))
                }
                LAZER_AUDIO_EVENT_FAILED -> {
                    handleTerminal(token, desktopTerminalError(event, positionMillis, lastError()))
                }
                LAZER_AUDIO_EVENT_TRACK_CHANGED -> handleTrackChanged(token)
                else -> Unit
            }
        }
    }

    private val logHandler = object : LazerAudioLogCallback {
        override fun callback(context: Pointer?, level: Int, message: String?) {
            if (level >= 2 && !message.isNullOrBlank()) {
                PlaybackDebugLog.event("hifi-engine", message.take(200))
            }
        }
    }

    init {
        engine = createEngine()
    }

    @Synchronized
    override fun play(
        url: String,
        trackId: Long,
        cacheVariant: String,
        expectedBytes: Long?,
        durationMillis: Long,
        fromProgress: Float,
        volume: Float,
        playWhenReady: Boolean,
    ): Long {
        stopInternal()
        activeTrackId = trackId
        activeReplayGainDb = 0.0
        this.volume = volume.coerceIn(0f, 1f)
        paused = !playWhenReady
        val token = generation.incrementAndGet()
        val enginePtr = engine ?: createEngine().also { engine = it }
        if (enginePtr == null) {
            onError(token, IOException("无法创建原生音频引擎"))
            return token
        }

        val deviceConfig = createDeviceConfig()
        val configured = api.lazer_audio_set_device(enginePtr, deviceConfig)
        if (configured != LAZER_AUDIO_OK) {
            onError(token, IOException("无法应用 ${nativeAudioBackendLabel()} 输出设置：${lastError()}"))
            return token
        }
        api.lazer_audio_set_volume(enginePtr, this.volume.toDouble())
        applyEqualizer(enginePtr)

        val seekable = cache.openSeekable(trackId, cacheVariant, url, expectedBytes)
        source = seekable
        val nativeReader = NativeReaderBinding(seekable)
        binding = nativeReader
        val readerStructure = LazerAudioReader().apply {
            read = nativeReader.read
            seek = nativeReader.seek
            length = nativeReader.length
            close = nativeReader.close
            context = null
            cancel = nativeReader.cancel
        }
        reader = readerStructure

        val startMillis = if (durationMillis > 0L) {
            (durationMillis * fromProgress.coerceIn(0f, 1f)).toLong()
        } else {
            0L
        }
        val params = LazerAudioOpenParams().apply {
            structSize = size()
            this.startMillis = startMillis
            durationHintMillis = durationMillis
        }
        val opened = api.lazer_audio_open_reader(enginePtr, readerStructure, params)
        if (opened != LAZER_AUDIO_OK) {
            onError(token, IOException("原生引擎打开音频失败：${lastError()}"))
            cleanupSource()
            return token
        }

        publishStreamInfo(enginePtr)
        activeToken.set(token)
        progressTarget.set(PlaybackProgressTarget(trackId, durationMillis))

        if (playWhenReady) {
            val started = api.lazer_audio_play(enginePtr)
            if (started != LAZER_AUDIO_OK) {
                activeToken.compareAndSet(token, 0L)
                runCatching { api.lazer_audio_stop(enginePtr) }
                cleanupSource()
                onStreamInfo(null)
                onError(token, IOException("原生引擎播放失败：${lastError()}"))
                return token
            }
        }
        startPolling(token)
        return token
    }

    /** Opens a user-selected local audio file through the UTF-8 native file API. */
    @Synchronized
    internal fun playLocalFile(
        file: File,
        trackId: Long,
        durationMillis: Long,
        fromProgress: Float,
        volume: Float,
        replayGainDb: Double = 0.0,
        pcmPlaybackPolicy: PlaybackPolicy? = null,
        playWhenReady: Boolean,
        cueStartFrame75: Long = 0L,
        cueEndFrame75: Long = 0L,
        onTokenActivated: (Long) -> Boolean = { true },
    ): Long {
        stopInternal()
        activeTrackId = trackId
        activeReplayGainDb = replayGainDb.takeIf(Double::isFinite)?.coerceIn(-60.0, 24.0) ?: 0.0
        this.volume = volume.coerceIn(0f, 1f)
        paused = !playWhenReady
        val token = generation.incrementAndGet()
        fun fail(error: Throwable): Nothing {
            activeToken.compareAndSet(token, 0L)
            engine?.let { runCatching { api.lazer_audio_stop(it) } }
            cleanupSource()
            progressTarget.set(null)
            onStreamInfo(null)
            throw error
        }

        try {
            require(file.isFile && file.canRead()) { "本地音频文件不存在或不可读取：${file.name}" }
            val enginePtr = engine ?: createEngine().also { engine = it }
                ?: return fail(IOException("无法创建原生音频引擎"))
            val configured = api.lazer_audio_set_device(
                enginePtr,
                createDeviceConfig(policyOverride = pcmPlaybackPolicy),
            )
            if (configured != LAZER_AUDIO_OK) {
                return fail(IOException("无法应用 ${nativeAudioBackendLabel()} 输出设置：${lastError()}"))
            }
            api.lazer_audio_set_volume(enginePtr, this.volume.toDouble())
            applyEqualizer(enginePtr)

            val safeProgress = playableSeekProgress(fromProgress, durationMillis)
            val params = LazerAudioOpenParams().apply {
                structSize = size()
                startMillis = if (durationMillis > 0L) {
                    (durationMillis * safeProgress).toLong()
                } else {
                    0L
                }
                this.durationHintMillis = durationMillis
                this.cueStartFrame75 = cueStartFrame75
                this.cueEndFrame75 = cueEndFrame75
                this.replayGainDb = activeReplayGainDb
            }
            val normalizedPath = file.toPath().toAbsolutePath().normalize().toString()
            val pathUtf8 = normalizedPath.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
            val opened = api.lazer_audio_open_file_utf8(enginePtr, pathUtf8, params)
            if (opened != LAZER_AUDIO_OK) {
                return fail(IOException("原生引擎打开本地音频失败：${lastError()}"))
            }

            activeToken.set(token)
            if (!onTokenActivated(token)) {
                return fail(CancellationException("本地播放请求已被更新的请求取代"))
            }
            publishStreamInfo(enginePtr)
            progressTarget.set(PlaybackProgressTarget(trackId, durationMillis))
            if (playWhenReady) {
                val started = api.lazer_audio_play(enginePtr)
                if (started != LAZER_AUDIO_OK) {
                    activeToken.compareAndSet(token, 0L)
                    return fail(IOException("原生引擎播放本地音频失败：${lastError()}"))
                }
            }
            startPolling(token)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            fail(error)
        }
        return token
    }

    /** Opens a generated local PCM test WAV without routing it through the music cache or queue. */
    @Synchronized
    internal fun playPcmTestFile(
        file: File,
        durationMillis: Long,
        volume: Float,
        onTerminal: (Throwable?) -> Unit,
    ): Long {
        stopInternal()
        activeTrackId = null
        activeReplayGainDb = 0.0
        temporarySourceFile = file
        this.volume = volume.coerceIn(0f, 1f)
        paused = false
        val token = generation.incrementAndGet()
        fun fail(error: Throwable): Long {
            activeToken.compareAndSet(token, 0L)
            clearTestTerminal(token)
            engine?.let { runCatching { api.lazer_audio_stop(it) } }
            cleanupSource()
            onStreamInfo(null)
            runCatching { onTerminal(error) }
            return token
        }

        try {
            val enginePtr = engine ?: createEngine().also { engine = it }
                ?: return fail(IOException("无法创建原生音频引擎"))

            val configured = api.lazer_audio_set_device(enginePtr, createDeviceConfig())
            if (configured != LAZER_AUDIO_OK) {
                return fail(IOException("无法应用 ${nativeAudioBackendLabel()} 输出设置：${lastError()}"))
            }
            api.lazer_audio_set_volume(enginePtr, this.volume.toDouble())
            applyEqualizer(enginePtr)

            val params = LazerAudioOpenParams().apply {
                structSize = size()
                startMillis = 0L
                this.durationHintMillis = durationMillis
            }
            val pathUtf8 = file.absolutePath.toByteArray(Charsets.UTF_8) + byteArrayOf(0)
            val opened = api.lazer_audio_open_file_utf8(enginePtr, pathUtf8, params)
            if (opened != LAZER_AUDIO_OK) {
                return fail(IOException("原生引擎打开 PCM 测试音失败：${lastError()}"))
            }

            publishStreamInfo(enginePtr)
            activeToken.set(token)
            testTerminalToken = token
            testTerminalCallback = onTerminal
            val started = api.lazer_audio_play(enginePtr)
            if (started != LAZER_AUDIO_OK) {
                return fail(IOException("原生引擎播放 PCM 测试音失败：${lastError()}"))
            }
            startPolling(token)
        } catch (error: Throwable) {
            return fail(error)
        }
        return token
    }

    override fun pause() {
        paused = true
        engine?.let { runCatching { api.lazer_audio_pause(it) } }
    }

    override fun resume() {
        paused = false
        engine?.let { runCatching { api.lazer_audio_play(it) } }
    }

    override fun setVolume(value: Float) {
        volume = value.coerceIn(0f, 1f)
        engine?.let { runCatching { api.lazer_audio_set_volume(it, volume.toDouble()) } }
    }

    override fun setUiForeground(foreground: Boolean) {
        uiForeground = foreground
    }

    override suspend fun setExclusiveAudio(enabled: Boolean) {
        // The device is chosen when the next stream opens; the controller rebuilds playback.
        exclusiveAudio = enabled
    }

    override fun setEqualizer(state: LazerEqualizerState) {
        equalizer = state
        engine?.let { applyEqualizer(it) }
    }

    /** Extra Bit-perfect toggle; not part of the shared engine surface because only this engine has it. */
    fun setBitPerfect(enabled: Boolean) {
        bitPerfect = enabled
    }

    /** Requires exact 24-bit DoP output and bypasses EQ and digital volume. */
    fun setDoPOutput(enabled: Boolean) {
        doPOutput = enabled
        if (enabled) nativeDsdOutput = false
    }

    /** Requires exact ALSA Native DSD output and bypasses EQ and digital volume. */
    fun setNativeDsdOutput(enabled: Boolean) {
        nativeDsdOutput = enabled
        if (enabled) doPOutput = false
    }

    /** Output buffer length in milliseconds; applied when the next stream opens. */
    fun setBufferMillis(value: Int) {
        bufferMillis = value.coerceIn(30, 1_000)
    }

    override fun queueNext(playback: DesktopQueuedPlayback) {
        clearQueuedNext()
        val token = activeToken.get()
        if (token == 0L || closing || activeTrackId != playback.afterTrackId) return
        val requestGeneration = queueGeneration.incrementAndGet()
        queueJob = scope.launch {
            var seekable: DesktopSeekableAudioSource? = null
            var preparingBinding: NativeReaderBinding? = null
            var preparingHandle: Closeable? = null
            var handle: NativeQueuedSource? = null
            try {
                val localSource = playback.track.playbackSource as? DesktopTrackSource.LocalFile
                seekable = if (localSource != null) {
                    openLocalSeekableAudioSource(File(localSource.absolutePath))
                } else {
                    val url = playback.url?.takeIf(String::isNotBlank)
                        ?: throw IOException("后继曲目没有可用的音源")
                    cache.openSeekable(
                        playback.track.id,
                        playback.cacheVariant,
                        url,
                        playback.expectedBytes,
                    )
                }
                val callbacks = NativeReaderBinding(seekable)
                preparingBinding = callbacks
                preparingSource.set(callbacks)
                preparingHandle = callbacks
                if (!isCurrentQueueRequest(requestGeneration, token, playback.afterTrackId)) {
                    callbacks.close()
                    return@launch
                }
                val structure = LazerAudioReader().apply {
                    read = callbacks.read
                    seek = callbacks.seek
                    length = callbacks.length
                    close = callbacks.close
                    context = null
                    cancel = callbacks.cancel
                }
                val sourceHandle = NativeQueuedSource(seekable, structure, callbacks, playback, token)
                handle = sourceHandle
                val enginePtr = synchronized(queueStateLock) {
                    if (!isCurrentQueueRequest(requestGeneration, token, playback.afterTrackId) || engine == null) {
                        callbacks.close()
                        return@launch
                    }
                    queuedSource = seekable
                    queuedReader = structure
                    queuedBinding = callbacks
                    queuedPlayback = playback
                    queuedToken = token
                    queuedSubmitted = true
                    engine
                } ?: return@launch
                if (!beginQueueCall(enginePtr)) {
                    discardQueued(sourceHandle)
                    return@launch
                }
                val params = LazerAudioOpenParams().apply {
                    structSize = size()
                    startMillis = 0L
                    durationHintMillis = playback.track.durationMillis
                    cueStartFrame75 = localSource?.cueStartFrame75 ?: 0L
                    cueEndFrame75 = localSource?.cueEndFrame75 ?: 0L
                    replayGainDb = playback.replayGainDb
                }
                val status = try {
                    api.lazer_audio_queue_reader(enginePtr, requestGeneration, structure, params)
                } finally {
                    endQueueCall()
                }
                if (status != LAZER_AUDIO_OK) {
                    discardQueued(sourceHandle)
                    PlaybackDebugLog.event(
                        "gapless-prepare-skipped",
                        "token=$token track=${playback.track.id} status=$status",
                    )
                } else {
                    PlaybackDebugLog.event(
                        "gapless-prepare-ready",
                        "token=$token track=${playback.track.id}",
                    )
                }
            } catch (error: Throwable) {
                handle?.let(::discardQueued)
                if (handle == null) {
                    (preparingBinding ?: seekable)?.let { runCatching { it.close() } }
                }
                if (error !is kotlinx.coroutines.CancellationException) {
                    PlaybackDebugLog.event(
                        "gapless-prepare-failed",
                        "token=$token track=${playback.track.id} error=${error.playbackDebugSummary()}",
                    )
                }
            } finally {
                preparingHandle?.let { preparingSource.compareAndSet(it, null) }
            }
        }
    }

    @Synchronized
    override fun clearQueuedNext() {
        invalidateQueuedSource()
    }

    /** Endpoint ID is resolved from the current device catalog; null selects the system default. */
    fun setOutputDevice(endpointId: String?) {
        outputEndpointId = endpointId?.takeIf(String::isNotBlank)
    }

    override fun stop() {
        stopInternal()
    }

    /** Waits for the native lock off the UI thread, then skips a stop superseded by a newer request. */
    @Synchronized
    internal fun stopIfCurrent(isRequestCurrent: () -> Boolean) {
        if (isRequestCurrent()) stopInternal()
    }

    override suspend fun clearCache(): Int {
        stopInternal()
        return cache.clear()
    }

    @Synchronized
    override fun close() {
        closing = true
        activeToken.set(0L)
        pollJob?.cancel()
        pollJob = null
        invalidateQueuedSource()
        val enginePtr = engine
        engine = null
        enginePtr?.let { runCatching { api.lazer_audio_stop(it) } }
        queueCallsLock.withLock {
            var interrupted = false
            while (queueCallsInFlight > 0) {
                try {
                    queueCallsDrained.await()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        if (enginePtr != null) runCatching { api.lazer_audio_destroy(enginePtr) }
        releaseQueuedReferences()
        cleanupSource()
        onStreamInfo(null)
        cache.close()
        scope.cancel()
    }

    @Synchronized
    private fun stopInternal() {
        invalidateQueuedSource()
        activeToken.set(0L)
        testTerminalToken = 0L
        testTerminalCallback = null
        pollJob?.cancel()
        pollJob = null
        paused = false
        engine?.let { runCatching { api.lazer_audio_stop(it) } }
        releaseQueuedReferences()
        cleanupSource()
        progressTarget.set(null)
        activeTrackId = null
        onStreamInfo(null)
    }

    private fun invalidateQueuedSource() {
        val requestGeneration = queueGeneration.incrementAndGet()
        queueJob?.cancel()
        queueJob = null
        val enginePtr = engine
        val clearResult = if (enginePtr != null) {
            runCatching { api.lazer_audio_clear_queued_reader(enginePtr, requestGeneration) }
                .getOrDefault(LAZER_AUDIO_ERROR_STATE)
        } else {
            LAZER_AUDIO_ERROR_STATE
        }
        val preparing = preparingSource.getAndSet(null)
        when (clearResult) {
            LAZER_AUDIO_OK -> preparing?.let { runCatching { it.close() } }
            LAZER_AUDIO_QUEUE_ALREADY_ACTIVE -> Unit
            else -> preparing?.let { runCatching { it.close() } }
        }
        synchronized(queueStateLock) {
            if (clearResult == LAZER_AUDIO_OK || (!queuedSubmitted &&
                    clearResult != LAZER_AUDIO_QUEUE_ALREADY_ACTIVE)) {
                queuedBinding?.let { runCatching { it.close() } }
                    ?: queuedSource?.let { runCatching { it.close() } }
                queuedSource = null
                queuedReader = null
                queuedBinding = null
                queuedPlayback = null
                queuedToken = 0L
                queuedSubmitted = false
            }
        }
    }

    private fun handleTrackChanged(token: Long) {
        val playback = synchronized(queueStateLock) {
            if (activeToken.get() != token || queuedToken != token) return
            val nextPlayback = queuedPlayback ?: return
            source = queuedSource
            reader = queuedReader
            binding = queuedBinding
            queuedSource = null
            queuedReader = null
            queuedBinding = null
            queuedPlayback = null
            queuedToken = 0L
            queuedSubmitted = false
            activeTrackId = nextPlayback.track.id
            progressTarget.set(PlaybackProgressTarget(nextPlayback.track.id, nextPlayback.track.durationMillis))
            nextPlayback
        }
        onTrackChanged(token, playback)
    }

    private fun isCurrentQueueRequest(
        requestGeneration: Long,
        token: Long,
        afterTrackId: Long,
    ): Boolean = !closing && queueGeneration.get() == requestGeneration &&
        activeToken.get() == token && activeTrackId == afterTrackId

    private fun beginQueueCall(enginePtr: Pointer): Boolean = queueCallsLock.withLock {
        if (closing || engine !== enginePtr) return@withLock false
        queueCallsInFlight++
        true
    }

    private fun endQueueCall() {
        queueCallsLock.withLock {
            queueCallsInFlight--
            if (queueCallsInFlight == 0) queueCallsDrained.signalAll()
        }
    }

    private fun discardQueued(handle: NativeQueuedSource) {
        synchronized(queueStateLock) {
            if (queuedSource === handle.source && queuedToken == handle.token) {
                queuedSource = null
                queuedReader = null
                queuedBinding = null
                queuedPlayback = null
                queuedToken = 0L
                queuedSubmitted = false
            }
        }
        runCatching { handle.binding.close() }
    }

    private fun releaseQueuedReferences() {
        synchronized(queueStateLock) {
            queuedBinding?.let { runCatching { it.close() } }
                ?: queuedSource?.let { runCatching { it.close() } }
            queuedSource = null
            queuedReader = null
            queuedBinding = null
            queuedPlayback = null
            queuedToken = 0L
            queuedSubmitted = false
        }
    }

    private fun cleanupSource() {
        val current = synchronized(queueStateLock) {
            (source to binding).also {
                source = null
                reader = null
                binding = null
            }
        }
        runCatching { current.second?.close() ?: current.first?.close() }
        val temporaryFile = temporarySourceFile
        temporarySourceFile = null
        runCatching { temporaryFile?.delete() }
    }

    private fun createEngine(): Pointer? = runCatching {
        val config = LazerAudioEngineConfig().apply {
            structSize = size()
            device = createDeviceConfig()
            events.onEvent = eventHandler
            events.context = null
            log.onLog = logHandler
            log.context = null
        }
        api.lazer_audio_create(config)
    }.getOrNull()

    private fun createDeviceConfig(policyOverride: PlaybackPolicy? = null) = LazerAudioDeviceConfig().apply {
        val pcmPolicy = policyOverride ?: resolvePcmPlaybackPolicy(
            bitPerfectRequested = this@DesktopNativeAudioPlayer.bitPerfect,
            exclusiveRequested = this@DesktopNativeAudioPlayer.exclusiveAudio,
            processingActive = this@DesktopNativeAudioPlayer.activeReplayGainDb != 0.0,
            bufferDurationMillis = this@DesktopNativeAudioPlayer.bufferMillis,
        )
        deviceId = outputEndpointId?.let(::WString)
        exclusive = if (exclusiveAudio) 1 else 0
        resampleMode = LAZER_AUDIO_RESAMPLE_NATIVE
        targetSampleRate = 0
        bufferMillis = this@DesktopNativeAudioPlayer.bufferMillis
        bitPerfect = if (this@DesktopNativeAudioPlayer.doPOutput ||
            this@DesktopNativeAudioPlayer.nativeDsdOutput) 0 else pcmPolicy.toNativePcmBitPerfectFlag()
        dsdOutputMode = when {
            this@DesktopNativeAudioPlayer.nativeDsdOutput -> LAZER_AUDIO_DSD_OUTPUT_REQUIRE_NATIVE
            this@DesktopNativeAudioPlayer.doPOutput -> LAZER_AUDIO_DSD_OUTPUT_REQUIRE_DOP
            else -> LAZER_AUDIO_DSD_OUTPUT_CONVERT_TO_PCM
        }
    }

    @Synchronized
    private fun handleTerminal(token: Long, error: Throwable?) {
        if (!activeToken.compareAndSet(token, 0L)) return
        pollJob?.cancel()
        scope.launch(Dispatchers.IO) { stopIfGeneration(token) }
        val testCallback = if (testTerminalToken == token) {
            testTerminalToken = 0L
            testTerminalCallback.also { testTerminalCallback = null }
        } else {
            null
        }
        if (testCallback != null) {
            testCallback(error)
        } else if (error == null) {
            onCompleted(token)
        } else {
            onError(token, error)
        }
    }

    /** A terminal callback runs on a native worker, so stop is deferred to a different thread. */
    private fun stopIfGeneration(expectedGeneration: Long) {
        synchronized(this) {
            if (generation.get() != expectedGeneration || closing) return
            stopInternal()
        }
    }

    private fun applyEqualizer(enginePtr: Pointer) {
        val state = equalizer
        val enabledBands = if (state.enabled) state.bands.filter(LazerEqBand::enabled) else emptyList()
        val bands = enabledBands.map { band ->
            LazerAudioEqBand().apply {
                kind = when (band.kind) {
                    LazerEqBandKind.Peak -> LAZER_AUDIO_EQ_PEAK
                    LazerEqBandKind.LowShelf -> LAZER_AUDIO_EQ_LOW_SHELF
                    LazerEqBandKind.HighShelf -> LAZER_AUDIO_EQ_HIGH_SHELF
                    LazerEqBandKind.LowPass -> LAZER_AUDIO_EQ_LOW_PASS
                    LazerEqBandKind.HighPass -> LAZER_AUDIO_EQ_HIGH_PASS
                    LazerEqBandKind.Notch -> LAZER_AUDIO_EQ_NOTCH
                    LazerEqBandKind.AllPass -> LAZER_AUDIO_EQ_ALL_PASS
                }
                frequencyHz = band.frequencyHz
                gainDb = band.gainDb
                q = band.q
                enabled = if (band.enabled) 1 else 0
            }
        }.toTypedArray()
        val dsp = LazerAudioDspConfig().apply {
            preampDb = if (state.enabled) state.preampDb else 0.0
            bandCount = bands.size
            this.bands = bands.takeIf { it.isNotEmpty() }
            limiterEnabled = if (state.limiterEnabled) 1 else 0
            limiterThresholdDb = -1.0
            balanceChannels = 0
        }
        runCatching { api.lazer_audio_set_dsp(enginePtr, dsp) }
    }

    private fun publishStreamInfo(enginePtr: Pointer, snapshot: LazerAudioSnapshot? = null) {
        val info = LazerAudioStreamInfo()
        if (api.lazer_audio_stream_info(enginePtr, info) != LAZER_AUDIO_OK) return
        val streamInfo = LazerHiFiStreamInfo(
            codec = info.codecName().ifBlank { "unknown" },
            sourceSampleRate = info.sourceSampleRate,
            sourceChannels = info.sourceChannels,
            sourceBitsPerSample = info.sourceBitsPerSample,
            sourceFormatKind = info.sourceFormatKind,
            sourceDsdRateMultiplier = info.sourceDsdRateMultiplier,
            sampleRate = info.outputSampleRate,
            channels = info.outputChannels,
            bitsPerSample = info.outputBitsPerSample,
            outputContainerBitsPerSample = info.outputContainerBitsPerSample,
            outputIsFloat = info.outputIsFloat != 0,
            outputFormatInitialized = info.outputFormatInitialized != 0,
            formatSelection = info.outputFormatSelection.toOutputFormatSelection(),
            outputTelemetry = info.toOutputTelemetrySnapshot(),
            outputFormatKind = info.outputFormatKind,
            outputDsdRateMultiplier = info.outputDsdRateMultiplier,
            outputNonMixable = info.coreAudioNonMixableOrNull(),
            exclusive = info.outputExclusive != 0,
            lossless = info.lossless != 0,
            bitPerfectActive = info.bitPerfectActive != 0,
            signalPath = SignalPathSnapshot(),
            underrunActive = snapshot?.underrunActive == 1,
            underrunFrames = snapshot?.underrunFrames ?: 0L,
        )
        onStreamInfo(streamInfo.copy(signalPath = streamInfo.toSignalPathSnapshot()))
    }

    private fun startPolling(token: Long) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var lastBuffered = -1
            while (isActive && activeToken.get() == token && generation.get() == token) {
                val enginePtr = engine ?: return@launch
                val snapshot = LazerAudioSnapshot()
                if (api.lazer_audio_snapshot(enginePtr, snapshot) == LAZER_AUDIO_OK) {
                    val target = progressTarget.get()
                    if (target != null && target.durationMillis > 0L) {
                        val progress = (snapshot.positionMillis.toFloat() / target.durationMillis.toFloat())
                            .coerceIn(0f, 0.999f)
                        onProgress(token, progress)
                    }
                    if (target != null && snapshot.bufferedPercent != lastBuffered) {
                        lastBuffered = snapshot.bufferedPercent
                        onBuffered(target.trackId, (snapshot.bufferedPercent / 100f).coerceIn(0f, 1f))
                    }
                    publishStreamInfo(enginePtr, snapshot)
                    if (snapshot.state == LAZER_AUDIO_STATE_FAILED) {
                        val errorDetail = lastError()
                        val failure = desktopTerminalError(
                            snapshot.terminalEvent,
                            snapshot.terminalPositionMillis,
                            errorDetail,
                        ) ?: IOException("原生音频引擎失败：$errorDetail")
                        if (activeToken.get() == token) handleTerminal(token, failure)
                        return@launch
                    }
                    if (snapshot.state == LAZER_AUDIO_STATE_STOPPED &&
                        snapshot.terminalEvent == LAZER_AUDIO_EVENT_ENDED) {
                        if (activeToken.get() == token) handleTerminal(token, null)
                        return@launch
                    }
                }
                delay(if (uiForeground) 200 else 1_000)
            }
        }
    }

    private fun lastError(): String {
        val enginePtr = engine ?: return "引擎未初始化"
        return runCatching { api.lazer_audio_last_error(enginePtr) }.getOrNull().orEmpty()
    }

    private fun clearTestTerminal(token: Long) {
        if (testTerminalToken != token) return
        testTerminalToken = 0L
        testTerminalCallback = null
    }
}

private data class PlaybackProgressTarget(val trackId: Long, val durationMillis: Long)

private data class NativeQueuedSource(
    val source: DesktopSeekableAudioSource,
    val reader: LazerAudioReader,
    val binding: NativeReaderBinding,
    val playback: DesktopQueuedPlayback,
    val token: Long,
)

/** Bridges the native read/seek/length/close callbacks onto a [DesktopSeekableAudioSource]. */
internal class NativeReaderBinding(private val source: DesktopSeekableAudioSource) : Closeable {
    private val sourceClosed = AtomicBoolean(false)

    override fun close() {
        if (sourceClosed.compareAndSet(false, true)) runCatching { source.close() }
    }

    val read = object : LazerAudioReadCallback {
        override fun callback(context: Pointer?, destination: Pointer?, length: Int): Int {
            if (destination == null || length <= 0) return LAZER_AUDIO_READER_IO_ERROR
            val buffer = ByteArray(length)
            val count = try {
                source.read(buffer, length)
            } catch (_: Throwable) {
                return LAZER_AUDIO_READER_IO_ERROR
            }
            if (count == -1 || count == 0) return count
            if (count < 0 || count > length) return LAZER_AUDIO_READER_IO_ERROR
            destination.write(0, buffer, 0, count)
            return count
        }
    }

    val seek = object : LazerAudioSeekCallback {
        override fun callback(context: Pointer?, positionBytes: Long): Long =
            runCatching { source.seek(positionBytes) }.getOrDefault(-1L)
    }

    val length = object : LazerAudioLengthCallback {
        override fun callback(context: Pointer?): Long =
            runCatching { source.length() }.getOrDefault(-1L)
    }

    val close = object : LazerAudioCloseCallback {
        override fun callback(context: Pointer?) {
            this@NativeReaderBinding.close()
        }
    }

    val cancel = object : LazerAudioCancelCallback {
        override fun callback(context: Pointer?) {
            this@NativeReaderBinding.close()
        }
    }
}
