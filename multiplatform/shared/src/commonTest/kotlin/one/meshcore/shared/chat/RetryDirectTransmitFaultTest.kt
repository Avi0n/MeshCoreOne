package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The radio attempt inside the direct retry loop. Cancellation keeps the thrown instance and the
 * speculative ACK. Every other send failure drops that ACK and keeps the thrown instance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RetryDirectTransmitFaultTest {
    @Test
    fun aCancelledRetryTransmitKeepsTheAck() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")
        val session = FakeChatSession().apply { directSendError = { stopped } }
        val service = service(this, session)

        val thrown = assertFailsWith<CancellationException> {
            service.sendDirectMessageWithRetryLoop(request(ChatFixtures.directContact()))
        }

        assertSame(stopped, thrown)
        assertNotNull(service.peekPendingAck(MESSAGE_ID))
        assertTrue(session.directSendAttempts.isEmpty())
    }

    @Test
    fun aRetryTransmitExceptionDropsTheAck() = runTest(UnconfinedTestDispatcher()) {
        val failure = RetryTransmitProbeException()
        val session = FakeChatSession().apply { directSendError = { failure } }
        val service = service(this, session)

        val thrown = assertFailsWith<RetryTransmitProbeException> {
            service.sendDirectMessageWithRetryLoop(request(ChatFixtures.directContact()))
        }

        assertSame(failure, thrown)
        assertNull(service.peekPendingAck(MESSAGE_ID))
        assertTrue(session.directSendAttempts.isEmpty())
    }

    @Test
    fun aRetryTransmitErrorDropsTheAck() = runTest(UnconfinedTestDispatcher()) {
        val probe = RetryTransmitProbeError()
        val session = FakeChatSession().apply { directSendError = { probe } }
        val service = service(this, session)

        val thrown = assertFailsWith<RetryTransmitProbeError> {
            service.sendDirectMessageWithRetryLoop(request(ChatFixtures.directContact()))
        }

        assertSame(probe, thrown)
        assertNull(service.peekPendingAck(MESSAGE_ID))
        assertTrue(session.directSendAttempts.isEmpty())
    }

    private fun service(scope: CoroutineScope, session: FakeChatSession) =
        testMessageService(session, InMemoryMessageStore(), scope)

    private fun request(contact: ContactDto) = DirectSendRequest(
        messageID = MESSAGE_ID,
        contactID = contact.id,
        radioID = contact.radioID,
        publicKey = contact.publicKey,
        text = "Hello",
        timestamp = 1L,
        timeoutSeconds = null,
    )

    private companion object {
        val MESSAGE_ID = SequentialIds().next()
    }
}

private class RetryTransmitProbeException : Exception("transport drop")

private class RetryTransmitProbeError : Error("probe")
