package dev.naominet.lazer

/** A channel position in a decoded or rendered audio stream. */
enum class AudioChannel {
    FrontLeft,
    FrontRight,
    FrontCenter,
    LowFrequency,
    SideLeft,
    SideRight,
    BackLeft,
    BackRight,
    BackCenter,
    TopFrontLeft,
    TopFrontRight,
    TopBackLeft,
    TopBackRight,
    Unknown,
}

/** Explicit channel ordering; order is significant when describing an interleaved PCM stream. */
data class AudioChannelLayout(val channels: List<AudioChannel>) {
    init {
        require(channels.isNotEmpty()) { "An audio channel layout must contain at least one channel." }
        require(channels.distinct().size == channels.size) { "An audio channel layout cannot repeat a channel." }
    }

    val channelCount: Int get() = channels.size

    companion object {
        val Mono = AudioChannelLayout(listOf(AudioChannel.FrontCenter))
        val Stereo = AudioChannelLayout(listOf(AudioChannel.FrontLeft, AudioChannel.FrontRight))
    }
}

enum class AudioByteOrder {
    LittleEndian,
    BigEndian,
}

/** DSD bit rates are derived from the 44.1 kHz base rate, rather than treated as PCM sample rates. */
enum class DsdRate(val multiplier: Int) {
    Dsd64(64),
    Dsd128(128),
    Dsd256(256),
    Dsd512(512),
    Dsd1024(1_024),
    ;

    val bitsPerSecondPerChannel: Int get() = 44_100 * multiplier
}

/**
 * A source, decoded, or negotiated output format. PCM sample precision distinguishes valid signal
 * bits from the physical container. DSD has a bit rate and no PCM sample-rate field. DoP keeps its
 * DSD rate while describing the PCM carrier used to transport it.
 */
sealed interface AudioFormat {
    val channelLayout: AudioChannelLayout

    data class Pcm(
        val sampleRateHz: Int,
        val validBitsPerSample: Int,
        val containerBitsPerSample: Int,
        val byteOrder: AudioByteOrder,
        override val channelLayout: AudioChannelLayout,
        val isFloat: Boolean = false,
    ) : AudioFormat {
        init {
            require(sampleRateHz > 0) { "PCM sample rate must be positive." }
            require(validBitsPerSample in 1..containerBitsPerSample) {
                "Valid PCM bits must fit in the sample container."
            }
            require(containerBitsPerSample in setOf(8, 16, 24, 32, 64)) {
                "Unsupported PCM sample container width."
            }
            if (isFloat) {
                require(validBitsPerSample == containerBitsPerSample && containerBitsPerSample in setOf(32, 64)) {
                    "Floating-point PCM must use a full-width 32- or 64-bit container."
                }
            }
        }
    }

    data class Dsd(
        val rate: DsdRate,
        override val channelLayout: AudioChannelLayout,
    ) : AudioFormat

    data class DoP(
        val rate: DsdRate,
        val carrierSampleRateHz: Int,
        override val channelLayout: AudioChannelLayout,
        val byteOrder: AudioByteOrder = AudioByteOrder.LittleEndian,
    ) : AudioFormat {
        init {
            require(carrierSampleRateHz > 0) { "DoP carrier sample rate must be positive." }
            require(carrierSampleRateHz * 16 == rate.bitsPerSecondPerChannel) {
                "DoP carrier rate must carry 16 DSD bits per PCM frame."
            }
        }

        /** DoP uses 24-bit PCM words: 16 DSD bits plus the marker byte. */
        val containerBitsPerSample: Int get() = 24
    }
}

enum class AudioBackend {
    WasapiShared,
    WasapiExclusive,
    Asio,
    CoreAudio,
    Alsa,
    AndroidAudio,
    AndroidUsb,
    IosCoreAudio,
    NetworkRenderer,
    Unknown,
}

/** States whether a capability is known from probing or merely hoped for. */
enum class CapabilitySupport {
    Unknown,
    Unsupported,
    Supported,
}

enum class CapabilityEvidence {
    /** Reported by a backend or device descriptor; not verified with a played signal. */
    Probed,
    /** Observed to open and run through the output backend. */
    RuntimeTested,
    /** Previously runtime-tested and retained for this device identity. */
    CachedRuntimeTest,
}

enum class VolumeControlCapability {
    Unknown,
    None,
    Software,
    Hardware,
    HardwareAndSoftware,
}

enum class DsdOutputMode {
    Native,
    DoP,
    ConvertToPcm,
}

/** Capabilities describe candidates and their evidence; they do not claim the current path uses them. */
data class OutputCapabilities(
    val deviceId: String,
    val displayName: String,
    val backend: AudioBackend,
    val supportedFormats: List<AudioFormat> = emptyList(),
    val evidence: CapabilityEvidence = CapabilityEvidence.Probed,
    val exclusiveAccess: CapabilitySupport = CapabilitySupport.Unknown,
    val dsdOutputModes: Set<DsdOutputMode> = emptySet(),
    val volumeControl: VolumeControlCapability = VolumeControlCapability.Unknown,
) {
    init {
        require(deviceId.isNotBlank()) { "Output device ID must be stable and non-empty." }
        require(displayName.isNotBlank()) { "Output device name must be non-empty." }
    }
}

enum class DirectnessPreference {
    PreferBitPerfect,
    RequireBitPerfect,
    PreferCompatibility,
}

enum class DsdOutputPreference {
    PreferNative,
    PreferDoP,
    ConvertToPcm,
    FollowDevice,
}

enum class OutputFallbackPolicy {
    Fail,
    TryOtherBitDepth,
    ConvertToDeviceFormat,
    SystemDefault,
}

enum class PlaybackVolumePolicy {
    /** Keep digital samples unchanged; use device-side volume if available. */
    PreferHardware,
    /** Permit software attenuation when hardware volume is unavailable. */
    AllowSoftware,
    /** Keep unity software gain and do not change device volume. */
    FixedUnity,
}

data class PlaybackPolicy(
    val directness: DirectnessPreference = DirectnessPreference.PreferBitPerfect,
    val dsdOutput: DsdOutputPreference = DsdOutputPreference.FollowDevice,
    val fallback: OutputFallbackPolicy = OutputFallbackPolicy.ConvertToDeviceFormat,
    val bufferDurationMillis: Int = 100,
    val volume: PlaybackVolumePolicy = PlaybackVolumePolicy.PreferHardware,
) {
    init {
        require(bufferDurationMillis in 10..2_000) { "Buffer duration must be between 10 and 2,000 ms." }
        require(directness != DirectnessPreference.RequireBitPerfect || fallback == OutputFallbackPolicy.Fail) {
            "A policy that requires bit-perfect output cannot silently fall back."
        }
    }
}

enum class SignalPathStage {
    Source,
    Decoder,
    Dsp,
    FormatConversion,
    OutputBackend,
    Device,
}

enum class SignalPathStageStatus {
    Pending,
    Active,
    Bypassed,
    Converted,
    Failed,
    Unknown,
}

data class SignalPathStageSnapshot(
    val stage: SignalPathStage,
    val status: SignalPathStageStatus,
    val format: AudioFormat? = null,
    val detail: String? = null,
)

enum class OutputNegotiationStatus {
    NotAttempted,
    Accepted,
    Rejected,
    Failed,
    Unknown,
}

/** Identifies the backend branch that selected a successfully opened output format. */
enum class OutputFormatSelection {
    Unknown,
    SharedMix,
    ExclusiveSource,
    ExclusiveSameRateAlternate,
    ExclusiveMonoToStereo,
    ExclusiveMixFallback,
    ExclusiveCommonRateFallback,
    DoPCarrier,
    NativeDsdU8,
    NativeDsdU16Le,
    NativeDsdU16Be,
    NativeDsdU32Le,
    NativeDsdU32Be,
}

/** Backend negotiation is reported separately from a digital capture comparison. */
data class OutputNegotiationSnapshot(
    val status: OutputNegotiationStatus = OutputNegotiationStatus.NotAttempted,
    val requestedFormat: AudioFormat? = null,
    val negotiatedFormat: AudioFormat? = null,
    val backend: AudioBackend? = null,
    val formatSelection: OutputFormatSelection = OutputFormatSelection.Unknown,
    val detail: String? = null,
) {
    init {
        require(status != OutputNegotiationStatus.Accepted || negotiatedFormat != null) {
            "An accepted output negotiation must include the selected format."
        }
        require(status != OutputNegotiationStatus.NotAttempted || negotiatedFormat == null) {
            "An output format cannot be negotiated before an attempt."
        }
    }
}

enum class BitPerfectVerificationStatus {
    NotRun,
    Verified,
    Failed,
    Inconclusive,
}

enum class BitPerfectVerificationMethod {
    DigitalLoopbackSampleComparison,
    DsdMarkerOrModeCapture,
    Other,
}

data class BitPerfectVerificationSnapshot(
    val status: BitPerfectVerificationStatus = BitPerfectVerificationStatus.NotRun,
    val method: BitPerfectVerificationMethod? = null,
    val detail: String? = null,
) {
    init {
        require(status == BitPerfectVerificationStatus.NotRun || method != null) {
            "A completed bit-perfect verification must identify its evidence method."
        }
    }
}

enum class DirectPathStatus {
    NotRequested,
    Eligible,
    Negotiated,
    Rejected,
    Unknown,
}

enum class DirectPathReason {
    DigitalCaptureNotVerified,
    SourceIsLossy,
    DecoderChangedSamples,
    DspEnabled,
    SoftwareVolume,
    SoftwareVolumeUnknown,
    SampleRateConversion,
    BitDepthConversion,
    ChannelLayoutConversion,
    OutputFormatMismatch,
    ExclusiveModeUnavailable,
    UnsupportedEncoding,
    DsdConvertedToPcm,
    MixerBehaviorNotAdvertised,
    MixerPreferenceRejected,
    SourceFormatUnknown,
    DeviceOrBackendUnknown,
    Other,
}

/**
 * “Negotiated” means the backend accepted the requested format. It is not a bit-perfect claim;
 * only [BitPerfectVerificationSnapshot] can record evidence from a digital capture.
 */
data class DirectPathSnapshot(
    val status: DirectPathStatus = DirectPathStatus.Unknown,
    val reason: DirectPathReason? = DirectPathReason.DeviceOrBackendUnknown,
    val detail: String? = null,
) {
    init {
        require(status != DirectPathStatus.Rejected || reason != null) {
            "A rejected direct path must explain why it was rejected."
        }
        require(status != DirectPathStatus.Negotiated || reason == null || reason == DirectPathReason.DigitalCaptureNotVerified) {
            "A negotiated direct path cannot have a format-changing rejection reason."
        }
    }
}

/** Stream-held PCM telemetry measured inside the application output path. */
data class OutputTelemetrySnapshot(
    /** Highest sample peak seen during this stream; negative infinity represents silence. */
    val samplePeakDbfs: Double,
    /** Greatest attenuation during this stream, expressed as a non-positive dB value. */
    val limiterGainReductionDb: Double,
    /** Cumulative count for the stream, when integer PCM clipping is measured. */
    val clippedIntegerSampleCount: Long? = null,
) {
    init {
        require(!samplePeakDbfs.isNaN() && samplePeakDbfs != Double.POSITIVE_INFINITY) {
            "Sample peak must be a real dBFS value or negative infinity for silence."
        }
        require(limiterGainReductionDb.isFinite() && limiterGainReductionDb <= 0.0) {
            "Maximum limiter attenuation must be a finite, non-positive dB value."
        }
        require(clippedIntegerSampleCount == null || clippedIntegerSampleCount >= 0L) {
            "Clipped integer sample count cannot be negative."
        }
    }
}

/** Immutable, presentation-neutral view of the current source-to-device path. */
data class SignalPathSnapshot(
    val stages: List<SignalPathStageSnapshot> = emptyList(),
    val negotiation: OutputNegotiationSnapshot = OutputNegotiationSnapshot(),
    val verification: BitPerfectVerificationSnapshot = BitPerfectVerificationSnapshot(),
    val directPath: DirectPathSnapshot = DirectPathSnapshot(),
    val device: OutputCapabilities? = null,
    val outputTelemetry: OutputTelemetrySnapshot? = null,
)
