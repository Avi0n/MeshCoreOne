package one.meshcore.shared.trace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.TraceInfo
import one.meshcore.shared.model.Uuid

/** Main-owned tagged wait; replacing a stream does not repeat the send or move its deadline. */
class TracePathWaiter(private val dependencies: Dependencies) {
    data class Dependencies(val events: () -> Flow<MeshEvent>?, val radioId: () -> Uuid?)
    data class State(val pendingTag: Long? = null, val deadlineEpochMs: Long? = null)

    private class Wait(val request: TracePathRequest, val tag: Long, val radioId: Uuid?, val startMs: Long) {
        val response = CompletableDeferred<TraceInfo?>()
        var deadline: Long? = null
        var timeout: Job? = null
        var sendCancellation: CancellationException? = null
    }

    private val mutableState = MutableStateFlow(State())
    val state: StateFlow<State> = mutableState.asStateFlow()
    private val binding = MutableStateFlow(0L)
    private var active: Wait? = null

    fun reattach() {
        binding.value += 1L
    }

    fun expireDeadline() {
        val wait = active ?: return
        val deadline = wait.deadline ?: return
        if (wait.request.nowMs() >= deadline) claim(wait, null)
    }

    /** Retirement cancels the caller's wait without manufacturing a failed trace result. */
    fun reset() {
        val wait = active
        active = null
        mutableState.value = State()
        wait?.timeout?.cancel()
        wait?.response?.cancel()
        reattach()
    }

    suspend fun run(request: TracePathRequest): TraceResult {
        currentCoroutineContext().ensureActive()
        reset()
        val wait = Wait(request, request.randomTag(), dependencies.radioId(), request.nowMs())
        active = wait
        mutableState.value = State(pendingTag = wait.tag)
        return try {
            coroutineScope {
                var receiver: Job? = null
                var sender: Job? = null
                try {
                    receiver = launch(start = CoroutineStart.UNDISPATCHED) { receive(wait) }
                    yield()
                    sender = launch(start = CoroutineStart.UNDISPATCHED) { send(wait) }
                    resultFor(wait, wait.response.await())
                } finally {
                    sender?.cancel()
                    receiver?.cancel()
                    wait.timeout?.cancel()
                    if (active === wait) {
                        active = null
                        mutableState.value = State()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw wait.sendCancellation ?: cancelled
        }
    }

    private suspend fun receive(wait: Wait) {
        binding.collectLatest { version ->
            val events = dependencies.events() ?: return@collectLatest
            try {
                events.collect { event ->
                    if (binding.value != version || dependencies.events() !== events || !ownsRadio(wait)) {
                        return@collect
                    }
                    if (event is MeshEvent.TraceData && event.trace.tag == wait.tag) claim(wait, event.trace)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A retired stream leaves the tag waiting for replacement or its existing deadline.
            }
        }
    }

    private suspend fun send(wait: Wait) {
        try {
            currentCoroutineContext().ensureActive()
            if (active !== wait || wait.response.isCompleted) return
            if (!ownsRadio(wait)) {
                wait.response.cancel()
                return
            }
            val request = wait.request
            val sent = request.sendTrace(wait.tag, request.flags, request.pathBytes)
            arm(wait, sanitizedFloodTimeoutMs(sent.suggestedTimeoutMs))
        } catch (cancelled: CancellationException) {
            if (currentCoroutineContext().isActive) wait.sendCancellation = cancelled
            wait.response.completeExceptionally(cancelled)
        } catch (_: Exception) {
            arm(wait, sanitizedFloodTimeoutMs(UNACKED_TIMEOUT_HINT))
        }
    }

    private fun resultFor(wait: Wait, trace: TraceInfo?): TraceResult {
        val request = wait.request
        return if (trace == null) {
            TraceResult.failure(request.failureMessages.noResponse, request.pathBytes, request.hashSize)
        } else {
            TraceResult(
                hops = buildTraceResultHops(trace.path, request.deviceName, request.nameForHash),
                durationMs = (request.nowMs() - wait.startMs).toInt().coerceAtLeast(0),
                success = true,
                errorMessage = null,
                tracedPathBytes = request.pathBytes,
                hashSize = request.hashSize,
            )
        }
    }

    private suspend fun arm(wait: Wait, timeoutMs: Long) = coroutineScope {
        if (active !== wait || wait.response.isCompleted) return@coroutineScope
        val deadline = wait.request.nowMs() + timeoutMs
        wait.deadline = deadline
        mutableState.value = State(wait.tag, deadline)
        wait.timeout = launch {
            val remaining = deadline - wait.request.nowMs()
            if (remaining > 0L) delay(remaining)
            claim(wait, null)
        }
    }

    private fun ownsRadio(wait: Wait): Boolean {
        val radio = dependencies.radioId()
        return wait.radioId == null || radio == null || radio == wait.radioId
    }

    private fun claim(wait: Wait, trace: TraceInfo?) {
        if (active !== wait || wait.response.isCompleted || !ownsRadio(wait)) return
        wait.response.complete(trace)
        wait.timeout?.cancel()
        mutableState.value = State()
    }

    private companion object {
        const val UNACKED_TIMEOUT_HINT = 0L
    }
}
