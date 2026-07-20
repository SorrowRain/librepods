package me.kavishdevar.librepods.presentation.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.data.SmartRoutingAudioCategory
import me.kavishdevar.librepods.data.SmartRoutingAudioRisk
import me.kavishdevar.librepods.data.PhoneSoundRoutingPolicy
import me.kavishdevar.librepods.presentation.components.StyledList
import me.kavishdevar.librepods.presentation.components.StyledListScope
import me.kavishdevar.librepods.presentation.components.StyledToggle
import me.kavishdevar.librepods.presentation.theme.DesignSystem
import me.kavishdevar.librepods.presentation.theme.LocalDesignSystem
import me.kavishdevar.librepods.presentation.viewmodel.AppSettingsViewModel
import me.kavishdevar.librepods.utils.PhoneSoundRoutingController

@Composable
fun SmartRoutingAudioSourcesScreen(viewModel: AppSettingsViewModel) {
    val state by viewModel.uiState.collectAsState()
    val phoneRoutingStatus by PhoneSoundRoutingController.status.collectAsState()
    val m3eEnabled = LocalDesignSystem.current == DesignSystem.Material
    val topPadding = if (m3eEnabled) {
        16.dp
    } else {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 84.dp
    }
    val bottomPadding = if (m3eEnabled) {
        0.dp
    } else {
        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        Spacer(modifier = Modifier.height(topPadding))

        StyledList(
            title = stringResource(R.string.phone_sound_routing_title),
            description = stringResource(R.string.phone_sound_routing_description)
        ) {
            StyledToggle(
                label = stringResource(R.string.phone_sound_routing_enabled),
                description = when (phoneRoutingStatus.state) {
                    "registered" -> stringResource(R.string.phone_sound_routing_status_active)
                    "permission_missing" ->
                        stringResource(R.string.phone_sound_routing_status_permission_missing)
                    "registration_failed", "speaker_unavailable", "hidden_api_blocked",
                    "local_build_rejected", "register_security_exception",
                    "register_policy_denied" ->
                        stringResource(R.string.phone_sound_routing_status_failed)
                    else -> stringResource(R.string.phone_sound_routing_enabled_description)
                },
                checked = state.phoneSoundRoutingEnabled,
                onCheckedChange = viewModel::setPhoneSoundRoutingEnabled
            )
            PhoneSoundRoutingPolicy.supportedCategories
                .sortedBy { it !in PhoneSoundRoutingPolicy.DEFAULT.routedCategories }
                .forEach { category ->
                PhoneSoundCategoryToggle(
                    category = category,
                    viewModel = viewModel,
                    enabledCategories = state.phoneSoundRoutingPolicy.routedCategories,
                    enabled = state.phoneSoundRoutingEnabled
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        StyledList(
            title = stringResource(R.string.smart_routing_audio_sources_recommended),
            description = if (state.smartRoutingAutoTakeover) {
                stringResource(R.string.smart_routing_audio_sources_description)
            } else {
                stringResource(R.string.smart_routing_audio_sources_master_off)
            }
        ) {
            SmartRoutingAudioCategory.entries
                .filter { it.risk == SmartRoutingAudioRisk.STANDARD }
                .forEach { category ->
                    AudioCategoryToggle(
                        category = category,
                        viewModel = viewModel,
                        enabledCategories = state.smartRoutingAudioPolicy.enabledCategories
                    )
                }
        }

        Spacer(modifier = Modifier.height(16.dp))

        StyledList(title = stringResource(R.string.smart_routing_audio_sources_optional)) {
            SmartRoutingAudioCategory.entries
                .filter { it.risk == SmartRoutingAudioRisk.CAUTION }
                .forEach { category ->
                    AudioCategoryToggle(
                        category = category,
                        viewModel = viewModel,
                        enabledCategories = state.smartRoutingAudioPolicy.enabledCategories
                    )
                }
        }

        Spacer(modifier = Modifier.height(16.dp))

        StyledList(
            title = stringResource(R.string.smart_routing_audio_sources_high_risk),
            description = stringResource(R.string.smart_routing_audio_sources_high_risk_description)
        ) {
            SmartRoutingAudioCategory.entries
                .filter { it.risk == SmartRoutingAudioRisk.HIGH }
                .forEach { category ->
                    AudioCategoryToggle(
                        category = category,
                        viewModel = viewModel,
                        enabledCategories = state.smartRoutingAudioPolicy.enabledCategories
                    )
                }
        }

        Spacer(modifier = Modifier.height(bottomPadding + 16.dp))
    }
}

@Composable
private fun StyledListScope.PhoneSoundCategoryToggle(
    category: SmartRoutingAudioCategory,
    viewModel: AppSettingsViewModel,
    enabledCategories: Set<SmartRoutingAudioCategory>,
    enabled: Boolean,
) {
    StyledToggle(
        label = stringResource(category.labelRes()),
        description = stringResource(R.string.phone_sound_routing_category_description),
        checked = category in enabledCategories,
        enabled = enabled,
        onCheckedChange = { checked ->
            viewModel.setPhoneSoundRoutingCategoryEnabled(category, checked)
        }
    )
}

@Composable
private fun StyledListScope.AudioCategoryToggle(
    category: SmartRoutingAudioCategory,
    viewModel: AppSettingsViewModel,
    enabledCategories: Set<SmartRoutingAudioCategory>
) {
    StyledToggle(
        label = stringResource(category.labelRes()),
        description = stringResource(category.descriptionRes()),
        checked = category in enabledCategories,
        onCheckedChange = { enabled ->
            viewModel.setSmartRoutingAudioCategoryEnabled(category, enabled)
        }
    )
}

@StringRes
private fun SmartRoutingAudioCategory.labelRes(): Int = when (this) {
    SmartRoutingAudioCategory.MEDIA -> R.string.smart_routing_audio_media
    SmartRoutingAudioCategory.GAME -> R.string.smart_routing_audio_game
    SmartRoutingAudioCategory.NAVIGATION -> R.string.smart_routing_audio_navigation
    SmartRoutingAudioCategory.ASSISTANT -> R.string.smart_routing_audio_assistant
    SmartRoutingAudioCategory.ACCESSIBILITY -> R.string.smart_routing_audio_accessibility
    SmartRoutingAudioCategory.ALARM -> R.string.smart_routing_audio_alarm
    SmartRoutingAudioCategory.NOTIFICATION -> R.string.smart_routing_audio_notification
    SmartRoutingAudioCategory.SYSTEM_SONIFICATION -> R.string.smart_routing_audio_system_sonification
    SmartRoutingAudioCategory.UNKNOWN -> R.string.smart_routing_audio_unknown
}

@StringRes
private fun SmartRoutingAudioCategory.descriptionRes(): Int = when (this) {
    SmartRoutingAudioCategory.MEDIA -> R.string.smart_routing_audio_media_description
    SmartRoutingAudioCategory.GAME -> R.string.smart_routing_audio_game_description
    SmartRoutingAudioCategory.NAVIGATION -> R.string.smart_routing_audio_navigation_description
    SmartRoutingAudioCategory.ASSISTANT -> R.string.smart_routing_audio_assistant_description
    SmartRoutingAudioCategory.ACCESSIBILITY ->
        R.string.smart_routing_audio_accessibility_description
    SmartRoutingAudioCategory.ALARM -> R.string.smart_routing_audio_alarm_description
    SmartRoutingAudioCategory.NOTIFICATION -> R.string.smart_routing_audio_notification_description
    SmartRoutingAudioCategory.SYSTEM_SONIFICATION ->
        R.string.smart_routing_audio_system_sonification_description
    SmartRoutingAudioCategory.UNKNOWN -> R.string.smart_routing_audio_unknown_description
}
