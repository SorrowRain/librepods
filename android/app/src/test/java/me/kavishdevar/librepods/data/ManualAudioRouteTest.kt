package me.kavishdevar.librepods.data

import kotlin.test.Test
import kotlin.test.assertEquals

class ManualAudioRouteTest {
    @Test
    fun `disconnected control channel is unavailable`() {
        assertEquals(
            ManualAudioRouteState.UNAVAILABLE,
            ManualAudioRoutePolicy.resolveState(
                aacpConnected = false,
                audioSource = TakeoverAudioSource.LOCAL,
                releasedByPhone = false,
                transition = ManualAudioRouteTransition.IDLE,
            )
        )
    }

    @Test
    fun `confirmed audio source determines stable owner`() {
        assertEquals(
            ManualAudioRouteState.PHONE,
            stableState(TakeoverAudioSource.LOCAL)
        )
        assertEquals(
            ManualAudioRouteState.REMOTE,
            stableState(TakeoverAudioSource.REMOTE)
        )
    }

    @Test
    fun `explicit release distinguishes released route from unknown source`() {
        listOf(TakeoverAudioSource.NONE, TakeoverAudioSource.UNKNOWN).forEach { source ->
            assertEquals(
                ManualAudioRouteState.RELEASED,
                stableState(source, releasedByPhone = true)
            )
            assertEquals(
                ManualAudioRouteState.UNKNOWN,
                stableState(source, releasedByPhone = false)
            )
        }
    }

    @Test
    fun `transition remains pending until matching source is observed`() {
        assertEquals(
            ManualAudioRouteState.SWITCHING_TO_PHONE,
            ManualAudioRoutePolicy.resolveState(
                aacpConnected = true,
                audioSource = TakeoverAudioSource.REMOTE,
                releasedByPhone = false,
                transition = ManualAudioRouteTransition.SWITCHING_TO_PHONE,
            )
        )
        assertEquals(
            ManualAudioRouteState.PHONE,
            ManualAudioRoutePolicy.resolveState(
                aacpConnected = true,
                audioSource = TakeoverAudioSource.LOCAL,
                releasedByPhone = false,
                transition = ManualAudioRouteTransition.SWITCHING_TO_PHONE,
            )
        )
        assertEquals(
            ManualAudioRouteState.RELEASING_PHONE,
            ManualAudioRoutePolicy.resolveState(
                aacpConnected = true,
                audioSource = TakeoverAudioSource.LOCAL,
                releasedByPhone = true,
                transition = ManualAudioRouteTransition.RELEASING_PHONE,
            )
        )
        assertEquals(
            ManualAudioRouteState.REMOTE,
            ManualAudioRoutePolicy.resolveState(
                aacpConnected = true,
                audioSource = TakeoverAudioSource.REMOTE,
                releasedByPhone = true,
                transition = ManualAudioRouteTransition.RELEASING_PHONE,
            )
        )
    }

    @Test
    fun `only stable local and released states are actionable`() {
        assertEquals(
            ManualAudioRouteAction.RELEASE,
            ManualAudioRoutePolicy.actionFor(ManualAudioRouteState.PHONE)
        )
        assertEquals(
            ManualAudioRouteAction.TAKE_OVER,
            ManualAudioRoutePolicy.actionFor(ManualAudioRouteState.REMOTE)
        )
        assertEquals(
            ManualAudioRouteAction.TAKE_OVER,
            ManualAudioRoutePolicy.actionFor(ManualAudioRouteState.RELEASED)
        )
        listOf(
            ManualAudioRouteState.UNKNOWN,
            ManualAudioRouteState.UNAVAILABLE,
            ManualAudioRouteState.SWITCHING_TO_PHONE,
            ManualAudioRouteState.RELEASING_PHONE,
        ).forEach { state ->
            assertEquals(ManualAudioRouteAction.NONE, ManualAudioRoutePolicy.actionFor(state))
        }
    }

    private fun stableState(
        source: TakeoverAudioSource,
        releasedByPhone: Boolean = false,
    ): ManualAudioRouteState = ManualAudioRoutePolicy.resolveState(
        aacpConnected = true,
        audioSource = source,
        releasedByPhone = releasedByPhone,
        transition = ManualAudioRouteTransition.IDLE,
    )
}
