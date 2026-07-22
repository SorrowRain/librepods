package me.kavishdevar.librepods.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals

class AacpAudioSourceParserTest {
    @Test
    fun `unknown active source type preserves remote host address`() {
        val packet = byteArrayOf(
            0x04,
            0x00,
            0x04,
            0x00,
            AACPManager.Companion.Opcodes.AUDIO_SOURCE,
            0x00,
            0x66,
            0x55,
            0x44,
            0x33,
            0x22,
            0x11,
            0x7F,
        )

        val (sourceAddress, sourceType) = AacpAudioSourceParser.parse(packet)

        assertEquals("11:22:33:44:55:66", sourceAddress)
        assertEquals(AACPManager.Companion.AudioSourceType.UNKNOWN, sourceType)
    }
}
