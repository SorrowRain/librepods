package me.kavishdevar.librepods.audio

import java.lang.reflect.InvocationTargetException
import org.junit.Assert.*
import org.junit.Test

class AudioProductStrategyLookupTest {
    class EmptyApi
    class ModernApi {
        companion object { @JvmStatic fun getAudioProductStrategies() = listOf("modern") }
    }
    class LegacyApi {
        companion object { @JvmStatic fun getAudioProductStrategies() = listOf("legacy") }
    }
    class DeniedApi {
        companion object {
            @JvmStatic fun getAudioProductStrategies(): List<String> = throw SecurityException("denied")
        }
    }

    @Test fun `Android 17 does not require the removed legacy method`() {
        assertEquals(listOf("modern"), AudioProductStrategyLookup.query(ModernApi::class.java, EmptyApi::class.java))
    }
    @Test fun `older platforms retain the legacy API owner`() {
        assertEquals(listOf("legacy"), AudioProductStrategyLookup.query(EmptyApi::class.java, LegacyApi::class.java))
    }
    @Test fun `permission failure is not hidden by a fallback`() {
        try {
            AudioProductStrategyLookup.query(DeniedApi::class.java, LegacyApi::class.java)
            fail("Expected the platform error")
        } catch (error: InvocationTargetException) {
            assertTrue(error.cause is SecurityException)
        }
    }
}
