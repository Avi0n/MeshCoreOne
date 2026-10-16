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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * After a room send resolves, a cancelled status write keeps the thrown instance, the pending row, and
 * skips the resolved event. An ordinary write failure, and a thrown error, are logged, the resolved
 * event still fires, and room activity is still recorded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomServerResolvedStatusFaultTest {
    @Test
    fun aCancelledStatusWriteKeepsTheThrownInstanceAndSkipsTheResolvedEvent() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val harness = harness(sendResult = roomSentInfo(), statusFailure = stopped)

        val thrown = assertFailsWith<CancellationException> { harness.send() }

        assertSame(stopped, thrown)
        assertEquals(MessageStatus.PENDING.raw, harness.inner.fetchRoomMessage(MESSAGE_ID)!!.statusRawValue)
        assertTrue(harness.events.none { it == RoomServerEvent.StatusUpdated(MESSAGE_ID, MessageStatus.DELIVERED) })
        assertTrue(harness.logs.none { it.message.contains(DELIVERED_STATUS_LOG) })
        assertNull(harness.inner.fetchSession(SESSION_ID)!!.lastMessageDate)
    }

    @Test
    fun aDeliveredStatusWriteFailureStillReportsDelivered() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(sendResult = roomSentInfo(), statusFailure = RoomResolvedStatusFailure())

        harness.send()

        assertEquals(MessageStatus.PENDING.raw, harness.inner.fetchRoomMessage(MESSAGE_ID)!!.statusRawValue)
        assertTrue(harness.events.contains(RoomServerEvent.StatusUpdated(MESSAGE_ID, MessageStatus.DELIVERED)))
        assertTrue(harness.logs.any { it.message == "$DELIVERED_STATUS_LOG: db" })
        assertNotNull(harness.inner.fetchSession(SESSION_ID)!!.lastMessageDate)
    }

    @Test
    fun aSentStatusWriteErrorIsLoggedAndDoesNotEscape() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(sendResult = null, statusFailure = RoomResolvedStatusProbeError())

        harness.send()

        assertEquals(MessageStatus.PENDING.raw, harness.inner.fetchRoomMessage(MESSAGE_ID)!!.statusRawValue)
        assertTrue(harness.events.contains(RoomServerEvent.StatusUpdated(MESSAGE_ID, MessageStatus.SENT)))
        assertTrue(harness.logs.any { it.message == "$SENT_STATUS_LOG: probe" })
        assertNotNull(harness.inner.fetchSession(SESSION_ID)!!.lastMessageDate)
    }

    @Test
    fun aCancelledActivityWriteRethrowsAfterTheResolvedEvent() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val harness = harness(sendResult = roomSentInfo(), statusFailure = null, activityFailure = stopped)

        val thrown = assertFailsWith<CancellationException> { harness.send() }

        assertSame(stopped, thrown)
        assertTrue(harness.events.contains(RoomServerEvent.StatusUpdated(MESSAGE_ID, MessageStatus.DELIVERED)))
        assertNull(harness.inner.fetchSession(SESSION_ID)!!.lastMessageDate)
    }

    @Test
    fun anActivityWriteFailureIsLoggedAfterTheResolvedEvent() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(
            sendResult = roomSentInfo(),
            statusFailure = null,
            activityFailure = RoomResolvedStatusFailure(),
        )

        harness.send()

        assertTrue(harness.events.contains(RoomServerEvent.StatusUpdated(MESSAGE_ID, MessageStatus.DELIVERED)))
        assertTrue(harness.logs.any { it.message == "Failed to update room activity: db" })
    }

    private suspend fun TestScope.harness(
        sendResult: one.meshcore.shared.meshcore.MessageSentInfo?,
        statusFailure: Throwable?,
        activityFailure: Throwable? = null,
    ): ResolvedStatusHarness {
        val inner = InMemoryRoomStore()
        val store = RoomResolvedStatusStore(inner)
        inner.seedSession(roomSession(id = SESSION_ID))
        inner.saveRoomMessage(
            roomMessage(id = MESSAGE_ID, sessionID = SESSION_ID, statusRawValue = MessageStatus.PENDING.raw),
        )
        val session = FakeRoomServerSession()
        session.sendResult = sendResult
        store.failure = statusFailure
        store.activityFailure = activityFailure
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
        return ResolvedStatusHarness(inner, service, events, logs)
    }
}

private class ResolvedStatusHarness(
    val inner: InMemoryRoomStore,
    val service: RoomServerService,
    val events: List<RoomServerEvent>,
    val logs: List<DebugLogEntryDto>,
) {
    suspend fun send() {
        service.runSend(MESSAGE_ID, SESSION_ID, testPublicKey(), "hello", STORED_AT_EPOCH_SECONDS)
    }
}

private class RoomResolvedStatusFailure : Exception("db")

private class RoomResolvedStatusProbeError : Error("probe")

private class RoomResolvedStatusStore(private val backing: InMemoryRoomStore) : RoomStore by backing {
    var failure: Throwable? = null
    var activityFailure: Throwable? = null

    override suspend fun updateRoomMessageStatus(
        id: Uuid,
        status: MessageStatus,
        ackCode: Long?,
        roundTripTime: Long?,
    ) {
        failure?.let { throw it }
        backing.updateRoomMessageStatus(id, status, ackCode, roundTripTime)
    }

    override suspend fun updateRoomActivity(id: Uuid, syncTimestamp: Long?) {
        activityFailure?.let { throw it }
        backing.updateRoomActivity(id, syncTimestamp)
    }
}

private val MESSAGE_ID: Uuid = testUuid(9)
private val SESSION_ID: Uuid = testUuid(1)
private const val STORED_AT_EPOCH_SECONDS = 1_720_000_000L
private const val DELIVERED_STATUS_LOG = "Failed to update message status after successful send"
private const val SENT_STATUS_LOG = "Failed to update message status to sent"
