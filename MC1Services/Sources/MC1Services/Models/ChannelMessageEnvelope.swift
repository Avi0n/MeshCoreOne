import Foundation

/// Channel-message envelope for `SendQueue<ChannelMessageEnvelope>`.
///
/// `isResend` is load-bearing: a resend must call
/// `MessageService.resendChannelMessage` (which stamps a fresh timestamp
/// so the mesh dedup ring at 128 cyclic slots does not silently drop the
/// retry), while a fresh send must call `sendPendingChannelMessage`
/// which preserves the original timestamp.
///
/// `messageText`, `messageTimestamp`, and `localNodeName` are captured at
/// enqueue time so the post-send `reactionService.indexMessage(...)` call
/// can read them without depending on view-model state at drain time.
/// During the mesh round-trip the user can navigate to a different channel
/// or rename the connected device — reading mutable state across that
/// window risks tagging the message with stale identity, or indexing
/// against the wrong message entirely.
public struct ChannelMessageEnvelope: Sendable {
    public let messageID: UUID
    public let channelIndex: UInt8
    public let isResend: Bool
    public let messageText: String
    public let messageTimestamp: UInt32
    public let localNodeName: String?

    public init(
        messageID: UUID,
        channelIndex: UInt8,
        isResend: Bool,
        messageText: String,
        messageTimestamp: UInt32,
        localNodeName: String?
    ) {
        self.messageID = messageID
        self.channelIndex = channelIndex
        self.isResend = isResend
        self.messageText = messageText
        self.messageTimestamp = messageTimestamp
        self.localNodeName = localNodeName
    }
}
