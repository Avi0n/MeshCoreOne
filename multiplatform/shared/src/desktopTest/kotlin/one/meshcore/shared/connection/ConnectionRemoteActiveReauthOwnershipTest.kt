package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionRemoteActiveReauthFixture.Companion.DEVICE
import one.meshcore.shared.connection.ConnectionRemoteActiveReauthFixture.Companion.FOREIGN_ID
import one.meshcore.shared.connection.ConnectionRemoteActiveReauthFixture.Companion.NEW_TIME
import one.meshcore.shared.connection.ConnectionRemoteActiveReauthFixture.Companion.TEST_PASSWORD
import one.meshcore.shared.connection.ConnectionRemoteActiveReauthFixture.Companion.TIMEOUT_MS
import one.meshcore.shared.connection.ConnectionRemoteActiveReauthFixture.Companion.assertWholeRow
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.remotenode.login
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionRemoteActiveReauthOwnershipTest {
    private lateinit var fixture: ConnectionRemoteActiveReauthFixture

    @BeforeTest
    fun setUp() {
        fixture = ConnectionRemoteActiveReauthFixture()
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun anOldMissingPasswordReauthCannotDisconnectReplacementAuthentication() = runBlocking(fixture.dispatcher) {
        withTimeout(TIMEOUT_MS) {
            val dto = fixture.connectAndCapture()
            val manager = fixture.manager
            val oldServices = requireNotNull(manager.services)
            val oldSession = manager.session as FakeMeshSession
            val generation = manager.connectGeneration
            val foreign = fixture.row(FOREIGN_ID)
            val oldCompletion = fixture.scope.async(start = CoroutineStart.LAZY) {
                manager.completeSync(DEVICE, oldServices, generation)
            }
            fixture.credentials.arm(oldCompletion, dto.publicKey)
            oldCompletion.start()
            try {
                fixture.assertOwner(oldCompletion, fixture.credentials.lookupCompleted.await())
                assertFalse(fixture.row(dto.id).isConnected)
                assertEquals(1, oldSession.loginDestinations.size)
                assertTrue(oldServices.remoteNodeService.pendingLogins.isEmpty())
                manager.rebuildConnectedBleAppStack(DEVICE)
                val freshServices = requireNotNull(manager.services)
                val freshSession = manager.session as FakeMeshSession
                val freshLink = requireNotNull(manager.link)
                assertNotSame(oldServices, freshServices)
                assertNotSame(oldSession, freshSession)
                assertTrue(oldSession.stopped)
                assertTrue(oldSession.closed)
                assertEquals(generation, manager.connectGeneration)
                assertEquals(dto.radioID, freshServices.radioId)
                assertTrue(manager.pendingReauthSessionIds.isEmpty())
                fixture.now = NEW_TIME
                assertEquals(
                    RoomPermissionLevel.ADMIN,
                    freshServices.remoteNodeService.login(dto.id, TEST_PASSWORD).permissionLevel,
                )
                val fresh = fixture.row(dto.id)
                assertTrue(fresh.isConnected)
                assertEquals(RoomPermissionLevel.ADMIN.raw, fresh.permissionLevelRawValue)
                assertEquals(NEW_TIME, fresh.lastConnectedDate)
                assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
                assertEquals(1, freshSession.loginDestinations.size)
                assertContentEquals(dto.publicKey, freshSession.loginDestinations.single())
                fixture.credentials.release.complete(Unit)
                val failure = runCatching { oldCompletion.await() }.exceptionOrNull()
                assertTrue(failure == null || failure is CancellationException)
                assertTrue(oldCompletion.isCompleted)
                assertTrue(fixture.scope.coroutineContext.job.isActive)
                assertTrue(manager.wantsConnection)
                assertEquals(generation, manager.connectGeneration)
                assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
                assertSame(freshServices, manager.services)
                assertSame(freshSession, manager.session)
                assertSame(freshLink, manager.link)
                assertFalse(freshSession.closed)
                assertEquals(2, fixture.sessions.size)
                assertEquals(1, oldSession.loginDestinations.size)
                assertEquals(1, freshSession.loginDestinations.size)
                assertTrue(oldServices.remoteNodeService.pendingLogins.isEmpty())
                assertTrue(manager.pendingReauthSessionIds.isEmpty())
                assertNull(manager.reconnectJob)
                fixture.assertNoMessagesOrPending(dto.radioID)
                assertWholeRow(foreign, fixture.row(FOREIGN_ID))
                assertWholeRow(fresh, fixture.row(dto.id))
            } finally {
                fixture.credentials.release.complete(Unit)
                oldCompletion.cancel()
                oldCompletion.join()
            }
        }
    }

    @Test
    fun aLiveMissingPasswordReauthStillDisconnectsItsSession() = runBlocking(fixture.dispatcher) {
        withTimeout(TIMEOUT_MS) {
            val dto = fixture.connectAndCapture()
            val manager = fixture.manager
            val services = requireNotNull(manager.services)
            val session = manager.session as FakeMeshSession
            val link = requireNotNull(manager.link)
            val generation = manager.connectGeneration
            val foreign = fixture.row(FOREIGN_ID)
            fixture.now = NEW_TIME
            assertEquals(
                RoomPermissionLevel.ADMIN,
                services.remoteNodeService.login(dto.id, TEST_PASSWORD).permissionLevel,
            )
            val connected = fixture.row(dto.id)
            assertTrue(connected.isConnected)
            assertEquals(RoomPermissionLevel.ADMIN.raw, connected.permissionLevelRawValue)
            assertEquals(NEW_TIME, connected.lastConnectedDate)
            assertEquals(setOf(dto.id), manager.pendingReauthSessionIds)
            assertEquals(2, session.loginDestinations.size)
            val completion = fixture.scope.async(start = CoroutineStart.LAZY) {
                manager.completeSync(DEVICE, services, generation)
            }
            fixture.credentials.arm(completion, dto.publicKey)
            completion.start()
            try {
                fixture.assertOwner(completion, fixture.credentials.lookupCompleted.await())
                assertWholeRow(connected, fixture.row(dto.id))
                assertEquals(2, session.loginDestinations.size)
                assertTrue(services.remoteNodeService.pendingLogins.isEmpty())
                fixture.credentials.release.complete(Unit)
                completion.await()
                assertTrue(fixture.scope.coroutineContext.job.isActive)
                assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
                assertEquals(generation, manager.connectGeneration)
                assertSame(services, manager.services)
                assertSame(session, manager.session)
                assertSame(link, manager.link)
                assertFalse(session.closed)
                assertEquals(1, fixture.sessions.size)
                assertEquals(2, session.loginDestinations.size)
                assertTrue(manager.pendingReauthSessionIds.isEmpty())
                fixture.assertNoMessagesOrPending(dto.radioID)
                assertWholeRow(foreign, fixture.row(FOREIGN_ID))
                assertWholeRow(connected.copy(isConnected = false), fixture.row(dto.id))
            } finally {
                fixture.credentials.release.complete(Unit)
                completion.cancel()
                completion.join()
            }
        }
    }
}
