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
import android.media.AudioPlaybackConfiguration
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.channels.Channel
import me.kavishdevar.librepods.BuildConfig
import me.kavishdevar.librepods.MainActivity
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.bluetooth.AACPManager
import me.kavishdevar.librepods.bluetooth.AACPManager.Companion.StemPressType
import me.kavishdevar.librepods.bluetooth.AacpSocketLease
import me.kavishdevar.librepods.bluetooth.ATTHandles
import me.kavishdevar.librepods.bluetooth.ATTManagerv2
import me.kavishdevar.librepods.bluetooth.BLEManager
import me.kavishdevar.librepods.bluetooth.BluetoothConnectionManager
import me.kavishdevar.librepods.bluetooth.ReconnectEpisodeGate
import me.kavishdevar.librepods.bluetooth.ReconnectLifecycleBegin
import me.kavishdevar.librepods.bluetooth.ReconnectLifecycleGate
import me.kavishdevar.librepods.bluetooth.ReconnectLifecycleToken
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
import me.kavishdevar.librepods.data.RemoteDeviceAudioGuard
import me.kavishdevar.librepods.data.RemoteDeviceAudioGuardAction
import me.kavishdevar.librepods.data.RemoteDeviceAudioGuardEvent
import me.kavishdevar.librepods.data.RemoteDeviceAudioGuardState
import me.kavishdevar.librepods.data.RemoteDeviceAudioSourceType
import me.kavishdevar.librepods.data.StemAction
import me.kavishdevar.librepods.data.XposedRemotePrefProvider
import me.kavishdevar.librepods.data.isHeadTrackingData
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
import me.kavishdevar.librepods.utils.XposedState
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
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "AirPodsService"
private const val A2DP_DIRECT_VOLUME_RESYNC_DELAY_MS = 350L
private const val A2DP_VOLUME_RESYNC_DEBOUNCE_MS = 1_000L
private const val A2DP_VOLUME_PULSE_MS = 180L
private const val AUTOMATIC_RECONNECT_RETRY_DELAY_MS = 1_500L
private const val PROFILE_REPAIR_INITIAL_DELAY_MS = 1_500L
private const val PROFILE_REPAIR_RETRY_DELAY_MS = 4_000L
private const val FRESH_IN_EAR_BLE_WINDOW_MS = 15_000L
private const val MEDIA_INFORMATION_REPORT_INTERVAL_MS = 8_000L
private const val UNKNOWN_PLAYING_APP = "NA"
private const val PLAYER_STATE_STARTED = 2
// Scheduling jitter is tens of milliseconds; anything past this means the process
// was not running when it should have been.
private const val GUARD_TICK_LATENESS_REPORT_MS = 750L
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
    private val remoteDeviceGuardScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class ConnectionNotificationContent(
        val title: String?,
        val text: String,
        val guardLine: String?,
    )
    private val connectionNotificationLock = Any()
    private var lastConnectionNotificationContent: ConnectionNotificationContent? = null
    private val remoteDeviceAudioGuard = RemoteDeviceAudioGuard()
    private data class RemoteDeviceGuardMessage(
        val event: RemoteDeviceAudioGuardEvent,
        val reason: String,
        val expectedLease: AacpSocketLease?,
    )
    private val remoteDeviceGuardEvents = Channel<RemoteDeviceGuardMessage>(Channel.UNLIMITED)
    private val remoteDeviceGuardConsumer = remoteDeviceGuardScope.launch {
        for (message in remoteDeviceGuardEvents) {
            processRemoteDeviceGuard(message.event, message.reason, message.expectedLease)
        }
    }
    private val remoteDeviceGuardHandler by lazy { Handler(Looper.getMainLooper()) }
    private val remoteDeviceRemotePreferences by lazy { XposedRemotePrefProvider.create() }
    private val remoteDeviceGuardTick = Runnable {
        reportGuardTickLateness()
        dispatchRemoteDeviceGuard(RemoteDeviceAudioGuardEvent.Tick, "deadline")
    }
    @Volatile
    private var guardTickDueAtMs = 0L
    private var loggedMissingNativeGuardTransport = false
    private var loggedUngatedNativeLease = false
    @Volatile
    private var audioProfileRepairDeferred = false
    @Volatile
    private var serviceShuttingDown = false
    @Volatile
    private var pendingAacpSocket: BluetoothSocket? = null
    @Volatile
    private var automaticMediaResumeAllowed = false
    private val connectionLifecycleLock = Any()
    private var aacpSessionJob: Job? = null
    private var aacpDelayedRefreshJob: Job? = null
    @Volatile
    private var mediaInformationReporterJob: Job? = null
    private data class LocalAudioPlaybackState(
        val playingApp: String = UNKNOWN_PLAYING_APP,
        val streamingState: Boolean = false,
    )
    @Volatile
    private var localAudioPlaybackState = LocalAudioPlaybackState()
    private var audioPlaybackMonitorRegistered = false
    private val audioPlaybackHandler by lazy { Handler(Looper.getMainLooper()) }
    private val audioPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            super.onPlaybackConfigChanged(configs)
            refreshLocalAudioPlaybackState(configs.orEmpty())
        }
    }
    private val reconnectEpisodeGate = ReconnectEpisodeGate()
    private val reconnectLifecycleGate = ReconnectLifecycleGate()
    @Volatile
    private var isAirPodsA2dpConnected = false
    private var isAirPodsA2dpPlaying = false
    private var spatialAudioTransitionId = 0
    private val spatialAudioTransitionMutex = Mutex()

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
                return false
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
                for (attempt in 0..1) {
                    if (socketConnected ||
                        !isAutomaticReconnectAllowed(
                            lifecycleToken,
                            reconnectEpisodeGeneration,
                        )
                    ) {
                        break
                    }
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
                    if (!socketConnected && attempt == 0) {
                        delay(AUTOMATIC_RECONNECT_RETRY_DELAY_MS)
                    }
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
                    Log.w(TAG, "Automatic AACP reconnect failed after bounded retry: $reason")
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
     * True while the AirPods report another host as their audio source. Connecting our own
     * audio profiles then would take the AirPods away from that host — the exact thing the
     * guard exists to prevent, except initiated by us instead of by the OS.
     */
    private fun remoteHostOwnsAudio(): Boolean {
        val state = remoteDeviceAudioGuard.snapshot.state
        return state == RemoteDeviceAudioGuardState.REMOTE_ACTIVE ||
            state == RemoteDeviceAudioGuardState.REMOTE_GRACE ||
            // Ownership is still unknown here and the barrier is already up, so grabbing the
            // audio profiles now would contradict the protection we are applying. The probe
            // resolves in well under two seconds and the repair runs then.
            state == RemoteDeviceAudioGuardState.PROBING
    }

    private fun requestBoundedAudioProfileRepair(
        reason: String,
        target: BluetoothDevice?,
        delayMs: Long = PROFILE_REPAIR_INITIAL_DELAY_MS,
    ) {
        val lifecycleToken = reconnectLifecycleGate.currentToken()
        if (target == null || lifecycleToken == null || isAirPodsA2dpConnected ||
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
                if (isAirPodsA2dpConnected ||
                    !isAutomaticReconnectAllowed(lifecycleToken, permit.generation) ||
                    !hasFreshInEarBlePresence() ||
                    BluetoothConnectionManager.aacpSocket?.isConnected != true
                ) {
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
                        isAutomaticReconnectAllowed(lifecycleToken, permit.generation) &&
                            hasFreshInEarBlePresence() &&
                            BluetoothConnectionManager.aacpSocket?.isConnected == true
                    },
                )
                if (permit.attempt == 1) {
                    delay(PROFILE_REPAIR_RETRY_DELAY_MS)
                    retryRequired = !isAirPodsA2dpConnected &&
                        isAutomaticReconnectAllowed(lifecycleToken, permit.generation) &&
                        hasFreshInEarBlePresence()
                }
            } finally {
                reconnectEpisodeGate.finishProfileRepair(
                    permit,
                    succeeded = isAirPodsA2dpConnected && attemptedConnect,
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
        reconnectLifecycleGate.suppress()
        cancelPendingAacpConnection()
    }

    private fun allowAutomaticReconnect() {
        reconnectLifecycleGate.allow()
    }

    private fun cancelPendingAacpConnection() {
        val pendingSocket = synchronized(connectionLifecycleLock) {
            pendingAacpSocket.also { pendingAacpSocket = null }
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

    private fun dispatchRemoteDeviceGuard(
        event: RemoteDeviceAudioGuardEvent,
        reason: String,
        expectedLease: AacpSocketLease? = null,
    ) {
        if (serviceShuttingDown) return
        if (event == RemoteDeviceAudioGuardEvent.AacpConnected ||
            event == RemoteDeviceAudioGuardEvent.AacpDisconnected ||
            event is RemoteDeviceAudioGuardEvent.EnabledChanged && event.enabled
        ) {
            automaticMediaResumeAllowed = false
        }
        val result = remoteDeviceGuardEvents.trySend(
            RemoteDeviceGuardMessage(event, reason, expectedLease)
        )
        if (result.isFailure && !serviceShuttingDown) {
            Log.e(TAG, "Could not enqueue remote-audio guard event: $event")
        }
    }

    private fun processRemoteDeviceGuard(
        event: RemoteDeviceAudioGuardEvent,
        reason: String,
        expectedLease: AacpSocketLease?,
    ) {
        if (serviceShuttingDown) return
        if (expectedLease != null && !BluetoothConnectionManager.isCurrent(expectedLease)) {
            Log.d(TAG, "Ignoring stale remote-audio guard event: $reason")
            return
        }
        val now = SystemClock.elapsedRealtime()
        val transition = remoteDeviceAudioGuard.dispatch(event, now)
            if (transition.previous.state != transition.current.state ||
                transition.actions.isNotEmpty()
            ) {
                Log.i(
                    TAG,
                    "Remote-device guard ${transition.previous.state} -> " +
                        "${transition.current.state}: $reason; actions=${transition.actions}"
                )
            }

            remoteDeviceGuardHandler.removeCallbacks(remoteDeviceGuardTick)
            guardTickDueAtMs = transition.nextDeadlineMs ?: 0L
            transition.nextDeadlineMs?.let { deadlineMs ->
                remoteDeviceGuardHandler.postDelayed(
                    remoteDeviceGuardTick,
                    (deadlineMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L),
                )
            }

            // Blocking A2DP START without a working speaker barrier does not move audio to
            // the speaker — it leaves the stream with nowhere to go and the phone goes
            // silent. So the native block must always be a strict subset of "barrier is
            // registered": establish or drop the barrier first, arm the block last, and
            // clear the block before the barrier goes away.
            transition.actions.sortedBy { action ->
                when (action) {
                    RemoteDeviceAudioGuardAction.ClearNativeLease -> 0
                    RemoteDeviceAudioGuardAction.EnableBarrier -> 1
                    RemoteDeviceAudioGuardAction.DisableBarrier -> 2
                    is RemoteDeviceAudioGuardAction.RefreshNativeLease -> 3
                    RemoteDeviceAudioGuardAction.RequestAudioSource -> 4
                }
            }.forEach { action ->
                if (serviceShuttingDown) return@forEach
                when (action) {
                    is RemoteDeviceAudioGuardAction.RefreshNativeLease -> {
                        if (!PhoneSoundRoutingController.status.active) {
                            if (!loggedUngatedNativeLease) {
                                loggedUngatedNativeLease = true
                                Log.w(
                                    TAG,
                                    "Not arming the A2DP START block: speaker barrier is " +
                                        "${PhoneSoundRoutingController.status.state}; blocking " +
                                        "without it would silence the phone instead of " +
                                        "routing to the speaker"
                                )
                            }
                            return@forEach
                        }
                        loggedUngatedNativeLease = false
                        runCatching {
                            if (remoteDeviceRemotePreferences.isAvailable()) {
                                remoteDeviceRemotePreferences.setA2dpStartGuardLease(action.durationMs)
                                loggedMissingNativeGuardTransport = false
                            } else if (!loggedMissingNativeGuardTransport) {
                                loggedMissingNativeGuardTransport = true
                                Log.w(
                                    TAG,
                                    "A2DP START guard transport unavailable; " +
                                        "speaker barrier remains active"
                                )
                            }
                        }.onFailure { error ->
                            Log.e(TAG, "Could not refresh native A2DP guard lease", error)
                        }
                    }

                    RemoteDeviceAudioGuardAction.EnableBarrier -> {
                        runCatching {
                            PhoneSoundRoutingController.setGuardEnabled(true, reason)
                        }.onFailure { error ->
                            Log.e(TAG, "Could not enable speaker barrier", error)
                        }
                    }

                    RemoteDeviceAudioGuardAction.DisableBarrier -> {
                        runCatching {
                            PhoneSoundRoutingController.setGuardEnabled(false, reason)
                        }.onFailure { error ->
                            Log.e(TAG, "Could not disable speaker barrier", error)
                        }
                    }

                    RemoteDeviceAudioGuardAction.RequestAudioSource -> {
                        aacpReaderScope.launch {
                            runCatching {
                                val requestLease = expectedLease
                                    ?: BluetoothConnectionManager.currentAacpSocketLease()
                                if (requestLease == null) {
                                    Log.d(
                                        TAG,
                                        "Skipping audio-source query without AACP: $reason"
                                    )
                                    return@runCatching
                                }
                                if (!aacpManager.requestAudioSource(requestLease)) {
                                    Log.w(
                                        TAG,
                                        "Audio-source query could not be sent: $reason"
                                    )
                                }
                            }.onFailure { error ->
                                Log.e(TAG, "Audio-source query failed: $reason", error)
                            }
                        }
                    }

                    RemoteDeviceAudioGuardAction.ClearNativeLease -> {
                        runCatching {
                            remoteDeviceRemotePreferences.setA2dpStartGuardLease(0L)
                        }.onFailure { error ->
                            Log.e(TAG, "Could not clear native A2DP guard lease", error)
                        }
                    }
                }
            }

        automaticMediaResumeAllowed = !transition.current.enabled ||
            transition.current.state == RemoteDeviceAudioGuardState.IDLE ||
            transition.current.state == RemoteDeviceAudioGuardState.LOCAL

        // A repair we skipped because another host owned the AirPods has to be retried once
        // they let go, otherwise the phone would sit without its audio profiles.
        if (audioProfileRepairDeferred && !remoteHostOwnsAudio()) {
            audioProfileRepairDeferred = false
            if (!isAirPodsA2dpConnected) {
                requestBoundedAudioProfileRepair(
                    reason = "remote_released",
                    target = resolveSavedAirPodsDevice(),
                    delayMs = 0L,
                )
            }
        }

        refreshGuardHealth()

        if (serviceShuttingDown) {
            runCatching {
                PhoneSoundRoutingController.setGuardEnabled(false, "service shutdown")
            }
            runCatching { remoteDeviceRemotePreferences.setA2dpStartGuardLease(0L) }
        }
    }

    /**
     * A frozen process still has its clocks advance, so an overdue deadline fires the
     * instant it is thawed. Reporting how late the tick was is the only way to see a
     * freeze after the fact — nothing can be logged while it is happening.
     */
    private fun reportGuardTickLateness() {
        val dueAt = guardTickDueAtMs
        if (dueAt <= 0L) return
        val latenessMs = SystemClock.elapsedRealtime() - dueAt
        if (latenessMs < GUARD_TICK_LATENESS_REPORT_MS) return
        Log.w(
            TAG,
            "Remote-device guard tick ran ${latenessMs}ms late; the process was most " +
                "likely frozen or starved, and the A2DP START block may have lapsed"
        )
    }

    private fun refreshGuardHealth() {
        val snapshot = remoteDeviceAudioGuard.snapshot
        val barrierStatus = PhoneSoundRoutingController.status
        val nativeTransportAvailable = runCatching {
            remoteDeviceRemotePreferences.isAvailable() && XposedState.bluetoothScopeEnabled
        }.getOrDefault(false)
        val health = GuardHealth.evaluate(
            enabled = snapshot.enabled,
            barrierState = barrierStatus.state,
            barrierActive = barrierStatus.active,
            nativeLeaseAvailable = nativeTransportAvailable,
            localMacResolved = localMac.isNotEmpty(),
            aacpConnected = snapshot.aacpConnected,
            guardState = snapshot.state,
            preferredRouteApplied = barrierStatus.preferredRouteApplied,
        )
        if (_guardHealth.value == health) return
        val levelChanged = _guardHealth.value.level != health.level ||
            _guardHealth.value.reasons != health.reasons
        _guardHealth.value = health
        if (levelChanged) Log.i(TAG, "Guard health: ${health.level} ${health.reasons}")

        // guardState flips between REMOTE_ACTIVE and REMOTE_GRACE about once a second while
        // a remote host streams, so refresh the notification only when the rendered line
        // actually changes — otherwise HyperOS unfolds the notification at 1 Hz.
        val guardLine = guardNotificationLine()
        if (guardLine == lastGuardNotificationLine) return
        lastGuardNotificationLine = guardLine
        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
            updateNotificationContent(true, config.deviceName, batteryNotification.getBattery())
        }
    }

    private fun startAudioPlaybackMonitor() {
        try {
            audioManager.registerAudioPlaybackCallback(
                audioPlaybackCallback,
                audioPlaybackHandler,
            )
            audioPlaybackMonitorRegistered = true
        } catch (error: Throwable) {
            Log.w(TAG, "Could not register audio playback monitor", error)
        }
        refreshLocalAudioPlaybackState()
    }

    private fun stopAudioPlaybackMonitor() {
        if (audioPlaybackMonitorRegistered) {
            runCatching {
                audioManager.unregisterAudioPlaybackCallback(audioPlaybackCallback)
            }.onFailure { error ->
                Log.w(TAG, "Could not unregister audio playback monitor", error)
            }
            audioPlaybackMonitorRegistered = false
        }
        if (::audioManager.isInitialized) {
            audioPlaybackHandler.removeCallbacksAndMessages(null)
        }
        localAudioPlaybackState = LocalAudioPlaybackState()
    }

    private fun refreshLocalAudioPlaybackState(
        fallbackConfigurations: Collection<AudioPlaybackConfiguration> = emptyList(),
    ) {
        if (!::audioManager.isInitialized) return
        val configurations = try {
            audioManager.activePlaybackConfigurations
        } catch (error: Throwable) {
            Log.w(TAG, "Could not read active playback configurations", error)
            fallbackConfigurations
        }
        val activeConfigurations = configurations.filter { configuration ->
            reflectedPlaybackInt(configuration, "getPlayerState", "mPlayerState")
                ?.let { it == PLAYER_STATE_STARTED }
                ?: true
        }
        val playingPackages = activeConfigurations.asSequence()
            .mapNotNull { configuration ->
                reflectedPlaybackInt(configuration, "getClientUid", "mClientUid")
            }
            .flatMap { uid -> packageManager.getPackagesForUid(uid).orEmpty().asSequence() }
            .distinct()
            .toList()
        val previousState = localAudioPlaybackState
        val playingApp = previousState.playingApp
            .takeIf { it != UNKNOWN_PLAYING_APP && it in playingPackages }
            ?: playingPackages.firstOrNull()
            ?: UNKNOWN_PLAYING_APP
        val newState = LocalAudioPlaybackState(
            playingApp = playingApp,
            streamingState = activeConfigurations.isNotEmpty(),
        )
        if (newState != previousState) {
            Log.d(
                TAG,
                "Local audio state changed: streaming=${newState.streamingState}, " +
                    "playingApp=${newState.playingApp}",
            )
            localAudioPlaybackState = newState
        }
    }

    private fun reflectedPlaybackInt(
        configuration: AudioPlaybackConfiguration,
        methodName: String,
        fieldName: String,
    ): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                configuration.javaClass.getMethod(methodName).invoke(configuration) as Int
            }.getOrNull()?.let { return it }
        }
        return runCatching {
            configuration.javaClass.getDeclaredField(fieldName).let { field ->
                field.isAccessible = true
                field.getInt(configuration)
            }
        }.getOrNull()
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
        startAudioPlaybackMonitor()
        PhoneSoundRoutingController.initialize(this, audioManager)
        dispatchRemoteDeviceGuard(
            RemoteDeviceAudioGuardEvent.EnabledChanged(
                sharedPreferences.getBoolean("remote_device_audio_guard_enabled", true)
            ),
            "service initialized",
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

    fun setupStemActions(expectedLease: AacpSocketLease? = null) {
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
            expectedLease,
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
//                CrossDevice.sendRemotePacket(batteryInfo)
//                CrossDevice.batteryBytes = batteryInfo

                for (battery in batteryNotification.getBattery()) {
                    Log.d(
                        "AirPodsParser",
                        "${battery.getComponentName()}: ${battery.getStatusName()} at ${battery.level}% "
                    )
                }

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
                Log.d(TAG, "Remote ownership release requested by $sender")
                aacpManager.sendControlCommand(
                    AACPManager.Companion.ControlCommandIdentifiers.OWNS_CONNECTION.value,
                    byteArrayOf(0x00),
                    expectedLease,
                )
            }

            override fun onRemoteStreamingStateChanged(
                sender: String,
                isStreaming: Boolean,
                expectedLease: AacpSocketLease,
            ) {
                Log.d(TAG, "Remote streaming state from $sender: $isStreaming")
                dispatchRemoteDeviceGuard(
                    RemoteDeviceAudioGuardEvent.RemoteStreamingChanged(
                        localMac = localMac,
                        sourceMac = sender,
                        isStreaming = isStreaming,
                    ),
                    "remote streaming=$isStreaming from $sender",
                    expectedLease,
                )
            }

            override fun onShowNearbyUI(sender: String) {
                Log.d(TAG, "Ignoring legacy Smart Routing UI request from $sender")
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
                updateNotificationContent(
                    true,
                    deviceInformation.name,
                    batteryNotification.getBattery(),
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
                val sourceType = when (audioSource?.type) {
                    AACPManager.Companion.AudioSourceType.NONE ->
                        RemoteDeviceAudioSourceType.NONE
                    AACPManager.Companion.AudioSourceType.CALL ->
                        RemoteDeviceAudioSourceType.CALL
                    AACPManager.Companion.AudioSourceType.MEDIA ->
                        RemoteDeviceAudioSourceType.MEDIA
                    AACPManager.Companion.AudioSourceType.UNKNOWN ->
                        RemoteDeviceAudioSourceType.ACTIVE_UNKNOWN
                    null -> RemoteDeviceAudioSourceType.UNKNOWN
                }
                dispatchRemoteDeviceGuard(
                    RemoteDeviceAudioGuardEvent.AudioSourceObserved(
                        localMac = localMac,
                        sourceMac = audioSource?.mac,
                        type = sourceType,
                    ),
                    "audio source ${audioSource?.mac}/${audioSource?.type}",
                    expectedLease,
                )
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
                } else {
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
            "remote_device_audio_guard_enabled" -> dispatchRemoteDeviceGuard(
                RemoteDeviceAudioGuardEvent.EnabledChanged(preferences.getBoolean(key, true)),
                "setting changed",
            )
            "automatic_ear_detection" -> config.earDetectionEnabled =
                preferences.getBoolean(key, true)

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
        val guardLine = guardNotificationLine()
        val content = ConnectionNotificationContent(title, text, guardLine)

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
                    GuardHealthReason.DISABLED_BY_USER -> R.string.guard_health_reason_disabled
                }
            )
        }
        // Only speak up while the guard is actually trying to protect something. A standing
        // "not active" line would otherwise sit in the notification forever on installs
        // without the root module, drowning out the line that matters.
        val protecting = health.guardState == RemoteDeviceAudioGuardState.REMOTE_ACTIVE ||
            health.guardState == RemoteDeviceAudioGuardState.REMOTE_GRACE
        if (!protecting) return null
        return when (health.level) {
            GuardHealthLevel.INACTIVE ->
                getString(R.string.guard_notification_inactive, reasonText.orEmpty())
            // The speaker barrier still holds; only the native A2DP block is missing.
            else -> getString(R.string.guard_notification_protecting)
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
                } else {
                    rejectCall()
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

    private fun startMediaInformationReporter(lease: AacpSocketLease) {
        stopMediaInformationReporter()
        if (!BluetoothConnectionManager.isCurrent(lease)) return
        // Smart Routing peers use this periodic report as status, not as an ownership request.
        mediaInformationReporterJob = aacpReaderScope.launch {
            while (true) {
                if (!BluetoothConnectionManager.isCurrent(lease)) break
                delay(MEDIA_INFORMATION_REPORT_INTERVAL_MS)
                if (!BluetoothConnectionManager.isCurrent(lease)) break
                reportMediaInformation(lease)
            }
        }
    }

    private fun stopMediaInformationReporter() {
        mediaInformationReporterJob?.cancel()
        mediaInformationReporterJob = null
    }

    private fun reportMediaInformation(lease: AacpSocketLease) {
        val selfMacAddress = LocalHostIdentity.normalize(config.selfMacAddress)
            ?: LocalHostIdentity.normalize(localMac)
            ?: run {
                Log.d(TAG, "Skipping Media Information report without a local MAC address")
                return
            }
        val targetMacAddress = aacpManager.resolveSmartRoutingTarget(selfMacAddress) ?: run {
            Log.d(TAG, "Skipping Media Information report without a remote Smart Routing host")
            return
        }
        val playbackState = localAudioPlaybackState
        val sent = aacpManager.sendDataPacket(
            aacpManager.createMediaInformationPacket(
                selfMacAddress = selfMacAddress,
                targetMacAddress = targetMacAddress,
                playingApp = playbackState.playingApp,
                streamingState = playbackState.streamingState,
            ),
            lease,
        )
        if (!sent && BluetoothConnectionManager.isCurrent(lease)) {
            Log.w(TAG, "Could not send periodic Smart Routing Media Information report")
        }
    }

    @Synchronized
    @SuppressLint("MissingPermission", "UnspecifiedRegisterReceiverFlag")
    fun connectToSocket(
        adapter: BluetoothAdapter,
        device: BluetoothDevice,
        manual: Boolean = false,
        connectionGuard: (() -> Boolean)? = null,
    ) {
        if (connectionGuard?.invoke() == false || serviceShuttingDown) return
        if (BluetoothConnectionManager.aacpSocket?.isConnected == true) {
            if (mediaInformationReporterJob?.isActive != true) {
                BluetoothConnectionManager.currentAacpSocketLease()?.let {
                    startMediaInformationReporter(it)
                }
            }
            dispatchRemoteDeviceGuard(
                RemoteDeviceAudioGuardEvent.AacpConnected,
                "existing AACP socket",
            )
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
                            runCatching { socket.close() }
                            return@withTimeout
                        }
                        val installedLease = synchronized(connectionLifecycleLock) {
                            if (serviceShuttingDown || connectionGuard?.invoke() == false) {
                                return@synchronized null
                            }
                            aacpSessionJob?.cancel()
                            aacpDelayedRefreshJob?.cancel()
                            stopMediaInformationReporter()
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
                                dispatchRemoteDeviceGuard(
                                    RemoteDeviceAudioGuardEvent.AacpConnected,
                                    "AACP socket connected",
                                )
                            }
                            lease
                        }
                        if (installedLease == null) {
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
                        if (pendingAacpSocket === socket) pendingAacpSocket = null
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
                } ?: runCatching { socket.close() }
                return
            }
            if (!socket.isConnected) {
                if (pendingAacpSocket === socket) pendingAacpSocket = null
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
                startMediaInformationReporter(lease)
                aacpSessionJob = aacpReaderScope.launch {
                    fun startupSuperseded(): Boolean {
                        val superseded = connectionGuard?.invoke() == false ||
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
                        startHeadTracking(lease)
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
                        stopHeadTracking(expectedLease = lease)
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
                            var data: ByteArray
                            if (bytesRead > 0) {
                                data = buffer.copyOfRange(0, bytesRead)
                                val formattedHex = data.joinToString(" ") { "%02X".format(it) }
                                if (!BluetoothConnectionManager.isCurrent(lease)) {
                                    Log.d(TAG, "Ignoring packet from stale AACP reader")
                                    runCatching { socket.close() }
                                    return@launch
                                }
                                aacpManager.receivePacket(data, lease)

                                sendBroadcast(Intent(AirPodsNotifications.AIRPODS_DATA).apply {
                                    putExtra("data", data)
                                    setPackage(packageName)
                                })
//                                    CrossDevice.sendReceivedPacket(data)

                                if (!isHeadTrackingData(data)) {
                                    Log.d("AirPodsData", "Data received: $formattedHex")
                                    logPacket(data, "AirPods")
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
            if (pendingAacpSocket === socket) pendingAacpSocket = null
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
            dispatchRemoteDeviceGuard(
                RemoteDeviceAudioGuardEvent.AacpDisconnected,
                "AACP $reason",
            )
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
        stopMediaInformationReporter()
        isAirPodsA2dpPlaying = false
        cancelPendingAirPodsAbsoluteVolumeResync("AACP $reason")
        rootAvrcpVolumeController.cancel()
        updateSpatialAudioTracking("AACP $reason")
        device = null
        popupShown = false
    }

    fun disconnectAirPods() {
        suppressAutomaticReconnect()
        var targetDevice: BluetoothDevice? = null
        val disconnectedCurrentSession = synchronized(connectionLifecycleLock) {
            val currentLease = BluetoothConnectionManager.currentAacpSocketLease()
                ?: return@synchronized false
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
            dispatchRemoteDeviceGuard(
                RemoteDeviceAudioGuardEvent.AacpDisconnected,
                "user disconnected AirPods",
            )
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

    fun connectAudio(
        context: Context,
        device: BluetoothDevice?,
        requestGuard: (() -> Boolean)? = null,
    ) {
        if (device == null || requestGuard?.invoke() == false || serviceShuttingDown) return
        val bluetoothAdapter = context.getSystemService(BluetoothManager::class.java).adapter

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.A2DP) return
                try {
                    if (requestGuard?.invoke() == false || serviceShuttingDown) return
                    if (context.checkSelfPermission("android.permission.BLUETOOTH_PRIVILEGED") == PackageManager.PERMISSION_GRANTED) {
                        val policyMethod = proxy.javaClass.getMethod(
                            "setConnectionPolicy",
                            BluetoothDevice::class.java,
                            Int::class.java,
                        )
                        Log.d(TAG, "calling A2DP.setConnectionPolicy for ${device.address} to 100")
                        policyMethod.invoke(proxy, device, 100)
                    } else {
                        Log.d(TAG, "not setting connection policy for A2DP, no BLUETOOTH_PRIVILEGED permission. just called connect")
                    }
                    if (requestGuard?.invoke() == false || serviceShuttingDown) return
                    val connectMethod =
                        proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                    connectMethod.invoke(proxy, device)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not reconnect AirPods A2DP profile", e)
                } finally {
                    bluetoothAdapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)

        bluetoothAdapter?.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                if (profile != BluetoothProfile.HEADSET) return
                try {
                    if (requestGuard?.invoke() == false || serviceShuttingDown) return
                    if (checkSelfPermission("android.permission.MODIFY_PHONE_STATE") == PackageManager.PERMISSION_GRANTED) {
                        val policyMethod = proxy.javaClass.getMethod(
                            "setConnectionPolicy",
                            BluetoothDevice::class.java,
                            Int::class.java,
                        )
                        Log.d(
                            TAG,
                            "calling HEADSET.setConnectionPolicy for ${device.address} to 100"
                        )
                        policyMethod.invoke(proxy, device, 100)
                        if (requestGuard?.invoke() == false || serviceShuttingDown) return
                        val connectMethod =
                            proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                        connectMethod.invoke(proxy, device)
                    } else {
                        Log.d(TAG, "not setting connection policy for HEADSET, no MODIFIY_PHONE_STATE permission")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not reconnect AirPods HEADSET profile", e)
                } finally {
                    bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
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
        clearPacketLogs()
        Log.d(TAG, "Service stopped is being destroyed for some reason!")

        serviceShuttingDown = true
        suppressAutomaticReconnect()
        remoteDeviceGuardHandler.removeCallbacks(remoteDeviceGuardTick)
        remoteDeviceGuardScope.cancel()
        runCatching {
            PhoneSoundRoutingController.setGuardEnabled(false, "service destroyed")
        }.onFailure { error ->
            Log.e(TAG, "Could not disable speaker barrier during shutdown", error)
        }
        runCatching {
            remoteDeviceRemotePreferences.setA2dpStartGuardLease(0L)
        }.onFailure { error ->
            Log.e(TAG, "Could not clear native A2DP guard during shutdown", error)
        }
        PhoneSoundRoutingController.shutdown()
        synchronized(connectionLifecycleLock) {
            aacpSessionJob?.cancel()
            aacpDelayedRefreshJob?.cancel()
            stopMediaInformationReporter()
            val currentLease = BluetoothConnectionManager.currentAacpSocketLease()
            BluetoothConnectionManager.invalidateAacpSocket()
            BluetoothConnectionManager.invalidateAttSocket()
            runCatching { pendingAacpSocket?.close() }
            pendingAacpSocket = null
            aacpManager.disconnected(currentLease)
        }
        aacpReaderScope.cancel()
        stopAudioPlaybackMonitor()
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

    fun startHeadTracking(expectedLease: AacpSocketLease? = null) {
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
            if (expectedLease == null) {
                aacpManager.sendDataPacket(aacpManager.createAlternateStartHeadTrackingPacket())
            } else {
                aacpManager.sendDataPacket(
                    aacpManager.createAlternateStartHeadTrackingPacket(),
                    expectedLease,
                )
            }
        } else {
            if (expectedLease == null) {
                aacpManager.sendStartHeadTracking()
            } else {
                aacpManager.sendStartHeadTracking(expectedLease)
            }
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

    fun stopHeadTracking(
        force: Boolean = false,
        expectedLease: AacpSocketLease? = null,
    ) {
        if (!force && shouldRunSpatialAudio()) {
            Log.d(TAG, "Keeping head tracking active for spatial audio")
            return
        }
        val useAlternatePackets =
            sharedPreferences.getBoolean("use_alternate_head_tracking_packets", true)
        if (useAlternatePackets) {
            if (expectedLease == null) {
                aacpManager.sendDataPacket(aacpManager.createAlternateStopHeadTrackingPacket())
            } else {
                aacpManager.sendDataPacket(
                    aacpManager.createAlternateStopHeadTrackingPacket(),
                    expectedLease,
                )
            }
        } else {
            if (expectedLease == null) {
                aacpManager.sendStopHeadTracking()
            } else {
                aacpManager.sendStopHeadTracking(expectedLease)
            }
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
            aacpReaderScope.launch {
                Log.d(TAG, "connecting to $macAddress")
                connectToSocket(bluetoothAdapter, device!!, manual = true)
                if (!serviceShuttingDown &&
                    BluetoothConnectionManager.aacpSocket?.isConnected == true
                ) {
                    connectAudio(this@AirPodsService, device!!)
                }
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
