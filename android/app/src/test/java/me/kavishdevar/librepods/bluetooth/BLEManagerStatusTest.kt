package me.kavishdevar.librepods.bluetooth

import kotlin.test.Test
import kotlin.test.assertFalse
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
}
