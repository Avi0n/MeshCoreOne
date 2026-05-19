import Testing
import SwiftUI
import UIKit
@testable import MC1

@Suite("ChatTableView Snapshot Regression Tests")
@MainActor
struct ChatTableViewSnapshotRegressionTests {

    private struct TestMessageItem: Identifiable, Hashable, Sendable {
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

    @Test("Pagination near-top callback is suppressed while older messages are loading")
    func nearTopCallbackSuppressedWhileLoadingOlderMessages() async throws {
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

    @Test("onNearTop latch suppresses duplicate fires until release is called")
    func nearTopRequestLatchSuppressesUntilRelease() async throws {
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

    @Test("onNearTop release clears latch even if isLoadingOlderMessages never flips")
    func nearTopReleaseClearsLatchOnShortCircuit() async throws {
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

    @Test("Auto-scroll defers while user is dragging and fires on drag end")
    func deferredScrollFiresOnDragEnd() async throws {
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

    @Test("Deferred messages count as unread if user releases scrolled away from bottom")
    func deferredMessagesBecomeUnreadIfReleasedAwayFromBottom() async throws {
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

    @Test("Auto-scroll defers through deceleration and fires when decelerating ends")
    func deferredScrollFiresAfterDeceleration() async throws {
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

    @Test("Scroll completion reload does not re-enter diffable apply during animated updates")
    func scrollCompletionReloadIsSafeDuringAnimatedUpdates() async throws {
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
}
