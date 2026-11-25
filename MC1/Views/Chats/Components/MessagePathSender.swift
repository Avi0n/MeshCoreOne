import Foundation
import MC1Services

/// Who originated the packet whose arrivals the path views show.
struct MessagePathSender: Equatable, Sendable {
  let keyPrefix: Data?
  /// Sender name from channel text; set only for channel packets.
  let channelSenderName: String?
  let isLocalDevice: Bool

  init(keyPrefix: Data?, channelSenderName: String?, isLocalDevice: Bool) {
    self.keyPrefix = keyPrefix
    self.channelSenderName = channelSenderName
    self.isLocalDevice = isLocalDevice
  }

  init(message: MessageDTO) {
    self.init(
      keyPrefix: message.senderKeyPrefix,
      channelSenderName: message.isChannelMessage ? message.senderNodeName : nil,
      isLocalDevice: message.isOutgoing
    )
  }

  /// RX Log bytes are unverified, so only a full advert key match marks the local radio.
  init(entry: RxLogEntryDTO, localPublicKey: Data?) {
    switch entry.payloadType {
    case .advert:
      // A truncated advert carries no usable key; the firmware logs frames before parsing.
      guard entry.packetPayload.count >= ProtocolLimits.publicKeySize else {
        self.init(keyPrefix: nil, channelSenderName: nil, isLocalDevice: false)
        return
      }
      let advertKey = Data(entry.packetPayload.prefix(ProtocolLimits.publicKeySize))
      self.init(
        keyPrefix: Data(advertKey.prefix(ProtocolLimits.publicKeyPrefixSize)),
        channelSenderName: nil,
        isLocalDevice: advertKey == localPublicKey
      )
    case .groupText:
      self.init(keyPrefix: nil, channelSenderName: entry.channelSenderName, isLocalDevice: false)
    case .textMessage:
      self.init(keyPrefix: entry.senderPrefix, channelSenderName: nil, isLocalDevice: false)
    default:
      self.init(keyPrefix: nil, channelSenderName: nil, isLocalDevice: false)
    }
  }
}
