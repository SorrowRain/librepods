package me.kavishdevar.librepods.data

import kotlin.test.Test
import kotlin.test.assertEquals

class GuardHealthTest {

    private fun evaluate(
        enabled: Boolean = true,
        barrierState: String = "registered",
        barrierActive: Boolean = true,
        nativeLeaseAvailable: Boolean = true,
        localMacResolved: Boolean = true,
        aacpConnected: Boolean = true,
        guardState: RemoteDeviceAudioGuardState = RemoteDeviceAudioGuardState.IDLE,
    ) = GuardHealth.evaluate(
        enabled = enabled,
        barrierState = barrierState,
        barrierActive = barrierActive,
        nativeLeaseAvailable = nativeLeaseAvailable,
        localMacResolved = localMacResolved,
        aacpConnected = aacpConnected,
        guardState = guardState,
    )

    @Test
    fun `everything in place is active without reasons`() {
        val health = evaluate()

        assertEquals(GuardHealthLevel.ACTIVE, health.level)
        assertEquals(emptyList(), health.reasons)
        assertEquals(null, health.primaryReason)
    }

    @Test
    fun `disabled by user short-circuits every other problem`() {
        val health = evaluate(
            enabled = false,
            barrierState = "permission_missing",
            barrierActive = false,
            nativeLeaseAvailable = false,
            localMacResolved = false,
        )

        assertEquals(GuardHealthLevel.DISABLED, health.level)
        assertEquals(listOf(GuardHealthReason.DISABLED_BY_USER), health.reasons)
    }

    @Test
    fun `missing routing permission makes protection inactive`() {
        val health = evaluate(barrierState = "permission_missing", barrierActive = false)

        assertEquals(GuardHealthLevel.INACTIVE, health.level)
        assertEquals(GuardHealthReason.PERMISSION_MISSING, health.primaryReason)
    }

    @Test
    fun `barrier states that could not be applied report BARRIER_FAILED`() {
        listOf(
            "registration_failed",
            "speaker_unavailable",
            "not_initialized",
        ).forEach { state ->
            val health = evaluate(barrierState = state, barrierActive = false)

            assertEquals(GuardHealthLevel.INACTIVE, health.level, "state=$state")
            assertEquals(GuardHealthReason.BARRIER_FAILED, health.primaryReason, "state=$state")
        }
    }

    @Test
    fun `barrier states stuck registered report BARRIER_STUCK, not BARRIER_FAILED`() {
        // These states keep audio pinned to the speaker — the opposite symptom, so they
        // must not share the "routing could not be applied" wording.
        listOf(
            "registration_cleanup_failed",
            "unregistration_failed",
        ).forEach { state ->
            val health = evaluate(barrierState = state, barrierActive = true)

            assertEquals(GuardHealthLevel.INACTIVE, health.level, "state=$state")
            assertEquals(GuardHealthReason.BARRIER_STUCK, health.primaryReason, "state=$state")
        }
    }

    @Test
    fun `unresolved local mac makes protection inactive`() {
        val health = evaluate(localMacResolved = false)

        assertEquals(GuardHealthLevel.INACTIVE, health.level)
        assertEquals(GuardHealthReason.LOCAL_MAC_UNKNOWN, health.primaryReason)
    }

    @Test
    fun `missing native transport alone only degrades`() {
        val health = evaluate(nativeLeaseAvailable = false)

        assertEquals(GuardHealthLevel.DEGRADED, health.level)
        assertEquals(listOf(GuardHealthReason.NATIVE_TRANSPORT_MISSING), health.reasons)
    }

    @Test
    fun `blocking reasons outrank the native transport reason`() {
        val health = evaluate(
            barrierState = "permission_missing",
            barrierActive = false,
            nativeLeaseAvailable = false,
            localMacResolved = false,
        )

        assertEquals(GuardHealthLevel.INACTIVE, health.level)
        assertEquals(
            listOf(
                GuardHealthReason.PERMISSION_MISSING,
                GuardHealthReason.LOCAL_MAC_UNKNOWN,
                GuardHealthReason.NATIVE_TRANSPORT_MISSING,
            ),
            health.reasons,
        )
    }

    @Test
    fun `idle barrier state while enabled is healthy`() {
        // "idle" is the normal state whenever no remote host is using the AirPods.
        val health = evaluate(barrierState = "idle", barrierActive = false)

        assertEquals(GuardHealthLevel.ACTIVE, health.level)
    }

    @Test
    fun `inputs are carried through for the troubleshooting view`() {
        val health = evaluate(
            barrierState = "registered",
            barrierActive = true,
            nativeLeaseAvailable = true,
            localMacResolved = true,
            aacpConnected = true,
            guardState = RemoteDeviceAudioGuardState.REMOTE_ACTIVE,
        )

        assertEquals("registered", health.barrierState)
        assertEquals(true, health.barrierActive)
        assertEquals(true, health.nativeLeaseAvailable)
        assertEquals(true, health.localMacResolved)
        assertEquals(true, health.aacpConnected)
        assertEquals(RemoteDeviceAudioGuardState.REMOTE_ACTIVE, health.guardState)
    }
}
