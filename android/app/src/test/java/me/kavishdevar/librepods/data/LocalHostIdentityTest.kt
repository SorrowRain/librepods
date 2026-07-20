package me.kavishdevar.librepods.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalHostIdentityTest {
    @Test
    fun `normalization rejects missing placeholders and malformed addresses`() {
        listOf(
            null,
            "",
            "null",
            "00:00:00:00:00:00",
            "02:00:00:00:00:00",
            "02:11:22:33:44",
            "not-a-mac",
        ).forEach { candidate ->
            assertNull(LocalHostIdentity.normalize(candidate), candidate)
        }
    }

    @Test
    fun `normalization accepts and canonicalizes a valid bluetooth address`() {
        assertEquals(
            "02:11:22:33:44:55",
            LocalHostIdentity.normalize(" 02:11:22:33:44:55 ")
        )
    }

    @Test
    fun `known source classification fails closed without local identity`() {
        assertEquals(
            TakeoverAudioSource.UNKNOWN,
            LocalHostIdentity.classifyKnownSource("", "02:11:22:33:44:55")
        )
        assertEquals(
            TakeoverAudioSource.UNKNOWN,
            LocalHostIdentity.classifyKnownSource(
                "02:00:00:00:00:00",
                "02:11:22:33:44:55"
            )
        )
    }

    @Test
    fun `known source classification distinguishes local and remote hosts`() {
        assertEquals(
            TakeoverAudioSource.LOCAL,
            LocalHostIdentity.classifyKnownSource(
                "02:11:22:33:44:55",
                "02:11:22:33:44:55"
            )
        )
        assertEquals(
            TakeoverAudioSource.REMOTE,
            LocalHostIdentity.classifyKnownSource(
                "02:11:22:33:44:55",
                "02:AA:BB:CC:DD:EE"
            )
        )
    }

    @Test
    fun `aacp inference requires every local playback signal`() {
        val source = "02:11:22:33:44:55"
        assertNull(
            LocalHostIdentity.inferFromActiveLocalMedia(
                "", source, false, true, false, true
            )
        )
        assertNull(
            LocalHostIdentity.inferFromActiveLocalMedia(
                "", source, true, false, false, true
            )
        )
        assertNull(
            LocalHostIdentity.inferFromActiveLocalMedia(
                "", source, true, true, false, false
            )
        )
        assertEquals(
            source,
            LocalHostIdentity.inferFromActiveLocalMedia(
                "", source, true, true, false, true
            )
        )
        assertEquals(
            source,
            LocalHostIdentity.inferFromActiveLocalMedia(
                "", source, false, true, true, true
            )
        )
    }
}
