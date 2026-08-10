package me.kavishdevar.librepods.bluetooth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingSocketWatchdogTest {
    @Test
    fun `stale timeout cannot claim a replacement socket`() {
        val watchdog = PendingSocketWatchdog<Any>()
        val first = Any()
        val replacement = Any()

        watchdog.arm(first)
        watchdog.arm(replacement)

        assertFalse(watchdog.expireIfCurrent(first))
        assertTrue(watchdog.isPending(replacement))
        assertTrue(watchdog.expireIfCurrent(replacement))
        assertFalse(watchdog.isPending(replacement))
    }

    @Test
    fun `cancelling an old owner cannot cancel a current socket`() {
        val watchdog = PendingSocketWatchdog<Any>()
        val first = Any()
        val replacement = Any()

        watchdog.arm(first)
        watchdog.arm(replacement)

        assertFalse(watchdog.cancel(first))
        assertTrue(watchdog.isPending(replacement))
        assertTrue(watchdog.cancel(replacement))
        assertFalse(watchdog.expireIfCurrent(replacement))
    }
}
