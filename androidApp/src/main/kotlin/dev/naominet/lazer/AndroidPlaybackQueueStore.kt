package dev.naominet.lazer

import android.content.Context
import android.content.SharedPreferences

internal data class AndroidSavedPlaybackPosition(
    val trackId: Long,
    val positionMillis: Long,
)

/** The queue payload changes on edits; the current position is checkpointed independently. */
internal class AndroidPlaybackQueueStore(context: Context) {
    private val queuePreferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        QUEUE_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val positionPreferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        POSITION_PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    fun loadQueue(): LazerPlaybackQueueSnapshot? =
        LazerPlaybackQueueCodec.decode(queuePreferences.getString(KEY_QUEUE, null))

    fun saveQueue(snapshot: LazerPlaybackQueueSnapshot): Boolean = runCatching {
        val serialized = LazerPlaybackQueueCodec.encode(snapshot)
        queuePreferences.edit().putString(KEY_QUEUE, serialized).apply()
        true
    }.getOrDefault(false)

    fun loadPosition(): AndroidSavedPlaybackPosition? {
        if (!positionPreferences.contains(KEY_POSITION_TRACK_ID)) return null
        return AndroidSavedPlaybackPosition(
            trackId = positionPreferences.getLong(KEY_POSITION_TRACK_ID, 0L),
            positionMillis = positionPreferences.getLong(KEY_POSITION_MILLIS, 0L).coerceAtLeast(0L),
        )
    }

    fun savePosition(trackId: Long, positionMillis: Long, commit: Boolean = false): Boolean {
        val edit = positionPreferences.edit()
            .putLong(KEY_POSITION_TRACK_ID, trackId)
            .putLong(KEY_POSITION_MILLIS, positionMillis.coerceAtLeast(0L))
        return if (commit) edit.commit() else {
            edit.apply()
            true
        }
    }

    private companion object {
        const val QUEUE_PREFERENCES_NAME = "lazer.android.playback.queue"
        const val POSITION_PREFERENCES_NAME = "lazer.android.playback.position"
        const val KEY_QUEUE = "queue.v1"
        const val KEY_POSITION_TRACK_ID = "position.track_id"
        const val KEY_POSITION_MILLIS = "position.millis"
    }
}
