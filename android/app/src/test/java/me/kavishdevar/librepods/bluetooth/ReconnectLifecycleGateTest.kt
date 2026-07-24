package me.kavishdevar.librepods.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReconnectLifecycleGateTest {
    @Test
    fun `explicit suppression invalidates work and blocks ordinary reconnect`() {
        val gate = ReconnectLifecycleGate()
        val started = assertIs<ReconnectLifecycleBegin.Started>(gate.begin(freshPresence = false))

        gate.suppress()

        assertFalse(gate.isCurrent(started.token))
        assertIs<ReconnectLifecycleBegin.Suppressed>(gate.begin(freshPresence = false))
        assertNull(gate.currentToken())

        gate.invalidate()

        assertIs<ReconnectLifecycleBegin.Suppressed>(gate.begin(freshPresence = false))
    }

    @Test
    fun `fresh presence or explicit allow clears suppression`() {
        val gate = ReconnectLifecycleGate()
        gate.suppress()

        val freshPresence = assertIs<ReconnectLifecycleBegin.Started>(
            gate.begin(freshPresence = true)
        )
        assertTrue(gate.isCurrent(freshPresence.token))

        gate.suppress()
        gate.allow()
        assertIs<ReconnectLifecycleBegin.Started>(gate.begin(freshPresence = false))
    }

    @Test
    fun `stale finish cannot clear replacement reconnect`() {
        val gate = ReconnectLifecycleGate()
        val stale = assertIs<ReconnectLifecycleBegin.Started>(gate.begin(false)).token
        gate.invalidate()
        val replacement = assertIs<ReconnectLifecycleBegin.Started>(gate.begin(false)).token

        gate.finish(stale)

        val stillInFlight = assertIs<ReconnectLifecycleBegin.InFlight>(gate.begin(false))
        assertEquals(replacement, stillInFlight.token)
        assertNotEquals(stale, replacement)

        gate.finish(replacement)
        assertIs<ReconnectLifecycleBegin.Started>(gate.begin(false))
    }

    @Test
    fun `coalesced begin reuses the active token`() {
        val gate = ReconnectLifecycleGate()
        val started = assertIs<ReconnectLifecycleBegin.Started>(gate.begin(false))
        val coalesced = assertIs<ReconnectLifecycleBegin.InFlight>(gate.begin(false))

        assertEquals(started.token, coalesced.token)
    }
}
