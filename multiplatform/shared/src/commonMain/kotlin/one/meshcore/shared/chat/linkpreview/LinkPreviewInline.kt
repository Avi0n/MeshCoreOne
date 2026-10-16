package one.meshcore.shared.chat.linkpreview

import one.meshcore.shared.chat.CachedDecodedImage
import one.meshcore.shared.chat.ImageDimensionReader
import one.meshcore.shared.chat.ImageURLClassifier
import one.meshcore.shared.chat.InlineImageResult
import one.meshcore.shared.chat.PreviewLoadState
import one.meshcore.shared.model.Uuid

/**
 * The whole inline-image safety gate plus the gated byte fetch. A blocked host lands on the warning
 * card, a private or reserved address lands on the failure placeholder, and neither ever reaches the
 * network. A decoded-cache hit paints Loaded without a fetch, matching iOS rehydration. Otherwise the
 * row reaches [PreviewLoadState.LOADING] only after both checks, then [InlineImageCache] fetches.
 */
internal suspend fun LinkPreviewController.admitInlineImage(messageID: Uuid, url: String, honorScopeGate: Boolean) {
    val fetchUrl = ImageURLClassifier.directImageURL(url)
    if (stopBeforeFetch(messageID, url, fetchUrl, honorScopeGate)) return
    transition(messageID, PreviewLoadState.LOADING)
    val result = guarded(messageID) { cache.inlineMedia.fetchInlineImageData(fetchUrl) } ?: return
    applyInline(messageID, url, fetchUrl, result)
}

private suspend fun LinkPreviewController.stopBeforeFetch(
    messageID: Uuid,
    url: String,
    fetchUrl: String,
    honorScopeGate: Boolean,
): Boolean {
    if (blockedByScope(messageID, fetchUrl, honorScopeGate)) return true
    if (isMalware(fetchUrl)) {
        transition(messageID, PreviewLoadState.MALWARE_WARNING)
        fetchJobs.remove(messageID)
        return true
    }
    if (!cache.inlineMedia.isDirectFetchAllowed(fetchUrl)) {
        transition(messageID, PreviewLoadState.NO_PREVIEW)
        fetchJobs.remove(messageID)
        return true
    }
    return paintedFromCacheOrPage(messageID, url, fetchUrl)
}

private suspend fun LinkPreviewController.blockedByScope(
    messageID: Uuid,
    fetchUrl: String,
    honorScopeGate: Boolean,
): Boolean {
    if (!honorScopeGate || cache.shouldAutoResolve(isChannelMessage())) return false
    val cached = cache.inlineMedia.decodedInline(fetchUrl)
    if (cached != null) {
        loadedInline[messageID] = cached
        transition(messageID, PreviewLoadState.LOADED)
    } else {
        transition(messageID, PreviewLoadState.DISABLED)
    }
    fetchJobs.remove(messageID)
    return true
}

private suspend fun LinkPreviewController.paintedFromCacheOrPage(
    messageID: Uuid,
    url: String,
    fetchUrl: String,
): Boolean {
    if (paintDecoded(messageID, fetchUrl)) return true
    if (cache.inlineMedia.servesHtmlPage(fetchUrl)) {
        rerouteToPagePreview(messageID, url)
        return true
    }
    return false
}

private fun LinkPreviewController.paintDecoded(messageID: Uuid, fetchUrl: String): Boolean {
    val cached = cache.inlineMedia.decodedInline(fetchUrl) ?: return false
    loadedInline[messageID] = cached
    transition(messageID, PreviewLoadState.LOADED)
    fetchJobs.remove(messageID)
    return true
}

private suspend fun LinkPreviewController.applyInline(
    messageID: Uuid,
    url: String,
    fetchUrl: String,
    result: InlineImageResult,
) {
    when (result) {
        is InlineImageResult.Loaded -> {
            val isGIF = ImageDimensionReader.isGifData(result.data)
            val entry = CachedDecodedImage(bytes = result.data, isGIF = isGIF)
            cache.inlineMedia.storeDecodedInline(entry, fetchUrl)
            loadedInline[messageID] = entry
            transition(messageID, PreviewLoadState.LOADED)
        }
        InlineImageResult.Loading -> Unit
        InlineImageResult.NotImage -> rerouteToPagePreview(messageID, url)
        InlineImageResult.Failed -> transition(messageID, PreviewLoadState.NO_PREVIEW)
    }
    fetchJobs.remove(messageID)
}

/**
 * A URL classified as an image turned out to serve an HTML page: park this row and every
 * loading twin sharing it, then fetch the og:image card when previews are on. With previews
 * off the reroute is text-only ([PreviewLoadState.NO_PREVIEW]), matching iOS `.urlServesPage`.
 */
internal suspend fun LinkPreviewController.rerouteToPagePreview(messageID: Uuid, url: String) {
    imageUrlsServingPages.add(url)
    cache.inlineMedia.markServesHtmlPage(ImageURLClassifier.directImageURL(url))
    val reroute = if (cache.shouldShowPreview()) {
        PreviewLoadState.IDLE
    } else {
        PreviewLoadState.NO_PREVIEW
    }
    previewStates[messageID] = reroute
    for ((twinID, twinUrl) in detectedUrls) {
        val sameLoadingTwin = twinID != messageID &&
            twinUrl == url &&
            previewStates[twinID] == PreviewLoadState.LOADING
        if (sameLoadingTwin) {
            previewStates[twinID] = reroute
            onRebuild(twinID)
        }
    }
    if (reroute == PreviewLoadState.NO_PREVIEW) {
        onRebuild(messageID)
        fetchJobs.remove(messageID)
        return
    }
    fetchPreview(messageID, url)
}
