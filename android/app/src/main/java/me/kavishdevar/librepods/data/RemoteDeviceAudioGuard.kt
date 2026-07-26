package me.kavishdevar.librepods.data

enum class RemoteDeviceAudioGuardState {
    OFF,
    PROBING,
    IDLE,
    LOCAL,
    REMOTE_ACTIVE,
    REMOTE_GRACE,
}

enum class RemoteDeviceAudioSourceType {
    NONE,
    CALL,
    MEDIA,
    ACTIVE_UNKNOWN,
    UNKNOWN,
}

sealed interface RemoteDeviceAudioGuardEvent {
    data class EnabledChanged(val enabled: Boolean) : RemoteDeviceAudioGuardEvent

    data object AacpConnected : RemoteDeviceAudioGuardEvent

    data object AacpDisconnected : RemoteDeviceAudioGuardEvent

    data class AudioSourceObserved(
        val localMac: String?,
        val sourceMac: String?,
        val type: RemoteDeviceAudioSourceType,
    ) : RemoteDeviceAudioGuardEvent

    data class RemoteStreamingChanged(
        val localMac: String?,
        val sourceMac: String?,
        val isStreaming: Boolean,
    ) : RemoteDeviceAudioGuardEvent

    data object Tick : RemoteDeviceAudioGuardEvent
}

sealed interface RemoteDeviceAudioGuardAction {
    data object EnableBarrier : RemoteDeviceAudioGuardAction

    data object DisableBarrier : RemoteDeviceAudioGuardAction

    data object RequestAudioSource : RemoteDeviceAudioGuardAction

    data class RefreshNativeLease(val durationMs: Long) : RemoteDeviceAudioGuardAction

    data object ClearNativeLease : RemoteDeviceAudioGuardAction
}

data class RemoteDeviceAudioGuardConfig(
    val probeTimeoutMs: Long = 1_500L,
    val probeRetryIntervalMs: Long = 750L,
    val remoteGraceMs: Long = 2_500L,
    val graceRetryIntervalMs: Long = 1_000L,
    val remoteRefreshIntervalMs: Long = 1_000L,
    val idlePollIntervalMs: Long = 5_000L,
    val stickyRemoteWindowMs: Long = 30_000L,
    // Well above the refresh cadence so a stalled refresh (doze, binder latency) does
    // not lapse the native block; still under the A2dpStartGuardLease 10s hard cap.
    val nativeLeaseDurationMs: Long = 8_000L,
) {
    init {
        require(probeTimeoutMs > 0L)
        require(probeRetryIntervalMs > 0L)
        require(remoteGraceMs > 0L)
        require(graceRetryIntervalMs > 0L)
        require(remoteRefreshIntervalMs > 0L)
        require(idlePollIntervalMs > 0L)
        require(stickyRemoteWindowMs > 0L)
        require(nativeLeaseDurationMs > remoteRefreshIntervalMs)
        require(nativeLeaseDurationMs > remoteGraceMs)
    }
}

data class RemoteDeviceAudioGuardSnapshot(
    val state: RemoteDeviceAudioGuardState = RemoteDeviceAudioGuardState.OFF,
    val enabled: Boolean = false,
    val aacpConnected: Boolean = false,
    val deadlineMs: Long? = null,
    // Absolute end of the current PROBING/REMOTE_GRACE phase; deadlineMs is the next
    // wake-up (retry or phase end), which is earlier when retries are pending.
    val phaseEndMs: Long? = null,
    // While set and in the future, a probe timeout fails CLOSED (into REMOTE_GRACE)
    // because a remote host was streaming shortly before the last AACP drop.
    val recentRemoteUntilMs: Long? = null,
) {
    val barrierEnabled: Boolean
        get() = state == RemoteDeviceAudioGuardState.PROBING ||
            state == RemoteDeviceAudioGuardState.REMOTE_ACTIVE ||
            state == RemoteDeviceAudioGuardState.REMOTE_GRACE
}

data class RemoteDeviceAudioGuardTransition(
    val previous: RemoteDeviceAudioGuardSnapshot,
    val current: RemoteDeviceAudioGuardSnapshot,
    val actions: List<RemoteDeviceAudioGuardAction>,
) {
    val state: RemoteDeviceAudioGuardState
        get() = current.state

    val nextDeadlineMs: Long?
        get() = current.deadlineMs
}

/**
 * A deterministic policy machine. Callers execute the returned actions and deliver [Tick]
 * at (or after) [RemoteDeviceAudioGuardTransition.nextDeadlineMs].
 *
 * [dispatch] first settles an already-overdue deadline before applying a non-Tick event,
 * so late Handler delivery (doze, main-thread congestion) cannot leave stale phases
 * unprocessed once any event arrives.
 */
class RemoteDeviceAudioGuard(
    private val config: RemoteDeviceAudioGuardConfig = RemoteDeviceAudioGuardConfig(),
    initialSnapshot: RemoteDeviceAudioGuardSnapshot = RemoteDeviceAudioGuardSnapshot(),
) {
    var snapshot: RemoteDeviceAudioGuardSnapshot = initialSnapshot
        private set

    fun dispatch(
        event: RemoteDeviceAudioGuardEvent,
        nowMs: Long,
    ): RemoteDeviceAudioGuardTransition {
        val original = snapshot
        val overdue = event != RemoteDeviceAudioGuardEvent.Tick &&
            snapshot.deadlineMs?.let { nowMs >= it } == true
        val preTick = if (overdue) {
            reduce(snapshot, RemoteDeviceAudioGuardEvent.Tick, nowMs, config)
        } else {
            null
        }
        val base = preTick?.current ?: snapshot
        val main = reduce(base, event, nowMs, config)
        snapshot = main.current
        return if (preTick == null) {
            main
        } else {
            RemoteDeviceAudioGuardTransition(
                previous = original,
                current = main.current,
                actions = preTick.actions + main.actions,
            )
        }
    }

    companion object {
        fun reduce(
            snapshot: RemoteDeviceAudioGuardSnapshot,
            event: RemoteDeviceAudioGuardEvent,
            nowMs: Long,
            config: RemoteDeviceAudioGuardConfig = RemoteDeviceAudioGuardConfig(),
        ): RemoteDeviceAudioGuardTransition {
            require(nowMs >= 0L)

            val next: RemoteDeviceAudioGuardSnapshot
            val actions = mutableListOf<RemoteDeviceAudioGuardAction>()

            when (event) {
                is RemoteDeviceAudioGuardEvent.EnabledChanged -> {
                    next = when {
                        event.enabled == snapshot.enabled -> snapshot
                        !event.enabled -> {
                            actions.releaseIfNeeded(snapshot)
                            snapshot.copy(
                                state = RemoteDeviceAudioGuardState.OFF,
                                enabled = false,
                                deadlineMs = null,
                                phaseEndMs = null,
                                recentRemoteUntilMs = null,
                            )
                        }
                        snapshot.aacpConnected -> {
                            actions.protectIfNeeded(snapshot, config)
                            actions += RemoteDeviceAudioGuardAction.RequestAudioSource
                            snapshot.enterProbing(nowMs, config).copy(enabled = true)
                        }
                        else -> snapshot.copy(enabled = true)
                    }
                }

                RemoteDeviceAudioGuardEvent.AacpConnected -> {
                    next = when {
                        !snapshot.enabled -> snapshot.copy(aacpConnected = true)
                        snapshot.aacpConnected && snapshot.state != RemoteDeviceAudioGuardState.OFF ->
                            snapshot
                        else -> {
                            actions.protectIfNeeded(snapshot, config)
                            actions += RemoteDeviceAudioGuardAction.RequestAudioSource
                            snapshot.enterProbing(nowMs, config).copy(aacpConnected = true)
                        }
                    }
                }

                RemoteDeviceAudioGuardEvent.AacpDisconnected -> {
                    actions.releaseIfNeeded(snapshot)
                    val stickyUntil = if (snapshot.state == RemoteDeviceAudioGuardState.REMOTE_ACTIVE ||
                        snapshot.state == RemoteDeviceAudioGuardState.REMOTE_GRACE
                    ) {
                        deadlineAfter(nowMs, config.stickyRemoteWindowMs)
                    } else {
                        snapshot.recentRemoteUntilMs
                    }
                    next = snapshot.copy(
                        state = RemoteDeviceAudioGuardState.OFF,
                        aacpConnected = false,
                        deadlineMs = null,
                        phaseEndMs = null,
                        recentRemoteUntilMs = stickyUntil,
                    )
                }

                is RemoteDeviceAudioGuardEvent.AudioSourceObserved -> {
                    next = if (!snapshot.enabled || !snapshot.aacpConnected) {
                        snapshot
                    } else {
                        when (event.owner()) {
                            AudioSourceOwner.LOCAL -> {
                                actions.releaseIfNeeded(snapshot)
                                snapshot.copy(
                                    state = RemoteDeviceAudioGuardState.LOCAL,
                                    deadlineMs = deadlineAfter(nowMs, config.idlePollIntervalMs),
                                    phaseEndMs = null,
                                    recentRemoteUntilMs = null,
                                )
                            }
                            AudioSourceOwner.REMOTE -> {
                                actions.protectOrRefresh(config)
                                snapshot.copy(
                                    state = RemoteDeviceAudioGuardState.REMOTE_ACTIVE,
                                    deadlineMs = deadlineAfter(
                                        nowMs,
                                        config.remoteRefreshIntervalMs,
                                    ),
                                    phaseEndMs = null,
                                    recentRemoteUntilMs = null,
                                )
                            }
                            AudioSourceOwner.NONE -> when (snapshot.state) {
                                RemoteDeviceAudioGuardState.PROBING -> {
                                    actions.releaseIfNeeded(snapshot)
                                    snapshot.copy(
                                        state = RemoteDeviceAudioGuardState.IDLE,
                                        deadlineMs = deadlineAfter(
                                            nowMs,
                                            config.idlePollIntervalMs,
                                        ),
                                        phaseEndMs = null,
                                        recentRemoteUntilMs = null,
                                    )
                                }
                                RemoteDeviceAudioGuardState.REMOTE_ACTIVE ->
                                    snapshot.enterGrace(actions, nowMs, config)
                                RemoteDeviceAudioGuardState.LOCAL -> snapshot.copy(
                                    state = RemoteDeviceAudioGuardState.IDLE,
                                    deadlineMs = deadlineAfter(nowMs, config.idlePollIntervalMs),
                                    phaseEndMs = null,
                                )
                                else -> snapshot
                            }
                            AudioSourceOwner.UNKNOWN -> when (snapshot.state) {
                                RemoteDeviceAudioGuardState.REMOTE_ACTIVE ->
                                    snapshot.enterGrace(actions, nowMs, config)
                                RemoteDeviceAudioGuardState.LOCAL -> snapshot.copy(
                                    state = RemoteDeviceAudioGuardState.IDLE,
                                    deadlineMs = deadlineAfter(nowMs, config.idlePollIntervalMs),
                                    phaseEndMs = null,
                                )
                                else -> snapshot
                            }
                        }
                    }
                }

                is RemoteDeviceAudioGuardEvent.RemoteStreamingChanged -> {
                    val remoteSender = event.hasRemoteSender()
                    next = if (!snapshot.enabled || !snapshot.aacpConnected || !remoteSender) {
                        snapshot
                    } else if (event.isStreaming) {
                        actions.protectOrRefresh(config)
                        actions += RemoteDeviceAudioGuardAction.RequestAudioSource
                        snapshot.copy(
                            state = RemoteDeviceAudioGuardState.REMOTE_ACTIVE,
                            deadlineMs = deadlineAfter(nowMs, config.remoteRefreshIntervalMs),
                            phaseEndMs = null,
                            recentRemoteUntilMs = null,
                        )
                    } else {
                        actions += RemoteDeviceAudioGuardAction.RequestAudioSource
                        snapshot
                    }
                }

                RemoteDeviceAudioGuardEvent.Tick -> {
                    val deadlineReached = snapshot.deadlineMs?.let { nowMs >= it } == true
                    next = if (!snapshot.enabled || !snapshot.aacpConnected || !deadlineReached) {
                        snapshot
                    } else {
                        when (snapshot.state) {
                            RemoteDeviceAudioGuardState.PROBING -> {
                                val phaseEnded = snapshot.phaseEndMs?.let { nowMs >= it } != false
                                when {
                                    !phaseEnded -> {
                                        actions += RemoteDeviceAudioGuardAction.RequestAudioSource
                                        snapshot.copy(
                                            deadlineMs = retryDeadline(
                                                nowMs,
                                                config.probeRetryIntervalMs,
                                                snapshot.phaseEndMs,
                                            ),
                                        )
                                    }
                                    snapshot.recentRemoteUntilMs?.let { nowMs < it } == true ->
                                        snapshot.enterGrace(actions, nowMs, config)
                                    else -> {
                                        actions.releaseIfNeeded(snapshot)
                                        snapshot.copy(
                                            state = RemoteDeviceAudioGuardState.IDLE,
                                            deadlineMs = deadlineAfter(
                                                nowMs,
                                                config.idlePollIntervalMs,
                                            ),
                                            phaseEndMs = null,
                                        )
                                    }
                                }
                            }
                            RemoteDeviceAudioGuardState.REMOTE_ACTIVE ->
                                snapshot.enterGrace(actions, nowMs, config)
                            RemoteDeviceAudioGuardState.REMOTE_GRACE -> {
                                val phaseEnded = snapshot.phaseEndMs?.let { nowMs >= it } != false
                                if (!phaseEnded) {
                                    actions += RemoteDeviceAudioGuardAction.RefreshNativeLease(
                                        config.nativeLeaseDurationMs
                                    )
                                    actions += RemoteDeviceAudioGuardAction.RequestAudioSource
                                    snapshot.copy(
                                        deadlineMs = retryDeadline(
                                            nowMs,
                                            config.graceRetryIntervalMs,
                                            snapshot.phaseEndMs,
                                        ),
                                    )
                                } else {
                                    actions.releaseIfNeeded(snapshot)
                                    // First re-poll comes quickly so a merely-lost answer
                                    // (Mac still streaming) is unprotected only briefly.
                                    snapshot.copy(
                                        state = RemoteDeviceAudioGuardState.IDLE,
                                        deadlineMs = deadlineAfter(
                                            nowMs,
                                            config.graceRetryIntervalMs,
                                        ),
                                        phaseEndMs = null,
                                    )
                                }
                            }
                            RemoteDeviceAudioGuardState.IDLE,
                            RemoteDeviceAudioGuardState.LOCAL,
                            -> {
                                actions += RemoteDeviceAudioGuardAction.RequestAudioSource
                                snapshot.copy(
                                    deadlineMs = deadlineAfter(nowMs, config.idlePollIntervalMs),
                                )
                            }
                            RemoteDeviceAudioGuardState.OFF -> snapshot.copy(deadlineMs = null)
                        }
                    }
                }
            }

            return RemoteDeviceAudioGuardTransition(
                previous = snapshot,
                current = next,
                actions = actions,
            )
        }

        private fun deadlineAfter(nowMs: Long, delayMs: Long): Long =
            if (Long.MAX_VALUE - nowMs < delayMs) Long.MAX_VALUE else nowMs + delayMs

        private fun retryDeadline(nowMs: Long, intervalMs: Long, phaseEndMs: Long?): Long {
            val nextRetry = deadlineAfter(nowMs, intervalMs)
            return if (phaseEndMs != null) minOf(nextRetry, phaseEndMs) else nextRetry
        }

        private fun RemoteDeviceAudioGuardSnapshot.enterProbing(
            nowMs: Long,
            config: RemoteDeviceAudioGuardConfig,
        ): RemoteDeviceAudioGuardSnapshot {
            val phaseEnd = deadlineAfter(nowMs, config.probeTimeoutMs)
            return copy(
                state = RemoteDeviceAudioGuardState.PROBING,
                deadlineMs = retryDeadline(nowMs, config.probeRetryIntervalMs, phaseEnd),
                phaseEndMs = phaseEnd,
            )
        }

        private fun RemoteDeviceAudioGuardSnapshot.enterGrace(
            actions: MutableList<RemoteDeviceAudioGuardAction>,
            nowMs: Long,
            config: RemoteDeviceAudioGuardConfig,
        ): RemoteDeviceAudioGuardSnapshot {
            if (state == RemoteDeviceAudioGuardState.REMOTE_ACTIVE ||
                state == RemoteDeviceAudioGuardState.REMOTE_GRACE
            ) {
                actions += RemoteDeviceAudioGuardAction.RefreshNativeLease(
                    config.nativeLeaseDurationMs
                )
                if (state == RemoteDeviceAudioGuardState.REMOTE_ACTIVE) {
                    actions += RemoteDeviceAudioGuardAction.EnableBarrier
                }
            } else {
                actions.protectOrRefresh(config)
            }
            actions += RemoteDeviceAudioGuardAction.RequestAudioSource
            val phaseEnd = deadlineAfter(nowMs, config.remoteGraceMs)
            return copy(
                state = RemoteDeviceAudioGuardState.REMOTE_GRACE,
                deadlineMs = retryDeadline(nowMs, config.graceRetryIntervalMs, phaseEnd),
                phaseEndMs = phaseEnd,
            )
        }

        private fun MutableList<RemoteDeviceAudioGuardAction>.protectIfNeeded(
            snapshot: RemoteDeviceAudioGuardSnapshot,
            config: RemoteDeviceAudioGuardConfig,
        ) {
            if (!snapshot.barrierEnabled) {
                add(RemoteDeviceAudioGuardAction.RefreshNativeLease(config.nativeLeaseDurationMs))
                add(RemoteDeviceAudioGuardAction.EnableBarrier)
            }
        }

        private fun MutableList<RemoteDeviceAudioGuardAction>.protectOrRefresh(
            config: RemoteDeviceAudioGuardConfig,
        ) {
            add(RemoteDeviceAudioGuardAction.RefreshNativeLease(config.nativeLeaseDurationMs))
            add(RemoteDeviceAudioGuardAction.EnableBarrier)
        }

        private fun MutableList<RemoteDeviceAudioGuardAction>.releaseIfNeeded(
            snapshot: RemoteDeviceAudioGuardSnapshot,
        ) {
            if (snapshot.barrierEnabled) {
                add(RemoteDeviceAudioGuardAction.DisableBarrier)
                add(RemoteDeviceAudioGuardAction.ClearNativeLease)
            }
        }

        private fun RemoteDeviceAudioGuardEvent.AudioSourceObserved.owner(): AudioSourceOwner =
            when (type) {
                RemoteDeviceAudioSourceType.NONE -> AudioSourceOwner.NONE
                RemoteDeviceAudioSourceType.UNKNOWN -> AudioSourceOwner.UNKNOWN
                RemoteDeviceAudioSourceType.CALL,
                RemoteDeviceAudioSourceType.MEDIA,
                RemoteDeviceAudioSourceType.ACTIVE_UNKNOWN,
                -> {
                    val normalizedLocalMac = LocalHostIdentity.normalize(localMac)
                    val normalizedSourceMac = LocalHostIdentity.normalize(sourceMac)
                    when {
                        normalizedSourceMac == null -> AudioSourceOwner.UNKNOWN
                        normalizedLocalMac == null -> AudioSourceOwner.UNKNOWN
                        normalizedSourceMac == normalizedLocalMac -> AudioSourceOwner.LOCAL
                        else -> AudioSourceOwner.REMOTE
                    }
                }
            }

        private fun RemoteDeviceAudioGuardEvent.RemoteStreamingChanged.hasRemoteSender(): Boolean {
            val normalizedLocalMac = LocalHostIdentity.normalize(localMac)
            val normalizedSourceMac = LocalHostIdentity.normalize(sourceMac)
            return normalizedLocalMac != null &&
                normalizedSourceMac != null &&
                normalizedSourceMac != normalizedLocalMac
        }

    }

    private enum class AudioSourceOwner {
        LOCAL,
        REMOTE,
        NONE,
        UNKNOWN,
    }
}
