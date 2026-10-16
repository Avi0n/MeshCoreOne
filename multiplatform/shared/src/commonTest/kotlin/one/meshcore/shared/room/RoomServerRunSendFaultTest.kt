package one.meshcore.shared.room

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.testPublicKey
import one.meshcore.shared.remotenode.testUuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A room radio send that is cancelled leaves the pending row alone. An ordinary failure, and a thrown
 * error, mark the row failed, log the failure, and do not escape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomServerRunSendFaultTest {
    @Test
    fun aCancelledSendKeepsTheThrownInstanceAndThePendingRow() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val harness = harness()
        harness.session.sendError = stopped

        val thrown = assertFailsWith<CancellationException> {
            harness.service.runSend(MESSAGE_ID, SESSION_ID, testPublicKey(), "hello", STORED_AT_EPOCH_SECONDS)
        }

        assertSame(stopped, thrown)
        assertEquals(MessageStatus.PENDING.raw, harness.store.fetchRoomMessage(MESSAGE_ID)!!.statusRawValue)
        assertTrue(harness.events.none { it == RoomServerEvent.StatusUpdated(MESSAGE_ID, MessageStatus.FAILED) })
        assertTrue(harness.logs.none { it.message.contains(SEND_FAILED_LOG) })
    }

    @Test
    fun aSendFailureMarksTheRowFailedAndLogs() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.session.sendError = RoomRunSendFailure()

        harness.service.runSend(MESSAGE_ID, SESSION_ID, testPublicKey(), "hello", STORED_AT_EPOCH_SECONDS)

        assertEquals(MessageStatus.FAILED.raw, harness.store.fetchRoomMessage(MESSAGE_ID)!!.statusRawValue)
        assertTrue(harness.events.contains(RoomServerEvent.StatusUpdated(MESSAGE_ID, MessageStatus.FAILED)))
        assertTrue(harness.logs.any { it.message == "$SEND_FAILED_LOG: radio" })
    }

    @Test
    fun aSendErrorMarksTheRowFailedAndDoesNotEscape() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness()
        harness.session.sendError = RoomRunSendProbeError()

        harness.service.runSend(MESSAGE_ID, SESSION_ID, testPublicKey(), "hello", STORED_AT_EPOCH_SECONDS)

        assertEquals(MessageStatus.FAILED.raw, harness.store.fetchRoomMessage(MESSAGE_ID)!!.statusRawValue)
        assertTrue(harness.events.contains(RoomServerEvent.StatusUpdated(MESSAGE_ID, MessageStatus.FAILED)))
        assertTrue(harness.logs.any { it.message == "$SEND_FAILED_LOG: probe" })
    }

    private suspend fun TestScope.harness(): RunSendHarness {
        val store = InMemoryRoomStore()
        store.saveRoomMessage(
            roomMessage(id = MESSAGE_ID, sessionID = SESSION_ID, statusRawValue = MessageStatus.PENDING.raw),
        )
        val session = FakeRoomServerSession()
        val passwords = InMemoryNodePasswordStore()
        val logs = mutableListOf<DebugLogEntryDto>()
        val logger = PersistentLogger(this, category = "RoomServerService", record = { logs += it })
        val service = RoomServerService(
            remoteNodeService = RemoteNodeService(FakeNodeSession(), store, passwords, backgroundScope),
            store = store,
            roomSession = session,
            passwordStore = passwords,
            scope = backgroundScope,
            edges = RoomServerEdges(now = { STORED_AT_EPOCH_SECONDS }, logger = logger),
        )
        val events = mutableListOf<RoomServerEvent>()
        backgroundScope.launch { service.events.collect { events += it } }
        return RunSendHarness(store, session, service, events, logs)
    }
}

private class RunSendHarness(
    val store: InMemoryRoomStore,
    val session: FakeRoomServerSession,
    val service: RoomServerService,
    val events: List<RoomServerEvent>,
    val logs: List<DebugLogEntryDto>,
)

private class RoomRunSendFailure : Exception("radio")

private class RoomRunSendProbeError : Error("probe")

private val MESSAGE_ID: Uuid = testUuid(7)
private val SESSION_ID: Uuid = testUuid(1)
private const val STORED_AT_EPOCH_SECONDS = 1_720_000_000L
private const val SEND_FAILED_LOG = "Room message send failed"
