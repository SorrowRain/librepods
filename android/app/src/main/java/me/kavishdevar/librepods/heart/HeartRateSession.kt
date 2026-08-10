/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.heart

/** Identifies one AACP connection without depending on Android Bluetooth classes. */
internal class HrmSessionKey(
    val socketIdentity: Any,
    val generation: Long,
    val deviceKey: String,
    val epoch: Long,
) {
    fun matches(other: HrmSessionKey): Boolean =
        socketIdentity === other.socketIdentity &&
            generation == other.generation &&
            deviceKey == other.deviceKey &&
            epoch == other.epoch

    override fun equals(other: Any?): Boolean =
        other is HrmSessionKey && matches(other)

    override fun hashCode(): Int {
        var result = System.identityHashCode(socketIdentity)
        result = 31 * result + generation.hashCode()
        result = 31 * result + deviceKey.hashCode()
        result = 31 * result + epoch.hashCode()
        return result
    }

    override fun toString(): String =
        "HrmSessionKey(generation=$generation, epoch=$epoch)"
}

/**
 * Identifies one capture attempt within a connection.
 *
 * Re-wear, Disable/Enable, a bounded stream restart, and manual Retry each mint a new acquisition.
 */
internal data class HrmAcquisitionToken(
    val key: HrmSessionKey,
    val acquisition: Long,
) {
    fun matches(other: HrmAcquisitionToken): Boolean =
        acquisition == other.acquisition && key.matches(other.key)

    override fun toString(): String =
        "HrmAcquisitionToken(generation=${key.generation}, " +
            "epoch=${key.epoch}, acquisition=$acquisition)"
}

internal enum class HeartRateSessionStatus {
    OFF,
    WAITING_FOR_AIRPODS,
    WAITING_TO_BE_WORN,
    STARTING,
    CALIBRATING,
    LIVE,
    RECONNECTING,
    COULDNT_START,
}

internal data class HeartRateWearState(
    val leftWorn: Boolean = false,
    val rightWorn: Boolean = false,
) {
    val isWorn: Boolean
        get() = leftWorn || rightWorn
}

/** A decoder-independent, validated measurement carried through the lifecycle reducer. */
internal data class HeartRateMeasurement(
    val bpm: Int,
    val sequence: Long,
    val wallClockMillis: Long,
    val elapsedRealtimeMillis: Long,
) {
    override fun toString(): String = "HeartRateMeasurement(redacted)"
}

internal enum class HeartRateWatchdogKind {
    FIRST_SAMPLE,
    STREAM_STALL,
}

internal enum class HeartRateTransportRecoveryReason {
    AUTOMATIC,
    USER_RETRY,
}

internal data class HeartRateWatchdog(
    val kind: HeartRateWatchdogKind,
    val deadlineMillis: Long,
)

internal data class HeartRateSessionState(
    val enabled: Boolean = false,
    val status: HeartRateSessionStatus = HeartRateSessionStatus.OFF,
    val key: HrmSessionKey? = null,
    val acquisition: HrmAcquisitionToken? = null,
    val nextAcquisition: Long = 0L,
    val supportedTransport: Boolean = false,
    val wear: HeartRateWearState = HeartRateWearState(),
    val acceptingSamples: Boolean = false,
    val warmupSamplesRemaining: Int = 0,
    val restartAttempts: Int = 0,
    val watchdog: HeartRateWatchdog? = null,
    val measurements: List<HeartRateMeasurement> = emptyList(),
    val sensorResourceClaimPending: Boolean = false,
    val ownsSensorResource: Boolean = false,
    val stopFrameNeeded: Boolean = false,
    val highestEpoch: Long = Long.MIN_VALUE,
    val destroyed: Boolean = false,
) {
    val latestMeasurement: HeartRateMeasurement?
        get() = measurements.lastOrNull()
}

internal sealed interface HeartRateSessionEvent {
    data object Enable : HeartRateSessionEvent
    data object Disable : HeartRateSessionEvent

    data class TransportConnected(
        val key: HrmSessionKey,
        val supported: Boolean,
        val wear: HeartRateWearState = HeartRateWearState(),
    ) : HeartRateSessionEvent

    data class TransportDisconnected(val key: HrmSessionKey) : HeartRateSessionEvent

    data class WearChanged(
        val key: HrmSessionKey,
        val wear: HeartRateWearState,
    ) : HeartRateSessionEvent

    data class SensorResourceGranted(
        val acquisition: HrmAcquisitionToken,
        val quietPeriodMillis: Long = 0L,
    ) : HeartRateSessionEvent

    data class StartFramesSent(
        val acquisition: HrmAcquisitionToken,
        val succeeded: Boolean,
    ) : HeartRateSessionEvent

    data class SampleReceived(
        val acquisition: HrmAcquisitionToken,
        val measurement: HeartRateMeasurement,
    ) : HeartRateSessionEvent

    data class Tick(val acquisition: HrmAcquisitionToken) : HeartRateSessionEvent
    data class Retry(val acquisition: HrmAcquisitionToken) : HeartRateSessionEvent
    data class TransportRecoveryResult(
        val acquisition: HrmAcquisitionToken,
        val accepted: Boolean,
    ) : HeartRateSessionEvent
    data class TransportRecoveryReady(
        val acquisition: HrmAcquisitionToken,
        val accepted: Boolean,
    ) : HeartRateSessionEvent
    data class TransportFailure(
        val acquisition: HrmAcquisitionToken,
        val reason: HeartRateTransportRecoveryReason,
    ) : HeartRateSessionEvent
    data class Stop(val acquisition: HrmAcquisitionToken) : HeartRateSessionEvent
    data object Destroy : HeartRateSessionEvent
}

internal sealed interface HeartRateSessionAction {
    val acquisition: HrmAcquisitionToken

    data class ClaimSensorResource(
        override val acquisition: HrmAcquisitionToken,
    ) : HeartRateSessionAction

    data class SendStartFrames(
        override val acquisition: HrmAcquisitionToken,
        val quietPeriodMillis: Long = 0L,
    ) : HeartRateSessionAction

    data class SendStopFrames(
        override val acquisition: HrmAcquisitionToken,
    ) : HeartRateSessionAction

    data class CancelWatchdog(
        override val acquisition: HrmAcquisitionToken,
    ) : HeartRateSessionAction

    data class ScheduleWatchdog(
        override val acquisition: HrmAcquisitionToken,
        val watchdog: HeartRateWatchdog,
    ) : HeartRateSessionAction

    data class PublishMeasurement(
        override val acquisition: HrmAcquisitionToken,
        val measurement: HeartRateMeasurement,
    ) : HeartRateSessionAction

    data class ReleaseSensorResource(
        override val acquisition: HrmAcquisitionToken,
    ) : HeartRateSessionAction

    data class ClearMeasurements(
        override val acquisition: HrmAcquisitionToken,
    ) : HeartRateSessionAction

    data class RequestTransportRecovery(
        override val acquisition: HrmAcquisitionToken,
        val reason: HeartRateTransportRecoveryReason,
    ) : HeartRateSessionAction
}

internal data class HeartRateSessionTransition(
    val state: HeartRateSessionState,
    val actions: List<HeartRateSessionAction> = emptyList(),
)

/** Pure HRM lifecycle reducer. Callers execute actions only for the action's exact acquisition. */
internal object HeartRateSessionReducer {
    const val FIRST_SAMPLE_TIMEOUT_MILLIS = 8_000L
    const val STREAM_STALL_TIMEOUT_MILLIS = 2_000L
    const val WARMUP_SAMPLE_COUNT = 4
    const val MAX_RESTART_ATTEMPTS = 1
    const val MAX_MEASUREMENTS = 60

    fun reduce(
        current: HeartRateSessionState,
        event: HeartRateSessionEvent,
        nowMillis: Long,
    ): HeartRateSessionTransition {
        if (current.destroyed) return unchanged(current)

        return when (event) {
            HeartRateSessionEvent.Enable -> enable(current)
            HeartRateSessionEvent.Disable -> disable(current)
            is HeartRateSessionEvent.TransportConnected ->
                transportConnected(current, event)
            is HeartRateSessionEvent.TransportDisconnected ->
                transportDisconnected(current, event)
            is HeartRateSessionEvent.WearChanged -> wearChanged(current, event)
            is HeartRateSessionEvent.SensorResourceGranted ->
                sensorResourceGranted(current, event)
            is HeartRateSessionEvent.StartFramesSent ->
                startFramesSent(current, event, nowMillis)
            is HeartRateSessionEvent.SampleReceived ->
                sampleReceived(current, event, nowMillis)
            is HeartRateSessionEvent.Tick -> tick(current, event, nowMillis)
            is HeartRateSessionEvent.Retry -> retry(current, event)
            is HeartRateSessionEvent.TransportRecoveryResult ->
                transportRecoveryResult(current, event)
            is HeartRateSessionEvent.TransportRecoveryReady ->
                transportRecoveryReady(current, event)
            is HeartRateSessionEvent.TransportFailure ->
                transportFailure(current, event)
            is HeartRateSessionEvent.Stop -> stop(current, event)
            HeartRateSessionEvent.Destroy -> destroy(current)
        }
    }

    private fun enable(current: HeartRateSessionState): HeartRateSessionTransition {
        if (current.enabled) return unchanged(current)

        val enabled = current.copy(enabled = true)
        val key = enabled.key
        return when {
            key == null || !enabled.supportedTransport -> HeartRateSessionTransition(
                enabled.resetCapture(HeartRateSessionStatus.WAITING_FOR_AIRPODS),
            )
            !enabled.wear.isWorn -> HeartRateSessionTransition(
                enabled.resetCapture(HeartRateSessionStatus.WAITING_TO_BE_WORN),
            )
            else -> beginCapture(enabled, key, restartAttempts = 0)
        }
    }

    private fun disable(current: HeartRateSessionState): HeartRateSessionTransition {
        if (!current.enabled && current.status == HeartRateSessionStatus.OFF) {
            return unchanged(current)
        }

        return HeartRateSessionTransition(
            current.resetCapture(HeartRateSessionStatus.OFF).copy(enabled = false),
            cleanupActions(current),
        )
    }

    private fun transportConnected(
        current: HeartRateSessionState,
        event: HeartRateSessionEvent.TransportConnected,
    ): HeartRateSessionTransition {
        val currentKey = current.key
        if (currentKey != null && currentKey.matches(event.key)) {
            val supportChanged = current.supportedTransport != event.supported
            val wearChanged = current.wear != event.wear
            if (!supportChanged && !wearChanged) return unchanged(current)

            val updated = current.copy(
                supportedTransport = event.supported,
                wear = event.wear,
            )
            if (!event.supported) {
                return HeartRateSessionTransition(
                    updated.resetCapture(
                        if (current.enabled) {
                            HeartRateSessionStatus.WAITING_FOR_AIRPODS
                        } else {
                            HeartRateSessionStatus.OFF
                        }
                    ),
                    cleanupActions(current),
                )
            }
            if (!updated.enabled) {
                return HeartRateSessionTransition(
                    updated.resetCapture(HeartRateSessionStatus.OFF),
                    cleanupActions(current),
                )
            }
            if (!event.wear.isWorn) {
                return HeartRateSessionTransition(
                    updated.resetCapture(HeartRateSessionStatus.WAITING_TO_BE_WORN),
                    cleanupActions(current),
                )
            }
            if (current.supportedTransport && current.wear.isWorn) {
                return unchanged(updated)
            }
            return beginCapture(updated, event.key, restartAttempts = 0)
        }

        if (event.key.epoch <= current.highestEpoch) return unchanged(current)

        val oldActions = cleanupActions(current)
        val connected = current.resetCapture(
            when {
                !current.enabled -> HeartRateSessionStatus.OFF
                !event.supported -> HeartRateSessionStatus.WAITING_FOR_AIRPODS
                !event.wear.isWorn -> HeartRateSessionStatus.WAITING_TO_BE_WORN
                else -> HeartRateSessionStatus.STARTING
            }
        ).copy(
            key = event.key,
            supportedTransport = event.supported,
            wear = event.wear,
            highestEpoch = event.key.epoch,
        )

        if (!connected.enabled || !event.supported || !event.wear.isWorn) {
            return HeartRateSessionTransition(connected, oldActions)
        }

        val started = beginCapture(connected, event.key, restartAttempts = 0)
        return started.copy(actions = oldActions + started.actions)
    }

    private fun transportDisconnected(
        current: HeartRateSessionState,
        event: HeartRateSessionEvent.TransportDisconnected,
    ): HeartRateSessionTransition {
        if (!current.matches(event.key)) return unchanged(current)

        return HeartRateSessionTransition(
            current.resetCapture(
                if (current.enabled) {
                    HeartRateSessionStatus.RECONNECTING
                } else {
                    HeartRateSessionStatus.OFF
                }
            ).copy(
                key = null,
                supportedTransport = false,
                wear = HeartRateWearState(),
            ),
            cleanupActions(current),
        )
    }

    private fun wearChanged(
        current: HeartRateSessionState,
        event: HeartRateSessionEvent.WearChanged,
    ): HeartRateSessionTransition {
        if (!current.matches(event.key) || !current.supportedTransport) {
            return unchanged(current)
        }
        if (current.wear == event.wear) return unchanged(current)

        val wasWorn = current.wear.isWorn
        val isWorn = event.wear.isWorn
        val updated = current.copy(wear = event.wear)
        if (!current.enabled || wasWorn == isWorn) return unchanged(updated)

        return if (isWorn) {
            beginCapture(updated, event.key, restartAttempts = 0)
        } else {
            HeartRateSessionTransition(
                updated.resetCapture(HeartRateSessionStatus.WAITING_TO_BE_WORN),
                cleanupActions(current),
            )
        }
    }

    private fun startFramesSent(
        current: HeartRateSessionState,
        event: HeartRateSessionEvent.StartFramesSent,
        nowMillis: Long,
    ): HeartRateSessionTransition {
        if (!current.matches(event.acquisition) ||
            !current.enabled ||
            !current.supportedTransport ||
            !current.wear.isWorn ||
            !current.ownsSensorResource ||
            current.acceptingSamples ||
            current.status !in setOf(
                HeartRateSessionStatus.STARTING,
                HeartRateSessionStatus.RECONNECTING,
            )
        ) {
            return unchanged(current)
        }

        if (!event.succeeded) return restartOrFail(current, event.acquisition)

        val watchdog = HeartRateWatchdog(
            HeartRateWatchdogKind.FIRST_SAMPLE,
            deadlineAfter(nowMillis, FIRST_SAMPLE_TIMEOUT_MILLIS),
        )
        return HeartRateSessionTransition(
            current.copy(acceptingSamples = true, watchdog = watchdog),
            listOf(
                HeartRateSessionAction.CancelWatchdog(event.acquisition),
                HeartRateSessionAction.ScheduleWatchdog(event.acquisition, watchdog),
            ),
        )
    }

    private fun sensorResourceGranted(
        current: HeartRateSessionState,
        event: HeartRateSessionEvent.SensorResourceGranted,
    ): HeartRateSessionTransition {
        if (!current.matches(event.acquisition) ||
            !current.enabled ||
            !current.supportedTransport ||
            !current.wear.isWorn ||
            !current.sensorResourceClaimPending ||
            current.ownsSensorResource ||
            current.status !in setOf(
                HeartRateSessionStatus.STARTING,
                HeartRateSessionStatus.RECONNECTING,
            )
        ) {
            return unchanged(current)
        }

        return HeartRateSessionTransition(
            current.copy(
                sensorResourceClaimPending = false,
                ownsSensorResource = true,
                stopFrameNeeded = true,
            ),
            listOf(
                HeartRateSessionAction.SendStartFrames(
                    event.acquisition,
                    quietPeriodMillis = event.quietPeriodMillis,
                )
            ),
        )
    }

    private fun sampleReceived(
        current: HeartRateSessionState,
        event: HeartRateSessionEvent.SampleReceived,
        nowMillis: Long,
    ): HeartRateSessionTransition {
        if (!current.matches(event.acquisition) ||
            !current.acceptingSamples ||
            !current.enabled ||
            !current.wear.isWorn ||
            current.status !in setOf(
                HeartRateSessionStatus.STARTING,
                HeartRateSessionStatus.RECONNECTING,
                HeartRateSessionStatus.CALIBRATING,
                HeartRateSessionStatus.LIVE,
            )
        ) {
            return unchanged(current)
        }

        val watchdog = current.watchdog ?: return unchanged(current)
        if (nowMillis >= watchdog.deadlineMillis) {
            return restartOrFail(current, event.acquisition)
        }

        val refreshedWatchdog = HeartRateWatchdog(
            HeartRateWatchdogKind.STREAM_STALL,
            deadlineAfter(nowMillis, STREAM_STALL_TIMEOUT_MILLIS),
        )
        val isWarmupSample = current.warmupSamplesRemaining > 0
        val measurements = if (isWarmupSample) {
            current.measurements
        } else {
            (current.measurements + event.measurement).takeLast(MAX_MEASUREMENTS)
        }
        val next = current.copy(
            status = if (isWarmupSample) {
                HeartRateSessionStatus.CALIBRATING
            } else {
                HeartRateSessionStatus.LIVE
            },
            warmupSamplesRemaining =
                (current.warmupSamplesRemaining - 1).coerceAtLeast(0),
            restartAttempts = if (isWarmupSample) current.restartAttempts else 0,
            watchdog = refreshedWatchdog,
            measurements = measurements,
        )
        val actions = buildList {
            add(HeartRateSessionAction.CancelWatchdog(event.acquisition))
            if (!isWarmupSample) {
                add(
                    HeartRateSessionAction.PublishMeasurement(
                        event.acquisition,
                        event.measurement,
                    )
                )
            }
            add(HeartRateSessionAction.ScheduleWatchdog(event.acquisition, refreshedWatchdog))
        }
        return HeartRateSessionTransition(next, actions)
    }

    private fun tick(
        current: HeartRateSessionState,
        event: HeartRateSessionEvent.Tick,
        nowMillis: Long,
    ): HeartRateSessionTransition {
        if (!current.matches(event.acquisition)) return unchanged(current)
        val watchdog = current.watchdog ?: return unchanged(current)
        if (nowMillis < watchdog.deadlineMillis) return unchanged(current)
        return restartOrFail(current, event.acquisition)
    }

    private fun retry(
        current: HeartRateSessionState,
        event: HeartRateSessionEvent.Retry,
    ): HeartRateSessionTransition {
        if (!current.matches(event.acquisition) ||
            !current.enabled ||
            !current.supportedTransport ||
            !current.wear.isWorn ||
            current.status != HeartRateSessionStatus.COULDNT_START
        ) {
            return unchanged(current)
        }
        return HeartRateSessionTransition(
            current,
            listOf(
                HeartRateSessionAction.RequestTransportRecovery(
                    event.acquisition,
                    HeartRateTransportRecoveryReason.USER_RETRY,
                )
            ),
        )
    }

    private fun transportRecoveryResult(
        current: HeartRateSessionState,
        event: HeartRateSessionEvent.TransportRecoveryResult,
    ): HeartRateSessionTransition {
        if (!current.matches(event.acquisition) ||
            current.status != HeartRateSessionStatus.COULDNT_START ||
            !current.enabled ||
            !current.supportedTransport ||
            !current.wear.isWorn
        ) {
            return unchanged(current)
        }
        return if (event.accepted) {
            HeartRateSessionTransition(current.copy(status = HeartRateSessionStatus.RECONNECTING))
        } else {
            unchanged(current)
        }
    }

    private fun transportRecoveryReady(
        current: HeartRateSessionState,
        event: HeartRateSessionEvent.TransportRecoveryReady,
    ): HeartRateSessionTransition {
        if (!current.matches(event.acquisition) ||
            current.status != HeartRateSessionStatus.RECONNECTING ||
            !current.enabled ||
            !current.supportedTransport ||
            !current.wear.isWorn
        ) {
            return unchanged(current)
        }
        if (!event.accepted) {
            return HeartRateSessionTransition(
                current.copy(status = HeartRateSessionStatus.COULDNT_START)
            )
        }
        return beginCapture(
            current,
            event.acquisition.key,
            restartAttempts = 0,
            status = HeartRateSessionStatus.RECONNECTING,
        )
    }

    private fun transportFailure(
        current: HeartRateSessionState,
        event: HeartRateSessionEvent.TransportFailure,
    ): HeartRateSessionTransition {
        if (!current.matches(event.acquisition) ||
            !current.enabled ||
            !current.supportedTransport ||
            !current.wear.isWorn ||
            current.status !in setOf(
                HeartRateSessionStatus.STARTING,
                HeartRateSessionStatus.CALIBRATING,
                HeartRateSessionStatus.LIVE,
                HeartRateSessionStatus.RECONNECTING,
            )
        ) {
            return unchanged(current)
        }
        return HeartRateSessionTransition(
            current.resetCapture(
                HeartRateSessionStatus.COULDNT_START,
                retainAcquisition = true,
                retainRestartAttempts = true,
            ),
            cleanupActions(current) + HeartRateSessionAction.RequestTransportRecovery(
                event.acquisition,
                event.reason,
            ),
        )
    }

    private fun stop(
        current: HeartRateSessionState,
        event: HeartRateSessionEvent.Stop,
    ): HeartRateSessionTransition {
        if (!current.matches(event.acquisition)) return unchanged(current)

        return HeartRateSessionTransition(
            current.resetCapture(HeartRateSessionStatus.OFF).copy(enabled = false),
            cleanupActions(current),
        )
    }

    private fun destroy(current: HeartRateSessionState): HeartRateSessionTransition =
        HeartRateSessionTransition(
            current.resetCapture(HeartRateSessionStatus.OFF).copy(
                enabled = false,
                key = null,
                supportedTransport = false,
                wear = HeartRateWearState(),
                destroyed = true,
            ),
            cleanupActions(current),
        )

    private fun beginCapture(
        current: HeartRateSessionState,
        key: HrmSessionKey,
        restartAttempts: Int,
        status: HeartRateSessionStatus = HeartRateSessionStatus.STARTING,
    ): HeartRateSessionTransition {
        val acquisition = current.nextToken(key)
        return HeartRateSessionTransition(
            current.copy(
                status = status,
                acquisition = acquisition,
                nextAcquisition = acquisition.acquisition,
                acceptingSamples = false,
                warmupSamplesRemaining = WARMUP_SAMPLE_COUNT,
                restartAttempts = restartAttempts,
                watchdog = null,
                measurements = emptyList(),
                sensorResourceClaimPending = true,
                ownsSensorResource = false,
                stopFrameNeeded = false,
            ),
            buildList {
                add(HeartRateSessionAction.ClaimSensorResource(acquisition))
                if (current.measurements.isNotEmpty()) {
                    add(HeartRateSessionAction.ClearMeasurements(acquisition))
                }
            },
        )
    }

    private fun restartOrFail(
        current: HeartRateSessionState,
        failedAcquisition: HrmAcquisitionToken,
    ): HeartRateSessionTransition {
        if (current.restartAttempts < MAX_RESTART_ATTEMPTS) {
            val replacement = current.nextToken(failedAcquisition.key)
            return HeartRateSessionTransition(
                current.copy(
                    status = HeartRateSessionStatus.RECONNECTING,
                    acquisition = replacement,
                    nextAcquisition = replacement.acquisition,
                    acceptingSamples = false,
                    warmupSamplesRemaining = WARMUP_SAMPLE_COUNT,
                    restartAttempts = current.restartAttempts + 1,
                    watchdog = null,
                    measurements = emptyList(),
                    sensorResourceClaimPending = true,
                    ownsSensorResource = false,
                    stopFrameNeeded = false,
                ),
                buildList {
                    add(HeartRateSessionAction.CancelWatchdog(failedAcquisition))
                    if (current.stopFrameNeeded) {
                        add(HeartRateSessionAction.SendStopFrames(failedAcquisition))
                    }
                    add(HeartRateSessionAction.ClaimSensorResource(replacement))
                    if (current.measurements.isNotEmpty()) {
                        add(HeartRateSessionAction.ClearMeasurements(replacement))
                    }
                },
            )
        }

        return HeartRateSessionTransition(
            current.resetCapture(
                HeartRateSessionStatus.COULDNT_START,
                retainAcquisition = true,
                retainRestartAttempts = true,
            ),
            cleanupActions(current) + HeartRateSessionAction.RequestTransportRecovery(
                failedAcquisition,
                HeartRateTransportRecoveryReason.AUTOMATIC,
            ),
        )
    }

    private fun cleanupActions(
        current: HeartRateSessionState,
    ): List<HeartRateSessionAction> {
        val acquisition = current.acquisition ?: return emptyList()
        return buildList {
            if (current.watchdog != null ||
                current.ownsSensorResource ||
                current.stopFrameNeeded
            ) {
                add(HeartRateSessionAction.CancelWatchdog(acquisition))
            }
            if (current.stopFrameNeeded) {
                add(HeartRateSessionAction.SendStopFrames(acquisition))
            }
            if (current.sensorResourceClaimPending || current.ownsSensorResource) {
                add(HeartRateSessionAction.ReleaseSensorResource(acquisition))
            }
            if (current.measurements.isNotEmpty()) {
                add(HeartRateSessionAction.ClearMeasurements(acquisition))
            }
        }
    }

    private fun HeartRateSessionState.resetCapture(
        status: HeartRateSessionStatus,
        retainAcquisition: Boolean = false,
        retainRestartAttempts: Boolean = false,
    ): HeartRateSessionState = copy(
        status = status,
        acquisition = if (retainAcquisition) acquisition else null,
        acceptingSamples = false,
        warmupSamplesRemaining = 0,
        restartAttempts = if (retainRestartAttempts) restartAttempts else 0,
        watchdog = null,
        measurements = emptyList(),
        sensorResourceClaimPending = false,
        ownsSensorResource = false,
        stopFrameNeeded = false,
    )

    private fun HeartRateSessionState.matches(key: HrmSessionKey): Boolean =
        this.key?.matches(key) == true

    private fun HeartRateSessionState.matches(token: HrmAcquisitionToken): Boolean =
        key?.matches(token.key) == true && acquisition?.matches(token) == true

    private fun HeartRateSessionState.nextToken(key: HrmSessionKey) =
        HrmAcquisitionToken(key, nextAcquisition + 1L)

    private fun deadlineAfter(nowMillis: Long, delayMillis: Long): Long =
        if (nowMillis > Long.MAX_VALUE - delayMillis) {
            Long.MAX_VALUE
        } else {
            nowMillis + delayMillis
        }

    private fun unchanged(state: HeartRateSessionState) =
        HeartRateSessionTransition(state)
}
