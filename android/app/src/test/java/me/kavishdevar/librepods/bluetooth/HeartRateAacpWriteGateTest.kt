package me.kavishdevar.librepods.bluetooth

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HeartRateAacpWriteGateTest {
    @Test
    fun `queued stale start is rejected before stop commits`() {
        val gate = HeartRateAacpWriteGate()
        val executor = Executors.newFixedThreadPool(3)
        val blockerEntered = CountDownLatch(1)
        val releaseBlocker = CountDownLatch(1)
        val startAttempted = CountDownLatch(1)
        val stopAttempted = CountDownLatch(1)
        val acquisitionCurrent = AtomicBoolean(true)
        val startWrites = AtomicInteger()
        val startThread = AtomicReference<Thread>()
        val stopThread = AtomicReference<Thread>()
        val writes = Collections.synchronizedList(mutableListOf<String>())

        try {
            val blocker = executor.submit<Boolean> {
                gate.writeIfCurrent(
                    isCurrent = { true },
                    write = {
                        blockerEntered.countDown()
                        assertTrue(releaseBlocker.await(5, TimeUnit.SECONDS))
                        true
                    },
                )
            }
            assertTrue(blockerEntered.await(5, TimeUnit.SECONDS))

            val staleStart = executor.submit<Boolean> {
                startThread.set(Thread.currentThread())
                startAttempted.countDown()
                gate.writeIfCurrent(
                    isCurrent = acquisitionCurrent::get,
                    write = {
                        startWrites.incrementAndGet()
                        writes += "start"
                        true
                    },
                )
            }
            assertTrue(startAttempted.await(5, TimeUnit.SECONDS))
            assertTrue(awaitBlocked(startThread))
            acquisitionCurrent.set(false)

            val stop = executor.submit<Boolean> {
                stopThread.set(Thread.currentThread())
                stopAttempted.countDown()
                gate.writeIfCurrent(
                    isCurrent = { true },
                    write = {
                        writes += "stop"
                        true
                    },
                )
            }
            assertTrue(stopAttempted.await(5, TimeUnit.SECONDS))
            assertTrue(awaitBlocked(stopThread))
            assertFalse(staleStart.isDone)
            assertFalse(stop.isDone)

            releaseBlocker.countDown()
            assertTrue(blocker.get(5, TimeUnit.SECONDS))
            assertFalse(staleStart.get(5, TimeUnit.SECONDS))
            assertTrue(stop.get(5, TimeUnit.SECONDS))
            assertEquals(0, startWrites.get())
            assertEquals(listOf("stop"), writes)
        } finally {
            releaseBlocker.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `in flight start completes before a following stop`() {
        val gate = HeartRateAacpWriteGate()
        val executor = Executors.newFixedThreadPool(2)
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val stopAttempted = CountDownLatch(1)
        val stopThread = AtomicReference<Thread>()
        val writes = Collections.synchronizedList(mutableListOf<String>())

        try {
            val start = executor.submit<Boolean> {
                gate.writeIfCurrent(
                    isCurrent = { true },
                    write = {
                        startEntered.countDown()
                        assertTrue(releaseStart.await(5, TimeUnit.SECONDS))
                        writes += "start"
                        true
                    },
                )
            }
            assertTrue(startEntered.await(5, TimeUnit.SECONDS))

            val stop = executor.submit<Boolean> {
                stopThread.set(Thread.currentThread())
                stopAttempted.countDown()
                gate.writeIfCurrent(
                    isCurrent = { true },
                    write = {
                        writes += "stop"
                        true
                    },
                )
            }
            assertTrue(stopAttempted.await(5, TimeUnit.SECONDS))
            assertTrue(awaitBlocked(stopThread))
            assertFalse(stop.isDone)

            releaseStart.countDown()
            assertTrue(start.get(5, TimeUnit.SECONDS))
            assertTrue(stop.get(5, TimeUnit.SECONDS))
            assertEquals(listOf("start", "stop"), writes)
        } finally {
            releaseStart.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun awaitBlocked(thread: AtomicReference<Thread>): Boolean {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadlineNanos) {
            if (thread.get()?.state == Thread.State.BLOCKED) return true
            Thread.yield()
        }
        return false
    }
}
