package me.kavishdevar.librepods.audio

import org.junit.Assert.*
import org.junit.Test

class PreferredRouteLeaseTest {
    private class Platform : PreferredRoutePort {
        val routes = mutableMapOf<Int, List<RouteDevice>>()
        var saved = emptyMap<Int, List<RouteDevice>>()
        var rejectSet = false
        var rejectClear = false
        var lieAboutSet = false
        var rejectSave = false
        override fun read(strategy: Int) = routes[strategy].orEmpty()
        override fun write(strategy: Int, devices: List<RouteDevice>): Boolean {
            if (devices.isEmpty() && rejectClear || devices.isNotEmpty() && rejectSet) return false
            if (!lieAboutSet) routes[strategy] = devices
            return true
        }
        override fun save(originals: Map<Int, List<RouteDevice>>): Boolean {
            if (rejectSave) return false
            saved = originals.toMap()
            return true
        }
    }

    @Test fun `false return is a failure even without an exception`() {
        val platform = Platform().apply { rejectSet = true }
        assertFalse(PreferredRouteLease(platform).apply(listOf(1)))
    }
    @Test fun `successful call without matching observed route is a failure`() {
        val platform = Platform().apply { lieAboutSet = true }
        assertFalse(PreferredRouteLease(platform).apply(listOf(1)))
    }
    @Test fun `failed restoration keeps durable ownership for the next run`() {
        val platform = Platform()
        val lease = PreferredRouteLease(platform)
        assertTrue(lease.apply(listOf(1, 2)))
        platform.rejectClear = true
        assertFalse(lease.release())
        assertEquals(setOf(1, 2), platform.saved.keys)
        platform.rejectClear = false
        assertTrue(PreferredRouteLease(platform, platform.saved).release())
        assertTrue(platform.saved.isEmpty())
    }
    @Test fun `previous preferred devices are restored`() {
        val platform = Platform().apply { routes[1] = listOf(RouteDevice(8, "test")) }
        val lease = PreferredRouteLease(platform)
        assertTrue(lease.apply(listOf(1)))
        assertTrue(lease.release())
        assertEquals(listOf(RouteDevice(8, "test")), platform.routes[1])
    }
    @Test fun `new user output selection is not overwritten by cleanup`() {
        val platform = Platform()
        val lease = PreferredRouteLease(platform)
        lease.apply(listOf(1))
        platform.routes[1] = listOf(RouteDevice(22, "usb"))
        assertTrue(lease.release())
        assertEquals(listOf(RouteDevice(22, "usb")), platform.routes[1])
    }
    @Test fun `no route is changed before recovery can be persisted`() {
        val platform = Platform().apply { rejectSave = true }
        assertFalse(PreferredRouteLease(platform).apply(listOf(1)))
        assertTrue(platform.routes.isEmpty())
    }
}
