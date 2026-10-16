package one.meshcore.shared.room

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.testUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Message-list assembly, send/retry, room-event folding (status → coalesced reload, recovery → refresh),
 * unread reset, and the timestamp-grouping rule for [RoomConversationController]. The Swift
 * `RoomConversationViewModel` has no unit tests, so these add coverage. Driven against a real
 * [RoomServerService] over the room fakes; `advanceTimeBy` fires the coalesced-reload debounce.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomConversationControllerTest {
    private val sessionId = testUuid(1)

    private class Fixture(scope: CoroutineScope) {
        val store = InMemoryRoomStore()
        val roomSession = FakeRoomServerSession()
        val passwords = InMemoryNodePasswordStore()
        val remoteNode = RemoteNodeService(FakeNodeSession(), store, passwords, scope)
        val service = RoomServerService(
            remoteNode,
            store,
            roomSession,
            passwords,
            scope,
            edges = RoomServerEdges(now = { NOW }),
        )
        var viewedCount = 0
        val controller = RoomConversationController(
            RoomConversationInputs(
                roomServerService = { service },
                scope = scope,
                fetchSession = { id -> store.fetchSession(id) },
                onViewed = { viewedCount++ },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )

        companion object {
            const val NOW = 1_720_000_000L
        }
    }

    private fun TestScope.fixture(): Fixture = Fixture(backgroundScope)

    @Test
    fun loadMessagesFetchesResetsUnreadAndFiresOnViewed() = runTest {
        val f = fixture()
        val session = roomSession(unreadCount = 3)
        f.store.seedSession(session)
        f.store.saveRoomMessage(roomMessage(testUuid(20), sessionID = sessionId, timestamp = 100))
        f.store.saveRoomMessage(roomMessage(testUuid(21), sessionID = sessionId, timestamp = 200))

        f.controller.loadMessages(session)

        assertEquals(2, f.controller.messages.size)
        assertTrue(f.controller.hasLoadedOnce)
        assertFalse(f.controller.isLoading)
        assertEquals(0, f.store.fetchSession(sessionId)!!.unreadCount)
        assertEquals(1, f.viewedCount)
    }

    @Test
    fun loadMessagesMarksUnseenFailedSendsSeen() = runTest {
        val f = fixture()
        val session = roomSession()
        f.store.seedSession(session)
        val failed = roomMessage(
            testUuid(30),
            sessionID = sessionId,
            timestamp = 100,
            isFromSelf = true,
            statusRawValue = MessageStatus.FAILED.raw,
        )
        f.store.saveRoomMessage(failed)

        f.controller.loadMessages(session)

        assertTrue(f.store.fetchRoomMessage(failed.id)!!.failureSeen)
    }

    @Test
    fun loadMessagesNilServiceMarksLoadedEmpty() = runTest {
        val controller = RoomConversationController(
            RoomConversationInputs(
                roomServerService = { null },
                scope = backgroundScope,
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        controller.loadMessages(roomSession())
        assertTrue(controller.hasLoadedOnce)
        assertFalse(controller.isLoading)
        assertTrue(controller.messages.isEmpty())
    }

    @Test
    fun sendMessagePostsAndAppends() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        f.controller.loadMessages(roomSession())

        f.controller.sendMessage("hi room")

        assertEquals(1, f.controller.messages.size)
        assertEquals("hi room", f.controller.messages.single().text)
        assertFalse(f.controller.isSending)
    }

    @Test
    fun sendMessageOverTheByteBudgetStashesAndDoesNotPost() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        f.controller.loadMessages(roomSession())
        val tooLong = "a".repeat(151)

        f.controller.sendMessage(tooLong)

        assertEquals(tooLong, f.controller.composingText)
        assertTrue(f.controller.messages.isEmpty())
    }

    @Test
    fun sendMessagePersistFaultClearsTheSpinnerAndSurfacesTheError() = runTest {
        val inner = InMemoryRoomStore()
        val store = object : RoomStore by inner {
            override suspend fun saveRoomMessage(dto: one.meshcore.shared.backup.RoomMessageDto) {
                error("db locked")
            }
        }
        val roomSession = FakeRoomServerSession()
        val passwords = InMemoryNodePasswordStore()
        val remoteNode = RemoteNodeService(FakeNodeSession(), inner, passwords, backgroundScope)
        val service =
            RoomServerService(
                remoteNode,
                store,
                roomSession,
                passwords,
                backgroundScope,
                edges = RoomServerEdges(now = { 1_720_000_000L }),
            )
        val controller = RoomConversationController(
            RoomConversationInputs(
                roomServerService = { service },
                scope = backgroundScope,
                fetchSession = { id -> store.fetchSession(id) },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        val session = roomSession()
        inner.seedSession(session)
        controller.loadMessages(session)

        controller.sendMessage("hi room")

        assertFalse(controller.isSending)
        assertTrue(controller.messages.isEmpty())
        assertEquals(ROOM_TEST_SEND_ERROR, controller.errorMessage)
    }

    @Test
    fun aSendFailureShowsSendFailedNotEnglish() = runTest {
        val inner = InMemoryRoomStore()
        val store = object : RoomStore by inner {
            override suspend fun saveRoomMessage(dto: one.meshcore.shared.backup.RoomMessageDto) {
                error("radio down")
            }
        }
        val roomSession = FakeRoomServerSession()
        val passwords = InMemoryNodePasswordStore()
        val remoteNode = RemoteNodeService(FakeNodeSession(), inner, passwords, backgroundScope)
        val service =
            RoomServerService(
                remoteNode,
                store,
                roomSession,
                passwords,
                backgroundScope,
                edges = RoomServerEdges(now = { 1_720_000_000L }),
            )
        val controller = RoomConversationController(
            RoomConversationInputs(
                roomServerService = { service },
                scope = backgroundScope,
                fetchSession = { id -> store.fetchSession(id) },
                sendFailedMessage = { "send fail" },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
            ),
        )
        val session = roomSession()
        inner.seedSession(session)
        controller.loadMessages(session)

        controller.sendMessage("hi room")

        assertEquals("send fail", controller.errorMessage)
        assertFalse(controller.isSending)
    }

    @Test
    fun loadMessagesFaultShowsLoadFailedNotEnglish() = runTest {
        val inner = InMemoryRoomStore()
        val store = object : RoomStore by inner {
            override suspend fun fetchRoomMessages(
                sessionID: one.meshcore.shared.model.Uuid,
                limit: Int?,
                offset: Int?,
            ) = error("radio down")
        }
        val roomSession = FakeRoomServerSession()
        val passwords = InMemoryNodePasswordStore()
        val remoteNode = RemoteNodeService(FakeNodeSession(), inner, passwords, backgroundScope)
        val service =
            RoomServerService(
                remoteNode,
                store,
                roomSession,
                passwords,
                backgroundScope,
                edges = RoomServerEdges(now = { 1_720_000_000L }),
            )
        val controller = RoomConversationController(
            RoomConversationInputs(
                roomServerService = { service },
                scope = backgroundScope,
                fetchSession = { id -> store.fetchSession(id) },
                loadFailedMessage = { "load fail" },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        val session = roomSession()
        inner.seedSession(session)

        controller.loadMessages(session)

        assertEquals("load fail", controller.errorMessage)
        assertTrue(controller.hasLoadedOnce)
        assertFalse(controller.isLoading)
    }

    @Test
    fun sendMessageEmptyOrDisconnectedStashesComposingText() = runTest {
        val f = fixture()
        // No session loaded yet → disconnected path stashes the text.
        f.controller.sendMessage("draft")
        assertEquals("draft", f.controller.composingText)
        assertTrue(f.controller.messages.isEmpty())
    }

    @Test
    fun retryMessageReplacesInPlace() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        val failed = roomMessage(
            testUuid(30),
            sessionID = sessionId,
            isFromSelf = true,
            statusRawValue = MessageStatus.FAILED.raw,
        )
        f.store.saveRoomMessage(failed)
        f.controller.loadMessages(roomSession())
        f.roomSession.sendResult = roomSentInfo()

        f.controller.retryMessage(failed.id)

        assertEquals(MessageStatus.DELIVERED.raw, f.controller.messages.single { it.id == failed.id }.statusRawValue)
    }

    @Test
    fun statusUpdatedForHeldMessageCoalescesReload() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        val held = roomMessage(testUuid(40), sessionID = sessionId, timestamp = 100)
        f.store.saveRoomMessage(held)
        f.controller.loadMessages(roomSession())
        f.viewedCount = 0
        // A new message lands in the store; the reload should pick it up.
        f.store.saveRoomMessage(roomMessage(testUuid(41), sessionID = sessionId, timestamp = 200))

        f.controller.handleServerEvent(RoomServerEvent.StatusUpdated(held.id, MessageStatus.SENT))
        f.controller.handleServerEvent(RoomServerEvent.StatusUpdated(held.id, MessageStatus.DELIVERED))
        advanceTimeBy(RoomConversationController.RELOAD_DEBOUNCE_MS)
        runCurrent()

        assertEquals(2, f.controller.messages.size)
        assertEquals(1, f.viewedCount)
    }

    @Test
    fun closeCancelsTheCoalescedReload() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        val held = roomMessage(testUuid(40), sessionID = sessionId, timestamp = 100)
        f.store.saveRoomMessage(held)
        f.controller.loadMessages(roomSession())
        f.viewedCount = 0
        f.store.saveRoomMessage(roomMessage(testUuid(41), sessionID = sessionId, timestamp = 200))
        f.controller.handleServerEvent(RoomServerEvent.StatusUpdated(held.id, MessageStatus.SENT))

        f.controller.close()
        advanceTimeBy(RoomConversationController.RELOAD_DEBOUNCE_MS)
        runCurrent()

        assertEquals(1, f.controller.messages.size)
        assertEquals(0, f.viewedCount)
    }

    @Test
    fun statusUpdatedForUnknownMessageDoesNotReload() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        f.store.saveRoomMessage(roomMessage(testUuid(50), sessionID = sessionId))
        f.controller.loadMessages(roomSession())
        f.viewedCount = 0

        f.controller.handleServerEvent(RoomServerEvent.StatusUpdated(testUuid(999), MessageStatus.SENT))
        advanceTimeBy(RoomConversationController.RELOAD_DEBOUNCE_MS)
        runCurrent()

        assertEquals(0, f.viewedCount)
    }

    @Test
    fun inThreadFailMarksFailedSendsSeenBeforeTheListReload() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        val failed = roomMessage(
            testUuid(60),
            sessionID = sessionId,
            timestamp = 100,
            isFromSelf = true,
            statusRawValue = MessageStatus.FAILED.raw,
        )
        f.store.saveRoomMessage(failed)
        f.controller.loadMessages(roomSession())
        f.store.saveRoomMessage(failed.copy(failureSeen = false))
        f.viewedCount = 0

        f.controller.handleServerEvent(RoomServerEvent.StatusUpdated(failed.id, MessageStatus.FAILED))
        runCurrent()

        assertTrue(f.store.fetchRoomMessage(failed.id)!!.failureSeen)
        assertEquals(1, f.viewedCount)
    }

    @Test
    fun refreshSessionPersistFaultKeepsTheOpenSession() = runTest {
        val session = roomSession(isConnected = false)
        val warnings = mutableListOf<String>()
        val controller = RoomConversationController(
            RoomConversationInputs(
                roomServerService = { null },
                scope = backgroundScope,
                fetchSession = { error("db locked") },
                logWarning = warnings::add,
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        controller.loadMessages(session)

        controller.refreshSession()

        assertEquals(session.id, controller.session?.id)
        assertFalse(controller.session!!.isConnected)
        assertEquals(listOf("$ROOM_SESSION_REFRESH_FAILED: db locked"), warnings)
    }

    @Test
    fun refreshSessionErrorLeavesTheOpenSession() = runTest {
        val session = roomSession(isConnected = false)
        val warnings = mutableListOf<String>()
        val fault = RoomSessionRefreshProbeFault()
        val controller = RoomConversationController(
            RoomConversationInputs(
                roomServerService = { null },
                scope = backgroundScope,
                fetchSession = { throw fault },
                logWarning = warnings::add,
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        controller.loadMessages(session)

        val thrown = assertFailsWith<RoomSessionRefreshProbeFault> {
            controller.refreshSession()
        }

        assertSame(fault, thrown)
        assertEquals(session.id, controller.session?.id)
        assertFalse(controller.session!!.isConnected)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun refreshSessionCancellationLeavesTheOpenSession() = runTest {
        val session = roomSession(isConnected = false)
        val warnings = mutableListOf<String>()
        val cancelled = CancellationException("left the room")
        val controller = RoomConversationController(
            RoomConversationInputs(
                roomServerService = { null },
                scope = backgroundScope,
                fetchSession = { throw cancelled },
                logWarning = warnings::add,
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        controller.loadMessages(session)

        val thrown = assertFailsWith<CancellationException> {
            controller.refreshSession()
        }

        assertSame(cancelled, thrown)
        assertEquals(session.id, controller.session?.id)
        assertFalse(controller.session!!.isConnected)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun connectionRecoveredRefreshesSession() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession(isConnected = false))
        f.controller.loadMessages(roomSession(isConnected = false))
        assertFalse(f.controller.session!!.isConnected)

        f.store.markRoomSessionConnected(sessionId)
        f.controller.handleServerEvent(RoomServerEvent.ConnectionRecovered(sessionId))

        assertTrue(f.controller.session!!.isConnected)
    }

    @Test
    fun onMessageReceivedAppendsAndReloads() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        f.controller.loadMessages(roomSession())

        val incoming = roomMessage(testUuid(60), sessionID = sessionId, timestamp = 300)
        f.store.saveRoomMessage(incoming)
        f.controller.onMessageReceived(incoming, sessionId)

        // Optimistic append is immediate.
        assertTrue(f.controller.messages.any { it.id == incoming.id })
    }

    @Test
    fun onMessageReceivedForOtherSessionIgnored() = runTest {
        val f = fixture()
        f.store.seedSession(roomSession())
        f.controller.loadMessages(roomSession())

        f.controller.onMessageReceived(roomMessage(testUuid(70)), testUuid(2))
        assertTrue(f.controller.messages.isEmpty())
    }

    @Test
    fun appendMessageIfNewInsertsInTimestampOrder() = runTest {
        val f = fixture()
        f.controller.appendMessageIfNew(roomMessage(testUuid(90), timestamp = 100))
        f.controller.appendMessageIfNew(roomMessage(testUuid(92), timestamp = 300))

        // Out-of-order arrival lands between, not at the tail.
        f.controller.appendMessageIfNew(roomMessage(testUuid(91), timestamp = 200))
        // Older-than-head arrival lands at the front.
        f.controller.appendMessageIfNew(roomMessage(testUuid(89), timestamp = 50))
        // Newest arrival lands at the tail.
        f.controller.appendMessageIfNew(roomMessage(testUuid(93), timestamp = 400))

        assertEquals(
            listOf(testUuid(89), testUuid(90), testUuid(91), testUuid(92), testUuid(93)),
            f.controller.messages.map { it.id },
        )
    }

    @Test
    fun appendMessageIfNewKeepsArrivalOrderForEqualTimestamps() = runTest {
        val f = fixture()
        f.controller.appendMessageIfNew(roomMessage(testUuid(100), timestamp = 100))
        f.controller.appendMessageIfNew(roomMessage(testUuid(101), timestamp = 100))
        f.controller.appendMessageIfNew(roomMessage(testUuid(102), timestamp = 100))

        assertEquals(
            listOf(testUuid(100), testUuid(101), testUuid(102)),
            f.controller.messages.map { it.id },
        )
    }

    @Test
    fun appendMessageIfNewDedupsById() = runTest {
        val f = fixture()
        f.controller.appendMessageIfNew(roomMessage(testUuid(110), timestamp = 100, text = "first"))
        f.controller.appendMessageIfNew(roomMessage(testUuid(110), timestamp = 999, text = "second"))

        assertEquals(1, f.controller.messages.size)
        assertEquals("first", f.controller.messages.single().text)
    }

    @Test
    fun shouldShowTimestampFirstAndOnGap() {
        val messages = listOf(
            roomMessage(testUuid(80), timestamp = 1000),
            roomMessage(testUuid(81), timestamp = 1200),
            roomMessage(testUuid(82), timestamp = 1600),
        )
        assertTrue(RoomConversationController.shouldShowTimestamp(0, messages))
        assertFalse(RoomConversationController.shouldShowTimestamp(1, messages))
        assertTrue(RoomConversationController.shouldShowTimestamp(2, messages))
    }

    @Test
    fun samePrefixBurstBooksNameOnFirstAndAvatarOnLast() {
        val alice = byteArrayOf(0xAA.toByte())
        val messages = listOf(
            roomMessage(testUuid(10), timestamp = 100, authorName = "Alice", authorKeyPrefix = alice),
            roomMessage(testUuid(11), timestamp = 160, authorName = "Alice", authorKeyPrefix = alice),
            roomMessage(testUuid(12), timestamp = 220, authorName = "Alice", authorKeyPrefix = alice),
        )
        val bookends = RoomConversationController.incomingBookends(messages)
        assertEquals(setOf(messages[0].id), bookends.nameIDs)
        assertEquals(setOf(messages[2].id), bookends.avatarIDs)
    }

    @Test
    fun differentPrefixesAreTwoClustersEvenWhenDisplayNamesMatch() {
        val messages = listOf(
            roomMessage(
                testUuid(10),
                timestamp = 100,
                authorName = "Alice",
                authorKeyPrefix = byteArrayOf(0xAA.toByte()),
            ),
            roomMessage(
                testUuid(11),
                timestamp = 160,
                authorName = "Alice",
                authorKeyPrefix = byteArrayOf(0xBB.toByte()),
            ),
        )
        val bookends = RoomConversationController.incomingBookends(messages)
        assertEquals(setOf(messages[0].id, messages[1].id), bookends.nameIDs)
        assertEquals(setOf(messages[0].id, messages[1].id), bookends.avatarIDs)
    }

    @Test
    fun selfMessagesNeverShowANameOrAvatar() {
        val prefix = byteArrayOf(0xAA.toByte())
        val messages = listOf(
            roomMessage(testUuid(10), timestamp = 100, authorName = "Me", isFromSelf = true, authorKeyPrefix = prefix),
            roomMessage(testUuid(11), timestamp = 160, authorName = "Me", isFromSelf = true, authorKeyPrefix = prefix),
        )
        val bookends = RoomConversationController.incomingBookends(messages)
        assertTrue(bookends.nameIDs.isEmpty())
        assertTrue(bookends.avatarIDs.isEmpty())
    }

    @Test
    fun threeHundredSecondsSamePrefixIsOneClusterThreeHundredOneIsTwo() {
        val prefix = byteArrayOf(0xAA.toByte())
        val a = roomMessage(testUuid(10), timestamp = 1000, authorName = "Alice", authorKeyPrefix = prefix)
        val at300 = roomMessage(testUuid(11), timestamp = 1300, authorName = "Alice", authorKeyPrefix = prefix)
        val at301 = roomMessage(testUuid(12), timestamp = 1301, authorName = "Alice", authorKeyPrefix = prefix)

        val clustered = RoomConversationController.incomingBookends(listOf(a, at300))
        assertEquals(setOf(a.id), clustered.nameIDs)
        assertEquals(setOf(at300.id), clustered.avatarIDs)

        val split = RoomConversationController.incomingBookends(listOf(a, at301))
        assertEquals(setOf(a.id, at301.id), split.nameIDs)
        assertEquals(setOf(a.id, at301.id), split.avatarIDs)
    }

    @Test
    fun selfMessageBreaksAnIncomingPrefixCluster() {
        val prefix = byteArrayOf(0xAA.toByte())
        val first = roomMessage(testUuid(10), timestamp = 100, authorName = "Alice", authorKeyPrefix = prefix)
        val me =
            roomMessage(
                testUuid(11),
                timestamp = 160,
                authorName = "Alice",
                isFromSelf = true,
                authorKeyPrefix = prefix,
            )
        val third = roomMessage(testUuid(12), timestamp = 220, authorName = "Alice", authorKeyPrefix = prefix)

        val bookends = RoomConversationController.incomingBookends(listOf(first, me, third))
        assertEquals(setOf(first.id, third.id), bookends.nameIDs)
        assertEquals(setOf(first.id, third.id), bookends.avatarIDs)
    }
}

private class RoomSessionRefreshProbeFault : Error("probe")
