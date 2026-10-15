package me.kavishdevar.librepods.receivers

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.kavishdevar.librepods.services.ServiceManager

/** Notification actions use the same manual commands as the Quick Settings tile. */
class AudioHandoffActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val service = ServiceManager.getService() ?: return
        val switching = service.audioHandoffState.value.operation != null
        when (intent.action) {
            ACTION_TAKE_PHONE -> if (!switching) service.takeAudioOnPhone()
            ACTION_CANCEL -> if (switching) service.cancelAudioHandoff()
        }
    }

    companion object {
        private const val ACTION_TAKE_PHONE = "me.kavishdevar.librepods.action.TAKE_AUDIO_ON_PHONE"
        private const val ACTION_CANCEL = "me.kavishdevar.librepods.action.CANCEL_AUDIO_HANDOFF"

        fun pendingIntent(context: Context, switching: Boolean): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, AudioHandoffActionReceiver::class.java).apply {
                    action = if (switching) ACTION_CANCEL else ACTION_TAKE_PHONE
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
    }
}
