package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.linkpreview.LinkPreviewData
import one.meshcore.shared.chat.linkpreview.inlineImageBytes
import one.meshcore.shared.chat.linkpreview.manualFetch
import one.meshcore.shared.chat.linkpreview.onInlineImageLoadFailed
import one.meshcore.shared.chat.linkpreview.previewIconBytes
import one.meshcore.shared.chat.linkpreview.previewImageBytes
import one.meshcore.shared.chat.linkpreview.requestPreviewFetch
import one.meshcore.shared.chat.linkpreview.retryFetch
import one.meshcore.shared.model.Uuid

internal fun ChatThreadViewModel.storedPreviewData(message: MessageDto): LinkPreviewData? {
    val url = message.linkPreviewURL ?: return null
    val hasPayload = message.linkPreviewTitle != null ||
        message.linkPreviewImageData != null ||
        message.linkPreviewIconData != null
    if (!hasPayload) return null
    return LinkPreviewData(
        url = url,
        title = message.linkPreviewTitle,
        imageBytes = message.linkPreviewImageData,
        iconBytes = message.linkPreviewIconData,
    )
}

/**
 * Auto-fetch when a row becomes visible. Idle-only and URL-gated inside the controller, matching iOS
 * `UnifiedMessageBubble`'s `.task(id: previewFetchTaskID)`. Opening a thread must not fetch every
 * loaded row — only the ones the list actually composed.
 */
fun ChatThreadViewModel.requestPreviewFetch(messageID: Uuid) {
    val message = coordinator.messagesByID[messageID] ?: return
    if (!currentEnvInputs().previewsEnabled) return
    linkPreviewController?.requestPreviewFetch(message)
}

/**
 * Tap-to-load for a row whose preview or inline image the scope gate parked at
 * [PreviewLoadState.DISABLED]: an explicit user action, so it fetches past the gate. Mirrors the Swift
 * `manualFetchPreview` / `manualFetchImage` pair, which share one grammar here because the Kotlin
 * controller owns both fragment kinds. Mirrors `onManualPreviewFetch`.
 */
fun ChatThreadViewModel.manualPreviewFetch(messageID: Uuid) {
    val message = coordinator.messagesByID[messageID] ?: return
    linkPreviewController?.manualFetch(message)
}

/**
 * Re-fetches a preview or inline image that failed, clearing the negative-cache entry first and
 * bypassing the scope gate the way an explicit tap does — routing it back through the auto path would
 * bounce a scope-off failure to the tap-to-load placeholder instead of retrying. Mirrors
 * `retryImageFetch`.
 */
fun ChatThreadViewModel.retryPreviewFetch(messageID: Uuid) {
    val message = coordinator.messagesByID[messageID] ?: return
    linkPreviewController?.retryFetch(message)
}

/** Coil could not decode the inline image; reroute to the HTML preview or tap-to-retry. */
fun ChatThreadViewModel.onInlineImageLoadFailed(messageID: Uuid) {
    linkPreviewController?.onInlineImageLoadFailed(messageID)
}

/**
 * Resolves the stored image bytes for an [ImageReference], the Kotlin analogue of the iOS `BubbleResolver`
 * image seam: link-preview hero and icon bytes come from the controller; inline bytes come from the
 * process-lifetime [one.meshcore.shared.chat.InlineImageCache] via the same controller.
 */
fun ChatThreadViewModel.previewImageBytes(reference: ImageReference): ByteArray? = when (reference.role) {
    ImageReference.Role.LINK_PREVIEW_IMAGE -> linkPreviewController?.previewImageBytes(reference.cacheKey)
    ImageReference.Role.LINK_PREVIEW_ICON -> linkPreviewController?.previewIconBytes(reference.cacheKey)
    ImageReference.Role.INLINE -> linkPreviewController?.inlineImageBytes(reference.cacheKey)
}
