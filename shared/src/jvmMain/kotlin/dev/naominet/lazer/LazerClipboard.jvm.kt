@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.naominet.lazer

import androidx.compose.ui.platform.ClipEntry
import java.awt.datatransfer.StringSelection

internal actual fun lazerTextClipEntry(text: String): ClipEntry = ClipEntry(StringSelection(text))
