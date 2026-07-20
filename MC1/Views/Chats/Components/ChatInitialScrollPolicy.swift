import Foundation

/// Pure decisions governing the chat list's first snapshot when a conversation
/// opens: whether to hand the library its first items yet, and which item to
/// open scrolled to (the "New Messages" divider).
enum ChatInitialScrollPolicy {
  /// The library's one-shot initial positioning is spent by the first non-empty
  /// snapshot it receives, so the timeline must stay withheld until the snapshot
  /// on screen is the one it should position on.
  enum FirstSnapshotDecision: Equatable {
    /// Keep the timeline off screen; a divider target is expected but its
    /// flag-bearing item is not yet among the current items.
    case withhold
    /// Show the timeline, opening scrolled to `target` (nil opens at the bottom).
    case present(target: UUID?)
  }

  /// Decides the first snapshot for a conversation open. The divider target
  /// comes from the per-session bake, never from state a shared coordinator
  /// carries over from a previous open, and presents only once the item baked
  /// with the divider row is among the items currently on screen — a stale warm
  /// page therefore withholds. `dividerRowOnScreen` is that readiness fact,
  /// computed by the caller from the current items. `initialLoadSettled` is the
  /// escape hatch: a populate that finished (any outcome) without a divider
  /// target has nothing to wait for.
  static func firstSnapshotDecision(
    hasConsumed: Bool,
    unreadCount: Int,
    initialLoadSettled: Bool,
    dividerMessageID: UUID?,
    dividerRowOnScreen: Bool
  ) -> FirstSnapshotDecision {
    guard !hasConsumed, unreadCount > 0 else { return .present(target: nil) }
    if let dividerMessageID {
      // Present only when the flag-bearing row is on screen: id membership
      // alone can be a prior bake without the flag, and presenting that spends
      // the one-shot before a later rebake grows a row under the input bar.
      return dividerRowOnScreen ? .present(target: dividerMessageID) : .withhold
    }
    return initialLoadSettled ? .present(target: nil) : .withhold
  }
}
