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
    val remoteGraceMs: Long = 2_500L,
    val remoteRefreshIntervalMs: Long = 1_000L,
    val nativeLeaseDurationMs: Long = 3_500L,
) {
    init {
        require(probeTimeoutMs > 0L)
        require(remoteGraceMs > 0L)
        require(remoteRefreshIntervalMs > 0L)
        require(nativeLeaseDurationMs > remoteRefreshIntervalMs)
        require(nativeLeaseDurationMs > remoteGraceMs)
    }
}

data class RemoteDeviceAudioGuardSnapshot(
    val state: RemoteDeviceAudioGuardState = RemoteDeviceAudioGuardState.OFF,
    val enabled: Boolean = false,
    val aacpConnected: Boolean = false,
    val deadlineMs: Long? = null,
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
        val transition = reduce(snapshot, event, nowMs, config)
        snapshot = transition.current
        return transition
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
                            )
                        }
                        snapshot.aacpConnected -> {
                            actions.protectIfNeeded(snapshot, config)
                            actions += RemoteDeviceAudioGuardAction.RequestAudioSource
                            snapshot.copy(
                                state = RemoteDeviceAudioGuardState.PROBING,
                                enabled = true,
                                deadlineMs = deadlineAfter(nowMs, config.probeTimeoutMs),
                            )
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
                            snapshot.copy(
                                state = RemoteDeviceAudioGuardState.PROBING,
                                aacpConnected = true,
                                deadlineMs = deadlineAfter(nowMs, config.probeTimeoutMs),
                            )
                        }
                    }
                }

                RemoteDeviceAudioGuardEvent.AacpDisconnected -> {
                    actions.releaseIfNeeded(snapshot)
                    next = snapshot.copy(
                        state = RemoteDeviceAudioGuardState.OFF,
                        aacpConnected = false,
                        deadlineMs = null,
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
                                    deadlineMs = null,
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
                                )
                            }
                            AudioSourceOwner.NONE,
                            AudioSourceOwner.UNKNOWN,
                            -> when (snapshot.state) {
                                RemoteDeviceAudioGuardState.REMOTE_ACTIVE -> {
                                    actions += RemoteDeviceAudioGuardAction.RefreshNativeLease(
                                        config.nativeLeaseDurationMs
                                    )
                                    actions += RemoteDeviceAudioGuardAction.RequestAudioSource
                                    snapshot.copy(
                                        state = RemoteDeviceAudioGuardState.REMOTE_GRACE,
                                        deadlineMs = deadlineAfter(nowMs, config.remoteGraceMs),
                                    )
                                }
                                RemoteDeviceAudioGuardState.LOCAL -> snapshot.copy(
                                    state = RemoteDeviceAudioGuardState.IDLE,
                                    deadlineMs = null,
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
                                actions.releaseIfNeeded(snapshot)
                                snapshot.copy(
                                    state = RemoteDeviceAudioGuardState.IDLE,
                                    deadlineMs = null,
                                )
                            }
                            RemoteDeviceAudioGuardState.REMOTE_ACTIVE -> {
                                actions += RemoteDeviceAudioGuardAction.RefreshNativeLease(
                                    config.nativeLeaseDurationMs
                                )
                                actions += RemoteDeviceAudioGuardAction.EnableBarrier
                                actions += RemoteDeviceAudioGuardAction.RequestAudioSource
                                snapshot.copy(
                                    state = RemoteDeviceAudioGuardState.REMOTE_GRACE,
                                    deadlineMs = deadlineAfter(nowMs, config.remoteGraceMs),
                                )
                            }
                            RemoteDeviceAudioGuardState.REMOTE_GRACE -> {
                                actions.releaseIfNeeded(snapshot)
                                snapshot.copy(
                                    state = RemoteDeviceAudioGuardState.IDLE,
                                    deadlineMs = null,
                                )
                            }
                            RemoteDeviceAudioGuardState.OFF,
                            RemoteDeviceAudioGuardState.IDLE,
                            RemoteDeviceAudioGuardState.LOCAL,
                            -> snapshot.copy(deadlineMs = null)
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
