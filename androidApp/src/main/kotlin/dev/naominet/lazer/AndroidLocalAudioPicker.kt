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
    val mimeType = resolver.getType(uri)
    if (!isSupportedAndroidLocalAudio(displayName, mimeType)) return null

    val fallbackTitle = displayName.substringAfterLast('/').substringBeforeLast('.', displayName).ifBlank { "Audio file" }
    val retriever = MediaMetadataRetriever()
    val metadata = runCatching {
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
    val embeddedMetadata = runCatching {
        val format = androidLocalAudioContainer(displayName, mimeType) ?: return@runCatching null
        val descriptor = resolver.openAssetFileDescriptor(uri, "r") ?: return@runCatching null
        try {
            descriptor.createInputStream().use { input ->
                val channel = input.channel
                val startOffset = descriptor.startOffset
                val length = descriptor.length.takeIf { it >= 0L }
                    ?: (channel.size() - startOffset)
                AndroidLocalReplayGainReader.readMetadata(channel, startOffset, length, format)
            }
        } catch (failure: Throwable) {
            runCatching { descriptor.close() }
            throw failure
        }
    }.getOrNull()
    val durationMillis = metadata.durationMillis.takeIf { it > 0L }
        ?: if (isAndroidDsdAudio(displayName, mimeType)) {
            runCatching { readAndroidDsdDurationMillis(context, uri) }.getOrNull()
        } else {
            null
        }
    return metadata.copy(
        title = embeddedMetadata?.title?.takeIf(String::isNotBlank) ?: metadata.title,
        artist = embeddedMetadata?.artist?.takeIf(String::isNotBlank) ?: metadata.artist,
        album = embeddedMetadata?.album?.takeIf(String::isNotBlank) ?: metadata.album,
        durationMillis = durationMillis ?: 0L,
        replayGain = embeddedMetadata?.replayGain,
    )
}

internal fun androidLocalAudioContainer(displayName: String, mimeType: String?): String? {
    val extension = displayName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    if (extension in setOf("wav", "wave", "flac", "dsf", "dff")) return extension
    return when (mimeType?.lowercase()) {
        "audio/flac", "audio/x-flac" -> "flac"
        "audio/wav", "audio/x-wav", "audio/wave" -> "wav"
        "audio/x-dsf" -> "dsf"
        "audio/x-dff" -> "dff"
        "audio/x-dsd" -> "dsd"
        else -> null
    }
}
