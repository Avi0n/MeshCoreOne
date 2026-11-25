import MC1Services

/// How a Pixel Paint drawing leaves the radio. It picks the text version and the byte budget.
nonisolated enum PixelPaintTransport: Sendable {
  /// `im3:` in a direct message.
  case direct
  /// `im3:` as group text, for a radio without `CMD_SEND_CHANNEL_DATA`.
  case channelText
  /// `im4:` as group data.
  case channelData

  init(isChannel: Bool, supportsChannelData: Bool) {
    if !isChannel {
      self = .direct
    } else {
      self = supportsChannelData ? .channelData : .channelText
    }
  }

  var isChannel: Bool {
    self != .direct
  }

  /// Wire bytes counted against `byteLimit`. Channel sizes include the sender name.
  func encodedByteCount(text: String, senderName: String) throws -> Int {
    switch self {
    case .direct:
      text.utf8.count
    case .channelText:
      senderName.utf8.count + ProtocolLimits.channelSenderPrefixSeparatorUTF8Count + text.utf8.count
    case .channelData:
      try MCOImageTransport.encode(text: text, senderName: senderName).count
    }
  }

  var byteLimit: Int {
    switch self {
    case .direct: ProtocolLimits.maxDirectMessageLength
    case .channelText: ProtocolLimits.maxChannelMessageTotalLength
    case .channelData: MCOImageWireBudget.maximumChannelDataBytes
    }
  }
}
