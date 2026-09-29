package dev.naominet.lazer

import androidx.compose.ui.platform.ClipEntry

/** Creates the native plain-text payload required by Compose's asynchronous clipboard API. */
internal expect fun lazerTextClipEntry(text: String): ClipEntry
