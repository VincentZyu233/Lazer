package dev.naominet.lazer

import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Versioned, platform-neutral representation of the Android queue stored between launches. */
object LazerPlaybackQueueCodec {
    private const val SCHEMA_VERSION = 3
    private const val MAX_ENCODED_LENGTH = 20 * 1024 * 1024
    private const val MAX_METADATA_FIELD_LENGTH = 16 * 1024
    private const val MAX_TRACK_METADATA_LENGTH = 16 * 1024
    private const val MAX_ARTISTS_PER_TRACK = 100

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    fun encode(snapshot: LazerPlaybackQueueSnapshot): String {
        require(isValid(snapshot)) { "Playback queue cannot be persisted" }
        val serialized = json.encodeToString(
            PersistedQueue(
                schemaVersion = SCHEMA_VERSION,
                tracks = snapshot.tracks.map(::PersistedTrack),
                index = snapshot.index,
                mode = snapshot.mode.name,
            ),
        )
        require(serialized.length <= MAX_ENCODED_LENGTH) { "Playback queue is too large to persist" }
        return serialized
    }

    fun decode(serialized: String?): LazerPlaybackQueueSnapshot? {
        if (serialized.isNullOrBlank() || serialized.length > MAX_ENCODED_LENGTH) return null
        return runCatching {
            val persisted = json.decodeFromString<PersistedQueue>(serialized)
            if (persisted.schemaVersion !in SUPPORTED_SCHEMA_VERSIONS) return null
            if (persisted.tracks.size > LazerPlaybackQueue.MAX_TRACKS) return null
            val mode = LazerPlayMode.entries.firstOrNull { it.name == persisted.mode } ?: return null
            val tracks = persisted.tracks.map(PersistedTrack::toTrack)
            LazerPlaybackQueueSnapshot(tracks, persisted.index, mode)
                .takeIf(::isValid)
        }.getOrNull()
    }

    /** Applies a saved checkpoint only when it belongs to the restored current track. */
    fun restoredPositionMillis(
        track: LazerTrack?,
        savedTrackId: Long?,
        savedPositionMillis: Long?,
    ): Long {
        if (track == null || savedTrackId != track.id) return 0L
        val position = savedPositionMillis?.coerceAtLeast(0L) ?: return 0L
        return if (track.durationMillis > 0L) position.coerceAtMost(track.durationMillis) else position
    }

    private fun isValid(snapshot: LazerPlaybackQueueSnapshot): Boolean {
        if (snapshot.tracks.size > LazerPlaybackQueue.MAX_TRACKS) return false
        if (snapshot.tracks.map(LazerTrack::id).distinct().size != snapshot.tracks.size) return false
        if (snapshot.index !in -1..snapshot.tracks.lastIndex) return false
        if (snapshot.tracks.isEmpty() && snapshot.index != -1) return false
        if (snapshot.tracks.isNotEmpty() && snapshot.index == -1) return false
        return snapshot.tracks.all { track ->
            track.id != 0L &&
                track.durationMillis >= 0L &&
                track.title.length <= MAX_METADATA_FIELD_LENGTH &&
                track.artist.length <= MAX_METADATA_FIELD_LENGTH &&
                track.album.length <= MAX_METADATA_FIELD_LENGTH &&
                (track.albumArtist?.length ?: 0) <= MAX_METADATA_FIELD_LENGTH &&
                (track.genre?.length ?: 0) <= MAX_METADATA_FIELD_LENGTH &&
                (track.coverUrl?.length ?: 0) <= MAX_METADATA_FIELD_LENGTH &&
                (track.translatedTitle?.length ?: 0) <= MAX_METADATA_FIELD_LENGTH &&
                track.artists.size <= MAX_ARTISTS_PER_TRACK &&
                track.artists.all { it.name.length <= MAX_METADATA_FIELD_LENGTH } &&
                (track.year == null || track.year in 1..9_999) &&
                (track.trackNumber == null || track.trackNumber in 1..99_999) &&
                (track.totalTracks == null || track.totalTracks in 1..99_999) &&
                (track.trackNumber == null || track.totalTracks == null || track.totalTracks >= track.trackNumber) &&
                (track.discNumber == null || track.discNumber in 1..99_999) &&
                (track.totalDiscs == null || track.totalDiscs in 1..99_999) &&
                (track.discNumber == null || track.totalDiscs == null || track.totalDiscs >= track.discNumber) &&
                (
                    track.title.length + track.artist.length + track.album.length +
                        (track.albumArtist?.length ?: 0) + (track.genre?.length ?: 0) +
                        (track.coverUrl?.length ?: 0) + (track.translatedTitle?.length ?: 0) +
                        (track.source as? LazerTrackSource.LocalFile)?.uri.orEmpty().length +
                        track.artists.sumOf { it.name.length }
                    ) <= MAX_TRACK_METADATA_LENGTH &&
                when (val source = track.source) {
                    LazerTrackSource.GatewaySong -> true
                    is LazerTrackSource.LocalFile -> source.uri.isNotBlank() && source.uri.length <= MAX_METADATA_FIELD_LENGTH
                }
        }
    }

    @Serializable
    private data class PersistedQueue(
        val schemaVersion: Int,
        val tracks: List<PersistedTrack>,
        val index: Int,
        val mode: String,
    )

    @Serializable
    private data class PersistedTrack(
        val id: Long,
        val title: String,
        val artist: String,
        val album: String,
        val durationMillis: Long,
        val coverUrl: String? = null,
        val artists: List<PersistedArtist> = emptyList(),
        val translatedTitle: String? = null,
        val sourceType: String = SOURCE_GATEWAY,
        val localUri: String? = null,
        val replayGain: PersistedReplayGainTags? = null,
        val albumArtist: String? = null,
        val genre: String? = null,
        val year: Int? = null,
        val trackNumber: Int? = null,
        val totalTracks: Int? = null,
        val discNumber: Int? = null,
        val totalDiscs: Int? = null,
    ) {
        constructor(track: LazerTrack) : this(
            id = track.id,
            title = track.title,
            artist = track.artist,
            album = track.album,
            durationMillis = track.durationMillis,
            coverUrl = track.coverUrl,
            artists = track.artists.map { PersistedArtist(it.id, it.name) },
            translatedTitle = track.translatedTitle,
            sourceType = when (track.source) {
                LazerTrackSource.GatewaySong -> SOURCE_GATEWAY
                is LazerTrackSource.LocalFile -> SOURCE_LOCAL
            },
            localUri = (track.source as? LazerTrackSource.LocalFile)?.uri,
            replayGain = track.replayGain?.toPersistedReplayGain(),
            albumArtist = track.albumArtist,
            genre = track.genre,
            year = track.year,
            trackNumber = track.trackNumber,
            totalTracks = track.totalTracks,
            discNumber = track.discNumber,
            totalDiscs = track.totalDiscs,
        )

        fun toTrack(): LazerTrack {
            require(id != 0L)
            require(title.length <= MAX_METADATA_FIELD_LENGTH)
            require(artist.length <= MAX_METADATA_FIELD_LENGTH)
            require(album.length <= MAX_METADATA_FIELD_LENGTH)
            require((albumArtist?.length ?: 0) <= MAX_METADATA_FIELD_LENGTH)
            require((genre?.length ?: 0) <= MAX_METADATA_FIELD_LENGTH)
            require((coverUrl?.length ?: 0) <= MAX_METADATA_FIELD_LENGTH)
            require((translatedTitle?.length ?: 0) <= MAX_METADATA_FIELD_LENGTH)
            require(artists.size <= MAX_ARTISTS_PER_TRACK)
            require(durationMillis >= 0L)
            val source = when (sourceType) {
                SOURCE_GATEWAY -> {
                    require(localUri == null)
                    LazerTrackSource.GatewaySong
                }
                SOURCE_LOCAL -> LazerTrackSource.LocalFile(
                    localUri?.takeIf { it.isNotBlank() && it.length <= MAX_METADATA_FIELD_LENGTH }
                        ?: error("Invalid local audio URI"),
                )
                else -> error("Unknown playback track source")
            }
            return LazerTrack(
                id = id,
                title = title,
                artist = artist,
                album = album,
                durationMillis = durationMillis.coerceAtLeast(0L),
                coverUrl = coverUrl,
                artists = artists.map { dev.naominet.lazer.gateway.model.Artist(id = it.id, name = it.name) },
                translatedTitle = translatedTitle,
                source = source,
                replayGain = replayGain?.toLazerReplayGain(),
                albumArtist = albumArtist,
                genre = genre,
                year = year,
                trackNumber = trackNumber,
                totalTracks = totalTracks,
                discNumber = discNumber,
                totalDiscs = totalDiscs,
            )
        }
    }

    @Serializable
    private data class PersistedReplayGainTags(
        val trackGainDb: Double? = null,
        val trackPeak: Double? = null,
        val albumGainDb: Double? = null,
        val albumPeak: Double? = null,
    )

    private fun LazerReplayGainTags.toPersistedReplayGain(): PersistedReplayGainTags? =
        PersistedReplayGainTags(
            trackGainDb = trackGainDb?.takeIf(Double::isValidLazerReplayGainDb),
            trackPeak = trackPeak?.takeIf(Double::isValidLazerReplayGainPeak),
            albumGainDb = albumGainDb?.takeIf(Double::isValidLazerReplayGainDb),
            albumPeak = albumPeak?.takeIf(Double::isValidLazerReplayGainPeak),
        ).takeIf { it.trackGainDb != null || it.trackPeak != null || it.albumGainDb != null || it.albumPeak != null }

    private fun PersistedReplayGainTags.toLazerReplayGain(): LazerReplayGainTags? =
        LazerReplayGainTags(
            trackGainDb = trackGainDb?.takeIf(Double::isValidLazerReplayGainDb),
            trackPeak = trackPeak?.takeIf(Double::isValidLazerReplayGainPeak),
            albumGainDb = albumGainDb?.takeIf(Double::isValidLazerReplayGainDb),
            albumPeak = albumPeak?.takeIf(Double::isValidLazerReplayGainPeak),
        ).takeIf { it.trackGainDb != null || it.trackPeak != null || it.albumGainDb != null || it.albumPeak != null }

    @Serializable
    private data class PersistedArtist(
        val id: Long,
        val name: String,
    )

    private const val SOURCE_GATEWAY = "gateway"
    private const val SOURCE_LOCAL = "local"
    private val SUPPORTED_SCHEMA_VERSIONS = setOf(1, 2, SCHEMA_VERSION)
}
