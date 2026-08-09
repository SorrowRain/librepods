package me.kavishdevar.librepods.heart

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HeadTrackingClaimControllerTest {
    @Test
    fun `all required request sources are represented`() {
        assertEquals(
            setOf(
                HeadTrackingRequestSource.BOOTSTRAP,
                HeadTrackingRequestSource.SPATIAL_AUDIO,
                HeadTrackingRequestSource.GESTURE,
                HeadTrackingRequestSource.UI_SCREEN,
            ),
            HeadTrackingRequestSource.entries.toSet(),
        )
    }

    @Test
    fun `first source creates one head tracking claim and later sources share it`() {
        val controller = HeadTrackingClaimController()
        val key = key(epoch = 1L)
        controller.installConnection(key)

        val requested = controller.request(key, HeadTrackingRequestSource.BOOTSTRAP)
        val owner = (requested.single() as HeadTrackingClaimAction.Request).owner
        assertEquals(SensorResource.HEAD_TRACKING, owner.resource)
        assertSame(key, owner.connection)
        assertEquals(1L, owner.acquisition)

        assertTrue(
            controller.request(key, HeadTrackingRequestSource.SPATIAL_AUDIO).isEmpty()
        )
        assertSame(owner, controller.currentOwner)
        assertEquals(
            setOf(
                HeadTrackingRequestSource.BOOTSTRAP,
                HeadTrackingRequestSource.SPATIAL_AUDIO,
            ),
            controller.desiredSources,
        )
    }

    @Test
    fun `duplicate request is idempotent`() {
        val controller = HeadTrackingClaimController()
        val key = key(epoch = 1L)
        controller.installConnection(key)
        controller.request(key, HeadTrackingRequestSource.GESTURE)
        val owner = controller.currentOwner

        assertTrue(controller.request(key, HeadTrackingRequestSource.GESTURE).isEmpty())
        assertSame(owner, controller.currentOwner)
        assertEquals(setOf(HeadTrackingRequestSource.GESTURE), controller.desiredSources)
    }

    @Test
    fun `only the final source release releases the shared claim`() {
        val controller = HeadTrackingClaimController()
        val key = key(epoch = 1L)
        controller.installConnection(key)
        val owner = requestOwner(controller, key, HeadTrackingRequestSource.BOOTSTRAP)
        controller.request(key, HeadTrackingRequestSource.UI_SCREEN)

        assertTrue(
            controller.release(key, HeadTrackingRequestSource.BOOTSTRAP).isEmpty()
        )
        assertSame(owner, controller.currentOwner)
        assertEquals(setOf(HeadTrackingRequestSource.UI_SCREEN), controller.desiredSources)

        assertEquals(
            listOf(HeadTrackingClaimAction.Release(owner)),
            controller.release(key, HeadTrackingRequestSource.UI_SCREEN),
        )
        assertNull(controller.currentOwner)
        assertFalse(controller.hasDesiredRequests)
    }

    @Test
    fun `release from a source without a request is rejected`() {
        val controller = HeadTrackingClaimController()
        val key = key(epoch = 1L)
        controller.installConnection(key)
        val owner = requestOwner(controller, key, HeadTrackingRequestSource.SPATIAL_AUDIO)

        assertTrue(controller.release(key, HeadTrackingRequestSource.GESTURE).isEmpty())
        assertSame(owner, controller.currentOwner)
        assertEquals(
            setOf(HeadTrackingRequestSource.SPATIAL_AUDIO),
            controller.desiredSources,
        )
    }

    @Test
    fun `reacquisition on one connection is monotonically newer`() {
        val controller = HeadTrackingClaimController()
        val key = key(epoch = 1L)
        controller.installConnection(key)
        val first = requestOwner(controller, key, HeadTrackingRequestSource.GESTURE)
        controller.release(key, HeadTrackingRequestSource.GESTURE)

        val second = requestOwner(controller, key, HeadTrackingRequestSource.GESTURE)
        controller.release(key, HeadTrackingRequestSource.GESTURE)
        val third = requestOwner(controller, key, HeadTrackingRequestSource.UI_SCREEN)

        assertEquals(listOf(1L, 2L, 3L), listOf(first.acquisition, second.acquisition, third.acquisition))
    }

    @Test
    fun `newer connection revokes every source and releases the old owner`() {
        val controller = HeadTrackingClaimController()
        val oldKey = key(epoch = 4L)
        controller.installConnection(oldKey)
        val oldOwner = requestOwner(controller, oldKey, HeadTrackingRequestSource.BOOTSTRAP)
        controller.request(oldKey, HeadTrackingRequestSource.SPATIAL_AUDIO)
        assertTrue(controller.onActiveOwnerChanged(oldOwner))

        val newKey = key(socket = Any(), generation = 5L, deviceKey = "new-device", epoch = 5L)
        assertEquals(
            listOf(HeadTrackingClaimAction.Release(oldOwner)),
            controller.installConnection(newKey),
        )
        assertFalse(controller.hasDesiredRequests)
        assertFalse(controller.isCurrentGrantActive)
        assertNull(controller.currentOwner)

        val newOwner = requestOwner(controller, newKey, HeadTrackingRequestSource.UI_SCREEN)
        assertTrue(newOwner.acquisition > oldOwner.acquisition)
    }

    @Test
    fun `older and conflicting same epoch connections are rejected`() {
        val controller = HeadTrackingClaimController()
        val currentKey = key(epoch = 7L)
        controller.installConnection(currentKey)
        val owner = requestOwner(controller, currentKey, HeadTrackingRequestSource.BOOTSTRAP)

        val older = key(socket = Any(), generation = 6L, deviceKey = "older", epoch = 6L)
        val sameEpoch = key(socket = Any(), generation = 8L, deviceKey = "other", epoch = 7L)
        assertTrue(controller.installConnection(older).isEmpty())
        assertTrue(controller.installConnection(sameEpoch).isEmpty())
        assertSame(owner, controller.currentOwner)
        assertEquals(setOf(HeadTrackingRequestSource.BOOTSTRAP), controller.desiredSources)
    }

    @Test
    fun `stale connection cannot request or release a current source`() {
        val controller = HeadTrackingClaimController()
        val staleKey = key(epoch = 1L)
        controller.installConnection(staleKey)
        val currentKey = key(socket = Any(), generation = 2L, deviceKey = "current", epoch = 2L)
        controller.installConnection(currentKey)
        val owner = requestOwner(controller, currentKey, HeadTrackingRequestSource.GESTURE)

        assertTrue(controller.request(staleKey, HeadTrackingRequestSource.UI_SCREEN).isEmpty())
        assertTrue(controller.release(staleKey, HeadTrackingRequestSource.GESTURE).isEmpty())
        assertSame(owner, controller.currentOwner)
        assertEquals(setOf(HeadTrackingRequestSource.GESTURE), controller.desiredSources)
    }

    @Test
    fun `grant is active only while the exact current claim owns the arbiter`() {
        val controller = HeadTrackingClaimController()
        val key = key(epoch = 1L)
        controller.installConnection(key)
        val headOwner = requestOwner(controller, key, HeadTrackingRequestSource.SPATIAL_AUDIO)
        val heartOwner = SensorResourceOwner(
            resource = SensorResource.HEART_RATE,
            connection = key,
            acquisition = 99L,
        )

        assertFalse(controller.isCurrentGrantActive)
        assertTrue(controller.onActiveOwnerChanged(headOwner))
        assertTrue(controller.isCurrentGrantActive)
        assertFalse(controller.onActiveOwnerChanged(heartOwner))
        assertFalse(controller.isCurrentGrantActive)
        assertTrue(controller.onActiveOwnerChanged(headOwner))

        controller.release(key, HeadTrackingRequestSource.SPATIAL_AUDIO)
        assertFalse(controller.isCurrentGrantActive)
    }

    @Test
    fun `stale grant cannot become active after connection replacement`() {
        val controller = HeadTrackingClaimController()
        val oldKey = key(epoch = 1L)
        controller.installConnection(oldKey)
        val oldOwner = requestOwner(controller, oldKey, HeadTrackingRequestSource.BOOTSTRAP)
        controller.onActiveOwnerChanged(oldOwner)

        val newKey = key(socket = Any(), generation = 2L, deviceKey = "replacement", epoch = 2L)
        controller.installConnection(newKey)
        assertFalse(controller.onActiveOwnerChanged(oldOwner))

        val newOwner = requestOwner(controller, newKey, HeadTrackingRequestSource.BOOTSTRAP)
        assertFalse(controller.isCurrentGrantActive)
        assertTrue(controller.onActiveOwnerChanged(newOwner))
    }

    @Test
    fun `disconnect revokes exact connection and rejects its later callbacks`() {
        val controller = HeadTrackingClaimController()
        val key = key(epoch = 1L)
        controller.installConnection(key)
        val owner = requestOwner(controller, key, HeadTrackingRequestSource.UI_SCREEN)
        controller.onActiveOwnerChanged(owner)

        assertEquals(
            listOf(HeadTrackingClaimAction.Release(owner)),
            controller.disconnect(key),
        )
        assertFalse(controller.hasDesiredRequests)
        assertFalse(controller.isCurrentGrantActive)
        assertTrue(controller.request(key, HeadTrackingRequestSource.UI_SCREEN).isEmpty())
        assertTrue(controller.release(key, HeadTrackingRequestSource.UI_SCREEN).isEmpty())
        assertTrue(controller.installConnection(key).isEmpty())
        assertNull(controller.currentOwner)
    }

    @Test
    fun `stale disconnect leaves the current connection untouched`() {
        val controller = HeadTrackingClaimController()
        val staleKey = key(epoch = 1L)
        controller.installConnection(staleKey)
        val currentKey = key(socket = Any(), generation = 2L, deviceKey = "current", epoch = 2L)
        controller.installConnection(currentKey)
        val owner = requestOwner(controller, currentKey, HeadTrackingRequestSource.GESTURE)
        controller.onActiveOwnerChanged(owner)

        assertTrue(controller.disconnect(staleKey).isEmpty())
        assertSame(owner, controller.currentOwner)
        assertTrue(controller.isCurrentGrantActive)
    }

    @Test
    fun `destroy releases once and permanently rejects later work`() {
        val controller = HeadTrackingClaimController()
        val key = key(epoch = 1L)
        controller.installConnection(key)
        val owner = requestOwner(controller, key, HeadTrackingRequestSource.BOOTSTRAP)

        assertEquals(
            listOf(HeadTrackingClaimAction.Release(owner)),
            controller.destroy(),
        )
        assertTrue(controller.destroy().isEmpty())
        assertTrue(controller.installConnection(key(socket = Any(), epoch = 2L)).isEmpty())
        assertTrue(controller.request(key, HeadTrackingRequestSource.BOOTSTRAP).isEmpty())
        assertFalse(controller.hasDesiredRequests)
        assertNull(controller.currentOwner)
    }

    @Test
    fun `public diagnostics do not reveal device keys`() {
        val controller = HeadTrackingClaimController()
        val secret = "sensitive-device-identifier"
        val key = key(deviceKey = secret, epoch = 1L)
        controller.installConnection(key)
        val action = controller.request(key, HeadTrackingRequestSource.BOOTSTRAP).single()

        assertFalse(action.toString().contains(secret))
        assertFalse(controller.toString().contains(secret))
    }

    private fun requestOwner(
        controller: HeadTrackingClaimController,
        key: HrmSessionKey,
        source: HeadTrackingRequestSource,
    ): SensorResourceOwner =
        (controller.request(key, source).single() as HeadTrackingClaimAction.Request).owner

    private fun key(
        socket: Any = Any(),
        generation: Long = 1L,
        deviceKey: String = "device-under-test",
        epoch: Long,
    ) = HrmSessionKey(socket, generation, deviceKey, epoch)
}
