import Foundation

/// Direct-message envelope for `SendQueue<DirectMessageEnvelope>`.
/// Captures the target contact at enqueue time so the drain can fetch
/// a fresh contact DTO per iteration — preserves the per-iteration
/// fetch semantics that defend against stale contact state.
///
/// `isResend` is true only when the envelope represents an explicit
/// user-initiated resend of an already-sent DM. The DM queue drain
/// branches on this flag between `sendPendingDirectMessage` (no
/// `sendCount` bump) and `resendDirectMessage` (bumps `sendCount`,
/// broadcasts `MessageStatusEvent.resent`).
public struct DirectMessageEnvelope: Sendable {
    public let messageID: UUID
    public let contactID: UUID
    public let isResend: Bool

    public init(messageID: UUID, contactID: UUID, isResend: Bool = false) {
        self.messageID = messageID
        self.contactID = contactID
        self.isResend = isResend
    }
}
