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

  /// Path join for a channel MCOimg. Group text keeps the content key, including wire time 0.
  /// Reactions use `isDatagramMCOImage`, which still includes that group-text row.
  var isDatagramPathMCOImage: Bool {
    guard isChannelMCOImage, let channelIndex else { return false }
    if isOutgoing { return true }
    guard senderTimestamp == 0 else { return false }
    let contentKey = DeduplicationKey.contentBased(
      contactID: nil,
      channelIndex: channelIndex,
      senderNodeName: senderNodeName,
      timestamp: 0,
      content: text
    )
    return deduplicationKey != contentKey
  }

  /// Nil unless this row is a path MCOimg and the data field can be rebuilt.
  var datagramDataField: Data? {
    guard isDatagramPathMCOImage,
          let name = senderNodeName, !name.isEmpty else { return nil }
    return try? MCOImageTransport.dataField(text: text, senderName: name)
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
