import MessagingUI
import SwiftUI
import Testing
import UIKit

/// Exercises the library's initial scroll target against a live layout pass.
/// The tiled layout positions content inside a large virtual coordinate space
/// via content insets, so anchor math validated only against item frames in
/// isolation can still resolve to a clamped bottom offset; these tests mount a
/// real hierarchy and assert the on-screen result.
@Suite("TiledView initial scroll target", .serialized)
@MainActor
struct TiledViewInitialScrollTargetTests {
  private struct Row: Identifiable, Hashable {
    let id: UUID
    let index: Int
  }

  private struct RowCell: TiledCellContent {
    let item: Row

    func body(context: CellContext<Void>) -> some View {
      Color.blue.frame(height: Harness.rowHeight)
    }
  }

  private struct Harness: View {
    let rows: [Row]
    let targetID: UUID?
    @State private var position = TiledScrollPosition(
      autoScrollsToBottomOnAppend: false,
      scrollsToBottomOnReplace: true
    )

    var body: some View {
      TiledView(items: rows, scrollPosition: $position) { row in
        RowCell(item: row)
      }
      .initialScrollTarget(id: targetID.map { AnyHashable($0) }, anchor: .top)
      .ignoresSafeArea()
    }

    static let rowHeight: CGFloat = 44
  }

  private static let viewportHeight: CGFloat = 600

  private func makeRows(count: Int) -> [Row] {
    (0..<count).map { Row(id: UUID(), index: $0) }
  }

  private func mount(rows: [Row], targetID: UUID?) -> (UIWindow, UIHostingController<Harness>) {
    let controller = UIHostingController(rootView: Harness(rows: rows, targetID: targetID))
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

  @Test
  func `opens with the target row at the viewport top`() throws {
    let rows = makeRows(count: 60)
    let target = rows[20]
    let (window, _) = mount(rows: rows, targetID: target.id)
    defer { window.isHidden = true }

    let found = try #require(waitForCollectionView(in: window, itemCount: rows.count))
    let attributes = try #require(found.collectionView.layoutAttributesForItem(
      at: IndexPath(item: target.index, section: found.messagesSection)
    ))

    let screenY = attributes.frame.minY - found.collectionView.contentOffset.y
    #expect(abs(screenY) < 1,
            "the target row must rest at the viewport top, not \(screenY) points below it")
  }

  @Test
  func `opens at the bottom without a target`() throws {
    let rows = makeRows(count: 60)
    let (window, _) = mount(rows: rows, targetID: nil)
    defer { window.isHidden = true }

    let found = try #require(waitForCollectionView(in: window, itemCount: rows.count))
    let attributes = try #require(found.collectionView.layoutAttributesForItem(
      at: IndexPath(item: rows.count - 1, section: found.messagesSection)
    ))

    let screenBottom = attributes.frame.maxY - found.collectionView.contentOffset.y
    #expect(abs(screenBottom - found.collectionView.bounds.height) < 1,
            "the last row must rest at the viewport bottom")
  }

  @Test
  func `a target close to the bottom clamps to the bottom edge`() throws {
    let rows = makeRows(count: 60)
    let target = rows[rows.count - 2]
    let (window, _) = mount(rows: rows, targetID: target.id)
    defer { window.isHidden = true }

    let found = try #require(waitForCollectionView(in: window, itemCount: rows.count))
    let attributes = try #require(found.collectionView.layoutAttributesForItem(
      at: IndexPath(item: rows.count - 1, section: found.messagesSection)
    ))

    let screenBottom = attributes.frame.maxY - found.collectionView.contentOffset.y
    #expect(abs(screenBottom - found.collectionView.bounds.height) < 1,
            "a near-bottom target must clamp so the list stays flush with the viewport bottom")
  }
}
