package me.kavishdevar.librepods.bluetooth

internal data class AacpReaderStartupCleanup(
    val includesAttSocket: Boolean,
)

/** Tracks cleanup ownership until installed control sockets have a live AACP reader. */
internal class AacpReaderStartupGate {
    private var socketInstalled = false
    private var attSocketInstalled = false
    private var readerStarted = false
    private var cleanupClaimed = false

    @Synchronized
    fun markSocketInstalled() {
        check(!socketInstalled)
        socketInstalled = true
    }

    @Synchronized
    fun markAttSocketInstalled() {
        check(socketInstalled && !readerStarted && !cleanupClaimed)
        check(!attSocketInstalled)
        attSocketInstalled = true
    }

    @Synchronized
    fun tryStartReader(connectionIsCurrent: Boolean): Boolean {
        if (!socketInstalled || readerStarted || cleanupClaimed || !connectionIsCurrent) {
            return false
        }
        readerStarted = true
        return true
    }

    @Synchronized
    fun claimCleanupBeforeReaderStarted(): AacpReaderStartupCleanup? {
        if (!socketInstalled || readerStarted || cleanupClaimed) return null
        cleanupClaimed = true
        return AacpReaderStartupCleanup(includesAttSocket = attSocketInstalled)
    }
}
