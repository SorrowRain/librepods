package me.kavishdevar.librepods.data

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
}

data class GuardHealth(
    val level: GuardHealthLevel = GuardHealthLevel.DISABLED,
    /** Most severe first; empty when [level] is ACTIVE. */
    val reasons: List<GuardHealthReason> = emptyList(),
    val barrierState: String = "not_initialized",
    val barrierActive: Boolean = false,
    val nativeLeaseAvailable: Boolean = false,
    val localMacResolved: Boolean = false,
    val aacpConnected: Boolean = false,
    val guardState: RemoteDeviceAudioGuardState = RemoteDeviceAudioGuardState.OFF,
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
            guardState: RemoteDeviceAudioGuardState,
        ): GuardHealth {
            val reasons = mutableListOf<GuardHealthReason>()
            if (!enabled) {
                reasons += GuardHealthReason.DISABLED_BY_USER
                return GuardHealth(
                    level = GuardHealthLevel.DISABLED,
                    reasons = reasons,
                    barrierState = barrierState,
                    barrierActive = barrierActive,
                    nativeLeaseAvailable = nativeLeaseAvailable,
                    localMacResolved = localMacResolved,
                    aacpConnected = aacpConnected,
                    guardState = guardState,
                )
            }

            // Ordered most severe first: each of these alone means no protection at all.
            if (barrierState == "permission_missing") reasons += GuardHealthReason.PERMISSION_MISSING
            if (barrierState in failedBarrierStates) reasons += GuardHealthReason.BARRIER_FAILED
            if (barrierState in stuckBarrierStates) reasons += GuardHealthReason.BARRIER_STUCK
            if (!localMacResolved) reasons += GuardHealthReason.LOCAL_MAC_UNKNOWN
            val blocking = reasons.isNotEmpty()
            if (!nativeLeaseAvailable) reasons += GuardHealthReason.NATIVE_TRANSPORT_MISSING

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
                nativeLeaseAvailable = nativeLeaseAvailable,
                localMacResolved = localMacResolved,
                aacpConnected = aacpConnected,
                guardState = guardState,
            )
        }
    }
}
