package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The contact read after a direct send is finalized. Cancellation keeps the thrown instance and is not
 * logged as a failed check. Any other failure, including a thrown error, is logged and finalization
 * still returns the message.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutingChangeCheckFaultTest {
    @Test
    fun aCancelledRoutingCheckKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val recorded = mutableListOf<DebugLogEntryDto>()
        val harness = harness(this, recorded)
        harness.session.contactError = { stopped }

        val thrown = assertFailsWith<CancellationException> {
            harness.service.finalizeSend(harness.settlement)
        }

        assertSame(stopped, thrown)
        assertEquals(MessageStatus.SENT, harness.store.fetchMessage(harness.messageID)?.status)
        assertTrue(recorded.none { it.message.contains("Failed to check routing change") })
    }

    @Test
    fun aRoutingCheckExceptionIsLogged() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val harness = harness(this, recorded)
        harness.session.contactError = { RoutingCheckProbeException() }

        val message = harness.service.finalizeSend(harness.settlement)

        assertEquals(harness.messageID, message.id)
        assertEquals(MessageStatus.SENT, message.status)
        assertTrue(recorded.any { it.message.contains("Failed to check routing change") })
    }

    @Test
    fun aRoutingCheckErrorIsLogged() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val harness = harness(this, recorded)
        harness.session.contactError = { RoutingCheckProbeError() }

        val message = harness.service.finalizeSend(harness.settlement)

        assertEquals(harness.messageID, message.id)
        assertEquals(MessageStatus.SENT, message.status)
        assertTrue(recorded.any { it.message.contains("Failed to check routing change") })
    }

    private suspend fun harness(scope: CoroutineScope, recorded: MutableList<DebugLogEntryDto>): Harness {
        val store = InMemoryMessageStore()
        val session = FakeChatSession()
        val messageID = ChatFixtures.freshUuid()
        store.saveMessage(ChatFixtures.message(id = messageID, status = MessageStatus.SENT))
        val service = testMessageService(
            session,
            store,
            scope,
            logger = PersistentLogger(
                scope = scope,
                category = "MessageService",
                record = { recorded += it },
            ),
        )
        val settlement = DirectSendSettlement(
            messageID = messageID,
            contactID = ChatFixtures.CONTACT,
            radioID = ChatFixtures.RADIO,
            publicKey = ByteArray(ChatConstants.PUBLIC_KEY_SIZE_BYTES),
            sentInfo = null,
            initialPathLength = 0,
        )
        return Harness(store, session, service, messageID, settlement)
    }

    private class Harness(
        val store: InMemoryMessageStore,
        val session: FakeChatSession,
        val service: MessageService,
        val messageID: Uuid,
        val settlement: DirectSendSettlement,
    )
}

private class RoutingCheckProbeException : Exception("db locked")

private class RoutingCheckProbeError : Error("probe")
