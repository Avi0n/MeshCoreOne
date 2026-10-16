package one.meshcore.shared.chat

/**
 * Value-typed link-preview payload the loaded [LinkPreviewFragmentState.Mode.Loaded] mode carries. [url] is
 * the identity; the hero/icon bytes are referenced out-of-band through [ImageReference] handles rather than
 * inlined here, so item equality stays cheap. Mirrors the render-relevant subset of the Swift
 * `LinkPreviewDataDTO`; the Room-backed cache entity (with the stored image bytes) lands with the
 * link-preview slice. Structural equality reloads the row when any displayed field changes.
 */
data class LinkPreviewDataDto(
    val url: String,
    val title: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val fetchedAtEpochSeconds: Double = 0.0,
)
