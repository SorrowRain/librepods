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

    fun startHeadTracking(
        owner: SensorResourceOwner,
        ownerIsCurrent: () -> Boolean,
    ): Boolean

    fun stopHeadTracking(
        owner: SensorResourceOwner,
        cleanup: OneShotSensorCleanup,
    )

    fun publishMeasurement(measurement: HeartRateMeasurement)
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
) {
    private val mutex = Mutex()
    private val headTrackingClaims = HeadTrackingClaimController()
    private var eligibilityState = HeartRateEligibilityState()
    private var sessionState = HeartRateSessionState()
    private var arbiterState = SensorResourceArbiterState()
    private val startJobs = mutableMapOf<HrmAcquisitionToken, Job>()
    private val watchdogJobs = mutableMapOf<HrmAcquisitionToken, Job>()

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
                applySessionEventLocked(HeartRateSessionEvent.Retry(acquisition))
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

            is HeartRateSessionAction.SendStartFrames -> startTransportLocked(action.acquisition)
            is HeartRateSessionAction.SendStopFrames -> stopTransportLocked(action.acquisition)
            is HeartRateSessionAction.CancelWatchdog -> cancelWatchdogLocked(action.acquisition)
            is HeartRateSessionAction.ScheduleWatchdog ->
                scheduleWatchdogLocked(action.acquisition, action.watchdog)
            is HeartRateSessionAction.PublishMeasurement -> {
                sink.publishMeasurement(action.measurement)
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
        }
    }

    private fun startTransportLocked(acquisition: HrmAcquisitionToken) {
        startJobs.remove(acquisition)?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val succeeded = try {
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
        val job = scope.launch(start = CoroutineStart.LAZY) {
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

    private fun scheduleFreshnessUpdateLocked(acquisition: HrmAcquisitionToken) {
        freshnessJob?.cancel()
        val job = scope.launch(start = CoroutineStart.LAZY) {
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
                        HeartRateSessionEvent.SensorResourceGranted(acquisition)
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
