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
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import me.kavishdevar.librepods.data.SmartRoutingAudioCategory
import me.kavishdevar.librepods.data.SmartRoutingAudioPolicyPreferences
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
        val eligibleUsages: Set<Int>,
        val eligibleConfigurationCount: Int,
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

    private var lastSelfActionAt: Long = 0L
    private const val PLAYBACK_DEBOUNCE_MS = 300L
    private const val OWNERSHIP_CONFIRMATION_TIMEOUT_MS = 5_000L
    private const val PLAYER_STATE_STARTED = 2
    private var playbackEpisode = PlaybackEpisode.INACTIVE
    private var pendingPlaybackGeneration = 0L
    private var pendingPlaybackRunnable: Runnable? = null
    private var ownershipConfirmationTimeoutRunnable: Runnable? = null
    private var eligiblePlaybackActive = false
    private var takeoverAttemptedInEpisode = false
    private var playbackCycleCounter = 0L
    private var currentPlaybackCycleId: String? = null
    private var currentTakeoverAttemptId: String? = null
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
        pendingPlaybackRunnable = null
        pausedWhileTakingOver = false
        iPausedTheMedia = false
        userPlayedTheMedia = false
        playbackEpisode = PlaybackEpisode.INACTIVE
        eligiblePlaybackActive = false
        takeoverAttemptedInEpisode = false
        currentPlaybackCycleId = null
        currentTakeoverAttemptId = null
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
        val eligibleUsages = if (masterEnabled) {
            activeUsages.filterTo(linkedSetOf(), policy::allowsPlatformUsage)
        } else {
            emptySet()
        }
        val eligibleConfigurationCount = if (masterEnabled) {
            startedConfigs.count { policy.allowsPlatformUsage(it.audioAttributes.usage) }
        } else {
            0
        }
        return PlaybackSnapshot(activeUsages, eligibleUsages, eligibleConfigurationCount)
    }

    private fun playerState(config: AudioPlaybackConfiguration): Int? = runCatching {
        config.javaClass.getMethod("getPlayerState").invoke(config) as Int
    }.getOrNull()

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
                eligiblePlaybackActive = snapshot.eligibleUsages.isNotEmpty()
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
            correlation.playbackCycleId != null &&
            correlation.takeoverAttemptId != null &&
            correlation.playbackCycleId == currentPlaybackCycleId &&
            correlation.takeoverAttemptId == currentTakeoverAttemptId

    @Synchronized
    fun isAwaitingLocalRoute(): Boolean = pausedWhileTakingOver && iPausedTheMedia

    @Synchronized
    private fun handlePlaybackSnapshot(snapshot: PlaybackSnapshot) {
        val eligibleNow = snapshot.eligibleUsages.isNotEmpty()

        if (!eligibleNow) {
            if (pausedWhileTakingOver) {
                cancelPendingPlaybackEvaluation("paused_while_waiting_for_ownership")
                return
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
            return
        }

        eligiblePlaybackActive = true
        if (playbackEpisode == PlaybackEpisode.ATTEMPTED) return

        // AudioPlaybackCallback can report metadata/configuration changes repeatedly while one
        // player remains active. Keep the original trailing confirmation deadline; otherwise a
        // steady stream of callbacks can postpone takeover indefinitely.
        if (playbackEpisode == PlaybackEpisode.PENDING && pendingPlaybackRunnable != null) {
            return
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
        RoutingTrace.record(currentCorrelation()) {
            RoutingEventDetail.Debounce(
                event = DebounceEvent.SCHEDULED,
                delayMs = PLAYBACK_DEBOUNCE_MS,
                generation = generation,
                reason = "eligible_playback_trailing_debounce"
            )
        }
        pendingPlaybackRunnable = Runnable {
            firePendingPlaybackEvaluation(generation)
        }.also { handler.postDelayed(it, PLAYBACK_DEBOUNCE_MS) }
    }

    @Synchronized
    private fun firePendingPlaybackEvaluation(generation: Long) {
        if (!initialized || generation != pendingPlaybackGeneration) {
            RoutingTrace.record(currentCorrelation()) {
                RoutingEventDetail.Debounce(
                    event = DebounceEvent.STALE,
                    delayMs = PLAYBACK_DEBOUNCE_MS,
                    generation = generation,
                    reason = "generation_superseded"
                )
            }
            return
        }
        val current = snapshotFrom(audioManager.activePlaybackConfigurations)
        if (current.eligibleUsages.isEmpty()) {
            handlePlaybackSnapshot(current)
            return
        }
        currentTakeoverAttemptId = "takeover-${currentPlaybackCycleId ?: generation}"
        RoutingTrace.record(currentCorrelation()) {
            RoutingEventDetail.Debounce(
                event = DebounceEvent.FIRED,
                delayMs = PLAYBACK_DEBOUNCE_MS,
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
                    delayMs = PLAYBACK_DEBOUNCE_MS,
                    generation = pendingPlaybackGeneration,
                    reason = reason
                )
            }
        }
        pendingPlaybackRunnable = null
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
                debounceMs = PLAYBACK_DEBOUNCE_MS
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
    fun pauseForTakeover(expectedCorrelation: RoutingCorrelation? = null): Boolean {
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
        if (!initialized || !audioManager.isMusicActive) return false
        pausedWhileTakingOver = true
        sendPause(force = true)
        if (!iPausedTheMedia) {
            pausedWhileTakingOver = false
            return false
        }
        ownershipConfirmationTimeoutRunnable?.let(handler::removeCallbacks)
        val timeoutAttemptId = currentTakeoverAttemptId
        ownershipConfirmationTimeoutRunnable = Runnable {
            handleOwnershipConfirmationTimeout(timeoutAttemptId)
        }.also {
            handler.postDelayed(it, OWNERSHIP_CONFIRMATION_TIMEOUT_MS)
        }
        return iPausedTheMedia
    }

    @Synchronized
    private fun handleOwnershipConfirmationTimeout(expectedAttemptId: String?) {
        if (!pausedWhileTakingOver || expectedAttemptId != currentTakeoverAttemptId) return
        onTakeoverFailed(
            reason = "ownership_confirmation_timeout",
            resumeIfStillActive = false
        )
    }

    @Synchronized
    fun onLocalRouteReady(expectedAttemptId: String?, trigger: String) {
        if (!pausedWhileTakingOver || !iPausedTheMedia ||
            expectedAttemptId == null || expectedAttemptId != currentTakeoverAttemptId
        ) {
            return
        }
        cancelOwnershipConfirmationTimeout()
        RoutingTrace.record(currentCorrelation()) {
            RoutingEventDetail.ActionResult(
                action = me.kavishdevar.librepods.diagnostics.RoutingAction.AUTOMATIC_TAKEOVER,
                outcome = "confirmed",
                reason = "local_route_ready:$trigger"
            )
        }
        sendPlay()
    }

    @Synchronized
    fun onTakeoverFailed(reason: String, resumeIfStillActive: Boolean = false) {
        if (!pausedWhileTakingOver) return
        cancelOwnershipConfirmationTimeout()
        pausedWhileTakingOver = false
        reportedStreamingCorrelation?.let {
            ServiceManager.getService()?.onEligibleLocalPlaybackStopped(it)
        }
        reportedStreamingCorrelation = null
        RoutingTrace.record(currentCorrelation()) {
            RoutingEventDetail.ActionResult(
                action = me.kavishdevar.librepods.diagnostics.RoutingAction.AUTOMATIC_TAKEOVER,
                outcome = "failed",
                reason = reason
            )
        }
        eligiblePlaybackActive = resumeIfStillActive
        takeoverAttemptedInEpisode = false
        transitionPlaybackEpisode(
            if (resumeIfStillActive) PlaybackEpisode.ATTEMPTED else PlaybackEpisode.INACTIVE,
            trigger = "takeover_failed",
            reason = if (resumeIfStillActive) {
                "$reason; wait_for_next_playback_edge"
            } else {
                "$reason; await_user_playback"
            }
        )
        if (!resumeIfStillActive) {
            currentPlaybackCycleId = null
            currentTakeoverAttemptId = null
        }
        if (resumeIfStillActive && iPausedTheMedia) {
            sendPlay()
        } else {
            iPausedTheMedia = false
        }
    }

    @Synchronized
    fun onRemoteOwnershipConfirmed() {
        if (pausedWhileTakingOver) {
            onTakeoverFailed("remote_ownership_confirmed", resumeIfStillActive = false)
        }
        cancelOwnershipConfirmationTimeout()
        cancelPendingPlaybackEvaluation("remote_ownership_confirmed")
        val currentEligible = initialized &&
            snapshotFrom(audioManager.activePlaybackConfigurations).eligibleUsages.isNotEmpty()
        if (currentEligible) {
            transitionPlaybackEpisode(
                PlaybackEpisode.ATTEMPTED,
                trigger = "remote_ownership_confirmed",
                reason = "prevent_automatic_retake_until_next_playback_edge"
            )
            takeoverAttemptedInEpisode = false
        } else {
            transitionPlaybackEpisode(
                PlaybackEpisode.INACTIVE,
                trigger = "remote_ownership_confirmed",
                reason = "no_local_eligible_playback"
            )
            eligiblePlaybackActive = false
            takeoverAttemptedInEpisode = false
            currentPlaybackCycleId = null
            currentTakeoverAttemptId = null
        }
        reportedStreamingCorrelation = null
        pausedWhileTakingOver = false
        if (currentEligible && audioManager.isMusicActive) {
            sendPause(force = true)
        }
    }

    private fun cancelOwnershipConfirmationTimeout() {
        ownershipConfirmationTimeoutRunnable?.let(handler::removeCallbacks)
        ownershipConfirmationTimeoutRunnable = null
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
                debounceMs = PLAYBACK_DEBOUNCE_MS,
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
    fun sendPause(force: Boolean = false) {
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
        }
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
