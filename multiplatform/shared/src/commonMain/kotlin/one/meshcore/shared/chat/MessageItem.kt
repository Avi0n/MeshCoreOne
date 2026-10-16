package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid

/**
 * One rendered row in the chat timeline. Fully structural value type: two items are equal iff every field
 * (envelope, content fragments, footer, grouping, preview flag) matches, so list diffing skips unchanged
 * rows and re-renders changed ones. Mirrors the Swift `MessageItem`.
 *
 * Equality-completeness invariant: every input that affects how the row renders must be encoded into a
 * field here. If a future change moves a rendering input onto a side channel, equality would return stale
 * "equal" for a visibly changed row. Keep the full input set on the item.
 *
 * [shouldRequestPreviewFetch] drives the row's preview-fetch task, while [content] carries every fragment
 * the bubble renders.
 */
data class MessageItem(
    val id: Uuid,
    val envelope: MessageEnvelope,
    val content: List<MessageFragment>,
    val footer: MessageFooter,
    val grouping: GroupingFlags,
    val shouldRequestPreviewFetch: Boolean,
    /** Long-press action gates stamped at build time from the source MessageDto. */
    val actionAvailability: MessageActionAvailability,
) {
    /**
     * Message-scoped identity for the bubble's preview-fetch task: the message id while a fetch is wanted,
     * null otherwise. Keying the task on this rather than the bare flag forces an edge when a reused cell
     * moves from one fetch-wanting message to another.
     */
    val previewFetchTaskID: Uuid? get() = if (shouldRequestPreviewFetch) id else null
}
