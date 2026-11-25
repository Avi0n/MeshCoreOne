import Foundation

/// The sender name and message hash a channel reaction carries to reach one message.
public struct ChannelReactionTarget: Sendable, Hashable {
  public let senderName: String
  public let messageHash: String

  public init(senderName: String, messageHash: String) {
    self.senderName = senderName
    self.messageHash = messageHash
  }

  /// Group text: the hash covers the body and the sender timestamp.
  public init(senderName: String, text: String, timestamp: UInt32) {
    self.init(senderName: senderName, messageHash: ReactionParser.generateMessageHash(text: text, timestamp: timestamp))
  }
}

public extension MessageDTO {
  /// Nil for a direct message or an unknown sender. An outgoing row uses its stored sender, else
  /// `localNodeName`. A group datagram has no sender clock, so its hash covers the data field.
  func channelReactionTarget(localNodeName: String?) -> ChannelReactionTarget? {
    guard channelIndex != nil else { return nil }
    let sender = isOutgoing ? senderNodeName ?? localNodeName : senderNodeName
    guard let sender, !sender.isEmpty else { return nil }
    guard isDatagramMCOImage else {
      return ChannelReactionTarget(senderName: sender, text: text, timestamp: reactionTimestamp)
    }
    guard let datagramDataField else { return nil }
    return ChannelReactionTarget(senderName: sender, messageHash: ReactionParser.generateUntimedHash(datagramDataField))
  }
}
