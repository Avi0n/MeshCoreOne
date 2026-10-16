package one.meshcore.shared.chat.linkpreview

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.chat.PreviewLoadState
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.urlsafety.UrlComponents

internal suspend fun LinkPreviewController.fetchPreview(messageID: Uuid, url: String) {
    if (isMalware(url)) {
        transition(messageID, PreviewLoadState.MALWARE_WARNING)
        fetchJobs.remove(messageID)
        return
    }
    transition(messageID, PreviewLoadState.LOADING)
    apply(messageID, guarded(messageID) { cache.preview(url, isChannelMessage()) } ?: return)
}

internal suspend fun LinkPreviewController.runManualFetch(messageID: Uuid, url: String) {
    transition(messageID, PreviewLoadState.LOADING)
    apply(messageID, guarded(messageID) { cache.manualFetch(url) } ?: return)
}

/** Runs [block], returning null (leaving state untouched) if the message's fetch was cancelled. */
internal suspend inline fun <T> LinkPreviewController.guarded(messageID: Uuid, block: () -> T): T? = try {
    block()
} catch (cancellation: CancellationException) {
    fetchJobs.remove(messageID)
    resetOrphanedLoading(messageID)
    throw cancellation
}

/**
 * A `.loading` row with no in-flight job is orphaned: cancel or a follower `.loading` result
 * left the shimmer behind. Reset to idle so the next visible-row fetch can re-fire.
 */
internal fun LinkPreviewController.recoverOrphanedLoadingState(messageID: Uuid) {
    if (fetchJobs[messageID] != null) return
    resetOrphanedLoading(messageID)
}

private fun LinkPreviewController.resetOrphanedLoading(messageID: Uuid) {
    if (previewStates[messageID] != PreviewLoadState.LOADING) return
    previewStates[messageID] = PreviewLoadState.IDLE
    onRebuild(messageID)
}

private fun LinkPreviewController.apply(messageID: Uuid, result: LinkPreviewResult) {
    when (result) {
        is LinkPreviewResult.Loaded -> {
            loadedPreviews[messageID] = result.data
            transition(messageID, PreviewLoadState.LOADED)
        }

        LinkPreviewResult.NoPreviewAvailable -> transition(messageID, PreviewLoadState.NO_PREVIEW)
        LinkPreviewResult.Disabled -> transition(messageID, PreviewLoadState.DISABLED)
    }
    fetchJobs.remove(messageID)
}

internal suspend fun LinkPreviewController.isMalware(url: String): Boolean {
    val host = UrlComponents.parse(url)?.host ?: return false
    return malwareFilter.isBlocked(host)
}

internal fun LinkPreviewController.transition(messageID: Uuid, state: PreviewLoadState) {
    previewStates[messageID] = state
    onRebuild(messageID)
}
