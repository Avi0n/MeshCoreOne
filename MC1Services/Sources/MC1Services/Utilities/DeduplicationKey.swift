import CryptoKit
import Foundation

/// Single source of truth for content-based deduplication key generation.
/// Used by SyncCoordinator (live sync), PersistenceStore+Migration (on-device backfill),
/// and PersistenceStore+Backup (export/import).
enum DeduplicationKey {
  static let channelPrefix = "ch-"
  static let directMessagePrefix = "dm-"
  static let outgoingIdentityPrefix = "out-"
  static let unknownContactPlaceholder = "unknown"

  /// Not a decimal timestamp, so an MCOimg key cannot collide with a group-text key.
  static let channelMCOImageMarker = "mcoimg"

  /// Incoming channel MCOimg identity: slot, marker, and data field. No clock.
  static func channelMCOImage(channelIndex: UInt8, dataField: Data) -> String {
    let encoded = dataField.map { String(format: "%02x", $0) }.joined()
    return "\(channelPrefix)\(channelIndex)-\(channelMCOImageMarker)-\(encoded)"
  }

  static func contentBased(
    contactID: UUID?,
    channelIndex: UInt8?,
    senderNodeName: String?,
    timestamp: UInt32,
    content: String
  ) -> String {
    let contentHash = SHA256.hash(data: Data(content.utf8))
    let hashPrefix = contentHash.prefix(4).map { String(format: "%02X", $0) }.joined()
    if let channelIndex {
      return "\(channelPrefix)\(channelIndex)-\(timestamp)-\(senderNodeName ?? "")-\(hashPrefix)"
    }
    let contactSegment = contactID?.uuidString ?? unknownContactPlaceholder
    return "\(directMessagePrefix)\(contactSegment)-\(timestamp)-\(hashPrefix)"
  }
}
