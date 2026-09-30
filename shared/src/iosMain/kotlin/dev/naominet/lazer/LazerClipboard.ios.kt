package dev.naominet.lazer

import androidx.compose.ui.platform.Clipboard
import platform.UIKit.UIPasteboard

internal actual suspend fun copyTextToClipboard(clipboard: Clipboard, text: String, label: String) {
    UIPasteboard.generalPasteboard.string = text
}
