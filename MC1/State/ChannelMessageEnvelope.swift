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
struct ChannelMessageEnvelope: Sendable {
    let messageID: UUID
    let channelIndex: UInt8
    let isResend: Bool
    let messageText: String
    let messageTimestamp: UInt32
    let localNodeName: String?
}
