import Foundation
import MC1Services

/// Sender-resolution inputs the item bake reads. The live view model builds this
/// from its observed contact tables; a primer builds it from a local fetch.
struct ChatSenderTables: Equatable {
  let contacts: [ContactDTO]
  let nicknamesByLoweredName: [String: String]

  static let empty = ChatSenderTables(contacts: [], nicknamesByLoweredName: [:])
}
