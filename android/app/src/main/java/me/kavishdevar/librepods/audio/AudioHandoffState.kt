package me.kavishdevar.librepods.audio

enum class AudioOwner { UNKNOWN, NONE, PHONE, REMOTE }
enum class AudioSourceKind { UNKNOWN, NONE, MEDIA, CALL }
enum class AudioHandoffPhase { DISCONNECTED, IDLE, REMOTE, PHONE, SWITCHING, FAILED }
enum class HandoffTrigger { MANUAL, MEDIA, CALL }
enum class HandoffFailure { TIMEOUT, CONNECTION, ROUTE, CANCELLED }

data class HandoffOperation(
    val id: Long,
    val trigger: HandoffTrigger,
    val startedAtMs: Long,
    val deadlineMs: Long,
)

data class AudioHandoffState(
    val enabled: Boolean = true,
    val session: Long? = null,
    val owner: AudioOwner = AudioOwner.UNKNOWN,
    val sourceKind: AudioSourceKind = AudioSourceKind.UNKNOWN,
    val sourceObservedAtMs: Long = -1,
    val remoteObservedAtMs: Long = -1,
    val connectedAtMs: Long = -1,
    val a2dpConnected: Boolean = false,
    val hfpConnected: Boolean = false,
    val targetOutput: Boolean = false,
    val mediaPlaying: Boolean = false,
    val callActive: Boolean = false,
    val suppressed: Boolean = false,
    val operation: HandoffOperation? = null,
    val releasedOperationId: Long? = null,
    val verifiedPhoneSession: Long? = null,
    val failure: HandoffFailure? = null,
    val protectionFailed: Boolean = false,
    val protectRemote: Boolean = false,
    val phase: AudioHandoffPhase = AudioHandoffPhase.DISCONNECTED,
) {
    val allowsAutomaticRepair: Boolean
        get() = !suppressed && !protectRemote && operation == null && session != null
    val allowsMediaResume: Boolean
        get() = !suppressed && !protectRemote && operation == null &&
            a2dpConnected && targetOutput && !callActive && failure == null

    /** Eligibility for an explicit diagnostic command, never an automatic takeover rule. */
    internal fun canSynchronizeIdleOwnership(nowMs: Long): Boolean =
        session != null && !suppressed && !protectRemote && operation == null &&
            !callActive && !mediaPlaying && owner == AudioOwner.NONE &&
            sourceObservedAtMs >= connectedAtMs && nowMs - sourceObservedAtMs in 0L..6_000L
}

sealed interface HandoffEvent {
    data class Enabled(val value: Boolean) : HandoffEvent
    data class Connection(val session: Long?) : HandoffEvent
    data class Source(val session: Long, val owner: AudioOwner, val kind: AudioSourceKind) : HandoffEvent
    data class RemoteStreaming(val session: Long, val streaming: Boolean) : HandoffEvent
    data class Output(val connected: Boolean, val target: Boolean, val playing: Boolean,
        val hfpConnected: Boolean = false) : HandoffEvent
    data class RemoteReleaseRequested(val session: Long) : HandoffEvent
    data class Call(val active: Boolean) : HandoffEvent
    data class TakePhone(val trigger: HandoffTrigger) : HandoffEvent
    data object Disconnect : HandoffEvent
    data object Cancel : HandoffEvent
    data class Failed(val operationId: Long, val reason: HandoffFailure) : HandoffEvent
    data class ProtectionReleased(val operationId: Long) : HandoffEvent
    data object ProtectionFailed : HandoffEvent
    data object Tick : HandoffEvent
}

/** Facts and intent are independent. Only fresh observations extend remote protection. */
internal class AudioHandoffPolicy(
    private val remoteEvidenceMs: Long = 5_000,
    private val disconnectedEvidenceMs: Long = 30_000,
    private val initialProbeMs: Long = 1_500,
    private val handoffTimeoutMs: Long = 12_000,
) {
    var state = AudioHandoffState()
        private set
    private var nextOperationId = 0L

    fun accept(event: HandoffEvent, nowMs: Long): AudioHandoffState {
        var next = state
        when (event) {
            is HandoffEvent.Enabled -> next = next.copy(
                enabled = event.value, protectionFailed = false,
                operation = next.operation?.takeIf { it.trigger != HandoffTrigger.MEDIA || event.value },
            )
            is HandoffEvent.Connection -> if (event.session != next.session) {
                next = next.copy(
                    session = event.session, connectedAtMs = if (event.session != null) nowMs else -1,
                    owner = AudioOwner.UNKNOWN, sourceKind = AudioSourceKind.UNKNOWN,
                    sourceObservedAtMs = -1, protectionFailed = false,
                    a2dpConnected = if (event.session == null) false else next.a2dpConnected,
                    targetOutput = if (event.session == null) false else next.targetOutput,
                )
            }
            is HandoffEvent.Source -> if (event.session == next.session) {
                next = next.copy(
                    owner = event.owner, sourceKind = event.kind, sourceObservedAtMs = nowMs,
                    remoteObservedAtMs = if (event.owner == AudioOwner.REMOTE) nowMs else next.remoteObservedAtMs,
                )
                if (event.owner == AudioOwner.PHONE && event.kind == AudioSourceKind.CALL) {
                    next = next.copy(remoteObservedAtMs = -1)
                }
            }
            is HandoffEvent.RemoteStreaming -> if (event.session == next.session && event.streaming) {
                next = next.copy(remoteObservedAtMs = nowMs)
            }
            is HandoffEvent.Output -> next = next.copy(
                a2dpConnected = event.connected, targetOutput = event.target, mediaPlaying = event.playing,
                hfpConnected = event.hfpConnected,
            )
            is HandoffEvent.RemoteReleaseRequested -> if (event.session == next.session && !next.callActive) {
                next = next.copy(operation = null, failure = null, remoteObservedAtMs = nowMs)
            }
            is HandoffEvent.Call -> {
                val started = event.active && !next.callActive
                next = next.copy(callActive = event.active)
                if (started && !next.suppressed) next = begin(next, HandoffTrigger.CALL, nowMs)
            }
            is HandoffEvent.TakePhone -> {
                if (event.trigger != HandoffTrigger.MEDIA || next.enabled && !next.suppressed) {
                    next = begin(next, event.trigger, nowMs)
                }
            }
            HandoffEvent.Disconnect -> next = next.copy(
                suppressed = true, operation = null, failure = null, remoteObservedAtMs = -1,
            )
            HandoffEvent.Cancel -> next = next.copy(operation = null, failure = HandoffFailure.CANCELLED)
            is HandoffEvent.Failed -> if (event.operationId == next.operation?.id) {
                next = next.copy(operation = null, failure = event.reason)
            }
            is HandoffEvent.ProtectionReleased -> if (event.operationId == next.operation?.id) {
                next = next.copy(releasedOperationId = event.operationId)
            }
            HandoffEvent.ProtectionFailed -> next = next.copy(protectionFailed = true)
            HandoffEvent.Tick -> Unit
        }
        next.operation?.let { operation ->
            val sourceConfirmed = next.sourceObservedAtMs >= operation.startedAtMs &&
                (next.owner == AudioOwner.PHONE ||
                    operation.trigger == HandoffTrigger.MANUAL && !next.mediaPlaying && next.owner == AudioOwner.NONE)
            val profileReady = if (next.callActive) next.hfpConnected else next.a2dpConnected
            if (next.releasedOperationId == operation.id && next.session != null &&
                profileReady && next.targetOutput && sourceConfirmed) {
                next = next.copy(operation = null, failure = null, remoteObservedAtMs = -1,
                    verifiedPhoneSession = next.session)
            } else if (nowMs >= operation.deadlineMs) {
                next = next.copy(operation = null, failure = HandoffFailure.TIMEOUT)
            }
        }
        val remoteWindow = if (next.session == null) disconnectedEvidenceMs else remoteEvidenceMs
        val recentRemote = next.remoteObservedAtMs >= 0 && nowMs - next.remoteObservedAtMs < remoteWindow
        val probing = next.session != null && next.verifiedPhoneSession != next.session &&
            (next.owner == AudioOwner.UNKNOWN || next.owner == AudioOwner.NONE) &&
            nowMs - next.connectedAtMs < initialProbeMs
        val protect = next.enabled && !next.suppressed && !next.callActive &&
            next.operation == null && (recentRemote || probing)
        val phase = when {
            next.operation != null -> AudioHandoffPhase.SWITCHING
            next.failure != null -> AudioHandoffPhase.FAILED
            next.session == null -> AudioHandoffPhase.DISCONNECTED
            protect -> AudioHandoffPhase.REMOTE
            next.targetOutput && next.owner == AudioOwner.PHONE -> AudioHandoffPhase.PHONE
            else -> AudioHandoffPhase.IDLE
        }
        state = next.copy(protectRemote = protect, phase = phase)
        return state
    }

    private fun begin(state: AudioHandoffState, trigger: HandoffTrigger, now: Long): AudioHandoffState {
        if (state.operation != null && trigger == HandoffTrigger.MEDIA) return state
        return state.copy(
            suppressed = false, failure = null, protectionFailed = false,
            releasedOperationId = null,
            operation = HandoffOperation(++nextOperationId, trigger, now, now + handoffTimeoutMs),
        )
    }
}
