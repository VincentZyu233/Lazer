@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package dev.naominet.lazer

import platform.Foundation.NSUserDefaults

/** The result distinguishes a first launch from data that must be preserved for recovery. */
internal sealed interface IosStoredQueue {
    data object Missing : IosStoredQueue
    data class Valid(val snapshot: LazerPlaybackQueueSnapshot) : IosStoredQueue
    data object Invalid : IosStoredQueue
}

/** Persists the iOS queue and a track-bound position checkpoint in the app's defaults. */
internal class IosPlaybackQueueStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
) {
    fun loadQueue(): IosStoredQueue {
        val storedValue = defaults.stringForKey(QUEUE_KEY)
        if (storedValue == null) {
            return if (defaults.dictionaryRepresentation().containsKey(QUEUE_KEY)) {
                IosStoredQueue.Invalid
            } else {
                IosStoredQueue.Missing
            }
        }
        val decoded = LazerPlaybackQueueCodec.decode(storedValue) ?: return IosStoredQueue.Invalid
        return IosStoredQueue.Valid(decoded)
    }

    /** Confirms the defaults cache accepted the snapshot; orphan cleanup still waits for next-launch restore. */
    fun saveQueue(snapshot: LazerPlaybackQueueSnapshot): Boolean = runCatching {
        val encoded = LazerPlaybackQueueCodec.encode(snapshot)
        defaults.setObject(encoded, QUEUE_KEY)
        defaults.stringForKey(QUEUE_KEY) == encoded
    }.getOrDefault(false)

    fun loadPosition(): Pair<Long, Long>? {
        val trackId = defaults.stringForKey(POSITION_TRACK_KEY)?.toLongOrNull() ?: return null
        val position = defaults.stringForKey(POSITION_MILLIS_KEY)?.toLongOrNull() ?: return null
        return trackId to position.coerceAtLeast(0L)
    }

    fun savePosition(trackId: Long, positionMillis: Long): Boolean {
        defaults.setObject(trackId.toString(), POSITION_TRACK_KEY)
        defaults.setObject(positionMillis.coerceAtLeast(0L).toString(), POSITION_MILLIS_KEY)
        return loadPosition() == (trackId to positionMillis.coerceAtLeast(0L))
    }

    fun clearPosition() {
        defaults.setObject(null, POSITION_TRACK_KEY)
        defaults.setObject(null, POSITION_MILLIS_KEY)
    }

    private companion object {
        const val QUEUE_KEY = "lazer.ios.playbackQueue.v1"
        const val POSITION_TRACK_KEY = "lazer.ios.playbackPosition.trackId"
        const val POSITION_MILLIS_KEY = "lazer.ios.playbackPosition.millis"
    }
}
