package me.kavishdevar.librepods.presentation.viewmodel

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.billing.BillingManager
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import me.kavishdevar.librepods.data.GuardHealthLevel
import me.kavishdevar.librepods.data.GuardHealthReason
import me.kavishdevar.librepods.data.XposedRemotePrefProvider
import me.kavishdevar.librepods.services.guardHealthFlow
import me.kavishdevar.librepods.utils.RootSpatialAudioController
import me.kavishdevar.librepods.utils.SpatialAudioMode
import kotlin.math.roundToInt

data class AppSettingsUiState(
    val showPhoneBatteryInWidget: Boolean = false,
    val conversationalAwarenessPauseMusicEnabled: Boolean = false,
    val conversationalAwarenessBothPodsOnlyEnabled: Boolean = false,
    val relativeConversationalAwarenessVolumeEnabled: Boolean = true,
    val disconnectWhenNotWearing: Boolean = false,
    val useAlternateHeadTrackingPackets: Boolean = true,
    val spatialAudioMode: SpatialAudioMode = SpatialAudioMode.OFF,
    val spatialAudioCapabilityChecked: Boolean = false,
    val spatialAudioHelperAvailable: Boolean = false,
    val spatializerAvailable: Boolean = false,
    val spatialAudioBusy: Boolean = false,
    val spatialAudioError: String? = null,
    val conversationalAwarenessVolume: Float = 43f,
    val showCameraDialog: Boolean = false,
    val cameraPackageValue: String = "",
    val cameraPackageError: String? = null,
    val vendorIdHook: Boolean = false,
    val vendorAttSocket: Boolean = false,
    val remoteDeviceGuardEnabled: Boolean = true,
    val guardHealthLevel: GuardHealthLevel? = null,
    val guardHealthReason: GuardHealthReason? = null,
    val isPremium: Boolean = false,
    val connectionSuccessful: Boolean = false,
    val showBottomSheetPopup: Boolean = true,
    val showIslandPopup: Boolean = true,
    val timeUntilFOSSPremiumExpiry: Long = 0L,
    val m3eEnabled: Boolean = false
)

class AppSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val sharedPreferences = application.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _uiState = MutableStateFlow(AppSettingsUiState())
    val uiState = _uiState.asStateFlow()

    private val xposedRemotePref = XposedRemotePrefProvider.create()
    private val spatialAudioController = RootSpatialAudioController(application)

    val sharedPrefListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPref, key ->
        when (key) {
            "connection_successful" -> {
                _uiState.update {
                    it.copy(connectionSuccessful = sharedPref.getBoolean(key, false))
                }
            }
            "remote_device_audio_guard_enabled" -> {
                _uiState.update {
                    it.copy(remoteDeviceGuardEnabled = sharedPref.getBoolean(key, true))
                }
            }
        }
    }


    init {
        loadSettings()
        refreshSpatialAudioCapability()
        observeBilling()
        observeGuardHealth()
        sharedPreferences.registerOnSharedPreferenceChangeListener(sharedPrefListener)
    }

    private fun observeGuardHealth() {
        viewModelScope.launch {
            // guardState churns once a second while a remote host streams; the toggle
            // subtitle only renders level and reason, so drop the rest.
            guardHealthFlow
                .map { it?.level to it?.primaryReason }
                .distinctUntilChanged()
                .collect { (level, reason) ->
                    _uiState.update {
                        it.copy(
                            guardHealthLevel = level,
                            guardHealthReason = reason,
                        )
                    }
                }
        }
    }

    override fun onCleared() {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(sharedPrefListener)
    }

    private fun observeBilling() {
        viewModelScope.launch {
            BillingManager.provider.isPremium.collect { premium ->
                if (premium) {
                    sharedPreferences.edit {
                        remove("premium_expiry_time")
                        if (BuildConfig.PLAY_BUILD) remove("foss_upgraded")
                    }
                    _uiState.update { it.copy(isPremium = true, timeUntilFOSSPremiumExpiry = 0L) }
                } else {
                    // No billing premium, only update if no temporary premium is active
                    if (_uiState.value.timeUntilFOSSPremiumExpiry <= 0L) {
                        _uiState.update { it.copy(isPremium = false) }
                        // The both-pods-only conversational-awareness option is premium-gated in the
                        // UI; once entitlement lapses, clear the persisted flag so the service stops
                        // applying a behavior the user can no longer see or change.
                        if (sharedPreferences.getBoolean(
                                "conversational_awareness_both_pods_only",
                                false
                            )
                        ) {
                            sharedPreferences.edit {
                                remove("conversational_awareness_both_pods_only")
                            }
                            _uiState.update {
                                it.copy(conversationalAwarenessBothPodsOnlyEnabled = false)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun loadSettings() {
        // faulty update on Play caused PLAY_BUILD to be false and resulted in use of FOSS billing in Play. since FOSS is not verified, we need to give 2 weeks to verify the purchase

        val fossUpgraded = sharedPreferences.getBoolean("foss_upgraded", false)
        val expiryTime = sharedPreferences.getLong("premium_expiry_time", 0L)
        val now = System.currentTimeMillis()

        when {
            // existing temporary premium
            expiryTime > 0L -> {
                if (expiryTime <= now) {
                    sharedPreferences.edit {
                        remove("premium_expiry_time")
                        remove("foss_upgraded")
                    }

                    _uiState.update {
                        it.copy(
                            timeUntilFOSSPremiumExpiry = 0L,
                            isPremium = false
                        )
                    }
                } else {
                    _uiState.update {
                        it.copy(
                            timeUntilFOSSPremiumExpiry = expiryTime - now,
                            isPremium = true
                        )
                    }
                }
            }

            // First migration from accidental FOSS Play build
            fossUpgraded && !_uiState.value.isPremium && BuildConfig.PLAY_BUILD -> {
                val newExpiry = now + 28L * 24 * 60 * 60 * 1000

                sharedPreferences.edit {
                    putLong("premium_expiry_time", newExpiry)
                }

                _uiState.update {
                    it.copy(
                        timeUntilFOSSPremiumExpiry = newExpiry - now,
                        isPremium = true
                    )
                }
            }
        }

        _uiState.update { currentState ->
            currentState.copy(
                showPhoneBatteryInWidget = sharedPreferences.getBoolean("show_phone_battery_in_widget", false),
                conversationalAwarenessPauseMusicEnabled = sharedPreferences.getBoolean("conversational_awareness_pause_music", false),
                conversationalAwarenessBothPodsOnlyEnabled = sharedPreferences.getBoolean("conversational_awareness_both_pods_only", false),
                relativeConversationalAwarenessVolumeEnabled = sharedPreferences.getBoolean("relative_conversational_awareness_volume", true),
                disconnectWhenNotWearing = sharedPreferences.getBoolean("disconnect_when_not_wearing", false),
                useAlternateHeadTrackingPackets = sharedPreferences.getBoolean("use_alternate_head_tracking_packets", true),
                spatialAudioMode = SpatialAudioMode.fromPreferences(sharedPreferences),
                conversationalAwarenessVolume = sharedPreferences.getInt("conversational_awareness_volume", 43).toFloat(),
                cameraPackageValue = sharedPreferences.getString("custom_camera_package", "") ?: "",
                vendorIdHook = xposedRemotePref.getBoolean("vendor_id_hook", false),
                vendorAttSocket = sharedPreferences.getBoolean("vendor_att_socket", false),
                remoteDeviceGuardEnabled = sharedPreferences.getBoolean(
                    "remote_device_audio_guard_enabled", true
                ),
                connectionSuccessful = sharedPreferences.getBoolean("connection_successful", false),
                showBottomSheetPopup = sharedPreferences.getBoolean("show_bottom_sheet_popup", true),
                showIslandPopup = sharedPreferences.getBoolean("show_island_popup", true),
                m3eEnabled = sharedPreferences.getBoolean("m3e_enabled", true)
            )
        }
    }

    fun setShowPhoneBatteryInWidget(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("show_phone_battery_in_widget", enabled) }
        _uiState.update { it.copy(showPhoneBatteryInWidget = enabled) }
    }

    fun setConversationalAwarenessPauseMusicEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("conversational_awareness_pause_music", enabled) }
        _uiState.update { it.copy(conversationalAwarenessPauseMusicEnabled = enabled) }
    }

    fun setConversationalAwarenessBothPodsOnlyEnabled(enabled: Boolean) {
        sharedPreferences.edit {
            putBoolean("conversational_awareness_both_pods_only", enabled)
        }
        _uiState.update { it.copy(conversationalAwarenessBothPodsOnlyEnabled = enabled) }
    }

    fun setRelativeConversationalAwarenessVolumeEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("relative_conversational_awareness_volume", enabled) }
        _uiState.update { it.copy(relativeConversationalAwarenessVolumeEnabled = enabled) }
    }

    fun setDisconnectWhenNotWearing(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("disconnect_when_not_wearing", enabled) }
        _uiState.update { it.copy(disconnectWhenNotWearing = enabled) }
    }

    fun setUseAlternateHeadTrackingPackets(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("use_alternate_head_tracking_packets", enabled) }
        _uiState.update { it.copy(useAlternateHeadTrackingPackets = enabled) }
    }

    fun refreshSpatialAudioCapability() {
        _uiState.update {
            it.copy(spatialAudioBusy = true, spatialAudioError = null)
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = spatialAudioController.query()
            _uiState.update {
                it.copy(
                    spatialAudioCapabilityChecked = true,
                    spatialAudioHelperAvailable = result.helperAvailable,
                    spatializerAvailable = result.spatializerAvailable,
                    spatialAudioBusy = false,
                    spatialAudioError = result.error
                )
            }
        }
    }

    fun setSpatialAudioMode(mode: SpatialAudioMode) {
        if (_uiState.value.spatialAudioBusy) return
        _uiState.update { it.copy(spatialAudioBusy = true, spatialAudioError = null) }
        viewModelScope.launch(Dispatchers.IO) {
            // Until local AirPods playback and AACP are both ready, the
            // head-tracked selection behaves as fixed spatial audio. The
            // service promotes it to RELATIVE_WORLD after creating UHID.
            val platformMode = if (mode == SpatialAudioMode.HEAD_TRACKED) {
                SpatialAudioMode.FIXED
            } else {
                mode
            }
            val result = spatialAudioController.setMode(platformMode)
            val succeeded = result.supported
            if (succeeded) {
                sharedPreferences.edit {
                    putString(SpatialAudioMode.PREFERENCE_KEY, mode.preferenceValue)
                    remove("spatial_audio_enabled")
                }
            }
            _uiState.update {
                it.copy(
                    spatialAudioMode = if (succeeded) mode else it.spatialAudioMode,
                    spatialAudioCapabilityChecked = true,
                    spatialAudioHelperAvailable = result.helperAvailable,
                    spatializerAvailable = result.spatializerAvailable,
                    spatialAudioBusy = false,
                    spatialAudioError = result.error
                )
            }
        }
    }

    fun setConversationalAwarenessVolume(volume: Float) {
        sharedPreferences.edit { putInt("conversational_awareness_volume", volume.roundToInt()) }
        _uiState.update { it.copy(conversationalAwarenessVolume = volume) }
    }

    fun setShowCameraDialog(show: Boolean) {
        _uiState.update { it.copy(showCameraDialog = show) }
    }

    fun setCameraPackageValue(value: String) {
        _uiState.update { it.copy(cameraPackageValue = value) }
    }

    fun setCameraPackageError(error: String?) {
        _uiState.update { it.copy(cameraPackageError = error) }
    }

    fun saveCameraPackage() {
        if (_uiState.value.cameraPackageValue.isBlank()) {
            sharedPreferences.edit { remove("custom_camera_package") }
        } else {
            sharedPreferences.edit { putString("custom_camera_package", _uiState.value.cameraPackageValue) }
        }
        setShowCameraDialog(false)
    }

    fun setVendorIdHook(enabled: Boolean) {
        xposedRemotePref.putBoolean("vendor_id_hook", enabled)
        _uiState.update { it.copy(vendorIdHook = enabled) }
    }

    fun setVendorAttSocket(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("vendor_att_socket", enabled) }
        _uiState.update { it.copy(vendorAttSocket = enabled) }
    }

    fun setRemoteDeviceGuardEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("remote_device_audio_guard_enabled", enabled) }
        _uiState.update { it.copy(remoteDeviceGuardEnabled = enabled) }
    }

    fun setShowBottomSheetPopup(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("show_bottom_sheet_popup", enabled) }
        _uiState.update { it.copy(showBottomSheetPopup = enabled) }
    }

    fun setShowIslandPopup(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("show_island_popup", enabled) }
        _uiState.update { it.copy(showIslandPopup = enabled) }
    }

    fun setm3eEnabled(enabled: Boolean) {
        sharedPreferences.edit { putBoolean("m3e_enabled", enabled) }
        _uiState.update { it.copy(m3eEnabled = enabled) }
    }
}
