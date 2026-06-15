import Foundation

/// One row in the chat timeline as the bubble sees it. Built from a `MessageDTO`
/// plus current render state plus per-message build inputs. `Sendable` so it can
/// flow across the UIKit-table cell-config boundary without copy-on-write
/// concerns.
///
/// Equatable invariant: bubble views (`MessageBubbleView`, `UnifiedMessageBubble`,
/// `BubbleFragmentStack`) conform to `Equatable` with `==` defined on this
/// struct alone, so SwiftUI can skip rebodies when the row identity and content
/// are unchanged. This is safe only because every input that affects bubble
/// rendering is encoded into this struct by `rebuildDisplayItem`: preview state
/// (via `shouldRequestPreviewFetch` and link-preview fragments), reactions,
/// inline images, footer status, and grouping flags. If a future refactor moves
/// any of those fields out of `MessageItem` (for example onto a side-channel
/// store on `ChatCoordinator`), the bubble Equatable check would silently
/// return stale renders. Verify the rebuild path still writes the full set of
/// inputs before changing this invariant.
public struct MessageItem: Identifiable, Sendable, Hashable {
    public let id: UUID
    public let envelope: MessageEnvelope
    public let content: [MessageFragment]
    public let footer: MessageFooter
    public let grouping: GroupingFlags
    public let shouldRequestPreviewFetch: Bool
    /// The lone http/https URL when the whole message is exactly that link, else
    /// nil. Precomputed so the bubble's link-only gesture handling reads it from
    /// the item rather than re-detecting it from `message.text` on every render.
    public let soleURL: URL?

    public init(
        id: UUID,
        envelope: MessageEnvelope,
        content: [MessageFragment],
        footer: MessageFooter,
        grouping: GroupingFlags,
        shouldRequestPreviewFetch: Bool,
        soleURL: URL? = nil
    ) {
        self.id = id
        self.envelope = envelope
        self.content = content
        self.footer = footer
        self.grouping = grouping
        self.shouldRequestPreviewFetch = shouldRequestPreviewFetch
        self.soleURL = soleURL
    }

    /// Returns a new item with the supplied envelope and/or footer overridden.
    /// Eliminates the 6-field rebuild at single-row mutation sites.
    public func with(
        envelope: MessageEnvelope? = nil,
        footer: MessageFooter? = nil
    ) -> MessageItem {
        MessageItem(
            id: id,
            envelope: envelope ?? self.envelope,
            content: content,
            footer: footer ?? self.footer,
            grouping: grouping,
            shouldRequestPreviewFetch: shouldRequestPreviewFetch,
            soleURL: soleURL
        )
    }
}
