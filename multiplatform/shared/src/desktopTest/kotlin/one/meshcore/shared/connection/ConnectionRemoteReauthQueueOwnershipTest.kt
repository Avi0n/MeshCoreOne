package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionRemoteReauthQueueFixture.Companion.DEVICE
import one.meshcore.shared.connection.ConnectionRemoteReauthQueueFixture.Companion.FOREIGN_ID
import one.meshcore.shared.connection.ConnectionRemoteReauthQueueFixture.Companion.NEW_TIME
import one.meshcore.shared.connection.ConnectionRemoteReauthQueueFixture.Companion.SECOND_ID
import one.meshcore.shared.connection.ConnectionRemoteReauthQueueFixture.Companion.SECOND_KEY
import one.meshcore.shared.connection.ConnectionRemoteReauthQueueFixture.Companion.TEST_PASSWORD
import one.meshcore.shared.connection.ConnectionRemoteReauthQueueFixture.Companion.TIMEOUT_MS
import one.meshcore.shared.connection.ConnectionRemoteReauthQueueFixture.Companion.assertWholeRow
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.remotenode.login
import one.meshcore.shared.transport.LinkDropReason
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

class ConnectionRemoteReauthQueueOwnershipTest {
    private lateinit var fixture: ConnectionRemoteReauthQueueFixture

    @BeforeTest
    fun setUp() {
        fixture = ConnectionRemoteReauthQueueFixture()
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun anObsoleteFailedReauthCannotConsumeANewerDropsSameSessionId() = runBlocking(fixture.dispatcher) {
        withTimeout(TIMEOUT_MS) {
            val dto = fixture.connectAndCapture()
            val manager = fixture.manager
            val oldServices = requireNotNull(manager.services)
            val oldSession = manager.session as FakeMeshSession
            val oldGeneration = manager.connectGeneration
            val foreign = fixture.row(FOREIGN_ID)
            val oldCompletion = fixture.scope.async(start = CoroutineStart.LAZY) {
                manager.completeSync(DEVICE, oldServices, oldGeneration)
            }
            fixture.credentials.arm(oldCompletion, dto.publicKey)
            oldCompletion.start()
            try {
                fixture.assertOwner(oldCompletion, fixture.credentials.lookupCompleted.await())
                assertEquals(1, oldSession.loginDestinations.size)
                assertTrue(oldServices.remoteNodeService.pendingLogins.isEmpty())
                manager.rebuildConnectedBleAppStack(DEVICE)
                val freshServices = requireNotNull(manager.services)
                val freshSession = manager.session as FakeMeshSession
                val freshLink = requireNotNull(manager.link)
                val freshGeneration = manager.connectGeneration
                assertNotSame(oldServices, freshServices)
                assertNotSame(oldSession, freshSession)
                assertTrue(oldSession.stopped)
                assertTrue(oldSession.closed)
                assertEquals(oldGeneration, freshGeneration)
                assertEquals(dto.radioID, freshServices.radioId)
                assertTrue(manager.pendingReauthSessionIds.isEmpty())
                fixture.now = NEW_TIME
                val second = dto.copy(id = SECOND_ID, publicKey = SECOND_KEY, isConnected = false)
                freshServices.remoteNodeService.store.saveSession(second)
                for (id in listOf(dto.id, SECOND_ID)) {
                    assertEquals(
                        RoomPermissionLevel.ADMIN,
                        freshServices.remoteNodeService.login(id, TEST_PASSWORD).permissionLevel,
                    )
                    val row = fixture.row(id)
                    assertTrue(row.isConnected)
                    assertEquals(RoomPermissionLevel.ADMIN.raw, row.permissionLevelRawValue)
                    assertEquals(NEW_TIME, row.lastConnectedDate)
                }
                assertEquals(2, freshSession.loginDestinations.size)
                assertContentEquals(dto.publicKey, freshSession.loginDestinations.first())
                assertContentEquals(SECOND_KEY, freshSession.loginDestinations.last())
                assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
                assertNull(manager.reconnectJob)
                manager.setPairingInProgress(true)
                assertSame(freshServices, manager.services)
                assertSame(freshSession, manager.session)
                assertSame(freshLink, manager.link)
                manager.handleLinkDrop(DEVICE, LinkDropReason.CONNECTION_LOST, freshGeneration, freshSession)
                assertEquals(setOf(dto.id, SECOND_ID), manager.pendingReauthSessionIds)
                val capturedA = fixture.row(dto.id)
                val capturedB = fixture.row(SECOND_ID)
                assertFalse(capturedA.isConnected)
                assertFalse(capturedB.isConnected)
                assertEquals(RoomPermissionLevel.ADMIN.raw, capturedA.permissionLevelRawValue)
                assertEquals(RoomPermissionLevel.ADMIN.raw, capturedB.permissionLevelRawValue)
                assertEquals(NEW_TIME, capturedA.lastConnectedDate)
                assertEquals(NEW_TIME, capturedB.lastConnectedDate)
                assertTrue(manager.connectGeneration > freshGeneration)
                fixture.credentials.release.complete(Unit)
                val failure = runCatching { oldCompletion.await() }.exceptionOrNull()
                assertTrue(failure == null || failure is CancellationException)
                assertTrue(oldCompletion.isCompleted)
                assertTrue(fixture.scope.coroutineContext.job.isActive)
                assertTrue(manager.wantsConnection)
                assertTrue(manager.pairingInProgress)
                assertEquals(ConnectionState.Disconnected, manager.state.value)
                assertNull(manager.services)
                assertNull(manager.session)
                assertNull(manager.link)
                assertNull(manager.reconnectJob)
                assertEquals(2, fixture.sessions.size)
                assertTrue(freshSession.closed)
                assertTrue(oldServices.remoteNodeService.pendingLogins.isEmpty())
                assertEquals(1, oldSession.loginDestinations.size)
                assertEquals(2, freshSession.loginDestinations.size)
                fixture.assertNoMessagesOrPending(dto.radioID)
                assertWholeRow(foreign, fixture.row(FOREIGN_ID))
                assertWholeRow(capturedA, fixture.row(dto.id))
                assertWholeRow(capturedB, fixture.row(SECOND_ID))
                assertEquals(setOf(dto.id, SECOND_ID), manager.pendingReauthSessionIds)
            } finally {
                fixture.credentials.release.complete(Unit)
                oldCompletion.cancel()
                oldCompletion.join()
            }
        }
    }

    @Test
    fun anObsoleteOriginalGenerationCannotConsumePendingIdsWhileTheOldGraphRemainsInstalled() =
        runBlocking(fixture.dispatcher) {
            withTimeout(TIMEOUT_MS) {
                val dto = fixture.connectAndCapture()
                val manager = fixture.manager
                val services = requireNotNull(manager.services)
                val session = fixture.firstSession
                val link = requireNotNull(manager.link)
                val generation = manager.connectGeneration
                val captured = fixture.row(dto.id)
                val foreign = fixture.row(FOREIGN_ID)
                val completion = fixture.scope.async(start = CoroutineStart.LAZY) {
                    manager.completeSync(DEVICE, services, generation)
                }
                manager.setPairingInProgress(true)
                session.holdNextStop = true
                val drop = fixture.scope.async {
                    manager.handleLinkDrop(DEVICE, LinkDropReason.CONNECTION_LOST, generation, session)
                }
                try {
                    fixture.assertOwner(drop, session.stopCompleted.await())
                    assertTrue(manager.connectGeneration > generation)
                    assertTrue(manager.wantsConnection)
                    assertSame(services, manager.services)
                    assertSame(session, manager.session)
                    assertSame(link, manager.link)
                    assertFalse(session.closed)
                    assertEquals(setOf(dto.id), manager.pendingReauthSessionIds)
                    completion.start()
                    completion.await()
                    assertTrue(completion.isCompleted)
                    assertTrue(drop.isActive)
                    assertSame(services, manager.services)
                    assertSame(session, manager.session)
                    assertSame(link, manager.link)
                    assertEquals(1, session.loginDestinations.size)
                    assertTrue(services.remoteNodeService.pendingLogins.isEmpty())
                    assertWholeRow(captured, fixture.row(dto.id))
                    assertWholeRow(foreign, fixture.row(FOREIGN_ID))
                    session.releaseStop.complete(Unit)
                    drop.await()
                    assertTrue(fixture.scope.coroutineContext.job.isActive)
                    assertTrue(manager.wantsConnection)
                    assertTrue(manager.pairingInProgress)
                    assertEquals(ConnectionState.Disconnected, manager.state.value)
                    assertNull(manager.services)
                    assertNull(manager.session)
                    assertNull(manager.link)
                    assertNull(manager.reconnectJob)
                    assertTrue(session.closed)
                    assertEquals(1, fixture.sessions.size)
                    assertEquals(1, session.loginDestinations.size)
                    fixture.assertNoMessagesOrPending(dto.radioID)
                    assertWholeRow(captured, fixture.row(dto.id))
                    assertWholeRow(foreign, fixture.row(FOREIGN_ID))
                    assertEquals(setOf(dto.id), manager.pendingReauthSessionIds)
                } finally {
                    session.releaseStop.complete(Unit)
                    completion.cancel()
                    completion.join()
                    drop.cancel()
                    drop.join()
                }
            }
        }

    @Test
    fun aLiveFailedReauthConsumesOnlyItsCapturedSessions() = runBlocking(fixture.dispatcher) {
        withTimeout(TIMEOUT_MS) {
            val dto = fixture.connectAndCapture()
            val manager = fixture.manager
            val services = requireNotNull(manager.services)
            val session = manager.session as FakeMeshSession
            val link = requireNotNull(manager.link)
            val generation = manager.connectGeneration
            val captured = fixture.row(dto.id)
            val foreign = fixture.row(FOREIGN_ID)
            val completion = fixture.scope.async(start = CoroutineStart.LAZY) {
                manager.completeSync(DEVICE, services, generation)
            }
            fixture.credentials.arm(completion, dto.publicKey)
            completion.start()
            try {
                fixture.assertOwner(completion, fixture.credentials.lookupCompleted.await())
                assertEquals(1, session.loginDestinations.size)
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
                assertEquals(1, session.loginDestinations.size)
                fixture.assertNoMessagesOrPending(dto.radioID)
                assertWholeRow(captured, fixture.row(dto.id))
                assertWholeRow(foreign, fixture.row(FOREIGN_ID))
                assertTrue(manager.pendingReauthSessionIds.isEmpty())
            } finally {
                fixture.credentials.release.complete(Unit)
                completion.cancel()
                completion.join()
            }
        }
    }
}
