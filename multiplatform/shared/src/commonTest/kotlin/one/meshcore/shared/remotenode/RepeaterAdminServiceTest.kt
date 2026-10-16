package one.meshcore.shared.remotenode

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.settings.CommandAuditLogger
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Admin connect, neighbour pagination, session queries, and the CLI-handler-survival invariant. */
@OptIn(ExperimentalCoroutinesApi::class)
class RepeaterAdminServiceTest {
    private class Harness(scope: kotlinx.coroutines.CoroutineScope, logger: PersistentLogger? = null) {
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val passwordStore = InMemoryNodePasswordStore()
        val remote = RemoteNodeService(session, store, passwordStore, scope)
        val cli = NodeCliCoordinator(session, store, passwordStore, scope)
        val auditEntries = mutableListOf<DebugLogEntryDto>()
        val auditLogger = CommandAuditLogger(record = { auditEntries += it })
        val admin = RepeaterAdminService(
            remote,
            cli,
            session,
            store,
            passwordStore,
            edges = RepeaterAdminEdges(auditLogger = auditLogger, logger = logger),
        )
    }

    @Test
    fun connectAsAdminCreatesSessionLogsInAndStoresPassword() = runTest {
        val h = Harness(backgroundScope)
        runCurrent()
        val contact = testContact()

        var result: one.meshcore.shared.backup.RemoteNodeSessionDto? = null
        val job = launch { result = h.admin.connectAsAdmin(testUuid(99), contact, password = "pw") }
        runCurrent()
        assertEquals(1, h.session.sendLoginCalls.size)
        h.session.emitLoginSuccess(testLoginInfo(contact.publicKey, isAdmin = true))
        runCurrent()
        job.join()

        assertTrue(result!!.isConnected)
        assertTrue(h.passwordStore.hasPassword(contact.publicKey))
    }

    @Test
    fun requestNeighborsRejectsNonRepeaterSession() = runTest {
        val h = Harness(backgroundScope)
        val room = testSession(role = RemoteNodeRole.ROOM_SERVER)
        h.store.saveSession(room)
        assertFailsWith<RemoteNodeError.SessionNotFound> { h.admin.requestNeighbors(room.id) }
    }

    @Test
    fun requestNeighborsReturnsPageForRepeater() = runTest {
        val h = Harness(backgroundScope)
        val dto = testSession(role = RemoteNodeRole.REPEATER)
        h.store.saveSession(dto)
        h.session.neighboursResponse = testNeighboursResponse(totalCount = 1, neighbours = listOf(testNeighbour()))

        val response = h.admin.requestNeighbors(dto.id)
        assertEquals(1, response.neighbours.size)
        assertEquals(20 to 0, h.session.requestNeighboursCalls.single())
        assertTrue(h.session.resetPathCalls.isEmpty())
    }

    @Test
    fun requestNeighborsTimeoutDoesNotResetPath() = runTest {
        val h = Harness(backgroundScope)
        val dto = testSession(role = RemoteNodeRole.REPEATER)
        h.store.saveSession(dto)
        h.session.neighboursError = MeshError.Timeout
        assertFailsWith<RemoteNodeError.Timeout> { h.admin.requestNeighbors(dto.id) }
        assertTrue(h.session.resetPathCalls.isEmpty())
    }

    @Test
    fun requestNeighborsMapsOtherMeshFailuresToSessionError() = runTest {
        val h = Harness(backgroundScope)
        val dto = testSession(role = RemoteNodeRole.REPEATER)
        h.store.saveSession(dto)
        h.session.neighboursError = MeshError.NotConnected
        val failure = assertFailsWith<RemoteNodeError.SessionError> { h.admin.requestNeighbors(dto.id) }
        assertEquals(MeshError.NotConnected, failure.error)
    }

    @Test
    fun fetchAllNeighborsPaginatesWithMaxPageSize() = runTest {
        val h = Harness(backgroundScope)
        val dto = testSession(role = RemoteNodeRole.REPEATER)
        h.store.saveSession(dto)
        val pages = mapOf(
            0 to testNeighboursResponse(totalCount = 3, neighbours = listOf(testNeighbour(), testNeighbour())),
            2 to testNeighboursResponse(totalCount = 3, neighbours = listOf(testNeighbour())),
        )
        h.session.requestNeighboursHandler = { _, offset -> pages.getValue(offset) }

        val all = h.admin.fetchAllNeighbors(dto.id)
        assertEquals(3, all.neighbours.size)
        // Each page requests the maximum count so the table needs the fewest round-trips.
        assertTrue(h.session.requestNeighboursCalls.all { it.first == 255 })
    }

    @Test
    fun fetchAllNeighborsRecordsIosTruncationDump() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val h = Harness(
            backgroundScope,
            logger = PersistentLogger(backgroundScope, category = "RepeaterAdmin", record = { recorded += it }),
        )
        val dto = testSession(role = RemoteNodeRole.REPEATER)
        h.store.saveSession(dto)
        val pages = mapOf(
            0 to testNeighboursResponse(totalCount = 10, neighbours = listOf(testNeighbour(), testNeighbour())),
            2 to testNeighboursResponse(totalCount = 10, neighbours = emptyList()),
        )
        h.session.requestNeighboursHandler = { _, offset -> pages.getValue(offset) }

        val all = h.admin.fetchAllNeighbors(dto.id)
        runCurrent()

        assertEquals(2, all.neighbours.size)
        assertEquals(10, all.totalCount)
        val line = recorded.single { it.message.contains("Neighbour pagination returned") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("RepeaterAdmin", line.category)
        assertEquals("Neighbour pagination returned 2 of 10 entries", line.message)
    }

    @Test
    fun fetchRepeaterSessionsFiltersByRole() = runTest {
        val h = Harness(backgroundScope)
        val radioID = testUuid(99)
        val repeater = testSession(id = testUuid(1), radioID = radioID, role = RemoteNodeRole.REPEATER)
        val room = testSession(
            id = testUuid(2),
            radioID = radioID,
            publicKey = testPublicKey(0x11),
            role = RemoteNodeRole.ROOM_SERVER,
        )
        h.store.saveSession(repeater)
        h.store.saveSession(room)

        val result = h.admin.fetchRepeaterSessions(radioID)
        assertEquals(listOf(repeater.id), result.map { it.id })
    }

    @Test
    fun getConnectedSessionRequiresConnectedRepeater() = runTest {
        val h = Harness(backgroundScope)
        val disconnected = testSession(role = RemoteNodeRole.REPEATER, isConnected = false)
        h.store.saveSession(disconnected)
        assertNull(h.admin.getConnectedSession(disconnected.publicKey.copyOf(6)))

        val connected = testSession(
            role = RemoteNodeRole.REPEATER,
            isConnected = true,
            permissionLevel = RoomPermissionLevel.ADMIN,
        )
        h.store.saveSession(connected)
        assertEquals(connected.id, h.admin.getConnectedSession(connected.publicKey.copyOf(6))?.id)
    }

    @Test
    fun clearStatusHandlersLeavesCliHandlerIntact() = runTest {
        val h = Harness(backgroundScope)
        var statusFired = false
        var cliFired = false
        h.admin.setStatusHandler { statusFired = true }
        h.admin.setCLIHandler { _, _ -> cliFired = true }

        h.admin.clearStatusHandlers()
        h.admin.invokeStatusHandler(anyStatus())
        h.admin.invokeCLIHandler(testContactMessage("response"), testContact())

        assertFalse(statusFired)
        assertTrue(cliFired)
    }

    @Test
    fun neighbourRequestAndResponseAreAudited() = runTest {
        val h = Harness(backgroundScope)
        val dto = testSession(role = RemoteNodeRole.REPEATER)
        h.store.saveSession(dto)
        val response = testNeighboursResponse(totalCount = 1, neighbours = listOf(testNeighbour()))
        h.session.neighboursResponse = response

        h.admin.requestNeighbors(dto.id)
        h.admin.invokeNeighboursHandler(response)

        assertTrue(h.auditEntries.any { it.message.contains("NEIGHBORS_REQ") })
        assertTrue(h.auditEntries.any { it.message.contains("NEIGHBORS from=") })
    }

    @Test
    fun missingHandlersStayOffDiskAndStillAudit() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val h = Harness(
            backgroundScope,
            logger = PersistentLogger(backgroundScope, category = "RepeaterAdmin", record = { recorded += it }),
        )
        val response = testNeighboursResponse(totalCount = 1, neighbours = listOf(testNeighbour()))
        h.admin.invokeStatusHandler(anyStatus())
        h.admin.invokeNeighboursHandler(response)
        h.admin.invokeTelemetryHandler(testTelemetryResponse())
        h.admin.invokeCLIHandler(testContactMessage("uptime: 5s"), testContact())
        runCurrent()

        assertTrue(recorded.isEmpty())
        assertTrue(h.auditEntries.any { it.message.contains("STATUS from=") })
        assertTrue(h.auditEntries.any { it.message.contains("NEIGHBORS from=") })
        assertTrue(h.auditEntries.any { it.message.contains("TELEM from=") })
        assertTrue(h.auditEntries.any { it.message.contains("CLI_RESP from=") })
    }

    @Test
    fun invokeHandlersAuditStatusTelemetryAndCliResponses() = runTest {
        val h = Harness(backgroundScope)
        h.admin.setStatusHandler { }
        h.admin.setTelemetryHandler { }
        h.admin.setCLIHandler { _, _ -> }

        h.admin.invokeStatusHandler(anyStatus())
        h.admin.invokeTelemetryHandler(testTelemetryResponse())
        h.admin.invokeCLIHandler(testContactMessage("uptime: 5s"), testContact())

        assertTrue(h.auditEntries.any { it.message.contains("STATUS from=") })
        assertTrue(h.auditEntries.any { it.message.contains("TELEM from=") })
        assertTrue(h.auditEntries.any { it.message.contains("CLI_RESP from=") })
    }

    private fun anyStatus(): StatusResponse = testStatusResponse()

    @Test
    fun lateCliReplyReachesTheRegisteredHandler() = runTest {
        val h = Harness(backgroundScope)
        runCurrent()
        val session = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        h.store.saveSession(session)
        h.store.setContact(session.radioID, testContact(publicKey = session.publicKey))
        val received = mutableListOf<String>()
        h.admin.setCLIHandler { message, _ -> received += message.text }

        // No request is pending: this is the reply that arrived after its section already timed out.
        h.session.emitCliResponse("915.0,250.0,10,5", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals(listOf("915.0,250.0,10,5"), received)
    }

    @Test
    fun lateCliReplyStripsTheEchoedWirePrefix() = runTest {
        val h = Harness(backgroundScope)
        runCurrent()
        val session = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN)
        h.store.saveSession(session)
        h.store.setContact(session.radioID, testContact(publicKey = session.publicKey))
        val received = mutableListOf<String>()
        h.admin.setCLIHandler { message, _ -> received += message.text }

        h.session.emitCliResponse("07|NodeName", session.publicKey.copyOf(6))
        runCurrent()

        assertEquals(listOf("NodeName"), received)
    }

    @Test
    fun lateCliReplyFromARoomSkipsTheRepeaterHandler() = runTest {
        val h = Harness(backgroundScope)
        runCurrent()
        val room = testSession(role = RemoteNodeRole.ROOM_SERVER, isConnected = true)
        h.store.saveSession(room)
        h.store.setContact(room.radioID, testContact(publicKey = room.publicKey))
        val received = mutableListOf<String>()
        h.admin.setCLIHandler { message, _ -> received += message.text }

        h.session.emitCliResponse("room reply", room.publicKey.copyOf(6))
        runCurrent()

        assertTrue(received.isEmpty())
    }
}
