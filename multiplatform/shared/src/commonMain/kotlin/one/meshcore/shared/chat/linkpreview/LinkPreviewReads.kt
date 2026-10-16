package one.meshcore.shared.chat.linkpreview

import one.meshcore.shared.chat.LinkPreviewDataDto
import one.meshcore.shared.chat.PreviewLoadState
import one.meshcore.shared.model.Uuid

/** The current load state for [messageID], defaulting to [PreviewLoadState.IDLE]. */
fun LinkPreviewController.previewState(messageID: Uuid): PreviewLoadState =
    previewStates[messageID] ?: PreviewLoadState.IDLE

/** The detected preview URL for [messageID]; null when the message carries none. */
fun LinkPreviewController.cachedUrl(messageID: Uuid): String? = detectedUrls[messageID]

/** The render-facing payload once [messageID] has resolved to [PreviewLoadState.LOADED], else null. */
fun LinkPreviewController.loadedPreview(messageID: Uuid): LinkPreviewDataDto? = loadedPreviews[messageID]?.let {
    LinkPreviewDataDto(
        url = it.url,
        title = it.title,
        imageWidth = it.imageWidth,
        imageHeight = it.imageHeight,
        fetchedAtEpochSeconds = it.fetchedAtEpochSeconds,
    )
}

/** The resolved hero bytes for [messageID], for the view model's image resolver; null when none. */
fun LinkPreviewController.previewImageBytes(messageID: Uuid): ByteArray? = loadedPreviews[messageID]?.imageBytes

/** The resolved icon bytes for [messageID], for the view model's image resolver; null when none. */
fun LinkPreviewController.previewIconBytes(messageID: Uuid): ByteArray? = loadedPreviews[messageID]?.iconBytes

/** Encoded inline-image bytes for [messageID], or null when this row has not resolved to Loaded. */
fun LinkPreviewController.inlineImageBytes(messageID: Uuid): ByteArray? = loadedInline[messageID]?.bytes

/** Whether the loaded inline image for [messageID] is a GIF. False when none. */
fun LinkPreviewController.inlineImageIsGIF(messageID: Uuid): Boolean = loadedInline[messageID]?.isGIF == true
