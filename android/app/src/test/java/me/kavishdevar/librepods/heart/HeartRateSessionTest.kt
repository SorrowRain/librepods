package me.kavishdevar.librepods.heart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HeartRateSessionTest {
    @Test
    fun `unsupported transport cannot start until the same connection is confirmed`() {
        val key = key(epoch = 1L)
        val initial = HeartRateSessionState(
            enabled = true,
            status = HeartRateSessionStatus.WAITING_FOR_AIRPODS,
        )

        val unsupported = reduce(
            initial,
            HeartRateSessionEvent.TransportConnected(
                key,
                supported = false,
                wear = HeartRateWearState(leftWorn = true),
            ),
        )
        assertEquals(HeartRateSessionStatus.WAITING_FOR_AIRPODS, unsupported.state.status)
        assertNull(unsupported.state.acquisition)
        assertTrue(unsupported.actions.isEmpty())

        val confirmed = reduce(
            unsupported.state,
            HeartRateSessionEvent.TransportConnected(
                key,
                supported = true,
                wear = HeartRateWearState(leftWorn = true),
            ),
        )
        val acquisition = confirmed.state.requireAcquisition()
        assertEquals(1L, acquisition.acquisition)
        assertEquals(HeartRateSessionStatus.STARTING, confirmed.state.status)
        assertEquals(
            listOf(HeartRateSessionAction.ClaimSensorResource(acquisition)),
            confirmed.actions,
        )
        assertFalse(HeartRateSessionAction.SendStartFrames(acquisition) in confirmed.actions)

        val prematureStart = reduce(
            confirmed.state,
            HeartRateSessionEvent.StartFramesSent(acquisition, succeeded = true),
        )
        assertSame(confirmed.state, prematureStart.state)

        val granted = reduce(
            confirmed.state,
            HeartRateSessionEvent.SensorResourceGranted(acquisition),
        )
        assertEquals(
            listOf(HeartRateSessionAction.SendStartFrames(acquisition)),
            granted.actions,
        )
    }

    @Test
    fun `single ear starts and four warmup samples precede live output`() {
        val key = key(epoch = 1L)
        var state = connectedWaitingState(key)

        val worn = reduce(
            state,
            HeartRateSessionEvent.WearChanged(
                key,
                HeartRateWearState(leftWorn = true),
            ),
        )
        state = worn.state
        val acquisition = state.requireAcquisition()
        assertEquals(
            listOf(HeartRateSessionAction.ClaimSensorResource(acquisition)),
            worn.actions,
        )

        state = reduce(
            state,
            HeartRateSessionEvent.SensorResourceGranted(acquisition),
        ).state
        state = reduce(
            state,
            HeartRateSessionEvent.StartFramesSent(acquisition, succeeded = true),
            nowMillis = 100L,
        ).state
        assertEquals(
            HeartRateWatchdog(
                HeartRateWatchdogKind.FIRST_SAMPLE,
                deadlineMillis = 8_100L,
            ),
            state.watchdog,
        )

        repeat(HeartRateSessionReducer.WARMUP_SAMPLE_COUNT) { index ->
            val transition = reduce(
                state,
                HeartRateSessionEvent.SampleReceived(
                    acquisition,
                    measurement(index.toLong(), bpm = 70 + index),
                ),
                nowMillis = 200L + index,
            )
            state = transition.state
            assertEquals(HeartRateSessionStatus.CALIBRATING, state.status)
            assertTrue(state.measurements.isEmpty())
            assertTrue(
                transition.actions.none {
                    it is HeartRateSessionAction.PublishMeasurement
                }
            )
        }

        val liveMeasurement = measurement(sequence = 4L, bpm = 74)
        val live = reduce(
            state,
            HeartRateSessionEvent.SampleReceived(acquisition, liveMeasurement),
            nowMillis = 300L,
        )
        assertEquals(HeartRateSessionStatus.LIVE, live.state.status)
        assertEquals(liveMeasurement, live.state.latestMeasurement)
        assertEquals(0, live.state.restartAttempts)
        assertTrue(
            HeartRateSessionAction.PublishMeasurement(
                acquisition,
                liveMeasurement,
            ) in live.actions
        )
        assertEquals(
            HeartRateWatchdog(
                HeartRateWatchdogKind.STREAM_STALL,
                deadlineMillis = 2_300L,
            ),
            live.state.watchdog,
        )
    }

    @Test
    fun `second ear changes do not restart and removal waits until both ears are out`() {
        val key = key(epoch = 1L)
        var state = startedState(key)
        val acquisition = state.requireAcquisition()
        state = startAfterGrant(state, acquisition, nowMillis = 0L)

        val both = reduce(
            state,
            HeartRateSessionEvent.WearChanged(
                key,
                HeartRateWearState(leftWorn = true, rightWorn = true),
            ),
        )
        state = both.state
        assertSame(acquisition, state.acquisition)
        assertTrue(both.actions.isEmpty())

        val rightOnly = reduce(
            state,
            HeartRateSessionEvent.WearChanged(
                key,
                HeartRateWearState(rightWorn = true),
            ),
        )
        state = rightOnly.state
        assertSame(acquisition, state.acquisition)
        assertTrue(rightOnly.actions.isEmpty())

        val removed = reduce(
            state,
            HeartRateSessionEvent.WearChanged(key, HeartRateWearState()),
        )
        assertNull(removed.state.acquisition)
        assertEquals(HeartRateSessionStatus.WAITING_TO_BE_WORN, removed.state.status)
        assertEquals(
            listOf(
                HeartRateSessionAction.CancelWatchdog(acquisition),
                HeartRateSessionAction.SendStopFrames(acquisition),
                HeartRateSessionAction.ReleaseSensorResource(acquisition),
            ),
            removed.actions,
        )
    }

    @Test
    fun `first sample timeout mints one replacement attempt and then fails`() {
        val key = key(epoch = 1L)
        var state = startedState(key)
        val first = state.requireAcquisition()
        state = startAfterGrant(state, first, nowMillis = 100L)

        val early = reduce(
            state,
            HeartRateSessionEvent.Tick(first),
            nowMillis = 8_099L,
        )
        assertSame(state, early.state)

        val retry = reduce(
            state,
            HeartRateSessionEvent.Tick(first),
            nowMillis = 8_100L,
        )
        val second = retry.state.requireAcquisition()
        assertEquals(first.acquisition + 1L, second.acquisition)
        assertEquals(1, retry.state.restartAttempts)
        assertEquals(HeartRateSessionStatus.RECONNECTING, retry.state.status)
        assertEquals(
            listOf(
                HeartRateSessionAction.CancelWatchdog(first),
                HeartRateSessionAction.SendStopFrames(first),
                HeartRateSessionAction.ClaimSensorResource(second),
            ),
            retry.actions,
        )

        state = reduce(
            retry.state,
            HeartRateSessionEvent.SensorResourceGranted(second),
        ).state
        state = reduce(
            state,
            HeartRateSessionEvent.StartFramesSent(second, succeeded = true),
            nowMillis = 8_200L,
        ).state
        val failed = reduce(
            state,
            HeartRateSessionEvent.Tick(second),
            nowMillis = 16_200L,
        )
        assertEquals(HeartRateSessionStatus.COULDNT_START, failed.state.status)
        assertEquals(second, failed.state.acquisition)
        assertFalse(failed.state.ownsSensorResource)
        assertEquals(
            listOf(
                HeartRateSessionAction.CancelWatchdog(second),
                HeartRateSessionAction.SendStopFrames(second),
                HeartRateSessionAction.ReleaseSensorResource(second),
                HeartRateSessionAction.RequestTransportRecovery(
                    second,
                    HeartRateTransportRecoveryReason.AUTOMATIC,
                ),
            ),
            failed.actions,
        )

        val accepted = reduce(
            failed.state,
            HeartRateSessionEvent.TransportRecoveryResult(second, accepted = true),
        )
        assertEquals(HeartRateSessionStatus.RECONNECTING, accepted.state.status)

        val rejected = reduce(
            failed.state,
            HeartRateSessionEvent.TransportRecoveryResult(second, accepted = false),
        )
        assertEquals(HeartRateSessionStatus.COULDNT_START, rejected.state.status)
    }

    @Test
    fun `warmup sample after retry does not reopen the restart budget`() {
        val key = key(epoch = 1L)
        var state = startedState(key)
        val first = state.requireAcquisition()
        state = startAfterGrant(state, first, nowMillis = 0L)
        state = reduce(
            state,
            HeartRateSessionEvent.Tick(first),
            nowMillis = 8_000L,
        ).state
        val retry = state.requireAcquisition()
        state = startAfterGrant(state, retry, nowMillis = 8_100L)

        state = reduce(
            state,
            HeartRateSessionEvent.SampleReceived(
                retry,
                measurement(sequence = 1L, bpm = 72),
            ),
            nowMillis = 8_200L,
        ).state
        assertEquals(HeartRateSessionStatus.CALIBRATING, state.status)
        assertEquals(1, state.restartAttempts)

        val stalledDuringWarmup = reduce(
            state,
            HeartRateSessionEvent.Tick(retry),
            nowMillis = 10_200L,
        )
        assertEquals(HeartRateSessionStatus.COULDNT_START, stalledDuringWarmup.state.status)
        assertEquals(retry, stalledDuringWarmup.state.acquisition)
    }

    @Test
    fun `returning to live reopens one bounded stall recovery`() {
        val key = key(epoch = 1L)
        var state = liveState(key, lastSampleNowMillis = 500L)
        val first = state.requireAcquisition()

        state = reduce(
            state,
            HeartRateSessionEvent.Tick(first),
            nowMillis = 2_500L,
        ).state
        val retry = state.requireAcquisition()
        assertEquals(1, state.restartAttempts)
        state = startAfterGrant(state, retry, nowMillis = 2_600L)

        repeat(HeartRateSessionReducer.WARMUP_SAMPLE_COUNT + 1) { index ->
            state = reduce(
                state,
                HeartRateSessionEvent.SampleReceived(
                    retry,
                    measurement(sequence = 10L + index, bpm = 72 + index),
                ),
                nowMillis = 2_700L + index,
            ).state
            if (index < HeartRateSessionReducer.WARMUP_SAMPLE_COUNT) {
                assertEquals(1, state.restartAttempts)
            }
        }
        assertEquals(HeartRateSessionStatus.LIVE, state.status)
        assertEquals(0, state.restartAttempts)

        val stalledAgain = reduce(
            state,
            HeartRateSessionEvent.Tick(retry),
            nowMillis = state.watchdog!!.deadlineMillis,
        )
        assertEquals(HeartRateSessionStatus.RECONNECTING, stalledAgain.state.status)
        assertEquals(1, stalledAgain.state.restartAttempts)
        assertTrue(stalledAgain.state.requireAcquisition().acquisition > retry.acquisition)
    }

    @Test
    fun `stale socket generation device and acquisition cannot affect current attempt`() {
        val socket = Any()
        val currentKey = key(
            socket = socket,
            generation = 8L,
            deviceKey = "current-device",
            epoch = 8L,
        )
        var state = startedState(currentKey)
        val first = state.requireAcquisition()
        state = startAfterGrant(state, first, nowMillis = 0L)
        state = reduce(
            state,
            HeartRateSessionEvent.Tick(first),
            nowMillis = 8_000L,
        ).state
        val current = state.requireAcquisition()

        val staleTokens = listOf(
            first,
            HrmAcquisitionToken(
                key(socket = Any(), generation = 8L, deviceKey = "current-device", epoch = 8L),
                current.acquisition,
            ),
            HrmAcquisitionToken(
                key(socket = socket, generation = 7L, deviceKey = "current-device", epoch = 8L),
                current.acquisition,
            ),
            HrmAcquisitionToken(
                key(socket = socket, generation = 8L, deviceKey = "other-device", epoch = 8L),
                current.acquisition,
            ),
        )
        staleTokens.forEach { stale ->
            listOf(
                HeartRateSessionEvent.StartFramesSent(stale, succeeded = true),
                HeartRateSessionEvent.SampleReceived(
                    stale,
                    measurement(sequence = 20L, bpm = 80),
                ),
                HeartRateSessionEvent.Tick(stale),
                HeartRateSessionEvent.Stop(stale),
            ).forEach { event ->
                val ignored = reduce(state, event, nowMillis = 20_000L)
                assertSame(state, ignored.state)
                assertTrue(ignored.actions.isEmpty())
            }
        }

        val staleDisconnect = reduce(
            state,
            HeartRateSessionEvent.TransportDisconnected(
                key(socket = socket, generation = 8L, deviceKey = "other-device", epoch = 8L)
            ),
        )
        assertSame(state, staleDisconnect.state)

        state = reduce(
            state,
            HeartRateSessionEvent.SensorResourceGranted(current),
        ).state
        val failed = reduce(state, HeartRateSessionEvent.StartFramesSent(current, succeeded = false))
        assertEquals(HeartRateSessionStatus.COULDNT_START, failed.state.status)
        staleTokens.forEach { stale ->
            val ignoredRetry = reduce(
                failed.state,
                HeartRateSessionEvent.Retry(stale),
            )
            assertSame(failed.state, ignoredRetry.state)
        }
    }

    @Test
    fun `disconnect and destroy fully clear live attempt and measurements`() {
        val key = key(epoch = 1L)
        val live = liveState(key, lastSampleNowMillis = 500L)
        val acquisition = live.requireAcquisition()

        val disconnected = reduce(
            live,
            HeartRateSessionEvent.TransportDisconnected(key),
        )
        assertEquals(HeartRateSessionStatus.RECONNECTING, disconnected.state.status)
        assertNull(disconnected.state.key)
        assertNull(disconnected.state.acquisition)
        assertTrue(disconnected.state.measurements.isEmpty())
        assertEquals(
            listOf(
                HeartRateSessionAction.CancelWatchdog(acquisition),
                HeartRateSessionAction.SendStopFrames(acquisition),
                HeartRateSessionAction.ReleaseSensorResource(acquisition),
                HeartRateSessionAction.ClearMeasurements(acquisition),
            ),
            disconnected.actions,
        )

        val reconnected = reduce(
            disconnected.state,
            HeartRateSessionEvent.TransportConnected(
                key(socket = Any(), generation = 2L, epoch = 2L),
                supported = true,
                wear = HeartRateWearState(rightWorn = true),
            ),
        )
        val replacement = reconnected.state.requireAcquisition()
        val activeReplacement = startAfterGrant(reconnected.state, replacement, nowMillis = 600L)
        val destroyed = reduce(activeReplacement, HeartRateSessionEvent.Destroy)
        assertTrue(destroyed.state.destroyed)
        assertNull(destroyed.state.key)
        assertNull(destroyed.state.acquisition)
        assertEquals(
            listOf(
                HeartRateSessionAction.CancelWatchdog(replacement),
                HeartRateSessionAction.SendStopFrames(replacement),
                HeartRateSessionAction.ReleaseSensorResource(replacement),
            ),
            destroyed.actions,
        )

        val ignored = reduce(destroyed.state, HeartRateSessionEvent.Enable)
        assertSame(destroyed.state, ignored.state)
    }

    @Test
    fun `sample received at its watchdog deadline cannot mask the timeout`() {
        val key = key(epoch = 1L)
        var state = startedState(key)
        val first = state.requireAcquisition()
        state = startAfterGrant(state, first, nowMillis = 100L)

        val timedOut = reduce(
            state,
            HeartRateSessionEvent.SampleReceived(
                first,
                measurement(sequence = 1L, bpm = 72),
            ),
            nowMillis = state.watchdog!!.deadlineMillis,
        )
        val replacement = timedOut.state.requireAcquisition()
        assertEquals(HeartRateSessionStatus.RECONNECTING, timedOut.state.status)
        assertTrue(replacement.acquisition > first.acquisition)
        assertTrue(timedOut.state.measurements.isEmpty())
        assertEquals(
            listOf(
                HeartRateSessionAction.CancelWatchdog(first),
                HeartRateSessionAction.SendStopFrames(first),
                HeartRateSessionAction.ClaimSensorResource(replacement),
            ),
            timedOut.actions,
        )
    }

    @Test
    fun `stop cleans the current attempt and a later enable mints a new one`() {
        val key = key(epoch = 1L)
        val live = liveState(key, lastSampleNowMillis = 500L)
        val acquisition = live.requireAcquisition()

        val stopped = reduce(
            live,
            HeartRateSessionEvent.Stop(acquisition),
        )
        assertEquals(HeartRateSessionStatus.OFF, stopped.state.status)
        assertFalse(stopped.state.enabled)
        assertNull(stopped.state.acquisition)
        assertTrue(stopped.state.measurements.isEmpty())
        assertEquals(
            listOf(
                HeartRateSessionAction.CancelWatchdog(acquisition),
                HeartRateSessionAction.SendStopFrames(acquisition),
                HeartRateSessionAction.ReleaseSensorResource(acquisition),
                HeartRateSessionAction.ClearMeasurements(acquisition),
            ),
            stopped.actions,
        )

        val enabled = reduce(stopped.state, HeartRateSessionEvent.Enable)
        val replacement = enabled.state.requireAcquisition()
        assertTrue(replacement.acquisition > acquisition.acquisition)
        assertEquals(
            listOf(HeartRateSessionAction.ClaimSensorResource(replacement)),
            enabled.actions,
        )
    }

    @Test
    fun `measurement and token string forms redact device and health data`() {
        val device = "AA-BB-CC-DD-EE-FF"
        val token = HrmAcquisitionToken(
            key(deviceKey = device, epoch = 1L),
            acquisition = 7L,
        )
        val measurement = HeartRateMeasurement(
            bpm = 187,
            sequence = 123_456L,
            wallClockMillis = 987_654_321L,
            elapsedRealtimeMillis = 123_456_789L,
        )

        assertFalse(token.toString().contains(device))
        assertFalse(measurement.toString().contains("187"))
        assertFalse(measurement.toString().contains("987654321"))
    }

    private fun connectedWaitingState(key: HrmSessionKey): HeartRateSessionState =
        reduce(
            HeartRateSessionState(
                enabled = true,
                status = HeartRateSessionStatus.WAITING_FOR_AIRPODS,
            ),
            HeartRateSessionEvent.TransportConnected(
                key,
                supported = true,
                wear = HeartRateWearState(),
            ),
        ).state

    private fun startedState(key: HrmSessionKey): HeartRateSessionState =
        reduce(
            HeartRateSessionState(
                enabled = true,
                status = HeartRateSessionStatus.WAITING_FOR_AIRPODS,
            ),
            HeartRateSessionEvent.TransportConnected(
                key,
                supported = true,
                wear = HeartRateWearState(leftWorn = true),
            ),
        ).state

    private fun liveState(
        key: HrmSessionKey,
        lastSampleNowMillis: Long,
    ): HeartRateSessionState {
        var state = startedState(key)
        val acquisition = state.requireAcquisition()
        state = startAfterGrant(state, acquisition, nowMillis = 0L)
        repeat(HeartRateSessionReducer.WARMUP_SAMPLE_COUNT + 1) { index ->
            val now = if (index == HeartRateSessionReducer.WARMUP_SAMPLE_COUNT) {
                lastSampleNowMillis
            } else {
                index.toLong()
            }
            state = reduce(
                state,
                HeartRateSessionEvent.SampleReceived(
                    acquisition,
                    measurement(index.toLong(), bpm = 70 + index),
                ),
                nowMillis = now,
            ).state
        }
        return state
    }

    private fun key(
        socket: Any = Any(),
        generation: Long = 1L,
        deviceKey: String = "device-under-test",
        epoch: Long,
    ) = HrmSessionKey(
        socketIdentity = socket,
        generation = generation,
        deviceKey = deviceKey,
        epoch = epoch,
    )

    private fun measurement(sequence: Long, bpm: Int) = HeartRateMeasurement(
        bpm = bpm,
        sequence = sequence,
        wallClockMillis = 1_000L + sequence,
        elapsedRealtimeMillis = 2_000L + sequence,
    )

    private fun HeartRateSessionState.requireAcquisition(): HrmAcquisitionToken =
        requireNotNull(acquisition)

    private fun reduce(
        state: HeartRateSessionState,
        event: HeartRateSessionEvent,
        nowMillis: Long = 0L,
    ) = HeartRateSessionReducer.reduce(state, event, nowMillis)

    private fun startAfterGrant(
        state: HeartRateSessionState,
        acquisition: HrmAcquisitionToken,
        nowMillis: Long,
    ): HeartRateSessionState = reduce(
        reduce(state, HeartRateSessionEvent.SensorResourceGranted(acquisition)).state,
        HeartRateSessionEvent.StartFramesSent(acquisition, succeeded = true),
        nowMillis,
    ).state
}
