package dev.naominet.lazer

/** Visual source rendered behind every desktop page. */
enum class DesktopBackgroundMode {
    SOLID,
    IMAGE,
    NOW_PLAYING_DYNAMIC,
    NOW_PLAYING_STATIC,
}

internal fun parseDesktopBackgroundMode(value: String?): DesktopBackgroundMode =
    DesktopBackgroundMode.entries.firstOrNull { it.name == value }
        ?: DesktopBackgroundMode.SOLID
