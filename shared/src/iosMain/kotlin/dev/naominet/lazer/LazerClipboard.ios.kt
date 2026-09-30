@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
package dev.naominet.lazer

import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.text.AnnotatedString

internal actual fun lazerTextClipEntry(text: String): ClipEntry = ClipEntry(AnnotatedString(text))
