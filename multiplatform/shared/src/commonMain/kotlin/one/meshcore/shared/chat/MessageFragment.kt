package one.meshcore.shared.chat

/**
 * One row of content inside a message bubble. A sealed hierarchy so every consumer's `when` is exhaustive:
 * adding a kind forces each consumer to handle it. Mirrors the Swift `MessageFragment` enum.
 *
 * [MessageFragmentBuilder.makeFragments] builds the applicable members, each carrying the full load state
 * its renderer needs.
 */
sealed interface MessageFragment {
    /** Plain or formatted message text. Always the first fragment. */
    data class Text(val payload: MessageTextPayload) : MessageFragment

    /** The reaction-summary badge, carrying the raw `"👍:3,❤️:2"` string parsed at render time. */
    data class ReactionSummary(val summary: String) : MessageFragment

    /** An inline image card. The payload type is qualified to disambiguate from this member's own name. */
    data class InlineImage(val image: one.meshcore.shared.chat.InlineImage) : MessageFragment

    /** A link-preview card in one of its load-state modes. */
    data class LinkPreview(val state: LinkPreviewFragmentState) : MessageFragment

    /** A map thumbnail for the message's first linkified coordinate. */
    data class MapPreview(val state: MapPreviewFragmentState) : MessageFragment

    /** A suspicious-link warning that replaces the image/preview fragment. */
    data class MalwareWarning(val url: String) : MessageFragment
}
