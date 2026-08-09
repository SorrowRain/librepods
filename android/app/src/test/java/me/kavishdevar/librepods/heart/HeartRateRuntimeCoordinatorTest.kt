package me.kavishdevar.librepods.heart

import java.io.IOException
import java.util.ArrayDeque
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import me.kavishdevar.librepods.bluetooth.HeartRateSample
import me.kavishdevar.librepods.data.Capability

class HeartRateRuntimeCoordinatorTest {
    @Test
    fun `heart rate preempts head tracking and disable resumes it in action order`() = runBlocking {
        val fixture = Fixture()
        val key = key(epoch = 1L)
        fixture.coordinator.installConnectionAndAwait(key)
        fixture.coordinator.requestHeadTracking(key, HeadTrackingRequestSource.SPATIAL_AUDIO)
        settle()
        assertTrue(fixture.coordinator.headTrackingActive.value)

        fixture.coordinator.setEnabledAndAwait(true)
        fixture.confirmSupported(key)
        fixture.coordinator.updateWear(key, HeartRateWearState(leftWorn = true))
        settle()

        assertEquals(
            listOf("start-head-1", "stop-head-1", "start-heart-1"),
            fixture.sink.events,
        )
        assertFalse(fixture.coordinator.headTrackingActive.value)
        assertEquals(HeartRateSessionStatus.STARTING, fixture.coordinator.session.value.status)

        fixture.coordinator.setEnabledAndAwait(false)
        settle()
        assertEquals(
            listOf(
                "start-head-1",
                "stop-head-1",
                "start-heart-1",
                "stop-heart-1",
                "start-head-1",
            ),
            fixture.sink.events,
        )
        assertEquals(listOf(CleanupUse(first = true, second = false)), fixture.sink.heartStops)
        assertEquals(listOf(CleanupUse(first = true, second = false)), fixture.sink.headStops)
        assertTrue(fixture.coordinator.headTrackingActive.value)

        fixture.coordinator.releaseHeadTracking(key, HeadTrackingRequestSource.SPATIAL_AUDIO)
        settle()
        assertEquals("stop-head-1", fixture.sink.events.last())
        assertFalse(fixture.coordinator.headTrackingActive.value)
        fixture.close()
    }

    @Test
    fun `heart rate preemption revokes stopped gesture intent without reviving it`() = runBlocking {
        val fixture = Fixture()
        val key = key(epoch = 1L)
        fixture.coordinator.installConnectionAndAwait(key)
        fixture.coordinator.requestHeadTracking(key, HeadTrackingRequestSource.GESTURE)
        settle()
        assertEquals(listOf("start-head-1"), fixture.sink.events)

        fixture.coordinator.setEnabledAndAwait(true)
        fixture.confirmSupported(key)
        fixture.coordinator.updateWear(key, HeartRateWearState(leftWorn = true))
        settle()
        assertEquals(
            listOf("start-head-1", "stop-head-1", "start-heart-1"),
            fixture.sink.events,
        )

        fixture.coordinator.setEnabledAndAwait(false)
        settle()
        assertEquals(
            listOf("start-head-1", "stop-head-1", "start-heart-1", "stop-heart-1"),
            fixture.sink.events,
        )
        assertFalse(fixture.coordinator.headTrackingActive.value)
        fixture.close()
    }

    @Test
    fun `gesture revocation during preemption preserves another head tracking source`() = runBlocking {
        val fixture = Fixture()
        val key = key(epoch = 1L)
        fixture.coordinator.installConnectionAndAwait(key)
        fixture.coordinator.requestHeadTracking(key, HeadTrackingRequestSource.GESTURE)
        fixture.coordinator.requestHeadTracking(key, HeadTrackingRequestSource.SPATIAL_AUDIO)
        settle()

        fixture.coordinator.setEnabledAndAwait(true)
        fixture.confirmSupported(key)
        fixture.coordinator.updateWear(key, HeartRateWearState(rightWorn = true))
        settle()
        fixture.coordinator.setEnabledAndAwait(false)
        settle()

        assertEquals(
            listOf(
                "start-head-1",
                "stop-head-1",
                "start-heart-1",
                "stop-heart-1",
                "start-head-1",
            ),
            fixture.sink.events,
        )
        assertTrue(fixture.coordinator.headTrackingActive.value)
        fixture.close()
    }

    @Test
    fun `gesture requiring immediate grant is rejected while heart rate owns sensor`() = runBlocking {
        val fixture = Fixture()
        val key = key(epoch = 1L)
        fixture.connectSupportedAndWorn(key)

        assertFalse(
            fixture.coordinator.requestHeadTrackingImmediately(
                key,
                HeadTrackingRequestSource.GESTURE,
            )
        )
        fixture.coordinator.setEnabledAndAwait(false)
        settle()

        assertEquals(listOf("start-heart-1", "stop-heart-1"), fixture.sink.events)
        assertFalse(fixture.coordinator.headTrackingActive.value)
        fixture.close()
    }

    @Test
    fun `rejected gesture test does not survive while deferred UI tracking resumes`() = runBlocking {
        val fixture = Fixture()
        val key = key(epoch = 1L)
        fixture.connectSupportedAndWorn(key)
        fixture.coordinator.requestHeadTracking(key, HeadTrackingRequestSource.UI_SCREEN)
        settle()

        assertFalse(
            fixture.coordinator.requestHeadTrackingImmediately(
                key,
                HeadTrackingRequestSource.GESTURE,
            )
        )
        fixture.coordinator.setEnabledAndAwait(false)
        settle()

        assertEquals(
            listOf("start-heart-1", "stop-heart-1", "start-head-1"),
            fixture.sink.events,
        )
        assertTrue(fixture.coordinator.headTrackingActive.value)

        fixture.coordinator.releaseHeadTracking(key, HeadTrackingRequestSource.UI_SCREEN)
        settle()
        assertEquals("stop-head-1", fixture.sink.events.last())
        assertFalse(fixture.coordinator.headTrackingActive.value)
        fixture.close()
    }

    @Test
    fun `gesture requiring immediate grant starts only when transport grants it`() = runBlocking {
        val fixture = Fixture()
        val key = key(epoch = 1L)
        fixture.coordinator.installConnectionAndAwait(key)

        assertTrue(
            fixture.coordinator.requestHeadTrackingImmediately(
                key,
                HeadTrackingRequestSource.GESTURE,
            )
        )
        assertEquals(listOf("start-head-1"), fixture.sink.events)
        assertTrue(fixture.coordinator.headTrackingActive.value)

        fixture.coordinator.releaseHeadTracking(key, HeadTrackingRequestSource.GESTURE)
        settle()
        assertEquals(listOf("start-head-1", "stop-head-1"), fixture.sink.events)
        fixture.close()
    }

    @Test
    fun `failed and throwing starts retry once then stop cleanly`() = runBlocking {
        val fixture = Fixture(StartBehavior.THROW, StartBehavior.FAILURE)
        val key = key(epoch = 1L)

        fixture.connectSupportedAndWorn(key)
        settle()

        assertEquals(
            listOf("start-heart-1", "stop-heart-1", "start-heart-2", "stop-heart-2"),
            fixture.sink.events,
        )
        assertEquals(HeartRateSessionStatus.COULDNT_START, fixture.coordinator.session.value.status)
        assertEquals(2L, fixture.coordinator.session.value.acquisition?.acquisition)
        assertEquals(2, fixture.sink.heartStops.size)
        assertTrue(fixture.sink.heartStops.all { it == CleanupUse(true, false) })
        fixture.close()
    }

    @Test
    fun `first sample watchdog restarts once and a second timeout fails`() = runBlocking {
        val fixture = Fixture(StartBehavior.SUCCESS, StartBehavior.SUCCESS)
        val key = key(epoch = 1L)
        fixture.connectSupportedAndWorn(key)
        assertEquals(listOf(8_000L), fixture.delays.pendingDurations())

        fixture.clock.nowMillis = 8_000L
        fixture.delays.resumeFirst(8_000L)
        settle()
        assertEquals(
            listOf("start-heart-1", "stop-heart-1", "start-heart-2"),
            fixture.sink.events,
        )
        assertEquals(HeartRateSessionStatus.RECONNECTING, fixture.coordinator.session.value.status)
        assertEquals(1, fixture.coordinator.session.value.restartAttempts)
        assertEquals(2L, fixture.coordinator.session.value.acquisition?.acquisition)
        assertEquals(listOf(8_000L), fixture.delays.pendingDurations())

        fixture.clock.nowMillis = 16_000L
        fixture.delays.resumeFirst(8_000L)
        settle()
        assertEquals("stop-heart-2", fixture.sink.events.last())
        assertEquals(HeartRateSessionStatus.COULDNT_START, fixture.coordinator.session.value.status)
        assertTrue(fixture.delays.pendingDurations().isEmpty())
        fixture.close()
    }

    @Test
    fun `stream stall after live sample publishes then restarts and clears presentation`() = runBlocking {
        val fixture = Fixture(StartBehavior.SUCCESS, StartBehavior.SUCCESS)
        val key = key(epoch = 1L)
        fixture.connectSupportedAndWorn(key)

        repeat(HeartRateSessionReducer.WARMUP_SAMPLE_COUNT + 1) { index ->
            fixture.clock.nowMillis = index * 100L
            fixture.coordinator.acceptSample(key, sample(index + 1, fixture.clock.nowMillis))
            settle()
        }

        assertEquals(HeartRateSessionStatus.LIVE, fixture.coordinator.session.value.status)
        assertEquals(1, fixture.sink.published.size)
        assertEquals(HeartRateUiStatus.LIVE, fixture.coordinator.uiState.value.status)
        assertEquals(80, fixture.coordinator.uiState.value.latestBpm)
        assertTrue(2_000L in fixture.delays.pendingDurations())

        fixture.clock.nowMillis += HeartRateSessionReducer.STREAM_STALL_TIMEOUT_MILLIS
        fixture.delays.resumeFirst(HeartRateSessionReducer.STREAM_STALL_TIMEOUT_MILLIS)
        settle()

        assertEquals(
            listOf("start-heart-1", "publish-5", "stop-heart-1", "start-heart-2"),
            fixture.sink.events,
        )
        assertEquals(HeartRateSessionStatus.RECONNECTING, fixture.coordinator.session.value.status)
        assertNull(fixture.coordinator.uiState.value.latestBpm)
        assertTrue(
            HeartRateUiStateMapper.LIVE_SAMPLE_FRESHNESS_MILLIS + 1L !in
                fixture.delays.pendingDurations()
        )
        fixture.close()
    }

    @Test
    fun `stale lease events cannot mutate or stop the current session`() = runBlocking {
        val fixture = Fixture(StartBehavior.SUCCESS, StartBehavior.SUCCESS)
        val staleKey = key(epoch = 1L)
        fixture.connectSupportedAndWorn(staleKey)

        val currentKey = key(
            socket = Any(),
            generation = 2L,
            deviceKey = "current-device",
            epoch = 2L,
        )
        fixture.coordinator.installConnectionAndAwait(currentKey)
        fixture.confirmSupported(currentKey)
        fixture.coordinator.updateWear(currentKey, HeartRateWearState(rightWorn = true))
        settle()
        val currentAcquisition = fixture.coordinator.session.value.acquisition
        val eventCount = fixture.sink.events.size

        fixture.coordinator.confirmDeviceInformation(
            staleKey,
            "A3063",
            setOf(Capability.HRM),
        )
        fixture.coordinator.updateWear(staleKey, HeartRateWearState())
        fixture.coordinator.requestHeadTracking(staleKey, HeadTrackingRequestSource.GESTURE)
        fixture.coordinator.releaseHeadTracking(staleKey, HeadTrackingRequestSource.GESTURE)
        fixture.coordinator.acceptSample(staleKey, sample(99, fixture.clock.nowMillis))
        fixture.coordinator.disconnectAndAwait(staleKey)
        settle()

        assertTrue(fixture.coordinator.currentKey()?.matches(currentKey) == true)
        assertEquals(currentAcquisition, fixture.coordinator.session.value.acquisition)
        assertEquals(eventCount, fixture.sink.events.size)
        assertEquals(0, fixture.sink.published.size)
        fixture.close()
    }

    @Test
    fun `disconnect stops current capture and stale disconnect is ignored`() = runBlocking {
        val fixture = Fixture()
        val key = key(epoch = 3L)
        fixture.connectSupportedAndWorn(key)
        val acquisition = fixture.coordinator.session.value.acquisition

        fixture.coordinator.disconnectAndAwait(
            key(socket = Any(), generation = 2L, deviceKey = "stale", epoch = 2L)
        )
        assertEquals(listOf("start-heart-1"), fixture.sink.events)

        fixture.coordinator.disconnectAndAwait(key)
        settle()
        assertEquals(listOf("start-heart-1", "stop-heart-1"), fixture.sink.events)
        assertEquals(HeartRateSessionStatus.RECONNECTING, fixture.coordinator.session.value.status)
        assertNull(fixture.coordinator.currentKey())
        assertFalse(fixture.coordinator.isAcquisitionCurrent(requireNotNull(acquisition)))
        assertTrue(fixture.delays.pendingDurations().isEmpty())
        fixture.close()
    }

    @Test
    fun `connection replacement cancels pending start and old callback cannot write`() = runBlocking {
        val fixture = Fixture(StartBehavior.SUSPEND)
        val oldKey = key(epoch = 1L)
        fixture.connectSupportedAndWorn(oldKey)
        val oldStart = fixture.sink.starts.single()
        assertTrue(oldStart.isCurrent())
        assertFalse(oldStart.cancelled)

        val newKey = key(
            socket = Any(),
            generation = 2L,
            deviceKey = "replacement",
            epoch = 2L,
        )
        fixture.coordinator.installConnectionAndAwait(newKey)
        settle()

        assertTrue(oldStart.cancelled)
        assertFalse(oldStart.isCurrent())
        assertFalse(fixture.sink.attemptWrite(oldStart))
        assertEquals(0, fixture.sink.allowedWrites)
        assertEquals(listOf("start-heart-1", "stop-heart-1", "write-heart-1-false"), fixture.sink.events)
        assertTrue(fixture.coordinator.currentKey()?.matches(newKey) == true)
        assertEquals(HeartRateSessionStatus.WAITING_FOR_AIRPODS, fixture.coordinator.session.value.status)
        fixture.close()
    }

    @Test
    fun `destroy removes deferred head claim without reviving it and is idempotent`() = runBlocking {
        val fixture = Fixture()
        val key = key(epoch = 1L)
        fixture.coordinator.installConnectionAndAwait(key)
        fixture.coordinator.requestHeadTracking(key, HeadTrackingRequestSource.BOOTSTRAP)
        settle()
        fixture.coordinator.setEnabledAndAwait(true)
        fixture.confirmSupported(key)
        fixture.coordinator.updateWear(key, HeartRateWearState(leftWorn = true))
        settle()
        assertEquals(
            listOf("start-head-1", "stop-head-1", "start-heart-1"),
            fixture.sink.events,
        )

        fixture.coordinator.destroyAndAwait()
        settle()
        val afterFirstDestroy = fixture.sink.events.toList()
        assertEquals(
            listOf("start-head-1", "stop-head-1", "start-heart-1", "stop-heart-1"),
            afterFirstDestroy,
        )
        assertTrue(fixture.coordinator.session.value.destroyed)
        assertFalse(fixture.coordinator.headTrackingActive.value)
        assertNull(fixture.coordinator.currentKey())
        assertTrue(fixture.delays.pendingDurations().isEmpty())

        fixture.coordinator.destroyAndAwait()
        fixture.coordinator.setEnabledAndAwait(true)
        fixture.coordinator.requestHeadTracking(key, HeadTrackingRequestSource.BOOTSTRAP)
        settle()
        assertEquals(afterFirstDestroy, fixture.sink.events)
        fixture.cancelScope()
    }

    private class Fixture(vararg startBehaviors: StartBehavior) {
        val clock = FakeClock()
        val delays = ManualDelay()
        val sink = FakeSink(startBehaviors.toList())
        private val rootJob = SupervisorJob()
        private val scope = CoroutineScope(rootJob + Dispatchers.Unconfined)
        val coordinator = HeartRateRuntimeCoordinator(
            scope = scope,
            sink = sink,
            elapsedRealtimeMillis = clock::now,
            suspendDelay = delays::await,
        )

        suspend fun connectSupportedAndWorn(key: HrmSessionKey) {
            coordinator.installConnectionAndAwait(key)
            coordinator.setEnabledAndAwait(true)
            confirmSupported(key)
            coordinator.updateWear(key, HeartRateWearState(leftWorn = true))
            settle()
        }

        suspend fun confirmSupported(key: HrmSessionKey) {
            coordinator.confirmDeviceInformation(
                key = key,
                modelNumber = "A3063",
                modelCapabilities = setOf(Capability.HRM),
            )
            settle()
        }

        suspend fun close() {
            coordinator.destroyAndAwait()
            cancelScope()
            settle()
        }

        fun cancelScope() {
            scope.cancel()
        }
    }

    private enum class StartBehavior {
        SUCCESS,
        FAILURE,
        THROW,
        SUSPEND,
    }

    private data class CleanupUse(
        val first: Boolean,
        val second: Boolean,
    )

    private class StartCall(
        val acquisition: HrmAcquisitionToken,
        val isCurrent: () -> Boolean,
    ) {
        var cancelled: Boolean = false
    }

    private class FakeSink(startBehaviors: List<StartBehavior>) : HeartRateRuntimeSink {
        private val startBehaviors = ArrayDeque(startBehaviors)
        val events = mutableListOf<String>()
        val starts = mutableListOf<StartCall>()
        val heartStops = mutableListOf<CleanupUse>()
        val headStops = mutableListOf<CleanupUse>()
        val published = mutableListOf<HeartRateMeasurement>()
        var allowedWrites = 0

        override suspend fun startHeartRate(
            acquisition: HrmAcquisitionToken,
            acquisitionIsCurrent: () -> Boolean,
        ): Boolean {
            events += "start-heart-${acquisition.acquisition}"
            val call = StartCall(acquisition, acquisitionIsCurrent)
            starts += call
            return when (startBehaviors.pollFirst() ?: StartBehavior.SUCCESS) {
                StartBehavior.SUCCESS -> acquisitionIsCurrent()
                StartBehavior.FAILURE -> false
                StartBehavior.THROW -> throw IOException("test transport failure")
                StartBehavior.SUSPEND -> suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation {
                        call.cancelled = true
                    }
                }
            }
        }

        override fun stopHeartRate(
            acquisition: HrmAcquisitionToken,
            cleanup: OneShotSensorCleanup,
        ): Boolean {
            events += "stop-heart-${acquisition.acquisition}"
            val use = CleanupUse(cleanup.consume(), cleanup.consume())
            heartStops += use
            return use.first
        }

        override fun startHeadTracking(
            owner: SensorResourceOwner,
            ownerIsCurrent: () -> Boolean,
        ): Boolean {
            events += "start-head-${owner.acquisition}"
            return ownerIsCurrent()
        }

        override fun stopHeadTracking(
            owner: SensorResourceOwner,
            cleanup: OneShotSensorCleanup,
        ) {
            events += "stop-head-${owner.acquisition}"
            headStops += CleanupUse(cleanup.consume(), cleanup.consume())
        }

        override fun publishMeasurement(
            acquisition: HrmAcquisitionToken,
            measurement: HeartRateMeasurement,
        ) {
            events += "publish-${measurement.sequence}"
            published += measurement
        }

        fun attemptWrite(call: StartCall): Boolean {
            val allowed = call.isCurrent()
            events += "write-heart-${call.acquisition.acquisition}-$allowed"
            if (allowed) allowedWrites += 1
            return allowed
        }
    }

    private class FakeClock {
        var nowMillis: Long = 0L
        fun now(): Long = nowMillis
    }

    private class ManualDelay {
        private class Waiter(
            val durationMillis: Long,
            val continuation: CancellableContinuation<Unit>,
        )

        private val waiters = mutableListOf<Waiter>()

        suspend fun await(durationMillis: Long) {
            suspendCancellableCoroutine { continuation ->
                val waiter = Waiter(durationMillis, continuation)
                waiters += waiter
                continuation.invokeOnCancellation {
                    waiters.remove(waiter)
                }
            }
        }

        fun pendingDurations(): List<Long> = waiters.map { it.durationMillis }

        fun resumeFirst(durationMillis: Long) {
            val index = waiters.indexOfFirst { it.durationMillis == durationMillis }
            check(index >= 0) { "No pending delay for $durationMillis ms" }
            val waiter = waiters.removeAt(index)
            if (waiter.continuation.isActive) waiter.continuation.resume(Unit)
        }
    }

    private fun sample(sequence: Int, elapsedRealtimeMillis: Long) = HeartRateSample(
        bpm = 80,
        sequence = sequence,
        receivedAtMillis = 1_000L + sequence,
        receivedAtElapsedRealtime = elapsedRealtimeMillis,
    )

    private fun key(
        socket: Any = Any(),
        generation: Long = 1L,
        deviceKey: String = "device-under-test",
        epoch: Long,
    ) = HrmSessionKey(socket, generation, deviceKey, epoch)

    private companion object {
        suspend fun settle() {
            repeat(4) { yield() }
        }
    }
}
