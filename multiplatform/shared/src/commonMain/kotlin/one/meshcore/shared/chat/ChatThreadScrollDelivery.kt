package one.meshcore.shared.chat

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/** Pages and bakes a notification target before retiring its conversation-bound request. */
suspend fun ChatThreadViewModel.deliverPendingScrollRequest() {
    val generation = completedInitialLoadGeneration ?: return
    if (generation != loadGeneration) return
    val request = dependencies.pendingScrollRequest() ?: return
    val capturedWriter = writer ?: return
    val context = currentCoroutineContext()
    fun isCurrent(): Boolean = context.isActive &&
        writer === capturedWriter &&
        capturedWriter.isCurrent &&
        generation == loadGeneration &&
        completedInitialLoadGeneration == generation &&
        dependencies.pendingScrollRequest()?.requestID == request.requestID

    while (isCurrent()) {
        if (!advanceScrollDelivery(request, ::isCurrent)) return
    }
}

private fun ChatThreadViewModel.needsScrollBake(): Boolean =
    buildInFlight || currentFirstSnapshot() == ChatInitialScrollPolicy.FirstSnapshotDecision.Withhold

private suspend fun ChatThreadViewModel.advanceScrollDelivery(
    request: ChatScrollRequest,
    isCurrent: () -> Boolean,
): Boolean = when {
    // Warm items can retain the previous open's index or divider flags until this bake lands.
    needsScrollBake() -> awaitScrollBake(request, isCurrent, requirePresentable = true)
    coordinator.renderState.itemIndexByID[request.messageID] != null -> {
        if (dependencies.consumePendingScrollRequest(request.requestID)) {
            openScrollIssued = true
            isAtBottom = false
            scrollToMessage(request.messageID)
        }
        false
    }
    coordinator.messagesByID[request.messageID] != null -> awaitScrollBake(
        request,
        isCurrent,
        requirePresentable = false,
    )
    dependencies.store() == null || errorMessage != null -> false
    !coordinator.renderState.hasMoreMessages -> {
        dependencies.consumePendingScrollRequest(request.requestID)
        false
    }
    else -> {
        val fetchedCount = coordinator.renderState.totalFetchedCount
        loadOlderMessages()
        if (!isCurrent()) {
            false
        } else {
            val targetPresent = coordinator.renderState.itemIndexByID[request.messageID] != null ||
                coordinator.messagesByID[request.messageID] != null
            val pageAdvanced = coordinator.renderState.totalFetchedCount > fetchedCount ||
                !coordinator.renderState.hasMoreMessages ||
                targetPresent
            if (pageAdvanced) {
                true
            } else {
                // This page made no progress, the target is still absent, and history remains.
                // Wait for a later page or for that id to be indexed.
                coordinator.waitForHonorableScrollRender(request.messageID)
                isCurrent()
            }
        }
    }
}

private suspend fun ChatThreadViewModel.awaitScrollBake(
    request: ChatScrollRequest,
    isCurrent: () -> Boolean,
    requirePresentable: Boolean,
): Boolean {
    val job = coordinator.buildItemsJob ?: return false
    val renderGeneration = coordinator.renderStateID
    job.join()
    if (!isCurrent()) return false
    val ready = if (requirePresentable) {
        !needsScrollBake()
    } else {
        coordinator.renderState.itemIndexByID[request.messageID] !=
            null
    }
    return ready || renderGeneration != coordinator.renderStateID
}
