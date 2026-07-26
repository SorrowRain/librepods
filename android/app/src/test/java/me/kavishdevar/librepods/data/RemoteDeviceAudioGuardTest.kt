package me.kavishdevar.librepods.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RemoteDeviceAudioGuardTest {
    private val localMac = "11:22:33:44:55:66"
    private val remoteMac = "AA:BB:CC:DD:EE:FF"

    @Test
    fun `enabled connect protects, retries probe, then fails open into polling idle`() {
        val guard = enabledGuard()

        val connected = guard.dispatch(RemoteDeviceAudioGuardEvent.AacpConnected, nowMs = 100L)

        assertEquals(RemoteDeviceAudioGuardState.PROBING, connected.state)
        assertEquals(850L, connected.nextDeadlineMs)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.RefreshNativeLease(8_000L),
                RemoteDeviceAudioGuardAction.EnableBarrier,
                RemoteDeviceAudioGuardAction.RequestAudioSource,
            ),
            connected.actions,
        )

        val earlyTick = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 849L)
        assertEquals(RemoteDeviceAudioGuardState.PROBING, earlyTick.state)
        assertTrue(earlyTick.actions.isEmpty())

        val retry = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 850L)
        assertEquals(RemoteDeviceAudioGuardState.PROBING, retry.state)
        assertEquals(1_600L, retry.nextDeadlineMs)
        assertEquals(
            listOf(RemoteDeviceAudioGuardAction.RequestAudioSource),
            retry.actions,
        )

        val timeout = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 1_600L)
        assertEquals(RemoteDeviceAudioGuardState.IDLE, timeout.state)
        assertEquals(6_600L, timeout.nextDeadlineMs)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.DisableBarrier,
                RemoteDeviceAudioGuardAction.ClearNativeLease,
            ),
            timeout.actions,
        )

        val idlePoll = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 6_600L)
        assertEquals(RemoteDeviceAudioGuardState.IDLE, idlePoll.state)
        assertEquals(11_600L, idlePoll.nextDeadlineMs)
        assertEquals(
            listOf(RemoteDeviceAudioGuardAction.RequestAudioSource),
            idlePoll.actions,
        )
    }

    @Test
    fun `local media or call releases protection immediately and keeps polling`() {
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
            assertEquals(5_020L, local.nextDeadlineMs)
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
                    RemoteDeviceAudioGuardAction.RefreshNativeLease(8_000L),
                    RemoteDeviceAudioGuardAction.EnableBarrier,
                ),
                remote.actions,
            )

            val refresh = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 1_200L)
            assertEquals(RemoteDeviceAudioGuardState.REMOTE_GRACE, refresh.state)
            assertEquals(2_200L, refresh.nextDeadlineMs)
            assertEquals(
                listOf(
                    RemoteDeviceAudioGuardAction.RefreshNativeLease(8_000L),
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
    fun `unanswered grace retries queries then releases into fast re-poll`() {
        val guard = connectedGuard()
        guard.dispatch(remoteMedia(), nowMs = 100L)

        val awaitingRefresh = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 1_100L)
        assertEquals(RemoteDeviceAudioGuardState.REMOTE_GRACE, awaitingRefresh.state)
        assertEquals(2_100L, awaitingRefresh.nextDeadlineMs)

        val firstRetry = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 2_100L)
        assertEquals(RemoteDeviceAudioGuardState.REMOTE_GRACE, firstRetry.state)
        assertEquals(3_100L, firstRetry.nextDeadlineMs)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.RefreshNativeLease(8_000L),
                RemoteDeviceAudioGuardAction.RequestAudioSource,
            ),
            firstRetry.actions,
        )

        val secondRetry = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 3_100L)
        assertEquals(RemoteDeviceAudioGuardState.REMOTE_GRACE, secondRetry.state)
        assertEquals(3_600L, secondRetry.nextDeadlineMs)

        val released = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 3_600L)
        assertEquals(RemoteDeviceAudioGuardState.IDLE, released.state)
        assertEquals(4_600L, released.nextDeadlineMs)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.DisableBarrier,
                RemoteDeviceAudioGuardAction.ClearNativeLease,
            ),
            released.actions,
        )

        val rePoll = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 4_600L)
        assertEquals(
            listOf(RemoteDeviceAudioGuardAction.RequestAudioSource),
            rePoll.actions,
        )

        val recovered = guard.dispatch(remoteMedia(), nowMs = 4_700L)
        assertEquals(RemoteDeviceAudioGuardState.REMOTE_ACTIVE, recovered.state)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.RefreshNativeLease(8_000L),
                RemoteDeviceAudioGuardAction.EnableBarrier,
            ),
            recovered.actions,
        )
    }

    @Test
    fun `remote none enters grace with retries and then releases`() {
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
        assertEquals(1_300L, grace.nextDeadlineMs)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.RefreshNativeLease(8_000L),
                RemoteDeviceAudioGuardAction.EnableBarrier,
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
        assertEquals(1_300L, repeatedNone.nextDeadlineMs)
        assertTrue(repeatedNone.actions.isEmpty())

        guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 1_300L)
        guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 2_300L)
        val released = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 2_800L)
        assertEquals(RemoteDeviceAudioGuardState.IDLE, released.state)
        assertEquals(3_800L, released.nextDeadlineMs)
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
                RemoteDeviceAudioGuardAction.RefreshNativeLease(8_000L),
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
        assertEquals(1_300L, remoteUnknown.nextDeadlineMs)
    }

    @Test
    fun `none during probing releases early into polling idle`() {
        val guard = connectedGuard()

        val resolved = guard.dispatch(
            RemoteDeviceAudioGuardEvent.AudioSourceObserved(
                localMac = localMac,
                sourceMac = null,
                type = RemoteDeviceAudioSourceType.NONE,
            ),
            nowMs = 200L,
        )

        assertEquals(RemoteDeviceAudioGuardState.IDLE, resolved.state)
        assertEquals(5_200L, resolved.nextDeadlineMs)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.DisableBarrier,
                RemoteDeviceAudioGuardAction.ClearNativeLease,
            ),
            resolved.actions,
        )
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
                RemoteDeviceAudioGuardAction.RefreshNativeLease(8_000L),
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
        assertEquals(RemoteDeviceAudioGuardState.IDLE, guard.snapshot.state)

        val remote = guard.dispatch(remoteMedia(), nowMs = 2_000L)

        assertEquals(RemoteDeviceAudioGuardState.REMOTE_ACTIVE, remote.state)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.RefreshNativeLease(8_000L),
                RemoteDeviceAudioGuardAction.EnableBarrier,
            ),
            remote.actions,
        )
    }

    @Test
    fun `sticky remote fails probe closed after quick reconnect`() {
        val guard = connectedGuard()
        guard.dispatch(remoteMedia(), nowMs = 100L)

        val disconnected = guard.dispatch(RemoteDeviceAudioGuardEvent.AacpDisconnected, nowMs = 200L)
        assertEquals(RemoteDeviceAudioGuardState.OFF, disconnected.state)
        assertEquals(30_200L, disconnected.current.recentRemoteUntilMs)

        val reconnected = guard.dispatch(RemoteDeviceAudioGuardEvent.AacpConnected, nowMs = 1_000L)
        assertEquals(RemoteDeviceAudioGuardState.PROBING, reconnected.state)
        assertEquals(1_750L, reconnected.nextDeadlineMs)

        guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 1_750L)
        val failedClosed = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 2_500L)

        assertEquals(RemoteDeviceAudioGuardState.REMOTE_GRACE, failedClosed.state)
        assertTrue(failedClosed.current.barrierEnabled)
        assertEquals(3_500L, failedClosed.nextDeadlineMs)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.RefreshNativeLease(8_000L),
                RemoteDeviceAudioGuardAction.EnableBarrier,
                RemoteDeviceAudioGuardAction.RequestAudioSource,
            ),
            failedClosed.actions,
        )
    }

    @Test
    fun `sticky remote expires after its window`() {
        val guard = connectedGuard()
        guard.dispatch(remoteMedia(), nowMs = 100L)
        guard.dispatch(RemoteDeviceAudioGuardEvent.AacpDisconnected, nowMs = 200L)

        guard.dispatch(RemoteDeviceAudioGuardEvent.AacpConnected, nowMs = 40_000L)
        guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 40_750L)
        val timedOut = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 41_500L)

        assertEquals(RemoteDeviceAudioGuardState.IDLE, timedOut.state)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.DisableBarrier,
                RemoteDeviceAudioGuardAction.ClearNativeLease,
            ),
            timedOut.actions,
        )
    }

    @Test
    fun `disable clears sticky memory`() {
        val guard = connectedGuard()
        guard.dispatch(remoteMedia(), nowMs = 100L)
        guard.dispatch(RemoteDeviceAudioGuardEvent.AacpDisconnected, nowMs = 200L)

        guard.dispatch(RemoteDeviceAudioGuardEvent.EnabledChanged(enabled = false), nowMs = 300L)
        assertEquals(null, guard.snapshot.recentRemoteUntilMs)

        guard.dispatch(RemoteDeviceAudioGuardEvent.EnabledChanged(enabled = true), nowMs = 400L)
        guard.dispatch(RemoteDeviceAudioGuardEvent.AacpConnected, nowMs = 500L)
        guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 1_250L)
        val timedOut = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 2_000L)

        assertEquals(RemoteDeviceAudioGuardState.IDLE, timedOut.state)
    }

    @Test
    fun `overdue deadline settles before a non-tick event`() {
        val guard = connectedGuard()
        guard.dispatch(remoteMedia(), nowMs = 100L)

        val lateObservation = guard.dispatch(
            RemoteDeviceAudioGuardEvent.AudioSourceObserved(
                localMac = localMac,
                sourceMac = null,
                type = RemoteDeviceAudioSourceType.NONE,
            ),
            nowMs = 5_000L,
        )

        assertEquals(RemoteDeviceAudioGuardState.REMOTE_GRACE, lateObservation.state)
        assertEquals(6_000L, lateObservation.nextDeadlineMs)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.RefreshNativeLease(8_000L),
                RemoteDeviceAudioGuardAction.EnableBarrier,
                RemoteDeviceAudioGuardAction.RequestAudioSource,
            ),
            lateObservation.actions,
        )
    }

    @Test
    fun `local polls and detects remote takeover`() {
        val guard = connectedGuard()
        guard.dispatch(
            RemoteDeviceAudioGuardEvent.AudioSourceObserved(
                localMac = localMac,
                sourceMac = localMac,
                type = RemoteDeviceAudioSourceType.MEDIA,
            ),
            nowMs = 20L,
        )

        val poll = guard.dispatch(RemoteDeviceAudioGuardEvent.Tick, nowMs = 5_020L)
        assertEquals(RemoteDeviceAudioGuardState.LOCAL, poll.state)
        assertEquals(10_020L, poll.nextDeadlineMs)
        assertEquals(
            listOf(RemoteDeviceAudioGuardAction.RequestAudioSource),
            poll.actions,
        )

        val takeover = guard.dispatch(remoteMedia(), nowMs = 10_100L)
        assertEquals(RemoteDeviceAudioGuardState.REMOTE_ACTIVE, takeover.state)
        assertEquals(11_100L, takeover.nextDeadlineMs)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.RequestAudioSource,
                RemoteDeviceAudioGuardAction.RefreshNativeLease(8_000L),
                RemoteDeviceAudioGuardAction.EnableBarrier,
            ),
            takeover.actions,
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
        assertEquals(null, disconnected.current.recentRemoteUntilMs)
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
        assertEquals(775L, enabled.nextDeadlineMs)
        assertEquals(
            listOf(
                RemoteDeviceAudioGuardAction.RefreshNativeLease(8_000L),
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
