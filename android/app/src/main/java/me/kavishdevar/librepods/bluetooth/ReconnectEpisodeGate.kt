package me.kavishdevar.librepods.bluetooth

internal data class ProfileRepairPermit(
    val generation: Long,
    val attempt: Int,
)

internal data class ControlReconnectPermit(
    val generation: Long,
    val attempt: Int,
)

internal class ReconnectEpisodeGate(
    private val maxProfileRepairAttempts: Int = 2,
    private val maxControlReconnectAttempts: Int = 3,
) {
    private var generation = 0L
    private var initialConnectConsumed = false
    private var profileRepairAttempts = 0
    private var profileRepairInFlight = false
    private var controlReconnectAttempts = 0
    private var controlReconnectInFlight = false

    init {
        require(maxProfileRepairAttempts > 0)
        require(maxControlReconnectAttempts > 0)
    }

    @Synchronized
    fun tryBeginInitialConnect(): Long? {
        if (initialConnectConsumed) return null
        initialConnectConsumed = true
        return generation
    }

    @Synchronized
    fun tryBeginProfileRepair(): ProfileRepairPermit? {
        if (profileRepairInFlight || profileRepairAttempts >= maxProfileRepairAttempts) return null
        profileRepairAttempts++
        profileRepairInFlight = true
        return ProfileRepairPermit(generation, profileRepairAttempts)
    }

    @Synchronized
    fun finishProfileRepair(permit: ProfileRepairPermit) {
        if (permit.generation == generation) {
            profileRepairInFlight = false
        }
    }

    @Synchronized
    fun tryBeginControlReconnect(): ControlReconnectPermit? {
        if (controlReconnectInFlight ||
            controlReconnectAttempts >= maxControlReconnectAttempts
        ) {
            return null
        }
        controlReconnectAttempts++
        controlReconnectInFlight = true
        return ControlReconnectPermit(generation, controlReconnectAttempts)
    }

    @Synchronized
    fun finishControlReconnect(permit: ControlReconnectPermit) {
        if (permit.generation == generation) {
            controlReconnectInFlight = false
        }
    }

    @Synchronized
    fun isCurrent(expectedGeneration: Long): Boolean = generation == expectedGeneration

    @Synchronized
    fun reset() {
        generation++
        initialConnectConsumed = false
        profileRepairAttempts = 0
        profileRepairInFlight = false
        controlReconnectAttempts = 0
        controlReconnectInFlight = false
    }
}
