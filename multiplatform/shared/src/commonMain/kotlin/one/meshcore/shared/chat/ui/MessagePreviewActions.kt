package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.ImageReference
import one.meshcore.shared.model.Uuid

/**
 * Link-preview and inline-image fetches for one thread. The view model owns the bytes and the
 * scope gate; each row only asks.
 */
internal data class MessagePreviewActions(
    /** Resolves a fragment's stored image bytes (link-preview hero/icon); the bubble decodes and memoizes. */
    val previewImageBytes: (ImageReference) -> ByteArray? = { null },
    /** Tap-to-load for a preview or inline image the scope gate parked; bypasses that gate. */
    val onManualPreviewFetch: (Uuid) -> Unit = {},
    /** Auto-fetch when a row becomes visible. */
    val onRequestPreviewFetch: (Uuid) -> Unit = {},
    /** Tap-to-retry for a preview or inline image whose fetch failed. */
    val onRetryPreviewFetch: (Uuid) -> Unit = {},
    val onInlineImageLoadFailed: (Uuid) -> Unit = {},
)
