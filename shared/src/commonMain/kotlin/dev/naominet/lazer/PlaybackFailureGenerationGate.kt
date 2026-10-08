package dev.naominet.lazer

/** Accepts at most one native playback failure for the currently selected playback generation. */
internal class PlaybackFailureGenerationGate {
    private var activeGeneration: Long? = null
    private var reported = false

    fun begin(generation: Long) {
        activeGeneration = generation
        reported = false
    }

    fun accept(generation: Long): Boolean {
        if (generation != activeGeneration || reported) return false
        reported = true
        return true
    }
}
