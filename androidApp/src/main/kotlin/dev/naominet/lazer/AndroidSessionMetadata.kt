package dev.naominet.lazer

import android.media.MediaMetadata

/** Builds framework session metadata from the playback queue's already-normalized tags. */
internal fun androidSessionMetadataBuilder(track: LazerTrack): MediaMetadata.Builder {
    val metadata = MediaMetadata.Builder()
        .putString(MediaMetadata.METADATA_KEY_TITLE, track.title)
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, track.title)
        .putString(MediaMetadata.METADATA_KEY_ARTIST, track.artist)
        .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, track.artist)
        .putString(MediaMetadata.METADATA_KEY_ALBUM, track.album)
        .putString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI, track.coverUrl)
        .putLong(MediaMetadata.METADATA_KEY_DURATION, track.durationMillis)
    track.albumArtist?.let { metadata.putString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST, it) }
    track.genre?.let { metadata.putString(MediaMetadata.METADATA_KEY_GENRE, it) }
    track.year?.let { metadata.putLong(MediaMetadata.METADATA_KEY_YEAR, it.toLong()) }
    track.trackNumber?.let { metadata.putLong(MediaMetadata.METADATA_KEY_TRACK_NUMBER, it.toLong()) }
    track.totalTracks?.let { metadata.putLong(MediaMetadata.METADATA_KEY_NUM_TRACKS, it.toLong()) }
    track.discNumber?.let { metadata.putLong(MediaMetadata.METADATA_KEY_DISC_NUMBER, it.toLong()) }
    track.totalDiscs?.let { metadata.putLong(METADATA_KEY_TOTAL_DISCS, it.toLong()) }
    return metadata
}

private const val METADATA_KEY_TOTAL_DISCS = "android.media.metadata.TOTAL_DISCS"
