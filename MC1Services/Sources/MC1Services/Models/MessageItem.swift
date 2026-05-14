import Foundation

/// One row in the chat timeline as the bubble sees it. Built from a `MessageDTO`
/// plus current render state plus per-message build inputs. `Sendable` so it can
/// flow across the UIKit-table cell-config boundary without copy-on-write
/// concerns.
public struct MessageItem: Identifiable, Sendable, Hashable {
    public let id: UUID
    public let envelope: MessageEnvelope
    public let content: [MessageFragment]
    public let footer: MessageFooter
    public let grouping: GroupingFlags
    public let shouldRequestPreviewFetch: Bool

    public init(
        id: UUID,
        envelope: MessageEnvelope,
        content: [MessageFragment],
        footer: MessageFooter,
        grouping: GroupingFlags,
        shouldRequestPreviewFetch: Bool
    ) {
        self.id = id
        self.envelope = envelope
        self.content = content
        self.footer = footer
        self.grouping = grouping
        self.shouldRequestPreviewFetch = shouldRequestPreviewFetch
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
            shouldRequestPreviewFetch: shouldRequestPreviewFetch
        )
    }
}
