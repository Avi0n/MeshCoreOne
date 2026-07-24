import Foundation
@testable import MC1
import Testing

/// Covers the pure first-snapshot decision table: divider-row-on-screen
/// gating, the consume-once latch, and the settled-load escape hatch.
@Suite("ChatInitialScrollPolicy")
struct ChatInitialScrollPolicyTests {
  @Test
  func `divider target presents when its baked row is on screen`() {
    let divider = UUID()
    #expect(ChatInitialScrollPolicy.firstSnapshotDecision(
      hasConsumed: false,
      unreadCount: 3,
      initialLoadSettled: true,
      dividerMessageID: divider,
      dividerRowOnScreen: true
    ) == .present(target: divider))
  }

  @Test
  func `an unresolved divider target withholds, even after the load settles`() {
    #expect(ChatInitialScrollPolicy.firstSnapshotDecision(
      hasConsumed: false,
      unreadCount: 3,
      initialLoadSettled: true,
      dividerMessageID: UUID(),
      dividerRowOnScreen: false
    ) == .withhold)
  }

  @Test
  func `a resolved divider target whose item lacks the divider row withholds`() {
    let divider = UUID()
    #expect(ChatInitialScrollPolicy.firstSnapshotDecision(
      hasConsumed: false,
      unreadCount: 3,
      initialLoadSettled: true,
      dividerMessageID: divider,
      dividerRowOnScreen: false
    ) == .withhold)
  }

  @Test
  func `no divider target withholds until the load settles`() {
    #expect(ChatInitialScrollPolicy.firstSnapshotDecision(
      hasConsumed: false,
      unreadCount: 3,
      initialLoadSettled: false,
      dividerMessageID: nil,
      dividerRowOnScreen: false
    ) == .withhold)
  }

  @Test
  func `a settled load with no divider target presents at the bottom`() {
    #expect(ChatInitialScrollPolicy.firstSnapshotDecision(
      hasConsumed: false,
      unreadCount: 3,
      initialLoadSettled: true,
      dividerMessageID: nil,
      dividerRowOnScreen: false
    ) == .present(target: nil))
  }

  @Test
  func `the target is retired once consumed`() {
    let divider = UUID()
    #expect(ChatInitialScrollPolicy.firstSnapshotDecision(
      hasConsumed: true,
      unreadCount: 3,
      initialLoadSettled: true,
      dividerMessageID: divider,
      dividerRowOnScreen: true
    ) == .present(target: nil))
  }

  @Test
  func `no unread backlog presents immediately with no target`() {
    let divider = UUID()
    #expect(ChatInitialScrollPolicy.firstSnapshotDecision(
      hasConsumed: false,
      unreadCount: 0,
      initialLoadSettled: false,
      dividerMessageID: divider,
      dividerRowOnScreen: true
    ) == .present(target: nil))
  }
}
