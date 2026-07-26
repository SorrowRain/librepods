package me.kavishdevar.librepods.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals

class AacpSmartRoutingParserTest {

    // Mirrors the wire layout of createMediaInformationNewDevicePacket: each value
    // follows its key after a single type/length byte.
    private fun packetString(playingApp: String, state: String, btName: String): String =
        "åJplayingAppB$playingApp" +
            "RhostStreamingStateB$state" +
            "IbtAddressQ11:22:33:44:55:66" +
            "FbtNameG$btName" +
            "XotherDeviceAudioCategory"

    @Test
    fun `yes value parses as streaming`() {
        assertEquals(
            HostStreamingState.STREAMING,
            AacpSmartRoutingParser.parseHostStreamingState(
                packetString("NA", "YES", "MacBook Pro")
            ),
        )
    }

    @Test
    fun `no value parses as not streaming`() {
        assertEquals(
            HostStreamingState.NOT_STREAMING,
            AacpSmartRoutingParser.parseHostStreamingState(
                packetString("NA", "NO", "MacBook Pro")
            ),
        )
    }

    @Test
    fun `yes inside device name after key does not flip a no state`() {
        assertEquals(
            HostStreamingState.NOT_STREAMING,
            AacpSmartRoutingParser.parseHostStreamingState(
                packetString("NA", "NO", "Yesenia's MacBook")
            ),
        )
    }

    @Test
    fun `yes inside playing app before key does not flip a no state`() {
        assertEquals(
            HostStreamingState.NOT_STREAMING,
            AacpSmartRoutingParser.parseHostStreamingState(
                packetString("yesmusic", "NO", "MacBook Pro")
            ),
        )
    }

    @Test
    fun `missing key reports absent`() {
        assertEquals(
            HostStreamingState.ABSENT,
            AacpSmartRoutingParser.parseHostStreamingState("IbtAddressQ11:22:33:44:55:66"),
        )
    }

    @Test
    fun `unknown value reports unrecognized`() {
        assertEquals(
            HostStreamingState.UNRECOGNIZED,
            AacpSmartRoutingParser.parseHostStreamingState(
                packetString("NA", "MAYBE", "MacBook Pro")
            ),
        )
    }

    @Test
    fun `key truncated at end of packet reports unrecognized`() {
        assertEquals(
            HostStreamingState.UNRECOGNIZED,
            AacpSmartRoutingParser.parseHostStreamingState("RhostStreamingState"),
        )
    }
}
