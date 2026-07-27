package me.kavishdevar.librepods.bluetooth

import java.util.concurrent.atomic.AtomicInteger

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

/** Serializes automatic audio-profile connects against remote-audio protection changes. */
internal class AudioProfileRepairFence {
    private val operationLock = Any()
    private var guardProtectionActive = false
    // This becomes visible without waiting for operationLock, so a protection request that
    // arrives during setConnectionPolicy blocks the callback's following connect call.
    private val pendingProtectionCount = AtomicInteger(0)

    val isProtected: Boolean
        get() {
            if (pendingProtectionCount.get() > 0) return true
            return synchronized(operationLock) {
                guardProtectionActive || pendingProtectionCount.get() > 0
            }
        }

    fun protect(): Boolean = synchronized(operationLock) {
        val newlyProtected = !guardProtectionActive
        guardProtectionActive = true
        newlyProtected
    }

    fun release() = synchronized(operationLock) {
        guardProtectionActive = false
    }

    fun beginPendingProtection() {
        pendingProtectionCount.incrementAndGet()
    }

    fun finishPendingProtection(): Boolean {
        decrementPendingProtection()
        return !isProtected
    }

    fun runIfAllowed(
        additionalGuard: () -> Boolean,
        operation: () -> Unit,
    ): Boolean = synchronized(operationLock) {
        if (guardProtectionActive || pendingProtectionCount.get() > 0 || !additionalGuard()) {
            false
        } else {
            operation()
            true
        }
    }

    private fun decrementPendingProtection(): Int {
        while (true) {
            val current = pendingProtectionCount.get()
            check(current > 0) { "No pending audio-profile protection to finish" }
            if (pendingProtectionCount.compareAndSet(current, current - 1)) {
                return current - 1
            }
        }
    }
}
