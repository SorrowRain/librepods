package me.kavishdevar.librepods.bluetooth

internal object AacpAudioSourceParser {
    fun parse(data: ByteArray): Pair<String, AACPManager.Companion.AudioSourceType> {
        if (data.size < 13) {
            throw IllegalArgumentException("Data array too short to parse Audio Source Response")
        }
        if (data[4] != AACPManager.Companion.Opcodes.AUDIO_SOURCE) {
            throw IllegalArgumentException("Data array does not start with AUDIO_SOURCE opcode")
        }

        val sourceAddress = data.sliceArray(6..11)
            .reversedArray()
            .joinToString(":") { "%02X".format(it) }
        val sourceType = AACPManager.Companion.AudioSourceType.fromByte(data[12])
        return sourceAddress to sourceType
    }
}
