package one.meshcore.shared.remotenode

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BLE-loss teardown and reconnect re-auth on [RemoteNodeService]: connected sessions are captured and
 * marked disconnected on loss, re-authenticated on reconnect, and defended against a degraded permission
 * or a failed re-auth. `runCurrent()` drives the login push without advancing time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNodeReconnectionTest {
    @Test
    fun disconnectionReturnsConnectedIdsAndMarksThemDisconnected() = runTest {
        val store = InMemoryRemoteNodeStore()
        val connected = testSession(id = testUuid(1), isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        val idle = testSession(id = testUuid(2), publicKey = testPublicKey(0x11), isConnected = false)
        store.saveSession(connected)
        store.saveSession(idle)
        val service = RemoteNodeService(FakeNodeSession(), store, InMemoryNodePasswordStore(), backgroundScope)
        val events = mutableListOf<RemoteNodeEvent>()
        backgroundScope.launch { service.events.collect { events += it } }
        runCurrent()

        val ids = service.handleBLEDisconnection()
        runCurrent()

        assertEquals(setOf(connected.id), ids)
        assertFalse(store.fetchSessionById(connected.id)!!.isConnected)
        // Permission level is preserved across a transient drop.
        assertEquals(RoomPermissionLevel.ADMIN, store.fetchSessionById(connected.id)!!.permissionLevel)
        assertTrue(events.any { it.isDisconnect(connected.id) })
    }

    private fun RemoteNodeEvent.isDisconnect(sessionID: Uuid): Boolean =
        this is RemoteNodeEvent.SessionStateChanged && this.sessionID == sessionID && !isConnected

    @Test
    fun disconnectionReturnsEmptyWhenNoneConnected() = runTest {
        val store = InMemoryRemoteNodeStore()
        store.saveSession(testSession(isConnected = false))
        val service = RemoteNodeService(FakeNodeSession(), store, InMemoryNodePasswordStore(), backgroundScope)

        assertTrue(service.handleBLEDisconnection().isEmpty())
    }

    @Test
    fun reconnectionReauthenticatesAndRestoresConnection() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val pw = InMemoryNodePasswordStore()
        val dto = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        store.saveSession(dto)
        pw.storePassword(dto.publicKey, "pw")
        val service = RemoteNodeService(session, store, pw, backgroundScope)
        runCurrent()

        val ids = service.handleBLEDisconnection()
        runCurrent()
        assertFalse(store.fetchSessionById(dto.id)!!.isConnected)

        val job = launch { service.handleBLEReconnection(ids) }
        runCurrent()
        assertEquals(1, session.sendLoginCalls.size)
        session.emitLoginSuccess(testLoginInfo(dto.publicKey, isAdmin = true))
        runCurrent()
        job.join()

        assertTrue(store.fetchSessionById(dto.id)!!.isConnected)
    }

    @Test
    fun reconnectionSkipsAFetchFaultAndStillReauthsTheRest() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeNodeSession()
        val inner = InMemoryRemoteNodeStore()
        val pw = InMemoryNodePasswordStore()
        val good = testSession(id = testUuid(1), isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        val doomed = testSession(
            id = testUuid(2),
            publicKey = testPublicKey(0x22),
            isConnected = true,
            permissionLevel = RoomPermissionLevel.READ_WRITE,
        )
        inner.saveSession(good)
        inner.saveSession(doomed)
        pw.storePassword(good.publicKey, "pw")
        val store = object : RemoteNodeStore by inner {
            override suspend fun fetchSessionById(id: Uuid) =
                if (id == doomed.id) error("db locked") else inner.fetchSessionById(id)
        }
        val service = RemoteNodeService(
            session,
            store,
            pw,
            backgroundScope,
            logger = PersistentLogger(
                scope = backgroundScope,
                category = "RemoteNodeService",
                record = { recorded += it },
            ),
        )
        runCurrent()

        val job = launch { service.handleBLEReconnection(setOf(good.id, doomed.id)) }
        runCurrent()
        session.emitLoginSuccess(testLoginInfo(good.publicKey, isAdmin = true))
        runCurrent()
        job.join()

        val skip = recorded.single { it.message.contains("Session ${doomed.id} not found for re-auth, skipping") }
        assertEquals(DebugLogLevel.WARNING, skip.level)
        val reauth = recorded.single { it.message.contains("BLE reconnection: re-authenticating") }
        assertTrue(reauth.message.contains("1 session"))
        assertTrue(store.fetchSessionById(good.id)!!.isConnected)
    }

    @Test
    fun reconnectionDisconnectsOnDegradedPermission() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val pw = InMemoryNodePasswordStore()
        val dto = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        store.saveSession(dto)
        pw.storePassword(dto.publicKey, "pw")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val service = RemoteNodeService(
            session,
            store,
            pw,
            backgroundScope,
            logger = PersistentLogger(
                backgroundScope,
                category = "RemoteNodeService",
                record = { recorded += it },
            ),
        )
        runCurrent()

        val job = launch { service.handleBLEReconnection(setOf(dto.id)) }
        runCurrent()
        // Re-auth returns read-write, a downgrade from the prior admin level.
        session.emitLoginSuccess(testLoginInfo(dto.publicKey, isAdmin = false, permissions = 1))
        runCurrent()
        job.join()
        runCurrent()

        assertFalse(store.fetchSessionById(dto.id)!!.isConnected)
        val line = recorded.single { it.message.contains("Re-auth returned degraded permission") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains(dto.id.toString()))
    }

    @Test
    fun reconnectionDisconnectsWhenReauthFails() = runTest {
        val store = InMemoryRemoteNodeStore()
        val dto = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        store.saveSession(dto)
        // No stored password: login raises passwordNotFound, so re-auth fails.
        val recorded = mutableListOf<DebugLogEntryDto>()
        val service = RemoteNodeService(
            FakeNodeSession(),
            store,
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(
                backgroundScope,
                category = "RemoteNodeService",
                record = { recorded += it },
            ),
        )
        val events = mutableListOf<RemoteNodeEvent>()
        backgroundScope.launch { service.events.collect { events += it } }
        runCurrent()

        service.handleBLEReconnection(setOf(dto.id))
        runCurrent()

        assertFalse(store.fetchSessionById(dto.id)!!.isConnected)
        assertTrue(events.any { it is RemoteNodeEvent.SessionStateChanged && !it.isConnected })
        val line = recorded.single { it.message.contains("Re-auth failed for session ${dto.id}") }
        assertEquals(DebugLogLevel.WARNING, line.level)
    }

    @Test
    fun reconnectionWithEmptySetIsNoOp() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val service = RemoteNodeService(session, store, InMemoryNodePasswordStore(), backgroundScope)

        service.handleBLEReconnection(emptySet())

        assertTrue(session.sendLoginCalls.isEmpty())
    }

    @Test
    fun reconnectionIsReentrancyGuarded() = runTest {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val pw = InMemoryNodePasswordStore()
        val dto = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        store.saveSession(dto)
        pw.storePassword(dto.publicKey, "pw")
        val service = RemoteNodeService(session, store, pw, backgroundScope)
        runCurrent()

        val first = launch { service.handleBLEReconnection(setOf(dto.id)) }
        runCurrent()
        assertEquals(1, session.sendLoginCalls.size)

        // A second call while the first is still awaiting its push is skipped.
        val second = launch { service.handleBLEReconnection(setOf(dto.id)) }
        runCurrent()
        second.join()
        assertEquals(1, session.sendLoginCalls.size)

        session.emitLoginSuccess(testLoginInfo(dto.publicKey, isAdmin = true))
        runCurrent()
        first.join()
    }
}
