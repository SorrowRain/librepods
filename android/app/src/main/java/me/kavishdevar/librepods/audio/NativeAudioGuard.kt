package me.kavishdevar.librepods.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import kotlinx.coroutines.delay
import me.kavishdevar.librepods.data.XposedRemotePrefProvider

/** A small acknowledgement for the existing lease transport; no second ownership policy. */
internal class NativeAudioGuard(private val context: Context) {
    private val preferences = XposedRemotePrefProvider.create()
    @Volatile private var closed = false
    @Volatile private var acknowledgedRevision = -1L
    @Volatile private var acknowledgedExpiry = 0L
    @Volatile private var hookInstalled = false
    @Volatile private var lastAcknowledgedAt = 0L
    private var possibleExpiry = SystemClock.elapsedRealtime() + 10_000L
    private val callbacks = HandlerThread("LibrePods-native-guard").apply { start() }
    val available: Boolean get() = hookInstalled && SystemClock.elapsedRealtime() - lastAcknowledgedAt < 5_000L
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) {
            val revision = intent.getLongExtra("revision", -1)
            if (revision < acknowledgedRevision || revision < 0 || revision % 2L != 0L) return
            hookInstalled = intent.getBooleanExtra("hookInstalled", false)
            acknowledgedExpiry = intent.getLongExtra("expiry", 0)
            acknowledgedRevision = revision
            lastAcknowledgedAt = SystemClock.elapsedRealtime()
        }
    }

    init {
        context.registerReceiver(receiver, IntentFilter("me.kavishdevar.librepods.NATIVE_GUARD_APPLIED"),
            "android.permission.BLUETOOTH_PRIVILEGED", Handler(callbacks.looper), Context.RECEIVER_EXPORTED)
    }

    suspend fun clear(): Boolean {
        val revision = preferences.setA2dpStartGuardLease(0)
        while (SystemClock.elapsedRealtime() < possibleExpiry) {
            if (revision != null && acknowledgedRevision == revision && acknowledgedExpiry == 0L) {
                possibleExpiry = 0L
                return true
            }
            delay(25)
        }
        return true // The native hard expiry also proves an old block is no longer active.
    }

    fun refresh() {
        if (closed) return
        if (!available) {
            preferences.setA2dpStartGuardLease(0) // Also asks the Bluetooth process for capability.
            return
        }
        possibleExpiry = SystemClock.elapsedRealtime() + 2_500L
        if (preferences.setA2dpStartGuardLease(2_000L) == null) hookInstalled = false
    }

    fun close() {
        closed = true
        preferences.setA2dpStartGuardLease(0)
        context.unregisterReceiver(receiver)
        callbacks.quitSafely()
    }
}
