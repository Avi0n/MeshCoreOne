package one.meshcore.shared.chat.linkpreview

import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.ImageURLClassifier
import one.meshcore.shared.chat.PreviewLoadState
import one.meshcore.shared.model.Uuid

/**
 * Detects and records [message]'s preview URL once, returning it (memoised so a rebuild is stable).
 * The view model calls this while assembling a row so [cachedUrl] is populated before the fragment is
 * built. Mirrors the per-message URL-detection writer feeding the iOS `cachedURLs`.
 */
fun LinkPreviewController.detectUrl(message: MessageDto): String? {
    val url = detectedUrls.getOrPut(message.id) { LinkPreviewUrlExtractor.extractFirst(message.text) }
    if (url != null) rehydrateInlineIfCached(message.id, url)
    return url
}

/**
 * Requests an auto-fetch when a row becomes visible. An orphaned `.loading` with no in-flight
 * job is reset to idle first (iOS `recoverOrphanedLoadingState`) so a cancelled or follower
 * fetch cannot leave a shimmer forever. Only idle rows start a new fetch; a settled non-idle
 * row rebakes so a desynced item repairs in one frame.
 */
fun LinkPreviewController.requestPreviewFetch(message: MessageDto) {
    recoverOrphanedLoadingState(message.id)
    val state = previewStates[message.id]
    if (state != null && state != PreviewLoadState.IDLE) {
        onRebuild(message.id)
        return
    }
    val url = detectUrl(message) ?: return
    fetchJobs[message.id] = if (routesToInlineImage(url)) {
        scope.launch { admitInlineImage(message.id, url, honorScopeGate = true) }
    } else {
        scope.launch { fetchPreview(message.id, url) }
    }
}

/**
 * Whether [url] routes to the inline-image fragment and fetch path: an image-extension
 * or resolvable URL the fetch path has not since found to serve an HTML page.
 * [imageUrlsServingPages] covers reroutes discovered this session;
 * [LinkPreviewCache.servesHtmlPageCached] carries the verdict across chat re-entry.
 */
fun LinkPreviewController.routesToInlineImage(url: String): Boolean {
    if (!ImageURLClassifier.isImageURL(url) || url in imageUrlsServingPages) return false
    return !cache.inlineMedia.servesHtmlPageCached(ImageURLClassifier.directImageURL(url))
}

/**
 * The inline renderer could not decode fetched image bytes. Reroute through the HTML preview
 * path the way iOS does for `.notImage`: a landing page at a `.jpg` path becomes an og:image card;
 * a genuine fetch failure lands on [PreviewLoadState.NO_PREVIEW] with tap-to-retry.
 */
fun LinkPreviewController.onInlineImageLoadFailed(messageID: Uuid) {
    if (previewStates[messageID] != PreviewLoadState.LOADING &&
        previewStates[messageID] != PreviewLoadState.LOADED
    ) {
        return
    }
    val url = detectedUrls[messageID] ?: return
    loadedInline.remove(messageID)
    fetchJobs[messageID] = scope.launch { rerouteToPagePreview(messageID, url) }
}

/**
 * Tap-to-load: fetches regardless of the scope gate, only from [PreviewLoadState.DISABLED] (the
 * placeholder the user tapped). Mirrors `manualFetchPreview`.
 */
fun LinkPreviewController.manualFetch(message: MessageDto) {
    if (previewStates[message.id] != PreviewLoadState.DISABLED) return
    val url = detectedUrls[message.id] ?: return
    fetchJobs[message.id] = launchFetch(message.id, url)
}

/**
 * Tap-to-retry after a failed resolve. Like [manualFetch] it goes through the cache's manual path,
 * which drops the URL's negative-cache entry and ignores the scope gate — routing a retry back through
 * the auto path would bounce a scope-off row to the tap-to-load placeholder instead of retrying. A row
 * already flagged as malware is never re-fetched. Mirrors `retryImageFetch`.
 */
fun LinkPreviewController.retryFetch(message: MessageDto) {
    val state = previewStates[message.id]
    if (state == PreviewLoadState.MALWARE_WARNING || state == PreviewLoadState.LOADING) return
    val url = detectedUrls[message.id] ?: return
    val fetchUrl = ImageURLClassifier.directImageURL(url)
    fetchJobs[message.id] = scope.launch {
        cache.inlineMedia.clearInlineFailure(fetchUrl)
        if (routesToInlineImage(url)) {
            admitInlineImage(message.id, url, honorScopeGate = false)
        } else {
            runManualFetch(messageID = message.id, url = url)
        }
    }
}

/**
 * A user-initiated fetch (tap-to-load or tap-to-retry): the image gate for an image URL, the manual
 * HTML fetch otherwise. Both skip the scope gate, which the tap has already overridden.
 */
private fun LinkPreviewController.launchFetch(messageID: Uuid, url: String): Job = if (routesToInlineImage(url)) {
    scope.launch { admitInlineImage(messageID, url, honorScopeGate = false) }
} else {
    scope.launch { runManualFetch(messageID, url) }
}

/**
 * Seeds Loaded from the process-lifetime decoded cache during item build, so a chat re-entry
 * skips the shimmer. Idempotent and a no-op once state has advanced past idle/disabled.
 */
private fun LinkPreviewController.rehydrateInlineIfCached(messageID: Uuid, url: String) {
    if (!routesToInlineImage(url)) return
    val existing = previewStates[messageID]
    if (existing != null &&
        existing != PreviewLoadState.IDLE &&
        existing != PreviewLoadState.DISABLED
    ) {
        return
    }
    val cached = cache.inlineMedia.decodedInline(ImageURLClassifier.directImageURL(url)) ?: return
    loadedInline[messageID] = cached
    previewStates[messageID] = PreviewLoadState.LOADED
}
