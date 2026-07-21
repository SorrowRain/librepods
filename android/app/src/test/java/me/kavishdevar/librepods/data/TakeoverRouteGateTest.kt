package me.kavishdevar.librepods.data

import me.kavishdevar.librepods.bluetooth.AacpSocketLeaseGate
import me.kavishdevar.librepods.bluetooth.TakeoverPacketSequenceMode
import me.kavishdevar.librepods.bluetooth.TakeoverPacketSequencePlan
import me.kavishdevar.librepods.bluetooth.TakeoverPacketStage
import kotlin.test.Test
import kotlin.test.assertEquals

class TakeoverRouteGateTest {
    @Test
    fun `local audio route is usable even while ownership bit is stale`() {
        assertEquals(
            true,
            TakeoverRouteGate.isLocalAudioAlreadyRouted(
                TakeoverRouteSignals(
                    ownsConnection = false,
                    audioSource = TakeoverAudioSource.LOCAL,
                    a2dpConnected = true,
                    targetAudioActive = true,
                )
            )
        )
    }

    @Test
    fun `remote or incomplete route never bypasses ownership takeover`() {
        listOf(
            TakeoverRouteSignals(false, TakeoverAudioSource.REMOTE, true),
            TakeoverRouteSignals(false, TakeoverAudioSource.LOCAL, false),
            TakeoverRouteSignals(false, TakeoverAudioSource.LOCAL, true),
            TakeoverRouteSignals(true, TakeoverAudioSource.UNKNOWN, true),
            TakeoverRouteSignals(true, TakeoverAudioSource.NONE, true)
        ).forEach { signals ->
            assertEquals(false, TakeoverRouteGate.isLocalAudioAlreadyRouted(signals), signals.toString())
        }
    }

    @Test
    fun `confirmed owner still repairs a concrete non target output`() {
        assertEquals(
            true,
            TakeoverRouteGate.shouldRequestTakeover(
                TakeoverRouteSignals(
                    ownsConnection = true,
                    audioSource = TakeoverAudioSource.LOCAL,
                    a2dpConnected = true,
                    targetAudioActive = false,
                )
            )
        )
        assertEquals(
            false,
            TakeoverRouteGate.shouldRequestTakeover(
                TakeoverRouteSignals(
                    ownsConnection = false,
                    audioSource = TakeoverAudioSource.LOCAL,
                    a2dpConnected = true,
                    targetAudioActive = true,
                )
            )
        )
    }

    @Test
    fun `route is ready only after every independent signal is confirmed`() {
        val cases = mapOf(
            TakeoverRouteSignals(false, TakeoverAudioSource.LOCAL, true) to
                TakeoverRouteReadiness.READY,
            TakeoverRouteSignals(true, TakeoverAudioSource.UNKNOWN, true) to
                TakeoverRouteReadiness.WAITING_FOR_LOCAL_SOURCE,
            TakeoverRouteSignals(true, TakeoverAudioSource.NONE, true) to
                TakeoverRouteReadiness.WAITING_FOR_LOCAL_SOURCE,
            TakeoverRouteSignals(true, TakeoverAudioSource.REMOTE, true) to
                TakeoverRouteReadiness.WAITING_FOR_LOCAL_SOURCE,
            TakeoverRouteSignals(true, TakeoverAudioSource.LOCAL, false) to
                TakeoverRouteReadiness.WAITING_FOR_A2DP,
            TakeoverRouteSignals(true, TakeoverAudioSource.LOCAL, true) to
                TakeoverRouteReadiness.READY,
            TakeoverRouteSignals(
                ownsConnection = true,
                audioSource = TakeoverAudioSource.LOCAL,
                a2dpConnected = true,
                targetAudioActive = false,
                requireTargetAudioActive = true,
            ) to TakeoverRouteReadiness.WAITING_FOR_TARGET_AUDIO,
            TakeoverRouteSignals(
                ownsConnection = true,
                audioSource = TakeoverAudioSource.LOCAL,
                a2dpConnected = true,
                targetAudioActive = true,
                requireTargetAudioActive = true,
            ) to TakeoverRouteReadiness.READY,
        )

        cases.forEach { (signals, expected) ->
            assertEquals(expected, TakeoverRouteGate.evaluate(signals), signals.toString())
        }
    }

    @Test
    fun `remote handoff needs a complete request and fresh ownership and source evidence`() {
        val base = TakeoverRouteSignals(
            ownsConnection = false,
            audioSource = TakeoverAudioSource.LOCAL,
            a2dpConnected = true,
            targetAudioActive = true,
            requireOwnership = true,
            requireFreshLocalSource = true,
            freshLocalSourceConfirmed = true,
            requireCompletedRequest = true,
            requestCompleted = true,
        )

        assertEquals(
            TakeoverRouteReadiness.WAITING_FOR_OWNERSHIP,
            TakeoverRouteGate.evaluate(base),
        )
        assertEquals(
            TakeoverRouteReadiness.WAITING_FOR_LOCAL_SOURCE,
            TakeoverRouteGate.evaluate(
                base.copy(
                    ownsConnection = true,
                    freshLocalSourceConfirmed = false,
                )
            ),
        )
        assertEquals(
            TakeoverRouteReadiness.WAITING_FOR_REQUEST,
            TakeoverRouteGate.evaluate(
                base.copy(
                    ownsConnection = true,
                    requestCompleted = false,
                )
            ),
        )
        assertEquals(
            TakeoverRouteReadiness.READY,
            TakeoverRouteGate.evaluate(base.copy(ownsConnection = true)),
        )
    }

    @Test
    fun `target output alone cannot complete a remote handoff`() {
        val targetRouteObservedFirst = TakeoverRouteSignals(
            ownsConnection = false,
            audioSource = TakeoverAudioSource.REMOTE,
            a2dpConnected = true,
            targetAudioActive = true,
            requireTargetAudioActive = true,
            requireOwnership = true,
            requireFreshLocalSource = true,
            freshLocalSourceConfirmed = false,
            requireCompletedRequest = true,
            requestCompleted = true,
        )

        assertEquals(
            TakeoverRouteReadiness.WAITING_FOR_LOCAL_SOURCE,
            TakeoverRouteGate.evaluate(targetRouteObservedFirst),
        )
        assertEquals(
            TakeoverRouteReadiness.WAITING_FOR_LOCAL_SOURCE,
            TakeoverRouteGate.evaluate(
                targetRouteObservedFirst.copy(
                    ownsConnection = true,
                    audioSource = TakeoverAudioSource.LOCAL,
                )
            ),
        )
        assertEquals(
            TakeoverRouteReadiness.READY,
            TakeoverRouteGate.evaluate(
                targetRouteObservedFirst.copy(
                    ownsConnection = true,
                    audioSource = TakeoverAudioSource.LOCAL,
                    freshLocalSourceConfirmed = true,
                )
            ),
        )
    }

    @Test
    fun `takeover completion waits for both route and requested pause`() {
        assertEquals(
            false,
            TakeoverCompletionGate.canComplete(
                routeReady = true,
                pauseRequired = true,
                pauseApplied = false,
            ),
        )
        assertEquals(
            false,
            TakeoverCompletionGate.canComplete(
                routeReady = false,
                pauseRequired = true,
                pauseApplied = true,
            ),
        )
        assertEquals(
            true,
            TakeoverCompletionGate.canComplete(
                routeReady = true,
                pauseRequired = true,
                pauseApplied = true,
            ),
        )
        assertEquals(
            true,
            TakeoverCompletionGate.canComplete(
                routeReady = true,
                pauseRequired = false,
                pauseApplied = false,
            ),
        )
    }

    @Test
    fun `non target callbacks dispatch only one pause while the request is outstanding`() {
        val started = TakeoverCompletionGate.start(pauseRequired = true)
        val firstPause = TakeoverCompletionGate.requestPause(started)
        val repeatedCallbackPause = TakeoverCompletionGate.requestPause(firstPause.state)
        val stillPlayingCallbackPause = TakeoverCompletionGate.requestPause(
            repeatedCallbackPause.state
        )

        assertEquals(true, firstPause.shouldDispatch)
        assertEquals(false, repeatedCallbackPause.shouldDispatch)
        assertEquals(false, stillPlayingCallbackPause.shouldDispatch)
        assertEquals(true, stillPlayingCallbackPause.state.pauseOutstanding)
        assertEquals(false, TakeoverCompletionGate.canComplete(stillPlayingCallbackPause.state))
    }

    @Test
    fun `new non target start after confirmed pause opens exactly one new pause barrier`() {
        val firstPause = TakeoverCompletionGate.requestPause(
            TakeoverCompletionGate.start(pauseRequired = true)
        )
        val firstPauseApplied = TakeoverCompletionGate.confirmPauseApplied(firstPause.state)
        val ordinaryCallbackAfterAppliedPause = TakeoverCompletionGate.requestPause(
            firstPauseApplied
        )
        val resumedOffTarget = TakeoverCompletionGate.requestPauseForNewPlaybackEdge(
            ordinaryCallbackAfterAppliedPause.state
        )
        val repeatedCallback = TakeoverCompletionGate.requestPauseForNewPlaybackEdge(
            resumedOffTarget.state
        )

        assertEquals(false, firstPauseApplied.pauseOutstanding)
        assertEquals(true, firstPauseApplied.pauseApplied)
        assertEquals(false, ordinaryCallbackAfterAppliedPause.shouldDispatch)
        assertEquals(true, resumedOffTarget.shouldDispatch)
        assertEquals(true, resumedOffTarget.state.pauseOutstanding)
        assertEquals(false, resumedOffTarget.state.pauseApplied)
        assertEquals(false, repeatedCallback.shouldDispatch)
        assertEquals(true, repeatedCallback.state.pauseOutstanding)
    }

    @Test
    fun `ready route is revoked when it disappears before asynchronous pause lands`() {
        val pauseRequested = TakeoverCompletionGate.requestPause(
            TakeoverCompletionGate.start(pauseRequired = true)
        ).state
        val readyBeforePause = TakeoverCompletionGate.updateRoute(
            pauseRequested,
            ready = true,
            trigger = "fresh_local_source",
        )
        val routeLost = TakeoverCompletionGate.updateRoute(
            readyBeforePause.state,
            ready = false,
            trigger = "a2dp_disconnected",
        )
        val delayedPauseApplied = TakeoverCompletionGate.confirmPauseApplied(routeLost.state)

        assertEquals(true, readyBeforePause.enteredReady)
        assertEquals(false, TakeoverCompletionGate.canComplete(readyBeforePause.state))
        assertEquals(true, routeLost.exitedReady)
        assertEquals(null, routeLost.state.routeReadyTrigger)
        assertEquals(false, TakeoverCompletionGate.canComplete(delayedPauseApplied))

        val routeRecovered = TakeoverCompletionGate.updateRoute(
            delayedPauseApplied,
            ready = true,
            trigger = "a2dp_reconnected",
        )
        assertEquals(true, TakeoverCompletionGate.canComplete(routeRecovered.state))
        assertEquals("a2dp_reconnected", routeRecovered.state.routeReadyTrigger)
    }

    @Test
    fun `takeover failure never creates a late pause for the next playback episode`() {
        val neverPaused = TakeoverCompletionGate.failureDecision(
            TakeoverCompletionGate.start(pauseRequired = false),
            resumeIfStillActive = false,
            playbackStillActive = false,
        )
        val pauseIsStillOutstanding = TakeoverCompletionGate.failureDecision(
            TakeoverCompletionGate.requestPause(
                TakeoverCompletionGate.start(pauseRequired = true)
            ).state,
            resumeIfStillActive = true,
            playbackStillActive = true,
        )
        val pauseWasApplied = TakeoverCompletionGate.failureDecision(
            TakeoverCompletionGate.confirmPauseApplied(
                TakeoverCompletionGate.requestPause(
                    TakeoverCompletionGate.start(pauseRequired = true)
                ).state
            ),
            resumeIfStillActive = true,
            playbackStillActive = false,
        )

        assertEquals(false, neverPaused.shouldDispatchPause)
        assertEquals(false, neverPaused.shouldResume)
        assertEquals(false, neverPaused.keepPlaybackEpisodeOpen)
        assertEquals(true, neverPaused.shouldReportPlaybackStopped)
        assertEquals(false, pauseIsStillOutstanding.shouldDispatchPause)
        assertEquals(false, pauseIsStillOutstanding.shouldResume)
        assertEquals(true, pauseIsStillOutstanding.keepPlaybackEpisodeOpen)
        assertEquals(false, pauseIsStillOutstanding.shouldReportPlaybackStopped)
        assertEquals(false, pauseWasApplied.shouldDispatchPause)
        assertEquals(false, pauseWasApplied.shouldResume)
        assertEquals(true, pauseWasApplied.keepPlaybackEpisodeOpen)
        assertEquals(true, pauseWasApplied.shouldReportPlaybackStopped)
    }

    @Test
    fun `outstanding pause keeps failed episode closed to a new takeover until it lands`() {
        val outstandingPause = TakeoverCompletionGate.requestPause(
            TakeoverCompletionGate.start(pauseRequired = true)
        ).state
        val failure = TakeoverCompletionGate.failureDecision(
            outstandingPause,
            resumeIfStillActive = false,
            playbackStillActive = false,
        )

        assertEquals(false, failure.shouldDispatchPause)
        assertEquals(false, failure.shouldResume)
        assertEquals(true, failure.keepPlaybackEpisodeOpen)
    }

    @Test
    fun `failed outstanding pause never auto resumes restarted playback`() {
        val failed = FailedPauseSettlementMachine.onTakeoverFailed(
            pauseOutstanding = true
        )
        val userRestarted = FailedPauseSettlementMachine.onPlaybackSnapshot(
            state = failed.state,
            eligiblePlaybackActive = true,
        )
        val repeatedStartedCallback = FailedPauseSettlementMachine.onPlaybackSnapshot(
            state = userRestarted.state,
            eligiblePlaybackActive = true,
        )
        val latePauseApplied = FailedPauseSettlementMachine.onPlaybackSnapshot(
            state = repeatedStartedCallback.state,
            eligiblePlaybackActive = false,
        )
        val stableQuiet = FailedPauseSettlementMachine.onQuietDrainTimeout(
            state = latePauseApplied.state,
            expectedGeneration = latePauseApplied.state.generation,
            expectedQuietGeneration = latePauseApplied.state.quietGeneration,
            eligiblePlaybackActive = false,
        )

        assertEquals(true, failed.state.awaitingOutstandingPause)
        assertEquals(FailedPauseSettlementAction.NONE, userRestarted.action)
        assertEquals(true, userRestarted.state.playbackStartedAfterFailure)
        assertEquals(FailedPauseSettlementAction.NONE, repeatedStartedCallback.action)
        assertEquals(FailedPauseSettlementAction.START_QUIET_DRAIN, latePauseApplied.action)
        assertEquals(true, latePauseApplied.state.awaitingOutstandingPause)
        assertEquals(FailedPauseSettlementAction.COMPLETE, stableQuiet.action)
        assertEquals(false, stableQuiet.state.awaitingOutstandingPause)
    }

    @Test
    fun `restarted playback invalidates old quiet drain before settlement completes`() {
        val failed = FailedPauseSettlementMachine.onTakeoverFailed(
            pauseOutstanding = true
        )
        val firstQuiet = FailedPauseSettlementMachine.onPlaybackSnapshot(
            state = failed.state,
            eligiblePlaybackActive = false,
        )
        val userRestarted = FailedPauseSettlementMachine.onPlaybackSnapshot(
            state = firstQuiet.state,
            eligiblePlaybackActive = true,
        )
        val staleQuietTimeout = FailedPauseSettlementMachine.onQuietDrainTimeout(
            state = userRestarted.state,
            expectedGeneration = firstQuiet.state.generation,
            expectedQuietGeneration = firstQuiet.state.quietGeneration,
            eligiblePlaybackActive = false,
        )
        val secondQuiet = FailedPauseSettlementMachine.onPlaybackSnapshot(
            state = staleQuietTimeout.state,
            eligiblePlaybackActive = false,
        )
        val settled = FailedPauseSettlementMachine.onQuietDrainTimeout(
            state = secondQuiet.state,
            expectedGeneration = secondQuiet.state.generation,
            expectedQuietGeneration = secondQuiet.state.quietGeneration,
            eligiblePlaybackActive = false,
        )

        assertEquals(FailedPauseSettlementAction.START_QUIET_DRAIN, firstQuiet.action)
        assertEquals(FailedPauseSettlementAction.CANCEL_QUIET_DRAIN, userRestarted.action)
        assertEquals(FailedPauseSettlementAction.NONE, staleQuietTimeout.action)
        assertEquals(true, staleQuietTimeout.state.awaitingOutstandingPause)
        assertEquals(FailedPauseSettlementAction.START_QUIET_DRAIN, secondQuiet.action)
        assertEquals(FailedPauseSettlementAction.COMPLETE, settled.action)
        assertEquals(false, settled.state.awaitingOutstandingPause)
    }

    @Test
    fun `remote route confirmation revokes ready before failed pause settlement`() {
        val pauseOutstanding = TakeoverCompletionGate.requestPause(
            TakeoverCompletionGate.start(pauseRequired = true)
        ).state
        val localRouteReady = TakeoverCompletionGate.updateRoute(
            state = pauseOutstanding,
            ready = true,
            trigger = "local_a2dp_ready",
        )
        val remoteRouteConfirmed = TakeoverCompletionGate.updateRoute(
            state = localRouteReady.state,
            ready = false,
            trigger = "remote_host_streaming",
        )
        val failed = FailedPauseSettlementMachine.onTakeoverFailed(
            pauseOutstanding = remoteRouteConfirmed.state.pauseOutstanding
        )
        val userRestarted = FailedPauseSettlementMachine.onPlaybackSnapshot(
            state = failed.state,
            eligiblePlaybackActive = true,
        )
        val latePauseApplied = FailedPauseSettlementMachine.onPlaybackSnapshot(
            state = userRestarted.state,
            eligiblePlaybackActive = false,
        )
        val settled = FailedPauseSettlementMachine.onQuietDrainTimeout(
            state = latePauseApplied.state,
            expectedGeneration = latePauseApplied.state.generation,
            expectedQuietGeneration = latePauseApplied.state.quietGeneration,
            eligiblePlaybackActive = false,
        )

        assertEquals(true, localRouteReady.state.routeReady)
        assertEquals(true, remoteRouteConfirmed.exitedReady)
        assertEquals(false, remoteRouteConfirmed.state.routeReady)
        assertEquals(FailedPauseSettlementAction.START_QUIET_DRAIN, latePauseApplied.action)
        assertEquals(FailedPauseSettlementAction.COMPLETE, settled.action)
    }

    @Test
    fun `failed outstanding pause without a restart never emits resume`() {
        val failed = FailedPauseSettlementMachine.onTakeoverFailed(
            pauseOutstanding = true
        )
        val latePauseApplied = FailedPauseSettlementMachine.onPlaybackSnapshot(
            state = failed.state,
            eligiblePlaybackActive = false,
        )
        val settled = FailedPauseSettlementMachine.onQuietDrainTimeout(
            state = latePauseApplied.state,
            expectedGeneration = latePauseApplied.state.generation,
            expectedQuietGeneration = latePauseApplied.state.quietGeneration,
            eligiblePlaybackActive = false,
        )

        assertEquals(FailedPauseSettlementAction.START_QUIET_DRAIN, latePauseApplied.action)
        assertEquals(FailedPauseSettlementAction.COMPLETE, settled.action)
    }

    @Test
    fun `hard timeout releases failed settlement without requesting media action`() {
        val failed = FailedPauseSettlementMachine.onTakeoverFailed(pauseOutstanding = true)
        val activeRelease = FailedPauseSettlementMachine.onHardTimeout(
            state = failed.state,
            expectedGeneration = failed.state.generation,
            eligiblePlaybackActive = true,
        )
        val nextFailure = FailedPauseSettlementMachine.onTakeoverFailed(
            state = activeRelease.state,
            pauseOutstanding = true,
        )
        val inactiveRelease = FailedPauseSettlementMachine.onHardTimeout(
            state = nextFailure.state,
            expectedGeneration = nextFailure.state.generation,
            eligiblePlaybackActive = false,
        )

        assertEquals(FailedPauseSettlementAction.HARD_RELEASE_ACTIVE, activeRelease.action)
        assertEquals(false, activeRelease.state.awaitingOutstandingPause)
        assertEquals(FailedPauseSettlementAction.HARD_RELEASE_INACTIVE, inactiveRelease.action)
        assertEquals(false, inactiveRelease.state.awaitingOutstandingPause)
    }

    @Test
    fun `hard timeout cannot preempt an in progress quiet drain`() {
        val failed = FailedPauseSettlementMachine.onTakeoverFailed(pauseOutstanding = true)
        val quiet = FailedPauseSettlementMachine.onPlaybackSnapshot(
            state = failed.state,
            eligiblePlaybackActive = false,
        )
        val hardTimeout = FailedPauseSettlementMachine.onHardTimeout(
            state = quiet.state,
            expectedGeneration = quiet.state.generation,
            eligiblePlaybackActive = false,
        )
        val stableQuiet = FailedPauseSettlementMachine.onQuietDrainTimeout(
            state = hardTimeout.state,
            expectedGeneration = quiet.state.generation,
            expectedQuietGeneration = quiet.state.quietGeneration,
            eligiblePlaybackActive = false,
        )

        assertEquals(
            FailedPauseSettlementAction.EXTEND_HARD_TIMEOUT_FOR_QUIET_DRAIN,
            hardTimeout.action,
        )
        assertEquals(true, hardTimeout.state.awaitingOutstandingPause)
        assertEquals(FailedPauseSettlementAction.COMPLETE, stableQuiet.action)
    }

    @Test
    fun `stale hard timeout cannot release a newer failed settlement`() {
        val first = FailedPauseSettlementMachine.onTakeoverFailed(pauseOutstanding = true)
        val released = FailedPauseSettlementMachine.onHardTimeout(
            state = first.state,
            expectedGeneration = first.state.generation,
            eligiblePlaybackActive = false,
        )
        val second = FailedPauseSettlementMachine.onTakeoverFailed(
            state = released.state,
            pauseOutstanding = true,
        )
        val stale = FailedPauseSettlementMachine.onHardTimeout(
            state = second.state,
            expectedGeneration = first.state.generation,
            eligiblePlaybackActive = false,
        )

        assertEquals(FailedPauseSettlementAction.NONE, stale.action)
        assertEquals(true, stale.state.awaitingOutstandingPause)
        assertEquals(second.state.generation, stale.state.generation)
    }

    @Test
    fun `already active route keeps pending and failed pause ownership`() {
        assertEquals(
            LocalRouteAlreadyActiveAction.UPDATE_PENDING_ROUTE,
            LocalRouteAlreadyActiveGate.decide(
                takeoverAwaitingRoute = true,
                failedPauseAwaiting = false,
            ),
        )
        assertEquals(
            LocalRouteAlreadyActiveAction.KEEP_FAILED_PAUSE_SETTLEMENT,
            LocalRouteAlreadyActiveGate.decide(
                takeoverAwaitingRoute = false,
                failedPauseAwaiting = true,
            ),
        )
        assertEquals(
            LocalRouteAlreadyActiveAction.COMPLETE_IMMEDIATELY,
            LocalRouteAlreadyActiveGate.decide(
                takeoverAwaitingRoute = false,
                failedPauseAwaiting = false,
            ),
        )
    }

    @Test
    fun `ear detection emits one media action only when worn count changes`() {
        assertEquals(
            EarDetectionMediaAction.PAUSE,
            EarDetectionMediaGate.decide(listOf(true, true), listOf(false, false)),
        )
        assertEquals(
            EarDetectionMediaAction.NONE,
            EarDetectionMediaGate.decide(listOf(false, false), listOf(false, false)),
        )
        assertEquals(
            EarDetectionMediaAction.RESUME,
            EarDetectionMediaGate.decide(listOf(false, false), listOf(true, false)),
        )
        assertEquals(
            EarDetectionMediaAction.PAUSE,
            EarDetectionMediaGate.decide(listOf(true, true), listOf(true, false)),
        )
        assertEquals(
            EarDetectionMediaAction.RESUME,
            EarDetectionMediaGate.decide(listOf(true, false), listOf(true, true)),
        )
        assertEquals(
            EarDetectionMediaAction.NONE,
            EarDetectionMediaGate.decide(listOf(true, false), listOf(false, true)),
        )
    }

    @Test
    fun `ear detection resume requires owned pause and stable inactive playback`() {
        assertEquals(
            false,
            EarDetectionResumeGate.canResume(
                pauseOwned = false,
                resumeRequested = true,
                playbackActive = false,
                inactiveForMs = 2_000L,
                quietWindowMs = 1_500L,
            ),
        )
        assertEquals(
            false,
            EarDetectionResumeGate.canResume(
                pauseOwned = true,
                resumeRequested = true,
                playbackActive = true,
                inactiveForMs = 2_000L,
                quietWindowMs = 1_500L,
            ),
        )
        assertEquals(
            false,
            EarDetectionResumeGate.canResume(
                pauseOwned = true,
                resumeRequested = true,
                playbackActive = false,
                inactiveForMs = 1_499L,
                quietWindowMs = 1_500L,
            ),
        )
        assertEquals(
            true,
            EarDetectionResumeGate.canResume(
                pauseOwned = true,
                resumeRequested = true,
                playbackActive = false,
                inactiveForMs = 1_500L,
                quietWindowMs = 1_500L,
            ),
        )
    }

    @Test
    fun `repeated ear pause cancels pending resume without dispatching another pause`() {
        val repeated = EarDetectionPauseRequestGate.decide(
            pauseOwned = true,
            playbackActive = true,
            anotherPauseOwnerActive = false,
        )
        val first = EarDetectionPauseRequestGate.decide(
            pauseOwned = false,
            playbackActive = true,
            anotherPauseOwnerActive = false,
        )
        val takeoverOwnsPause = EarDetectionPauseRequestGate.decide(
            pauseOwned = false,
            playbackActive = true,
            anotherPauseOwnerActive = true,
        )

        assertEquals(false, repeated.dispatchPause)
        assertEquals(true, repeated.cancelPendingResume)
        assertEquals(true, first.dispatchPause)
        assertEquals(false, first.cancelPendingResume)
        assertEquals(false, takeoverOwnsPause.dispatchPause)
    }

    @Test
    fun `explicit playback after guarded takeover reevaluates a lost route`() {
        assertEquals(
            false,
            ExplicitResumeRouteGate.shouldReevaluateRoute(
                awaitingExplicitResume = true,
                playbackRoutedToTarget = true,
            ),
        )
        assertEquals(
            true,
            ExplicitResumeRouteGate.shouldReevaluateRoute(
                awaitingExplicitResume = true,
                playbackRoutedToTarget = false,
            ),
        )
        assertEquals(
            false,
            ExplicitResumeRouteGate.shouldReevaluateRoute(
                awaitingExplicitResume = false,
                playbackRoutedToTarget = false,
            ),
        )
    }

    @Test
    fun `stale ear detection receiver cannot handle a newer resume generation`() {
        assertEquals(
            false,
            EarDetectionReceiverGate.shouldHandle(
                expectedGeneration = 1L,
                currentGeneration = 2L,
                wornCount = 1,
            ),
        )
        assertEquals(
            false,
            EarDetectionReceiverGate.shouldHandle(
                expectedGeneration = 2L,
                currentGeneration = 2L,
                wornCount = 0,
            ),
        )
        assertEquals(
            true,
            EarDetectionReceiverGate.shouldHandle(
                expectedGeneration = 2L,
                currentGeneration = 2L,
                wornCount = 1,
            ),
        )
    }

    @Test
    fun `route ready cannot complete while asynchronous pause is outstanding`() {
        val outstanding = TakeoverCompletionGate.requestPause(
            TakeoverCompletionGate.start(pauseRequired = true)
        ).state
        val ready = TakeoverCompletionGate.updateRoute(
            state = outstanding,
            ready = true,
            trigger = "local_route_already_active",
        ).state
        val pauseApplied = TakeoverCompletionGate.confirmPauseApplied(ready)

        assertEquals(true, ready.routeReady)
        assertEquals(true, ready.pauseOutstanding)
        assertEquals(false, TakeoverCompletionGate.canComplete(ready))
        assertEquals(true, TakeoverCompletionGate.canComplete(pauseApplied))
    }

    @Test
    fun `route ready before five second timeout disarms stale timeout without a pause`() {
        val armedTimeout = TakeoverRouteTimeoutMachine.arm(TakeoverRouteTimeoutState())
        val noPauseRequired = TakeoverCompletionGate.start(pauseRequired = false)
        val routeReady = TakeoverCompletionGate.updateRoute(
            state = noPauseRequired,
            ready = true,
            trigger = "target_a2dp_playing",
        ).state
        val timeoutAfterReady = TakeoverRouteTimeoutMachine.onTimeout(
            state = TakeoverRouteTimeoutMachine.disarm(armedTimeout),
            expectedGeneration = armedTimeout.generation,
        )

        assertEquals(true, armedTimeout.armed)
        assertEquals(true, TakeoverCompletionGate.canComplete(routeReady))
        assertEquals(false, routeReady.pauseOutstanding)
        assertEquals(TakeoverRouteTimeoutAction.NONE, timeoutAfterReady.action)
    }

    @Test
    fun `stale timeout generation cannot consume rearmed route timeout`() {
        val first = TakeoverRouteTimeoutMachine.arm(TakeoverRouteTimeoutState())
        val rearmed = TakeoverRouteTimeoutMachine.arm(
            TakeoverRouteTimeoutMachine.disarm(first)
        )
        val staleTimeout = TakeoverRouteTimeoutMachine.onTimeout(
            state = rearmed,
            expectedGeneration = first.generation,
        )
        val currentTimeout = TakeoverRouteTimeoutMachine.onTimeout(
            state = staleTimeout.state,
            expectedGeneration = rearmed.generation,
        )

        assertEquals(TakeoverRouteTimeoutAction.NONE, staleTimeout.action)
        assertEquals(true, staleTimeout.state.armed)
        assertEquals(TakeoverRouteTimeoutAction.FAIL_ATTEMPT, currentTimeout.action)
        assertEquals(false, currentTimeout.state.armed)
    }

    @Test
    fun `show ui is emitted once per takeover attempt despite retries and route flaps`() {
        val readyBeforeGuardStarts = TakeoverRouteObservationGate.observe(
            TakeoverRouteObservationState(),
            attemptId = "takeover-1",
            readiness = TakeoverRouteReadiness.READY,
            allowShowUi = false,
        )
        val waiting = TakeoverRouteObservationGate.observe(
            readyBeforeGuardStarts.state,
            attemptId = "takeover-1",
            readiness = TakeoverRouteReadiness.WAITING_FOR_LOCAL_SOURCE,
        )
        val firstReady = TakeoverRouteObservationGate.observe(
            waiting.state,
            attemptId = "takeover-1",
            readiness = TakeoverRouteReadiness.READY,
        )
        val duplicateReady = TakeoverRouteObservationGate.observe(
            firstReady.state,
            attemptId = "takeover-1",
            readiness = TakeoverRouteReadiness.READY,
        )
        val routeLost = TakeoverRouteObservationGate.observe(
            duplicateReady.state,
            attemptId = "takeover-1",
            readiness = TakeoverRouteReadiness.WAITING_FOR_A2DP,
        )
        val recoveredSameAttempt = TakeoverRouteObservationGate.observe(
            routeLost.state,
            attemptId = "takeover-1",
            readiness = TakeoverRouteReadiness.READY,
        )
        val nextAttempt = TakeoverRouteObservationGate.observe(
            recoveredSameAttempt.state,
            attemptId = "takeover-2",
            readiness = TakeoverRouteReadiness.READY,
        )

        assertEquals(false, readyBeforeGuardStarts.shouldShowUi)
        assertEquals(false, readyBeforeGuardStarts.state.showUiSent)
        assertEquals(false, waiting.shouldShowUi)
        assertEquals(true, firstReady.shouldShowUi)
        assertEquals(false, duplicateReady.readinessChanged)
        assertEquals(false, duplicateReady.shouldShowUi)
        assertEquals(false, recoveredSameAttempt.shouldShowUi)
        assertEquals(true, nextAttempt.shouldShowUi)
        assertEquals(null, nextAttempt.previousReadiness)
    }

    @Test
    fun `local route repair can reuse ownership and source but still waits for target`() {
        val repair = TakeoverRouteSignals(
            ownsConnection = false,
            audioSource = TakeoverAudioSource.LOCAL,
            a2dpConnected = true,
            targetAudioActive = false,
            requireTargetAudioActive = true,
        )

        assertEquals(
            TakeoverRouteReadiness.WAITING_FOR_TARGET_AUDIO,
            TakeoverRouteGate.evaluate(repair),
        )
        assertEquals(
            TakeoverRouteReadiness.READY,
            TakeoverRouteGate.evaluate(repair.copy(targetAudioActive = true)),
        )
    }

    @Test
    fun `target output requires the saved AirPods A2DP address`() {
        assertEquals(
            TargetAudioOutput.TARGET,
            TargetAudioOutputClassifier.classify(
                isBluetoothA2dp = true,
                outputAddress = "02:11:22:33:44:55",
                targetAddress = "02:11:22:33:44:55",
            )
        )
        assertEquals(
            TargetAudioOutput.OTHER,
            TargetAudioOutputClassifier.classify(
                isBluetoothA2dp = true,
                outputAddress = "02:11:22:33:44:56",
                targetAddress = "02:11:22:33:44:55",
            )
        )
        assertEquals(
            TargetAudioOutput.OTHER,
            TargetAudioOutputClassifier.classify(
                isBluetoothA2dp = false,
                outputAddress = null,
                targetAddress = "02:11:22:33:44:55",
            )
        )
        assertEquals(
            TargetAudioOutput.UNKNOWN,
            TargetAudioOutputClassifier.classify(
                isBluetoothA2dp = true,
                outputAddress = null,
                targetAddress = "02:11:22:33:44:55",
            )
        )
    }

    @Test
    fun `mixed concrete outputs cannot claim the target route`() {
        assertEquals(
            false,
            TargetAudioOutputClassifier.confirmsTarget(
                listOf(TargetAudioOutput.TARGET, TargetAudioOutput.UNKNOWN)
            )
        )
        assertEquals(
            false,
            TargetAudioOutputClassifier.confirmsTarget(
                listOf(TargetAudioOutput.TARGET, TargetAudioOutput.OTHER)
            )
        )
        assertEquals(
            true,
            TargetAudioOutputClassifier.confirmsTarget(
                listOf(TargetAudioOutput.TARGET, TargetAudioOutput.TARGET)
            )
        )
        assertEquals(
            TargetRouteEvidence.CONFIRMED_OTHER,
            TargetAudioOutputClassifier.aggregate(
                listOf(TargetAudioOutput.TARGET, TargetAudioOutput.OTHER)
            )
        )
        assertEquals(
            TargetRouteEvidence.UNKNOWN,
            TargetAudioOutputClassifier.aggregate(
                listOf(TargetAudioOutput.TARGET, TargetAudioOutput.UNKNOWN)
            )
        )
    }

    @Test
    fun `pending deadline is reused only for the same eligible players`() {
        val mediaPlayer = PlaybackIdentity(playerId = 11, usage = 1)
        val replacementMediaPlayer = PlaybackIdentity(playerId = 12, usage = 1)
        val shortSoundPlayer = PlaybackIdentity(playerId = 21, usage = 5)
        assertEquals(
            true,
            PendingPlaybackDeadlineGate.shouldKeepExistingDeadline(
                scheduledPlayers = setOf(mediaPlayer),
                currentPlayers = setOf(mediaPlayer),
                requestedDelayMs = 300,
                remainingDelayMs = 80,
            )
        )
        assertEquals(
            false,
            PendingPlaybackDeadlineGate.shouldKeepExistingDeadline(
                scheduledPlayers = setOf(mediaPlayer),
                currentPlayers = setOf(replacementMediaPlayer),
                requestedDelayMs = 300,
                remainingDelayMs = 80,
            )
        )
        assertEquals(
            false,
            PendingPlaybackDeadlineGate.shouldKeepExistingDeadline(
                scheduledPlayers = setOf(shortSoundPlayer),
                currentPlayers = setOf(mediaPlayer),
                requestedDelayMs = 100,
                remainingDelayMs = 250,
            )
        )
    }

    @Test
    fun `smart routing target prefers active remote then current and remembered hosts`() {
        val self = "06:11:22:33:44:55"
        val activeMac = "0A:11:22:33:44:55"
        val currentMac = "0E:11:22:33:44:55"
        val rememberedMac = "AA:BB:CC:DD:EE:FF"

        assertEquals(
            listOf(activeMac, currentMac, rememberedMac),
            SmartRoutingTargetResolver.resolveTargets(
                selfAddress = self,
                activeSourceAddress = activeMac.lowercase(),
                currentDeviceAddresses = listOf(self, currentMac),
                rememberedDeviceAddresses = listOf(rememberedMac, activeMac),
            ),
        )
        assertEquals(
            rememberedMac,
            SmartRoutingTargetResolver.resolve(
                selfAddress = self,
                activeSourceAddress = "00:00:00:00:00:00",
                currentDeviceAddresses = listOf(self),
                rememberedDeviceAddresses = listOf(self, rememberedMac),
            ),
        )
    }

    @Test
    fun `known target activity prevents pause during a transient playback route gap`() {
        assertEquals(
            false,
            TakeoverPausePolicyGate.shouldPauseImmediately(
                targetAudioActive = true,
                playbackRoutedToTarget = false,
                localSourceConfirmed = true,
            ),
        )
        assertEquals(
            false,
            TakeoverPausePolicyGate.shouldPauseImmediately(
                targetAudioActive = false,
                playbackRoutedToTarget = true,
                localSourceConfirmed = true,
            ),
        )
        assertEquals(
            true,
            TakeoverPausePolicyGate.shouldPauseImmediately(
                targetAudioActive = false,
                playbackRoutedToTarget = false,
                localSourceConfirmed = true,
            ),
        )
    }

    @Test
    fun `remote or unknown source never gets an eager local pause`() {
        assertEquals(
            false,
            TakeoverPausePolicyGate.shouldPauseImmediately(
                targetAudioActive = false,
                playbackRoutedToTarget = false,
                localSourceConfirmed = false,
            ),
        )
    }

    @Test
    fun `playback edge cannot create a pause barrier for a remote handoff`() {
        assertEquals(
            false,
            TakeoverPausePolicyGate.shouldPauseForPlaybackEdge(
                pauseRequired = false,
                playbackRoutedToTarget = false,
                musicActive = true,
            ),
        )
        assertEquals(
            true,
            TakeoverPausePolicyGate.shouldPauseForPlaybackEdge(
                pauseRequired = true,
                playbackRoutedToTarget = false,
                musicActive = true,
            ),
        )
        assertEquals(
            false,
            TakeoverPausePolicyGate.shouldPauseForPlaybackEdge(
                pauseRequired = true,
                playbackRoutedToTarget = true,
                musicActive = true,
            ),
        )
    }

    @Test
    fun `takeover side effects require the exact live attempt`() {
        assertEquals(
            true,
            TakeoverAttemptSideEffectGate.canRun(
                expectedAttemptId = "takeover-1",
                currentAttemptId = "takeover-1",
                attemptIsCurrent = true,
            ),
        )
        assertEquals(
            false,
            TakeoverAttemptSideEffectGate.canRun(
                expectedAttemptId = "takeover-1",
                currentAttemptId = "takeover-2",
                attemptIsCurrent = true,
            ),
        )
        assertEquals(
            false,
            TakeoverAttemptSideEffectGate.canRun(
                expectedAttemptId = "takeover-1",
                currentAttemptId = "takeover-1",
                attemptIsCurrent = false,
            ),
        )
    }

    @Test
    fun `source convergence retry is bounded to two claims for one live attempt`() {
        val started = TakeoverSourceRetryGate.start(
            TakeoverSourceRetryState(),
            attemptId = "takeover-1",
        )
        val first = TakeoverSourceRetryGate.claim(
            state = started,
            expectedAttemptId = "takeover-1",
            expectedGeneration = started.generation,
            currentAttemptId = "takeover-1",
            requestCompleted = true,
            socketConnected = true,
            playbackActive = true,
            socketLeaseCurrent = true,
            targetSetCurrent = true,
            audioSource = TakeoverAudioSource.NONE,
        )
        val second = TakeoverSourceRetryGate.claim(
            state = first.state,
            expectedAttemptId = "takeover-1",
            expectedGeneration = started.generation,
            currentAttemptId = "takeover-1",
            requestCompleted = true,
            socketConnected = true,
            playbackActive = true,
            socketLeaseCurrent = true,
            targetSetCurrent = true,
            audioSource = TakeoverAudioSource.REMOTE,
        )
        val exhausted = TakeoverSourceRetryGate.claim(
            state = second.state,
            expectedAttemptId = "takeover-1",
            expectedGeneration = started.generation,
            currentAttemptId = "takeover-1",
            requestCompleted = true,
            socketConnected = true,
            playbackActive = true,
            socketLeaseCurrent = true,
            targetSetCurrent = true,
            audioSource = TakeoverAudioSource.UNKNOWN,
        )

        assertEquals(listOf(450L, 1000L), TakeoverSourceRetryGate.retryAtMs)
        assertEquals(true, first.shouldRetry)
        assertEquals(1, first.retryOrdinal)
        assertEquals(true, second.shouldRetry)
        assertEquals(2, second.retryOrdinal)
        assertEquals(false, exhausted.shouldRetry)
        assertEquals(2, exhausted.state.claimedRetries)
    }

    @Test
    fun `source convergence retry cancels on local source stop or attempt change`() {
        val started = TakeoverSourceRetryGate.start(
            TakeoverSourceRetryState(),
            attemptId = "takeover-1",
        )
        fun claim(
            currentAttemptId: String? = "takeover-1",
            requestCompleted: Boolean = true,
            socketConnected: Boolean = true,
            socketLeaseCurrent: Boolean = true,
            targetSetCurrent: Boolean = true,
            playbackActive: Boolean = true,
            source: TakeoverAudioSource = TakeoverAudioSource.NONE,
        ) = TakeoverSourceRetryGate.claim(
            state = started,
            expectedAttemptId = "takeover-1",
            expectedGeneration = started.generation,
            currentAttemptId = currentAttemptId,
            requestCompleted = requestCompleted,
            socketConnected = socketConnected,
            socketLeaseCurrent = socketLeaseCurrent,
            targetSetCurrent = targetSetCurrent,
            playbackActive = playbackActive,
            audioSource = source,
        )

        assertEquals(false, claim(source = TakeoverAudioSource.LOCAL).shouldRetry)
        assertEquals(false, claim(playbackActive = false).shouldRetry)
        assertEquals(false, claim(socketConnected = false).shouldRetry)
        assertEquals(false, claim(socketLeaseCurrent = false).shouldRetry)
        assertEquals(false, claim(targetSetCurrent = false).shouldRetry)
        assertEquals(false, claim(requestCompleted = false).shouldRetry)
        assertEquals(false, claim(currentAttemptId = "takeover-2").shouldRetry)

        val cancelled = TakeoverSourceRetryGate.cancel(started)
        val stale = TakeoverSourceRetryGate.claim(
            state = cancelled,
            expectedAttemptId = "takeover-1",
            expectedGeneration = started.generation,
            currentAttemptId = "takeover-1",
            requestCompleted = true,
            socketConnected = true,
            socketLeaseCurrent = true,
            targetSetCurrent = true,
            playbackActive = true,
            audioSource = TakeoverAudioSource.NONE,
        )
        assertEquals(false, stale.shouldRetry)
    }

    @Test
    fun `unknown source is never eligible for a convergence retry`() {
        val started = TakeoverSourceRetryGate.start(
            TakeoverSourceRetryState(),
            attemptId = "takeover-unknown",
        )
        val decision = TakeoverSourceRetryGate.claim(
            state = started,
            expectedAttemptId = "takeover-unknown",
            expectedGeneration = started.generation,
            currentAttemptId = "takeover-unknown",
            requestCompleted = true,
            socketConnected = true,
            socketLeaseCurrent = true,
            targetSetCurrent = true,
            playbackActive = true,
            audioSource = TakeoverAudioSource.UNKNOWN,
        )

        assertEquals(false, decision.shouldRetry)
        assertEquals(0, decision.state.claimedRetries)
    }

    @Test
    fun `socket lease requires both object identity and generation`() {
        val socket = Any()
        val replacement = Any()

        assertEquals(true, AacpSocketLeaseGate.isCurrent(socket, 4L, socket, 4L))
        assertEquals(false, AacpSocketLeaseGate.isCurrent(socket, 4L, socket, 5L))
        assertEquals(false, AacpSocketLeaseGate.isCurrent(socket, 4L, replacement, 4L))
    }

    @Test
    fun `takeover sequence plans keep initialization packets out of takeover stages`() {
        assertEquals(
            listOf(
                TakeoverPacketStage.OWNERSHIP,
                TakeoverPacketStage.MEDIA_INFORMATION,
                TakeoverPacketStage.HIJACK_REQUEST,
            ),
            TakeoverPacketSequencePlan.stages(TakeoverPacketSequenceMode.FULL),
        )
        assertEquals(
            listOf(
                TakeoverPacketStage.MEDIA_INFORMATION,
                TakeoverPacketStage.HIJACK_REQUEST,
            ),
            TakeoverPacketSequencePlan.stages(TakeoverPacketSequenceMode.MEDIA_AND_HIJACK),
        )
    }
}
