package me.kavishdevar.librepods.data

import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import me.kavishdevar.librepods.utils.XposedServiceHolder

class XposedRemotePrefImpl: XposedRemotePref {
    override fun isAvailable(): Boolean {
        return XposedServiceHolder.service != null
    }

    override fun getBoolean(key: String, def: Boolean): Boolean {
        val s = XposedServiceHolder.service ?: return def
        return s.getRemotePreferences("me.kavishdevar.librepods").getBoolean(key, def)
    }

    override fun putBoolean(key: String, value: Boolean) {
        val s = XposedServiceHolder.service ?: return
        s.getRemotePreferences("me.kavishdevar.librepods")
            .edit { putBoolean(key, value) }
    }

    override fun setA2dpStartGuardLease(remainingMs: Long): Long? {
        val clampedRemainingMs = A2dpStartGuardLease.clampRequestedRemainingMs(remainingMs)
        val bootId = A2dpStartGuardLease.currentBootId()
        val enabled = clampedRemainingMs > 0L && bootId != null
        val expiryElapsedRealtimeMs = if (enabled) {
            SystemClock.elapsedRealtime() + clampedRemainingMs
        } else {
            0L
        }

        return synchronized(a2dpStartGuardLeaseWriteLock) {
            val service = XposedServiceHolder.service ?: return null
            val preferences = service.getRemotePreferences(REMOTE_PREFERENCES_NAME)
            val currentRevision = preferences.getLong(A2dpStartGuardLease.REVISION_KEY, 0L)
            val writingRevision = if (currentRevision % 2L == 0L) {
                currentRevision + 1L
            } else {
                currentRevision + 2L
            }
            val markedWriting = preferences.edit()
                .putLong(A2dpStartGuardLease.REVISION_KEY, writingRevision)
                .commit()
            if (!markedWriting) {
                Log.e(TAG, "Failed to mark A2DP StartStream guard lease update")
                return null
            }
            val committed = preferences
                .edit()
                .putBoolean(A2dpStartGuardLease.ENABLED_KEY, enabled)
                .putLong(
                    A2dpStartGuardLease.EXPIRY_ELAPSED_REALTIME_MS_KEY,
                    expiryElapsedRealtimeMs
                )
                .putString(A2dpStartGuardLease.BOOT_ID_KEY, bootId.orEmpty())
                .putLong(A2dpStartGuardLease.REVISION_KEY, writingRevision + 1L)
                .commit()
            if (!committed) {
                Log.e(TAG, "Failed to commit A2DP StartStream guard lease")
            }
            if (committed) writingRevision + 1L else null
        }
    }

    private companion object {
        const val REMOTE_PREFERENCES_NAME = "me.kavishdevar.librepods"
        const val TAG = "LibrePodsHook"
        val a2dpStartGuardLeaseWriteLock = Any()
    }
}
