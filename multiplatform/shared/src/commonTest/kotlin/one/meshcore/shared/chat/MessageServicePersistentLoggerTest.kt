package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The radio-drop dump must carry MessageService ACK and send-retry lines. iOS writes these through
 * `PersistentLogger(category: "MessageService")`; these tests pin the same strings on the Kotlin side.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageServicePersistentLoggerTest {
    @Test
    fun `unmatched ACK records ack-diag warning`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val service = serviceWithLogger(this, recorded)
        val ack = ackBytes(0xDE, 0xAD, 0xBE, 0xEF)

        service.handleAcknowledgement(ack, tripTime = 100)

        val line = recorded.single { it.message.contains("[ack-diag] unmatched ACK") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("MessageService", line.category)
        assertTrue(line.message.contains("code=DEADBEEF"))
        assertTrue(line.message.contains("livePending=0"))
        assertTrue(line.message.contains("orphanSentRow=false"))
    }

    @Test
    fun `orphan unmatched ACK flags orphanSentRow`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryMessageStore()
        val service = serviceWithLogger(this, recorded, store)
        val id = ChatFixtures.freshUuid()
        val ack = ackBytes(0xCA, 0xFE, 0xBA, 0xBE)
        store.saveMessage(
            ChatFixtures.message(id = id, status = MessageStatus.SENT).copy(ackCode = ack.ackCodeLeUInt32()),
        )

        service.handleAcknowledgement(ack, tripTime = 150)

        val line = recorded.single { it.message.contains("[ack-diag] unmatched ACK") }
        assertTrue(line.message.contains("orphanSentRow=true"))
        assertEquals(MessageStatus.SENT, store.fetchMessage(id)?.status)
    }

    @Test
    fun `matched ACK records delta and firmware trip`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryMessageStore()
        val service = serviceWithLogger(this, recorded, store)
        val id = ChatFixtures.freshUuid()
        val ack = ackBytes(0xEF, 0xBE, 0xAD, 0xDE)
        store.saveMessage(
            ChatFixtures.message(id = id, status = MessageStatus.SENT).copy(ackCode = ack.ackCodeLeUInt32()),
        )
        service.setPendingAck(pendingAck(id, codes = setOf(ack.ackCodeLeUInt32()), ageSeconds = 10.0))

        service.handleAcknowledgement(ack, tripTime = 250)

        val line = recorded.single { it.message.contains("[ack-diag] ACK matched") }
        assertEquals(DebugLogLevel.INFO, line.level)
        assertTrue(line.message.contains("deltaSinceLastSend=10.00s"))
        assertTrue(line.message.contains("firmwareTrip=250ms"))
        assertTrue(line.message.contains("livePending=0"))
    }

    @Test
    fun `expired ACK give-up records ack-diag warning`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = InMemoryMessageStore()
        val service = serviceWithLogger(this, recorded, store)
        val id = ChatFixtures.freshUuid()
        store.saveMessage(ChatFixtures.message(id = id, status = MessageStatus.SENT))
        service.setPendingAck(pendingAck(id, ageSeconds = 60.0, timeoutSeconds = 30.0))

        service.checkExpiredAcks()

        val line = recorded.single { it.message.contains("[ack-diag] give-up") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains("failed after 60.0s"))
        assertTrue(line.message.contains("window=30.0s"))
        assertTrue(line.message.contains("deadline=30.0s"))
        assertTrue(line.message.contains("livePending=0"))
    }

    @Test
    fun `trackPendingAck records ackCode collision`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val service = serviceWithLogger(this, recorded)
        val first = ChatFixtures.freshUuid()
        val second = ChatFixtures.freshUuid()
        val ack = ackBytes(0x11, 0x22, 0x33, 0x44)

        service.trackPendingAck(first, ChatFixtures.CONTACT, ack, timeoutSeconds = 30.0)
        service.trackPendingAck(second, ChatFixtures.CONTACT, ack, timeoutSeconds = 30.0)

        val line = recorded.single { it.message.contains("[ack-diag] ackCode collision") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains("code=11223344"))
        assertTrue(line.message.contains(first.value))
        assertTrue(line.message.contains(second.value))
    }

    @Test
    fun `flood escalate records reset-path line`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeChatSession().apply { ackArrives = false }
        val service = serviceWithLogger(
            this,
            recorded,
            session = session,
            config = MessageServiceConfig(maxAttempts = 3, floodAfter = 2),
        )

        service.sendMessageWithRetry("escalate me", contact())

        val line = recorded.single { it.message.contains("Resetting path to flood") }
        assertEquals(DebugLogLevel.INFO, line.level)
        assertTrue(line.message.contains("after 2 failed attempts"))
        assertEquals(1, session.resetPathCalls)
    }

    @Test
    fun `flood escalate records reset-path failure and stays in flood mode`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeChatSession().apply {
            ackArrives = false
            resetPathError = { IllegalStateException("radio busy") }
        }
        val service = serviceWithLogger(
            this,
            recorded,
            session = session,
            config = MessageServiceConfig(maxAttempts = 3, floodAfter = 2),
        )

        service.sendMessageWithRetry("escalate me", contact())

        val line = recorded.single { it.message.contains("Failed to reset path") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains("radio busy"))
        assertTrue(line.message.contains("continuing..."))
        assertEquals(0, session.resetPathCalls)
        assertEquals(listOf(0, 1, 2), session.directSendAttempts)
    }

    @Test
    fun `retry loop records attempt and give-up lines`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeChatSession().apply { ackArrives = false }
        val service = serviceWithLogger(
            this,
            recorded,
            session = session,
            config = MessageServiceConfig(maxAttempts = 2, floodAfter = 10),
        )

        service.sendMessageWithRetry("retry me", contact())

        val retry = recorded.single { it.message.startsWith("Retry sending message") }
        assertEquals(DebugLogLevel.INFO, retry.level)
        assertEquals("Retry sending message: attempt 2/2", retry.message)
        val giveUp = recorded.single { it.message.startsWith("Message delivery failed") }
        assertEquals(DebugLogLevel.WARNING, giveUp.level)
        assertEquals("Message delivery failed after 2 attempts", giveUp.message)
        val missing = recorded.single { it.message.contains("Contact not found in device contacts after retry") }
        assertEquals(DebugLogLevel.INFO, missing.level)
    }

    @Test
    fun `retry loop records acknowledgement on the first attempt`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val session = FakeChatSession().apply { ackArrives = true }
        val service = serviceWithLogger(this, recorded, session = session)

        service.sendMessageWithRetry("ok", contact())

        val line = recorded.single { it.message.contains("Message acknowledged on attempt") }
        assertEquals(DebugLogLevel.INFO, line.level)
        assertEquals("Message acknowledged on attempt 1", line.message)
    }

    @Test
    fun `an in-flight send records the already-in-progress warning`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val service = serviceWithLogger(this, recorded)
        val id = ChatFixtures.freshUuid()
        service.insertInFlightRetry(id)

        val error = kotlin.test.assertFailsWith<MessageServiceException.SendFailed> {
            service.sendPendingDirectMessage(id, contact())
        }
        assertTrue(error.reason.contains("in progress"))
        val line = recorded.single { it.message.contains("Send already in progress") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains(id.value))
    }

    @Test
    fun `expectedAck mismatch records the iOS merge warning`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val service = serviceWithLogger(this, recorded)
        val sent = service.sendDirectMessage("hi", contact())
        val line = recorded.single { it.message.contains("expectedAck mismatch") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals(
            expectedAckMismatchLine(sent.id, 0, predictedAckFor("hi"), FAKE_ACK),
            line.message,
        )
    }

    @Test
    fun `DM resend sendCount persist fault records the iOS warning and does not throw`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<DebugLogEntryDto>()
            val store = FailOnIncrementSendCountStore()
            val service = serviceWithLogger(this, recorded, store)
            val id = ChatFixtures.freshUuid()
            store.saveMessage(
                ChatFixtures.message(id = id, status = MessageStatus.DELIVERED, contactID = ChatFixtures.CONTACT)
                    .copy(sendCount = 1),
            )
            service.setPendingAck(pendingAck(id, isDelivered = true))

            service.resendDirectMessage(id, contact())

            val line = recorded.single { it.message.contains("DM resend sendCount bookkeeping failed") }
            assertEquals(DebugLogLevel.WARNING, line.level)
            assertEquals("MessageService", line.category)
            assertEquals(dmResendSendCountBookkeepingFailLine(id, "db locked"), line.message)
            assertEquals(1, store.fetchMessage(id)?.sendCount)
        }

    @Test
    fun `DM post-send bookkeeping failure records the iOS warning`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val store = FailOnAckStore()
        val service = serviceWithLogger(this, recorded, store)
        val sent = service.sendDirectMessage("hi", contact())
        val line = recorded.single { it.message.contains("DM post-send bookkeeping failed") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals(dmPostSendBookkeepingFailLine(sent.id, "db locked"), line.message)
    }

    @Test
    fun `a delivered-status persist fault records the iOS error and keeps the listener`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<DebugLogEntryDto>()
            val store = FailOnAckStore()
            val service = serviceWithLogger(this, recorded, store)
            val id = ChatFixtures.freshUuid()
            val ack = ackBytes(0xEF, 0xBE, 0xAD, 0xDE)
            store.saveMessage(
                ChatFixtures.message(id = id, status = MessageStatus.SENT).copy(ackCode = ack.ackCodeLeUInt32()),
            )
            service.setPendingAck(pendingAck(id, codes = setOf(ack.ackCodeLeUInt32()), ageSeconds = 10.0))

            service.handleAcknowledgement(ack, tripTime = 250)

            val line = recorded.single { it.message.contains("Failed to write delivered status") }
            assertEquals(DebugLogLevel.ERROR, line.level)
            assertTrue(line.message.contains("db locked"))
            assertEquals(0, service.pendingAckCount())
        }

    private fun predictedAckFor(text: String): ByteArray {
        val sender = ByteArray(32) { 0x01 }
        return AckCodeBuilder.expectedAck(TEST_NOW_SECONDS.toLong(), 0, text, sender)
    }

    private class FailOnAckStore(private val inner: InMemoryMessageStore = InMemoryMessageStore()) :
        MessageStore by inner {
        override suspend fun updateMessageAck(id: Uuid, ackCode: Long, status: MessageStatus, roundTripTime: Long?) {
            error("db locked")
        }
    }

    private class FailOnIncrementSendCountStore(private val inner: InMemoryMessageStore = InMemoryMessageStore()) :
        MessageStore by inner {
        override suspend fun incrementMessageSendCount(id: Uuid): Int {
            error("db locked")
        }
    }

    private fun contact() = ChatFixtures.contact(name = "Alice", id = ChatFixtures.CONTACT)
        .copy(typeRawValue = ContactType.CHAT.raw)

    private fun serviceWithLogger(
        scope: kotlinx.coroutines.CoroutineScope,
        recorded: MutableList<DebugLogEntryDto>,
        store: MessageStore = InMemoryMessageStore(),
        session: FakeChatSession = FakeChatSession(),
        config: MessageServiceConfig = MessageServiceConfig(),
    ): MessageService = testMessageService(
        session,
        store,
        scope,
        config = config,
        logger = PersistentLogger(
            scope = scope,
            category = "MessageService",
            record = { recorded += it },
        ),
    )
}
