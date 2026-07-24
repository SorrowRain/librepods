package me.kavishdevar.librepods.bluetooth

internal data class ReconnectLifecycleToken(
    val generation: Long,
    val operation: Long,
)

internal sealed interface ReconnectLifecycleBegin {
    data class Started(val token: ReconnectLifecycleToken) : ReconnectLifecycleBegin
    data class InFlight(val token: ReconnectLifecycleToken) : ReconnectLifecycleBegin
    data object Suppressed : ReconnectLifecycleBegin
}

/** Orders reconnect jobs against explicit disconnects and newer presence episodes. */
internal class ReconnectLifecycleGate {
    private var generation = 0L
    private var nextOperation = 0L
    private var suppressed = false
    private var inFlight: ReconnectLifecycleToken? = null

    @Synchronized
    fun begin(freshPresence: Boolean): ReconnectLifecycleBegin {
        if (freshPresence && suppressed) {
            suppressed = false
            generation++
            inFlight = null
        }
        if (suppressed) return ReconnectLifecycleBegin.Suppressed

        inFlight?.takeIf { it.generation == generation }?.let {
            return ReconnectLifecycleBegin.InFlight(it)
        }
        val token = ReconnectLifecycleToken(generation, ++nextOperation)
        inFlight = token
        return ReconnectLifecycleBegin.Started(token)
    }

    @Synchronized
    fun currentToken(): ReconnectLifecycleToken? = if (suppressed) {
        null
    } else {
        ReconnectLifecycleToken(generation, nextOperation)
    }

    @Synchronized
    fun isCurrent(token: ReconnectLifecycleToken): Boolean =
        !suppressed && token.generation == generation

    @Synchronized
    fun isSuppressed(): Boolean = suppressed

    @Synchronized
    fun finish(token: ReconnectLifecycleToken) {
        if (inFlight == token) inFlight = null
    }

    @Synchronized
    fun invalidate() {
        generation++
        inFlight = null
    }

    @Synchronized
    fun suppress() {
        suppressed = true
        generation++
        inFlight = null
    }

    @Synchronized
    fun allow() {
        suppressed = false
        generation++
        inFlight = null
    }
}
