package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import one.meshcore.shared.chat.ChatSendQueueRetirementAssertions.assertFreshReplay
import one.meshcore.shared.chat.ChatSendQueueRetirementAssertions.assertParked
import one.meshcore.shared.chat.ChatSendQueueRetirementAssertions.withFixture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatSendQueueRetirementCancellationTest {
    @Test
    fun aCancelledWaitingDirectProducerDoesNotWriteBehindTheAdmittedProducer() = cancelledProducer(false)

    @Test
    fun aCancelledWaitingChannelProducerDoesNotWriteBehindTheAdmittedProducer() = cancelledProducer(true)

    @Test
    fun cancellingDirectShutdownStillWaitsForItsAdmittedRoomWrite() = cancelledShutdown(false)

    @Test
    fun cancellingChannelShutdownStillWaitsForItsAdmittedRoomWrite() = cancelledShutdown(true)

    private fun cancelledProducer(channel: Boolean) = withFixture(channel) { fixture ->
        val gate = fixture.holdWrite(afterCommit = false)
        val accepted = fixture.start { fixture.produce() }
        gate.entered.await()
        val waiting = fixture.start { fixture.produce() }
        try {
            assertFalse(waiting.isCompleted, "a second producer must wait outside the Room write")
            waiting.cancelAndJoin()
            assertTrue(waiting.isCancelled)
            assertEquals(1, fixture.store.writeCalls, "a canceled lock waiter must not enter the store")
            val closing = fixture.start { fixture.queue.shutdown() }
            try {
                gate.release.complete(Unit)
                accepted.await()
                closing.await()
                fixture.awaitOldDrain()
                assertParked(fixture, "cancelled-waiter-channel=$channel")
                assertFreshReplay(fixture)
            } finally {
                gate.release.complete(Unit)
                closing.cancelAndJoin()
            }
        } finally {
            gate.release.complete(Unit)
            waiting.cancelAndJoin()
            accepted.cancelAndJoin()
        }
    }

    private fun cancelledShutdown(channel: Boolean) = withFixture(channel) { fixture ->
        val gate = fixture.holdWrite(afterCommit = false)
        val accepted = fixture.start { fixture.produce() }
        gate.entered.await()
        val closing = fixture.start { fixture.queue.shutdown() }
        try {
            closing.cancel(CancellationException(CANCEL_REASON))
            fixture.flush()
            assertFalse(closing.isCompleted, "canceled teardown must finish its persistence barrier")
            gate.release.complete(Unit)
            accepted.await()
            assertFailsWith<CancellationException> { closing.await() }
            assertTrue(closing.isCancelled)
            fixture.awaitOldDrain()
            assertParked(fixture, "cancelled-shutdown-channel=$channel")
            assertFreshReplay(fixture)
        } finally {
            gate.release.complete(Unit)
            accepted.cancelAndJoin()
            closing.cancelAndJoin()
        }
    }

    private companion object {
        const val CANCEL_REASON = "fixture shutdown cancellation"
    }
}
