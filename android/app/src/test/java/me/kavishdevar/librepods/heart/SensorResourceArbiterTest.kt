package me.kavishdevar.librepods.heart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SensorResourceArbiterTest {
    @Test
    fun `heart rate preempts head tracking and release resumes the still requested owner`() {
        val connection = key(epoch = 1L)
        val head = owner(SensorResource.HEAD_TRACKING, connection, acquisition = 1L)
        val heart = owner(SensorResource.HEART_RATE, connection, acquisition = 1L)

        val headStarted = SensorResourceArbiter.reduce(
            SensorResourceArbiterState(),
            SensorResourceEvent.Request(head),
        )
        val heartStarted = SensorResourceArbiter.reduce(
            headStarted.state,
            SensorResourceEvent.Request(heart),
        )
        assertEquals(heart, heartStarted.state.activeOwner)
        assertEquals(head, heartStarted.state.headTrackingRequest)
        assertEquals(
            listOf(
                SensorResourceAction.StopHeadTracking(head),
                SensorResourceAction.StartHeartRate(heart),
            ),
            heartStarted.actions,
        )

        val released = SensorResourceArbiter.reduce(
            heartStarted.state,
            SensorResourceEvent.Release(heart),
        )
        assertEquals(head, released.state.activeOwner)
        assertEquals(
            listOf(
                SensorResourceAction.StopHeartRate(heart),
                SensorResourceAction.StartHeadTracking(head),
            ),
            released.actions,
        )
    }

    @Test
    fun `cancelled deferred head tracking is not resumed`() {
        val connection = key(epoch = 1L)
        val head = owner(SensorResource.HEAD_TRACKING, connection, acquisition = 1L)
        val heart = owner(SensorResource.HEART_RATE, connection, acquisition = 1L)
        var state = SensorResourceArbiter.reduce(
            SensorResourceArbiterState(),
            SensorResourceEvent.Request(head),
        ).state
        state = SensorResourceArbiter.reduce(
            state,
            SensorResourceEvent.Request(heart),
        ).state
        state = SensorResourceArbiter.reduce(
            state,
            SensorResourceEvent.Release(head),
        ).state

        val released = SensorResourceArbiter.reduce(
            state,
            SensorResourceEvent.Release(heart),
        )
        assertNull(released.state.activeOwner)
        assertEquals(
            listOf(SensorResourceAction.StopHeartRate(heart)),
            released.actions,
        )
    }

    @Test
    fun `connection and acquisition high water rejects stale owners but accepts reacquisition`() {
        val socket = Any()
        val currentConnection = key(
            socket = socket,
            generation = 5L,
            deviceKey = "current",
            epoch = 5L,
        )
        val first = owner(SensorResource.HEART_RATE, currentConnection, acquisition = 1L)
        var state = SensorResourceArbiter.reduce(
            SensorResourceArbiterState(),
            SensorResourceEvent.Request(first),
        ).state
        state = SensorResourceArbiter.reduce(
            state,
            SensorResourceEvent.Release(first),
        ).state

        listOf(
            first,
            owner(
                SensorResource.HEART_RATE,
                key(socket = Any(), generation = 5L, deviceKey = "current", epoch = 5L),
                acquisition = 2L,
            ),
            owner(
                SensorResource.HEART_RATE,
                key(socket = socket, generation = 4L, deviceKey = "current", epoch = 5L),
                acquisition = 2L,
            ),
            owner(
                SensorResource.HEART_RATE,
                key(socket = socket, generation = 5L, deviceKey = "stale", epoch = 5L),
                acquisition = 2L,
            ),
        ).forEach { stale ->
            val ignored = SensorResourceArbiter.reduce(
                state,
                SensorResourceEvent.Request(stale),
            )
            assertSame(state, ignored.state)
            assertTrue(ignored.actions.isEmpty())
        }

        val reacquired = owner(
            SensorResource.HEART_RATE,
            currentConnection,
            acquisition = 2L,
        )
        val accepted = SensorResourceArbiter.reduce(
            state,
            SensorResourceEvent.Request(reacquired),
        )
        assertEquals(reacquired, accepted.state.activeOwner)

        val replacementConnection = key(
            socket = Any(),
            generation = 6L,
            deviceKey = "replacement",
            epoch = 6L,
        )
        val replacement = owner(
            SensorResource.HEART_RATE,
            replacementConnection,
            acquisition = 1L,
        )
        val replaced = SensorResourceArbiter.reduce(
            accepted.state,
            SensorResourceEvent.Request(replacement),
        )
        assertEquals(replacement, replaced.state.activeOwner)
    }

    @Test
    fun `new connection revokes old claims and never revives deferred head tracking`() {
        val oldConnection = key(epoch = 1L)
        val oldHead = owner(SensorResource.HEAD_TRACKING, oldConnection, acquisition = 1L)
        val oldHeart = owner(SensorResource.HEART_RATE, oldConnection, acquisition = 1L)
        var state = SensorResourceArbiter.reduce(
            SensorResourceArbiterState(),
            SensorResourceEvent.Request(oldHead),
        ).state
        state = SensorResourceArbiter.reduce(
            state,
            SensorResourceEvent.Request(oldHeart),
        ).state

        val newConnection = key(epoch = 2L)
        val newHeart = owner(SensorResource.HEART_RATE, newConnection, acquisition = 1L)
        val replaced = SensorResourceArbiter.reduce(
            state,
            SensorResourceEvent.Request(newHeart),
        )
        assertEquals(
            listOf(
                SensorResourceAction.StopHeartRate(oldHeart),
                SensorResourceAction.StartHeartRate(newHeart),
            ),
            replaced.actions,
        )
        assertEquals(newConnection, replaced.state.currentConnection)
        assertEquals(newHeart, replaced.state.heartRateRequest)
        assertNull(replaced.state.headTrackingRequest)

        val released = SensorResourceArbiter.reduce(
            replaced.state,
            SensorResourceEvent.Release(newHeart),
        )
        assertNull(released.state.activeOwner)
        assertEquals(
            listOf(SensorResourceAction.StopHeartRate(newHeart)),
            released.actions,
        )

        listOf(
            SensorResourceEvent.Request(oldHead),
            SensorResourceEvent.Release(oldHead),
            SensorResourceEvent.Release(oldHeart),
        ).forEach { stale ->
            val ignored = SensorResourceArbiter.reduce(released.state, stale)
            assertSame(released.state, ignored.state)
            assertTrue(ignored.actions.isEmpty())
        }
    }

    @Test
    fun `session and arbiter chain reacquires after removal and disable enable`() {
        val key = key(epoch = 1L)
        var session = HeartRateSessionState(
            enabled = true,
            status = HeartRateSessionStatus.WAITING_FOR_AIRPODS,
        )
        var arbiter = SensorResourceArbiterState()

        var transition = sessionReduce(
            session,
            HeartRateSessionEvent.TransportConnected(
                key,
                supported = true,
                wear = HeartRateWearState(leftWorn = true),
            ),
        )
        session = transition.state
        arbiter = applySessionActions(arbiter, transition.actions)
        session = grantSessionIfRequested(session, arbiter)
        val first = session.requireAcquisition()
        assertEquals(SensorResourceOwner.heartRate(first), arbiter.activeOwner)

        transition = sessionReduce(
            session,
            HeartRateSessionEvent.WearChanged(key, HeartRateWearState()),
        )
        session = transition.state
        arbiter = applySessionActions(arbiter, transition.actions)
        assertNull(arbiter.activeOwner)

        transition = sessionReduce(
            session,
            HeartRateSessionEvent.WearChanged(
                key,
                HeartRateWearState(rightWorn = true),
            ),
        )
        session = transition.state
        arbiter = applySessionActions(arbiter, transition.actions)
        session = grantSessionIfRequested(session, arbiter)
        val second = session.requireAcquisition()
        assertTrue(second.acquisition > first.acquisition)
        assertEquals(SensorResourceOwner.heartRate(second), arbiter.activeOwner)

        transition = sessionReduce(session, HeartRateSessionEvent.Disable)
        session = transition.state
        arbiter = applySessionActions(arbiter, transition.actions)
        assertNull(arbiter.activeOwner)

        transition = sessionReduce(session, HeartRateSessionEvent.Enable)
        session = transition.state
        arbiter = applySessionActions(arbiter, transition.actions)
        session = grantSessionIfRequested(session, arbiter)
        val third = session.requireAcquisition()
        assertTrue(third.acquisition > second.acquisition)
        assertEquals(SensorResourceOwner.heartRate(third), arbiter.activeOwner)

        val staleRelease = SensorResourceArbiter.reduce(
            arbiter,
            SensorResourceEvent.Release(SensorResourceOwner.heartRate(first)),
        )
        assertSame(arbiter, staleRelease.state)
        assertEquals(SensorResourceOwner.heartRate(third), staleRelease.state.activeOwner)
    }

    @Test
    fun `session and arbiter chain supports manual retry then disconnect and destroy cleanup`() {
        val firstKey = key(epoch = 1L)
        var session = HeartRateSessionState(
            enabled = true,
            status = HeartRateSessionStatus.WAITING_FOR_AIRPODS,
        )
        var arbiter = SensorResourceArbiterState()

        var transition = sessionReduce(
            session,
            HeartRateSessionEvent.TransportConnected(
                firstKey,
                supported = true,
                wear = HeartRateWearState(leftWorn = true),
            ),
        )
        session = transition.state
        arbiter = applySessionActions(arbiter, transition.actions)
        session = grantSessionIfRequested(session, arbiter)
        val first = session.requireAcquisition()

        transition = sessionReduce(
            session,
            HeartRateSessionEvent.StartFramesSent(first, succeeded = false),
        )
        session = transition.state
        arbiter = applySessionActions(arbiter, transition.actions)
        session = grantSessionIfRequested(session, arbiter)
        val second = session.requireAcquisition()
        assertEquals(SensorResourceOwner.heartRate(second), arbiter.activeOwner)

        transition = sessionReduce(
            session,
            HeartRateSessionEvent.StartFramesSent(second, succeeded = false),
        )
        session = transition.state
        arbiter = applySessionActions(arbiter, transition.actions)
        assertEquals(HeartRateSessionStatus.COULDNT_START, session.status)
        assertNull(arbiter.activeOwner)

        val staleRetry = sessionReduce(
            session,
            HeartRateSessionEvent.Retry(first),
        )
        assertSame(session, staleRetry.state)

        transition = sessionReduce(
            session,
            HeartRateSessionEvent.Retry(second),
        )
        session = transition.state
        arbiter = applySessionActions(arbiter, transition.actions)
        session = grantSessionIfRequested(session, arbiter)
        val third = session.requireAcquisition()
        assertEquals(SensorResourceOwner.heartRate(third), arbiter.activeOwner)

        transition = sessionReduce(
            session,
            HeartRateSessionEvent.TransportDisconnected(firstKey),
        )
        session = transition.state
        arbiter = applySessionActions(arbiter, transition.actions)
        assertNull(session.key)
        assertNull(session.acquisition)
        assertNull(arbiter.activeOwner)

        val replacementKey = key(
            socket = Any(),
            generation = 2L,
            deviceKey = "replacement",
            epoch = 2L,
        )
        transition = sessionReduce(
            session,
            HeartRateSessionEvent.TransportConnected(
                replacementKey,
                supported = true,
                wear = HeartRateWearState(rightWorn = true),
            ),
        )
        session = transition.state
        arbiter = applySessionActions(arbiter, transition.actions)
        session = grantSessionIfRequested(session, arbiter)
        val fourth = session.requireAcquisition()
        assertEquals(SensorResourceOwner.heartRate(fourth), arbiter.activeOwner)

        transition = sessionReduce(session, HeartRateSessionEvent.Destroy)
        session = transition.state
        arbiter = applySessionActions(arbiter, transition.actions)
        assertTrue(session.destroyed)
        assertNull(arbiter.activeOwner)

        val arbiterDestroyed = SensorResourceArbiter.reduce(
            arbiter,
            SensorResourceEvent.Destroy,
        )
        assertTrue(arbiterDestroyed.state.destroyed)
        assertNull(arbiterDestroyed.state.activeOwner)
    }

    @Test
    fun `destroy stops active owner and ignores later requests`() {
        val owner = owner(
            SensorResource.HEART_RATE,
            key(epoch = 1L),
            acquisition = 1L,
        )
        val active = SensorResourceArbiter.reduce(
            SensorResourceArbiterState(),
            SensorResourceEvent.Request(owner),
        ).state

        val destroyed = SensorResourceArbiter.reduce(
            active,
            SensorResourceEvent.Destroy,
        )
        assertEquals(
            listOf(SensorResourceAction.StopHeartRate(owner)),
            destroyed.actions,
        )
        assertTrue(destroyed.state.destroyed)

        val ignored = SensorResourceArbiter.reduce(
            destroyed.state,
            SensorResourceEvent.Request(
                owner(
                    SensorResource.HEAD_TRACKING,
                    key(epoch = 2L),
                    acquisition = 1L,
                )
            ),
        )
        assertSame(destroyed.state, ignored.state)
    }

    private fun applySessionActions(
        initial: SensorResourceArbiterState,
        actions: List<HeartRateSessionAction>,
    ): SensorResourceArbiterState {
        var state = initial
        actions.forEach { action ->
            val event = when (action) {
                is HeartRateSessionAction.ClaimSensorResource ->
                    SensorResourceEvent.Request(
                        SensorResourceOwner.heartRate(action.acquisition)
                    )
                is HeartRateSessionAction.ReleaseSensorResource ->
                    SensorResourceEvent.Release(
                        SensorResourceOwner.heartRate(action.acquisition)
                    )
                else -> null
            }
            if (event != null) {
                state = SensorResourceArbiter.reduce(state, event).state
            }
        }
        return state
    }

    private fun owner(
        resource: SensorResource,
        connection: HrmSessionKey,
        acquisition: Long,
    ) = SensorResourceOwner(resource, connection, acquisition)

    private fun key(
        socket: Any = Any(),
        generation: Long = 1L,
        deviceKey: String = "device-under-test",
        epoch: Long,
    ) = HrmSessionKey(socket, generation, deviceKey, epoch)

    private fun HeartRateSessionState.requireAcquisition(): HrmAcquisitionToken =
        requireNotNull(acquisition)

    private fun grantSessionIfRequested(
        state: HeartRateSessionState,
        arbiter: SensorResourceArbiterState,
    ): HeartRateSessionState {
        val acquisition = state.acquisition ?: return state
        return if (state.sensorResourceClaimPending &&
            arbiter.activeOwner == SensorResourceOwner.heartRate(acquisition)
        ) {
            sessionReduce(state, HeartRateSessionEvent.SensorResourceGranted(acquisition)).state
        } else {
            state
        }
    }

    private fun sessionReduce(
        state: HeartRateSessionState,
        event: HeartRateSessionEvent,
        nowMillis: Long = 0L,
    ) = HeartRateSessionReducer.reduce(state, event, nowMillis)
}
