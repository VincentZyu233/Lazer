package dev.naominet.lazer

import android.content.ClipData
import androidx.compose.ui.platform.ClipEntry

internal actual fun lazerTextClipEntry(text: String): ClipEntry =
    ClipEntry(ClipData.newPlainText("Lazer", text))
