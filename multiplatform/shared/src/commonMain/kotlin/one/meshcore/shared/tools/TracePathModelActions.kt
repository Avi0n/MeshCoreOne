package one.meshcore.shared.tools

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import one.meshcore.shared.map.MapFilterState

internal fun TracePathViewModel.setAutoReturn(value: Boolean) {
    mutableState.update { it.copy(autoReturn = value) }
}
internal fun TracePathViewModel.setBatchEnabled(value: Boolean) {
    mutableState.update { it.copy(batchEnabled = value) }
}
internal fun TracePathViewModel.setBatchCount(value: Int) {
    mutableState.update { it.copy(batchCount = value.coerceIn(MIN_BATCH_COUNT, MAX_BATCH_COUNT)) }
}
internal fun TracePathViewModel.setMapMode(value: Boolean) {
    mutableState.update { it.copy(mapMode = value) }
}
internal fun TracePathViewModel.setMapFilter(value: MapFilterState) {
    mutableState.update { it.copy(mapFilter = value) }
}
internal fun TracePathViewModel.setError(message: String?) {
    errorJob?.cancel()
    mutableState.update { it.copy(errorMessage = message) }
    if (visible && message != null) {
        val owner = owner()
        errorJob = scope.launch {
            delay(ERROR_AUTO_CLEAR_MS)
            if (owns(owner) && state.value.errorMessage == message) {
                mutableState.update { it.copy(errorMessage = null) }
            }
        }
    }
}
internal const val MIN_BATCH_COUNT = 3
internal const val MAX_BATCH_COUNT = 5
internal const val INTER_TRACE_BUFFER_MS = 500L
private const val ERROR_AUTO_CLEAR_MS = 4_000L
