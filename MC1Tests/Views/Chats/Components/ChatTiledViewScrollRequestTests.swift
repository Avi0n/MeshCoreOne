@testable import MC1
import SwiftUI
import Testing
import UIKit

/// Holds bindings for a hosted `ChatTiledView` so mutations do not remount it.
@Observable
@MainActor
private final class ChatTiledViewScrollHarnessModel {
  var rows: [ChatTiledViewScrollRequestTests.Row]
  var isAtBottom = true
  var unreadCount = 0
  var scrollToBottomRequest = 0
  var userScrollToBottomRequest = 0

  init(rows: [ChatTiledViewScrollRequestTests.Row]) {
    self.rows = rows
  }
}

/// Hosted regressions for `ChatTiledView` token, send, and unread policy.
@Suite("ChatTiledView scroll-to-bottom request", .serialized)
@MainActor
struct ChatTiledViewScrollRequestTests {
  struct Row: Identifiable, Hashable {
    let id: UUID
    let index: Int
    var countsTowardUnread: Bool = true
  }

  private struct RowCell: View {
    let item: Row
    var body: some View {
      Color.blue.frame(height: Harness.rowHeight)
    }
  }

  private struct Harness: View {
    @Bindable var model: ChatTiledViewScrollHarnessModel

    var body: some View {
      ChatTiledView(
        items: model.rows,
        cellContent: { RowCell(item: $0) },
        isAtBottom: $model.isAtBottom,
        unreadCount: $model.unreadCount,
        scrollToBottomRequest: model.scrollToBottomRequest,
        userScrollToBottomRequest: model.userScrollToBottomRequest,
        countsTowardUnread: { $0.countsTowardUnread }
      )
      .ignoresSafeArea()
    }

    static let rowHeight: CGFloat = 44
  }

  private static let viewportHeight: CGFloat = 600
  private static let scrollAwayPoints: CGFloat = 400
  private static let collectionWaitTimeout: TimeInterval = 5
  private static let runLoopSlice: TimeInterval = 0.05
  private static let afterRequestSettle: TimeInterval = 0.3
  private static let stayPutSlop: CGFloat = 1

  private func makeRows(count: Int) -> [Row] {
    (0..<count).map { Row(id: UUID(), index: $0) }
  }

  private func mount(
    model: ChatTiledViewScrollHarnessModel
  ) throws -> (UIWindow, UIHostingController<Harness>, UICollectionView) {
    let controller = UIHostingController(rootView: Harness(model: model))
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: Self.viewportHeight))
    window.rootViewController = controller
    window.isHidden = false
    window.layoutIfNeeded()
    let found = try #require(waitForCollectionView(in: window, itemCount: model.rows.count))
    return (window, controller, found.collectionView)
  }

  private func waitForCollectionView(
    in window: UIWindow,
    itemCount: Int,
    timeout: TimeInterval = collectionWaitTimeout
  ) -> (collectionView: UICollectionView, messagesSection: Int)? {
    let deadline = Date(timeIntervalSinceNow: timeout)
    while Date() < deadline {
      RunLoop.main.run(until: Date(timeIntervalSinceNow: Self.runLoopSlice))
      guard let collectionView = findCollectionView(in: window) else { continue }
      for section in 0..<collectionView.numberOfSections
        where collectionView.numberOfItems(inSection: section) == itemCount {
        if collectionView.contentSize.height > 0 {
          return (collectionView, section)
        }
      }
    }
    return nil
  }

  private func findCollectionView(in view: UIView) -> UICollectionView? {
    if let collectionView = view as? UICollectionView { return collectionView }
    for subview in view.subviews {
      if let found = findCollectionView(in: subview) { return found }
    }
    return nil
  }

  private func viewWithIdentifier(_ identifier: String, in view: UIView) -> UIView? {
    if view.accessibilityIdentifier == identifier { return view }
    for subview in view.subviews {
      if let found = viewWithIdentifier(identifier, in: subview) { return found }
    }
    return nil
  }

  private func waitForView(
    withIdentifier identifier: String,
    in window: UIWindow,
    timeout: TimeInterval = collectionWaitTimeout
  ) -> UIView? {
    let deadline = Date(timeIntervalSinceNow: timeout)
    while Date() < deadline {
      window.layoutIfNeeded()
      if let view = viewWithIdentifier(identifier, in: window) { return view }
      RunLoop.main.run(until: Date(timeIntervalSinceNow: Self.runLoopSlice))
    }
    return nil
  }

  private func waitUntilNotAtBottom(
    _ isAtBottom: @escaping () -> Bool,
    timeout: TimeInterval = collectionWaitTimeout
  ) -> Bool {
    let deadline = Date(timeIntervalSinceNow: timeout)
    while Date() < deadline {
      RunLoop.main.run(until: Date(timeIntervalSinceNow: Self.runLoopSlice))
      if !isAtBottom() { return true }
    }
    return false
  }

  private func waitUntilLastRowPinned(
    _ found: (collectionView: UICollectionView, messagesSection: Int),
    in rows: [Row],
    timeout: TimeInterval = collectionWaitTimeout
  ) -> CGFloat? {
    let deadline = Date(timeIntervalSinceNow: timeout)
    var lastBottom: CGFloat?
    while Date() < deadline {
      RunLoop.main.run(until: Date(timeIntervalSinceNow: Self.runLoopSlice))
      guard let screenBottom = try? lastRowScreenBottom(found, in: rows) else { continue }
      lastBottom = screenBottom
      if abs(screenBottom - found.collectionView.bounds.height) < Self.stayPutSlop {
        return screenBottom
      }
    }
    return lastBottom
  }

  private func lastRowScreenBottom(
    _ found: (collectionView: UICollectionView, messagesSection: Int),
    id: UUID? = nil,
    in rows: [Row]
  ) throws -> CGFloat {
    let itemIndex: Int = if let id {
      try #require(rows.firstIndex(where: { $0.id == id }))
    } else {
      found.collectionView.numberOfItems(inSection: found.messagesSection) - 1
    }
    let attributes = try #require(found.collectionView.layoutAttributesForItem(
      at: IndexPath(item: itemIndex, section: found.messagesSection)
    ))
    return attributes.frame.maxY - found.collectionView.contentOffset.y
  }

  private func scrollAway(_ collectionView: UICollectionView) {
    collectionView.setContentOffset(
      CGPoint(x: 0, y: collectionView.contentOffset.y - Self.scrollAwayPoints),
      animated: false
    )
  }

  @Test
  func `request while scrolled up does not jump to the last row`() throws {
    let model = ChatTiledViewScrollHarnessModel(rows: makeRows(count: 60))
    let (window, _, collectionView) = try mount(model: model)
    defer { window.isHidden = true }

    let found = try #require(waitForCollectionView(in: window, itemCount: model.rows.count))
    #expect(ObjectIdentifier(found.collectionView) == ObjectIdentifier(collectionView))
    scrollAway(found.collectionView)
    try #require(waitUntilNotAtBottom { model.isAtBottom })

    let before = try lastRowScreenBottom(found, in: model.rows)
    model.scrollToBottomRequest += 1
    window.layoutIfNeeded()
    RunLoop.main.run(until: Date(timeIntervalSinceNow: Self.afterRequestSettle))

    #expect(ObjectIdentifier(found.collectionView) == ObjectIdentifier(collectionView))
    let after = try lastRowScreenBottom(found, in: model.rows)
    #expect(abs(after - before) < Self.stayPutSlop, "scrolled-up request must not move the list")
    #expect(!model.isAtBottom)
  }

  @Test
  func `request while at the bottom keeps the last row pinned`() throws {
    let model = ChatTiledViewScrollHarnessModel(rows: makeRows(count: 60))
    let (window, _, _) = try mount(model: model)
    defer { window.isHidden = true }

    let found = try #require(waitForCollectionView(in: window, itemCount: model.rows.count))
    #expect(model.isAtBottom)

    model.scrollToBottomRequest += 1
    window.layoutIfNeeded()
    RunLoop.main.run(until: Date(timeIntervalSinceNow: Self.afterRequestSettle))

    let screenBottom = try lastRowScreenBottom(found, in: model.rows)
    #expect(
      abs(screenBottom - found.collectionView.bounds.height) < Self.stayPutSlop,
      "at-bottom request must keep the last row on the viewport bottom"
    )
    #expect(model.isAtBottom)
  }

  @Test
  func `request plus outgoing append while scrolled up stays put and does not count unread`() throws {
    let model = ChatTiledViewScrollHarnessModel(rows: makeRows(count: 60))
    let (window, _, _) = try mount(model: model)
    defer { window.isHidden = true }

    let found = try #require(waitForCollectionView(in: window, itemCount: model.rows.count))
    scrollAway(found.collectionView)
    try #require(waitUntilNotAtBottom { model.isAtBottom })

    let previousLast = try #require(model.rows.last)
    let before = try lastRowScreenBottom(found, id: previousLast.id, in: model.rows)
    model.scrollToBottomRequest += 1
    model.rows.append(Row(id: UUID(), index: model.rows.count, countsTowardUnread: false))
    window.layoutIfNeeded()
    _ = waitForCollectionView(in: window, itemCount: model.rows.count)
    RunLoop.main.run(until: Date(timeIntervalSinceNow: Self.afterRequestSettle))

    let after = try lastRowScreenBottom(found, id: previousLast.id, in: model.rows)
    #expect(abs(after - before) < Self.stayPutSlop, "send-shaped append must not jump a scrolled-up list")
    #expect(model.unreadCount == 0, "a sent row must not raise the unread badge")
    #expect(!model.isAtBottom)
  }

  @Test
  func `incoming append while scrolled up raises unread and stays put`() throws {
    let model = ChatTiledViewScrollHarnessModel(rows: makeRows(count: 60))
    let (window, _, _) = try mount(model: model)
    defer { window.isHidden = true }

    let found = try #require(waitForCollectionView(in: window, itemCount: model.rows.count))
    scrollAway(found.collectionView)
    try #require(waitUntilNotAtBottom { model.isAtBottom })

    let previousLast = try #require(model.rows.last)
    let before = try lastRowScreenBottom(found, id: previousLast.id, in: model.rows)
    model.rows.append(Row(id: UUID(), index: model.rows.count, countsTowardUnread: true))
    window.layoutIfNeeded()
    _ = waitForCollectionView(in: window, itemCount: model.rows.count)
    RunLoop.main.run(until: Date(timeIntervalSinceNow: Self.afterRequestSettle))

    let after = try lastRowScreenBottom(found, id: previousLast.id, in: model.rows)
    #expect(abs(after - before) < Self.stayPutSlop, "incoming append must not jump a scrolled-up list")
    #expect(model.unreadCount >= 1)
    #expect(!model.isAtBottom)
  }

  @Test
  func `scroll to bottom then send still pins the new last row`() throws {
    let model = ChatTiledViewScrollHarnessModel(rows: makeRows(count: 60))
    let (window, _, _) = try mount(model: model)
    defer { window.isHidden = true }

    let found = try #require(waitForCollectionView(in: window, itemCount: model.rows.count))
    scrollAway(found.collectionView)
    try #require(waitUntilNotAtBottom { model.isAtBottom })
    _ = try #require(
      waitForView(
        withIdentifier: ChatScrollConstants.scrollToBottomButtonIdentifier,
        in: window
      ),
      "scroll-to-bottom button should be in the tree while scrolled up"
    )

    model.userScrollToBottomRequest += 1
    model.rows.append(Row(id: UUID(), index: model.rows.count))
    window.layoutIfNeeded()
    let afterAppend = try #require(waitForCollectionView(in: window, itemCount: model.rows.count))
    let screenBottom = try #require(waitUntilLastRowPinned(afterAppend, in: model.rows))

    #expect(
      abs(screenBottom - afterAppend.collectionView.bounds.height) < Self.stayPutSlop,
      "scroll-to-bottom plus append must pin the new last row (bottom \(screenBottom), viewport \(afterAppend.collectionView.bounds.height))"
    )
  }
}
