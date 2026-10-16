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
 * After a room send fails, a cancelled status write keeps the thrown instance and the pending row.
 * An ordinary write failure, and a thrown error, are logged, the failed event still fires, and the
 * row stays pending.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomServerRunSendStatusFaultTest {
    @Test
    fun aCancelledStatusWriteKeepsTheThrownInstanceAndThePendingRow() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val harness = harness(stopped)

        val thrown = assertFailsWith<CancellationException> { harness.send() }

        assertSame(stopped, thrown)
        assertEquals(MessageStatus.PENDING.raw, harness.store.fetchRoomMessage(MESSAGE_ID)!!.statusRawValue)
        assertTrue(harness.events.none { it == RoomServerEvent.StatusUpdated(MESSAGE_ID, MessageStatus.FAILED) })
        assertTrue(harness.logs.none { it.message.contains(SEND_FAILED_LOG) })
        assertTrue(harness.logs.none { it.message.contains(STATUS_FAILED_LOG) })
    }

    @Test
    fun aStatusWriteFailureStillReportsTheFailedSend() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(RoomRunSendStatusFailure())

        harness.send()

        assertEquals(MessageStatus.PENDING.raw, harness.store.fetchRoomMessage(MESSAGE_ID)!!.statusRawValue)
        assertTrue(harness.events.contains(RoomServerEvent.StatusUpdated(MESSAGE_ID, MessageStatus.FAILED)))
        assertTrue(harness.logs.any { it.message == "$SEND_FAILED_LOG: radio" })
        assertTrue(harness.logs.any { it.message == "$STATUS_FAILED_LOG: db" })
    }

    @Test
    fun aStatusWriteErrorIsLoggedAndDoesNotEscape() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(RoomRunSendStatusProbeError())

        harness.send()

        assertEquals(MessageStatus.PENDING.raw, harness.store.fetchRoomMessage(MESSAGE_ID)!!.statusRawValue)
        assertTrue(harness.events.contains(RoomServerEvent.StatusUpdated(MESSAGE_ID, MessageStatus.FAILED)))
        assertTrue(harness.logs.any { it.message == "$SEND_FAILED_LOG: radio" })
        assertTrue(harness.logs.any { it.message == "$STATUS_FAILED_LOG: probe" })
    }

    private suspend fun TestScope.harness(statusFailure: Throwable): StatusFaultHarness {
        val inner = InMemoryRoomStore()
        val store = RoomRunSendStatusStore(inner)
        inner.saveRoomMessage(
            roomMessage(id = MESSAGE_ID, sessionID = SESSION_ID, statusRawValue = MessageStatus.PENDING.raw),
        )
        val session = FakeRoomServerSession()
        session.sendError = RoomRunSendStatusTrigger()
        store.failure = statusFailure
        val passwords = InMemoryNodePasswordStore()
        val logs = mutableListOf<DebugLogEntryDto>()
        val logger = PersistentLogger(this, category = "RoomServerService", record = { logs += it })
        val service = RoomServerService(
            remoteNodeService = RemoteNodeService(FakeNodeSession(), inner, passwords, backgroundScope),
            store = store,
            roomSession = session,
            passwordStore = passwords,
            scope = backgroundScope,
            edges = RoomServerEdges(now = { STORED_AT_EPOCH_SECONDS }, logger = logger),
        )
        val events = mutableListOf<RoomServerEvent>()
        backgroundScope.launch { service.events.collect { events += it } }
        return StatusFaultHarness(store, service, events, logs)
    }
}

private class StatusFaultHarness(
    val store: RoomRunSendStatusStore,
    val service: RoomServerService,
    val events: List<RoomServerEvent>,
    val logs: List<DebugLogEntryDto>,
) {
    suspend fun send() {
        service.runSend(MESSAGE_ID, SESSION_ID, testPublicKey(), "hello", STORED_AT_EPOCH_SECONDS)
    }
}

private class RoomRunSendStatusTrigger : Exception("radio")

private class RoomRunSendStatusFailure : Exception("db")

private class RoomRunSendStatusProbeError : Error("probe")

private class RoomRunSendStatusStore(private val backing: InMemoryRoomStore) : RoomStore by backing {
    var failure: Throwable? = null

    override suspend fun updateRoomMessageStatus(
        id: Uuid,
        status: MessageStatus,
        ackCode: Long?,
        roundTripTime: Long?,
    ) {
        failure?.let { throw it }
        backing.updateRoomMessageStatus(id, status, ackCode, roundTripTime)
    }
}

private val MESSAGE_ID: Uuid = testUuid(8)
private val SESSION_ID: Uuid = testUuid(1)
private const val STORED_AT_EPOCH_SECONDS = 1_720_000_000L
private const val SEND_FAILED_LOG = "Room message send failed"
private const val STATUS_FAILED_LOG = "Failed to update message status after send error"
