package one.meshcore.shared.room

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ackCodeLeUInt32
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.MeshError
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.testLoginInfo
import one.meshcore.shared.remotenode.testPublicKey
import one.meshcore.shared.settings.CommandAuditLogger
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Send-outcome, dedup, incoming-ingest, and auth-flow behavior for [RoomServerService], driven against
 * fakes and a real [RemoteNodeService] login path. These are the tests the Swift `RoomServerService` lacks:
 * the optimistic-pending → delivered/sent/failed transitions, the deduplication safety net, connection
 * recovery, and no-author-echo. `runCurrent()` dispatches ready work (a parked login's push, a background
 * post) without advancing virtual time, so a parked login resolves on its push rather than its timeout.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomServerServiceTest {
    private val selfPrefix = byteArrayOf(0x11, 0x22, 0x33, 0x44)

    private class Fixture(
        scope: CoroutineScope,
        logger: PersistentLogger = PersistentLogger(scope, category = "RoomServerService"),
    ) {
        val nodeSession = FakeNodeSession()
        val store = InMemoryRoomStore()
        val passwords = InMemoryNodePasswordStore()
        val roomSession = FakeRoomServerSession()
        val remoteNode = RemoteNodeService(nodeSession, store, passwords, scope)
        val auditEntries = mutableListOf<DebugLogEntryDto>()
        val auditLogger = CommandAuditLogger(record = { auditEntries += it })
        val service = RoomServerService(
            remoteNode,
            store,
            roomSession,
            passwords,
            scope,
            edges = RoomServerEdges(now = { NOW }, auditLogger = auditLogger, logger = logger),
        )
    }

    private fun TestScope.fixture(): Fixture = Fixture(backgroundScope)

    private fun TestScope.collectEvents(service: RoomServerService): MutableList<RoomServerEvent> {
        val events = mutableListOf<RoomServerEvent>()
        backgroundScope.launch { service.events.collect { events += it } }
        runCurrent()
        return events
    }

    // MARK: Posting — send outcomes

    @Test
    fun postDeliveredWhenAckReceived() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        f.service.setSelfPublicKeyPrefix(selfPrefix)
        f.roomSession.sendResult = roomSentInfo(ack = byteArrayOf(1, 2, 3, 4), suggestedTimeoutMs = 1234)
        val events = collectEvents(f.service)

        val pending = f.service.postMessage(SESSION_ID, "hello room")
        assertEquals(MessageStatus.PENDING.raw, pending.statusRawValue)
        assertTrue(pending.isFromSelf)
        assertEquals("Me", pending.authorName)
        assertContentEquals(selfPrefix, pending.authorKeyPrefix)

        runCurrent()
        val saved = f.store.fetchRoomMessage(pending.id)!!
        assertEquals(MessageStatus.DELIVERED.raw, saved.statusRawValue)
        assertEquals(byteArrayOf(1, 2, 3, 4).ackCodeLeUInt32(), saved.ackCode)
        assertEquals(1234, saved.roundTripTime)
        assertTrue(events.contains(RoomServerEvent.StatusUpdated(pending.id, MessageStatus.DELIVERED)))
        assertNotNull(f.store.fetchSession(SESSION_ID)!!.lastMessageDate)
    }

    @Test
    fun postSentWhenRetriesExhaustedWithoutAck() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        f.roomSession.sendResult = null
        val events = collectEvents(f.service)

        val pending = f.service.postMessage(SESSION_ID, "no ack")
        runCurrent()

        assertEquals(1, f.roomSession.sendCalls.size)
        val saved = f.store.fetchRoomMessage(pending.id)!!
        assertEquals(MessageStatus.SENT.raw, saved.statusRawValue)
        assertNull(saved.ackCode)
        assertNull(saved.roundTripTime)
        assertTrue(events.contains(RoomServerEvent.StatusUpdated(pending.id, MessageStatus.SENT)))
        assertNotNull(f.store.fetchSession(SESSION_ID)!!.lastMessageDate)
    }

    @Test
    fun postFailedWhenSendThrowsAndActivityUntouched() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        f.roomSession.sendError = MeshError.Timeout
        val events = collectEvents(f.service)

        val pending = f.service.postMessage(SESSION_ID, "boom")
        runCurrent()

        val saved = f.store.fetchRoomMessage(pending.id)!!
        assertEquals(MessageStatus.FAILED.raw, saved.statusRawValue)
        assertTrue(events.contains(RoomServerEvent.StatusUpdated(pending.id, MessageStatus.FAILED)))
        assertNull(f.store.fetchSession(SESSION_ID)!!.lastMessageDate)
    }

    @Test
    fun postRequiresCanPost() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession(permissionLevel = RoomPermissionLevel.GUEST))

        assertFailsWith<RoomServerError.PermissionDenied> { f.service.postMessage(SESSION_ID, "nope") }
    }

    @Test
    fun postMessageRejectsTextOverTheByteBudget() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        assertFailsWith<RoomServerError.SendFailed> {
            f.service.postMessage(SESSION_ID, "a".repeat(151))
        }
    }

    @Test
    fun postUnknownSessionThrowsSessionNotFound() = runTest {
        val f = fixture()
        assertFailsWith<RoomServerError.SessionNotFound> { f.service.postMessage(SESSION_ID, "nope") }
    }

    @Test
    fun postFallsBackToZeroAuthorPrefixWhenSelfUnknown() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())

        val pending = f.service.postMessage(SESSION_ID, "anon")
        assertContentEquals(ByteArray(4), pending.authorKeyPrefix)
    }

    // MARK: Retry

    @Test
    fun retryFailedMessageResolvesToDelivered() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        f.roomSession.sendError = MeshError.Timeout
        val events = collectEvents(f.service)
        val pending = f.service.postMessage(SESSION_ID, "retry me")
        runCurrent()

        f.roomSession.sendError = null
        f.roomSession.sendResult = roomSentInfo()
        val retried = f.service.retryMessage(pending.id)
        runCurrent()

        assertEquals(MessageStatus.DELIVERED.raw, retried.statusRawValue)
        assertEquals(1, retried.retryAttempt)
        assertTrue(events.contains(RoomServerEvent.StatusUpdated(pending.id, MessageStatus.PENDING)))
        assertTrue(events.contains(RoomServerEvent.StatusUpdated(pending.id, MessageStatus.DELIVERED)))
    }

    @Test
    fun retryRejectsNonFailedMessage() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        val pending = f.service.postMessage(SESSION_ID, "ok")
        runCurrent()

        assertFailsWith<RoomServerError.SendFailed> { f.service.retryMessage(pending.id) }
    }

    @Test
    fun retryRejectsConcurrentRetryForSameMessage() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        f.roomSession.sendError = MeshError.Timeout
        val pending = f.service.postMessage(SESSION_ID, "gate")
        runCurrent()

        // Park the first retry inside the send so a second retry sees it in flight.
        val gate = CompletableDeferred<Unit>()
        f.roomSession.sendError = null
        f.roomSession.onSend = { gate.await() }
        val first = launch { f.service.retryMessage(pending.id) }
        runCurrent()

        assertFailsWith<RoomServerError.SendFailed> { f.service.retryMessage(pending.id) }
        gate.complete(Unit)
        first.join()
    }

    // MARK: Incoming

    @Test
    fun postMessageRecordsCommandAudit() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())

        f.service.postMessage(SESSION_ID, "hello room")

        assertEquals(
            listOf(CommandAuditLogger.Traffic.roomMessagePosted(testPublicKey(), "hello room".length)),
            f.auditEntries.map { it.message },
        )
    }

    @Test
    fun incomingRecordsCommandAuditOnce() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        val author = byteArrayOf(1, 2, 3, 4)

        f.service.handleIncomingMessage(ROOM_PREFIX, INBOUND_TS, author, "dup")
        f.service.handleIncomingMessage(ROOM_PREFIX, INBOUND_TS, author, "dup")

        assertEquals(
            listOf(CommandAuditLogger.Traffic.roomMessageReceived(ROOM_PREFIX, author, "dup".length)),
            f.auditEntries.map { it.message },
        )
    }

    @Test
    fun incomingUnknownRoomDoesNotRecordCommandAudit() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())

        f.service.handleIncomingMessage(byteArrayOf(0, 0, 0, 0, 0, 0), INBOUND_TS, byteArrayOf(1), "x")

        assertTrue(f.auditEntries.isEmpty())
    }

    @Test
    fun incomingSavesMessageAndIncrementsUnread() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession(unreadCount = 0))
        f.service.setSelfPublicKeyPrefix(selfPrefix)
        val authorPrefix = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        f.store.setContactName(authorPrefix, "Alice")

        val saved = f.service.handleIncomingMessage(ROOM_PREFIX, INBOUND_TS, authorPrefix, "hi all")

        assertNotNull(saved)
        assertEquals("Alice", saved.authorName)
        assertFalse(saved.isFromSelf)
        assertEquals(MessageStatus.DELIVERED.raw, saved.statusRawValue)
        val session = f.store.fetchSession(SESSION_ID)!!
        assertEquals(1, session.unreadCount)
        assertEquals(INBOUND_TS, session.lastSyncTimestamp)
    }

    @Test
    fun incomingOlderTimestampDoesNotRewindSyncWatermark() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession(lastSyncTimestamp = INBOUND_TS))
        val author = byteArrayOf(1, 2, 3, 4)

        f.service.handleIncomingMessage(ROOM_PREFIX, INBOUND_TS - 100, author, "late dump")

        assertEquals(INBOUND_TS, f.store.fetchSession(SESSION_ID)!!.lastSyncTimestamp)
    }

    @Test
    fun incomingDeduplicatesRepeatedMessage() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        val author = byteArrayOf(1, 2, 3, 4)

        val first = f.service.handleIncomingMessage(ROOM_PREFIX, INBOUND_TS, author, "dup")
        val second = f.service.handleIncomingMessage(ROOM_PREFIX, INBOUND_TS, author, "dup")

        assertNotNull(first)
        assertNull(second)
        assertEquals(1, f.service.fetchMessages(SESSION_ID).size)
    }

    @Test
    fun incomingUnknownRoomPrefixIgnored() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())

        val result = f.service.handleIncomingMessage(byteArrayOf(0, 0, 0, 0, 0, 0), INBOUND_TS, byteArrayOf(1), "x")
        assertNull(result)
    }

    @Test
    fun incomingRecoversDisconnectedSession() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession(isConnected = false))
        val events = collectEvents(f.service)

        f.service.handleIncomingMessage(ROOM_PREFIX, INBOUND_TS, byteArrayOf(5, 6, 7, 8), "back")
        runCurrent()

        assertTrue(f.store.fetchSession(SESSION_ID)!!.isConnected)
        assertTrue(events.contains(RoomServerEvent.ConnectionRecovered(SESSION_ID)))
    }

    @Test
    fun incomingSelfAuthoredDoesNotIncrementUnread() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession(unreadCount = 0))
        f.service.setSelfPublicKeyPrefix(selfPrefix)

        val saved = f.service.handleIncomingMessage(ROOM_PREFIX, INBOUND_TS, selfPrefix, "echo")

        assertNotNull(saved)
        assertTrue(saved.isFromSelf)
        assertEquals(0, f.store.fetchSession(SESSION_ID)!!.unreadCount)
    }

    @Test
    fun postedMessageWouldDedupeAgainstServerEcho() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        f.service.setSelfPublicKeyPrefix(selfPrefix)
        val posted = f.service.postMessage(SESSION_ID, "no echo please")
        runCurrent()

        // Simulate the room echoing the author's own post back: same timestamp + author + text.
        val echo = f.service.handleIncomingMessage(ROOM_PREFIX, posted.timestamp, selfPrefix, "no echo please")

        assertNull(echo)
        assertEquals(1, f.service.fetchMessages(SESSION_ID).size)
    }

    // MARK: Auth flow

    @Test
    fun joinRoomLogsInStoresPasswordAndSyncsHistory() = runTest {
        val f = fixture()
        val contact = roomContact()
        f.store.setContactRouting(contact.radioID, contact.publicKey, isFloodRouted = false)

        val job = launch { f.service.joinRoom(contact.radioID, contact, password = "secret") }
        runCurrent()
        f.nodeSession.emitLoginSuccess(testLoginInfo(contact.publicKey, isAdmin = false, permissions = 1))
        runCurrent()
        job.join()

        assertTrue(f.passwords.hasPassword(contact.publicKey))
        assertContentEquals(contact.publicKey, f.roomSession.historySyncCalls.single())
        val session = f.store.fetchSessionByPublicKey(contact.publicKey)!!
        assertTrue(session.isConnected)
        assertEquals(RoomPermissionLevel.READ_WRITE, session.permissionLevel)
    }

    @Test
    fun historySyncDumpLinesMatchIos() {
        assertEquals("Trying advert path for room Ops", tryingAdvertPathForRoomLine("Ops"))
        assertEquals("History sync succeeded using advert path", HISTORY_SYNC_SUCCEEDED_USING_ADVERT_PATH)
        assertEquals(
            "Advert path failed for Ops: timeout, trying path discovery",
            advertPathFailedLine("Ops", "timeout"),
        )
        assertEquals("Room Ops is flood-routed, attempting path discovery", roomIsFloodRoutedLine("Ops"))
        assertEquals(
            "Could not establish direct route for Ops, skipping history sync",
            couldNotEstablishDirectRouteLine("Ops"),
        )
        assertEquals("History sync succeeded after path discovery", HISTORY_SYNC_SUCCEEDED_AFTER_PATH_DISCOVERY)
        assertEquals("Requested history sync for room Ops", requestedHistorySyncForRoomLine("Ops"))
    }

    @Test
    fun joinRoomHistorySyncRecordsAdvertPathDumps() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val f = Fixture(
            backgroundScope,
            PersistentLogger(this, category = "RoomServerService", record = { recorded += it }),
        )
        val contact = roomContact()
        f.store.setContactRouting(contact.radioID, contact.publicKey, isFloodRouted = false)

        val job = launch { f.service.joinRoom(contact.radioID, contact, password = "secret") }
        runCurrent()
        f.nodeSession.emitLoginSuccess(testLoginInfo(contact.publicKey, isAdmin = false, permissions = 1))
        runCurrent()
        job.join()
        runCurrent()

        val name = f.store.fetchSessionByPublicKey(contact.publicKey)!!.name
        assertEquals(
            tryingAdvertPathForRoomLine(name),
            recorded.single {
                it.message.startsWith("Trying advert path")
            }.message,
        )
        assertEquals(
            requestedHistorySyncForRoomLine(name),
            recorded.single {
                it.message.startsWith("Requested history sync")
            }.message,
        )
        assertEquals(
            HISTORY_SYNC_SUCCEEDED_USING_ADVERT_PATH,
            recorded.single {
                it.message ==
                    HISTORY_SYNC_SUCCEEDED_USING_ADVERT_PATH
            }.message,
        )
    }

    @Test
    fun joinRoomHistorySyncFaultRecordsIosDumpAndStillJoins() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val f = Fixture(
            backgroundScope,
            PersistentLogger(this, category = "RoomServerService", record = { recorded += it }),
        )
        val contact = roomContact()
        f.store.setContactRouting(contact.radioID, contact.publicKey, isFloodRouted = false)
        f.roomSession.historySyncError = IllegalStateException("db locked")

        val job = launch { f.service.joinRoom(contact.radioID, contact, password = "secret") }
        runCurrent()
        f.nodeSession.emitLoginSuccess(testLoginInfo(contact.publicKey, isAdmin = false, permissions = 1))
        runCurrent()
        job.join()

        val session = f.store.fetchSessionByPublicKey(contact.publicKey)!!
        assertTrue(session.isConnected)
        val line = recorded.single { it.message.contains("Failed to sync history for session") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("RoomServerService", line.category)
        assertTrue(line.message.contains("db locked"))
    }

    @Test
    fun joinRoomDoesNotStorePasswordWhenRememberFalse() = runTest {
        val f = fixture()
        val contact = roomContact()

        val job = launch {
            f.service.joinRoom(
                contact.radioID,
                contact,
                password = "secret",
                login = RoomServerLogin(rememberPassword = false),
            )
        }
        runCurrent()
        f.nodeSession.emitLoginSuccess(testLoginInfo(contact.publicKey, isAdmin = false, permissions = 1))
        runCurrent()
        job.join()

        assertFalse(f.passwords.hasPassword(contact.publicKey))
    }

    @Test
    fun joinRoomDoesNotStorePasswordOnLoginFailure() = runTest {
        val f = fixture()
        val contact = roomContact()

        var thrown: Throwable? = null
        val job = launch {
            thrown = runCatching { f.service.joinRoom(contact.radioID, contact, password = "secret") }.exceptionOrNull()
        }
        runCurrent()
        f.nodeSession.emitLoginFailed(contact.publicKey.copyOf(6))
        runCurrent()
        job.join()

        assertTrue(thrown is RemoteNodeError.LoginFailed)
        assertFalse(f.passwords.hasPassword(contact.publicKey))
    }

    @Test
    fun reconnectRejectsNonRoomSession() = runTest {
        val f = fixture()
        f.store.seedSession(
            roomSession().copy(role = one.meshcore.shared.model.RemoteNodeRole.REPEATER),
        )
        assertFailsWith<RemoteNodeError.InvalidResponse> { f.service.reconnectRoom(SESSION_ID) }
    }

    @Test
    fun leaveRoomLogsOutAndClearsPassword() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        f.passwords.storePassword(testPublicKey(), "secret")

        f.service.leaveRoom(SESSION_ID, testPublicKey())

        assertNull(f.store.fetchSession(SESSION_ID))
        assertFalse(f.passwords.hasPassword(testPublicKey()))
        assertContentEquals(testPublicKey(), f.nodeSession.sendLogoutCalls.single())
    }

    // MARK: Queries

    @Test
    fun getConnectedSessionRequiresConnectedRoom() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession(isConnected = false))
        assertNull(f.service.getConnectedSession(ROOM_PREFIX))

        f.store.seedSession(roomSession(isConnected = true))
        assertNotNull(f.service.getConnectedSession(ROOM_PREFIX))
    }

    private companion object {
        const val NOW = 1_720_000_000L
        const val INBOUND_TS = 1_720_000_500L
        val SESSION_ID = testId(1)
        val ROOM_PREFIX = testPublicKey().copyOf(6)
    }
}

private fun testId(n: Int) = one.meshcore.shared.remotenode.testUuid(n)
