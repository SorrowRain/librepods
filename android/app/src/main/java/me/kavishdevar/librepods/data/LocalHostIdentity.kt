package me.kavishdevar.librepods.data

import java.util.Locale

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
}
