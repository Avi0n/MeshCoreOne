import Foundation

/// Direct-message envelope for `SendQueue<DirectMessageEnvelope>`.
/// Captures the target contact at enqueue time so the drain can fetch
/// a fresh contact DTO per iteration — preserves the per-iteration
/// fetch semantics that defend against stale contact state.
public struct DirectMessageEnvelope: Sendable {
    public let messageID: UUID
    public let contactID: UUID

    public init(messageID: UUID, contactID: UUID) {
        self.messageID = messageID
        self.contactID = contactID
    }
}
