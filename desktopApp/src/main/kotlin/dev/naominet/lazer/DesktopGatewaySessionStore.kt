package dev.naominet.lazer

import dev.naominet.lazer.gateway.GatewaySessionStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Properties

/** Stores the Gateway credential in a private per-user application state file. */
internal class DesktopGatewaySessionStore : GatewaySessionStore {
    override var cookie: String?
        get() = DesktopStateFile.get(SESSION_COOKIE_KEY)?.let { encoded ->
            runCatching { String(Base64.getDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
        }?.takeIf(String::isNotBlank)
        set(value) {
            DesktopStateFile.set(
                SESSION_COOKIE_KEY,
                value?.takeIf(String::isNotBlank)?.let {
                    Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8))
                },
            )
        }

    companion object {
        private const val SESSION_COOKIE_KEY = "gateway.session.cookie"
    }
}

internal object DesktopSettings {
    var isDark: Boolean
        get() = DesktopStateFile.get("appearance.dark")?.toBooleanStrictOrNull() ?: false
        set(value) = DesktopStateFile.set("appearance.dark", value.toString())

    /**
     * Single appearance style. The Acrylic style is Windows-only (it needs the DWM backdrop), so a
     * value persisted on Windows falls back to Material when the app runs elsewhere.
     */
    var style: LazerStyle
        get() {
            val stored = DesktopStateFile.get("appearance.style")?.let(::parseLazerStyle)
                ?: run {
                    val legacyEngine = parseLazerThemeEngine(DesktopStateFile.get("appearance.theme_engine"))
                    if (legacyEngine == LazerThemeEngine.MIUIX) LazerStyle.MIUIX else LazerStyle.MATERIAL
                }
            return stored
        }
        set(value) = DesktopStateFile.set("appearance.style", value.name)

    var language: LazerLanguage
        get() = parseLazerLanguage(DesktopStateFile.get("appearance.language"))
        set(value) = DesktopStateFile.set("appearance.language", value.name)

    /** Colour source. Migrates the legacy system-monet toggle on first read. */
    var palette: LazerPalette
        get() {
            DesktopStateFile.get("appearance.palette")?.let { return LazerPalette.parse(it) }
            return LazerPalette.Default
        }
        set(value) = DesktopStateFile.set("appearance.palette", value.serialize())

    var backgroundImagePath: String?
        get() = DesktopStateFile.get("appearance.background_image")
        set(value) = DesktopStateFile.set("appearance.background_image", value)

    var backgroundImageEnabled: Boolean
        get() = DesktopStateFile.get("appearance.background_image_enabled")?.toBooleanStrictOrNull() ?: true
        set(value) = DesktopStateFile.set("appearance.background_image_enabled", value.toString())

    /** Background source. Migrates the former image-enabled switch on first read. */
    var backgroundMode: DesktopBackgroundMode
        get() {
            DesktopStateFile.get("appearance.background_mode")?.let {
                return parseDesktopBackgroundMode(it)
            }
            return if (backgroundImagePath != null && backgroundImageEnabled) {
                DesktopBackgroundMode.IMAGE
            } else {
                DesktopBackgroundMode.SOLID
            }
        }
        set(value) = DesktopStateFile.set("appearance.background_mode", value.name)

    var backgroundAlpha: Float
        get() = DesktopStateFile.get("appearance.background_alpha")?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 0.5f
        set(value) = DesktopStateFile.set("appearance.background_alpha", value.coerceIn(0f, 1f).toString())

    var lyricFollowDelayMillis: Long
        get() = normalizeLyricFollowDelayMillis(
            DesktopStateFile.get("lyrics.follow_delay_millis")?.toLongOrNull()
                ?: DEFAULT_LYRIC_FOLLOW_DELAY_MILLIS,
        )
        set(value) = DesktopStateFile.set(
            "lyrics.follow_delay_millis",
            normalizeLyricFollowDelayMillis(value).toString(),
        )

    var lyricAnimationSpeed: LyricAnimationSpeed
        get() = parseLyricAnimationSpeed(DesktopStateFile.get("lyrics.animation_speed"))
        set(value) = DesktopStateFile.set("lyrics.animation_speed", value.name)

    var wordLyricsEnabled: Boolean
        get() = DesktopStateFile.get("lyrics.word_animation_enabled")?.toBooleanStrictOrNull() ?: true
        set(value) = DesktopStateFile.set("lyrics.word_animation_enabled", value.toString())

    var lyricGlowEnabled: Boolean
        get() = DesktopStateFile.get("lyrics.glow_enabled")?.toBooleanStrictOrNull() ?: true
        set(value) = DesktopStateFile.set("lyrics.glow_enabled", value.toString())

    var lyricFontSizeSp: Int
        get() = normalizeLyricFontSizeSp(
            DesktopStateFile.get("lyrics.font_size_sp")?.toIntOrNull()
                ?: DEFAULT_DESKTOP_LYRIC_FONT_SIZE_SP,
        )
        set(value) = DesktopStateFile.set(
            "lyrics.font_size_sp",
            normalizeLyricFontSizeSp(value).toString(),
        )

    var showFullLyrics: Boolean
        get() = DesktopStateFile.get("lyrics.show_full_lines")?.toBooleanStrictOrNull() ?: false
        set(value) = DesktopStateFile.set("lyrics.show_full_lines", value.toString())

    /** Backend-exclusive request: WASAPI Exclusive on Windows or CoreAudio Hog Mode on macOS. */
    var exclusiveAudio: Boolean
        get() = DesktopStateFile.get("playback.exclusive_audio")?.toBooleanStrictOrNull() ?: false
        set(value) = DesktopStateFile.set("playback.exclusive_audio", value.toString())

    var equalizer: LazerEqualizerState
        get() = parseLazerEqualizer(DesktopStateFile.get("playback.equalizer"))
        set(value) = DesktopStateFile.set("playback.equalizer", value.serialize())

    /** ReplayGain normalization mode for local files with supported metadata. */
    var replayGainMode: DesktopReplayGainMode
        get() = DesktopStateFile.get("playback.replaygain_mode")
            ?.let { stored -> DesktopReplayGainMode.entries.firstOrNull { it.name.equals(stored, ignoreCase = true) } }
            ?: DesktopReplayGainMode.Off
        set(value) = DesktopStateFile.set("playback.replaygain_mode", value.name)

    /** Whether the native FFmpeg/WASAPI engine is the one playing. Off falls back to Java Sound. */
    var hifiEngine: Boolean
        get() = DesktopStateFile.get("playback.hifi_engine")?.toBooleanStrictOrNull() ?: false
        set(value) = DesktopStateFile.set("playback.hifi_engine", value.toString())

    /** Output buffer length in milliseconds for the native engine. */
    var hifiBufferMillis: Int
        get() = DesktopStateFile.get("playback.hifi_buffer_millis")?.toIntOrNull()?.coerceIn(30, 1_000) ?: 120
        set(value) = DesktopStateFile.set("playback.hifi_buffer_millis", value.coerceIn(30, 1_000).toString())

    /** Bit-perfect exclusive output, which bypasses the engine's own volume and EQ. */
    var hifiBitPerfect: Boolean
        get() = DesktopStateFile.get("playback.hifi_bit_perfect")?.toBooleanStrictOrNull() ?: false
        set(value) = DesktopStateFile.set("playback.hifi_bit_perfect", value.toString())

    /** Require raw DSD to open only as an exact DoP carrier; unsupported paths fail explicitly. */
    var hifiDoPOutput: Boolean
        get() = DesktopStateFile.get("playback.hifi_dop_output")?.toBooleanStrictOrNull() ?: false
        set(value) = DesktopStateFile.set("playback.hifi_dop_output", value.toString())

    /** Require raw DSD to use an exact ALSA Native DSD_U8/U16/U32 hardware format. */
    var hifiNativeDsdOutput: Boolean
        get() = DesktopStateFile.get("playback.hifi_native_dsd_output")?.toBooleanStrictOrNull() ?: false
        set(value) = DesktopStateFile.set("playback.hifi_native_dsd_output", value.toString())

    /** Opaque stable identity for the selected Windows WASAPI render endpoint; null means default. */
    var hifiDeviceIdentity: String?
        get() = DesktopStateFile.get("playback.hifi_device_identity")?.takeIf(String::isNotBlank)
        set(value) = DesktopStateFile.set("playback.hifi_device_identity", value?.takeIf(String::isNotBlank))

    /** Address of the last MPD control server. No credentials are persisted. */
    var mpdHost: String
        get() = DesktopStateFile.get("network.mpd.host")?.takeIf(String::isNotBlank) ?: "127.0.0.1"
        set(value) = DesktopStateFile.set("network.mpd.host", value.trim().takeIf(String::isNotBlank))

    var mpdPort: Int
        get() = DesktopStateFile.get("network.mpd.port")?.toIntOrNull()?.takeIf { it in 1..65535 } ?: 6600
        set(value) {
            if (value in 1..65535) DesktopStateFile.set("network.mpd.port", value.toString())
        }

    var volume: Float
        get() = DesktopStateFile.get("playback.volume")?.toFloatOrNull()?.coerceIn(0f, 1f)
            ?: DEFAULT_DESKTOP_VOLUME
        set(value) = DesktopStateFile.set("playback.volume", value.coerceIn(0f, 1f).toString())

    var localLibraryRoots: List<String>
        get() = DesktopStateFile.get("library.local.roots")
            ?.split(',')
            ?.mapNotNull { encoded ->
                runCatching { String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
            }
            ?.filter(String::isNotBlank)
            .orEmpty()
        set(value) = DesktopStateFile.set(
            "library.local.roots",
            value.map { Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8)) }
                .joinToString(","),
        )
}

private object DesktopStateFile {
    private val statePath: Path by lazy {
        Path.of(System.getProperty("user.home"), ".lazer", "state.properties")
    }

    @Synchronized
    fun get(key: String): String? = runCatching {
        if (!Files.exists(statePath)) return@runCatching null
        Properties().apply { Files.newInputStream(statePath).use(::load) }.getProperty(key)
    }.getOrNull()

    @Synchronized
    fun set(key: String, value: String?) {
        runCatching {
            Files.createDirectories(statePath.parent)
            val properties = Properties().apply {
                if (Files.exists(statePath)) Files.newInputStream(statePath).use(::load)
            }
            if (value == null) properties.remove(key) else properties.setProperty(key, value)

            val temporary = statePath.resolveSibling("${statePath.fileName}.tmp")
            Files.newOutputStream(temporary).use { properties.store(it, "Lazer local state") }
            runCatching {
                Files.move(
                    temporary,
                    statePath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            }.getOrElse {
                Files.move(temporary, statePath, StandardCopyOption.REPLACE_EXISTING)
            }
        }
    }
}
