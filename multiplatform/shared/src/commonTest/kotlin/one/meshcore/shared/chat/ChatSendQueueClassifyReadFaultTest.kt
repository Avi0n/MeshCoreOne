package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A drain's store read. A fault, including a thrown error, is a transient miss. Cancellation
 * keeps the thrown instance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatSendQueueClassifyReadFaultTest {
    @Test
    fun aValueIsFoundAndNullIsMissing() = runTest(UnconfinedTestDispatcher()) {
        val queue = queue(this)

        assertEquals(ReadOutcome.Found(7), queue.classifyRead { 7 })
        assertEquals(ReadOutcome.Missing, queue.classifyRead<Int> { null })
    }

    @Test
    fun aReadExceptionIsTheSameTransient() = runTest(UnconfinedTestDispatcher()) {
        val failure = IllegalStateException("db locked")

        val outcome = queue(this).classifyRead<Int> { throw failure }

        assertTrue(outcome is ReadOutcome.Transient)
        assertSame(failure, (outcome as ReadOutcome.Transient).error)
    }

    @Test
    fun aReadErrorIsTheSameTransient() = runTest(UnconfinedTestDispatcher()) {
        val probe = ClassifyReadProbeError()

        val outcome = queue(this).classifyRead<Int> { throw probe }

        assertTrue(outcome is ReadOutcome.Transient)
        assertSame(probe, (outcome as ReadOutcome.Transient).error)
    }

    @Test
    fun aCancelledReadKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("superseded")

        val cancelled = assertFailsWith<CancellationException> {
            queue(this).classifyRead<Int> { throw stopped }
        }

        assertSame(stopped, cancelled)
    }

    private fun queue(scope: CoroutineScope): ChatSendQueueService = ChatSendQueueService(
        radioID = RADIO,
        collaborators = ChatSendQueueCollaborators(
            store = FakeSendQueueStore(),
            messageService = MessageService(
                FakeChatSession(),
                InMemoryMessageStore(),
                scope,
                MessageServiceIdentity({ NOW }, SequentialIds()::next),
            ),
            channelResolver = FakeChannelResolver(),
            scope = scope,
            now = { NOW },
            newPendingSendId = PendingSendIds()::next,
        ),
    )

    private companion object {
        const val NOW: Double = 1_700_000_000.0
        val RADIO: Uuid = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
    }
}

private class ClassifyReadProbeError : Error("probe")
