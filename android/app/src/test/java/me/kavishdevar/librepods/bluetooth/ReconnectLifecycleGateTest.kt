package me.kavishdevar.librepods.bluetooth

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
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

    @Test
    fun `profile repair fence blocks automatic connects until protection releases`() {
        val fence = AudioProfileRepairFence()
        var operations = 0

        assertTrue(fence.runIfAllowed({ true }) { operations++ })
        fence.protect()
        assertTrue(fence.isProtected)
        assertFalse(fence.runIfAllowed({ true }) { operations++ })
        assertFalse(fence.runIfAllowed({ false }) { operations++ })

        fence.release()
        assertFalse(fence.isProtected)
        assertTrue(fence.runIfAllowed({ true }) { operations++ })
        assertEquals(2, operations)
    }

    @Test
    fun `older guard release cannot clear a newer pending protection`() {
        val fence = AudioProfileRepairFence()
        var operations = 0

        fence.protect()
        fence.beginPendingProtection()
        fence.release()

        assertTrue(fence.isProtected)
        assertFalse(fence.runIfAllowed({ true }) { operations++ })

        assertTrue(fence.finishPendingProtection())
        assertFalse(fence.isProtected)
        assertTrue(fence.runIfAllowed({ true }) { operations++ })
        assertEquals(1, operations)
    }

    @Test
    fun `discarded pending protection does not leave repair fenced`() {
        val fence = AudioProfileRepairFence()

        fence.beginPendingProtection()
        assertTrue(fence.isProtected)

        assertTrue(fence.finishPendingProtection())

        assertFalse(fence.isProtected)
    }

    @Test
    fun `pending protection hands off to active guard without releasing fence`() {
        val fence = AudioProfileRepairFence()

        fence.beginPendingProtection()
        assertTrue(fence.protect())
        assertFalse(fence.finishPendingProtection())
        assertTrue(fence.isProtected)

        fence.release()
        assertFalse(fence.isProtected)
    }

    @Test
    fun `pending protection becomes visible before an in-flight operation releases its lock`() {
        val fence = AudioProfileRepairFence()
        val firstOperationStarted = CountDownLatch(1)
        val releaseFirstOperation = CountDownLatch(1)
        val pendingProtectionVisible = CountDownLatch(1)
        val operations = AtomicInteger(0)
        val secondOperationAllowed = AtomicBoolean(true)

        val worker = thread(name = "profile-repair-worker") {
            fence.runIfAllowed({ true }) {
                firstOperationStarted.countDown()
                releaseFirstOperation.await()
                operations.incrementAndGet()
            }
            secondOperationAllowed.set(
                fence.runIfAllowed({ true }) { operations.incrementAndGet() }
            )
        }

        val firstOperationDidStart = firstOperationStarted.await(5, TimeUnit.SECONDS)
        var protector: Thread? = null
        val protectionWasVisibleWhileOperationRan = try {
            if (firstOperationDidStart) {
                protector = thread(name = "profile-repair-protector") {
                    fence.beginPendingProtection()
                    pendingProtectionVisible.countDown()
                }
                pendingProtectionVisible.await(5, TimeUnit.SECONDS)
            } else {
                false
            }
        } finally {
            releaseFirstOperation.countDown()
        }

        worker.join(5_000L)
        protector?.join(5_000L)

        assertTrue(firstOperationDidStart)
        assertTrue(protectionWasVisibleWhileOperationRan)
        assertFalse(worker.isAlive)
        assertFalse(protector?.isAlive ?: false)
        assertFalse(secondOperationAllowed.get())
        assertEquals(1, operations.get())
        assertTrue(fence.finishPendingProtection())
    }
}
