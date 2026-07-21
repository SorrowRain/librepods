package me.kavishdevar.librepods.data

import java.util.Locale

enum class TakeoverAudioSource {
    LOCAL,
    REMOTE,
    NONE,
    UNKNOWN
}

enum class TakeoverRouteReadiness {
    READY,
    WAITING_FOR_REQUEST,
    WAITING_FOR_OWNERSHIP,
    WAITING_FOR_LOCAL_SOURCE,
    WAITING_FOR_A2DP,
    WAITING_FOR_TARGET_AUDIO,
}

data class TakeoverRouteSignals(
    val ownsConnection: Boolean,
    val audioSource: TakeoverAudioSource,
    val a2dpConnected: Boolean,
    val targetAudioActive: Boolean = false,
    val requireTargetAudioActive: Boolean = false,
    val requireOwnership: Boolean = false,
    val requireFreshLocalSource: Boolean = false,
    val freshLocalSourceConfirmed: Boolean = true,
    val requireCompletedRequest: Boolean = false,
    val requestCompleted: Boolean = true,
)

object TakeoverRouteGate {
    /**
     * The AACP ownership bit can lag behind the actual Bluetooth route. When the
     * accessory is already streaming from this phone and the A2DP profile is up,
     * requesting another takeover would unnecessarily pause a working stream.
     */
    fun isLocalAudioAlreadyRouted(signals: TakeoverRouteSignals): Boolean =
        signals.audioSource == TakeoverAudioSource.LOCAL &&
            signals.a2dpConnected &&
            signals.targetAudioActive

    fun shouldRequestTakeover(signals: TakeoverRouteSignals): Boolean =
        !isLocalAudioAlreadyRouted(signals)

    fun evaluate(signals: TakeoverRouteSignals): TakeoverRouteReadiness = when {
        signals.requireCompletedRequest && !signals.requestCompleted ->
            TakeoverRouteReadiness.WAITING_FOR_REQUEST
        signals.audioSource != TakeoverAudioSource.LOCAL ||
            (signals.requireFreshLocalSource && !signals.freshLocalSourceConfirmed) ->
            TakeoverRouteReadiness.WAITING_FOR_LOCAL_SOURCE
        signals.requireOwnership && !signals.ownsConnection ->
            TakeoverRouteReadiness.WAITING_FOR_OWNERSHIP
        !signals.a2dpConnected -> TakeoverRouteReadiness.WAITING_FOR_A2DP
        signals.requireTargetAudioActive && !signals.targetAudioActive ->
            TakeoverRouteReadiness.WAITING_FOR_TARGET_AUDIO
        // Ownership is a control-plane hint and can arrive after the actual route.
        // Once both route signals are local, keeping media paused causes a loop.
        else -> TakeoverRouteReadiness.READY
    }
}

data class TakeoverCompletionState(
    val routeReady: Boolean = false,
    val routeReadyTrigger: String? = null,
    val pauseRequired: Boolean = false,
    val pauseOutstanding: Boolean = false,
    val pauseApplied: Boolean = false,
)

data class TakeoverPauseRequest(
    val state: TakeoverCompletionState,
    val shouldDispatch: Boolean,
)

data class TakeoverRouteUpdate(
    val state: TakeoverCompletionState,
    val enteredReady: Boolean,
    val exitedReady: Boolean,
)

data class TakeoverFailureDecision(
    val shouldDispatchPause: Boolean,
    val shouldResume: Boolean,
    val keepPlaybackEpisodeOpen: Boolean,
    val shouldReportPlaybackStopped: Boolean,
)

object TakeoverPausePolicyGate {
    fun shouldPauseImmediately(
        targetAudioActive: Boolean,
        playbackRoutedToTarget: Boolean,
        localSourceConfirmed: Boolean,
    ): Boolean = localSourceConfirmed && !targetAudioActive && !playbackRoutedToTarget

    /**
     * A playback edge may re-arm an already required pause barrier, but it must not turn a
     * remote/NONE handoff (which deliberately started without a pause) into a local pause.
     */
    fun shouldPauseForPlaybackEdge(
        pauseRequired: Boolean,
        playbackRoutedToTarget: Boolean,
        musicActive: Boolean,
    ): Boolean = pauseRequired && musicActive && !playbackRoutedToTarget
}

object TakeoverAttemptSideEffectGate {
    fun canRun(
        expectedAttemptId: String?,
        currentAttemptId: String?,
        attemptIsCurrent: Boolean,
    ): Boolean = expectedAttemptId != null &&
        expectedAttemptId == currentAttemptId &&
        attemptIsCurrent
}

data class TakeoverSourceRetryState(
    val attemptId: String? = null,
    val generation: Long = 0L,
    val claimedRetries: Int = 0,
)

data class TakeoverSourceRetryDecision(
    val state: TakeoverSourceRetryState,
    val shouldRetry: Boolean,
    val retryOrdinal: Int? = null,
)

object TakeoverSourceRetryGate {
    /** Absolute retry times from the successful initial packet sequence. */
    val retryAtMs: List<Long> = listOf(450L, 1000L)

    fun start(
        state: TakeoverSourceRetryState,
        attemptId: String,
    ): TakeoverSourceRetryState = TakeoverSourceRetryState(
        attemptId = attemptId,
        generation = state.generation + 1L,
    )

    fun cancel(state: TakeoverSourceRetryState): TakeoverSourceRetryState =
        TakeoverSourceRetryState(generation = state.generation + 1L)

    fun isEligible(
        state: TakeoverSourceRetryState,
        expectedAttemptId: String,
        expectedGeneration: Long,
        currentAttemptId: String?,
        requestCompleted: Boolean,
        socketConnected: Boolean,
        socketLeaseCurrent: Boolean,
        targetSetCurrent: Boolean,
        playbackActive: Boolean,
        audioSource: TakeoverAudioSource,
    ): Boolean = state.attemptId == expectedAttemptId &&
        state.generation == expectedGeneration &&
        currentAttemptId == expectedAttemptId &&
        requestCompleted &&
        socketConnected &&
        socketLeaseCurrent &&
        targetSetCurrent &&
        playbackActive &&
        (audioSource == TakeoverAudioSource.NONE ||
            audioSource == TakeoverAudioSource.REMOTE)

    fun claim(
        state: TakeoverSourceRetryState,
        expectedAttemptId: String,
        expectedGeneration: Long,
        currentAttemptId: String?,
        requestCompleted: Boolean,
        socketConnected: Boolean,
        socketLeaseCurrent: Boolean,
        targetSetCurrent: Boolean,
        playbackActive: Boolean,
        audioSource: TakeoverAudioSource,
    ): TakeoverSourceRetryDecision {
        if (!isEligible(
                state = state,
                expectedAttemptId = expectedAttemptId,
                expectedGeneration = expectedGeneration,
                currentAttemptId = currentAttemptId,
                requestCompleted = requestCompleted,
                socketConnected = socketConnected,
                socketLeaseCurrent = socketLeaseCurrent,
                targetSetCurrent = targetSetCurrent,
                playbackActive = playbackActive,
                audioSource = audioSource,
            ) || state.claimedRetries >= retryAtMs.size
        ) {
            return TakeoverSourceRetryDecision(state = state, shouldRetry = false)
        }
        val retryOrdinal = state.claimedRetries + 1
        return TakeoverSourceRetryDecision(
            state = state.copy(claimedRetries = retryOrdinal),
            shouldRetry = true,
            retryOrdinal = retryOrdinal,
        )
    }
}

enum class FailedPauseSettlementAction {
    NONE,
    START_QUIET_DRAIN,
    CANCEL_QUIET_DRAIN,
    COMPLETE,
    EXTEND_HARD_TIMEOUT_FOR_QUIET_DRAIN,
    HARD_RELEASE_ACTIVE,
    HARD_RELEASE_INACTIVE,
}

enum class FailedPauseSettlementPhase {
    INACTIVE,
    WAITING_FOR_PAUSE,
    QUIET_DRAIN,
}

data class FailedPauseSettlementState(
    val phase: FailedPauseSettlementPhase = FailedPauseSettlementPhase.INACTIVE,
    val generation: Long = 0L,
    val quietGeneration: Long = 0L,
    val playbackStartedAfterFailure: Boolean = false,
) {
    val awaitingOutstandingPause: Boolean
        get() = phase != FailedPauseSettlementPhase.INACTIVE
}

data class FailedPauseSettlementTransition(
    val state: FailedPauseSettlementState,
    val action: FailedPauseSettlementAction,
)

object FailedPauseSettlementMachine {
    fun onTakeoverFailed(
        state: FailedPauseSettlementState = FailedPauseSettlementState(),
        pauseOutstanding: Boolean,
    ): FailedPauseSettlementTransition = FailedPauseSettlementTransition(
        state = if (pauseOutstanding) {
            state.copy(
                phase = FailedPauseSettlementPhase.WAITING_FOR_PAUSE,
                generation = state.generation + 1L,
                quietGeneration = state.quietGeneration + 1L,
                playbackStartedAfterFailure = false,
            )
        } else {
            state.copy(
                phase = FailedPauseSettlementPhase.INACTIVE,
                quietGeneration = state.quietGeneration + 1L,
                playbackStartedAfterFailure = false,
            )
        },
        action = FailedPauseSettlementAction.NONE,
    )

    fun onPlaybackSnapshot(
        state: FailedPauseSettlementState,
        eligiblePlaybackActive: Boolean,
    ): FailedPauseSettlementTransition {
        if (!state.awaitingOutstandingPause) {
            return FailedPauseSettlementTransition(
                state = state,
                action = FailedPauseSettlementAction.NONE,
            )
        }
        if (eligiblePlaybackActive) {
            val wasDraining = state.phase == FailedPauseSettlementPhase.QUIET_DRAIN
            return FailedPauseSettlementTransition(
                state = state.copy(
                    phase = FailedPauseSettlementPhase.WAITING_FOR_PAUSE,
                    quietGeneration = if (wasDraining) {
                        state.quietGeneration + 1L
                    } else {
                        state.quietGeneration
                    },
                    playbackStartedAfterFailure = true,
                ),
                action = if (wasDraining) {
                    FailedPauseSettlementAction.CANCEL_QUIET_DRAIN
                } else {
                    FailedPauseSettlementAction.NONE
                },
            )
        }
        if (state.phase == FailedPauseSettlementPhase.QUIET_DRAIN) {
            return FailedPauseSettlementTransition(
                state = state,
                action = FailedPauseSettlementAction.NONE,
            )
        }
        return FailedPauseSettlementTransition(
            state = state.copy(
                phase = FailedPauseSettlementPhase.QUIET_DRAIN,
                quietGeneration = state.quietGeneration + 1L,
            ),
            action = FailedPauseSettlementAction.START_QUIET_DRAIN,
        )
    }

    fun onQuietDrainTimeout(
        state: FailedPauseSettlementState,
        expectedGeneration: Long,
        expectedQuietGeneration: Long,
        eligiblePlaybackActive: Boolean,
    ): FailedPauseSettlementTransition {
        if (!state.awaitingOutstandingPause ||
            state.phase != FailedPauseSettlementPhase.QUIET_DRAIN ||
            state.generation != expectedGeneration ||
            state.quietGeneration != expectedQuietGeneration
        ) {
            return FailedPauseSettlementTransition(
                state = state,
                action = FailedPauseSettlementAction.NONE,
            )
        }
        if (eligiblePlaybackActive) {
            return FailedPauseSettlementTransition(
                state = state.copy(
                    phase = FailedPauseSettlementPhase.WAITING_FOR_PAUSE,
                    quietGeneration = state.quietGeneration + 1L,
                    playbackStartedAfterFailure = true,
                ),
                action = FailedPauseSettlementAction.NONE,
            )
        }
        return FailedPauseSettlementTransition(
            state = state.copy(
                phase = FailedPauseSettlementPhase.INACTIVE,
                quietGeneration = state.quietGeneration + 1L,
                playbackStartedAfterFailure = false,
            ),
            action = FailedPauseSettlementAction.COMPLETE,
        )
    }

    fun onHardTimeout(
        state: FailedPauseSettlementState,
        expectedGeneration: Long,
        eligiblePlaybackActive: Boolean,
    ): FailedPauseSettlementTransition {
        if (!state.awaitingOutstandingPause || state.generation != expectedGeneration) {
            return FailedPauseSettlementTransition(
                state = state,
                action = FailedPauseSettlementAction.NONE,
            )
        }
        if (!eligiblePlaybackActive && state.phase == FailedPauseSettlementPhase.QUIET_DRAIN) {
            return FailedPauseSettlementTransition(
                state = state,
                action = FailedPauseSettlementAction.EXTEND_HARD_TIMEOUT_FOR_QUIET_DRAIN,
            )
        }
        return FailedPauseSettlementTransition(
            state = state.copy(
                phase = FailedPauseSettlementPhase.INACTIVE,
                quietGeneration = state.quietGeneration + 1L,
                playbackStartedAfterFailure = false,
            ),
            action = if (eligiblePlaybackActive) {
                FailedPauseSettlementAction.HARD_RELEASE_ACTIVE
            } else {
                FailedPauseSettlementAction.HARD_RELEASE_INACTIVE
            },
        )
    }
}

enum class LocalRouteAlreadyActiveAction {
    UPDATE_PENDING_ROUTE,
    KEEP_FAILED_PAUSE_SETTLEMENT,
    COMPLETE_IMMEDIATELY,
}

object LocalRouteAlreadyActiveGate {
    fun decide(
        takeoverAwaitingRoute: Boolean,
        failedPauseAwaiting: Boolean,
    ): LocalRouteAlreadyActiveAction = when {
        takeoverAwaitingRoute -> LocalRouteAlreadyActiveAction.UPDATE_PENDING_ROUTE
        failedPauseAwaiting -> LocalRouteAlreadyActiveAction.KEEP_FAILED_PAUSE_SETTLEMENT
        else -> LocalRouteAlreadyActiveAction.COMPLETE_IMMEDIATELY
    }
}

enum class EarDetectionMediaAction {
    NONE,
    PAUSE,
    RESUME,
}

object EarDetectionMediaGate {
    fun decide(
        previousInEar: Collection<Boolean>,
        currentInEar: Collection<Boolean>,
    ): EarDetectionMediaAction {
        val previousCount = previousInEar.count { it }
        val currentCount = currentInEar.count { it }
        if (previousCount == currentCount) return EarDetectionMediaAction.NONE
        return when {
            currentCount == 0 -> EarDetectionMediaAction.PAUSE
            previousCount == 0 -> EarDetectionMediaAction.RESUME
            currentCount < previousCount -> EarDetectionMediaAction.PAUSE
            else -> EarDetectionMediaAction.RESUME
        }
    }
}

object EarDetectionResumeGate {
    fun canResume(
        pauseOwned: Boolean,
        resumeRequested: Boolean,
        playbackActive: Boolean,
        inactiveForMs: Long,
        quietWindowMs: Long,
    ): Boolean = pauseOwned &&
        resumeRequested &&
        !playbackActive &&
        inactiveForMs >= quietWindowMs
}

data class EarDetectionPauseRequestDecision(
    val dispatchPause: Boolean,
    val cancelPendingResume: Boolean,
)

object EarDetectionPauseRequestGate {
    fun decide(
        pauseOwned: Boolean,
        playbackActive: Boolean,
        anotherPauseOwnerActive: Boolean,
    ): EarDetectionPauseRequestDecision = when {
        pauseOwned -> EarDetectionPauseRequestDecision(
            dispatchPause = false,
            cancelPendingResume = true,
        )
        anotherPauseOwnerActive || !playbackActive -> EarDetectionPauseRequestDecision(
            dispatchPause = false,
            cancelPendingResume = false,
        )
        else -> EarDetectionPauseRequestDecision(
            dispatchPause = true,
            cancelPendingResume = false,
        )
    }
}

object ExplicitResumeRouteGate {
    fun shouldReevaluateRoute(
        awaitingExplicitResume: Boolean,
        playbackRoutedToTarget: Boolean,
    ): Boolean = awaitingExplicitResume && !playbackRoutedToTarget
}

object EarDetectionReceiverGate {
    fun shouldHandle(
        expectedGeneration: Long,
        currentGeneration: Long,
        wornCount: Int,
    ): Boolean = expectedGeneration == currentGeneration && wornCount > 0
}

enum class TakeoverRouteTimeoutAction {
    NONE,
    FAIL_ATTEMPT,
}

data class TakeoverRouteTimeoutState(
    val armed: Boolean = false,
    val generation: Long = 0L,
)

data class TakeoverRouteTimeoutTransition(
    val state: TakeoverRouteTimeoutState,
    val action: TakeoverRouteTimeoutAction,
)

object TakeoverRouteTimeoutMachine {
    fun arm(state: TakeoverRouteTimeoutState): TakeoverRouteTimeoutState =
        TakeoverRouteTimeoutState(
            armed = true,
            generation = state.generation + 1L,
        )

    fun disarm(state: TakeoverRouteTimeoutState): TakeoverRouteTimeoutState =
        state.copy(armed = false)

    fun onTimeout(
        state: TakeoverRouteTimeoutState,
        expectedGeneration: Long,
    ): TakeoverRouteTimeoutTransition = if (
        state.armed && state.generation == expectedGeneration
    ) {
        TakeoverRouteTimeoutTransition(
            state = disarm(state),
            action = TakeoverRouteTimeoutAction.FAIL_ATTEMPT,
        )
    } else {
        TakeoverRouteTimeoutTransition(
            state = state,
            action = TakeoverRouteTimeoutAction.NONE,
        )
    }
}

object TakeoverCompletionGate {
    fun start(pauseRequired: Boolean): TakeoverCompletionState =
        TakeoverCompletionState(pauseRequired = pauseRequired)

    /** A takeover owns at most one outstanding asynchronous media-pause request. */
    fun requestPause(state: TakeoverCompletionState): TakeoverPauseRequest {
        val required = state.copy(pauseRequired = true)
        val shouldDispatch = !required.pauseOutstanding && !required.pauseApplied
        return TakeoverPauseRequest(
            state = if (shouldDispatch) {
                required.copy(pauseOutstanding = true)
            } else {
                required
            },
            shouldDispatch = shouldDispatch,
        )
    }

    /** A confirmed pause followed by a new non-target STARTED edge needs a new pause barrier. */
    fun requestPauseForNewPlaybackEdge(
        state: TakeoverCompletionState,
    ): TakeoverPauseRequest = requestPause(
        if (state.pauseApplied) state.copy(pauseApplied = false) else state
    )

    fun pauseDispatchFailed(state: TakeoverCompletionState): TakeoverCompletionState =
        state.copy(pauseOutstanding = false)

    fun confirmPauseApplied(state: TakeoverCompletionState): TakeoverCompletionState =
        state.copy(pauseOutstanding = false, pauseApplied = true)

    fun updateRoute(
        state: TakeoverCompletionState,
        ready: Boolean,
        trigger: String,
    ): TakeoverRouteUpdate {
        val next = if (ready) {
            state.copy(
                routeReady = true,
                routeReadyTrigger = state.routeReadyTrigger ?: trigger,
            )
        } else {
            state.copy(routeReady = false, routeReadyTrigger = null)
        }
        return TakeoverRouteUpdate(
            state = next,
            enteredReady = !state.routeReady && next.routeReady,
            exitedReady = state.routeReady && !next.routeReady,
        )
    }

    /**
     * Failure cleanup must never enqueue a new pause. Media-key delivery is asynchronous, so a
     * pause created while tearing an attempt down can arrive in the next playback episode.
     */
    fun failureDecision(
        state: TakeoverCompletionState,
        resumeIfStillActive: Boolean,
        playbackStillActive: Boolean,
    ): TakeoverFailureDecision = TakeoverFailureDecision(
        shouldDispatchPause = false,
        shouldResume = false,
        keepPlaybackEpisodeOpen = resumeIfStillActive ||
            playbackStillActive ||
            state.pauseOutstanding,
        shouldReportPlaybackStopped = !playbackStillActive,
    )

    fun canComplete(state: TakeoverCompletionState): Boolean = canComplete(
        routeReady = state.routeReady,
        pauseRequired = state.pauseRequired,
        pauseApplied = state.pauseApplied,
    )

    fun canComplete(
        routeReady: Boolean,
        pauseRequired: Boolean,
        pauseApplied: Boolean,
    ): Boolean = routeReady && (!pauseRequired || pauseApplied)
}

data class TakeoverRouteObservationState(
    val attemptId: String? = null,
    val readiness: TakeoverRouteReadiness? = null,
    val showUiSent: Boolean = false,
)

data class TakeoverRouteObservation(
    val state: TakeoverRouteObservationState,
    val previousReadiness: TakeoverRouteReadiness?,
    val readinessChanged: Boolean,
    val shouldShowUi: Boolean,
)

object TakeoverRouteObservationGate {
    /** Tracks READY/UI state by attempt so retries cannot manufacture a second READY edge. */
    fun observe(
        state: TakeoverRouteObservationState,
        attemptId: String?,
        readiness: TakeoverRouteReadiness,
        allowShowUi: Boolean = true,
    ): TakeoverRouteObservation {
        val sameAttempt = state.attemptId == attemptId
        val previousReadiness = state.readiness.takeIf { sameAttempt }
        val alreadyShown = sameAttempt && state.showUiSent
        val shouldShowUi = attemptId != null &&
            readiness == TakeoverRouteReadiness.READY &&
            allowShowUi &&
            !alreadyShown
        return TakeoverRouteObservation(
            state = TakeoverRouteObservationState(
                attemptId = attemptId,
                readiness = readiness,
                showUiSent = alreadyShown || shouldShowUi,
            ),
            previousReadiness = previousReadiness,
            readinessChanged = previousReadiness != readiness,
            shouldShowUi = shouldShowUi,
        )
    }
}

data class PlaybackIdentity(
    val playerId: Int,
    val usage: Int,
)

object PendingPlaybackDeadlineGate {
    fun shouldKeepExistingDeadline(
        scheduledPlayers: Set<PlaybackIdentity>,
        currentPlayers: Set<PlaybackIdentity>,
        requestedDelayMs: Long,
        remainingDelayMs: Long,
    ): Boolean = scheduledPlayers == currentPlayers && requestedDelayMs >= remainingDelayMs
}

enum class TargetAudioOutput {
    TARGET,
    OTHER,
    UNKNOWN,
}

enum class TargetRouteEvidence {
    CONFIRMED_TARGET,
    CONFIRMED_OTHER,
    UNKNOWN,
}

object TargetAudioOutputClassifier {
    fun classify(
        isBluetoothA2dp: Boolean?,
        outputAddress: String?,
        targetAddress: String?,
    ): TargetAudioOutput {
        if (isBluetoothA2dp == null) return TargetAudioOutput.UNKNOWN
        if (!isBluetoothA2dp) return TargetAudioOutput.OTHER

        val output = LocalHostIdentity.normalize(outputAddress)
            ?: return TargetAudioOutput.UNKNOWN
        val target = LocalHostIdentity.normalize(targetAddress)
            ?: return TargetAudioOutput.UNKNOWN
        return if (output == target) TargetAudioOutput.TARGET else TargetAudioOutput.OTHER
    }

    fun aggregate(outputs: Collection<TargetAudioOutput>): TargetRouteEvidence = when {
        outputs.isEmpty() -> TargetRouteEvidence.UNKNOWN
        TargetAudioOutput.OTHER in outputs -> TargetRouteEvidence.CONFIRMED_OTHER
        outputs.all { it == TargetAudioOutput.TARGET } ->
            TargetRouteEvidence.CONFIRMED_TARGET
        else -> TargetRouteEvidence.UNKNOWN
    }

    fun confirmsTarget(outputs: Collection<TargetAudioOutput>): Boolean =
        aggregate(outputs) == TargetRouteEvidence.CONFIRMED_TARGET
}

object SmartRoutingTargetResolver {
    fun resolveTargets(
        selfAddress: String?,
        activeSourceAddress: String?,
        currentDeviceAddresses: Collection<String>,
        rememberedDeviceAddresses: Collection<String>,
    ): List<String> {
        val self = LocalHostIdentity.normalize(selfAddress) ?: return emptyList()
        return buildList {
            LocalHostIdentity.normalize(activeSourceAddress)?.let(::add)
            currentDeviceAddresses.mapNotNullTo(this, LocalHostIdentity::normalize)
            rememberedDeviceAddresses.mapNotNullTo(this, LocalHostIdentity::normalize)
        }.filter { it != self }
            .distinct()
    }

    fun resolve(
        selfAddress: String?,
        activeSourceAddress: String?,
        currentDeviceAddresses: Collection<String>,
        rememberedDeviceAddresses: Collection<String>,
    ): String? = resolveTargets(
        selfAddress,
        activeSourceAddress,
        currentDeviceAddresses,
        rememberedDeviceAddresses,
    ).firstOrNull()
}

object LocalHostIdentity {
    private val bluetoothMacPattern = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")
    private val unusableAddresses = setOf(
        "00:00:00:00:00:00",
        "02:00:00:00:00:00",
    )

    fun normalize(candidate: String?): String? {
        val normalized = candidate
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeUnless { it.equals("null", ignoreCase = true) }
            ?: return null
        return normalized.takeIf {
            bluetoothMacPattern.matches(it) && it !in unusableAddresses
        }
    }

    fun classifyKnownSource(
        localMac: String?,
        sourceMac: String?,
    ): TakeoverAudioSource {
        val normalizedLocal = normalize(localMac) ?: return TakeoverAudioSource.UNKNOWN
        val normalizedSource = normalize(sourceMac) ?: return TakeoverAudioSource.UNKNOWN
        return if (normalizedSource == normalizedLocal) {
            TakeoverAudioSource.LOCAL
        } else {
            TakeoverAudioSource.REMOTE
        }
    }

    fun inferFromActiveLocalMedia(
        currentLocalMac: String?,
        sourceMac: String?,
        ownsConnection: Boolean,
        a2dpPlaying: Boolean,
        freshLocalA2dpStart: Boolean,
        eligibleLocalMediaPlaying: Boolean,
    ): String? {
        normalize(currentLocalMac)?.let { return it }
        if ((!ownsConnection && !freshLocalA2dpStart) ||
            !a2dpPlaying ||
            !eligibleLocalMediaPlaying
        ) {
            return null
        }
        return normalize(sourceMac)
    }
}
