package me.kavishdevar.librepods.services

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.audio.AudioHandoffPhase

/** A separate handoff tile leaves the existing ANC tile's behavior unchanged. */
class AudioHandoffTileService : TileService() {
    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var listeningJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        listeningJob?.cancel()
        listeningJob = tileScope.launch {
            ServiceManager.serviceFlow.collectLatest { service ->
                if (service == null) {
                    updateTile(null)
                } else {
                    service.audioHandoffState.map { it.phase }.distinctUntilChanged().collect {
                        updateTile(it)
                    }
                }
            }
        }
    }

    override fun onStopListening() {
        listeningJob?.cancel()
        listeningJob = null
        super.onStopListening()
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        val service = ServiceManager.getService() ?: return
        if (service.audioHandoffState.value.operation != null) {
            service.cancelAudioHandoff()
        } else {
            service.takeAudioOnPhone()
        }
    }

    private fun updateTile(phase: AudioHandoffPhase?) {
        val tile = qsTile ?: return
        tile.state = when (phase) {
            null -> Tile.STATE_UNAVAILABLE
            AudioHandoffPhase.PHONE -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(
            if (phase == AudioHandoffPhase.SWITCHING) R.string.audio_handoff_cancel
            else R.string.audio_handoff_take_phone
        )
        tile.subtitle = getString(when (phase) {
            null -> R.string.audio_handoff_service_unavailable
            AudioHandoffPhase.SWITCHING -> R.string.audio_handoff_tile_switching
            AudioHandoffPhase.FAILED -> R.string.audio_handoff_tile_retry
            AudioHandoffPhase.REMOTE -> R.string.audio_handoff_tile_remote
            AudioHandoffPhase.PHONE -> R.string.audio_handoff_phone
            AudioHandoffPhase.DISCONNECTED -> R.string.audio_handoff_disconnected
            AudioHandoffPhase.IDLE -> R.string.audio_handoff_ready
        })
        tile.contentDescription = "${tile.label}, ${tile.subtitle}"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_audio_handoff)
        tile.updateTile()
    }
}
