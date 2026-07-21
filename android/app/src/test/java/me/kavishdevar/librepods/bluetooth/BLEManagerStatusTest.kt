package me.kavishdevar.librepods.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BLEManagerStatusTest {
    @Test
    fun `last seen timestamp does not create a device status transition`() {
        val first = BLEManager.AirPodsStatus(
            address = "02:11:22:33:44:55",
            lastSeen = 1_000L,
            connectionState = "Idle"
        )
        val refreshed = first.copy(lastSeen = 6_500L)

        assertTrue(refreshed.hasSameObservableState(first))
    }

    @Test
    fun `connection and ear changes remain observable`() {
        val initial = BLEManager.AirPodsStatus(
            address = "02:11:22:33:44:55",
            lastSeen = 1_000L,
            connectionState = "Disconnected"
        )

        assertFalse(
            initial.copy(lastSeen = 2_000L, connectionState = "Idle")
                .hasSameObservableState(initial)
        )
        assertFalse(
            initial.copy(lastSeen = 2_000L, isLeftInEar = true)
                .hasSameObservableState(initial)
        )
    }

    @Test
    fun `lid timeout makes a same address reopen observable`() {
        val open = BLEManager.AirPodsStatus(
            address = "02:11:22:33:44:55",
            lastSeen = 1_000L,
            lidOpen = true,
        )
        val timedOut = BLEManager.markLidClosedAfterTimeout(open)
        val reopened = timedOut.copy(lastSeen = 2_000L, lidOpen = true)

        assertFalse(timedOut.lidOpen)
        assertFalse(reopened.hasSameObservableState(timedOut))
    }

    @Test
    fun `batched HyperOS advertisements do not create false lid close edges`() {
        val lastBroadcastAt = 10_000L

        assertFalse(
            BLEManager.shouldInferLidClosed(
                now = lastBroadcastAt + 5_500L,
                lastBroadcastAt = lastBroadcastAt,
                lidOpen = true,
            )
        )
        assertFalse(
            BLEManager.shouldInferLidClosed(
                now = lastBroadcastAt + BLEManager.LID_CLOSE_TIMEOUT_MS,
                lastBroadcastAt = lastBroadcastAt,
                lidOpen = true,
            )
        )
        assertTrue(
            BLEManager.shouldInferLidClosed(
                now = lastBroadcastAt + BLEManager.LID_CLOSE_TIMEOUT_MS + 1L,
                lastBroadcastAt = lastBroadcastAt,
                lidOpen = true,
            )
        )
        assertFalse(
            BLEManager.shouldInferLidClosed(
                now = lastBroadcastAt + BLEManager.LID_CLOSE_TIMEOUT_MS + 1L,
                lastBroadcastAt = lastBroadcastAt,
                lidOpen = false,
            )
        )
    }

    @Test
    fun `reconnect budget resets only for a new presence episode`() {
        val gate = ReconnectEpisodeGate(
            maxProfileRepairAttempts = 2,
            maxControlReconnectAttempts = 3,
        )
        val firstGeneration = assertNotNull(gate.tryBeginInitialConnect())

        assertNull(gate.tryBeginInitialConnect())
        val firstRepair = assertNotNull(gate.tryBeginProfileRepair())
        assertEquals(1, firstRepair.attempt)
        assertNull(gate.tryBeginProfileRepair())
        gate.finishProfileRepair(firstRepair)
        val secondRepair = assertNotNull(gate.tryBeginProfileRepair())
        assertEquals(2, secondRepair.attempt)
        gate.finishProfileRepair(secondRepair)
        assertNull(gate.tryBeginProfileRepair())
        repeat(3) { index ->
            val controlPermit = assertNotNull(gate.tryBeginControlReconnect())
            assertEquals(index + 1, controlPermit.attempt)
            assertNull(gate.tryBeginControlReconnect())
            gate.finishControlReconnect(controlPermit)
        }
        assertNull(gate.tryBeginControlReconnect())
        assertTrue(gate.isCurrent(firstGeneration))

        gate.reset()

        val secondGeneration = assertNotNull(gate.tryBeginInitialConnect())
        assertFalse(gate.isCurrent(firstGeneration))
        assertTrue(gate.isCurrent(secondGeneration))
        assertEquals(1, assertNotNull(gate.tryBeginProfileRepair()).attempt)
        assertEquals(1, assertNotNull(gate.tryBeginControlReconnect()).attempt)
    }
}
