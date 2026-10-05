package dev.naominet.lazer

import android.content.Context
import android.media.AudioAttributes as PlatformAudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.media3.common.C
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.ForwardingAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

private const val ANDROID_MEDIA3_OUTPUT_TAG = "AndroidMedia3Output"

/** Format as configured on Android's AudioTrack, not a report from the DAC. */
internal data class AndroidMedia3TrackFormat(
    val sampleRateHz: Int,
    val encoding: Int,
    val channelMask: Int,
    val channelIndexMask: Int,
)

internal data class AndroidMedia3OutputDevice(
    val id: Int,
    val type: Int,
    val productName: String,
)

/**
 * Evidence observed at the Android AudioTrack boundary. In particular, [actualTrackFormat] and
 * [routedDevice] do not prove that a DAC locked to that format or received unchanged PCM.
 */
internal data class AndroidMedia3OutputSnapshot(
    val requestedTrackFormat: AndroidMedia3TrackFormat,
    val actualTrackFormat: AndroidMedia3TrackFormat?,
    val audioSessionId: Int,
    val offload: Boolean,
    val tunneling: Boolean,
    val routedDevice: AndroidMedia3OutputDevice?,
    val selectedUsbDeviceId: Int?,
    val usbRouteSelection: AndroidMedia3UsbRouteSelection,
    val preferredUsbDeviceAccepted: Boolean?,
    val audioTrackFormatMatchesRequested: Boolean?,
    val mixerAdvertisesExactFormat: Boolean?,
    val mixerAdvertisesBitPerfectBehavior: Boolean?,
    val mixerPreferenceAccepted: Boolean?,
    val appDspMayModifySamples: Boolean,
    val replayGainAppliedDb: Double?,
    val directPath: DirectPathSnapshot,
    val outputDataFormat: PlaybackAudioOutputDataSnapshot,
)

/**
 * Media3 AudioTrack provider with conservative USB routing and API 34 mixer-attribute requests.
 * The helper owns the preference override it makes and restores the prior value when closed.
 *
 * Pass [provider] to ExoPlayer.Builder.setAudioOutputProvider. Release ExoPlayer first, then call
 * close(); both this method and Media3's provider release are idempotent.
 */
internal class AndroidMedia3AudioOutputProvider(
    context: Context,
    initialEqualizer: LazerEqualizerState = LazerEqualizerState(),
    initialReplayGainDb: Double = 0.0,
    private val onOutputChanged: (AndroidMedia3OutputSnapshot) -> Unit = {},
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val releaseStarted = AtomicBoolean(false)
    private val snapshotPublicationGate = AndroidMedia3SnapshotPublicationGate()
    private val pcmEqualizer = AndroidPcmEqualizer(initialEqualizer, initialReplayGainDb)
    @Volatile private var selectedUsbTargetIdentity: String? = AndroidUsbAudioTargetStore(appContext).selectedIdentity
    private val mixerOverrides = linkedMapOf<MixerPreferenceKey, MixerPreferenceOverride>()
    private val externallyModifiedMixerPreferences = mutableSetOf<MixerPreferenceKey>()
    private val pendingRouteDecision = ThreadLocal<UsbRouteDecision?>()

    @Volatile
    var latestSnapshot: AndroidMedia3OutputSnapshot? = null
        private set

    private var routeListenerTrack: AudioTrack? = null
    private var routeListener: AudioRouting.OnRoutingChangedListener? = null
    private var routeListenerConfig: AudioOutputProvider.OutputConfig? = null

    private val audioTrackProvider = AudioTrackAudioOutputProvider.Builder(appContext)
        .setAudioTrackBuilderModifier { _, config -> modifyBuilder(config) }
        .build()

    private val forwardingProvider = object : ForwardingAudioOutputProvider(audioTrackProvider) {
        override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput {
            pendingRouteDecision.remove()
            try {
                val output = super.getAudioOutput(config)
                val decision = pendingRouteDecision.get()
                val audioTrackOutput = output as? AudioTrackAudioOutput
                val track = audioTrackOutput?.audioTrack
                if (track != null) observeTrack(track, config, decision)
                val channelCount = Integer.bitCount(config.channelMask)
                return if (
                    isPcmEqualizerSupportedEncoding(config.encoding) &&
                    !config.isOffload &&
                    !config.isTunneling &&
                    channelCount in 1..8
                ) {
                    AndroidEqualizingAudioOutput(
                        output,
                        pcmEqualizer,
                        config.sampleRate,
                        channelCount,
                        config.encoding,
                    )
                } else {
                    output
                }
            } finally {
                pendingRouteDecision.remove()
            }
        }

        override fun release() {
            if (!releaseStarted.compareAndSet(false, true)) return
            try {
                super.release()
            } finally {
                releaseOwnedResources()
            }
        }
    }

    val provider: AudioOutputProvider
        get() = forwardingProvider

    fun updateEqualizer(state: LazerEqualizerState) {
        pcmEqualizer.update(state)
        if (closed.get()) return
        val active = synchronized(lock) { routeListenerTrack to routeListenerConfig }
        val track = active.first ?: return
        val config = active.second ?: return
        val requested = config.toPlatformTrackFormat() ?: return
        val decision = chooseUsbRoute(requested)
        publishTrackSnapshot(track, config, decision, latestSnapshot?.preferredUsbDeviceAccepted)
    }

    fun updateReplayGainDb(gainDb: Double) {
        pcmEqualizer.updateReplayGainDb(gainDb)
        if (closed.get()) return
        val active = synchronized(lock) { routeListenerTrack to routeListenerConfig }
        val track = active.first ?: return
        val config = active.second ?: return
        val requested = config.toPlatformTrackFormat() ?: return
        val decision = chooseUsbRoute(requested)
        publishTrackSnapshot(track, config, decision, latestSnapshot?.preferredUsbDeviceAccepted)
    }

    /** Apply a saved target change to the active AudioTrack as well as any future track. */
    fun updateUsbAudioTarget(identity: String?) {
        if (closed.get()) return
        selectedUsbTargetIdentity = identity
        val active = synchronized(lock) { routeListenerTrack to routeListenerConfig }
        val track = active.first ?: return
        val config = active.second ?: return
        val requested = config.toPlatformTrackFormat() ?: return
        val decision = chooseUsbRoute(requested)
        requestBitPerfectMixerPreference(config, requested, decision)
        val preferredDeviceAccepted = setPreferredRouteDevice(track, decision)
        publishTrackSnapshot(track, config, decision, preferredDeviceAccepted)
    }

    private fun modifyBuilder(config: AudioOutputProvider.OutputConfig) {
        if (closed.get()) return
        val requestedFormat = config.toPlatformTrackFormat() ?: return
        val decision = chooseUsbRoute(requestedFormat)
        pendingRouteDecision.set(decision)
        requestBitPerfectMixerPreference(config, requestedFormat, decision)
    }

    private fun observeTrack(
        track: AudioTrack,
        config: AudioOutputProvider.OutputConfig,
        builderDecision: UsbRouteDecision?,
    ) {
        val decision = builderDecision ?: chooseUsbRoute(config.toPlatformTrackFormat())
        val preferredDeviceAccepted = setPreferredRouteDevice(track, decision)
        synchronized(lock) {
            if (closed.get()) return
            removeRouteListenerLocked()
            routeListenerTrack = track
            routeListenerConfig = config
            val listener = AudioRouting.OnRoutingChangedListener { changedTrack ->
                if (changedTrack === track) {
                    refreshTrackRouting(track, config)
                }
            }
            routeListener = listener
            runCatching { track.addOnRoutingChangedListener(listener, mainHandler) }
        }
        publishTrackSnapshot(track, config, decision, preferredDeviceAccepted)
    }

    /** Refresh both the selected target and its HAL evidence when Android changes the live route. */
    private fun refreshTrackRouting(track: AudioTrack, config: AudioOutputProvider.OutputConfig) {
        if (closed.get()) return
        val requested = config.toPlatformTrackFormat()
        val decision = requested?.let(::chooseUsbRoute)
        if (requested != null && decision != null) {
            requestBitPerfectMixerPreference(config, requested, decision)
        }
        val preferredDeviceAccepted = decision?.let { setPreferredRouteDevice(track, it) }
        publishTrackSnapshot(track, config, decision, preferredDeviceAccepted)
    }

    private fun setPreferredRouteDevice(
        track: AudioTrack,
        decision: UsbRouteDecision,
    ): Boolean? {
        val device = decision.device ?: run {
            // Clear a previous explicit preference when the user selects Automatic, the saved
            // device disappears, or automatic routing is ambiguous. Let Android choose its route.
            runCatching { track.setPreferredDevice(null) }
            return null
        }
        val routedId = runCatching { track.routedDevice?.id }.getOrNull()
        return if (routedId == device.id) {
            true
        } else {
            runCatching { track.setPreferredDevice(device) }.getOrDefault(false)
        }
    }

    private fun publishTrackSnapshot(
        track: AudioTrack,
        config: AudioOutputProvider.OutputConfig,
        decision: UsbRouteDecision?,
        preferredDeviceAccepted: Boolean?,
    ) {
        if (closed.get()) return
        val requested = config.toPlatformTrackFormat() ?: return
        // Reserve before collecting platform state so a later invocation always invalidates
        // this snapshot, even if its AudioTrack reads finish first.
        val sequence = snapshotPublicationGate.reserve()
        val actual = runCatching { track.format.toTrackFormat() }.getOrNull()
        val routed = runCatching { track.routedDevice }.getOrNull()?.let(::toOutputDevice)
        val supportsExact = decision?.exactCandidates?.isNotEmpty() ?: false
        val supportsBitPerfect = decision?.bitPerfectCandidates?.isNotEmpty() ?: false
        val mixerAccepted = if (
            Build.VERSION.SDK_INT >= 34 &&
            !config.isOffload &&
            !config.isTunneling &&
            decision?.device != null &&
            supportsBitPerfect
        ) mixerPreferenceResult(config, decision.device) else null
        val routeEvidence = reconcileAndroidMedia3ActiveRoute(
            routedDeviceId = routed?.id,
            selectedUsbDeviceId = decision?.device?.id,
            selection = decision?.selection ?: AndroidMedia3UsbRouteSelection.NONE,
            preferredDeviceAccepted = preferredDeviceAccepted,
            mixerAdvertisesExactFormat = if (Build.VERSION.SDK_INT >= 34 && decision?.device != null) {
                supportsExact
            } else {
                null
            },
            mixerAdvertisesBitPerfectBehavior = if (
                Build.VERSION.SDK_INT >= 34 && decision?.device != null
            ) supportsBitPerfect else null,
            mixerPreferenceAccepted = mixerAccepted,
        )
        val outputDataFormat = config.toPlaybackAudioOutputDataSnapshot()
        val appDspMayModifySamples = pcmEqualizer.mayModifySamples(
            sampleRateHz = config.sampleRate,
            channelCount = Integer.bitCount(config.channelMask),
            encoding = config.encoding,
            offload = config.isOffload,
            tunneling = config.isTunneling,
        )
        val trackFormatMatchesRequested = actual?.let { it == requested }
        val snapshot = AndroidMedia3OutputSnapshot(
            requestedTrackFormat = requested,
            actualTrackFormat = actual,
            audioSessionId = runCatching { track.audioSessionId }.getOrDefault(0),
            offload = config.isOffload,
            tunneling = config.isTunneling,
            routedDevice = routed,
            selectedUsbDeviceId = routeEvidence.selectedUsbDeviceId,
            usbRouteSelection = routeEvidence.selection,
            preferredUsbDeviceAccepted = routeEvidence.preferredDeviceAccepted,
            audioTrackFormatMatchesRequested = trackFormatMatchesRequested,
            mixerAdvertisesExactFormat = routeEvidence.mixerAdvertisesExactFormat,
            mixerAdvertisesBitPerfectBehavior = routeEvidence.mixerAdvertisesBitPerfectBehavior,
            mixerPreferenceAccepted = routeEvidence.mixerPreferenceAccepted,
            appDspMayModifySamples = appDspMayModifySamples,
            replayGainAppliedDb = pcmEqualizer.replayGainDb().takeIf {
                it != 0.0 && appDspMayModifySamples
            },
            directPath = assessAndroidMedia3DirectPath(
                routedDeviceKnown = routed != null,
                outputFormat = outputDataFormat,
                audioTrackFormatMatchesRequested = trackFormatMatchesRequested,
                appDspMayModifySamples = appDspMayModifySamples,
                mixerAdvertisesExactFormat = routeEvidence.mixerAdvertisesExactFormat,
                mixerAdvertisesBitPerfectBehavior = routeEvidence.mixerAdvertisesBitPerfectBehavior,
                mixerPreferenceAccepted = routeEvidence.mixerPreferenceAccepted,
            ),
            outputDataFormat = outputDataFormat,
        )
        mainHandler.post {
            if (closed.get() || !snapshotPublicationGate.isCurrent(sequence)) return@post
            latestSnapshot = snapshot
            onOutputChanged(snapshot)
        }
    }

    private fun chooseUsbRoute(requested: AndroidMedia3TrackFormat?): UsbRouteDecision {
        val usbDevices = runCatching {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter(::isUsbAudioOutput)
        }.getOrDefault(emptyList())

        val exactByDevice = if (Build.VERSION.SDK_INT >= 34 && requested != null) {
            usbDevices.associateWith { device ->
                runCatching { supportedMixerAttributes(device) }.getOrDefault(emptyList())
                    .filter { it.format.toTrackFormat() == requested }
            }
        } else {
            emptyMap()
        }
        val bitPerfectByDevice = if (Build.VERSION.SDK_INT >= 34) {
            exactByDevice.mapValues { (_, candidates) ->
                candidates.filter { it.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT }
            }
        } else {
            emptyMap()
        }
        val candidates = usbDevices.map { device ->
            AndroidUsbRouteCandidate(
                deviceId = device.id,
                stableIdentity = stableUsbAudioTargetIdentity(
                    type = device.type,
                    address = usbAudioAddressOrEmpty(device),
                    productName = device.productName.toString(),
                ),
                exactFormat = if (Build.VERSION.SDK_INT >= 34 && requested != null) {
                    exactByDevice[device].orEmpty().isNotEmpty()
                } else {
                    null
                },
                bitPerfectBehavior = if (Build.VERSION.SDK_INT >= 34 && requested != null) {
                    bitPerfectByDevice[device].orEmpty().isNotEmpty()
                } else {
                    null
                },
            )
        }
        val choice = chooseAndroidUsbRoute(candidates, selectedUsbTargetIdentity)
        val selected = choice.deviceId?.let { selectedId -> usbDevices.singleOrNull { it.id == selectedId } }
        return UsbRouteDecision(
            device = selected,
            selection = choice.selection,
            exactCandidates = selected?.let { exactByDevice[it] }.orEmpty(),
            bitPerfectCandidates = selected?.let { bitPerfectByDevice[it] }.orEmpty(),
        )
    }

    private fun requestBitPerfectMixerPreference(
        config: AudioOutputProvider.OutputConfig,
        requested: AndroidMedia3TrackFormat,
        decision: UsbRouteDecision,
    ) {
        if (Build.VERSION.SDK_INT < 34) return
        val attrs = config.toPlatformAudioAttributes()
        val key = decision.device?.let {
            MixerPreferenceKey(
                deviceId = it.id,
                usage = attrs.usage,
                contentType = attrs.contentType,
                flags = attrs.flags,
            )
        }
        synchronized(lock) {
            if (closed.get()) return
            // A route can disappear or become ambiguous while playback is active. Drop any
            // preference owned by this helper that no longer matches the current output target.
            mixerOverrides.entries
                .filter { (existingKey, _) -> existingKey != key }
                .map { it.key to it.value }
                .forEach { (obsoleteKey, override) ->
                    if (restoreMixerPreference(override)) mixerOverrides.remove(obsoleteKey)
                }
            val activeKey = key ?: return
            if (activeKey in externallyModifiedMixerPreferences) return
            val device = decision.device
            val existing = mixerOverrides[activeKey]
            if (existing != null) {
                val current = runCatching { getPreferredMixerAttributes(attrs, device) }
                when (mixerPreferenceOwnership(
                    currentPreference = current,
                    appliedPreference = existing.applied,
                    samePreference = { value, applied -> value?.hasSameValuesAs(applied) == true },
                )) {
                    MixerPreferenceOwnership.UNKNOWN -> return
                    MixerPreferenceOwnership.CHANGED_EXTERNALLY -> {
                        mixerOverrides.remove(activeKey)
                        externallyModifiedMixerPreferences += activeKey
                        return
                    }
                    MixerPreferenceOwnership.CURRENTLY_OWNED -> Unit
                }
            }
            val canRequestBitPerfect =
                !config.isOffload && !config.isTunneling && decision.bitPerfectCandidates.isNotEmpty()
            if (!canRequestBitPerfect) {
                if (existing != null) {
                    if (restoreMixerPreference(existing)) mixerOverrides.remove(activeKey)
                }
                return
            }

            val desired = runCatching {
                AudioMixerAttributes.Builder(requested.toAudioFormat())
                    .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                    .build()
            }.getOrNull() ?: return
            if (existing?.applied?.hasSameValuesAs(desired) == true) return
            if (existing != null) {
                val accepted = runCatching {
                    setPreferredMixerAttributes(attrs, device, desired)
                }.getOrDefault(false)
                if (accepted) {
                    mixerOverrides[activeKey] = existing.copy(applied = desired)
                } else {
                    // The previous request was ours; don't leave it active for a different format.
                    if (restoreMixerPreference(existing)) mixerOverrides.remove(activeKey)
                }
            } else {
                when (val update = captureAndApplyMixerPreference(
                    readPreviousPreference = { getPreferredMixerAttributes(attrs, device) },
                    applyDesiredPreference = { setPreferredMixerAttributes(attrs, device, desired) },
                )) {
                    is MixerPreferenceApplyResult.Applied -> {
                        mixerOverrides[activeKey] = MixerPreferenceOverride(
                            audioAttributes = attrs,
                            device = device,
                            previous = update.previousPreference,
                            applied = desired,
                        )
                    }
                    MixerPreferenceApplyResult.PreviousPreferenceUnavailable,
                    MixerPreferenceApplyResult.Rejected -> return
                }
            }
        }
    }

    private fun mixerPreferenceResult(
        config: AudioOutputProvider.OutputConfig,
        device: AudioDeviceInfo,
    ): Boolean? {
        if (Build.VERSION.SDK_INT < 34 || config.isOffload) return null
        val requested = config.toPlatformTrackFormat() ?: return null
        val attrs = config.toPlatformAudioAttributes()
        val key = MixerPreferenceKey(device.id, attrs.usage, attrs.contentType, attrs.flags)
        val desired = runCatching {
            AudioMixerAttributes.Builder(requested.toAudioFormat())
                .setMixerBehavior(AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT)
                .build()
        }.getOrNull() ?: return null
        return synchronized(lock) {
            val readback = runCatching { getPreferredMixerAttributes(attrs, device) }
            if (readback.isFailure) return@synchronized null
            val current = readback.getOrNull()
            val existing = mixerOverrides[key]
            if (existing != null && current?.hasSameValuesAs(existing.applied) != true) {
                mixerOverrides.remove(key)
                externallyModifiedMixerPreferences += key
            }
            current?.hasSameValuesAs(desired) == true
        }
    }

    private fun releaseOwnedResources() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            removeRouteListenerLocked()
            if (Build.VERSION.SDK_INT >= 34) {
                mixerOverrides.values.toList().asReversed().forEach { override ->
                    if (!restoreMixerPreference(override)) {
                        Log.w(
                            ANDROID_MEDIA3_OUTPUT_TAG,
                            "Could not restore mixer preference for device ${override.device.id}; retrying once",
                        )
                        if (!restoreMixerPreference(override)) {
                            Log.e(
                                ANDROID_MEDIA3_OUTPUT_TAG,
                                "Mixer preference for device ${override.device.id} may remain after player release",
                            )
                        }
                    }
                }
            }
            mixerOverrides.clear()
        }
    }

    private fun removeRouteListenerLocked() {
        val oldTrack = routeListenerTrack
        val oldListener = routeListener
        if (oldTrack != null && oldListener != null) {
            runCatching { oldTrack.removeOnRoutingChangedListener(oldListener) }
        }
        routeListenerTrack = null
        routeListener = null
        routeListenerConfig = null
    }

    override fun close() {
        forwardingProvider.release()
    }

    private fun AudioOutputProvider.OutputConfig.toPlatformTrackFormat(): AndroidMedia3TrackFormat? =
        runCatching {
            AndroidMedia3TrackFormat(
                sampleRateHz = sampleRate,
                encoding = encoding,
                channelMask = channelMask,
                channelIndexMask = 0,
            )
        }.getOrNull()?.takeIf { it.sampleRateHz > 0 && it.encoding != AudioFormat.ENCODING_INVALID }

    private fun AudioOutputProvider.OutputConfig.toPlatformAudioAttributes(): PlatformAudioAttributes =
        PlatformAudioAttributes.Builder()
            .setUsage(audioAttributes.usage)
            .setContentType(audioAttributes.contentType)
            .setFlags(audioAttributes.flags)
            .build()

    private fun AndroidMedia3TrackFormat.toAudioFormat(): AudioFormat = AudioFormat.Builder()
        .setSampleRate(sampleRateHz)
        .setEncoding(encoding)
        .setChannelMask(channelMask)
        .build()

    private fun AudioFormat.toTrackFormat() = AndroidMedia3TrackFormat(
        sampleRateHz = sampleRate,
        encoding = encoding,
        channelMask = channelMask,
        channelIndexMask = channelIndexMask,
    )

    private fun isUsbAudioOutput(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_USB_DEVICE || device.type == AudioDeviceInfo.TYPE_USB_HEADSET

    private fun toOutputDevice(device: AudioDeviceInfo) = AndroidMedia3OutputDevice(
        id = device.id,
        type = device.type,
        productName = device.productName.toString(),
    )

    private data class UsbRouteDecision(
        val device: AudioDeviceInfo?,
        val selection: AndroidMedia3UsbRouteSelection,
        val exactCandidates: List<AudioMixerAttributes> = emptyList(),
        val bitPerfectCandidates: List<AudioMixerAttributes> = emptyList(),
    )

    private data class MixerPreferenceKey(
        val deviceId: Int,
        val usage: Int,
        val contentType: Int,
        val flags: Int,
    )

    private data class MixerPreferenceOverride(
        val audioAttributes: PlatformAudioAttributes,
        val device: AudioDeviceInfo,
        val previous: AudioMixerAttributes?,
        val applied: AudioMixerAttributes,
    )

    @RequiresApi(34)
    private fun supportedMixerAttributes(device: AudioDeviceInfo): List<AudioMixerAttributes> =
        audioManager.getSupportedMixerAttributes(device)

    @RequiresApi(34)
    private fun getPreferredMixerAttributes(
        attrs: PlatformAudioAttributes,
        device: AudioDeviceInfo,
    ): AudioMixerAttributes? = audioManager.getPreferredMixerAttributes(attrs, device)

    @RequiresApi(34)
    private fun setPreferredMixerAttributes(
        attrs: PlatformAudioAttributes,
        device: AudioDeviceInfo,
        mixerAttributes: AudioMixerAttributes,
    ): Boolean = audioManager.setPreferredMixerAttributes(attrs, device, mixerAttributes)

    @RequiresApi(34)
    private fun restoreMixerPreference(override: MixerPreferenceOverride): Boolean {
        val current = runCatching {
            getPreferredMixerAttributes(override.audioAttributes, override.device)
        }
        val key = MixerPreferenceKey(
            deviceId = override.device.id,
            usage = override.audioAttributes.usage,
            contentType = override.audioAttributes.contentType,
            flags = override.audioAttributes.flags,
        )
        return when (restoreMixerPreferenceIfOwned(
            currentPreference = current,
            appliedPreference = override.applied,
            previousPreference = override.previous,
            samePreference = { value, applied -> value?.hasSameValuesAs(applied) == true },
            clearPreference = {
                audioManager.clearPreferredMixerAttributes(override.audioAttributes, override.device)
            },
            setPreviousPreference = { previous ->
                setPreferredMixerAttributes(override.audioAttributes, override.device, previous)
            },
        )) {
            MixerPreferenceRestoreResult.UNAVAILABLE,
            MixerPreferenceRestoreResult.REJECTED -> false
            MixerPreferenceRestoreResult.CHANGED_EXTERNALLY -> {
                externallyModifiedMixerPreferences += key
                true
            }
            MixerPreferenceRestoreResult.RESTORED -> true
        }
    }

    @RequiresApi(34)
    private fun AudioMixerAttributes.hasSameValuesAs(other: AudioMixerAttributes): Boolean =
        mixerBehavior == other.mixerBehavior && format.toTrackFormat() == other.format.toTrackFormat()
}

/** Keeps the caller's buffer immutable and retains one transformed copy across partial writes. */
internal class AndroidEqualizingAudioOutput(
    output: AudioOutput,
    private val equalizer: AndroidPcmEqualizer,
    private val sampleRateHz: Int,
    private val channelCount: Int,
    private val encoding: Int,
) : ForwardingAudioOutput(output) {
    private data class PendingWrite(
        val source: ByteBuffer,
        val sourcePosition: Int,
        val output: ByteBuffer,
    )

    private var pendingWrite: PendingWrite? = null

    override fun write(buffer: ByteBuffer, encodedAccessUnitCount: Int, presentationTimeUs: Long): Boolean {
        val pending = pendingWrite
        if (pending == null || pending.source !== buffer) {
            check(pending == null) { "Media3 replaced an unconsumed PCM buffer." }
            val processed = equalizer.processCopy(buffer, sampleRateHz, channelCount, encoding)
            if (processed == null) return super.write(buffer, encodedAccessUnitCount, presentationTimeUs)
            pendingWrite = PendingWrite(buffer, buffer.position(), processed)
        }

        val current = checkNotNull(pendingWrite)
        val handled = super.write(current.output, encodedAccessUnitCount, presentationTimeUs)
        current.source.position(current.sourcePosition + current.output.position())
        if (handled) pendingWrite = null
        return handled
    }

    override fun flush() {
        pendingWrite = null
        equalizer.resetStream()
        super.flush()
    }

    override fun release() {
        pendingWrite = null
        equalizer.resetStream()
        super.release()
    }
}
