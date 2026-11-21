import Foundation

/// `isResend` selects a fresh text timestamp; an MCOimg nonce is already in the captured text, so drain does not mint one.
/// Captured `messageText`, `messageTimestamp`, and `localNodeName` keep reaction indexing off a later channel or radio name.
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
