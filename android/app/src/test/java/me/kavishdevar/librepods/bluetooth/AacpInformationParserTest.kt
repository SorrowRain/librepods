package me.kavishdevar.librepods.bluetooth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Guards the INFORMATION branch hardening: a truncated or zero-payload device-info frame must
 * degrade to empty fields instead of throwing IndexOutOfBoundsException out of receivePacket
 * (which the reader used to treat as a disconnect). Field semantics follow the existing
 * parser layout (each string is null-delimited); these tests assert the crash fix, not the
 * upstream field mapping.
 */
class AacpInformationParserTest {

    @Test
    fun `parses a normal device-info packet without throwing`() {
        // 6-byte header + opcode 0x1D, then null-delimited string fields.
        val packet = bytes(
            "04 00 1F 00 1D 00 " +
                "00 4D 61 63 42 6F 6F 6B 20 50 72 6F 00 41 32 34 36 37 00 " + // "MacBook Pro" "A2467"
                "41 70 70 6C 65 00 53 65 72 69 61 6C 31 32 33" // "Apple" "Serial123"
        )
        val info = AACPManager().parseInformationPacket(packet)
        assertNotNull(info)
    }

    @Test
    fun `zero-length payload degrades to empty fields instead of crashing`() {
        // Just the 6-byte header + opcode, nothing after — previously crashed on removeAt(0).
        val packet = bytes("04 00 04 00 1D 00")
        val info = AACPManager().parseInformationPacket(packet)
        assertEquals("", info.name)
        assertEquals("", info.modelNumber)
        assertEquals("", info.serialNumber)
    }

    @Test
    fun `all-zero payload degrades to empty fields instead of crashing`() {
        val packet = bytes("04 00 0A 00 1D 00 00 00 00 00")
        val info = AACPManager().parseInformationPacket(packet)
        assertEquals("", info.name)
        assertEquals("", info.modelNumber)
    }

    private fun bytes(hex: String): ByteArray =
        hex.trim().split(Regex("\\s+")).map { it.toInt(16).toByte() }.toByteArray()
}
