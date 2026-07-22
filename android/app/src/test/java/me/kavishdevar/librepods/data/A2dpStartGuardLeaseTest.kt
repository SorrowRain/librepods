package me.kavishdevar.librepods.data

import kotlin.test.Test
import kotlin.test.assertEquals

class A2dpStartGuardLeaseTest {
    @Test
    fun `only unchanged even revisions are stable snapshots`() {
        assertEquals(true, A2dpStartGuardLease.isStableRevision(0L, 0L))
        assertEquals(true, A2dpStartGuardLease.isStableRevision(4L, 4L))
        assertEquals(false, A2dpStartGuardLease.isStableRevision(3L, 3L))
        assertEquals(false, A2dpStartGuardLease.isStableRevision(4L, 6L))
    }

    @Test
    fun `requested duration is bounded to a short lease`() {
        assertEquals(0L, A2dpStartGuardLease.clampRequestedRemainingMs(-1L))
        assertEquals(5_000L, A2dpStartGuardLease.clampRequestedRemainingMs(5_000L))
        assertEquals(
            A2dpStartGuardLease.MAX_REMAINING_MS,
            A2dpStartGuardLease.clampRequestedRemainingMs(Long.MAX_VALUE),
        )
    }

    @Test
    fun `remaining duration expires and disabled state always clears`() {
        assertEquals(
            4_000L,
            A2dpStartGuardLease.remainingMs(
                enabled = true,
                expiryElapsedRealtimeMs = 5_000L,
                leaseBootId = "boot-a",
                currentBootId = "boot-a",
                nowElapsedRealtimeMs = 1_000L,
            ),
        )
        assertEquals(
            0L,
            A2dpStartGuardLease.remainingMs(
                enabled = true,
                expiryElapsedRealtimeMs = 1_000L,
                leaseBootId = "boot-a",
                currentBootId = "boot-a",
                nowElapsedRealtimeMs = 1_000L,
            ),
        )
        assertEquals(
            0L,
            A2dpStartGuardLease.remainingMs(
                enabled = false,
                expiryElapsedRealtimeMs = 5_000L,
                leaseBootId = "boot-a",
                currentBootId = "boot-a",
                nowElapsedRealtimeMs = 1_000L,
            ),
        )
    }

    @Test
    fun `implausible future expiry fails open after reboot or corrupt write`() {
        assertEquals(
            0L,
            A2dpStartGuardLease.remainingMs(
                enabled = true,
                expiryElapsedRealtimeMs = A2dpStartGuardLease.MAX_REMAINING_MS + 1_001L,
                leaseBootId = "boot-a",
                currentBootId = "boot-a",
                nowElapsedRealtimeMs = 1_000L,
            ),
        )
    }

    @Test
    fun `lease from another boot fails open`() {
        assertEquals(
            0L,
            A2dpStartGuardLease.remainingMs(
                enabled = true,
                expiryElapsedRealtimeMs = 5_000L,
                leaseBootId = "boot-a",
                currentBootId = "boot-b",
                nowElapsedRealtimeMs = 1_000L,
            ),
        )
    }
}
