package one.meshcore.shared.chat.linkpreview

import one.meshcore.shared.runExceptionFallback

/** A preview-store failure uses the caller's cache fallback; cancellation still stops the request. */
internal suspend fun <T> runLinkPreviewStore(onFailure: () -> T, block: suspend () -> T): T =
    runExceptionFallback(onFailure, block)
