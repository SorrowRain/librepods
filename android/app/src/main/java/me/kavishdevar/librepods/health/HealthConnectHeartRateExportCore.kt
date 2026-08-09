/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.health

import java.nio.ByteBuffer
import java.security.MessageDigest
import me.kavishdevar.librepods.heart.HeartRateMeasurement

internal enum class HeartRateExportGranularity(
    val windowMillis: Long,
    internal val identityCode: Byte,
) {
    MINUTE_AVERAGE(windowMillis = 60_000L, identityCode = 0),
    DETAILED(windowMillis = 1_000L, identityCode = 1),
}

/** Non-device-identifying discriminator for one HRM acquisition. */
internal data class HeartRateExportAcquisitionIdentity(
    val connectionGeneration: Long,
    val sessionEpoch: Long,
    val acquisition: Long,
) {
    override fun toString(): String = "HeartRateExportAcquisitionIdentity(redacted)"
}

/**
 * Non-health metadata used to mint an idempotent Health Connect client record ID.
 *
 * Implementations must return an opaque value. They must not embed timestamps or device identifiers
 * in plaintext. BPM is intentionally absent from this input.
 */
internal data class HeartRateExportRecordIdentity(
    val acquisitionIdentity: HeartRateExportAcquisitionIdentity,
    val granularity: HeartRateExportGranularity,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val firstSequence: Long,
    val lastSequence: Long,
    val sampleCount: Int,
) {
    override fun toString(): String = "HeartRateExportRecordIdentity(redacted)"
}

internal fun interface HeartRateClientRecordIdFactory {
    fun create(identity: HeartRateExportRecordIdentity): String
}

/** Stable, device-independent record IDs whose observable value contains only a SHA-256 digest. */
internal object Sha256HeartRateClientRecordIdFactory : HeartRateClientRecordIdFactory {
    override fun create(identity: HeartRateExportRecordIdentity): String {
        val canonicalIdentity = ByteBuffer.allocate(CANONICAL_IDENTITY_SIZE)
            .put(identity.granularity.identityCode)
            .putLong(identity.acquisitionIdentity.connectionGeneration)
            .putLong(identity.acquisitionIdentity.sessionEpoch)
            .putLong(identity.acquisitionIdentity.acquisition)
            .putLong(identity.startTimeMillis)
            .putLong(identity.endTimeMillis)
            .putLong(identity.firstSequence)
            .putLong(identity.lastSequence)
            .putInt(identity.sampleCount)
            .array()
        val digest = MessageDigest.getInstance("SHA-256").apply {
            update(RECORD_ID_DOMAIN.toByteArray(Charsets.UTF_8))
            update(0)
            update(canonicalIdentity)
        }.digest()

        return buildString(RECORD_ID_PREFIX.length + digest.size * 2) {
            append(RECORD_ID_PREFIX)
            digest.forEach { byte ->
                val unsigned = byte.toInt() and 0xff
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }

    private const val CANONICAL_IDENTITY_SIZE = 1 + Long.SIZE_BYTES * 7 + Int.SIZE_BYTES
    private const val RECORD_ID_DOMAIN = "librepods-heart-rate-record-v1"
    private const val RECORD_ID_PREFIX = "librepods-heart-rate-v1-"
    private const val HEX = "0123456789abcdef"
}

/** A platform-neutral record ready for conversion to a Health Connect HeartRateRecord. */
internal data class PendingHeartRateExportRecord(
    val beatsPerMinute: Long,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val sampleCount: Int,
    val granularity: HeartRateExportGranularity,
    val clientRecordId: String,
) {
    val sampleTimeMillis: Long
        get() = startTimeMillis + (endTimeMillis - startTimeMillis) / 2L

    override fun toString(): String = "PendingHeartRateExportRecord(redacted)"
}

/**
 * Pure aggregation and retry state for optional Health Connect export.
 *
 * This class is intentionally synchronous and not thread-safe. The service owns serialization and
 * performs platform writes. A failed or cancelled write remains reserved at the head of the queue;
 * retries therefore receive the exact same object and client record ID.
 */
internal class HealthConnectHeartRateExportCore(
    private val recordIdFactory: HeartRateClientRecordIdFactory =
        Sha256HeartRateClientRecordIdFactory,
    private val maxPendingRecords: Int = MAX_PENDING_RECORDS,
) {
    init {
        require(maxPendingRecords in 1..MAX_PENDING_RECORDS) {
            "Pending record limit must be within the supported bound"
        }
    }

    var enabled: Boolean = false
        private set

    var detailedSamples: Boolean = false
        private set

    val granularity: HeartRateExportGranularity
        get() = if (detailedSamples) {
            HeartRateExportGranularity.DETAILED
        } else {
            HeartRateExportGranularity.MINUTE_AVERAGE
        }

    val pendingRecordCount: Int
        get() = pendingRecords.size

    val hasPartialWindow: Boolean
        get() = activeWindow != null

    val activeWindowDeadlineElapsedRealtimeMillis: Long?
        get() = activeWindow?.let { window ->
            saturatingAdd(window.startElapsedRealtimeMillis, granularity.windowMillis)
        }

    private val pendingRecords = mutableListOf<PendingHeartRateExportRecord>()
    private val recentSequences = LinkedHashMap<AcquisitionSequence, Unit>()
    private var activeWindow: AccumulatingWindow? = null
    private var inFlightRecordId: String? = null

    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) clearExportData()
    }

    /** Finalizes the old partial window before applying a new aggregation interval. */
    fun setDetailedSamples(value: Boolean) {
        if (detailedSamples == value) return
        if (enabled) flushPartialWindow()
        detailedSamples = value
    }

    /** Returns false when export is disabled or this sequence has already been consumed recently. */
    fun consume(
        acquisitionIdentity: HeartRateExportAcquisitionIdentity,
        measurement: HeartRateMeasurement,
    ): Boolean {
        if (!enabled) return false
        require(measurement.bpm in MIN_VALIDATED_BPM..MAX_VALIDATED_BPM) {
            "Heart rate export requires a validated measurement"
        }
        require(measurement.wallClockMillis in 0 until Long.MAX_VALUE) {
            "Heart rate export requires a valid wall clock"
        }
        require(measurement.elapsedRealtimeMillis >= 0L) {
            "Heart rate export requires a valid monotonic timestamp"
        }
        if (!rememberSequence(acquisitionIdentity, measurement.sequence)) return false

        val current = activeWindow
        if (current == null) {
            activeWindow = AccumulatingWindow(acquisitionIdentity, measurement)
            return true
        }

        if (current.acquisitionIdentity != acquisitionIdentity) {
            finalizeActiveWindow(completed = false)
            activeWindow = AccumulatingWindow(acquisitionIdentity, measurement)
            return true
        }

        val deadline = saturatingAdd(current.startElapsedRealtimeMillis, granularity.windowMillis)
        if (measurement.elapsedRealtimeMillis >= deadline) {
            finalizeActiveWindow(completed = true)
            activeWindow = AccumulatingWindow(acquisitionIdentity, measurement)
        } else if (current.hasClockDiscontinuity(measurement)) {
            finalizeActiveWindow(completed = false)
            activeWindow = AccumulatingWindow(acquisitionIdentity, measurement)
        } else {
            current.add(measurement)
        }
        return true
    }

    /** Enqueues the current non-empty window without writing it. */
    fun flushPartialWindow(): Boolean = finalizeActiveWindow(completed = false)

    /**
     * Reserves and returns the oldest record. Repeated calls return the same record until success.
     */
    fun nextPendingRecord(): PendingHeartRateExportRecord? {
        if (!enabled) return null
        val record = pendingRecords.firstOrNull() ?: return null
        val reservedId = inFlightRecordId
        check(reservedId == null || reservedId == record.clientRecordId) {
            "Heart rate export queue reservation is inconsistent"
        }
        inFlightRecordId = record.clientRecordId
        return record
    }

    /** A failure deliberately leaves the head reservation and its stable ID unchanged. */
    fun onWriteFailed(clientRecordId: String): Boolean =
        enabled &&
            inFlightRecordId == clientRecordId &&
            pendingRecords.firstOrNull()?.clientRecordId == clientRecordId

    /** Removes exactly one reserved head record. Duplicate or late acknowledgements are ignored. */
    fun onWriteSucceeded(clientRecordId: String): Boolean {
        if (!enabled || inFlightRecordId != clientRecordId) return false
        if (pendingRecords.firstOrNull()?.clientRecordId != clientRecordId) return false

        pendingRecords.removeAt(0)
        inFlightRecordId = null
        return true
    }

    override fun toString(): String =
        "HealthConnectHeartRateExportCore(" +
            "enabled=$enabled, detailedSamples=$detailedSamples, " +
            "pendingRecordCount=$pendingRecordCount, hasPartialWindow=$hasPartialWindow)"

    private fun rememberSequence(
        acquisitionIdentity: HeartRateExportAcquisitionIdentity,
        sequence: Long,
    ): Boolean {
        val key = AcquisitionSequence(acquisitionIdentity, sequence)
        if (recentSequences.put(key, Unit) != null) return false
        if (recentSequences.size > MAX_TRACKED_SEQUENCES) {
            val oldest = recentSequences.entries.iterator()
            oldest.next()
            oldest.remove()
        }
        return true
    }

    private fun finalizeActiveWindow(completed: Boolean): Boolean {
        val window = activeWindow ?: return false
        val record = window.toRecord(
            granularity = granularity,
            completed = completed,
            recordIdFactory = recordIdFactory,
        )
        enqueue(record)
        activeWindow = null
        return true
    }

    private fun enqueue(record: PendingHeartRateExportRecord) {
        if (pendingRecords.size >= maxPendingRecords) {
            val reservedHead = inFlightRecordId == pendingRecords.firstOrNull()?.clientRecordId
            val evictionIndex = if (reservedHead) 1 else 0
            if (evictionIndex >= pendingRecords.size) return
            pendingRecords.removeAt(evictionIndex)
        }
        pendingRecords += record
    }

    private fun clearExportData() {
        activeWindow = null
        pendingRecords.clear()
        recentSequences.clear()
        inFlightRecordId = null
    }

    private data class AcquisitionSequence(
        val acquisitionIdentity: HeartRateExportAcquisitionIdentity,
        val sequence: Long,
    )

    private class AccumulatingWindow(
        val acquisitionIdentity: HeartRateExportAcquisitionIdentity,
        measurement: HeartRateMeasurement,
    ) {
        val startElapsedRealtimeMillis: Long = measurement.elapsedRealtimeMillis
        private val firstWallClockMillis: Long = measurement.wallClockMillis
        private val firstSequence: Long = measurement.sequence
        private var lastElapsedRealtimeMillis: Long = measurement.elapsedRealtimeMillis
        private var lastSequence: Long = measurement.sequence
        private var bpmSum: Long = measurement.bpm.toLong()
        private var count: Int = 1

        fun hasClockDiscontinuity(measurement: HeartRateMeasurement): Boolean {
            if (measurement.elapsedRealtimeMillis < lastElapsedRealtimeMillis) return true
            val elapsedSinceStart = measurement.elapsedRealtimeMillis - startElapsedRealtimeMillis
            val expectedWallClock = saturatingAdd(firstWallClockMillis, elapsedSinceStart)
            return absoluteDifference(measurement.wallClockMillis, expectedWallClock) >
                MAX_WALL_CLOCK_DRIFT_MILLIS
        }

        fun add(measurement: HeartRateMeasurement) {
            lastElapsedRealtimeMillis = measurement.elapsedRealtimeMillis
            lastSequence = measurement.sequence
            bpmSum += measurement.bpm.toLong()
            count += 1
        }

        fun toRecord(
            granularity: HeartRateExportGranularity,
            completed: Boolean,
            recordIdFactory: HeartRateClientRecordIdFactory,
        ): PendingHeartRateExportRecord {
            val observedDuration = lastElapsedRealtimeMillis - startElapsedRealtimeMillis
            val observedEnd = saturatingAdd(
                firstWallClockMillis,
                saturatingAdd(observedDuration, 1L),
            )
            val completedEnd = saturatingAdd(firstWallClockMillis, granularity.windowMillis)
            val endTimeMillis = if (completed) {
                maxOf(observedEnd, completedEnd)
            } else {
                observedEnd
            }
            val identity = HeartRateExportRecordIdentity(
                acquisitionIdentity = acquisitionIdentity,
                granularity = granularity,
                startTimeMillis = firstWallClockMillis,
                endTimeMillis = endTimeMillis,
                firstSequence = firstSequence,
                lastSequence = lastSequence,
                sampleCount = count,
            )
            val clientRecordId = recordIdFactory.create(identity)
            require(clientRecordId.isNotBlank() && clientRecordId.length <= MAX_CLIENT_RECORD_ID_LENGTH) {
                "Heart rate client record ID is invalid"
            }
            require(clientRecordId.all { it.code in PRINTABLE_ASCII_RANGE }) {
                "Heart rate client record ID must be opaque printable ASCII"
            }

            return PendingHeartRateExportRecord(
                beatsPerMinute = (bpmSum + count / 2L) / count,
                startTimeMillis = firstWallClockMillis,
                endTimeMillis = endTimeMillis,
                sampleCount = count,
                granularity = granularity,
                clientRecordId = clientRecordId,
            )
        }

        override fun toString(): String = "AccumulatingWindow(redacted)"
    }

    internal companion object {
        const val MAX_PENDING_RECORDS = 300
        private const val MAX_TRACKED_SEQUENCES = 4_096
        private const val MAX_CLIENT_RECORD_ID_LENGTH = 100
        private const val MIN_VALIDATED_BPM = 30
        private const val MAX_VALIDATED_BPM = 220
        private const val MAX_WALL_CLOCK_DRIFT_MILLIS = 5_000L
        private val PRINTABLE_ASCII_RANGE = 0x21..0x7e

        private fun saturatingAdd(value: Long, increment: Long): Long =
            if (value > Long.MAX_VALUE - increment) Long.MAX_VALUE else value + increment

        private fun absoluteDifference(first: Long, second: Long): Long =
            if (first >= second) first - second else second - first
    }
}
