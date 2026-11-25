import Foundation
import MC1Services

/// Who originated the packet whose arrivals the path views show.
enum MessagePathSender: Equatable, Sendable {
  case localDevice
  case contact(keyPrefix: Data)
  case channelName(String)
  case unknown

  init(message: MessageDTO) {
    if message.isOutgoing {
      self = .localDevice
    } else if let keyPrefix = message.senderKeyPrefix, !keyPrefix.isEmpty {
      self = .contact(keyPrefix: keyPrefix)
    } else if message.isChannelMessage, let name = message.senderNodeName, !name.isEmpty {
      self = .channelName(name)
    } else {
      self = .unknown
    }
  }

  /// RX Log bytes are unverified, so only a full advert key match marks the local radio.
  init(entry: RxLogEntryDTO, localPublicKey: Data?) {
    switch entry.payloadType {
    case .advert:
      guard let advertKey = entry.advertPublicKey else {
        self = .unknown
        return
      }
      if advertKey == localPublicKey {
        self = .localDevice
      } else {
        self = .contact(keyPrefix: Data(advertKey.prefix(ProtocolLimits.publicKeyPrefixSize)))
      }
    case .groupText:
      if let name = entry.channelSenderName, !name.isEmpty {
        self = .channelName(name)
      } else {
        self = .unknown
      }
    case .textMessage:
      if let prefix = entry.senderPrefix, !prefix.isEmpty {
        self = .contact(keyPrefix: prefix)
      } else {
        self = .unknown
      }
    default:
      self = .unknown
    }
  }
}
