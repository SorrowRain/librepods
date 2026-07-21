package me.kavishdevar.librepods.data

enum class ManualAudioRouteTransition {
    IDLE,
    SWITCHING_TO_PHONE,
    RELEASING_PHONE,
}

enum class ManualAudioRouteState {
    PHONE,
    REMOTE,
    RELEASED,
    UNKNOWN,
    UNAVAILABLE,
    SWITCHING_TO_PHONE,
    RELEASING_PHONE,
}

enum class ManualAudioRouteAction {
    TAKE_OVER,
    RELEASE,
    NONE,
}

enum class ManualAudioRouteRequestResult {
    TAKEOVER_STARTED,
    RELEASE_STARTED,
    BUSY,
    UNAVAILABLE,
    UNKNOWN_SOURCE,
}

object ManualAudioRoutePolicy {
    fun resolveState(
        aacpConnected: Boolean,
        audioSource: TakeoverAudioSource,
        releasedByPhone: Boolean,
        transition: ManualAudioRouteTransition,
    ): ManualAudioRouteState {
        if (!aacpConnected) return ManualAudioRouteState.UNAVAILABLE

        return when (transition) {
            ManualAudioRouteTransition.SWITCHING_TO_PHONE -> {
                if (audioSource == TakeoverAudioSource.LOCAL) {
                    ManualAudioRouteState.PHONE
                } else {
                    ManualAudioRouteState.SWITCHING_TO_PHONE
                }
            }

            ManualAudioRouteTransition.RELEASING_PHONE -> when (audioSource) {
                TakeoverAudioSource.REMOTE -> ManualAudioRouteState.REMOTE
                TakeoverAudioSource.NONE -> ManualAudioRouteState.RELEASED
                else -> ManualAudioRouteState.RELEASING_PHONE
            }

            ManualAudioRouteTransition.IDLE -> when (audioSource) {
                TakeoverAudioSource.LOCAL -> ManualAudioRouteState.PHONE
                TakeoverAudioSource.REMOTE -> ManualAudioRouteState.REMOTE
                TakeoverAudioSource.NONE,
                TakeoverAudioSource.UNKNOWN -> if (releasedByPhone) {
                    ManualAudioRouteState.RELEASED
                } else {
                    ManualAudioRouteState.UNKNOWN
                }
            }
        }
    }

    fun actionFor(state: ManualAudioRouteState): ManualAudioRouteAction = when (state) {
        ManualAudioRouteState.PHONE -> ManualAudioRouteAction.RELEASE
        ManualAudioRouteState.REMOTE,
        ManualAudioRouteState.RELEASED -> ManualAudioRouteAction.TAKE_OVER
        ManualAudioRouteState.UNKNOWN,
        ManualAudioRouteState.UNAVAILABLE,
        ManualAudioRouteState.SWITCHING_TO_PHONE,
        ManualAudioRouteState.RELEASING_PHONE -> ManualAudioRouteAction.NONE
    }
}
