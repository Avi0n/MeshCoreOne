package one.meshcore.shared.tools.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.trace.SavedTracePathStore

/** One saved-path delete: remove the row, reload the list, and clear the open detail. */
internal data class TracePathDeleteRequest(
    val store: SavedTracePathStore,
    val pathId: Uuid,
    val radioId: () -> Uuid?,
    val loadFailedMessage: String,
    val deleteFailedMessage: String,
    val onSavedPaths: (List<SavedTracePathDto>) -> Unit,
    val onError: (String) -> Unit,
    val onDetailPath: (SavedTracePathDto?) -> Unit,
    val isCurrentOwner: () -> Boolean = { true },
)

internal suspend fun confirmSavedTracePathDelete(request: TracePathDeleteRequest) {
    try {
        currentCoroutineContext().ensureActive()
        if (!request.isCurrentOwner()) return
        request.store.deleteSavedTracePath(request.pathId)
        currentCoroutineContext().ensureActive()
        if (!request.isCurrentOwner()) return
        reloadTracePathScreenSaved(
            store = request.store,
            radio = request.radioId(),
            onLoaded = request.onSavedPaths,
            onFailed = {
                request.onSavedPaths(emptyList())
                if (request.isCurrentOwner()) request.onError(request.loadFailedMessage)
            },
        )
        if (request.isCurrentOwner()) request.onDetailPath(null)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        if (request.isCurrentOwner()) request.onError(request.deleteFailedMessage)
    }
}
