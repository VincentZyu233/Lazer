package dev.naominet.lazer

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.Structure.FieldOrder
import com.sun.jna.WString
import com.sun.jna.ptr.FloatByReference
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.io.File

/**
 * JNA mapping for `lazer-audio.dll`. Field order and types mirror `lazer_audio_api.h` exactly; JNA
 * derives the same x64 alignment C does, so a mismatch here shows up as garbage in a struct rather
 * than a link error.
 */
internal interface LazerAudioLibrary : Library {
    fun lazer_audio_abi_version(): Int

    fun lazer_audio_create(config: LazerAudioEngineConfig?): Pointer?

    fun lazer_audio_destroy(engine: Pointer?)

    fun lazer_audio_open_file(
        engine: Pointer?,
        path: WString?,
        params: LazerAudioOpenParams?,
    ): Int

    fun lazer_audio_open_file_utf8(
        engine: Pointer?,
        pathUtf8: ByteArray,
        params: LazerAudioOpenParams?,
    ): Int

    fun lazer_audio_open_reader(
        engine: Pointer?,
        reader: LazerAudioReader?,
        params: LazerAudioOpenParams?,
    ): Int

    /** Queue one prepared successor in the current output session; call from the IO dispatcher. */
    fun lazer_audio_queue_reader(
        engine: Pointer?,
        queueGeneration: Long,
        reader: LazerAudioReader?,
        params: LazerAudioOpenParams?,
    ): Int

    fun lazer_audio_clear_queued_reader(engine: Pointer?, queueGeneration: Long): Int

    fun lazer_audio_play(engine: Pointer?): Int

    fun lazer_audio_pause(engine: Pointer?): Int

    fun lazer_audio_stop(engine: Pointer?): Int

    fun lazer_audio_seek(engine: Pointer?, positionMillis: Long): Int

    fun lazer_audio_set_volume(engine: Pointer?, volume: Double): Int

    fun lazer_audio_set_dsp(engine: Pointer?, dsp: LazerAudioDspConfig?): Int

    fun lazer_audio_set_device(engine: Pointer?, device: LazerAudioDeviceConfig?): Int

    fun lazer_audio_snapshot(engine: Pointer?, out: LazerAudioSnapshot?): Int

    fun lazer_audio_stream_info(engine: Pointer?, out: LazerAudioStreamInfo?): Int

    fun lazer_audio_last_error(engine: Pointer?): String

    fun lazer_audio_build_information(): String

    /** Optional additive API: older DLLs may not export the device-catalog symbols. */
    fun lazer_audio_device_catalog_create(outCatalog: PointerByReference?): Int

    fun lazer_audio_device_catalog_count(catalog: Pointer?, outCount: IntByReference?): Int

    fun lazer_audio_device_catalog_get(
        catalog: Pointer?,
        index: Int,
        outInfo: LazerAudioDeviceInfo?,
        endpointId: Pointer?,
        endpointIdCapacity: Int,
        endpointIdChars: IntByReference?,
        identityKey: Pointer?,
        identityKeyCapacity: Int,
        identityKeyChars: IntByReference?,
        friendlyName: Pointer?,
        friendlyNameCapacity: Int,
        friendlyNameChars: IntByReference?,
    ): Int

    fun lazer_audio_device_catalog_destroy(catalog: Pointer?)

    /** Generic UTF-8 direct-output catalog, currently backed by ALSA `hw:` on Linux. */
    fun lazer_audio_output_device_catalog_create(outCatalog: PointerByReference?): Int

    fun lazer_audio_output_device_catalog_count(catalog: Pointer?, outCount: IntByReference?): Int

    fun lazer_audio_output_device_catalog_get(
        catalog: Pointer?,
        index: Int,
        outInfo: LazerAudioOutputDeviceInfo?,
        deviceTokenUtf8: Pointer?,
        deviceTokenCapacity: Int,
        deviceTokenBytes: IntByReference?,
        identityKeyUtf8: Pointer?,
        identityKeyCapacity: Int,
        identityKeyBytes: IntByReference?,
        displayNameUtf8: Pointer?,
        displayNameCapacity: Int,
        displayNameBytes: IntByReference?,
    ): Int

    fun lazer_audio_output_device_catalog_destroy(catalog: Pointer?)

    /** Optional additive API: exact WASAPI exclusive PCM format queries. */
    fun lazer_audio_device_probe_pcm_formats(
        endpointId: WString?,
        candidates: Pointer?,
        candidateCount: Int,
        outResults: Pointer?,
    ): Int

    /** Windows endpoint master volume; this is separate from USB UAC Feature Unit access. */
    fun lazer_audio_endpoint_volume_get(
        endpointId: WString?,
        outScalar: FloatByReference?,
        outHardwareFlags: IntByReference?,
        outHresult: IntByReference?,
    ): Int

    fun lazer_audio_endpoint_volume_set(
        endpointId: WString?,
        scalar: Float,
        outHardwareFlags: IntByReference?,
        outHresult: IntByReference?,
    ): Int
}

/** Called by the engine from its own threads, so implementations must never block on the UI. */
internal interface LazerAudioEventCallback : Callback {
    fun callback(context: Pointer?, event: Int, detail: Int, positionMillis: Long)
}

internal interface LazerAudioLogCallback : Callback {
    fun callback(context: Pointer?, level: Int, message: String?)
}

/**
 * The byte source is owned by Kotlin: it is normally the growing HTTP cache file, whose reads block
 * until the downloader lands more bytes. The callback returns a positive byte count for data, zero
 * for temporary unavailability, -1 for clean EOF, or [LAZER_AUDIO_READER_IO_ERROR] for a terminal
 * I/O failure.
 */
internal interface LazerAudioReadCallback : Callback {
    fun callback(context: Pointer?, destination: Pointer?, length: Int): Int
}

internal interface LazerAudioSeekCallback : Callback {
    fun callback(context: Pointer?, positionBytes: Long): Long
}

internal interface LazerAudioLengthCallback : Callback {
    fun callback(context: Pointer?): Long
}

internal interface LazerAudioCloseCallback : Callback {
    fun callback(context: Pointer?)
}

internal interface LazerAudioCancelCallback : Callback {
    fun callback(context: Pointer?)
}

@FieldOrder("abiVersion", "structSize", "device", "events", "log")
internal class LazerAudioEngineConfig : Structure() {
    // Order must match lazer_audio_api.h exactly; JNA validates that every public field is listed.
    @JvmField var abiVersion: Int = LAZER_AUDIO_ABI_VERSION
    @JvmField var structSize: Int = 0
    @JvmField var device = LazerAudioDeviceConfig()
    @JvmField var events = LazerAudioEventHandler()
    @JvmField var log = LazerAudioLogHandler()
}

@FieldOrder("deviceId", "exclusive", "resampleMode", "targetSampleRate", "bufferMillis", "bitPerfect", "dsdOutputMode")
internal class LazerAudioDeviceConfig : Structure() {
    @JvmField var deviceId: WString? = null
    @JvmField var exclusive: Int = 0
    @JvmField var resampleMode: Int = LAZER_AUDIO_RESAMPLE_NATIVE
    @JvmField var targetSampleRate: Int = 0
    @JvmField var bufferMillis: Int = 120
    @JvmField var bitPerfect: Int = 0
    @JvmField var dsdOutputMode: Int = LAZER_AUDIO_DSD_OUTPUT_CONVERT_TO_PCM

    override fun getFieldOrder(): List<String> =
        listOf("deviceId", "exclusive", "resampleMode", "targetSampleRate", "bufferMillis", "bitPerfect", "dsdOutputMode")
}

@FieldOrder("onEvent", "context")
internal class LazerAudioEventHandler : Structure() {
    @JvmField var onEvent: LazerAudioEventCallback? = null
    @JvmField var context: Pointer? = null

    override fun getFieldOrder(): List<String> = listOf("onEvent", "context")
}

@FieldOrder("onLog", "context")
internal class LazerAudioLogHandler : Structure() {
    @JvmField var onLog: LazerAudioLogCallback? = null
    @JvmField var context: Pointer? = null

    override fun getFieldOrder(): List<String> = listOf("onLog", "context")
}

@FieldOrder("read", "seek", "length", "close", "context", "cancel")
internal class LazerAudioReader : Structure() {
    @JvmField var read: LazerAudioReadCallback? = null
    @JvmField var seek: LazerAudioSeekCallback? = null
    @JvmField var length: LazerAudioLengthCallback? = null
    @JvmField var close: LazerAudioCloseCallback? = null
    @JvmField var context: Pointer? = null
    @JvmField var cancel: LazerAudioCancelCallback? = null

    override fun getFieldOrder(): List<String> =
        listOf("read", "seek", "length", "close", "context", "cancel")
}

@FieldOrder(
    "structSize", "startMillis", "durationHintMillis", "cueStartFrame75", "cueEndFrame75", "replayGainDb",
)
internal class LazerAudioOpenParams : Structure() {
    /** Left at zero on purpose: [size] only becomes meaningful once the structure is instantiated. */
    @JvmField var structSize: Int = 0
    @JvmField var startMillis: Long = 0
    @JvmField var durationHintMillis: Long = 0
    @JvmField var cueStartFrame75: Long = 0
    @JvmField var cueEndFrame75: Long = 0
    @JvmField var replayGainDb: Double = 0.0

    override fun getFieldOrder(): List<String> =
        listOf(
            "structSize", "startMillis", "durationHintMillis", "cueStartFrame75", "cueEndFrame75", "replayGainDb",
        )
}

@FieldOrder(
    "state", "paused", "positionMillis", "durationMillis", "bufferedPercent", "error",
    "underrunActive", "underrunFrames", "terminalEvent", "terminalDetail", "terminalPositionMillis",
)
internal class LazerAudioSnapshot : Structure() {
    @JvmField var state: Int = 0
    @JvmField var paused: Int = 0
    @JvmField var positionMillis: Long = 0
    @JvmField var durationMillis: Long = 0
    @JvmField var bufferedPercent: Int = 0
    @JvmField var error: Int = 0
    @JvmField var underrunActive: Int = 0
    @JvmField var underrunFrames: Long = 0
    @JvmField var terminalEvent: Int = LAZER_AUDIO_EVENT_NONE
    @JvmField var terminalDetail: Int = 0
    @JvmField var terminalPositionMillis: Long = 0

    override fun getFieldOrder(): List<String> =
        listOf(
            "state", "paused", "positionMillis", "durationMillis", "bufferedPercent", "error",
            "underrunActive", "underrunFrames", "terminalEvent", "terminalDetail", "terminalPositionMillis",
        )
}

@FieldOrder(
    "codec", "sourceSampleRate", "sourceChannels", "sourceBitsPerSample", "sourceBitrateKbps",
    "lossless", "outputSampleRate", "outputChannels", "outputBitsPerSample", "outputExclusive",
    "bitPerfectActive", "outputContainerBitsPerSample", "outputIsFloat", "outputFormatInitialized",
    "sourceFormatKind", "sourceDsdRateMultiplier", "outputFormatSelection", "outputPeakMilliDbfs",
    "limiterGainReductionMilliDb", "outputClippedSampleCount", "outputTelemetryValid",
    "outputFormatKind", "outputDsdRateMultiplier", "outputCoreAudioMixabilityKnown",
    "outputCoreAudioNonMixable",
)
internal class LazerAudioStreamInfo : Structure() {
    @JvmField var codec = ByteArray(64)
    @JvmField var sourceSampleRate: Int = 0
    @JvmField var sourceChannels: Int = 0
    @JvmField var sourceBitsPerSample: Int = 0
    @JvmField var sourceBitrateKbps: Int = 0
    @JvmField var lossless: Int = 0
    @JvmField var outputSampleRate: Int = 0
    @JvmField var outputChannels: Int = 0
    @JvmField var outputBitsPerSample: Int = 0
    @JvmField var outputExclusive: Int = 0
    @JvmField var bitPerfectActive: Int = 0
    @JvmField var outputContainerBitsPerSample: Int = 0
    @JvmField var outputIsFloat: Int = 0
    @JvmField var outputFormatInitialized: Int = 0
    @JvmField var sourceFormatKind: Int = 0
    @JvmField var sourceDsdRateMultiplier: Int = 0
    @JvmField var outputFormatSelection: Int = LAZER_AUDIO_FORMAT_SELECTION_UNKNOWN
    @JvmField var outputPeakMilliDbfs: Int = -120_000
    @JvmField var limiterGainReductionMilliDb: Int = 0
    @JvmField var outputClippedSampleCount: Long = 0L
    @JvmField var outputTelemetryValid: Int = 0
    @JvmField var outputFormatKind: Int = LAZER_AUDIO_OUTPUT_FORMAT_PCM
    @JvmField var outputDsdRateMultiplier: Int = 0
    @JvmField var outputCoreAudioMixabilityKnown: Int = 0
    @JvmField var outputCoreAudioNonMixable: Int = 0

    override fun getFieldOrder(): List<String> = listOf(
        "codec", "sourceSampleRate", "sourceChannels", "sourceBitsPerSample", "sourceBitrateKbps",
        "lossless", "outputSampleRate", "outputChannels", "outputBitsPerSample", "outputExclusive",
        "bitPerfectActive", "outputContainerBitsPerSample", "outputIsFloat", "outputFormatInitialized",
        "sourceFormatKind", "sourceDsdRateMultiplier", "outputFormatSelection", "outputPeakMilliDbfs",
        "limiterGainReductionMilliDb", "outputClippedSampleCount", "outputTelemetryValid",
        "outputFormatKind", "outputDsdRateMultiplier", "outputCoreAudioMixabilityKnown",
        "outputCoreAudioNonMixable",
    )

    fun codecName(): String = String(codec, 0, codec.indexOfFirst { it == 0.toByte() }
        .takeIf { it >= 0 } ?: codec.size, Charsets.US_ASCII)
}

@FieldOrder(
    "structSize", "endpointState", "defaultRoleMask", "identityKind",
    "endpointVolumeQueryHresult", "endpointVolumeHardwareSupportFlags",
)
internal class LazerAudioDeviceInfo : Structure() {
    @JvmField var structSize: Int = 0
    @JvmField var endpointState: Int = 0
    @JvmField var defaultRoleMask: Int = 0
    @JvmField var identityKind: Int = 0
    @JvmField var endpointVolumeQueryHresult: Int = Int.MIN_VALUE
    @JvmField var endpointVolumeHardwareSupportFlags: Int = 0

    override fun getFieldOrder(): List<String> =
        listOf(
            "structSize", "endpointState", "defaultRoleMask", "identityKind",
            "endpointVolumeQueryHresult", "endpointVolumeHardwareSupportFlags",
        )
}

@FieldOrder("kind", "frequencyHz", "gainDb", "q", "enabled")
internal class LazerAudioEqBand : Structure() {
    @JvmField var kind: Int = LAZER_AUDIO_EQ_PEAK
    @JvmField var frequencyHz: Double = 1000.0
    @JvmField var gainDb: Double = 0.0
    @JvmField var q: Double = 1.0
    @JvmField var enabled: Int = 1

    override fun getFieldOrder(): List<String> =
        listOf("kind", "frequencyHz", "gainDb", "q", "enabled")
}

@FieldOrder("preampDb", "bandCount", "bands", "limiterEnabled", "limiterThresholdDb", "balanceChannels")
internal class LazerAudioDspConfig : Structure() {
    @JvmField var preampDb: Double = 0.0
    @JvmField var bandCount: Int = 0
    @JvmField var bands: Array<LazerAudioEqBand>? = null
    @JvmField var limiterEnabled: Int = 0
    @JvmField var limiterThresholdDb: Double = -1.0
    @JvmField var balanceChannels: Int = 0

    override fun getFieldOrder(): List<String> = listOf(
        "preampDb", "bandCount", "bands", "limiterEnabled", "limiterThresholdDb", "balanceChannels",
    )
}

internal const val LAZER_AUDIO_ABI_VERSION = 23
internal const val LAZER_AUDIO_READER_IO_ERROR = -2
internal const val LAZER_AUDIO_SOURCE_FORMAT_PCM = 0
internal const val LAZER_AUDIO_SOURCE_FORMAT_DSD = 1
internal const val LAZER_AUDIO_DSD_OUTPUT_CONVERT_TO_PCM = 0
internal const val LAZER_AUDIO_DSD_OUTPUT_REQUIRE_DOP = 1
internal const val LAZER_AUDIO_DSD_OUTPUT_REQUIRE_NATIVE = 2
internal const val LAZER_AUDIO_OUTPUT_FORMAT_PCM = 0
internal const val LAZER_AUDIO_OUTPUT_FORMAT_DOP = 1
internal const val LAZER_AUDIO_OUTPUT_FORMAT_NATIVE_DSD = 2
internal const val LAZER_AUDIO_FORMAT_SELECTION_UNKNOWN = 0
internal const val LAZER_AUDIO_FORMAT_SELECTION_SHARED_MIX = 1
internal const val LAZER_AUDIO_FORMAT_SELECTION_EXCLUSIVE_SOURCE = 2
internal const val LAZER_AUDIO_FORMAT_SELECTION_EXCLUSIVE_SAME_RATE_ALTERNATE = 3
internal const val LAZER_AUDIO_FORMAT_SELECTION_EXCLUSIVE_MONO_TO_STEREO = 4
internal const val LAZER_AUDIO_FORMAT_SELECTION_EXCLUSIVE_MIX_FALLBACK = 5
internal const val LAZER_AUDIO_FORMAT_SELECTION_EXCLUSIVE_COMMON_RATE_FALLBACK = 6
internal const val LAZER_AUDIO_FORMAT_SELECTION_DOP_CARRIER = 7
internal const val LAZER_AUDIO_FORMAT_SELECTION_NATIVE_DSD_U8 = 8
internal const val LAZER_AUDIO_FORMAT_SELECTION_NATIVE_DSD_U16_LE = 9
internal const val LAZER_AUDIO_FORMAT_SELECTION_NATIVE_DSD_U16_BE = 10
internal const val LAZER_AUDIO_FORMAT_SELECTION_NATIVE_DSD_U32_LE = 11
internal const val LAZER_AUDIO_FORMAT_SELECTION_NATIVE_DSD_U32_BE = 12

internal const val LAZER_AUDIO_STATE_IDLE = 0
internal const val LAZER_AUDIO_STATE_PREPARING = 1
internal const val LAZER_AUDIO_STATE_READY = 2
internal const val LAZER_AUDIO_STATE_PLAYING = 3
internal const val LAZER_AUDIO_STATE_PAUSED = 4
internal const val LAZER_AUDIO_STATE_STOPPED = 5
internal const val LAZER_AUDIO_STATE_FAILED = 6

internal const val LAZER_AUDIO_OK = 0
internal const val LAZER_AUDIO_ERROR_INVALID_ARGUMENT = -1
internal const val LAZER_AUDIO_ERROR_UNSUPPORTED = -2
internal const val LAZER_AUDIO_ERROR_NO_MEMORY = -3
internal const val LAZER_AUDIO_ERROR_SOURCE = -4
internal const val LAZER_AUDIO_ERROR_DECODE = -5
internal const val LAZER_AUDIO_ERROR_DEVICE = -6
internal const val LAZER_AUDIO_ERROR_CANCELLED = -7
internal const val LAZER_AUDIO_ERROR_STATE = -8
internal const val LAZER_AUDIO_QUEUE_ALREADY_ACTIVE = 1

internal const val LAZER_AUDIO_ERROR_BUFFER_TOO_SMALL = -9
internal const val LAZER_AUDIO_ERROR_INDEX_OUT_OF_RANGE = -10

internal const val LAZER_AUDIO_EVENT_READY = 0
internal const val LAZER_AUDIO_EVENT_NONE = -1
internal const val LAZER_AUDIO_EVENT_ENDED = 1
internal const val LAZER_AUDIO_EVENT_FAILED = 2
internal const val LAZER_AUDIO_EVENT_SEEK_COMPLETED = 3
internal const val LAZER_AUDIO_EVENT_DEVICE_LOST = 4
internal const val LAZER_AUDIO_EVENT_BUFFER_PROGRESS = 5
internal const val LAZER_AUDIO_EVENT_TRACK_CHANGED = 6

internal const val LAZER_AUDIO_RESAMPLE_NATIVE = 0
internal const val LAZER_AUDIO_RESAMPLE_FIXED_RATE = 1
internal const val LAZER_AUDIO_RESAMPLE_ALWAYS_FLOAT32 = 2

@FieldOrder("structSize", "flags")
internal class LazerAudioOutputDeviceInfo : Structure() {
    @JvmField var structSize: Int = 0
    @JvmField var flags: Int = 0

    override fun getFieldOrder(): List<String> = listOf("structSize", "flags")
}

internal const val LAZER_AUDIO_EQ_PEAK = 0
internal const val LAZER_AUDIO_EQ_LOW_SHELF = 1
internal const val LAZER_AUDIO_EQ_HIGH_SHELF = 2
internal const val LAZER_AUDIO_EQ_LOW_PASS = 3
internal const val LAZER_AUDIO_EQ_HIGH_PASS = 4
internal const val LAZER_AUDIO_EQ_NOTCH = 5
internal const val LAZER_AUDIO_EQ_ALL_PASS = 6

/** Loads the host and architecture-specific native bridge from an explicit path or app resources. */
internal object LazerAudioLoader {
    private const val OVERRIDE_PROPERTY = "lazer.audio.library"
    private val artifact = resolveLazerAudioNativeArtifact(
        System.getProperty("os.name").orEmpty(),
        System.getProperty("os.arch").orEmpty(),
    )

    @Volatile
    private var cached: LazerAudioLibrary? = null

    @Volatile
    private var attempted = false

    @Volatile
    private var loadFailure: Throwable? = null

    @Volatile
    private var availabilityFailure: Throwable? = null

    val library: LazerAudioLibrary?
        get() {
            if (!attempted) {
                synchronized(this) {
                    if (!attempted) {
                        val loadResult = runCatching {
                            val file = libraryFile() ?: return@runCatching null
                            Native.load(file.absolutePath, LazerAudioLibrary::class.java)
                        }
                        cached = loadResult.getOrNull()
                        loadFailure = loadResult.exceptionOrNull()
                        attempted = true
                    }
                }
            }
            return cached
        }

    val isAvailable: Boolean
        get() {
            val api = library ?: return false
            return try {
                val version = api.lazer_audio_abi_version()
                if (version == LAZER_AUDIO_ABI_VERSION) {
                    availabilityFailure = null
                    true
                } else {
                    availabilityFailure = IllegalStateException(
                        "The native library ABI version is $version; expected $LAZER_AUDIO_ABI_VERSION.",
                    )
                    false
                }
            } catch (error: Throwable) {
                availabilityFailure = error
                false
            }
        }

    val unavailableReason: String
        get() = (loadFailure ?: availabilityFailure)?.let { "${it.javaClass.simpleName}: ${it.message}" }
            ?: missingLibraryReason()

    private fun libraryFile(): File? {
        System.getProperty(OVERRIDE_PROPERTY)
            ?.let(::File)
            ?.takeIf(File::isFile)
            ?.let { return it }
        val resourcePath = artifact?.resourcePath ?: return null
        return WindowsMediaControlIcons.appResourceFile(resourcePath)
            ?: WindowsMediaControlIcons.classpathResourceFile(resourcePath)
    }

    private fun missingLibraryReason(): String {
        val overridePath = System.getProperty(OVERRIDE_PROPERTY)
        if (!overridePath.isNullOrBlank() && !File(overridePath).isFile) {
            return "native library override path does not exist: $overridePath"
        }
        val nativeArtifact = artifact
            ?: return "no native library artifact for ${System.getProperty("os.name")}/${System.getProperty("os.arch")}"
        return "native library resource '${nativeArtifact.resourcePath}' was not found"
    }
}
