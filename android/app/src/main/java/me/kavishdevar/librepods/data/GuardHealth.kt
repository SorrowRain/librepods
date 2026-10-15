package me.kavishdevar.librepods.data

import me.kavishdevar.librepods.audio.AudioHandoffPhase

enum class GuardHealthLevel {
    /** The guard is on and every protection layer it needs is in place. */
    ACTIVE,

    /** Protection works, but one layer is missing (currently: the native A2DP block). */
    DEGRADED,

    /** The guard is on but cannot protect anything — the user must fix something. */
    INACTIVE,

    /** Turned off by the user. */
    DISABLED,
}

enum class GuardHealthReason {
    DISABLED_BY_USER,

    /** MODIFY_AUDIO_ROUTING is missing — the app is not installed as a priv-app. */
    PERMISSION_MISSING,

    /** The dynamic AudioPolicy could not be registered. */
    BARRIER_FAILED,

    /**
     * The AudioPolicy could not be released, so phone audio stays pinned to the speaker
     * even when no remote host is using the AirPods. The opposite symptom of
     * [BARRIER_FAILED] and it needs its own wording.
     */
    BARRIER_STUCK,

    /** Without the phone's own adapter MAC no audio source can be attributed to a host. */
    LOCAL_MAC_UNKNOWN,

    /** The LSPosed remote-preferences path to the Bluetooth process is unavailable. */
    NATIVE_TRANSPORT_MISSING,

    /**
     * The AudioPolicy registered but the preferred-device override was not applied, so
     * already-playing media stalls on the AirPods instead of moving to the speaker — the guard
     * silently stops the phone instead of protecting it. Non-blocking (the native A2DP block still
     * runs), but the symptom is worse than a missing layer, so it degrades health.
     */
    PREFERRED_ROUTE_MISSING,
}

data class GuardHealth(
    val level: GuardHealthLevel = GuardHealthLevel.DISABLED,
    /** Most severe first; empty when [level] is ACTIVE. */
    val reasons: List<GuardHealthReason> = emptyList(),
    val barrierState: String = "not_initialized",
    val barrierActive: Boolean = false,
    /** False means already-playing media stalls instead of moving to the speaker. */
    val preferredRouteApplied: Boolean = false,
    val nativeLeaseAvailable: Boolean = false,
    val localMacResolved: Boolean = false,
    val aacpConnected: Boolean = false,
    val guardState: AudioHandoffPhase = AudioHandoffPhase.DISCONNECTED,
) {
    val primaryReason: GuardHealthReason?
        get() = reasons.firstOrNull()

    companion object {
        /**
         * Barrier states reported by PhoneSoundRoutingController that mean the speaker
         * barrier could not be put in place. "permission_missing" is handled separately
         * because it has its own actionable remedy.
         */
        private val failedBarrierStates = setOf(
            "registration_failed",
            "speaker_unavailable",
            "not_initialized",
        )

        /**
         * States where the policy is stuck registered: [PhoneSoundRoutingStatus.active]
         * stays true and audio keeps rendering to the speaker until the retry succeeds.
         */
        private val stuckBarrierStates = setOf(
            "registration_cleanup_failed",
            "unregistration_failed",
        )

        fun evaluate(
            enabled: Boolean,
            barrierState: String,
            barrierActive: Boolean,
            nativeLeaseAvailable: Boolean,
            localMacResolved: Boolean,
            aacpConnected: Boolean,
            guardState: AudioHandoffPhase,
            preferredRouteApplied: Boolean = false,
            protectionFailed: Boolean = false,
        ): GuardHealth {
            val reasons = mutableListOf<GuardHealthReason>()
            if (!enabled) {
                reasons += GuardHealthReason.DISABLED_BY_USER
                return GuardHealth(
                    level = GuardHealthLevel.DISABLED,
                    reasons = reasons,
                    barrierState = barrierState,
                    barrierActive = barrierActive,
                    preferredRouteApplied = preferredRouteApplied,
                    nativeLeaseAvailable = nativeLeaseAvailable,
                    localMacResolved = localMacResolved,
                    aacpConnected = aacpConnected,
                    guardState = guardState,
                )
            }

            // Ordered most severe first: each of these alone means no protection at all.
            if (barrierState == "permission_missing") reasons += GuardHealthReason.PERMISSION_MISSING
            if (barrierState in failedBarrierStates || protectionFailed) reasons += GuardHealthReason.BARRIER_FAILED
            if (barrierState in stuckBarrierStates) reasons += GuardHealthReason.BARRIER_STUCK
            if (!localMacResolved) reasons += GuardHealthReason.LOCAL_MAC_UNKNOWN
            val blocking = reasons.isNotEmpty()
            if (!nativeLeaseAvailable) reasons += GuardHealthReason.NATIVE_TRANSPORT_MISSING
            // A registered-but-not-preferred barrier silently stalls already-playing media instead
            // of moving it to the speaker. Only relevant when the barrier is actually supposed to
            // be protecting (a barrier-enabled guard state with a registered policy).
            val barrierEnabledState = guardState == AudioHandoffPhase.REMOTE
            if (barrierEnabledState &&
                barrierState == "registered" &&
                !preferredRouteApplied
            ) {
                reasons += GuardHealthReason.PREFERRED_ROUTE_MISSING
            }

            val level = when {
                blocking -> GuardHealthLevel.INACTIVE
                reasons.isNotEmpty() -> GuardHealthLevel.DEGRADED
                else -> GuardHealthLevel.ACTIVE
            }
            return GuardHealth(
                level = level,
                reasons = reasons,
                barrierState = barrierState,
                barrierActive = barrierActive,
                preferredRouteApplied = preferredRouteApplied,
                nativeLeaseAvailable = nativeLeaseAvailable,
                localMacResolved = localMacResolved,
                aacpConnected = aacpConnected,
                guardState = guardState,
            )
        }
    }
}
