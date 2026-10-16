package one.meshcore.shared.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.pathedit.PathHop
import one.meshcore.shared.runExceptionFallback
import one.meshcore.shared.tools.ui.recordRunsOnMatchingSavedPath
import one.meshcore.shared.tools.ui.traceRunFailureMessage
import one.meshcore.shared.trace.TraceResult

private data class Execution(
    val owner: TracePathViewModel.Owner,
    val path: ByteArray,
    val runs: Int,
    val outbound: List<PathHop>,
    val hashSize: Int,
    val mode: Int,
    val runner: suspend (ByteArray, Int, Int) -> TraceResult,
)

internal fun TracePathViewModel.run() {
    if (state.value.isRunning) return
    val runner = dependencies.runner() ?: return
    val snapshot = state.value
    val path = builder.fullPathBytes(snapshot.autoReturn)
    if (path.isEmpty()) {
        mutableState.update { it.copy(result = null, batchResults = emptyList(), successId = null) }
        setError(dependencies.messages().emptyPath)
        return
    }
    epoch += 1L
    val runs = if (snapshot.batchEnabled) snapshot.batchCount.coerceIn(MIN_BATCH_COUNT, MAX_BATCH_COUNT) else 1
    val execution = Execution(
        owner(),
        path,
        runs,
        builder.outboundPath.toList(),
        builder.hashSize,
        builder.effectiveTraceMode,
        runner,
    )
    resultDismissed = false
    presentedSuccessId = null
    setError(null)
    mutableState.update {
        it.copy(
            isRunning = true,
            batchProgress = if (runs > 1) 1 to runs else null,
            result = null,
            batchResults = emptyList(),
            successId = null,
        )
    }
    val job = scope.launch(start = CoroutineStart.LAZY) { execute(execution) }
    runJob = job
    job.start()
}

private suspend fun TracePathViewModel.execute(execution: Execution) {
    val owner = execution.owner
    try {
        val matchingPaths = runExceptionFallback({ emptyList() }) {
            owner.radioId?.let { dependencies.savedPathStore?.fetchSavedTracePaths(it) } ?: emptyList()
        }
        val outcomes = mutableListOf<TraceResult>()
        repeat(execution.runs) { index ->
            currentCoroutineContext().ensureActive()
            if (!owns(owner)) return
            if (execution.runs > 1) mutableState.update { it.copy(batchProgress = index + 1 to execution.runs) }
            val result = execution.runner(execution.path, execution.hashSize, execution.mode)
            currentCoroutineContext().ensureActive()
            if (!owns(owner)) return
            val nodes = builder.state.value
            val resolved = resolveTraceHops(
                result,
                execution.outbound,
                nodes.allContacts,
                nodes.discoveredNodes,
                dependencies.deviceLocation(),
            )
            outcomes += resolved
            publish(outcomes, resolved)
            recordMember(owner, matchingPaths, execution.path, resolved)
            if (index < execution.runs - 1) delay(INTER_TRACE_BUFFER_MS)
        }
        val messages = dependencies.messages()
        if (owns(owner)) setError(traceRunFailureMessage(outcomes, messages.allFailed, messages.failed))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        if (owns(owner)) setError(dependencies.messages().failed)
    } finally {
        if (owns(owner)) {
            mutableState.update { it.copy(isRunning = false, batchProgress = null) }
            runJob = null
        }
    }
}

private fun TracePathViewModel.publish(outcomes: List<TraceResult>, result: TraceResult) {
    if (result.success && state.value.successId == null) successSequence += 1L
    mutableState.update {
        it.copy(
            batchResults = outcomes.toList(),
            result =
            result.takeIf { value -> value.success } ?: it.result ?: result,
            successId = it.successId ?: successSequence.takeIf { result.success },
        )
    }
}

private suspend fun TracePathViewModel.recordMember(
    owner: TracePathViewModel.Owner,
    savedPaths: List<SavedTracePathDto>,
    path: ByteArray,
    result: TraceResult,
) {
    val store = dependencies.savedPathStore ?: return
    currentCoroutineContext().ensureActive()
    if (!owns(owner)) return
    recordRunsOnMatchingSavedPath(store, savedPaths, path, listOf(result))
    currentCoroutineContext().ensureActive()
    if (owns(owner)) reloadSaved(owner)
}
