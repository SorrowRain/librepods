package me.kavishdevar.librepods.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.ui.unit.dp
import me.kavishdevar.librepods.R
import me.kavishdevar.librepods.audio.AudioHandoffPhase
import me.kavishdevar.librepods.services.ServiceManager

@Composable
fun AudioHandoffControl() {
    val context = LocalContext.current
    val service by ServiceManager.serviceFlow.collectAsState()
    val live = service ?: return
    val state by live.audioHandoffState.collectAsState()
    val text = when (state.phase) {
        AudioHandoffPhase.SWITCHING -> R.string.audio_handoff_switching
        AudioHandoffPhase.FAILED -> R.string.audio_handoff_failed
        AudioHandoffPhase.REMOTE -> R.string.audio_handoff_remote
        AudioHandoffPhase.PHONE -> R.string.audio_handoff_phone
        AudioHandoffPhase.DISCONNECTED -> R.string.audio_handoff_disconnected
        AudioHandoffPhase.IDLE -> R.string.audio_handoff_ready
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = {
            context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("LibrePods audio", live.audioHandoffDiagnostics()))
            Toast.makeText(context, R.string.audio_handoff_diagnostics_copied, Toast.LENGTH_SHORT).show()
        }) {
            Text(stringResource(R.string.audio_handoff_copy_diagnostics))
        }
    }
}
