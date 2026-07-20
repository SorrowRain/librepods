package me.kavishdevar.librepods.data

import kotlin.test.Test
import kotlin.test.assertEquals

class TakeoverRouteGateTest {
    @Test
    fun `route is ready only after every independent signal is confirmed`() {
        val cases = mapOf(
            TakeoverRouteSignals(false, TakeoverAudioSource.LOCAL, true) to
                TakeoverRouteReadiness.WAITING_FOR_OWNERSHIP,
            TakeoverRouteSignals(true, TakeoverAudioSource.UNKNOWN, true) to
                TakeoverRouteReadiness.WAITING_FOR_LOCAL_SOURCE,
            TakeoverRouteSignals(true, TakeoverAudioSource.NONE, true) to
                TakeoverRouteReadiness.WAITING_FOR_LOCAL_SOURCE,
            TakeoverRouteSignals(true, TakeoverAudioSource.REMOTE, true) to
                TakeoverRouteReadiness.WAITING_FOR_LOCAL_SOURCE,
            TakeoverRouteSignals(true, TakeoverAudioSource.LOCAL, false) to
                TakeoverRouteReadiness.WAITING_FOR_A2DP,
            TakeoverRouteSignals(true, TakeoverAudioSource.LOCAL, true) to
                TakeoverRouteReadiness.READY
        )

        cases.forEach { (signals, expected) ->
            assertEquals(expected, TakeoverRouteGate.evaluate(signals), signals.toString())
        }
    }
}
