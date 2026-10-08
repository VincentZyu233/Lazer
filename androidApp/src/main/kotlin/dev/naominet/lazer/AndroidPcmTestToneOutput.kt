package dev.naominet.lazer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/** Framework-independent description used to compare an AudioTrack request with a HAL report. */
internal data class AndroidPcmMixerFormat(
    val sampleRateHz: Int,
    val encoding: Int,
    val channelMask: Int,
    val channelIndexMask: Int,
)

internal data class AndroidPcmMixerCandidate(
    val format: AndroidPcmMixerFormat,
    val behavior: Int,
)

internal fun exactMixerCandidate(
    candidates: List<AndroidPcmMixerCandidate>,
    requested: AndroidPcmMixerFormat,
): AndroidPcmMixerCandidate? = candidates.firstOrNull { it.format == requested }

internal fun exactBitPerfectMixerCandidate(
    candidates: List<AndroidPcmMixerCandidate>,
    requested: AndroidPcmMixerFormat,
    bitPerfectBehavior: Int,
): AndroidPcmMixerCandidate? = candidates.firstOrNull {
    it.format == requested && it.behavior == bitPerfectBehavior
}

/** Maps test-tone HAL reports into the same exact-format route policy used by normal playback. */
internal fun androidPcmTestToneRouteCandidate(
    deviceId: Int,
    stableIdentity: String?,
    candidates: List<AndroidPcmMixerCandidate>,
    requested: AndroidPcmMixerFormat,
    bitPerfectBehavior: Int?,
): AndroidUsbRouteCandidate {
    val exact = exactMixerCandidate(candidates, requested) != null
    val bitPerfect = bitPerfectBehavior?.let {
        exactBitPerfectMixerCandidate(candidates, requested, it) != null
    }
    return AndroidUsbRouteCandidate(
        deviceId = deviceId,
        stableIdentity = stableIdentity,
        exactFormat = bitPerfectBehavior?.let { exact },
        bitPerfectBehavior = bitPerfect,
    )
}

internal fun pcmBitDepthForAndroidEncoding(
    encoding: Int,
    pcm16: Int,
    packedPcm24: Int,
    pcm32: Int,
): Int? = when (encoding) {
    pcm16 -> 16
    packedPcm24 -> 24
    pcm32 -> 32
    else -> null
}

/** A short PCM-only output probe. It deliberately does not claim DAC readback or loopback proof. */
internal class AndroidPcmTestToneOutput(context: Context) : AutoCloseable {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private val closed = AtomicBoolean(false)
    private val mixerPreferenceLock = Any()

    @Volatile private var track: AudioTrack? = null
    private var selectedDevice: AudioDeviceInfo? = null
    @Volatile var selectedUsbDeviceId: Int? = null
        private set
    private var oldMixerPreference: AudioMixerAttributes? = null
    private var appliedMixerPreference: AudioMixerAttributes? = null
    private var mixerPreferenceDevice: AudioDeviceInfo? = null
    private var changedMixerPreference = false

    data class Result(
        val route: LazerPcmTestToneRoute,
        val mixerPreference: LazerMixerPreferenceStatus,
        val mixerReportsBitPerfectBehavior: Boolean?,
    )

    /** Writes one tone, waits for its final frame to leave AudioTrack, then returns the route. */
    suspend fun play(
        format: LazerPcmTestFormat,
        onPlaying: (route: LazerPcmTestToneRoute, mixerPreference: LazerMixerPreferenceStatus, bitPerfect: Boolean?) -> Unit,
    ): Result {
        try {
            check(!closed.get()) { "PCM test output has already stopped" }
            val requested = audioFormat(format)
            val requestedKey = requested.toMixerFormat()
            val usbDevices = outputDevices().filter(::isUsbAudioOutput)
            val candidatesByDevice = if (Build.VERSION.SDK_INT >= 34) {
                usbDevices.associateWith { device ->
                    runCatching { queryMixerCandidates(device) }.getOrDefault(emptyList())
                }
            } else {
                emptyMap()
            }
            val routeCandidates = usbDevices.map { device ->
                androidPcmTestToneRouteCandidate(
                    deviceId = device.id,
                    stableIdentity = stableUsbAudioTargetIdentity(
                        type = device.type,
                        address = usbAudioAddressOrEmpty(device),
                        productName = device.productName.toString(),
                    ),
                    candidates = candidatesByDevice[device].orEmpty(),
                    requested = requestedKey,
                    bitPerfectBehavior = if (Build.VERSION.SDK_INT >= 34) {
                        AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT
                    } else {
                        null
                    },
                )
            }
            val savedTargetIdentity = AndroidUsbAudioTargetStore(appContext).selectedIdentity
            val routeChoice = chooseAndroidUsbRoute(routeCandidates, savedTargetIdentity)
            selectedDevice = routeChoice.deviceId?.let { selectedId ->
                usbDevices.singleOrNull { it.id == selectedId }
            }
            selectedUsbDeviceId = selectedDevice?.id

            var preferenceStatus = when {
                Build.VERSION.SDK_INT < 34 || selectedDevice == null -> LazerMixerPreferenceStatus.NotAvailable
                else -> LazerMixerPreferenceStatus.NoExactMatch
            }
            var reportsBitPerfect: Boolean? = null
            if (Build.VERSION.SDK_INT >= 34 && selectedDevice != null) {
                val device = selectedDevice!!
                val candidates = candidatesByDevice[device].orEmpty()
                val exact = exactMixerCandidate(candidates, requestedKey)
                val bitPerfectCandidate = exactBitPerfectMixerCandidate(
                    candidates,
                    requestedKey,
                    AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT,
                )
                reportsBitPerfect = bitPerfectCandidate != null
                when {
                    bitPerfectCandidate != null -> {
                        val desired = runCatching {
                            AudioMixerAttributes.Builder(requested)
                                .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                                .build()
                        }.getOrNull()
                        preferenceStatus = when {
                            desired == null -> LazerMixerPreferenceStatus.Rejected
                            else -> when (applyMixerPreference(device, desired)) {
                                is MixerPreferenceApplyResult.Applied -> LazerMixerPreferenceStatus.Accepted
                                MixerPreferenceApplyResult.PreviousPreferenceUnavailable ->
                                    LazerMixerPreferenceStatus.NotAvailable
                                MixerPreferenceApplyResult.Rejected -> LazerMixerPreferenceStatus.Rejected
                            }
                        }
                    }
                    exact != null -> preferenceStatus = LazerMixerPreferenceStatus.NotBitPerfect
                    else -> preferenceStatus = LazerMixerPreferenceStatus.NoExactMatch
                }
            }

            val minBufferBytes = AudioTrack.getMinBufferSize(
                format.sampleRateHz,
                AudioFormat.CHANNEL_OUT_STEREO,
                requested.encoding,
            )
            check(minBufferBytes > 0) { "AudioTrack rejected the requested PCM format ($minBufferBytes)" }
            val bytesPerFrame = 2 * (format.bitDepth / 8)
            val bufferBytes = max(minBufferBytes, format.sampleRateHz / 5 * bytesPerFrame)
            val newTrack = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes)
                .setAudioFormat(requested)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferBytes)
                .build()
            check(newTrack.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack could not initialize" }
            track = newTrack
            selectedDevice?.let { device ->
                val accepted = runCatching { newTrack.setPreferredDevice(device) }.getOrDefault(false)
                if (!accepted) {
                    // The USB target may disappear between enumeration and track creation. Keep the
                    // test tone useful on Android's default route, without selecting another DAC.
                    selectedDevice = null
                    selectedUsbDeviceId = null
                    restoreMixerPreference()
                    preferenceStatus = LazerMixerPreferenceStatus.NotAvailable
                    reportsBitPerfect = null
                }
            }

            newTrack.play()
            val primeFrames = max(1, format.sampleRateHz / 20)
            val silence = ByteArray(primeFrames * bytesPerFrame)
            writeFully(newTrack, silence)
            var routedDevice = newTrack.routedDevice
            if (selectedDevice != null) {
                repeat(20) {
                    if (routedDevice?.id != selectedDevice?.id) {
                        delay(15)
                        routedDevice = newTrack.routedDevice
                    }
                }
                check(routedDevice?.id == selectedDevice?.id) {
                    "The requested USB output did not become the active AudioTrack route"
                }
            }
            val route = routeFor(routedDevice)
            onPlaying(route, preferenceStatus, reportsBitPerfect)

            val tone = LazerPcmTestTone.createPcm(format.sampleRateHz, channels = 2, bitDepth = format.bitDepth)
            writeFully(newTrack, tone)
            val targetFrames = primeFrames + tone.size / bytesPerFrame
            val deadlineMillis = System.nanoTime() / 1_000_000 + LazerPcmTestTone.DURATION_MILLISECONDS + 3_000L
            while (playbackFrames(newTrack) < targetFrames) {
                check(!closed.get()) { "PCM test output was stopped" }
                check(System.nanoTime() / 1_000_000 < deadlineMillis) { "AudioTrack did not drain the test tone" }
                delay(8)
            }
            routedDevice = newTrack.routedDevice
            check(selectedDevice == null || routedDevice?.id == selectedDevice?.id) {
                "The active output route changed while the test tone was playing"
            }
            return Result(routeFor(routedDevice), preferenceStatus, reportsBitPerfect)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } finally {
            close()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        track?.let { current ->
            runCatching { if (current.playState == AudioTrack.PLAYSTATE_PLAYING) current.stop() }
            runCatching { current.release() }
        }
        track = null
        selectedUsbDeviceId = null
        restoreMixerPreference()
    }

    private fun restoreMixerPreference() {
        if (Build.VERSION.SDK_INT < 34) return
        synchronized(mixerPreferenceLock) {
            if (!changedMixerPreference) return
            val device = mixerPreferenceDevice ?: return
            val applied = appliedMixerPreference ?: return
            val result = restoreMixerPreferenceWithRetry(
                readCurrentPreference = {
                    runCatching { audioManager.getPreferredMixerAttributes(audioAttributes, device) }
                },
                appliedPreference = applied,
                previousPreference = oldMixerPreference,
                samePreference = { current, expected -> current?.hasSameValuesAs(expected) == true },
                clearPreference = {
                    audioManager.clearPreferredMixerAttributes(audioAttributes, device)
                },
                setPreviousPreference = { previous ->
                    audioManager.setPreferredMixerAttributes(audioAttributes, device, previous)
                },
            )
            when (result) {
                MixerPreferenceRestoreResult.RESTORED -> clearMixerPreferenceLease()
                MixerPreferenceRestoreResult.CHANGED_EXTERNALLY -> {
                    Log.i(PCM_TEST_TONE_TAG,
                        "Mixer preference for device ${device.id} changed externally; leaving it in place")
                    clearMixerPreferenceLease()
                }
                MixerPreferenceRestoreResult.UNAVAILABLE -> Log.w(
                    PCM_TEST_TONE_TAG,
                    "Could not read mixer preference for device ${device.id} after retry",
                )
                MixerPreferenceRestoreResult.REJECTED -> Log.e(
                    PCM_TEST_TONE_TAG,
                    "Could not restore mixer preference for device ${device.id} after retry",
                )
            }
        }
    }

    @RequiresApi(34)
    private fun applyMixerPreference(
        device: AudioDeviceInfo,
        desired: AudioMixerAttributes,
    ): MixerPreferenceApplyResult<AudioMixerAttributes> = synchronized(mixerPreferenceLock) {
        if (closed.get()) return@synchronized MixerPreferenceApplyResult.Rejected
        val result = captureAndApplyMixerPreference(
            readPreviousPreference = {
                audioManager.getPreferredMixerAttributes(audioAttributes, device)
            },
            applyDesiredPreference = {
                audioManager.setPreferredMixerAttributes(audioAttributes, device, desired)
            },
        )
        if (result is MixerPreferenceApplyResult.Applied) {
            oldMixerPreference = result.previousPreference
            appliedMixerPreference = desired
            mixerPreferenceDevice = device
            changedMixerPreference = true
        }
        result
    }

    private fun clearMixerPreferenceLease() {
        changedMixerPreference = false
        oldMixerPreference = null
        appliedMixerPreference = null
        mixerPreferenceDevice = null
    }

    @RequiresApi(34)
    private fun AudioMixerAttributes.hasSameValuesAs(other: AudioMixerAttributes): Boolean =
        mixerBehavior == other.mixerBehavior && format.toMixerFormat() == other.format.toMixerFormat()

    private suspend fun writeFully(output: AudioTrack, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            check(!closed.get()) { "PCM test output was stopped" }
            val count = output.write(
                bytes,
                offset,
                min(bytes.size - offset, WRITE_CHUNK_BYTES),
                AudioTrack.WRITE_NON_BLOCKING,
            )
            when {
                count < 0 -> error("AudioTrack write failed ($count)")
                count == 0 -> delay(2)
                else -> offset += count
            }
        }
    }

    private fun outputDevices(): List<AudioDeviceInfo> =
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

    private fun audioFormat(format: LazerPcmTestFormat): AudioFormat = AudioFormat.Builder()
        .setSampleRate(format.sampleRateHz)
        .setEncoding(encodingForBitDepth(format.bitDepth))
        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
        .build()

    private fun encodingForBitDepth(bitDepth: Int): Int = when (bitDepth) {
        16 -> AudioFormat.ENCODING_PCM_16BIT
        24 -> {
            check(Build.VERSION.SDK_INT >= 31) { "Packed 24-bit PCM needs Android 12 or later" }
            AudioFormat.ENCODING_PCM_24BIT_PACKED
        }
        32 -> {
            check(Build.VERSION.SDK_INT >= 31) { "32-bit integer PCM needs Android 12 or later" }
            AudioFormat.ENCODING_PCM_32BIT
        }
        else -> error("Unsupported PCM test bit depth: $bitDepth")
    }

    private fun AudioFormat.toMixerFormat() = AndroidPcmMixerFormat(
        sampleRateHz = sampleRate,
        encoding = encoding,
        channelMask = channelMask,
        channelIndexMask = channelIndexMask,
    )

    @RequiresApi(34)
    private fun queryMixerCandidates(device: AudioDeviceInfo): List<AndroidPcmMixerCandidate> =
        audioManager.getSupportedMixerAttributes(device).map { attributes ->
            AndroidPcmMixerCandidate(
                format = attributes.format.toMixerFormat(),
                behavior = attributes.mixerBehavior,
            )
        }

    private fun isUsbAudioOutput(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET

    private fun routeFor(device: AudioDeviceInfo?): LazerPcmTestToneRoute = when (device?.type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> LazerPcmTestToneRoute.Usb
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> LazerPcmTestToneRoute.Wired
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> LazerPcmTestToneRoute.Bluetooth
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> LazerPcmTestToneRoute.BuiltIn
        AudioDeviceInfo.TYPE_HDMI -> LazerPcmTestToneRoute.Hdmi
        null -> LazerPcmTestToneRoute.Unknown
        else -> LazerPcmTestToneRoute.Other
    }

    private fun playbackFrames(output: AudioTrack): Long = output.playbackHeadPosition.toLong() and 0xffffffffL

    companion object {
        private const val PCM_TEST_TONE_TAG = "LazerPcmTestTone"
        private const val WRITE_CHUNK_BYTES = 32 * 1024

        private val fallbackFormats = listOf(
            LazerPcmTestFormat(44_100, 16),
            LazerPcmTestFormat(48_000, 16),
        )

        fun queryCapabilities(context: Context): LazerPcmTestToneSnapshot {
            val manager = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val usbDevices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { device ->
                device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
            val advertised = if (Build.VERSION.SDK_INT >= 34) {
                usbDevices.flatMap { device ->
                    runCatching { queryMixerCandidates(manager, device) }.getOrDefault(emptyList())
                }.mapNotNull(::toTestFormat).distinct().sortedWith(formatComparator)
            } else {
                emptyList()
            }
            return LazerPcmTestToneSnapshot(
                availableFormats = advertised.ifEmpty { fallbackFormats },
                connectedUsbOutputs = usbDevices.size,
            )
        }

        private val formatComparator = compareBy<LazerPcmTestFormat>({ it.sampleRateHz }, { it.bitDepth })

        private fun toTestFormat(candidate: AndroidPcmMixerCandidate): LazerPcmTestFormat? {
            if (candidate.format.channelMask != AudioFormat.CHANNEL_OUT_STEREO || candidate.format.channelIndexMask != 0) {
                return null
            }
            val bitDepth = pcmBitDepthForAndroidEncoding(
                candidate.format.encoding,
                AudioFormat.ENCODING_PCM_16BIT,
                AudioFormat.ENCODING_PCM_24BIT_PACKED,
                AudioFormat.ENCODING_PCM_32BIT,
            ) ?: return null
            return runCatching { LazerPcmTestFormat(candidate.format.sampleRateHz, bitDepth) }.getOrNull()
        }

        @RequiresApi(34)
        private fun queryMixerCandidates(
            manager: AudioManager,
            device: AudioDeviceInfo,
        ): List<AndroidPcmMixerCandidate> = manager.getSupportedMixerAttributes(device).map { attributes ->
            AndroidPcmMixerCandidate(
                format = AndroidPcmMixerFormat(
                    sampleRateHz = attributes.format.sampleRate,
                    encoding = attributes.format.encoding,
                    channelMask = attributes.format.channelMask,
                    channelIndexMask = attributes.format.channelIndexMask,
                ),
                behavior = attributes.mixerBehavior,
            )
        }
    }
}
