package dev.naominet.lazer

/**
 * Selects one descriptor-backed PCM stream tuple for the initial direct USB path.
 *
 * UAC2 Type-I descriptors do not carry the active sample-rate range; the configured session must
 * still read Clock Source GET_RANGE/GET_CUR and verify or set the exact rate before opening the
 * native isochronous transport. Returning a plan here means only that one advertised alternate
 * setting has the requested sample width and the supported stereo channel layout.
 */
internal fun resolveAndroidUac2PcmCandidatePlan(
    alternateSettings: List<AndroidUac2PlaybackAltSetting>,
    sampleRateHz: Int,
    channelCount: Int,
    bytesPerSample: Int,
    validBitResolution: Int,
): AndroidUac2PlaybackStreamPlan? {
    if (sampleRateHz <= 0 || sampleRateHz > ANDROID_UAC2_MAX_PCM_RATE_HZ) return null
    if (channelCount != ANDROID_UAC2_SUPPORTED_CHANNEL_COUNT) return null
    if (bytesPerSample !in ANDROID_UAC2_PCM_VALID_BITS_BY_SUBSLOT) return null
    if (ANDROID_UAC2_PCM_VALID_BITS_BY_SUBSLOT[bytesPerSample] != validBitResolution) return null

    val candidates = alternateSettings.filter { alternate ->
        alternate.channelCount == channelCount &&
            alternate.channelConfig == ANDROID_UAC2_STEREO_FRONT_LEFT_RIGHT &&
            alternate.subslotSizeBytes == bytesPerSample &&
            alternate.validBitResolution == validBitResolution &&
            (alternate.dataEndpoint.synchronizationType != ANDROID_UAC2_ISO_SYNC_ASYNCHRONOUS ||
                alternate.feedbackEndpoint != null)
    }
    val alternate = candidates.singleOrNull() ?: return null
    return AndroidUac2PlaybackStreamPlan(
        configurationValue = alternate.configurationValue,
        controlInterfaceNumber = alternate.controlInterfaceNumber,
        interfaceNumber = alternate.interfaceNumber,
        alternateSetting = alternate.alternateSetting,
        clockSourceId = alternate.clockSourceId,
        clockFrequencyAccess = alternate.clockFrequencyAccess,
        sampleRateHz = sampleRateHz.toLong(),
        channelCount = alternate.channelCount,
        channelConfig = alternate.channelConfig,
        subslotSizeBytes = alternate.subslotSizeBytes,
        validBitResolution = alternate.validBitResolution,
        dataEndpoint = alternate.dataEndpoint,
        feedbackEndpoint = alternate.feedbackEndpoint,
        clockSelector = alternate.clockSelector,
        clockSourceCandidates = alternate.clockSourceCandidates,
    )
}

private const val ANDROID_UAC2_MAX_PCM_RATE_HZ = 768_000
private const val ANDROID_UAC2_SUPPORTED_CHANNEL_COUNT = 2
private const val ANDROID_UAC2_STEREO_FRONT_LEFT_RIGHT = 0x0000_0003L
private const val ANDROID_UAC2_ISO_SYNC_ASYNCHRONOUS = 1
private val ANDROID_UAC2_PCM_VALID_BITS_BY_SUBSLOT = mapOf(2 to 16, 3 to 24, 4 to 32)
