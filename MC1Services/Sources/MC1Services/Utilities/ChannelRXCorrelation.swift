import Foundation
import MeshCore

/// Pure matcher over decrypted RX log entries. No actor, no crypto.
enum ChannelRXCorrelation {
  /// Channel RX rows whose decrypted `"sender: body"` matches `deduplicationKey`, oldest first.
  static func matching(
    _ entries: [RxLogEntryDTO],
    deduplicationKey: String?
  ) -> [RxLogEntryDTO] {
    guard let deduplicationKey else { return [] }

    return entries.filter { entry in
      guard entry.payloadType == .groupText else { return false }
      guard entry.decryptStatus == .success else { return false }
      guard let decodedText = entry.decodedText else { return false }
      guard let channelIndex = entry.channelIndex else { return false }
      guard let senderTimestamp = entry.senderTimestamp else { return false }
      guard let (sender, body) = ChannelMessageFormat.parse(decodedText) else {
        return false
      }
      let candidate = DeduplicationKey.contentBased(
        contactID: nil,
        channelIndex: channelIndex,
        senderNodeName: sender,
        timestamp: senderTimestamp,
        content: body
      )
      return candidate == deduplicationKey
    }
    .sorted { $0.receivedAt < $1.receivedAt }
  }

  /// Group-datagram rows whose `0x0120` data field equals `blob`, oldest first.
  /// Group text is not a match. Other data types are not a match.
  static func matchingDatagrams(
    _ entries: [RxLogEntryDTO],
    blob: Data,
    radioID: UUID,
    channelIndex: UInt8
  ) -> [RxLogEntryDTO] {
    entries.filter { entry in
      entry.payloadType == .groupData &&
        entry.radioID == radioID &&
        entry.channelIndex == channelIndex &&
        entry.datagramDataType == MCOImageTransport.applicationDataType &&
        entry.datagramData == blob
    }
    .sorted { $0.receivedAt < $1.receivedAt }
  }
}
