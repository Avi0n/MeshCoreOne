import Foundation

public extension MessageDTO {
  var mcoImage: MCOImageContent? {
    MCOImageCodec.content(from: text)
  }

  var isChannelMCOImage: Bool {
    channelIndex != nil && MCOImageCodec.isMCOImageText(text)
  }

  /// A channel MCOimg with no packet sender timestamp.
  /// Incoming `senderTimestamp` nil is an accepted packet time. Zero is the datagram tuple.
  /// Every outgoing channel MCOimg has no packet sender timestamp.
  var isDatagramMCOImage: Bool {
    guard isChannelMCOImage else { return false }
    if isOutgoing { return true }
    return senderTimestamp == 0
  }

  /// Nil when the name is missing or the datagram bytes cannot be rebuilt.
  func mcoImageReactionTarget() -> (senderName: String, messageHash: String)? {
    guard isChannelMCOImage else { return nil }
    guard let senderNodeName, !senderNodeName.isEmpty else { return nil }
    if isDatagramMCOImage {
      guard let data = try? MCOImageTransport.dataField(text: text, senderName: senderNodeName) else { return nil }
      return (senderNodeName, ReactionParser.generateUntimedHash(data))
    }
    return (senderNodeName, ReactionParser.generateMessageHash(text: text, timestamp: reactionTimestamp))
  }
}
