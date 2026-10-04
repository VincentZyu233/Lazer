package dev.naominet.lazer

/** Prevents a late native open from making a superseded playback token active again. */
internal class DesktopPlaybackTokenGate {
    private var requestGeneration = 0L
    private var activeToken = 0L

    @Synchronized
    fun beginRequest(): Long {
        requestGeneration += 1L
        activeToken = 0L
        return requestGeneration
    }

    @Synchronized
    fun activateIfCurrent(request: Long, token: Long): Boolean {
        if (request != requestGeneration || token == 0L) return false
        activeToken = token
        return true
    }

    @Synchronized
    fun isActive(request: Long, token: Long): Boolean =
        request == requestGeneration && token != 0L && activeToken == token

    @Synchronized
    fun isCurrent(request: Long): Boolean = request == requestGeneration

    @Synchronized
    fun get(): Long = activeToken

    @Synchronized
    fun compareAndSet(expected: Long, updated: Long): Boolean {
        if (activeToken != expected) return false
        activeToken = updated
        return true
    }
}
