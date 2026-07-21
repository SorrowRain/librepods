/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.services

//import me.kavishdevar.librepods.utils.CrossDevice
//import me.kavishdevar.librepods.utils.CrossDevicePackets
import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.SystemClock
import android.os.UserHandle
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.MainActivity
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.StemPressType
import me.kavishdevar.librepods.bluetooth.AacpSocketLease
import me.kavishdevar.librepods.bluetooth.AacpReaderStartupGate
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.bluetooth.ATTManagerv2
import me.kavishdevar.librepods.bluetooth.BLEManager
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import me.kavishdevar.librepods.bluetooth.ReconnectEpisodeGate
import me.kavishdevar.librepods.bluetooth.TakeoverPacketSequenceFailure
import me.kavishdevar.librepods.bluetooth.TakeoverPacketSequenceMode
import me.kavishdevar.librepods.bluetooth.TakeoverPacketSourcePolicy
import me.kavishdevar.librepods.bluetooth.createBluetoothSocket
import me.kavishdevar.librepods.data.AirPodsInstance
import me.kavishdevar.librepods.data.AirPodsModels
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.CustomEq
import me.kavishdevar.librepods.data.EarDetectionMediaAction
import me.kavishdevar.librepods.data.EarDetectionMediaGate
import me.kavishdevar.librepods.data.EarDetectionReceiverGate
import me.kavishdevar.librepods.data.LocalHostIdentity
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.data.XposedRemotePrefProvider
import me.kavishdevar.librepods.data.SmartRoutingAudioPolicyPreferences
import me.kavishdevar.librepods.data.TakeoverAudioSource
import me.kavishdevar.librepods.data.TakeoverAttemptSideEffectGate
import me.kavishdevar.librepods.data.TakeoverPausePolicyGate
import me.kavishdevar.librepods.data.TakeoverRouteGate
import me.kavishdevar.librepods.data.TakeoverRouteObservationGate
import me.kavishdevar.librepods.data.TakeoverRouteObservationState
import me.kavishdevar.librepods.data.TakeoverRouteReadiness
import me.kavishdevar.librepods.data.TakeoverRouteSignals
import me.kavishdevar.librepods.data.TakeoverSourceRetryGate
import me.kavishdevar.librepods.data.TakeoverSourceRetryState
import me.kavishdevar.librepods.data.TargetRouteEvidence
import me.kavishdevar.librepods.diagnostics.AacpOperation
import me.kavishdevar.librepods.diagnostics.AacpSendOutcome
import me.kavishdevar.librepods.diagnostics.AacpStateKind
import me.kavishdevar.librepods.diagnostics.RoutingAction
import me.kavishdevar.librepods.diagnostics.RoutingCorrelation
import me.kavishdevar.librepods.diagnostics.RoutingEventDetail
import me.kavishdevar.librepods.diagnostics.RoutingTrace
import me.kavishdevar.librepods.presentation.overlays.IslandType
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import me.kavishdevar.librepods.presentation.overlays.PopupWindow
import me.kavishdevar.librepods.presentation.widgets.BatteryWidget
import me.kavishdevar.librepods.presentation.widgets.NoiseControlWidget
import me.kavishdevar.librepods.utils.GestureDetector
import me.kavishdevar.librepods.utils.HeadTracking
import me.kavishdevar.librepods.utils.MediaController
import me.kavishdevar.librepods.utils.PhoneSoundRoutingController
import me.kavishdevar.librepods.utils.RootHeadTrackerBridge
import me.kavishdevar.librepods.utils.RootAvrcpVolumeController
import me.kavishdevar.librepods.utils.RootSpatialAudioController
import me.kavishdevar.librepods.utils.SpatialAudioMode
import me.kavishdevar.librepods.utils.SystemApisUtils
import me.kavishdevar.librepods.utils.SystemApisUtils.DEVICE_TYPE_UNTETHERED_HEADSET
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_COMPANION_APP
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_DEVICE_TYPE
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MAIN_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MANUFACTURER_NAME
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_MODEL_NAME
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_BATTERY
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_CHARGING
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_ICON
import me.kavishdevar.librepods.utils.SystemApisUtils.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "AirPodsService"
private const val A2DP_VOLUME_RESYNC_DEBOUNCE_MS = 1_000L
private const val A2DP_VOLUME_PULSE_MS = 180L
private const val LOCAL_IDENTITY_A2DP_EVIDENCE_WINDOW_MS = 1_500L
private const val A2DP_ACTIVE_ROUTE_CONFIRMATION_TIMEOUT_MS = 1_000L
private const val PROFILE_REPAIR_INITIAL_DELAY_MS = 1_500L
private const val PROFILE_REPAIR_RETRY_DELAY_MS = 4_000L
private const val FRESH_IN_EAR_BLE_WINDOW_MS = 15_000L
private const val ACTION_A2DP_ACTIVE_DEVICE_CHANGED =
    "android.bluetooth.a2dp.profile.action.ACTIVE_DEVICE_CHANGED"
private const val MIUI_FOLD_TIMEOUT_EXTRA = "miui.fold.timeout"
private const val MIUI_FOLD_TIMEOUT_MS = 1_000L

private fun Notification.applyMiuiFoldTimeout(): Notification = apply {
    extras.putLong(MIUI_FOLD_TIMEOUT_EXTRA, MIUI_FOLD_TIMEOUT_MS)
}

object ServiceManager {
    private var service: AirPodsService? = null

    @Synchronized
    fun getService(): AirPodsService? {
        return service
    }

    @Synchronized
    fun setService(service: AirPodsService?) {
        this.service = service
    }
}

// @Suppress("unused")
class AirPodsService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    private enum class A2dpActivationResult {
        CONFIRMED,
        REQUESTED,
        FAILED,
    }

    var macAddress = ""
    @Volatile
    var localMac = ""
    lateinit var aacpManager: AACPManager
    lateinit var attManager: ATTManagerv2
    var airpodsInstance: AirPodsInstance? = null
    var cameraActive = false
    private var disconnectedBecauseReversed = false
    private var otherDeviceTookOver = false
    private var lastAudioSourceMac: String? = null
    private var lastAudioSourceType: AACPManager.Companion.AudioSourceType? = null
    private var lastConfirmedOwnership: Boolean? = null
    private var lastAacpControlRefreshAt = 0L
    private var lastA2dpVolumeResyncAt = 0L
    private var a2dpVolumeResyncGeneration = 0
    private val spatialHeadTrackerBridge = RootHeadTrackerBridge()
    private val rootAvrcpVolumeController by lazy { RootAvrcpVolumeController(this) }
    private val spatialAudioController by lazy { RootSpatialAudioController(this) }
    private val audioFeatureScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val aacpConnectLock = Any()
    private val automaticReconnectLock = Any()
    private val reconnectEpisodeGate = ReconnectEpisodeGate()
    private var automaticReconnectInFlightGeneration: Long? = null
    @Volatile
    private var automaticReconnectSuppressed = false
    @Volatile
    private var automaticReconnectGeneration = 0L
    // Kept until the reader takes ownership so a lifecycle reset can close startup sockets.
    @Volatile
    private var connectingAacpSocket: BluetoothSocket? = null
    @Volatile
    private var connectingAttSocket: BluetoothSocket? = null
    @Volatile
    private var isAirPodsA2dpPlaying = false
    @Volatile
    private var lastAirPodsA2dpStartedAt = 0L
    @Volatile
    private var isAirPodsA2dpConnected = false
    private val earDetectionReceiverLock = Any()
    private val a2dpActivationLock = Any()
    private var a2dpBroadcastGeneration = 0L
    private var a2dpQueryGeneration = 0L
    private var a2dpActivationGeneration = 0L
    private var earDetectionWornCount = 0
    private var earDetectionResumeGeneration = 0L
    private var earDetectionA2dpReceiver: BroadcastReceiver? = null
    @Volatile
    private var confirmedActiveA2dpAddress: String? = null
    @Volatile
    private var pendingTargetRouteActivationAttemptId: String? = null
    @Volatile
    private var requiredTargetRouteAttemptId: String? = null
    @Volatile
    private var pendingOwnershipRequiredAttemptId: String? = null
    @Volatile
    private var pendingFreshLocalSourceAttemptId: String? = null
    @Volatile
    private var freshLocalSourceConfirmedAttemptId: String? = null
    @Volatile
    private var completedTakeoverRequestAttemptId: String? = null
    @Volatile
    private var pendingTakeoverRetryCorrelation: RoutingCorrelation? = null
    private val takeoverSourceRetryLock = Any()
    private var takeoverSourceRetryState = TakeoverSourceRetryState()
    /** Captures the socket and target set used by the completed takeover request. */
    private val activeTakeoverPacketPermit = AtomicReference<TakeoverPacketPermit?>(null)
    private var takeoverRouteObservationState = TakeoverRouteObservationState()
    private var spatialAudioTransitionId = 0
    private val spatialAudioTransitionMutex = Mutex()

    private data class A2dpConnectionObservationToken(
        val broadcastGeneration: Long,
        val queryGeneration: Long,
    )

    private class TakeoverPacketPermit(
        val correlation: RoutingCorrelation,
        val lease: AacpSocketLease,
        val selfMacAddress: String,
        val targetMacAddresses: List<String>,
    ) {
        @Volatile
        var valid: Boolean = true
    }

    private fun invalidateActiveTakeoverPacketPermit(expectedAttemptId: String? = null) {
        while (true) {
            val permit = activeTakeoverPacketPermit.get() ?: return
            if (expectedAttemptId != null &&
                permit.correlation.takeoverAttemptId != expectedAttemptId
            ) {
                return
            }
            permit.valid = false
            if (activeTakeoverPacketPermit.compareAndSet(permit, null)) return
        }
    }

    data class ServiceConfig(
        var deviceName: String = "AirPods",
        var earDetectionEnabled: Boolean = true,
        var conversationalAwarenessPauseMusic: Boolean = false,
        var showPhoneBatteryInWidget: Boolean = true,
        var relativeConversationalAwarenessVolume: Boolean = true,
        var headGestures: Boolean = true,
        var disconnectWhenNotWearing: Boolean = false,
        var conversationalAwarenessVolume: Int = 43,
        var qsClickBehavior: String = "cycle",
        var bleOnlyMode: Boolean = false,

        // AirPods state-based takeover
        var takeoverWhenDisconnected: Boolean = true,
        var takeoverWhenIdle: Boolean = true,
        var takeoverWhenMusic: Boolean = false,
        var takeoverWhenCall: Boolean = true,

        // Phone state-based takeover
        var takeoverWhenRingingCall: Boolean = true,

        var leftSinglePressAction: StemAction = StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!,
        var rightSinglePressAction: StemAction = StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!,

        var leftDoublePressAction: StemAction = StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!,
        var rightDoublePressAction: StemAction = StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!,

        var leftTriplePressAction: StemAction = StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!,
        var rightTriplePressAction: StemAction = StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!,

        var leftLongPressAction: StemAction = StemAction.defaultActions[StemPressType.LONG_PRESS]!!,
        var rightLongPressAction: StemAction = StemAction.defaultActions[StemPressType.LONG_PRESS]!!,

        var cameraAction: StemPressType? = null,

        // AirPods device information
        var airpodsName: String = "",
        var airpodsModelNumber: String = "",
        var airpodsManufacturer: String = "",
        var airpodsSerialNumber: String = "",
        var airpodsLeftSerialNumber: String = "",
        var airpodsRightSerialNumber: String = "",
        var airpodsVersion1: String = "",
        var airpodsVersion2: String = "",
        var airpodsVersion3: String = "",
        var airpodsHardwareRevision: String = "",
        var airpodsUpdaterIdentifier: String = "",

        // phone's mac, needed for tipi
        var selfMacAddress: String = ""
    )

    private lateinit var config: ServiceConfig

    inner class LocalBinder : Binder() {
        fun getService(): AirPodsService = this@AirPodsService
    }

    private lateinit var sharedPreferences: SharedPreferences

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var phoneStateListener: TelephonyCallback

    private var handleIncomingCallOnceConnected = false

    lateinit var bleManager: BLEManager

    companion object {
        // Hidden Bluetooth profile APIs use 100 for CONNECTION_POLICY_ALLOWED,
        // but the constant itself is not exposed by the public SDK stubs.
        private const val CONNECTION_POLICY_ALLOWED = 100
        private const val AUTOMATIC_RECONNECT_RETRY_DELAY_MS = 1_500L
        private const val EXTRA_AACP_SOCKET_GENERATION = "aacp_socket_generation"

        init {
            System.loadLibrary("bluetooth_socket")
        }
    }

    private val bleStatusListener = object : BLEManager.AirPodsStatusListener {
        @SuppressLint("NewApi")
        override fun onDeviceStatusChanged(
            device: BLEManager.AirPodsStatus, previousStatus: BLEManager.AirPodsStatus?
        ) {
            Log.d(TAG, "Device status changed")
            if (BluetoothConnectionManager.aacpSocket?.isConnected == true) return
            val leftLevel = bleManager.getMostRecentStatus()?.leftBattery ?: 0
            val rightLevel = bleManager.getMostRecentStatus()?.rightBattery ?: 0
            val caseLevel = bleManager.getMostRecentStatus()?.caseBattery ?: 0
            val leftCharging = bleManager.getMostRecentStatus()?.isLeftCharging
            val rightCharging = bleManager.getMostRecentStatus()?.isRightCharging
            val caseCharging = bleManager.getMostRecentStatus()?.isCaseCharging

            batteryNotification.setBatteryDirect(
                leftLevel = leftLevel,
                leftCharging = leftCharging == true,
                rightLevel = rightLevel,
                rightCharging = rightCharging == true,
                caseLevel = caseLevel,
                caseCharging = caseCharging == true
            )
            updateBattery()
        }

        override fun onBroadcastFromNewAddress(device: BLEManager.AirPodsStatus) {
            Log.d(TAG, "New address detected")
            if (device.lidOpen || device.isLeftInEar || device.isRightInEar) {
                requestAutomaticReconnect(
                    "new_ble_address",
                    oncePerPresenceEpisode = true,
                )
            }
        }

        override fun onLidStateChanged(
            lidOpen: Boolean,
        ) {
            if (lidOpen) {
                Log.d(TAG, "Lid opened")
                showPopup(
                    this@AirPodsService,
                    getSharedPreferences("settings", MODE_PRIVATE).getString("name", "AirPods Pro")
                        ?: "AirPods"
                )
                requestAutomaticReconnect(
                    "lid_open",
                    oncePerPresenceEpisode = true,
                )
                if (BluetoothConnectionManager.aacpSocket?.isConnected == true) return
                val leftLevel = bleManager.getMostRecentStatus()?.leftBattery ?: 0
                val rightLevel = bleManager.getMostRecentStatus()?.rightBattery ?: 0
                val caseLevel = bleManager.getMostRecentStatus()?.caseBattery ?: 0
                val leftCharging = bleManager.getMostRecentStatus()?.isLeftCharging
                val rightCharging = bleManager.getMostRecentStatus()?.isRightCharging
                val caseCharging = bleManager.getMostRecentStatus()?.isCaseCharging

                batteryNotification.setBatteryDirect(
                    leftLevel = leftLevel,
                    leftCharging = leftCharging == true,
                    rightLevel = rightLevel,
                    rightCharging = rightCharging == true,
                    caseLevel = caseLevel,
                    caseCharging = caseCharging == true
                )
                sendBatteryBroadcast()
            } else {
                Log.d(TAG, "Lid closed")
                val status = bleManager.getMostRecentStatus()
                if (status?.isLeftInEar != true && status?.isRightInEar != true) {
                    resetReconnectPresenceEpisode("lid_closed")
                }
            }
        }

        override fun onEarStateChanged(
            device: BLEManager.AirPodsStatus, leftInEar: Boolean, rightInEar: Boolean
        ) {
            Log.d(TAG, "Ear state changed - Left: $leftInEar, Right: $rightInEar")
            if (leftInEar || rightInEar) {
                val reconnectStarted = requestAutomaticReconnect(
                    "ear_in",
                    oncePerPresenceEpisode = true,
                )
                if (!reconnectStarted && !isAirPodsA2dpConnected) {
                    val savedTarget = this@AirPodsService.device ?: runCatching {
                        getSystemService(BluetoothManager::class.java).adapter
                            .getRemoteDevice(macAddress)
                    }.getOrNull()
                    requestBoundedAudioProfileRepair(
                        reason = "ear_in_profile_missing",
                        target = savedTarget,
                    )
                }
            }

            // In BLE-only mode, ear detection is purely based on BLE data
            if (config.bleOnlyMode) {
                Log.d(TAG, "BLE-only mode: ear detection from BLE data")
            }
        }

        override fun onBatteryChanged(device: BLEManager.AirPodsStatus) {
            if (BluetoothConnectionManager.aacpSocket?.isConnected == true) return
            val leftLevel = bleManager.getMostRecentStatus()?.leftBattery ?: 0
            val rightLevel = bleManager.getMostRecentStatus()?.rightBattery ?: 0
            val caseLevel = bleManager.getMostRecentStatus()?.caseBattery ?: 0
            val leftCharging = bleManager.getMostRecentStatus()?.isLeftCharging
            val rightCharging = bleManager.getMostRecentStatus()?.isRightCharging
            val caseCharging = bleManager.getMostRecentStatus()?.isCaseCharging

            batteryNotification.setBatteryDirect(
                leftLevel = leftLevel,
                leftCharging = leftCharging == true,
                rightLevel = rightLevel,
                rightCharging = rightCharging == true,
                caseLevel = caseLevel,
                caseCharging = caseCharging == true
            )
            updateBattery()
            Log.d(TAG, "Battery changed")
        }

        override fun onDeviceDisappeared() {
            Log.d(TAG, "All disappeared")
            resetReconnectPresenceEpisode("ble_device_disappeared")
            updateNotificationContent(
                false
            )
        }
    }

    private fun handleAacpDisconnectedState(reason: String) {
        cancelTakeoverSourceRetries()
        invalidateActiveTakeoverPacketPermit()
        confirmedActiveA2dpAddress = null
        pendingTargetRouteActivationAttemptId = null
        if (MediaController.isAwaitingLocalRoute()) {
            pendingTakeoverRetryCorrelation = MediaController.currentTakeoverCorrelation()
            freshLocalSourceConfirmedAttemptId = null
            completedTakeoverRequestAttemptId = null
        }
        PhoneSoundRoutingController.setTargetAvailable(false, reason)
        RoutingTrace.record(MediaController.currentTakeoverCorrelation()) {
            RoutingEventDetail.AacpStateChanged(
                kind = AacpStateKind.SOCKET,
                previousValue = "connected",
                newValue = "disconnected",
                correlationBasis = reason,
            )
        }
        cancelPendingAirPodsAbsoluteVolumeResync(reason)
        rootAvrcpVolumeController.cancel()
        updateSpatialAudioTracking(reason)
        popupShown = false
        updateNotificationContent(false)
        evaluateTakeoverRoute(reason)
    }

    @SuppressLint("MissingPermission")
    private fun requestAutomaticReconnect(
        reason: String,
        ensureAudioProfiles: Boolean = true,
        oncePerPresenceEpisode: Boolean = false,
        boundedControlRepair: Boolean = false,
    ): Boolean {
        val controlRepairPermit = if (boundedControlRepair) {
            reconnectEpisodeGate.tryBeginControlReconnect().also { permit ->
                if (permit == null) {
                    Log.d(TAG, "AACP reconnect already pending or budget exhausted: $reason")
                }
            } ?: return false
        } else {
            null
        }
        val presenceEpisodeGeneration = if (oncePerPresenceEpisode) {
            reconnectEpisodeGate.tryBeginInitialConnect().also { generation ->
                if (generation == null) {
                    Log.d(TAG, "Automatic reconnect already attempted for this presence episode")
                }
            } ?: return false
        } else {
            null
        }
        val reconnectEpisodeGeneration =
            presenceEpisodeGeneration ?: controlRepairPermit?.generation
        var skipReason: String? = null
        val generation = synchronized(automaticReconnectLock) {
            if (presenceEpisodeGeneration != null && automaticReconnectSuppressed) {
                automaticReconnectSuppressed = false
                automaticReconnectGeneration++
            }
            when {
                automaticReconnectSuppressed -> {
                    skipReason = "suppressed after explicit disconnect"
                    -1L
                }
                automaticReconnectInFlightGeneration == automaticReconnectGeneration -> {
                    skipReason = "already in flight"
                    -1L
                }
                else -> {
                    automaticReconnectInFlightGeneration = automaticReconnectGeneration
                    automaticReconnectGeneration
                }
            }
        }
        if (generation < 0L) {
            Log.d(TAG, "Automatic reconnect $skipReason; coalescing $reason")
            controlRepairPermit?.let(reconnectEpisodeGate::finishControlReconnect)
            if (skipReason == "already in flight" && ensureAudioProfiles &&
                presenceEpisodeGeneration != null
            ) {
                requestCoalescedAudioProfileUpgrade(presenceEpisodeGeneration)
                return true
            }
            return false
        }

        audioFeatureScope.launch(Dispatchers.IO) {
            try {
                if (!isAutomaticReconnectAllowed(generation, reconnectEpisodeGeneration)) {
                    return@launch
                }
                val savedMac = sharedPreferences.getString("mac_address", null)
                if (savedMac.isNullOrBlank()) {
                    Log.w(TAG, "Skipping automatic reconnect: no saved AirPods address")
                    return@launch
                }
                val adapter = getSystemService(BluetoothManager::class.java).adapter
                val target = runCatching { adapter.getRemoteDevice(savedMac) }
                    .onFailure { error ->
                        Log.w(TAG, "Skipping automatic reconnect for invalid address", error)
                    }
                    .getOrNull() ?: return@launch
                Log.i(TAG, "Attempting automatic AirPods reconnect: $reason")
                if (ensureAudioProfiles) {
                    connectAudio(
                        this@AirPodsService,
                        target,
                        requestGuard = {
                            isAutomaticReconnectAllowed(generation, reconnectEpisodeGeneration)
                        },
                    )
                }
                var socketConnected = false
                for (attempt in 0..1) {
                    coroutineContext.ensureActive()
                    if (!isAutomaticReconnectAllowed(generation, reconnectEpisodeGeneration)) {
                        return@launch
                    }
                    socketConnected = connectToSocket(
                        adapter,
                        target,
                        connectionGuard = {
                            isAutomaticReconnectAllowed(generation, reconnectEpisodeGeneration)
                        },
                    )
                    coroutineContext.ensureActive()
                    if (socketConnected) break
                    if (attempt == 0) delay(AUTOMATIC_RECONNECT_RETRY_DELAY_MS)
                }
                if (!socketConnected) {
                    Log.w(TAG, "Automatic AACP reconnect failed after bounded retry: $reason")
                }
            } finally {
                controlRepairPermit?.let(reconnectEpisodeGate::finishControlReconnect)
                synchronized(automaticReconnectLock) {
                    if (automaticReconnectInFlightGeneration == generation) {
                        automaticReconnectInFlightGeneration = null
                    }
                }
            }
        }
        return true
    }

    private fun requestCoalescedAudioProfileUpgrade(presenceEpisodeGeneration: Long) {
        val lifecycleGeneration = automaticReconnectGeneration
        audioFeatureScope.launch(Dispatchers.IO) {
            if (!isAutomaticReconnectAllowed(lifecycleGeneration, presenceEpisodeGeneration)) {
                return@launch
            }
            val savedMac = sharedPreferences.getString("mac_address", null)
                ?.takeIf(String::isNotBlank) ?: return@launch
            val adapter = getSystemService(BluetoothManager::class.java).adapter
            val target = runCatching { adapter.getRemoteDevice(savedMac) }.getOrNull()
                ?: return@launch
            Log.i(TAG, "Upgrading coalesced reconnect to include A2DP/HFP")
            connectAudio(
                this@AirPodsService,
                target,
                requestGuard = {
                    isAutomaticReconnectAllowed(
                        lifecycleGeneration,
                        presenceEpisodeGeneration,
                    )
                },
                activateForMedia = false,
            )
        }
    }

    private fun isAutomaticReconnectAllowed(
        generation: Long,
        presenceEpisodeGeneration: Long? = null,
    ): Boolean =
        !automaticReconnectSuppressed &&
            automaticReconnectGeneration == generation &&
            (presenceEpisodeGeneration == null ||
                reconnectEpisodeGate.isCurrent(presenceEpisodeGeneration))

    private fun resetReconnectPresenceEpisode(reason: String) {
        reconnectEpisodeGate.reset()
        val startupSockets = synchronized(automaticReconnectLock) {
            automaticReconnectGeneration++
            connectingAacpSocket to connectingAttSocket
        }
        startupSockets.first?.let { socket -> runCatching { socket.close() } }
        startupSockets.second?.let(BluetoothConnectionManager::invalidateAttSocket)
        synchronized(automaticReconnectLock) {
            if (connectingAacpSocket === startupSockets.first) connectingAacpSocket = null
            if (connectingAttSocket === startupSockets.second) connectingAttSocket = null
        }
        Log.d(TAG, "Reset automatic reconnect presence episode: $reason")
    }

    private fun hasFreshInEarBlePresence(): Boolean {
        if (!this::bleManager.isInitialized) return false
        val status = bleManager.getMostRecentStatus() ?: return false
        val ageMs = System.currentTimeMillis() - status.lastSeen
        return ageMs in 0L..FRESH_IN_EAR_BLE_WINDOW_MS &&
            (status.isLeftInEar || status.isRightInEar)
    }

    private fun requestBoundedAudioProfileRepair(
        reason: String,
        target: BluetoothDevice?,
        delayMs: Long = PROFILE_REPAIR_INITIAL_DELAY_MS,
    ) {
        if (target == null || isAirPodsA2dpConnected || automaticReconnectSuppressed ||
            disconnectedBecauseReversed ||
            !hasFreshInEarBlePresence()
        ) {
            return
        }
        val permit = reconnectEpisodeGate.tryBeginProfileRepair() ?: run {
            Log.d(TAG, "Audio profile repair already pending or budget exhausted")
            return
        }
        val lifecycleGeneration = automaticReconnectGeneration
        Log.i(TAG, "Scheduling bounded audio profile repair ${permit.attempt}: $reason")
        audioFeatureScope.launch(Dispatchers.IO) {
            var retryRequired = false
            try {
                if (delayMs > 0) delay(delayMs)
                if (isAirPodsA2dpConnected ||
                    !isAutomaticReconnectAllowed(lifecycleGeneration, permit.generation) ||
                    disconnectedBecauseReversed || !hasFreshInEarBlePresence()
                ) {
                    return@launch
                }
                connectAudio(
                    this@AirPodsService,
                    target,
                    requestGuard = {
                        isAutomaticReconnectAllowed(lifecycleGeneration, permit.generation) &&
                            !disconnectedBecauseReversed && hasFreshInEarBlePresence()
                    },
                    activateForMedia = false,
                )
                RoutingTrace.record(MediaController.currentTakeoverCorrelation()) {
                    RoutingEventDetail.ActionResult(
                        action = RoutingAction.A2DP_CONNECT,
                        outcome = "requested",
                        reason = "bounded_profile_repair:$reason:attempt_${permit.attempt}",
                    )
                }
                if (permit.attempt == 1) {
                    delay(PROFILE_REPAIR_RETRY_DELAY_MS)
                    retryRequired = !isAirPodsA2dpConnected &&
                        isAutomaticReconnectAllowed(lifecycleGeneration, permit.generation) &&
                        !disconnectedBecauseReversed && hasFreshInEarBlePresence()
                }
            } finally {
                reconnectEpisodeGate.finishProfileRepair(permit)
            }
            if (retryRequired) {
                requestBoundedAudioProfileRepair(
                    reason = "${reason}_retry",
                    target = target,
                    delayMs = 0L,
                )
            }
        }
    }

    private fun suppressAutomaticReconnect() {
        val connectingSockets = synchronized(automaticReconnectLock) {
            automaticReconnectSuppressed = true
            automaticReconnectGeneration++
            (connectingAacpSocket to connectingAttSocket).also {
                connectingAacpSocket = null
                connectingAttSocket = null
            }
        }
        connectingSockets.first?.let { socket -> runCatching { socket.close() } }
        connectingSockets.second?.let(BluetoothConnectionManager::invalidateAttSocket)
    }

    private fun allowAutomaticReconnect() {
        synchronized(automaticReconnectLock) {
            automaticReconnectSuppressed = false
            automaticReconnectGeneration++
        }
    }

    fun isBluetoothSocketExempted(): Boolean {
        return try {
            BluetoothSocket::class.java.declaredConstructors // will throw if still blocked
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    @Synchronized
    private fun acceptLocalHostIdentity(candidate: String?, reason: String): Boolean {
        val normalized = LocalHostIdentity.normalize(candidate) ?: return false
        val changed = !normalized.equals(localMac, ignoreCase = true)
        localMac = normalized
        config.selfMacAddress = normalized
        if (sharedPreferences.getString("self_mac_address", null) != normalized) {
            sharedPreferences.edit { putString("self_mac_address", normalized) }
        }
        if (changed) {
            Log.i(TAG, "Resolved local Bluetooth identity from $reason")
        }
        return true
    }

    @SuppressLint("HardwareIds", "MissingPermission")
    private fun ensureLocalHostIdentity(reason: String): Boolean {
        if (acceptLocalHostIdentity(localMac, "cached_runtime")) return true
        if (acceptLocalHostIdentity(config.selfMacAddress, "cached_preference")) return true

        val canReadAdapterAddress =
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED &&
                checkSelfPermission("android.permission.LOCAL_MAC_ADDRESS") ==
                PackageManager.PERMISSION_GRANTED
        if (canReadAdapterAddress) {
            val adapterAddress = runCatching {
                getSystemService(BluetoothManager::class.java).adapter.address
            }.onFailure { error ->
                Log.w(TAG, "Could not read Bluetooth adapter address", error)
            }.getOrNull()
            if (acceptLocalHostIdentity(adapterAddress, "bluetooth_adapter:$reason")) return true
        }

        val rootAddress = runCatching {
            val process = Runtime.getRuntime().exec(
                arrayOf("su", "-c", "settings get secure bluetooth_address")
            )
            try {
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    process.destroy()
                    null
                } else if (process.exitValue() == 0) {
                    process.inputStream.bufferedReader().use { it.readLine() }
                } else {
                    null
                }
            } finally {
                process.destroy()
            }
        }.onFailure { error ->
            Log.w(TAG, "Root fallback could not read local Bluetooth identity", error)
        }.getOrNull()
        if (acceptLocalHostIdentity(rootAddress, "root_settings:$reason")) return true

        Log.w(TAG, "Local Bluetooth identity remains unavailable after $reason")
        return false
    }

    private fun maybeInferLocalHostIdentity(
        source: AACPManager.Companion.AudioSource?,
        reason: String,
    ): Boolean {
        if (source?.type != AACPManager.Companion.AudioSourceType.MEDIA) return false
        val inferred = LocalHostIdentity.inferFromActiveLocalMedia(
            currentLocalMac = localMac,
            sourceMac = source.mac,
            ownsConnection = aacpManager.owns,
            a2dpPlaying = isAirPodsA2dpPlaying,
            freshLocalA2dpStart = SystemClock.elapsedRealtime() - lastAirPodsA2dpStartedAt <=
                LOCAL_IDENTITY_A2DP_EVIDENCE_WINDOW_MS,
            eligibleLocalMediaPlaying = MediaController.isEligibleLocalPlaybackActive(),
        ) ?: return false
        return acceptLocalHostIdentity(inferred, "aacp_active_media:$reason")
    }

    private fun classifyAudioSource(
        source: AACPManager.Companion.AudioSource?
    ): TakeoverAudioSource = when {
        source == null -> TakeoverAudioSource.UNKNOWN
        source.type == AACPManager.Companion.AudioSourceType.NONE -> TakeoverAudioSource.NONE
        else -> LocalHostIdentity.classifyKnownSource(localMac, source.mac)
    }


    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag", "HardwareIds")
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "lib exempt worked: ${isBluetoothSocketExempted()}")

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        initializeConfig()

        aacpManager = AACPManager()
        initializeAACPManagerCallback()

        attManager = ATTManagerv2()

        sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        localMac = LocalHostIdentity.normalize(config.selfMacAddress).orEmpty()
        ensureLocalHostIdentity("service_create")

        ServiceManager.setService(this)
        startForegroundNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            initGestureDetector()
        } else {
            gestureDetector = null
            config.headGestures = false
            sharedPreferences.edit { putBoolean("head_gestures", false) }
            Log.d(TAG, "Head gestures disabled as device is running Android 9 or below")
        }

        bleManager = BLEManager(this)
        bleManager.setAirPodsStatusListener(bleStatusListener)

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)

        with(sharedPreferences) {
            edit {
                if (!contains("conversational_awareness_pause_music")) putBoolean(
                    "conversational_awareness_pause_music", false
                )
                if (!contains("personalized_volume")) putBoolean("personalized_volume", false)
                if (!contains("automatic_ear_detection")) putBoolean(
                    "automatic_ear_detection", true
                )
                if (!contains("long_press_nc")) putBoolean("long_press_nc", true)
                if (!contains("show_phone_battery_in_widget")) putBoolean(
                    "show_phone_battery_in_widget", true
                )
                if (!contains("single_anc")) putBoolean("single_anc", true)
                if (!contains("long_press_transparency")) putBoolean(
                    "long_press_transparency", true
                )
                if (!contains("conversational_awareness")) putBoolean(
                    "conversational_awareness", true
                )
                if (!contains("relative_conversational_awareness_volume")) putBoolean(
                    "relative_conversational_awareness_volume", true
                )
                if (!contains("long_press_adaptive")) putBoolean("long_press_adaptive", true)
                if (!contains("loud_sound_reduction")) putBoolean("loud_sound_reduction", true)
                if (!contains("long_press_off")) putBoolean("long_press_off", false)
                if (!contains("volume_control")) putBoolean("volume_control", true)
                if (!contains("head_gestures")) putBoolean("head_gestures", true)
                if (!contains("disconnect_when_not_wearing")) putBoolean(
                    "disconnect_when_not_wearing", false
                )

                // AirPods state-based takeover
                if (!contains("takeover_when_disconnected")) putBoolean(
                    "takeover_when_disconnected", false
                )
                if (!contains("takeover_when_idle")) putBoolean("takeover_when_idle", false)
                if (!contains("takeover_when_music")) putBoolean("takeover_when_music", false)
                if (!contains("takeover_when_call")) putBoolean("takeover_when_call", false)

                // Phone state-based takeover
                if (!contains("takeover_when_ringing_call")) putBoolean(
                    "takeover_when_ringing_call", false
                )

                if (!contains("adaptive_strength")) putInt("adaptive_strength", 51)
                if (!contains("tone_volume")) putInt("tone_volume", 75)
                if (!contains("conversational_awareness_volume")) putInt(
                    "conversational_awareness_volume", 43
                )

                if (!contains("qs_click_behavior")) putString("qs_click_behavior", "cycle")
                if (!contains("name")) putString("name", "AirPods")

                if (!contains("left_single_press_action")) putString(
                    "left_single_press_action",
                    StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!.name
                )
                if (!contains("right_single_press_action")) putString(
                    "right_single_press_action",
                    StemAction.defaultActions[StemPressType.SINGLE_PRESS]!!.name
                )
                if (!contains("left_double_press_action")) putString(
                    "left_double_press_action",
                    StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!.name
                )
                if (!contains("right_double_press_action")) putString(
                    "right_double_press_action",
                    StemAction.defaultActions[StemPressType.DOUBLE_PRESS]!!.name
                )
                if (!contains("left_triple_press_action")) putString(
                    "left_triple_press_action",
                    StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!.name
                )
                if (!contains("right_triple_press_action")) putString(
                    "right_triple_press_action",
                    StemAction.defaultActions[StemPressType.TRIPLE_PRESS]!!.name
                )
                if (!contains("left_long_press_action")) putString(
                    "left_long_press_action",
                    StemAction.defaultActions[StemPressType.LONG_PRESS]!!.name
                )
                if (!contains("right_long_press_action")) putString(
                    "right_long_press_action",
                    StemAction.defaultActions[StemPressType.LONG_PRESS]!!.name
                )
                if (!contains("camera_action")) putString("camera_action", "SINGLE_PRESS")

            }
        }

        initializeConfig()

        externalBroadcastReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "me.kavishdevar.librepods.SET_ANC_MODE") {
                    if (intent.hasExtra("mode")) {
                        val mode = intent.getIntExtra("mode", -1)
                        if (mode in 1..4) {
                            aacpManager.sendControlCommand(
                                AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
                                mode
                            )
                        }
                    } else {
                        val currentMode = ancNotification.status
                        val configByte = sharedPreferences.getInt("long_press_byte", 0b0111)
                        val allowOffModeValue =
                            aacpManager.controlCommandStatusList.find { it.identifier == AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION }
                        val allowOffMode =
                            allowOffModeValue?.value?.takeIf { it.isNotEmpty() }?.get(0) == 0x01.toByte() || sharedPreferences.getBoolean("off_listening_mode", true)
                        val nextMode = getNextMode(currentMode = currentMode, configByte = configByte, allowOffMode)

                        aacpManager.sendControlCommand(
                            AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
                            nextMode
                        )
                        Log.d(
                            TAG,
                            "Cycling ANC mode from $currentMode to $nextMode"
                        )
                    }
                } else  if (intent?.action == "me.kavishdevar.librepods.CONVO_DETECT") {
                    if (intent.hasExtra("enabled")) {
                        val enabled = intent.getBooleanExtra("enabled", false)
                        aacpManager.sendControlCommand(
                            AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG.value,
                            enabled
                        )
                    }
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(externalBroadcastReceiver, externalBroadcastFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                externalBroadcastReceiver, externalBroadcastFilter
            )
        }
        val audioManager = this@AirPodsService.getSystemService(AUDIO_SERVICE) as AudioManager
        PhoneSoundRoutingController.initialize(
            this@AirPodsService,
            audioManager,
            sharedPreferences
        )
        PhoneSoundRoutingController.setTargetAvailable(
            BluetoothConnectionManager.aacpSocket?.isConnected == true,
            "service_startup"
        )
        MediaController.initialize(
            audioManager, this@AirPodsService.getSharedPreferences(
                "settings", MODE_PRIVATE
            )
        )
//        Log.d(TAG, "Initializing CrossDevice")
//        CoroutineScope(Dispatchers.IO).launch {
//            CrossDevice.init(this@AirPodsService)
//            Log.d(TAG, "CrossDevice initialized")
//        }

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        macAddress = sharedPreferences.getString("mac_address", "") ?: ""
        if (macAddress.isNotBlank()) {
            val adapter = getSystemService(BluetoothManager::class.java).adapter
            device = adapter.bondedDevices.find {
                it.address.equals(macAddress, ignoreCase = true)
            }
            // Repair policy 0 left behind by older LibrePods builds without forcing
            // an audio takeover during service startup.
            connectAudio(this, device, requestConnection = false)
        }

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object: TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        val leAvailableForAudio =
                            bleManager.getMostRecentStatus()?.isLeftInEar == true || bleManager.getMostRecentStatus()?.isRightInEar == true
//                        if ((CrossDevice.isAvailable && !isConnectedLocally && earDetectionNotification.status.contains(0x00)) || leAvailableForAudio) CoroutineScope(Dispatchers.IO).launch {
                        if (leAvailableForAudio) runBlocking {
                            takeOver("call")
                        }
                        if (config.headGestures) {
                            handleIncomingCall()
                        }
                    }

                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        val leAvailableForAudio =
                            bleManager.getMostRecentStatus()?.isLeftInEar == true || bleManager.getMostRecentStatus()?.isRightInEar == true
//                        if ((CrossDevice.isAvailable && !isConnectedLocally && earDetectionNotification.status.contains(0x00)) || leAvailableForAudio) CoroutineScope(
                        if (leAvailableForAudio) CoroutineScope(
                            Dispatchers.IO
                        ).launch {
                            takeOver("call")
                        }
                        isInCall = true
                    }

                    TelephonyManager.CALL_STATE_IDLE -> {
                        isInCall = false
                        gestureDetector?.stopDetection()
                    }
                }
            }
        }
        if (checkSelfPermission("android.permission.READ_PHONE_STATE") == PackageManager.PERMISSION_GRANTED) {
            telephonyManager.registerTelephonyCallback(mainExecutor, phoneStateListener)
        }

        if (config.showPhoneBatteryInWidget) {
            widgetMobileBatteryEnabled = true
            val batteryChangedIntentFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            batteryChangedIntentFilter.addAction(AirPodsNotifications.DISCONNECT_RECEIVERS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(
                    BatteryChangedIntentReceiver, batteryChangedIntentFilter, RECEIVER_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                    BatteryChangedIntentReceiver, batteryChangedIntentFilter
                )
            }
        }
        val serviceIntentFilter = IntentFilter().apply {
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            addAction("android.bluetooth.device.action.BOND_STATE_CHANGED")
            addAction("android.bluetooth.device.action.NAME_CHANGED")
            addAction("android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.adapter.action.STATE_CHANGED")
            addAction("android.bluetooth.headset.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.headset.action.VENDOR_SPECIFIC_HEADSET_EVENT")
            addAction("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.a2dp.profile.action.PLAYING_STATE_CHANGED")
            addAction(ACTION_A2DP_ACTIVE_DEVICE_CHANGED)
            addAction("android.bluetooth.device.action.UUID")
        }

        connectionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == AirPodsNotifications.AIRPODS_CONNECTION_DETECTED) {
                    device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra("device", BluetoothDevice::class.java)!!
                    } else {
                        intent.getParcelableExtra("device") as BluetoothDevice?
                    }

                    if (config.deviceName == "AirPods" && device?.name != null) {
                        config.deviceName = device?.name ?: "AirPods"
                        sharedPreferences.edit { putString("name", config.deviceName) }
                    }

//                    Log.d("AirPodsCrossDevice", CrossDevice.isAvailable.toString())
//                    if (!CrossDevice.isAvailable) {
                    Log.d(TAG, "${config.deviceName} connected")
                    CoroutineScope(Dispatchers.IO).launch {
                        val bluetoothManager = getSystemService(BluetoothManager::class.java)
                        connectToSocket(bluetoothManager.adapter, device!!)
                    }
                    Log.d(TAG, "Setting metadata")
                    setMetadatas(device!!)
//                    isConnectedLocally = true
                    macAddress = device!!.address
                    sharedPreferences.edit {
                        putString("mac_address", macAddress)
                    }
//                    }

                } else if (intent?.action == AirPodsNotifications.AIRPODS_DISCONNECTED) {
                    val disconnectedGeneration = intent.getLongExtra(
                        EXTRA_AACP_SOCKET_GENERATION,
                        -1L,
                    )
                    if (disconnectedGeneration >= 0L) {
                        Log.d(
                            TAG,
                            "AACP disconnect already handled by generation owner: " +
                                "$disconnectedGeneration",
                        )
                        return
                    }
                    handleAacpDisconnectedState("legacy_disconnect_broadcast")
                }
            }
        }
        val showIslandReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == "me.kavishdevar.librepods.cross_device_island") {
                    showIsland(
                        this@AirPodsService,
                        batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.LEFT }?.level!!.coerceAtMost(
                                batteryNotification.getBattery()
                                    .find { it.component == BatteryComponent.RIGHT }?.level!!
                            )
                    )
                } else if (intent?.action == AirPodsNotifications.DISCONNECT_RECEIVERS) {
                    try {
                        context?.unregisterReceiver(this)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        val showIslandIntentFilter = IntentFilter().apply {
            addAction("me.kavishdevar.librepods.cross_device_island")
            addAction(AirPodsNotifications.DISCONNECT_RECEIVERS)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(showIslandReceiver, showIslandIntentFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                showIslandReceiver, showIslandIntentFilter
            )
        }

        val deviceIntentFilter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_CONNECTION_DETECTED)
            addAction(AirPodsNotifications.AIRPODS_DISCONNECTED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectionReceiver, deviceIntentFilter, RECEIVER_EXPORTED)
            registerReceiver(bluetoothReceiver, serviceIntentFilter, RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag") registerReceiver(
                connectionReceiver, deviceIntentFilter
            )
            registerReceiver(bluetoothReceiver, serviceIntentFilter)
        }

        RoutingTrace.setBaselineProvider(::recordRoutingDiagnosticBaseline)
        refreshCurrentA2dpPlaybackState("service startup")

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter

        bluetoothAdapter.bondedDevices.forEach { device ->
            device.fetchUuidsWithSdp()
            if (device.uuids != null) {
                if (device.uuids.contains(ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a"))) {
                    bluetoothAdapter.getProfileProxy(
                        this, object : BluetoothProfile.ServiceListener {
                            @SuppressLint("NewApi")
                            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                                if (profile == BluetoothProfile.A2DP) {
                                    val connectedDevices = proxy.connectedDevices
                                    if (connectedDevices.isNotEmpty()) {
//                                        if (!CrossDevice.isAvailable) {
                                        CoroutineScope(Dispatchers.IO).launch {
                                            connectToSocket(bluetoothAdapter, device)
                                        }
                                        setMetadatas(device)
                                        macAddress = device.address
                                        sharedPreferences.edit {
                                            putString("mac_address", macAddress)
                                        }
//                                        }
                                        sendBroadcast(
                                            Intent(AirPodsNotifications.AIRPODS_CONNECTED).apply {
                                                setPackage(packageName)
                                            })
                                    }
                                }
                                bluetoothAdapter.closeProfileProxy(profile, proxy)
                            }

                            override fun onServiceDisconnected(profile: Int) {}
                        }, BluetoothProfile.A2DP
                    )
                }
            }
        }

//        if (!isConnectedLocally && !CrossDevice.isAvailable) {
//            clearPacketLogs()
//        }

        CoroutineScope(Dispatchers.IO).launch {
            bleManager.startScanning()
        }
    }

    @Suppress("unused")
    fun cameraOpened() {
        Log.d(TAG, "Camera opened, gonna handle stem presses and take action if visible")
        cameraActive = true
        setupStemActions()
    }

    @Suppress("unused")
    fun cameraClosed() {
        cameraActive = false
        setupStemActions()
    }

    fun isCustomAction(
        action: StemAction?, default: StemAction?
    ): Boolean {
        return action != default
    }

    fun setupStemActions() {
        val singlePressDefault = StemAction.defaultActions[StemPressType.SINGLE_PRESS]
        val doublePressDefault = StemAction.defaultActions[StemPressType.DOUBLE_PRESS]
        val triplePressDefault = StemAction.defaultActions[StemPressType.TRIPLE_PRESS]
        val longPressDefault = StemAction.defaultActions[StemPressType.LONG_PRESS]

        val singlePressCustomized =
            isCustomAction(config.leftSinglePressAction, singlePressDefault) || isCustomAction(
                config.rightSinglePressAction, singlePressDefault
            ) || (cameraActive && config.cameraAction == StemPressType.SINGLE_PRESS)
        val doublePressCustomized =
            isCustomAction(config.leftDoublePressAction, doublePressDefault) || isCustomAction(
                config.rightDoublePressAction, doublePressDefault
            )
        val triplePressCustomized =
            isCustomAction(config.leftTriplePressAction, triplePressDefault) || isCustomAction(
                config.rightTriplePressAction, triplePressDefault
            )
        val longPressCustomized = isCustomAction(
            config.leftLongPressAction, longPressDefault
        ) || isCustomAction(
            config.rightLongPressAction, longPressDefault
        ) || (cameraActive && config.cameraAction == StemPressType.LONG_PRESS)
        Log.d(
            TAG,
            "Setting up stem actions: Single Press Customized: $singlePressCustomized, Double Press Customized: $doublePressCustomized, Triple Press Customized: $triplePressCustomized, Long Press Customized: $longPressCustomized"
        )
        aacpManager.sendStemConfigPacket(
            singlePressCustomized,
            doublePressCustomized,
            triplePressCustomized,
            longPressCustomized,
        )
    }

    @ExperimentalEncodingApi
    private fun initializeAACPManagerCallback() {
        aacpManager.setPacketCallback(object : AACPManager.PacketCallback {
            @SuppressLint("MissingPermission")
            override fun onBatteryInfoReceived(batteryInfo: ByteArray) {
                batteryNotification.setBattery(batteryInfo)
                sendBroadcast(Intent(AirPodsNotifications.BATTERY_DATA).apply {
                    putParcelableArrayListExtra("data", ArrayList(batteryNotification.getBattery()))
                    setPackage(packageName)
                })
                updateBattery()
                updateNotificationContent(
                    true,
                    this@AirPodsService.getSharedPreferences("settings", MODE_PRIVATE)
                        .getString("name", device?.name),
                    batteryNotification.getBattery()
                )
//                CrossDevice.sendRemotePacket(batteryInfo)
//                CrossDevice.batteryBytes = batteryInfo

                for (battery in batteryNotification.getBattery()) {
                    Log.d(
                        "AirPodsParser",
                        "${battery.getComponentName()}: ${battery.getStatusName()} at ${battery.level}% "
                    )
                }

                // Battery telemetry is not a connection-state signal. AirPods send these
                // packets repeatedly, so changing A2DP/HFP here creates a reconnect storm.
            }

            override fun onEarDetectionReceived(earDetection: ByteArray) {
                sendBroadcast(Intent(AirPodsNotifications.EAR_DETECTION_DATA).apply {
                    val list = earDetectionNotification.status
                    val bytes = ByteArray(2)
                    bytes[0] = list[0]
                    bytes[1] = list[1]
                    putExtra("data", bytes)
                }.apply {
                    setPackage(packageName)
                })
                Log.d(
                    "AirPodsParser",
                    "Ear Detection: ${earDetectionNotification.status[0]} ${earDetectionNotification.status[1]}"
                )
                processEarDetectionChange(earDetection)
            }

            override fun onConversationAwarenessReceived(conversationAwareness: ByteArray) {
                conversationAwarenessNotification.setData(conversationAwareness)
                sendBroadcast(Intent(AirPodsNotifications.CA_DATA).apply {
                    putExtra("data", conversationAwarenessNotification.status)
                }.apply {
                    setPackage(packageName)
                })

                if (conversationAwarenessNotification.status == 1.toByte() || conversationAwarenessNotification.status == 2.toByte()) {
                    MediaController.startSpeaking()
                } else if (conversationAwarenessNotification.status == 6.toByte() ||conversationAwarenessNotification.status == 8.toByte() || conversationAwarenessNotification.status == 9.toByte()) {
                    MediaController.stopSpeaking()
                }

                Log.d(
                    "AirPodsParser",
                    "Conversation Awareness: ${conversationAwarenessNotification.status}"
                )
            }

            override fun onControlCommandReceived(controlCommand: ByteArray) {
                val command = AACPManager.ControlCommand.fromByteArray(controlCommand)
                if (command.identifier == AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value) {
                    ancNotification.setStatus(byteArrayOf(command.value.takeIf { it.isNotEmpty() }
                        ?.get(0) ?: 0x00.toByte()))
                    sendANCBroadcast()
                    updateNoiseControlWidget()
                }
            }

            override fun onOwnershipChangeReceived(owns: Boolean) {
                val previousOwnership = lastConfirmedOwnership
                lastConfirmedOwnership = owns
                val correlation = MediaController.currentTakeoverCorrelation()
                RoutingTrace.record(correlation) {
                    RoutingEventDetail.AacpStateChanged(
                        kind = AacpStateKind.CONFIRMED_OWNERSHIP,
                        previousValue = previousOwnership?.toString(),
                        newValue = owns.toString(),
                        correlationBasis = if (correlation.takeoverAttemptId != null) {
                            "nearest_open_attempt_temporal"
                        } else {
                            "inbound_state_no_open_attempt"
                        }
                    )
                }
                if (owns) {
                    otherDeviceTookOver = false
                    evaluateTakeoverRoute("ownership_confirmed_local")
                    scheduleAirPodsAbsoluteVolumeResync(
                        "AACP ownership confirmed for this phone",
                        delayMs = 0L
                    )
                } else {
                    prewarmAirPodsAbsoluteVolumeResync("AACP ownership lost")
                    cancelPendingAirPodsAbsoluteVolumeResync("AACP ownership lost")
                    Log.d(TAG, "ownership lost")
                    if (MediaController.isAwaitingLocalRoute()) {
                        RoutingTrace.record(correlation) {
                            RoutingEventDetail.Decision(
                                action = RoutingAction.RELEASE_OWNERSHIP,
                                allowed = false,
                                reason = "transient_remote_ownership_during_local_takeover",
                                gates = mapOf("local_takeover_pending" to true)
                            )
                        }
                        evaluateTakeoverRoute("ownership_pending_remote_confirmation")
                        return
                    }
                    evaluateTakeoverRoute("ownership_confirmed_remote")
                    otherDeviceTookOver = true
                }
            }

            override fun onOwnershipToFalseRequest(sender: String, reasonReverseTapped: Boolean) {
                if (MediaController.isAwaitingLocalRoute() && !reasonReverseTapped) {
                    RoutingTrace.record(MediaController.currentTakeoverCorrelation()) {
                        RoutingEventDetail.Decision(
                            action = RoutingAction.RELEASE_OWNERSHIP,
                            allowed = false,
                            reason = "remote_release_request_during_local_takeover",
                            gates = mapOf(
                                "local_takeover_pending" to true,
                                "remote_reverse_explicit" to false
                            )
                        )
                    }
                    return
                }
                // TODO: Show a reverse button, but that's a lot of effort -- i'd have to change the UI too, which i hate doing, and handle other device's reverses too, and disconnect audio etc... so for now, just pause the audio and show the island without asking to reverse.
                // handling reverse is a problem because we'd have to disconnect the audio, but there's no option connect audio again natively, so notification would have to be changed. I wish there was a way to just "change the audio output device".
                // (20 minutes later) i've done it nonetheless :]
                val senderName =
                    aacpManager.connectedDevices.find { it.mac == sender }?.type ?: "Other device"
                Log.d(
                    TAG,
                    "other device has hijacked the connection, reasonReverseTapped: $reasonReverseTapped"
                )
                aacpManager.sendControlCommand(
                    AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                    byteArrayOf(0x00)
                )
                otherDeviceTookOver = true
                if (reasonReverseTapped) {
                    Log.d(TAG, "reverse tapped, releasing the local audio profiles once")
                    disconnectedBecauseReversed = true
                    disconnectAudio(this@AirPodsService, device)
                    showIsland(
                        this@AirPodsService,
                        (batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.LEFT }?.level
                            ?: 0).coerceAtMost(
                            batteryNotification.getBattery()
                                .find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                        ),
                        IslandType.MOVED_TO_OTHER_DEVICE,
                        reversed = true,
                        otherDeviceName = senderName
                    )
                }
                if (!aacpManager.owns) {
                    showIsland(
                        this@AirPodsService,
                        (batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.LEFT }?.level
                            ?: 0).coerceAtMost(
                            batteryNotification.getBattery()
                                .find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                        ),
                        IslandType.MOVED_TO_OTHER_DEVICE,
                        reversed = reasonReverseTapped,
                        otherDeviceName = senderName
                    )
                }
                MediaController.sendPause()
            }

            override fun onRemoteStreamingStateChanged(sender: String, isStreaming: Boolean) {
                if (!isStreaming) return
                when (LocalHostIdentity.classifyKnownSource(localMac, sender)) {
                    TakeoverAudioSource.LOCAL -> return
                    TakeoverAudioSource.UNKNOWN -> {
                        Log.w(TAG, "Ignoring streaming-host identity while local MAC is unknown")
                        return
                    }
                    else -> Unit
                }

                Log.d(
                    TAG,
                    "Remote Smart Routing host started streaming; releasing AACP ownership"
                )
                if (MediaController.isAwaitingLocalRoute()) {
                    RoutingTrace.record(MediaController.currentTakeoverCorrelation()) {
                        RoutingEventDetail.Decision(
                            action = RoutingAction.RELEASE_OWNERSHIP,
                            allowed = false,
                            reason = "remote_streaming_seen_during_local_takeover",
                            gates = mapOf("local_takeover_pending" to true)
                        )
                    }
                    return
                }
                MediaController.onRemoteAudioRouteConfirmed("remote_host_streaming")
                if (aacpManager.owns) {
                    aacpManager.sendControlCommand(
                        AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                        byteArrayOf(0x00),
                        MediaController.currentTakeoverCorrelation(),
                        "remote_host_started_streaming"
                    )
                }
                otherDeviceTookOver = true
            }

            override fun onShowNearbyUI(sender: String) {
                val senderName =
                    aacpManager.connectedDevices.find { it.mac == sender }?.type ?: "Other device"
                showIsland(
                    this@AirPodsService,
                    (batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.LEFT }?.level ?: 0).coerceAtMost(
                        batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                    ),
                    IslandType.MOVED_TO_OTHER_DEVICE,
                    reversed = false,
                    otherDeviceName = senderName
                )
            }

            override fun onDeviceInformationReceived(deviceInformation: AACPManager.Companion.AirPodsInformation) {
                Log.d(
                    "AirPodsParser",
                    "Device Information: name: ${deviceInformation.name}, modelNumber: ${deviceInformation.modelNumber}, manufacturer: ${deviceInformation.manufacturer}, serialNumber: ${deviceInformation.serialNumber}, version1: ${deviceInformation.version1}, version2: ${deviceInformation.version2}, hardwareRevision: ${deviceInformation.hardwareRevision}, updaterIdentifier: ${deviceInformation.updaterIdentifier}, leftSerialNumber: ${deviceInformation.leftSerialNumber}, rightSerialNumber: ${deviceInformation.rightSerialNumber}, version3: ${deviceInformation.version3}"
                )
                // Store in SharedPreferences
                sharedPreferences.edit {
                    putString("name", deviceInformation.name)
                    putString("airpods_model_number", deviceInformation.modelNumber)
                    putString("airpods_manufacturer", deviceInformation.manufacturer)
                    putString("airpods_serial_number", deviceInformation.serialNumber)
                    putString("airpods_left_serial_number", deviceInformation.leftSerialNumber)
                    putString("airpods_right_serial_number", deviceInformation.rightSerialNumber)
                    putString("airpods_version1", deviceInformation.version1)
                    putString("airpods_version2", deviceInformation.version2)
                    putString("airpods_version3", deviceInformation.version3)
                    putString("airpods_hardware_revision", deviceInformation.hardwareRevision)
                    putString("airpods_updater_identifier", deviceInformation.updaterIdentifier)
                }
                // Update config
                config.airpodsName = deviceInformation.name
                config.airpodsModelNumber = deviceInformation.modelNumber
                config.airpodsManufacturer = deviceInformation.manufacturer
                config.airpodsSerialNumber = deviceInformation.serialNumber
                config.airpodsLeftSerialNumber = deviceInformation.leftSerialNumber
                config.airpodsRightSerialNumber = deviceInformation.rightSerialNumber
                config.airpodsVersion1 = deviceInformation.version1
                config.airpodsVersion2 = deviceInformation.version2
                config.airpodsVersion3 = deviceInformation.version3
                config.airpodsHardwareRevision = deviceInformation.hardwareRevision
                config.airpodsUpdaterIdentifier = deviceInformation.updaterIdentifier

                val model = AirPodsModels.getModelByModelNumber(config.airpodsModelNumber)
                if (model != null) {
                    airpodsInstance = AirPodsInstance(
                        name = config.airpodsName,
                        model = model,
                        actualModelNumber = config.airpodsModelNumber,
                        serialNumber = config.airpodsSerialNumber,
                        leftSerialNumber = config.airpodsLeftSerialNumber,
                        rightSerialNumber = config.airpodsRightSerialNumber,
                        version1 = config.airpodsVersion1,
                        version2 = config.airpodsVersion2,
                        version3 = config.airpodsVersion3,
                    )
                    if (device != null) setMetadatas(device!!)
                }
                sendBroadcast(
                    Intent(AirPodsNotifications.AIRPODS_INFORMATION_UPDATED).setPackage(
                        packageName
                    )
                )
            }

            @SuppressLint("NewApi")
            override fun onHeadTrackingReceived(headTracking: ByteArray) {
                if (isHeadTrackingActive) {
                    HeadTracking.processPacket(headTracking)?.let(spatialHeadTrackerBridge::submit)
                    processHeadTrackingData(headTracking)
                }
            }

            override fun onProximityKeysReceived(proximityKeys: ByteArray) {
                val keys = aacpManager.parseProximityKeysResponse(proximityKeys)
                Log.d("AirPodsParser", "Proximity keys: $keys")
                sharedPreferences.edit {
                    for (key in keys) {
                        Log.d("AirPodsParser", "Proximity key: ${key.key.name} = ${key.value}")
                        putString(key.key.name, Base64.encode(key.value))
                    }
                }
            }

            override fun onStemPressReceived(stemPress: ByteArray) {

                val (stemPressType, bud) = aacpManager.parseStemPressResponse(stemPress)

                Log.d(
                    "AirPodsParser",
                    "Stem press received: $stemPressType on $bud, cameraActive: $cameraActive, cameraAction: ${config.cameraAction}"
                )
                if (cameraActive && config.cameraAction != null && stemPressType == config.cameraAction) {
                        Runtime.getRuntime().exec(arrayOf("su", "-c", "input keyevent 27"))
                } else {
                    val action = getActionFor(bud, stemPressType)
                    Log.d("AirPodsParser", "$bud $stemPressType action: $action")
                    action?.let { executeStemAction(it) }
                }
            }

            override fun onAudioSourceReceived(audioSource: ByteArray) {
                val previousMac = lastAudioSourceMac
                val previousType = lastAudioSourceType
                val currentSource = aacpManager.audioSource
                lastAudioSourceMac = currentSource?.mac
                lastAudioSourceType = currentSource?.type
                maybeInferLocalHostIdentity(currentSource, "audio_source_changed")
                val sourceOwner = classifyAudioSource(currentSource)
                val correlation = MediaController.currentTakeoverCorrelation()
                if (sourceOwner == TakeoverAudioSource.LOCAL &&
                    pendingFreshLocalSourceAttemptId == correlation.takeoverAttemptId
                ) {
                    freshLocalSourceConfirmedAttemptId = correlation.takeoverAttemptId
                    cancelTakeoverSourceRetries(correlation.takeoverAttemptId)
                }
                RoutingTrace.record(correlation) {
                    RoutingEventDetail.AacpStateChanged(
                        kind = AacpStateKind.AUDIO_SOURCE,
                        previousValue = previousType?.name,
                        newValue = currentSource?.type?.name ?: "UNKNOWN",
                        sourceAlias = when (sourceOwner) {
                            TakeoverAudioSource.LOCAL -> "local_phone"
                            TakeoverAudioSource.REMOTE -> "remote_device"
                            TakeoverAudioSource.NONE -> "none"
                            TakeoverAudioSource.UNKNOWN -> "unknown"
                        },
                        sourceType = currentSource?.type?.name,
                        correlationBasis = if (correlation.takeoverAttemptId != null) {
                            "nearest_open_attempt_temporal"
                        } else {
                            "inbound_state_no_open_attempt"
                        }
                    )
                }
                Log.d(
                    "AirPodsParser",
                    "Audio source changed mac: ${currentSource?.mac}, type: ${currentSource?.type?.name}"
                )
                if (sourceOwner == TakeoverAudioSource.REMOTE) {
                    if (MediaController.isAwaitingLocalRoute()) {
                        Log.d("AirPodsParser", "Remote source still active during local takeover")
                        RoutingTrace.record(correlation) {
                            RoutingEventDetail.Decision(
                                action = RoutingAction.RELEASE_OWNERSHIP,
                                allowed = false,
                                reason = "remote_source_during_local_takeover",
                                gates = mapOf("local_takeover_pending" to true)
                            )
                        }
                    } else {
                        confirmedActiveA2dpAddress = null
                        pendingTargetRouteActivationAttemptId = null
                        MediaController.onRemoteAudioRouteConfirmed("aacp_remote_source")
                        Log.d(
                            "AirPodsParser",
                            "Audio source is another device, giving up AACP control"
                        )
                        // Do not keep sending release packets after the accessory already
                        // confirmed that another host owns the session. A pending ownership=1
                        // request is still released, but a settled false state needs no packet.
                        if (aacpManager.owns || aacpManager.requestedOwnership == true) {
                            aacpManager.sendControlCommand(
                                AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                                byteArrayOf(0x00),
                                correlation,
                                "audio_source_is_remote"
                            )
                        } else {
                            Log.d(
                                "AirPodsParser",
                                "Skipping redundant ownership release; remote host already owns session"
                            )
                        }
                    }
                    // this also means that the other device has start playing the audio, and if that's true, we can again start listening for audio config changes
//                    Log.d(TAG, "Another device started playing audio, listening for audio config changes again")
//                    MediaController.pausedForOtherDevice = false
// future me: what the heck is this? this just means it will not be taking over again if audio source doesn't change???
                } else if (
                    sourceOwner == TakeoverAudioSource.LOCAL &&
                    previousMac != null &&
                    (LocalHostIdentity.classifyKnownSource(localMac, previousMac) !=
                        TakeoverAudioSource.LOCAL ||
                        previousType == AACPManager.Companion.AudioSourceType.NONE)
                ) {
                    refreshAacpControlSession("audio source returned to this phone")
                    scheduleAirPodsAbsoluteVolumeResync(
                        "audio source returned to this phone",
                        delayMs = 0L
                    )
                }
                if (sourceOwner == TakeoverAudioSource.REMOTE) {
                    retryPendingTakeover("remote_audio_source_received")
                }
                evaluateTakeoverRoute("audio_source_changed")
            }

            override fun onConnectedDevicesReceived(connectedDevices: List<AACPManager.Companion.ConnectedDevice>) {
                for (device in connectedDevices) {
                    Log.d(
                        "AirPodsParser",
                        "Connected device: ${device.mac}, info1: ${device.info1}, info2: ${device.info2})"
                    )
                }
                val newDevices = connectedDevices.filter { newDevice ->
                    val notInOld =
                        aacpManager.oldConnectedDevices.none { oldDevice -> oldDevice.mac == newDevice.mac }
                    val notLocal = LocalHostIdentity.classifyKnownSource(
                        localMac,
                        newDevice.mac
                    ) == TakeoverAudioSource.REMOTE
                    notInOld && notLocal
                }

                for (device in newDevices) {
                    Log.d(
                        "AirPodsParser",
                        "New connected device: ${device.mac}, info1: ${device.info1}, info2: ${device.info2})"
                    )
                    Log.d(
                        TAG,
                        "Sending new Tipi packet for device ${device.mac}, and sending media info to the device"
                    )
                    aacpManager.sendMediaInformationNewDevice(
                        selfMacAddress = localMac, targetMacAddress = device.mac
                    )
                    aacpManager.sendAddTiPiDevice(
                        selfMacAddress = localMac, targetMacAddress = device.mac
                    )
                }
                retryPendingTakeover("connected_devices_received")
            }

            override fun onHeadphoneAccommodationReceived(eqData: FloatArray) {
                sendBroadcast(
                    Intent(AirPodsNotifications.EQ_DATA).putExtra("eqData", eqData).apply {
                        setPackage(packageName)
                    })
            }

            override fun onCustomEqReceived(customEq: CustomEq) {
                // TODO
            }

            override fun onCapabilitiesReceived(capabilities: List<Capability>) {
                // TODO
            }

            override fun onUnknownPacketReceived(packet: ByteArray) {
                Log.d("AACPManager", "Unknown packet received (${packet.size} bytes)")
            }
        })
    }

    private fun isTargetAudioRouteActive(): Boolean {
        val targetAddress = device?.address ?: macAddress
        val activeA2dpDeviceMatches =
            LocalHostIdentity.normalize(confirmedActiveA2dpAddress) != null &&
                LocalHostIdentity.normalize(confirmedActiveA2dpAddress) ==
                LocalHostIdentity.normalize(targetAddress)
        return when (MediaController.eligibleTargetRouteEvidence()) {
            TargetRouteEvidence.CONFIRMED_TARGET -> true
            TargetRouteEvidence.CONFIRMED_OTHER -> false
            TargetRouteEvidence.UNKNOWN -> isAirPodsA2dpPlaying || activeA2dpDeviceMatches
        }
    }

    private fun clearTakeoverRouteRequirements(attemptId: String) {
        cancelTakeoverSourceRetries(attemptId)
        invalidateActiveTakeoverPacketPermit(attemptId)
        if (pendingTargetRouteActivationAttemptId == attemptId) {
            invalidateA2dpActivationTimeout()
            pendingTargetRouteActivationAttemptId = null
        }
        if (requiredTargetRouteAttemptId == attemptId) {
            requiredTargetRouteAttemptId = null
        }
        if (pendingOwnershipRequiredAttemptId == attemptId) {
            pendingOwnershipRequiredAttemptId = null
        }
        if (pendingFreshLocalSourceAttemptId == attemptId) {
            pendingFreshLocalSourceAttemptId = null
        }
        if (freshLocalSourceConfirmedAttemptId == attemptId) {
            freshLocalSourceConfirmedAttemptId = null
        }
        if (completedTakeoverRequestAttemptId == attemptId) {
            completedTakeoverRequestAttemptId = null
        }
        if (pendingTakeoverRetryCorrelation?.takeoverAttemptId == attemptId) {
            pendingTakeoverRetryCorrelation = null
        }
    }

    @Synchronized
    private fun evaluateTakeoverRoute(trigger: String) {
        val source = aacpManager.audioSource
        val sourceOwner = classifyAudioSource(source)
        val correlation = MediaController.currentTakeoverCorrelation()
        val attemptId = correlation.takeoverAttemptId
        val targetAudioActive = isTargetAudioRouteActive()
        if (pendingTargetRouteActivationAttemptId != null &&
            pendingTargetRouteActivationAttemptId != attemptId
        ) {
            invalidateA2dpActivationTimeout()
            pendingTargetRouteActivationAttemptId = null
        }
        if (requiredTargetRouteAttemptId != null && requiredTargetRouteAttemptId != attemptId) {
            requiredTargetRouteAttemptId = null
        }
        if (pendingOwnershipRequiredAttemptId != null &&
            pendingOwnershipRequiredAttemptId != attemptId
        ) {
            pendingOwnershipRequiredAttemptId = null
        }
        if (pendingFreshLocalSourceAttemptId != null &&
            pendingFreshLocalSourceAttemptId != attemptId
        ) {
            pendingFreshLocalSourceAttemptId = null
            freshLocalSourceConfirmedAttemptId = null
            completedTakeoverRequestAttemptId = null
        }
        val requireTargetAudioActive = attemptId != null &&
            requiredTargetRouteAttemptId == attemptId
        val requireOwnership = attemptId != null &&
            pendingOwnershipRequiredAttemptId == attemptId
        val requireFreshLocalSource = attemptId != null &&
            pendingFreshLocalSourceAttemptId == attemptId
        val signals = TakeoverRouteSignals(
            ownsConnection = aacpManager.owns,
            audioSource = sourceOwner,
            a2dpConnected = isAirPodsA2dpConnected,
            targetAudioActive = targetAudioActive,
            requireTargetAudioActive = requireTargetAudioActive,
            requireOwnership = requireOwnership,
            requireFreshLocalSource = requireFreshLocalSource,
            freshLocalSourceConfirmed = freshLocalSourceConfirmedAttemptId == attemptId,
            requireCompletedRequest = requireFreshLocalSource,
            requestCompleted = completedTakeoverRequestAttemptId == attemptId,
        )
        val readiness = TakeoverRouteGate.evaluate(signals)
        val awaitingLocalRoute = MediaController.isAwaitingLocalRoute()
        val observingLocalRoute = MediaController.isObservingLocalRoute()
        val observation = TakeoverRouteObservationGate.observe(
            state = takeoverRouteObservationState,
            attemptId = attemptId,
            readiness = readiness,
            allowShowUi = awaitingLocalRoute,
        )
        takeoverRouteObservationState = observation.state
        if (observation.readinessChanged) {
            RoutingTrace.record(correlation) {
                RoutingEventDetail.StateTransition(
                    stateMachine = "takeover_route",
                    previousState = observation.previousReadiness?.name ?: "UNKNOWN",
                    newState = readiness.name,
                    trigger = trigger,
                    reason = "owns=${signals.ownsConnection};source=${signals.audioSource.name};" +
                        "a2dp=${signals.a2dpConnected};target=${signals.targetAudioActive};" +
                        "targetRequired=${signals.requireTargetAudioActive};" +
                        "ownershipRequired=${signals.requireOwnership};" +
                        "freshSource=${signals.freshLocalSourceConfirmed};" +
                        "requestComplete=${signals.requestCompleted}"
                )
            }
        }
        if (attemptId != null && observingLocalRoute) {
            if (readiness != TakeoverRouteReadiness.READY) {
                MediaController.onLocalRouteNotReady(
                    attemptId,
                    "$trigger:${readiness.name.lowercase()}",
                )
                return
            }

            // The active-route request itself is confirmed, but retain the ownership/source/
            // target requirements until MediaController completes its independent pause barrier.
            invalidateA2dpActivationTimeout()
            pendingTargetRouteActivationAttemptId = null
            pendingTakeoverRetryCorrelation = null
            if (observation.shouldShowUi) {
                val showUiSent = aacpManager.sendSmartRoutingShowUI(localMac)
                traceAacpSend(
                    correlation,
                    AacpOperation.SHOW_NEARBY_UI,
                    showUiSent,
                    reason = "local_route_ready"
                )
            }
            if (MediaController.onLocalRouteReady(attemptId, trigger)) {
                clearTakeoverRouteRequirements(attemptId)
            }
        } else if (attemptId != null) {
            // Completion can happen from the asynchronous playback callback after READY was
            // observed. Retire its retained requirements on the next route observation.
            clearTakeoverRouteRequirements(attemptId)
        }
    }

    private fun recordRoutingDiagnosticBaseline() {
        if (!RoutingTrace.isEnabled) return
        val correlation = MediaController.currentTakeoverCorrelation()
        val source = aacpManager.audioSource
        val sourceOwner = classifyAudioSource(source)
        RoutingTrace.record(correlation) {
            RoutingEventDetail.AacpStateChanged(
                kind = AacpStateKind.SOCKET,
                previousValue = null,
                newValue = if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
                    "connected"
                } else {
                    "disconnected"
                },
                correlationBasis = "diagnostic_baseline"
            )
        }
        RoutingTrace.record(correlation) {
            RoutingEventDetail.AacpStateChanged(
                kind = AacpStateKind.CONFIRMED_OWNERSHIP,
                previousValue = null,
                newValue = aacpManager.owns.toString(),
                correlationBasis = "diagnostic_baseline"
            )
        }
        RoutingTrace.record(correlation) {
            RoutingEventDetail.AacpStateChanged(
                kind = AacpStateKind.AUDIO_SOURCE,
                previousValue = null,
                newValue = source?.type?.name ?: "UNKNOWN",
                sourceAlias = when (sourceOwner) {
                    TakeoverAudioSource.LOCAL -> "local_phone"
                    TakeoverAudioSource.REMOTE -> "remote_device"
                    TakeoverAudioSource.NONE -> "none"
                    TakeoverAudioSource.UNKNOWN -> "unknown"
                },
                sourceType = source?.type?.name,
                correlationBasis = "diagnostic_baseline"
            )
        }
        RoutingTrace.record(correlation) {
            RoutingEventDetail.A2dpStateChanged(
                signal = "connection",
                previousState = null,
                newState = isAirPodsA2dpConnected.toString(),
                targetDeviceMatched = true,
                observationSource = "diagnostic_baseline"
            )
        }
        RoutingTrace.record(correlation) {
            RoutingEventDetail.A2dpStateChanged(
                signal = "playing",
                previousState = null,
                newState = isAirPodsA2dpPlaying.toString(),
                targetDeviceMatched = true,
                observationSource = "diagnostic_baseline"
            )
        }
        MediaController.recordDiagnosticBaseline(
            additionalFlags = mapOf(
                "vendor_identity_hook_enabled" to isVendorIdentityHookEnabled(),
                "aacp_socket_connected" to
                    (BluetoothConnectionManager.aacpSocket?.isConnected == true),
                "a2dp_connected" to isAirPodsA2dpConnected,
                "phone_sound_policy_active" to PhoneSoundRoutingController.status.value.active
            )
        )
        PhoneSoundRoutingController.recordDiagnosticBaseline()
    }

    private fun getActionFor(
        bud: AACPManager.Companion.StemPressBudType, type: StemPressType
    ): StemAction? {
        return when (type) {
            StemPressType.SINGLE_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftSinglePressAction else config.rightSinglePressAction
            StemPressType.DOUBLE_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftDoublePressAction else config.rightDoublePressAction
            StemPressType.TRIPLE_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftTriplePressAction else config.rightTriplePressAction
            StemPressType.LONG_PRESS -> if (bud == AACPManager.Companion.StemPressBudType.LEFT) config.leftLongPressAction else config.rightLongPressAction
        }
    }

    private fun executeStemAction(action: StemAction) {
        when (action) {
            StemAction.defaultActions[StemPressType.SINGLE_PRESS] -> {
                Log.d(
                    "AirPodsParser", "Default single press action: Play/Pause, not taking action."
                )
            }

            StemAction.PLAY_PAUSE -> MediaController.sendPlayPause()
            StemAction.PREVIOUS_TRACK -> MediaController.sendPreviousTrack()
            StemAction.NEXT_TRACK -> MediaController.sendNextTrack()
            StemAction.DIGITAL_ASSISTANT -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val intent = Intent(Intent.ACTION_VOICE_COMMAND).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                } else {
                    Log.w(
                        "AirPodsParser",
                        "Digital Assistant action is not supported on this Android version."
                    )
                }
            }

            StemAction.CYCLE_NOISE_CONTROL_MODES -> {
                Log.d("AirPodsParser", "Cycling noise control modes")
                sendBroadcast(Intent("me.kavishdevar.librepods.SET_ANC_MODE").apply {
                    setPackage(packageName)
                })
            }
        }
    }

    private fun processEarDetectionChange(earDetection: ByteArray) {
        val inEarData = listOf(
            earDetectionNotification.status[0] == 0x00.toByte(),
            earDetectionNotification.status[1] == 0x00.toByte()
        )
        earDetectionNotification.setStatus(earDetection)
        if (config.earDetectionEnabled) {
            val data = earDetection.copyOfRange(earDetection.size - 2, earDetection.size)
            val newInEarData = listOf(
                data[0] == 0x00.toByte(), data[1] == 0x00.toByte()
            )
            val newWornCount = newInEarData.count { it }

            if (inEarData.sorted() == listOf(false, false) && newInEarData.sorted() != listOf(
                    false, false
                ) && islandWindow?.isVisible != true
            ) {
                showIsland(
                    this@AirPodsService,
                    (batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.LEFT }?.level ?: 0).coerceAtMost(
                        batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.RIGHT }?.level ?: 0
                    )
                )
            }

            if (newInEarData == listOf(false, false) && islandWindow?.isVisible == true) {
                islandWindow?.close()
            }

            when (EarDetectionMediaGate.decide(inEarData, newInEarData)) {
                EarDetectionMediaAction.PAUSE -> {
                    synchronized(earDetectionReceiverLock) {
                        earDetectionWornCount = newWornCount
                        invalidateEarDetectionA2dpReceiverLocked()
                        MediaController.requestEarDetectionPause()
                    }
                    if (newInEarData.none { it } && config.disconnectWhenNotWearing) {
                        disconnectAudio(this@AirPodsService, device)
                    }
                }
                EarDetectionMediaAction.RESUME -> {
                    synchronized(earDetectionReceiverLock) {
                        earDetectionWornCount = newWornCount
                    }
                    if (inEarData.none { it } || !isAirPodsA2dpConnected) {
                        connectAudio(this@AirPodsService, device)
                    }
                    if (isAirPodsA2dpConnected) {
                        synchronized(earDetectionReceiverLock) {
                            invalidateEarDetectionA2dpReceiverLocked()
                            requestEarDetectionResumeIfA2dpReadyLocked()
                        }
                    } else {
                        registerA2dpConnectionReceiver()
                    }
                }
                EarDetectionMediaAction.NONE -> synchronized(earDetectionReceiverLock) {
                    earDetectionWornCount = newWornCount
                }
            }

            Log.d(
                "AirPodsParser",
                "inEarData: ${inEarData.sorted()}, newInEarData: ${newInEarData.sorted()}"
            )
        }
    }

    private fun registerA2dpConnectionReceiver() {
        val a2dpIntentFilter =
            IntentFilter("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
        synchronized(earDetectionReceiverLock) {
            invalidateEarDetectionA2dpReceiverLocked()
            if (earDetectionWornCount == 0) return
            if (isAirPodsA2dpConnected) {
                requestEarDetectionResumeIfA2dpReadyLocked()
                return
            }

            val expectedGeneration = earDetectionResumeGeneration
            val a2dpConnectionStateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val isCurrent = synchronized(earDetectionReceiverLock) {
                        earDetectionA2dpReceiver === this &&
                            EarDetectionReceiverGate.shouldHandle(
                                expectedGeneration = expectedGeneration,
                                currentGeneration = earDetectionResumeGeneration,
                                wornCount = earDetectionWornCount,
                            )
                    }
                    if (!isCurrent) {
                        unregisterEarDetectionA2dpReceiver(this)
                        return
                    }
                    if (intent.action == "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED") {
                        val state = intent.getIntExtra(
                            BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED
                        )
                        val previousState = intent.getIntExtra(
                            BluetoothProfile.EXTRA_PREVIOUS_STATE,
                            BluetoothProfile.STATE_DISCONNECTED
                        )
                        val device = intent.getParcelableExtra<BluetoothDevice>(
                            BluetoothDevice.EXTRA_DEVICE
                        )

                        Log.d(
                            "MediaController",
                            "A2DP state changed: $previousState -> $state for device: ${device?.address}"
                        )

                        if (state == BluetoothProfile.STATE_CONNECTED &&
                            previousState != BluetoothProfile.STATE_CONNECTED &&
                            device?.address == this@AirPodsService.device?.address
                        ) {
                            val shouldEvaluateTakeover = synchronized(earDetectionReceiverLock) {
                                if (earDetectionA2dpReceiver !== this ||
                                    !EarDetectionReceiverGate.shouldHandle(
                                        expectedGeneration = expectedGeneration,
                                        currentGeneration = earDetectionResumeGeneration,
                                        wornCount = earDetectionWornCount,
                                    )
                                ) {
                                    unregisterEarDetectionA2dpReceiverLocked(this)
                                    return
                                }
                                // The always-on receiver owns the authoritative broadcast epoch.
                                // This one-shot receiver only makes the same CONNECTED state visible
                                // early enough to continue ear-detection handling.
                                isAirPodsA2dpConnected = true
                                unregisterEarDetectionA2dpReceiverLocked(this)
                                MediaController.isAwaitingLocalRoute()
                            }
                            if (shouldEvaluateTakeover) {
                                evaluateTakeoverRoute("ear_detection_a2dp_connected")
                            }
                            synchronized(earDetectionReceiverLock) {
                                requestEarDetectionResumeIfA2dpReadyLocked()
                            }
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(a2dpConnectionStateReceiver, a2dpIntentFilter, RECEIVER_EXPORTED)
            } else {
                registerReceiver(a2dpConnectionStateReceiver, a2dpIntentFilter)
            }
            earDetectionA2dpReceiver = a2dpConnectionStateReceiver
        }
    }

    private fun invalidateEarDetectionA2dpReceiver() {
        synchronized(earDetectionReceiverLock) {
            invalidateEarDetectionA2dpReceiverLocked()
        }
    }

    private fun invalidateEarDetectionA2dpReceiverLocked() {
        ++earDetectionResumeGeneration
        val receiver = earDetectionA2dpReceiver
        earDetectionA2dpReceiver = null
        if (receiver != null) {
            runCatching { unregisterReceiver(receiver) }
        }
    }

    private fun unregisterEarDetectionA2dpReceiver(receiver: BroadcastReceiver) {
        synchronized(earDetectionReceiverLock) {
            unregisterEarDetectionA2dpReceiverLocked(receiver)
        }
    }

    private fun unregisterEarDetectionA2dpReceiverLocked(receiver: BroadcastReceiver) {
        if (earDetectionA2dpReceiver === receiver) {
            ++earDetectionResumeGeneration
            earDetectionA2dpReceiver = null
        }
        runCatching { unregisterReceiver(receiver) }
    }

    /**
     * Re-arm the one-shot ear-detection resume after a profile reconnect. All callers hold
     * [earDetectionReceiverLock], so a disconnect cannot suspend the request between the route
     * check and the request itself.
     */
    private fun requestEarDetectionResumeIfA2dpReadyLocked() {
        if (earDetectionWornCount <= 0 ||
            !isAirPodsA2dpConnected
        ) {
            return
        }
        Log.d(
            "MediaController",
            "A2DP connected after ear insertion, requesting guarded resume"
        )
        MediaController.requestEarDetectionResume()
    }

    private fun beginA2dpConnectionObservation(): A2dpConnectionObservationToken =
        synchronized(earDetectionReceiverLock) {
            A2dpConnectionObservationToken(
                broadcastGeneration = a2dpBroadcastGeneration,
                queryGeneration = ++a2dpQueryGeneration,
            )
        }

    private fun applyA2dpConnectionBroadcastLocked(connected: Boolean) {
        ++a2dpBroadcastGeneration
        isAirPodsA2dpConnected = connected
        if (!connected) {
            MediaController.suspendEarDetectionResumeUntilRouteReady()
        }
    }

    private fun applyA2dpConnectionObservation(
        expectedToken: A2dpConnectionObservationToken,
        connected: Boolean,
    ): Boolean = synchronized(earDetectionReceiverLock) {
        if (a2dpBroadcastGeneration != expectedToken.broadcastGeneration ||
            a2dpQueryGeneration != expectedToken.queryGeneration
        ) {
            return@synchronized false
        }
        isAirPodsA2dpConnected = connected
        if (!connected) {
            MediaController.suspendEarDetectionResumeUntilRouteReady()
        }
        true
    }

    private fun isA2dpBroadcastGenerationCurrent(
        expectedToken: A2dpConnectionObservationToken,
    ): Boolean = synchronized(earDetectionReceiverLock) {
        a2dpBroadcastGeneration == expectedToken.broadcastGeneration
    }

    private fun invalidateA2dpActivationTimeout() {
        synchronized(a2dpActivationLock) {
            ++a2dpActivationGeneration
        }
    }

    private fun armA2dpActivationTimeout(): Long = synchronized(a2dpActivationLock) {
        ++a2dpActivationGeneration
    }

    private fun claimA2dpActivationTimeout(
        expectedAttemptId: String,
        expectedGeneration: Long,
    ): Boolean = synchronized(a2dpActivationLock) {
        if (a2dpActivationGeneration != expectedGeneration ||
            pendingTargetRouteActivationAttemptId != expectedAttemptId
        ) {
            return@synchronized false
        }
        ++a2dpActivationGeneration
        pendingTargetRouteActivationAttemptId = null
        true
    }

    private fun initializeConfig() {
        config = ServiceConfig(
            deviceName = sharedPreferences.getString("name", "AirPods") ?: "AirPods",
            earDetectionEnabled = sharedPreferences.getBoolean("automatic_ear_detection", true),
            conversationalAwarenessPauseMusic = sharedPreferences.getBoolean(
                "conversational_awareness_pause_music", false
            ),
            showPhoneBatteryInWidget = sharedPreferences.getBoolean(
                "show_phone_battery_in_widget", true
            ),
            relativeConversationalAwarenessVolume = sharedPreferences.getBoolean(
                "relative_conversational_awareness_volume", true
            ),
            headGestures = sharedPreferences.getBoolean("head_gestures", true),
            disconnectWhenNotWearing = sharedPreferences.getBoolean(
                "disconnect_when_not_wearing", false
            ),
            conversationalAwarenessVolume = sharedPreferences.getInt(
                "conversational_awareness_volume", 43
            ),
            qsClickBehavior = sharedPreferences.getString("qs_click_behavior", "cycle") ?: "cycle",

            // AirPods state-based takeover
            takeoverWhenDisconnected = sharedPreferences.getBoolean(
                "takeover_when_disconnected", false
            ),
            takeoverWhenIdle = sharedPreferences.getBoolean("takeover_when_idle", false),
            takeoverWhenMusic = sharedPreferences.getBoolean("takeover_when_music", false),
            takeoverWhenCall = sharedPreferences.getBoolean("takeover_when_call", false),

            // Phone state-based takeover
            takeoverWhenRingingCall = sharedPreferences.getBoolean(
                "takeover_when_ringing_call", false
            ),
            // Stem actions
            leftSinglePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_single_press_action", "PLAY_PAUSE"
                ) ?: "PLAY_PAUSE"
            )!!,
            rightSinglePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_single_press_action", "PLAY_PAUSE"
                ) ?: "PLAY_PAUSE"
            )!!,

            leftDoublePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_double_press_action", "PREVIOUS_TRACK"
                ) ?: "NEXT_TRACK"
            )!!,
            rightDoublePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_double_press_action", "NEXT_TRACK"
                ) ?: "NEXT_TRACK"
            )!!,

            leftTriplePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_triple_press_action", "PREVIOUS_TRACK"
                ) ?: "PREVIOUS_TRACK"
            )!!,
            rightTriplePressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_triple_press_action", "PREVIOUS_TRACK"
                ) ?: "PREVIOUS_TRACK"
            )!!,

            leftLongPressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "left_long_press_action", "CYCLE_NOISE_CONTROL_MODES"
                ) ?: "CYCLE_NOISE_CONTROL_MODES"
            )!!,
            rightLongPressAction = StemAction.fromString(
                sharedPreferences.getString(
                    "right_long_press_action", "DIGITAL_ASSISTANT"
                ) ?: "DIGITAL_ASSISTANT"
            )!!,

            cameraAction = sharedPreferences.getString("camera_action", null)
                ?.let { StemPressType.valueOf(it) },

            // AirPods device information
            airpodsName = sharedPreferences.getString("airpods_name", "") ?: "",
            airpodsModelNumber = sharedPreferences.getString("airpods_model_number", "") ?: "",
            airpodsManufacturer = sharedPreferences.getString("airpods_manufacturer", "") ?: "",
            airpodsSerialNumber = sharedPreferences.getString("airpods_serial_number", "") ?: "",
            airpodsLeftSerialNumber = sharedPreferences.getString("airpods_left_serial_number", "")
                ?: "",
            airpodsRightSerialNumber = sharedPreferences.getString(
                "airpods_right_serial_number", ""
            ) ?: "",
            airpodsVersion1 = sharedPreferences.getString("airpods_version1", "") ?: "",
            airpodsVersion2 = sharedPreferences.getString("airpods_version2", "") ?: "",
            airpodsVersion3 = sharedPreferences.getString("airpods_version3", "") ?: "",
            airpodsHardwareRevision = sharedPreferences.getString("airpods_hardware_revision", "")
                ?: "",
            airpodsUpdaterIdentifier = sharedPreferences.getString("airpods_updater_identifier", "")
                ?: "",

            selfMacAddress = sharedPreferences.getString("self_mac_address", "") ?: ""
        )
    }

    override fun onSharedPreferenceChanged(preferences: SharedPreferences?, key: String?) {
        if (preferences == null || key == null) return

        when (key) {
            "name" -> config.deviceName = preferences.getString(key, "AirPods") ?: "AirPods"
            "mac_address" -> macAddress = preferences.getString(key, "") ?: ""
            "automatic_ear_detection" -> {
                config.earDetectionEnabled = preferences.getBoolean(key, true)
                if (!config.earDetectionEnabled) {
                    synchronized(earDetectionReceiverLock) {
                        earDetectionWornCount = 0
                        invalidateEarDetectionA2dpReceiverLocked()
                        MediaController.cancelEarDetectionAutomation()
                    }
                }
            }

            "conversational_awareness_pause_music" -> config.conversationalAwarenessPauseMusic =
                preferences.getBoolean(key, false)

            "show_phone_battery_in_widget" -> {
                config.showPhoneBatteryInWidget = preferences.getBoolean(key, true)
                widgetMobileBatteryEnabled = config.showPhoneBatteryInWidget
                updateBattery()
            }

            "relative_conversational_awareness_volume" -> config.relativeConversationalAwarenessVolume =
                preferences.getBoolean(key, true)

            "head_gestures" -> config.headGestures = preferences.getBoolean(key, true)
            "spatial_audio_enabled", SpatialAudioMode.PREFERENCE_KEY -> {
                updateSpatialAudioTracking("setting changed")
            }
            "disconnect_when_not_wearing" -> config.disconnectWhenNotWearing =
                preferences.getBoolean(key, false)

            "conversational_awareness_volume" -> config.conversationalAwarenessVolume =
                preferences.getInt(key, 43)

            "qs_click_behavior" -> config.qsClickBehavior =
                preferences.getString(key, "cycle") ?: "cycle"

            // AirPods state-based takeover
            "takeover_when_disconnected" -> config.takeoverWhenDisconnected =
                preferences.getBoolean(key, true)

            "takeover_when_idle" -> config.takeoverWhenIdle = preferences.getBoolean(key, true)
            "takeover_when_music" -> config.takeoverWhenMusic = preferences.getBoolean(key, false)
            "takeover_when_call" -> config.takeoverWhenCall = preferences.getBoolean(key, true)

            // Phone state-based takeover
            "takeover_when_ringing_call" -> config.takeoverWhenRingingCall =
                preferences.getBoolean(key, true)

            "left_single_press_action" -> {
                config.leftSinglePressAction = StemAction.fromString(
                    preferences.getString(key, "PLAY_PAUSE") ?: "PLAY_PAUSE"
                )!!
                setupStemActions()
            }

            "right_single_press_action" -> {
                config.rightSinglePressAction = StemAction.fromString(
                    preferences.getString(key, "PLAY_PAUSE") ?: "PLAY_PAUSE"
                )!!
                setupStemActions()
            }

            "left_double_press_action" -> {
                config.leftDoublePressAction = StemAction.fromString(
                    preferences.getString(key, "PREVIOUS_TRACK") ?: "PREVIOUS_TRACK"
                )!!
                setupStemActions()
            }

            "right_double_press_action" -> {
                config.rightDoublePressAction = StemAction.fromString(
                    preferences.getString(key, "NEXT_TRACK") ?: "NEXT_TRACK"
                )!!
                setupStemActions()
            }

            "left_triple_press_action" -> {
                config.leftTriplePressAction = StemAction.fromString(
                    preferences.getString(key, "PREVIOUS_TRACK") ?: "PREVIOUS_TRACK"
                )!!
                setupStemActions()
            }

            "right_triple_press_action" -> {
                config.rightTriplePressAction = StemAction.fromString(
                    preferences.getString(key, "PREVIOUS_TRACK") ?: "PREVIOUS_TRACK"
                )!!
                setupStemActions()
            }

            "left_long_press_action" -> {
                config.leftLongPressAction = StemAction.fromString(
                    preferences.getString(key, "CYCLE_NOISE_CONTROL_MODES")
                        ?: "CYCLE_NOISE_CONTROL_MODES"
                )!!
                setupStemActions()
            }

            "right_long_press_action" -> {
                config.rightLongPressAction = StemAction.fromString(
                    preferences.getString(key, "DIGITAL_ASSISTANT") ?: "DIGITAL_ASSISTANT"
                )!!
                setupStemActions()
            }

            "camera_action" -> config.cameraAction =
                preferences.getString(key, null)?.let { StemPressType.valueOf(it) }

            // AirPods device information
            "airpods_name" -> config.airpodsName = preferences.getString(key, "") ?: ""
            "airpods_model_number" -> config.airpodsModelNumber =
                preferences.getString(key, "") ?: ""

            "airpods_manufacturer" -> config.airpodsManufacturer =
                preferences.getString(key, "") ?: ""

            "airpods_serial_number" -> config.airpodsSerialNumber =
                preferences.getString(key, "") ?: ""

            "airpods_left_serial_number" -> config.airpodsLeftSerialNumber =
                preferences.getString(key, "") ?: ""

            "airpods_right_serial_number" -> config.airpodsRightSerialNumber =
                preferences.getString(key, "") ?: ""

            "airpods_version1" -> config.airpodsVersion1 = preferences.getString(key, "") ?: ""
            "airpods_version2" -> config.airpodsVersion2 = preferences.getString(key, "") ?: ""
            "airpods_version3" -> config.airpodsVersion3 = preferences.getString(key, "") ?: ""
            "airpods_hardware_revision" -> config.airpodsHardwareRevision =
                preferences.getString(key, "") ?: ""

            "airpods_updater_identifier" -> config.airpodsUpdaterIdentifier =
                preferences.getString(key, "") ?: ""

            "self_mac_address" -> {
                val configuredAddress = preferences.getString(key, null)
                if (acceptLocalHostIdentity(configuredAddress, "preference_changed")) {
                    evaluateTakeoverRoute("local_identity_preference_changed")
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return LocalBinder()
    }

    private var gestureDetector: GestureDetector? = null
    private var isInCall = false
    private var callNumber: String? = null

    private fun initGestureDetector() {
        if (gestureDetector == null) {
            gestureDetector = GestureDetector(this)
        }
    }


    var popupShown = false
    fun showPopup(service: Service, name: String) {
        if (!sharedPreferences.getBoolean("show_bottom_sheet_popup", true)) {
            return
        }
        if (!Settings.canDrawOverlays(service)) {
            Log.d(TAG, "No permission for SYSTEM_ALERT_WINDOW")
            return
        }
        if (popupShown) {
            return
        }
        val popupWindow = PopupWindow(service.applicationContext)
        popupWindow.open(name, batteryNotification)
        popupShown = true
    }

    var islandOpen = false
    var islandWindow: IslandWindow? = null

    @SuppressLint("MissingPermission")
    fun showIsland(
        service: Service,
        batteryPercentage: Int,
        type: IslandType = IslandType.CONNECTED,
        reversed: Boolean = false,
        otherDeviceName: String? = null
    ) {
        Log.d(TAG, "Showing island window")
        if (!sharedPreferences.getBoolean("show_island_popup", true)) {
            return
        }
        if (!Settings.canDrawOverlays(service)) {
            Log.d(TAG, "No permission for SYSTEM_ALERT_WINDOW")
            return
        }
        CoroutineScope(Dispatchers.Main).launch {
            islandWindow = IslandWindow(service.applicationContext)
            islandWindow!!.show(
                sharedPreferences.getString("name", "AirPods Pro").toString(),
                batteryPercentage,
                this@AirPodsService,
                type,
                reversed,
                otherDeviceName
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun startMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    //    var isConnectedLocally = false
    var device: BluetoothDevice? = null

    private lateinit var earReceiver: BroadcastReceiver
    var widgetMobileBatteryEnabled = false

    object BatteryChangedIntentReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                ServiceManager.getService()?.updateBattery()
            } else if (intent.action == AirPodsNotifications.DISCONNECT_RECEIVERS) {
                try {
                    context?.unregisterReceiver(this)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun startForegroundNotification() {
        val disconnectedNotificationChannel = NotificationChannel(
            "background_service_status",
            "Background Service Status",
            NotificationManager.IMPORTANCE_NONE
        )

        val connectedNotificationChannel = NotificationChannel(
            "airpods_connection_status",
            "AirPods Connection Status",
            NotificationManager.IMPORTANCE_LOW,
        )

        val socketFailureChannel = NotificationChannel(
            "socket_connection_failure",
            "AirPods BluetoothConnectionManager.aacpSocket? Connection Issues",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications about problems connecting to AirPods protocol"
            enableLights(true)
            lightColor = Color.RED
            enableVibration(true)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(disconnectedNotificationChannel)
        notificationManager.createNotificationChannel(connectedNotificationChannel)
        notificationManager.createNotificationChannel(socketFailureChannel)

        val notificationSettingsIntent =
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                putExtra(Settings.EXTRA_CHANNEL_ID, "background_service_status")
            }
        val pendingIntentNotifDisable = PendingIntent.getActivity(
            this,
            0,
            notificationSettingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "background_service_status")
            .setSmallIcon(R.drawable.airpods).setContentTitle("Background Service Running")
            .setContentText("Useless notification, disable it by clicking on it.")
            .setContentIntent(pendingIntentNotifDisable).setCategory(Notification.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).build()
            .applyMiuiFoldTimeout()

        try {
            startForeground(1, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Suppress("KotlinUnreachableCode")
    @OptIn(ExperimentalMaterial3Api::class)
    private fun showSocketConnectionFailureNotification(errorMessage: String) {
        return // something causes too many notifications. turning off for now
        if (BuildConfig.FLAVOR != "xposed") {
            Log.w(
                TAG,
                "Not showing BluetoothConnectionManager.aacpSocket? error notification to user, the service shouldn't be running if it isn't supported."
            )
            return
        }
        val notificationManager = getSystemService(NotificationManager::class.java)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, "socket_connection_failure")
            .setSmallIcon(R.drawable.airpods).setContentTitle("AirPods Connection Issue")
            .setContentText("Unable to connect to AirPods over L2CAP").setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Your AirPods are connected via Bluetooth, but LibrePods couldn't connect to AirPods using L2CAP. Error: $errorMessage"
                )
            ).setContentIntent(pendingIntent).setCategory(Notification.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true).build()

        notificationManager.notify(3, notification)
    }

    fun sendANCBroadcast() {
        sendBroadcast(Intent(AirPodsNotifications.ANC_DATA).apply {
            putExtra("data", ancNotification.status)
            setPackage(packageName)
        })
    }

    fun sendBatteryBroadcast() {
        broadcastBatteryInformation()
        sendBroadcast(Intent(AirPodsNotifications.BATTERY_DATA).apply {
            putParcelableArrayListExtra("data", ArrayList(batteryNotification.getBattery()))
            setPackage(packageName)
        })
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    fun sendBatteryNotification() {
        updateNotificationContent(
            true,
            getSharedPreferences("settings", MODE_PRIVATE).getString("name", device?.name),
            batteryNotification.getBattery()
        )
    }

    fun setBatteryMetadata() {
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") != PackageManager.PERMISSION_GRANTED) {
            device?.let { it ->
                SystemApisUtils.setMetadata(
                    it,
                    it.METADATA_UNTETHERED_CASE_BATTERY,
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.CASE }?.level.toString()
                        .toByteArray()
                )
                SystemApisUtils.setMetadata(
                    it,
                    it.METADATA_UNTETHERED_CASE_CHARGING,
                    (if (batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.CASE }?.status == BatteryStatus.CHARGING
                    ) "1".toByteArray() else "0".toByteArray())
                )
                SystemApisUtils.setMetadata(
                    it,
                    it.METADATA_UNTETHERED_LEFT_BATTERY,
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.LEFT }?.level.toString()
                        .toByteArray()
                )
                SystemApisUtils.setMetadata(
                    it,
                    it.METADATA_UNTETHERED_LEFT_CHARGING,
                    (if (batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.LEFT }?.status == BatteryStatus.CHARGING
                    ) "1".toByteArray() else "0".toByteArray())
                )
                SystemApisUtils.setMetadata(
                    it,
                    it.METADATA_UNTETHERED_RIGHT_BATTERY,
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.RIGHT }?.level.toString()
                        .toByteArray()
                )
                SystemApisUtils.setMetadata(
                    it,
                    it.METADATA_UNTETHERED_RIGHT_CHARGING,
                    (if (batteryNotification.getBattery()
                            .find { it.component == BatteryComponent.RIGHT }?.status == BatteryStatus.CHARGING
                    ) "1".toByteArray() else "0".toByteArray())
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun updateBatteryWidget() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, BatteryWidget::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)

        val remoteViews = RemoteViews(packageName, R.layout.battery_widget).also { it ->
            val openActivityIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            it.setOnClickPendingIntent(R.id.battery_widget, openActivityIntent)

            val leftBattery =
                batteryNotification.getBattery().find { it.component == BatteryComponent.LEFT }
            val rightBattery =
                batteryNotification.getBattery().find { it.component == BatteryComponent.RIGHT }
            val caseBattery =
                batteryNotification.getBattery().find { it.component == BatteryComponent.CASE }

            it.setTextViewText(R.id.left_battery_widget, leftBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.left_battery_progress, 100, leftBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.left_charging_icon,
                if (leftBattery?.status == BatteryStatus.CHARGING || leftBattery?.status == BatteryStatus.OPTIMIZED_CHARGING) View.VISIBLE else View.GONE
            )

            it.setTextViewText(R.id.right_battery_widget, rightBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.right_battery_progress, 100, rightBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.right_charging_icon,
                if (rightBattery?.status == BatteryStatus.CHARGING || rightBattery?.status == BatteryStatus.OPTIMIZED_CHARGING ) View.VISIBLE else View.GONE
            )

            it.setTextViewText(R.id.case_battery_widget, caseBattery?.let {
                "${it.level}%"
            } ?: "")
            it.setProgressBar(
                R.id.case_battery_progress, 100, caseBattery?.level ?: 0, false
            )
            it.setViewVisibility(
                R.id.case_charging_icon,
                if (caseBattery?.status == BatteryStatus.CHARGING || caseBattery?.status == BatteryStatus.OPTIMIZED_CHARGING ) View.VISIBLE else View.GONE
            )

            it.setViewVisibility(
                R.id.phone_battery_widget_container,
                if (widgetMobileBatteryEnabled) View.VISIBLE else View.GONE
            )
            if (widgetMobileBatteryEnabled) {
                val batteryManager = getSystemService(BatteryManager::class.java)
                val batteryLevel =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                val charging =
                    batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
                it.setTextViewText(
                    R.id.phone_battery_widget, "$batteryLevel%"
                )
                it.setViewVisibility(
                    R.id.phone_charging_icon, if (charging) View.VISIBLE else View.GONE
                )
                it.setProgressBar(
                    R.id.phone_battery_progress, 100, batteryLevel, false
                )
            }
        }
        appWidgetManager.updateAppWidget(widgetIds, remoteViews)
    }

    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalMaterial3Api::class)
    fun updateBattery() {
        setBatteryMetadata()
        updateBatteryWidget()
        sendBatteryBroadcast()
        sendBatteryNotification()
    }

    fun updateNoiseControlWidget() {
        val appWidgetManager = AppWidgetManager.getInstance(this)
        val componentName = ComponentName(this, NoiseControlWidget::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)
        val remoteViews = RemoteViews(packageName, R.layout.noise_control_widget).also { it ->
            val ancStatus = ancNotification.status
            val allowOffModeValue =
                aacpManager.controlCommandStatusList.find { it.identifier == AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION }
            val allowOffMode =
                allowOffModeValue?.value?.takeIf { it.isNotEmpty() }?.get(0) == 0x01.toByte() || sharedPreferences.getBoolean("off_listening_mode", true)
            it.setInt(
                R.id.widget_off_button,
                "setBackgroundResource",
                if (ancStatus == 1) R.drawable.widget_button_checked_shape_start else R.drawable.widget_button_shape_start
            )
            it.setInt(
                R.id.widget_transparency_button,
                "setBackgroundResource",
                if (ancStatus == 3) (if (allowOffMode) R.drawable.widget_button_checked_shape_middle else R.drawable.widget_button_checked_shape_start) else (if (allowOffMode) R.drawable.widget_button_shape_middle else R.drawable.widget_button_shape_start)
            )
            it.setInt(
                R.id.widget_adaptive_button,
                "setBackgroundResource",
                if (ancStatus == 4) R.drawable.widget_button_checked_shape_middle else R.drawable.widget_button_shape_middle
            )
            it.setInt(
                R.id.widget_anc_button,
                "setBackgroundResource",
                if (ancStatus == 2) R.drawable.widget_button_checked_shape_end else R.drawable.widget_button_shape_end
            )
            it.setViewVisibility(
                R.id.widget_off_button, if (allowOffMode) View.VISIBLE else View.GONE
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                it.setViewLayoutMargin(
                    R.id.widget_transparency_button,
                    RemoteViews.MARGIN_START,
                    if (allowOffMode) 2f else 12f,
                    TypedValue.COMPLEX_UNIT_DIP
                )
            } else {
                it.setViewPadding(
                    R.id.widget_transparency_button,
                    if (allowOffMode) 2.dpToPx() else 12.dpToPx(),
                    12.dpToPx(),
                    2.dpToPx(),
                    12.dpToPx()
                )
            }
        }

        appWidgetManager.updateAppWidget(widgetIds, remoteViews)
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun updateNotificationContent(
        connected: Boolean, airpodsName: String? = null, batteryList: List<Battery>? = null
    ) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (BluetoothConnectionManager.aacpSocket == null) {
            return
        }
        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
            val updatedNotificationBuilder =
                NotificationCompat.Builder(this, "airpods_connection_status")
                    .setSmallIcon(R.drawable.airpods)
                    .setContentTitle(airpodsName ?: config.deviceName).setContentText(
                        """${
                        batteryList?.find { it.component == BatteryComponent.LEFT }?.let {
                            if (it.status != BatteryStatus.DISCONNECTED) {
                                "L: ${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                            } else {
                                ""
                            }
                        } ?: ""
                    } ${
                        batteryList?.find { it.component == BatteryComponent.RIGHT }?.let {
                            if (it.status != BatteryStatus.DISCONNECTED) {
                                "R: ${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                            } else {
                                ""
                            }
                        } ?: ""
                    } ${
                        batteryList?.find { it.component == BatteryComponent.CASE }?.let {
                            if (it.status != BatteryStatus.DISCONNECTED) {
                                "Case: ${if (it.status == BatteryStatus.CHARGING) "⚡" else ""} ${it.level}%"
                            } else {
                                ""
                            }
                        } ?: ""
                    }""").setContentIntent(pendingIntent).setCategory(Notification.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true)

            if (disconnectedBecauseReversed) {
                updatedNotificationBuilder.addAction(
                    R.drawable.ic_bluetooth, "Reconnect", PendingIntent.getService(
                        this, 0, Intent(this, AirPodsService::class.java).apply {
                            action = "me.kavishdevar.librepods.RECONNECT_AFTER_REVERSE"
                        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            val updatedNotification = updatedNotificationBuilder.build()
                .applyMiuiFoldTimeout()

            notificationManager.notify(2, updatedNotification)
            notificationManager.cancel(1)
        } else if (!connected) {
            notificationManager.cancel(2)
        } else if (!config.bleOnlyMode && BluetoothConnectionManager.aacpSocket?.isConnected != true) {
            showSocketConnectionFailureNotification("BluetoothConnectionManager.aacpSocket? created, but not connected. Check logs")
        }
    }

    fun handleIncomingCall() {
        if (isInCall) return
        if (config.headGestures) {
            initGestureDetector()
            startHeadTracking()
            gestureDetector?.startDetection { accepted ->
                if (accepted) {
                    answerCall()
                    handleIncomingCallOnceConnected = false
                } else {
                    rejectCall()
                    handleIncomingCallOnceConnected = false
                }
            }

        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun testHeadGestures(): Boolean {
        return suspendCancellableCoroutine { continuation ->
            gestureDetector?.startDetection(doNotStop = true) { accepted ->
                if (continuation.isActive) {
                    continuation.resume(accepted) { _, _, _ ->
                        gestureDetector?.stopDetection()
                    }
                }
            }
        }
    }

    private fun answerCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.acceptRingingCall() // TODO: Switch to InCallService (needs CDM association)
                }
            } else {
                val telephonyService = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                val telephonyClass = Class.forName(telephonyService.javaClass.name)
                val method = telephonyClass.getDeclaredMethod("getITelephony")
                method.isAccessible = true
                val telephonyInterface = method.invoke(telephonyService)
                val answerCallMethod =
                    telephonyInterface.javaClass.getDeclaredMethod("answerRingingCall")
                answerCallMethod.invoke(telephonyInterface)
            }

            sendToast("Call answered via head gesture")
        } catch (e: Exception) {
            e.printStackTrace()
            sendToast("Failed to answer call: ${e.message}")
        } finally {
            islandWindow?.close()
        }
    }

    private fun rejectCall() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val telecomManager = getSystemService(TELECOM_SERVICE) as TelecomManager
                if (checkSelfPermission(Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.endCall() // TODO: Switch to InCallService (needs CDM association)
                }
            } else {
                val telephonyService = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
                val telephonyClass = Class.forName(telephonyService.javaClass.name)
                val method = telephonyClass.getDeclaredMethod("getITelephony")
                method.isAccessible = true
                val telephonyInterface = method.invoke(telephonyService)
                val endCallMethod = telephonyInterface.javaClass.getDeclaredMethod("endCall")
                endCallMethod.invoke(telephonyInterface)
            }

            sendToast("Call rejected via head gesture")
        } catch (e: Exception) {
            e.printStackTrace()
            sendToast("Failed to reject call: ${e.message}")
        } finally {
            islandWindow?.close()
        }
    }

    fun sendToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    fun processHeadTrackingData(data: ByteArray) {
        val horizontal = ByteBuffer.wrap(data, 51, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        val vertical = ByteBuffer.wrap(data, 53, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        try {
            gestureDetector?.processHeadOrientation(horizontal, vertical)
        } catch (e: Exception) {
            Log.w(TAG, "gesture detector on ${data.toHexString()}: ${e.message}")
        }
    }

    private lateinit var connectionReceiver: BroadcastReceiver

    private fun resToUri(resId: Int): Uri? {
        return try {
            Uri.Builder().scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority("me.kavishdevar.librepods")
                .appendPath(applicationContext.resources.getResourceTypeName(resId))
                .appendPath(applicationContext.resources.getResourceEntryName(resId)).build()
        } catch (_: Resources.NotFoundException) {
            null
        }
    }

    @Suppress("PrivatePropertyName")
    private val VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV = "+IPHONEACCEV"

    @Suppress("PrivatePropertyName")
    private val VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL = 1

    @Suppress("PrivatePropertyName")
    private val APPLE = 0x004C

    @Suppress("PrivatePropertyName")
    private val ACTION_BATTERY_LEVEL_CHANGED =
        "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED"

    @Suppress("PrivatePropertyName")
    private val EXTRA_BATTERY_LEVEL = "android.bluetooth.device.extra.BATTERY_LEVEL"

    @Suppress("PrivatePropertyName")
    private val PACKAGE_ASI = "com.google.android.settings.intelligence"

    @Suppress("PrivatePropertyName")
    private val ACTION_ASI_UPDATE_BLUETOOTH_DATA = "batterywidget.impl.action.update_bluetooth_data"

    @SuppressLint("MissingPermission")
    fun broadcastBatteryInformation() {
        if (device == null || checkSelfPermission("android.permission.INTERACT_ACROSS_USERS") != PackageManager.PERMISSION_GRANTED) return

        val batteryList = batteryNotification.getBattery()
        val leftBattery = batteryList.find { it.component == BatteryComponent.LEFT }
        val rightBattery = batteryList.find { it.component == BatteryComponent.RIGHT }

        // Calculate unified battery level (minimum of left and right)
        val batteryUnified = minOf(
            leftBattery?.level ?: 100, rightBattery?.level ?: 100
        )

        // Check charging status
        val isLeftCharging = leftBattery?.status == BatteryStatus.CHARGING
        val isRightCharging = rightBattery?.status == BatteryStatus.CHARGING
        isLeftCharging && isRightCharging

        // Create arguments for vendor-specific event
        val arguments = arrayOf<Any>(
            1, // Number of key/value pairs
            VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV_BATTERY_LEVEL, // IndicatorType: Battery Level
            batteryUnified // Battery Level
        )

        // Broadcast vendor-specific event
        val intent = Intent(BluetoothHeadset.ACTION_VENDOR_SPECIFIC_HEADSET_EVENT).apply {
            putExtra(
                BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD,
                VENDOR_SPECIFIC_HEADSET_EVENT_IPHONEACCEV
            )
            putExtra(
                BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_CMD_TYPE,
                BluetoothHeadset.AT_CMD_TYPE_SET
            )
            putExtra(BluetoothHeadset.EXTRA_VENDOR_SPECIFIC_HEADSET_EVENT_ARGS, arguments)
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            putExtra(BluetoothDevice.EXTRA_NAME, device?.name)
            addCategory("${BluetoothHeadset.VENDOR_SPECIFIC_HEADSET_EVENT_COMPANY_ID_CATEGORY}.$APPLE")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sendBroadcastAsUser(
                    intent,
                    UserHandle.getUserHandleForUid(-1),
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            } else {
                sendBroadcastAsUser(intent, UserHandle.getUserHandleForUid(-1))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send vendor-specific event: ${e.message}")
        }

        // Broadcast battery level changes
        val batteryIntent = Intent(ACTION_BATTERY_LEVEL_CHANGED).apply {
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            putExtra(EXTRA_BATTERY_LEVEL, batteryUnified)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                sendBroadcast(batteryIntent, Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                sendBroadcastAsUser(batteryIntent, UserHandle.getUserHandleForUid(-1))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send battery level broadcast: ${e.message}")
        }

        // Update Android Settings Intelligence's battery widget
        val statusIntent = Intent(ACTION_ASI_UPDATE_BLUETOOTH_DATA).apply {
            setPackage(PACKAGE_ASI)
            putExtra(ACTION_BATTERY_LEVEL_CHANGED, intent)
        }

        try {
            sendBroadcastAsUser(statusIntent, UserHandle.getUserHandleForUid(-1))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send ASI battery level broadcast: ${e.message}")
        }

        Log.d(TAG, "Broadcast battery level $batteryUnified% to system")
    }

    private fun setMetadatas(d: BluetoothDevice) {
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "no permission BLUETOOTH_PRIVILEGED, returning")
            return
        }
        Log.d(TAG, "has permission BLUETOOTH_PRIVILEGED, proceeding")
        d.let { device ->
            val instance = airpodsInstance
            if (instance != null) {
                val metadataSet = SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_MAIN_ICON,
                    resToUri(instance.model.budCaseRes).toString().toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device, device.METADATA_MODEL_NAME, instance.model.name.toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_DEVICE_TYPE,
                    device.DEVICE_TYPE_UNTETHERED_HEADSET.toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_CASE_ICON,
                    resToUri(instance.model.caseRes).toString().toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_RIGHT_ICON,
                    resToUri(instance.model.rightBudsRes).toString().toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_LEFT_ICON,
                    resToUri(instance.model.leftBudsRes).toString().toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_MANUFACTURER_NAME,
                    instance.model.manufacturer.toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device, device.METADATA_COMPANION_APP, "me.kavishdevar.librepods".toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_CASE_LOW_BATTERY_THRESHOLD,
                    "20".toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_LEFT_LOW_BATTERY_THRESHOLD,
                    "20".toByteArray()
                ) && SystemApisUtils.setMetadata(
                    device,
                    device.METADATA_UNTETHERED_RIGHT_LOW_BATTERY_THRESHOLD,
                    "20".toByteArray()
                )
                Log.d(TAG, "Metadata set: $metadataSet")
            } else {
                Log.w(
                    TAG,
                    "AirPods demoInstance is not of type AirPodsInstance, skipping metadata setting"
                )
            }
        }
    }

    @Suppress("ClassName")
    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context?, intent: Intent) {
            val bluetoothDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    "android.bluetooth.device.extra.DEVICE", BluetoothDevice::class.java
                )
            } else {
                intent.getParcelableExtra("android.bluetooth.device.extra.DEVICE") as BluetoothDevice?
            }
            val action = intent.action
            val context = context?.applicationContext
            if (BluetoothAdapter.ACTION_STATE_CHANGED == action) {
                val state = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR
                )
                if (state == BluetoothAdapter.STATE_ON &&
                    ensureLocalHostIdentity("bluetooth_state_on")
                ) {
                    evaluateTakeoverRoute("local_identity_resolved")
                }
                return
            }
            if (ACTION_A2DP_ACTIVE_DEVICE_CHANGED == action) {
                val targetAddress = this@AirPodsService.device?.address ?: macAddress
                confirmedActiveA2dpAddress = bluetoothDevice?.address?.takeIf {
                    it.equals(targetAddress, ignoreCase = true)
                }
                if (confirmedActiveA2dpAddress != null) {
                    invalidateA2dpActivationTimeout()
                }
                evaluateTakeoverRoute("a2dp_active_device_changed")
                return
            }
            val name = context?.getSharedPreferences("settings", MODE_PRIVATE)
                ?.getString("name", bluetoothDevice?.name)
            if (bluetoothDevice != null && !action.isNullOrEmpty()) {
                Log.d(TAG, "Received bluetooth connection broadcast: action=$action")
                val uuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")

                if (BluetoothDevice.ACTION_ACL_CONNECTED == action) {
                    if (bluetoothDevice.uuids?.contains(uuid) == true) {
                        val intent = Intent(AirPodsNotifications.AIRPODS_CONNECTION_DETECTED)
                        intent.putExtra("name", name)
                        intent.putExtra("device", bluetoothDevice)
                        context?.sendBroadcast(intent)
                    } else {
                        bluetoothDevice.fetchUuidsWithSdp()
                    }
                } else if (BluetoothDevice.ACTION_ACL_DISCONNECTED == action) {
                    val savedMac = context?.getSharedPreferences("settings", MODE_PRIVATE)
                        ?.getString("mac_address", "") ?: ""
                    if (savedMac.isNotEmpty() &&
                        bluetoothDevice.address.equals(savedMac, ignoreCase = true)
                    ) {
                        requestBoundedAudioProfileRepair(
                            reason = "acl_disconnected",
                            target = bluetoothDevice,
                        )
                    }
                } else if ("android.bluetooth.device.action.UUID" == action) {
                    val savedMac = context?.getSharedPreferences("settings", MODE_PRIVATE)
                        ?.getString("mac_address", "") ?: ""
                    val matchedByMac = savedMac.isNotEmpty() && bluetoothDevice.address == savedMac
                    val matchedByUuid = bluetoothDevice.uuids?.contains(uuid) == true
                    if (matchedByUuid || matchedByMac) {
                        val intent = Intent(AirPodsNotifications.AIRPODS_CONNECTION_DETECTED)
                        intent.putExtra("name", name)
                        intent.putExtra("device", bluetoothDevice)
                        context?.sendBroadcast(intent)
                    }
                } else if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED == action) {
                    val state = intent.getIntExtra(
                        BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED
                    )
                    val previousState = intent.getIntExtra(
                        BluetoothProfile.EXTRA_PREVIOUS_STATE,
                        BluetoothProfile.STATE_DISCONNECTED
                    )
                    val airPodsMac = this@AirPodsService.device?.address ?: macAddress
                    val isCurrentAirPods = airPodsMac.isNotEmpty() &&
                        bluetoothDevice.address.equals(airPodsMac, ignoreCase = true)
                    RoutingTrace.record {
                        RoutingEventDetail.A2dpStateChanged(
                            signal = "connection",
                            previousState = previousState.toString(),
                            newState = state.toString(),
                            targetDeviceMatched = isCurrentAirPods,
                            observationSource = "broadcast"
                        )
                    }
                    if (isCurrentAirPods && state == BluetoothProfile.STATE_CONNECTED) {
                        val activateForMedia = synchronized(earDetectionReceiverLock) {
                            applyA2dpConnectionBroadcastLocked(connected = true)
                            val currentAttemptId = MediaController.currentTakeoverCorrelation()
                                .takeoverAttemptId
                            val activationRequired = currentAttemptId != null &&
                                (pendingTargetRouteActivationAttemptId == currentAttemptId ||
                                    requiredTargetRouteAttemptId == currentAttemptId)
                            if (activationRequired) {
                                invalidateA2dpActivationTimeout()
                                pendingTargetRouteActivationAttemptId = currentAttemptId
                            }
                            activationRequired
                        }
                        if (activateForMedia) {
                            connectAudio(
                                this@AirPodsService,
                                bluetoothDevice,
                                activateForMedia = true,
                            )
                        } else {
                            evaluateTakeoverRoute("a2dp_connected_broadcast")
                        }
                        synchronized(earDetectionReceiverLock) {
                            requestEarDetectionResumeIfA2dpReadyLocked()
                        }
                        refreshCurrentA2dpPlaybackState("AirPods A2DP connected")
                    } else if (isCurrentAirPods && state == BluetoothProfile.STATE_DISCONNECTED) {
                        val wasConnected = synchronized(earDetectionReceiverLock) {
                            val connected = isAirPodsA2dpConnected ||
                                previousState == BluetoothProfile.STATE_CONNECTED
                            applyA2dpConnectionBroadcastLocked(connected = false)
                            connected
                        }
                        invalidateA2dpActivationTimeout()
                        confirmedActiveA2dpAddress = null
                        evaluateTakeoverRoute("a2dp_disconnected_broadcast")
                        isAirPodsA2dpPlaying = false
                        cancelPendingAirPodsAbsoluteVolumeResync("AirPods A2DP disconnected")
                        updateSpatialAudioTracking("AirPods A2DP disconnected")
                        if (wasConnected) {
                            requestBoundedAudioProfileRepair(
                                reason = "a2dp_disconnected",
                                target = bluetoothDevice,
                            )
                        }
                    }
                } else if (BluetoothA2dp.ACTION_PLAYING_STATE_CHANGED == action) {
                    val state = intent.getIntExtra(
                        BluetoothProfile.EXTRA_STATE, BluetoothA2dp.STATE_NOT_PLAYING
                    )
                    val airPodsMac = this@AirPodsService.device?.address ?: macAddress
                    val isCurrentAirPods = airPodsMac.isNotEmpty() &&
                        bluetoothDevice.address.equals(airPodsMac, ignoreCase = true)
                    val wasPlaying = isAirPodsA2dpPlaying
                    RoutingTrace.record {
                        RoutingEventDetail.A2dpStateChanged(
                            signal = "playing",
                            previousState = wasPlaying.toString(),
                            newState = (state == BluetoothA2dp.STATE_PLAYING).toString(),
                            targetDeviceMatched = isCurrentAirPods,
                            observationSource = "broadcast"
                        )
                    }
                    if (isCurrentAirPods && state == BluetoothA2dp.STATE_PLAYING) {
                        isAirPodsA2dpPlaying = true
                        confirmedActiveA2dpAddress = bluetoothDevice.address
                        lastAirPodsA2dpStartedAt = SystemClock.elapsedRealtime()
                        Log.d(TAG, "Local AirPods A2DP started playing")
                        evaluateTakeoverRoute("a2dp_playing_broadcast")
                        if (SpatialAudioMode.fromPreferences(sharedPreferences) ==
                            SpatialAudioMode.HEAD_TRACKED
                        ) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                updateSpatialAudioTracking("A2DP playback started")
                            }, 300)
                        }
                    } else if (isCurrentAirPods && state == BluetoothA2dp.STATE_NOT_PLAYING) {
                        isAirPodsA2dpPlaying = false
                        lastAirPodsA2dpStartedAt = 0L
                        prewarmAirPodsAbsoluteVolumeResync("AirPods A2DP stopped")
                        cancelPendingAirPodsAbsoluteVolumeResync("AirPods A2DP stopped")
                        updateSpatialAudioTracking("A2DP playback stopped")
                    }
                }
            }
        }
    }

    val externalBroadcastFilter = IntentFilter().apply {
        addAction("me.kavishdevar.librepods.SET_ANC_MODE")
        addAction("me.kavishdevar.librepods.CONVO_DETECT")
    }
    var externalBroadcastReceiver: BroadcastReceiver? = null

    @SuppressLint("InlinedApi", "MissingPermission", "UnspecifiedRegisterReceiverFlag")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started with intent action: ${intent?.action}")

        if (intent?.action == "me.kavishdevar.librepods.RECONNECT_AFTER_REVERSE") {
            Log.d(TAG, "reconnect after reversed received, taking over")
            disconnectedBecauseReversed = false
            otherDeviceTookOver = false
            takeOver("music", manualTakeOverAfterReversed = true)
        }

        return START_STICKY
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission", "HardwareIds")
    fun onEligibleLocalPlaybackStarting(
        usages: Set<Int>,
        correlation: RoutingCorrelation
    ) {
        Log.i(TAG, "Eligible local playback started for usages=$usages")
        // A legacy takeover may need to establish the AACP socket with a bounded blocking
        // connect. Keep that work off the AudioPlaybackCallback/main thread.
        audioFeatureScope.launch(Dispatchers.IO) {
            takeOver(
                "music",
                correlation = correlation,
            )
        }
    }

    fun onEligibleLocalPlaybackStopped(correlation: RoutingCorrelation) {
        cancelTakeoverSourceRetries(correlation.takeoverAttemptId)
        invalidateActiveTakeoverPacketPermit(correlation.takeoverAttemptId)
        if (pendingTakeoverRetryCorrelation?.takeoverAttemptId == correlation.takeoverAttemptId) {
            pendingTakeoverRetryCorrelation = null
        }
        if (LocalHostIdentity.normalize(localMac) == null ||
            BluetoothConnectionManager.aacpSocket?.isConnected != true
        ) {
            RoutingTrace.record(correlation) {
                RoutingEventDetail.AacpSend(
                    operation = AacpOperation.MEDIA_INFORMATION,
                    outcome = AacpSendOutcome.SKIPPED,
                    socketConnected = BluetoothConnectionManager.aacpSocket?.isConnected == true,
                    requestedValue = 0,
                    reason = if (LocalHostIdentity.normalize(localMac) == null) {
                        "eligible_playback_stopped_without_local_address"
                    } else {
                        "eligible_playback_stopped_without_socket"
                    }
                )
            }
            return
        }
        val sent = aacpManager.sendMediaInformataion(localMac, streamingState = false)
        RoutingTrace.record(correlation) {
            RoutingEventDetail.AacpSend(
                operation = AacpOperation.MEDIA_INFORMATION,
                outcome = if (sent) AacpSendOutcome.SENT else AacpSendOutcome.WRITE_FAILED,
                socketConnected = BluetoothConnectionManager.aacpSocket?.isConnected == true,
                requestedValue = 0,
                reason = "eligible_playback_stopped"
            )
        }
    }

    private fun retryPendingTakeover(trigger: String) {
        val correlation = synchronized(this) {
            val pending = pendingTakeoverRetryCorrelation ?: return
            if (!MediaController.isCurrentTakeoverAttempt(pending)) {
                pendingTakeoverRetryCorrelation = null
                return
            }
            if (BluetoothConnectionManager.aacpSocket?.isConnected != true ||
                aacpManager.resolveSmartRoutingTarget(localMac) == null
            ) {
                return
            }
            pendingTakeoverRetryCorrelation = null
            pending
        }
        Log.d(TAG, "Retrying pending Smart Routing takeover after $trigger")
        audioFeatureScope.launch(Dispatchers.IO) {
            if (MediaController.isCurrentTakeoverAttempt(correlation)) {
                takeOver("music", correlation = correlation)
            }
        }
    }

    private fun isCurrentAutomaticTakeoverSideEffect(
        correlation: RoutingCorrelation,
        stage: String,
    ): Boolean {
        val expectedAttemptId = correlation.takeoverAttemptId
        val currentAttemptId = MediaController.currentTakeoverCorrelation().takeoverAttemptId
        val allowed = TakeoverAttemptSideEffectGate.canRun(
            expectedAttemptId = expectedAttemptId,
            currentAttemptId = currentAttemptId,
            attemptIsCurrent = MediaController.isCurrentTakeoverAttempt(correlation),
        )
        if (allowed) return true

        expectedAttemptId?.let(::clearTakeoverRouteRequirements)
        traceTakeoverDecision(
            correlation,
            allowed = false,
            reason = "stale_playback_attempt:$stage",
            gates = mapOf("current_playback_attempt" to false),
        )
        return false
    }

    private fun cancelTakeoverSourceRetries(expectedAttemptId: String? = null) {
        synchronized(takeoverSourceRetryLock) {
            if (expectedAttemptId != null &&
                takeoverSourceRetryState.attemptId != expectedAttemptId
            ) {
                return
            }
            takeoverSourceRetryState = TakeoverSourceRetryGate.cancel(takeoverSourceRetryState)
        }
    }

    private fun sameTakeoverTargetSet(first: List<String>, second: List<String>): Boolean =
        first.map(String::uppercase).toSet() == second.map(String::uppercase).toSet()

    private fun settleTakeoverAsLocalSource(
        attemptId: String?,
        trigger: String,
    ) {
        if (attemptId == null) return
        pendingOwnershipRequiredAttemptId = null
        pendingFreshLocalSourceAttemptId = null
        freshLocalSourceConfirmedAttemptId = null
        completedTakeoverRequestAttemptId = attemptId
        invalidateActiveTakeoverPacketPermit(attemptId)
        cancelTakeoverSourceRetries(attemptId)
        evaluateTakeoverRoute(trigger)
    }

    /**
     * This guard is deliberately evaluated outside AACP's packet-write lock. It may consult
     * MediaController and service state, while the sequence itself only re-checks its socket
     * lease and the lock-free AACP source snapshot.
     */
    private fun canEnterTakeoverPacketSequence(
        permit: TakeoverPacketPermit,
        sourcePolicy: TakeoverPacketSourcePolicy?,
        stage: String,
    ): Boolean {
        if (activeTakeoverPacketPermit.get() !== permit ||
            !isCurrentAutomaticTakeoverSideEffect(permit.correlation, stage)
        ) {
            return false
        }
        if (!BluetoothConnectionManager.isCurrent(permit.lease)) return false
        if (!MediaController.isEligibleLocalPlaybackActive() &&
            !MediaController.isAwaitingLocalRoute()
        ) {
            return false
        }
        val currentTargets = aacpManager.resolveSmartRoutingTargets(permit.selfMacAddress)
        if (!sameTakeoverTargetSet(permit.targetMacAddresses, currentTargets)) return false
        val sourceOwner = classifyAudioSource(aacpManager.audioSource)
        return when (sourcePolicy) {
            null -> true
            TakeoverPacketSourcePolicy.INITIAL_NON_LOCAL ->
                sourceOwner != TakeoverAudioSource.LOCAL
            TakeoverPacketSourcePolicy.RETRY_KNOWN_NON_LOCAL ->
                sourceOwner == TakeoverAudioSource.NONE ||
                    sourceOwner == TakeoverAudioSource.REMOTE
        }
    }

    private fun scheduleTakeoverSourceRetries(
        correlation: RoutingCorrelation,
        permit: TakeoverPacketPermit,
    ) {
        val attemptId = correlation.takeoverAttemptId ?: return
        if (!canEnterTakeoverPacketSequence(
                permit,
                sourcePolicy = TakeoverPacketSourcePolicy.INITIAL_NON_LOCAL,
                stage = "source_retry_schedule",
            )
        ) {
            return
        }
        val scheduledState = synchronized(takeoverSourceRetryLock) {
            TakeoverSourceRetryGate.start(takeoverSourceRetryState, attemptId).also {
                takeoverSourceRetryState = it
            }
        }
        audioFeatureScope.launch(Dispatchers.IO) {
            var previousRetryAtMs = 0L
            for (retryAtMs in TakeoverSourceRetryGate.retryAtMs) {
                delay(retryAtMs - previousRetryAtMs)
                previousRetryAtMs = retryAtMs
                val currentAttemptId = MediaController.currentTakeoverCorrelation()
                    .takeoverAttemptId
                val requestCompleted = completedTakeoverRequestAttemptId == attemptId
                val socketConnected = BluetoothConnectionManager.aacpSocket?.isConnected == true
                val socketLeaseCurrent = BluetoothConnectionManager.isCurrent(permit.lease)
                val targetSetCurrent = sameTakeoverTargetSet(
                    permit.targetMacAddresses,
                    aacpManager.resolveSmartRoutingTargets(permit.selfMacAddress),
                )
                val playbackActive = MediaController.isEligibleLocalPlaybackActive()
                val audioSource = classifyAudioSource(aacpManager.audioSource)
                if (!socketLeaseCurrent || !targetSetCurrent) {
                    synchronized(takeoverSourceRetryLock) {
                        takeoverSourceRetryState = TakeoverSourceRetryGate.cancel(
                            takeoverSourceRetryState
                        )
                    }
                    if (currentAttemptId == attemptId) {
                        pendingTakeoverRetryCorrelation = correlation
                    }
                    if (activeTakeoverPacketPermit.get() === permit) {
                        invalidateActiveTakeoverPacketPermit(attemptId)
                    }
                    return@launch
                }
                val decision = synchronized(takeoverSourceRetryLock) {
                    TakeoverSourceRetryGate.claim(
                        state = takeoverSourceRetryState,
                        expectedAttemptId = attemptId,
                        expectedGeneration = scheduledState.generation,
                        currentAttemptId = currentAttemptId,
                        requestCompleted = requestCompleted,
                        socketConnected = socketConnected,
                        socketLeaseCurrent = socketLeaseCurrent,
                        targetSetCurrent = targetSetCurrent,
                        playbackActive = playbackActive,
                        audioSource = audioSource,
                    ).also { takeoverSourceRetryState = it.state }
                }
                if (!decision.shouldRetry) {
                    if (audioSource == TakeoverAudioSource.LOCAL ||
                        audioSource == TakeoverAudioSource.UNKNOWN ||
                        !playbackActive
                    ) {
                        invalidateActiveTakeoverPacketPermit(attemptId)
                        cancelTakeoverSourceRetries(attemptId)
                    }
                    return@launch
                }
                resendTakeoverPacketsForSourceRetry(
                    correlation = correlation,
                    permit = permit,
                    retryOrdinal = decision.retryOrdinal ?: return@launch,
                )
            }
        }
    }

    private fun resendTakeoverPacketsForSourceRetry(
        correlation: RoutingCorrelation,
        permit: TakeoverPacketPermit,
        retryOrdinal: Int,
    ) {
        val reason = "local_source_retry_$retryOrdinal"
        if (!canEnterTakeoverPacketSequence(
                permit,
                sourcePolicy = TakeoverPacketSourcePolicy.RETRY_KNOWN_NON_LOCAL,
                stage = "$reason:preflight",
            )
        ) {
            return
        }
        val sourceOwner = classifyAudioSource(aacpManager.audioSource)
        val mode = if (sourceOwner == TakeoverAudioSource.NONE && aacpManager.owns) {
            TakeoverPacketSequenceMode.MEDIA_AND_HIJACK
        } else {
            TakeoverPacketSequenceMode.FULL
        }
        val result = aacpManager.sendTakeoverPacketSequence(
            selfMacAddress = permit.selfMacAddress,
            targetMacAddresses = permit.targetMacAddresses,
            lease = permit.lease,
            mode = mode,
            sourcePolicy = TakeoverPacketSourcePolicy.RETRY_KNOWN_NON_LOCAL,
            correlation = correlation,
            reason = reason,
            packetPermit = {
                activeTakeoverPacketPermit.get() === permit && permit.valid
            },
        )
        traceTakeoverSequenceResult(correlation, result, reason)
        if (!result.completed) {
            if (result.failure == TakeoverPacketSequenceFailure.SOCKET_STALE) {
                pendingTakeoverRetryCorrelation = correlation
                invalidateActiveTakeoverPacketPermit(correlation.takeoverAttemptId)
                cancelTakeoverSourceRetries(correlation.takeoverAttemptId)
            } else if (result.failure == TakeoverPacketSequenceFailure.WRITE_FAILED) {
                pendingTakeoverRetryCorrelation = correlation
                invalidateActiveTakeoverPacketPermit(correlation.takeoverAttemptId)
                cancelTakeoverSourceRetries(correlation.takeoverAttemptId)
                requestAutomaticReconnect(
                    "takeover_retry_packet_write_failed",
                    ensureAudioProfiles = false,
                    boundedControlRepair = true,
                )
            } else if (result.failure == TakeoverPacketSequenceFailure.SOURCE_REJECTED) {
                if (classifyAudioSource(aacpManager.audioSource) == TakeoverAudioSource.LOCAL) {
                    settleTakeoverAsLocalSource(
                        correlation.takeoverAttemptId,
                        "takeover_retry_source_changed",
                    )
                } else {
                    invalidateActiveTakeoverPacketPermit(correlation.takeoverAttemptId)
                    cancelTakeoverSourceRetries(correlation.takeoverAttemptId)
                }
            } else if (result.failure == TakeoverPacketSequenceFailure.PERMIT_REVOKED) {
                invalidateActiveTakeoverPacketPermit(correlation.takeoverAttemptId)
                cancelTakeoverSourceRetries(correlation.takeoverAttemptId)
            }
            return
        }
        if (canEnterTakeoverPacketSequence(
                permit,
                sourcePolicy = TakeoverPacketSourcePolicy.RETRY_KNOWN_NON_LOCAL,
                stage = "$reason:complete",
            )
        ) {
            evaluateTakeoverRoute(reason)
        }
    }

    fun onTargetPlaybackRouteObserved(correlation: RoutingCorrelation) {
        if (!MediaController.isObservingTakeoverRoute(correlation)) return
        confirmedActiveA2dpAddress = device?.address ?: macAddress
        if (pendingTargetRouteActivationAttemptId == correlation.takeoverAttemptId) {
            pendingTargetRouteActivationAttemptId = null
        }
        evaluateTakeoverRoute("playback_target_output_observed")
    }

    @RequiresApi(Build.VERSION_CODES.R)
    @SuppressLint("MissingPermission", "HardwareIds")
    fun takeOver(
        takingOverFor: String,
        manualTakeOverAfterReversed: Boolean = false,
        correlation: RoutingCorrelation = RoutingCorrelation(),
    ) {
        val manualTakeover = takingOverFor == "reverse" || manualTakeOverAfterReversed
        if (manualTakeover) allowAutomaticReconnect()
        val automaticTakeoverEnabled = sharedPreferences.getBoolean(
            SmartRoutingAudioPolicyPreferences.MASTER_ENABLED_KEY, false
        )
        val vendorIdentityHookReportedEnabled = isVendorIdentityHookEnabled()

        if (!manualTakeover && takingOverFor == "music" &&
            !MediaController.isCurrentTakeoverAttempt(correlation)
        ) {
            traceTakeoverDecision(
                correlation,
                allowed = false,
                reason = "stale_playback_attempt",
                gates = mapOf("current_playback_attempt" to false)
            )
            return
        }

        if (!manualTakeover && takingOverFor == "music" &&
            !MediaController.isEligibleLocalPlaybackActive() &&
            !MediaController.isAwaitingLocalRoute()
        ) {
            Log.d(TAG, "Not taking over: eligible local playback ended before request")
            traceTakeoverDecision(
                correlation,
                allowed = false,
                reason = "eligible_playback_ended_before_takeover",
                gates = mapOf("eligible_local_playback" to false)
            )
            return
        }

        if (!manualTakeover && takingOverFor == "music") {
            maybeInferLocalHostIdentity(aacpManager.audioSource, "takeover_preflight")
            if (LocalHostIdentity.normalize(localMac) == null) {
                Log.w(TAG, "Not taking over: local Bluetooth identity is unavailable")
                traceTakeoverDecision(
                    correlation,
                    allowed = false,
                    reason = "local_identity_unavailable",
                    gates = mapOf("local_identity_available" to false)
                )
                return
            }
        }

        if (!manualTakeover && !automaticTakeoverEnabled) {
            Log.d(TAG, "Not taking over: automatic Smart Routing takeover is disabled")
            traceTakeoverDecision(
                correlation,
                allowed = false,
                reason = "automatic_takeover_disabled",
                gates = mapOf("master_enabled" to false)
            )
            return
        }

        // Automatic ownership takeover must use the vendor identity hook. Without it,
        // this process advertises as a normal Android host and can race Xiaomi's native
        // AirPods integration for the same ownership channel. Ordinary control-channel
        // connections remain available so a transient Xposed preference read failure does
        // not disable the rest of LibrePods.
        if (!manualTakeover && !vendorIdentityHookReportedEnabled) {
            Log.d(TAG, "Not taking over: vendor identity hook is not enabled")
            traceTakeoverDecision(
                correlation,
                allowed = false,
                reason = "vendor_identity_hook_disabled",
                gates = mapOf(
                    "master_enabled" to automaticTakeoverEnabled,
                    "vendor_identity_hook_enabled" to false
                )
            )
            return
        }

        val shouldTakeOverPhoneState = when (takingOverFor) {
            "music" -> true
            "call" -> config.takeoverWhenRingingCall
            else -> false
        }

        if (!manualTakeover && !shouldTakeOverPhoneState) {
            Log.d(TAG, "Not taking over audio: phone state takeover is disabled")
            traceTakeoverDecision(
                correlation,
                allowed = false,
                reason = "phone_state_policy_disabled",
                gates = mapOf(
                    "master_enabled" to automaticTakeoverEnabled,
                    "phone_state_enabled" to false
                )
            )
            return
        }

        if (takingOverFor == "reverse") {
            aacpManager.sendControlCommand(
                AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                byteArrayOf(0x01),
                correlation,
                "manual_move_back"
            )
            aacpManager.sendMediaInformataion(
                localMac
            )
            aacpManager.sendHijackReversed(
                localMac
            )
            connectAudio(
                this@AirPodsService, device
            )
            otherDeviceTookOver = false
            refreshAacpControlSession("manual Smart Routing takeover")
            Log.d(TAG, "Manual Smart Routing takeover request sent")
            return
        }
        val ownsConnection = aacpManager.owns
        Log.d(TAG, "confirmed ownership: $ownsConnection")
        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
            val currentSource = aacpManager.audioSource
            val currentSignals = TakeoverRouteSignals(
                ownsConnection = ownsConnection,
                audioSource = classifyAudioSource(currentSource),
                a2dpConnected = isAirPodsA2dpConnected,
                targetAudioActive = isTargetAudioRouteActive(),
            )
            val targetRouteRepairRequired = takingOverFor == "music" &&
                !currentSignals.targetAudioActive
            val continuingRemoteHandoff = correlation.takeoverAttemptId != null &&
                pendingFreshLocalSourceAttemptId == correlation.takeoverAttemptId
            if (takingOverFor == "music" &&
                !continuingRemoteHandoff &&
                TakeoverRouteGate.isLocalAudioAlreadyRouted(currentSignals)
            ) {
                Log.d(TAG, "AirPods audio is already routed locally; skipping ownership takeover")
                if (LocalHostIdentity.normalize(localMac) != null) {
                    val mediaInformationSent = aacpManager.sendMediaInformataion(
                        localMac,
                        streamingState = true
                    )
                    traceAacpSend(
                        correlation,
                        AacpOperation.MEDIA_INFORMATION,
                        mediaInformationSent,
                        requestedValue = 1,
                        reason = "local_route_already_active"
                    )
                }
                MediaController.onLocalRouteAlreadyActive(
                    correlation.takeoverAttemptId,
                    "aacp_local_source_and_a2dp_connected"
                )
                traceTakeoverDecision(
                    correlation,
                    allowed = false,
                    reason = "local_audio_route_already_active",
                    gates = mapOf(
                        "confirmed_local_source" to true,
                        "a2dp_connected" to true,
                        "target_audio_active" to true,
                        "ownership_required" to false
                    )
                )
                return
            }
            if (TakeoverRouteGate.shouldRequestTakeover(currentSignals)) {
                if (disconnectedBecauseReversed) {
                    if (manualTakeOverAfterReversed) {
                        Log.d(TAG, "forcefully taking over despite reverse as user requested")
                        disconnectedBecauseReversed = false
                    } else {
                        Log.d(
                            TAG,
                            "connected locally, but can not hijack as other device had reversed"
                        )
                        traceTakeoverDecision(
                            correlation,
                            allowed = false,
                            reason = "remote_reverse_requires_manual_confirmation",
                            gates = mapOf("reversed_by_remote" to true)
                        )
                        return
                    }
                }

                Log.d(TAG, "already connected locally, hijacking connection by asking AirPods")
                val remoteHandoffRequired = continuingRemoteHandoff ||
                    currentSignals.audioSource != TakeoverAudioSource.LOCAL
                if (takingOverFor == "music") {
                    val playbackRoutedToTarget =
                        MediaController.isEligiblePlaybackRoutedToTarget()
                    if (!MediaController.beginTakeover(
                            correlation,
                            pauseImmediately = TakeoverPausePolicyGate.shouldPauseImmediately(
                                targetAudioActive = currentSignals.targetAudioActive,
                                playbackRoutedToTarget = playbackRoutedToTarget,
                                // A remote/NONE source is the handoff case: pausing here sends a
                                // key to the phone that just requested playback. Only pause when
                                // an already-local stream needs an output-route repair.
                                localSourceConfirmed =
                                    currentSignals.audioSource == TakeoverAudioSource.LOCAL,
                            ),
                        )
                    ) {
                        traceTakeoverDecision(
                            correlation,
                            allowed = false,
                            reason = "playback_ended_before_takeover_guard",
                            gates = mapOf("takeover_route_guard_started" to false)
                        )
                        return
                    }
                    if (!isCurrentAutomaticTakeoverSideEffect(
                            correlation,
                            "after_begin_takeover",
                        )
                    ) {
                        return
                    }
                    if (targetRouteRepairRequired) {
                        confirmedActiveA2dpAddress = null
                        pendingTargetRouteActivationAttemptId = correlation.takeoverAttemptId
                        requiredTargetRouteAttemptId = correlation.takeoverAttemptId
                    }
                }
                connectAudio(
                    this,
                    device,
                    activateForMedia = targetRouteRepairRequired,
                )
                if (!isCurrentAutomaticTakeoverSideEffect(
                        correlation,
                        "after_connect_audio",
                    )
                ) {
                    return
                }
                if (!remoteHandoffRequired) {
                    traceTakeoverDecision(
                        correlation,
                        allowed = true,
                        reason = "local_route_repair_started",
                        gates = mapOf(
                            "confirmed_local_source" to true,
                            "target_route_repair_required" to targetRouteRepairRequired,
                        )
                    )
                    evaluateTakeoverRoute("local_route_repair_started")
                    return
                }

                val attemptId = correlation.takeoverAttemptId
                // A new takeover attempt retires every packet permit from the previous one.
                invalidateActiveTakeoverPacketPermit()
                pendingOwnershipRequiredAttemptId = attemptId
                pendingFreshLocalSourceAttemptId = attemptId
                freshLocalSourceConfirmedAttemptId = null
                completedTakeoverRequestAttemptId = null
                val targetMacAddresses = aacpManager.resolveSmartRoutingTargets(localMac)
                if (targetMacAddresses.isEmpty()) {
                    if (!isCurrentAutomaticTakeoverSideEffect(
                            correlation,
                            "before_notification_request",
                        )
                    ) {
                        return
                    }
                    pendingTakeoverRetryCorrelation = correlation
                    aacpManager.sendNotificationRequest()
                    traceTakeoverDecision(
                        correlation,
                        allowed = false,
                        reason = "waiting_for_remote_smart_routing_target",
                        gates = mapOf("remote_target_available" to false)
                    )
                    return
                }

                val lease = BluetoothConnectionManager.currentAacpSocketLease()
                if (lease == null || !BluetoothConnectionManager.isCurrent(lease)) {
                    pendingTakeoverRetryCorrelation = correlation
                    traceTakeoverDecision(
                        correlation,
                        allowed = false,
                        reason = "takeover_socket_lease_unavailable",
                        gates = mapOf("aacp_socket_lease_current" to false),
                    )
                    return
                }
                val permit = TakeoverPacketPermit(
                    correlation = correlation,
                    lease = lease,
                    selfMacAddress = localMac,
                    targetMacAddresses = targetMacAddresses.toList(),
                )
                activeTakeoverPacketPermit.set(permit)
                if (!canEnterTakeoverPacketSequence(
                        permit,
                        sourcePolicy = TakeoverPacketSourcePolicy.INITIAL_NON_LOCAL,
                        stage = "before_takeover_packet_sequence",
                    )
                ) {
                    invalidateActiveTakeoverPacketPermit(attemptId)
                    return
                }
                if (!isCurrentAutomaticTakeoverSideEffect(
                        correlation,
                        "before_takeover_packet_sequence",
                    )
                ) {
                    invalidateActiveTakeoverPacketPermit(attemptId)
                    return
                }
                val sequenceResult = aacpManager.sendTakeoverPacketSequence(
                    selfMacAddress = permit.selfMacAddress,
                    targetMacAddresses = permit.targetMacAddresses,
                    lease = permit.lease,
                    mode = TakeoverPacketSequenceMode.FULL,
                    sourcePolicy = TakeoverPacketSourcePolicy.INITIAL_NON_LOCAL,
                    correlation = correlation,
                    reason = "eligible_local_playback",
                    packetPermit = {
                        activeTakeoverPacketPermit.get() === permit && permit.valid
                    },
                )
                traceTakeoverSequenceResult(
                    correlation,
                    sequenceResult,
                    "takeover_requested",
                )
                if (!sequenceResult.completed) {
                    if (sequenceResult.failure == TakeoverPacketSequenceFailure.SOURCE_REJECTED) {
                        if (classifyAudioSource(aacpManager.audioSource) == TakeoverAudioSource.LOCAL) {
                            settleTakeoverAsLocalSource(
                                attemptId,
                                "takeover_source_converged_during_sequence",
                            )
                        } else {
                            invalidateActiveTakeoverPacketPermit(attemptId)
                        }
                        return
                    }
                    invalidateActiveTakeoverPacketPermit(attemptId)
                    if (sequenceResult.failure == TakeoverPacketSequenceFailure.PERMIT_REVOKED) {
                        return
                    }
                    pendingTakeoverRetryCorrelation = correlation
                    requestAutomaticReconnect(
                        "takeover_packet_sequence_failed",
                        ensureAudioProfiles = false,
                        boundedControlRepair = true,
                    )
                    traceTakeoverDecision(
                        correlation,
                        allowed = false,
                        reason = "required_takeover_packet_sequence_failed",
                        gates = mapOf(
                            "aacp_socket_lease_current" to
                                (sequenceResult.failure != TakeoverPacketSequenceFailure.SOCKET_STALE),
                            "sequence_completed" to false,
                        )
                    )
                    return
                }
                if (!canEnterTakeoverPacketSequence(
                        permit,
                        sourcePolicy = null,
                        stage = "after_takeover_packet_sequence",
                    )
                ) {
                    invalidateActiveTakeoverPacketPermit(attemptId)
                    return
                }
                completedTakeoverRequestAttemptId = attemptId
                pendingTakeoverRetryCorrelation = null
                traceTakeoverDecision(
                    correlation,
                    allowed = true,
                    reason = "required_takeover_packets_sent",
                    gates = mapOf(
                        "master_enabled" to automaticTakeoverEnabled,
                        "aacp_socket_connected" to true,
                        "vendor_identity_hook_reported_enabled" to
                            vendorIdentityHookReportedEnabled,
                        "already_confirmed_local" to false
                    )
                )
                scheduleTakeoverSourceRetries(correlation, permit)
                evaluateTakeoverRoute("takeover_request_complete")
                showIsland(
                    this,
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.LEFT }?.level!!.coerceAtMost(
                            batteryNotification.getBattery()
                                .find { it.component == BatteryComponent.RIGHT }?.level!!
                        ),
                    IslandType.CONNECTED
                )

            } else {
                Log.d(
                    TAG, "Already connected locally and already own connection, skipping takeover"
                )
                if (takingOverFor == "music" &&
                    LocalHostIdentity.normalize(localMac) != null
                ) {
                    aacpManager.sendMediaInformataion(localMac, streamingState = true)
                    // The AACP control socket can survive an A2DP profile drop. Re-issue the
                    // profile connection request so a subsequent local media start cannot fall
                    // back to the phone speaker while the control channel still looks healthy.
                    if (!isAirPodsA2dpConnected) {
                        connectAudio(this, device)
                    }
                }
                traceTakeoverDecision(
                    correlation,
                    allowed = false,
                    reason = "already_confirmed_local_owner",
                    gates = mapOf(
                        "confirmed_ownership" to true,
                        "confirmed_local_source" to true
                    )
                )
            }
            return
        }

//        if (CrossDevice.isAvailable) {
//            Log.d(TAG, "CrossDevice is available, continuing")
//        }
//        else if (bleManager.getMostRecentStatus()?.isLeftInEar == true || bleManager.getMostRecentStatus()?.isRightInEar == true) {
//            Log.d(TAG, "At least one AirPod is in ear, continuing")
//        }
//        else {
//            Log.d(TAG, "CrossDevice not available and AirPods not in ear, skipping")
//            return
//        }

        if (bleManager.getMostRecentStatus()?.isLeftInEar == false && bleManager.getMostRecentStatus()?.isRightInEar == false) {
            Log.d(TAG, "Both AirPods are out of ear, not taking over audio")
            traceTakeoverDecision(
                correlation,
                allowed = false,
                reason = "both_airpods_out_of_ear",
                gates = mapOf("airpods_in_ear" to false)
            )
            return
        }

        val shouldTakeOver = when (bleManager.getMostRecentStatus()?.connectionState) {
            "Disconnected" -> config.takeoverWhenDisconnected
            "Idle" -> config.takeoverWhenIdle
            "Music" -> config.takeoverWhenMusic
            "Call" -> config.takeoverWhenCall
            "Ringing" -> config.takeoverWhenCall
            "Hanging Up" -> config.takeoverWhenCall
            else -> false
        }

        if (!shouldTakeOver) {
            Log.d(TAG, "Not taking over audio, airpods state takeover disabled")
            traceTakeoverDecision(
                correlation,
                allowed = false,
                reason = "airpods_state_policy_disabled",
                gates = mapOf("airpods_state_enabled" to false)
            )
            return
        }

        if (takingOverFor == "music") {
            Log.d(TAG, "Preparing local media takeover without pre-pausing a remote handoff")
            if (!config.bleOnlyMode) {
                val playbackRoutedToTarget = MediaController.isEligiblePlaybackRoutedToTarget()
                if (!MediaController.beginTakeover(
                        correlation,
                        pauseImmediately = TakeoverPausePolicyGate.shouldPauseImmediately(
                            targetAudioActive = isTargetAudioRouteActive(),
                            playbackRoutedToTarget = playbackRoutedToTarget,
                            localSourceConfirmed =
                                classifyAudioSource(aacpManager.audioSource) ==
                                    TakeoverAudioSource.LOCAL,
                        ),
                    )
                ) {
                    traceTakeoverDecision(
                        correlation,
                        allowed = false,
                        reason = "playback_ended_before_legacy_takeover_guard",
                        gates = mapOf("takeover_route_guard_started" to false)
                    )
                    return
                }
                confirmedActiveA2dpAddress = null
                pendingTargetRouteActivationAttemptId = correlation.takeoverAttemptId
                requiredTargetRouteAttemptId = correlation.takeoverAttemptId
                pendingTakeoverRetryCorrelation = correlation
            }
        } else {
            handleIncomingCallOnceConnected = true
        }

        Log.d(TAG, "Taking over audio")
//        CrossDevice.sendRemotePacket(CrossDevicePackets.REQUEST_DISCONNECT.packet)
        Log.d(TAG, macAddress)

//        sharedPreferences.edit { putBoolean("CrossDeviceIsAvailable", false) }
        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager.adapter
        device = bluetoothAdapter.bondedDevices.find {
            it.address == macAddress
        }

        if (device != null) {
            if (config.bleOnlyMode) {
                // In BLE-only mode, just show connecting status without actual L2CAP connection
                Log.d(TAG, "BLE-only mode: showing connecting status without L2CAP connection")
                updateNotificationContent(
                    true, config.deviceName, batteryNotification.getBattery()
                )
                // Set a temporary connecting state
//                isConnectedLocally = false // Keep as false since we're not actually connecting to L2CAP
            } else {
                val socketConnected = connectToSocket(
                    bluetoothAdapter,
                    device!!,
                    manual = manualTakeover
                )
                if (!socketConnected) {
                    pendingTargetRouteActivationAttemptId = null
                    MediaController.onTakeoverFailed("legacy_socket_connection_failed")
                    return
                }
                runCatching {
                    connectAudio(
                        this,
                        device,
                        activateForMedia = takingOverFor == "music",
                    )
                }
                    .onFailure { error ->
                        Log.w(TAG, "Legacy A2DP takeover could not start", error)
                        pendingTargetRouteActivationAttemptId = null
                        MediaController.onTakeoverFailed("legacy_audio_connection_failed")
                    }
//                isConnectedLocally = true
            }
        } else if (takingOverFor == "music" && !config.bleOnlyMode) {
            pendingTargetRouteActivationAttemptId = null
            MediaController.onTakeoverFailed("airpods_device_not_found")
        }
        showIsland(
            this,
            batteryNotification.getBattery()
                .find { it.component == BatteryComponent.LEFT }?.level!!.coerceAtMost(
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.RIGHT }?.level!!
                ),
            IslandType.TAKING_OVER
        )

        traceTakeoverDecision(
            correlation,
            allowed = true,
            reason = "legacy_connection_takeover_started",
            gates = mapOf(
                "master_enabled" to automaticTakeoverEnabled,
                "airpods_state_enabled" to true
            )
        )

//        CrossDevice.isAvailable = false
    }

    private fun traceTakeoverDecision(
        correlation: RoutingCorrelation,
        allowed: Boolean,
        reason: String,
        gates: Map<String, Boolean>
    ) {
        RoutingTrace.record(correlation) {
            RoutingEventDetail.Decision(
                action = RoutingAction.AUTOMATIC_TAKEOVER,
                allowed = allowed,
                reason = reason,
                gates = gates
            )
        }
    }

    private fun isVendorIdentityHookEnabled(): Boolean = runCatching {
        XposedRemotePrefProvider.create().getBoolean("vendor_id_hook", false)
    }.getOrDefault(false)

    private fun traceAacpSend(
        correlation: RoutingCorrelation,
        operation: AacpOperation,
        sent: Boolean,
        requestedValue: Int? = null,
        reason: String
    ) {
        RoutingTrace.record(correlation) {
            RoutingEventDetail.AacpSend(
                operation = operation,
                outcome = if (sent) AacpSendOutcome.SENT else AacpSendOutcome.WRITE_FAILED,
                socketConnected = BluetoothConnectionManager.aacpSocket?.isConnected == true,
                requestedValue = requestedValue,
                reason = reason
            )
        }
    }

    private fun traceTakeoverSequenceResult(
        correlation: RoutingCorrelation,
        result: me.kavishdevar.librepods.bluetooth.TakeoverPacketSequenceResult,
        reason: String,
    ) {
        // Ownership is already traced by AACPManager's control-command writer. Keep the
        // remaining two packet outcomes explicit so a partial sequence is diagnosable.
        traceAacpSend(
            correlation,
            AacpOperation.MEDIA_INFORMATION,
            result.mediaInformationSent,
            requestedValue = 1,
            reason = "$reason:media_information",
        )
        traceAacpSend(
            correlation,
            AacpOperation.HIJACK_REQUEST,
            result.hijackSent,
            reason = "$reason:hijack_request",
        )
    }

    private fun refreshAacpControlSession(reason: String) {
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (now - lastAacpControlRefreshAt < 2_000L) {
                Log.d(TAG, "Skipping duplicate AACP control refresh: $reason")
                return
            }
            lastAacpControlRefreshAt = now
        }

        CoroutineScope(Dispatchers.IO).launch {
            delay(350)
            if (BluetoothConnectionManager.aacpSocket?.isConnected != true) {
                Log.w(TAG, "Cannot refresh AACP control session: socket is not connected")
                return@launch
            }

            Log.d(TAG, "Refreshing AACP control session after $reason")
            val conversationAwarenessValue = aacpManager.getControlCommandStatus(
                AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG
            )?.value?.firstOrNull()

            aacpManager.sendPacket(aacpManager.createHandshakePacket())
            aacpManager.sendSetFeatureFlagsPacket()
            aacpManager.sendNotificationRequest()

            if (conversationAwarenessValue == 0x01.toByte() || conversationAwarenessValue == 0x02.toByte()) {
                aacpManager.sendControlCommand(
                    AACPManager.Companion.ControlCommandIdentifiers.CONVERSATION_DETECT_CONFIG.value,
                    conversationAwarenessValue
                )
            }

            delay(350)
            aacpManager.sendNotificationRequest()
            Log.d(TAG, "AACP control session refresh complete")
        }
    }

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    private fun closeCurrentAacpGeneration(
        socket: BluetoothSocket,
        socketGeneration: Long,
        reason: String,
    ): Boolean = synchronized(aacpConnectLock) {
        if (!BluetoothConnectionManager.closeAacpGeneration(socket, socketGeneration)) {
            return@synchronized false
        }
        attManager.disconnected()
        BluetoothConnectionManager.attSocket = null
        aacpManager.disconnected()
        handleAacpDisconnectedState(reason)
        true
    }

    private fun cleanupAbandonedControlStartup(
        aacpSocket: BluetoothSocket,
        aacpSocketGeneration: Long,
        attSocket: BluetoothSocket?,
        readerStartupGate: AacpReaderStartupGate,
        reason: String,
    ) {
        val cleanup = readerStartupGate.claimCleanupBeforeReaderStarted() ?: return
        synchronized(automaticReconnectLock) {
            if (connectingAacpSocket === aacpSocket) connectingAacpSocket = null
            if (connectingAttSocket === attSocket) connectingAttSocket = null
        }
        if (cleanup.includesAttSocket) {
            BluetoothConnectionManager.invalidateAttSocket(attSocket)
        }
        closeCurrentAacpGeneration(aacpSocket, aacpSocketGeneration, reason)
    }

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    fun connectToSocket(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        manual: Boolean = false,
        connectionGuard: (() -> Boolean)? = null,
    ): Boolean = synchronized(aacpConnectLock) {
        connectToSocketLocked(adapter, device, manual, connectionGuard)
    }

    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    private fun connectToSocketLocked(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        manual: Boolean,
        connectionGuard: (() -> Boolean)?,
    ): Boolean {
        val lifecycleGeneration = automaticReconnectGeneration
        val effectiveGuard = {
            !automaticReconnectSuppressed &&
                automaticReconnectGeneration == lifecycleGeneration &&
                connectionGuard?.invoke() != false
        }
        if (!effectiveGuard()) return false
        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
            Log.d(TAG, "AACP socket is already connected, skipping duplicate request")
            return true
        }
        Log.d(TAG, "<LogCollector:Start> Connecting to socket")
        val uuid: ParcelUuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
        var socketGeneration = -1L
        val readerStartupGate = AacpReaderStartupGate()
        var readerJobScheduled = false
        var startupAttSocket: BluetoothSocket? = null
//        if (!isConnectedLocally) {
        val socket = try {
            createBluetoothSocket(adapter, device, uuid, 4097)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create BluetoothSocket: ${e.message}")
            showSocketConnectionFailureNotification("Failed to create Bluetooth socket: ${e.localizedMessage}")
            return false
        }
        val accepted = synchronized(automaticReconnectLock) {
            if (effectiveGuard()) {
                connectingAacpSocket = socket
                true
            } else {
                false
            }
        }
        if (!accepted) {
            runCatching { socket.close() }
            return false
        }

        var installedAsCurrentSocket = false
        try {
            runBlocking {
                withTimeout(5000.milliseconds) {
                    try {
                        socket.connect()
                        val installed = synchronized(automaticReconnectLock) {
                            if (!effectiveGuard()) {
                                false
                            } else {
                                this@AirPodsService.device = device
                                socketGeneration =
                                    BluetoothConnectionManager.installAacpSocket(socket)
                                installedAsCurrentSocket = true
                                readerStartupGate.markSocketInstalled()
                                true
                            }
                        }
                        if (!installed) {
                            socket.close()
                            return@withTimeout
                        }
                        audioFeatureScope.launch(Dispatchers.IO) {
                            synchronized(automaticReconnectLock) {
                                if (effectiveGuard() &&
                                    BluetoothConnectionManager.aacpSocket === socket
                                ) {
                                    PhoneSoundRoutingController.setTargetAvailable(
                                        true,
                                        "aacp_socket_connected"
                                    )
                                }
                            }
                        }
                        RoutingTrace.record(MediaController.currentTakeoverCorrelation()) {
                            RoutingEventDetail.AacpStateChanged(
                                kind = AacpStateKind.SOCKET,
                                previousValue = "connecting",
                                newValue = "connected",
                                correlationBasis = "socket_connect_result"
                            )
                        }
                        val useVendorAttSocket = XposedRemotePrefProvider.create().getBoolean(
                            "vendor_id_hook", false
                        ) && sharedPreferences.getBoolean("vendor_att_socket", false)
                        val attSocket = if (useVendorAttSocket) {
                            attManager.disconnected()
                            BluetoothConnectionManager.attSocket = null
                            try {
                                val candidate = createBluetoothSocket(
                                    adapter,
                                    device,
                                    ParcelUuid.fromString("00000000-0000-0000-0000-000000000000"),
                                    31
                                )
                                val acceptedAttConnection = synchronized(automaticReconnectLock) {
                                    if (effectiveGuard() &&
                                        BluetoothConnectionManager.aacpSocket === socket
                                    ) {
                                        connectingAttSocket = candidate
                                        true
                                    } else {
                                        false
                                    }
                                }
                                if (!acceptedAttConnection) {
                                    candidate.close()
                                    null
                                } else {
                                    try {
                                        candidate.connect()
                                        val installedAttSocket = synchronized(
                                            automaticReconnectLock
                                        ) {
                                            if (effectiveGuard() &&
                                                BluetoothConnectionManager.aacpSocket === socket
                                            ) {
                                                BluetoothConnectionManager.attSocket = candidate
                                                startupAttSocket = candidate
                                                readerStartupGate.markAttSocketInstalled()
                                                true
                                            } else {
                                                false
                                            }
                                        }
                                        if (installedAttSocket) candidate else {
                                            candidate.close()
                                            null
                                        }
                                    } catch (error: Exception) {
                                        runCatching { candidate.close() }
                                        throw error
                                    } finally {
                                        synchronized(automaticReconnectLock) {
                                            if (startupAttSocket !== candidate &&
                                                connectingAttSocket === candidate
                                            ) {
                                                connectingAttSocket = null
                                            }
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w(
                                    TAG,
                                    "Optional PSM 31 channel failed; continuing with AACP only: ${e.message}"
                                )
                                null
                            }
                        } else null

                        if (attSocket != null) {
                            attManager.startReader()
                            attManager.readCharacteristic(ATTHandles.LOUD_SOUND_REDUCTION)
                            attManager.readCharacteristic(ATTHandles.TRANSPARENCY)
                            attManager.readCharacteristic(ATTHandles.HEARING_AID)
                        }
                        if (!effectiveGuard() ||
                            BluetoothConnectionManager.aacpSocket !== socket
                        ) {
                            return@withTimeout
                        }

                        // Create AirPodsInstance from stored config if available
                        if (airpodsInstance == null && config.airpodsModelNumber.isNotEmpty()) {
                            val model =
                                AirPodsModels.getModelByModelNumber(config.airpodsModelNumber)
                            if (model != null) {
                                airpodsInstance = AirPodsInstance(
                                    name = config.airpodsName,
                                    model = model,
                                    actualModelNumber = config.airpodsModelNumber,
                                    serialNumber = config.airpodsSerialNumber,
                                    leftSerialNumber = config.airpodsLeftSerialNumber,
                                    rightSerialNumber = config.airpodsRightSerialNumber,
                                    version1 = config.airpodsVersion1,
                                    version2 = config.airpodsVersion2,
                                    version3 = config.airpodsVersion3,
                                )
                                setMetadatas(device)
                            }
                        }

                        val publishedConnectedState = synchronized(automaticReconnectLock) {
                            if (!effectiveGuard() ||
                                BluetoothConnectionManager.aacpSocket !== socket
                            ) {
                                false
                            } else {
                                updateNotificationContent(
                                    true, config.deviceName, batteryNotification.getBattery()
                                )
                                Log.d(
                                    TAG,
                                    "<LogCollector:Complete:Success> Socket connected"
                                )
                                sharedPreferences.edit {
                                    putBoolean("connection_successful", true)
                                }
                                if (!sharedPreferences.contains(
                                        "first_connection_successful_time"
                                    )
                                ) {
                                    sharedPreferences.edit {
                                        putLong(
                                            "first_connection_successful_time",
                                            System.currentTimeMillis()
                                        )
                                    }
                                }
                                sendBroadcast(
                                    Intent(
                                        AirPodsNotifications.AIRPODS_L2CAP_CONNECTED
                                    ).apply { setPackage(packageName) }
                                )
                                true
                            }
                        }
                        if (!publishedConnectedState) {
                            return@withTimeout
                        }
                    } catch (e: Exception) {
                        if (!effectiveGuard()) return@withTimeout
                        PhoneSoundRoutingController.setTargetAvailable(
                            false,
                            "aacp_socket_connect_failed"
                        )
                        RoutingTrace.recordError(
                            "aacp_socket",
                            "connect_failed",
                            e,
                            MediaController.currentTakeoverCorrelation()
                        )
//                        sharedPreferences.edit { putBoolean("connection_successful", false) }
                        Log.d(
                            TAG, "<LogCollector:Complete:Failed> Socket not connected, ${e.message}"
                        )
                        if (manual) {
                            sendToast(
                                "Couldn't connect to socket: ${e.localizedMessage}"
                            )
                        } else {
                            showSocketConnectionFailureNotification("Couldn't connect to socket: ${e.localizedMessage}")
                        }
                        return@withTimeout
//                            throw e // lol how did i not catch this before... gonna comment this line instead of removing to preserve history
                    }
                }
            }
            if (!effectiveGuard() || BluetoothConnectionManager.aacpSocket !== socket) {
                return false
            }
            if (!socket.isConnected) {
                if (!effectiveGuard()) return false
                PhoneSoundRoutingController.setTargetAvailable(
                    false,
                    "aacp_socket_connect_timeout"
                )
                Log.d(TAG, "<LogCollector:Complete:Failed> socket not connected")
                if (manual) {
                    sendToast(
                        "Couldn't connect to socket: timeout."
                    )
                } else {
                    showSocketConnectionFailureNotification("Couldn't connect to socket: Timeout")
                }
                return false
            }
            this@AirPodsService.device = device
            socket.let {
                aacpManager.sendPacket(aacpManager.createHandshakePacket())
                aacpManager.sendSetFeatureFlagsPacket()
                aacpManager.sendNotificationRequest()
                audioFeatureScope.launch {
                    delay(250)
                    retryPendingTakeover("aacp_socket_connected")
                }
                Log.d(TAG, "Requesting proximity keys")
                aacpManager.sendRequestProximityKeys((AACPManager.Companion.ProximityKeyType.IRK.value + AACPManager.Companion.ProximityKeyType.ENC_KEY.value).toByte())
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (!effectiveGuard() ||
                            BluetoothConnectionManager.aacpSocket !== socket
                        ) return@launch
                    delay(200)
                    if (!effectiveGuard() ||
                        BluetoothConnectionManager.aacpSocket !== socket
                    ) return@launch
                    aacpManager.sendPacket(aacpManager.createHandshakePacket())
                    delay(200)
                    if (!effectiveGuard() ||
                        BluetoothConnectionManager.aacpSocket !== socket
                    ) return@launch
                    aacpManager.sendSetFeatureFlagsPacket()
                    delay(200)
                    if (!effectiveGuard() ||
                        BluetoothConnectionManager.aacpSocket !== socket
                    ) return@launch
                    aacpManager.sendNotificationRequest()
                    delay(200)
                    if (!effectiveGuard() ||
                        BluetoothConnectionManager.aacpSocket !== socket
                    ) return@launch
                    aacpManager.sendSomePacketIDontKnowWhatItIs()
                    delay(200)
                    if (!effectiveGuard() ||
                        BluetoothConnectionManager.aacpSocket !== socket
                    ) return@launch
                    aacpManager.sendRequestProximityKeys((AACPManager.Companion.ProximityKeyType.IRK.value + AACPManager.Companion.ProximityKeyType.ENC_KEY.value).toByte())
                    if (!handleIncomingCallOnceConnected) {
                        if (SpatialAudioMode.fromPreferences(sharedPreferences) ==
                            SpatialAudioMode.HEAD_TRACKED
                        ) {
                            // The PLAYING broadcast may have happened while the
                            // service was stopped, so query again once AACP is usable.
                            refreshCurrentA2dpPlaybackState("AACP connected")
                        } else {
                            startHeadTracking()
                        }
                    } else {
                        handleIncomingCall()
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!effectiveGuard() ||
                            BluetoothConnectionManager.aacpSocket !== socket
                        ) return@postDelayed
                        aacpManager.sendPacket(aacpManager.createHandshakePacket())
                        aacpManager.sendSetFeatureFlagsPacket()
                        aacpManager.sendNotificationRequest()
                        aacpManager.sendRequestProximityKeys(AACPManager.Companion.ProximityKeyType.IRK.value)
                        if (!handleIncomingCallOnceConnected) stopHeadTracking()
                    }, 5000)

                    if (!effectiveGuard() ||
                        BluetoothConnectionManager.aacpSocket !== socket
                    ) return@launch
                    sendBroadcast(
                        Intent(AirPodsNotifications.AIRPODS_CONNECTED).putExtra("device", device)
                            .apply {
                                setPackage(packageName)
                            })

                    setupStemActions()

                    val readerStarted = synchronized(automaticReconnectLock) {
                        val connectionIsCurrent = effectiveGuard() &&
                            BluetoothConnectionManager.aacpSocket === socket
                        if (readerStartupGate.tryStartReader(connectionIsCurrent)) {
                            if (connectingAacpSocket === socket) {
                                connectingAacpSocket = null
                            }
                            startupAttSocket?.let { attSocket ->
                                if (connectingAttSocket === attSocket) {
                                    connectingAttSocket = null
                                }
                            }
                            true
                        } else {
                            false
                        }
                    }
                    if (!readerStarted) return@launch

                    while (socket.isConnected) {
                        try {
                            val buffer = ByteArray(1024)
                            val bytesRead = it.inputStream.read(buffer)
                            var data: ByteArray
                            if (bytesRead > 0) {
                                data = buffer.copyOfRange(0, bytesRead)
                                sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DATA).apply {
                                    putExtra("data", buffer.copyOfRange(0, bytesRead))
                                    setPackage(packageName)
                                })
//                                    CrossDevice.sendReceivedPacket(bytes)
                                updateNotificationContent(
                                    true,
                                    sharedPreferences.getString("name", device.name),
                                    batteryNotification.getBattery()
                                )

                                aacpManager.receivePacket(data)

                            } else if (bytesRead == -1) {
                                Log.d("AirPodsService", "socket closed (bytesRead = -1)")
                                val closedCurrentGeneration = closeCurrentAacpGeneration(
                                    socket,
                                    socketGeneration,
                                    "aacp_remote_close",
                                )
                                if (closedCurrentGeneration) {
                                    sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED).apply {
                                        putExtra(EXTRA_AACP_SOCKET_GENERATION, socketGeneration)
                                        setPackage(packageName)
                                    })
                                    requestAutomaticReconnect(
                                        "aacp_remote_close",
                                        ensureAudioProfiles = false,
                                        boundedControlRepair = true,
                                    )
                                }
                                return@launch
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error reading data, we have probably disconnected.")
                            e.printStackTrace()
                            val closedCurrentGeneration = closeCurrentAacpGeneration(
                                socket,
                                socketGeneration,
                                "aacp_read_failed",
                            )
                            if (closedCurrentGeneration) {
                                sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED).apply {
                                    putExtra(EXTRA_AACP_SOCKET_GENERATION, socketGeneration)
                                    setPackage(packageName)
                                })
                                requestAutomaticReconnect(
                                    "aacp_read_failed",
                                    ensureAudioProfiles = false,
                                    boundedControlRepair = true,
                                )
                            }
                            return@launch
                        }

                    }
                    Log.d("AirPods Service", "socket closed")
//                        isConnectedLocally = false
                    val closedCurrentGeneration = closeCurrentAacpGeneration(
                        socket,
                        socketGeneration,
                        "aacp_reader_stopped",
                    )
                        if (closedCurrentGeneration) {
                            sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED).apply {
                                putExtra(EXTRA_AACP_SOCKET_GENERATION, socketGeneration)
                                setPackage(packageName)
                            })
                        }
                    } finally {
                        cleanupAbandonedControlStartup(
                            aacpSocket = socket,
                            aacpSocketGeneration = socketGeneration,
                            attSocket = startupAttSocket,
                            readerStartupGate = readerStartupGate,
                            reason = "aacp_reader_startup_abandoned",
                        )
                    }
                }
                readerJobScheduled = true
            }
            return socket.isConnected
        } catch (e: Exception) {
            if (!effectiveGuard()) return false
            PhoneSoundRoutingController.setTargetAvailable(false, "aacp_socket_connect_error")
            RoutingTrace.recordError(
                "aacp_socket",
                "connect_unhandled_error",
                e,
                MediaController.currentTakeoverCorrelation()
            )
            e.printStackTrace()
            Log.d(TAG, "Failed to connect to BluetoothConnectionManager.aacpSocket?: ${e.message}")
            showSocketConnectionFailureNotification("Failed to establish connection: ${e.localizedMessage}")
//                isConnectedLocally = false
            this@AirPodsService.device = device
            updateNotificationContent(false)
            return false
        } finally {
            if (!readerJobScheduled) {
                synchronized(automaticReconnectLock) {
                    if (connectingAacpSocket === socket) {
                        connectingAacpSocket = null
                    }
                    startupAttSocket?.let { attSocket ->
                        if (connectingAttSocket === attSocket) {
                            connectingAttSocket = null
                        }
                    }
                }
            }
            if (!readerJobScheduled) {
                cleanupAbandonedControlStartup(
                    aacpSocket = socket,
                    aacpSocketGeneration = socketGeneration,
                    attSocket = startupAttSocket,
                    readerStartupGate = readerStartupGate,
                    reason = "aacp_connect_setup_abandoned",
                )
            }
            if (!installedAsCurrentSocket) {
                runCatching { socket.close() }
            }
        }
//        } else {
//            Log.d(TAG, "Already connected locally, skipping BluetoothConnectionManager.aacpSocket? connection (isConnectedLocally = $isConnectedLocally, BluetoothConnectionManager.aacpSocket?.isConnected = ${this::BluetoothConnectionManager.aacpSocket?.isInitialized && BluetoothConnectionManager.aacpSocket?.isConnected})")
//        }
    }

    fun disconnectForCD() {
        suppressAutomaticReconnect()
        BluetoothConnectionManager.invalidateAacpSocket()
        runCatching { BluetoothConnectionManager.attSocket?.close() }
        BluetoothConnectionManager.attSocket = null
        aacpManager.disconnected()
        handleAacpDisconnectedState("cross_device_disconnect")
        MediaController.pausedWhileTakingOver = false
        Log.d(TAG, "Disconnected from AirPods, showing island.")
        showIsland(
            this,
            batteryNotification.getBattery()
                .find { it.component == BatteryComponent.LEFT }?.level!!.coerceAtMost(
                    batteryNotification.getBattery()
                        .find { it.component == BatteryComponent.RIGHT }?.level!!
                ),
            IslandType.MOVED_TO_REMOTE
        )
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        bluetoothAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    val connectedDevices = proxy.connectedDevices
                    if (connectedDevices.isNotEmpty()) {
                        MediaController.sendPause()
                    }
                }
                bluetoothAdapter.closeProfileProxy(profile, proxy)
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
//        isConnectedLocally = false
//        CrossDevice.isAvailable = true
    }

    fun disconnectAirPods() {
        suppressAutomaticReconnect()
        BluetoothConnectionManager.invalidateAacpSocket()
        runCatching { BluetoothConnectionManager.attSocket?.close() }
        BluetoothConnectionManager.attSocket = null
        aacpManager.disconnected()
        handleAacpDisconnectedState("explicit_user_disconnect")
        sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED).apply {
            putExtra(
                EXTRA_AACP_SOCKET_GENERATION,
                BluetoothConnectionManager.currentAacpSocketGeneration(),
            )
            setPackage(packageName)
        })
        MediaController.sendPause(force = true)
        disconnectAudio(this, device)
        if (Build.VERSION.SDK_INT >= 37) {
            runCatching { device?.disconnect() }
                .onFailure { Log.w(TAG, "device.disconnect() failed", it) }
        }
        Log.d(TAG, "Disconnected AirPods upon user request")
    }

    val earDetectionNotification = AirPodsNotifications.EarDetection()
    val ancNotification = AirPodsNotifications.ANC()
    val batteryNotification = AirPodsNotifications.BatteryNotification()
    val conversationAwarenessNotification =
        AirPodsNotifications.ConversationalAwarenessNotification()

    @Suppress("unused")
    fun setEarDetection(enabled: Boolean) {
        if (config.earDetectionEnabled != enabled) {
            config.earDetectionEnabled = enabled
            sharedPreferences.edit { putBoolean("automatic_ear_detection", enabled) }
        }
    }

    fun getBattery(): List<Battery> {
//        if (!isConnectedLocally && CrossDevice.isAvailable) {
//            batteryNotification.setBattery(CrossDevice.batteryBytes)
//        }
        return batteryNotification.getBattery()
    }

    fun getANC(): Int {
//        if (!isConnectedLocally && CrossDevice.isAvailable) {
//            ancNotification.setStatus(CrossDevice.ancBytes)
//        }
        return ancNotification.status
    }

    fun disconnectAudio(context: Context, device: BluetoothDevice?) {
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
        if (device == null) {
            Log.w(TAG, "Not disconnecting audio profiles: AirPods device is unavailable")
            return
        }
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) {
                        try {
                            if (proxy.getConnectionState(device) == BluetoothProfile.STATE_DISCONNECTED) {
                                Log.d(TAG, "Already disconnected from A2DP")
                                return
                            }
                            val disconnectMethod = proxy.javaClass.getMethod(
                                "disconnect",
                                BluetoothDevice::class.java
                            )
                            Log.d(TAG, "Requesting one-time A2DP disconnect for ${device.address}")
                            disconnectMethod.invoke(proxy, device)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not request one-time A2DP disconnect", e)
                        } finally {
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                        }
                    }
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.A2DP)
        } else {
            Log.d(TAG, "not disconnecting A2DP, no BLUETOOTH_PRIVILEGED permission")
        }
        if (checkSelfPermission("android.permission.MODIFY_PHONE_STATE") == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        try {
                            if (proxy.getConnectionState(device) == BluetoothProfile.STATE_DISCONNECTED) {
                                Log.d(TAG, "Already disconnected from HEADSET")
                                return
                            }
                            val disconnectMethod = proxy.javaClass.getMethod(
                                "disconnect",
                                BluetoothDevice::class.java
                            )
                            Log.d(TAG, "Requesting one-time HEADSET disconnect for ${device.address}")
                            disconnectMethod.invoke(proxy, device)
                        } catch (e: Exception) {
                            Log.w(TAG, "Could not request one-time HEADSET disconnect", e)
                        } finally {
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                        }
                    }
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.HEADSET)
        } else {
            Log.d(TAG, "not disconnecting HEADSET, no MODIFIY_PHONE_STATE permission")
        }
    }

    private fun requestActiveA2dpRoute(
        proxy: BluetoothProfile,
        target: BluetoothDevice,
        correlation: RoutingCorrelation,
    ): A2dpActivationResult {
        val currentActiveDevice = runCatching {
            proxy.javaClass.getMethod("getActiveDevice").invoke(proxy) as? BluetoothDevice
        }.getOrNull()
        if (currentActiveDevice?.address.equals(target.address, ignoreCase = true)) {
            confirmedActiveA2dpAddress = target.address
            return A2dpActivationResult.CONFIRMED
        }

        return runCatching {
            val method = proxy.javaClass.getMethod("setActiveDevice", BluetoothDevice::class.java)
            val result = method.invoke(proxy, target)
            val accepted = result !is Boolean || result
            RoutingTrace.record(correlation) {
                RoutingEventDetail.ActionResult(
                    action = RoutingAction.A2DP_CONNECT,
                    outcome = if (accepted) "requested" else "failed",
                    reason = "set_active_media_device",
                )
            }
            if (accepted) {
                A2dpActivationResult.REQUESTED
            } else {
                A2dpActivationResult.FAILED
            }
        }.onFailure { error ->
            RoutingTrace.recordError(
                "a2dp_route",
                "set_active_device_failed",
                error,
                correlation,
            )
            Log.w(TAG, "Could not make AirPods the active A2DP media device", error)
        }.getOrDefault(A2dpActivationResult.FAILED)
    }

    private fun scheduleA2dpActivationTimeout(
        correlation: RoutingCorrelation,
        reason: String,
    ) {
        val expectedAttemptId = correlation.takeoverAttemptId ?: return
        val expectedGeneration = armA2dpActivationTimeout()
        Handler(Looper.getMainLooper()).postDelayed({
            if (!claimA2dpActivationTimeout(expectedAttemptId, expectedGeneration)) {
                return@postDelayed
            }
            RoutingTrace.record(correlation) {
                RoutingEventDetail.ActionResult(
                    action = RoutingAction.A2DP_CONNECT,
                    outcome = "failed",
                    reason = reason,
                )
            }
            Log.w(TAG, "A2DP active-device confirmation missing; ending automatic takeover")
            MediaController.onTakeoverFailed(
                "a2dp_active_device_unconfirmed",
                resumeIfStillActive = false,
            )
        }, A2DP_ACTIVE_ROUTE_CONFIRMATION_TIMEOUT_MS)
    }

    fun connectAudio(
        context: Context,
        device: BluetoothDevice?,
        requestConnection: Boolean = true,
        requestGuard: (() -> Boolean)? = null,
        activateForMedia: Boolean = false,
    ) {
        val lifecycleGeneration = automaticReconnectGeneration
        val effectiveGuard = {
            !automaticReconnectSuppressed &&
                automaticReconnectGeneration == lifecycleGeneration &&
                requestGuard?.invoke() != false
        }
        if (!effectiveGuard()) return
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter
        val correlation = MediaController.currentTakeoverCorrelation()
        if (device == null) {
            RoutingTrace.record(correlation) {
                RoutingEventDetail.ActionResult(
                    action = RoutingAction.A2DP_CONNECT,
                    outcome = "skipped",
                    reason = "target_device_unavailable"
                )
            }
            return
        }
        val a2dpObservationGeneration = beginA2dpConnectionObservation()

        val a2dpProxyRequested = bluetoothAdapter?.getProfileProxy(
            context,
            object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.A2DP) {
                    if (!effectiveGuard()) {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                        return
                    }
                    val connectionState = proxy.getConnectionState(device)
                    val targetConnected = connectionState == BluetoothProfile.STATE_CONNECTED
                    val observationApplied = applyA2dpConnectionObservation(
                        expectedToken = a2dpObservationGeneration,
                        connected = targetConnected,
                    )
                    val activationResult = if (activateForMedia && targetConnected &&
                        isA2dpBroadcastGenerationCurrent(a2dpObservationGeneration) &&
                        pendingTargetRouteActivationAttemptId == correlation.takeoverAttemptId &&
                        MediaController.isCurrentTakeoverAttempt(correlation)
                    ) {
                        requestActiveA2dpRoute(proxy, device, correlation).also { result ->
                            if (result == A2dpActivationResult.CONFIRMED) {
                                invalidateA2dpActivationTimeout()
                            } else {
                                scheduleA2dpActivationTimeout(
                                    correlation,
                                    reason = if (result == A2dpActivationResult.REQUESTED) {
                                        "active_device_broadcast_timeout"
                                    } else {
                                        "active_device_request_failed"
                                    },
                                )
                            }
                        }
                    } else {
                        null
                    }
                    if (observationApplied || activationResult == A2dpActivationResult.CONFIRMED) {
                        evaluateTakeoverRoute("a2dp_profile_proxy_connected")
                        synchronized(earDetectionReceiverLock) {
                            requestEarDetectionResumeIfA2dpReadyLocked()
                        }
                    }
                    if (context.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED) {
                        try {
                            val policyMethod = proxy.javaClass.getMethod(
                                "setConnectionPolicy",
                                BluetoothDevice::class.java,
                                Int::class.java
                            )
                            Log.d(TAG, "Keeping A2DP connection policy allowed for ${device.address}")
                            policyMethod.invoke(
                                proxy,
                                device,
                                CONNECTION_POLICY_ALLOWED
                            )

                            if (!effectiveGuard()) return
                            if (!requestConnection) {
                                RoutingTrace.record(correlation) {
                                    RoutingEventDetail.ActionResult(
                                        action = RoutingAction.A2DP_CONNECT,
                                        outcome = "skipped",
                                        reason = "connection_policy_repaired_only"
                                    )
                                }
                                return
                            }
                            if (connectionState == BluetoothProfile.STATE_CONNECTED ||
                                connectionState == BluetoothProfile.STATE_CONNECTING
                            ) {
                                RoutingTrace.record(correlation) {
                                    RoutingEventDetail.ActionResult(
                                        action = RoutingAction.A2DP_CONNECT,
                                        outcome = "skipped",
                                        reason = if (connectionState == BluetoothProfile.STATE_CONNECTED) {
                                            "profile_already_connected"
                                        } else {
                                            "profile_connection_in_progress"
                                        }
                                    )
                                }
                                return
                            }
                            val connectMethod =
                                proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                            connectMethod.invoke(
                                proxy, device
                            )
                            RoutingTrace.record(correlation) {
                                RoutingEventDetail.ActionResult(
                                    action = RoutingAction.A2DP_CONNECT,
                                    outcome = "requested",
                                    reason = "privileged_profile_connect_invoked"
                                )
                            }
                        } catch (e: Exception) {
                            RoutingTrace.recordError(
                                "a2dp_connect",
                                "privileged_reflection_failed",
                                e,
                                correlation
                            )
                            Log.e(TAG, "Could not request privileged A2DP connection", e)
                        } finally {
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                        }
                    }
                    else {
                        if (!effectiveGuard()) {
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                            return
                        }
                        if (!requestConnection) {
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                            return
                        }
                        if (connectionState == BluetoothProfile.STATE_CONNECTED ||
                            connectionState == BluetoothProfile.STATE_CONNECTING
                        ) {
                            RoutingTrace.record(correlation) {
                                RoutingEventDetail.ActionResult(
                                    action = RoutingAction.A2DP_CONNECT,
                                    outcome = "skipped",
                                    reason = "profile_already_connected_without_privileged_policy"
                                )
                            }
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                            return
                        }
                        runCatching {
                            val connectMethod = proxy.javaClass.getMethod(
                                "connect",
                                BluetoothDevice::class.java
                            )
                            connectMethod.invoke(proxy, device)
                        }.onSuccess {
                            RoutingTrace.record(correlation) {
                                RoutingEventDetail.ActionResult(
                                    action = RoutingAction.A2DP_CONNECT,
                                    outcome = "requested",
                                    reason = "unprivileged_profile_connect_invoked"
                                )
                            }
                        }.onFailure { error ->
                            RoutingTrace.recordError(
                                "a2dp_connect",
                                "unprivileged_reflection_failed",
                                error,
                                correlation
                            )
                        }
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP) ?: false
        if (!a2dpProxyRequested && activateForMedia) {
            scheduleA2dpActivationTimeout(correlation, "a2dp_profile_proxy_unavailable")
        }

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile == BluetoothProfile.HEADSET) {
                    if (!effectiveGuard()) {
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                        return
                    }
                    if (checkSelfPermission("android.permission.MODIFY_PHONE_STATE") == PackageManager.PERMISSION_GRANTED) {
                        try {
                            val connectionState = proxy.getConnectionState(device)
                            val policyMethod = proxy.javaClass.getMethod(
                                "setConnectionPolicy",
                                BluetoothDevice::class.java,
                                Int::class.java
                            )
                            Log.d(
                                TAG,
                                "Keeping HEADSET connection policy allowed for ${device.address}"
                            )
                            policyMethod.invoke(
                                proxy,
                                device,
                                CONNECTION_POLICY_ALLOWED
                            )
                            if (!effectiveGuard()) return
                            if (!requestConnection) {
                                return
                            }
                            if (connectionState == BluetoothProfile.STATE_CONNECTED ||
                                connectionState == BluetoothProfile.STATE_CONNECTING
                            ) {
                                Log.d(TAG, "HEADSET profile is already connected or connecting")
                                return
                            }
                            val connectMethod =
                                proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                            connectMethod.invoke(proxy, device)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        } finally {
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                        }
                    } else {
                        Log.d(TAG, "not setting connection policy for HEADSET, no MODIFIY_PHONE_STATE permission")
                        bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                    }
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.HEADSET)
    }

    fun setName(name: String) {
        aacpManager.sendRename(name)

        if (config.deviceName != name) {
            config.deviceName = name
            device?.alias = name
            sharedPreferences.edit { putString("name", name) }
        }

        updateNotificationContent(true, name, batteryNotification.getBattery())
        Log.d(TAG, "setName: $name")
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        Log.d(TAG, "Service stopped is being destroyed for some reason!")
        cancelTakeoverSourceRetries()
        invalidateActiveTakeoverPacketPermit()
        suppressAutomaticReconnect()
        BluetoothConnectionManager.invalidateAacpSocket()
        runCatching { BluetoothConnectionManager.attSocket?.close() }
        BluetoothConnectionManager.attSocket = null
        aacpManager.disconnected()
        invalidateEarDetectionA2dpReceiver()

        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        RoutingTrace.setBaselineProvider(null)
        MediaController.shutdown()
        PhoneSoundRoutingController.shutdown()
        ServiceManager.setService(null)

        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(externalBroadcastReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(connectionReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            unregisterReceiver(earReceiver)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            bleManager.stopScanning()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        if (checkSelfPermission("android.permission.READ_PHONE_STATE") == PackageManager.PERMISSION_GRANTED) {
            telephonyManager.unregisterTelephonyCallback(phoneStateListener)
        }
        audioFeatureScope.cancel()
        spatialHeadTrackerBridge.stop()
        rootAvrcpVolumeController.cancel()
//        isConnectedLocally = false
//        CrossDevice.isAvailable = true
        super.onDestroy()
    }

    var isHeadTrackingActive = false

    @SuppressLint("MissingPermission")
    private fun refreshCurrentA2dpPlaybackState(reason: String) {
        Log.d(TAG, "Querying current AirPods A2DP playback state: $reason")
        val a2dpObservationGeneration = beginA2dpConnectionObservation()
        val savedMac = macAddress.takeIf { it.isNotBlank() }
            ?: sharedPreferences.getString("mac_address", null)
        if (savedMac.isNullOrBlank()) {
            applyA2dpConnectionObservation(a2dpObservationGeneration, connected = false)
            isAirPodsA2dpPlaying = false
            updateSpatialAudioTracking("$reason; no saved AirPods")
            return
        }

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        val target = try {
            bluetoothAdapter.getRemoteDevice(savedMac)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Cannot query A2DP playback for invalid address $savedMac", error)
            applyA2dpConnectionObservation(a2dpObservationGeneration, connected = false)
            isAirPodsA2dpPlaying = false
            updateSpatialAudioTracking("$reason; invalid AirPods address")
            return
        }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.A2DP) return
                val a2dp = proxy as BluetoothA2dp
                val targetPlaying = try {
                    a2dp.isA2dpPlaying(target)
                } catch (error: Exception) {
                    Log.w(TAG, "BluetoothA2dp.isA2dpPlaying unavailable", error)
                    false
                }
                val targetConnected = a2dp.getConnectionState(target) ==
                    BluetoothProfile.STATE_CONNECTED
                bluetoothAdapter.closeProfileProxy(profile, proxy)

                Handler(Looper.getMainLooper()).post {
                    if (!applyA2dpConnectionObservation(
                            expectedToken = a2dpObservationGeneration,
                            connected = targetConnected,
                        )
                    ) {
                        Log.d(TAG, "Ignoring stale A2DP profile query: $reason")
                        return@post
                    }
                    isAirPodsA2dpPlaying = targetPlaying
                    Log.i(
                        TAG,
                        "Current AirPods A2DP playing=$targetPlaying " +
                            "(connected=$targetConnected): $reason"
                    )
                    evaluateTakeoverRoute("a2dp_profile_query")
                    synchronized(earDetectionReceiverLock) {
                        requestEarDetectionResumeIfA2dpReadyLocked()
                    }
                    updateSpatialAudioTracking(reason)
                }
            }

            override fun onServiceDisconnected(profile: Int) = Unit
        }

        if (!bluetoothAdapter.getProfileProxy(this, listener, BluetoothProfile.A2DP)) {
            Log.w(TAG, "Could not obtain A2DP profile proxy: $reason")
            updateSpatialAudioTracking("$reason; A2DP proxy unavailable")
        }
    }

    private fun shouldRunSpatialAudio(): Boolean {
        return SpatialAudioMode.fromPreferences(sharedPreferences) ==
            SpatialAudioMode.HEAD_TRACKED &&
            isAirPodsA2dpPlaying &&
            ::aacpManager.isInitialized &&
            aacpManager.owns &&
            BluetoothConnectionManager.aacpSocket?.isConnected == true
    }

    private fun scheduleAirPodsAbsoluteVolumeResync(reason: String, delayMs: Long) {
        if (SystemClock.elapsedRealtime() - lastA2dpVolumeResyncAt <
            A2DP_VOLUME_RESYNC_DEBOUNCE_MS
        ) {
            Log.d(TAG, "Skipping recently completed A2DP volume resync: $reason")
            return
        }
        val generation = ++a2dpVolumeResyncGeneration
        Handler(Looper.getMainLooper()).postDelayed({
            if (generation != a2dpVolumeResyncGeneration) {
                Log.d(TAG, "Skipping superseded A2DP volume resync: $reason")
                return@postDelayed
            }
            resendAirPodsAbsoluteVolume(reason, generation)
        }, delayMs)
    }

    private fun cancelPendingAirPodsAbsoluteVolumeResync(reason: String) {
        a2dpVolumeResyncGeneration++
        Log.d(TAG, "Cancelled pending A2DP volume resync: $reason")
    }

    @SuppressLint("MissingPermission", "SoonBlockedPrivateApi")
    private fun resendAirPodsAbsoluteVolume(reason: String, generation: Int) {
        if (!isAirPodsA2dpPlaying || !aacpManager.owns) {
            Log.d(TAG, "Skipping A2DP volume resync while not playing: $reason")
            return
        }

        val target = device
        if (target == null) {
            Log.w(TAG, "Cannot resend A2DP volume without an AirPods device: $reason")
            return
        }
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.d(TAG, "Using root for AirPods volume resync; app is not privileged: $reason")
            resendAirPodsAbsoluteVolumeAsRoot(target, reason, generation)
            return
        }
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.A2DP) return
                val a2dp = proxy as BluetoothA2dp
                try {
                    if (generation != a2dpVolumeResyncGeneration ||
                        !isAirPodsA2dpPlaying ||
                        a2dp.getConnectionState(target) != BluetoothProfile.STATE_CONNECTED
                    ) {
                        Log.d(TAG, "Skipping stale A2DP volume resend: $reason")
                        return
                    }

                    val (pulseVolume, volume) = currentA2dpVolumePulse(direct = true)
                    val method = BluetoothA2dp::class.java.getDeclaredMethod(
                        "setAvrcpAbsoluteVolume",
                        Int::class.javaPrimitiveType
                    )
                    method.isAccessible = true
                    // Despite the public method name/docs, Android's audio
                    // service supplies the active device-volume index here.
                    // HyperOS then maps its 0..150 index to AVRCP's 0..127.
                    method.invoke(a2dp, pulseVolume)
                    method.invoke(a2dp, volume)
                    lastA2dpVolumeResyncAt = SystemClock.elapsedRealtime()
                    Log.i(
                        TAG,
                        "Directly pulsed AirPods device volume=$pulseVolume->$volume: $reason"
                    )
                } catch (error: Exception) {
                    val cause = error.cause ?: error
                    Log.w(
                        TAG,
                        "Direct AirPods AVRCP volume resend failed; trying root: $reason",
                        cause
                    )
                    resendAirPodsAbsoluteVolumeAsRoot(target, reason, generation)
                } finally {
                    bluetoothAdapter.closeProfileProxy(profile, proxy)
                }
            }

            override fun onServiceDisconnected(profile: Int) = Unit
        }

        if (!bluetoothAdapter.getProfileProxy(this, listener, BluetoothProfile.A2DP)) {
            Log.w(TAG, "Could not obtain A2DP proxy for volume resend: $reason")
            resendAirPodsAbsoluteVolumeAsRoot(target, reason, generation)
        }
    }

    private fun resendAirPodsAbsoluteVolumeAsRoot(
        target: BluetoothDevice,
        reason: String,
        generation: Int
    ) {
        if (generation != a2dpVolumeResyncGeneration ||
            !isAirPodsA2dpPlaying || !aacpManager.owns
        ) return
        val (pulseVolume, volume) = currentA2dpVolumePulse(direct = true)
        audioFeatureScope.launch(Dispatchers.IO) {
            val result = rootAvrcpVolumeController.resend(
                target.address,
                pulseVolume,
                volume
            )
            withContext(Dispatchers.Main) {
                if (result.success) {
                    Log.i(
                        TAG,
                        "Root pulsed AirPods device volume=$pulseVolume->$volume: $reason"
                    )
                    lastA2dpVolumeResyncAt = SystemClock.elapsedRealtime()
                    prewarmAirPodsAbsoluteVolumeResync("root volume resync complete")
                } else {
                    Log.w(TAG, "Root AirPods AVRCP resend failed: ${result.output}")
                    if (generation == a2dpVolumeResyncGeneration) {
                        pulseAirPodsAbsoluteVolume(reason)
                    }
                }
            }
        }
    }

    private fun prewarmAirPodsAbsoluteVolumeResync(reason: String) {
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") ==
            PackageManager.PERMISSION_GRANTED
        ) return
        val target = device ?: return
        audioFeatureScope.launch(Dispatchers.IO) {
            val result = rootAvrcpVolumeController.prewarm(target.address)
            if (result.success) {
                Log.i(TAG, "Prewarmed root AirPods volume bridge: $reason")
            } else {
                Log.w(TAG, "Could not prewarm root AirPods volume bridge: ${result.output}")
            }
        }
    }

    private fun pulseAirPodsAbsoluteVolume(reason: String) {
        if (!isAirPodsA2dpPlaying || !aacpManager.owns) {
            Log.d(TAG, "Skipping A2DP volume pulse while not playing: $reason")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastA2dpVolumeResyncAt < A2DP_VOLUME_RESYNC_DEBOUNCE_MS) {
            Log.d(TAG, "Skipping duplicate A2DP volume pulse: $reason")
            return
        }

        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        // AirPods/HyperOS quantizes AVRCP volume to roughly 15 steps. A same-
        // index write, or a one-unit change on fine-volume devices, is filtered
        // before it reaches the earbuds. Pulse exactly one effective step and
        // restore the cached phone value after the first command is delivered.
        val (pulseVolume, volume) = currentA2dpVolumePulse(direct = false)
        lastA2dpVolumeResyncAt = now
        if (pulseVolume == volume) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
            Log.i(TAG, "Resent AirPods A2DP volume=$volume: $reason")
            return
        }

        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, pulseVolume, 0)
        Handler(Looper.getMainLooper()).postDelayed({
            // Do not overwrite a real user adjustment made during the pulse.
            if (audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) == pulseVolume) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                Log.i(
                    TAG,
                    "Restored AirPods A2DP volume=$volume after pulse=$pulseVolume: $reason"
                )
            } else {
                Log.i(TAG, "User changed volume during A2DP resync; not restoring $volume")
            }
        }, A2DP_VOLUME_PULSE_MS)
        Log.i(TAG, "Pulsed AirPods A2DP volume=$pulseVolume from $volume: $reason")
    }

    private fun calculateA2dpVolumePulse(
        volume: Int,
        minimum: Int,
        maximum: Int
    ): Int {
        // AirPods/HyperOS quantizes absolute volume to roughly 15 steps.
        // Move one effective step so the Bluetooth cache cannot discard the
        // following restore as a same-value write.
        val step = ((maximum - minimum) / 15).coerceAtLeast(1)
        return if (volume + step <= maximum) {
            volume + step
        } else {
            (volume - step).coerceAtLeast(minimum)
        }
    }

    private fun currentA2dpVolumePulse(direct: Boolean): Pair<Int, Int> {
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val minimum = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val pulse = if (direct) {
            calculateDirectA2dpVolumePulse(volume, minimum, maximum)
        } else {
            calculateA2dpVolumePulse(volume, minimum, maximum)
        }
        return pulse to volume
    }

    private fun calculateDirectA2dpVolumePulse(
        volume: Int,
        minimum: Int,
        maximum: Int
    ): Int {
        val range = maximum - minimum
        if (range <= 0) return volume
        fun toAvrcp(deviceVolume: Int): Int {
            val normalized = (deviceVolume - minimum).coerceIn(0, range)
            return ((normalized.toLong() * 127L + range / 2L) / range).toInt()
        }

        val currentAvrcp = toAvrcp(volume)
        for (candidate in (volume + 1)..maximum) {
            if (toAvrcp(candidate) != currentAvrcp) return candidate
        }
        for (candidate in (volume - 1) downTo minimum) {
            if (toAvrcp(candidate) != currentAvrcp) return candidate
        }
        return volume
    }

    private fun updateSpatialAudioTracking(reason: String) {
        val shouldRun = shouldRunSpatialAudio()
        val transitionId = ++spatialAudioTransitionId
        if (shouldRun) {
            Log.i(TAG, "Starting spatial tracking: $reason")
            // Android can select a head-tracking mode only after the matching
            // dynamic sensor exists, so create UHID before changing the mode.
            startHeadTracking()
            audioFeatureScope.launch(Dispatchers.IO) {
                spatialAudioTransitionMutex.withLock {
                    if (transitionId != spatialAudioTransitionId) return@withLock
                    var state = spatialAudioController.setMode(SpatialAudioMode.HEAD_TRACKED)
                    if (state.error == null && state.actualMode != 1 &&
                        transitionId == spatialAudioTransitionId && shouldRunSpatialAudio()
                    ) {
                        // HyperOS can keep a previously registered dynamic
                        // head tracker stuck in DISABLED even though it reports
                        // available. Recreating the UHID device makes the native
                        // Spatializer bind the new sensor handle and apply the
                        // already-requested RELATIVE_WORLD mode.
                        Log.w(
                            TAG,
                            "Spatializer actual mode stayed ${state.actualMode}; " +
                                "recreating the head tracker"
                        )
                        withContext(Dispatchers.Main) {
                            spatialHeadTrackerBridge.stop()
                            HeadTracking.reset()
                        }
                        // DynamicSensorManager removes UHID sensors
                        // asynchronously. Starting the replacement too soon
                        // can make it overlap the old device with the same UUID.
                        delay(1500)
                        if (transitionId != spatialAudioTransitionId ||
                            !shouldRunSpatialAudio()
                        ) {
                            return@withLock
                        }
                        withContext(Dispatchers.Main) {
                            startHeadTracking()
                        }
                        // Give the HID raw sensor service time to publish the
                        // replacement handle before asking AudioService again.
                        delay(2500)
                        if (transitionId != spatialAudioTransitionId ||
                            !shouldRunSpatialAudio()
                        ) {
                            return@withLock
                        }
                        state = spatialAudioController.setMode(SpatialAudioMode.HEAD_TRACKED)
                    }
                    Log.i(
                        TAG,
                        "Spatializer mode for '$reason': desired=${state.desiredMode}, " +
                            "actual=${state.actualMode}, error=${state.error}"
                    )
                }
            }
        } else {
            Log.i(TAG, "Stopping spatial tracking: $reason")
            // Keep UHID alive until AudioService confirms the disabled mode;
            // otherwise some vendor implementations retain a stale actual=1.
            audioFeatureScope.launch(Dispatchers.IO) {
                spatialAudioTransitionMutex.withLock {
                    if (transitionId != spatialAudioTransitionId) return@withLock
                    val selectedMode = SpatialAudioMode.fromPreferences(sharedPreferences)
                    val inactiveMode = if (selectedMode == SpatialAudioMode.OFF) {
                        SpatialAudioMode.OFF
                    } else {
                        SpatialAudioMode.FIXED
                    }
                    val state = spatialAudioController.setMode(inactiveMode)
                    Log.i(
                        TAG,
                        "Spatializer mode=$inactiveMode for '$reason': " +
                            "enabled=${state.spatializerEnabled}, desired=${state.desiredMode}, " +
                            "actual=${state.actualMode}, error=${state.error}"
                    )
                    withContext(Dispatchers.Main) {
                        if (transitionId == spatialAudioTransitionId && !shouldRunSpatialAudio()) {
                            stopHeadTracking(force = true)
                        }
                    }
                }
            }
        }
    }

    fun startHeadTracking() {
        isHeadTrackingActive = true
        val useAlternatePackets =
            sharedPreferences.getBoolean("use_alternate_head_tracking_packets", true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && aacpManager.getControlCommandStatus(
                AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION
            )?.value?.get(0)?.toInt() != 1
        ) {
            Log.w(TAG, "Not taking over ownership for head tracking; tracking might not work")
        } else {
            Log.d(TAG, "Already own the connection; starting head tracking")
        }
        if (useAlternatePackets) {
            aacpManager.sendDataPacket(aacpManager.createAlternateStartHeadTrackingPacket())
        } else {
            aacpManager.sendStartHeadTracking()
        }
        HeadTracking.reset()
        if (shouldRunSpatialAudio()) {
            val trackerMac = device?.address
                ?: macAddress.takeIf { it.isNotBlank() }
                ?: sharedPreferences.getString("mac_address", null)
            if (trackerMac == null) {
                Log.e(TAG, "Cannot start spatial head tracker: no AirPods Bluetooth address")
            } else {
                Log.d(TAG, "Starting spatial head tracker for $trackerMac")
                spatialHeadTrackerBridge.start(trackerMac)
            }
        }
    }

    fun stopHeadTracking(force: Boolean = false) {
        if (!force && shouldRunSpatialAudio()) {
            Log.d(TAG, "Keeping head tracking active for spatial audio")
            return
        }
        val useAlternatePackets =
            sharedPreferences.getBoolean("use_alternate_head_tracking_packets", true)
        if (useAlternatePackets) {
            aacpManager.sendDataPacket(aacpManager.createAlternateStopHeadTrackingPacket())
        } else {
            aacpManager.sendStopHeadTracking()
        }
        isHeadTrackingActive = false
        spatialHeadTrackerBridge.stop()
        gestureDetector?.stopDetection(doNotStop = true)
    }

    @SuppressLint("MissingPermission")
    fun reconnectFromSavedMac() {
        allowAutomaticReconnect()
        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        device = bluetoothAdapter.bondedDevices.find {
            it.address == macAddress
        }
        if (device != null) {
            CoroutineScope(Dispatchers.IO).launch {
                Log.d(TAG, "connecting to $macAddress")
                connectToSocket(bluetoothAdapter, device!!, manual = true)
                connectAudio(this@AirPodsService, device!!)
            }
        }
    }
}

private fun Int.dpToPx(): Int {
    val density = Resources.getSystem().displayMetrics.density
    return (this * density).toInt()
}

fun getNextMode(currentMode: Int, configByte: Int, offmodeEnabled: Boolean): Int {
    val enabledModes = buildList {
        if ((configByte and 0x01) != 0 && offmodeEnabled) add(1)
        if ((configByte and 0x04) != 0) add(3)
        if ((configByte and 0x08) != 0) add(4)
        if ((configByte and 0x02) != 0) add(2)
    }
    Log.d(TAG, "currentMode: $currentMode, config: ${configByte.toString(2)}")

    if (enabledModes.isEmpty()) return currentMode

    val currentIndex = enabledModes.indexOf(currentMode)
    val nextIndex = if (currentIndex == -1) 0 else (currentIndex + 1) % enabledModes.size

    return enabledModes[nextIndex]
}
