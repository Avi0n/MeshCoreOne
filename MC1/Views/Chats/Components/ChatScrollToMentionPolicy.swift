import Foundation

enum ChatScrollToMentionPolicy {
  static func shouldScrollToBottom(mentionTargetID: UUID?, newestItemID: UUID?) -> Bool {
    guard let mentionTargetID, let newestItemID else { return false }
    return mentionTargetID == newestItemID
  }

  /// Picks which off-screen mention a button tap scrolls to. `offscreenMentions` is ordered
  /// oldest-to-newest, so the newest (last) is the latest unread the user hasn't reached;
  /// repeated taps consume it and walk upward through older mentions, showing the earliest last.
  static func nextTarget(offscreenMentions: [UUID]) -> UUID? {
    offscreenMentions.last
  }
}
