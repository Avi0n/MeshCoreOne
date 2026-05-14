import Foundation

/// Per-message identity, direction, status, sender — everything not specific
/// to one content fragment.
///
/// `hasFailed` is true only for `MessageStatus.failed`. Transitional statuses
/// (`.pending`, `.sending`, `.retrying`) do not set this flag.
public struct MessageEnvelope: Sendable, Hashable {
    public let messageID: UUID
    public let isOutgoing: Bool
    public let senderName: String
    public let senderResolution: NodeNameResolution
    public let status: MessageStatus
    public let date: Date
    public let hasFailed: Bool
    public let containsSelfMention: Bool
    public let mentionSeen: Bool

    public init(
        messageID: UUID,
        isOutgoing: Bool,
        senderName: String,
        senderResolution: NodeNameResolution,
        status: MessageStatus,
        date: Date,
        hasFailed: Bool,
        containsSelfMention: Bool,
        mentionSeen: Bool
    ) {
        self.messageID = messageID
        self.isOutgoing = isOutgoing
        self.senderName = senderName
        self.senderResolution = senderResolution
        self.status = status
        self.date = date
        self.hasFailed = hasFailed
        self.containsSelfMention = containsSelfMention
        self.mentionSeen = mentionSeen
    }

    /// Returns a new envelope with `status` (and the derived `hasFailed`)
    /// overridden. Eliminates the 9-field rebuild at status-flip sites.
    public func with(status: MessageStatus) -> MessageEnvelope {
        MessageEnvelope(
            messageID: messageID,
            isOutgoing: isOutgoing,
            senderName: senderName,
            senderResolution: senderResolution,
            status: status,
            date: date,
            hasFailed: status == .failed,
            containsSelfMention: containsSelfMention,
            mentionSeen: mentionSeen
        )
    }
}
