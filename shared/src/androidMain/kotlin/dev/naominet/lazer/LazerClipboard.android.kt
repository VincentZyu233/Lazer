package dev.naominet.lazer

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard

internal actual suspend fun copyTextToClipboard(clipboard: Clipboard, text: String, label: String) {
    clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
}
