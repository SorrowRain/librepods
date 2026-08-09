package me.kavishdevar.librepods.bluetooth

internal data class ProfileRepairPermit(
    val generation: Long,
    val attempt: Int,
)

internal data class ControlReconnectPermit(
    val generation: Long,
    val attempt: Int,
)

internal data class InitialReconnectPermit(
    val generation: Long,
    val attempt: Int,
)

internal data class HeartRateRecoveryPermit(
    val generation: Long,
    val attempt: Int,
    val userInitiated: Boolean,
)

/** Bounds reconnect work to one physical AirPods presence episode. */
internal class ReconnectEpisodeGate(
    private val maxInitialConnectAttempts: Int = 2,
    private val maxProfileRepairAttempts: Int = 2,
    private val maxControlReconnectAttempts: Int = 3,
    private val maxHeartRateTransportResets: Int = 3,
    private val maxAutomaticHeartRateRecoveryAttempts: Int = 1,
    private val maxManualHeartRateRecoveryAttempts: Int = 2,
) {
    private var generation = 0L
    private var initialConnectAttempts = 0
    private var initialConnectInFlight = false
    private var initialConnectSatisfied = false
    private var profileRepairAttempts = 0
    private var profileRepairInFlight = false
    private var controlReconnectAttempts = 0
    private var controlReconnectInFlight = false
    private var heartRateTransportResets = 0
    private var automaticHeartRateRecoveryAttempts = 0
    private var manualHeartRateRecoveryAttempts = 0

    init {
        require(maxInitialConnectAttempts > 0)
        require(maxProfileRepairAttempts > 0)
        require(maxControlReconnectAttempts > 0)
        require(maxHeartRateTransportResets > 0)
        require(maxAutomaticHeartRateRecoveryAttempts > 0)
        require(maxManualHeartRateRecoveryAttempts > 0)
    }

    @Synchronized
    fun tryBeginInitialConnect(): InitialReconnectPermit? {
        if (initialConnectSatisfied || initialConnectInFlight ||
            initialConnectAttempts >= maxInitialConnectAttempts
        ) {
            return null
        }
        initialConnectAttempts++
        initialConnectInFlight = true
        return InitialReconnectPermit(generation, initialConnectAttempts)
    }

    @Synchronized
    fun finishInitialConnect(permit: InitialReconnectPermit, succeeded: Boolean) {
        if (permit.generation != generation) return
        initialConnectInFlight = false
        if (succeeded) initialConnectSatisfied = true
    }

    @Synchronized
    fun tryBeginProfileRepair(): ProfileRepairPermit? {
        if (profileRepairInFlight || profileRepairAttempts >= maxProfileRepairAttempts) return null
        profileRepairAttempts++
        profileRepairInFlight = true
        return ProfileRepairPermit(generation, profileRepairAttempts)
    }

    @Synchronized
    fun finishProfileRepair(permit: ProfileRepairPermit, succeeded: Boolean = false) {
        if (permit.generation != generation) return
        profileRepairInFlight = false
        if (succeeded && profileRepairAttempts > 0) profileRepairAttempts--
    }

    @Synchronized
    fun tryBeginControlReconnect(): ControlReconnectPermit? {
        if (controlReconnectInFlight || controlReconnectAttempts >= maxControlReconnectAttempts) {
            return null
        }
        controlReconnectAttempts++
        controlReconnectInFlight = true
        return ControlReconnectPermit(generation, controlReconnectAttempts)
    }

    @Synchronized
    fun finishControlReconnect(permit: ControlReconnectPermit, succeeded: Boolean = false) {
        if (permit.generation != generation) return
        controlReconnectInFlight = false
        // Successful attempts hand their permit back: the budget bounds futile retry
        // storms, not the number of clean recoveries in a long wear session.
        if (succeeded && controlReconnectAttempts > 0) controlReconnectAttempts--
    }

    /** Bounds repeated malformed RTBuddy reconnects even when each socket reconnect succeeds. */
    @Synchronized
    fun tryConsumeHeartRateTransportReset(): Boolean {
        if (heartRateTransportResets >= maxHeartRateTransportResets) return false
        heartRateTransportResets++
        return true
    }

    /**
     * Claims a non-refundable AACP reset for HRM. Automatic failures get one attempt per presence
     * episode; explicit Retry taps have a separate small budget and still use the lifecycle gate.
     */
    @Synchronized
    fun tryBeginHeartRateRecovery(userInitiated: Boolean): HeartRateRecoveryPermit? {
        val attempt = if (userInitiated) {
            if (manualHeartRateRecoveryAttempts >= maxManualHeartRateRecoveryAttempts) return null
            ++manualHeartRateRecoveryAttempts
        } else {
            if (automaticHeartRateRecoveryAttempts >= maxAutomaticHeartRateRecoveryAttempts) {
                return null
            }
            ++automaticHeartRateRecoveryAttempts
        }
        return HeartRateRecoveryPermit(generation, attempt, userInitiated)
    }

    @Synchronized
    fun isCurrent(expectedGeneration: Long): Boolean = generation == expectedGeneration

    @Synchronized
    fun reset() {
        generation++
        initialConnectAttempts = 0
        initialConnectInFlight = false
        initialConnectSatisfied = false
        profileRepairAttempts = 0
        profileRepairInFlight = false
        controlReconnectAttempts = 0
        controlReconnectInFlight = false
        heartRateTransportResets = 0
        automaticHeartRateRecoveryAttempts = 0
        manualHeartRateRecoveryAttempts = 0
    }
}
