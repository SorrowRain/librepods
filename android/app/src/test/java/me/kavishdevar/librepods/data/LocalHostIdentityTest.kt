package me.kavishdevar.librepods.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalHostIdentityTest {
    @Test
    fun `normalization rejects placeholders and malformed addresses`() {
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
    fun `normalization canonicalizes a valid address`() {
        assertEquals(
            "02:11:22:33:44:55",
            LocalHostIdentity.normalize(" 02:11:22:33:44:55 "),
        )
    }
}
