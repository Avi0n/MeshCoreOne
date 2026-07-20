import MessagingUI
import SwiftUI
import Testing
import UIKit

/// Exercises the initial scroll target against the bottom chrome the real chat
/// carries: `ChatConversationView` hosts the input bar in a `.safeAreaInset`,
/// which reaches the library as `swiftUIWorldSafeAreaInset`. Anchor math that is
/// correct against a bare viewport can still rest content underneath that bar.
@Suite("TiledView initial target under input bar chrome", .serialized)
@MainActor
struct TiledViewInputBarChromeTests {
  private struct Row: Identifiable, Hashable {
    let id: UUID
    let index: Int
  }

  /// Grows by `Harness.dividerHeight` once `grown` flips, standing in for the
  /// New Messages divider baking into the row after the list is positioned.
  private struct RowCell: TiledCellContent {
    let item: Row
    let grown: Bool

    func body(context: CellContext<Void>) -> some View {
      Color.blue.frame(height: Harness.rowHeight + (grown ? Harness.dividerHeight : 0))
    }
  }

  private struct Harness: View {
    let rows: [Row]
    let targetID: UUID?
    /// Rows that render at their grown height.
    var grownRowIDs: Set<UUID> = []
    @State private var position = TiledScrollPosition(
      autoScrollsToBottomOnAppend: false,
      scrollsToBottomOnReplace: true
    )

    var body: some View {
      TiledView(items: rows, scrollPosition: $position) { row in
        RowCell(item: row, grown: grownRowIDs.contains(row.id))
      }
      .initialScrollTarget(id: targetID.map { AnyHashable($0) }, anchor: .top)
      .safeAreaInset(edge: .bottom, spacing: 0) {
        Color.gray.frame(height: Harness.inputBarHeight)
      }
      .ignoresSafeArea()
    }

    static let rowHeight: CGFloat = 44
    static let inputBarHeight: CGFloat = 100
    static let dividerHeight: CGFloat = 50
  }

  private static let viewportHeight: CGFloat = 600

  private func makeRows(count: Int) -> [Row] {
    (0..<count).map { Row(id: UUID(), index: $0) }
  }

  private func mount(
    rows: [Row],
    targetID: UUID?,
    grownRowIDs: Set<UUID> = []
  ) -> (UIWindow, UIHostingController<Harness>) {
    let controller = UIHostingController(
      rootView: Harness(rows: rows, targetID: targetID, grownRowIDs: grownRowIDs)
    )
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: Self.viewportHeight))
    window.rootViewController = controller
    window.isHidden = false
    window.layoutIfNeeded()
    return (window, controller)
  }

  private func waitForCollectionView(
    in window: UIWindow,
    itemCount: Int,
    timeout: TimeInterval = 5
  ) -> (collectionView: UICollectionView, messagesSection: Int)? {
    let deadline = Date(timeIntervalSinceNow: timeout)
    while Date() < deadline {
      RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.05))
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

  /// Screen-space rect of a row, in the window's coordinate space.
  private func screenFrame(
    of index: Int,
    in found: (collectionView: UICollectionView, messagesSection: Int)
  ) throws -> CGRect {
    let attributes = try #require(found.collectionView.layoutAttributesForItem(
      at: IndexPath(item: index, section: found.messagesSection)
    ))
    return found.collectionView.convert(attributes.frame, to: nil)
  }

  /// Video 2: a shallow backlog whose newest row carries the New Messages
  /// divider. The row must rest above the input bar, not underneath it.
  @Test
  func `the target row rests above the input bar, not under it`() throws {
    let rows = makeRows(count: 16)
    let target = rows[rows.count - 1]
    let (window, _) = mount(rows: rows, targetID: target.id)
    defer { window.isHidden = true }

    let found = try #require(waitForCollectionView(in: window, itemCount: rows.count))
    let frame = try screenFrame(of: target.index, in: found)

    let inputBarTop = Self.viewportHeight - Harness.inputBarHeight
    #expect(frame.maxY <= inputBarTop + 1,
            """
            the target row rests under the input bar: row bottom \(frame.maxY) \
            is below the bar top \(inputBarTop) (viewport \(Self.viewportHeight))
            """)
  }

  /// The same shape without a target: the plain bottom pin must also clear the
  /// input bar, so the newest message is readable on open.
  @Test
  func `the last row rests above the input bar without a target`() throws {
    let rows = makeRows(count: 16)
    let (window, _) = mount(rows: rows, targetID: nil)
    defer { window.isHidden = true }

    let found = try #require(waitForCollectionView(in: window, itemCount: rows.count))
    let frame = try screenFrame(of: rows.count - 1, in: found)

    let inputBarTop = Self.viewportHeight - Harness.inputBarHeight
    #expect(frame.maxY <= inputBarTop + 1,
            """
            the newest row rests under the input bar: row bottom \(frame.maxY) \
            is below the bar top \(inputBarTop) (viewport \(Self.viewportHeight))
            """)
  }

  /// Video 2: a shallow backlog opens with the newest row as the target, so the
  /// anchor clamps to the bottom edge. The divider then bakes into that row and
  /// grows it. A target held by its top edge spills the growth below the fold,
  /// and no clamp runs afterwards, so the row parks under the input bar.
  @Test
  func `the target row stays above the input bar when the divider bakes in late`() throws {
    let rows = makeRows(count: 16)
    let target = rows[rows.count - 1]
    let (window, controller) = mount(rows: rows, targetID: target.id)
    defer { window.isHidden = true }

    let found = try #require(waitForCollectionView(in: window, itemCount: rows.count))
    let inputBarTop = Self.viewportHeight - Harness.inputBarHeight
    let atRest = try screenFrame(of: target.index, in: found)
    #expect(atRest.maxY <= inputBarTop + 1, "precondition: the target must start above the input bar")

    // The divider bakes into the target row a beat after positioning.
    controller.rootView = Harness(rows: rows, targetID: target.id, grownRowIDs: [target.id])
    window.layoutIfNeeded()
    RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.3))

    let grown = try screenFrame(of: target.index, in: found)
    #expect(grown.maxY <= inputBarTop + 1,
            """
            the target row parked under the input bar after the divider baked in: \
            row bottom \(grown.maxY) is below the bar top \(inputBarTop)
            """)
  }

  /// A deep backlog with the target mid-list: the target must land at the top of
  /// the readable viewport regardless of the bottom chrome.
  @Test
  func `a mid-list target rests at the viewport top with chrome present`() throws {
    let rows = makeRows(count: 60)
    let target = rows[20]
    let (window, _) = mount(rows: rows, targetID: target.id)
    defer { window.isHidden = true }

    let found = try #require(waitForCollectionView(in: window, itemCount: rows.count))
    let frame = try screenFrame(of: target.index, in: found)

    #expect(abs(frame.minY) < 1,
            "the target row must rest at the viewport top, not \(frame.minY)")
  }
}
