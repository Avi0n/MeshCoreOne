import Foundation

/// Pure decisions governing how the chat list treats its first geometry report
/// after opening at an initial scroll target (the "New Messages" divider).
enum ChatInitialScrollPolicy {
  /// The divider id the conversation opens scrolled to: nil once consumed, when
  /// there is no unread backlog, or when no item carries the baked divider — so
  /// a list rebuild mid-conversation does not re-jump to a divider the reader
  /// scrolled past.
  static func openAtDividerItemID(hasConsumed: Bool, unreadCount: Int, dividerItemID: UUID?) -> UUID? {
    guard !hasConsumed, unreadCount > 0 else { return nil }
    return dividerItemID
  }
}
