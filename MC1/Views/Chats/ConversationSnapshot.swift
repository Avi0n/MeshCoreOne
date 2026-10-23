import Foundation

/// Complete, internally consistent pin/other split of the conversation list.
/// Committed as a single observed value so the list can never diff pins from
/// one fetch generation against others from another.
struct ConversationSnapshot: Equatable {
  var pinned: [Conversation]
  var others: [Conversation]

  static let empty = ConversationSnapshot(pinned: [], others: [])
}
