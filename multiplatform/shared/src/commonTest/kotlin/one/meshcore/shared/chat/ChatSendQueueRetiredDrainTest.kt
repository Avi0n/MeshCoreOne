package one.meshcore.shared.chat

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatSendQueueRetiredDrainTest {
    @Test
    fun retiringAnActiveDirectSendPreservesItsPendingRow() = retireActive(false, MessageStatus.PENDING)

    @Test
    fun retiringAnActiveDirectSendPreservesItsSentRow() = retireActive(false, MessageStatus.SENT)

    @Test
    fun retiringAnActiveDirectSendPreservesItsDeliveredRow() = retireActive(false, MessageStatus.DELIVERED)

    @Test
    fun retiringAnActiveChannelSendPreservesItsPendingRow() = retireActive(true, MessageStatus.PENDING)

    @Test
    fun retiringAnActiveChannelSendPreservesItsSentRow() = retireActive(true, MessageStatus.SENT)

    @Test
    fun retiringAnActiveChannelSendPreservesItsDeliveredRow() = retireActive(true, MessageStatus.DELIVERED)

    @Test
    fun retiringAQueuedDirectResendRollsRetryingBackToSentWithoutCompletingTheResend() = runTest {
        val fixture = ChatSendQueueRetiredDrainFixture(backgroundScope, channel = false, retrying = true)
        try {
            withTimeout(TIMEOUT_MS) {
                fixture.initialize(MessageStatus.SENT)
                fixture.enqueue(resend = true)
                fixture.entered.await()
                assertEquals(ChatSendQueueRetiredDrainFixture.RETRY_ATTEMPTS, fixture.sessionCalls)
                assertEquals(1, fixture.completedSessionCalls)
                assertEquals(MessageStatus.RETRYING, fixture.store.messages.fetchMessage(fixture.original.id)?.status)
                assertNotNull(fixture.service.peekPendingAck(fixture.original.id))
                assertTrue(fixture.original.id in fixture.service.inFlightRetries)

                fixture.queue.shutdown()

                assertSettled(fixture, MessageStatus.SENT)
                val emitted = fixture.events.drain()
                assertEquals(
                    listOf(fixture.original.id),
                    emitted.filterIsInstance<MessageStatusEvent.Retrying>().map { it.messageID },
                )
                assertNoCompletion(emitted)
                assertEquals(1, fixture.completedSessionCalls)
                assertTrue(fixture.store.fetchPendingSends(ChatFixtures.RADIO).single().isResend)
            }
        } finally {
            fixture.release.complete(Unit)
            withTimeout(TIMEOUT_MS) { fixture.queue.shutdown() }
        }
    }

    @Test
    fun aWrappedRetirementCancellationPreservesTheActiveDirectPendingIntent() =
        retireActive(false, MessageStatus.PENDING, wrapped = true)

    @Test
    fun aWrappedRetirementCancellationPreservesTheActiveChannelPendingIntent() =
        retireActive(true, MessageStatus.PENDING, wrapped = true)

    private fun retireActive(channel: Boolean, status: MessageStatus, wrapped: Boolean = false) = runTest {
        val fixture = ChatSendQueueRetiredDrainFixture(backgroundScope, channel, wrapped = wrapped)
        try {
            withTimeout(TIMEOUT_MS) {
                fixture.initialize(status)
                fixture.enqueue()
                fixture.entered.await()
                val pending = fixture.store.fetchPendingSends(ChatFixtures.RADIO).single()
                assertEquals(1, pending.attemptCount)
                if (!channel) {
                    assertNotNull(fixture.service.peekPendingAck(fixture.original.id))
                    assertTrue(fixture.original.id in fixture.service.inFlightRetries)
                }

                fixture.queue.shutdown()

                assertSettled(fixture, status)
                assertEquals(fixture.original, fixture.store.messages.fetchMessage(fixture.original.id))
                assertEquals(listOf(pending), fixture.store.fetchPendingSends(ChatFixtures.RADIO))
                assertNoCompletion(fixture.events.drain())
                assertEquals(1, fixture.sessionCalls)
                assertEquals(0, fixture.completedSessionCalls)
            }
        } finally {
            fixture.release.complete(Unit)
            withTimeout(TIMEOUT_MS) { fixture.queue.shutdown() }
        }
    }

    private suspend fun assertSettled(fixture: ChatSendQueueRetiredDrainFixture, expected: MessageStatus) {
        assertTrue(fixture.queue.isRetired)
        assertTrue(fixture.cancellation.await().isSendQueueRetirement(), "the session must observe typed retirement")
        assertEquals(expected, fixture.store.messages.fetchMessage(fixture.original.id)?.status)
        assertNull(fixture.service.peekPendingAck(fixture.original.id))
        assertTrue(fixture.service.inFlightRetries.isEmpty(), "the retired drain must release retry ownership")
        assertEquals(fixture.original.sendCount, fixture.store.messages.fetchMessage(fixture.original.id)?.sendCount)
        assertEquals(fixture.original.id, fixture.store.fetchPendingSends(ChatFixtures.RADIO).single().messageID)
    }

    private fun assertNoCompletion(events: List<MessageStatusEvent>) {
        assertTrue(events.failedIds().isEmpty())
        assertTrue(events.resolvedTo().isEmpty(), "retirement cannot manufacture a success acknowledgement")
        assertTrue(events.resentIds().isEmpty())
    }

    private companion object {
        const val TIMEOUT_MS = 5_000L
    }
}
