@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.naominet.lazer

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.text.AnnotatedString

internal actual suspend fun copyTextToClipboard(clipboard: Clipboard, text: String, label: String) {
    clipboard.setClipEntry(ClipEntry(AnnotatedString(text)))
}
