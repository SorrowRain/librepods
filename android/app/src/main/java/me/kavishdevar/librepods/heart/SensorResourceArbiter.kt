/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.heart

internal enum class SensorResource {
    HEART_RATE,
    HEAD_TRACKING,
}

/** An owner is unique across both an AACP connection and one resource acquisition. */
internal data class SensorResourceOwner(
    val resource: SensorResource,
    val connection: HrmSessionKey,
    val acquisition: Long,
) {
    override fun toString(): String =
        "SensorResourceOwner(resource=$resource, " +
            "generation=${connection.generation}, epoch=${connection.epoch}, " +
            "acquisition=$acquisition)"

    companion object {
        fun heartRate(token: HrmAcquisitionToken) = SensorResourceOwner(
            resource = SensorResource.HEART_RATE,
            connection = token.key,
            acquisition = token.acquisition,
        )
    }
}

internal data class SensorResourceArbiterState(
    val activeOwner: SensorResourceOwner? = null,
    val heartRateRequest: SensorResourceOwner? = null,
    val headTrackingRequest: SensorResourceOwner? = null,
    /**
     * Global connection lease high-water mark. A newer connection invalidates requests for both
     * resources, including a head-tracking request that was waiting behind HRM.
     */
    val currentConnection: HrmSessionKey? = null,
    val latestHeartRateOwner: SensorResourceOwner? = null,
    val latestHeadTrackingOwner: SensorResourceOwner? = null,
    val destroyed: Boolean = false,
)

internal sealed interface SensorResourceEvent {
    data class Request(val owner: SensorResourceOwner) : SensorResourceEvent
    data class Release(val owner: SensorResourceOwner) : SensorResourceEvent
    data object Destroy : SensorResourceEvent
}

internal sealed interface SensorResourceAction {
    data class StartHeartRate(
        val owner: SensorResourceOwner,
        val quietPeriodMillis: Long = 0L,
    ) : SensorResourceAction
    data class StopHeartRate(val owner: SensorResourceOwner) : SensorResourceAction
    data class StartHeadTracking(val owner: SensorResourceOwner) : SensorResourceAction
    data class StopHeadTracking(val owner: SensorResourceOwner) : SensorResourceAction
}

internal data class SensorResourceTransition(
    val state: SensorResourceArbiterState,
    val actions: List<SensorResourceAction> = emptyList(),
)

/**
 * Gives HRM exclusive priority over the shared AirPods sensor stream.
 *
 * A head-tracking request remains deferred while HRM owns the stream and resumes only if the same
 * request is still live when HRM releases it. A new connection globally revokes both resource
 * claims, so an old deferred head-tracking request can never resume on a later connection.
 */
internal object SensorResourceArbiter {
    fun reduce(
        current: SensorResourceArbiterState,
        event: SensorResourceEvent,
    ): SensorResourceTransition {
        if (current.destroyed) return unchanged(current)
        return when (event) {
            is SensorResourceEvent.Request -> request(current, event.owner)
            is SensorResourceEvent.Release -> release(current, event.owner)
            SensorResourceEvent.Destroy -> destroy(current)
        }
    }

    private fun request(
        current: SensorResourceArbiterState,
        owner: SensorResourceOwner,
    ): SensorResourceTransition {
        val connection = current.currentConnection
        return when {
            connection == null -> requestOnCurrentConnection(
                current.copy(currentConnection = owner.connection),
                owner,
            )
            connection.matches(owner.connection) -> requestOnCurrentConnection(current, owner)
            owner.connection.epoch > connection.epoch -> replaceConnection(current, owner)
            else -> unchanged(current)
        }
    }

    private fun requestOnCurrentConnection(
        current: SensorResourceArbiterState,
        owner: SensorResourceOwner,
    ): SensorResourceTransition = when (owner.resource) {
        SensorResource.HEART_RATE -> requestHeartRate(current, owner)
        SensorResource.HEAD_TRACKING -> requestHeadTracking(current, owner)
    }

    private fun replaceConnection(
        current: SensorResourceArbiterState,
        owner: SensorResourceOwner,
    ): SensorResourceTransition {
        val stoppedHeadTracking = current.activeOwner?.resource == SensorResource.HEAD_TRACKING
        val actions = buildList {
            current.activeOwner?.let { add(stopAction(it)) }
            add(startAction(owner, quietAfterHeadTrackingStop = stoppedHeadTracking))
        }
        return SensorResourceTransition(
            SensorResourceArbiterState(
                activeOwner = owner,
                heartRateRequest = if (owner.resource == SensorResource.HEART_RATE) owner else null,
                headTrackingRequest = if (owner.resource == SensorResource.HEAD_TRACKING) owner else null,
                currentConnection = owner.connection,
                latestHeartRateOwner =
                    if (owner.resource == SensorResource.HEART_RATE) owner else null,
                latestHeadTrackingOwner =
                    if (owner.resource == SensorResource.HEAD_TRACKING) owner else null,
            ),
            actions,
        )
    }

    private fun requestHeartRate(
        current: SensorResourceArbiterState,
        owner: SensorResourceOwner,
    ): SensorResourceTransition {
        if (!isNewer(owner, current.latestHeartRateOwner)) return unchanged(current)

        val stoppedHeadTracking = current.activeOwner?.resource == SensorResource.HEAD_TRACKING
        val actions = buildList {
            current.activeOwner?.let { add(stopAction(it)) }
            add(
                SensorResourceAction.StartHeartRate(
                    owner,
                    quietPeriodMillis = if (stoppedHeadTracking) {
                        HEAD_TRACKING_QUIET_PERIOD_MILLIS
                    } else {
                        0L
                    },
                )
            )
        }
        return SensorResourceTransition(
            current.copy(
                activeOwner = owner,
                heartRateRequest = owner,
                latestHeartRateOwner = owner,
            ),
            actions,
        )
    }

    private fun requestHeadTracking(
        current: SensorResourceArbiterState,
        owner: SensorResourceOwner,
    ): SensorResourceTransition {
        if (!isNewer(owner, current.latestHeadTrackingOwner)) return unchanged(current)

        val requested = current.copy(
            headTrackingRequest = owner,
            latestHeadTrackingOwner = owner,
        )
        if (current.heartRateRequest != null) {
            return SensorResourceTransition(requested)
        }

        val actions = buildList {
            current.activeOwner?.let { add(stopAction(it)) }
            add(SensorResourceAction.StartHeadTracking(owner))
        }
        return SensorResourceTransition(
            requested.copy(activeOwner = owner),
            actions,
        )
    }

    private fun release(
        current: SensorResourceArbiterState,
        owner: SensorResourceOwner,
    ): SensorResourceTransition = when (owner.resource) {
        SensorResource.HEART_RATE -> releaseHeartRate(current, owner)
        SensorResource.HEAD_TRACKING -> releaseHeadTracking(current, owner)
    }

    private fun releaseHeartRate(
        current: SensorResourceArbiterState,
        owner: SensorResourceOwner,
    ): SensorResourceTransition {
        if (!current.currentConnection.matches(owner.connection) ||
            current.heartRateRequest != owner
        ) {
            return unchanged(current)
        }

        val isActiveOwner = current.activeOwner == owner
        val deferredHeadTracking = current.headTrackingRequest
        val actions = buildList {
            if (isActiveOwner) add(SensorResourceAction.StopHeartRate(owner))
            if (isActiveOwner && deferredHeadTracking != null) {
                add(SensorResourceAction.StartHeadTracking(deferredHeadTracking))
            }
        }
        return SensorResourceTransition(
            current.copy(
                activeOwner = if (isActiveOwner) deferredHeadTracking else current.activeOwner,
                heartRateRequest = null,
            ),
            actions,
        )
    }

    private fun releaseHeadTracking(
        current: SensorResourceArbiterState,
        owner: SensorResourceOwner,
    ): SensorResourceTransition {
        if (!current.currentConnection.matches(owner.connection) ||
            current.headTrackingRequest != owner
        ) {
            return unchanged(current)
        }

        val isActiveOwner = current.activeOwner == owner
        return SensorResourceTransition(
            current.copy(
                activeOwner = if (isActiveOwner) null else current.activeOwner,
                headTrackingRequest = null,
            ),
            if (isActiveOwner) {
                listOf(SensorResourceAction.StopHeadTracking(owner))
            } else {
                emptyList()
            },
        )
    }

    private fun destroy(current: SensorResourceArbiterState): SensorResourceTransition {
        val actions = current.activeOwner?.let { listOf(stopAction(it)) }.orEmpty()
        return SensorResourceTransition(
            current.copy(
                activeOwner = null,
                heartRateRequest = null,
                headTrackingRequest = null,
                destroyed = true,
            ),
            actions,
        )
    }

    private fun isNewer(
        candidate: SensorResourceOwner,
        latest: SensorResourceOwner?,
    ): Boolean {
        if (latest == null) return true
        return candidate.connection.matches(latest.connection) &&
            candidate.acquisition > latest.acquisition
    }

    private fun stopAction(owner: SensorResourceOwner): SensorResourceAction =
        when (owner.resource) {
            SensorResource.HEART_RATE -> SensorResourceAction.StopHeartRate(owner)
            SensorResource.HEAD_TRACKING -> SensorResourceAction.StopHeadTracking(owner)
        }

    private fun startAction(
        owner: SensorResourceOwner,
        quietAfterHeadTrackingStop: Boolean = false,
    ): SensorResourceAction =
        when (owner.resource) {
            SensorResource.HEART_RATE -> SensorResourceAction.StartHeartRate(
                owner,
                quietPeriodMillis = if (quietAfterHeadTrackingStop) {
                    HEAD_TRACKING_QUIET_PERIOD_MILLIS
                } else {
                    0L
                },
            )
            SensorResource.HEAD_TRACKING -> SensorResourceAction.StartHeadTracking(owner)
        }

    private fun HrmSessionKey?.matches(other: HrmSessionKey): Boolean =
        this?.matches(other) == true

    private fun unchanged(state: SensorResourceArbiterState) =
        SensorResourceTransition(state)

    internal const val HEAD_TRACKING_QUIET_PERIOD_MILLIS = 220L
}
