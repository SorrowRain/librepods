package me.kavishdevar.librepods.data

import java.io.File

interface XposedRemotePref {
    fun isAvailable(): Boolean

    fun getBoolean(key: String, def: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)

    /**
     * Publishes a short A2DP START guard lease to the Bluetooth process.
     * Passing zero clears the lease immediately.
     */
    fun setA2dpStartGuardLease(remainingMs: Long)
}

object A2dpStartGuardLease {
    const val ENABLED_KEY = "a2dp_start_guard_lease_enabled"
    const val EXPIRY_ELAPSED_REALTIME_MS_KEY =
        "a2dp_start_guard_lease_expiry_elapsed_realtime_ms"
    const val BOOT_ID_KEY = "a2dp_start_guard_lease_boot_id"
    const val REVISION_KEY = "a2dp_start_guard_lease_revision"
    const val MAX_REMAINING_MS = 10_000L

    fun clampRequestedRemainingMs(remainingMs: Long): Long =
        remainingMs.coerceIn(0L, MAX_REMAINING_MS)

    fun isStableRevision(revisionBefore: Long, revisionAfter: Long): Boolean =
        revisionBefore == revisionAfter && revisionAfter % 2L == 0L

    fun currentBootId(): String? = runCatching {
        File("/proc/sys/kernel/random/boot_id").readText().trim()
            .takeIf(String::isNotEmpty)
    }.getOrNull()

    fun remainingMs(
        enabled: Boolean,
        expiryElapsedRealtimeMs: Long,
        leaseBootId: String?,
        currentBootId: String?,
        nowElapsedRealtimeMs: Long,
    ): Long {
        if (!enabled || leaseBootId.isNullOrEmpty() || leaseBootId != currentBootId ||
            expiryElapsedRealtimeMs <= nowElapsedRealtimeMs
        ) return 0L

        val remainingMs = expiryElapsedRealtimeMs - nowElapsedRealtimeMs
        return remainingMs.takeIf { it <= MAX_REMAINING_MS } ?: 0L
    }
}
