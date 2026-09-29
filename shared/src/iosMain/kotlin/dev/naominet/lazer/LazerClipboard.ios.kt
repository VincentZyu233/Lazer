@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.naominet.lazer

import androidx.compose.ui.platform.ClipEntry

internal actual fun lazerTextClipEntry(text: String): ClipEntry = ClipEntry.withPlainText(text)
