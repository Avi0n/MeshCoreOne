package one.meshcore.shared.meshcore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AwaitRegisteredTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun cancellationForwardsTheDispatchedRequestId() = runTest {
        val pending = mutableMapOf<Long, CompletableDeferred<String>>()
        val cancelled = mutableListOf<Long>()
        val job = launch {
            awaitRegistered(pending, REQUEST_ID, onCancellation = { cancelled += it }) {}
        }
        runCurrent()
        assertTrue(pending.containsKey(REQUEST_ID))
        job.cancelAndJoin()
        assertEquals(listOf(REQUEST_ID), cancelled)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun aPreCancelledCallerDoesNotDispatch() = runTest {
        val pending = mutableMapOf<Long, CompletableDeferred<String>>()
        var dispatched = false
        val job = launch {
            coroutineContext.cancel()
            awaitRegistered(pending, REQUEST_ID) { dispatched = true }
        }
        job.join()
        assertEquals(false, dispatched)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun aCompletedDispatchReturnsTheCallbackValue() = runTest {
        val pending = mutableMapOf<Long, CompletableDeferred<String>>()
        val value = awaitRegistered(pending, REQUEST_ID) { id ->
            pending.getValue(id).complete("ok")
        }
        assertEquals("ok", value)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun aCancelledDispatchDropsTheIdAndRethrows() = runTest {
        val pending = mutableMapOf<Long, CompletableDeferred<String>>()
        val cancelled = CancellationException("stopped")
        val thrown = assertFailsWith<CancellationException> {
            awaitRegistered(pending, REQUEST_ID) { throw cancelled }
        }
        assertSame(cancelled, thrown)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun aFailedDispatchDropsTheIdAndRethrows() = runTest {
        val pending = mutableMapOf<Long, CompletableDeferred<String>>()
        val thrown = assertFailsWith<IllegalStateException> {
            awaitRegistered(pending, REQUEST_ID) { error("bridge down") }
        }
        assertEquals("bridge down", thrown.message)
        assertTrue(pending.isEmpty())
    }

    @Test
    fun anErrorDropsTheIdAndPropagates() = runTest {
        val pending = mutableMapOf<Long, CompletableDeferred<String>>()
        val probe = AwaitRegisteredProbeError()
        val thrown = assertFailsWith<AwaitRegisteredProbeError> {
            awaitRegistered(pending, REQUEST_ID) { throw probe }
        }
        assertSame(probe, thrown)
        assertTrue(pending.isEmpty())
    }
}

private class AwaitRegisteredProbeError : Error("probe")

private const val REQUEST_ID = 7L
