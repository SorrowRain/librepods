package me.kavishdevar.librepods.bluetooth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AacpSocketLeaseGateTest {
    @Test
    fun `lease requires both socket identity and generation`() {
        val current = Any()

        assertTrue(AacpSocketLeaseGate.isCurrent(current, 4L, current, 4L))
        assertFalse(AacpSocketLeaseGate.isCurrent(current, 5L, current, 4L))
        assertFalse(AacpSocketLeaseGate.isCurrent(current, 4L, Any(), 4L))
    }

    @Test
    fun `stale disconnect lease cannot claim a replacement socket`() {
        val staleSocket = Any()
        val replacementSocket = Any()

        assertFalse(
            AacpSocketLeaseGate.isCurrent(
                currentSocket = replacementSocket,
                currentGeneration = 8L,
                leaseSocket = staleSocket,
                leaseGeneration = 7L,
            )
        )
    }

    @Test
    fun `stale session cannot clear a replacement session`() {
        val oldSocket = Any()
        val replacementSocket = Any()

        assertFalse(
            AacpSessionCleanupGate.canClear(
                boundSocket = replacementSocket,
                boundGeneration = 5L,
                expectedSocket = oldSocket,
                expectedGeneration = 4L,
            )
        )
        assertFalse(
            AacpSessionCleanupGate.canClear(
                boundSocket = oldSocket,
                boundGeneration = 5L,
                expectedSocket = oldSocket,
                expectedGeneration = 4L,
            )
        )
        assertTrue(
            AacpSessionCleanupGate.canClear(
                boundSocket = replacementSocket,
                boundGeneration = 5L,
                expectedSocket = replacementSocket,
                expectedGeneration = 5L,
            )
        )
    }

    @Test
    fun `unscoped shutdown cleanup remains allowed`() {
        val currentSocket = Any()

        assertFalse(
            AacpSessionCleanupGate.canClear(
                boundSocket = currentSocket,
                boundGeneration = 6L,
                expectedSocket = null,
                expectedGeneration = null,
            )
        )
        assertTrue(
            AacpSessionCleanupGate.canClear(
                boundSocket = null,
                boundGeneration = null,
                expectedSocket = null,
                expectedGeneration = null,
            )
        )
    }
}
