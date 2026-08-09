/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.heart

internal enum class HeadTrackingRequestSource {
    BOOTSTRAP,
    SPATIAL_AUDIO,
    GESTURE,
    UI_SCREEN,
}

internal sealed interface HeadTrackingClaimAction {
    val owner: SensorResourceOwner

    data class Request(
        override val owner: SensorResourceOwner,
    ) : HeadTrackingClaimAction

    data class Release(
        override val owner: SensorResourceOwner,
    ) : HeadTrackingClaimAction
}

/**
 * Coalesces independent head-tracking intents into one lease-bound sensor claim.
 *
 * The caller must serialize access with the service's connection coordinator. Each action must be
 * forwarded to [SensorResourceArbiter] in order. No device identifier is included in diagnostics.
 */
internal class HeadTrackingClaimController {
    private var connection: HrmSessionKey? = null
    private var connectionOpen = false
    private val requestedSources = linkedSetOf<HeadTrackingRequestSource>()
    private var acquisitionHighWater = 0L
    private var claimOwner: SensorResourceOwner? = null
    private var grantedOwner: SensorResourceOwner? = null
    private var destroyed = false

    val desiredSources: Set<HeadTrackingRequestSource>
        get() = requestedSources.toSet()

    val currentOwner: SensorResourceOwner?
        get() = claimOwner

    val hasDesiredRequests: Boolean
        get() = requestedSources.isNotEmpty()

    val isCurrentGrantActive: Boolean
        get() = claimOwner != null && grantedOwner == claimOwner

    fun isSourceGranted(
        key: HrmSessionKey,
        source: HeadTrackingRequestSource,
    ): Boolean = accepts(key) &&
        source in requestedSources &&
        claimOwner?.connection?.matches(key) == true &&
        isCurrentGrantActive

    /** Installs the first connection or a strictly newer connection epoch. */
    fun installConnection(key: HrmSessionKey): List<HeadTrackingClaimAction> {
        if (destroyed) return emptyList()

        val installed = connection
        if (installed == null) {
            connection = key
            connectionOpen = true
            return emptyList()
        }
        if (installed.matches(key)) {
            return emptyList()
        }
        if (key.epoch <= installed.epoch) {
            return emptyList()
        }

        val actions = revokeRequests()
        connection = key
        connectionOpen = true
        return actions
    }

    /** Requests head tracking for [source] on the exact installed connection. */
    fun request(
        key: HrmSessionKey,
        source: HeadTrackingRequestSource,
    ): List<HeadTrackingClaimAction> {
        if (!accepts(key) || !requestedSources.add(source)) return emptyList()
        if (claimOwner != null) return emptyList()

        check(acquisitionHighWater < Long.MAX_VALUE) {
            "Head-tracking acquisition counter exhausted"
        }
        acquisitionHighWater += 1L
        val owner = SensorResourceOwner(
            resource = SensorResource.HEAD_TRACKING,
            connection = key,
            acquisition = acquisitionHighWater,
        )
        claimOwner = owner
        return listOf(HeadTrackingClaimAction.Request(owner))
    }

    /** Releases only a currently desired [source] on the exact installed connection. */
    fun release(
        key: HrmSessionKey,
        source: HeadTrackingRequestSource,
    ): List<HeadTrackingClaimAction> {
        if (!accepts(key) || !requestedSources.remove(source)) return emptyList()
        if (requestedSources.isNotEmpty()) return emptyList()

        val owner = claimOwner ?: return emptyList()
        claimOwner = null
        grantedOwner = null
        return listOf(HeadTrackingClaimAction.Release(owner))
    }

    /** Revokes the exact connection without allowing it to be reopened after stale callbacks. */
    fun disconnect(key: HrmSessionKey): List<HeadTrackingClaimAction> {
        if (!accepts(key)) return emptyList()
        connectionOpen = false
        return revokeRequests()
    }

    /** Mirrors the arbiter's active owner so UI state reflects an actual grant, not intent. */
    fun onActiveOwnerChanged(activeOwner: SensorResourceOwner?): Boolean {
        grantedOwner = if (connectionOpen && activeOwner == claimOwner) claimOwner else null
        return isCurrentGrantActive
    }

    fun destroy(): List<HeadTrackingClaimAction> {
        if (destroyed) return emptyList()
        destroyed = true
        connectionOpen = false
        return revokeRequests()
    }

    private fun accepts(key: HrmSessionKey): Boolean =
        !destroyed && connectionOpen && connection?.matches(key) == true

    private fun revokeRequests(): List<HeadTrackingClaimAction> {
        requestedSources.clear()
        grantedOwner = null
        val owner = claimOwner
        claimOwner = null
        return owner?.let { listOf(HeadTrackingClaimAction.Release(it)) }.orEmpty()
    }
}
