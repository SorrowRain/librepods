package me.kavishdevar.librepods.data

import java.util.Locale

enum class TakeoverAudioSource {
    LOCAL,
    REMOTE,
    NONE,
    UNKNOWN
}

enum class TakeoverRouteReadiness {
    READY,
    WAITING_FOR_OWNERSHIP,
    WAITING_FOR_LOCAL_SOURCE,
    WAITING_FOR_A2DP
}

data class TakeoverRouteSignals(
    val ownsConnection: Boolean,
    val audioSource: TakeoverAudioSource,
    val a2dpConnected: Boolean
)

object TakeoverRouteGate {
    fun evaluate(signals: TakeoverRouteSignals): TakeoverRouteReadiness = when {
        !signals.ownsConnection -> TakeoverRouteReadiness.WAITING_FOR_OWNERSHIP
        signals.audioSource != TakeoverAudioSource.LOCAL ->
            TakeoverRouteReadiness.WAITING_FOR_LOCAL_SOURCE
        !signals.a2dpConnected -> TakeoverRouteReadiness.WAITING_FOR_A2DP
        else -> TakeoverRouteReadiness.READY
    }
}

object LocalHostIdentity {
    private val bluetoothMacPattern = Regex("^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$")
    private val unusableAddresses = setOf(
        "00:00:00:00:00:00",
        "02:00:00:00:00:00",
    )

    fun normalize(candidate: String?): String? {
        val normalized = candidate
            ?.trim()
            ?.uppercase(Locale.ROOT)
            ?.takeUnless { it.equals("null", ignoreCase = true) }
            ?: return null
        return normalized.takeIf {
            bluetoothMacPattern.matches(it) && it !in unusableAddresses
        }
    }

    fun classifyKnownSource(
        localMac: String?,
        sourceMac: String?,
    ): TakeoverAudioSource {
        val normalizedLocal = normalize(localMac) ?: return TakeoverAudioSource.UNKNOWN
        val normalizedSource = normalize(sourceMac) ?: return TakeoverAudioSource.UNKNOWN
        return if (normalizedSource == normalizedLocal) {
            TakeoverAudioSource.LOCAL
        } else {
            TakeoverAudioSource.REMOTE
        }
    }

    fun inferFromActiveLocalMedia(
        currentLocalMac: String?,
        sourceMac: String?,
        ownsConnection: Boolean,
        a2dpPlaying: Boolean,
        freshLocalA2dpStart: Boolean,
        eligibleLocalMediaPlaying: Boolean,
    ): String? {
        normalize(currentLocalMac)?.let { return it }
        if ((!ownsConnection && !freshLocalA2dpStart) ||
            !a2dpPlaying ||
            !eligibleLocalMediaPlaying
        ) {
            return null
        }
        return normalize(sourceMac)
    }
}
