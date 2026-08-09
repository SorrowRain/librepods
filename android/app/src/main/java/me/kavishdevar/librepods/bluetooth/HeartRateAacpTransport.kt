/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.bluetooth

import android.util.Log
import kotlinx.coroutines.delay

internal enum class HeartRateAacpStage {
    CONNECT_SERVICE_0,
    CAPABILITIES_SERVICE_0,
    CONNECT_SERVICE_4,
    CAPABILITIES_SERVICE_4,
    ENABLE_HRM_STATE,
    START_SENSOR_STREAM,
}

internal data class HeartRateAacpStep(
    val stage: HeartRateAacpStage,
    val packet: ByteArray,
    val delayAfterMillis: Long,
)

/** Exact AACP 1.3 and RTBuddy frames verified at upstream commit a573241. */
internal object HeartRateAacpFrames {
    private val CONNECT_SERVICE_0 = byteArrayOf(
        0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x03, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )
    private val CAPABILITIES_SERVICE_0 = byteArrayOf(
        0x04, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00,
    )
    private val CONNECT_SERVICE_4 = byteArrayOf(
        0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x03, 0x00,
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )
    private val CAPABILITIES_SERVICE_4 = byteArrayOf(
        0x04, 0x00, 0x04, 0x00, 0x01, 0x00, 0x00,
    )
    private val ENABLE_HRM_STATE = byteArrayOf(
        0x04, 0x00, 0x04, 0x00,
        0x09, 0x00, 0x2D, 0x01, 0x00, 0x00, 0x00,
    )
    private val START_SENSOR_STREAM = byteArrayOf(
        0x04, 0x00, 0x04, 0x00,
        0x17, 0x00, 0x00, 0x00, 0x10, 0x00, 0x10, 0x00,
        0x08, 0xE3.toByte(), 0x46, 0x42, 0x0B, 0x08, 0x13, 0x10,
        0x02, 0x1A, 0x05, 0x01, 0x40, 0x42, 0x0F, 0x00,
    )
    private val STOP_SENSOR_STREAM = byteArrayOf(
        0x04, 0x00, 0x04, 0x00,
        0x17, 0x00, 0x00, 0x00, 0x10, 0x00, 0x10, 0x00,
        0x08, 0xED.toByte(), 0x46, 0x42, 0x0B, 0x08, 0x13, 0x10,
        0x02, 0x1A, 0x05, 0x01, 0x00, 0x00, 0x00, 0x00,
    )

    fun startSequence(): List<HeartRateAacpStep> = listOf(
        HeartRateAacpStep(
            HeartRateAacpStage.CONNECT_SERVICE_0,
            CONNECT_SERVICE_0.copyOf(),
            180L,
        ),
        HeartRateAacpStep(
            HeartRateAacpStage.CAPABILITIES_SERVICE_0,
            CAPABILITIES_SERVICE_0.copyOf(),
            220L,
        ),
        HeartRateAacpStep(
            HeartRateAacpStage.CONNECT_SERVICE_4,
            CONNECT_SERVICE_4.copyOf(),
            180L,
        ),
        HeartRateAacpStep(
            HeartRateAacpStage.CAPABILITIES_SERVICE_4,
            CAPABILITIES_SERVICE_4.copyOf(),
            220L,
        ),
        HeartRateAacpStep(
            HeartRateAacpStage.ENABLE_HRM_STATE,
            ENABLE_HRM_STATE.copyOf(),
            120L,
        ),
        HeartRateAacpStep(
            HeartRateAacpStage.START_SENSOR_STREAM,
            START_SENSOR_STREAM.copyOf(),
            0L,
        ),
    )

    fun stopPacket(): ByteArray = STOP_SENSOR_STREAM.copyOf()
}

/**
 * Serializes the final current-acquisition check with one synchronous socket write.
 *
 * START and STOP share this gate. If START enters first, STOP is guaranteed to follow it. If STOP
 * enters first, a queued stale START observes its invalidated acquisition before it can write.
 */
internal class HeartRateAacpWriteGate {
    private val monitor = Any()

    fun writeIfCurrent(
        isCurrent: () -> Boolean,
        write: () -> Boolean,
    ): Boolean = synchronized(monitor) {
        if (!isCurrent()) return@synchronized false
        write()
    }
}

/**
 * Writes only heart-rate frames and never logs packet bytes. Every write is tied to the exact
 * socket lease and repeats the current-lease check immediately before the manager's atomic write.
 */
internal class HeartRateAacpTransport(
    private val writeGate: HeartRateAacpWriteGate = HeartRateAacpWriteGate(),
) {
    suspend fun start(
        lease: AacpSocketLease,
        acquisitionIsCurrent: () -> Boolean,
    ): Boolean {
        for (step in HeartRateAacpFrames.startSequence()) {
            if (!write(step.stage, step.packet, lease, acquisitionIsCurrent)) return false
            if (step.delayAfterMillis > 0L) delay(step.delayAfterMillis)
        }
        return acquisitionIsCurrent() && BluetoothConnectionManager.isCurrent(lease)
    }

    fun stop(
        lease: AacpSocketLease,
        acquisitionIsCurrent: () -> Boolean,
    ): Boolean = write(
        stage = null,
        packet = HeartRateAacpFrames.stopPacket(),
        lease = lease,
        acquisitionIsCurrent = acquisitionIsCurrent,
    )

    private fun write(
        stage: HeartRateAacpStage?,
        packet: ByteArray,
        lease: AacpSocketLease,
        acquisitionIsCurrent: () -> Boolean,
    ): Boolean = writeGate.writeIfCurrent(
        isCurrent = {
            acquisitionIsCurrent() && BluetoothConnectionManager.isCurrent(lease)
        },
        write = {
            try {
                BluetoothConnectionManager.writeAacpPacket(packet, lease).also { sent ->
                    if (!sent) {
                        Log.w(TAG, "Heart-rate AACP write was rejected at ${stage ?: "stop"}")
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Heart-rate AACP write failed at ${stage ?: "stop"}", error)
                false
            }
        },
    )

    private companion object {
        const val TAG = "HeartRateTransport"
    }
}
