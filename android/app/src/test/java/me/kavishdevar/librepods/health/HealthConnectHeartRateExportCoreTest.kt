/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.health

import me.kavishdevar.librepods.heart.HeartRateMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectHeartRateExportCoreTest {
    @Test
    fun `export defaults disabled with minute aggregation`() {
        val core = HealthConnectHeartRateExportCore()

        assertFalse(core.enabled)
        assertFalse(core.detailedSamples)
        assertEquals(HeartRateExportGranularity.MINUTE_AVERAGE, core.granularity)
        assertFalse(core.consume(measurement(sequence = 1L, bpm = 72, elapsed = 0L)))
        assertEquals(0, core.pendingRecordCount)
        assertFalse(core.hasPartialWindow)
    }

    @Test
    fun `minute mode emits rounded average when the next window starts`() {
        val core = enabledCore()

        assertTrue(core.consume(measurement(sequence = 1L, bpm = 60, elapsed = 0L)))
        assertTrue(core.consume(measurement(sequence = 2L, bpm = 89, elapsed = 59_999L)))
        assertTrue(core.consume(measurement(sequence = 3L, bpm = 100, elapsed = 60_000L)))

        val record = requireNotNull(core.nextPendingRecord())
        assertEquals(75L, record.beatsPerMinute)
        assertEquals(2, record.sampleCount)
        assertEquals(HeartRateExportGranularity.MINUTE_AVERAGE, record.granularity)
        assertEquals(BASE_WALL_CLOCK, record.startTimeMillis)
        assertEquals(BASE_WALL_CLOCK + 60_000L, record.endTimeMillis)
        assertTrue(core.hasPartialWindow)
    }

    @Test
    fun `detailed mode averages one second windows`() {
        val core = enabledCore().apply { setDetailedSamples(true) }

        core.consume(measurement(sequence = 1L, bpm = 80, elapsed = 5_000L))
        core.consume(measurement(sequence = 2L, bpm = 82, elapsed = 5_999L))
        core.consume(measurement(sequence = 3L, bpm = 90, elapsed = 6_000L))

        val record = requireNotNull(core.nextPendingRecord())
        assertEquals(81L, record.beatsPerMinute)
        assertEquals(2, record.sampleCount)
        assertEquals(HeartRateExportGranularity.DETAILED, record.granularity)
        assertEquals(record.startTimeMillis + 500L, record.sampleTimeMillis)
    }

    @Test
    fun `partial window can be flushed without a later sample`() {
        val core = enabledCore()
        core.consume(measurement(sequence = 1L, bpm = 70, elapsed = 0L))
        core.consume(measurement(sequence = 2L, bpm = 81, elapsed = 10_000L))

        assertTrue(core.flushPartialWindow())
        assertFalse(core.flushPartialWindow())

        val record = requireNotNull(core.nextPendingRecord())
        assertEquals(76L, record.beatsPerMinute)
        assertEquals(BASE_WALL_CLOCK, record.startTimeMillis)
        assertEquals(BASE_WALL_CLOCK + 10_001L, record.endTimeMillis)
        assertFalse(core.hasPartialWindow)
    }

    @Test
    fun `forward wall clock jump starts a new bounded window`() {
        val core = enabledCore()
        core.consume(measurement(sequence = 1L, bpm = 70, elapsed = 0L))

        core.consume(
            measurement(
                sequence = 2L,
                bpm = 80,
                elapsed = 1_000L,
                wallClock = BASE_WALL_CLOCK + 3_600_000L,
            )
        )

        val beforeJump = requireNotNull(core.nextPendingRecord())
        assertEquals(BASE_WALL_CLOCK, beforeJump.startTimeMillis)
        assertEquals(BASE_WALL_CLOCK + 1L, beforeJump.endTimeMillis)
        assertTrue(core.onWriteSucceeded(beforeJump.clientRecordId))
        assertTrue(core.flushPartialWindow())
        val afterJump = requireNotNull(core.nextPendingRecord())
        assertEquals(BASE_WALL_CLOCK + 3_600_000L, afterJump.startTimeMillis)
        assertEquals(afterJump.startTimeMillis + 1L, afterJump.endTimeMillis)
    }

    @Test
    fun `backward wall clock jump starts a new bounded window`() {
        val core = enabledCore()
        core.consume(measurement(sequence = 1L, bpm = 70, elapsed = 0L))

        core.consume(
            measurement(
                sequence = 2L,
                bpm = 80,
                elapsed = 1_000L,
                wallClock = BASE_WALL_CLOCK - 3_600_000L,
            )
        )

        val beforeJump = requireNotNull(core.nextPendingRecord())
        assertEquals(BASE_WALL_CLOCK, beforeJump.startTimeMillis)
        assertEquals(BASE_WALL_CLOCK + 1L, beforeJump.endTimeMillis)
        assertTrue(core.onWriteSucceeded(beforeJump.clientRecordId))
        assertTrue(core.flushPartialWindow())
        val afterJump = requireNotNull(core.nextPendingRecord())
        assertEquals(BASE_WALL_CLOCK - 3_600_000L, afterJump.startTimeMillis)
        assertEquals(afterJump.startTimeMillis + 1L, afterJump.endTimeMillis)
    }

    @Test
    fun `duplicate sequence is ignored and cannot skew an average`() {
        val core = enabledCore()

        assertTrue(core.consume(measurement(sequence = 7L, bpm = 70, elapsed = 0L)))
        assertFalse(core.consume(measurement(sequence = 7L, bpm = 200, elapsed = 1_000L)))
        assertTrue(core.flushPartialWindow())

        val record = requireNotNull(core.nextPendingRecord())
        assertEquals(70L, record.beatsPerMinute)
        assertEquals(1, record.sampleCount)
    }

    @Test
    fun `same sequence is accepted again for a new acquisition`() {
        val core = enabledCore()

        assertTrue(
            core.consume(
                DEFAULT_ACQUISITION,
                measurement(sequence = 7L, bpm = 70, elapsed = 0L),
            )
        )
        assertTrue(
            core.consume(
                SECOND_ACQUISITION,
                measurement(sequence = 7L, bpm = 80, elapsed = 1_000L),
            )
        )
        assertTrue(core.flushPartialWindow())

        val first = requireNotNull(core.nextPendingRecord())
        assertEquals(70L, first.beatsPerMinute)
        assertTrue(core.onWriteSucceeded(first.clientRecordId))
        val second = requireNotNull(core.nextPendingRecord())
        assertEquals(80L, second.beatsPerMinute)
        assertNotEquals(first.clientRecordId, second.clientRecordId)
    }

    @Test
    fun `retry keeps exact record and id then success removes it once`() {
        val core = enabledCore()
        core.consume(measurement(sequence = 21L, bpm = 74, elapsed = 0L))
        core.flushPartialWindow()

        val firstAttempt = requireNotNull(core.nextPendingRecord())
        assertTrue(core.onWriteFailed(firstAttempt.clientRecordId))
        val retry = requireNotNull(core.nextPendingRecord())
        assertSame(firstAttempt, retry)
        assertEquals(firstAttempt.clientRecordId, retry.clientRecordId)

        assertTrue(core.onWriteSucceeded(retry.clientRecordId))
        assertFalse(core.onWriteSucceeded(retry.clientRecordId))
        assertNull(core.nextPendingRecord())
        assertFalse(core.consume(measurement(sequence = 21L, bpm = 74, elapsed = 1_000L)))
        assertFalse(core.flushPartialWindow())
    }

    @Test
    fun `wrong acknowledgement cannot remove reserved record`() {
        val core = enabledCore()
        core.consume(measurement(sequence = 1L, bpm = 72, elapsed = 0L))
        core.flushPartialWindow()
        val record = requireNotNull(core.nextPendingRecord())

        assertFalse(core.onWriteFailed("wrong-id"))
        assertFalse(core.onWriteSucceeded("wrong-id"))
        assertEquals(1, core.pendingRecordCount)
        assertSame(record, core.nextPendingRecord())
    }

    @Test
    fun `disable drops pending partial and retry state without a tail write`() {
        val core = enabledCore()
        core.consume(measurement(sequence = 1L, bpm = 72, elapsed = 0L))
        core.flushPartialWindow()
        val pending = requireNotNull(core.nextPendingRecord())
        core.consume(measurement(sequence = 2L, bpm = 73, elapsed = 1_000L))

        core.setEnabled(false)

        assertFalse(core.enabled)
        assertEquals(0, core.pendingRecordCount)
        assertFalse(core.hasPartialWindow)
        assertNull(core.nextPendingRecord())
        assertFalse(core.onWriteSucceeded(pending.clientRecordId))
        assertFalse(core.flushPartialWindow())

        core.setEnabled(true)
        assertNull(core.nextPendingRecord())
        assertFalse(core.hasPartialWindow)
    }

    @Test
    fun `mode change flushes old partial under its original granularity`() {
        val core = enabledCore()
        core.consume(measurement(sequence = 1L, bpm = 72, elapsed = 0L))

        core.setDetailedSamples(true)

        val oldWindow = requireNotNull(core.nextPendingRecord())
        assertEquals(HeartRateExportGranularity.MINUTE_AVERAGE, oldWindow.granularity)
        assertEquals(HeartRateExportGranularity.DETAILED, core.granularity)
    }

    @Test
    fun `queue never exceeds the hard maximum`() {
        val core = enabledCore().apply { setDetailedSamples(true) }

        repeat(HealthConnectHeartRateExportCore.MAX_PENDING_RECORDS + 5) { index ->
            core.consume(
                measurement(
                    sequence = index.toLong(),
                    bpm = 70,
                    elapsed = index * 1_000L,
                ),
            )
        }
        core.flushPartialWindow()

        assertEquals(
            HealthConnectHeartRateExportCore.MAX_PENDING_RECORDS,
            core.pendingRecordCount,
        )
    }

    @Test
    fun `failed head is preserved when bounded queue discards older backlog`() {
        val idFactory = HeartRateClientRecordIdFactory { identity ->
            "opaque-sequence-${identity.firstSequence}"
        }
        val core = HealthConnectHeartRateExportCore(
            recordIdFactory = idFactory,
            maxPendingRecords = 3,
        ).apply {
            setEnabled(true)
            setDetailedSamples(true)
        }

        repeat(3) { index ->
            core.consume(measurement(index.toLong(), bpm = 70, elapsed = index * 1_000L))
        }
        val failed = requireNotNull(core.nextPendingRecord())
        assertTrue(core.onWriteFailed(failed.clientRecordId))

        repeat(3) { offset ->
            val index = offset + 3
            core.consume(measurement(index.toLong(), bpm = 70, elapsed = index * 1_000L))
        }
        core.flushPartialWindow()

        assertEquals(3, core.pendingRecordCount)
        assertSame(failed, core.nextPendingRecord())
    }

    @Test
    fun `default client id is stable opaque and independent of device identity`() {
        val first = completedRecord()
        val second = completedRecord()

        assertEquals(first.clientRecordId, second.clientRecordId)
        assertTrue(first.clientRecordId.startsWith("librepods-heart-rate-v1-"))
        assertFalse(first.clientRecordId.contains("187"))
        assertFalse(first.clientRecordId.contains(BASE_WALL_CLOCK.toString()))
        assertFalse(first.clientRecordId.contains("AA-BB-CC-DD-EE-FF"))

        val changed = completedRecord(sequence = 10L)
        assertNotEquals(first.clientRecordId, changed.clientRecordId)
    }

    @Test
    fun `record id factory is injectable without receiving bpm`() {
        var captured: HeartRateExportRecordIdentity? = null
        val core = HealthConnectHeartRateExportCore(
            recordIdFactory = HeartRateClientRecordIdFactory { identity ->
                captured = identity
                "opaque-test-record"
            },
        ).apply { setEnabled(true) }

        core.consume(measurement(sequence = 33L, bpm = 199, elapsed = 0L))
        core.flushPartialWindow()

        val identity = requireNotNull(captured)
        assertEquals(33L, identity.firstSequence)
        assertEquals(1, identity.sampleCount)
        assertEquals(
            "HeartRateExportAcquisitionIdentity(redacted)",
            identity.acquisitionIdentity.toString(),
        )
        assertEquals("HeartRateExportRecordIdentity(redacted)", identity.toString())
        assertEquals("opaque-test-record", core.nextPendingRecord()?.clientRecordId)
    }

    @Test
    fun `ordinary string forms redact bpm time and record contents`() {
        val record = completedRecord()
        val core = enabledCore()
        core.consume(measurement(sequence = 1L, bpm = 187, elapsed = 0L))

        val sensitiveValues = listOf("187", BASE_WALL_CLOCK.toString())
        sensitiveValues.forEach { sensitive ->
            assertFalse(record.toString().contains(sensitive))
            assertFalse(core.toString().contains(sensitive))
        }
        assertEquals("PendingHeartRateExportRecord(redacted)", record.toString())
    }

    private fun completedRecord(sequence: Long = 9L): PendingHeartRateExportRecord {
        val core = enabledCore()
        core.consume(measurement(sequence = sequence, bpm = 187, elapsed = 0L))
        core.flushPartialWindow()
        return requireNotNull(core.nextPendingRecord())
    }

    private fun enabledCore(): HealthConnectHeartRateExportCore =
        HealthConnectHeartRateExportCore().apply { setEnabled(true) }

    private fun HealthConnectHeartRateExportCore.consume(
        measurement: HeartRateMeasurement,
    ): Boolean = consume(DEFAULT_ACQUISITION, measurement)

    private fun measurement(
        sequence: Long,
        bpm: Int,
        elapsed: Long,
        wallClock: Long = BASE_WALL_CLOCK + elapsed,
    ) = HeartRateMeasurement(
        bpm = bpm,
        sequence = sequence,
        wallClockMillis = wallClock,
        elapsedRealtimeMillis = elapsed,
    )

    private companion object {
        const val BASE_WALL_CLOCK = 1_725_000_000_000L
        val DEFAULT_ACQUISITION = HeartRateExportAcquisitionIdentity(
            connectionGeneration = 4L,
            sessionEpoch = 9L,
            acquisition = 2L,
        )
        val SECOND_ACQUISITION = HeartRateExportAcquisitionIdentity(
            connectionGeneration = 5L,
            sessionEpoch = 10L,
            acquisition = 1L,
        )
    }
}
