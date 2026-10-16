package one.meshcore.shared.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SendQueueCloseTest {
    @Test
    fun closeBeforeALazyDrainStartsClearsItsSlotAndRejectsLaterEnvelopes() = runTest {
        val sent = mutableListOf<Int>()
        val queue = queue(backgroundScope, sent)
        queue.enqueue(7)
        queue.close()
        queue.enqueue(8)
        queue.close()
        queue.awaitDrainCompletion()
        assertTrue(sent.isEmpty())
        assertEquals(1, queue.count())
    }

    @Test
    fun cancelDrainBeforeALazyStartStillAllowsLaterEnqueue() = runTest {
        val sent = mutableListOf<Int>()
        val queue = queue(backgroundScope, sent)
        queue.enqueue(7)
        queue.cancelDrain()
        queue.enqueue(8)
        queue.awaitDrainCompletion()
        assertEquals(listOf(7, 8), sent)
    }

    @Test
    fun closeCancelsAnActiveSendWithTheTypedRetirementCauseAndCannotResume() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<Int>()
        val failures = mutableListOf<Throwable>()
        var retirementSeen = false
        val queue = SendQueue<Int>(
            backgroundScope,
            send = {
                entered.complete(Unit)
                try {
                    release.await()
                    sent += it
                } catch (failure: Throwable) {
                    retirementSeen = failure.isSendQueueRetirement()
                    throw failure
                }
            },
            onError = { error, _ -> failures += error },
            onDrain = {},
        )
        queue.enqueue(7)
        entered.await()
        queue.close()
        release.complete(Unit)
        queue.enqueue(8)
        queue.awaitDrainCompletion()
        assertTrue(retirementSeen)
        assertTrue(sent.isEmpty())
        assertTrue(failures.isEmpty())
        assertEquals(1, queue.count())
    }

    @Test
    fun closeWaitsForANoncancellableCompletionCallbackWithoutRespawning() = runTest {
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<Int>()
        val queue = SendQueue<Int>(
            backgroundScope,
            send = { sent += it },
            onError = { _, _ -> },
            onDrain = {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    reached.complete(Unit)
                    release.await()
                }
            },
        )
        queue.enqueue(7)
        reached.await()
        queue.enqueue(8)
        val closing = async(start = CoroutineStart.UNDISPATCHED) { queue.close() }
        assertFalse(closing.isCompleted)
        release.complete(Unit)
        closing.await()
        queue.enqueue(9)
        queue.awaitDrainCompletion()
        assertEquals(listOf(7), sent)
        assertEquals(1, queue.count())
    }

    private fun queue(scope: CoroutineScope, sent: MutableList<Int>) = SendQueue<Int>(
        scope,
        send = { sent += it },
        onError = { _, _ -> },
        onDrain = {},
    )
}
