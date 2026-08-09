package me.kavishdevar.librepods.heart

import me.kavishdevar.librepods.data.Capability
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HeartRateEligibilityTest {
    @Test
    fun `all exact AirPods Pro 3 model numbers require current Device Information`() {
        listOf("A3063", "A3064", "A3065").forEachIndexed { index, modelNumber ->
            val key = key(epoch = index + 1L)
            var state = install(key)
            assertFalse(state.supported)

            state = confirm(
                state,
                key,
                modelNumber,
                setOf(Capability.LISTENING_MODE, Capability.HRM),
            )
            assertTrue(state.supported, modelNumber)
        }
    }

    @Test
    fun `model number matching is exact and HRM capability is mandatory`() {
        val rejected = listOf(
            "A3049" to setOf(Capability.HRM),
            "a3063" to setOf(Capability.HRM),
            " A3063" to setOf(Capability.HRM),
            "A3063 " to setOf(Capability.HRM),
            "A3063" to setOf(Capability.LISTENING_MODE),
            "A3063" to emptySet(),
        )

        rejected.forEachIndexed { index, (modelNumber, capabilities) ->
            val key = key(epoch = index + 1L)
            val state = confirm(install(key), key, modelNumber, capabilities)
            assertFalse(state.supported, "$modelNumber with $capabilities")
        }
    }

    @Test
    fun `stale Device Information cannot confirm the current session`() {
        val oldKey = key(epoch = 1L)
        val currentKey = key(epoch = 2L)
        val current = HeartRateEligibilityReducer.reduce(
            install(oldKey),
            HeartRateEligibilityEvent.SessionInstalled(currentKey),
        )

        val stale = confirm(
            current,
            oldKey,
            "A3063",
            setOf(Capability.HRM),
        )
        assertSame(current, stale)
        assertFalse(stale.supported)

        val sameEpochDifferentIdentity = key(epoch = 2L)
        val colliding = confirm(
            current,
            sameEpochDifferentIdentity,
            "A3064",
            setOf(Capability.HRM),
        )
        assertSame(current, colliding)
        assertFalse(colliding.supported)
    }

    @Test
    fun `new session invalidates confirmation and stale clear cannot remove it`() {
        val firstKey = key(epoch = 1L)
        var state = confirm(
            install(firstKey),
            firstKey,
            "A3063",
            setOf(Capability.HRM),
        )
        assertTrue(state.supported)

        val secondKey = key(epoch = 2L)
        state = HeartRateEligibilityReducer.reduce(
            state,
            HeartRateEligibilityEvent.SessionInstalled(secondKey),
        )
        assertFalse(state.supported)
        assertTrue(state.isCurrent(secondKey))

        val staleClear = HeartRateEligibilityReducer.reduce(
            state,
            HeartRateEligibilityEvent.SessionCleared(firstKey),
        )
        assertSame(state, staleClear)
        assertTrue(staleClear.isCurrent(secondKey))

        val cleared = HeartRateEligibilityReducer.reduce(
            staleClear,
            HeartRateEligibilityEvent.SessionCleared(secondKey),
        )
        assertFalse(cleared.supported)
        assertFalse(cleared.isCurrent(secondKey))

        val staleReinstall = HeartRateEligibilityReducer.reduce(
            cleared,
            HeartRateEligibilityEvent.SessionInstalled(firstKey),
        )
        assertSame(cleared, staleReinstall)
    }

    @Test
    fun `confirmed capabilities are copied and destroy rejects late callbacks`() {
        val key = key(epoch = 1L)
        val capabilities = mutableSetOf(Capability.HRM)
        val confirmed = confirm(install(key), key, "A3065", capabilities)
        capabilities.clear()
        assertTrue(confirmed.supported)

        val destroyed = HeartRateEligibilityReducer.reduce(
            confirmed,
            HeartRateEligibilityEvent.Destroy,
        )
        assertFalse(destroyed.supported)

        val lateInstall = HeartRateEligibilityReducer.reduce(
            destroyed,
            HeartRateEligibilityEvent.SessionInstalled(key(epoch = 2L)),
        )
        assertSame(destroyed, lateInstall)
    }

    private fun install(key: HrmSessionKey): HeartRateEligibilityState =
        HeartRateEligibilityReducer.reduce(
            HeartRateEligibilityState(),
            HeartRateEligibilityEvent.SessionInstalled(key),
        )

    private fun confirm(
        state: HeartRateEligibilityState,
        key: HrmSessionKey,
        modelNumber: String,
        capabilities: Set<Capability>,
    ): HeartRateEligibilityState = HeartRateEligibilityReducer.reduce(
        state,
        HeartRateEligibilityEvent.DeviceInformationConfirmed(
            key = key,
            modelNumber = modelNumber,
            modelCapabilities = capabilities,
        ),
    )

    private fun key(epoch: Long) = HrmSessionKey(
        socketIdentity = Any(),
        generation = epoch,
        deviceKey = "device-$epoch",
        epoch = epoch,
    )
}
