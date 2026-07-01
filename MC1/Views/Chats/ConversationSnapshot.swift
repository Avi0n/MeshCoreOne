import Foundation

/// Complete, internally consistent favorite/other split of the conversation list.
/// Committed as a single observed value so the list can never diff favorites from
/// one fetch generation against others from another.
struct ConversationSnapshot: Equatable {
  var favorites: [Conversation]
  var others: [Conversation]

  static let empty = ConversationSnapshot(favorites: [], others: [])
}
