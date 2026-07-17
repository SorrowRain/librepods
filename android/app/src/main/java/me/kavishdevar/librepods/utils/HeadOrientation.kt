/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

package me.kavishdevar.librepods.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

data class Orientation(val pitch: Float = 0f, val yaw: Float = 0f)
data class Acceleration(val vertical: Float = 0f, val horizontal: Float = 0f)
data class HeadPose(
    val rx: Float,
    val ry: Float,
    val rz: Float,
    val vx: Float = 0f,
    val vy: Float = 0f,
    val vz: Float = 0f,
    val discontinuityCounter: Int = 0
)

object HeadTracking {
    private val _orientation = MutableStateFlow(Orientation())
    val orientation = _orientation.asStateFlow()

    private val _acceleration = MutableStateFlow(Acceleration())
    val acceleration = _acceleration.asStateFlow()

    private data class Quaternion(
        val w: Double,
        val x: Double,
        val y: Double,
        val z: Double
    ) {
        fun normalized(): Quaternion {
            val magnitude = sqrt(w * w + x * x + y * y + z * z)
            if (magnitude < 1e-9) return Quaternion(1.0, 0.0, 0.0, 0.0)
            return Quaternion(w / magnitude, x / magnitude, y / magnitude, z / magnitude)
        }

        fun conjugate() = Quaternion(w, -x, -y, -z)

        operator fun times(other: Quaternion) = Quaternion(
            w = w * other.w - x * other.x - y * other.y - z * other.z,
            x = w * other.x + x * other.w + y * other.z - z * other.y,
            y = w * other.y - x * other.z + y * other.w + z * other.x,
            z = w * other.z + x * other.y - y * other.x + z * other.w
        )

        fun canonicalized() = if (w < 0.0) {
            Quaternion(-w, -x, -y, -z)
        } else {
            this
        }

        fun toRotationVector(): Triple<Float, Float, Float> {
            val q = normalized().canonicalized()
            val vectorMagnitude = sqrt(q.x * q.x + q.y * q.y + q.z * q.z)
            if (vectorMagnitude < 1e-9) return Triple(0f, 0f, 0f)
            val angle = 2.0 * atan2(vectorMagnitude, q.w)
            val scale = angle / vectorMagnitude
            return Triple(
                (q.x * scale).toFloat(),
                (q.y * scale).toFloat(),
                (q.z * scale).toFloat()
            )
        }
    }

    private val calibrationSamples = mutableListOf<Quaternion>()
    private var referenceOrientation: Quaternion? = null
    private var discontinuityCounter = 0

    private const val CALIBRATION_SAMPLE_COUNT = 10
    private const val QUATERNION_SCALE = 32767.0

    fun processPacket(packet: ByteArray): HeadPose? {
        if (packet.size < 55) return null

        val quaternion = decodeQuaternion(
            bytesToInt(packet[43], packet[44]),
            bytesToInt(packet[45], packet[46]),
            bytesToInt(packet[47], packet[48])
        )

        val horizontalAccel = bytesToInt(packet[51], packet[52]).toFloat()
        val verticalAccel = bytesToInt(packet[53], packet[54]).toFloat()
        _acceleration.value = Acceleration(verticalAccel, horizontalAccel)

        if (referenceOrientation == null) {
            calibrationSamples.add(quaternion)
            if (calibrationSamples.size >= CALIBRATION_SAMPLE_COUNT) {
                calibrate()
            }
            return null
        }

        // AirPods sends a reference-to-head quaternion with the positive W
        // component omitted. Android's head tracker expects the inverse
        // (head-to-reference) transform, rebased to the stream-start pose.
        // Without this inversion, a head turn makes the rendered sound field
        // move in the opposite direction.
        val relative = (quaternion * referenceOrientation!!.conjugate())
            .conjugate()
            .normalized()
            .canonicalized()
        val (rx, ry, rz) = relative.toRotationVector()

        _orientation.value = Orientation(
            pitch = Math.toDegrees(rx.toDouble()).toFloat(),
            yaw = Math.toDegrees(rz.toDouble()).toFloat()
        )

        // Pose is sufficient for Android's tracker. Leave angular velocity at
        // zero until the remaining AACP motion fields are fully identified.
        return HeadPose(rx, ry, rz, discontinuityCounter = discontinuityCounter)
    }

    private fun calibrate() {
        if (calibrationSamples.size < CALIBRATION_SAMPLE_COUNT) return
        referenceOrientation = Quaternion(
            w = calibrationSamples.sumOf { it.w },
            x = calibrationSamples.sumOf { it.x },
            y = calibrationSamples.sumOf { it.y },
            z = calibrationSamples.sumOf { it.z }
        ).normalized()
    }

    private fun decodeQuaternion(xRaw: Int, yRaw: Int, zRaw: Int): Quaternion {
        val x = xRaw / QUATERNION_SCALE
        val y = yRaw / QUATERNION_SCALE
        val z = zRaw / QUATERNION_SCALE
        val w = sqrt(max(0.0, 1.0 - x * x - y * y - z * z))
        return Quaternion(w, x, y, z).normalized()
    }

    private fun bytesToInt(b1: Byte, b2: Byte): Int {
        return (b2.toInt() shl 8) or (b1.toInt() and 0xFF)
    }

    fun reset() {
        calibrationSamples.clear()
        referenceOrientation = null
        discontinuityCounter = (discontinuityCounter + 1) and 0xFF
        _orientation.value = Orientation()
        _acceleration.value = Acceleration()
    }
}
