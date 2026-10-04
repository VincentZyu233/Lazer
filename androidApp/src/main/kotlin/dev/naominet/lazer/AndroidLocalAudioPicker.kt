package dev.naominet.lazer

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

internal fun readAndroidLocalAudioFile(context: Context, uri: Uri): LazerPickedAudioFile? {
    val resolver = context.contentResolver
    val displayName = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull().orEmpty().ifBlank { uri.lastPathSegment.orEmpty() }
    if (!isSupportedAndroidLocalAudio(displayName, resolver.getType(uri))) return null

    val fallbackTitle = displayName.substringAfterLast('/').substringBeforeLast('.', displayName).ifBlank { "Audio file" }
    val retriever = MediaMetadataRetriever()
    val tags = runCatching {
        retriever.setDataSource(context, uri)
        LazerPickedAudioFile(
            uri = uri.toString(),
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()?.takeIf(String::isNotEmpty) ?: fallbackTitle,
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().trim(),
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().trim(),
            durationMillis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
        )
    }.getOrElse {
        LazerPickedAudioFile(uri = uri.toString(), title = fallbackTitle)
    }
    runCatching { retriever.release() }
    return tags
}
