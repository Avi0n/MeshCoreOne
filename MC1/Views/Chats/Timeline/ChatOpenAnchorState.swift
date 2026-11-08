import Foundation

/// Captures one open's unread count and keeps Populate completion from replaying a consumed Anchor.
struct ChatOpenAnchorState: Equatable {
  /// The list spends its initial positioning on its first non-empty snapshot,
  /// so an unread open waits until the current divider-bearing row is ready.
  enum FirstSnapshotDecision: Equatable {
    case withhold
    /// A nil target opens at the bottom.
    case present(target: UUID?)
  }

  private enum Phase {
    case pendingPopulate
    case populateSettled
    case consumed
  }

  private let unreadCount: Int
  private var phase = Phase.pendingPopulate

  init(unreadCount: Int) {
    self.unreadCount = unreadCount
  }

  var hasUnread: Bool {
    unreadCount > 0
  }

  mutating func completePopulate(_ outcome: ChatTimelinePopulator.Outcome) {
    guard phase == .pendingPopulate else { return }
    switch outcome {
    case .loaded, .unavailable, .failed:
      phase = .populateSettled
    case .cancelled:
      break
    }
  }

  mutating func consumeAnchor() {
    phase = .consumed
  }

  func firstSnapshot(
    dividerMessageID: UUID?,
    dividerRowOnScreen: Bool
  ) -> FirstSnapshotDecision {
    guard hasUnread, phase != .consumed else { return .present(target: nil) }
    if let dividerMessageID {
      // Membership alone can come from a prior Bake. Wait for the flag-bearing
      // row before spending the list's one-shot initial positioning.
      return dividerRowOnScreen ? .present(target: dividerMessageID) : .withhold
    }
    return phase == .populateSettled ? .present(target: nil) : .withhold
  }
}
