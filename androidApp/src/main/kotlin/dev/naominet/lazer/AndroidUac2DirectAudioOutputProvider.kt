package dev.naominet.lazer

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioFormat as PlatformAudioFormat
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import java.io.IOException

/**
 * Explicit Android USB UAC2 PCM output. It never falls back to AudioTrack: unsupported formats,
 * missing permissions, or devices without a unique descriptor-backed alternate fail creation.
 * The temporary descriptor-read connection closes before the configured playback session opens.
 */
@OptIn(UnstableApi::class)
internal class AndroidUac2DirectAudioOutputProvider(
    context: Context,
    private val deviceId: String,
    private val onOutputConfigured: (AudioOutputProvider.OutputConfig, String, Int) -> Unit = { _, _, _ -> },
    private val onOutputFailed: (Throwable) -> Unit = {},
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val delegate = AudioTrackAudioOutputProvider.Builder(appContext).build()
    private val lifecycle = AndroidUac2DirectOutputLifecycle<AndroidUac2NativeIsochronousTransport>(
        closeTransport = { transport, detached ->
            if (detached) transport.onDeviceDetached() else transport.close()
        },
    )
    private val outputProvider = object : ForwardingAudioOutputProvider(delegate) {
        override fun getFormatSupport(
            config: AudioOutputProvider.FormatConfig,
        ): AudioOutputProvider.FormatSupport {
            if (!lifecycle.isAvailable ||
                config.format.channelCount != ANDROID_UAC2_DIRECT_CHANNEL_COUNT
            ) {
                return AudioOutputProvider.FormatSupport.UNSUPPORTED
            }
            return androidUac2DirectPcmFormatSupport(config)
        }

        override fun getOutputConfig(
            config: AudioOutputProvider.FormatConfig,
        ): AudioOutputProvider.OutputConfig = createOutputConfig(config)

        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput = try {
            lifecycle.createOutput(
                createTransport = { createTransport(config) },
                createAudioOutput = { transport ->
                    AndroidUac2Media3AudioOutput(config, transport).also {
                        onOutputConfigured(
                            config,
                            requireNotNull(usbManager.deviceList[deviceId]).productName?.toString().orEmpty(),
                            transport.bytesPerSample * 8,
                        )
                    }
                },
            )
        } catch (error: Throwable) {
            runCatching { onOutputFailed(error) }
            throw AudioOutputProvider.InitializationException(error)
        }

        override fun release() {
            lifecycle.closeProvider { super.release() }
        }
    }

    val provider: AudioOutputProvider get() = outputProvider

    /** Close native callbacks before the service releases the Media3 player. */
    fun onDeviceDetached(detachedDeviceId: String) {
        if (detachedDeviceId != deviceId) return
        lifecycle.onDeviceDetached()
    }

    override fun close() {
        lifecycle.closeProvider { delegate.release() }
    }

    private fun createOutputConfig(
        config: AudioOutputProvider.FormatConfig,
    ): AudioOutputProvider.OutputConfig {
        val format = config.format
        require(format.sampleMimeType == MimeTypes.AUDIO_RAW) { "USB direct output only accepts decoded PCM" }
        require(config.format.channelCount == ANDROID_UAC2_DIRECT_CHANNEL_COUNT) {
            "USB direct output currently supports stereo PCM only"
        }
        require(!config.enablePlaybackParameters && !config.enableOffload && !config.enableTunneling) {
            "USB direct output does not support playback parameters, offload, or tunneling"
        }
        require(config.format.sampleRate in 8_000..ANDROID_UAC2_DIRECT_MAX_SAMPLE_RATE_HZ) {
            "PCM sample rate is outside the USB direct-output range"
        }
        require(format.pcmEncoding in ANDROID_UAC2_DIRECT_PCM_BYTES_BY_ENCODING) {
            "USB direct output supports integer PCM or Media3 high-resolution float PCM"
        }

        val encoding = format.pcmEncoding
        val sampleRate = format.sampleRate
        val bufferSizeBytes = config.preferredBufferSize.takeIf { it > 0 }
            ?: run {
                val frameBytes = ANDROID_UAC2_DIRECT_PCM_BYTES_BY_ENCODING.getValue(encoding) *
                    ANDROID_UAC2_DIRECT_CHANNEL_COUNT
                (sampleRate.toLong() * frameBytes / 5L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
        return AudioOutputProvider.OutputConfig.Builder()
            .setEncoding(encoding)
            .setSampleRate(sampleRate)
            .setChannelMask(PlatformAudioFormat.CHANNEL_OUT_STEREO)
            .setBufferSize(bufferSizeBytes)
            .setIsOffload(false)
            .setIsTunneling(false)
            .setAudioAttributes(config.audioAttributes)
            .setAudioSessionId(config.audioSessionId)
            .setVirtualDeviceId(config.virtualDeviceId)
            .setUsePlaybackParameters(false)
            .setUseOffloadGapless(false)
            .build()
    }

    private fun createTransport(
        config: AudioOutputProvider.OutputConfig,
    ): AndroidUac2NativeIsochronousTransport {
        require(!config.isOffload && !config.isTunneling && !config.usePlaybackParameters && !config.useOffloadGapless) {
            "USB direct output received an unsupported Media3 output configuration"
        }
        require(config.encoding in ANDROID_UAC2_DIRECT_PCM_BYTES_BY_ENCODING) {
            "Media3 selected an unsupported PCM encoding"
        }
        require(config.sampleRate in 8_000..ANDROID_UAC2_DIRECT_MAX_SAMPLE_RATE_HZ) {
            "Media3 selected a sample rate outside the USB direct-output range"
        }
        require(config.channelMask == PlatformAudioFormat.CHANNEL_OUT_STEREO) {
            "Media3 selected a channel layout other than stereo"
        }

        val device = usbManager.deviceList[deviceId]
            ?: throw IOException("Selected USB device is no longer attached")
        check(usbManager.hasPermission(device)) { "USB permission is no longer granted" }
        val descriptors = readRawDescriptors(device)
        val alternates = parseAndroidUac2PlaybackAltSettings(descriptors)
            ?: throw IOException("Selected USB device has invalid or unsupported UAC2 descriptors")
        val candidateBitDepths = when (config.encoding) {
            C.ENCODING_PCM_16BIT -> listOf(16)
            C.ENCODING_PCM_24BIT -> listOf(24)
            C.ENCODING_PCM_32BIT -> listOf(32)
            C.ENCODING_PCM_FLOAT -> listOf(32, 24, 16)
            else -> error("Unsupported PCM encoding")
        }
        val plan = candidateBitDepths.firstNotNullOfOrNull { bitDepth ->
            val bytesPerSample = bitDepth / 8
            val matchingAlternates = alternates.filter { alternate ->
                alternate.channelCount == Integer.bitCount(config.channelMask) &&
                    alternate.channelConfig == 0x0000_0003L &&
                    alternate.subslotSizeBytes == bytesPerSample &&
                    alternate.validBitResolution == bitDepth &&
                    (alternate.dataEndpoint.synchronizationType != ANDROID_UAC2_ASYNC_SYNC ||
                        alternate.feedbackEndpoint != null)
            }
            if (matchingAlternates.size > 1) {
                throw IOException("The selected device has ambiguous $bitDepth-bit UAC2 alternates")
            }
            resolveAndroidUac2PcmCandidatePlan(
                alternateSettings = matchingAlternates,
                sampleRateHz = config.sampleRate,
                channelCount = Integer.bitCount(config.channelMask),
                bytesPerSample = bytesPerSample,
                validBitResolution = bitDepth,
            )
        } ?: throw IOException(
            "The selected device has no unique stereo UAC2 alternate for ${config.sampleRate} Hz PCM",
        )

        val session = AndroidUac2PlaybackSession(
            device = device,
            plan = plan,
            gateway = AndroidFrameworkUac2PlaybackSessionGateway(usbManager),
            volumeControl = findAndroidUacPlaybackVolumeControl(descriptors),
        )
        session.openAndConfigure()
        return AndroidUac2NativeIsochronousTransport(session, plan)
    }

    private fun readRawDescriptors(device: UsbDevice): ByteArray {
        check(usbManager.hasPermission(device)) { "USB permission is no longer granted" }
        val connection: UsbDeviceConnection = usbManager.openDevice(device)
            ?: throw IOException("Selected USB device could not be opened to read descriptors")
        return try {
            connection.rawDescriptors
        } finally {
            connection.close()
        }
    }

    private companion object {
        const val ANDROID_UAC2_DIRECT_CHANNEL_COUNT = 2
        const val ANDROID_UAC2_DIRECT_MAX_SAMPLE_RATE_HZ = 768_000
        val ANDROID_UAC2_DIRECT_PCM_BYTES_BY_ENCODING = mapOf(
            C.ENCODING_PCM_16BIT to 2,
            C.ENCODING_PCM_24BIT to 3,
            C.ENCODING_PCM_32BIT to 4,
            C.ENCODING_PCM_FLOAT to 4,
        )
    }
}

private const val ANDROID_UAC2_ASYNC_SYNC = 1

private fun androidUac2DirectPcmFormatSupport(
    config: AudioOutputProvider.FormatConfig,
): AudioOutputProvider.FormatSupport {
    val format = config.format
    val floatHighResolutionPcm = format.sampleMimeType == MimeTypes.AUDIO_RAW &&
        format.pcmEncoding == C.ENCODING_PCM_FLOAT && config.enableHighResolutionPcmOutput &&
        format.sampleRate in 8_000..768_000 && format.channelCount == 2 &&
        !config.enablePlaybackParameters && !config.enableOffload && !config.enableTunneling
    if (!floatHighResolutionPcm) return androidUac2PcmFormatSupport(config)
    return AudioOutputProvider.FormatSupport.Builder()
        .setFormatSupportLevel(AudioOutputProvider.FORMAT_SUPPORTED_DIRECTLY)
        .build()
}
