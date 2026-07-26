package me.kavishdevar.librepods.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ReconnectEpisodeGateTest {

    @Test
    fun `failed control reconnects exhaust the budget`() {
        val gate = ReconnectEpisodeGate(maxControlReconnectAttempts = 3)

        repeat(3) {
            val permit = assertNotNull(gate.tryBeginControlReconnect())
            gate.finishControlReconnect(permit, succeeded = false)
        }

        assertNull(gate.tryBeginControlReconnect())
    }

    @Test
    fun `successful control reconnects refund their permit`() {
        val gate = ReconnectEpisodeGate(maxControlReconnectAttempts = 3)

        repeat(10) {
            val permit = assertNotNull(gate.tryBeginControlReconnect())
            gate.finishControlReconnect(permit, succeeded = true)
        }

        repeat(3) {
            val permit = assertNotNull(gate.tryBeginControlReconnect())
            gate.finishControlReconnect(permit, succeeded = false)
        }
        assertNull(gate.tryBeginControlReconnect())
    }

    @Test
    fun `successful profile repairs refund their permit`() {
        val gate = ReconnectEpisodeGate(maxProfileRepairAttempts = 2)

        repeat(5) {
            val permit = assertNotNull(gate.tryBeginProfileRepair())
            gate.finishProfileRepair(permit, succeeded = true)
        }

        repeat(2) {
            val permit = assertNotNull(gate.tryBeginProfileRepair())
            gate.finishProfileRepair(permit, succeeded = false)
        }
        assertNull(gate.tryBeginProfileRepair())
    }

    @Test
    fun `only one control reconnect may be in flight`() {
        val gate = ReconnectEpisodeGate()

        val permit = assertNotNull(gate.tryBeginControlReconnect())
        assertNull(gate.tryBeginControlReconnect())
        gate.finishControlReconnect(permit, succeeded = true)
        assertNotNull(gate.tryBeginControlReconnect())
    }

    @Test
    fun `reset restores all budgets and invalidates stale permits`() {
        val gate = ReconnectEpisodeGate(maxControlReconnectAttempts = 1)

        val stale = assertNotNull(gate.tryBeginControlReconnect())
        assertNull(gate.tryBeginControlReconnect())

        gate.reset()
        val fresh = assertNotNull(gate.tryBeginControlReconnect())
        assertEquals(1, fresh.attempt)

        // A stale finish must neither release the in-flight slot nor refund budget.
        gate.finishControlReconnect(stale, succeeded = true)
        assertNull(gate.tryBeginControlReconnect())
        gate.finishControlReconnect(fresh, succeeded = false)
        assertNull(gate.tryBeginControlReconnect())
    }
}
