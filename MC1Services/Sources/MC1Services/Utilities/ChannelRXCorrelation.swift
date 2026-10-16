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
}
