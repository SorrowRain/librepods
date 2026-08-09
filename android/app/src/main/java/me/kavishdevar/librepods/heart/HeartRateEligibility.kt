/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.heart

import me.kavishdevar.librepods.data.Capability

internal data class HeartRateDeviceInformationConfirmation(
    val key: HrmSessionKey,
    val modelNumber: String,
    val modelCapabilities: Set<Capability>,
)

/** Current-connection evidence used to decide whether HRM may be offered. */
internal data class HeartRateEligibilityState(
    val currentKey: HrmSessionKey? = null,
    val deviceInformation: HeartRateDeviceInformationConfirmation? = null,
    val highestEpoch: Long = Long.MIN_VALUE,
    val destroyed: Boolean = false,
) {
    val supported: Boolean
        get() {
            val key = currentKey ?: return false
            val confirmed = deviceInformation ?: return false
            return confirmed.key.matches(key) &&
                confirmed.modelNumber in HEART_RATE_MODEL_NUMBERS &&
                Capability.HRM in confirmed.modelCapabilities
        }

    fun isCurrent(key: HrmSessionKey?): Boolean {
        val current = currentKey ?: return false
        return key?.let(current::matches) == true
    }

    private companion object {
        val HEART_RATE_MODEL_NUMBERS = setOf("A3063", "A3064", "A3065")
    }
}

internal sealed interface HeartRateEligibilityEvent {
    data class SessionInstalled(val key: HrmSessionKey) : HeartRateEligibilityEvent
    data class SessionCleared(val key: HrmSessionKey) : HeartRateEligibilityEvent

    data class DeviceInformationConfirmed(
        val key: HrmSessionKey,
        val modelNumber: String,
        val modelCapabilities: Set<Capability>,
    ) : HeartRateEligibilityEvent

    data object Destroy : HeartRateEligibilityEvent
}

/**
 * Keeps Device Information confirmation fenced to the exact current AACP session.
 *
 * The epoch high-water mark prevents a delayed connection callback from replacing a newer session.
 */
internal object HeartRateEligibilityReducer {
    fun reduce(
        current: HeartRateEligibilityState,
        event: HeartRateEligibilityEvent,
    ): HeartRateEligibilityState {
        if (current.destroyed) return current

        return when (event) {
            is HeartRateEligibilityEvent.SessionInstalled -> install(current, event.key)
            is HeartRateEligibilityEvent.SessionCleared -> clear(current, event.key)
            is HeartRateEligibilityEvent.DeviceInformationConfirmed ->
                confirmDeviceInformation(current, event)
            HeartRateEligibilityEvent.Destroy -> current.copy(
                currentKey = null,
                deviceInformation = null,
                destroyed = true,
            )
        }
    }

    private fun install(
        current: HeartRateEligibilityState,
        key: HrmSessionKey,
    ): HeartRateEligibilityState {
        if (current.isCurrent(key)) return current
        if (key.epoch <= current.highestEpoch) return current

        return current.copy(
            currentKey = key,
            deviceInformation = null,
            highestEpoch = key.epoch,
        )
    }

    private fun clear(
        current: HeartRateEligibilityState,
        key: HrmSessionKey,
    ): HeartRateEligibilityState {
        if (!current.isCurrent(key)) return current
        return current.copy(
            currentKey = null,
            deviceInformation = null,
        )
    }

    private fun confirmDeviceInformation(
        current: HeartRateEligibilityState,
        event: HeartRateEligibilityEvent.DeviceInformationConfirmed,
    ): HeartRateEligibilityState {
        if (!current.isCurrent(event.key)) return current

        val confirmation = HeartRateDeviceInformationConfirmation(
            key = event.key,
            modelNumber = event.modelNumber,
            modelCapabilities = event.modelCapabilities.toSet(),
        )
        if (current.deviceInformation == confirmation) return current
        return current.copy(deviceInformation = confirmation)
    }
}
