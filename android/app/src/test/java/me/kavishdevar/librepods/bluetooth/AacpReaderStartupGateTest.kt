package me.kavishdevar.librepods.bluetooth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AacpReaderStartupGateTest {
    @Test
    fun `generation reset after install rejects reader and requires cleanup`() {
        val gate = AacpReaderStartupGate()
        gate.markSocketInstalled()

        assertFalse(gate.tryStartReader(connectionIsCurrent = false))
        assertFalse(assertNotNull(gate.claimCleanupBeforeReaderStarted()).includesAttSocket)
        assertNull(gate.claimCleanupBeforeReaderStarted())
    }

    @Test
    fun `old generation cleanup retains installed ATT lease when reader is rejected`() {
        val gate = AacpReaderStartupGate()
        gate.markSocketInstalled()
        gate.markAttSocketInstalled()

        assertFalse(gate.tryStartReader(connectionIsCurrent = false))
        assertTrue(assertNotNull(gate.claimCleanupBeforeReaderStarted()).includesAttSocket)
        assertNull(gate.claimCleanupBeforeReaderStarted())
    }

    @Test
    fun `setup failure after install requires exactly one cleanup`() {
        val gate = AacpReaderStartupGate()
        gate.markSocketInstalled()

        assertNotNull(gate.claimCleanupBeforeReaderStarted())
        assertFalse(gate.tryStartReader(connectionIsCurrent = true))
        assertNull(gate.claimCleanupBeforeReaderStarted())
    }

    @Test
    fun `started reader owns the installed socket lifecycle`() {
        val gate = AacpReaderStartupGate()
        gate.markSocketInstalled()
        gate.markAttSocketInstalled()

        assertTrue(gate.tryStartReader(connectionIsCurrent = true))
        assertNull(gate.claimCleanupBeforeReaderStarted())
        assertFalse(gate.tryStartReader(connectionIsCurrent = true))
    }
}
