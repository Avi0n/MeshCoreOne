package one.meshcore.shared.trace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.meshcore.TraceInfo
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TracePathWaiterTest {
    @Test
    fun replacementStreamKeepsTheOriginalTagDeadlineAndSendCount() = runTest {
        val first = MutableSharedFlow<MeshEvent>()
        val replacement = MutableSharedFlow<MeshEvent>()
        var stream = first
        var sends = 0
        val waiter = TracePathWaiter(TracePathWaiter.Dependencies({ stream }, { RADIO }))
        val result = async {
            waiter.run(
                request(first, now = { testScheduler.currentTime }, send = {
                    sends++
                    ACK
                }),
            )
        }
        runCurrent()
        val deadline = waiter.state.value.deadlineEpochMs
        advanceTimeBy(1_000L)
        stream = replacement
        waiter.reattach()
        runCurrent()
        assertEquals(TAG, waiter.state.value.pendingTag)
        assertEquals(deadline, waiter.state.value.deadlineEpochMs)
        assertEquals(1, sends)
        first.emit(response())
        runCurrent()
        assertFalse(result.isCompleted)
        replacement.emit(response())
        assertTrue(result.await().success)
        assertNull(waiter.state.value.pendingTag)
    }

    @Test
    fun aResponseCanCompleteBeforeTheSendAcknowledgement() = runTest {
        val events = MutableSharedFlow<MeshEvent>()
        val ack = CompletableDeferred<MessageSentInfo>()
        val waiter = TracePathWaiter(TracePathWaiter.Dependencies({ events }, { RADIO }))
        val result = async { waiter.run(request(events, send = { ack.await() })) }
        runCurrent()
        assertNull(waiter.state.value.deadlineEpochMs)
        events.emit(response())
        assertTrue(result.await().success)
        ack.complete(ACK)
        runCurrent()
        assertNull(waiter.state.value.pendingTag)
    }

    @Test
    fun foregroundExpiryClaimsTheOriginalDeadlineOnlyOnce() = runTest {
        val events = MutableSharedFlow<MeshEvent>()
        var clock = 0L
        val waiter = TracePathWaiter(TracePathWaiter.Dependencies({ events }, { RADIO }))
        val result = async { waiter.run(request(events, now = { clock })) }
        runCurrent()
        clock = requireNotNull(waiter.state.value.deadlineEpochMs)
        waiter.expireDeadline()
        waiter.expireDeadline()
        events.emit(response())
        val value = result.await()
        assertFalse(value.success)
        assertEquals("no reply", value.errorMessage)
        assertNull(waiter.state.value.pendingTag)
    }

    @Test
    fun anEndedStreamWaitsForReplacementWithoutResending() = runTest {
        val replacement = MutableSharedFlow<MeshEvent>()
        var events = emptyFlow<MeshEvent>()
        var sends = 0
        val waiter = TracePathWaiter(TracePathWaiter.Dependencies({ events }, { RADIO }))
        val result = async {
            waiter.run(
                request(events, send = {
                    sends++
                    ACK
                }),
            )
        }
        runCurrent()
        assertFalse(result.isCompleted)
        events = replacement
        waiter.reattach()
        runCurrent()
        replacement.emit(response())
        assertTrue(result.await().success)
        assertEquals(1, sends)
    }

    @Test
    fun retiringTheWaitDoesNotCreateAFailedResult() = runTest {
        val events = MutableSharedFlow<MeshEvent>()
        val waiter = TracePathWaiter(TracePathWaiter.Dependencies({ events }, { RADIO }))
        val result = async { waiter.run(request(events)) }
        runCurrent()
        waiter.reset()
        runCurrent()
        assertTrue(result.isCancelled)
        assertNull(waiter.state.value.pendingTag)
    }

    @Test
    fun aCancelledCallerCannotRetireAnExistingWaitOrSend() = runTest {
        val events = MutableSharedFlow<MeshEvent>()
        val waiter = TracePathWaiter(TracePathWaiter.Dependencies({ events }, { RADIO }))
        val original = async { waiter.run(request(events)) }
        runCurrent()
        val state = waiter.state.value
        var sent = false
        val cancelled = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            waiter.run(
                request(events, send = {
                    sent = true
                    ACK
                }),
            )
        }
        runCurrent()
        assertTrue(cancelled.isCancelled)
        assertFalse(sent)
        assertEquals(state, waiter.state.value)
        events.emit(response())
        assertTrue(original.await().success)
    }

    @Test
    fun cancellationDuringSubscriptionClearsPendingStateWithoutSending() = runTest {
        lateinit var caller: Deferred<TraceResult>
        val events = flow<MeshEvent> {
            caller.cancel()
            awaitCancellation()
        }
        var sent = false
        val waiter = TracePathWaiter(TracePathWaiter.Dependencies({ events }, { RADIO }))
        caller = async {
            waiter.run(
                request(events, send = {
                    sent = true
                    ACK
                }),
            )
        }
        runCurrent()
        assertTrue(caller.isCancelled)
        assertFalse(sent)
        assertNull(waiter.state.value.pendingTag)
    }

    @Test
    fun aRetiredSendAcknowledgementCannotChangeTheReplacementWait() = runTest {
        val events = MutableSharedFlow<MeshEvent>()
        val oldAck = CompletableDeferred<MessageSentInfo>()
        var clock = 0L
        val waiter = TracePathWaiter(TracePathWaiter.Dependencies({ events }, { RADIO }))
        val old = async {
            waiter.run(request(events, send = { withContext(NonCancellable) { oldAck.await() } }))
        }
        runCurrent()
        waiter.reset()
        val replacement = async {
            waiter.run(request(events, now = { clock }).copy(randomTag = { TAG + 1L }))
        }
        runCurrent()
        val state = waiter.state.value
        oldAck.complete(ACK)
        runCurrent()
        assertTrue(old.isCancelled)
        assertEquals(state, waiter.state.value)
        clock = requireNotNull(state.deadlineEpochMs)
        waiter.expireDeadline()
        assertFalse(replacement.await().success)
    }

    private fun request(
        events: kotlinx.coroutines.flow.Flow<MeshEvent>,
        now: () -> Long = { 0L },
        send: suspend () -> MessageSentInfo = { ACK },
    ) = TracePathRequest(
        pathBytes = byteArrayOf(0x11), hashSize = 1, flags = 0, events = events,
        sendTrace = { _, _, _ -> send() }, deviceName = "Radio",
        failureMessages = TraceFailureMessages("no reply", "send fail"), nowMs = now, randomTag = { TAG },
    )

    private fun response() = MeshEvent.TraceData(TraceInfo(TAG, 0, 0, 0, emptyList()))

    private companion object {
        const val TAG = 7L
        val RADIO = Uuid.random()
        val ACK = MessageSentInfo(0, ByteArray(0), 0L)
    }
}
