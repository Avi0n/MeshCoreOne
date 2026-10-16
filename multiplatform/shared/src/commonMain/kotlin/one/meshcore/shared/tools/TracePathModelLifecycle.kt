package one.meshcore.shared.tools

import kotlinx.coroutines.flow.update

internal fun TracePathViewModel.setVisible(value: Boolean) {
    visible = value
    if (!value) {
        errorJob?.cancel()
        errorJob = null
    }
}
internal fun TracePathViewModel.consumeStoredResult(): Boolean {
    val snapshot = state.value
    val id = snapshot.successId
    val eligible = visible && !resultDismissed && presentedSuccessId != id
    val canPresent = !snapshot.isRunning || snapshot.batchProgress != null
    val hasResult = snapshot.result?.success == true && !snapshot.mapMode
    if (eligible && canPresent && hasResult) {
        presentedSuccessId = id
        return true
    }
    return false
}
internal fun TracePathViewModel.dismissResult() {
    if (!visible) {
        presentedSuccessId = null
        return
    }
    resultDismissed = true
    if (state.value.batchProgress != null && state.value.isRunning) cancelRun()
}
internal fun TracePathViewModel.cancelRun() {
    epoch += 1L
    runJob?.cancel()
    runJob = null
    mutableState.update { it.copy(isRunning = false, batchProgress = null) }
}
internal fun TracePathViewModel.clearPath() {
    cancelRun()
    builder.clearPath()
    setError(null)
    mutableState.update { it.copy(result = null, batchResults = emptyList(), successId = null) }
    presentedSuccessId = null
    resultDismissed = false
}
internal fun TracePathViewModel.reset() {
    cancelRun()
    loadJob?.cancel()
    errorJob?.cancel()
    builder.reset()
    val options = state.value
    mutableState.value = TracePathViewModel.State(
        autoReturn = options.autoReturn,
        batchEnabled = options.batchEnabled,
        batchCount = options.batchCount,
        mapMode = options.mapMode,
        mapFilter = options.mapFilter,
        ownerGeneration = options.ownerGeneration + 1L,
    )
    presentedSuccessId = null
    resultDismissed = false
}

internal fun TracePathViewModel.attach(): Long {
    visibilityGeneration += 1L
    setVisible(true)
    return visibilityGeneration
}
internal fun TracePathViewModel.detach(token: Long, showingResult: Boolean) {
    if (token != visibilityGeneration) return
    setVisible(false)
    if (showingResult) dismissResult()
}
