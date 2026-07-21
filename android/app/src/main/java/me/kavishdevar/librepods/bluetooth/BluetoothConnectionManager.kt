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

package me.kavishdevar.librepods.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import android.util.Log

class AacpSocketLease internal constructor(
    val socket: BluetoothSocket,
    val generation: Long,
)

internal object AacpSocketLeaseGate {
    fun isCurrent(
        currentSocket: Any?,
        currentGeneration: Long,
        leaseSocket: Any,
        leaseGeneration: Long,
    ): Boolean = currentSocket === leaseSocket && currentGeneration == leaseGeneration
}

object BluetoothConnectionManager {
    @Volatile
    var aacpSocket: BluetoothSocket? = null
        private set
    @Volatile
    var attSocket: BluetoothSocket? = null
    @Volatile
    private var aacpSocketGeneration: Long = 0L

    fun installAacpSocket(socket: BluetoothSocket): Long {
        synchronized(this) {
            val previousSocket = aacpSocket
            val nextGeneration = aacpSocketGeneration + 1L
            aacpSocket = socket
            aacpSocketGeneration = nextGeneration
            if (previousSocket != null && previousSocket !== socket) {
                runCatching { previousSocket.close() }
            }
            return nextGeneration
        }
    }

    @Synchronized
    fun currentAacpSocketGeneration(): Long = aacpSocketGeneration

    @Synchronized
    fun currentAacpSocketLease(): AacpSocketLease? = aacpSocket?.let {
        AacpSocketLease(socket = it, generation = aacpSocketGeneration)
    }

    @Synchronized
    fun isCurrent(lease: AacpSocketLease): Boolean = AacpSocketLeaseGate.isCurrent(
        currentSocket = aacpSocket,
        currentGeneration = aacpSocketGeneration,
        leaseSocket = lease.socket,
        leaseGeneration = lease.generation,
    )

    /**
     * Checks a lease and writes while holding the same lock used for socket replacement. This
     * prevents a new generation from being installed between the identity check and the write.
     */
    fun writeAacpPacket(packet: ByteArray, expectedLease: AacpSocketLease?): Boolean =
        synchronized(this) {
            val socket = expectedLease?.socket ?: aacpSocket ?: return@synchronized false
            if (expectedLease != null && !isCurrent(expectedLease)) return@synchronized false
            if (!socket.isConnected) return@synchronized false
            val output = socket.outputStream ?: return@synchronized false
            output.write(packet)
            output.flush()
            true
        }

    fun invalidateAacpSocket(expectedSocket: BluetoothSocket? = aacpSocket) {
        if (expectedSocket == null) return
        synchronized(this) {
            if (aacpSocket === expectedSocket) {
                aacpSocket = null
                aacpSocketGeneration++
                runCatching { expectedSocket.close() }
            }
        }
    }

    fun invalidateAttSocket(expectedSocket: BluetoothSocket? = attSocket) {
        if (expectedSocket == null) return
        synchronized(this) {
            if (attSocket === expectedSocket) {
                attSocket = null
            }
        }
        runCatching { expectedSocket.close() }
    }

    fun closeAacpGeneration(
        expectedSocket: BluetoothSocket,
        expectedGeneration: Long,
    ): Boolean {
        return synchronized(this) {
            if (aacpSocketGeneration != expectedGeneration || aacpSocket !== expectedSocket) {
                return@synchronized false
            } else {
                aacpSocket = null
                aacpSocketGeneration++
                runCatching { expectedSocket.close() }
                true
            }
        }
    }
}

fun createBluetoothSocket(
    adapter: BluetoothAdapter, device: BluetoothDevice, uuid: ParcelUuid, psm: Int
): BluetoothSocket {
    val type = 3 // L2CAP
    val constructorSpecs = listOf(
        arrayOf(adapter, device, type, true, true, psm, uuid), // A16QPR3
        arrayOf(device, type, true, true, psm, uuid),
        arrayOf(device, type, 1, true, true, psm, uuid),
        arrayOf(type, 1, true, true, device, psm, uuid),
        arrayOf(type, true, true, device, psm, uuid)
    )

    val constructors = BluetoothSocket::class.java.declaredConstructors
    Log.d("createSocket<psm>", "BluetoothSocket has ${constructors.size} constructors:")

    constructors.forEachIndexed { index, constructor ->
        val params = constructor.parameterTypes.joinToString(", ") { it.simpleName }
        Log.d("createSocket<psm>", "Constructor $index: ($params)")
    }

    var lastException: Exception? = null
    var attemptedConstructors = 0

    for ((index, params) in constructorSpecs.withIndex()) {
        try {
            Log.d("createSocket<psm>", "Trying constructor signature #${index + 1}")
            attemptedConstructors++

            val paramTypes =
                params.map { it::class.javaPrimitiveType ?: it::class.java }.toTypedArray()
            val constructor = BluetoothSocket::class.java.getDeclaredConstructor(*paramTypes)
            constructor.isAccessible = true
            return constructor.newInstance(*params) as BluetoothSocket

        } catch (e: Exception) {
            Log.e("createSocket<psm>", "Constructor signature #${index + 1} failed: ${e.message}")
            lastException = e
        }
    }

    val errorMessage =
        "Failed to create BluetoothSocket after trying $attemptedConstructors constructor signatures"
    Log.e("createSocket<psm>", errorMessage)
    throw lastException ?: IllegalStateException(errorMessage)
}
