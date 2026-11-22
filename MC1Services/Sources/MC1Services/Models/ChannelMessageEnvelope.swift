import Foundation

/// Channel-message envelope for `SendQueue<ChannelMessageEnvelope>`.
///
/// `isResend` is load-bearing: a resend calls `MessageService.resendChannelMessage`, which
/// stamps a fresh timestamp so the mesh dedup ring (128 cyclic slots) does not silently drop
/// the retry. A fresh send calls `sendPendingChannelMessage`, which keeps the original
/// timestamp. An MCOimg resend already carries a fresh nonce in the captured text.
///
/// `messageText`, `messageTimestamp`, and `localNodeName` are captured at enqueue time so
/// post-send reaction indexing does not read a later channel selection or device name.
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
