package dev.naominet.lazer

import android.content.Context
import dev.naominet.lazer.gateway.AudioQuality

/**
 * Settings, appearance modes and their keys live in the shared module now, so iOS reads the same
 * values Android does. The names below are the ones the Android screens and service already call.
 */
internal typealias AndroidSettingsStore = LazerSettingsStore

typealias AndroidPlaybackInterface = LazerPlaybackInterface

typealias AndroidBackgroundMode = LazerBackgroundMode

internal val ANDROID_AUDIO_QUALITY_OPTIONS = lazerAudioQualityOptions

internal fun AndroidSettingsStore(context: Context): LazerSettingsStore =
    LazerSettingsStore(androidPreferences(context))

internal fun parseAndroidAudioQuality(value: String?): AudioQuality = parseLazerAudioQuality(value)

internal fun parseAndroidPlaybackInterface(value: String?): LazerPlaybackInterface =
    parseLazerPlaybackInterface(value)

internal fun parseAndroidBackgroundMode(value: String?): LazerBackgroundMode =
    parseLazerBackgroundMode(value)
