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

package me.kavishdevar.librepods.utils

import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import me.kavishdevar.librepods.data.FailedPauseSettlementMachine
import me.kavishdevar.librepods.data.FailedPauseSettlementAction
import me.kavishdevar.librepods.data.FailedPauseSettlementState
import me.kavishdevar.librepods.data.EarDetectionResumeGate
import me.kavishdevar.librepods.data.EarDetectionPauseRequestGate
import me.kavishdevar.librepods.data.ExplicitResumeRouteGate
import me.kavishdevar.librepods.data.LocalRouteAlreadyActiveAction
import me.kavishdevar.librepods.data.LocalRouteAlreadyActiveGate
import me.kavishdevar.librepods.data.PendingPlaybackDeadlineGate
import me.kavishdevar.librepods.data.PlaybackIdentity
import me.kavishdevar.librepods.data.SmartRoutingAudioCategory
import me.kavishdevar.librepods.data.SmartRoutingAudioPolicyPreferences
import me.kavishdevar.librepods.data.TakeoverCompletionGate
import me.kavishdevar.librepods.data.TakeoverCompletionState
import me.kavishdevar.librepods.data.TakeoverPausePolicyGate
import me.kavishdevar.librepods.data.TakeoverRouteTimeoutAction
import me.kavishdevar.librepods.data.TakeoverRouteTimeoutMachine
import me.kavishdevar.librepods.data.TakeoverRouteTimeoutState
import me.kavishdevar.librepods.data.TargetAudioOutput
import me.kavishdevar.librepods.data.TargetAudioOutputClassifier
import me.kavishdevar.librepods.data.TargetRouteEvidence
import me.kavishdevar.librepods.diagnostics.DebounceEvent
import me.kavishdevar.librepods.diagnostics.PlaybackConfigurationTrace
import me.kavishdevar.librepods.diagnostics.RoutingCorrelation
import me.kavishdevar.librepods.diagnostics.RoutingEventDetail
import me.kavishdevar.librepods.diagnostics.RoutingTrace
import me.kavishdevar.librepods.services.ServiceManager
import kotlin.io.encoding.ExperimentalEncodingApi

object MediaController {
    private enum class PlaybackEpisode {
        INACTIVE,
        PENDING,
        ATTEMPTED
    }

    private data class PlaybackSnapshot(
        val activeUsages: Set<Int>,
        val earDetectionMediaActive: Boolean,
        val eligibleUsages: Set<Int>,
        val eligibleConfigurationCount: Int,
        val eligiblePlayers: Set<PlaybackIdentity>,
        val eligibleOutputRoutes: Set<TargetAudioOutput>,
        val eligiblePlaybackRoutedToTarget: Boolean,
    )

    private var initialVolume: Int? = null
    private lateinit var audioManager: AudioManager
    private var initialized = false
    var iPausedTheMedia = false
    var userPlayedTheMedia = false
    private lateinit var sharedPreferences: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener

    var pausedWhileTakingOver = false
    private var takeoverAwaitingRoute = false
    private var takeoverCompletionState = TakeoverCompletionState()
    private var failedPauseSettlementState = FailedPauseSettlementState()

    private var lastSelfActionAt: Long = 0L
    private const val UNKNOWN_MEDIA_ROUTE_SETTLE_MS = 100L
    private const val SHORT_PLAYBACK_DEBOUNCE_MS = 300L
    // HyperOS can drop and re-establish A2DP several seconds after the AACP request. The
    // previous 5 s deadline expired before the observed 9-10 s reconnect completed.
    private const val OWNERSHIP_CONFIRMATION_TIMEOUT_MS = 12_000L
    private const val TAKEOVER_PAUSE_BARRIER_TIMEOUT_MS = 3_000L
    private const val FAILED_PAUSE_QUIET_DRAIN_MS = 500L
    private const val FAILED_PAUSE_HARD_TIMEOUT_MS = 1_500L
    private const val EAR_DETECTION_PAUSE_QUIET_MS = 1_500L
    private const val EAR_DETECTION_RESUME_TIMEOUT_MS = 3_000L
    private const val PLAYER_STATE_STARTED = 2
    private val PLAYER_INTERFACE_ID_PATTERN =
        Regex("""\bpiid\s*[:=]\s*(\d+)""", RegexOption.IGNORE_CASE)
    private var playbackEpisode = PlaybackEpisode.INACTIVE
    private var pendingPlaybackGeneration = 0L
    private var pendingPlaybackRunnable: Runnable? = null
    private var pendingPlaybackDelayMs: Long? = null
    private var pendingPlaybackDeadlineAt = 0L
    private var pendingEligiblePlayers: Set<PlaybackIdentity> = emptySet()
    private var ownershipConfirmationTimeoutRunnable: Runnable? = null
    private var takeoverPauseQuietDrainRunnable: Runnable? = null
    private var takeoverPauseQuietDrainGeneration = 0L
    private var takeoverPauseHardTimeoutRunnable: Runnable? = null
    private var takeoverPauseHardTimeoutGeneration = 0L
    private var failedPauseQuietDrainRunnable: Runnable? = null
    private var failedPauseHardTimeoutRunnable: Runnable? = null
    private var earDetectionPauseOwned = false
    private var earDetectionPauseGeneration = 0L
    private var earDetectionResumeTimerGeneration = 0L
    private var earDetectionPlaybackInactiveSince = 0L
    private var earDetectionResumeRequested = false
    private var earDetectionRouteAvailable = false
    private var earDetectionResumeRunnable: Runnable? = null
    private var earDetectionResumeTimeoutRunnable: Runnable? = null
    private var takeoverRouteTimeoutState = TakeoverRouteTimeoutState()
    private var eligiblePlaybackActive = false
    private var takeoverAttemptedInEpisode = false
    private var playbackCycleCounter = 0L
    private var currentPlaybackCycleId: String? = null
    private var currentTakeoverAttemptId: String? = null
    private var awaitingExplicitUserPlayAfterTakeover = false
    private var reportedStreamingCorrelation: RoutingCorrelation? = null
    private var routingSettingsRevision = 0L

    private var relativeVolume: Boolean = false
    private var conversationalAwarenessVolume: Int = 2
    private var conversationalAwarenessPauseMusic: Boolean = false

    @Synchronized
    fun initialize(audioManager: AudioManager, sharedPreferences: SharedPreferences) {
        if (initialized) shutdown()
        this.audioManager = audioManager
        this.sharedPreferences = sharedPreferences
        initialized = true
        Log.d("MediaController", "Initializing MediaController")
        relativeVolume = sharedPreferences.getBoolean("relative_conversational_awareness_volume", false)
        conversationalAwarenessVolume = sharedPreferences.getInt("conversational_awareness_volume", (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 0.4).toInt())
        conversationalAwarenessPauseMusic = sharedPreferences.getBoolean("conversational_awareness_pause_music", false)

        preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "relative_conversational_awareness_volume" -> {
                    relativeVolume = sharedPreferences.getBoolean("relative_conversational_awareness_volume", false)
                }
                "conversational_awareness_volume" -> {
                    conversationalAwarenessVolume = sharedPreferences.getInt("conversational_awareness_volume", (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.4).toInt())
                }
                "conversational_awareness_pause_music" -> {
                    conversationalAwarenessPauseMusic = sharedPreferences.getBoolean("conversational_awareness_pause_music", false)
                }
                SmartRoutingAudioPolicyPreferences.MASTER_ENABLED_KEY,
                SmartRoutingAudioPolicyPreferences.ENABLED_CATEGORIES_KEY -> {
                    resetRoutingEpisodeForPolicyChange()
                }
            }
        }

        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        audioManager.registerAudioPlaybackCallback(cb, handler)
        suppressCurrentlyPlayingEligibleAudio()
    }

    @Synchronized
    fun shutdown() {
        if (!initialized) return
        reportedStreamingCorrelation?.let {
            ServiceManager.getService()?.onEligibleLocalPlaybackStopped(it)
        }
        reportedStreamingCorrelation = null
        cancelPendingPlaybackEvaluation("controller_shutdown")
        handler.removeCallbacksAndMessages(null)
        runCatching { audioManager.unregisterAudioPlaybackCallback(cb) }
        if (this::preferenceChangeListener.isInitialized && this::sharedPreferences.isInitialized) {
            runCatching {
                sharedPreferences.unregisterOnSharedPreferenceChangeListener(
                    preferenceChangeListener
                )
            }
        }
        ownershipConfirmationTimeoutRunnable = null
        takeoverPauseQuietDrainRunnable = null
        takeoverPauseQuietDrainGeneration = 0L
        takeoverPauseHardTimeoutRunnable = null
        takeoverPauseHardTimeoutGeneration = 0L
        failedPauseQuietDrainRunnable = null
        failedPauseHardTimeoutRunnable = null
        earDetectionPauseOwned = false
        earDetectionPauseGeneration = 0L
        earDetectionResumeTimerGeneration = 0L
        earDetectionPlaybackInactiveSince = 0L
        earDetectionResumeRequested = false
        earDetectionRouteAvailable = false
        earDetectionResumeRunnable = null
        earDetectionResumeTimeoutRunnable = null
        takeoverRouteTimeoutState = TakeoverRouteTimeoutState()
        pendingPlaybackRunnable = null
        pendingPlaybackDelayMs = null
        pendingPlaybackDeadlineAt = 0L
        pendingEligiblePlayers = emptySet()
        takeoverAwaitingRoute = false
        clearTakeoverCompletionBarriers()
        failedPauseSettlementState = FailedPauseSettlementState()
        pausedWhileTakingOver = false
        iPausedTheMedia = false
        userPlayedTheMedia = false
        playbackEpisode = PlaybackEpisode.INACTIVE
        eligiblePlaybackActive = false
        takeoverAttemptedInEpisode = false
        currentPlaybackCycleId = null
        currentTakeoverAttemptId = null
        awaitingExplicitUserPlayAfterTakeover = false
        initialized = false
    }

    val cb = object : AudioManager.AudioPlaybackCallback() {
        @RequiresApi(Build.VERSION_CODES.R)
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            super.onPlaybackConfigChanged(configs)
            if (!initialized) return
            if (RoutingTrace.isEnabled) {
                val callbackConfigs = configs.orEmpty().toList()
                tracePlaybackSnapshot(
                    callbackConfigs,
                    snapshotFrom(callbackConfigs),
                    observationSource = "callback_payload"
                )
            }
            // The callback argument can describe the transition that triggered this callback,
            // while the manager may already have advanced to a newer active-player snapshot.
            // Prefer the public current snapshot for the routing decision; only fall back to the
            // callback payload if the platform rejects the query.
            val currentConfigs = try {
                audioManager.activePlaybackConfigurations
            } catch (error: Throwable) {
                Log.w("MediaController", "Could not read current playback configurations", error)
                configs.orEmpty()
            }
            val snapshot = snapshotFrom(currentConfigs)
            tracePlaybackSnapshot(
                currentConfigs,
                snapshot,
                observationSource = "decision_snapshot"
            )
            handlePlaybackSnapshot(snapshot)
        }
    }

    private fun snapshotFrom(
        configs: Collection<AudioPlaybackConfiguration>
    ): PlaybackSnapshot {
        val startedConfigs = configs.filter(::isStartedConfiguration)
        val activeUsages = startedConfigs.asSequence()
            .map { it.audioAttributes.usage }
            .toSet()
        val policy = SmartRoutingAudioPolicyPreferences.read(sharedPreferences)
        val masterEnabled = sharedPreferences.getBoolean(
            SmartRoutingAudioPolicyPreferences.MASTER_ENABLED_KEY,
            false
        )
        val eligibleConfigurations = if (masterEnabled) {
            startedConfigs.filter { policy.allowsPlatformUsage(it.audioAttributes.usage) }
        } else {
            emptyList()
        }
        val eligibleUsages = eligibleConfigurations.asSequence()
            .map { it.audioAttributes.usage }
            .toCollection(linkedSetOf())
        val eligiblePlayers = eligibleConfigurations.mapTo(linkedSetOf()) {
            PlaybackIdentity(
                playerId = playerInterfaceId(it),
                usage = it.audioAttributes.usage,
            )
        }
        val targetAddress = sharedPreferences.getString("mac_address", null)
        val eligibleOutputRoutes = eligibleConfigurations.mapTo(linkedSetOf()) {
            classifyTargetOutput(it, targetAddress)
        }
        return PlaybackSnapshot(
            activeUsages = activeUsages,
            earDetectionMediaActive = activeUsages.any {
                it == AudioAttributes.USAGE_MEDIA || it == AudioAttributes.USAGE_GAME
            },
            eligibleUsages = eligibleUsages,
            eligibleConfigurationCount = eligibleConfigurations.size,
            eligiblePlayers = eligiblePlayers,
            eligibleOutputRoutes = eligibleOutputRoutes,
            eligiblePlaybackRoutedToTarget =
                TargetAudioOutputClassifier.confirmsTarget(eligibleOutputRoutes),
        )
    }

    private fun classifyTargetOutput(
        configuration: AudioPlaybackConfiguration,
        targetAddress: String?,
    ): TargetAudioOutput {
        val output = configuration.audioDeviceInfo
        return TargetAudioOutputClassifier.classify(
            isBluetoothA2dp = output?.let {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            },
            outputAddress = output?.address,
            targetAddress = targetAddress,
        )
    }

    private fun playerState(config: AudioPlaybackConfiguration): Int? = runCatching {
        config.javaClass.getMethod("getPlayerState").invoke(config) as Int
    }.getOrNull()

    private fun reflectedInt(
        config: AudioPlaybackConfiguration,
        methodName: String,
        fieldName: String,
    ): Int? {
        runCatching {
            return config.javaClass.getMethod(methodName).invoke(config) as Int
        }
        return runCatching {
            config.javaClass.getDeclaredField(fieldName).let { field ->
                field.isAccessible = true
                field.getInt(config)
            }
        }.getOrNull()
    }

    private fun playerInterfaceId(config: AudioPlaybackConfiguration): Int {
        reflectedInt(config, "getPlayerInterfaceId", "mPlayerIId")?.let { return it }
        PLAYER_INTERFACE_ID_PATTERN.find(config.toString())
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.let { return it }

        val uid = reflectedInt(config, "getClientUid", "mClientUid")
        val pid = reflectedInt(config, "getClientPid", "mClientPid")
        val sessionId = reflectedInt(config, "getSessionId", "mSessionId")
        if (uid != null || pid != null || sessionId != null) {
            return listOf(uid ?: 0, pid ?: 0, sessionId ?: 0).hashCode()
        }

        val attributes = config.audioAttributes
        return listOf(attributes.usage, attributes.contentType, attributes.flags).hashCode()
    }

    private fun isStartedConfiguration(config: AudioPlaybackConfiguration): Boolean =
        playerState(config)?.let { it == PLAYER_STATE_STARTED } ?: true

    private fun playerStateName(state: Int?): String? = when (state) {
        null -> null
        0 -> "UNKNOWN"
        1 -> "RELEASED"
        PLAYER_STATE_STARTED -> "STARTED"
        3 -> "PAUSED"
        4 -> "STOPPED"
        5 -> "IDLE"
        else -> "UNMAPPED_$state"
    }

    private fun tracePlaybackSnapshot(
        configs: Collection<AudioPlaybackConfiguration>,
        snapshot: PlaybackSnapshot,
        observationSource: String,
    ) {
        RoutingTrace.record(currentCorrelation()) {
            val policy = SmartRoutingAudioPolicyPreferences.read(sharedPreferences)
            val masterEnabled = sharedPreferences.getBoolean(
                SmartRoutingAudioPolicyPreferences.MASTER_ENABLED_KEY,
                false
            )
            val targetAddress = sharedPreferences.getString("mac_address", null)
            RoutingEventDetail.PlaybackSnapshot(
                observationSource = observationSource,
                configurations = configs.map { config ->
                    val attributes = config.audioAttributes
                    val usage = attributes.usage
                    val state = playerState(config)
                    val category = SmartRoutingAudioCategory.fromPlatformUsage(usage)
                    val policyEnabled = policy.allowsPlatformUsage(usage)
                    PlaybackConfigurationTrace(
                        usage = usage,
                        usageName = category?.name ?: "UNMAPPED_$usage",
                        contentType = attributes.contentType,
                        contentTypeName = contentTypeName(attributes.contentType),
                        outputDeviceType = config.audioDeviceInfo?.type,
                        outputDeviceTypeName = config.audioDeviceInfo?.type?.let {
                            "AUDIO_DEVICE_$it"
                        },
                        targetDeviceMatched = when (
                            classifyTargetOutput(config, targetAddress)
                        ) {
                            TargetAudioOutput.TARGET -> true
                            TargetAudioOutput.OTHER -> false
                            TargetAudioOutput.UNKNOWN -> null
                        },
                        playerState = state,
                        playerStateName = playerStateName(state),
                        policyEnabled = policyEnabled,
                        eligible = masterEnabled && policyEnabled,
                        reason = when {
                            category == null -> "unmapped_usage_fail_closed"
                            !masterEnabled -> "automatic_takeover_disabled"
                            !policyEnabled -> "category_disabled"
                            else -> "category_enabled"
                        }
                    )
                },
                configurationCount = configs.size,
                eligibleConfigurationCount = snapshot.eligibleConfigurationCount,
                eligiblePlaybackActive = snapshot.eligibleUsages.isNotEmpty(),
                eligiblePlaybackRoutedToTarget =
                    snapshot.eligiblePlaybackRoutedToTarget,
            )
        }
    }

    private fun contentTypeName(contentType: Int): String = when (contentType) {
        android.media.AudioAttributes.CONTENT_TYPE_UNKNOWN -> "UNKNOWN"
        android.media.AudioAttributes.CONTENT_TYPE_SPEECH -> "SPEECH"
        android.media.AudioAttributes.CONTENT_TYPE_MUSIC -> "MUSIC"
        android.media.AudioAttributes.CONTENT_TYPE_MOVIE -> "MOVIE"
        android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION -> "SONIFICATION"
        else -> "UNMAPPED_$contentType"
    }

    private fun currentCorrelation(): RoutingCorrelation = RoutingCorrelation(
        playbackCycleId = currentPlaybackCycleId,
        takeoverAttemptId = currentTakeoverAttemptId
    )

    @Synchronized
    fun currentTakeoverCorrelation(): RoutingCorrelation = currentCorrelation()

    @Synchronized
    fun isCurrentTakeoverAttempt(correlation: RoutingCorrelation): Boolean =
        playbackEpisode == PlaybackEpisode.ATTEMPTED &&
            !failedPauseSettlementState.awaitingOutstandingPause &&
            correlation.playbackCycleId != null &&
            correlation.takeoverAttemptId != null &&
            correlation.playbackCycleId == currentPlaybackCycleId &&
            correlation.takeoverAttemptId == currentTakeoverAttemptId

    private fun isTrackingTakeoverRoute(): Boolean =
        takeoverAwaitingRoute || failedPauseSettlementState.awaitingOutstandingPause

    @Synchronized
    fun isObservingTakeoverRoute(correlation: RoutingCorrelation): Boolean =
        playbackEpisode == PlaybackEpisode.ATTEMPTED &&
            isTrackingTakeoverRoute() &&
            correlation.playbackCycleId != null &&
            correlation.takeoverAttemptId != null &&
            correlation.playbackCycleId == currentPlaybackCycleId &&
            correlation.takeoverAttemptId == currentTakeoverAttemptId

    @Synchronized
    fun isAwaitingLocalRoute(): Boolean = takeoverAwaitingRoute

    @Synchronized
    fun isObservingLocalRoute(): Boolean = isTrackingTakeoverRoute()

    @Synchronized
    fun requestEarDetectionPause(): Boolean {
        if (!initialized) return false
        val decision = EarDetectionPauseRequestGate.decide(
            pauseOwned = earDetectionPauseOwned,
            playbackActive = snapshotFrom(audioManager.activePlaybackConfigurations)
                .earDetectionMediaActive,
            anotherPauseOwnerActive = takeoverAwaitingRoute ||
                failedPauseSettlementState.awaitingOutstandingPause,
        )
        if (decision.cancelPendingResume) {
            earDetectionResumeRequested = false
            earDetectionRouteAvailable = false
            cancelEarDetectionResumeTimers()
            return true
        }
        if (!decision.dispatchPause) return false

        clearEarDetectionPauseState(resetMediaOwnership = false)
        val dispatched = sendPause(force = true)
        if (!dispatched) return false
        earDetectionPauseOwned = true
        RoutingTrace.record {
            RoutingEventDetail.ActionResult(
                action = me.kavishdevar.librepods.diagnostics.RoutingAction.MEDIA_KEY_PAUSE,
                outcome = "requested",
                reason = "ear_detection_transition",
            )
        }
        return true
    }

    @Synchronized
    fun requestEarDetectionResume(): Boolean {
        if (!initialized || !earDetectionPauseOwned) return false
        earDetectionResumeRequested = true
        earDetectionRouteAvailable = !takeoverAwaitingRoute &&
            !failedPauseSettlementState.awaitingOutstandingPause
        maybeScheduleEarDetectionResume()
        return true
    }

    @Synchronized
    fun cancelEarDetectionAutomation() {
        clearEarDetectionPauseState(resetMediaOwnership = true)
    }

    @Synchronized
    fun suspendEarDetectionResumeUntilRouteReady() {
        if (!earDetectionPauseOwned) return
        earDetectionRouteAvailable = false
        cancelEarDetectionResumeTimers()
    }

    private fun observeEarDetectionPlayback(eligiblePlaybackActive: Boolean) {
        if (!earDetectionPauseOwned) return
        if (eligiblePlaybackActive) {
            if (earDetectionPlaybackInactiveSince > 0L) {
                clearEarDetectionPauseState(resetMediaOwnership = true)
            }
            return
        }
        if (earDetectionPlaybackInactiveSince == 0L) {
            earDetectionPlaybackInactiveSince = SystemClock.uptimeMillis()
        }
        maybeScheduleEarDetectionResume()
    }

    private fun maybeScheduleEarDetectionResume() {
        if (!earDetectionPauseOwned ||
            !earDetectionResumeRequested ||
            !earDetectionRouteAvailable ||
            takeoverAwaitingRoute ||
            failedPauseSettlementState.awaitingOutstandingPause
        ) {
            return
        }
        scheduleEarDetectionResumeTimeout()
        if (earDetectionPlaybackInactiveSince <= 0L || earDetectionResumeRunnable != null) {
            return
        }
        val inactiveForMs = SystemClock.uptimeMillis() - earDetectionPlaybackInactiveSince
        val delayMs = (EAR_DETECTION_PAUSE_QUIET_MS - inactiveForMs).coerceAtLeast(0L)
        val expectedPauseGeneration = earDetectionPauseGeneration
        val expectedTimerGeneration = earDetectionResumeTimerGeneration
        earDetectionResumeRunnable = Runnable {
            handleEarDetectionResumeTimeout(
                expectedPauseGeneration = expectedPauseGeneration,
                expectedTimerGeneration = expectedTimerGeneration,
            )
        }.also { handler.postDelayed(it, delayMs) }
    }

    private fun scheduleEarDetectionResumeTimeout() {
        if (earDetectionResumeTimeoutRunnable != null) return
        val expectedPauseGeneration = earDetectionPauseGeneration
        val expectedTimerGeneration = earDetectionResumeTimerGeneration
        earDetectionResumeTimeoutRunnable = Runnable {
            handleEarDetectionResumeHardTimeout(
                expectedPauseGeneration = expectedPauseGeneration,
                expectedTimerGeneration = expectedTimerGeneration,
            )
        }.also { handler.postDelayed(it, EAR_DETECTION_RESUME_TIMEOUT_MS) }
    }

    private fun cancelEarDetectionResumeTimers() {
        ++earDetectionResumeTimerGeneration
        earDetectionResumeRunnable?.let(handler::removeCallbacks)
        earDetectionResumeTimeoutRunnable?.let(handler::removeCallbacks)
        earDetectionResumeRunnable = null
        earDetectionResumeTimeoutRunnable = null
    }

    @Synchronized
    private fun handleEarDetectionResumeTimeout(
        expectedPauseGeneration: Long,
        expectedTimerGeneration: Long,
    ) {
        if (!initialized ||
            expectedPauseGeneration != earDetectionPauseGeneration ||
            expectedTimerGeneration != earDetectionResumeTimerGeneration ||
            !earDetectionPauseOwned ||
            !earDetectionResumeRequested
        ) {
            return
        }
        earDetectionResumeRunnable = null
        if (!earDetectionRouteAvailable ||
            takeoverAwaitingRoute ||
            failedPauseSettlementState.awaitingOutstandingPause
        ) {
            return
        }
        val playbackActive = snapshotFrom(audioManager.activePlaybackConfigurations)
            .earDetectionMediaActive
        val inactiveForMs = if (earDetectionPlaybackInactiveSince > 0L) {
            SystemClock.uptimeMillis() - earDetectionPlaybackInactiveSince
        } else {
            0L
        }
        if (EarDetectionResumeGate.canResume(
                pauseOwned = earDetectionPauseOwned,
                resumeRequested = earDetectionResumeRequested,
                playbackActive = playbackActive,
                inactiveForMs = inactiveForMs,
                quietWindowMs = EAR_DETECTION_PAUSE_QUIET_MS,
            )
        ) {
            clearEarDetectionPauseState(resetMediaOwnership = false)
            sendPlay()
        } else if (playbackActive && earDetectionPlaybackInactiveSince > 0L) {
            clearEarDetectionPauseState(resetMediaOwnership = true)
        } else {
            maybeScheduleEarDetectionResume()
        }
    }

    @Synchronized
    private fun handleEarDetectionResumeHardTimeout(
        expectedPauseGeneration: Long,
        expectedTimerGeneration: Long,
    ) {
        if (!initialized ||
            expectedPauseGeneration != earDetectionPauseGeneration ||
            expectedTimerGeneration != earDetectionResumeTimerGeneration
        ) {
            return
        }
        earDetectionResumeTimeoutRunnable = null
        if (!earDetectionPauseOwned || !earDetectionResumeRequested) return
        if (!earDetectionRouteAvailable ||
            takeoverAwaitingRoute ||
            failedPauseSettlementState.awaitingOutstandingPause
        ) {
            return
        }
        RoutingTrace.record {
            RoutingEventDetail.ActionResult(
                action = me.kavishdevar.librepods.diagnostics.RoutingAction.MEDIA_KEY_PLAY,
                outcome = "skipped",
                reason = "ear_detection_pause_not_stably_confirmed",
            )
        }
        clearEarDetectionPauseState(resetMediaOwnership = true)
    }

    private fun clearEarDetectionPauseState(resetMediaOwnership: Boolean) {
        val ownedPause = earDetectionPauseOwned
        ++earDetectionPauseGeneration
        cancelEarDetectionResumeTimers()
        earDetectionPauseOwned = false
        earDetectionPlaybackInactiveSince = 0L
        earDetectionResumeRequested = false
        earDetectionRouteAvailable = false
        if (resetMediaOwnership && ownedPause &&
            !takeoverAwaitingRoute &&
            !failedPauseSettlementState.awaitingOutstandingPause
        ) {
            iPausedTheMedia = false
        }
    }

    private fun handleFailedPauseSettlementSnapshot(snapshot: PlaybackSnapshot): Boolean {
        if (!failedPauseSettlementState.awaitingOutstandingPause) return false

        val eligibleNow = snapshot.eligibleUsages.isNotEmpty()
        val previousState = failedPauseSettlementState
        val transition = FailedPauseSettlementMachine.onPlaybackSnapshot(
            state = previousState,
            eligiblePlaybackActive = eligibleNow,
        )
        failedPauseSettlementState = transition.state

        when (transition.action) {
            FailedPauseSettlementAction.START_QUIET_DRAIN -> {
                scheduleFailedPauseQuietDrain(transition.state)
                RoutingTrace.record(currentCorrelation()) {
                    RoutingEventDetail.StateTransition(
                        stateMachine = "failed_pause_settlement",
                        previousState = previousState.phase.name,
                        newState = transition.state.phase.name,
                        trigger = "playback_callback",
                        reason = "eligible_playback_became_inactive; wait_for_stable_quiet",
                    )
                }
            }
            FailedPauseSettlementAction.CANCEL_QUIET_DRAIN -> {
                cancelFailedPauseQuietDrain()
            }
            else -> Unit
        }

        if (eligibleNow) {
            if (!previousState.playbackStartedAfterFailure) {
                RoutingTrace.record(currentCorrelation()) {
                    RoutingEventDetail.StateTransition(
                        stateMachine = "failed_pause_settlement",
                        previousState = "WAITING_FOR_PAUSE",
                        newState = "PLAYBACK_RESTARTED",
                        trigger = "playback_callback",
                        reason = "eligible_playback_started_after_takeover_failure",
                    )
                }
            }
            if (snapshot.eligiblePlaybackRoutedToTarget) {
                val correlation = currentCorrelation()
                handler.post {
                    ServiceManager.getService()?.onTargetPlaybackRouteObserved(correlation)
                }
            }
            return true
        }
        return true
    }

    private fun scheduleFailedPauseQuietDrain(state: FailedPauseSettlementState) {
        failedPauseQuietDrainRunnable?.let(handler::removeCallbacks)
        val expectedGeneration = state.generation
        val expectedQuietGeneration = state.quietGeneration
        failedPauseQuietDrainRunnable = Runnable {
            handleFailedPauseQuietDrainTimeout(
                expectedGeneration = expectedGeneration,
                expectedQuietGeneration = expectedQuietGeneration,
            )
        }.also { handler.postDelayed(it, FAILED_PAUSE_QUIET_DRAIN_MS) }
    }

    private fun cancelFailedPauseQuietDrain() {
        failedPauseQuietDrainRunnable?.let(handler::removeCallbacks)
        failedPauseQuietDrainRunnable = null
    }

    private fun scheduleFailedPauseHardTimeout(
        state: FailedPauseSettlementState,
        delayMs: Long = FAILED_PAUSE_HARD_TIMEOUT_MS,
    ) {
        failedPauseHardTimeoutRunnable?.let(handler::removeCallbacks)
        val expectedGeneration = state.generation
        failedPauseHardTimeoutRunnable = Runnable {
            handleFailedPauseHardTimeout(expectedGeneration)
        }.also { handler.postDelayed(it, delayMs) }
    }

    private fun cancelFailedPauseHardTimeout() {
        failedPauseHardTimeoutRunnable?.let(handler::removeCallbacks)
        failedPauseHardTimeoutRunnable = null
    }

    @Synchronized
    private fun handleFailedPauseQuietDrainTimeout(
        expectedGeneration: Long,
        expectedQuietGeneration: Long,
    ) {
        if (!initialized) return
        val stateBefore = failedPauseSettlementState
        val timerMatches = stateBefore.generation == expectedGeneration &&
            stateBefore.quietGeneration == expectedQuietGeneration
        val playbackStillActive = snapshotFrom(audioManager.activePlaybackConfigurations)
            .eligibleUsages
            .isNotEmpty()
        val transition = FailedPauseSettlementMachine.onQuietDrainTimeout(
            state = stateBefore,
            expectedGeneration = expectedGeneration,
            expectedQuietGeneration = expectedQuietGeneration,
            eligiblePlaybackActive = playbackStillActive,
        )
        failedPauseSettlementState = transition.state
        if (timerMatches) failedPauseQuietDrainRunnable = null
        if (transition.action == FailedPauseSettlementAction.COMPLETE) {
            finishFailedPauseSettlement(
                playbackStillActive = false,
                trigger = "failed_pause_quiet_drain_complete",
                reason = if (stateBefore.playbackStartedAfterFailure) {
                    "restart_seen_before_stable_quiet"
                } else {
                    "stable_quiet_after_takeover_failure"
                },
            )
        }
    }

    @Synchronized
    private fun handleFailedPauseHardTimeout(expectedGeneration: Long) {
        if (!initialized) return
        val stateBefore = failedPauseSettlementState
        val timerMatches = stateBefore.generation == expectedGeneration
        val playbackStillActive = snapshotFrom(audioManager.activePlaybackConfigurations)
            .eligibleUsages
            .isNotEmpty()
        val transition = FailedPauseSettlementMachine.onHardTimeout(
            state = stateBefore,
            expectedGeneration = expectedGeneration,
            eligiblePlaybackActive = playbackStillActive,
        )
        failedPauseSettlementState = transition.state
        if (timerMatches) failedPauseHardTimeoutRunnable = null
        when (transition.action) {
            FailedPauseSettlementAction.EXTEND_HARD_TIMEOUT_FOR_QUIET_DRAIN -> {
                scheduleFailedPauseHardTimeout(
                    state = transition.state,
                    delayMs = FAILED_PAUSE_QUIET_DRAIN_MS,
                )
            }
            FailedPauseSettlementAction.HARD_RELEASE_ACTIVE -> finishFailedPauseSettlement(
                playbackStillActive = true,
                trigger = "failed_pause_hard_release",
                reason = "pause_delivery_unconfirmed; suppress_until_next_playback_edge",
            )
            FailedPauseSettlementAction.HARD_RELEASE_INACTIVE -> finishFailedPauseSettlement(
                playbackStillActive = false,
                trigger = "failed_pause_hard_release",
                reason = "pause_delivery_unconfirmed; no_active_playback",
            )
            else -> Unit
        }
    }

    private fun finishFailedPauseSettlement(
        playbackStillActive: Boolean,
        trigger: String,
        reason: String,
    ) {
        if (!playbackStillActive) {
            reportedStreamingCorrelation?.let {
                ServiceManager.getService()?.onEligibleLocalPlaybackStopped(it)
            }
            reportedStreamingCorrelation = null
        }
        cancelFailedPauseQuietDrain()
        cancelFailedPauseHardTimeout()
        RoutingTrace.record(currentCorrelation()) {
            RoutingEventDetail.ActionResult(
                action = me.kavishdevar.librepods.diagnostics.RoutingAction.MEDIA_KEY_PAUSE,
                outcome = "drained",
                reason = reason,
            )
        }
        cancelPendingPlaybackEvaluation(trigger)
        takeoverAwaitingRoute = false
        clearTakeoverCompletionBarriers()
        pausedWhileTakingOver = false
        eligiblePlaybackActive = playbackStillActive
        takeoverAttemptedInEpisode = false
        currentTakeoverAttemptId = null
        transitionPlaybackEpisode(
            if (playbackStillActive) PlaybackEpisode.ATTEMPTED else PlaybackEpisode.INACTIVE,
            trigger = trigger,
            reason = reason,
        )
        if (!playbackStillActive) currentPlaybackCycleId = null
        iPausedTheMedia = earDetectionPauseOwned
        if (earDetectionPauseOwned && earDetectionResumeRequested) {
            clearEarDetectionPauseState(resetMediaOwnership = true)
        }
    }

    @Synchronized
    private fun handlePlaybackSnapshot(snapshot: PlaybackSnapshot) {
        val eligibleNow = snapshot.eligibleUsages.isNotEmpty()
        observeEarDetectionPlayback(snapshot.earDetectionMediaActive)

        if (handleFailedPauseSettlementSnapshot(snapshot)) return
        if (eligibleNow && takeoverPauseQuietDrainRunnable != null) {
            cancelTakeoverPauseQuietDrain()
        }

        if (!eligibleNow) {
            if (pausedWhileTakingOver) {
                cancelPendingPlaybackEvaluation("paused_while_waiting_for_ownership")
                if (takeoverAwaitingRoute &&
                    takeoverCompletionState.pauseRequired &&
                    takeoverCompletionState.pauseOutstanding &&
                    !takeoverCompletionState.pauseApplied &&
                    iPausedTheMedia
                ) {
                    scheduleTakeoverPauseQuietDrain()
                }
                return
            }
            if (takeoverAwaitingRoute) {
                cancelOwnershipConfirmationTimeout()
                takeoverAwaitingRoute = false
                clearTakeoverCompletionBarriers()
            }
            // A notification or system sound may remain active after media stops. The
            // eligibility edge, rather than an empty configuration list, determines when the
            // local host must publish HostStreamingState=NO.
            reportedStreamingCorrelation?.let {
                ServiceManager.getService()?.onEligibleLocalPlaybackStopped(it)
            }
            reportedStreamingCorrelation = null
            cancelPendingPlaybackEvaluation("eligible_playback_stopped")
            transitionPlaybackEpisode(
                PlaybackEpisode.INACTIVE,
                trigger = "playback_callback",
                reason = "no_eligible_configurations"
            )
            eligiblePlaybackActive = false
            takeoverAttemptedInEpisode = false
            currentPlaybackCycleId = null
            currentTakeoverAttemptId = null
            awaitingExplicitUserPlayAfterTakeover = false
            return
        }

        eligiblePlaybackActive = true
        if (playbackEpisode == PlaybackEpisode.ATTEMPTED) {
            if (takeoverAwaitingRoute) {
                if (snapshot.eligiblePlaybackRoutedToTarget) {
                    val correlation = currentCorrelation()
                    handler.post {
                        ServiceManager.getService()?.onTargetPlaybackRouteObserved(correlation)
                    }
                } else if (TakeoverPausePolicyGate.shouldPauseForPlaybackEdge(
                        pauseRequired = takeoverCompletionState.pauseRequired,
                        playbackRoutedToTarget = snapshot.eligiblePlaybackRoutedToTarget,
                        musicActive = audioManager.isMusicActive,
                    )
                ) {
                    if (!pauseActiveTakeoverPlayback(newPlaybackEdge = true)) {
                        onTakeoverFailed("takeover_pause_dispatch_failed")
                    }
                }
                return
            }
            if (ExplicitResumeRouteGate.shouldReevaluateRoute(
                    awaitingExplicitResume = awaitingExplicitUserPlayAfterTakeover,
                    playbackRoutedToTarget = snapshot.eligiblePlaybackRoutedToTarget,
                )
            ) {
                awaitingExplicitUserPlayAfterTakeover = false
                eligiblePlaybackActive = false
                takeoverAttemptedInEpisode = false
                currentTakeoverAttemptId = null
                currentPlaybackCycleId = null
                transitionPlaybackEpisode(
                    PlaybackEpisode.INACTIVE,
                    trigger = "explicit_play_after_takeover",
                    reason = "target_route_no_longer_available; reevaluate_takeover",
                )
            } else {
                awaitingExplicitUserPlayAfterTakeover = false
                return
            }
        }

        val delayMs = playbackEvaluationDelayMs(snapshot)
        // Repeated callbacks for the same players may shorten but never extend a deadline. A new
        // player gets a new window, even when it uses the same category as the player it replaced.
        if (playbackEpisode == PlaybackEpisode.PENDING && pendingPlaybackRunnable != null) {
            val remainingMs = (pendingPlaybackDeadlineAt - SystemClock.uptimeMillis())
                .coerceAtLeast(0L)
            if (PendingPlaybackDeadlineGate.shouldKeepExistingDeadline(
                    scheduledPlayers = pendingEligiblePlayers,
                    currentPlayers = snapshot.eligiblePlayers,
                    requestedDelayMs = delayMs,
                    remainingDelayMs = remainingMs,
                )
            ) {
                return
            }
        }

        if (playbackEpisode == PlaybackEpisode.INACTIVE) {
            currentPlaybackCycleId = "playback-${++playbackCycleCounter}"
            currentTakeoverAttemptId = null
        }
        transitionPlaybackEpisode(
            PlaybackEpisode.PENDING,
            trigger = "playback_callback",
            reason = "eligible_configuration_active"
        )
        val generation = ++pendingPlaybackGeneration
        pendingPlaybackRunnable?.let(handler::removeCallbacks)
        pendingPlaybackDelayMs = delayMs
        pendingPlaybackDeadlineAt = SystemClock.uptimeMillis() + delayMs
        pendingEligiblePlayers = snapshot.eligiblePlayers
        RoutingTrace.record(currentCorrelation()) {
            RoutingEventDetail.Debounce(
                event = DebounceEvent.SCHEDULED,
                delayMs = delayMs,
                generation = generation,
                reason = when {
                    snapshot.eligiblePlaybackRoutedToTarget -> "target_route_already_visible"
                    delayMs == UNKNOWN_MEDIA_ROUTE_SETTLE_MS -> "media_output_route_unknown"
                    delayMs == 0L -> "media_output_route_is_not_target"
                    else -> "short_playback_trailing_debounce"
                }
            )
        }
        pendingPlaybackRunnable = Runnable {
            firePendingPlaybackEvaluation(generation, delayMs)
        }.also { handler.postDelayed(it, delayMs) }
    }

    private fun playbackEvaluationDelayMs(snapshot: PlaybackSnapshot): Long {
        if (snapshot.eligiblePlaybackRoutedToTarget) return 0L
        val hasLongFormMedia = snapshot.eligibleUsages.any {
            it == AudioAttributes.USAGE_MEDIA || it == AudioAttributes.USAGE_GAME
        }
        if (!hasLongFormMedia) return SHORT_PLAYBACK_DEBOUNCE_MS
        return if (snapshot.eligibleOutputRoutes == setOf(TargetAudioOutput.UNKNOWN)) {
            UNKNOWN_MEDIA_ROUTE_SETTLE_MS
        } else {
            0L
        }
    }

    @Synchronized
    private fun firePendingPlaybackEvaluation(generation: Long, delayMs: Long) {
        if (!initialized || generation != pendingPlaybackGeneration) {
            RoutingTrace.record(currentCorrelation()) {
                RoutingEventDetail.Debounce(
                    event = DebounceEvent.STALE,
                    delayMs = delayMs,
                    generation = generation,
                    reason = "generation_superseded"
                )
            }
            return
        }
        val current = snapshotFrom(audioManager.activePlaybackConfigurations)
        if (current.eligiblePlayers != pendingEligiblePlayers) {
            pendingPlaybackRunnable = null
            pendingPlaybackDelayMs = null
            pendingPlaybackDeadlineAt = 0L
            pendingEligiblePlayers = emptySet()
            handlePlaybackSnapshot(current)
            return
        }
        if (current.eligibleUsages.isEmpty()) {
            handlePlaybackSnapshot(current)
            return
        }
        currentTakeoverAttemptId = "takeover-${currentPlaybackCycleId ?: generation}"
        RoutingTrace.record(currentCorrelation()) {
            RoutingEventDetail.Debounce(
                event = DebounceEvent.FIRED,
                delayMs = delayMs,
                generation = generation,
                reason = "eligible_playback_still_active"
            )
        }
        transitionPlaybackEpisode(
            PlaybackEpisode.ATTEMPTED,
            trigger = "debounce_fired",
            reason = "takeover_attempt_started"
        )
        takeoverAttemptedInEpisode = true
        pendingPlaybackRunnable = null
        pendingPlaybackDelayMs = null
        pendingPlaybackDeadlineAt = 0L
        pendingEligiblePlayers = emptySet()
        reportedStreamingCorrelation = currentCorrelation()
        ServiceManager.getService()?.onEligibleLocalPlaybackStarting(
            current.eligibleUsages,
            currentCorrelation()
        )
    }

    @Synchronized
    private fun cancelPendingPlaybackEvaluation(reason: String) {
        pendingPlaybackGeneration++
        pendingPlaybackRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
            RoutingTrace.record(currentCorrelation()) {
                RoutingEventDetail.Debounce(
                    event = DebounceEvent.CANCELLED,
                    delayMs = pendingPlaybackDelayMs ?: 0L,
                    generation = pendingPlaybackGeneration,
                    reason = reason
                )
            }
        }
        pendingPlaybackRunnable = null
        pendingPlaybackDelayMs = null
        pendingPlaybackDeadlineAt = 0L
        pendingEligiblePlayers = emptySet()
    }

    @Synchronized
    private fun transitionPlaybackEpisode(
        newState: PlaybackEpisode,
        trigger: String,
        reason: String
    ) {
        val previousState = playbackEpisode
        if (previousState == newState) return
        playbackEpisode = newState
        RoutingTrace.record(currentCorrelation()) {
            RoutingEventDetail.StateTransition(
                stateMachine = "playback_episode",
                previousState = previousState.name,
                newState = newState.name,
                trigger = trigger,
                reason = reason
            )
        }
    }

    @Synchronized
    private fun resetRoutingEpisodeForPolicyChange() {
        if (!initialized) return
        cancelPendingPlaybackEvaluation("routing_policy_changed")
        awaitingExplicitUserPlayAfterTakeover = false
        val policy = SmartRoutingAudioPolicyPreferences.read(sharedPreferences)
        val masterEnabled = sharedPreferences.getBoolean(
            SmartRoutingAudioPolicyPreferences.MASTER_ENABLED_KEY,
            false
        )
        val revision = ++routingSettingsRevision
        RoutingTrace.record {
            RoutingEventDetail.SettingsSnapshot(
                revision = revision,
                automaticTakeoverEnabled = masterEnabled,
                mediaTakeoverEnabled = policy.isEnabled(SmartRoutingAudioCategory.MEDIA),
                enabledUsages = policy.enabledCategories.flatMapTo(linkedSetOf()) {
                    it.platformUsages
                },
                debounceMs = SHORT_PLAYBACK_DEBOUNCE_MS
            )
        }
        val current = snapshotFrom(audioManager.activePlaybackConfigurations)
        if (current.eligibleUsages.isEmpty()) {
            reportedStreamingCorrelation?.let {
                ServiceManager.getService()?.onEligibleLocalPlaybackStopped(it)
            }
            reportedStreamingCorrelation = null
            if (pausedWhileTakingOver) {
                onTakeoverFailed("routing_policy_changed", resumeIfStillActive = false)
            }
        }
        eligiblePlaybackActive = current.eligibleUsages.isNotEmpty()
        val newState = if (eligiblePlaybackActive) {
            PlaybackEpisode.ATTEMPTED
        } else {
            PlaybackEpisode.INACTIVE
        }
        transitionPlaybackEpisode(
            newState,
            trigger = "routing_policy_changed",
            reason = if (eligiblePlaybackActive) {
                "suppress_currently_playing_audio_until_next_edge"
            } else {
                "no_eligible_audio_active"
            }
        )
        takeoverAttemptedInEpisode = false
    }

    private fun suppressCurrentlyPlayingEligibleAudio() {
        resetRoutingEpisodeForPolicyChange()
    }

    @Synchronized
    fun beginTakeover(
        expectedCorrelation: RoutingCorrelation? = null,
        pauseImmediately: Boolean,
    ): Boolean {
        if (expectedCorrelation != null && !isCurrentTakeoverAttempt(expectedCorrelation)) {
            RoutingTrace.record(expectedCorrelation) {
                RoutingEventDetail.ActionResult(
                    action = me.kavishdevar.librepods.diagnostics.RoutingAction.MEDIA_KEY_PAUSE,
                    outcome = "skipped",
                    reason = "stale_takeover_attempt"
                )
            }
            return false
        }
        if (!initialized) return false
        if (failedPauseSettlementState.awaitingOutstandingPause) return false
        if (takeoverAwaitingRoute) {
            if (pauseImmediately) {
                if (!pauseActiveTakeoverPlayback()) {
                    onTakeoverFailed("takeover_pause_dispatch_failed")
                }
            }
            return takeoverAwaitingRoute
        }
        if (!audioManager.isMusicActive) return false
        awaitingExplicitUserPlayAfterTakeover = false
        takeoverAwaitingRoute = true
        if (earDetectionPauseOwned && earDetectionResumeRequested) {
            earDetectionRouteAvailable = false
        }
        cancelEarDetectionResumeTimers()
        takeoverCompletionState = TakeoverCompletionGate.start(
            pauseRequired = pauseImmediately
        )
        if (pauseImmediately && !pauseActiveTakeoverPlayback()) {
            takeoverAwaitingRoute = false
            clearTakeoverCompletionBarriers()
            if (earDetectionPauseOwned && earDetectionResumeRequested) {
                clearEarDetectionPauseState(resetMediaOwnership = true)
            }
            return false
        }
        scheduleOwnershipConfirmationTimeout(currentTakeoverAttemptId)
        return takeoverAwaitingRoute
    }

    @Synchronized
    fun pauseForTakeover(expectedCorrelation: RoutingCorrelation? = null): Boolean =
        beginTakeover(expectedCorrelation, pauseImmediately = true)

    private fun pauseActiveTakeoverPlayback(newPlaybackEdge: Boolean = false): Boolean {
        if (!initialized) return false
        val request = if (newPlaybackEdge) {
            TakeoverCompletionGate.requestPauseForNewPlaybackEdge(takeoverCompletionState)
        } else {
            TakeoverCompletionGate.requestPause(takeoverCompletionState)
        }
        takeoverCompletionState = request.state
        if (!request.shouldDispatch) {
            return pausedWhileTakingOver ||
                takeoverCompletionState.pauseOutstanding ||
                takeoverCompletionState.pauseApplied
        }
        if (!audioManager.isMusicActive) {
            takeoverCompletionState = TakeoverCompletionGate.pauseDispatchFailed(
                takeoverCompletionState
            )
            pausedWhileTakingOver = false
            return false
        }
        pausedWhileTakingOver = true
        RoutingTrace.record(currentCorrelation()) {
            RoutingEventDetail.ActionResult(
                action = me.kavishdevar.librepods.diagnostics.RoutingAction.MEDIA_KEY_PAUSE,
                outcome = "requested",
                reason = "takeover_pause_guard",
            )
        }
        val dispatched = sendPause(force = true)
        if (!dispatched) {
            takeoverCompletionState = TakeoverCompletionGate.pauseDispatchFailed(
                takeoverCompletionState
            )
            pausedWhileTakingOver = false
        }
        return pausedWhileTakingOver
    }

    private fun scheduleTakeoverPauseQuietDrain() {
        if (takeoverPauseQuietDrainRunnable != null) return
        val expectedAttemptId = currentTakeoverAttemptId
        val expectedGeneration = ++takeoverPauseQuietDrainGeneration
        takeoverPauseQuietDrainRunnable = Runnable {
            handleTakeoverPauseQuietDrainTimeout(expectedAttemptId, expectedGeneration)
        }.also { handler.postDelayed(it, FAILED_PAUSE_QUIET_DRAIN_MS) }
    }

    private fun cancelTakeoverPauseQuietDrain() {
        ++takeoverPauseQuietDrainGeneration
        takeoverPauseQuietDrainRunnable?.let(handler::removeCallbacks)
        takeoverPauseQuietDrainRunnable = null
    }

    private fun scheduleTakeoverPauseHardTimeout(
        expectedAttemptId: String?,
        delayMs: Long = TAKEOVER_PAUSE_BARRIER_TIMEOUT_MS,
    ) {
        if (takeoverPauseHardTimeoutRunnable != null) return
        cancelTakeoverPauseHardTimeout()
        val expectedGeneration = ++takeoverPauseHardTimeoutGeneration
        takeoverPauseHardTimeoutRunnable = Runnable {
            handleTakeoverPauseHardTimeout(expectedAttemptId, expectedGeneration)
        }.also { handler.postDelayed(it, delayMs) }
    }

    private fun cancelTakeoverPauseHardTimeout() {
        ++takeoverPauseHardTimeoutGeneration
        takeoverPauseHardTimeoutRunnable?.let(handler::removeCallbacks)
        takeoverPauseHardTimeoutRunnable = null
    }

    @Synchronized
    private fun handleTakeoverPauseHardTimeout(
        expectedAttemptId: String?,
        expectedGeneration: Long,
    ) {
        if (!initialized ||
            expectedGeneration != takeoverPauseHardTimeoutGeneration ||
            expectedAttemptId == null ||
            expectedAttemptId != currentTakeoverAttemptId ||
            !takeoverAwaitingRoute ||
            !takeoverCompletionState.routeReady ||
            !takeoverCompletionState.pauseOutstanding
        ) {
            return
        }
        takeoverPauseHardTimeoutRunnable = null
        val playbackStillActive = snapshotFrom(audioManager.activePlaybackConfigurations)
            .eligibleUsages
            .isNotEmpty()
        if (!playbackStillActive && takeoverPauseQuietDrainRunnable != null) {
            scheduleTakeoverPauseHardTimeout(
                expectedAttemptId = expectedAttemptId,
                delayMs = FAILED_PAUSE_QUIET_DRAIN_MS,
            )
            return
        }
        onTakeoverFailed(
            reason = "pause_barrier_timeout",
            resumeIfStillActive = false,
        )
    }

    @Synchronized
    private fun handleTakeoverPauseQuietDrainTimeout(
        expectedAttemptId: String?,
        expectedGeneration: Long,
    ) {
        if (!initialized ||
            expectedGeneration != takeoverPauseQuietDrainGeneration ||
            expectedAttemptId == null ||
            expectedAttemptId != currentTakeoverAttemptId ||
            !takeoverAwaitingRoute ||
            !takeoverCompletionState.pauseOutstanding
        ) {
            return
        }
        takeoverPauseQuietDrainRunnable = null
        val playbackStillActive = snapshotFrom(audioManager.activePlaybackConfigurations)
            .eligibleUsages
            .isNotEmpty()
        if (playbackStillActive) return

        takeoverCompletionState = TakeoverCompletionGate.confirmPauseApplied(
            takeoverCompletionState
        )
        RoutingTrace.record(currentCorrelation()) {
            RoutingEventDetail.ActionResult(
                action = me.kavishdevar.librepods.diagnostics.RoutingAction.MEDIA_KEY_PAUSE,
                outcome = "confirmed",
                reason = "eligible_playback_stably_inactive",
            )
        }
        maybeCompleteTakeover("pause_quiet_drain_complete")
    }

    @Synchronized
    private fun handleOwnershipConfirmationTimeout(
        expectedAttemptId: String?,
        expectedGeneration: Long,
    ) {
        if (!takeoverAwaitingRoute || expectedAttemptId != currentTakeoverAttemptId) return
        val transition = TakeoverRouteTimeoutMachine.onTimeout(
            state = takeoverRouteTimeoutState,
            expectedGeneration = expectedGeneration,
        )
        takeoverRouteTimeoutState = transition.state
        ownershipConfirmationTimeoutRunnable = null
        if (transition.action != TakeoverRouteTimeoutAction.FAIL_ATTEMPT) return
        onTakeoverFailed(
            reason = "ownership_confirmation_timeout",
            resumeIfStillActive = false
        )
    }

    @Synchronized
    fun onLocalRouteReady(expectedAttemptId: String?, trigger: String): Boolean {
        if (!isTrackingTakeoverRoute() ||
            expectedAttemptId == null || expectedAttemptId != currentTakeoverAttemptId
        ) {
            return false
        }
        takeoverCompletionState = TakeoverCompletionGate.updateRoute(
            state = takeoverCompletionState,
            ready = true,
            trigger = trigger,
        ).state
        if (earDetectionPauseOwned && earDetectionResumeRequested) {
            earDetectionRouteAvailable = true
        }
        cancelOwnershipConfirmationTimeout()
        if (failedPauseSettlementState.awaitingOutstandingPause) return false
        if (takeoverCompletionState.pauseOutstanding) {
            scheduleTakeoverPauseHardTimeout(currentTakeoverAttemptId)
        }
        if (takeoverCompletionState.pauseRequired &&
            takeoverCompletionState.pauseOutstanding &&
            pausedWhileTakingOver &&
            iPausedTheMedia &&
            !audioManager.isMusicActive
        ) {
            scheduleTakeoverPauseQuietDrain()
        }
        return maybeCompleteTakeover(trigger)
    }

    @Synchronized
    fun onLocalRouteNotReady(expectedAttemptId: String?, trigger: String) {
        if (!isTrackingTakeoverRoute() ||
            expectedAttemptId == null || expectedAttemptId != currentTakeoverAttemptId
        ) {
            return
        }
        val update = TakeoverCompletionGate.updateRoute(
            state = takeoverCompletionState,
            ready = false,
            trigger = trigger,
        )
        takeoverCompletionState = update.state
        if (earDetectionPauseOwned && earDetectionResumeRequested) {
            earDetectionRouteAvailable = false
            cancelEarDetectionResumeTimers()
        }
        if (update.exitedReady && takeoverAwaitingRoute) {
            cancelTakeoverPauseHardTimeout()
            scheduleOwnershipConfirmationTimeout(currentTakeoverAttemptId)
        }
        if (update.exitedReady) {
            RoutingTrace.record(currentCorrelation()) {
                RoutingEventDetail.ActionResult(
                    action = me.kavishdevar.librepods.diagnostics.RoutingAction.AUTOMATIC_TAKEOVER,
                    outcome = "pending",
                    reason = "local_route_invalidated:$trigger",
                )
            }
        }
    }

    private fun maybeCompleteTakeover(trigger: String): Boolean {
        if (!takeoverAwaitingRoute ||
            !TakeoverCompletionGate.canComplete(takeoverCompletionState)
        ) {
            return false
        }
        val completionTrigger = takeoverCompletionState.routeReadyTrigger ?: trigger
        val pauseGuardApplied = takeoverCompletionState.pauseRequired &&
            pausedWhileTakingOver &&
            iPausedTheMedia
        cancelOwnershipConfirmationTimeout()
        cancelTakeoverPauseHardTimeout()
        takeoverAwaitingRoute = false
        RoutingTrace.record(currentCorrelation()) {
            RoutingEventDetail.ActionResult(
                action = me.kavishdevar.librepods.diagnostics.RoutingAction.AUTOMATIC_TAKEOVER,
                outcome = "confirmed",
                reason = "local_route_ready:$completionTrigger;completion=$trigger"
            )
        }
        clearTakeoverCompletionBarriers()
        pausedWhileTakingOver = false
        iPausedTheMedia = earDetectionPauseOwned
        awaitingExplicitUserPlayAfterTakeover = pauseGuardApplied
        if (earDetectionPauseOwned && earDetectionResumeRequested) {
            earDetectionRouteAvailable = true
        }
        maybeScheduleEarDetectionResume()
        if (pauseGuardApplied) {
            RoutingTrace.record(currentCorrelation()) {
                RoutingEventDetail.ActionResult(
                    action = me.kavishdevar.librepods.diagnostics.RoutingAction.MEDIA_KEY_PLAY,
                    outcome = "skipped",
                    reason = "await_explicit_user_play_after_takeover_guard",
                )
            }
        }
        return true
    }

    private fun clearTakeoverCompletionBarriers() {
        cancelTakeoverPauseQuietDrain()
        cancelTakeoverPauseHardTimeout()
        takeoverCompletionState = TakeoverCompletionState()
    }

    /**
     * Marks an attempt complete when the stream was already audible through the
     * phone's AirPods A2DP route. The ownership notification can arrive late, so
     * requiring the ownership bit here would introduce a needless pause/retry loop.
     */
    @Synchronized
    fun onLocalRouteAlreadyActive(expectedAttemptId: String?, trigger: String) {
        if (expectedAttemptId == null || expectedAttemptId != currentTakeoverAttemptId ||
            playbackEpisode != PlaybackEpisode.ATTEMPTED
        ) {
            return
        }
        when (LocalRouteAlreadyActiveGate.decide(
            takeoverAwaitingRoute = takeoverAwaitingRoute,
            failedPauseAwaiting = failedPauseSettlementState.awaitingOutstandingPause,
        )) {
            LocalRouteAlreadyActiveAction.UPDATE_PENDING_ROUTE -> {
                onLocalRouteReady(expectedAttemptId, "local_route_already_active:$trigger")
                return
            }
            LocalRouteAlreadyActiveAction.KEEP_FAILED_PAUSE_SETTLEMENT -> {
                RoutingTrace.record(currentCorrelation()) {
                    RoutingEventDetail.ActionResult(
                        action = me.kavishdevar.librepods.diagnostics.RoutingAction.AUTOMATIC_TAKEOVER,
                        outcome = "pending",
                        reason = "local_route_ready_while_late_pause_is_settling:$trigger",
                    )
                }
                return
            }
            LocalRouteAlreadyActiveAction.COMPLETE_IMMEDIATELY -> Unit
        }
        cancelOwnershipConfirmationTimeout()
        takeoverAwaitingRoute = false
        clearTakeoverCompletionBarriers()
        pausedWhileTakingOver = false
        iPausedTheMedia = earDetectionPauseOwned
        if (earDetectionPauseOwned && earDetectionResumeRequested) {
            earDetectionRouteAvailable = true
        }
        maybeScheduleEarDetectionResume()
        RoutingTrace.record(currentCorrelation()) {
            RoutingEventDetail.ActionResult(
                action = me.kavishdevar.librepods.diagnostics.RoutingAction.AUTOMATIC_TAKEOVER,
                outcome = "confirmed",
                reason = "local_route_already_active:$trigger"
            )
        }
    }

    @Synchronized
    fun onTakeoverFailed(reason: String, resumeIfStillActive: Boolean = false) {
        if (!takeoverAwaitingRoute) return
        cancelOwnershipConfirmationTimeout()
        cancelTakeoverPauseQuietDrain()
        cancelTakeoverPauseHardTimeout()
        awaitingExplicitUserPlayAfterTakeover = false
        val playbackStillActive = snapshotFrom(audioManager.activePlaybackConfigurations)
            .eligibleUsages
            .isNotEmpty()
        val failureDecision = TakeoverCompletionGate.failureDecision(
            state = takeoverCompletionState,
            resumeIfStillActive = resumeIfStillActive,
            playbackStillActive = playbackStillActive,
        )
        val settlement = FailedPauseSettlementMachine.onTakeoverFailed(
            state = failedPauseSettlementState,
            pauseOutstanding = takeoverCompletionState.pauseOutstanding
        )
        failedPauseSettlementState = settlement.state
        val settlingOutstandingPause =
            failedPauseSettlementState.awaitingOutstandingPause
        takeoverAwaitingRoute = false
        if (!settlingOutstandingPause) {
            clearTakeoverCompletionBarriers()
            pausedWhileTakingOver = false
        }
        if (failureDecision.shouldReportPlaybackStopped) {
            reportedStreamingCorrelation?.let {
                ServiceManager.getService()?.onEligibleLocalPlaybackStopped(it)
            }
            reportedStreamingCorrelation = null
        }
        RoutingTrace.record(currentCorrelation()) {
            RoutingEventDetail.ActionResult(
                action = me.kavishdevar.librepods.diagnostics.RoutingAction.AUTOMATIC_TAKEOVER,
                outcome = "failed",
                reason = reason
            )
        }
        eligiblePlaybackActive = playbackStillActive
        takeoverAttemptedInEpisode = false
        transitionPlaybackEpisode(
            if (failureDecision.keepPlaybackEpisodeOpen) {
                PlaybackEpisode.ATTEMPTED
            } else {
                PlaybackEpisode.INACTIVE
            },
            trigger = "takeover_failed",
            reason = if (failureDecision.keepPlaybackEpisodeOpen) {
                "$reason; wait_for_current_playback_to_stop"
            } else {
                "$reason; await_user_playback"
            }
        )
        if (settlingOutstandingPause) {
            scheduleFailedPauseHardTimeout(failedPauseSettlementState)
            if (!playbackStillActive) {
                val quietTransition = FailedPauseSettlementMachine.onPlaybackSnapshot(
                    state = failedPauseSettlementState,
                    eligiblePlaybackActive = false,
                )
                failedPauseSettlementState = quietTransition.state
                if (quietTransition.action == FailedPauseSettlementAction.START_QUIET_DRAIN) {
                    scheduleFailedPauseQuietDrain(quietTransition.state)
                }
            }
            RoutingTrace.record(currentCorrelation()) {
                RoutingEventDetail.StateTransition(
                    stateMachine = "failed_pause_settlement",
                    previousState = "INACTIVE",
                    newState = failedPauseSettlementState.phase.name,
                    trigger = "takeover_failed",
                    reason = reason,
                )
            }
            return
        }
        cancelFailedPauseQuietDrain()
        cancelFailedPauseHardTimeout()
        currentTakeoverAttemptId = null
        if (!failureDecision.keepPlaybackEpisodeOpen) currentPlaybackCycleId = null
        iPausedTheMedia = earDetectionPauseOwned
        if (earDetectionPauseOwned && earDetectionResumeRequested) {
            clearEarDetectionPauseState(resetMediaOwnership = true)
        }
    }

    @Synchronized
    fun onRemoteAudioRouteConfirmed(trigger: String) {
        handleRemoteRouteState(
            pauseActivePlayback = true,
            trigger = trigger,
        )
    }

    private fun handleRemoteRouteState(
        pauseActivePlayback: Boolean,
        trigger: String,
    ) {
        awaitingExplicitUserPlayAfterTakeover = false
        val takeoverWasPending = takeoverAwaitingRoute
        val takeoverPauseOutstanding = takeoverCompletionState.pauseOutstanding &&
            (takeoverWasPending || failedPauseSettlementState.awaitingOutstandingPause)
        if (isTrackingTakeoverRoute()) {
            onLocalRouteNotReady(
                currentTakeoverAttemptId,
                "$trigger:remote_route_confirmed",
            )
        }
        if (takeoverWasPending) {
            onTakeoverFailed(trigger, resumeIfStillActive = false)
        }
        cancelOwnershipConfirmationTimeout()
        cancelPendingPlaybackEvaluation(trigger)
        val currentEligible = initialized &&
            snapshotFrom(audioManager.activePlaybackConfigurations).eligibleUsages.isNotEmpty()
        if (currentEligible) {
            transitionPlaybackEpisode(
                PlaybackEpisode.ATTEMPTED,
                trigger = trigger,
                reason = "prevent_automatic_retake_until_next_playback_edge"
            )
            takeoverAttemptedInEpisode = false
        } else {
            transitionPlaybackEpisode(
                PlaybackEpisode.INACTIVE,
                trigger = trigger,
                reason = "no_local_eligible_playback"
            )
            eligiblePlaybackActive = false
            takeoverAttemptedInEpisode = false
            currentPlaybackCycleId = null
            currentTakeoverAttemptId = null
        }
        reportedStreamingCorrelation = null
        takeoverAwaitingRoute = false
        if (!failedPauseSettlementState.awaitingOutstandingPause) {
            clearTakeoverCompletionBarriers()
            pausedWhileTakingOver = false
        }
        // Ownership alone is not proof that audio moved. Pause only after AACP
        // confirms a remote source or a remote host reports active streaming.
        if (pauseActivePlayback &&
            !takeoverPauseOutstanding &&
            currentEligible &&
            audioManager.isMusicActive
        ) {
            sendPause(force = true)
        }
    }

    private fun scheduleOwnershipConfirmationTimeout(expectedAttemptId: String?) {
        cancelOwnershipConfirmationTimeout()
        takeoverRouteTimeoutState = TakeoverRouteTimeoutMachine.arm(
            takeoverRouteTimeoutState
        )
        val timeoutGeneration = takeoverRouteTimeoutState.generation
        ownershipConfirmationTimeoutRunnable = Runnable {
            handleOwnershipConfirmationTimeout(expectedAttemptId, timeoutGeneration)
        }.also {
            handler.postDelayed(it, OWNERSHIP_CONFIRMATION_TIMEOUT_MS)
        }
    }

    private fun cancelOwnershipConfirmationTimeout() {
        ownershipConfirmationTimeoutRunnable?.let(handler::removeCallbacks)
        ownershipConfirmationTimeoutRunnable = null
        takeoverRouteTimeoutState = TakeoverRouteTimeoutMachine.disarm(
            takeoverRouteTimeoutState
        )
    }

    @Synchronized
    fun getMusicActive(): Boolean {
        return audioManager.isMusicActive
    }

    /** Returns whether a currently active playback configuration is allowed by Smart Routing. */
    @Synchronized
    fun isEligibleLocalPlaybackActive(): Boolean {
        if (!initialized) return false
        return snapshotFrom(audioManager.activePlaybackConfigurations)
            .eligibleUsages
            .isNotEmpty()
    }

    @Synchronized
    fun isEligiblePlaybackRoutedToTarget(): Boolean {
        if (!initialized) return false
        return snapshotFrom(audioManager.activePlaybackConfigurations)
            .eligiblePlaybackRoutedToTarget
    }

    @Synchronized
    fun eligibleTargetRouteEvidence(): TargetRouteEvidence {
        if (!initialized) return TargetRouteEvidence.UNKNOWN
        return TargetAudioOutputClassifier.aggregate(
            snapshotFrom(audioManager.activePlaybackConfigurations).eligibleOutputRoutes
        )
    }

    @Synchronized
    fun recordDiagnosticBaseline(additionalFlags: Map<String, Boolean> = emptyMap()) {
        if (!initialized || !RoutingTrace.isEnabled) return
        val configs = audioManager.activePlaybackConfigurations
        tracePlaybackSnapshot(
            configs,
            snapshotFrom(configs),
            observationSource = "diagnostic_baseline"
        )
        val policy = SmartRoutingAudioPolicyPreferences.read(sharedPreferences)
        RoutingTrace.record(currentCorrelation()) {
            RoutingEventDetail.SettingsSnapshot(
                revision = routingSettingsRevision,
                automaticTakeoverEnabled = sharedPreferences.getBoolean(
                    SmartRoutingAudioPolicyPreferences.MASTER_ENABLED_KEY,
                    false
                ),
                mediaTakeoverEnabled = policy.isEnabled(SmartRoutingAudioCategory.MEDIA),
                enabledUsages = policy.enabledCategories.flatMapTo(linkedSetOf()) {
                    it.platformUsages
                },
                debounceMs = SHORT_PLAYBACK_DEBOUNCE_MS,
                additionalFlags = additionalFlags
            )
        }
    }

    @Synchronized
    fun sendPlayPause() {
        if (audioManager.isMusicActive) {
            Log.d("MediaController", "Sending pause because music is active")
            sendPause()
        } else {
            Log.d("MediaController", "Sending play because music is not active")
            sendPlay()
        }
    }

    @Synchronized
    fun sendPreviousTrack() {
        Log.d("MediaController", "Sending previous track")
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS
            )
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS
            )
        )
        lastSelfActionAt = SystemClock.uptimeMillis()
    }

    @Synchronized
    fun sendNextTrack() {
        Log.d("MediaController", "Sending next track")
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_NEXT
            )
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_NEXT
            )
        )
        lastSelfActionAt = SystemClock.uptimeMillis()
    }

    @Synchronized
    fun sendPause(force: Boolean = false): Boolean {
        Log.d("MediaController", "Sending pause with iPausedTheMedia: $iPausedTheMedia, userPlayedTheMedia: $userPlayedTheMedia, isMusicActive: ${audioManager.isMusicActive}, force: $force")
        if ((audioManager.isMusicActive) && (!userPlayedTheMedia || force)) {
            iPausedTheMedia = if (force) audioManager.isMusicActive else true
            userPlayedTheMedia = false
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PAUSE
                )
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PAUSE
                )
            )
            lastSelfActionAt = SystemClock.uptimeMillis()
            return true
        }
        return false
    }

    @Synchronized
    fun sendPlay() {
        Log.d("MediaController", "Sending play with iPausedTheMedia: $iPausedTheMedia")
        if (iPausedTheMedia) {
            Log.d("MediaController", "Sending play and setting userPlayedTheMedia to false")
            userPlayedTheMedia = false
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PLAY
                )
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PLAY
                )
            )
            lastSelfActionAt = SystemClock.uptimeMillis()
        }
        if (!audioManager.isMusicActive) {
            Log.d("MediaController", "Setting iPausedTheMedia to false")
            iPausedTheMedia = false
        }
        if (pausedWhileTakingOver) {
            Log.d("MediaController", "Setting pausedWhileTakingOver to false")
            pausedWhileTakingOver = false
        }
    }

    @Synchronized
    fun startSpeaking() {
        Log.d("MediaController", "Starting speaking max vol: ${audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)}, current vol: ${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)}, conversationalAwarenessVolume: $conversationalAwarenessVolume, relativeVolume: $relativeVolume")

        if (initialVolume == null) {
            initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            Log.d("MediaController", "Initial Volume: $initialVolume")
            val targetVolume = if (relativeVolume) {
                (initialVolume!! * conversationalAwarenessVolume / 100)
            } else if (initialVolume!! > (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * conversationalAwarenessVolume / 100)) {
                (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * conversationalAwarenessVolume / 100)
            } else {
                initialVolume!!
            }
            smoothVolumeTransition(initialVolume!!, targetVolume)
            if (conversationalAwarenessPauseMusic) {
                sendPause(force = true)
            }
        }
        Log.d("MediaController", "Initial Volume: $initialVolume")
    }

    @Synchronized
    fun stopSpeaking() {
        Log.d("MediaController", "Stopping speaking, initialVolume: $initialVolume")
        if (initialVolume != null) {
            smoothVolumeTransition(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC), initialVolume!!)
            if (conversationalAwarenessPauseMusic) {
                sendPlay()
            }
            initialVolume = null
        }
    }

    private fun smoothVolumeTransition(fromVolume: Int, toVolume: Int) {
        Log.d("MediaController", "Smooth volume transition from $fromVolume to $toVolume")
        val step = if (fromVolume < toVolume) 1 else -1
        val delay = 50L
        var currentVolume = fromVolume

        handler.post(object : Runnable {
            override fun run() {
                if (currentVolume != toVolume) {
                    currentVolume += step
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
                    handler.postDelayed(this, delay)
                }
            }
        })
    }
}
