package dev.naominet.lazer

import androidx.compose.ui.platform.Clipboard

/**
 * Puts plain text where the platform keeps the clipboard. Each host has its own way in - Android and
 * the desktop go through Compose's entry, iOS writes the system pasteboard - so the screens ask for
 * a copy instead of naming a type that differs.
 *
 * [label] says what was copied. Android shows it in the preview the system flashes when something
 * lands on the clipboard, which is why the screens keep passing their own wording.
 */
internal expect suspend fun copyTextToClipboard(clipboard: Clipboard, text: String, label: String)
