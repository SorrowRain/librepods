package me.kavishdevar.librepods.audio

import org.junit.Assert.*
import org.junit.Test

class AudioHandoffPolicyTest {
    private fun remote(policy: AudioHandoffPolicy, time: Long, session: Long = 1) =
        policy.accept(HandoffEvent.Source(session, AudioOwner.REMOTE, AudioSourceKind.MEDIA), time)
    private fun connected() = AudioHandoffPolicy().apply { accept(HandoffEvent.Connection(1), 0) }

    @Test fun `fresh remote evidence gives every gap its full window`() {
        val policy = connected()
        for (time in 100L..10_100L step 1_000L) {
            remote(policy, time)
            assertTrue(policy.accept(HandoffEvent.Tick, time + 900).protectRemote)
        }
        assertTrue(policy.accept(HandoffEvent.Tick, 14_999).protectRemote)
        assertFalse(policy.accept(HandoffEvent.Tick, 15_100).protectRemote)
    }

    @Test fun `initial none report does not open the switch window before the probe ends`() {
        val policy = connected()
        assertTrue(policy.accept(HandoffEvent.Source(1, AudioOwner.NONE, AudioSourceKind.NONE), 50).protectRemote)
        assertTrue(policy.accept(HandoffEvent.Source(1, AudioOwner.UNKNOWN, AudioSourceKind.UNKNOWN), 1_000).protectRemote)
        assertFalse(policy.accept(HandoffEvent.Tick, 1_500).protectRemote)
    }

    @Test fun `failure stays separate from ownership and cannot be revived by observations`() {
        val policy = connected()
        remote(policy, 100)
        policy.accept(HandoffEvent.ProtectionFailed, 200)
        assertTrue(remote(policy, 300).protectionFailed)
        assertTrue(policy.accept(HandoffEvent.Connection(1), 400).protectionFailed)
        assertFalse(policy.accept(HandoffEvent.Connection(2), 500).protectionFailed)
    }

    @Test fun `manual intent releases protection without inventing a local owner`() {
        val policy = connected()
        remote(policy, 100)
        val taking = policy.accept(HandoffEvent.TakePhone(HandoffTrigger.MANUAL), 200)
        assertFalse(taking.protectRemote)
        assertEquals(AudioOwner.REMOTE, taking.owner)
        assertEquals(AudioHandoffPhase.SWITCHING, taking.phase)
        assertFalse(taking.allowsAutomaticRepair)
    }

    @Test fun `handoff requires both route and fresh source confirmation`() {
        val policy = connected()
        policy.accept(HandoffEvent.Source(1, AudioOwner.PHONE, AudioSourceKind.MEDIA), 50)
        policy.accept(HandoffEvent.TakePhone(HandoffTrigger.MEDIA), 100)
        assertNotNull(policy.accept(HandoffEvent.Output(true, true, true), 150).operation)
        policy.accept(HandoffEvent.ProtectionReleased(policy.state.operation!!.id), 175)
        val complete = policy.accept(HandoffEvent.Source(1, AudioOwner.PHONE, AudioSourceKind.MEDIA), 200)
        assertNull(complete.operation)
        assertEquals(AudioHandoffPhase.PHONE, complete.phase)
    }

    @Test fun `manual idle handoff can be ready without claiming audio is playing`() {
        val policy = connected()
        policy.accept(HandoffEvent.TakePhone(HandoffTrigger.MANUAL), 100)
        policy.accept(HandoffEvent.Output(true, true, false), 150)
        policy.accept(HandoffEvent.ProtectionReleased(policy.state.operation!!.id), 175)
        val complete = policy.accept(HandoffEvent.Source(1, AudioOwner.NONE, AudioSourceKind.NONE), 200)
        assertNull(complete.operation)
        assertEquals(AudioHandoffPhase.IDLE, complete.phase)
    }

    @Test fun `automatic handoff cannot complete on an idle source`() {
        val policy = connected()
        policy.accept(HandoffEvent.TakePhone(HandoffTrigger.MEDIA), 100)
        policy.accept(HandoffEvent.Output(true, true, true), 150)
        assertNotNull(policy.accept(HandoffEvent.Source(1, AudioOwner.NONE, AudioSourceKind.NONE), 200).operation)
    }

    @Test fun `stale socket observations and old operation failures are ignored`() {
        val policy = connected()
        policy.accept(HandoffEvent.Connection(2), 100)
        assertEquals(AudioOwner.UNKNOWN, remote(policy, 200, session = 1).owner)
        val first = policy.accept(HandoffEvent.TakePhone(HandoffTrigger.MANUAL), 250).operation!!
        val second = policy.accept(HandoffEvent.TakePhone(HandoffTrigger.MANUAL), 300).operation!!
        val current = policy.accept(HandoffEvent.Failed(first.id, HandoffFailure.CONNECTION), 400)
        assertEquals(second, current.operation)
        assertNull(current.failure)
    }

    @Test fun `repeated media observations cannot extend a pending handoff`() {
        val policy = connected()
        val operation = policy.accept(HandoffEvent.TakePhone(HandoffTrigger.MEDIA), 100).operation
        assertEquals(operation, policy.accept(HandoffEvent.TakePhone(HandoffTrigger.MEDIA), 5_000).operation)
        val timedOut = policy.accept(HandoffEvent.Tick, 12_100)
        assertNull(timedOut.operation)
        assertEquals(HandoffFailure.TIMEOUT, timedOut.failure)
        assertNotNull(policy.accept(HandoffEvent.TakePhone(HandoffTrigger.MANUAL), 12_200).operation)
    }

    @Test fun `disconnect cancels automatic work until explicit manual intent`() {
        val policy = connected()
        remote(policy, 100)
        val disconnected = policy.accept(HandoffEvent.Disconnect, 200)
        assertFalse(disconnected.protectRemote)
        assertFalse(disconnected.allowsAutomaticRepair)
        assertNull(policy.accept(HandoffEvent.TakePhone(HandoffTrigger.MEDIA), 300).operation)
        assertNotNull(policy.accept(HandoffEvent.TakePhone(HandoffTrigger.MANUAL), 400).operation)
    }

    @Test fun `unknown reports and polling do not refresh remote evidence`() {
        val policy = connected()
        remote(policy, 100)
        for (time in 1_000L..5_000L step 1_000L) {
            policy.accept(HandoffEvent.Source(1, AudioOwner.UNKNOWN, AudioSourceKind.UNKNOWN), time)
        }
        assertFalse(policy.accept(HandoffEvent.Tick, 5_100).protectRemote)
    }

    @Test fun `brief control loss has a bounded remote window`() {
        val policy = connected()
        remote(policy, 100)
        assertTrue(policy.accept(HandoffEvent.Connection(null), 200).protectRemote)
        assertTrue(policy.accept(HandoffEvent.Tick, 30_099).protectRemote)
        assertFalse(policy.accept(HandoffEvent.Tick, 30_100).protectRemote)
    }

    @Test fun `passive phone media report alone does not defeat recent remote protection`() {
        val policy = connected()
        remote(policy, 100)
        assertTrue(policy.accept(HandoffEvent.Source(1, AudioOwner.PHONE, AudioSourceKind.MEDIA), 200).protectRemote)
    }

    @Test fun `disabled automation leaves manual recovery available`() {
        val policy = connected()
        policy.accept(HandoffEvent.Enabled(false), 100)
        assertNull(policy.accept(HandoffEvent.TakePhone(HandoffTrigger.MEDIA), 200).operation)
        assertNotNull(policy.accept(HandoffEvent.TakePhone(HandoffTrigger.MANUAL), 300).operation)
    }

    @Test fun `phone call completes against HFP without requiring an A2DP stream`() {
        val policy = connected()
        remote(policy, 100)
        assertNotNull(policy.accept(HandoffEvent.Call(true), 200).operation)
        policy.accept(HandoffEvent.ProtectionReleased(policy.state.operation!!.id), 225)
        policy.accept(HandoffEvent.Output(false, true, false, hfpConnected = true), 250)
        val complete = policy.accept(HandoffEvent.Source(1, AudioOwner.PHONE, AudioSourceKind.CALL), 300)
        assertNull(complete.operation)
        assertFalse(complete.protectRemote)
        assertFalse(complete.allowsMediaResume)
    }

    @Test fun `remote release request does not cancel an active phone call`() {
        val policy = connected()
        val operation = policy.accept(HandoffEvent.Call(true), 100).operation
        assertEquals(operation, policy.accept(HandoffEvent.RemoteReleaseRequested(1), 200).operation)
    }

    @Test fun `remote user request supersedes media handoff without inventing source data`() {
        val policy = connected()
        policy.accept(HandoffEvent.TakePhone(HandoffTrigger.MEDIA), 100)
        val yielded = policy.accept(HandoffEvent.RemoteReleaseRequested(1), 200)
        assertNull(yielded.operation)
        assertTrue(yielded.protectRemote)
        assertEquals(AudioOwner.UNKNOWN, yielded.owner)
    }

    @Test fun `matching source and route cannot report success while native clear is pending`() {
        val policy = connected()
        val operation = policy.accept(HandoffEvent.TakePhone(HandoffTrigger.MEDIA), 100).operation!!
        policy.accept(HandoffEvent.Output(true, true, true), 150)
        assertNotNull(policy.accept(HandoffEvent.Source(1, AudioOwner.PHONE, AudioSourceKind.MEDIA), 200).operation)
        assertNull(policy.accept(HandoffEvent.ProtectionReleased(operation.id), 250).operation)
    }
}
