/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.health

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.heart.HeartRateMeasurement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthConnectHeartRateExporterTest {
    @Test
    fun `export defaults off even when write permission is already granted`() = exporterTest {
        gateway.permissionGranted = true

        exporter.refreshAndAwait()

        assertEquals(HealthConnectExportStatus.READY, exporter.state.value.status)
        assertFalse(exporter.state.value.requestedEnabled)
        assertFalse(exporter.state.value.enabled)
        assertFalse(preferences.exportEnabled)
        assertFalse(exporter.acceptAndAwait(measurement(1L, bpm = 72, elapsed = 0L)))
        assertTrue(gateway.attempts.isEmpty())
    }

    @Test
    fun `only write heart rate permission is requested`() {
        assertEquals(
            setOf("android.permission.health.WRITE_HEART_RATE"),
            HealthConnectHeartRateExporter.REQUIRED_PERMISSIONS,
        )
        assertEquals(
            "android.permission.health.WRITE_HEART_RATE",
            HealthConnectHeartRateExporter.WRITE_HEART_RATE_PERMISSION,
        )
    }

    @Test
    fun `enable without permission remains off and is not persisted`() = exporterTest {
        gateway.permissionGranted = false

        exporter.setEnabledAndAwait(true)

        assertFalse(exporter.state.value.enabled)
        assertFalse(exporter.state.value.requestedEnabled)
        assertEquals(
            HealthConnectExportStatus.PERMISSION_REQUIRED,
            exporter.state.value.status,
        )
        assertFalse(preferences.exportEnabled)
        assertFalse(exporter.acceptAndAwait(measurement(1L, bpm = 70, elapsed = 0L)))
        assertTrue(gateway.attempts.isEmpty())
    }

    @Test
    fun `minute mode writes one rounded average after the window completes`() = exporterTest {
        enable()

        accept(sequence = 1L, bpm = 60, elapsed = 0L)
        accept(sequence = 2L, bpm = 89, elapsed = 59_999L)
        accept(sequence = 3L, bpm = 100, elapsed = 60_000L)

        val written = gateway.successes.single()
        assertEquals(75L, written.beatsPerMinute)
        assertEquals(2, written.sampleCount)
        assertEquals(HeartRateExportGranularity.MINUTE_AVERAGE, written.granularity)
        assertEquals(HealthConnectExportStatus.ENABLED, exporter.state.value.status)
    }

    @Test
    fun `detailed mode writes one average per second`() = exporterTest {
        exporter.setDetailedSamplesAndAwait(true)
        enable()

        accept(sequence = 1L, bpm = 80, elapsed = 0L)
        accept(sequence = 2L, bpm = 82, elapsed = 999L)
        accept(sequence = 3L, bpm = 90, elapsed = 1_000L)

        val written = gateway.successes.single()
        assertEquals(81L, written.beatsPerMinute)
        assertEquals(HeartRateExportGranularity.DETAILED, written.granularity)
        assertTrue(preferences.detailedSamples)
        assertTrue(exporter.state.value.detailedSamples)
    }

    @Test
    fun `same sequence can be exported by consecutive acquisitions`() = exporterTest {
        enable()

        accept(sequence = 1L, bpm = 72, elapsed = 0L)
        assertTrue(exporter.flushAndAwait())
        accept(
            sequence = 1L,
            bpm = 78,
            elapsed = 1_000L,
            acquisitionIdentity = SECOND_ACQUISITION,
        )
        assertTrue(exporter.flushAndAwait())

        assertEquals(listOf(72L, 78L), gateway.successes.map { it.beatsPerMinute })
        assertNotEquals(
            gateway.successes[0].clientRecordId,
            gateway.successes[1].clientRecordId,
        )
    }

    @Test
    fun `partial window flush writes only while enabled and authorized`() = exporterTest {
        enable()
        accept(sequence = 1L, bpm = 77, elapsed = 0L)

        assertTrue(exporter.flushAndAwait())

        assertEquals(77L, gateway.successes.single().beatsPerMinute)
        assertTrue(exporter.state.value.enabled)
    }

    @Test
    fun `retry reuses exact client id and success is not written twice`() = exporterTest {
        enable()
        gateway.insertFailures.addLast(IOException("temporary"))
        accept(sequence = 9L, bpm = 74, elapsed = 0L)

        assertTrue(exporter.flushAndAwait())
        assertEquals(HealthConnectExportStatus.ERROR, exporter.state.value.status)
        val firstId = gateway.attempts.single().clientRecordId

        assertTrue(exporter.retryNowAndAwait())
        assertEquals(2, gateway.attempts.size)
        assertEquals(firstId, gateway.attempts.last().clientRecordId)
        assertEquals(firstId, gateway.successes.single().clientRecordId)

        assertTrue(exporter.retryNowAndAwait())
        assertEquals(2, gateway.attempts.size)
    }

    @Test
    fun `permission revocation before write disables and clears without insertion`() = exporterTest {
        enable()
        accept(sequence = 1L, bpm = 72, elapsed = 0L)
        gateway.permissionGranted = false

        assertTrue(exporter.flushAndAwait())

        assertTrue(gateway.attempts.isEmpty())
        assertFalse(exporter.state.value.enabled)
        assertFalse(exporter.state.value.requestedEnabled)
        assertFalse(preferences.exportEnabled)
        assertEquals(
            HealthConnectExportStatus.PERMISSION_REQUIRED,
            exporter.state.value.status,
        )
        gateway.permissionGranted = true
        assertFalse(exporter.retryNowAndAwait())
        assertTrue(gateway.attempts.isEmpty())
    }

    @Test
    fun `new samples respect retry backoff and disable discards queued data`() = exporterTest {
        enable()
        gateway.insertFailures.addLast(IOException("temporary"))
        gateway.insertFailures.addLast(IOException("temporary again"))
        accept(sequence = 1L, bpm = 72, elapsed = 0L)
        exporter.flushAndAwait()
        accept(sequence = 2L, bpm = 73, elapsed = 1_000L)
        val attemptsBeforeDisable = gateway.attempts.size
        assertEquals(1, attemptsBeforeDisable)

        exporter.setEnabledAndAwait(false)

        assertFalse(exporter.state.value.enabled)
        assertFalse(preferences.exportEnabled)
        assertFalse(exporter.flushAndAwait())
        assertFalse(exporter.retryNowAndAwait())
        assertEquals(attemptsBeforeDisable, gateway.attempts.size)

        exporter.setEnabledAndAwait(true)
        assertTrue(exporter.retryNowAndAwait())
        assertEquals(attemptsBeforeDisable, gateway.attempts.size)
    }

    @Test
    fun `public disable persists opt out before asynchronous cleanup`() = exporterTest {
        enable()

        exporter.setEnabled(false)

        assertFalse(preferences.exportEnabled)
        assertFalse(exporter.state.value.enabled)
        exporter.closeAndAwait()
        assertFalse(preferences.exportEnabled)
    }

    @Test
    fun `close cancels a blocked provider write without a tail success`() = exporterTest {
        enable()
        accept(sequence = 1L, bpm = 72, elapsed = 0L)
        gateway.insertDelayMillis = 10_000L

        exporter.flush()
        withTimeout(1_000L) {
            while (gateway.attempts.isEmpty()) delay(1L)
        }
        withTimeout(1_000L) {
            exporter.closeAndAwait()
        }

        assertFalse(exporter.state.value.enabled)
        assertTrue(gateway.successes.isEmpty())
    }

    @Test
    fun `disable cancels a blocked provider write and revokes requested consent`() = exporterTest {
        enable()
        accept(sequence = 1L, bpm = 72, elapsed = 0L)
        gateway.insertDelayMillis = 10_000L

        exporter.flush()
        withTimeout(1_000L) {
            while (gateway.attempts.isEmpty()) delay(1L)
        }
        exporter.setEnabled(false)
        withTimeout(1_000L) {
            while (gateway.insertCancellations == 0) delay(1L)
        }
        exporter.setEnabledAndAwait(false)

        assertFalse(exporter.state.value.requestedEnabled)
        assertFalse(exporter.state.value.enabled)
        assertFalse(preferences.exportEnabled)
        assertTrue(gateway.successes.isEmpty())
    }

    @Test
    fun `buffered measurements from revoked consent are not exported after reenable`() = exporterTest {
        exporter.setDetailedSamplesAndAwait(true)
        enable()
        accept(sequence = 1L, bpm = 72, elapsed = 0L)
        gateway.insertDelayMillis = 10_000L
        exporter.flush()
        withTimeout(1_000L) {
            while (gateway.attempts.isEmpty()) delay(1L)
        }

        exporter.accept(DEFAULT_ACQUISITION, measurement(2L, bpm = 99, elapsed = 1_000L))
        exporter.setEnabled(false)
        exporter.setEnabledAndAwait(false)
        gateway.insertDelayMillis = 0L
        exporter.setEnabledAndAwait(true)
        delay(20L)

        assertFalse(exporter.flushAndAwait())
        assertTrue(gateway.successes.isEmpty())
    }

    @Test
    fun `permission denial immediately clears requested enable`() = exporterTest {
        enable()

        exporter.markPermissionDeniedAndAwait()

        assertFalse(exporter.state.value.enabled)
        assertFalse(exporter.state.value.requestedEnabled)
        assertFalse(preferences.exportEnabled)
        assertEquals(
            HealthConnectExportStatus.PERMISSION_DENIED,
            exporter.state.value.status,
        )
    }

    @Test
    fun `restored enable preference is cleared when permission is absent`() = exporterTest(
        initialExportEnabled = true,
    ) {
        gateway.permissionGranted = false

        exporter.refreshAndAwait()

        assertFalse(preferences.exportEnabled)
        assertFalse(exporter.state.value.enabled)
        assertEquals(
            HealthConnectExportStatus.PERMISSION_REQUIRED,
            exporter.state.value.status,
        )
    }

    @Test
    fun `permission query failure keeps export off and writes nothing`() = exporterTest(
        initialExportEnabled = true,
    ) {
        gateway.permissionGranted = true
        gateway.permissionFailure = IOException("provider unavailable")

        exporter.refreshAndAwait()

        assertFalse(exporter.state.value.enabled)
        assertEquals(HealthConnectExportStatus.ERROR, exporter.state.value.status)
        assertTrue(exporter.state.value.requestedEnabled)
        assertTrue(preferences.exportEnabled)
        assertTrue(gateway.attempts.isEmpty())

        gateway.permissionFailure = null
        assertTrue(exporter.retryNowAndAwait())
        assertTrue(exporter.state.value.enabled)
        assertEquals(HealthConnectExportStatus.ENABLED, exporter.state.value.status)
    }

    @Test
    fun `activation error remains visibly requested and can be revoked`() = exporterTest(
        initialExportEnabled = true,
    ) {
        gateway.permissionGranted = true
        gateway.permissionFailure = IOException("provider unavailable")
        exporter.refreshAndAwait()

        assertTrue(exporter.state.value.requestedEnabled)
        assertFalse(exporter.state.value.enabled)

        exporter.setEnabledAndAwait(false)
        gateway.permissionFailure = null

        assertFalse(exporter.state.value.requestedEnabled)
        assertFalse(preferences.exportEnabled)
        assertFalse(exporter.retryNowAndAwait())
    }

    @Test
    fun `permission query timeout remains off until bounded retry succeeds`() = exporterTest(
        initialExportEnabled = true,
        operationTimeoutMillis = 5L,
    ) {
        gateway.permissionGranted = true
        gateway.permissionDelayMillis = 100L

        exporter.refreshAndAwait()

        assertFalse(exporter.state.value.enabled)
        assertEquals(HealthConnectExportStatus.ERROR, exporter.state.value.status)
        assertTrue(exporter.state.value.requestedEnabled)
        assertTrue(preferences.exportEnabled)
        assertTrue(gateway.attempts.isEmpty())

        gateway.permissionDelayMillis = 0L
        assertTrue(exporter.retryNowAndAwait())
        assertTrue(exporter.state.value.enabled)
    }

    @Test
    fun `platform record contains average and opaque id without device identifiers`() {
        val pending = PendingHeartRateExportRecord(
            beatsPerMinute = 88L,
            startTimeMillis = BASE_WALL_CLOCK,
            endTimeMillis = BASE_WALL_CLOCK + 1_000L,
            sampleCount = 1,
            granularity = HeartRateExportGranularity.DETAILED,
            clientRecordId = "librepods-heart-rate-v1-opaque",
        )

        val platform = pending.toHealthConnectRecord()

        assertEquals(88L, platform.samples.single().beatsPerMinute)
        assertEquals(pending.clientRecordId, platform.metadata.clientRecordId)
        assertEquals(0L, platform.metadata.clientRecordVersion)
        val device = requireNotNull(platform.metadata.device)
        assertEquals(0, device.type)
        assertNull(device.manufacturer)
        assertNull(device.model)
    }

    private fun exporterTest(
        initialExportEnabled: Boolean = false,
        operationTimeoutMillis: Long = 10_000L,
        block: suspend ExporterHarness.() -> Unit,
    ) = runBlocking {
        val harness = ExporterHarness(initialExportEnabled, operationTimeoutMillis)
        try {
            harness.block()
        } finally {
            harness.exporter.closeAndAwait()
            harness.scope.cancel()
        }
    }

    private class ExporterHarness(
        initialExportEnabled: Boolean,
        operationTimeoutMillis: Long,
    ) {
        val gateway = FakeGateway()
        val preferences = FakePreferences(exportEnabled = initialExportEnabled)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var nowElapsedRealtimeMillis = 0L
        val exporter = HealthConnectHeartRateExporter(
            gateway = gateway,
            preferences = preferences,
            scope = scope,
            elapsedRealtimeMillis = { nowElapsedRealtimeMillis },
            retryDelayMillis = 60_000L,
            operationTimeoutMillis = operationTimeoutMillis,
            logger = HealthConnectExportLogger { },
        )

        suspend fun enable() {
            gateway.permissionGranted = true
            exporter.setEnabledAndAwait(true)
            assertTrue(exporter.state.value.enabled)
            assertTrue(exporter.state.value.requestedEnabled)
            assertTrue(preferences.exportEnabled)
        }

        suspend fun accept(
            sequence: Long,
            bpm: Int,
            elapsed: Long,
            acquisitionIdentity: HeartRateExportAcquisitionIdentity = DEFAULT_ACQUISITION,
        ) {
            nowElapsedRealtimeMillis = elapsed
            assertTrue(
                exporter.acceptAndAwait(
                    acquisitionIdentity,
                    measurement(sequence, bpm, elapsed),
                )
            )
        }
    }

    private class FakeGateway : HealthConnectHeartRateGateway {
        var sdkAvailability = HealthConnectSdkAvailability.AVAILABLE
        var permissionGranted = false
        var permissionFailure: Exception? = null
        var permissionDelayMillis = 0L
        var insertDelayMillis = 0L
        var insertCancellations = 0
        val insertFailures = ArrayDeque<Exception>()
        val attempts = mutableListOf<PendingHeartRateExportRecord>()
        val successes = mutableListOf<PendingHeartRateExportRecord>()

        override fun availability(): HealthConnectSdkAvailability = sdkAvailability

        override suspend fun hasWriteHeartRatePermission(): Boolean {
            if (permissionDelayMillis > 0L) delay(permissionDelayMillis)
            permissionFailure?.let { throw it }
            return permissionGranted
        }

        override suspend fun insert(record: PendingHeartRateExportRecord) {
            attempts += record
            try {
                if (insertDelayMillis > 0L) delay(insertDelayMillis)
                insertFailures.removeFirstOrNull()?.let { throw it }
                successes += record
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                insertCancellations += 1
                throw cancellation
            }
        }
    }

    private data class FakePreferences(
        override var exportEnabled: Boolean = false,
        override var detailedSamples: Boolean = false,
    ) : HeartRateExportPreferences

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

        fun measurement(sequence: Long, bpm: Int, elapsed: Long) = HeartRateMeasurement(
            bpm = bpm,
            sequence = sequence,
            wallClockMillis = BASE_WALL_CLOCK + elapsed,
            elapsedRealtimeMillis = elapsed,
        )
    }

    private suspend fun HealthConnectHeartRateExporter.acceptAndAwait(
        measurement: HeartRateMeasurement,
    ): Boolean = acceptAndAwait(DEFAULT_ACQUISITION, measurement)
}
