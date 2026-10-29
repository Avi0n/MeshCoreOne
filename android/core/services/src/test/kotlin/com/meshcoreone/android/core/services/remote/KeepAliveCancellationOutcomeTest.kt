// AndroidOnly: WP-210 observable keep-alive cancellation and explicit-stop outcomes.
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KeepAliveCancellationOutcomeTest {
    @TestFactory
    fun nativeCases(): List<DynamicTest> = listOf(
        remoteCoreNative("domain cancellation stops a real keep-alive tick without disconnecting") {
            val clock = RemoteCoreTestClock()
            val radio = RadioId(UUID.randomUUID())
            val remote = remoteCoreSession(radio, isConnected = true)
            val key = EntityKey(radio, remote.id)
            val store = RemoteCoreFakeStore()
            store.saveRemoteNodeSessionDTO(remote)
            store.saveContact(remoteCoreContact(radio, remote.publicKey, outPathLength = 1u))
            val session = RemoteCoreFakeSession(clock).apply {
                sendKeepAliveResult = Result.failure(RemoteNodeError.Cancelled())
            }
            val service = RemoteNodeService(session, store, RemoteCoreFakePasswordStore(), this, clock)
            val eventFlow = service.events()
            val events = async { eventFlow.toList() }
            try {
                val tick = service.performKeepAliveTick(key, remote.publicKey, 1)
                assertFalse(tick.shouldContinue)
                assertEquals(1, tick.consecutiveFailures)
                assertEquals(1, session.sendKeepAliveInvocations.size)
                assertTrue(checkNotNull(store.session(key)).isConnected)
            } finally {
                service.close()
            }
            assertTrue(events.await().isEmpty())
        },
        remoteCoreNative("parent cancellation propagates from a parked real keep-alive tick") {
            val clock = RemoteCoreTestClock()
            val radio = RadioId(UUID.randomUUID())
            val remote = remoteCoreSession(radio, isConnected = true)
            val key = EntityKey(radio, remote.id)
            val backing = RemoteCoreFakeStore()
            backing.saveRemoteNodeSessionDTO(remote)
            val entered = CompletableDeferred<Unit>()
            val store = object : RemoteNodeStore by backing {
                override suspend fun fetchRemoteNodeSession(key: EntityKey): Nothing {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            val service = RemoteNodeService(
                RemoteCoreFakeSession(clock), store, RemoteCoreFakePasswordStore(), this, clock,
            )
            val caller = async { service.performKeepAliveTick(key, remote.publicKey, 1) }
            try {
                entered.await()
                caller.cancelAndJoin()
                assertTrue(caller.isCancelled)
                assertFailsWith<kotlinx.coroutines.CancellationException> { caller.await() }
                assertTrue(checkNotNull(backing.session(key)).isConnected)
            } finally {
                service.close()
            }
        },
        remoteCoreNative("explicit session stop cancels the parked loop without disconnecting") {
            val clock = RemoteCoreTestClock()
            val radio = RadioId(UUID.randomUUID())
            val remote = remoteCoreSession(radio, isConnected = true)
            val key = EntityKey(radio, remote.id)
            val store = RemoteCoreFakeStore()
            store.saveRemoteNodeSessionDTO(remote)
            store.saveContact(remoteCoreContact(radio, remote.publicKey))
            val service = RemoteNodeService(
                RemoteCoreFakeSession(clock), store, RemoteCoreFakePasswordStore(), this, clock,
            )
            try {
                service.startSessionKeepAlive(key, remote.publicKey)
                remoteCoreAwait("keep-alive never parked") { clock.sleeperCount == 1 }
                val task = checkNotNull(service.keepAliveTasks[key])
                service.stopSessionKeepAlive(key)
                task.join()
                assertTrue(task.isCancelled)
                assertTrue(service.keepAliveTasks.isEmpty())
                assertEquals(0, clock.sleeperCount)
                assertTrue(checkNotNull(store.session(key)).isConnected)
            } finally {
                service.close()
            }
        },
    )
}
