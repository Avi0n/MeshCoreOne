package one.meshcore.shared.tools

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import one.meshcore.shared.tools.ui.readTraceDeviceHashMode

internal fun TracePathViewModel.load() {
    val radio = dependencies.builder.radioId()
    if (loadedRadio != null && radio != null && loadedRadio != radio) reset()
    if (radio != null) loadedRadio = radio
    loadJob?.cancel()
    val owner = owner()
    val service = dependencies.builder.contactService()
    loadJob = scope.launch {
        val mode = readTraceDeviceHashMode(dependencies.deviceHashMode)
        currentCoroutineContext().ensureActive()
        if (!owns(owner) || dependencies.builder.contactService() !== service) return@launch
        mutableState.update { it.copy(deviceHashMode = mode) }
        builder.load()
        currentCoroutineContext().ensureActive()
        if (owns(owner)) reloadSaved(owner)
    }
}
internal suspend fun TracePathViewModel.reloadSaved(owner: TracePathViewModel.Owner = owner()) {
    val store = dependencies.savedPathStore
    val radio = owner.radioId
    if (!owns(owner)) return
    if (store == null || radio == null) {
        mutableState.update { it.copy(savedPaths = emptyList()) }
        return
    }
    try {
        val paths = store.fetchSavedTracePaths(radio)
        currentCoroutineContext().ensureActive()
        if (owns(owner)) mutableState.update { it.copy(savedPaths = paths) }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        if (owns(owner)) {
            mutableState.update { it.copy(savedPaths = emptyList()) }
            setError(dependencies.messages().loadFailed)
        }
    }
}

internal fun TracePathViewModel.publishSavedPaths(
    owner: TracePathViewModel.Owner,
    paths: List<one.meshcore.shared.backup.SavedTracePathDto>,
) {
    if (owns(owner)) mutableState.update { it.copy(savedPaths = paths) }
}
