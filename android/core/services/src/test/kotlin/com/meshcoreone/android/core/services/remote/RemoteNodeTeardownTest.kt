// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/RemoteNodeTeardownTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import java.lang.ref.WeakReference
import java.util.UUID
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.yield
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Teardown. As in Swift, a real session over a transport that accepts writes but never answers keeps
 * `sendLogin` parked: MockTransport is connected and silent, and the session waits on the virtual clock.
 */
class RemoteNodeTeardownTest {
    private class Fixture(scope: CoroutineScope) {
        val clock = RemoteCoreTestClock()
        val transport = MockTransport()
        val session = MeshCoreSession(
            transport,
            SessionConfiguration(defaultTimeout = 30.0, clientIdentifier = "MCTst"),
            clock = clock,
            coroutineContext = checkNotNull(scope.coroutineContext[ContinuationInterceptor]),
        )
        val store = RemoteCoreFakeStore()
        val radioId = RadioId(UUID.randomUUID())
        val serviceScope = CoroutineScope(SupervisorJob() + checkNotNull(scope.coroutineContext[ContinuationInterceptor]))

        fun service() = RemoteNodeService(
            session.asRemoteNodeSessionPort(), store, RemoteCoreFakePasswordStore(), serviceScope, clock,
        )
    }

    private fun case(name: String, body: suspend CoroutineScope.(Fixture) -> Unit): DynamicTest =
        remoteCoreOriginal("RemoteNodeTeardownTests", name) {
            val fixture = Fixture(this)
            fixture.transport.connect()
            try {
                body(fixture)
            } finally {
                fixture.serviceScope.cancel()
                fixture.session.stop()
            }
        }

    @TestFactory
    fun sourceCases(): List<DynamicTest> = listOf(
        case("stopAllKeepAlives resumes a parked login continuation") { fixture ->
            val remoteSession = remoteCoreSession(fixture.radioId)
            fixture.store.saveRemoteNodeSessionDTO(remoteSession)
            val service = fixture.service()
            val login = async { runCatching { service.login(EntityKey(fixture.radioId, remoteSession.id), "test-password") } }

            // Wait until the login is parked before tearing down.
            remoteCoreAwait("login continuation was never parked") { service.pendingLoginCount == 1 }
            remoteCoreSettle()
            // The login frame reached the silent radio and the caller is still parked.
            assertEquals(1, fixture.transport.sentData.size)
            assertFalse(login.isCompleted)
            service.stopAllKeepAlives()

            assertIs<RemoteNodeError.Cancelled>(login.await().exceptionOrNull())
            assertEquals(0, service.pendingLoginCount)
        },
        case("keep-alive loop does not retain the service after the last strong reference is dropped") { fixture ->
            val remoteSession = remoteCoreSession(fixture.radioId)
            fixture.store.saveRemoteNodeSessionDTO(remoteSession)
            // A flood-routed contact makes each tick a skip, so the loop parks in its inter-tick sleep.
            fixture.store.saveContact(remoteCoreContact(fixture.radioId, remoteSession.publicKey, outPathLength = 0xFFu))

            val weakService = startKeepAliveAndForget(fixture, remoteSession.id, remoteSession.publicKey)
            remoteCoreAwait("keep-alive never parked in its interval sleep") { fixture.clock.sleeperCount == 1 }
            awaitCollected("keep-alive task must not keep the service alive", weakService)
        },
    )

    @TestFactory
    fun nativeCases(): List<DynamicTest> = listOf(
        remoteCoreNative("close cancels the parked keep-alive and finishes event subscribers") {
            val fixture = Fixture(this)
            fixture.transport.connect()
            val service = fixture.service()
            try {
                val remoteSession = remoteCoreSession(fixture.radioId)
                fixture.store.saveRemoteNodeSessionDTO(remoteSession)
                fixture.store.saveContact(remoteCoreContact(fixture.radioId, remoteSession.publicKey, outPathLength = 0xFFu))
                val events = service.events()
                val collected = async { events.toList() }
                val key = EntityKey(fixture.radioId, remoteSession.id)
                service.startSessionKeepAlive(key, remoteSession.publicKey)
                remoteCoreAwait("keep-alive never parked") { fixture.clock.sleeperCount == 1 }
                val keepAlive = checkNotNull(service.keepAliveTasks[key])

                service.close()
                keepAlive.join()

                assertTrue(keepAlive.isCancelled)
                assertEquals(0, fixture.clock.sleeperCount)
                assertTrue(service.keepAliveTasks.isEmpty())
                assertTrue(collected.await().isEmpty())
            } finally {
                service.close()
                fixture.serviceScope.cancel()
                fixture.session.stop()
            }
        },
    )

    /** Creates the service in its own frame so no test local keeps it reachable. */
    private fun startKeepAliveAndForget(
        fixture: Fixture, id: UUID, publicKey: com.meshcoreone.android.core.protocol.bytes.Bytes,
    ): WeakReference<RemoteNodeService> {
        val service = fixture.service()
        service.startSessionKeepAlive(EntityKey(fixture.radioId, id), publicKey)
        return WeakReference(service)
    }

    /** Swift `waitUntil { weakService == nil }`: request collection between scheduler turns. */
    private suspend fun awaitCollected(description: String, reference: WeakReference<*>) {
        repeat(200) {
            if (reference.get() == null) return
            System.gc()
            yield()
        }
        if (reference.get() != null) fail(description)
    }
}
