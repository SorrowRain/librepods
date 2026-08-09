package me.kavishdevar.librepods.heart

import me.kavishdevar.librepods.data.Capability
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeartRateUiStateTest {
    @Test
    fun `all eight reducer states map without exposing session identity`() {
        val key = key(epoch = 1L)
        val eligibility = supportedEligibility(key)

        HeartRateSessionStatus.values().forEach { status ->
            val ui = HeartRateUiStateMapper.map(
                eligibility = eligibility,
                session = HeartRateSessionState(
                    enabled = status != HeartRateSessionStatus.OFF,
                    status = status,
                    key = key,
                ),
                nowElapsedRealtimeMillis = 1_000L,
            )
            assertEquals(HeartRateUiStatus.valueOf(status.name), ui.status)
            assertTrue(ui.supported)
            assertNull(ui.latestBpm)
            assertEquals(HeartRateSampleFreshness.NO_SAMPLE, ui.sampleFreshness)
        }
        assertEquals(8, HeartRateUiStatus.values().size)
    }

    @Test
    fun `LIVE sample remains visible through exactly ten seconds`() {
        val key = key(epoch = 1L)
        val sampleTime = 5_000L
        val ui = HeartRateUiStateMapper.map(
            eligibility = supportedEligibility(key),
            session = liveSession(key, bpm = 73, sampleTime = sampleTime),
            nowElapsedRealtimeMillis = sampleTime +
                HeartRateUiStateMapper.LIVE_SAMPLE_FRESHNESS_MILLIS,
        )

        assertEquals(73, ui.latestBpm)
        assertEquals(HeartRateSampleFreshness.FRESH, ui.sampleFreshness)
    }

    @Test
    fun `LIVE sample older than ten seconds is stale and hides BPM`() {
        val key = key(epoch = 1L)
        val sampleTime = 5_000L
        val ui = HeartRateUiStateMapper.map(
            eligibility = supportedEligibility(key),
            session = liveSession(key, bpm = 74, sampleTime = sampleTime),
            nowElapsedRealtimeMillis = sampleTime +
                HeartRateUiStateMapper.LIVE_SAMPLE_FRESHNESS_MILLIS + 1L,
        )

        assertNull(ui.latestBpm)
        assertEquals(HeartRateSampleFreshness.STALE, ui.sampleFreshness)
    }

    @Test
    fun `future timestamp is stale and never exposes BPM`() {
        val key = key(epoch = 1L)
        val ui = HeartRateUiStateMapper.map(
            eligibility = supportedEligibility(key),
            session = liveSession(key, bpm = 75, sampleTime = 5_001L),
            nowElapsedRealtimeMillis = 5_000L,
        )

        assertNull(ui.latestBpm)
        assertEquals(HeartRateSampleFreshness.STALE, ui.sampleFreshness)
    }

    @Test
    fun `overflowing sample age is stale and never exposes BPM`() {
        val key = key(epoch = 1L)
        val ui = HeartRateUiStateMapper.map(
            eligibility = supportedEligibility(key),
            session = liveSession(key, bpm = 75, sampleTime = Long.MIN_VALUE),
            nowElapsedRealtimeMillis = Long.MAX_VALUE,
        )

        assertNull(ui.latestBpm)
        assertEquals(HeartRateSampleFreshness.STALE, ui.sampleFreshness)
    }

    @Test
    fun `non-LIVE and stale session samples remain hidden`() {
        val currentKey = key(epoch = 2L)
        val oldKey = key(epoch = 1L)
        val eligibility = supportedEligibility(currentKey)
        val measurement = measurement(bpm = 76, sampleTime = 5_000L)

        val calibrating = HeartRateUiStateMapper.map(
            eligibility = eligibility,
            session = HeartRateSessionState(
                enabled = true,
                status = HeartRateSessionStatus.CALIBRATING,
                key = currentKey,
                measurements = listOf(measurement),
            ),
            nowElapsedRealtimeMillis = 5_001L,
        )
        assertNull(calibrating.latestBpm)
        assertEquals(HeartRateSampleFreshness.NO_SAMPLE, calibrating.sampleFreshness)

        val staleSession = HeartRateUiStateMapper.map(
            eligibility = eligibility,
            session = liveSession(oldKey, bpm = 77, sampleTime = 5_000L),
            nowElapsedRealtimeMillis = 5_001L,
        )
        assertNull(staleSession.latestBpm)
        assertEquals(HeartRateSampleFreshness.NO_SAMPLE, staleSession.sampleFreshness)
    }

    @Test
    fun `unsupported or disabled LIVE state cannot expose BPM`() {
        val key = key(epoch = 1L)
        val unsupported = HeartRateUiStateMapper.map(
            eligibility = HeartRateEligibilityReducer.reduce(
                HeartRateEligibilityState(),
                HeartRateEligibilityEvent.SessionInstalled(key),
            ),
            session = liveSession(key, bpm = 78, sampleTime = 5_000L),
            nowElapsedRealtimeMillis = 5_001L,
        )
        assertFalse(unsupported.supported)
        assertNull(unsupported.latestBpm)
        assertEquals(HeartRateSampleFreshness.NO_SAMPLE, unsupported.sampleFreshness)

        val disabled = HeartRateUiStateMapper.map(
            eligibility = supportedEligibility(key),
            session = liveSession(key, bpm = 79, sampleTime = 5_000L).copy(enabled = false),
            nowElapsedRealtimeMillis = 5_001L,
        )
        assertNull(disabled.latestBpm)
        assertEquals(HeartRateSampleFreshness.NO_SAMPLE, disabled.sampleFreshness)
    }

    @Test
    fun `disconnected confirmed Pro 3 keeps waiting states visible without exposing old BPM`() {
        val key = key(epoch = 1L)
        val eligibility = HeartRateEligibilityReducer.reduce(
            supportedEligibility(key),
            HeartRateEligibilityEvent.SessionCleared(key),
        )
        assertFalse(eligibility.supported)
        assertTrue(eligibility.featureVisible)

        listOf(
            HeartRateSessionStatus.RECONNECTING,
            HeartRateSessionStatus.WAITING_FOR_AIRPODS,
        ).forEach { status ->
            val ui = HeartRateUiStateMapper.map(
                eligibility = eligibility,
                session = liveSession(key, bpm = 80, sampleTime = 5_000L).copy(
                    status = status,
                    key = null,
                    supportedTransport = false,
                ),
                nowElapsedRealtimeMillis = 5_001L,
            )

            assertTrue(ui.supported, status.name)
            assertEquals(HeartRateUiStatus.valueOf(status.name), ui.status)
            assertNull(ui.latestBpm, status.name)
            assertEquals(
                HeartRateSampleFreshness.NO_SAMPLE,
                ui.sampleFreshness,
                status.name,
            )
        }
    }

    @Test
    fun `new unconfirmed lease hides the old Pro 3 feature eligibility`() {
        val oldKey = key(epoch = 1L)
        val disconnected = HeartRateEligibilityReducer.reduce(
            supportedEligibility(oldKey),
            HeartRateEligibilityEvent.SessionCleared(oldKey),
        )
        val newKey = key(epoch = 2L)
        val unconfirmed = HeartRateEligibilityReducer.reduce(
            disconnected,
            HeartRateEligibilityEvent.SessionInstalled(newKey),
        )

        val ui = HeartRateUiStateMapper.map(
            eligibility = unconfirmed,
            session = HeartRateSessionState(
                enabled = true,
                status = HeartRateSessionStatus.WAITING_FOR_AIRPODS,
                key = newKey,
            ),
            nowElapsedRealtimeMillis = 5_001L,
        )

        assertFalse(ui.supported)
        assertNull(ui.latestBpm)
        assertEquals(HeartRateSampleFreshness.NO_SAMPLE, ui.sampleFreshness)
    }

    @Test
    fun `UI state string representation redacts health data`() {
        val text = HeartRateUiState(
            supported = true,
            enabled = true,
            status = HeartRateUiStatus.LIVE,
            latestBpm = 181,
            sampleFreshness = HeartRateSampleFreshness.FRESH,
        ).toString()

        assertFalse(text.contains("181"))
        assertTrue(text.contains("latestBpm=redacted"))
        assertFalse(text.contains("lease", ignoreCase = true))
        assertFalse(text.contains("token", ignoreCase = true))
    }

    private fun supportedEligibility(key: HrmSessionKey): HeartRateEligibilityState {
        var state = HeartRateEligibilityReducer.reduce(
            HeartRateEligibilityState(),
            HeartRateEligibilityEvent.SessionInstalled(key),
        )
        state = HeartRateEligibilityReducer.reduce(
            state,
            HeartRateEligibilityEvent.DeviceInformationConfirmed(
                key = key,
                modelNumber = "A3063",
                modelCapabilities = setOf(Capability.HRM),
            ),
        )
        return state
    }

    private fun liveSession(
        key: HrmSessionKey,
        bpm: Int,
        sampleTime: Long,
    ) = HeartRateSessionState(
        enabled = true,
        status = HeartRateSessionStatus.LIVE,
        key = key,
        measurements = listOf(measurement(bpm, sampleTime)),
    )

    private fun measurement(bpm: Int, sampleTime: Long) = HeartRateMeasurement(
        bpm = bpm,
        sequence = 1L,
        wallClockMillis = 1_000L,
        elapsedRealtimeMillis = sampleTime,
    )

    private fun key(epoch: Long) = HrmSessionKey(
        socketIdentity = Any(),
        generation = epoch,
        deviceKey = "device-$epoch",
        epoch = epoch,
    )
}
