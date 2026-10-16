package one.meshcore.shared.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Mirrors the Swift `MessageServiceSendTests`: DM/channel validation, optimistic create, the retry-loop
 * budget and direct->flood escalation, retry exhaustion leaving `.sent` (never premature `.failed`), and
 * the resend send-count/broadcast bookkeeping.
 */
class MessageServiceSendTest {
    private val radioId = ChatFixtures.RADIO

    private fun contact(type: ContactType = ContactType.CHAT, outPathLength: Int = 0): ContactDto =
        ChatFixtures.contact(name = "Alice", id = ChatFixtures.CONTACT)
            .copy(typeRawValue = type.raw, outPathLength = outPathLength)

    // MARK: DM validation + optimistic create

    @Test
    fun `sendDirectMessage throws invalidRecipient for a repeater`() = runTest {
        val service = testMessageService(FakeChatSession(), InMemoryMessageStore(), backgroundScope)
        assertFailsWith<MessageServiceException.InvalidRecipient> {
            service.sendDirectMessage("Hello", contact(type = ContactType.REPEATER))
        }
    }

    @Test
    fun `sendDirectMessage throws messageTooLong for oversized text`() = runTest {
        val service = testMessageService(FakeChatSession(), InMemoryMessageStore(), backgroundScope)
        val long = "a".repeat(ChatConstants.MAX_DIRECT_MESSAGE_LENGTH_BYTES + 1)
        assertFailsWith<MessageServiceException.MessageTooLong> { service.sendDirectMessage(long, contact()) }
    }

    @Test
    fun `sendDirectMessage saves the message before the send attempt`() = runTest {
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { directSendError = { RuntimeException("dropped") } }
        val ids = SequentialIds()
        val service = testMessageService(session, store, backgroundScope, ids = ids)
        val expectedId = SequentialIds().next() // ids issues the same first value below

        runCatching { service.sendDirectMessage("Hello", contact()) }

        val saved = store.fetchMessage(expectedId)
        assertNotNull(saved)
        assertEquals("Hello", saved.text)
        assertEquals(MessageDirection.OUTGOING, saved.direction)
        assertEquals(MessageStatus.FAILED, saved.status)
    }

    @Test
    fun `createPendingMessage saves pending and stamps the contact last-message date`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)

        val message = service.createPendingMessage("First DM", contact())

        assertEquals(MessageStatus.PENDING, message.status)
        assertEquals(MessageDirection.OUTGOING, message.direction)
        assertEquals(MessageStatus.PENDING, store.fetchMessage(message.id)?.status)
        assertNotNull(store.contactLastMessageDate(ChatFixtures.CONTACT))
    }

    @Test
    fun `createPendingMessage throws for a repeater and for oversized text`() = runTest {
        val service = testMessageService(FakeChatSession(), InMemoryMessageStore(), backgroundScope)
        assertFailsWith<MessageServiceException.InvalidRecipient> {
            service.createPendingMessage("hi", contact(type = ContactType.REPEATER))
        }
        val long = "a".repeat(ChatConstants.MAX_DIRECT_MESSAGE_LENGTH_BYTES + 1)
        assertFailsWith<MessageServiceException.MessageTooLong> { service.createPendingMessage(long, contact()) }
    }

    @Test
    fun `sendPendingDirectMessage rejects a concurrent send for the same messageID`() = runTest {
        val service = testMessageService(FakeChatSession(), InMemoryMessageStore(), backgroundScope)
        val id = ChatFixtures.freshUuid()
        service.insertInFlightRetry(id)

        val error = assertFailsWith<MessageServiceException.SendFailed> {
            service.sendPendingDirectMessage(id, contact())
        }
        assertContains(error.reason, "in progress")
    }

    @Test
    fun `sendPendingDirectMessage throws when the message is not found`() = runTest {
        val service = testMessageService(FakeChatSession(), InMemoryMessageStore(), backgroundScope)
        assertFailsWith<MessageServiceException.SendFailed> {
            service.sendPendingDirectMessage(ChatFixtures.freshUuid(), contact())
        }
    }

    @Test
    fun `sendDirectMessage tracks the pending ACK before the session send`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val session = FakeChatSession().apply { directSendGate = gate }
        val service = testMessageService(session, InMemoryMessageStore(), backgroundScope)

        val job = backgroundScope.launch { runCatching { service.sendDirectMessage("hi", contact()) } }
        testScheduler.runCurrent()

        assertEquals(1, service.pendingAckCount())
        gate.complete(Unit)
        job.cancel()
    }

    // MARK: DM retry loop

    @Test
    fun `the retry loop sends exactly maxAttempts times under sustained non-ACK`() = runTest {
        val session = FakeChatSession().apply { ackArrives = false }
        val service = testMessageService(
            session,
            InMemoryMessageStore(),
            backgroundScope,
            config = MessageServiceConfig(maxAttempts = 2, floodAfter = 5),
        )

        val sent = service.sendMessageWithRetry("count me", contact())

        assertEquals(MessageStatus.SENT, sent.status)
        assertEquals(listOf(0, 1), session.directSendAttempts)
    }

    @Test
    fun `the retry loop escalates to flood after exactly floodAfter direct failures`() = runTest {
        val session = FakeChatSession().apply { ackArrives = false }
        val floodAfter = 2
        val service = testMessageService(
            session,
            InMemoryMessageStore(),
            backgroundScope,
            config = MessageServiceConfig(maxAttempts = 3, floodAfter = floodAfter),
        )

        service.sendMessageWithRetry("escalate me", contact())

        assertEquals(1, session.resetPathCalls)
        assertEquals(floodAfter, session.directSendsBeforeReset)
    }

    @Test
    fun `retry exhaustion leaves the DM sent with its pending entry alive, never prematurely failed`() = runTest {
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { ackArrives = false }
        val service = testMessageService(
            session,
            store,
            backgroundScope,
            config = MessageServiceConfig(maxAttempts = 2, floodAfter = 5),
        )
        val events = service.statusEvents()

        val sent = service.sendMessageWithRetry("exhaust me", contact())

        assertEquals(MessageStatus.SENT, sent.status)
        assertEquals(MessageStatus.SENT, store.fetchMessage(sent.id)?.status)
        assertEquals(1, service.pendingAckCount())
        val drained = events.drain()
        assertFalse(drained.failedIds().contains(sent.id))
        assertTrue(drained.retryingEvents().isNotEmpty())
        assertTrue(drained.resolvedTo().contains(sent.id to MessageStatus.SENT))
    }

    @Test
    fun `sendPendingDirectMessage cancel mid-send writes failed without a Failed event`() = runTest {
        val store = InMemoryMessageStore()
        val gate = CompletableDeferred<Unit>()
        val session = FakeChatSession().apply { directSendGate = gate }
        val service = testMessageService(session, store, backgroundScope)
        val pending = service.createPendingMessage("queued", contact())
        val events = service.statusEvents()

        val job = backgroundScope.launch {
            service.sendPendingDirectMessage(pending.id, contact())
        }
        testScheduler.runCurrent()
        job.cancel()
        job.join()

        assertEquals(MessageStatus.FAILED, store.fetchMessage(pending.id)?.status)
        assertFalse(events.drain().failedIds().contains(pending.id))
    }

    @Test
    fun `sendMessageWithRetry cancel mid-send writes failed and yields Failed`() = runTest {
        val store = InMemoryMessageStore()
        val gate = CompletableDeferred<Unit>()
        val session = FakeChatSession().apply { directSendGate = gate }
        val ids = SequentialIds()
        val service = testMessageService(session, store, backgroundScope, ids = ids)
        val expectedId = SequentialIds().next()
        val events = service.statusEvents()

        val job = backgroundScope.launch {
            service.sendMessageWithRetry("inline", contact())
        }
        testScheduler.runCurrent()
        job.cancel()
        job.join()

        assertEquals(MessageStatus.FAILED, store.fetchMessage(expectedId)?.status)
        assertTrue(events.drain().failedIds().contains(expectedId))
    }

    @Test
    fun `a genuine send exception still fails the DM through the outer catch`() = runTest {
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { directSendError = { RuntimeException("transport drop") } }
        val service = testMessageService(session, store, backgroundScope)

        assertFailsWith<RuntimeException> { service.sendMessageWithRetry("boom", contact()) }

        val stored = store.fetchMessage(SequentialIds().next())
        assertEquals(MessageStatus.FAILED, stored?.status)
    }

    @Test
    fun `finalizeSend exhaustion does not downgrade a delivered row`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.DELIVERED))
        service.setPendingAck(pendingAck(id, isDelivered = false))

        service.finalizeSend(
            DirectSendSettlement(
                messageID = id,
                contactID = ChatFixtures.CONTACT,
                radioID = radioId,
                publicKey = ByteArray(ChatConstants.PUBLIC_KEY_SIZE_BYTES),
                sentInfo = null,
                initialPathLength = 0,
            ),
        )

        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(id)?.status)
    }

    // MARK: Resend bookkeeping

    @Test
    fun `resendDirectMessage increments sendCount and broadcasts resent on a successful resend`() = runTest {
        val store = InMemoryMessageStore()
        val session = FakeChatSession()
        val service = testMessageService(session, store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        store.saveMessage(
            ChatFixtures.message(id = id, status = MessageStatus.DELIVERED, contactID = ChatFixtures.CONTACT)
                .copy(sendCount = 1),
        )
        // Pre-populate a delivered entry so the loop short-circuits after the send returns.
        service.setPendingAck(pendingAck(id, isDelivered = true))
        val events = service.statusEvents()

        service.resendDirectMessage(id, contact())

        assertEquals(2, store.fetchMessage(id)?.sendCount)
        assertEquals(listOf(id), events.drain().resentIds())
    }

    @Test
    fun `sendPendingDirectMessage does not bump sendCount or broadcast resent on first send`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        store.saveMessage(
            ChatFixtures.message(id = id, status = MessageStatus.PENDING, contactID = ChatFixtures.CONTACT)
                .copy(sendCount = 1),
        )
        service.setPendingAck(pendingAck(id, isDelivered = true))
        val events = service.statusEvents()

        service.sendPendingDirectMessage(id, contact())

        assertEquals(1, store.fetchMessage(id)?.sendCount)
        assertTrue(events.drain().resentIds().isEmpty())
    }

    // MARK: Channel send

    @Test
    fun `sendChannelMessage throws messageTooLong for oversized text`() = runTest {
        val service = testMessageService(FakeChatSession(), InMemoryMessageStore(), backgroundScope)
        val long = "a".repeat(ChatConstants.MAX_CHANNEL_MESSAGE_TOTAL_LENGTH_BYTES + 1)
        assertFailsWith<MessageServiceException.MessageTooLong> { service.sendChannelMessage(long, 0, radioId) }
    }

    @Test
    fun `sendChannelMessage throws messageTooLong when composed name plus text exceeds the cap`() = runTest {
        val store = InMemoryMessageStore()
        store.seedDeviceNodeName(radioId, "Node")
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val maxUser = ChatConstants.maxChannelMessageLength("Node")
        val long = "a".repeat(maxUser + 1)
        assertFailsWith<MessageServiceException.MessageTooLong> {
            service.sendChannelMessage(long, 0, radioId)
        }
        service.sendChannelMessage("a".repeat(maxUser), 0, radioId)
    }

    @Test
    fun `sendChannelMessage marks failed after a send error`() = runTest {
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { directSendError = { RuntimeException("dropped") } }
        val service = testMessageService(session, store, backgroundScope)

        runCatching { service.sendChannelMessage("Hello channel", 0, radioId) }

        val stored = store.fetchMessage(SequentialIds().next())
        assertEquals(MessageStatus.FAILED, stored?.status)
    }

    @Test
    fun `createPendingChannelMessage uses the Device node name for the cap`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val nodeName = "Node"
        val atCap = "a".repeat(ChatConstants.maxChannelMessageLength(nodeName))
        assertFailsWith<MessageServiceException.MessageTooLong> {
            service.createPendingChannelMessage(atCap, 0, radioId)
        }
        store.seedDeviceNodeName(radioId, nodeName)
        service.createPendingChannelMessage(atCap, 0, radioId)
    }

    @Test
    fun `channel send last gate ignores a missing Device name and uses the 31-byte worst case`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        val worstCaseCap = ChatConstants.maxChannelMessageLength(null)
        service.sendChannelMessage("a".repeat(worstCaseCap), 0, radioId)
        assertFailsWith<MessageServiceException.MessageTooLong> {
            service.sendChannelMessage("a".repeat(worstCaseCap + 1), 0, radioId)
        }
    }

    @Test
    fun `createPendingChannelMessage saves with pending status`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)

        val message = service.createPendingChannelMessage("Hello channel", 0, radioId)

        assertEquals(MessageStatus.PENDING, message.status)
        assertEquals(0, message.channelIndex)
        assertEquals(MessageStatus.PENDING, store.fetchMessage(message.id)?.status)
    }

    @Test
    fun `sendPendingChannelMessage throws when the message is not found`() = runTest {
        val service = testMessageService(FakeChatSession(), InMemoryMessageStore(), backgroundScope)
        assertFailsWith<MessageServiceException.SendFailed> {
            service.sendPendingChannelMessage(ChatFixtures.freshUuid())
        }
    }

    @Test
    fun `sendPendingChannelMessage sets failed status on a send error`() = runTest {
        val store = InMemoryMessageStore()
        val session = FakeChatSession().apply { directSendError = { RuntimeException("dropped") } }
        val service = testMessageService(session, store, backgroundScope)
        val message = service.createPendingChannelMessage("Hello channel", 0, radioId)

        runCatching { service.sendPendingChannelMessage(message.id) }

        assertEquals(MessageStatus.FAILED, store.fetchMessage(message.id)?.status)
    }

    @Test
    fun `resendChannelMessage throws when not found and when not a channel message`() = runTest {
        val store = InMemoryMessageStore()
        val service = testMessageService(FakeChatSession(), store, backgroundScope)
        assertFailsWith<MessageServiceException.SendFailed> { service.resendChannelMessage(ChatFixtures.freshUuid()) }

        val dmId = ChatFixtures.freshUuid()
        store.saveMessage(ChatFixtures.message(id = dmId, contactID = ChatFixtures.CONTACT, channelIndex = null))
        assertFailsWith<MessageServiceException.SendFailed> { service.resendChannelMessage(dmId) }
    }

    @Test
    fun `resendChannelMessage writes sent before broadcasting resent and refreshes counts`() = runTest {
        val store = InMemoryMessageStore()
        val session = FakeChatSession()
        val service = testMessageService(session, store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        store.saveMessage(
            ChatFixtures.message(id = id, status = MessageStatus.FAILED, contactID = null, channelIndex = 0)
                .copy(heardRepeats = 3, sendCount = 1),
        )
        val events = service.statusEvents()

        service.resendChannelMessage(id)

        assertEquals(listOf(id), events.drain().resentIds())
        val stored = store.fetchMessage(id)
        assertEquals(MessageStatus.SENT, stored?.status)
        assertEquals(0, stored?.heardRepeats)
        assertEquals(2, stored?.sendCount)
    }

    @Test
    fun `resendChannelMessage stamps a fresh wire timestamp on the first drain`() = runTest {
        val store = InMemoryMessageStore()
        val session = FakeChatSession()
        var clock = TEST_NOW_SECONDS
        val service = testMessageService(session, store, backgroundScope, now = { clock })
        val id = ChatFixtures.freshUuid()
        val originalTimestamp = 1_699_000_000L
        store.saveMessage(
            ChatFixtures.message(id = id, timestamp = originalTimestamp, contactID = null, channelIndex = 0),
        )
        clock = TEST_NOW_SECONDS + 100

        val used = service.resendChannelMessage(id, preserveTimestamp = false)

        assertEquals(clock.toLong(), used)
        assertEquals(clock.toLong(), store.fetchMessage(id)?.timestamp)
        assertEquals(clock.toLong(), session.channelSends.single().third)
    }

    @Test
    fun `resendChannelMessage reuses the wire timestamp when preserving`() = runTest {
        val store = InMemoryMessageStore()
        val session = FakeChatSession()
        val service = testMessageService(session, store, backgroundScope)
        val id = ChatFixtures.freshUuid()
        val originalTimestamp = 1_699_000_000L
        store.saveMessage(
            ChatFixtures.message(id = id, timestamp = originalTimestamp, contactID = null, channelIndex = 0),
        )

        val used = service.resendChannelMessage(id, preserveTimestamp = true)

        assertEquals(originalTimestamp, used)
        assertEquals(originalTimestamp, session.channelSends.single().third)
    }
}
