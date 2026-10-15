package me.kavishdevar.librepods.audio

import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class AudioHandoffCoordinatorTest {
    private suspend fun eventually(check: () -> Boolean) {
        withTimeout(2_000) { while (!check()) delay(5) }
    }

    @Test fun `connection begins only after protection release actually finishes`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val released = CompletableDeferred<Unit>()
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val platform = object : HandoffPlatform {
            override suspend fun setProtection(enabled: Boolean): Boolean {
                if (!enabled) { released.await(); calls.add("released") }
                return true
            }
            override suspend fun refreshProtection() = true
        }
        val connection = object : HandoffConnection {
            override suspend fun takePhone(trigger: HandoffTrigger, isCurrent: () -> Boolean): Boolean { calls.add("connect"); return true }
            override suspend fun querySource() = Unit
        }
        val coordinator = AudioHandoffCoordinator(scope, platform, connection, { 100L })
        try {
            coordinator.accept(HandoffEvent.TakePhone(HandoffTrigger.MANUAL))
            delay(50)
            assertFalse(calls.contains("connect"))
            released.complete(Unit)
            eventually { calls.contains("connect") }
            assertTrue(calls.indexOf("released") < calls.indexOf("connect"))
        } finally { released.complete(Unit); coordinator.close(); scope.cancel() }
    }

    @Test fun `cancelled operation cannot connect after delayed cleanup`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val released = CompletableDeferred<Unit>()
        var connects = 0
        val platform = object : HandoffPlatform {
            override suspend fun setProtection(enabled: Boolean): Boolean { released.await(); return true }
            override suspend fun refreshProtection() = true
        }
        val connection = object : HandoffConnection {
            override suspend fun takePhone(trigger: HandoffTrigger, isCurrent: () -> Boolean): Boolean { connects++; return true }
            override suspend fun querySource() = Unit
        }
        val coordinator = AudioHandoffCoordinator(scope, platform, connection, { 100L })
        try {
            coordinator.accept(HandoffEvent.TakePhone(HandoffTrigger.MANUAL))
            delay(30)
            coordinator.accept(HandoffEvent.Cancel)
            released.complete(Unit)
            delay(100)
            assertEquals(0, connects)
        } finally { released.complete(Unit); coordinator.close(); scope.cancel() }
    }

    @Test fun `terminal routing failure is not retried on every source report`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = Collections.synchronizedList(mutableListOf<Boolean>())
        val platform = object : HandoffPlatform {
            override suspend fun setProtection(enabled: Boolean): Boolean { calls.add(enabled); return !enabled }
            override suspend fun refreshProtection() = false
        }
        val connection = object : HandoffConnection {
            override suspend fun takePhone(trigger: HandoffTrigger, isCurrent: () -> Boolean) = true
            override suspend fun querySource() = Unit
        }
        val coordinator = AudioHandoffCoordinator(scope, platform, connection, { 100L })
        try {
            coordinator.accept(HandoffEvent.Connection(1))
            eventually { coordinator.state.value.protectionFailed }
            repeat(5) { coordinator.accept(HandoffEvent.Source(1, AudioOwner.REMOTE, AudioSourceKind.MEDIA)) }
            delay(80)
            assertEquals(1, calls.count { it })
        } finally { coordinator.close(); scope.cancel() }
    }

    @Test fun `remote release is acknowledged even when speaker routing fails`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val acknowledged = CompletableDeferred<Unit>()
        val platform = object : HandoffPlatform {
            override suspend fun setProtection(enabled: Boolean) = !enabled
            override suspend fun refreshProtection() = false
        }
        val connection = object : HandoffConnection {
            override suspend fun takePhone(trigger: HandoffTrigger, isCurrent: () -> Boolean) = true
            override suspend fun querySource() = Unit
        }
        val coordinator = AudioHandoffCoordinator(scope, platform, connection, { 100L })
        try {
            coordinator.accept(HandoffEvent.Connection(1))
            eventually { coordinator.state.value.protectionFailed }
            coordinator.yieldToRemote(1) { acknowledged.complete(Unit) }
            withTimeout(500) { acknowledged.await() }
        } finally { coordinator.close(); scope.cancel() }
    }

    @Test fun `remote protocol response does not wait for the routing lock`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val acknowledged = CompletableDeferred<Unit>()
        val platform = object : HandoffPlatform {
            override suspend fun setProtection(enabled: Boolean): Boolean {
                entered.complete(Unit)
                gate.await()
                return true
            }
            override suspend fun refreshProtection() = true
        }
        val connection = object : HandoffConnection {
            override suspend fun takePhone(trigger: HandoffTrigger, isCurrent: () -> Boolean) = true
            override suspend fun querySource() = Unit
        }
        val coordinator = AudioHandoffCoordinator(scope, platform, connection, { 100L })
        try {
            coordinator.accept(HandoffEvent.Connection(1))
            withTimeout(500) { entered.await() }
            coordinator.yieldToRemote(1) { acknowledged.complete(Unit) }
            withTimeout(500) { acknowledged.await() }
        } finally { gate.complete(Unit); coordinator.close(); scope.cancel() }
    }
}
