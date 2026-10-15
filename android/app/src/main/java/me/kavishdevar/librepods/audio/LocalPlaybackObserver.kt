package me.kavishdevar.librepods.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal class LocalPlaybackObserver(
    private val context: Context,
    private val manager: AudioManager,
    scope: CoroutineScope,
    private val monitoring: () -> Boolean,
    private val connected: () -> Boolean,
    private val targetMac: () -> String?,
    private val emit: (HandoffEvent) -> Unit,
) {
    private val tracker = LocalPlaybackIntentTracker()
    private var loggedFailure = false
    @Volatile var capability: String = "not_observed"
        private set
    private val job: Job = scope.launch(Dispatchers.IO) {
        while (true) {
            if (monitoring()) sample() else tracker.reset()
            delay(if (monitoring()) 500 else 2_000)
        }
    }

    fun checkCapability(): String = try {
        context.getSystemService(MediaSessionManager::class.java).getActiveSessions(null)
        val service = AudioManager::class.java.getDeclaredMethod("getService")
            .apply { isAccessible = true }.invoke(null)
        service.javaClass.getMethod("getFocusStack").invoke(service)
        "focus_and_media_sessions_readable"
    } catch (error: Exception) {
        val cause = error.cause ?: error
        "unavailable:${cause.javaClass.simpleName}:${cause.message}"
    }

    private fun sample() {
        try {
            val call = manager.mode == AudioManager.MODE_IN_CALL || manager.mode == AudioManager.MODE_IN_COMMUNICATION
            val attributes = AudioAttributes.Builder().setUsage(
                if (call) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA).build()
            val devices = AudioManager::class.java.getMethod("getDevicesForAttributes", AudioAttributes::class.java)
                .invoke(manager, attributes) as List<*>
            val target = targetMac()
            val onTarget = target != null && devices.filterNotNull().any { device ->
                device.javaClass.getMethod("getType").invoke(device) ==
                    (if (call) AudioDeviceInfo.TYPE_BLUETOOTH_SCO else AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) &&
                    (device.javaClass.getMethod("getAddress").invoke(device) as? String).equals(target, true)
            }
            val tracks = manager.activePlaybackConfigurations.filter {
                it.audioAttributes.usage == AudioAttributes.USAGE_MEDIA &&
                    runCatching { it.javaClass.getMethod("isActive").invoke(it) == true }.getOrDefault(false)
            }
            emit(HandoffEvent.Call(call))
            emit(HandoffEvent.Output(connected(), onTarget, tracks.isNotEmpty(), hfpConnected = call && onTarget))

            val mediaSessions = context.getSystemService(MediaSessionManager::class.java).getActiveSessions(null)
                .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            val playing = mediaSessions.map { it.packageName }.toSet()
            val audioService = AudioManager::class.java.getDeclaredMethod("getService").apply { isAccessible = true }.invoke(null)
            val focus = audioService.javaClass.getMethod("getFocusStack").invoke(audioService) as List<*>
            val top = focus.lastOrNull()
            val focusedPackage = top?.javaClass?.getMethod("getPackageName")?.invoke(top) as? String
            val focusedUid = top?.javaClass?.getMethod("getClientUid")?.invoke(top) as? Int
            val gain = top?.javaClass?.getMethod("getGainRequest")?.invoke(top) as? Int
            val focusAttributes = top?.javaClass?.getMethod("getAttributes")?.invoke(top) as? AudioAttributes
            val activeUids = tracks.mapNotNull { configuration ->
                runCatching { configuration.javaClass.getMethod("getClientUid").invoke(configuration) as Int }.getOrNull()
            }
            val eligible = if (gain == AudioManager.AUDIOFOCUS_GAIN &&
                focusAttributes?.usage == AudioAttributes.USAGE_MEDIA && focusedUid in activeUids &&
                focusedPackage != null) setOf(focusedPackage) else emptySet()
            if (tracker.observe(playing, eligible, SystemClock.elapsedRealtime())) emit(HandoffEvent.TakePhone(HandoffTrigger.MEDIA))
            capability = "focus_session_uid_available"
            loggedFailure = false
        } catch (error: Exception) {
            val cause = error.cause ?: error
            capability = "unavailable:${cause.javaClass.simpleName}:${cause.message}"
            tracker.reset()
            if (!loggedFailure) Log.w("AudioHandoff", "Playback intent observation unavailable; manual handoff remains available", error)
            loggedFailure = true
        }
    }

    fun close() { job.cancel() }
}
