import Foundation
import MC1Services

/// Shared helper for resolving a channel sender's display name to stored contacts.
///
/// Channel messages carry a human-readable sender name rather than a stable ID, so
/// features that need to act on the sender (Block Sender, Send DM) rely on
/// case-insensitive name matching against the local contact list.
enum SenderContactMatcher {
  /// Returns contacts whose `name` matches `senderName` case-insensitively.
  ///
  /// - Parameters:
  ///   - contacts: The contact list to filter, typically fetched from `PersistenceStore`.
  ///   - senderName: The sender display name from the channel message.
  ///   - excludeBlocked: When `true`, already-blocked contacts are omitted.
  static func filter(
    contacts: [ContactDTO],
    senderName: String,
    excludeBlocked: Bool = false
  ) -> [ContactDTO] {
    contacts.filter { contact in
      (!excludeBlocked || !contact.isBlocked)
        && contact.name.localizedCaseInsensitiveCompare(senderName) == .orderedSame
    }
  }
}
