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
        val trackIndex = parseLocalAudioIndex(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
        )
        val discIndex = parseLocalAudioIndex(
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER),
        )
        val totalTracks = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_NUM_TRACKS)
            ?.toIntOrNull()?.takeIf { total ->
                total in 1..99_999 && (trackIndex.first?.let { total >= it } ?: true)
            }
            ?: trackIndex.second
        LazerPickedAudioFile(
            uri = uri.toString(),
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.trim()?.takeIf(String::isNotEmpty) ?: fallbackTitle,
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty().trim(),
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty().trim(),
            durationMillis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L,
            albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
                ?.trim()?.takeIf(String::isNotEmpty),
            genre = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                ?.trim()?.takeIf(String::isNotEmpty),
            year = parseLocalAudioYear(
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE),
            ),
            trackNumber = trackIndex.first,
            totalTracks = totalTracks,
            discNumber = discIndex.first,
            totalDiscs = discIndex.second,
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
    val trackNumber = embeddedMetadata?.trackNumber ?: metadata.trackNumber
    val discNumber = embeddedMetadata?.discNumber ?: metadata.discNumber
    val totalTracks = (embeddedMetadata?.totalTracks ?: metadata.totalTracks)
        ?.takeIf { trackNumber == null || it >= trackNumber }
    val totalDiscs = (embeddedMetadata?.totalDiscs ?: metadata.totalDiscs)
        ?.takeIf { discNumber == null || it >= discNumber }
    return metadata.copy(
        title = embeddedMetadata?.title?.takeIf(String::isNotBlank) ?: metadata.title,
        artist = embeddedMetadata?.artist?.takeIf(String::isNotBlank) ?: metadata.artist,
        album = embeddedMetadata?.album?.takeIf(String::isNotBlank) ?: metadata.album,
        durationMillis = durationMillis ?: 0L,
        replayGain = embeddedMetadata?.replayGain,
        albumArtist = embeddedMetadata?.albumArtist?.takeIf(String::isNotBlank) ?: metadata.albumArtist,
        genre = embeddedMetadata?.genre?.takeIf(String::isNotBlank) ?: metadata.genre,
        year = embeddedMetadata?.year ?: metadata.year,
        trackNumber = trackNumber,
        totalTracks = totalTracks,
        discNumber = discNumber,
        totalDiscs = totalDiscs,
    )
}

private fun parseLocalAudioYear(value: String?): Int? = value
    ?.trim()
    ?.takeIf { it.length >= 4 && it.take(4).all(Char::isDigit) }
    ?.let { text ->
        val next = text.getOrNull(4)
        if (next != null && !next.isDigit() && next !in "-/. T") return@let null
        text.take(4)
    }
    ?.toIntOrNull()
    ?.takeIf { it in 1..9_999 }

private fun parseLocalAudioIndex(value: String?): Pair<Int?, Int?> {
    val parts = value?.trim()?.split('/', limit = 2) ?: return null to null
    val number = parts[0].toIntOrNull()?.takeIf { it in 1..99_999 } ?: return null to null
    val total = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in number..99_999 }
    return number to total
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
