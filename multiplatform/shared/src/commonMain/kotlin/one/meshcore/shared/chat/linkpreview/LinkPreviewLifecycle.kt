package one.meshcore.shared.chat.linkpreview

import one.meshcore.shared.chat.PreviewLoadState
import one.meshcore.shared.model.Uuid

/**
 * Repaints a cached preview to loaded on chat re-entry WITHOUT re-running the malware gate (a flagged
 * URL never entered the cache), guarded so it never overwrites an active malware warning. Mirrors the
 * iOS rehydration repaint.
 */
fun LinkPreviewController.rehydrate(messageID: Uuid, data: LinkPreviewData) {
    if (previewStates[messageID] == PreviewLoadState.MALWARE_WARNING) return
    loadedPreviews[messageID] = data
    transition(messageID, PreviewLoadState.LOADED)
}

/** Cancels an in-flight fetch for [messageID] (its row scrolled away). */
fun LinkPreviewController.cancel(messageID: Uuid) {
    fetchJobs.remove(messageID)?.cancel()
}

/** Drops all state for a deleted [messageID]. */
fun LinkPreviewController.cleanup(messageID: Uuid) {
    fetchJobs.remove(messageID)?.cancel()
    previewStates.remove(messageID)
    detectedUrls.remove(messageID)
    loadedPreviews.remove(messageID)
    loadedInline.remove(messageID)
}

/** Clears every message's state on conversation switch, cancelling in-flight fetches. */
fun LinkPreviewController.clear() {
    fetchJobs.values.forEach { it.cancel() }
    fetchJobs.clear()
    previewStates.clear()
    detectedUrls.clear()
    loadedPreviews.clear()
    loadedInline.clear()
    imageUrlsServingPages.clear()
}
