package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionActiveLogoutFixture.Companion.DEVICE
import one.meshcore.shared.connection.ConnectionActiveLogoutFixture.Companion.FOREIGN_ID
import one.meshcore.shared.connection.ConnectionActiveLogoutFixture.Companion.LOGOUT_NAME
import one.meshcore.shared.connection.ConnectionActiveLogoutFixture.Companion.NEW_TIME
import one.meshcore.shared.connection.ConnectionActiveLogoutFixture.Companion.TEST_PASSWORD
import one.meshcore.shared.connection.ConnectionActiveLogoutFixture.Companion.TIMEOUT_MS
import one.meshcore.shared.connection.ConnectionActiveLogoutFixture.Companion.assertWholeRow
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.remotenode.RemoteNodeEvent
import one.meshcore.shared.remotenode.login
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionActiveLogoutOwnershipTest {
    private lateinit var fixture: ConnectionActiveLogoutFixture

    @BeforeTest
    fun setUp() {
        fixture = ConnectionActiveLogoutFixture()
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun anActiveOldLogoutCannotDowngradeReplacementAuthentication() = runBlocking(fixture.dispatcher) {
        withTimeout(TIMEOUT_MS) {
            val dto = fixture.connectAndSeed()
            val manager = fixture.manager
            val oldServices = requireNotNull(manager.services)
            val generation = manager.connectGeneration
            val original = fixture.row(dto.id)
            val foreign = fixture.row(FOREIGN_ID)
            val oldLogout = fixture.scope.async(CoroutineName(LOGOUT_NAME)) {
                oldServices.remoteNodeService.logout(dto.id)
            }
            try {
                fixture.assertLogoutOwner(oldLogout, fixture.oldSession.sendCompleted.await())
                assertTrue(oldLogout.isActive)
                assertWholeRow(original, fixture.row(dto.id))
                manager.rebuildConnectedBleAppStack(DEVICE)
                val newServices = requireNotNull(manager.services)
                val newSession = manager.session as FakeMeshSession
                val newLink = requireNotNull(manager.link)
                assertNotSame(oldServices, newServices)
                assertNotSame(fixture.oldSession, newSession)
                assertTrue(fixture.oldSession.stopped)
                assertTrue(fixture.oldSession.closed)
                assertEquals(1, fixture.oldSession.closeCount)
                assertEquals(generation, manager.connectGeneration)
                assertEquals(dto.radioID, newServices.radioId)
                fixture.now = NEW_TIME
                assertEquals(
                    RoomPermissionLevel.ADMIN,
                    newServices.remoteNodeService.login(dto.id, TEST_PASSWORD).permissionLevel,
                )
                val fresh = fixture.row(dto.id)
                assertTrue(fresh.isConnected)
                assertEquals(RoomPermissionLevel.ADMIN.raw, fresh.permissionLevelRawValue)
                assertEquals(NEW_TIME, fresh.lastConnectedDate)
                fixture.oldSession.release.complete(Unit)
                val failure = runCatching { oldLogout.await() }.exceptionOrNull()
                assertTrue(failure == null || failure is CancellationException)
                assertTrue(oldLogout.isCompleted)
                fixture.joinLogoutWriters()
                assertTrue(fixture.scope.coroutineContext.job.isActive)
                assertEquals(ConnectionState.Ready(DEVICE), manager.state.value)
                assertSame(newServices, manager.services)
                assertSame(newSession, manager.session)
                assertSame(newLink, manager.link)
                assertEquals(2, fixture.sessions.size)
                assertEquals(1, newSession.loginDestinations.size)
                assertContentEquals(dto.publicKey, newSession.loginDestinations.single())
                assertEquals(1, fixture.oldSession.logoutDestinations.size)
                assertContentEquals(dto.publicKey, fixture.oldSession.logoutDestinations.single())
                assertWholeRow(foreign, fixture.row(FOREIGN_ID))
                assertWholeRow(fresh, fixture.row(dto.id))
            } finally {
                fixture.oldSession.release.complete(Unit)
                oldLogout.cancel()
                oldLogout.join()
            }
        }
    }

    @Test
    fun aLiveLogoutDowngradesOnlyConnectionFields() = runBlocking(fixture.dispatcher) {
        withTimeout(TIMEOUT_MS) {
            val dto = fixture.connectAndSeed()
            val services = requireNotNull(fixture.manager.services)
            val original = fixture.row(dto.id)
            val foreign = fixture.row(FOREIGN_ID)
            val disconnected = fixture.scope.async(start = CoroutineStart.UNDISPATCHED) {
                services.remoteNodeService.events.first {
                    it == RemoteNodeEvent.SessionStateChanged(dto.id, false)
                }
            }
            val logout = fixture.scope.async(CoroutineName(LOGOUT_NAME)) {
                services.remoteNodeService.logout(dto.id)
            }
            try {
                fixture.assertLogoutOwner(logout, fixture.oldSession.sendCompleted.await())
                assertTrue(logout.isActive)
                assertWholeRow(original, fixture.row(dto.id))
                fixture.oldSession.release.complete(Unit)
                logout.await()
                fixture.joinLogoutWriters()
                assertEquals(RemoteNodeEvent.SessionStateChanged(dto.id, false), disconnected.await())
                assertWholeRow(
                    original.copy(isConnected = false, permissionLevelRawValue = RoomPermissionLevel.GUEST.raw),
                    fixture.row(dto.id),
                )
                assertWholeRow(foreign, fixture.row(FOREIGN_ID))
                assertEquals(1, fixture.oldSession.logoutDestinations.size)
                assertContentEquals(dto.publicKey, fixture.oldSession.logoutDestinations.single())
                assertEquals(1, fixture.sessions.size)
                assertFalse(fixture.oldSession.closed)
                assertSame(services, fixture.manager.services)
                assertEquals(ConnectionState.Ready(DEVICE), fixture.manager.state.value)
            } finally {
                fixture.oldSession.release.complete(Unit)
                logout.cancel()
                logout.join()
                disconnected.cancel()
                disconnected.join()
            }
        }
    }
}
