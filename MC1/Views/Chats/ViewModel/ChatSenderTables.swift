import Foundation
import MC1Services

/// Sender-resolution inputs the item bake reads. The live view model builds this
/// from its observed contact tables; a primer builds it from a local fetch.
struct ChatSenderTables: Equatable {
  let contacts: [ContactDTO]
  let nicknamesByLoweredName: [String: String]
  /// Unique lowered contact name → channel incoming-avatar identity.
  let incomingAvatars: [String: IncomingAvatarIdentity]

  static let empty = ChatSenderTables(
    contacts: [],
    nicknamesByLoweredName: [:],
    incomingAvatars: [:]
  )
}
