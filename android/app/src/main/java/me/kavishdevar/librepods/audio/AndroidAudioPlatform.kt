package me.kavishdevar.librepods.audio

import android.content.Context
import android.media.AudioManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.kavishdevar.librepods.utils.PhoneSoundRoutingController

internal class AndroidAudioPlatform(context: Context, manager: AudioManager) : HandoffPlatform {
    private val native = NativeAudioGuard(context)
    @Volatile private var closed = false
    val nativeAvailable: Boolean get() = native.available
    init { PhoneSoundRoutingController.initialize(context, manager) }

    override suspend fun setProtection(enabled: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (closed && enabled) return@withContext false
        if (!enabled) {
            if (!native.clear()) return@withContext false
            PhoneSoundRoutingController.setGuardEnabled(false, "handoff coordinator")
            return@withContext !PhoneSoundRoutingController.status.active
        }
        PhoneSoundRoutingController.setGuardEnabled(true, "handoff coordinator")
        if (closed) {
            native.clear()
            PhoneSoundRoutingController.setGuardEnabled(false, "service stopped during routing")
            return@withContext false
        }
        val ready = PhoneSoundRoutingController.status.active &&
            PhoneSoundRoutingController.verifyPreferredSpeakerRoute()
        if (ready) native.refresh() else {
            native.clear()
            PhoneSoundRoutingController.setGuardEnabled(false, "route verification failed")
        }
        ready
    }

    override suspend fun refreshProtection(): Boolean = withContext(Dispatchers.IO) {
        val ready = PhoneSoundRoutingController.verifyPreferredSpeakerRoute()
        if (ready) native.refresh() else native.clear()
        ready
    }

    fun close() {
        closed = true
        native.close()
        PhoneSoundRoutingController.shutdown()
    }
}
