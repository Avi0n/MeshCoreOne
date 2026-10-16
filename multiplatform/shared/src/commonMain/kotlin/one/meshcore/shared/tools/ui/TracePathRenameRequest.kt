package one.meshcore.shared.tools.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.trace.SavedTracePathStore

/** One saved-path rename: the new name, then a list reload and the open detail. */
internal data class TracePathRenameRequest(
    val store: SavedTracePathStore,
    val pathId: Uuid,
    val name: String,
    val radioId: () -> Uuid?,
    val detailPathId: Uuid?,
    val loadFailedMessage: String,
    val renameFailedMessage: String,
    val onSavedPaths: (List<SavedTracePathDto>) -> Unit,
    val onError: (String) -> Unit,
    val onDetailPath: (SavedTracePathDto?) -> Unit,
    val isCurrentOwner: () -> Boolean = { true },
)

internal suspend fun renameSavedTracePath(request: TracePathRenameRequest) {
    try {
        currentCoroutineContext().ensureActive()
        if (!request.isCurrentOwner()) return
        request.store.updateSavedTracePathName(request.pathId, request.name)
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
        if (request.isCurrentOwner() && request.detailPathId == request.pathId) {
            val detail = loadSavedTracePath(request.store, request.pathId)
            currentCoroutineContext().ensureActive()
            if (request.isCurrentOwner()) request.onDetailPath(detail)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        if (request.isCurrentOwner()) request.onError(request.renameFailedMessage)
    }
}
