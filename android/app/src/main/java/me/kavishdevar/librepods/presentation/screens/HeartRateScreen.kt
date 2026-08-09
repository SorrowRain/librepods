/*
    LibrePods - AirPods liberated from Apple's ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.
*/

package me.kavishdevar.librepods.presentation.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.heart.HeartRateSampleFreshness
import me.kavishdevar.librepods.heart.HeartRateUiState
import me.kavishdevar.librepods.heart.HeartRateUiStatus
import me.kavishdevar.librepods.health.HealthConnectExportState
import me.kavishdevar.librepods.health.HealthConnectExportStatus
import me.kavishdevar.librepods.health.HealthConnectHeartRateExporter
import me.kavishdevar.librepods.presentation.components.StyledButton
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LibrePodsTheme
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AirPodsViewModel

@Composable
fun HeartRateRoute(
    viewModel: AirPodsViewModel,
    onUnsupported: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) { grantedPermissions ->
        if (grantedPermissions.containsAll(HealthConnectHeartRateExporter.REQUIRED_PERMISSIONS)) {
            viewModel.setHealthConnectHeartRateExportEnabled(true)
        } else {
            viewModel.markHealthConnectHeartRatePermissionDenied()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshHealthConnectHeartRateExport()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!state.heartRateState.supported) {
        LaunchedEffect(Unit) { onUnsupported() }
        return
    }

    HeartRateScreen(
        state = state.heartRateState,
        healthConnectState = state.healthConnectExportState,
        onEnabledChanged = viewModel::setHeartRateMonitoringEnabled,
        onRetry = viewModel::retryHeartRateMonitoring,
        onHealthConnectEnabledChanged = { enabled ->
            if (!enabled) {
                viewModel.setHealthConnectHeartRateExportEnabled(false)
            } else {
                when (state.healthConnectExportState.status) {
                    HealthConnectExportStatus.PERMISSION_REQUIRED,
                    HealthConnectExportStatus.PERMISSION_DENIED,
                    -> permissionLauncher.launch(
                        HealthConnectHeartRateExporter.REQUIRED_PERMISSIONS
                    )

                    HealthConnectExportStatus.READY,
                    HealthConnectExportStatus.ERROR,
                    -> viewModel.setHealthConnectHeartRateExportEnabled(true)

                    HealthConnectExportStatus.ENABLED,
                    HealthConnectExportStatus.UNAVAILABLE,
                    HealthConnectExportStatus.UPDATE_REQUIRED,
                    -> Unit
                }
            }
        },
        onDetailedSamplesChanged = viewModel::setHealthConnectDetailedSamples,
    )

}

@Composable
fun HeartRateScreen(
    state: HeartRateUiState,
    healthConnectState: HealthConnectExportState,
    onEnabledChanged: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onHealthConnectEnabledChanged: (Boolean) -> Unit,
    onDetailedSamplesChanged: (Boolean) -> Unit,
) {
    val backdrop = rememberLayerBackdrop()
    val materialDesign = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (materialDesign) {
        0.dp
    } else {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    }
    val bottomPadding = if (materialDesign) {
        0.dp
    } else {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .layerBackdrop(backdrop)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(modifier = Modifier.height(topPadding))
        HeartRateReadingPanel(state = state, materialDesign = materialDesign)
        Spacer(modifier = Modifier.height(12.dp))

        StyledToggle(
            label = stringResource(R.string.heart_rate_monitoring),
            description = stringResource(R.string.heart_rate_monitoring_description),
            checked = state.enabled,
            enabled = state.supported || state.enabled,
            onCheckedChange = onEnabledChanged,
        )

        if (!state.supported) {
            Text(
                text = stringResource(R.string.heart_rate_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        if (state.supported &&
            state.enabled &&
            state.status == HeartRateUiStatus.COULDNT_START
        ) {
            Spacer(modifier = Modifier.height(12.dp))
            StyledButton(
                onClick = onRetry,
                backdrop = backdrop,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.heart_rate_try_again),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        HealthConnectControls(
            state = healthConnectState,
            onEnabledChanged = onHealthConnectEnabledChanged,
            onDetailedSamplesChanged = onDetailedSamplesChanged,
        )

        Spacer(modifier = Modifier.height(bottomPadding))
    }
}

@Composable
private fun HealthConnectControls(
    state: HealthConnectExportState,
    onEnabledChanged: (Boolean) -> Unit,
    onDetailedSamplesChanged: (Boolean) -> Unit,
) {
    val available = state.status != HealthConnectExportStatus.UNAVAILABLE &&
        state.status != HealthConnectExportStatus.UPDATE_REQUIRED

    Text(
        text = stringResource(R.string.health_connect_export_section),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    StyledToggle(
        label = stringResource(R.string.health_connect_export_heart_rate),
        description = stringResource(R.string.health_connect_export_description),
        checked = state.requestedEnabled,
        enabled = available || state.requestedEnabled,
        onCheckedChange = onEnabledChanged,
    )
    Text(
        text = healthConnectStatusText(state.status),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
    )
    StyledToggle(
        label = stringResource(R.string.health_connect_detailed_samples),
        description = stringResource(R.string.health_connect_detailed_samples_description),
        checked = state.detailedSamples,
        enabled = available,
        onCheckedChange = onDetailedSamplesChanged,
    )
}

@Composable
private fun HeartRateReadingPanel(
    state: HeartRateUiState,
    materialDesign: Boolean,
) {
    val reading = state.latestBpm?.toString() ?: "--"
    val readingCaption = when {
        state.latestBpm != null -> stringResource(R.string.heart_rate_current_reading)
        state.sampleFreshness == HeartRateSampleFreshness.STALE ->
            stringResource(R.string.heart_rate_no_recent_reading)
        else -> stringResource(R.string.heart_rate_no_reading)
    }
    val liveReading = state.latestBpm != null &&
        state.sampleFreshness == HeartRateSampleFreshness.FRESH

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(172.dp),
        shape = RoundedCornerShape(if (materialDesign) 24.dp else 28.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(
                            if (liveReading) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.outline
                            },
                            CircleShape,
                        )
                )
                Text(
                    text = heartRateStatusText(state.status),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = reading,
                    style = MaterialTheme.typography.displayMedium.copy(letterSpacing = 0.sp),
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(min = 84.dp),
                )
                Text(
                    text = stringResource(R.string.heart_rate_bpm_unit),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 7.dp),
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = readingCaption,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun heartRateSummaryText(state: HeartRateUiState): String = when {
    state.latestBpm != null -> stringResource(R.string.heart_rate_bpm_format, state.latestBpm)
    state.status == HeartRateUiStatus.LIVE &&
        state.sampleFreshness == HeartRateSampleFreshness.STALE ->
        stringResource(R.string.heart_rate_no_recent_reading)
    state.status == HeartRateUiStatus.LIVE -> stringResource(R.string.heart_rate_no_reading)
    else -> heartRateStatusText(state.status)
}

@Composable
private fun heartRateStatusText(status: HeartRateUiStatus): String = when (status) {
    HeartRateUiStatus.OFF -> stringResource(R.string.heart_rate_status_off)
    HeartRateUiStatus.WAITING_FOR_AIRPODS ->
        stringResource(R.string.heart_rate_status_waiting_for_airpods)
    HeartRateUiStatus.WAITING_TO_BE_WORN ->
        stringResource(R.string.heart_rate_status_waiting_to_be_worn)
    HeartRateUiStatus.STARTING -> stringResource(R.string.heart_rate_status_starting)
    HeartRateUiStatus.CALIBRATING -> stringResource(R.string.heart_rate_status_calibrating)
    HeartRateUiStatus.LIVE -> stringResource(R.string.heart_rate_status_live)
    HeartRateUiStatus.RECONNECTING -> stringResource(R.string.heart_rate_status_reconnecting)
    HeartRateUiStatus.COULDNT_START ->
        stringResource(R.string.heart_rate_status_couldnt_start)
}

@Composable
private fun healthConnectStatusText(status: HealthConnectExportStatus): String = when (status) {
    HealthConnectExportStatus.UNAVAILABLE ->
        stringResource(R.string.health_connect_status_unavailable)
    HealthConnectExportStatus.UPDATE_REQUIRED ->
        stringResource(R.string.health_connect_status_update_required)
    HealthConnectExportStatus.PERMISSION_REQUIRED ->
        stringResource(R.string.health_connect_status_permission_required)
    HealthConnectExportStatus.PERMISSION_DENIED ->
        stringResource(R.string.health_connect_status_permission_denied)
    HealthConnectExportStatus.READY -> stringResource(R.string.health_connect_status_ready)
    HealthConnectExportStatus.ENABLED -> stringResource(R.string.health_connect_status_enabled)
    HealthConnectExportStatus.ERROR -> stringResource(R.string.health_connect_status_error)
}

@Preview(name = "Heart rate live")
@Composable
private fun HeartRateScreenPreview() {
    LibrePodsTheme(m3eEnabled = true) {
        HeartRateScreen(
            state = HeartRateUiState(
                supported = true,
                enabled = true,
                status = HeartRateUiStatus.LIVE,
                latestBpm = 72,
                sampleFreshness = HeartRateSampleFreshness.FRESH,
            ),
            healthConnectState = HealthConnectExportState(
                requestedEnabled = true,
                enabled = true,
                status = HealthConnectExportStatus.ENABLED,
            ),
            onEnabledChanged = {},
            onRetry = {},
            onHealthConnectEnabledChanged = {},
            onDetailedSamplesChanged = {},
        )
    }
}
