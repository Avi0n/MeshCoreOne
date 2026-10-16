package one.meshcore.shared.chat

import kotlinx.coroutines.cancelAndJoin
import one.meshcore.shared.chat.ChatSendQueueRetirementAssertions.assertFreshReplay
import one.meshcore.shared.chat.ChatSendQueueRetirementAssertions.assertParked
import one.meshcore.shared.chat.ChatSendQueueRetirementAssertions.withFixture
import one.meshcore.shared.model.MessageStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatSendQueueRetirementPendingTest {
    @Test
    fun aDirectInsertHeldBeforeRoomCommitDelaysShutdownAndParks() = heldWrite(false, false, false)

    @Test
    fun aDirectInsertHeldAfterRoomCommitDelaysShutdownAndParks() = heldWrite(false, false, true)

    @Test
    fun aChannelInsertHeldBeforeRoomCommitDelaysShutdownAndParks() = heldWrite(true, false, false)

    @Test
    fun aChannelInsertHeldAfterRoomCommitDelaysShutdownAndParks() = heldWrite(true, false, true)

    @Test
    fun aDirectRetryHeldBeforeRoomCommitDelaysShutdownAndParks() = heldWrite(false, true, false)

    @Test
    fun aDirectRetryHeldAfterRoomCommitDelaysShutdownAndParks() = heldWrite(false, true, true)

    @Test
    fun aChannelRetryHeldBeforeRoomCommitDelaysShutdownAndParks() = heldWrite(true, true, false)

    @Test
    fun aChannelRetryHeldAfterRoomCommitDelaysShutdownAndParks() = heldWrite(true, true, true)

    @Test
    fun anAcceptedDirectCommitRemainsDurableWithoutStartingTheRetiredSession() = acceptedCommit(false)

    @Test
    fun anAcceptedChannelCommitRemainsDurableWithoutStartingTheRetiredSession() = acceptedCommit(true)

    @Test
    fun aCommittedChannelSendAgainKeepsItsOriginalSentRowWhileParked() = withFixture(channel = true, sent = true) {
        val original = it.message
        val gate = it.holdWrite(afterCommit = true)
        val producer = it.start { it.produce(resend = true) }
        gate.entered.await()
        val closing = it.start { it.queue.shutdown() }
        try {
            gate.release.complete(Unit)
            producer.await()
            closing.await()
            it.awaitOldDrain()
            val parked = assertParked(it, "sent-channel-parked", MessageStatus.SENT)
            assertEquals(original, parked.message)
            assertTrue(parked.pending.single().isResend)
            assertFreshReplay(it)
        } finally {
            gate.release.complete(Unit)
            producer.cancelAndJoin()
            closing.cancelAndJoin()
        }
    }

    private fun heldWrite(channel: Boolean, retry: Boolean, afterCommit: Boolean) =
        withFixture(channel, retry) { fixture ->
            val gate = fixture.holdWrite(afterCommit)
            val producer = fixture.start { fixture.produce(retry) }
            gate.entered.await()
            val held = fixture.snapshot("held-channel=$channel-retry=$retry-afterCommit=$afterCommit")
            if (afterCommit) {
                assertEquals(MessageStatus.PENDING, held.message.status)
                assertEquals(0, held.pending.single().attemptCount)
            } else {
                assertEquals(if (retry) MessageStatus.FAILED else MessageStatus.PENDING, held.message.status)
                assertEquals(if (retry) 1 else 0, held.pending.size)
            }
            val closing = fixture.start { fixture.queue.shutdown() }
            try {
                assertFalse(closing.isCompleted, "shutdown must wait for the admitted Room writer")
                val calls = fixture.store.writeCalls
                assertFailsWith<ChatSendQueueServiceException.NotConnected> { fixture.produce(retry) }
                assertEquals(calls, fixture.store.writeCalls, "retired admission must reject before the store")
                gate.release.complete(Unit)
                producer.await()
                closing.await()
                fixture.awaitOldDrain()
                assertParked(fixture, "released-channel=$channel-retry=$retry-afterCommit=$afterCommit")
                assertFreshReplay(fixture)
            } finally {
                gate.release.complete(Unit)
                producer.cancelAndJoin()
                closing.cancelAndJoin()
            }
        }

    private fun acceptedCommit(channel: Boolean) = withFixture(channel) { fixture ->
        val gate = fixture.holdWrite(afterCommit = true)
        val producer = fixture.start { fixture.produce() }
        gate.entered.await()
        val closing = fixture.start { fixture.queue.shutdown() }
        try {
            // This outcome oracle remains reachable even when the separate barrier assertion fails.
            gate.release.complete(Unit)
            producer.await()
            closing.await()
            fixture.awaitOldDrain()
            assertParked(fixture, "accepted-commit-channel=$channel")
            assertFreshReplay(fixture)
        } finally {
            gate.release.complete(Unit)
            producer.cancelAndJoin()
            closing.cancelAndJoin()
        }
    }
}
