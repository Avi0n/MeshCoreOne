import Foundation
@testable import MC1
import Testing

/// Covers the pure first-snapshot decision table: resolve-in-items gating,
/// the consume-once latch, and the settled-load escape hatch.
@Suite("ChatInitialScrollPolicy")
struct ChatInitialScrollPolicyTests {
  @Test
  func `divider target presents when it resolves in the current items`() {
    let divider = UUID()
    #expect(ChatInitialScrollPolicy.firstSnapshotDecision(
      hasConsumed: false,
      unreadCount: 3,
      initialLoadSettled: true,
      dividerMessageID: divider,
      itemIndexByID: [divider: 4]
    ) == .present(target: divider))
  }

  @Test
  func `an unresolved divider target withholds, even after the load settles`() {
    #expect(ChatInitialScrollPolicy.firstSnapshotDecision(
      hasConsumed: false,
      unreadCount: 3,
      initialLoadSettled: true,
      dividerMessageID: UUID(),
      itemIndexByID: [UUID(): 0]
    ) == .withhold)
  }

  @Test
  func `no divider target withholds until the load settles`() {
    #expect(ChatInitialScrollPolicy.firstSnapshotDecision(
      hasConsumed: false,
      unreadCount: 3,
      initialLoadSettled: false,
      dividerMessageID: nil,
      itemIndexByID: [:]
    ) == .withhold)
  }

  @Test
  func `a settled load with no divider target presents at the bottom`() {
    #expect(ChatInitialScrollPolicy.firstSnapshotDecision(
      hasConsumed: false,
      unreadCount: 3,
      initialLoadSettled: true,
      dividerMessageID: nil,
      itemIndexByID: [:]
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
      itemIndexByID: [divider: 4]
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
      itemIndexByID: [divider: 4]
    ) == .present(target: nil))
  }
}
