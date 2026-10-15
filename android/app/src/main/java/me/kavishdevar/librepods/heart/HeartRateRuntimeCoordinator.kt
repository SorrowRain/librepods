/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.heart

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.kavishdevar.librepods.bluetooth.HeartRateSample
import me.kavishdevar.librepods.data.Capability

internal fun interface OneShotSensorCleanup {
    fun consume(): Boolean
}

internal interface HeartRateRuntimeSink {
    suspend fun startHeartRate(
        acquisition: HrmAcquisitionToken,
        acquisitionIsCurrent: () -> Boolean,
    ): Boolean

    fun stopHeartRate(
        acquisition: HrmAcquisitionToken,
        cleanup: OneShotSensorCleanup,
    ): Boolean

    /** Must only claim and queue recovery; transport teardown cannot run under the coordinator lock. */
    fun requestHeartRateTransportRecovery(
        acquisition: HrmAcquisitionToken,
        reason: HeartRateTransportRecoveryReason,
        acquisitionIsCurrent: () -> Boolean,
        onResult: (Boolean) -> Unit,
    ): Boolean

    fun startHeadTracking(
        owner: SensorResourceOwner,
        ownerIsCurrent: () -> Boolean,
    ): Boolean

    fun stopHeadTracking(
        owner: SensorResourceOwner,
        cleanup: OneShotSensorCleanup,
    )

    fun publishMeasurement(
        acquisition: HrmAcquisitionToken,
        measurement: HeartRateMeasurement,
    )
}

/**
 * Serializes the HRM reducer, shared sensor arbiter, transport jobs, and watchdogs.
 *
 * Slow START sequences run outside the reducer lock and carry the exact acquisition token back to
 * the reducer. Cleanup remains synchronous and one-shot so an old STOP cannot race a later capture.
 */
internal class HeartRateRuntimeCoordinator(
    private val scope: CoroutineScope,
    private val sink: HeartRateRuntimeSink,
    private val elapsedRealtimeMillis: () -> Long,
    private val suspendDelay: suspend (Long) -> Unit = { delay(it) },
    private val autoRetryEnabled: () -> Boolean = { true },
    private val autoRetrySuppressed: () -> Boolean = { false },
    private val watchdogScope: CoroutineScope = scope,
) {
    private val autoRetryBaseQuietPeriodMillis = 3_000L
    private val autoRetryMaxQuietPeriodMillis = 15_000L
    private val autoRetryQuietPeriodGrowthFactor = 1.6

    private val mutex = Mutex()
    private val headTrackingClaims = HeadTrackingClaimController()
    private var eligibilityState = HeartRateEligibilityState()
    private var sessionState = HeartRateSessionState()
    private var arbiterState = SensorResourceArbiterState()
    private val startJobs = mutableMapOf<HrmAcquisitionToken, Job>()
    private val watchdogJobs = mutableMapOf<HrmAcquisitionToken, Job>()
    private var autoRetryJob: Job? = null
    private var autoRetryAttempt = 0
    private var lastAutoRetryAcquisition: HrmAcquisitionToken? = null

    /**
     * The retry loop stays unbounded (the user wants a dead sensor to keep trying, never silently
     * stop), but the quiet period backs off so a transport that cannot recover stops hammering the
     * shared AACP socket every 3s. Backoff is exponential, capped at [autoRetryMaxQuietPeriodMillis],
     * and resets to the base delay once the session leaves the failing state.
     */
    private fun autoRetryQuietPeriodMillis(attempt: Int): Long {
        // attempt = number of failed auto retries so far (0 before the first failure).
        // The quiet period grows with each failure so a transport that cannot recover stops
        // hammering the shared AACP socket every 3s, capped at autoRetryMaxQuietPeriodMillis.
        val grown = autoRetryBaseQuietPeriodMillis *
            (autoRetryQuietPeriodGrowthFactor.toDouble().pow(attempt))
        return grown.roundToLong().coerceIn(
            autoRetryBaseQuietPeriodMillis,
            autoRetryMaxQuietPeriodMillis,
        )
    }

    private val _session = MutableStateFlow(sessionState)
    val session: StateFlow<HeartRateSessionState> = _session.asStateFlow()

    private val _headTrackingActive = MutableStateFlow(false)
    val headTrackingActive: StateFlow<Boolean> = _headTrackingActive.asStateFlow()

    private val _uiState = MutableStateFlow(HeartRateUiState())
    val uiState: StateFlow<HeartRateUiState> = _uiState.asStateFlow()

    private var freshnessJob: Job? = null

    @Volatile
    private var acquisitionSnapshot: HrmAcquisitionToken? = null

    @Volatile
    private var activeSensorOwnerSnapshot: SensorResourceOwner? = null

    private var headTrackingTransportOwner: SensorResourceOwner? = null

    fun installConnection(key: HrmSessionKey) {
        scope.launch {
            installConnectionAndAwait(key)
        }
    }

    suspend fun installConnectionAndAwait(key: HrmSessionKey) {
        mutex.withLock {
            executeHeadTrackingClaimActionsLocked(headTrackingClaims.installConnection(key))
            eligibilityState = HeartRateEligibilityReducer.reduce(
                eligibilityState,
                HeartRateEligibilityEvent.SessionInstalled(key),
            )
            applySessionEventLocked(
                HeartRateSessionEvent.TransportConnected(
                    key = key,
                    supported = false,
                    wear = HeartRateWearState(),
                )
            )
        }
    }

    fun confirmDeviceInformation(
        key: HrmSessionKey,
        modelNumber: String,
        modelCapabilities: Set<Capability>,
    ) {
        scope.launch {
            mutex.withLock {
                if (!sessionState.matchesKey(key)) return@withLock
                eligibilityState = HeartRateEligibilityReducer.reduce(
                    eligibilityState,
                    HeartRateEligibilityEvent.DeviceInformationConfirmed(
                        key = key,
                        modelNumber = modelNumber,
                        modelCapabilities = modelCapabilities,
                    ),
                )
                applySessionEventLocked(
                    HeartRateSessionEvent.TransportConnected(
                        key = key,
                        supported = eligibilityState.supported,
                        wear = sessionState.wear,
                    )
                )
            }
        }
    }

    fun updateWear(
        key: HrmSessionKey,
        wear: HeartRateWearState,
    ) {
        scope.launch {
            mutex.withLock {
                if (!sessionState.matchesKey(key)) return@withLock
                // TransportConnected also retains wear received before Device Information confirms
                // HRM support. WearChanged intentionally ignores an unsupported transport.
                applySessionEventLocked(
                    HeartRateSessionEvent.TransportConnected(
                        key = key,
                        supported = sessionState.supportedTransport,
                        wear = wear,
                    )
                )
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        scope.launch {
            setEnabledAndAwait(enabled)
        }
    }

    suspend fun setEnabledAndAwait(enabled: Boolean) {
        mutex.withLock {
            applySessionEventLocked(
                if (enabled) HeartRateSessionEvent.Enable else HeartRateSessionEvent.Disable
            )
        }
    }

    fun retry() {
        scope.launch {
            mutex.withLock {
                val acquisition = sessionState.acquisition ?: return@withLock
                applySessionEventLocked(
                    HeartRateSessionEvent.Retry(
                        acquisition,
                        HeartRateTransportRecoveryReason.USER_RETRY,
                    )
                )
            }
        }
    }

    /**
     * Re-evaluates auto retry when conditions outside session events change, e.g. another host
     * starts or stops owning the AirPods audio. A pending retry is cancelled while suppressed and
     * re-armed once the gate opens.
     */
    fun refreshAutoRetryGate() {
        scope.launch {
            mutex.withLock {
                updateAutoRetryLocked(sessionState)
                updatePresentationLocked()
            }
        }
    }

    /** Starts a transport-reset request from the reader without touching the shared socket. */
    fun requestTransportRecovery(
        acquisition: HrmAcquisitionToken,
        reason: HeartRateTransportRecoveryReason,
    ) {
        scope.launch {
            mutex.withLock {
                applySessionEventLocked(
                    HeartRateSessionEvent.TransportFailure(acquisition, reason)
                )
            }
        }
    }

    /** Delivers the asynchronous same-lease recovery result back to the reducer. */
    fun completeTransportRecovery(
        acquisition: HrmAcquisitionToken,
        accepted: Boolean,
    ) {
        scope.launch {
            mutex.withLock {
                applySessionEventLocked(
                    HeartRateSessionEvent.TransportRecoveryReady(acquisition, accepted)
                )
            }
        }
    }

    fun acceptSample(
        key: HrmSessionKey,
        sample: HeartRateSample,
    ) {
        val acquisition = acquisitionSnapshot?.takeIf { it.key.matches(key) } ?: return
        scope.launch {
            mutex.withLock {
                applySessionEventLocked(
                    HeartRateSessionEvent.SampleReceived(
                        acquisition = acquisition,
                        measurement = HeartRateMeasurement(
                            bpm = sample.bpm,
                            sequence = sample.sequence.toLong(),
                            wallClockMillis = sample.receivedAtMillis,
                            elapsedRealtimeMillis = sample.receivedAtElapsedRealtime,
                        ),
                    )
                )
            }
        }
    }

    fun requestHeadTracking(
        key: HrmSessionKey,
        source: HeadTrackingRequestSource,
    ) {
        scope.launch {
            mutex.withLock {
                executeHeadTrackingClaimActionsLocked(headTrackingClaims.request(key, source))
            }
        }
    }

    /** Requests a grant without leaving a deferred claim behind when HRM owns the sensor. */
    suspend fun requestHeadTrackingImmediately(
        key: HrmSessionKey,
        source: HeadTrackingRequestSource,
    ): Boolean = mutex.withLock {
        executeHeadTrackingClaimActionsLocked(headTrackingClaims.request(key, source))
        val granted = headTrackingClaims.isSourceGranted(key, source)
        if (!granted) {
            executeHeadTrackingClaimActionsLocked(headTrackingClaims.release(key, source))
        }
        granted
    }

    fun releaseHeadTracking(
        key: HrmSessionKey,
        source: HeadTrackingRequestSource,
    ) {
        scope.launch {
            mutex.withLock {
                executeHeadTrackingClaimActionsLocked(headTrackingClaims.release(key, source))
            }
        }
    }

    suspend fun disconnectAndAwait(key: HrmSessionKey) {
        mutex.withLock {
            if (!sessionState.matchesKey(key)) return
            executeHeadTrackingClaimActionsLocked(headTrackingClaims.disconnect(key))
            applySessionEventLocked(HeartRateSessionEvent.TransportDisconnected(key))
            eligibilityState = HeartRateEligibilityReducer.reduce(
                eligibilityState,
                HeartRateEligibilityEvent.SessionCleared(key),
            )
            updatePresentationLocked()
        }
    }

    suspend fun destroyAndAwait() {
        mutex.withLock {
            executeHeadTrackingClaimActionsLocked(headTrackingClaims.destroy())
            applySessionEventLocked(HeartRateSessionEvent.Destroy)
            eligibilityState = HeartRateEligibilityReducer.reduce(
                eligibilityState,
                HeartRateEligibilityEvent.Destroy,
            )
            val transition = SensorResourceArbiter.reduce(
                arbiterState,
                SensorResourceEvent.Destroy,
            )
            applyArbiterTransitionLocked(transition)
            startJobs.values.forEach(Job::cancel)
            startJobs.clear()
            watchdogJobs.values.forEach(Job::cancel)
            watchdogJobs.clear()
            cancelAutoRetryLocked()
            freshnessJob?.cancel()
            freshnessJob = null
            updatePresentationLocked()
        }
    }

    fun isAcquisitionCurrent(acquisition: HrmAcquisitionToken): Boolean =
        acquisitionSnapshot?.matches(acquisition) == true

    fun isHeadTrackingOwnerCurrent(owner: SensorResourceOwner): Boolean =
        activeSensorOwnerSnapshot == owner &&
            owner.resource == SensorResource.HEAD_TRACKING

    fun currentKey(): HrmSessionKey? = _session.value.key

    private fun applySessionEventLocked(event: HeartRateSessionEvent) {
        val transition = HeartRateSessionReducer.reduce(
            current = sessionState,
            event = event,
            nowMillis = elapsedRealtimeMillis(),
        )
        sessionState = transition.state
        acquisitionSnapshot = sessionState.acquisition
        _session.value = sessionState
        updatePresentationLocked()
        transition.actions.forEach(::executeSessionActionLocked)
        updateAutoRetryLocked(sessionState)
    }

    private fun executeSessionActionLocked(action: HeartRateSessionAction) {
        when (action) {
            is HeartRateSessionAction.ClaimSensorResource -> {
                applyArbiterTransitionLocked(
                    SensorResourceArbiter.reduce(
                        arbiterState,
                        SensorResourceEvent.Request(
                            SensorResourceOwner.heartRate(action.acquisition)
                        ),
                    )
                )
            }

            is HeartRateSessionAction.SendStartFrames -> startTransportLocked(
                action.acquisition,
                action.quietPeriodMillis,
            )
            is HeartRateSessionAction.SendStopFrames -> stopTransportLocked(action.acquisition)
            is HeartRateSessionAction.CancelWatchdog -> cancelWatchdogLocked(action.acquisition)
            is HeartRateSessionAction.ScheduleWatchdog ->
                scheduleWatchdogLocked(action.acquisition, action.watchdog)
            is HeartRateSessionAction.PublishMeasurement -> {
                sink.publishMeasurement(action.acquisition, action.measurement)
                scheduleFreshnessUpdateLocked(action.acquisition)
            }
            is HeartRateSessionAction.ReleaseSensorResource -> {
                applyArbiterTransitionLocked(
                    SensorResourceArbiter.reduce(
                        arbiterState,
                        SensorResourceEvent.Release(
                            SensorResourceOwner.heartRate(action.acquisition)
                        ),
                    )
                )
            }
            is HeartRateSessionAction.ClearMeasurements -> {
                freshnessJob?.cancel()
                freshnessJob = null
                updatePresentationLocked()
            }
            is HeartRateSessionAction.RequestTransportRecovery -> {
                val accepted = runCatching {
                    sink.requestHeartRateTransportRecovery(
                        acquisition = action.acquisition,
                        reason = action.reason,
                        acquisitionIsCurrent = {
                            isAcquisitionCurrent(action.acquisition)
                        },
                        onResult = { result ->
                            completeTransportRecovery(action.acquisition, result)
                        },
                    )
                }.getOrDefault(false)
                applySessionEventLocked(
                    HeartRateSessionEvent.TransportRecoveryResult(
                        action.acquisition,
                        accepted,
                    )
                )
            }
        }
    }

    private fun startTransportLocked(
        acquisition: HrmAcquisitionToken,
        quietPeriodMillis: Long,
    ) {
        startJobs.remove(acquisition)?.cancel()
        val job = watchdogScope.launch(start = CoroutineStart.LAZY) {
            val succeeded = try {
                if (quietPeriodMillis > 0L) suspendDelay(quietPeriodMillis)
                sink.startHeartRate(acquisition) {
                    isAcquisitionCurrent(acquisition)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            mutex.withLock {
                startJobs.remove(acquisition)
                applySessionEventLocked(
                    HeartRateSessionEvent.StartFramesSent(acquisition, succeeded)
                )
            }
        }
        startJobs[acquisition] = job
        job.start()
    }

    /**
     * Stops the transport synchronously while holding the mutex. This is a deliberate trade-off:
     * the reducer emits STOP then START (restartOrFail, transportFailure) and relies on the old
     * STOP write completing before the new START acquires the sensor resource. Deferring STOP off
     * the mutex would let a stale sensor keep streaming under a replacement acquisition. The
     * synchronous write is bounded in practice by the L2CAP socket's own buffering and by the
     * reconnect/watchdog path closing a stalled socket; see HeartRateSession for the documented
     * limitation.
     */
    private fun stopTransportLocked(acquisition: HrmAcquisitionToken) {
        startJobs.remove(acquisition)?.cancel()
        cancelWatchdogLocked(acquisition)
        sink.stopHeartRate(acquisition, oneShotCleanup())
    }

    private fun scheduleWatchdogLocked(
        acquisition: HrmAcquisitionToken,
        watchdog: HeartRateWatchdog,
    ) {
        cancelWatchdogLocked(acquisition)
        val job = watchdogScope.launch(start = CoroutineStart.LAZY) {
            suspendDelay((watchdog.deadlineMillis - elapsedRealtimeMillis()).coerceAtLeast(0L))
            mutex.withLock {
                watchdogJobs.remove(acquisition)
                applySessionEventLocked(HeartRateSessionEvent.Tick(acquisition))
            }
        }
        watchdogJobs[acquisition] = job
        job.start()
    }

    private fun cancelWatchdogLocked(acquisition: HrmAcquisitionToken) {
        watchdogJobs.remove(acquisition)?.cancel()
    }

    private fun updateAutoRetryLocked(state: HeartRateSessionState) {
        val acquisition = state.acquisition
        // Reset the backoff only on a genuinely new episode: a sustained LIVE reading or a fresh
        // acquisition (wear change / new connection, which mints a new token via beginCapture).
        // CALIBRATING (the first warmup sample) is deliberately NOT a reset — a device that
        // streams a few samples then dies should keep backing off rather than hammering the shared
        // AACP socket at the base 3s forever. RECONNECTING is excluded: a recovery is in flight.
        if (state.status == HeartRateSessionStatus.LIVE ||
            (state.acquisition != null && state.acquisition != lastAutoRetryAcquisition)
        ) {
            autoRetryAttempt = 0
            lastAutoRetryAcquisition = state.acquisition
        }
        val shouldAutoRetry = !state.destroyed &&
            state.enabled &&
            state.supportedTransport &&
            state.wear.isWorn &&
            state.status == HeartRateSessionStatus.COULDNT_START &&
            autoRetryEnabled() &&
            !autoRetrySuppressed() &&
            acquisition != null
        if (!shouldAutoRetry) {
            cancelAutoRetryLocked()
            return
        }
        if (autoRetryJob?.isActive == true) return

        val quietPeriodMillis = autoRetryQuietPeriodMillis(autoRetryAttempt)
        val job = watchdogScope.launch(start = CoroutineStart.LAZY) {
            suspendDelay(quietPeriodMillis)
            mutex.withLock {
                autoRetryJob = null
                val current = sessionState
                if (!current.destroyed &&
                    current.enabled &&
                    current.supportedTransport &&
                    current.wear.isWorn &&
                    current.status == HeartRateSessionStatus.COULDNT_START &&
                    autoRetryEnabled() &&
                    !autoRetrySuppressed() &&
                    acquisitionSnapshot?.matches(acquisition) == true
                ) {
                    autoRetryAttempt++
                    applySessionEventLocked(
                        HeartRateSessionEvent.Retry(
                            acquisition,
                            HeartRateTransportRecoveryReason.AUTO_RETRY,
                        )
                    )
                }
            }
        }
        autoRetryJob = job
        job.start()
    }

    private fun cancelAutoRetryLocked() {
        autoRetryJob?.cancel()
        autoRetryJob = null
    }

    private fun scheduleFreshnessUpdateLocked(acquisition: HrmAcquisitionToken) {
        freshnessJob?.cancel()
        val job = watchdogScope.launch(start = CoroutineStart.LAZY) {
            suspendDelay(HeartRateUiStateMapper.LIVE_SAMPLE_FRESHNESS_MILLIS + 1L)
            mutex.withLock {
                if (acquisitionSnapshot?.matches(acquisition) == true) {
                    updatePresentationLocked()
                }
            }
        }
        freshnessJob = job
        job.start()
    }

    private fun updatePresentationLocked() {
        _uiState.value = HeartRateUiStateMapper.map(
            eligibility = eligibilityState,
            session = sessionState,
            nowElapsedRealtimeMillis = elapsedRealtimeMillis(),
            autoRetryPaused = autoRetryEnabled() && autoRetrySuppressed(),
            autoRetryAttempt = autoRetryAttempt.takeIf { autoRetryEnabled() } ?: 0,
        )
    }

    private fun executeHeadTrackingClaimActionsLocked(
        actions: List<HeadTrackingClaimAction>,
    ) {
        actions.forEach { action ->
            val event = when (action) {
                is HeadTrackingClaimAction.Request -> SensorResourceEvent.Request(action.owner)
                is HeadTrackingClaimAction.Release -> SensorResourceEvent.Release(action.owner)
            }
            applyArbiterTransitionLocked(SensorResourceArbiter.reduce(arbiterState, event))
        }
    }

    private fun applyArbiterTransitionLocked(transition: SensorResourceTransition) {
        arbiterState = transition.state
        activeSensorOwnerSnapshot = arbiterState.activeOwner
        transition.actions.forEach { action ->
            when (action) {
                is SensorResourceAction.StartHeartRate -> {
                    val acquisition = action.owner.toHeartRateAcquisition() ?: return@forEach
                    applySessionEventLocked(
                        HeartRateSessionEvent.SensorResourceGranted(
                            acquisition,
                            quietPeriodMillis = action.quietPeriodMillis,
                        )
                    )
                }
                is SensorResourceAction.StopHeartRate -> {
                    action.owner.toHeartRateAcquisition()?.let { acquisition ->
                        startJobs.remove(acquisition)?.cancel()
                    }
                }
                is SensorResourceAction.StartHeadTracking -> {
                    val started = sink.startHeadTracking(action.owner) {
                        isHeadTrackingOwnerCurrent(action.owner)
                    }
                    headTrackingTransportOwner = action.owner.takeIf { started }
                }
                is SensorResourceAction.StopHeadTracking -> {
                    sink.stopHeadTracking(action.owner, oneShotCleanup())
                    if (headTrackingTransportOwner == action.owner) {
                        headTrackingTransportOwner = null
                    }
                    // The transport stop also terminates gesture detection. Revoke that exact
                    // connection's intent in-lock so it cannot revive after HRM releases the
                    // sensor, and avoid a service callback re-entering this mutex.
                    executeHeadTrackingClaimActionsLocked(
                        headTrackingClaims.release(
                            action.owner.connection,
                            HeadTrackingRequestSource.GESTURE,
                        )
                    )
                }
            }
        }
        activeSensorOwnerSnapshot = arbiterState.activeOwner
        _headTrackingActive.value =
            headTrackingClaims.onActiveOwnerChanged(
                headTrackingTransportOwner?.takeIf { it == arbiterState.activeOwner }
            )
    }

    private fun SensorResourceOwner.toHeartRateAcquisition(): HrmAcquisitionToken? =
        takeIf { it.resource == SensorResource.HEART_RATE }?.let {
            HrmAcquisitionToken(it.connection, it.acquisition)
        }

    private fun HeartRateSessionState.matchesKey(key: HrmSessionKey): Boolean =
        this.key?.matches(key) == true

    private fun oneShotCleanup(): OneShotSensorCleanup {
        val available = AtomicBoolean(true)
        return OneShotSensorCleanup { available.compareAndSet(true, false) }
    }
}
