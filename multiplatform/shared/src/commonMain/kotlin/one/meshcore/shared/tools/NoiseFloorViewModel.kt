package one.meshcore.shared.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.Uuid
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Retained samples with one poller shared by the resumed screen mounts. */
@OptIn(ExperimentalTime::class)
class NoiseFloorViewModel(
    parentScope: CoroutineScope,
    private val dependencies: Dependencies,
    private val nowEpochMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    data class Dependencies(
        val fetchReading: () -> (suspend () -> NoiseFloorReading?)?,
        val sessionOwner: () -> Flow<MeshEvent>?,
        val radioId: () -> Uuid?,
    )

    data class Messages(val disconnected: String, val unableToRead: String)

    data class State(
        val readings: List<NoiseFloorReading> = emptyList(),
        val chartStartEpochMs: Long? = null,
        val isPolling: Boolean = false,
        val errorMessage: String? = null,
    )

    private class Binding(
        val fetch: (suspend () -> NoiseFloorReading?)?,
        val owner: Flow<MeshEvent>?,
        val radio: Uuid?,
    ) {
        fun matches(other: Binding): Boolean = fetch === other.fetch && owner === other.owner && radio == other.radio
    }

    private val ownerJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + ownerJob)
    private val updates = MutableStateFlow(State())
    val state = updates.asStateFlow()
    private val mounts = mutableSetOf<Long>()
    private var nextMount = 0L
    private var generation = 0L
    private var pollJob: Job? = null
    private var activeBinding: Binding? = null
    private var radioId: Uuid? = null
    private var messages: Messages? = null
    private var lastPoll: NoiseFloorPoll? = null

    fun updateMessages(messages: Messages) {
        this.messages = messages
        updates.update {
            it.copy(
                errorMessage = when (lastPoll) {
                    NoiseFloorPoll.Disconnected -> messages.disconnected
                    NoiseFloorPoll.ReadFailed -> messages.unableToRead
                    else -> null
                },
            )
        }
    }

    fun shown(messages: Messages): Long {
        updateMessages(messages)
        reconcileRadio()
        val token = ++nextMount
        mounts.add(token)
        startPolling()
        return token
    }

    fun hidden(token: Long) {
        mounts.remove(token)
        if (mounts.isEmpty()) stopPolling()
    }

    fun close() {
        mounts.clear()
        stopPolling()
        ownerJob.cancel()
    }

    private fun binding() = Binding(dependencies.fetchReading(), dependencies.sessionOwner(), dependencies.radioId())

    private fun reconcileRadio() {
        val current = dependencies.radioId() ?: return
        if (radioId != null && radioId != current) {
            lastPoll = null
            updates.update { State(isPolling = it.isPolling) }
        }
        radioId = current
    }

    private fun startPolling() {
        if (!scope.isActive) return
        val binding = binding()
        if (pollJob?.isActive == true && activeBinding?.matches(binding) == true) return
        val token = ++generation
        pollJob?.cancel()
        activeBinding = binding
        updates.update { it.copy(isPolling = true, chartStartEpochMs = it.chartStartEpochMs ?: nowEpochMillis()) }
        pollJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                pollWhileShown(token)
            } finally {
                if (scope.isActive && generation == token) {
                    pollJob = null
                    activeBinding = null
                    updates.update { it.copy(isPolling = false) }
                }
            }
        }
        pollJob?.start()
    }

    private fun stopPolling() {
        generation++
        pollJob?.cancel()
        pollJob = null
        activeBinding = null
        updates.update { it.copy(isPolling = false) }
    }

    private suspend fun pollWhileShown(token: Long) {
        while (mounts.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            reconcileRadio()
            updates.update { it.copy(chartStartEpochMs = it.chartStartEpochMs ?: nowEpochMillis()) }
            val captured = binding()
            val fetch = captured.fetch
            val poll = if (fetch == null ||
                captured.radio == null
            ) {
                NoiseFloorPoll.Disconnected
            } else {
                pollNoiseFloor(fetch)
            }
            currentCoroutineContext().ensureActive()
            if (generation == token && captured.matches(binding())) {
                lastPoll = poll
                val copy = requireNotNull(messages)
                updates.update {
                    val next = applyNoiseFloorPoll(poll, it.readings, copy.disconnected, copy.unableToRead)
                    it.copy(readings = next.readings, errorMessage = next.errorMessage)
                }
            }
            delay(NOISE_FLOOR_POLL_INTERVAL_MS)
        }
    }
}
