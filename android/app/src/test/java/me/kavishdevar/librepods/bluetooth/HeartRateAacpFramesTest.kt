/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.bluetooth

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HeartRateAacpFramesTest {
    @Test
    fun `startup sequence matches the adopted upstream frames and delays`() {
        val steps = HeartRateAacpFrames.startSequence()

        assertEquals(
            listOf(
                HeartRateAacpStage.CONNECT_SERVICE_0,
                HeartRateAacpStage.CAPABILITIES_SERVICE_0,
                HeartRateAacpStage.CONNECT_SERVICE_4,
                HeartRateAacpStage.CAPABILITIES_SERVICE_4,
                HeartRateAacpStage.ENABLE_HRM_STATE,
                HeartRateAacpStage.START_SENSOR_STREAM,
            ),
            steps.map(HeartRateAacpStep::stage),
        )
        assertEquals(listOf(180L, 220L, 180L, 220L, 120L, 0L), steps.map { it.delayAfterMillis })

        assertContentEquals(
            bytes("00 00 00 00 01 00 03 00 00 00 00 00 00 00 00 00"),
            steps[0].packet,
        )
        assertContentEquals(bytes("04 00 00 00 01 00 00"), steps[1].packet)
        assertContentEquals(
            bytes("00 00 04 00 01 00 03 00 00 00 00 00 00 00 00 00"),
            steps[2].packet,
        )
        assertContentEquals(bytes("04 00 04 00 01 00 00"), steps[3].packet)
        assertContentEquals(bytes("04 00 04 00 09 00 2D 01 00 00 00"), steps[4].packet)
        assertContentEquals(
            bytes(
                "04 00 04 00 17 00 00 00 10 00 10 00 " +
                    "08 E3 46 42 0B 08 13 10 02 1A 05 01 40 42 0F 00"
            ),
            steps[5].packet,
        )
    }

    @Test
    fun `stop frame matches SensorDataWX stop and never disables HRM state`() {
        val stop = HeartRateAacpFrames.stopPacket()

        assertContentEquals(
            bytes(
                "04 00 04 00 17 00 00 00 10 00 10 00 " +
                    "08 ED 46 42 0B 08 13 10 02 1A 05 01 00 00 00 00"
            ),
            stop,
        )
        assertFalse(stop.contentEquals(bytes("04 00 04 00 09 00 2D 00 00 00 00")))
    }

    @Test
    fun `callers cannot mutate stored frames`() {
        val firstSequence = HeartRateAacpFrames.startSequence()
        val firstStop = HeartRateAacpFrames.stopPacket()
        firstSequence.forEach { step -> step.packet.fill(0x7F) }
        firstStop.fill(0x7F)

        val secondSequence = HeartRateAacpFrames.startSequence()
        val secondStop = HeartRateAacpFrames.stopPacket()

        assertContentEquals(
            bytes("00 00 00 00 01 00 03 00 00 00 00 00 00 00 00 00"),
            secondSequence.first().packet,
        )
        assertContentEquals(
            bytes(
                "04 00 04 00 17 00 00 00 10 00 10 00 " +
                    "08 ED 46 42 0B 08 13 10 02 1A 05 01 00 00 00 00"
            ),
            secondStop,
        )
    }

    private fun bytes(hex: String): ByteArray = hex.split(' ')
        .filter(String::isNotBlank)
        .map { it.toInt(16).toByte() }
        .toByteArray()
}
