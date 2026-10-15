package me.kavishdevar.librepods.audio

import org.junit.Assert.*
import org.junit.Test

class LocalPlaybackIntentTrackerTest {
    @Test fun `startup does not seize audio for an existing player`() {
        assertFalse(LocalPlaybackIntentTracker().observe(setOf("player"), setOf("player")))
    }
    @Test fun `new matching player is emitted only once`() {
        val tracker = LocalPlaybackIntentTracker()
        tracker.observe(emptySet(), emptySet())
        assertTrue(tracker.observe(setOf("player"), setOf("player")))
        assertFalse(tracker.observe(setOf("player"), setOf("player")))
    }
    @Test fun `notification or focusless media does not acquire headphones`() {
        val tracker = LocalPlaybackIntentTracker()
        tracker.observe(emptySet(), emptySet())
        assertFalse(tracker.observe(emptySet(), setOf("notification")))
        assertFalse(tracker.observe(setOf("preview"), emptySet()))
    }
    @Test fun `focus arriving just after a playback edge can confirm the same intent`() {
        val tracker = LocalPlaybackIntentTracker()
        tracker.observe(emptySet(), emptySet(), 0)
        assertFalse(tracker.observe(setOf("player"), emptySet(), 100))
        assertTrue(tracker.observe(setOf("player"), setOf("player"), 500))
        assertFalse(tracker.observe(setOf("player"), setOf("player"), 600))
    }
    @Test fun `old playback cannot acquire headphones after a much later focus grant`() {
        val tracker = LocalPlaybackIntentTracker()
        tracker.observe(emptySet(), emptySet(), 0)
        tracker.observe(setOf("player"), emptySet(), 100)
        assertFalse(tracker.observe(setOf("player"), setOf("player"), 2_000))
    }
}
