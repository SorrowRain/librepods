package me.kavishdevar.librepods.audio

/** Require a new MediaSession playback edge, matching media track and full audio-focus grant. */
internal class LocalPlaybackIntentTracker {
    private var previousSessions: Set<String>? = null
    private val pendingStarts = mutableMapOf<String, Long>()

    fun observe(playingSessions: Set<String>, eligibleSessions: Set<String>, nowMs: Long = 0): Boolean {
        val previous = previousSessions
        previousSessions = playingSessions
        // Starting the observer must not turn an existing background player into a new intent.
        if (previous == null) return false
        pendingStarts.keys.retainAll(playingSessions)
        pendingStarts.entries.removeAll { nowMs - it.value > 1_500 }
        (playingSessions - previous).forEach { pendingStarts[it] = nowMs }
        val ready = pendingStarts.keys.intersect(eligibleSessions)
        ready.forEach(pendingStarts::remove)
        return ready.isNotEmpty()
    }

    fun reset() { previousSessions = null; pendingStarts.clear() }
}
