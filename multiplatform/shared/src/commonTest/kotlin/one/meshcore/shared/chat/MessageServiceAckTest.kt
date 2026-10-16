package one.meshcore.shared.chat

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors the Swift `MessageServiceACKTests`: the expiry checker, ACK-to-delivered with RTT, the late-ACK
 * grace window, the terminal write guards, and the `finalizeSend` reconciliation branches. Each test
 * seeds `pendingAcks` directly and drives one method, as the Swift suite does.
 */
class MessageServiceAckTest {
    private val radioId = ChatFixtures.RADIO
    private val publicKey = ByteArray(ChatConstants.PUBLIC_KEY_SIZE_BYTES)

    private fun sentInfo(ack: ByteArray) = MessageSentInfo(route = 0, expectedAck = ack, suggestedTimeoutMs = 5000)

    private fun settlement(
        messageID: Uuid,
        contactID: Uuid = ChatFixtures.CONTACT,
        sentInfo: MessageSentInfo? = null,
    ): DirectSendSettlement = DirectSendSettlement(
        messageID = messageID,
        contactID = contactID,
        radioID = radioId,
        publicKey = publicKey,
        sentInfo = sentInfo,
        initialPathLength = 0,
    )

    // MARK: Expiry checker lifecycle

    @Test
    fun `isAckExpiryCheckingActive toggles`() = runTest {
        val service = testMessageService(FakeChatSession(), InMemoryMessageStore(), backgroundScope)
        assertFalse(service.isAckExpiryCheckingActive)
        service.startAckExpiryChecking()
        assertTrue(service.isAckExpiryCheckingActive)
        service.stopAckExpiryChecking()
        assertFalse(service.isAckExpiryCheckingActive)
    }

    // MARK: checkExpiredAcks

    @Test
    fun `checkExpiredAcks marks expired ACK as failed`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.SENT))
        val events = service.statusEvents()
        service.setPendingAck(pendingAck(id, ageSeconds = 60.0, timeoutSeconds = 30.0))

        service.checkExpiredAcks()

        assertEquals(MessageStatus.FAILED, store.fetchMessage(id)?.status)
        assertContains(events.drain().failedIds(), id)
    }

    @Test
    fun `ACK timeout keeps message sent through the grace window`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(
            FakeChatSession(),
            store,
            backgroundScope,
            config = MessageServiceConfig(ackGiveUpWindowSeconds = 45.0),
        )
        val id = ChatFixtures.freshUuid()
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.SENT))
        val events = service.statusEvents()
        service.setPendingAck(pendingAck(id, ageSeconds = 31.0, timeoutSeconds = 30.0))

        service.checkExpiredAcks()

        assertEquals(MessageStatus.SENT, store.fetchMessage(id)?.status)
        assertEquals(1, service.pendingAckCount())
        val drained = events.drain()
        assertFalse(drained.failedIds().contains(id))
        assertTrue(drained.retryingEvents().isEmpty())
    }

    @Test
    fun `checkExpiredAcks preserves non-expired ACK`() = runTest {
        val service = testMessageService(FakeChatSession(), InMemoryMessageStore(), backgroundScope)
        service.setPendingAck(pendingAck(ChatFixtures.freshUuid(), ageSeconds = 0.0, timeoutSeconds = 30.0))
        service.checkExpiredAcks()
        assertEquals(1, service.pendingAckCount())
    }

    @Test
    fun `checkExpiredAcks skips already-delivered ACK`() = runTest {
        val service = testMessageService(FakeChatSession(), InMemoryMessageStore(), backgroundScope)
        service.setPendingAck(pendingAck(ChatFixtures.freshUuid(), ageSeconds = 60.0, isDelivered = true))
        service.checkExpiredAcks()
        assertEquals(1, service.pendingAckCount())
    }

    @Test
    fun `checkExpiredAcks does not broadcast failure when DB stays delivered`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        val ack = ackBytes(0xCE, 0xEC, 0xAC, 0xCE)
        store.saveMessage(
            ChatFixtures.message(id = id, status = MessageStatus.DELIVERED).copy(ackCode = ack.ackCodeLeUInt32()),
        )
        val events = service.statusEvents()
        service.setPendingAck(pendingAck(id, codes = setOf(ack.ackCodeLeUInt32()), ageSeconds = 60.0))

        service.checkExpiredAcks()

        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(id)?.status)
        assertFalse(events.drain().failedIds().contains(id))
    }

    @Test
    fun `checkExpiredAcks fails a DM after the window floor, keeping one inside it`() = runTest {
        val window = 20.0
        val store = InMemoryMessageStore()
        val service = testMessageService(
            FakeChatSession(),
            store,
            backgroundScope,
            config = MessageServiceConfig(ackGiveUpWindowSeconds = window),
        )
        val surviving = ChatFixtures.freshUuid()
        val expired = ChatFixtures.freshUuid()
        store.saveMessage(ChatFixtures.message(id = surviving, status = MessageStatus.SENT))
        store.saveMessage(ChatFixtures.message(id = expired, status = MessageStatus.SENT))
        service.setPendingAck(pendingAck(surviving, ageSeconds = window - 5, timeoutSeconds = 5.0))
        service.setPendingAck(pendingAck(expired, ageSeconds = window + 5, timeoutSeconds = 5.0))

        service.checkExpiredAcks()

        assertEquals(MessageStatus.SENT, store.fetchMessage(surviving)?.status)
        assertEquals(MessageStatus.FAILED, store.fetchMessage(expired)?.status)
    }

    @Test
    fun `checkExpiredAcks honors a per-attempt timeout longer than the window`() = runTest {
        val window = 20.0
        val longTimeout = 60.0
        val store = InMemoryMessageStore()
        val service = testMessageService(
            FakeChatSession(),
            store,
            backgroundScope,
            config = MessageServiceConfig(ackGiveUpWindowSeconds = window),
        )
        val waiting = ChatFixtures.freshUuid()
        val expired = ChatFixtures.freshUuid()
        store.saveMessage(ChatFixtures.message(id = waiting, status = MessageStatus.SENT))
        store.saveMessage(ChatFixtures.message(id = expired, status = MessageStatus.SENT))
        service.setPendingAck(pendingAck(waiting, ageSeconds = window + 10, timeoutSeconds = longTimeout))
        service.setPendingAck(pendingAck(expired, ageSeconds = longTimeout + 10, timeoutSeconds = longTimeout))

        service.checkExpiredAcks()

        assertEquals(MessageStatus.SENT, store.fetchMessage(waiting)?.status)
        assertEquals(MessageStatus.FAILED, store.fetchMessage(expired)?.status)
    }

    @Test
    fun `stopAckExpiryChecking leaves in-flight DMs sent`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.SENT))
        val events = service.statusEvents()
        service.setPendingAck(pendingAck(id))
        service.startAckExpiryChecking()

        service.stopAckExpiryChecking()

        assertFalse(service.isAckExpiryCheckingActive)
        assertEquals(MessageStatus.SENT, store.fetchMessage(id)?.status)
        assertEquals(1, service.pendingAckCount())
        assertTrue(events.drain().failedIds().isEmpty())
    }

    // MARK: failAllPendingMessages / stopAndFailAllPending

    @Test
    fun `failAllPendingMessages fails all non-delivered and broadcasts`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val a = ChatFixtures.freshUuid()
        val b = ChatFixtures.freshUuid()
        store.saveMessage(ChatFixtures.message(id = a, status = MessageStatus.SENT))
        store.saveMessage(ChatFixtures.message(id = b, status = MessageStatus.SENT))
        val events = service.statusEvents()
        service.setPendingAck(pendingAck(a, codes = setOf(1L)))
        service.setPendingAck(pendingAck(b, codes = setOf(2L)))

        service.failAllPendingMessages()

        assertEquals(MessageStatus.FAILED, store.fetchMessage(a)?.status)
        assertEquals(MessageStatus.FAILED, store.fetchMessage(b)?.status)
        val failed = events.drain().failedIds()
        assertEquals(2, failed.size)
        assertContains(failed, a)
        assertContains(failed, b)
    }

    @Test
    fun `failAllPendingMessages does not downgrade or notify a delivered row`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        val ack = ackBytes(0xFA, 0x11, 0x77, 0x33)
        store.saveMessage(
            ChatFixtures.message(id = id, status = MessageStatus.DELIVERED).copy(ackCode = ack.ackCodeLeUInt32()),
        )
        val events = service.statusEvents()
        service.setPendingAck(pendingAck(id, codes = setOf(ack.ackCodeLeUInt32()), isDelivered = false))

        service.failAllPendingMessages()

        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(id)?.status)
        assertFalse(events.drain().failedIds().contains(id))
    }

    @Test
    fun `stopAndFailAllPending stops checking and fails all pending`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.SENT))
        service.startAckExpiryChecking()
        service.setPendingAck(pendingAck(id))

        service.stopAndFailAllPending()

        assertFalse(service.isAckExpiryCheckingActive)
        assertEquals(MessageStatus.FAILED, store.fetchMessage(id)?.status)
    }

    // MARK: handleAcknowledgement

    @Test
    fun `handleAcknowledgement uses firmware tripTime when provided`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        val ack = ackBytes(0xEF, 0xBE, 0xAD, 0xDE)
        store.saveMessage(
            ChatFixtures.message(id = id, status = MessageStatus.SENT).copy(ackCode = ack.ackCodeLeUInt32()),
        )
        service.setPendingAck(pendingAck(id, codes = setOf(ack.ackCodeLeUInt32()), ageSeconds = 10.0))

        service.handleAcknowledgement(ack, tripTime = 250)

        val fetched = store.fetchMessage(id)
        assertEquals(MessageStatus.DELIVERED, fetched?.status)
        assertEquals(250L, fetched?.roundTripTime)
    }

    @Test
    fun `handleAcknowledgement leaves roundTripTime null when firmware omits tripTime`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        val ack = ackBytes(0xBE, 0xBA, 0xFE, 0xCA)
        store.saveMessage(
            ChatFixtures.message(id = id, status = MessageStatus.SENT).copy(ackCode = ack.ackCodeLeUInt32()),
        )
        service.setPendingAck(pendingAck(id, codes = setOf(ack.ackCodeLeUInt32()), ageSeconds = 2.0))

        service.handleAcknowledgement(ack, tripTime = null)

        val fetched = store.fetchMessage(id)
        assertEquals(MessageStatus.DELIVERED, fetched?.status)
        assertNull(fetched?.roundTripTime)
    }

    @Test
    fun `handleAcknowledgement matches any CRC accumulated across retry attempts`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.PENDING))
        val attempt0 = ackBytes(0xAA, 0xAA, 0xAA, 0xAA)
        val codes = setOf(attempt0.ackCodeLeUInt32(), 0xBBBBBBBBL, 0xCCCCCCCCL)
        service.setPendingAck(pendingAck(id, codes = codes))

        service.handleAcknowledgement(attempt0, tripTime = 500)

        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(id)?.status)
        assertEquals(500L, store.fetchMessage(id)?.roundTripTime)
        assertEquals(0, service.pendingAckCount())
    }

    @Test
    fun `handleAcknowledgement updates contact lastMessageDate on late ACK`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        val contactId = ChatFixtures.CONTACT
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.PENDING, contactID = contactId))
        val ack = ackBytes(0x11, 0x22, 0x33, 0x44)
        service.setPendingAck(pendingAck(id, contactID = contactId, codes = setOf(ack.ackCodeLeUInt32())))

        service.handleAcknowledgement(ack, tripTime = 500)

        assertEquals(TEST_NOW_SECONDS, store.contactLastMessageDate(contactId))
    }

    @Test
    fun `handleAcknowledgement is a no-op when no entry matches`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.SENT))

        service.handleAcknowledgement(ackBytes(0xDE, 0xAD, 0xBE, 0xEF), tripTime = 100)

        assertEquals(MessageStatus.SENT, store.fetchMessage(id)?.status)
        assertEquals(0, service.pendingAckCount())
    }

    @Test
    fun `trackPendingAck on retry resets sentAt so checkExpiredAcks preserves a retrying message`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.SENT))
        service.setPendingAck(pendingAck(id, codes = setOf(0xAAAAAAAAL), ageSeconds = 60.0, timeoutSeconds = 30.0))

        service.trackPendingAck(id, ChatFixtures.CONTACT, ackBytes(0xBB, 0xBB, 0xBB, 0xBB), timeoutSeconds = 30.0)
        service.checkExpiredAcks()

        assertEquals(1, service.pendingAckCount())
        assertTrue(store.fetchMessage(id)?.status != MessageStatus.FAILED)
    }

    @Test
    fun `late ACK in the checker await-gap cannot flip a just-failed row to delivered`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        val ack = ackBytes(0x9A, 0xBC, 0xDE, 0xF0)
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.SENT))
        service.setPendingAck(pendingAck(id, codes = setOf(ack.ackCodeLeUInt32())))
        // The checker wrote .failed but its entry is still present (suspended before removeValue).
        store.updateMessageStatusUnlessDelivered(id, MessageStatus.FAILED)

        service.handleAcknowledgement(ack, tripTime = 200)

        assertEquals(MessageStatus.FAILED, store.fetchMessage(id)?.status)
    }

    @Test
    fun `late ACK after the row is already failed with no live entry stays failed`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        val ack = ackBytes(0x01, 0xEF, 0xCD, 0xAB)
        store.saveMessage(
            ChatFixtures.message(id = id, status = MessageStatus.FAILED).copy(ackCode = ack.ackCodeLeUInt32()),
        )

        service.handleAcknowledgement(ack, tripTime = 300)

        assertEquals(MessageStatus.FAILED, store.fetchMessage(id)?.status)
        assertEquals(0, service.pendingAckCount())
    }

    @Test
    fun `orphan ACK after reconnect leaves a sent DM sent`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        val contactId = ChatFixtures.CONTACT
        val ack = ackBytes(0xCA, 0xFE, 0xBA, 0xBE)
        store.saveMessage(
            ChatFixtures.message(id = id, contactID = contactId, status = MessageStatus.SENT)
                .copy(ackCode = ack.ackCodeLeUInt32()),
        )
        val events = service.statusEvents()
        // No live pending entry — a new MessageService after tearDown dropped the map.

        service.handleAcknowledgement(ack, tripTime = 150)

        assertEquals(MessageStatus.SENT, store.fetchMessage(id)?.status)
        assertEquals(0, service.pendingAckCount())
        assertTrue(events.drain().isEmpty())
    }

    @Test
    fun `starting the ACK listener tracks nothing from the store`() = runTest {
        val store = InMemoryMessageStore()
        val session = FakeChatSession()
        val service = testMessageService(session, store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        val ack = ackBytes(0x11, 0x22, 0x33, 0x44)
        // A stored `.sent` DM from an earlier connection, older than any give-up window.
        store.saveMessage(
            ChatFixtures.message(
                id = id,
                status = MessageStatus.SENT,
                createdAt = ChatFixtures.BASE_EPOCH_SECONDS - HISTORICAL_AGE_SECONDS,
            ).copy(ackCode = ack.ackCodeLeUInt32()),
        )

        service.startEventMonitoring()
        testScheduler.runCurrent()
        service.checkExpiredAcks()

        assertEquals(0, service.pendingAckCount())
        assertEquals(MessageStatus.SENT, store.fetchMessage(id)?.status)
    }

    @Test
    fun `ACK within the grace window reconciles sent to delivered`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(
            FakeChatSession(),
            store,
            backgroundScope,
            config = MessageServiceConfig(ackGiveUpWindowSeconds = 45.0),
        )
        val id = ChatFixtures.freshUuid()
        val ack = ackBytes(0xDE, 0xAD, 0xBE, 0xEF)
        store.saveMessage(
            ChatFixtures.message(id = id, status = MessageStatus.SENT).copy(ackCode = ack.ackCodeLeUInt32()),
        )
        service.setPendingAck(
            pendingAck(id, codes = setOf(ack.ackCodeLeUInt32()), ageSeconds = 31.0, timeoutSeconds = 30.0),
        )

        service.checkExpiredAcks()
        assertEquals(MessageStatus.SENT, store.fetchMessage(id)?.status)

        service.handleAcknowledgement(ack, tripTime = 99)
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(id)?.status)
    }

    // MARK: finalizeSend

    @Test
    fun `finalizeSend delivered branch writes delivered, updates contact, yields, removes`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        val contactId = ChatFixtures.CONTACT
        val ack = ackBytes(0x10, 0x20, 0x30, 0x40)
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.SENT, contactID = contactId))
        service.setPendingAck(pendingAck(id, contactID = contactId, codes = setOf(ack.ackCodeLeUInt32())))
        val events = service.statusEvents()

        service.finalizeSend(settlement(id, contactId, sentInfo(ack)))

        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(id)?.status)
        assertNotNull(store.contactLastMessageDate(contactId))
        assertEquals(0, service.pendingAckCount())
        assertEquals(listOf(id to MessageStatus.DELIVERED), events.drain().resolvedTo())
    }

    @Test
    fun `finalizeSend preserves roundTripTime after listener-won delivery`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        val contactId = ChatFixtures.CONTACT
        val ack = ackBytes(0xAA, 0xBB, 0xCC, 0xDD)
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.SENT, contactID = contactId))
        service.setPendingAck(pendingAck(id, contactID = contactId, codes = setOf(ack.ackCodeLeUInt32())))

        // Listener path fires first: writes .delivered + RTT=500 and removes the entry.
        service.handleAcknowledgement(ack, tripTime = 500)
        assertEquals(500L, store.fetchMessage(id)?.roundTripTime)

        service.finalizeSend(settlement(id, contactId, sentInfo(ack)))

        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(id)?.status)
        assertEquals(500L, store.fetchMessage(id)?.roundTripTime)
    }

    @Test
    fun `finalizeSend nil branch skips the DB write when the listener already delivered`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        val ack = ackBytes(0x55, 0x66, 0x77, 0x88)
        store.saveMessage(
            ChatFixtures.message(id = id, status = MessageStatus.DELIVERED).copy(ackCode = ack.ackCodeLeUInt32()),
        )
        service.setPendingAck(pendingAck(id, codes = setOf(ack.ackCodeLeUInt32()), isDelivered = true))
        val events = service.statusEvents()

        service.finalizeSend(settlement(id))

        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(id)?.status)
        val drained = events.drain()
        assertTrue(drained.failedIds().isEmpty())
        assertTrue(drained.resolvedTo().isEmpty())
    }

    @Test
    fun `finalizeSend nil branch does not re-stamp the pending entry sentAt`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        val originalSentAt = TEST_NOW_SECONDS - 12.0
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.SENT))
        service.setPendingAck(pendingAck(id, ageSeconds = 12.0, timeoutSeconds = 30.0))

        service.finalizeSend(settlement(id))

        val entry = service.peekPendingAck(id)
        assertNotNull(entry)
        assertEquals(originalSentAt, entry.sentAtEpochSeconds)
    }

    @Test
    fun `finalizeSend nil branch over an already-failed row leaves it failed`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.FAILED))
        service.setPendingAck(pendingAck(id))
        val events = service.statusEvents()

        service.finalizeSend(settlement(id))

        assertEquals(MessageStatus.FAILED, store.fetchMessage(id)?.status)
        val drained = events.drain()
        assertTrue(drained.resolvedTo().isEmpty())
        assertTrue(drained.failedIds().isEmpty())
    }

    @Test
    fun `finalizeSend nil branch yields statusResolved sent from a non-terminal row`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.RETRYING))
        service.setPendingAck(pendingAck(id))
        val events = service.statusEvents()

        service.finalizeSend(settlement(id))

        assertEquals(MessageStatus.SENT, store.fetchMessage(id)?.status)
        assertEquals(listOf(id to MessageStatus.SENT), events.drain().resolvedTo())
    }

    @Test
    fun `finalizeSend broadcasts statusResolved when the in-loop wait wins the ACK race`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        val ack = ackBytes(0x11, 0x22, 0x33, 0x44)
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.SENT))
        service.setPendingAck(pendingAck(id, codes = setOf(ack.ackCodeLeUInt32())))
        val events = service.statusEvents()

        service.finalizeSend(settlement(id, sentInfo = sentInfo(ack)))

        assertEquals(listOf(id to MessageStatus.DELIVERED), events.drain().resolvedTo())
    }

    // MARK: failMessageAndRethrow / pendingAckCount

    @Test
    fun `failMessageAndRethrow removes pendingAcks entry and rethrows`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.PENDING))
        service.setPendingAck(pendingAck(id))
        assertEquals(1, service.pendingAckCount())

        assertFailsWith<MessageServiceException.SessionError> {
            service.failMessageAndRethrow(ChatDeviceException(FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL), id)
        }

        assertEquals(0, service.pendingAckCount())
        assertEquals(MessageStatus.FAILED, store.fetchMessage(id)?.status)
    }

    @Test
    fun `failMessageAndRethrow does not downgrade a delivered row`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.DELIVERED))
        service.setPendingAck(pendingAck(id, isDelivered = true))

        assertFailsWith<MessageServiceException> {
            service.failMessageAndRethrow(ChatDeviceException(FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL), id)
        }

        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(id)?.status)
    }

    @Test
    fun `pendingAckCount reflects the tracked count`() = runTest {
        val service = testMessageService(FakeChatSession(), InMemoryMessageStore(), backgroundScope)
        assertEquals(0, service.pendingAckCount())
        service.setPendingAck(pendingAck(ChatFixtures.freshUuid(), codes = setOf(1L)))
        assertEquals(1, service.pendingAckCount())
        service.setPendingAck(pendingAck(ChatFixtures.freshUuid(), codes = setOf(2L)))
        assertEquals(2, service.pendingAckCount())
    }

    private companion object {
        /** Older than any give-up window, so a sweep would fail the row on its first pass. */
        const val HISTORICAL_AGE_SECONDS = 3600.0
    }
}
