package me.kavishdevar.librepods.audio

import me.kavishdevar.librepods.data.LocalHostIdentity
import org.junit.Assert.*
import org.junit.Test

class RemoteSenderTest {
    @Test fun `own streaming echoes cannot arm remote protection`() {
        assertFalse(LocalHostIdentity.isRemote("aa:bb:cc:dd:ee:01", "AA:BB:CC:DD:EE:01"))
        assertFalse(LocalHostIdentity.isRemote("AA:BB:CC:DD:EE:02", null))
        assertFalse(LocalHostIdentity.isRemote("00:00:00:00:00:00", "AA:BB:CC:DD:EE:01"))
        assertTrue(LocalHostIdentity.isRemote("AA:BB:CC:DD:EE:02", "AA:BB:CC:DD:EE:01"))
    }
}
