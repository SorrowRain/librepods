package me.kavishdevar.librepods.bluetooth

internal enum class HostStreamingState {
    STREAMING,
    NOT_STREAMING,
    ABSENT,
    UNRECOGNIZED,
}

internal object AacpSmartRoutingParser {
    private const val HOST_STREAMING_KEY = "hostStreamingState"

    // The value follows the key after a single type/length byte (see
    // createMediaInformationNewDevicePacket). A short window tolerates minor layout
    // drift without matching unrelated fields such as btName or playingApp, whose
    // values may themselves contain "yes"/"no".
    private const val VALUE_WINDOW_CHARS = 8

    fun parseHostStreamingState(packetString: String): HostStreamingState {
        val keyIndex = packetString.indexOf(HOST_STREAMING_KEY, ignoreCase = true)
        if (keyIndex < 0) return HostStreamingState.ABSENT
        val valueStart = keyIndex + HOST_STREAMING_KEY.length
        val valueEnd = (valueStart + VALUE_WINDOW_CHARS).coerceAtMost(packetString.length)
        val window = packetString.substring(valueStart, valueEnd)
        return when {
            window.contains("YES", ignoreCase = true) -> HostStreamingState.STREAMING
            window.contains("NO", ignoreCase = true) -> HostStreamingState.NOT_STREAMING
            else -> HostStreamingState.UNRECOGNIZED
        }
    }
}
