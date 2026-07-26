package me.kavishdevar.librepods.presentation.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.GuardHealthReason

@Composable
fun guardHealthReasonText(reason: GuardHealthReason?): String = when (reason) {
    GuardHealthReason.PERMISSION_MISSING ->
        stringResource(R.string.guard_health_reason_permission_missing)
    GuardHealthReason.BARRIER_FAILED ->
        stringResource(R.string.guard_health_reason_barrier_failed)
    GuardHealthReason.BARRIER_STUCK ->
        stringResource(R.string.guard_health_reason_barrier_stuck)
    GuardHealthReason.LOCAL_MAC_UNKNOWN ->
        stringResource(R.string.guard_health_reason_local_mac_unknown)
    GuardHealthReason.NATIVE_TRANSPORT_MISSING ->
        stringResource(R.string.guard_health_reason_native_transport_missing)
    GuardHealthReason.DISABLED_BY_USER ->
        stringResource(R.string.guard_health_reason_disabled)
    null -> ""
}

@Composable
fun guardHealthRemedyText(reason: GuardHealthReason): String? = when (reason) {
    GuardHealthReason.PERMISSION_MISSING ->
        stringResource(R.string.guard_health_remedy_permission)
    GuardHealthReason.BARRIER_FAILED -> stringResource(R.string.guard_health_remedy_barrier)
    GuardHealthReason.BARRIER_STUCK ->
        stringResource(R.string.guard_health_remedy_barrier_stuck)
    GuardHealthReason.LOCAL_MAC_UNKNOWN -> stringResource(R.string.guard_health_remedy_local_mac)
    GuardHealthReason.NATIVE_TRANSPORT_MISSING ->
        stringResource(R.string.guard_health_remedy_native)
    GuardHealthReason.DISABLED_BY_USER -> null
}
