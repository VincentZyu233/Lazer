package dev.naominet.lazer

import androidx.compose.ui.platform.ClipEntry

/**
 * Plain text handed to the system clipboard. Each platform's clipboard object is its own type, so
 * the shared screens ask for this instead of naming a `ClipEntry` constructor that differs.
 */
internal expect fun lazerTextClipEntry(text: String): ClipEntry
