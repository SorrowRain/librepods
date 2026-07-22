package me.kavishdevar.librepods.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteDeviceAudioGuardTest {
    private val localMac = "11:22:33:44:55:66"
    private val remoteMac = "AA:BB:CC:DD:EE:FF"

    @Test
    fun `enabled connect protects before querying and cold probe fails open`() {
        val guard = enabledGuard()

        val connected = guard.dispatch(RemoteDeviceAudioGuardEvent.AacpConnected, nowMs = 100L)

        assertEquals(RemoteDeviceAudioGuardState.PROBING, connected.state)
        assertEquals(1_600L, connected.nextDeadlineMs)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.RefreshNativeLease(3_500L),
                RemoteDeviceAudioGuardAction.EnableBarrier,
                RemoteDeviceAudioGuardAction.RequestAudioSource,
            ),
            connected.actions,
        )

        val earlyTick = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 1_599L)
        assertEquals(RemoteDeviceAudioGuardState.PROBING, earlyTick.state)
        assertTrue(earlyTick.actions.isEmpty())

        val timeout = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 1_600L)
        assertEquals(RemoteDeviceAudioGuardState.IDLE, timeout.state)
        assertEquals(null, timeout.nextDeadlineMs)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.DisableBarrier,
                RemoteDeviceAudioGuardAction.ClearNativeLease,
            ),
            timeout.actions,
        )
    }

    @Test
    fun `local media or call releases protection immediately`() {
        RemoteDeviceAudioSourceType.MEDIA.and(RemoteDeviceAudioSourceType.CALL).forEach { type ->
            val guard = connectedGuard()
            val local = guard.dispatch(
                RemoteDeviceAudioGuardEvent.AudioSourceObserved(
                    localMac = localMac.lowercase(),
                    sourceMac = localMac,
                    type = type,
                ),
                nowMs = 20L,
            )

            assertEquals(RemoteDeviceAudioGuardState.LOCAL, local.state)
            assertFalse(local.current.barrierEnabled)
            assertEquals(
                listOf(
                    RemoteDeviceAudioGuardAction.DisableBarrier,
                    RemoteDeviceAudioGuardAction.ClearNativeLease,
                ),
                local.actions,
            )
        }
    }

    @Test
    fun `remote media or call keeps barrier and schedules refresh`() {
        listOf(
            RemoteDeviceAudioSourceType.MEDIA,
            RemoteDeviceAudioSourceType.CALL,
            RemoteDeviceAudioSourceType.ACTIVE_UNKNOWN,
        ).forEach { type ->
            val guard = connectedGuard()
            val remote = guard.dispatch(
                RemoteDeviceAudioGuardEvent.AudioSourceObserved(
                    localMac = localMac,
                    sourceMac = remoteMac,
                    type = type,
                ),
                nowMs = 200L,
            )

            assertEquals(RemoteDeviceAudioGuardState.REMOTE_ACTIVE, remote.state)
            assertTrue(remote.current.barrierEnabled)
            assertEquals(1_200L, remote.nextDeadlineMs)
            assertEquals(
                listOf(
                    RemoteDeviceAudioGuardAction.RefreshNativeLease(3_500L),
                    RemoteDeviceAudioGuardAction.EnableBarrier,
                ),
                remote.actions,
            )

            val refresh = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 1_200L)
            assertEquals(RemoteDeviceAudioGuardState.REMOTE_GRACE, refresh.state)
            assertEquals(3_700L, refresh.nextDeadlineMs)
            assertEquals(
                listOf(
                    RemoteDeviceAudioGuardAction.RefreshNativeLease(3_500L),
                    RemoteDeviceAudioGuardAction.EnableBarrier,
                    RemoteDeviceAudioGuardAction.RequestAudioSource,
                ),
                refresh.actions,
            )
        }
    }

    @Test
    fun `any valid non-local host is protected without device type knowledge`() {
        listOf(
            "10:20:30:40:50:60",
            "12:34:56:78:9A:BC",
            "FE:DC:BA:98:76:54",
        ).forEachIndexed { index, sourceAddress ->
            val guard = connectedGuard()

            val transition = guard.dispatch(
                RemoteDeviceAudioGuardEvent.AudioSourceObserved(
                    localMac = localMac,
                    sourceMac = sourceAddress,
                    type = RemoteDeviceAudioSourceType.ACTIVE_UNKNOWN,
                ),
                nowMs = 200L + index,
            )

            assertEquals(RemoteDeviceAudioGuardState.REMOTE_ACTIVE, transition.state)
            assertTrue(transition.current.barrierEnabled)
        }
    }

    @Test
    fun `remote evidence expires when refresh query is not answered`() {
        val guard = connectedGuard()
        guard.dispatch(remoteMedia(), nowMs = 100L)

        val awaitingRefresh = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 1_100L)
        assertEquals(RemoteDeviceAudioGuardState.REMOTE_GRACE, awaitingRefresh.state)
        assertEquals(3_600L, awaitingRefresh.nextDeadlineMs)

        val released = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 3_600L)
        assertEquals(RemoteDeviceAudioGuardState.IDLE, released.state)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.DisableBarrier,
                RemoteDeviceAudioGuardAction.ClearNativeLease,
            ),
            released.actions,
        )
    }

    @Test
    fun `remote none enters one grace window and then releases`() {
        val guard = connectedGuard()
        guard.dispatch(remoteMedia(), nowMs = 100L)

        val grace = guard.dispatch(
            RemoteDeviceAudioGuardEvent.AudioSourceObserved(
                localMac = localMac,
                sourceMac = null,
                type = RemoteDeviceAudioSourceType.NONE,
            ),
            nowMs = 300L,
        )

        assertEquals(RemoteDeviceAudioGuardState.REMOTE_GRACE, grace.state)
        assertEquals(2_800L, grace.nextDeadlineMs)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.RefreshNativeLease(3_500L),
                RemoteDeviceAudioGuardAction.RequestAudioSource,
            ),
            grace.actions,
        )

        val repeatedNone = guard.dispatch(
            RemoteDeviceAudioGuardEvent.AudioSourceObserved(
                localMac = localMac,
                sourceMac = null,
                type = RemoteDeviceAudioSourceType.NONE,
            ),
            nowMs = 1_000L,
        )
        assertEquals(2_800L, repeatedNone.nextDeadlineMs)
        assertTrue(repeatedNone.actions.isEmpty())

        val released = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 2_800L)
        assertEquals(RemoteDeviceAudioGuardState.IDLE, released.state)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.DisableBarrier,
                RemoteDeviceAudioGuardAction.ClearNativeLease,
            ),
            released.actions,
        )
    }

    @Test
    fun `remote activity returning during grace cancels release`() {
        val guard = connectedGuard()
        guard.dispatch(remoteMedia(), nowMs = 100L)
        guard.dispatch(
            RemoteDeviceAudioGuardEvent.AudioSourceObserved(
                localMac = localMac,
                sourceMac = null,
                type = RemoteDeviceAudioSourceType.NONE,
            ),
            nowMs = 200L,
        )

        val recovered = guard.dispatch(remoteMedia(), nowMs = 500L)

        assertEquals(RemoteDeviceAudioGuardState.REMOTE_ACTIVE, recovered.state)
        assertEquals(1_500L, recovered.nextDeadlineMs)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.RefreshNativeLease(3_500L),
                RemoteDeviceAudioGuardAction.EnableBarrier,
            ),
            recovered.actions,
        )
        val oldGraceDeadline = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 2_700L)
        assertEquals(RemoteDeviceAudioGuardState.REMOTE_GRACE, oldGraceDeadline.state)
        assertTrue(oldGraceDeadline.current.barrierEnabled)
    }

    @Test
    fun `unknown source only enters grace after remote evidence`() {
        val guard = connectedGuard()
        val coldUnknown = guard.dispatch(
            RemoteDeviceAudioGuardEvent.AudioSourceObserved(
                localMac = localMac,
                sourceMac = null,
                type = RemoteDeviceAudioSourceType.UNKNOWN,
            ),
            nowMs = 100L,
        )
        assertEquals(RemoteDeviceAudioGuardState.PROBING, coldUnknown.state)
        assertTrue(coldUnknown.actions.isEmpty())

        guard.dispatch(remoteMedia(), nowMs = 200L)
        val remoteUnknown = guard.dispatch(
            RemoteDeviceAudioGuardEvent.AudioSourceObserved(
                localMac = localMac,
                sourceMac = null,
                type = RemoteDeviceAudioSourceType.UNKNOWN,
            ),
            nowMs = 300L,
        )
        assertEquals(RemoteDeviceAudioGuardState.REMOTE_GRACE, remoteUnknown.state)
        assertEquals(2_800L, remoteUnknown.nextDeadlineMs)
    }

    @Test
    fun `remote streaming yes is early evidence and no only requests source`() {
        val guard = connectedGuard()

        val yes = guard.dispatch(
            RemoteDeviceAudioGuardEvent.RemoteStreamingChanged(
                localMac = localMac,
                sourceMac = remoteMac,
                isStreaming = true,
            ),
            nowMs = 50L,
        )
        assertEquals(RemoteDeviceAudioGuardState.REMOTE_ACTIVE, yes.state)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.RefreshNativeLease(3_500L),
                RemoteDeviceAudioGuardAction.EnableBarrier,
                RemoteDeviceAudioGuardAction.RequestAudioSource,
            ),
            yes.actions,
        )

        val no = guard.dispatch(
            RemoteDeviceAudioGuardEvent.RemoteStreamingChanged(
                localMac = localMac,
                sourceMac = remoteMac,
                isStreaming = false,
            ),
            nowMs = 60L,
        )
        assertEquals(RemoteDeviceAudioGuardState.REMOTE_ACTIVE, no.state)
        assertEquals(1_050L, no.nextDeadlineMs)
        assertEquals(
            listOf(RemoteDeviceAudioGuardAction.RequestAudioSource),
            no.actions,
        )
    }

    @Test
    fun `streaming hints require valid non-local sender identity`() {
        listOf(
            null,
            "",
            "02:00:00:00:00:00",
            localMac.lowercase(),
        ).forEach { sender ->
            val guard = connectedGuard()

            val transition = guard.dispatch(
                RemoteDeviceAudioGuardEvent.RemoteStreamingChanged(
                    localMac = localMac,
                    sourceMac = sender,
                    isStreaming = true,
                ),
                nowMs = 50L,
            )

            assertEquals(RemoteDeviceAudioGuardState.PROBING, transition.state)
            assertTrue(transition.actions.isEmpty())
        }
    }

    @Test
    fun `remote activity observed from idle restores protection`() {
        val guard = connectedGuard()
        guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 1_500L)

        val remote = guard.dispatch(remoteMedia(), nowMs = 2_000L)

        assertEquals(RemoteDeviceAudioGuardState.REMOTE_ACTIVE, remote.state)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.RefreshNativeLease(3_500L),
                RemoteDeviceAudioGuardAction.EnableBarrier,
            ),
            remote.actions,
        )
    }

    @Test
    fun `disconnect and disable release in policy then native order`() {
        val disconnectedGuard = connectedGuard()
        val disconnected = disconnectedGuard.dispatch(
            RemoteDeviceAudioGuardEvent.AacpDisconnected,
            nowMs = 10L,
        )
        assertEquals(RemoteDeviceAudioGuardState.OFF, disconnected.state)
        assertFalse(disconnected.current.aacpConnected)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.DisableBarrier,
                RemoteDeviceAudioGuardAction.ClearNativeLease,
            ),
            disconnected.actions,
        )

        val disabledGuard = connectedGuard()
        val disabled = disabledGuard.dispatch(
            RemoteDeviceAudioGuardEvent.EnabledChanged(enabled = false),
            nowMs = 10L,
        )
        assertEquals(RemoteDeviceAudioGuardState.OFF, disabled.state)
        assertFalse(disabled.current.enabled)
        assertTrue(disabled.current.aacpConnected)
        assertEquals(disconnected.actions, disabled.actions)
    }

    @Test
    fun `events while disabled or disconnected do not protect`() {
        val disabled = RemoteDeviceAudioGuard()
        assertTrue(
            disabled.dispatch(RemoteDeviceAudioGuardEvent.AacpConnected, nowMs = 0L)
                .actions.isEmpty()
        )
        assertEquals(RemoteDeviceAudioGuardState.OFF, disabled.snapshot.state)

        val enabledDisconnected = enabledGuard()
        val ignored = enabledDisconnected.dispatch(remoteMedia(), nowMs = 100L)
        assertEquals(RemoteDeviceAudioGuardState.OFF, ignored.state)
        assertTrue(ignored.actions.isEmpty())
    }

    @Test
    fun `enabling an existing AACP connection starts a fresh probe`() {
        val guard = RemoteDeviceAudioGuard()
        guard.dispatch(RemoteDeviceAudioGuardEvent.AacpConnected, nowMs = 0L)

        val enabled = guard.dispatch(
            RemoteDeviceAudioGuardEvent.EnabledChanged(enabled = true),
            nowMs = 25L,
        )

        assertEquals(RemoteDeviceAudioGuardState.PROBING, enabled.state)
        assertEquals(1_525L, enabled.nextDeadlineMs)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.RefreshNativeLease(3_500L),
                RemoteDeviceAudioGuardAction.EnableBarrier,
                RemoteDeviceAudioGuardAction.RequestAudioSource,
            ),
            enabled.actions,
        )
    }

    private fun enabledGuard(): RemoteDeviceAudioGuard = RemoteDeviceAudioGuard().also {
        it.dispatch(RemoteDeviceAudioGuardEvent.EnabledChanged(enabled = true), nowMs = 0L)
    }

    private fun connectedGuard(): RemoteDeviceAudioGuard = enabledGuard().also {
        it.dispatch(RemoteDeviceAudioGuardEvent.AacpConnected, nowMs = 0L)
    }

    private fun remoteMedia() = RemoteDeviceAudioGuardEvent.AudioSourceObserved(
        localMac = localMac,
        sourceMac = remoteMac,
        type = RemoteDeviceAudioSourceType.MEDIA,
    )

    private fun <T> T.and(other: T): List<T> = listOf(this, other)
}
