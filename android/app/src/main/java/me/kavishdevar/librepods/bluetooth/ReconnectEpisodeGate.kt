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

/** Bounds reconnect work to one physical AirPods presence episode. */
internal class ReconnectEpisodeGate(
    private val maxInitialConnectAttempts: Int = 2,
    private val maxProfileRepairAttempts: Int = 2,
    private val maxControlReconnectAttempts: Int = 3,
) {
    private var generation = 0L
    private var initialConnectAttempts = 0
    private var initialConnectInFlight = false
    private var initialConnectSatisfied = false
    private var profileRepairAttempts = 0
    private var profileRepairInFlight = false
    private var controlReconnectAttempts = 0
    private var controlReconnectInFlight = false

    init {
        require(maxInitialConnectAttempts > 0)
        require(maxProfileRepairAttempts > 0)
        require(maxControlReconnectAttempts > 0)
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
    }
}
