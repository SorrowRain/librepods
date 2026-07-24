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
            connectionState = "Idle",
        )

        assertTrue(first.copy(lastSeen = 6_500L).hasSameObservableState(first))
    }

    @Test
    fun `connection and ear changes remain observable`() {
        val initial = BLEManager.AirPodsStatus(
            address = "02:11:22:33:44:55",
            lastSeen = 1_000L,
            connectionState = "Disconnected",
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
    fun `batched advertisements do not create false lid close edges`() {
        val lastBroadcastAt = 10_000L

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
            maxInitialConnectAttempts = 2,
            maxProfileRepairAttempts = 2,
            maxControlReconnectAttempts = 3,
        )
        val firstInitial = assertNotNull(gate.tryBeginInitialConnect())
        val firstGeneration = firstInitial.generation

        assertNull(gate.tryBeginInitialConnect())
        gate.finishInitialConnect(firstInitial, succeeded = true)
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

        val secondGeneration = assertNotNull(gate.tryBeginInitialConnect()).generation
        assertFalse(gate.isCurrent(firstGeneration))
        assertTrue(gate.isCurrent(secondGeneration))
        assertEquals(1, assertNotNull(gate.tryBeginProfileRepair()).attempt)
        assertEquals(1, assertNotNull(gate.tryBeginControlReconnect()).attempt)
    }

    @Test
    fun `failed early reconnect leaves one retry for the in-ear edge`() {
        val gate = ReconnectEpisodeGate(maxInitialConnectAttempts = 2)
        val lidOpen = assertNotNull(gate.tryBeginInitialConnect())

        gate.finishInitialConnect(lidOpen, succeeded = false)

        val inEar = assertNotNull(gate.tryBeginInitialConnect())
        assertEquals(2, inEar.attempt)
        gate.finishInitialConnect(inEar, succeeded = false)
        assertNull(gate.tryBeginInitialConnect())
    }

    @Test
    fun `finishing stale reconnect work cannot release a new episode permit`() {
        val gate = ReconnectEpisodeGate()
        val staleProfile = assertNotNull(gate.tryBeginProfileRepair())
        val staleControl = assertNotNull(gate.tryBeginControlReconnect())

        gate.reset()
        val currentProfile = assertNotNull(gate.tryBeginProfileRepair())
        val currentControl = assertNotNull(gate.tryBeginControlReconnect())

        gate.finishProfileRepair(staleProfile)
        gate.finishControlReconnect(staleControl)

        assertNull(gate.tryBeginProfileRepair())
        assertNull(gate.tryBeginControlReconnect())

        gate.finishProfileRepair(currentProfile)
        gate.finishControlReconnect(currentControl)
        assertEquals(2, assertNotNull(gate.tryBeginProfileRepair()).attempt)
        assertEquals(2, assertNotNull(gate.tryBeginControlReconnect()).attempt)
    }
}
