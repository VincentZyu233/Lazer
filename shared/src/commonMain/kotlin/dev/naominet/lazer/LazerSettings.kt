package dev.naominet.lazer

import dev.naominet.lazer.gateway.AudioQuality

/** Qualities the app offers. The Gateway can return more; these are the ones a listener chooses. */
val lazerAudioQualityOptions = listOf(
    AudioQuality.STANDARD,
    AudioQuality.HIGHER,
    AudioQuality.EXHIGH,
    AudioQuality.LOSSLESS,
    AudioQuality.HI_RES,
    AudioQuality.JYMASTER,
)

fun parseLazerAudioQuality(value: String?): AudioQuality =
    lazerAudioQualityOptions.firstOrNull { it.name == value } ?: AudioQuality.EXHIGH

/** Whether playback hands itself to the platform's system media-control surfaces. */
enum class LazerPlaybackInterface {
    SYSTEM_MEDIA,
    INDEPENDENT,
}

fun parseLazerPlaybackInterface(value: String?): LazerPlaybackInterface =
    LazerPlaybackInterface.entries.firstOrNull { it.name == value }
        ?: LazerPlaybackInterface.SYSTEM_MEDIA

/** Visual source rendered behind every page. */
enum class LazerBackgroundMode {
    SOLID,
    IMAGE,
    NOW_PLAYING_DYNAMIC,
    NOW_PLAYING_STATIC,
}

fun parseLazerBackgroundMode(value: String?): LazerBackgroundMode =
    LazerBackgroundMode.entries.firstOrNull { it.name == value } ?: LazerBackgroundMode.SOLID

fun normalizeBackgroundImageBlurIntensity(value: Float): Float = value.coerceIn(0f, 1f)

/**
 * Appearance, playback and lyric settings shared by every platform. The keys and the migrations
 * below are the contract: one instance reads the same names on every host, so a preference set on
 * one platform means exactly what it means on another.
 */
class LazerSettingsStore(
    private val preferences: LazerPreferences,
    /** What a setting the listener never touched reads back as, where platforms genuinely differ. */
    private val defaultsExclusiveAudio: Boolean = false,
) {

    var isDark: Boolean
        get() = preferences.getBoolean(KEY_DARK_THEME, false)
        set(value) = preferences.putBoolean(KEY_DARK_THEME, value)

    var useSystemMonetColors: Boolean
        get() = preferences.getBoolean(KEY_SYSTEM_MONET, false)
        set(value) = preferences.putBoolean(KEY_SYSTEM_MONET, value)

    /** Colour source. Migrates the legacy system-monet toggle on first read. */
    var palette: LazerPalette
        get() {
            preferences.getString(KEY_PALETTE, null)?.let { return LazerPalette.parse(it) }
            return if (preferences.getBoolean(KEY_SYSTEM_MONET, false)) LazerPalette.System else LazerPalette.Default
        }
        set(value) = preferences.putString(KEY_PALETTE, value.serialize())

    /** Absolute path of the user's custom background image, or null. */
    var backgroundImagePath: String?
        get() = preferences.getString(KEY_BACKGROUND_IMAGE, null)
        set(value) = preferences.putString(KEY_BACKGROUND_IMAGE, value)

    /** Whether the custom background image is currently shown. */
    var backgroundImageEnabled: Boolean
        get() = preferences.getBoolean(KEY_BACKGROUND_IMAGE_ENABLED, true)
        set(value) = preferences.putBoolean(KEY_BACKGROUND_IMAGE_ENABLED, value)

    /** Background source. Migrates the former image enabled switch on first read. */
    var backgroundMode: LazerBackgroundMode
        get() {
            preferences.getString(KEY_BACKGROUND_MODE, null)?.let { return parseLazerBackgroundMode(it) }
            return if (backgroundImagePath != null && backgroundImageEnabled) {
                LazerBackgroundMode.IMAGE
            } else {
                LazerBackgroundMode.SOLID
            }
        }
        set(value) = preferences.putString(KEY_BACKGROUND_MODE, value.name)

    var backgroundAlpha: Float
        get() = preferences.getFloat(KEY_BACKGROUND_ALPHA, 0.5f).coerceIn(0f, 1f)
        set(value) = preferences.putFloat(KEY_BACKGROUND_ALPHA, value.coerceIn(0f, 1f))

    var backgroundImageBlurEnabled: Boolean
        get() = preferences.getBoolean(KEY_BACKGROUND_IMAGE_BLUR_ENABLED, false)
        set(value) = preferences.putBoolean(KEY_BACKGROUND_IMAGE_BLUR_ENABLED, value)

    var backgroundImageBlurIntensity: Float
        get() = normalizeBackgroundImageBlurIntensity(
            preferences.getFloat(KEY_BACKGROUND_IMAGE_BLUR_INTENSITY, 0.35f),
        )
        set(value) = preferences.putFloat(
            KEY_BACKGROUND_IMAGE_BLUR_INTENSITY,
            normalizeBackgroundImageBlurIntensity(value),
        )

    /** Single appearance style. Migrates the legacy theme-engine key on first read. */
    var style: LazerStyle
        get() {
            preferences.getString(KEY_STYLE, null)?.let { return parseLazerStyle(it) }
            val legacyEngine = parseLazerThemeEngine(preferences.getString(KEY_THEME_ENGINE, null))
            return when {
                legacyEngine == LazerThemeEngine.MIUIX -> LazerStyle.MIUIX
                else -> LazerStyle.MATERIAL
            }
        }
        set(value) = preferences.putString(KEY_STYLE, value.name)

    var language: LazerLanguage
        get() = parseLazerLanguage(preferences.getString(KEY_LANGUAGE, null))
        set(value) = preferences.putString(KEY_LANGUAGE, value.name)

    var lyricFollowDelayMillis: Long
        get() = normalizeLyricFollowDelayMillis(
            preferences.getLong(KEY_LYRIC_FOLLOW_DELAY, DEFAULT_LYRIC_FOLLOW_DELAY_MILLIS),
        )
        set(value) = preferences.putLong(KEY_LYRIC_FOLLOW_DELAY, normalizeLyricFollowDelayMillis(value))

    var lyricAnimationSpeed: LyricAnimationSpeed
        get() = parseLyricAnimationSpeed(preferences.getString(KEY_LYRIC_ANIMATION_SPEED, null))
        set(value) = preferences.putString(KEY_LYRIC_ANIMATION_SPEED, value.name)

    var wordLyricsEnabled: Boolean
        get() = preferences.getBoolean(KEY_WORD_LYRICS_ENABLED, true)
        set(value) = preferences.putBoolean(KEY_WORD_LYRICS_ENABLED, value)

    var lyricGlowEnabled: Boolean
        get() = preferences.getBoolean(KEY_LYRIC_GLOW_ENABLED, true)
        set(value) = preferences.putBoolean(KEY_LYRIC_GLOW_ENABLED, value)

    var lyricFontSizeSp: Int
        get() = normalizeLyricFontSizeSp(
            preferences.getInt(KEY_LYRIC_FONT_SIZE_SP, DEFAULT_ANDROID_LYRIC_FONT_SIZE_SP),
        )
        set(value) = preferences.putInt(KEY_LYRIC_FONT_SIZE_SP, normalizeLyricFontSizeSp(value))

    var showFullLyrics: Boolean
        get() = preferences.getBoolean(KEY_SHOW_FULL_LYRICS, false)
        set(value) = preferences.putBoolean(KEY_SHOW_FULL_LYRICS, value)

    var audioQuality: AudioQuality
        get() = parseLazerAudioQuality(preferences.getString(KEY_AUDIO_QUALITY, null))
        set(value) = preferences.putString(KEY_AUDIO_QUALITY, value.name)

    /** How the queue advances. Survives a restart because the player reads it on start. */
    var playMode: LazerPlayMode
        get() = LazerPlayMode.parse(preferences.getString(KEY_PLAY_MODE, null))
        set(value) = preferences.putString(KEY_PLAY_MODE, value.name)

    var exclusiveAudio: Boolean
        get() = preferences.getBoolean(KEY_EXCLUSIVE_AUDIO, defaultsExclusiveAudio)
        set(value) = preferences.putBoolean(KEY_EXCLUSIVE_AUDIO, value)

    /** The listener's equalizer curve. Stored as one encoded string so every platform reads it. */
    var equalizer: LazerEqualizerState
        get() = parseLazerEqualizer(preferences.getString(KEY_EQUALIZER, null))
        set(value) = preferences.putString(KEY_EQUALIZER, value.serialize())

    /** Local-file ReplayGain policy. Online Gateway tracks never receive this per-file gain. */
    var replayGainMode: LazerReplayGainMode
        get() = LazerReplayGainMode.entries.firstOrNull {
            it.name == preferences.getString(KEY_REPLAY_GAIN_MODE, null)
        } ?: LazerReplayGainMode.Off
        set(value) = preferences.putString(KEY_REPLAY_GAIN_MODE, value.name)

    var playbackInterface: LazerPlaybackInterface
        get() = parseLazerPlaybackInterface(preferences.getString(KEY_PLAYBACK_INTERFACE, null))
        set(value) = preferences.putString(KEY_PLAYBACK_INTERFACE, value.name)

    /** Whether the playlist indicator follows the captured spectrum of the playing track. */
    var audioReactiveLevels: Boolean
        get() = preferences.getBoolean(KEY_AUDIO_REACTIVE_LEVELS, false)
        set(value) = preferences.putBoolean(KEY_AUDIO_REACTIVE_LEVELS, value)

    /**
     * Whether a tap that lands answers on the hand. There is one strength and it belongs to the
     * system: grading it here meant bypassing the waveform each maker tunes for its own motor, and
     * the result felt worse than the plain platform click.
     */
    var hapticsEnabled: Boolean
        get() = preferences.getBoolean(
            KEY_HAPTICS_ENABLED,
            // Readers who had already picked 关闭 in the old four-way choice keep that answer.
            preferences.getString(KEY_HAPTIC_LEVEL, null) != "OFF",
        )
        set(value) = preferences.putBoolean(KEY_HAPTICS_ENABLED, value)

    companion object {
        const val KEY_DARK_THEME = "appearance.dark"
        const val KEY_SYSTEM_MONET = "appearance.system_monet"
        const val KEY_STYLE = "appearance.style"
        const val KEY_PALETTE = "appearance.palette"
        const val KEY_BACKGROUND_IMAGE = "appearance.background_image"
        const val KEY_BACKGROUND_IMAGE_ENABLED = "appearance.background_image_enabled"
        const val KEY_BACKGROUND_MODE = "appearance.background_mode"
        const val KEY_BACKGROUND_ALPHA = "appearance.background_alpha"
        const val KEY_BACKGROUND_IMAGE_BLUR_ENABLED = "appearance.background_image_blur_enabled"
        const val KEY_BACKGROUND_IMAGE_BLUR_INTENSITY = "appearance.background_image_blur_intensity"
        const val KEY_THEME_ENGINE = "appearance.theme_engine"
        const val KEY_LANGUAGE = "appearance.language"
        const val KEY_LYRIC_FOLLOW_DELAY = "lyrics.follow_delay_millis"
        const val KEY_LYRIC_ANIMATION_SPEED = "lyrics.animation_speed"
        const val KEY_WORD_LYRICS_ENABLED = "lyrics.word_animation_enabled"
        const val KEY_LYRIC_GLOW_ENABLED = "lyrics.glow_enabled"
        const val KEY_LYRIC_FONT_SIZE_SP = "lyrics.font_size_sp"
        const val KEY_SHOW_FULL_LYRICS = "lyrics.show_full_lines"
        const val KEY_AUDIO_QUALITY = "playback.audio_quality"
        const val KEY_PLAY_MODE = "playback.mode"
        const val KEY_EXCLUSIVE_AUDIO = "playback.exclusive_audio"
        const val KEY_EQUALIZER = "playback.equalizer"
        const val KEY_REPLAY_GAIN_MODE = "playback.replay_gain_mode"
        const val KEY_PLAYBACK_INTERFACE = "playback.interface"
        const val KEY_AUDIO_REACTIVE_LEVELS = "playback.audio_reactive_levels"
        const val KEY_HAPTICS_ENABLED = "interaction.haptics_enabled"
        // The four-way strength this replaced is gone; the key is only ever read, so a reader who had
        // chosen 关闭 does not get an answer back that they had turned off.
        const val KEY_HAPTIC_LEVEL = "interaction.haptic_level"
    }
}
