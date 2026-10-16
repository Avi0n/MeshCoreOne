package one.meshcore.shared.chat

import one.meshcore.shared.chat.text.FormattedText
import one.meshcore.shared.model.Uuid

/**
 * Per-message inputs the item builder consumes that are not on the [MessageDto][one.meshcore.shared.backup.MessageDto]
 * itself — the resolved sender name, the base colour slot, the pair-wise grouping flags, the formatted text,
 * and the preview / inline-image / map-preview snapshots. A value-typed snapshot the caller assembles per
 * message; the view model recomputes it (flipping its value equality) as previews resolve, driving a rebuild.
 * Mirrors the Swift `MessageBuildInputs`.
 *
 * Preview fields default to their "nothing detected / not yet loaded" values so a caller without rich
 * content produces a text-only item.
 */
data class MessageBuildInputs(
    val messageID: Uuid,
    val baseColor: BaseColorSlot,
    val senderResolution: NodeNameResolution,
    val showTimestamp: Boolean,
    val showDirectionGap: Boolean,
    val showSenderName: Boolean,
    val showNewMessagesDivider: Boolean,
    val showDayDivider: Boolean = false,
    val isClusterEnd: Boolean = false,
    val incomingAvatar: IncomingAvatarIdentity? = null,
    /** The linkified text (display string plus spans); null makes the renderer use the raw string. */
    val formattedText: FormattedText? = null,
    /** Loading state of the message's single detected URL. */
    val previewState: PreviewLoadState = PreviewLoadState.IDLE,
    /** The resolved link-preview payload once [previewState] is [PreviewLoadState.LOADED]. */
    val loadedPreview: LinkPreviewDataDto? = null,
    /** The single detected URL that drives the image / link-preview fragment; null when none. */
    val cachedURL: String? = null,
    /** Whether [cachedURL] routes to the inline-image fragment (precomputed, incl. the HTML-page reroute). */
    val isInlineImageURL: Boolean = false,
    val hasInlineImageRef: Boolean = false,
    val hasPreviewImageRef: Boolean = false,
    val hasPreviewIconRef: Boolean = false,
    val imageIsGIF: Boolean = false,
    /** Cached width-over-height ratio for an inline-image [cachedURL]; null falls back to a 16:9 skeleton. */
    val inlineImageAspect: Double? = null,
    /** Latitude of the message's first linkified coordinate; drives the map-preview fragment when non-null. */
    val mapPreviewLatitude: Double? = null,
    val mapPreviewLongitude: Double? = null,
    /** True once the snapshot for this coordinate's request has resolved (cached or failed). */
    val isMapPreviewReady: Boolean = false,
    /** The pre-formatted routing path for the footer's path slot; null when there is nothing to show. */
    val formattedPath: String? = null,
)
