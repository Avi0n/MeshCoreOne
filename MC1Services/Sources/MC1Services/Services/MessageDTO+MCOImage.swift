import Foundation

public extension MessageDTO {
  var mcoImage: MCOImageContent? {
    MCOImageCodec.content(from: text)
  }

  var isChannelMCOImage: Bool {
    channelIndex != nil && MCOImageCodec.isMCOImageText(text)
  }

  /// A channel MCOimg sent as group data, which has no sender clock. An outgoing row stores `im4:`.
  /// An incoming row stores sender time 0 and a key that is not the group-text key at wire time 0.
  var isDatagramMCOImage: Bool {
    guard isChannelMCOImage, let channelIndex else { return false }
    if isOutgoing { return MCOImageCodec.isChannelDataText(text) }
    guard senderTimestamp == 0 else { return false }
    let groupTextKey = DeduplicationKey.contentBased(
      contactID: nil,
      channelIndex: channelIndex,
      senderNodeName: senderNodeName,
      timestamp: 0,
      content: text
    )
    return deduplicationKey != groupTextKey
  }

  /// False for an incoming datagram MCOimg: the packet carries no send time, so only receipt is known.
  var hasWireSendTime: Bool {
    isOutgoing || !isDatagramMCOImage
  }

  /// The group-datagram data field this row went out or arrived as. Nil for any other row.
  var datagramDataField: Data? {
    guard isDatagramMCOImage, let name = senderNodeName, !name.isEmpty else { return nil }
    return try? MCOImageTransport.dataField(text: text, senderName: name)
  }
}
