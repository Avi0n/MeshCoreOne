package one.meshcore.shared.chat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Mirrors the Swift `SendQueueTests`: the serial-drain FIFO order, the CancellationError-requeue +
 * auto-respawn park primitive, per-envelope errors continuing the drain, and the `onDrain` contract
 * (fires once per pass, last-error-wins, nil when none failed, and the outer re-check that picks up an
 * enqueue landing during the drain callback). The "drain task captures actor strongly" case is ARC-specific
 * and has no Kotlin analog (the launched job holds the closure; there is no reference-counted actor).
 */
class SendQueueTest {
    private class Boom(val id: Int) : Exception("boom $id")

    @Test
    fun `serial draining sends all envelopes in FIFO order`() = runTest {
        val observed = mutableListOf<Int>()
        val queue = SendQueue<Int>(backgroundScope, send = { observed.add(it) }, onError = { _, _ -> }, onDrain = {})

        (1..5).forEach { queue.enqueue(it) }
        queue.awaitDrainCompletion()

        assertEquals(listOf(1, 2, 3, 4, 5), observed)
        assertEquals(0, queue.count())
    }

    @Test
    fun `CancellationError requeues at the front and the drain auto-respawns`() = runTest {
        var sendCalls = 0
        val succeeded = mutableListOf<Int>()
        val errors = mutableListOf<Throwable>()
        val parked = CancellationException("park")
        val queue = SendQueue<Int>(
            backgroundScope,
            send = {
                sendCalls += 1
                if (sendCalls == 1) throw parked
                succeeded.add(it)
            },
            onError = { error, _ -> errors.add(error) },
            onDrain = {},
        )

        queue.enqueue(42)
        queue.awaitDrainCompletion()

        assertEquals(2, sendCalls, "envelope is attempted twice: once parked, once successful")
        assertEquals(listOf(42), succeeded, "the auto-respawn retry delivers the envelope")
        assertEquals(0, queue.count())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `non-cancellation error fires onError and the drain continues`() = runTest {
        val sent = mutableListOf<Int>()
        val errored = mutableListOf<Int>()
        val queue = SendQueue<Int>(
            backgroundScope,
            send = { if (it == 2) throw Boom(it) else sent.add(it) },
            onError = { _, envelope -> errored.add(envelope) },
            onDrain = {},
        )

        (1..3).forEach { queue.enqueue(it) }
        queue.awaitDrainCompletion()

        assertEquals(listOf(1, 3), sent, "drain must continue past the failing envelope")
        assertEquals(listOf(2), errored)
    }

    @Test
    fun anErrorIsDeliveredToOnErrorAndTheDrainContinues() = runTest {
        val probe = SendQueueDrainProbeError()
        val sent = mutableListOf<Int>()
        val errors = mutableListOf<Throwable>()
        val queue = SendQueue<Int>(
            backgroundScope,
            send = {
                if (it == 2) throw probe
                sent.add(it)
            },
            onError = { error, _ -> errors.add(error) },
            onDrain = {},
        )

        (1..3).forEach { queue.enqueue(it) }
        queue.awaitDrainCompletion()

        assertEquals(listOf(1, 3), sent)
        assertSame(probe, errors.single())
        assertEquals(0, queue.count())
    }

    @Test
    fun cancelDrainRequeuesTheInFlightEnvelopeAndStaysDormant() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<Int>()
        val errors = mutableListOf<Throwable>()
        val queue = SendQueue<Int>(
            backgroundScope,
            send = {
                started.complete(Unit)
                release.await()
                sent.add(it)
            },
            onError = { error, _ -> errors.add(error) },
            onDrain = {},
        )

        queue.enqueue(7)
        started.await()
        queue.cancelDrain()

        assertEquals(1, queue.count())
        queue.awaitDrainCompletion()
        assertEquals(1, queue.count())
        assertTrue(errors.isEmpty())
        assertTrue(sent.isEmpty())

        release.complete(Unit)
        queue.enqueue(8)
        queue.awaitDrainCompletion()

        assertEquals(listOf(7, 8), sent)
        assertEquals(0, queue.count())
    }

    @Test
    fun `onDrain fires exactly once per drain pass`() = runTest {
        val sent = mutableListOf<Int>()
        var drainCalls = 0
        val firstSendReached = CompletableDeferred<Unit>()
        val releaseFirstSend = CompletableDeferred<Unit>()
        var firstSend = true
        val queue = SendQueue<Int>(
            backgroundScope,
            send = {
                if (firstSend) {
                    firstSend = false
                    firstSendReached.complete(Unit)
                    releaseFirstSend.await()
                }
                sent.add(it)
            },
            onError = { _, _ -> },
            onDrain = { drainCalls += 1 },
        )

        queue.enqueue(1)
        firstSendReached.await()
        queue.enqueue(2)
        queue.enqueue(3)
        releaseFirstSend.complete(Unit)
        queue.awaitDrainCompletion()

        assertEquals(listOf(1, 2, 3), sent)
        assertEquals(1, drainCalls, "all three envelopes drain in one pass; onDrain fires once")
    }

    @Test
    fun `onDrain receives the most recent non-cancellation error from the drain pass`() = runTest {
        var recorded: Throwable? = null
        val queue = SendQueue<Int>(
            backgroundScope,
            send = { throw Boom(it) },
            onError = { _, _ -> },
            onDrain = { recorded = it },
        )

        (1..3).forEach { queue.enqueue(it) }
        queue.awaitDrainCompletion()

        assertEquals(3, (recorded as? Boom)?.id, "last-error-wins: the third envelope's error reaches onDrain")
    }

    @Test
    fun `onDrain receives null when no envelope failed`() = runTest {
        var fired = false
        var recorded: Throwable? = null
        val queue = SendQueue<Int>(
            backgroundScope,
            send = {},
            onError = { _, _ -> },
            onDrain = {
                fired = true
                recorded = it
            },
        )

        queue.enqueue(1)
        queue.awaitDrainCompletion()

        assertEquals(true, fired)
        assertNull(recorded)
    }

    @Test
    fun `outer loop processes envelopes enqueued during onDrain`() = runTest {
        val sent = mutableListOf<Int>()
        var drainCalls = 0
        val firstOnDrainReached = CompletableDeferred<Unit>()
        val releaseFirstOnDrain = CompletableDeferred<Unit>()
        val queue = SendQueue<Int>(
            backgroundScope,
            send = { sent.add(it) },
            onError = { _, _ -> },
            onDrain = {
                drainCalls += 1
                if (drainCalls == 1) {
                    firstOnDrainReached.complete(Unit)
                    releaseFirstOnDrain.await()
                }
            },
        )

        queue.enqueue(1)
        firstOnDrainReached.await()
        queue.enqueue(2)
        releaseFirstOnDrain.complete(Unit)
        queue.awaitDrainCompletion()

        assertEquals(listOf(1, 2), sent)
        assertEquals(2, drainCalls, "the outer re-check produces a second onDrain after the late envelope drains")
    }
}

private class SendQueueDrainProbeError : Error("probe")
