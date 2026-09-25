package dev.naominet.lazer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowsTaskbarIntegrationTest {
    @Test
    fun `dark Windows applications use the high contrast taskbar icons`() {
        assertEquals(WindowsAppTheme.Light, windowsAppTheme(1))
        assertEquals(WindowsAppTheme.Light, windowsAppTheme(null))
        assertEquals(WindowsAppTheme.Dark, windowsAppTheme(0))
        assertEquals(
            "media-control/dark/play.ico",
            WindowsMediaControlIcons.resourcePath(WindowsAppTheme.Dark, "play"),
        )
    }

    @Test
    fun `media actions keep their taskbar order and playback state`() {
        val paused = mediaControlItems(isPlaying = false) { it }
        val playing = mediaControlItems(isPlaying = true) { it }

        assertEquals(
            listOf(MediaControlAction.Previous, MediaControlAction.PlayPause, MediaControlAction.Next),
            paused.map(MediaControlItem::action),
        )
        assertEquals("play", paused[1].iconResource)
        assertEquals("pause", playing[1].iconResource)
    }

    @Test
    fun `Jump List command arguments only accept known media actions`() {
        assertEquals(
            WindowsMediaCommand.Next,
            windowsMediaCommandFromArgs(arrayOf("--lazer-media-command=next")),
        )
        assertNull(windowsMediaCommandFromArgs(arrayOf("--lazer-media-command=unknown")))
    }
}
