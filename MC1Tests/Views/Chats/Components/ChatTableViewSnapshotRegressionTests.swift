@testable import MC1
import SwiftUI
import Testing
import UIKit

@Suite("ChatTableView Snapshot Regression Tests")
@MainActor
struct ChatTableViewSnapshotRegressionTests {
  private struct TestMessageItem: Identifiable, Hashable {
    let id: UUID
    let text: String
    let revision: Int
  }

  private func waitForRowCount(
    _ expectedCount: Int,
    in controller: ChatTableViewController<TestMessageItem, Text>,
    context: String
  ) async throws {
    try await waitUntil(
      timeout: .seconds(30),
      pollingInterval: .milliseconds(20),
      "table rows should match expected count for \(context)"
    ) {
      controller.tableView.numberOfRows(inSection: 0) == expectedCount
    }
  }

  @Test
  func `Pagination near-top callback is suppressed while older messages are loading`() {
    let controller = ChatTableViewController<TestMessageItem, Text>()
    controller.configure { item in
      Text(item.text)
    }
    controller.loadViewIfNeeded()
    controller.tableView.frame = CGRect(x: 0, y: 0, width: 320, height: 600)

    // Small dataset so all rows fall within the near-top threshold regardless of layout
    let items = (0..<5).map { index in
      TestMessageItem(id: UUID(), text: "Message \(index)", revision: 0)
    }
    controller.updateItems(items, animated: false)
    controller.tableView.layoutIfNeeded()

    var callCount = 0
    var capturedRelease: (@MainActor () -> Void)?
    controller.onNearTop = { release in
      callCount += 1
      capturedRelease = release
    }

    controller.scrollViewDidScroll(controller.tableView)
    controller.flushScrollObservationsForTests()
    let baseline = callCount
    #expect(baseline > 0, "Baseline should call onNearTop at least once when not loading")

    controller.isLoadingOlderMessages = true
    controller.scrollViewDidScroll(controller.tableView)
    controller.flushScrollObservationsForTests()
    controller.scrollViewDidScroll(controller.tableView)
    controller.flushScrollObservationsForTests()
    #expect(callCount == baseline, "onNearTop must be suppressed while loading older messages")

    controller.isLoadingOlderMessages = false
    capturedRelease?()
    capturedRelease = nil
    controller.scrollViewDidScroll(controller.tableView)
    controller.flushScrollObservationsForTests()
    #expect(callCount > baseline, "onNearTop must resume after release is called")
  }

  @Test
  func `onNearTop latch suppresses duplicate fires until release is called`() {
    let controller = ChatTableViewController<TestMessageItem, Text>()
    controller.configure { item in
      Text(item.text)
    }
    controller.loadViewIfNeeded()
    controller.tableView.frame = CGRect(x: 0, y: 0, width: 320, height: 600)

    let items = (0..<5).map { index in
      TestMessageItem(id: UUID(), text: "Message \(index)", revision: 0)
    }
    controller.updateItems(items, animated: false)
    controller.tableView.layoutIfNeeded()

    var callCount = 0
    var capturedRelease: (@MainActor () -> Void)?
    controller.onNearTop = { release in
      callCount += 1
      capturedRelease = release
    }

    controller.scrollViewDidScroll(controller.tableView)
    controller.flushScrollObservationsForTests()
    let baseline = callCount
    #expect(baseline == 1, "First near-top tick should fire onNearTop once")

    // Multiple scroll ticks before release is called — the controller-owned
    // latch must suppress them so the view model isn't bombarded with redundant Task spawns
    controller.scrollViewDidScroll(controller.tableView)
    controller.flushScrollObservationsForTests()
    controller.scrollViewDidScroll(controller.tableView)
    controller.flushScrollObservationsForTests()
    controller.scrollViewDidScroll(controller.tableView)
    controller.flushScrollObservationsForTests()
    #expect(callCount == baseline, "Latch must suppress fires until release is called")

    // Simulate the consumer's pagination work completing
    capturedRelease?()
    capturedRelease = nil

    controller.scrollViewDidScroll(controller.tableView)
    controller.flushScrollObservationsForTests()
    #expect(callCount == baseline + 1, "After release, the latch resets and the next near-top fires")
  }

  @Test
  func `onNearTop release clears latch even if isLoadingOlderMessages never flips`() {
    let controller = ChatTableViewController<TestMessageItem, Text>()
    controller.configure { item in
      Text(item.text)
    }
    controller.loadViewIfNeeded()
    controller.tableView.frame = CGRect(x: 0, y: 0, width: 320, height: 600)

    let items = (0..<5).map { index in
      TestMessageItem(id: UUID(), text: "Message \(index)", revision: 0)
    }
    controller.updateItems(items, animated: false)
    controller.tableView.layoutIfNeeded()

    var callCount = 0
    var capturedRelease: (@MainActor () -> Void)?
    controller.onNearTop = { release in
      callCount += 1
      capturedRelease = release
    }

    controller.scrollViewDidScroll(controller.tableView)
    controller.flushScrollObservationsForTests()
    #expect(callCount == 1)

    // Simulate the view model short-circuiting (e.g., hasMoreMessages == false) —
    // isLoadingOlderMessages never transitions, but the consumer still calls release
    capturedRelease?()
    capturedRelease = nil

    controller.scrollViewDidScroll(controller.tableView)
    controller.flushScrollObservationsForTests()
    #expect(callCount == 2, "Release must clear the latch even when isLoadingOlder never flipped")
  }

  @Test
  func `Auto-scroll defers while user is dragging and fires on drag end`() {
    let controller = ChatTableViewController<TestMessageItem, Text>()
    controller.configure { item in
      Text(item.text)
    }
    controller.loadViewIfNeeded()
    controller.tableView.frame = CGRect(x: 0, y: 0, width: 320, height: 600)

    let initialItems = (0..<5).map { index in
      TestMessageItem(id: UUID(), text: "Message \(index)", revision: 0)
    }
    controller.updateItems(initialItems, animated: false)
    controller.tableView.layoutIfNeeded()

    controller.scrollViewWillBeginDragging(controller.tableView)

    var updatedItems = initialItems
    updatedItems.append(TestMessageItem(id: UUID(), text: "Incoming during drag", revision: 0))
    controller.updateItems(updatedItems, animated: false)

    #expect(controller.deferredScrollToBottomPending, "Auto-scroll should defer while user is dragging")

    controller.scrollViewDidEndDragging(controller.tableView, willDecelerate: false)

    #expect(!controller.deferredScrollToBottomPending, "Deferred latch should clear when drag ends without deceleration")
  }

  @Test
  func `Deferred messages count as unread if user releases scrolled away from bottom`() {
    let controller = ChatTableViewController<TestMessageItem, Text>()
    controller.configure { item in
      Text(item.text)
    }
    controller.loadViewIfNeeded()
    controller.tableView.frame = CGRect(x: 0, y: 0, width: 320, height: 600)

    let initialItems = (0..<50).map { index in
      TestMessageItem(id: UUID(), text: "Message \(index)", revision: 0)
    }
    controller.updateItems(initialItems, animated: false)
    controller.tableView.layoutIfNeeded()

    let initialUnread = controller.unreadCount

    controller.scrollViewWillBeginDragging(controller.tableView)

    // Mid-drag before user has actually scrolled past the at-bottom threshold:
    // wasAtBottom == true latches the deferred-scroll flag
    var updatedItems = initialItems
    updatedItems.append(TestMessageItem(id: UUID(), text: "Incoming during drag", revision: 0))
    controller.updateItems(updatedItems, animated: false)

    #expect(controller.deferredScrollToBottomPending)
    #expect(controller.deferredScrollMessageCount == 1)

    // User now drags far away from bottom; isAtBottom flips false
    controller.tableView.contentOffset.y = 500
    controller.scrollViewDidScroll(controller.tableView)
    controller.flushScrollObservationsForTests()
    #expect(!controller.isAtBottom, "Scrolling past threshold should flip isAtBottom false")

    controller.scrollViewDidEndDragging(controller.tableView, willDecelerate: false)

    #expect(!controller.deferredScrollToBottomPending, "Deferred latch should clear after drag end")
    #expect(controller.deferredScrollMessageCount == 0, "Deferred count should reset after drag end")
    #expect(controller.unreadCount == initialUnread + 1, "Deferred message should count as unread when released away from bottom")
  }

  @Test
  func `Auto-scroll defers through deceleration and fires when decelerating ends`() {
    let controller = ChatTableViewController<TestMessageItem, Text>()
    controller.configure { item in
      Text(item.text)
    }
    controller.loadViewIfNeeded()
    controller.tableView.frame = CGRect(x: 0, y: 0, width: 320, height: 600)

    let initialItems = (0..<5).map { index in
      TestMessageItem(id: UUID(), text: "Message \(index)", revision: 0)
    }
    controller.updateItems(initialItems, animated: false)
    controller.tableView.layoutIfNeeded()

    controller.scrollViewWillBeginDragging(controller.tableView)

    var updatedItems = initialItems
    updatedItems.append(TestMessageItem(id: UUID(), text: "Incoming during drag", revision: 0))
    controller.updateItems(updatedItems, animated: false)

    controller.scrollViewDidEndDragging(controller.tableView, willDecelerate: true)
    #expect(controller.deferredScrollToBottomPending, "Latch should persist through deceleration phase")

    controller.scrollViewDidEndDecelerating(controller.tableView)
    #expect(!controller.deferredScrollToBottomPending, "Deferred latch should clear after deceleration ends")
  }

  @Test
  func `Scroll-to-item row resolves from the applied snapshot, never the leading items model`() async throws {
    let controller = ChatTableViewController<TestMessageItem, Text>()
    controller.configure { item in
      Text(item.text)
    }
    controller.loadViewIfNeeded()
    controller.tableView.frame = CGRect(x: 0, y: 0, width: 320, height: 600)

    let applied = (0..<10).map { index in
      TestMessageItem(id: UUID(), text: "Message \(index)", revision: 0)
    }
    controller.updateItems(applied, animated: false)
    try await waitForRowCount(applied.count, in: controller, context: "seed")

    // Converged baseline: a present id resolves to an in-bounds applied-snapshot row.
    let presentPath = try #require(controller.resolvedScrollRowForTests(id: applied[3].id))
    #expect(presentPath.row >= 0)
    #expect(presentPath.row < controller.tableView.numberOfRows(inSection: 0))

    // Reproduce the model-ahead-of-snapshot divergence that could abort scrollToRow:
    // grow the items model by one without applying a snapshot, so the model (11)
    // leads the applied row count (10).
    let ghost = TestMessageItem(id: UUID(), text: "Message 10", revision: 0)
    controller.advanceItemsModelWithoutApplyingForTests(applied + [ghost])
    #expect(controller.tableView.numberOfRows(inSection: 0) == applied.count)

    // An id in the model but not yet in the applied snapshot must resolve to nil.
    // The old model math (items.count - 1 - itemIndex) would instead return row 0
    // here and hand a stale row to scrollToRow.
    #expect(controller.resolvedScrollRowForTests(id: ghost.id) == nil)

    // An applied id must resolve to its applied-snapshot row, which stays in bounds.
    // For the oldest item the old math would compute items.count - 1 - 0 = 10 — one
    // past the applied snapshot's last row (9), the exact out-of-range index that
    // aborted scrollToRow. The snapshot-derived row is 9.
    let oldestPath = try #require(controller.resolvedScrollRowForTests(id: applied[0].id))
    #expect(oldestPath.row < controller.tableView.numberOfRows(inSection: 0))
  }

  @Test
  func `Scroll completion reload does not re-enter diffable apply during animated updates`() async throws {
    let controller = ChatTableViewController<TestMessageItem, Text>()
    controller.configure { item in
      Text(item.text)
    }
    controller.loadViewIfNeeded()

    var items = (0..<120).map { index in
      TestMessageItem(id: UUID(), text: "Message \(index)", revision: 0)
    }
    let targetIndex = 60
    let targetID = items[targetIndex].id

    controller.updateItems(items, animated: false)
    try await waitForRowCount(items.count, in: controller, context: "initial seed")

    for iteration in 1...5 {
      controller.scrollToItem(id: targetID, animated: true)

      var updatedItems = items
      updatedItems[targetIndex] = TestMessageItem(
        id: targetID,
        text: "Message \(targetIndex) iteration \(iteration)",
        revision: iteration
      )
      updatedItems.append(
        TestMessageItem(
          id: UUID(),
          text: "Appended \(iteration)",
          revision: 0
        )
      )

      controller.updateItems(updatedItems, animated: true)
      controller.scrollViewDidEndScrollingAnimation(controller.tableView)
      items = updatedItems
    }

    try await waitForRowCount(items.count, in: controller, context: "final snapshot")

    #expect(controller.tableView.numberOfRows(inSection: 0) == items.count)
  }

  @Test
  func `A content-only reconfigure superseded mid-apply still reaches an applied snapshot`() async throws {
    let controller = ChatTableViewController<TestMessageItem, Text>()
    controller.configure { item in
      Text(item.text)
    }
    controller.loadViewIfNeeded()
    controller.tableView.frame = CGRect(x: 0, y: 0, width: 320, height: 600)

    let x = TestMessageItem(id: UUID(), text: "X revision 0", revision: 0)
    let older = TestMessageItem(id: UUID(), text: "Older", revision: 0)
    controller.updateItems([older, x], animated: false)
    try await waitForRowCount(2, in: controller, context: "seed")

    // Open the coalescer window: mark an apply in flight so the next two updateItems
    // calls park in the pending slot instead of applying immediately.
    controller.beginApplyingForTests()

    // Content-only change to X parks reconfigureItems([x]) in the pending slot.
    let xBumped = TestMessageItem(id: x.id, text: "X revision 1", revision: 1)
    controller.updateItems([older, xBumped], animated: false)

    // Structural change (append Y) with X unchanged vs the parked snapshot. This
    // supersedes the pending slot; the coalescer must carry X's targeted reconfigure
    // forward rather than dropping it with latest-wins.
    let y = TestMessageItem(id: UUID(), text: "Y", revision: 0)
    controller.updateItems([older, xBumped, y], animated: false)

    // Drain applies the surviving snapshot synchronously (no window means non-animated).
    controller.drainPendingForTests()
    try await waitForRowCount(3, in: controller, context: "after drain")

    let reconfiguredX = controller.appliedReconfiguredItemIDsForTests.contains(x.id)
    #expect(
      reconfiguredX,
      "X's content reconfigure must survive supersession and reach an applied snapshot"
    )
  }

  // MARK: - Off-screen mention reporting

  /// Builds a controller with more rows than fit a 600pt viewport, so the flipped table rests at
  /// the bottom with the newest item visible and the oldest off the top, and asserts that split so
  /// every off-screen test shares the precondition: shifted row metrics fail here, not below.
  private func makeOffscreenMentionController() throws
    -> (controller: ChatTableViewController<TestMessageItem, Text>, items: [TestMessageItem]) {
    let controller = ChatTableViewController<TestMessageItem, Text>()
    controller.configure { Text($0.text) }
    controller.loadViewIfNeeded()
    controller.tableView.frame = CGRect(x: 0, y: 0, width: 320, height: 600)

    let items = (0..<50).map { TestMessageItem(id: UUID(), text: "Message \($0)", revision: 0) }
    controller.updateItems(items, animated: false)
    controller.tableView.layoutIfNeeded()

    // Read row visibility directly rather than driving onOffscreenMentionsChanged, so the
    // precondition does not seed the report change-detection the tests rely on starting clean.
    let visibleRows = Set(controller.tableView.indexPathsForVisibleRows ?? [])
    let newestRow = try #require(controller.resolvedScrollRowForTests(id: items[items.count - 1].id))
    let oldestRow = try #require(controller.resolvedScrollRowForTests(id: items[0].id))
    #expect(visibleRows.contains(newestRow), "Precondition: newest item must be visible at rest")
    #expect(!visibleRows.contains(oldestRow), "Precondition: oldest item must be off screen at rest")

    return (controller, items)
  }

  @Test
  func `A mention that is currently visible is not reported off screen`() throws {
    let (controller, items) = try makeOffscreenMentionController()

    var reported: [UUID]?
    controller.onOffscreenMentionsChanged = { reported = $0 }
    controller.unseenMentionIDs = [items[items.count - 1].id] // newest, on screen

    controller.scrollViewDidScroll(controller.tableView)
    controller.flushScrollObservationsForTests()

    #expect(reported == [], "A visible mention must not be reported off screen")
  }

  @Test
  func `A mention scrolled off screen is reported by id`() throws {
    let (controller, items) = try makeOffscreenMentionController()

    var reported: [UUID]?
    controller.onOffscreenMentionsChanged = { reported = $0 }
    controller.unseenMentionIDs = [items[0].id] // oldest, above the viewport

    controller.scrollViewDidScroll(controller.tableView)
    controller.flushScrollObservationsForTests()

    #expect(reported == [items[0].id], "A mention above the viewport must be reported off screen")
  }

  @Test
  func `Only the off-screen mentions are reported, in order`() throws {
    let (controller, items) = try makeOffscreenMentionController()

    var reported: [UUID]?
    controller.onOffscreenMentionsChanged = { reported = $0 }
    controller.unseenMentionIDs = [items[0].id, items[items.count - 1].id]

    controller.scrollViewDidScroll(controller.tableView)
    controller.flushScrollObservationsForTests()

    // The visible newest is excluded; the off-screen oldest is the scroll target the button taps.
    #expect(reported == [items[0].id], "Only the off-screen mention is reported")
  }

  @Test
  func `Off-screen report empties when the unseen set empties`() throws {
    let (controller, items) = try makeOffscreenMentionController()

    var reported: [UUID]?
    controller.onOffscreenMentionsChanged = { reported = $0 }
    controller.unseenMentionIDs = [items[0].id]
    controller.scrollViewDidScroll(controller.tableView)
    controller.flushScrollObservationsForTests()
    #expect(reported == [items[0].id])

    controller.unseenMentionIDs = []
    controller.scrollViewDidScroll(controller.tableView)
    controller.flushScrollObservationsForTests()
    #expect(reported == [], "Clearing the unseen set must hide the @ button")
  }

  @Test
  func `The settle-delay recheck reports an off-screen mention on load`() async throws {
    let (controller, items) = try makeOffscreenMentionController()

    var reported: [UUID]?
    controller.onOffscreenMentionsChanged = { reported = $0 }
    controller.unseenMentionIDs = [items[0].id] // oldest, above the viewport

    // Exercise the layout-settle async path that actually fires on first load, not the
    // synchronous scroll drain the other tests use.
    controller.scheduleVisibleMentionsRecheck()

    try await waitUntil("settle-delay recheck should report the off-screen mention") {
      reported != nil
    }

    #expect(reported == [items[0].id], "The settle-delay recheck must report the off-screen mention")
  }
}
