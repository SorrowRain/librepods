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

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.bluetooth

import android.util.Log
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.CustomEq
import me.kavishdevar.librepods.data.LocalHostIdentity
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.io.encoding.ExperimentalEncodingApi

internal object AacpSessionCleanupGate {
    fun canClear(
        boundSocket: Any?,
        boundGeneration: Long?,
        expectedSocket: Any?,
        expectedGeneration: Long?,
    ): Boolean {
        if (expectedSocket == null || expectedGeneration == null) {
            return boundSocket == null && boundGeneration == null
        }
        return boundGeneration != null && AacpSocketLeaseGate.isCurrent(
            currentSocket = boundSocket,
            currentGeneration = boundGeneration,
            leaseSocket = expectedSocket,
            leaseGeneration = expectedGeneration,
        )
    }

    fun canClear(
        boundLease: AacpSocketLease?,
        expectedLease: AacpSocketLease?,
    ): Boolean = canClear(
        boundSocket = boundLease?.socket,
        boundGeneration = boundLease?.generation,
        expectedSocket = expectedLease?.socket,
        expectedGeneration = expectedLease?.generation,
    )
}

/**
 * Manager class for Apple Accessory Communication Protocol (AACP)
 * This class is responsible for handling the L2CAP socket management,
 * constructing and parsing packets for communication with AirPods.
 */
class AACPManager {
    private val TAG = "AACPManager[${System.identityHashCode(this)}]"
    companion object {
        @Suppress("unused")
        object Opcodes {
            const val SET_FEATURE_FLAGS: Byte = 0x4D
            const val REQUEST_NOTIFICATIONS: Byte = 0x0F
            const val BATTERY_INFO: Byte = 0x04
            const val CONTROL_COMMAND: Byte = 0x09
            const val EAR_DETECTION: Byte = 0x06
            const val CONVERSATION_AWARENESS: Byte = 0x4B
            const val INFORMATION: Byte = 0x1D
            const val RENAME: Byte = 0x1A
            const val HEADTRACKING: Byte = 0x17
            const val PROXIMITY_KEYS_REQ: Byte = 0x30
            const val PROXIMITY_KEYS_RSP: Byte = 0x31
            const val STEM_PRESS: Byte = 0x19
            const val HEADPHONE_ACCOMMODATION: Byte = 0x53
            const val CONNECTED_DEVICES: Byte = 0x2E // TiPi 1
            const val AUDIO_SOURCE_REQUEST: Byte = 0x0D
            const val AUDIO_SOURCE: Byte = 0x0E // TiPi 2
            const val SMART_ROUTING: Byte = 0x10
            const val TIPI_3: Byte = 0x0C // Don't know this one
            const val SMART_ROUTING_RESP: Byte = 0x11
            const val SEND_CONNECTED_MAC: Byte = 0x14
            const val AUDIO_SOURCE_2: Byte = 0x0C // seems redundant?
            const val CUSTOM_EQ: Byte = 0x63
        }

        private val HEADER_BYTES = byteArrayOf(0x04, 0x00, 0x04, 0x00)

        data class ControlCommandStatus(
            val identifier: ControlCommandIdentifiers, val value: ByteArray
        ) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false

                other as ControlCommandStatus

                if (identifier != other.identifier) return false
                if (!value.contentEquals(other.value)) return false

                return true
            }

            override fun hashCode(): Int {
                var result: Int = identifier.hashCode()
                result = 31 * result + value.contentHashCode()
                return result
            }
        }

        //        @Suppress("unused")
        enum class ControlCommandIdentifiers(val value: Byte) {
            MIC_MODE(0x01), BUTTON_SEND_MODE(0x05), VOICE_TRIGGER(0x12), SINGLE_CLICK_MODE(0x14), DOUBLE_CLICK_MODE(
                0x15
            ),
            CLICK_HOLD_MODE(0x16), DOUBLE_CLICK_INTERVAL(0x17), CLICK_HOLD_INTERVAL(0x18), LISTENING_MODE_CONFIGS(
                0x1A
            ),
            ONE_BUD_ANC_MODE(0x1B), CROWN_ROTATION_DIRECTION(0x1C), LISTENING_MODE(0x0D), AUTO_ANSWER_MODE(
                0x1E
            ),
            CHIME_VOLUME(0x1F), VOLUME_SWIPE_INTERVAL(0x23), CALL_MANAGEMENT_CONFIG(0x24), VOLUME_SWIPE_MODE(
                0x25
            ),
            ADAPTIVE_VOLUME_CONFIG(0x26), SOFTWARE_MUTE_CONFIG(0x27), CONVERSATION_DETECT_CONFIG(
                0x28
            ),
            SSL(0x29), HEARING_AID(0x2C), AUTO_ANC_STRENGTH(0x2E), HPS_GAIN_SWIPE(0x2F), HRM_STATE(
                0x30
            ),
            IN_CASE_TONE_CONFIG(0x31), SIRI_MULTITONE_CONFIG(0x32), HEARING_ASSIST_CONFIG(0x33), ALLOW_OFF_OPTION(
                0x34
            ),
            STEM_CONFIG(0x39), SLEEP_DETECTION_CONFIG(0x35), ALLOW_AUTO_CONNECT(0x36), // not sure what this does, AUTOMATIC_CONNECTION is the only one used, but this is newer... so ¯\_(ツ)_/¯
            EAR_DETECTION_CONFIG(0x0A), AUTOMATIC_CONNECTION_CONFIG(0x20), OWNS_CONNECTION(0x06), PPE_TOGGLE_CONFIG(
                0x37
            ),
            PPE_CAP_LEVEL_CONFIG(0x38),
            DYNAMIC_END_OF_CHARGE(0x3B);

            companion object {
                fun fromByte(byte: Byte): ControlCommandIdentifiers? =
                    entries.find { it.value == byte }
            }
        }

        enum class ProximityKeyType(val value: Byte) {
            IRK(0x01), ENC_KEY(0x04);

            companion object {
                fun fromByte(byte: Byte): ProximityKeyType = entries.find { it.value == byte }
                    ?: throw IllegalArgumentException("Unknown ProximityKeyType: $byte")
            }
        }

        enum class StemPressType(val value: Byte) {
            SINGLE_PRESS(0x05), DOUBLE_PRESS(0x06), TRIPLE_PRESS(0x07), LONG_PRESS(0x08);

            companion object {
                fun fromByte(byte: Byte): StemPressType? = entries.find { it.value == byte }
            }
        }

        enum class StemPressBudType(val value: Byte) {
            LEFT(0x01), RIGHT(0x02);

            companion object {
                fun fromByte(byte: Byte): StemPressBudType? = entries.find { it.value == byte }
            }
        }

        enum class AudioSourceType(val value: Byte) {
            NONE(0x00), CALL(0x01), MEDIA(0x02), UNKNOWN(0xFF.toByte());

            companion object {
                fun fromByte(byte: Byte): AudioSourceType =
                    entries.find { it != UNKNOWN && it.value == byte } ?: UNKNOWN
            }
        }

        data class AudioSource(
            val mac: String, val type: AudioSourceType
        )

        data class ConnectedDevice(
            val mac: String, val info1: Byte, val info2: Byte, var type: String?
        )

        data class AirPodsInformation(
            val name: String,
            val modelNumber: String,
            val manufacturer: String,
            val serialNumber: String,
            val version1: String,
            val version2: String,
            val hardwareRevision: String,
            val updaterIdentifier: String,
            val leftSerialNumber: String,
            val rightSerialNumber: String,
            val version3: String
        )
    }

    var controlCommandStatusList: MutableList<ControlCommandStatus> =
        mutableListOf<ControlCommandStatus>()
    var controlCommandListeners: MutableMap<ControlCommandIdentifiers, MutableList<ControlCommandListener>> =
        mutableMapOf()

    var owns: Boolean = false
        private set

    var oldConnectedDevices: List<ConnectedDevice> = listOf()
        private set

    @Volatile
    var connectedDevices: List<ConnectedDevice> = listOf()
        private set

    @Volatile
    var audioSource: AudioSource? = null
        private set

    var eqData = FloatArray(8)
        private set

    var eqOnPhone: Boolean = false
        private set

    var eqOnMedia: Boolean = false
        private set

    var customEq: CustomEq = CustomEq(state = 1, low = 50, mid = 50, high = 50)
        private set

    var customEqCallback: ((CustomEq) -> Unit)? = null

    @Volatile
    private var socketLease: AacpSocketLease? = null

    @Synchronized
    fun bindSocketLease(lease: AacpSocketLease?) {
        socketLease = lease?.takeIf(BluetoothConnectionManager::isCurrent)
    }

    @Synchronized
    fun getControlCommandStatus(identifier: ControlCommandIdentifiers): ControlCommandStatus? {
        return controlCommandStatusList.find { it.identifier == identifier }
    }

    @Synchronized
    private fun setControlCommandStatusValue(
        identifier: ControlCommandIdentifiers, value: ByteArray
    ) {
        val storedValue = value.copyOf()
        controlCommandStatusList.removeAll { it.identifier == identifier }
        controlCommandStatusList.add(ControlCommandStatus(identifier, storedValue))
        controlCommandListeners[identifier]?.forEach { listener ->
            listener.onControlCommandReceived(ControlCommand(identifier.value, storedValue))
        }

        if (identifier == ControlCommandIdentifiers.OWNS_CONNECTION) {
            owns = storedValue.isNotEmpty() && storedValue[0] == 0x01.toByte()
        }
    }

    interface PacketCallback {
        fun onBatteryInfoReceived(batteryInfo: ByteArray)
        fun onEarDetectionReceived(
            earDetection: ByteArray,
            expectedLease: AacpSocketLease,
        )
        fun onConversationAwarenessReceived(conversationAwareness: ByteArray)
        fun onControlCommandReceived(controlCommand: ByteArray)
        fun onDeviceInformationReceived(
            deviceInformation: AirPodsInformation,
            expectedLease: AacpSocketLease,
        )
        fun onHeadTrackingReceived(
            headTracking: ByteArray,
            expectedLease: AacpSocketLease,
        )
        fun onUnknownPacketReceived(packet: ByteArray)
        fun onProximityKeysReceived(proximityKeys: ByteArray)
        fun onStemPressReceived(stemPress: ByteArray, expectedLease: AacpSocketLease)
        fun onAudioSourceReceived(
            audioSource: AudioSource?,
            expectedLease: AacpSocketLease,
        )
        fun onOwnershipChangeReceived(owns: Boolean, expectedLease: AacpSocketLease)
        fun onConnectedDevicesReceived(
            connectedDevices: List<ConnectedDevice>,
            expectedLease: AacpSocketLease,
        )
        fun onOwnershipToFalseRequest(
            sender: String,
            reasonReverseTapped: Boolean,
            expectedLease: AacpSocketLease,
        )
        fun onRemoteStreamingStateChanged(
            sender: String,
            isStreaming: Boolean,
            expectedLease: AacpSocketLease,
        )
        fun onShowNearbyUI(sender: String)
        fun onHeadphoneAccommodationReceived(eqData: FloatArray)
        fun onCustomEqReceived(customEq: CustomEq)
        fun onCapabilitiesReceived(capabilities: List<Capability>)
    }

    fun parseStemPressResponse(data: ByteArray): Pair<StemPressType, StemPressBudType> {
        Log.d(TAG, "Parsing Stem Press Response: ${data.joinToString(" ") { "%02X".format(it) }}")
        if (data.size != 8) {
            throw IllegalArgumentException("Data array too short to parse Stem Press Response")
        }
        if (data[4] != Opcodes.STEM_PRESS) {
            throw IllegalArgumentException("Data array does not start with STEM_PRESS opcode")
        }
        val type = StemPressType.fromByte(data[6])
            ?: throw IllegalArgumentException("Unknown Stem Press Type: ${data[5]}")
        val bud = StemPressBudType.fromByte(data[7])
            ?: throw IllegalArgumentException("Unknown Stem Press Bud Type: ${data[6]}")
        return Pair(type, bud)
    }

    interface ControlCommandListener {
        fun onControlCommandReceived(controlCommand: ControlCommand)
    }

    fun registerControlCommandListener(
        identifier: ControlCommandIdentifiers, callback: ControlCommandListener
    ) {
        controlCommandListeners.getOrPut(identifier) { mutableListOf() }.add(callback)
    }

    fun unregisterControlCommandListener(
        identifier: ControlCommandIdentifiers, callback: ControlCommandListener
    ) {
        controlCommandListeners[identifier]?.remove(callback)
    }

    private var callback: PacketCallback? = null

    fun setPacketCallback(callback: PacketCallback) {
        this.callback = callback
    }

    fun createDataPacket(data: ByteArray): ByteArray {
        return HEADER_BYTES + data
    }

    fun createControlCommandPacket(identifier: Byte, data: ByteArray): ByteArray {
        val opcode = byteArrayOf(Opcodes.CONTROL_COMMAND, 0x00)
        val payload = ByteArray(7)

        System.arraycopy(opcode, 0, payload, 0, 2)
        payload[2] = identifier

        val dataLength = minOf(data.size, 4)
        System.arraycopy(data, 0, payload, 3, dataLength)

        return payload
    }

    fun sendDataPacket(
        data: ByteArray,
        expectedLease: AacpSocketLease? = socketLease,
    ): Boolean {
        if (data.isEmpty()) return false
        return sendPacket(createDataPacket(data), expectedLease)
    }

    fun requestAudioSource(expectedLease: AacpSocketLease? = socketLease): Boolean {
        return sendDataPacket(
            byteArrayOf(Opcodes.AUDIO_SOURCE_REQUEST, 0x00),
            expectedLease,
        )
    }

    fun sendControlCommand(
        identifier: Byte,
        value: ByteArray,
        expectedLease: AacpSocketLease? = socketLease,
    ): Boolean {
        val controlPacket = createControlCommandPacket(identifier, value)
        if (ControlCommandIdentifiers.fromByte(identifier) == null) return false
        return sendDataPacket(controlPacket, expectedLease)
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun sendControlCommand(
        identifier: Byte,
        value: Byte,
        expectedLease: AacpSocketLease? = socketLease,
    ): Boolean {
        val controlPacket = createControlCommandPacket(identifier, byteArrayOf(value))
        if (ControlCommandIdentifiers.fromByte(identifier) == null) return false
        return sendDataPacket(controlPacket, expectedLease)
    }

    fun sendControlCommand(
        identifier: Byte,
        value: Boolean,
        expectedLease: AacpSocketLease? = socketLease,
    ): Boolean {
        val controlPacket = createControlCommandPacket(
            identifier, if (value) byteArrayOf(0x01) else byteArrayOf(0x02)
        )
        if (ControlCommandIdentifiers.fromByte(identifier) == null) return false
        return sendDataPacket(controlPacket, expectedLease)
    }

    fun sendControlCommand(
        identifier: Byte,
        value: Int,
        expectedLease: AacpSocketLease? = socketLease,
    ): Boolean {
        val controlPacket = createControlCommandPacket(identifier, byteArrayOf(value.toByte()))
        if (ControlCommandIdentifiers.fromByte(identifier) == null) return false
        return sendDataPacket(controlPacket, expectedLease)
    }

    fun parseProximityKeysResponse(data: ByteArray): Map<ProximityKeyType, ByteArray> {
        Log.d(
            TAG, "Parsing Proximity Keys Response: ${data.joinToString(" ") { "%02X".format(it) }}"
        )
        if (data.size < 7) {
            throw IllegalArgumentException("Data array too short to parse Proximity Keys Response")
        }
        if (data[4] != Opcodes.PROXIMITY_KEYS_RSP) {
            throw IllegalArgumentException("Data array does not start with PROXIMITY_KEYS_RSP opcode")
        }
        val keyCount = data[6].toInt() and 0xFF
        val keys = mutableMapOf<ProximityKeyType, ByteArray>()
        var offset = 7
        for (i in 0 until keyCount) {
            Log.d(TAG, "Parsing Proximity Key $i")
            if (offset + 3 >= data.size) {
                throw IllegalArgumentException("Data array too short to parse Proximity Keys Response")
            }
            val keyType = data[offset]
            val keyLength = data[offset + 2].toInt() and 0xFF
            Log.d(TAG, "Key Type: ${keyType.toString(16)}, Key Length: $keyLength")
            offset += 4
            if (offset + keyLength > data.size) {
                throw IllegalArgumentException("Data array too short to parse Proximity Keys Response")
            }
            val key = ByteArray(keyLength)
            System.arraycopy(data, offset, key, 0, keyLength)
            try {
                keys[ProximityKeyType.fromByte(keyType)] = key
            } catch (e: Exception) {
                Log.e(
                    TAG, "incorrect key type received: $keyType, ${key.toHexString()}"
                )
            }
            offset += keyLength
            Log.d(
                TAG, "Parsed Proximity Key: Type: ${keyType}, Length: $keyLength, Key: ${
                key.joinToString(" ") { "%02X".format(it) }
            }")
        }
        return keys
    }

    fun sendRequestProximityKeys(
        type: Byte,
        expectedLease: AacpSocketLease? = socketLease,
    ): Boolean {
        Log.d(TAG, "Requesting proximity keys of type: ${type.toString(16)}")
        return sendDataPacket(createRequestProximityKeysPacket(type), expectedLease)
    }

    fun createRequestProximityKeysPacket(type: Byte): ByteArray {
        val opcode = byteArrayOf(Opcodes.PROXIMITY_KEYS_REQ, 0x00)
        val data = byteArrayOf(type, 0x00)
        return opcode + data
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun receivePacket(
        packet: ByteArray,
        expectedLease: AacpSocketLease,
        allowRawLogging: Boolean = true,
    ) {
        if (!BluetoothConnectionManager.isCurrent(expectedLease)) {
            Log.d(TAG, "Ignoring packet from a stale AACP socket")
            return
        }
        if (packet.size < 4 || !packet.copyOfRange(0, 4).contentEquals(HEADER_BYTES)) {
            if (allowRawLogging) {
                Log.w(
                    TAG, "Received packet does not start with expected header: ${
                    packet.joinToString(" ") {
                        "%02X".format(it)
                    }
                }")
            } else {
                Log.w(TAG, "Received redacted packet without the expected AACP header")
            }
            return
        }
        if (packet.size < 6) {
            if (allowRawLogging) {
                Log.w(
                    TAG,
                    "Received packet too short: ${packet.joinToString(" ") { "%02X".format(it) }}",
                )
            } else {
                Log.w(TAG, "Received redacted packet that was too short")
            }
            return
        }

        when (val opcode = packet[4]) {
            Opcodes.BATTERY_INFO -> {
                callback?.onBatteryInfoReceived(packet)
            }

            Opcodes.CONTROL_COMMAND -> {
                val controlCommand = try {
                    ControlCommand.fromByteArray(packet)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse control command: ${e.message}")
                    callback?.onUnknownPacketReceived(packet)
                    return
                }
                val controlCommandIdentifier =
                    ControlCommandIdentifiers.fromByte(controlCommand.identifier)
                if (controlCommandIdentifier == null) {
                    Log.w(
                        TAG,
                        "Unknown control command identifier: ${controlCommand.identifier.toHexString()}"
                    )
                    callback?.onUnknownPacketReceived(packet)
                    return
                }
                setControlCommandStatusValue(controlCommandIdentifier, controlCommand.value)
                Log.d(
                    TAG,
                    "Control command received: ${controlCommand.identifier.toHexString()} - ${
                        controlCommand.value.joinToString(" ") { "%02X".format(it) }
                    }")

                val controlCommandListText = try {
                    controlCommandStatusList.joinToString(", ") { it ->
                        "${it.identifier.name} (${it.identifier.value.toHexString()}) - ${
                            it.value.joinToString(
                                " "
                            ) { "%02X".format(it) }
                        }"
                    }
                } catch (e: Exception) {
                    e.message
                }

                Log.d(
                    TAG, "Control command list is now: $controlCommandListText"
                )

                if (controlCommandIdentifier == ControlCommandIdentifiers.OWNS_CONNECTION) {
                    callback?.onOwnershipChangeReceived(owns, expectedLease)
                }

                callback?.onControlCommandReceived(packet)
            }

            Opcodes.EAR_DETECTION -> {
                callback?.onEarDetectionReceived(packet, expectedLease)
            }

            Opcodes.CONVERSATION_AWARENESS -> {
                callback?.onConversationAwarenessReceived(packet)
            }

            Opcodes.HEADTRACKING -> {
                if (packet.size < 70) {
                    if (allowRawLogging) {
                        Log.w(
                            TAG, "Received HEADTRACKING packet too short: ${
                            packet.joinToString(" ") {
                                "%02X".format(it)
                            }
                        }")
                    } else {
                        Log.w(TAG, "Received redacted HEADTRACKING packet that was too short")
                    }
                    return
                }
                callback?.onHeadTrackingReceived(packet, expectedLease)
            }

            Opcodes.PROXIMITY_KEYS_RSP -> {
                callback?.onProximityKeysReceived(packet)
            }

            Opcodes.STEM_PRESS -> {
                callback?.onStemPressReceived(packet, expectedLease)
            }

            Opcodes.AUDIO_SOURCE -> {
                try {
                    val (mac, type) = parseAudioSourceResponse(packet)
                    audioSource = AudioSource(mac, type)
                } catch (e: Exception) {
                    audioSource = null
                    Log.e(TAG, "Error parsing audio source response: ${e.message}")
                }
                callback?.onAudioSourceReceived(audioSource, expectedLease)
            }

            Opcodes.CONNECTED_DEVICES -> {
                val parsedDevices = try {
                    parseConnectedDevicesResponse(packet)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parsing connected devices response: ${e.message}")
                    return
                }
                oldConnectedDevices = connectedDevices
                connectedDevices = parsedDevices
                callback?.onConnectedDevicesReceived(connectedDevices, expectedLease)
            }

            Opcodes.SMART_ROUTING_RESP -> {
                if (packet.size < 12) {
                    Log.w(
                        TAG, "Received SMART_ROUTING_RESP packet too short: ${
                        packet.joinToString(" ") {
                            "%02X".format(it)
                        }
                    }")
                    return
                }
                val packetString = packet.decodeToString()
                val sender =
                    packet.sliceArray(6..11).reversedArray().joinToString(":") { "%02X".format(it) }

                // if (connectedDevices.find { it.mac == sender }?.type == null && packetString.contains("btName")) {
                //     val nameStartIndex = packetString.indexOf("btName") + 8
                //     val nameEndIndex = if (packetString.contains("other")) (packetString.indexOf("otherDevice") - 1) else (packetString.indexOf("nearbyAudio") - 1)
                //     val name = packet.sliceArray(nameStartIndex..nameEndIndex).decodeToString()
                //     connectedDevices.find { it.mac == sender }?.type = name
                //     Log.d(TAG, "Device $sender is named $name")
                // } // doesn't work, it's different for Mac and iPad. just hardcoding for now
                if ("iPad" in packetString) {
                    connectedDevices.find { it.mac == sender }?.type = "iPad"
                } else if ("Mac" in packetString) {
                    connectedDevices.find { it.mac == sender }?.type = "Mac"
                } else if ("iPhone" in packetString) { // not sure if this is it - don't have an iphone
                    connectedDevices.find { it.mac == sender }?.type = "iPhone"
                } else if ("Linux" in packetString) {
                    connectedDevices.find { it.mac == sender }?.type = "Linux"
                } else if ("Android" in packetString) {
                    connectedDevices.find { it.mac == sender }?.type = "Android"
                }
                Log.d(
                    TAG,
                    "Smart Routing Response from $sender: $packetString, type: ${connectedDevices.find { it.mac == sender }?.type}"
                )
                when (AacpSmartRoutingParser.parseHostStreamingState(packetString)) {
                    HostStreamingState.STREAMING ->
                        callback?.onRemoteStreamingStateChanged(sender, true, expectedLease)
                    HostStreamingState.NOT_STREAMING ->
                        callback?.onRemoteStreamingStateChanged(sender, false, expectedLease)
                    HostStreamingState.UNRECOGNIZED ->
                        Log.w(TAG, "Unrecognized hostStreamingState value from $sender")
                    HostStreamingState.ABSENT -> Unit
                }
                if (packetString.contains("SetOwnershipToFalse")) {
                    callback?.onOwnershipToFalseRequest(
                        sender,
                        packetString.contains("ReverseBannerTapped"),
                        expectedLease,
                    )
                }
                if (packetString.contains("ShowNearbyUI")) {
                    callback?.onShowNearbyUI(sender)
                }
            }

            Opcodes.HEADPHONE_ACCOMMODATION -> {
                if (packet.size != 140) {
                    Log.w(
                        TAG,
                        "Received HEADPHONE_ACCOMMODATION packet of unexpected size: ${packet.size}, expected 140"
                    )
                    return
                }
                if (packet[6] != 0x84.toByte()) {
                    Log.w(
                        TAG,
                        "Received HEADPHONE_ACCOMMODATION packet with unexpected identifier: ${packet[6].toHexString()}, expected 0x84"
                    )
                    return
                }

                eqOnMedia = (packet[10] == 0x01.toByte())
                eqOnPhone = (packet[11] == 0x01.toByte())
                // there are 4 eqs. i am not sure what those are for, maybe all 4 listening modes, or maybe phone+media left+right, but then there shouldn't be another flag for phone/media visible. just directly the EQ... weird.
                // the EQs are little endian floats
                val eq1 =
                    ByteBuffer.wrap(packet, 12, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                ByteBuffer.wrap(packet, 44, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                ByteBuffer.wrap(packet, 76, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                ByteBuffer.wrap(packet, 108, 32).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()

                // for now, taking just the first EQ
                eqData = FloatArray(8) { i -> eq1.get(i) }

                Log.d(
                    TAG,
                    "EQ Data set to: ${eqData.toList()}, eqOnPhone: $eqOnPhone, eqOnMedia: $eqOnMedia"
                )

                callback?.onHeadphoneAccommodationReceived(eqData)
            }

            Opcodes.INFORMATION -> {
                Log.d(TAG, "Parsing Information Packet")
                val information = parseInformationPacket(packet)
                callback?.onDeviceInformationReceived(information, expectedLease)
            }

            Opcodes.CUSTOM_EQ -> {
                if (allowRawLogging) {
                    Log.d(TAG, "Parsing CUSTOM_EQ: ${packet.toHexString()}")
                } else {
                    Log.d(TAG, "Parsing redacted CUSTOM_EQ packet")
                }
                customEq = parseCustomEqPacket(packet)
                customEqCallback?.invoke(customEq)
                callback?.onCustomEqReceived(customEq)
            }

            else -> {
                Log.d(TAG, "Unhandled opcode received: ${opcode.toHexString()}")
                if (allowRawLogging) callback?.onUnknownPacketReceived(packet)
            }
        }
    }

    fun sendNotificationRequest(expectedLease: AacpSocketLease? = socketLease): Boolean {
        return sendDataPacket(createRequestNotificationPacket(), expectedLease)
    }

    fun createRequestNotificationPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.REQUEST_NOTIFICATIONS, 0x00)
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        // note to self #1: third byte is 0xfd when ear detection is disabled
        // note to self #2: this can be sent any time, not just at the start of the aacp connection
        return opcode + data
    }

    fun sendSetFeatureFlagsPacket(expectedLease: AacpSocketLease? = socketLease): Boolean {
        return sendDataPacket(createSetFeatureFlagsPacket(), expectedLease)
    }

    fun createSetFeatureFlagsPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.SET_FEATURE_FLAGS, 0x00)
        val data = byteArrayOf(0xD7.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        return opcode + data
    }

    fun createHandshakePacket(): ByteArray {
        return byteArrayOf(
            0x00,
            0x00,
            0x04,
            0x00,
            0x01,
            0x00,
            0x02,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00
        )
    }

    fun sendStartHeadTracking(expectedLease: AacpSocketLease? = socketLease): Boolean {
        return sendDataPacket(createStartHeadTrackingPacket(), expectedLease)
    }

    fun createStartHeadTrackingPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.HEADTRACKING, 0x00)
        val data = byteArrayOf(
            0x00,
            0x00,
            0x10,
            0x00,
            0x10,
            0x00,
            0x08,
            0xA1.toByte(),
            0x02,
            0x42,
            0x0B,
            0x08,
            0x0E,
            0x10,
            0x02,
            0x1A,
            0x05,
            0x01,
            0x40,
            0x9C.toByte(),
            0x00,
            0x00,
        )
        return opcode + data
    }

    fun createAlternateStartHeadTrackingPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.HEADTRACKING, 0x00)
        val data = byteArrayOf(
            0x00,
            0x00,
            0x10,
            0x00,
            0x0F,
            0x00,
            0x08,
            0x73,
            0x42,
            0x0B,
            0x08,
            0x10,
            0x10,
            0x02,
            0x1A,
            0x05,
            0x01,
            0x40,
            0x9C.toByte(),
            0x00,
            0x00
        )
        return opcode + data
    }

    fun sendStopHeadTracking(expectedLease: AacpSocketLease? = socketLease): Boolean {
        return sendDataPacket(createStopHeadTrackingPacket(), expectedLease)
    }

    fun createStopHeadTrackingPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.HEADTRACKING, 0x00)
        val data = byteArrayOf(
            0x00,
            0x00,
            0x10,
            0x00,
            0x11,
            0x00,
            0x08,
            0x7E,
            0x10,
            0x02,
            0x42,
            0x0B,
            0x08,
            0x4E,
            0x10,
            0x02,
            0x1A,
            0x05,
            0x01,
            0x00,
            0x00,
            0x00,
            0x00
        )
        return opcode + data
    }

    fun createAlternateStopHeadTrackingPacket(): ByteArray {
        val opcode = byteArrayOf(Opcodes.HEADTRACKING, 0x00)
        val data = byteArrayOf(
            0x00,
            0x00,
            0x10,
            0x00,
            0x0F,
            0x00,
            0x08,
            0x75,
            0x42,
            0x0B,
            0x08,
            0x10,
            0x10,
            0x02,
            0x1A,
            0x05,
            0x01,
            0x00,
            0x00,
            0x00,
            0x00
        )
        return opcode + data
    }

    fun sendRename(name: String): Boolean {
        return sendDataPacket(createRenamePacket(name))
    }

    fun createRenamePacket(name: String): ByteArray {
        val nameBytes = name.toByteArray()
        val size = nameBytes.size
        val packet = ByteArray(5 + size)
        packet[0] = Opcodes.RENAME
        packet[1] = 0x00
        packet[2] = 0x01
        packet[3] = size.toByte()
        packet[4] = 0x00
        System.arraycopy(nameBytes, 0, packet, 5, size)

        return packet
    }

    fun sendMediaInformationNewDevice(
        selfMacAddress: String,
        targetMacAddress: String,
        expectedLease: AacpSocketLease? = socketLease,
    ): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) || targetMacAddress.length != 17 || !targetMacAddress.matches(
                Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
            )
        ) {
            // throw IllegalArgumentException("MAC address must be 6 bytes")
            Log.w(
                TAG,
                "Invalid MAC address format, got: selfMacAddress=$selfMacAddress, targetMacAddress=$targetMacAddress"
            )
            return false
        }
        Log.d(TAG, "SELFMAC: ${selfMacAddress}, TARGETMAC: $targetMacAddress")
        Log.d(TAG, "Sending Media Information packet to $targetMacAddress")
        return sendDataPacket(
            createMediaInformationNewDevicePacket(
                selfMacAddress,
                targetMacAddress
            ),
            expectedLease,
        )
    }

    fun createMediaInformationNewDevicePacket(
        selfMacAddress: String,
        targetMacAddress: String
    ): ByteArray {
        val opcode = byteArrayOf(Opcodes.SMART_ROUTING, 0x00)
        val buffer = ByteBuffer.allocate(116)
        buffer.put(
            targetMacAddress.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()
        )
        buffer.put(byteArrayOf(0x6C, 0x00))
        buffer.put(byteArrayOf(0x01, 0xE5.toByte(), 0x4A))
        buffer.put("playingApp".toByteArray())
        buffer.put(0x42)
        buffer.put("NA".toByteArray())
        buffer.put(0x52)
        buffer.put("hostStreamingState".toByteArray())
        buffer.put(0x42)
        buffer.put("NO".toByteArray())
        buffer.put(0x49)
        buffer.put("btAddress".toByteArray())
        buffer.put(0x51)
        buffer.put(selfMacAddress.toByteArray())
        buffer.put(0x46)
        buffer.put("btName".toByteArray())
        buffer.put(0x47)
        buffer.put("Android".toByteArray())
        buffer.put(0x58)
        buffer.put("otherDevice".toByteArray())
        buffer.put("AudioCategory".toByteArray())
        buffer.put(byteArrayOf(0x30, 0x64))

        return opcode + buffer.array()
    }

    /**
     * Builds Apple's periodic Smart Routing Media Information report. This reports local
     * playback state only; unlike the removed takeover path, it never requests ownership.
     * Uses fixed separators matching historical format, not OPACK dynamic length encoding.
     */
    fun createMediaInformationPacket(
        selfMacAddress: String,
        targetMacAddress: String,
        playingApp: String,
        streamingState: Boolean = true,
    ): ByteArray {
        val opcode = byteArrayOf(Opcodes.SMART_ROUTING, 0x00)
        val buffer = ByteBuffer.allocate(138)
        val rawPlayingApp = playingApp.ifBlank { "NA" }
        val playingAppBytes = rawPlayingApp.toByteArray().copyOf(22).apply {
            for (i in rawPlayingApp.length until 22) this[i] = 0
        }
        val streamingStateBytes = (if (streamingState) "YES" else "NO").toByteArray()

        buffer.put(
            targetMacAddress.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()
        )
        buffer.put(byteArrayOf(0x82.toByte(), 0x00))
        buffer.put(byteArrayOf(0x01, 0xE5.toByte(), 0x4A))
        buffer.put("PlayingApp".toByteArray())
        buffer.put(0x56)
        buffer.put(playingAppBytes)
        buffer.put(0x52)
        buffer.put("HostStreamingState".toByteArray())
        buffer.put(0x42)
        buffer.put(streamingStateBytes)
        buffer.put(0x49)
        buffer.put("btAddress".toByteArray())
        buffer.put(0x51)
        buffer.put(selfMacAddress.toByteArray())
        buffer.put(0x46)
        buffer.put("btName".toByteArray())
        buffer.put(0x47)
        buffer.put("Android".toByteArray())
        buffer.put(0x58)
        buffer.put("otherDevice".toByteArray())
        buffer.put("AudioCategory".toByteArray())
        buffer.put(byteArrayOf(0x31, 0x2D, 0x01))

        return opcode + buffer.array()
    }

    fun resolveSmartRoutingTarget(selfMacAddress: String): String? {
        val selfAddress = LocalHostIdentity.normalize(selfMacAddress) ?: return null
        return buildList {
            audioSource
                ?.takeUnless { it.type == AudioSourceType.NONE }
                ?.mac
                ?.let(::add)
            connectedDevices.mapTo(this) { it.mac }
        }.asSequence()
            .mapNotNull(LocalHostIdentity::normalize)
            .firstOrNull { it != selfAddress }
    }

    fun sendAddTiPiDevice(
        selfMacAddress: String,
        targetMacAddress: String,
        expectedLease: AacpSocketLease? = socketLease,
    ): Boolean {
        if (selfMacAddress.length != 17 || !selfMacAddress.matches(Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")) || targetMacAddress.length != 17 || !targetMacAddress.matches(
                Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")
            )
        ) {
            // throw IllegalArgumentException("MAC address must be 6 bytes")
            Log.w(
                TAG,
                "Invalid MAC address format, got: selfMacAddress=$selfMacAddress, targetMacAddress=$targetMacAddress"
            )
            return false
        }
        Log.d(TAG, "Sending Add TiPi Device packet to $targetMacAddress")
        return sendDataPacket(
            createAddTiPiDevicePacket(selfMacAddress, targetMacAddress),
            expectedLease,
        )
    }

    fun createAddTiPiDevicePacket(selfMacAddress: String, targetMacAddress: String): ByteArray {
        val opcode = byteArrayOf(Opcodes.SMART_ROUTING, 0x00)
        val buffer = ByteBuffer.allocate(90)
        buffer.put(
            targetMacAddress.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()
        )
        buffer.put(byteArrayOf(0x52, 0x00))
        buffer.put(byteArrayOf(0x01, 0xE5.toByte()))
        buffer.put(0x48) // 'H'
        buffer.put("idleTime".toByteArray())
        buffer.put(byteArrayOf(0x08, 0x47))
        buffer.put("newTipi".toByteArray())
        buffer.put(byteArrayOf(0x01, 0x49))
        buffer.put("btAddress".toByteArray())
        buffer.put(0x51)
        buffer.put(selfMacAddress.toByteArray())
        buffer.put(0x46)
        buffer.put("btName".toByteArray())
        buffer.put(0x47)
        buffer.put("Android".toByteArray())
        buffer.put(0x50)
        buffer.put("nearbyAudioScore".toByteArray())
        buffer.put(byteArrayOf(0x0E))
        return opcode + buffer.array()
    }

    data class ControlCommand(
        val identifier: Byte, val value: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as ControlCommand

            if (identifier != other.identifier) return false
            if (!value.contentEquals(other.value)) return false

            return true
        }

        override fun hashCode(): Int {
            var result: Int = identifier.toInt()
            result = 31 * result + value.contentHashCode()
            return result
        }

        companion object {
            fun fromByteArray(data: ByteArray): ControlCommand {
                var offset = 0
                while (data.size - offset >= 4 &&
                    data[offset] == 0x04.toByte() &&
                    data[offset + 1] == 0x00.toByte() &&
                    data[offset + 2] == 0x04.toByte() &&
                    data[offset + 3] == 0x00.toByte()
                ) {
                    offset += 4
                }
                if (data.size - offset < 7) {
                    throw IllegalArgumentException("Too short for ControlCommand")
                }
                if (data[offset] != Opcodes.CONTROL_COMMAND) {
                    throw IllegalArgumentException("Invalid opcode")
                }
                val identifier = data[offset + 2]
                val value = data.copyOfRange(offset + 3, offset + 7)
                val trimmed = value.dropLastWhile { it == 0x00.toByte() }.toByteArray()
                return ControlCommand(identifier, if (trimmed.isEmpty()) byteArrayOf(0x00) else trimmed)
            }
        }
    }

    @OptIn(ExperimentalStdlibApi::class)
    fun sendStemConfigPacket(
        singlePressCustomized: Boolean = false,
        doublePressCustomized: Boolean = false,
        triplePressCustomized: Boolean = false,
        longPressCustomized: Boolean = false,
        expectedLease: AacpSocketLease? = socketLease,
    ): Boolean {
        val value =
            ((if (singlePressCustomized) 0x01 else 0) or (if (doublePressCustomized) 0x02 else 0) or (if (triplePressCustomized) 0x04 else 0) or (if (longPressCustomized) 0x08 else 0)).toByte()
        Log.d(TAG, "Sending Stem Config Packet with value: ${value.toHexString()}")
        return sendControlCommand(
            ControlCommandIdentifiers.STEM_CONFIG.value,
            value,
            expectedLease,
        )
    }

    @OptIn(ExperimentalStdlibApi::class)
    @Synchronized
    fun sendPacket(
        packet: ByteArray,
        expectedLease: AacpSocketLease? = socketLease,
    ): Boolean {
        if (packet.size < 5) {
            Log.w(TAG, "Refusing malformed AACP packet (${packet.size} bytes)")
            return false
        }
        try {
            Log.d(TAG, "Sending packet: ${packet.joinToString(" ") { "%02X".format(it) }}")

            if (packet.size > 4 && packet[4] == Opcodes.CONTROL_COMMAND) {
                val controlCommand = try {
                    ControlCommand.fromByteArray(packet)
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid control command: ${e.message}")
                    callback?.onUnknownPacketReceived(packet)
                    return false
                }
                Log.d(
                    TAG, "Control command: ${controlCommand.identifier.toHexString()} - ${
                    controlCommand.value.joinToString(" ") { "%02X".format(it) }
                }")
                if (ControlCommandIdentifiers.fromByte(controlCommand.identifier) == null) {
                    return false
                }
            }

            if (BluetoothConnectionManager.writeAacpPacket(packet, expectedLease)) {
                return true
            } else {
                Log.d(TAG, "Can't send packet: Socket not initialized or connected")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending packet: ${e.message}")
            return false
        }
    }

    fun sendPhoneMediaEQ(eq: FloatArray, phone: Byte = 0x02.toByte(), media: Byte = 0x02.toByte()) {
        if (eq.size != 8) throw IllegalArgumentException("EQ must be 8 floats")
        val header = byteArrayOf(
            0x04.toByte(),
            0x00.toByte(),
            0x04.toByte(),
            0x00.toByte(),
            0x53.toByte(),
            0x00.toByte(),
            0x84.toByte(),
            0x00.toByte(),
            0x02.toByte(),
            0x02.toByte(),
            phone,
            media
        )
        val buffer = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        for (block in 0..3) {
            for (i in 0..7) {
                buffer.putFloat(eq[i])
            }
        }
        val payload = buffer.array()
        val packet = header + payload
        sendPacket(packet)
        this.eqData = eq.copyOf()
        this.eqOnPhone = phone == 0x01.toByte()
        this.eqOnMedia = media == 0x01.toByte()
    }

    fun parseAudioSourceResponse(data: ByteArray): Pair<String, AudioSourceType> {
        Log.d(TAG, "Parsing Audio Source Response: ${data.joinToString(" ") { "%02X".format(it) }}")
        val (sourceAddress, type) = AacpAudioSourceParser.parse(data)
        if (type == AudioSourceType.UNKNOWN) {
            Log.w(TAG, "Unknown active audio source type: ${data[12].toInt() and 0xFF}")
        }
        return sourceAddress to type
    }

    fun parseConnectedDevicesResponse(data: ByteArray): List<ConnectedDevice> {
        Log.d(
            TAG,
            "Parsing Connected Devices Response: ${data.joinToString(" ") { "%02X".format(it) }}"
        )
        if (data.size < 9) {
            throw IllegalArgumentException("Data array too short to parse Connected Devices Response")
        }
        if (data[4] != Opcodes.CONNECTED_DEVICES) {
            throw IllegalArgumentException("Data array does not start with CONNECTED_DEVICES opcode")
        }
        val deviceCount = data[8].toInt() and 0xFF
        val devices = mutableListOf<ConnectedDevice>()

        var offset = 9
        for (i in 0 until deviceCount) {
            if (offset + 8 > data.size) {
                Log.w(
                    TAG,
                    "Data array too short to parse all connected devices, returning what we have"
                )
                break
            }
            val macBytes = data.sliceArray(offset until offset + 6)
            val mac = macBytes.joinToString(":") { "%02X".format(it) }
            val info1 = data[offset + 6]
            val info2 = data[offset + 7]
            val existingDevice = devices.find { it.mac == mac }
            devices.add(ConnectedDevice(mac, info1, info2, existingDevice?.type))
            offset += 8
        }

        return devices
    }

    fun sendSomePacketIDontKnowWhatItIs(
        expectedLease: AacpSocketLease? = socketLease,
    ) {
        // 2900 00ff ffff ffff ffff -- enables setting EQ
        sendDataPacket(
            byteArrayOf(
                0x29, 0x00,
                0x00, 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte(),
                0xFF.toByte(), 0xFF.toByte(),
            ),
            expectedLease,
        )
    }

    @Synchronized
    fun disconnected(expectedLease: AacpSocketLease? = socketLease): Boolean {
        val boundLease = socketLease
        if (!AacpSessionCleanupGate.canClear(boundLease, expectedLease)) {
            Log.d(TAG, "Ignoring stale AACP session cleanup")
            return false
        }
        Log.d(TAG, "Disconnected, clearing state")
        socketLease = null
        controlCommandStatusList.clear()
        controlCommandListeners.clear()
        owns = false
        oldConnectedDevices = listOf()
        connectedDevices = listOf()
        audioSource = null
        return true
    }

    fun parseInformationPacket(packet: ByteArray): AirPodsInformation {
        val data = packet.sliceArray(6 until packet.size)

        var index = 0
        while (index < data.size && data[index] != 0x00.toByte()) index++

        val strings = mutableListOf<String>()
        while (index < data.size) {
            // skip 0x00 bytes
            while (index < data.size && data[index] == 0x00.toByte()) index++
            if (index >= data.size) break
            val start = index
            // find next 0x00 byte
            while (index < data.size && data[index] != 0x00.toByte()) index++
            val str = data.sliceArray(start until index).decodeToString()
            strings.add(str)
        }

        strings.removeAt(0) // I'm too lazy to adjust, just removing the first empty string

        return AirPodsInformation(
            name = strings.getOrNull(0) ?: "",
            modelNumber = strings.getOrNull(1) ?: "",
            manufacturer = strings.getOrNull(2) ?: "",
            serialNumber = strings.getOrNull(3) ?: "",
            version1 = strings.getOrNull(4) ?: "",
            version2 = strings.getOrNull(5) ?: "",
            hardwareRevision = strings.getOrNull(6) ?: "",
            updaterIdentifier = strings.getOrNull(7) ?: "",
            leftSerialNumber = strings.getOrNull(8) ?: "",
            rightSerialNumber = strings.getOrNull(9) ?: "",
            version3 = strings.getOrNull(10) ?: "",
        )
    }

    fun sendCustomEqPacket(customEq: CustomEq): Boolean {
        return sendDataPacket(customEq.toPacket())
    }

    fun parseCustomEqPacket(packet: ByteArray): CustomEq {
        val data = packet.sliceArray(6 until packet.size)

        if (data.size < 7) {
            Log.e(TAG, "custom EQ packet length less than 7, returning default")
            return CustomEq(1, 50, 50, 50)
        }

        val lengthLow = data[0].toInt() and 0xFF
        val lengthHigh = data[1].toInt() and 0xFF

        val length = (lengthHigh shl 8) or lengthLow

        if (length != 5) {
            Log.w(TAG, "parseCustomEqPacket: unexpected length ($length). parsing normally")
        }

        val state = data[3].toInt()
        val low = data[4].toInt()
        val mid = data[5].toInt()
        val high = data[6].toInt()

        return CustomEq(
            state,
            low,
            mid,
            high
        )
    }
}
