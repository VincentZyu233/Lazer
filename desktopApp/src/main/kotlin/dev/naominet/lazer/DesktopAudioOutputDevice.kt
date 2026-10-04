package dev.naominet.lazer

/** A local output device normalized for the desktop picker; backend-specific APIs stay separate. */
internal interface DesktopAudioOutputDevice {
    /** Token passed to the native output backend. */
    val deviceToken: String
    /** Persisted identity used to resolve the token after restart. */
    val identityKey: String
    val displayName: String
    val active: Boolean
    val isDefault: Boolean
    val stableIdentity: Boolean
    val backendName: String
}

internal data class DesktopAlsaOutputDevice(
    override val deviceToken: String,
    override val identityKey: String,
    override val displayName: String,
    val flags: Int,
) : DesktopAudioOutputDevice {
    override val active: Boolean
        get() = flags and ALSA_OUTPUT_DEVICE_ACTIVE != 0

    // ALSA's direct `hw:` path has no portable system-default concept.
    override val isDefault: Boolean = false
    override val stableIdentity: Boolean
        get() = flags and ALSA_OUTPUT_DEVICE_IDENTITY_EPHEMERAL == 0
    override val backendName: String = "ALSA hw"
}

internal data class DesktopAudioOutputSelection(
    val deviceToken: String?,
    val unavailable: Boolean,
)

/** A missing saved device never silently changes the active hardware endpoint. */
internal fun resolveDesktopAudioOutputSelection(
    identityKey: String?,
    devices: List<DesktopAudioOutputDevice>,
): DesktopAudioOutputSelection {
    if (identityKey == null) return DesktopAudioOutputSelection(deviceToken = null, unavailable = false)
    val selected = devices.firstOrNull { it.identityKey == identityKey && it.active }
    return DesktopAudioOutputSelection(
        deviceToken = selected?.deviceToken,
        unavailable = selected == null,
    )
}

internal enum class DesktopAudioOutputBackend {
    Wasapi,
    Alsa,
    CoreAudio,
    Unsupported,
}

internal fun resolveDesktopAudioOutputBackend(osName: String): DesktopAudioOutputBackend = when {
    osName.contains("windows", ignoreCase = true) -> DesktopAudioOutputBackend.Wasapi
    osName.contains("linux", ignoreCase = true) -> DesktopAudioOutputBackend.Alsa
    osName.contains("mac", ignoreCase = true) || osName.contains("darwin", ignoreCase = true) ->
        DesktopAudioOutputBackend.CoreAudio
    else -> DesktopAudioOutputBackend.Unsupported
}

internal fun supportsDesktopExclusiveOutput(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    resolveDesktopAudioOutputBackend(osName) in setOf(
        DesktopAudioOutputBackend.Wasapi,
        DesktopAudioOutputBackend.CoreAudio,
    )

internal fun isMacOSDesktop(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    resolveDesktopAudioOutputBackend(osName) == DesktopAudioOutputBackend.CoreAudio

internal fun shouldRequestDesktopExclusiveOutput(
    enabled: Boolean,
    osName: String = System.getProperty("os.name").orEmpty(),
): Boolean = enabled && supportsDesktopExclusiveOutput(osName)

internal fun supportsDesktopBitPerfectOutput(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    resolveDesktopAudioOutputBackend(osName) != DesktopAudioOutputBackend.Unsupported

/** Native DSD_U8/U16/U32 negotiation is currently implemented only by the direct ALSA backend. */
internal fun supportsDesktopNativeDsdOutput(osName: String = System.getProperty("os.name").orEmpty()): Boolean =
    resolveDesktopAudioOutputBackend(osName) == DesktopAudioOutputBackend.Alsa

/** WASAPI and CoreAudio require an explicit exclusive/Hog request; ALSA hw is direct by definition. */
internal fun desktopBitPerfectRequiresExclusiveRequest(
    osName: String = System.getProperty("os.name").orEmpty(),
): Boolean = resolveDesktopAudioOutputBackend(osName) in setOf(
    DesktopAudioOutputBackend.Wasapi,
    DesktopAudioOutputBackend.CoreAudio,
)

internal const val ALSA_OUTPUT_DEVICE_ACTIVE = 1 shl 0
internal const val ALSA_OUTPUT_DEVICE_DEFAULT = 1 shl 1
internal const val ALSA_OUTPUT_DEVICE_IDENTITY_EPHEMERAL = 1 shl 2

internal data class DesktopCoreAudioOutputDevice(
    override val deviceToken: String,
    override val identityKey: String,
    override val displayName: String,
    val flags: Int,
) : DesktopAudioOutputDevice {
    override val active: Boolean
        get() = flags and COREAUDIO_OUTPUT_DEVICE_ACTIVE != 0
    override val isDefault: Boolean
        get() = flags and COREAUDIO_OUTPUT_DEVICE_DEFAULT != 0
    override val stableIdentity: Boolean = true
    override val backendName: String = "CoreAudio"
}

internal const val COREAUDIO_OUTPUT_DEVICE_ACTIVE = 1 shl 0
internal const val COREAUDIO_OUTPUT_DEVICE_DEFAULT = 1 shl 1
