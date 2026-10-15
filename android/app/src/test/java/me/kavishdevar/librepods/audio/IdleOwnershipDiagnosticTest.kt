package me.kavishdevar.librepods.audio

import org.junit.Assert.*
import org.junit.Test

class IdleOwnershipDiagnosticTest {
    private fun idle() = AudioHandoffState(
        session = 1, owner = AudioOwner.NONE, sourceKind = AudioSourceKind.NONE,
        connectedAtMs = 100, sourceObservedAtMs = 200)

    @Test fun `fresh idle source permits the isolated release command`() {
        assertTrue(idle().canSynchronizeIdleOwnership(300))
    }
    @Test fun `playing media or a call cannot be released by the diagnostic`() {
        assertFalse(idle().copy(mediaPlaying = true).canSynchronizeIdleOwnership(300))
        assertFalse(idle().copy(callActive = true).canSynchronizeIdleOwnership(300))
    }
    @Test fun `unknown remote or stale evidence does not permit the command`() {
        assertFalse(idle().copy(owner = AudioOwner.UNKNOWN).canSynchronizeIdleOwnership(300))
        assertFalse(idle().copy(owner = AudioOwner.REMOTE).canSynchronizeIdleOwnership(300))
        assertFalse(idle().canSynchronizeIdleOwnership(6_201))
        assertFalse(idle().copy(connectedAtMs = 250).canSynchronizeIdleOwnership(300))
    }
    @Test fun `an active handoff or explicit disconnect cannot be overridden`() {
        val operation = HandoffOperation(2, HandoffTrigger.MANUAL, 100, 12_100)
        assertFalse(idle().copy(operation = operation).canSynchronizeIdleOwnership(300))
        assertFalse(idle().copy(suppressed = true).canSynchronizeIdleOwnership(300))
        assertFalse(idle().copy(session = null).canSynchronizeIdleOwnership(300))
        assertFalse(idle().copy(protectRemote = true).canSynchronizeIdleOwnership(300))
    }
}
