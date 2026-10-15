package me.kavishdevar.librepods.audio

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface HandoffPlatform {
    /** Returns false if routing or native-lease release could not be confirmed. */
    suspend fun setProtection(enabled: Boolean): Boolean
    suspend fun refreshProtection(): Boolean
}

internal interface HandoffConnection {
    suspend fun takePhone(trigger: HandoffTrigger, isCurrent: () -> Boolean): Boolean
    suspend fun querySource()
    suspend fun clearCallRoute() = Unit
}

/** Serial decisions, serialized routing effects, and one cancellable handoff operation. */
internal class AudioHandoffCoordinator(
    parentScope: CoroutineScope,
    private val platform: HandoffPlatform,
    private val connection: HandoffConnection,
    private val nowMs: () -> Long,
    private val onState: (AudioHandoffState) -> Unit = {},
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val policy = AudioHandoffPolicy()
    private val mutableState = MutableStateFlow(policy.state)
    val state: StateFlow<AudioHandoffState> = mutableState
    private val routing = Mutex()
    @Volatile private var closed = false
    private val recentEvents = ArrayDeque<String>()

    private data class Protection(val enabled: Boolean, val failed: Boolean, val session: Long?)

    init {
        scope.launch {
            state.map { Protection(it.protectRemote, it.protectionFailed, it.session) }
                .distinctUntilChanged().collectLatest { target ->
                    if (target.failed) {
                        routing.withLock { platform.setProtection(false) }
                    } else {
                        val applied = routing.withLock { platform.setProtection(target.enabled) }
                        if (!applied && target.enabled && state.value.protectRemote && state.value.session == target.session) {
                            accept(HandoffEvent.ProtectionFailed)
                        } else if (applied && target.enabled) {
                            while (true) {
                                delay(750)
                                if (!routing.withLock { platform.refreshProtection() }) {
                                    accept(HandoffEvent.ProtectionFailed)
                                    break
                                }
                            }
                        }
                    }
                }
        }
        scope.launch {
            state.map { it.operation }.distinctUntilChanged().collectLatest { operation ->
                if (operation == null) return@collectLatest
                try {
                    val released = routing.withLock { platform.setProtection(false) }
                    if (!released) {
                        accept(HandoffEvent.Failed(operation.id, HandoffFailure.ROUTE))
                    } else {
                        accept(HandoffEvent.ProtectionReleased(operation.id))
                        if (isCurrent(operation.id) && !connection.takePhone(
                            if (state.value.callActive) HandoffTrigger.CALL else operation.trigger
                        ) { isCurrent(operation.id) }) {
                            accept(HandoffEvent.Failed(operation.id, HandoffFailure.CONNECTION))
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    accept(HandoffEvent.Failed(operation.id, HandoffFailure.CONNECTION))
                }
            }
        }
        scope.launch {
            while (true) {
                delay(250)
                accept(HandoffEvent.Tick)
            }
        }
        scope.launch {
            while (true) {
                val current = state.value
                if (current.session != null && !current.suppressed) {
                    try {
                        connection.querySource()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A query is evidence collection, never a reason to seize the profiles.
                    }
                }
                delay(if (current.protectRemote || current.operation != null) 750 else 5_000)
            }
        }
    }

    @Synchronized
    fun accept(event: HandoffEvent) {
        if (closed) return
        val previous = mutableState.value
        val next = policy.accept(event, nowMs())
        if (previous.phase != next.phase || previous.operation?.id != next.operation?.id ||
            previous.protectRemote != next.protectRemote || previous.protectionFailed != next.protectionFailed) {
            if (recentEvents.size == 64) recentEvents.removeFirst()
            recentEvents.addLast("${nowMs()} ${event.javaClass.simpleName}: ${previous.phase}->${next.phase} " +
                "protect=${next.protectRemote} operation=${next.operation?.id} failure=${next.failure} " +
                "routeFailed=${next.protectionFailed}")
        }
        if (previous.callActive && !next.callActive) scope.launch { connection.clearCallRoute() }
        if (next != previous) {
            // Publish the profile fence before waking effect consumers.
            onState(next)
            mutableState.value = next
        }
    }

    fun isCurrent(operationId: Long): Boolean = !closed && state.value.operation?.id == operationId
    @Synchronized fun diagnostics(): List<String> = recentEvents.toList()

    fun yieldToRemote(session: Long, acknowledge: suspend () -> Unit) {
        if (state.value.callActive || state.value.session != session) return
        accept(HandoffEvent.RemoteReleaseRequested(session))
        scope.launch {
            // The protocol response must not depend on an Android routing operation succeeding.
            // The protection collector already applies the desired routing independently.
            if (state.value.session == session && state.value.operation == null &&
                !state.value.callActive && !closed) acknowledge()
        }
    }

    suspend fun close() {
        closed = true
        scope.cancel()
        routing.withLock { platform.setProtection(false) }
        connection.clearCallRoute()
    }
}
