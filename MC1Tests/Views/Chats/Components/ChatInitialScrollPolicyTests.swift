import Foundation
@testable import MC1
import Testing

/// Covers the consume-once divider target decision so a mid-conversation rebuild
/// cannot re-jump to a divider the reader already scrolled past.
@Suite("ChatInitialScrollPolicy")
struct ChatInitialScrollPolicyTests {
  // MARK: - Consume-once divider target

  @Test
  func `divider target resolves on a fresh unread conversation`() {
    let divider = UUID()
    #expect(ChatInitialScrollPolicy.openAtDividerItemID(
      hasConsumed: false,
      unreadCount: 3,
      dividerItemID: divider
    ) == divider)
  }

  @Test
  func `divider target is nil once consumed`() {
    #expect(ChatInitialScrollPolicy.openAtDividerItemID(
      hasConsumed: true,
      unreadCount: 3,
      dividerItemID: UUID()
    ) == nil)
  }

  @Test
  func `divider target is nil without an unread backlog`() {
    #expect(ChatInitialScrollPolicy.openAtDividerItemID(
      hasConsumed: false,
      unreadCount: 0,
      dividerItemID: UUID()
    ) == nil)
  }

  @Test
  func `divider target is nil when no item carries the divider`() {
    #expect(ChatInitialScrollPolicy.openAtDividerItemID(
      hasConsumed: false,
      unreadCount: 3,
      dividerItemID: nil
    ) == nil)
  }
}
