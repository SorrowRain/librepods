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
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.MainActivity
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.receivers.AudioHandoffActionReceiver
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.StemPressType
import me.kavishdevar.librepods.bluetooth.AacpSocketLease
import me.kavishdevar.librepods.bluetooth.AudioProfileRepairFence
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.bluetooth.ATTManagerv2
import me.kavishdevar.librepods.bluetooth.BLEManager
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import me.kavishdevar.librepods.bluetooth.HeartRateAacpTransport
import me.kavishdevar.librepods.bluetooth.LeaseFencedRtBuddyHeartRateDecoder
import me.kavishdevar.librepods.bluetooth.PendingSocketWatchdog
import me.kavishdevar.librepods.bluetooth.ReconnectEpisodeGate
import me.kavishdevar.librepods.bluetooth.ReconnectLifecycleBegin
import me.kavishdevar.librepods.bluetooth.ReconnectLifecycleGate
import me.kavishdevar.librepods.bluetooth.ReconnectLifecycleToken
import me.kavishdevar.librepods.bluetooth.RtBuddyHeartRateDecoder
import me.kavishdevar.librepods.bluetooth.createBluetoothSocket
import me.kavishdevar.librepods.data.AirPodsInstance
import me.kavishdevar.librepods.data.AirPodsModels
import me.kavishdevar.librepods.data.AirPodsNotifications
import me.kavishdevar.librepods.data.Battery
import me.kavishdevar.librepods.data.BatteryComponent
import me.kavishdevar.librepods.data.BatteryStatus
import me.kavishdevar.librepods.data.Capability
import me.kavishdevar.librepods.data.CustomEq
import me.kavishdevar.librepods.data.GuardHealth
import me.kavishdevar.librepods.data.GuardHealthLevel
import me.kavishdevar.librepods.data.GuardHealthReason
import me.kavishdevar.librepods.data.LocalHostIdentity
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.data.XposedRemotePrefProvider
import me.kavishdevar.librepods.data.isHeadTrackingData
import me.kavishdevar.librepods.heart.HeadTrackingRequestSource
import me.kavishdevar.librepods.heart.HeartRateMeasurement
import me.kavishdevar.librepods.heart.HeartRateRuntimeCoordinator
import me.kavishdevar.librepods.heart.HeartRateRuntimeSink
import me.kavishdevar.librepods.heart.HeartRateSessionStatus
import me.kavishdevar.librepods.heart.HeartRateTransportRecoveryReason
import me.kavishdevar.librepods.heart.HeartRateUiState
import me.kavishdevar.librepods.heart.HeartRateWearState
import me.kavishdevar.librepods.heart.HrmAcquisitionToken
import me.kavishdevar.librepods.heart.HrmSessionKey
import me.kavishdevar.librepods.heart.OneShotSensorCleanup
import me.kavishdevar.librepods.heart.SensorResourceOwner
import me.kavishdevar.librepods.health.HealthConnectExportState
import me.kavishdevar.librepods.health.HealthConnectHeartRateExporter
import me.kavishdevar.librepods.health.HeartRateExportAcquisitionIdentity
import me.kavishdevar.librepods.presentation.overlays.IslandWindow
import me.kavishdevar.librepods.presentation.overlays.PopupWindow
import me.kavishdevar.librepods.presentation.widgets.BatteryWidget
import me.kavishdevar.librepods.presentation.widgets.NoiseControlWidget
import me.kavishdevar.librepods.utils.GestureDetector
import me.kavishdevar.librepods.utils.HeadTracking
import me.kavishdevar.librepods.utils.MediaController
import me.kavishdevar.librepods.audio.*
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
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "AirPodsService"
private const val A2DP_DIRECT_VOLUME_RESYNC_DELAY_MS = 350L
private const val A2DP_VOLUME_RESYNC_DEBOUNCE_MS = 1_000L
private const val A2DP_VOLUME_PULSE_MS = 180L
private const val AUTOMATIC_RECONNECT_RETRY_DELAY_MS = 1_500L
private const val AACP_CONNECT_TIMEOUT_MS = 5_000L
private const val HEART_RATE_TRANSPORT_RESET_QUIET_PERIOD_MS = 3_000L
private const val PROFILE_REPAIR_INITIAL_DELAY_MS = 1_500L
private const val PROFILE_REPAIR_RETRY_DELAY_MS = 4_000L
private const val FRESH_IN_EAR_BLE_WINDOW_MS = 15_000L
// Scheduling jitter is tens of milliseconds; anything past this means the process
// was not running when it should have been.
private const val MIUI_FOLD_TIMEOUT_EXTRA = "miui.fold.timeout"
private const val MIUI_FOLD_TIMEOUT_MS = 1_000L

private fun Notification.applyMiuiFoldTimeout(): Notification = apply {
    extras.putLong(MIUI_FOLD_TIMEOUT_EXTRA, MIUI_FOLD_TIMEOUT_MS)
}

object ServiceManager {
    private val _serviceFlow = MutableStateFlow<AirPodsService?>(null)

    /** Emits the live service instance so observers can re-bind after a restart. */
    val serviceFlow: StateFlow<AirPodsService?> get() = _serviceFlow

    fun getService(): AirPodsService? = _serviceFlow.value

    fun setService(service: AirPodsService?) {
        _serviceFlow.value = service
    }
}

/** Guard health of whichever service instance is currently alive. */
@OptIn(ExperimentalCoroutinesApi::class)
val guardHealthFlow: Flow<GuardHealth?> =
    ServiceManager.serviceFlow.flatMapLatest { service ->
        service?.guardHealth ?: flowOf(null)
    }

// @Suppress("unused")
class AirPodsService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {
    var macAddress = ""
    var localMac = ""
    lateinit var aacpManager: AACPManager
    lateinit var attManager: ATTManagerv2
    var airpodsInstance: AirPodsInstance? = null
    var cameraActive = false
    private var lastA2dpVolumeResyncAt = 0L
    private var a2dpVolumeResyncGeneration = 0
    private var earlyA2dpVolumeResyncInFlight = false
    private val spatialHeadTrackerBridge = RootHeadTrackerBridge()
    private val rootAvrcpVolumeController by lazy { RootAvrcpVolumeController(this) }
    private val spatialAudioController by lazy { RootSpatialAudioController(this) }
    private lateinit var audioManager: AudioManager
    private val audioFeatureScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val aacpReaderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** A blocked platform BluetoothSocket.connect() must not occupy the only reconnect worker. */
    private val aacpConnectWatchdogExecutor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "LibrePods-AACP-connect-watchdog").apply {
                isDaemon = true
            }
        }
    private val pendingAacpSocketWatchdog = PendingSocketWatchdog<BluetoothSocket>()
    private val heartRateScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO.limitedParallelism(1)
    )
    // Watchdogs, auto retry, and transport jobs must not share the single-thread heartRateScope:
    // a slow socket write would otherwise starve them. The coordinator mutex keeps state safe.
    private val heartRateWatchdogScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO
    )
    private val audioHandoffScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val heartRateTransport = HeartRateAacpTransport()
    private lateinit var heartRateRuntime: HeartRateRuntimeCoordinator
    private lateinit var heartRatePreferences: SharedPreferences
    private lateinit var healthConnectHeartRateExporter: HealthConnectHeartRateExporter
    private val _heartRateAutoRetryEnabled = MutableStateFlow(true)
    val heartRateAutoRetryEnabledState: StateFlow<Boolean> get() = _heartRateAutoRetryEnabled
    private val _heartRateRespectRemoteAudioGuard = MutableStateFlow(true)
    val heartRateRespectRemoteAudioGuardState: StateFlow<Boolean>
        get() = _heartRateRespectRemoteAudioGuard
    @Volatile
    private var heartRateSessionKey: HrmSessionKey? = null
    @Volatile
    private var replayedControlConfigLease: AacpSocketLease? = null
    @Volatile
    private var aacpDeviceInformationReceived = false
    private val heartRateRecoveryLock = Any()
    private var heartRateRecoveryJob: Job? = null
    private var heartRateRecoveryAcquisition: HrmAcquisitionToken? = null
    private var heartRateRecoveryRevision = 0L
    private data class ConnectionNotificationContent(
        val title: String?,
        val text: String,
        val guardLine: String?,
        val switching: Boolean,
    )
    private val connectionNotificationLock = Any()
    private var lastConnectionNotificationContent: ConnectionNotificationContent? = null
    private lateinit var audioHandoff: AudioHandoffCoordinator
    private lateinit var handoffPlatform: AndroidAudioPlatform
    private lateinit var handoffConnection: AirPodsConnectionController
    private lateinit var playbackObserver: LocalPlaybackObserver
    private var handoffDiagnosticReceiver: BroadcastReceiver? = null
    val audioHandoffState: StateFlow<AudioHandoffState> get() = audioHandoff.state
    @Volatile
    private var audioProfileRepairDeferred = false
    @Volatile
    private var serviceShuttingDown = false
    @Volatile
    private var pendingAacpSocket: BluetoothSocket? = null
    private val automaticMediaResumeAllowed: Boolean
        get() = ::audioHandoff.isInitialized && audioHandoff.state.value.allowsMediaResume
    private val connectionLifecycleLock = Any()
    private var aacpSessionJob: Job? = null
    private var aacpDelayedRefreshJob: Job? = null
    private val reconnectEpisodeGate = ReconnectEpisodeGate()
    private val reconnectLifecycleGate = ReconnectLifecycleGate()
    private val audioProfileRepairFence = AudioProfileRepairFence()
    @Volatile
    private var isAirPodsA2dpConnected = false
    @Volatile private var isAirPodsHfpConnected = false
    private fun audioProfilesConnected() = isAirPodsA2dpConnected && isAirPodsHfpConnected
    private var isAirPodsA2dpPlaying = false
    private var spatialAudioTransitionId = 0
    private val spatialAudioTransitionMutex = Mutex()

    data class ServiceConfig(
        var deviceName: String = "AirPods",
        var earDetectionEnabled: Boolean = true,
        var conversationalAwarenessPauseMusic: Boolean = false,
        var conversationalAwarenessBothPodsOnly: Boolean = false,
        var showPhoneBatteryInWidget: Boolean = true,
        var relativeConversationalAwarenessVolume: Boolean = true,
        var headGestures: Boolean = true,
        var disconnectWhenNotWearing: Boolean = false,
        var conversationalAwarenessVolume: Int = 43,
        var qsClickBehavior: String = "cycle",
        var bleOnlyMode: Boolean = false,

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

    private lateinit var sharedPreferencesLogs: SharedPreferences
    private lateinit var sharedPreferences: SharedPreferences
    private val packetLogKey = "packet_log"
    private val _packetLogsFlow = MutableStateFlow<Set<String>>(emptySet())
    val packetLogsFlow: StateFlow<Set<String>> get() = _packetLogsFlow
    private val _guardHealth = MutableStateFlow(GuardHealth())
    val guardHealth: StateFlow<GuardHealth> get() = _guardHealth
    @Volatile
    private var lastGuardNotificationLine: String? = null

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var phoneStateListener: TelephonyCallback
    private val maxLogEntries = 1000
    private val inMemoryLogs = mutableSetOf<String>()

    lateinit var bleManager: BLEManager

    companion object {
        private const val HEART_RATE_MONITORING_ENABLED = "heart_rate_monitoring_enabled"
        private const val HEART_RATE_AUTO_RETRY_ENABLED = "heart_rate_auto_retry_enabled"
        private const val HEART_RATE_RESPECT_REMOTE_AUDIO_GUARD =
            "heart_rate_respect_remote_audio_guard"

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
                    allowAfterExplicitDisconnect = true,
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
                    requestBoundedAudioProfileRepair(
                        reason = "ear_in_profile_missing",
                        target = resolveSavedAirPodsDevice(),
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
            // BLE scan gaps (batching pauses, pocket attenuation) can fake a disappearance
            // while the control link is healthy; resetting then would close an in-flight
            // AACP socket and flip the notification for a device that is still present.
            if (BluetoothConnectionManager.aacpSocket?.isConnected == true ||
                pendingAacpSocket != null
            ) {
                Log.d(TAG, "Ignoring BLE disappearance while AACP is connected or connecting")
                return
            }
            resetReconnectPresenceEpisode("ble_device_disappeared")
            updateNotificationContent(
                false
            )
        }
    }

    @SuppressLint("MissingPermission")
    private fun resolveSavedAirPodsDevice(): BluetoothDevice? {
        device?.let { return it }
        val savedMac = sharedPreferences.getString("mac_address", null)
            ?.takeIf(String::isNotBlank) ?: return null
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        return runCatching { adapter.getRemoteDevice(savedMac) }
            .onFailure { error ->
                Log.w(TAG, "Cannot resolve saved AirPods address for reconnect", error)
            }
            .getOrNull()
    }

    @SuppressLint("MissingPermission")
    private fun requestAutomaticReconnect(
        reason: String,
        ensureAudioProfiles: Boolean = true,
        oncePerPresenceEpisode: Boolean = false,
        boundedControlRepair: Boolean = false,
        allowAfterExplicitDisconnect: Boolean = false,
    ): Boolean {
        if (serviceShuttingDown) return false
        if (reconnectLifecycleGate.isSuppressed()) {
            if (!allowAfterExplicitDisconnect) {
                Log.d(TAG, "Automatic reconnect suppressed after explicit disconnect: $reason")
                return false
            }
            reconnectLifecycleGate.allow()
        }
        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
            if (ensureAudioProfiles) {
                requestBoundedAudioProfileRepair(
                    reason = "existing_aacp:$reason",
                    target = resolveSavedAirPodsDevice(),
                )
            }
            return true
        }

        val presencePermit = if (oncePerPresenceEpisode) {
            reconnectEpisodeGate.tryBeginInitialConnect().also { permit ->
                if (permit == null) {
                    Log.d(TAG, "Automatic reconnect already attempted for this presence episode")
                }
            } ?: return false
        } else {
            null
        }

        val lifecycleToken = when (
            val begin = reconnectLifecycleGate.begin(
                freshPresence = false
            )
        ) {
            ReconnectLifecycleBegin.Suppressed -> {
                Log.d(TAG, "Automatic reconnect suppressed after explicit disconnect: $reason")
                presencePermit?.let {
                    reconnectEpisodeGate.finishInitialConnect(it, succeeded = false)
                }
                return false
            }
            is ReconnectLifecycleBegin.InFlight -> {
                Log.d(TAG, "Automatic reconnect already in flight; coalescing $reason")
                presencePermit?.let {
                    reconnectEpisodeGate.finishInitialConnect(
                        it,
                        succeeded = BluetoothConnectionManager.aacpSocket?.isConnected == true,
                    )
                }
                if (ensureAudioProfiles) {
                    requestCoalescedAudioProfileUpgrade(
                        begin.token,
                        presencePermit?.generation,
                    )
                    return true
                }
                return true
            }
            is ReconnectLifecycleBegin.Started -> begin.token
        }
        val reconnectEpisodeGeneration = presencePermit?.generation
        val controlRepairPermit = if (boundedControlRepair) {
            reconnectEpisodeGate.tryBeginControlReconnect().also { permit ->
                if (permit == null) {
                    Log.d(TAG, "AACP reconnect already pending or budget exhausted: $reason")
                }
            } ?: run {
                presencePermit?.let {
                    reconnectEpisodeGate.finishInitialConnect(it, succeeded = false)
                }
                reconnectLifecycleGate.finish(lifecycleToken)
                return false
            }
        } else {
            null
        }

        aacpReaderScope.launch {
            var socketConnected = false
            // Only a permit that actually drove a connect attempt has earned a refund;
            // otherwise an already-healthy link would make attempts free and unbounded.
            var attemptedConnect = false
            try {
                if (!isAutomaticReconnectAllowed(lifecycleToken, reconnectEpisodeGeneration)) {
                    return@launch
                }
                val target = resolveSavedAirPodsDevice() ?: run {
                    Log.w(TAG, "Skipping automatic reconnect: no valid saved AirPods address")
                    return@launch
                }
                val adapter = getSystemService(BluetoothManager::class.java).adapter
                Log.i(TAG, "Attempting automatic AirPods reconnect: $reason")

                socketConnected = BluetoothConnectionManager.aacpSocket?.isConnected == true
                if (!socketConnected && isAutomaticReconnectAllowed(
                        lifecycleToken,
                        reconnectEpisodeGeneration,
                    )
                ) {
                    attemptedConnect = true
                    connectToSocket(
                        adapter,
                        target,
                        connectionGuard = {
                            isAutomaticReconnectAllowed(
                                lifecycleToken,
                                reconnectEpisodeGeneration,
                            )
                        },
                    )
                    socketConnected = BluetoothConnectionManager.aacpSocket?.isConnected == true
                }
                if (socketConnected && ensureAudioProfiles &&
                    isAutomaticReconnectAllowed(lifecycleToken, reconnectEpisodeGeneration)
                ) {
                    // The control channel is always worth restoring — it is how we learn who
                    // owns the audio. The audio profiles are not, while someone else has it.
                    requestBoundedAudioProfileRepair(
                        reason = "aacp_reconnected:$reason",
                        target = target,
                    )
                }
                if (!socketConnected &&
                    isAutomaticReconnectAllowed(lifecycleToken, reconnectEpisodeGeneration)
                ) {
                    Log.w(TAG, "Automatic AACP reconnect attempt failed: $reason")
                }
            } finally {
                presencePermit?.let {
                    reconnectEpisodeGate.finishInitialConnect(it, socketConnected)
                }
                controlRepairPermit?.let {
                    reconnectEpisodeGate.finishControlReconnect(
                        it,
                        succeeded = socketConnected && attemptedConnect,
                    )
                }
                reconnectLifecycleGate.finish(lifecycleToken)
            }
        }
        return true
    }

    private fun requestCoalescedAudioProfileUpgrade(
        lifecycleToken: ReconnectLifecycleToken,
        presenceEpisodeGeneration: Long?,
    ) {
        aacpReaderScope.launch {
            repeat(60) {
                if (!isAutomaticReconnectAllowed(lifecycleToken, presenceEpisodeGeneration)) {
                    return@launch
                }
                if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
                    val target = resolveSavedAirPodsDevice() ?: return@launch
                    Log.i(TAG, "Upgrading coalesced reconnect to include A2DP/HFP")
                    requestBoundedAudioProfileRepair(
                        reason = "coalesced_aacp_reconnect",
                        target = target,
                    )
                    return@launch
                }
                delay(200L)
            }
            Log.w(TAG, "Coalesced A2DP/HFP reconnect timed out waiting for AACP")
        }
    }

    private fun isAutomaticReconnectAllowed(
        lifecycleToken: ReconnectLifecycleToken,
        presenceEpisodeGeneration: Long? = null,
    ): Boolean =
        !serviceShuttingDown &&
            reconnectLifecycleGate.isCurrent(lifecycleToken) &&
            (presenceEpisodeGeneration == null ||
                reconnectEpisodeGate.isCurrent(presenceEpisodeGeneration))

    private fun resetReconnectPresenceEpisode(reason: String) {
        reconnectEpisodeGate.reset()
        reconnectLifecycleGate.invalidate()
        cancelPendingAacpConnection()
        Log.d(TAG, "Reset automatic reconnect presence episode: $reason")
    }

    private fun hasFreshInEarBlePresence(): Boolean {
        if (!this::bleManager.isInitialized) return false
        val status = bleManager.getMostRecentStatus() ?: return false
        val ageMs = System.currentTimeMillis() - status.lastSeen
        return ageMs in 0L..FRESH_IN_EAR_BLE_WINDOW_MS &&
            (status.isLeftInEar || status.isRightInEar)
    }

    /**
     * True while remote audio is reported or still unresolved, including evidence queued for
     * the guard consumer. Connecting our own profiles then could take the AirPods away from the
     * other host — the exact thing the guard exists to prevent, except initiated by us.
     */
    private fun remoteHostOwnsAudio(): Boolean = audioProfileRepairFence.isProtected ||
        (::audioHandoff.isInitialized && (audioHandoff.state.value.protectRemote ||
            audioHandoff.state.value.operation != null))

    private fun requestBoundedAudioProfileRepair(
        reason: String,
        target: BluetoothDevice?,
        delayMs: Long = PROFILE_REPAIR_INITIAL_DELAY_MS,
    ) {
        val lifecycleToken = reconnectLifecycleGate.currentToken()
        if (target == null || lifecycleToken == null || audioProfilesConnected() ||
            !hasFreshInEarBlePresence()) {
            return
        }
        if (remoteHostOwnsAudio()) {
            Log.i(
                TAG,
                "Deferring audio profile repair while another device owns the AirPods: $reason"
            )
            audioProfileRepairDeferred = true
            return
        }
        if (BluetoothConnectionManager.aacpSocket?.isConnected != true) {
            requestAutomaticReconnect(
                "profile_repair_control:$reason",
                ensureAudioProfiles = true,
                boundedControlRepair = true,
            )
            return
        }
        val permit = reconnectEpisodeGate.tryBeginProfileRepair() ?: run {
            Log.d(TAG, "Audio profile repair already pending or budget exhausted: $reason")
            return
        }
        Log.i(TAG, "Scheduling bounded audio profile repair ${permit.attempt}: $reason")
        aacpReaderScope.launch {
            var retryRequired = false
            // See requestAutomaticReconnect: refunds are for permits that did the work.
            var attemptedConnect = false
            try {
                if (delayMs > 0L) delay(delayMs)
                if (audioProfilesConnected() ||
                    remoteHostOwnsAudio() ||
                    !isAutomaticReconnectAllowed(lifecycleToken, permit.generation) ||
                    !hasFreshInEarBlePresence() ||
                    BluetoothConnectionManager.aacpSocket?.isConnected != true
                ) {
                    if (remoteHostOwnsAudio()) {
                        audioProfileRepairDeferred = true
                    }
                    if (BluetoothConnectionManager.aacpSocket?.isConnected != true) {
                        requestAutomaticReconnect(
                            "profile_repair_control:$reason",
                            ensureAudioProfiles = true,
                            boundedControlRepair = true,
                        )
                    }
                    return@launch
                }
                attemptedConnect = true
                connectAudio(
                    this@AirPodsService,
                    target,
                    requestGuard = {
                        !remoteHostOwnsAudio() &&
                            isAutomaticReconnectAllowed(lifecycleToken, permit.generation) &&
                            hasFreshInEarBlePresence() &&
                            BluetoothConnectionManager.aacpSocket?.isConnected == true
                    },
                )
                if (permit.attempt == 1) {
                    delay(PROFILE_REPAIR_RETRY_DELAY_MS)
                    retryRequired = !audioProfilesConnected() &&
                        isAutomaticReconnectAllowed(lifecycleToken, permit.generation) &&
                        hasFreshInEarBlePresence()
                }
            } finally {
                reconnectEpisodeGate.finishProfileRepair(
                    permit,
                    succeeded = audioProfilesConnected() && attemptedConnect,
                )
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
        invalidateHeartRateTransportRecovery("automatic reconnect suppressed")
        reconnectLifecycleGate.suppress()
        cancelPendingAacpConnection()
    }

    private fun allowAutomaticReconnect() {
        reconnectLifecycleGate.allow()
    }

    private fun armPendingAacpSocketWatchdog(socket: BluetoothSocket) {
        pendingAacpSocketWatchdog.arm(socket)
        aacpConnectWatchdogExecutor.schedule(
            {
                val shouldClose = synchronized(connectionLifecycleLock) {
                    if (pendingAacpSocket !== socket ||
                        !pendingAacpSocketWatchdog.expireIfCurrent(socket)
                    ) {
                        false
                    } else {
                        pendingAacpSocket = null
                        true
                    }
                }
                if (shouldClose) {
                    Log.w(TAG, "AACP socket connect watchdog expired")
                    runCatching { socket.close() }
                }
            },
            AACP_CONNECT_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** Clears watchdog ownership only for the exact pending socket. */
    private fun clearPendingAacpSocket(socket: BluetoothSocket): Boolean =
        synchronized(connectionLifecycleLock) {
            if (pendingAacpSocket !== socket) return@synchronized false
            pendingAacpSocket = null
            pendingAacpSocketWatchdog.cancel(socket)
            true
        }

    private fun cancelPendingAacpConnection() {
        val pendingSocket = synchronized(connectionLifecycleLock) {
            pendingAacpSocket.also {
                pendingAacpSocket = null
                it?.let(pendingAacpSocketWatchdog::cancel)
            }
        }
        pendingSocket?.let { socket -> runCatching { socket.close() } }
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

    private fun submitHandoff(event: HandoffEvent, expectedLease: AacpSocketLease? = null) {
        if (serviceShuttingDown || !::audioHandoff.isInitialized) return
        if (expectedLease != null && !BluetoothConnectionManager.isCurrent(expectedLease)) return
        val pendingProtection = event is HandoffEvent.Connection && event.session != null ||
            event is HandoffEvent.Source && event.owner == AudioOwner.REMOTE ||
            event is HandoffEvent.RemoteStreaming && event.streaming
        if (pendingProtection) audioProfileRepairFence.beginPendingProtection()
        try {
            audioHandoff.accept(event)
        } finally {
            if (pendingProtection) audioProfileRepairFence.finishPendingProtection()
        }
    }

    private fun initializeAudioHandoff() {
        audioManager = getSystemService(AudioManager::class.java)
        handoffPlatform = AndroidAudioPlatform(this, audioManager)
        handoffConnection = AirPodsConnectionController(this, ::resolveSavedAirPodsDevice,
            { aacpManager }, ensureControl = { target, current ->
                connectToSocket(getSystemService(BluetoothManager::class.java).adapter, target,
                    manual = true, connectionGuard = current)
            }, profileObserved = { profile, connected ->
                if (profile == BluetoothProfile.A2DP) isAirPodsA2dpConnected = connected
                if (profile == BluetoothProfile.HEADSET) isAirPodsHfpConnected = connected
            })
        audioHandoff = AudioHandoffCoordinator(audioHandoffScope, handoffPlatform, handoffConnection,
            SystemClock::elapsedRealtime) { state ->
            if (state.protectRemote || state.operation != null) {
                audioProfileRepairFence.protect()
                audioProfileRepairDeferred = true
            } else audioProfileRepairFence.release()
            audioFeatureScope.launch {
                if (serviceShuttingDown) return@launch
                refreshGuardHealth()
                drainDeferredAudioProfileRepair()
                if (::heartRateRuntime.isInitialized) heartRateRuntime.refreshAutoRetryGate()
            }
        }
        submitHandoff(HandoffEvent.Enabled(sharedPreferences.getBoolean("remote_device_audio_guard_enabled", true)))
        playbackObserver = LocalPlaybackObserver(this, audioManager, audioHandoffScope,
            { BluetoothConnectionManager.aacpSocket?.isConnected == true },
            { isAirPodsA2dpConnected },
            { resolveSavedAirPodsDevice()?.address }, ::submitHandoffEvent)
        registerHandoffDiagnosticReceiver()
    }

    private fun registerHandoffDiagnosticReceiver() {
        if (!BuildConfig.DEBUG) return
        val action = "me.kavishdevar.librepods.AUDIO_DIAGNOSTIC_RELEASE_IDLE_OWNER"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent) {
                if (intent.action != action) return
                val result = goAsync()
                audioHandoffScope.launch {
                    try {
                        val session = audioHandoff.state.value.session
                        val allowed = {
                            val state = audioHandoff.state.value
                            !serviceShuttingDown && session != null && state.session == session &&
                                state.canSynchronizeIdleOwnership(SystemClock.elapsedRealtime()) &&
                                audioManager.mode == AudioManager.MODE_NORMAL && !audioManager.isMusicActive
                        }
                        if (!allowed()) {
                            result.resultCode = android.app.Activity.RESULT_CANCELED
                            result.resultData = "rejected: requires a fresh idle source, no call, and no local playback"
                        } else {
                            val sent = handoffConnection.synchronizeIdleOwnership(allowed)
                            result.resultCode = if (sent) android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED
                            result.resultData = "idle ownership declaration sent=$sent; no profile or route selection performed"
                            Log.i(TAG, "Explicit idle ownership diagnostic sent=$sent")
                        }
                    } catch (error: Exception) {
                        result.resultCode = android.app.Activity.RESULT_CANCELED
                        result.resultData = "failed: ${error.javaClass.simpleName}"
                        Log.w(TAG, "Idle ownership diagnostic failed", error)
                    }
                }.invokeOnCompletion { result.finish() }
            }
        }
        // Only shell/system callers with DUMP can invoke this fixed debug-only operation.
        registerReceiver(receiver, IntentFilter(action), "android.permission.DUMP", null, RECEIVER_EXPORTED)
        handoffDiagnosticReceiver = receiver
    }

    private fun submitHandoffEvent(event: HandoffEvent) = submitHandoff(event)

    fun takeAudioOnPhone() {
        allowAutomaticReconnect()
        submitHandoff(HandoffEvent.TakePhone(HandoffTrigger.MANUAL))
    }

    fun cancelAudioHandoff() = submitHandoff(HandoffEvent.Cancel)

    private fun drainDeferredAudioProfileRepair() {
        if (serviceShuttingDown || !audioProfileRepairDeferred || remoteHostOwnsAudio() ||
            !::audioHandoff.isInitialized || !audioHandoff.state.value.allowsAutomaticRepair) return
        audioProfileRepairDeferred = false
        if (!audioProfilesConnected()) {
            requestBoundedAudioProfileRepair(
                reason = "remote_released",
                target = resolveSavedAirPodsDevice(),
                delayMs = 0L,
            )
        }
    }

    private fun refreshGuardHealth() {
        if (!::audioHandoff.isInitialized) return
        val snapshot = audioHandoff.state.value
        val barrierStatus = PhoneSoundRoutingController.status
        val nativeTransportAvailable = handoffPlatform.nativeAvailable
        val health = GuardHealth.evaluate(
            enabled = snapshot.enabled,
            barrierState = barrierStatus.state,
            barrierActive = barrierStatus.active,
            nativeLeaseAvailable = nativeTransportAvailable,
            localMacResolved = localMac.isNotEmpty(),
            aacpConnected = snapshot.session != null,
            guardState = snapshot.phase,
            preferredRouteApplied = barrierStatus.preferredRouteApplied,
            protectionFailed = snapshot.protectionFailed,
        )
        if (_guardHealth.value == health) return
        val levelChanged = _guardHealth.value.level != health.level ||
            _guardHealth.value.reasons != health.reasons
        _guardHealth.value = health
        if (levelChanged) Log.i(TAG, "Guard health: ${health.level} ${health.reasons}")

        // Only update the foreground notification when its rendered content changes.
        val guardLine = guardNotificationLine()
        if (guardLine == lastGuardNotificationLine) return
        lastGuardNotificationLine = guardLine
        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
            updateNotificationContent(true, config.deviceName, batteryNotification.getBattery())
        }
    }

    private fun initializeHeartRateRuntime() {
        heartRatePreferences = getSharedPreferences("heart_rate_private", MODE_PRIVATE)
        _heartRateAutoRetryEnabled.value = isHeartRateAutoRetryEnabled()
        _heartRateRespectRemoteAudioGuard.value = isHeartRateRespectRemoteAudioGuardEnabled()
        healthConnectHeartRateExporter = HealthConnectHeartRateExporter(
            context = this,
            sharedPreferences = heartRatePreferences,
            scope = heartRateScope,
        )
        heartRateRuntime = HeartRateRuntimeCoordinator(
            scope = heartRateScope,
            elapsedRealtimeMillis = SystemClock::elapsedRealtime,
            autoRetryEnabled = ::heartRateAutoRetryEnabled,
            autoRetrySuppressed = {
                heartRateRespectRemoteAudioGuardEnabled() && remoteHostOwnsAudio()
            },
            watchdogScope = heartRateWatchdogScope,
            sink = object : HeartRateRuntimeSink {
                override suspend fun startHeartRate(
                    acquisition: HrmAcquisitionToken,
                    acquisitionIsCurrent: () -> Boolean,
                ): Boolean {
                    val lease = currentLeaseFor(acquisition.key) ?: return false
                    return heartRateTransport.start(lease) {
                        acquisitionIsCurrent() &&
                            heartRateRuntime.isAcquisitionCurrent(acquisition)
                    }
                }

                override fun stopHeartRate(
                    acquisition: HrmAcquisitionToken,
                    cleanup: OneShotSensorCleanup,
                ): Boolean {
                    val lease = currentLeaseFor(acquisition.key) ?: return false
                    return heartRateTransport.stop(lease, cleanup::consume)
                }

                override fun requestHeartRateTransportRecovery(
                    acquisition: HrmAcquisitionToken,
                    reason: HeartRateTransportRecoveryReason,
                    acquisitionIsCurrent: () -> Boolean,
                    onResult: (Boolean) -> Unit,
                ): Boolean = queueHeartRateTransportRecovery(
                    acquisition = acquisition,
                    reason = reason,
                    acquisitionIsCurrent = acquisitionIsCurrent,
                    onResult = onResult,
                )

                override fun startHeadTracking(
                    owner: SensorResourceOwner,
                    ownerIsCurrent: () -> Boolean,
                ): Boolean {
                    if (!ownerIsCurrent()) return false
                    val lease = currentLeaseFor(owner.connection) ?: return false
                    return startHeadTrackingTransport(lease)
                }

                override fun stopHeadTracking(
                    owner: SensorResourceOwner,
                    cleanup: OneShotSensorCleanup,
                ) {
                    val lease = currentLeaseFor(owner.connection) ?: return
                    if (!cleanup.consume()) return
                    stopHeadTrackingTransport(lease)
                }

                override fun publishMeasurement(
                    acquisition: HrmAcquisitionToken,
                    measurement: HeartRateMeasurement,
                ) {
                    onValidatedHeartRateMeasurement(acquisition, measurement)
                }
            },
        )
        runBlocking {
            heartRateRuntime.setEnabledAndAwait(
                heartRatePreferences.getBoolean(HEART_RATE_MONITORING_ENABLED, false)
            )
        }
        healthConnectHeartRateExporter.refresh()
    }

    val heartRateState: StateFlow<HeartRateUiState>
        get() = heartRateRuntime.uiState

    val headTrackingActiveState: StateFlow<Boolean>
        get() = heartRateRuntime.headTrackingActive

    val healthConnectExportState: StateFlow<HealthConnectExportState>
        get() = healthConnectHeartRateExporter.state

    fun setHeartRateMonitoringEnabled(enabled: Boolean) {
        if (!enabled) {
            invalidateHeartRateTransportRecovery("heart-rate monitoring disabled")
        }
        heartRatePreferences.edit { putBoolean(HEART_RATE_MONITORING_ENABLED, enabled) }
        heartRateRuntime.setEnabled(enabled)
    }

    fun isHeartRateAutoRetryEnabled(): Boolean =
        heartRatePreferences.getBoolean(HEART_RATE_AUTO_RETRY_ENABLED, true)

    fun setHeartRateAutoRetryEnabled(enabled: Boolean) {
        heartRatePreferences.edit { putBoolean(HEART_RATE_AUTO_RETRY_ENABLED, enabled) }
        _heartRateAutoRetryEnabled.value = enabled
        heartRateRuntime.refreshAutoRetryGate()
    }

    fun isHeartRateRespectRemoteAudioGuardEnabled(): Boolean =
        heartRatePreferences.getBoolean(HEART_RATE_RESPECT_REMOTE_AUDIO_GUARD, true)

    fun setHeartRateRespectRemoteAudioGuardEnabled(enabled: Boolean) {
        heartRatePreferences.edit {
            putBoolean(HEART_RATE_RESPECT_REMOTE_AUDIO_GUARD, enabled)
        }
        _heartRateRespectRemoteAudioGuard.value = enabled
        heartRateRuntime.refreshAutoRetryGate()
    }

    private fun heartRateAutoRetryEnabled(): Boolean = isHeartRateAutoRetryEnabled()

    private fun heartRateRespectRemoteAudioGuardEnabled(): Boolean =
        isHeartRateRespectRemoteAudioGuardEnabled()

    fun retryHeartRateMonitoring() {
        heartRateRuntime.retry()
    }

    private fun queueHeartRateTransportRecovery(
        acquisition: HrmAcquisitionToken,
        reason: HeartRateTransportRecoveryReason,
        acquisitionIsCurrent: () -> Boolean,
        onResult: (Boolean) -> Unit,
    ): Boolean {
        if (serviceShuttingDown ||
            reconnectLifecycleGate.isSuppressed() ||
            !heartRateRuntime.session.value.enabled ||
            !acquisitionIsCurrent() ||
            // Manual retries stay available; automatic and 3s auto retries must not fight another
            // host that currently owns the AirPods audio.
            (reason != HeartRateTransportRecoveryReason.USER_RETRY &&
                heartRateRespectRemoteAudioGuardEnabled() &&
                remoteHostOwnsAudio())
        ) {
            return false
        }

        val queued = synchronized(heartRateRecoveryLock) {
            heartRateRecoveryJob?.takeIf { !it.isCompleted }?.let {
                val sameAcquisition = heartRateRecoveryAcquisition?.matches(acquisition) == true
                Log.d(
                    TAG,
                    "Heart-rate AACP recovery already queued sameAcquisition=$sameAcquisition"
                )
                return sameAcquisition
            }

            val lease = currentLeaseFor(acquisition.key) ?: return false
            val permit = reconnectEpisodeGate.tryBeginHeartRateRecovery(
                reason = reason,
            ) ?: run {
                Log.w(TAG, "Heart-rate AACP recovery budget exhausted reason=$reason")
                return false
            }
            val recoveryRevision = heartRateRecoveryRevision
            val recoveryDeviceKey = acquisition.key.deviceKey

            aacpReaderScope.launch(start = CoroutineStart.LAZY) {
                var resultDelivered = false
                try {
                    Log.i(
                        TAG,
                        "Waiting ${HEART_RATE_TRANSPORT_RESET_QUIET_PERIOD_MS}ms before " +
                            "same-lease heart-rate restart"
                    )
                    delay(HEART_RATE_TRANSPORT_RESET_QUIET_PERIOD_MS)
                    val current = !serviceShuttingDown &&
                        !reconnectLifecycleGate.isSuppressed() &&
                        reconnectEpisodeGate.isCurrent(permit.generation) &&
                        heartRateRuntime.session.value.enabled &&
                        isHeartRateTransportRecoveryCurrent(recoveryRevision) &&
                        isHeartRateRecoveryDeviceCurrent(recoveryDeviceKey) &&
                        acquisitionIsCurrent() &&
                        BluetoothConnectionManager.isCurrent(lease)
                    resultDelivered = true
                    onResult(current)
                } finally {
                    if (!resultDelivered) {
                        runCatching { onResult(false) }
                    }
                    val completed = kotlinx.coroutines.currentCoroutineContext()[Job]
                    synchronized(heartRateRecoveryLock) {
                        if (heartRateRecoveryJob === completed) {
                            heartRateRecoveryJob = null
                            heartRateRecoveryAcquisition = null
                        }
                    }
                }
            }.also {
                heartRateRecoveryJob = it
                heartRateRecoveryAcquisition = acquisition
            }
        }
        queued.start()
        return true
    }

    private fun isHeartRateTransportRecoveryCurrent(expectedRevision: Long): Boolean =
        synchronized(heartRateRecoveryLock) {
            heartRateRecoveryRevision == expectedRevision
        }

    private fun isHeartRateRecoveryDeviceCurrent(expectedDeviceKey: String): Boolean =
        sharedPreferences.getString("mac_address", null)
            ?.equals(expectedDeviceKey, ignoreCase = true) == true

    private fun invalidateHeartRateTransportRecovery(reason: String) {
        val cancelledJob = synchronized(heartRateRecoveryLock) {
            heartRateRecoveryRevision++
            heartRateRecoveryAcquisition = null
            heartRateRecoveryJob.also { heartRateRecoveryJob = null }
        }
        if (cancelledJob != null) {
            cancelledJob.cancel()
            Log.d(TAG, "Cancelled heart-rate AACP recovery: $reason")
        }
    }

    fun refreshHealthConnectHeartRateExport() {
        healthConnectHeartRateExporter.refresh()
    }

    fun setHealthConnectHeartRateExportEnabled(enabled: Boolean) {
        healthConnectHeartRateExporter.setEnabled(enabled)
    }

    fun setHealthConnectDetailedSamples(enabled: Boolean) {
        healthConnectHeartRateExporter.setDetailedSamples(enabled)
    }

    fun markHealthConnectHeartRatePermissionDenied() {
        healthConnectHeartRateExporter.markPermissionDenied()
    }

    private fun onValidatedHeartRateMeasurement(
        acquisition: HrmAcquisitionToken,
        measurement: HeartRateMeasurement,
    ) {
        // A live sample means the current HRM session is working; keep recovery permits available
        // so later transient stalls can recover without a socket reconnect.
        reconnectEpisodeGate.resetHeartRateRecoveryBudget()
        healthConnectHeartRateExporter.accept(
            acquisitionIdentity = HeartRateExportAcquisitionIdentity(
                connectionGeneration = acquisition.key.generation,
                sessionEpoch = acquisition.key.epoch,
                acquisition = acquisition.acquisition,
            ),
            measurement = measurement,
        )
    }

    private fun currentLeaseFor(key: HrmSessionKey): AacpSocketLease? =
        BluetoothConnectionManager.currentAacpSocketLease()?.takeIf { lease ->
            lease.socket === key.socketIdentity &&
                lease.generation == key.generation &&
                BluetoothConnectionManager.isCurrent(lease)
        }

    private fun currentHeartRateKeyFor(lease: AacpSocketLease): HrmSessionKey? =
        heartRateSessionKey?.takeIf { key ->
            key.socketIdentity === lease.socket && key.generation == lease.generation
        }

    private fun installHeartRateSession(
        lease: AacpSocketLease,
        connectedDevice: BluetoothDevice,
    ) {
        invalidateHeartRateTransportRecovery("new AACP session installed")
        aacpDeviceInformationReceived = false
        val key = HrmSessionKey(
            socketIdentity = lease.socket,
            generation = lease.generation,
            deviceKey = connectedDevice.address,
            epoch = lease.generation,
        )
        heartRateSessionKey = key
        runBlocking { heartRateRuntime.installConnectionAndAwait(key) }
    }

    private fun cleanupHeartRateSessionBeforeLeaseClose(lease: AacpSocketLease) {
        val key = currentHeartRateKeyFor(lease) ?: return
        // Bounded teardown: a stalled coordinator mutex (e.g. a blocked watchdogScope stop write
        // that still holds the gate) must not hang the caller. On a normal disconnect this finishes
        // well within the cap; on a stalled transport the socket is already being closed and the
        // next install/destroy path retries the cleanup.
        runBlocking {
            withTimeoutOrNull(3_000L) {
                heartRateRuntime.disconnectAndAwait(key)
            }
        }
        if (heartRateSessionKey?.matches(key) == true) heartRateSessionKey = null
        aacpDeviceInformationReceived = false
    }


    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag", "HardwareIds")
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "lib exempt worked: ${isBluetoothSocketExempted()}")

        sharedPreferencesLogs = getSharedPreferences("packet_logs", MODE_PRIVATE)

        inMemoryLogs.addAll(
            sharedPreferencesLogs.getStringSet(packetLogKey, emptySet()) ?: emptySet()
        )
        _packetLogsFlow.value = inMemoryLogs.toSet()

        sharedPreferences = getSharedPreferences("settings", MODE_PRIVATE)
        initializeConfig()

        aacpManager = AACPManager()
        initializeAACPManagerCallback()
        initializeHeartRateRuntime()

        attManager = ATTManagerv2()

        sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        localMac = LocalHostIdentity.normalize(config.selfMacAddress).orEmpty()
        if (localMac.isEmpty()) {
            val frameworkAddress = if (
                checkSelfPermission("android.permission.LOCAL_MAC_ADDRESS") ==
                PackageManager.PERMISSION_GRANTED
            ) {
                val bluetoothManager = getSystemService(BluetoothManager::class.java)
                val bluetoothAdapter = bluetoothManager.adapter
                bluetoothAdapter.address
            } else {
                null
            }
            localMac = LocalHostIdentity.normalize(frameworkAddress).orEmpty()

            if (localMac.isEmpty()) {
                val rootAddress = try {
                    val process = Runtime.getRuntime().exec(
                        arrayOf("su", "-c", "settings get secure bluetooth_address")
                    )

                    val exitCode = process.waitFor()

                    if (exitCode == 0) {
                        process.inputStream.bufferedReader().use { it.readLine()?.trim().orEmpty() }
                    } else {
                        ""
                    }
                } catch (e: Exception) {
                    Log.e(
                        TAG,
                        "Error retrieving local MAC address: ${e.message}. We probably aren't rooted."
                    )
                    ""
                }
                localMac = LocalHostIdentity.normalize(rootAddress).orEmpty()
            }
            config.selfMacAddress = localMac
            sharedPreferences.edit {
                putString("self_mac_address", localMac)
            }
        }

        initializeAudioHandoff()
        ServiceManager.setService(this)
        startForegroundNotification()
        observeHandoffNotification()
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
                if (!contains("conversational_awareness_both_pods_only")) putBoolean(
                    "conversational_awareness_both_pods_only", false
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
                        cycleNoiseControlMode()
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
        audioManager = this@AirPodsService.getSystemService(AUDIO_SERVICE) as AudioManager
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

        telephonyManager = getSystemService(TELEPHONY_SERVICE) as TelephonyManager
        phoneStateListener = object: TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                when (state) {
                    TelephonyManager.CALL_STATE_RINGING -> {
                        if (config.headGestures) {
                            handleIncomingCall()
                        }
                    }

                    TelephonyManager.CALL_STATE_OFFHOOK -> {
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
                    Log.d(TAG, "Setting metadata")
                    setMetadatas(device!!)
//                    isConnectedLocally = true
                    macAddress = device!!.address
                    sharedPreferences.edit {
                        putString("mac_address", macAddress)
                    }
                    requestAutomaticReconnect(
                        "acl_connected",
                        ensureAudioProfiles = false,
                        boundedControlRepair = true,
                    )
//                    }

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

        ContextCompat.registerReceiver(
            this,
            showIslandReceiver,
            showIslandIntentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val deviceIntentFilter = IntentFilter().apply {
            addAction(AirPodsNotifications.AIRPODS_CONNECTION_DETECTED)
        }

        ContextCompat.registerReceiver(
            this,
            connectionReceiver,
            deviceIntentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothReceiver, serviceIntentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(bluetoothReceiver, serviceIntentFilter)
        }

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
                                        this@AirPodsService.device = device
                                        setMetadatas(device)
                                        macAddress = device.address
                                        sharedPreferences.edit {
                                            putString("mac_address", macAddress)
                                        }
                                        requestAutomaticReconnect(
                                            "existing_a2dp_connection",
                                            ensureAudioProfiles = false,
                                            boundedControlRepair = true,
                                        )
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

        aacpReaderScope.launch {
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

    private fun cycleNoiseControlMode(expectedLease: AacpSocketLease? = null) {
        val currentMode = ancNotification.status
        val configByte = sharedPreferences.getInt("long_press_byte", 0b0111)
        val allowOffModeValue = aacpManager.controlCommandStatusList.find {
            it.identifier == AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION
        }
        val allowOffMode =
            allowOffModeValue?.value?.firstOrNull() == 0x01.toByte() ||
                sharedPreferences.getBoolean("off_listening_mode", true)
        val nextMode = getNextMode(currentMode, configByte, allowOffMode)

        aacpManager.sendControlCommand(
            AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE.value,
            nextMode,
            expectedLease,
        )
        Log.d(TAG, "Cycling ANC mode from $currentMode to $nextMode")
    }

    fun setupStemActions(expectedLease: AacpSocketLease? = null): Boolean {
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
        return aacpManager.sendStemConfigPacket(
            singlePressCustomized,
            doublePressCustomized,
            triplePressCustomized,
            longPressCustomized,
            expectedLease,
        )
    }

    /**
     * Listening-mode availability, the Off option, and stem customization are accessory state that
     * must be overwritten on every fresh AACP session (upstream librepods #691). Replays once per
     * lease after the first valid frame; any failed write is retried on the next frame.
     */
    private fun replayPersistentControlStateOnce() {
        val lease = BluetoothConnectionManager.currentAacpSocketLease() ?: return
        val replayed = replayedControlConfigLease
        if (replayed?.socket === lease.socket && replayed.generation == lease.generation) return
        // Do not interleave configuration writes into an in-flight HRM start sequence; defer to
        // the next frame instead (the lease is not marked, so the retry path keeps trying).
        if (::heartRateRuntime.isInitialized &&
            heartRateRuntime.session.value.status in setOf(
                HeartRateSessionStatus.STARTING,
                HeartRateSessionStatus.RECONNECTING,
            )
        ) {
            return
        }
        // Firmware is past the handshake once Device Information has been parsed; earlier frames
        // can arrive before the accessory is ready to accept configuration writes.
        if (!aacpDeviceInformationReceived) return
        val listeningModes = sharedPreferences.getInt("long_press_byte", 0b0111)
            .coerceIn(0, 0xFF)
            .toByte()
        val allowOffMode = sharedPreferences.getBoolean("off_listening_mode", true)
        val sent = aacpManager.sendControlCommand(
            AACPManager.Companion.ControlCommandIdentifiers.LISTENING_MODE_CONFIGS.value,
            listeningModes,
            lease,
        ) && aacpManager.sendControlCommand(
            AACPManager.Companion.ControlCommandIdentifiers.ALLOW_OFF_OPTION.value,
            allowOffMode,
            lease,
        ) && setupStemActions(lease)
        if (sent) {
            replayedControlConfigLease = lease
            Log.d(TAG, "AACP persistent configuration replayed for current session")
        } else {
            Log.w(TAG, "AACP persistent configuration replay failed; retrying on next frame")
        }
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
//                CrossDevice.sendRemotePacket(batteryInfo)
//                CrossDevice.batteryBytes = batteryInfo

                for (battery in batteryNotification.getBattery()) {
                    Log.d(
                        "AirPodsParser",
                        "${battery.getComponentName()}: ${battery.getStatusName()} at ${battery.level}% "
                    )
                }

            }

            override fun onEarDetectionReceived(
                earDetection: ByteArray,
                expectedLease: AacpSocketLease,
            ) {
                if (!BluetoothConnectionManager.isCurrent(expectedLease)) return
                val heartRateKey = currentHeartRateKeyFor(expectedLease)
                if (heartRateKey != null && earDetection.size >= 2) {
                    val wearBytes = earDetection.copyOfRange(
                        earDetection.size - 2,
                        earDetection.size,
                    )
                    heartRateRuntime.updateWear(
                        heartRateKey,
                        HeartRateWearState(
                            leftWorn = wearBytes[0] == 0x00.toByte(),
                            rightWorn = wearBytes[1] == 0x00.toByte(),
                        ),
                    )
                }
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

                val budsInEar = earDetectionNotification.status.count { it == 0x00.toByte() }
                val blockedForSinglePod =
                    config.conversationalAwarenessBothPodsOnly && budsInEar < 2

                if (conversationAwarenessNotification.status == 1.toByte() || conversationAwarenessNotification.status == 2.toByte()) {
                    if (blockedForSinglePod) {
                        Log.d(
                            "AirPodsParser",
                            "CA startSpeaking suppressed — both-pods-only is ON and " +
                                "only $budsInEar bud(s) in ear"
                        )
                    } else {
                        MediaController.startSpeaking()
                    }
                } else if (conversationAwarenessNotification.status == 6.toByte() ||conversationAwarenessNotification.status == 8.toByte() || conversationAwarenessNotification.status == 9.toByte()) {
                    // Never gated: volume must always be restored.
                    MediaController.stopSpeaking(
                        resumePlayback = automaticMediaResumeAllowed,
                    )
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

            override fun onOwnershipChangeReceived(
                owns: Boolean,
                expectedLease: AacpSocketLease,
            ) {
                if (!BluetoothConnectionManager.isCurrent(expectedLease)) return
                if (owns) {
                    scheduleAirPodsAbsoluteVolumeResync(
                        "AACP ownership confirmed for this phone",
                        delayMs = 0L
                    )
                } else {
                    prewarmAirPodsAbsoluteVolumeResync("AACP ownership lost")
                    cancelPendingAirPodsAbsoluteVolumeResync("AACP ownership lost")
                    Log.d(TAG, "AACP ownership moved to another device")
                }
            }

            override fun onOwnershipToFalseRequest(
                sender: String,
                reasonReverseTapped: Boolean,
                expectedLease: AacpSocketLease,
            ) {
                if (!LocalHostIdentity.isRemote(sender, localMac)) return
                if (!BluetoothConnectionManager.isCurrent(expectedLease) || !::audioHandoff.isInitialized) return
                audioHandoff.yieldToRemote(expectedLease.generation) {
                    if (BluetoothConnectionManager.isCurrent(expectedLease)) {
                        aacpManager.sendControlCommand(
                            AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                            byteArrayOf(0x00), expectedLease)
                    }
                }
            }

            override fun onRemoteStreamingStateChanged(
                sender: String,
                isStreaming: Boolean,
                expectedLease: AacpSocketLease,
            ) {
                if (!LocalHostIdentity.isRemote(sender, localMac)) return
                Log.d(TAG, "Remote streaming state from $sender: $isStreaming")
                submitHandoff(HandoffEvent.RemoteStreaming(expectedLease.generation, isStreaming), expectedLease)
            }

            override fun onShowNearbyUI(sender: String) {
                Log.d(TAG, "Ignoring legacy Smart Routing UI request from $sender")
            }

            override fun onDeviceInformationReceived(
                deviceInformation: AACPManager.Companion.AirPodsInformation,
                expectedLease: AacpSocketLease,
            ) {
                if (!BluetoothConnectionManager.isCurrent(expectedLease)) return
                // A size>=8 but malformed/partial frame parses to empty fields (e.g. a passthrough
                // fragment interleaved with an HRM frame). Committing it would overwrite the real
                // device identity and, because getModelByModelNumber("") matches the first model,
                // bind the wrong AirPods model — feeding wrong capabilities into heart-rate
                // eligibility. Require a plausible model number before accepting the frame.
                val modelNumber = deviceInformation.modelNumber.trim()
                if (modelNumber.isEmpty()) {
                    Log.w(
                        "AirPodsParser",
                        "Ignoring device-info frame with empty model number (malformed/partial?)"
                    )
                    return
                }
                aacpDeviceInformationReceived = true
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
                currentHeartRateKeyFor(expectedLease)?.let { heartRateKey ->
                    heartRateRuntime.confirmDeviceInformation(
                        key = heartRateKey,
                        modelNumber = deviceInformation.modelNumber,
                        modelCapabilities = model?.capabilities.orEmpty(),
                    )
                }
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
                updateNotificationContent(
                    true,
                    deviceInformation.name,
                    batteryNotification.getBattery(),
                )
            }

            @SuppressLint("NewApi")
            override fun onHeadTrackingReceived(
                headTracking: ByteArray,
                expectedLease: AacpSocketLease,
            ) {
                if (!BluetoothConnectionManager.isCurrent(expectedLease)) return
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

            override fun onStemPressReceived(
                stemPress: ByteArray,
                expectedLease: AacpSocketLease,
            ) {

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
                    action?.let { executeStemAction(it, expectedLease) }
                }
            }

            override fun onAudioSourceReceived(
                audioSource: AACPManager.Companion.AudioSource?,
                expectedLease: AacpSocketLease,
            ) {
                Log.d(
                    "AirPodsParser",
                    "Audio source changed mac: ${audioSource?.mac}, " +
                        "type: ${audioSource?.type?.name}"
                )
                val kind = when (audioSource?.type) {
                    AACPManager.Companion.AudioSourceType.NONE -> AudioSourceKind.NONE
                    AACPManager.Companion.AudioSourceType.CALL -> AudioSourceKind.CALL
                    AACPManager.Companion.AudioSourceType.MEDIA -> AudioSourceKind.MEDIA
                    else -> AudioSourceKind.UNKNOWN
                }
                val source = LocalHostIdentity.normalize(audioSource?.mac)
                val self = LocalHostIdentity.normalize(localMac)
                val owner = when {
                    kind == AudioSourceKind.NONE -> AudioOwner.NONE
                    source == null || self == null -> AudioOwner.UNKNOWN
                    source == self -> AudioOwner.PHONE
                    else -> AudioOwner.REMOTE
                }
                submitHandoff(HandoffEvent.Source(expectedLease.generation, owner, kind), expectedLease)
                if (BluetoothConnectionManager.isCurrent(expectedLease) &&
                    !isAirPodsA2dpConnected &&
                    hasFreshInEarBlePresence()
                ) {
                    requestBoundedAudioProfileRepair(
                        reason = "aacp_source_observed",
                        target = resolveSavedAirPodsDevice(),
                    )
                }
            }

            override fun onConnectedDevicesReceived(
                connectedDevices: List<AACPManager.Companion.ConnectedDevice>,
                expectedLease: AacpSocketLease,
            ) {
                for (device in connectedDevices) {
                    Log.d(
                        "AirPodsParser",
                        "Connected device: ${device.mac}, info1: ${device.info1}, info2: ${device.info2})"
                    )
                }
                val newDevices = connectedDevices.filter { newDevice ->
                    val notInOld =
                        aacpManager.oldConnectedDevices.none { oldDevice -> oldDevice.mac == newDevice.mac }
                    val notLocal = newDevice.mac != localMac
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
                        selfMacAddress = localMac,
                        targetMacAddress = device.mac,
                        expectedLease = expectedLease,
                    )
                    aacpManager.sendAddTiPiDevice(
                        selfMacAddress = localMac,
                        targetMacAddress = device.mac,
                        expectedLease = expectedLease,
                    )
                }
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

            override fun onAACPFrameReceived() {
                replayPersistentControlStateOnce()
            }

            override fun onUnknownPacketReceived(packet: ByteArray) {
                Log.d(
                    "AACPManager",
                    "Unknown packet received: ${packet.joinToString(" ") { "%02X".format(it) }}"
                )
            }
        })
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

    private fun executeStemAction(
        action: StemAction,
        expectedLease: AacpSocketLease,
    ) {
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
                cycleNoiseControlMode(expectedLease)
            }
        }
    }

    private fun processEarDetectionChange(earDetection: ByteArray) {
        var inEar: Boolean
        val inEarData = listOf(
            earDetectionNotification.status[0] == 0x00.toByte(),
            earDetectionNotification.status[1] == 0x00.toByte()
        )
        var justEnabledA2dp = false
        earDetectionNotification.setStatus(earDetection)
        if (config.earDetectionEnabled) {
            val data = earDetection.copyOfRange(earDetection.size - 2, earDetection.size)
            inEar = data[0] == 0x00.toByte() && data[1] == 0x00.toByte()

            val newInEarData = listOf(
                data[0] == 0x00.toByte(), data[1] == 0x00.toByte()
            )

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

            if (newInEarData.contains(true) && inEarData == listOf(false, false)) {
                requestBoundedAudioProfileRepair(
                    reason = "aacp_ear_in",
                    target = resolveSavedAirPodsDevice(),
                )
                justEnabledA2dp = true
                if (automaticMediaResumeAllowed) {
                    registerA2dpConnectionReceiver()
                    if (MediaController.getMusicActive()) {
                        MediaController.userPlayedTheMedia = true
                    }
                } else {
                    Log.d(TAG, "Deferring ear-detection media resume while remote state is protected")
                }
            } else if (newInEarData == listOf(false, false)) {
                MediaController.sendPause(force = true)
                if (config.disconnectWhenNotWearing) {
                    disconnectAudio(this@AirPodsService, device)
                }
            }
            val wasNone = inEarData == listOf(false, false)
            val nowSingle = newInEarData.count { it } == 1

            if (wasNone && nowSingle) {
                resumeMediaAfterEarDetection("single AirPod inserted")
                return
            }

            if (inEarData.contains(false) && newInEarData == listOf(true, true)) {
                Log.d("AirPodsParser", "User put in both AirPods from just one.")
                MediaController.userPlayedTheMedia = false
            }

            if (newInEarData.contains(false) && inEarData == listOf(true, true)) {
                Log.d("AirPodsParser", "User took one of two out.")
                MediaController.userPlayedTheMedia = false
            }

            Log.d(
                "AirPodsParser",
                "inEarData: ${inEarData.sorted()}, newInEarData: ${newInEarData.sorted()}"
            )

            if (newInEarData.sorted() != inEarData.sorted()) {
                if (inEar) {
                    if (!justEnabledA2dp) {
                        resumeMediaAfterEarDetection("ear state changed")
                    }
                } else if (newInEarData != listOf(false, false)) {
                    // The "both removed" case already paused with force=true above.
                    // This generic ear-detection pause is independent of the CA both-pods-only
                    // toggle, which only gates conversational-awareness volume ducking.
                    MediaController.sendPause()
                }
            }
        }
    }

    private fun resumeMediaAfterEarDetection(reason: String) {
        if (!automaticMediaResumeAllowed) {
            Log.d(TAG, "Skipping ear-detection media resume while remote state is protected: $reason")
            return
        }
        MediaController.sendPlay()
        MediaController.iPausedTheMedia = false
    }

    private fun registerA2dpConnectionReceiver() {
        val a2dpConnectionStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == "android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED") {
                    val state = intent.getIntExtra(
                        BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED
                    )
                    val previousState = intent.getIntExtra(
                        BluetoothProfile.EXTRA_PREVIOUS_STATE, BluetoothProfile.STATE_DISCONNECTED
                    )
                    val device =
                        intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)

                    Log.d(
                        "MediaController",
                        "A2DP state changed: $previousState -> $state for device: ${device?.address}"
                    )

                    if (state == BluetoothProfile.STATE_CONNECTED && previousState != BluetoothProfile.STATE_CONNECTED && device?.address == this@AirPodsService.device?.address) {

                        resumeMediaAfterEarDetection("A2DP profile connected")

                        context.unregisterReceiver(this)
                    }
                }
            }
        }

        val a2dpIntentFilter =
            IntentFilter("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(a2dpConnectionStateReceiver, a2dpIntentFilter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(a2dpConnectionStateReceiver, a2dpIntentFilter)
        }
    }

    private fun initializeConfig() {
        config = ServiceConfig(
            deviceName = sharedPreferences.getString("name", "AirPods") ?: "AirPods",
            earDetectionEnabled = sharedPreferences.getBoolean("automatic_ear_detection", true),
            conversationalAwarenessPauseMusic = sharedPreferences.getBoolean(
                "conversational_awareness_pause_music", false
            ),
            conversationalAwarenessBothPodsOnly = sharedPreferences.getBoolean(
                "conversational_awareness_both_pods_only", false
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
            "remote_device_audio_guard_enabled" -> submitHandoff(
                HandoffEvent.Enabled(preferences.getBoolean(key, true)))
            "automatic_ear_detection" -> config.earDetectionEnabled =
                preferences.getBoolean(key, true)

            "conversational_awareness_pause_music" -> config.conversationalAwarenessPauseMusic =
                preferences.getBoolean(key, false)

            "conversational_awareness_both_pods_only" ->
                config.conversationalAwarenessBothPodsOnly = preferences.getBoolean(key, false)

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

            "self_mac_address" -> config.selfMacAddress = preferences.getString(key, "") ?: ""
        }
    }

    private fun logPacket(packet: ByteArray, @Suppress("SameParameterValue") source: String) {
        val packetHex = packet.joinToString(" ") { "%02X".format(it) }
        val logEntry = "$source: $packetHex"

        synchronized(inMemoryLogs) {
            inMemoryLogs.add(logEntry)
            if (inMemoryLogs.size > maxLogEntries) {
                inMemoryLogs.iterator().next().let {
                    inMemoryLogs.remove(it)
                }
            }

            _packetLogsFlow.value = inMemoryLogs.toSet()
        }

        CoroutineScope(Dispatchers.IO).launch {
            val logs =
                sharedPreferencesLogs.getStringSet(packetLogKey, mutableSetOf())?.toMutableSet()
                    ?: mutableSetOf()
            logs.add(logEntry)

            if (logs.size > maxLogEntries) {
                val toKeep = logs.toList().takeLast(maxLogEntries).toSet()
                sharedPreferencesLogs.edit { putStringSet(packetLogKey, toKeep) }
            } else {
                sharedPreferencesLogs.edit { putStringSet(packetLogKey, logs) }
            }
        }
    }

    private fun clearPacketLogs() {
        synchronized(inMemoryLogs) {
            inMemoryLogs.clear()
            _packetLogsFlow.value = emptySet()
        }
        sharedPreferencesLogs.edit { remove(packetLogKey) }
    }

    fun clearLogs() {
        clearPacketLogs()
        _packetLogsFlow.value = emptySet()
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
        batteryPercentage: Int
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
                this@AirPodsService
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

    private fun observeHandoffNotification() {
        audioFeatureScope.launch {
            audioHandoffState.map { it.phase }.distinctUntilChanged().collect {
                if (!serviceShuttingDown && BluetoothConnectionManager.aacpSocket?.isConnected == true) {
                    updateNotificationContent(true, config.deviceName, batteryNotification.getBattery())
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    fun updateNotificationContent(
        connected: Boolean, airpodsName: String? = null, batteryList: List<Battery>? = null
    ) {
        val notificationManager = getSystemService(NotificationManager::class.java)

        if (!connected) {
            synchronized(connectionNotificationLock) {
                lastConnectionNotificationContent = null
            }
            notificationManager.cancel(2)
            return
        }

        if (BluetoothConnectionManager.aacpSocket?.isConnected != true) {
            if (!config.bleOnlyMode) {
                showSocketConnectionFailureNotification("BluetoothConnectionManager.aacpSocket? created, but not connected. Check logs")
            }
            return
        }

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = airpodsName ?: config.deviceName
        val text = """${
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
        }"""
        val handoffState = if (::audioHandoff.isInitialized) audioHandoffState.value else null
        val switching = handoffState?.operation != null
        val guardLine = when {
            switching -> getString(R.string.audio_handoff_switching)
            handoffState?.phase == AudioHandoffPhase.FAILED -> getString(R.string.audio_handoff_failed)
            else -> guardNotificationLine()
        }
        val content = ConnectionNotificationContent(title, text, guardLine, switching)

        synchronized(connectionNotificationLock) {
            if (BluetoothConnectionManager.aacpSocket?.isConnected != true) return
            // HyperOS unfolds a folded notification on every notify() update.
            if (lastConnectionNotificationContent == content) return

            val updatedNotificationBuilder =
                NotificationCompat.Builder(this, "airpods_connection_status")
                    .setSmallIcon(R.drawable.airpods).setContentTitle(title).setContentText(text)
                    .setContentIntent(pendingIntent).setCategory(Notification.CATEGORY_STATUS)
                    .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).setSilent(true)
                    .setOnlyAlertOnce(true).setShowWhen(false)
                    .addAction(
                        if (switching) android.R.drawable.ic_menu_close_clear_cancel
                        else R.drawable.ic_audio_handoff,
                        getString(if (switching) R.string.audio_handoff_cancel else R.string.audio_handoff_take_phone),
                        AudioHandoffActionReceiver.pendingIntent(this, switching),
                    )

            if (guardLine != null) {
                updatedNotificationBuilder.setSubText(guardLine)
                updatedNotificationBuilder.setStyle(
                    NotificationCompat.BigTextStyle().bigText("$text\n$guardLine")
                )
            }

            val updatedNotification = updatedNotificationBuilder.build()
                .applyMiuiFoldTimeout()

            notificationManager.notify(2, updatedNotification)
            lastConnectionNotificationContent = content
        }
        notificationManager.cancel(1)
    }

    /** One line describing protection state, or null when there is nothing worth saying. */
    private fun guardNotificationLine(): String? {
        val health = _guardHealth.value
        val reasonText = health.primaryReason?.let { reason ->
            getString(
                when (reason) {
                    GuardHealthReason.PERMISSION_MISSING ->
                        R.string.guard_health_reason_permission_missing
                    GuardHealthReason.BARRIER_FAILED -> R.string.guard_health_reason_barrier_failed
                    GuardHealthReason.BARRIER_STUCK -> R.string.guard_health_reason_barrier_stuck
                    GuardHealthReason.LOCAL_MAC_UNKNOWN ->
                        R.string.guard_health_reason_local_mac_unknown
                    GuardHealthReason.NATIVE_TRANSPORT_MISSING ->
                        R.string.guard_health_reason_native_transport_missing
                    GuardHealthReason.PREFERRED_ROUTE_MISSING ->
                        R.string.guard_health_reason_preferred_route_missing
                    GuardHealthReason.DISABLED_BY_USER -> R.string.guard_health_reason_disabled
                }
            )
        }
        // Only speak up while the guard is actually trying to protect something. A standing
        // "not active" line would otherwise sit in the notification forever on installs
        // without the root module, drowning out the line that matters.
        val protecting = health.guardState == AudioHandoffPhase.REMOTE
        if (!protecting) return null
        return when (health.level) {
            GuardHealthLevel.INACTIVE ->
                getString(R.string.guard_notification_inactive, reasonText.orEmpty())
            // The speaker barrier registered but the preferred route did not apply — already-playing
            // media stays pinned on the AirPods and goes silent. That is worse than a missing layer,
            // so say what is actually wrong instead of the generic "protecting" line.
            GuardHealthLevel.DEGRADED ->
                if (health.primaryReason == GuardHealthReason.PREFERRED_ROUTE_MISSING) {
                    getString(R.string.guard_notification_degraded, reasonText.orEmpty())
                } else {
                    getString(R.string.guard_notification_protecting)
                }
            // The speaker barrier still holds; only the native A2DP block is missing.
            else -> getString(R.string.guard_notification_protecting)
        }
    }

    fun handleIncomingCall() {
        if (isInCall) return
        if (config.headGestures) {
            initGestureDetector()
            gestureDetector?.startDetection { accepted ->
                if (accepted) {
                    answerCall()
                } else {
                    rejectCall()
                }
            }

        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun testHeadGestures(): Boolean {
        val detector = gestureDetector ?: return false
        return suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                detector.stopDetection()
            }
            val started = detector.startDetection(
                onUnavailable = {
                    if (continuation.isActive) {
                        continuation.resume(false) { _, _, _ -> detector.stopDetection() }
                    }
                }
            ) { accepted ->
                if (continuation.isActive) {
                    continuation.resume(accepted) { _, _, _ ->
                        detector.stopDetection()
                    }
                }
            }
            if (!started && continuation.isActive) {
                continuation.resume(false) { _, _, _ -> }
            } else if (!continuation.isActive) {
                detector.stopDetection()
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
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val adapterState = intent.getIntExtra(
                    BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR,
                )
                Log.d(TAG, "Bluetooth adapter state changed: $adapterState")
                if (this@AirPodsService::bleManager.isInitialized) {
                    when (adapterState) {
                        BluetoothAdapter.STATE_ON -> {
                            resetReconnectPresenceEpisode("adapter_on")
                            bleManager.startScanning()
                        }
                        BluetoothAdapter.STATE_TURNING_OFF,
                        BluetoothAdapter.STATE_OFF,
                        -> bleManager.stopScanning()
                    }
                }
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
                    val savedMac = sharedPreferences.getString("mac_address", "") ?: ""
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
                } else if (BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED == action) {
                    if (bluetoothDevice.address.equals(macAddress, true)) {
                        isAirPodsHfpConnected = intent.getIntExtra(BluetoothProfile.EXTRA_STATE,
                            BluetoothProfile.STATE_DISCONNECTED) == BluetoothProfile.STATE_CONNECTED
                        if (!isAirPodsHfpConnected) requestBoundedAudioProfileRepair("hfp_disconnected", bluetoothDevice)
                    }
                } else if (BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED == action) {
                    val state = intent.getIntExtra(
                        BluetoothProfile.EXTRA_STATE,
                        BluetoothProfile.STATE_DISCONNECTED
                    )
                    val previousState = intent.getIntExtra(
                        BluetoothProfile.EXTRA_PREVIOUS_STATE,
                        BluetoothProfile.STATE_DISCONNECTED,
                    )
                    val airPodsMac = this@AirPodsService.device?.address
                        ?: macAddress.takeIf(String::isNotBlank)
                        ?: sharedPreferences.getString("mac_address", null)
                    val isCurrentAirPods = !airPodsMac.isNullOrEmpty() &&
                        bluetoothDevice.address.equals(airPodsMac, ignoreCase = true)
                    if (isCurrentAirPods && state == BluetoothProfile.STATE_CONNECTED) {
                        isAirPodsA2dpConnected = true
                        refreshCurrentA2dpPlaybackState("AirPods A2DP connected")
                    } else if (isCurrentAirPods && state == BluetoothProfile.STATE_DISCONNECTED) {
                        val wasConnected = isAirPodsA2dpConnected ||
                            previousState == BluetoothProfile.STATE_CONNECTED
                        isAirPodsA2dpConnected = false
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
                    val airPodsMac = this@AirPodsService.device?.address
                        ?: macAddress.takeIf(String::isNotBlank)
                        ?: sharedPreferences.getString("mac_address", null)
                    val isCurrentAirPods = !airPodsMac.isNullOrEmpty() &&
                        bluetoothDevice.address.equals(airPodsMac, ignoreCase = true)
                    if (isCurrentAirPods && state == BluetoothA2dp.STATE_PLAYING) {
                        isAirPodsA2dpPlaying = true
                        Log.d(TAG, "Local AirPods A2DP started playing")
                        scheduleAirPodsAbsoluteVolumeResync(
                            "A2DP playback started",
                            A2DP_DIRECT_VOLUME_RESYNC_DELAY_MS
                        )
                        if (SpatialAudioMode.fromPreferences(sharedPreferences) ==
                            SpatialAudioMode.HEAD_TRACKED
                        ) {
                            Handler(Looper.getMainLooper()).postDelayed({
                                updateSpatialAudioTracking("A2DP playback started")
                            }, 300)
                        }
                    } else if (isCurrentAirPods && state == BluetoothA2dp.STATE_NOT_PLAYING) {
                        isAirPodsA2dpPlaying = false
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
        return START_STICKY
    }

    fun connectToSocket(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        manual: Boolean = false,
        connectionGuard: (() -> Boolean)? = null,
    ) {
        if (connectionGuard?.invoke() == false || serviceShuttingDown) return
        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
            submitHandoff(HandoffEvent.Connection(BluetoothConnectionManager.currentAacpSocketLease()?.generation))
            Log.d(TAG, "AACP socket is already connected, skipping duplicate request")
            return
        }
        Log.d(TAG, "<LogCollector:Start> Connecting to socket")
        val uuid: ParcelUuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
//        if (!isConnectedLocally) {
        val socket = try {
            createBluetoothSocket(adapter, device, uuid, 4097)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create BluetoothSocket: ${e.message}")
            showSocketConnectionFailureNotification("Failed to create Bluetooth socket: ${e.localizedMessage}")
            return
        }
        if (connectionGuard?.invoke() == false || serviceShuttingDown) {
            runCatching { socket.close() }
            return
        }
        val acceptedAsPending = synchronized(connectionLifecycleLock) {
            if (connectionGuard?.invoke() == false || serviceShuttingDown) {
                false
            } else {
                pendingAacpSocket = socket
                armPendingAacpSocketWatchdog(socket)
                true
            }
        }
        if (!acceptedAsPending) {
            runCatching { socket.close() }
            return
        }
        var socketGeneration = 0L
        var connectionLease: AacpSocketLease? = null
        var setupFailureHandled = false

        try {
            runBlocking {
                withTimeout(5000.milliseconds) {
                    try {
                        socket.connect()
                        if (serviceShuttingDown || connectionGuard?.invoke() == false) {
                            clearPendingAacpSocket(socket)
                            runCatching { socket.close() }
                            return@withTimeout
                        }
                        val installedLease = synchronized(connectionLifecycleLock) {
                            if (serviceShuttingDown ||
                                connectionGuard?.invoke() == false ||
                                pendingAacpSocket !== socket ||
                                !pendingAacpSocketWatchdog.cancel(socket)
                            ) {
                                return@synchronized null
                            }
                            aacpSessionJob?.cancel()
                            aacpDelayedRefreshJob?.cancel()
                            BluetoothConnectionManager.currentAacpSocketLease()?.let(
                                ::cleanupHeartRateSessionBeforeLeaseClose
                            )
                            this@AirPodsService.device = device
                            socketGeneration =
                                BluetoothConnectionManager.installAacpSocket(socket)
                            val lease = BluetoothConnectionManager.currentAacpSocketLease()?.takeIf {
                                it.socket === socket && it.generation == socketGeneration
                            }
                            if (lease != null) {
                                pendingAacpSocket = null
                                connectionLease = lease
                                aacpManager.bindSocketLease(lease)
                                // Publish the runtime key under the same fence used by teardown.
                                installHeartRateSession(lease, device)
                                submitHandoff(HandoffEvent.Connection(lease.generation), lease)
                            }
                            lease
                        }
                        if (installedLease == null) {
                            clearPendingAacpSocket(socket)
                            runCatching { socket.close() }
                            return@withTimeout
                        }
                        val useVendorAttSocket = XposedRemotePrefProvider.create().getBoolean(
                            "vendor_id_hook", false
                        ) && sharedPreferences.getBoolean("vendor_att_socket", false)
                        val attCandidate = if (useVendorAttSocket) {
                            try {
                                createBluetoothSocket(
                                    adapter,
                                    device,
                                    ParcelUuid.fromString("00000000-0000-0000-0000-000000000000"),
                                    31
                                ).also { it.connect() }
                            } catch (e: Exception) {
                                Log.w(
                                    TAG,
                                    "Optional PSM 31 channel failed; continuing with AACP only: ${e.message}"
                                )
                                null
                            }
                        } else null
                        val attSocket = synchronized(connectionLifecycleLock) {
                            if (serviceShuttingDown ||
                                connectionGuard?.invoke() == false ||
                                !BluetoothConnectionManager.isCurrent(installedLease)
                            ) {
                                null
                            } else {
                                BluetoothConnectionManager.invalidateAttSocket()
                                BluetoothConnectionManager.attSocket = attCandidate
                                attCandidate
                            }
                        }
                        if (attCandidate != null && attSocket !== attCandidate) {
                            runCatching { attCandidate.close() }
                        }

                        if (attSocket != null) {
                            attManager.startReader()
                            attManager.readCharacteristic(ATTHandles.LOUD_SOUND_REDUCTION)
                            attManager.readCharacteristic(ATTHandles.TRANSPARENCY)
                            attManager.readCharacteristic(ATTHandles.HEARING_AID)
                        }
                        if (connectionGuard?.invoke() == false ||
                            !BluetoothConnectionManager.isCurrent(installedLease)
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

                        updateNotificationContent(
                            true, config.deviceName, batteryNotification.getBattery()
                        )
                        Log.d(TAG, "<LogCollector:Complete:Success> Socket connected")
                        sharedPreferences.edit { putBoolean("connection_successful", true) }
                        if (!sharedPreferences.contains("first_connection_successful_time")) {
                            sharedPreferences.edit {
                                putLong(
                                    "first_connection_successful_time",
                                    System.currentTimeMillis()
                                )
                            }
                        }
                        sendBroadcast(Intent(AirPodsNotifications.AIRPODS_L2CAP_CONNECTED).apply {
                            setPackage(packageName)
                        })
                    } catch (e: Exception) {
                        clearPendingAacpSocket(socket)
                        connectionLease?.let { installedLease ->
                            setupFailureHandled = true
                            finishAacpReader(installedLease, "connection setup failed")
                        } ?: runCatching { socket.close() }
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
            if (setupFailureHandled) return
            if (connectionGuard?.invoke() == false || serviceShuttingDown) {
                connectionLease?.let { installedLease ->
                    finishAacpReader(installedLease, "reconnect request superseded")
                } ?: run {
                    clearPendingAacpSocket(socket)
                    runCatching { socket.close() }
                }
                return
            }
            if (!socket.isConnected) {
                clearPendingAacpSocket(socket)
                runCatching { socket.close() }
                Log.d(TAG, "<LogCollector:Complete:Failed> socket not connected")
                if (manual) {
                    sendToast(
                        "Couldn't connect to socket: timeout."
                    )
                } else {
                    showSocketConnectionFailureNotification("Couldn't connect to socket: Timeout")
                }
                return
            }
            this@AirPodsService.device = device
            if (socketGeneration == 0L) {
                Log.e(TAG, "Connected AACP socket has no generation; closing it")
                runCatching { socket.close() }
                return
            }
            val lease = connectionLease ?: run {
                Log.e(TAG, "Connected AACP socket has no lease; closing it")
                BluetoothConnectionManager.invalidateAacpSocket(socket)
                return
            }
            if (!BluetoothConnectionManager.isCurrent(lease)) {
                runCatching { socket.close() }
                return
            }
            val leaseHeartRateDecoder = LeaseFencedRtBuddyHeartRateDecoder(
                decoder = RtBuddyHeartRateDecoder(
                    elapsedRealtimeMillis = SystemClock::elapsedRealtime,
                ),
                leaseIsCurrent = { BluetoothConnectionManager.isCurrent(lease) },
            )
            socket.let {
                aacpManager.sendPacket(aacpManager.createHandshakePacket(), lease)
                aacpManager.sendSetFeatureFlagsPacket(lease)
                aacpManager.sendNotificationRequest(lease)
                if (sharedPreferences.getBoolean("remote_device_audio_guard_enabled", true)) {
                    aacpManager.requestAudioSource(lease)
                }
                Log.d(TAG, "Requesting proximity keys")
                aacpManager.sendRequestProximityKeys(
                    (AACPManager.Companion.ProximityKeyType.IRK.value +
                        AACPManager.Companion.ProximityKeyType.ENC_KEY.value).toByte(),
                    lease,
                )
                aacpSessionJob = aacpReaderScope.launch {
                    fun startupSuperseded(): Boolean {
                        val superseded = serviceShuttingDown ||
                            !BluetoothConnectionManager.isCurrent(socket, socketGeneration)
                        if (superseded && BluetoothConnectionManager.isCurrent(lease)) {
                            finishAacpReader(lease, "reconnect request superseded")
                        }
                        return superseded
                    }

                    delay(200)
                    if (startupSuperseded()) return@launch
                    aacpManager.sendPacket(aacpManager.createHandshakePacket(), lease)
                    delay(200)
                    if (startupSuperseded()) return@launch
                    aacpManager.sendSetFeatureFlagsPacket(lease)
                    delay(200)
                    if (startupSuperseded()) return@launch
                    aacpManager.sendNotificationRequest(lease)
                    if (sharedPreferences.getBoolean("remote_device_audio_guard_enabled", true)) {
                        aacpManager.requestAudioSource(lease)
                    }
                    delay(200)
                    if (startupSuperseded()) return@launch
                    aacpManager.sendSomePacketIDontKnowWhatItIs(lease)
                    delay(200)
                    if (startupSuperseded()) return@launch
                    aacpManager.sendRequestProximityKeys(
                        (AACPManager.Companion.ProximityKeyType.IRK.value +
                            AACPManager.Companion.ProximityKeyType.ENC_KEY.value).toByte(),
                        lease,
                    )
                    if (SpatialAudioMode.fromPreferences(sharedPreferences) ==
                        SpatialAudioMode.HEAD_TRACKED
                    ) {
                        // The PLAYING broadcast may have happened while the
                        // service was stopped, so query again once AACP is usable.
                        refreshCurrentA2dpPlaybackState("AACP connected")
                    } else {
                        requestHeadTracking(HeadTrackingRequestSource.BOOTSTRAP, lease)
                    }
                    aacpDelayedRefreshJob = aacpReaderScope.launch {
                        delay(5_000L)
                        if (!BluetoothConnectionManager.isCurrent(socket, socketGeneration)) {
                            return@launch
                        }
                        aacpManager.sendPacket(aacpManager.createHandshakePacket(), lease)
                        aacpManager.sendSetFeatureFlagsPacket(lease)
                        aacpManager.sendNotificationRequest(lease)
                        aacpManager.sendRequestProximityKeys(
                            AACPManager.Companion.ProximityKeyType.IRK.value,
                            lease,
                        )
                        releaseHeadTracking(HeadTrackingRequestSource.BOOTSTRAP, lease)
                    }

                    sendBroadcast(
                        Intent(AirPodsNotifications.AIRPODS_CONNECTED).putExtra("device", device)
                            .apply {
                                setPackage(packageName)
                            })

                    setupStemActions(lease)

                    while (
                        socket.isConnected &&
                        BluetoothConnectionManager.isCurrent(socket, socketGeneration)
                    ) {
                        try {
                            val buffer = ByteArray(1024)
                            val bytesRead = it.inputStream.read(buffer)
                            if (bytesRead > 0) {
                                val data = buffer.copyOfRange(0, bytesRead)
                                if (!BluetoothConnectionManager.isCurrent(lease)) {
                                    Log.d(TAG, "Ignoring packet from stale AACP reader")
                                    runCatching { socket.close() }
                                    return@launch
                                }
                                val decoded = leaseHeartRateDecoder.feed(data) ?: run {
                                    Log.d(TAG, "Discarding decoded data from stale AACP reader")
                                    runCatching { socket.close() }
                                    return@launch
                                }
                                if (!BluetoothConnectionManager.isCurrent(lease)) {
                                    Log.d(TAG, "Discarding stale AACP reader result")
                                    runCatching { socket.close() }
                                    return@launch
                                }
                                if (BuildConfig.DEBUG &&
                                    (decoded.relatedFrameCount > 0 ||
                                        decoded.rejectedFrameCount > 0 ||
                                        decoded.requiresTransportReset)
                                ) {
                                    Log.d(
                                        TAG,
                                        "Heart-rate RTBuddy related=${decoded.relatedFrameCount} " +
                                            "samples=${decoded.samples.size} " +
                                            "rejected=${decoded.rejectedFrameCount} " +
                                            "reset=${decoded.requiresTransportReset}",
                                    )
                                }
                                if (decoded.requiresTransportReset) {
                                    Log.w(TAG, "Heart-rate sensor transport requested a reset")
                                    if (reconnectEpisodeGate.tryConsumeHeartRateTransportReset()) {
                                        val heartRateKey = currentHeartRateKeyFor(lease)
                                        val acquisition = heartRateRuntime.session.value.acquisition
                                            ?.takeIf { token ->
                                                heartRateKey?.matches(token.key) == true
                                            }
                                        if (acquisition != null) {
                                            heartRateRuntime.requestTransportRecovery(
                                                acquisition,
                                                HeartRateTransportRecoveryReason.AUTOMATIC,
                                            )
                                        }
                                    } else {
                                        Log.w(
                                            TAG,
                                            "Heart-rate transport reset budget exhausted " +
                                                "for this presence episode",
                                        )
                                    }
                                    // The AACP control link remains shared and current. Reset only
                                    // this reader's decoder; the coordinator will stop/start HRM
                                    // on the same lease after its quiet period.
                                    leaseHeartRateDecoder.reset()
                                    continue
                                }

                                val heartRateKey = currentHeartRateKeyFor(lease)
                                if (heartRateKey != null) {
                                    decoded.samples.forEach { sample ->
                                        heartRateRuntime.acceptSample(heartRateKey, sample)
                                    }
                                }

                                decoded.passthroughPackets.forEach { packet ->
                                    aacpManager.receivePacket(
                                        packet = packet,
                                        expectedLease = lease,
                                        allowRawLogging = !decoded.suppressRawLogging,
                                    )

                                    sendBroadcast(
                                        Intent(AirPodsNotifications.AIRPODS_DATA).apply {
                                            putExtra("data", packet)
                                            setPackage(packageName)
                                        }
                                    )
//                                    CrossDevice.sendReceivedPacket(packet)

                                    if (!decoded.suppressRawLogging &&
                                        !isHeadTrackingData(packet)
                                    ) {
                                        val formattedHex =
                                            packet.joinToString(" ") { "%02X".format(it) }
                                        Log.d("AirPodsData", "Data received: $formattedHex")
                                        logPacket(packet, "AirPods")
                                    }
                                }

                            } else if (bytesRead == -1) {
                                Log.d("AirPodsService", "socket closed (bytesRead = -1)")
                                if (finishAacpReader(lease, "reader reached EOF")) {
                                    requestAutomaticReconnect(
                                        "aacp_remote_close",
                                        ensureAudioProfiles = !isAirPodsA2dpConnected,
                                        boundedControlRepair = true,
                                    )
                                }
                                return@launch
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Error reading data, we have probably disconnected.")
                            e.printStackTrace()
                            if (BluetoothConnectionManager.isCurrent(lease)) {
                                if (finishAacpReader(lease, "reader failed")) {
                                    requestAutomaticReconnect(
                                        "aacp_read_failed",
                                        ensureAudioProfiles = !isAirPodsA2dpConnected,
                                        boundedControlRepair = true,
                                    )
                                }
                            } else {
                                runCatching { socket.close() }
                            }
                            return@launch
                        }

                    }
                    Log.d("AirPods Service", "socket closed")
//                        isConnectedLocally = false
                    finishAacpReader(lease, "socket closed")
                }
            }
        } catch (e: Exception) {
            clearPendingAacpSocket(socket)
            connectionLease?.let { installedLease ->
                finishAacpReader(installedLease, "connection failed")
            } ?: runCatching { socket.close() }
            e.printStackTrace()
            Log.d(TAG, "Failed to connect to BluetoothConnectionManager.aacpSocket?: ${e.message}")
            showSocketConnectionFailureNotification("Failed to establish connection: ${e.localizedMessage}")
//                isConnectedLocally = false
            this@AirPodsService.device = device
            updateNotificationContent(false)
        }
//        } else {
//            Log.d(TAG, "Already connected locally, skipping BluetoothConnectionManager.aacpSocket? connection (isConnectedLocally = $isConnectedLocally, BluetoothConnectionManager.aacpSocket?.isConnected = ${this::BluetoothConnectionManager.aacpSocket?.isInitialized && BluetoothConnectionManager.aacpSocket?.isConnected})")
//        }
    }

    private fun finishAacpReader(
        expectedLease: AacpSocketLease,
        reason: String,
    ): Boolean {
        val closedCurrentSession = synchronized(connectionLifecycleLock) {
            if (!BluetoothConnectionManager.isCurrent(expectedLease)) {
                return@synchronized false
            }
            cleanupHeartRateSessionBeforeLeaseClose(expectedLease)
            if (!BluetoothConnectionManager.closeAacpGeneration(
                    expectedLease.socket,
                    expectedLease.generation,
                )
            ) {
                return@synchronized false
            }
            aacpSessionJob?.cancel()
            aacpDelayedRefreshJob?.cancel()
            BluetoothConnectionManager.invalidateAttSocket()
            clearAacpSessionState(reason)
            submitHandoff(HandoffEvent.Connection(null))
            aacpManager.disconnected(expectedLease)
            true
        }
        if (!closedCurrentSession) {
            Log.d(TAG, "Ignoring stale AACP reader termination: $reason")
            return false
        }
        updateNotificationContent(false)
        sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED).apply {
            setPackage(packageName)
        })
        return true
    }

    private fun clearAacpSessionState(reason: String) {
        isAirPodsA2dpPlaying = false
        cancelPendingAirPodsAbsoluteVolumeResync("AACP $reason")
        rootAvrcpVolumeController.cancel()
        updateSpatialAudioTracking("AACP $reason")
        device = null
        popupShown = false
    }

    fun disconnectAirPods() {
        submitHandoff(HandoffEvent.Disconnect)
        suppressAutomaticReconnect()
        var targetDevice: BluetoothDevice? = null
        val disconnectedCurrentSession = synchronized(connectionLifecycleLock) {
            val currentLease = BluetoothConnectionManager.currentAacpSocketLease()
                ?: return@synchronized false
            cleanupHeartRateSessionBeforeLeaseClose(currentLease)
            if (!BluetoothConnectionManager.closeAacpGeneration(
                    currentLease.socket,
                    currentLease.generation,
                )
            ) {
                return@synchronized false
            }
            targetDevice = device
            BluetoothConnectionManager.invalidateAttSocket()
            clearAacpSessionState("user disconnected")
//        isConnectedLocally = false
            submitHandoff(HandoffEvent.Connection(null))
            aacpManager.disconnected(currentLease)
            true
        }
        if (!disconnectedCurrentSession) return

        updateNotificationContent(false)
        sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DISCONNECTED).apply {
            setPackage(packageName)
        })

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED){
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
            if (Build.VERSION.SDK_INT >= 37) {
                try {
                    targetDevice?.disconnect()
                } catch (e: Exception) {
                    Log.w(TAG, "device.disconnect() failed, $e")
                }
            }
        }
        if (checkSelfPermission("android.permission.MODIFY_PHONE_STATE") == PackageManager.PERMISSION_GRANTED){
            bluetoothAdapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.HEADSET) {
                        val connectedDevices = proxy.connectedDevices
                        if (connectedDevices.isNotEmpty()) {
                            MediaController.sendPause()
                        }
                    }
                    bluetoothAdapter.closeProfileProxy(profile, proxy)
                }

                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.HEADSET)
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
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED) {
            bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile == BluetoothProfile.A2DP) {
                        try {
                            if (proxy.getConnectionState(device) == BluetoothProfile.STATE_DISCONNECTED) {
                                Log.d(TAG, "Already disconnected from A2DP")
                                return
                            }
                            val method = proxy.javaClass.getMethod(
                                "setConnectionPolicy", BluetoothDevice::class.java, Int::class.java
                            )
                            Log.d(TAG, "calling A2DP.setConnectionPolicy for ${device?.address} to 0")
                            method.invoke(proxy, device, 0)
                        } catch (e: Exception) {
                            e.printStackTrace()
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
                            val method =
                                proxy.javaClass.getMethod(
                                    "setConnectionPolicy",
                                    BluetoothDevice::class.java,
                                    Int::class.java
                                )
                            Log.d(TAG, "calling HEADSET.setConnectionPolicy for ${device?.address} to 0")
                            method.invoke(proxy, device, 0)
                        } catch (e: Exception) {
                            e.printStackTrace()
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

    fun connectAudio(context: Context, device: BluetoothDevice?, requestGuard: (() -> Boolean)? = null) {
        if (device == null || serviceShuttingDown || !::handoffConnection.isInitialized) return
        if (requestGuard == null) {
            takeAudioOnPhone()
            return
        }
        aacpReaderScope.launch {
            val current = { !serviceShuttingDown && requestGuard() }
            val perform: (() -> Boolean) -> Boolean = { operation ->
                var result = false
                audioProfileRepairFence.runIfAllowed(current) { result = operation() } && result
            }
            for (profile in listOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
                if (!current()) break
                runCatching { handoffConnection.connectProfile(device, profile, current, false, perform) }
                    .onFailure { Log.w(TAG, "Audio profile repair failed", it) }
            }
        }
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

    fun audioHandoffDiagnostics(): String {
        val output = java.io.StringWriter()
        writeAudioHandoffDiagnostics(java.io.PrintWriter(output))
        return output.toString()
    }

    override fun dump(fd: java.io.FileDescriptor, writer: java.io.PrintWriter, args: Array<out String>) {
        if (::playbackObserver.isInitialized && "capabilities" in args) {
            writer.println("Capability probe=${playbackObserver.checkCapability()}")
        }
        writeAudioHandoffDiagnostics(writer)
    }

    private fun writeAudioHandoffDiagnostics(writer: java.io.PrintWriter) {
        writer.println("LibrePods ${BuildConfig.VERSION_NAME} / ${BuildConfig.VERSION_CODE}")
        writer.println("Time=${System.currentTimeMillis()} elapsed=${SystemClock.elapsedRealtime()}")
        if (!::audioHandoff.isInitialized) { writer.println("Audio handoff not initialized"); return }
        val state = audioHandoff.state.value
        writer.println("Audio handoff: phase=${state.phase} owner=${state.owner} kind=${state.sourceKind}")
        val reportedOwnership = runCatching { aacpManager.getControlCommandStatus(
            AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION)?.value?.firstOrNull()?.toInt()
        }.getOrNull()
        val protocolSource = aacpManager.audioSource
        val sourceHost = when (LocalHostIdentity.normalize(protocolSource?.mac)) {
            null -> "unknown"
            LocalHostIdentity.normalize(localMac) -> "phone"
            else -> "remote"
        }
        writer.println("AACP reportedOwnership=${reportedOwnership ?: "not_reported"} " +
            "sourceHost=$sourceHost sourceType=${protocolSource?.type} peers=${aacpManager.connectedDevices.size}")
        writer.println("Initial session=${state.session} connectedAt=${state.connectedAtMs} " +
            "sourceObservedAt=${state.sourceObservedAtMs} verifiedPhoneSession=${state.verifiedPhoneSession}")
        writer.println("Control=${state.session != null} A2DP=${state.a2dpConnected} HFP=${state.hfpConnected} " +
            "targetOutput=${state.targetOutput} operation=${state.operation} failure=${state.failure}")
        writer.println("Protection requested=${state.protectRemote} failed=${state.protectionFailed} " +
            "nativeAcknowledged=${handoffPlatform.nativeAvailable}")
        writer.println("Playback observation=${playbackObserver.capability}")
        writer.println("Route=${PhoneSoundRoutingController.status}")
        audioHandoff.diagnostics().forEach(writer::println)
    }

    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        clearPacketLogs()
        Log.d(TAG, "Service stopped is being destroyed for some reason!")

        serviceShuttingDown = true
        handoffDiagnosticReceiver?.let { runCatching { unregisterReceiver(it) } }
        handoffDiagnosticReceiver = null
        suppressAutomaticReconnect()
        gestureDetector?.stopDetection(doNotStop = true)
        if (::playbackObserver.isInitialized) playbackObserver.close()
        if (::audioHandoff.isInitialized) runBlocking { withTimeoutOrNull(3_000) { audioHandoff.close() } }
        if (::handoffPlatform.isInitialized) handoffPlatform.close()
        audioHandoffScope.cancel()
        synchronized(connectionLifecycleLock) {
            aacpSessionJob?.cancel()
            aacpDelayedRefreshJob?.cancel()
            val currentLease = BluetoothConnectionManager.currentAacpSocketLease()
            currentLease?.let(::cleanupHeartRateSessionBeforeLeaseClose)
            BluetoothConnectionManager.invalidateAacpSocket()
            BluetoothConnectionManager.invalidateAttSocket()
            runCatching { pendingAacpSocket?.close() }
            pendingAacpSocket = null
            pendingAacpSocketWatchdog.clear()
            aacpManager.disconnected(currentLease)
        }
        aacpConnectWatchdogExecutor.shutdownNow()
        runBlocking {
            withTimeoutOrNull(3_000L) {
                healthConnectHeartRateExporter.closeAndAwait()
            }
            withTimeoutOrNull(3_000L) {
                heartRateRuntime.destroyAndAwait()
            }
        }
        heartRateSessionKey = null
        heartRateScope.cancel()
        heartRateWatchdogScope.cancel()
        aacpReaderScope.cancel()
        ServiceManager.setService(null)

        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)

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
        val savedMac = macAddress.takeIf { it.isNotBlank() }
            ?: sharedPreferences.getString("mac_address", null)
        if (savedMac.isNullOrBlank()) {
            isAirPodsA2dpConnected = false
            isAirPodsA2dpPlaying = false
            updateSpatialAudioTracking("$reason; no saved AirPods")
            return
        }

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        val target = try {
            bluetoothAdapter.getRemoteDevice(savedMac)
        } catch (error: IllegalArgumentException) {
            Log.w(TAG, "Cannot query A2DP playback for invalid address $savedMac", error)
            isAirPodsA2dpConnected = false
            isAirPodsA2dpPlaying = false
            updateSpatialAudioTracking("$reason; invalid AirPods address")
            return
        }
        val listener = object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.A2DP) return
                val a2dp = proxy as BluetoothA2dp
                val reflectedPlaying = try {
                    val method = BluetoothA2dp::class.java.getDeclaredMethod(
                        "isA2dpPlaying",
                        BluetoothDevice::class.java
                    )
                    method.isAccessible = true
                    method.invoke(a2dp, target) as? Boolean
                } catch (error: Exception) {
                    Log.w(TAG, "BluetoothA2dp.isA2dpPlaying unavailable", error)
                    null
                }
                val targetConnected = a2dp.getConnectionState(target) ==
                    BluetoothProfile.STATE_CONNECTED
                val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
                val playing = reflectedPlaying
                    ?: (targetConnected && audioManager.isMusicActive)
                bluetoothAdapter.closeProfileProxy(profile, proxy)

                Handler(Looper.getMainLooper()).post {
                    isAirPodsA2dpConnected = targetConnected
                    isAirPodsA2dpPlaying = playing
                    Log.i(
                        TAG,
                        "Current AirPods A2DP playing=$playing " +
                            "(connected=$targetConnected): $reason"
                    )
                    updateSpatialAudioTracking(reason)
                }
            }

            override fun onServiceDisconnected(profile: Int) = Unit
        }

        if (!bluetoothAdapter.getProfileProxy(this, listener, BluetoothProfile.A2DP)) {
            Log.w(TAG, "Could not obtain A2DP profile proxy: $reason")
            isAirPodsA2dpConnected = false
            isAirPodsA2dpPlaying = false
            updateSpatialAudioTracking("$reason; A2DP proxy unavailable")
        }
    }

    private fun shouldRunSpatialAudio(): Boolean {
        return SpatialAudioMode.fromPreferences(sharedPreferences) ==
            SpatialAudioMode.HEAD_TRACKED &&
            isAirPodsA2dpPlaying &&
            BluetoothConnectionManager.aacpSocket?.isConnected == true
    }

    private fun scheduleAirPodsAbsoluteVolumeResync(reason: String, delayMs: Long) {
        if (remoteHostOwnsAudio()) return
        if (earlyA2dpVolumeResyncInFlight) {
            Log.d(TAG, "Early A2DP volume resync already active: $reason")
            return
        }
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
        if (remoteHostOwnsAudio()) return
        if (!isAirPodsA2dpPlaying) {
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
        if (generation != a2dpVolumeResyncGeneration || !isAirPodsA2dpPlaying) return
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

    fun onLocalMediaPlaybackStarting() {
        if (isAirPodsA2dpPlaying || earlyA2dpVolumeResyncInFlight) return
        if (checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") ==
            PackageManager.PERMISSION_GRANTED
        ) return
        val target = device ?: return
        val (pulseVolume, volume) = currentA2dpVolumePulse(direct = true)
        earlyA2dpVolumeResyncInFlight = true
        Log.i(TAG, "Triggering prewarmed AirPods volume bridge from local media start")
        audioFeatureScope.launch(Dispatchers.IO) {
            val result = rootAvrcpVolumeController.resend(
                target.address,
                pulseVolume,
                volume,
                awaitPlayback = true
            )
            withContext(Dispatchers.Main) {
                earlyA2dpVolumeResyncInFlight = false
                if (result.success) {
                    Log.i(
                        TAG,
                        "Early root pulse restored AirPods device volume=" +
                            "$pulseVolume->$volume"
                    )
                    lastA2dpVolumeResyncAt = SystemClock.elapsedRealtime()
                    prewarmAirPodsAbsoluteVolumeResync("early volume resync complete")
                } else {
                    Log.w(TAG, "Early root AirPods AVRCP resend failed: ${result.output}")
                    if (isAirPodsA2dpPlaying) {
                        pulseAirPodsAbsoluteVolume("early root AVRCP fallback")
                    }
                }
            }
        }
    }

    private fun pulseAirPodsAbsoluteVolume(reason: String) {
        if (remoteHostOwnsAudio()) return
        if (!isAirPodsA2dpPlaying) {
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
            requestHeadTracking(HeadTrackingRequestSource.SPATIAL_AUDIO)
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
                            requestHeadTracking(HeadTrackingRequestSource.SPATIAL_AUDIO)
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
                            releaseHeadTracking(HeadTrackingRequestSource.SPATIAL_AUDIO)
                        }
                    }
                }
            }
        }
    }

    fun startHeadTracking(expectedLease: AacpSocketLease? = null): Boolean =
        requestHeadTracking(HeadTrackingRequestSource.UI_SCREEN, expectedLease)

    fun stopHeadTracking(
        force: Boolean = false,
        expectedLease: AacpSocketLease? = null,
    ): Boolean = releaseHeadTracking(HeadTrackingRequestSource.UI_SCREEN, expectedLease)

    suspend fun requestGestureHeadTracking(): Boolean {
        val lease = BluetoothConnectionManager.currentAacpSocketLease() ?: return false
        if (!BluetoothConnectionManager.isCurrent(lease)) return false
        val key = currentHeartRateKeyFor(lease) ?: return false
        return heartRateRuntime.requestHeadTrackingImmediately(
            key,
            HeadTrackingRequestSource.GESTURE,
        )
    }

    fun releaseGestureHeadTracking(): Boolean =
        releaseHeadTracking(HeadTrackingRequestSource.GESTURE)

    private fun requestHeadTracking(
        source: HeadTrackingRequestSource,
        expectedLease: AacpSocketLease? = null,
    ): Boolean {
        val lease = expectedLease ?: BluetoothConnectionManager.currentAacpSocketLease()
            ?: return false
        if (!BluetoothConnectionManager.isCurrent(lease)) return false
        val key = currentHeartRateKeyFor(lease) ?: return false
        heartRateRuntime.requestHeadTracking(key, source)
        return true
    }

    private fun releaseHeadTracking(
        source: HeadTrackingRequestSource,
        expectedLease: AacpSocketLease? = null,
    ): Boolean {
        val lease = expectedLease ?: BluetoothConnectionManager.currentAacpSocketLease()
            ?: return false
        if (!BluetoothConnectionManager.isCurrent(lease)) return false
        val key = currentHeartRateKeyFor(lease) ?: return false
        heartRateRuntime.releaseHeadTracking(key, source)
        return true
    }

    private fun startHeadTrackingTransport(expectedLease: AacpSocketLease): Boolean {
        if (!BluetoothConnectionManager.isCurrent(expectedLease)) return false
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
        val sent = if (useAlternatePackets) {
            aacpManager.sendDataPacket(
                aacpManager.createAlternateStartHeadTrackingPacket(),
                expectedLease,
            )
        } else {
            aacpManager.sendStartHeadTracking(expectedLease)
        }
        if (!sent) return false
        isHeadTrackingActive = true
        HeadTracking.reset()
        if (shouldRunSpatialAudio()) {
            val trackerMac = device?.address
            if (trackerMac == null) {
                Log.e(TAG, "Cannot start spatial head tracker: no AirPods Bluetooth address")
            } else {
                Log.d(TAG, "Starting spatial head tracker")
                spatialHeadTrackerBridge.start(trackerMac)
            }
        }
        return true
    }

    private fun stopHeadTrackingTransport(expectedLease: AacpSocketLease): Boolean {
        val useAlternatePackets =
            sharedPreferences.getBoolean("use_alternate_head_tracking_packets", true)
        val sent = if (useAlternatePackets) {
            aacpManager.sendDataPacket(
                aacpManager.createAlternateStopHeadTrackingPacket(),
                expectedLease,
            )
        } else {
            aacpManager.sendStopHeadTracking(expectedLease)
        }
        isHeadTrackingActive = false
        spatialHeadTrackerBridge.stop()
        gestureDetector?.stopDetection(doNotStop = true)
        return sent
    }

    @SuppressLint("MissingPermission")
    fun reconnectFromSavedMac() = takeAudioOnPhone()

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
