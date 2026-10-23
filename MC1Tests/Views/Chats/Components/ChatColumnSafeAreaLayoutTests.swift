import MessagingUI
import SwiftUI
import Testing
import UIKit

/// Hosted column rect. The collection stays on the host edges, and each cell
/// tracks that collection's safe-area layout frame.
@Suite("Chat column safe area", .serialized)
@MainActor
struct ChatColumnSafeAreaLayoutTests {
  private struct Row: Identifiable, Hashable {
    let id: UUID
  }

  private struct RowCell: TiledCellContent {
    let item: Row

    func body(context: CellContext<Void>) -> some View {
      Text(Layout.longText)
        .lineLimit(1)
        .frame(maxWidth: .infinity, alignment: .leading)
        .frame(height: Layout.rowHeight)
    }
  }

  private struct Harness: View {
    let rows: [Row]
    @State private var position = TiledScrollPosition(
      autoScrollsToBottomOnAppend: false,
      scrollsToBottomOnReplace: true
    )

    var body: some View {
      TiledView(items: rows, scrollPosition: $position) { row in
        RowCell(item: row)
      }
    }
  }

  private struct Found {
    let collectionView: UICollectionView
    let messagesSection: Int
  }

  private enum Layout {
    static let hostSize = CGSize(width: 844, height: 560)
    static let rowHeight: CGFloat = 48
    static let rowCount = 30
    static let longTextRepeatCount = 24
    static let longText = String(repeating: "long-bubble ", count: longTextRepeatCount)
    static let leftInset: CGFloat = 72
    static let rightInset: CGFloat = 56
    static let unequalLeftInset: CGFloat = 96
    static let unequalRightInset: CGFloat = 40
    static let outerLeftInset: CGFloat = 64
    static let outerRightInset: CGFloat = 28
    static let frameSlop: CGFloat = 1
    static let scrollAwayPoints: CGFloat = 400
    static let stayPutSlop: CGFloat = 1
    static let waitTimeout: TimeInterval = 6
    static let runLoopSlice: TimeInterval = 0.05
    static let settle: TimeInterval = 0.25
  }

  @Test
  func `left inset matches the physical safe-area layout frame`() throws {
    let host = try mountBleed(left: Layout.leftInset, right: 0, direction: .forceLeftToRight)
    defer { host.window.isHidden = true }
    let found = try requireColumn(host, left: Layout.leftInset, right: 0)
    try expectCellMatchesGuide(found)
  }

  @Test
  func `left inset stays on the physical left in right-to-left layout`() throws {
    let host = try mountBleed(left: Layout.leftInset, right: 0, direction: .forceRightToLeft)
    defer { host.window.isHidden = true }
    let found = try requireColumn(host, left: Layout.leftInset, right: 0)
    #expect(found.collectionView.effectiveUserInterfaceLayoutDirection == .rightToLeft)
    try expectCellMatchesGuide(found)
  }

  @Test
  func `right inset matches the physical safe-area layout frame`() throws {
    let host = try mountBleed(left: 0, right: Layout.rightInset, direction: .forceLeftToRight)
    defer { host.window.isHidden = true }
    let found = try requireColumn(host, left: 0, right: Layout.rightInset)
    try expectCellMatchesGuide(found)
  }

  @Test
  func `unequal insets match both edges of the safe-area layout frame`() throws {
    let host = try mountBleed(
      left: Layout.unequalLeftInset,
      right: Layout.unequalRightInset,
      direction: .forceLeftToRight
    )
    defer { host.window.isHidden = true }
    let found = try requireColumn(
      host,
      left: Layout.unequalLeftInset,
      right: Layout.unequalRightInset
    )
    let cell = try expectCellMatchesGuide(found)
    let boundsWidth = found.collectionView.bounds.width
    let minusLeftOnly = boundsWidth - Layout.unequalLeftInset
    let leftAppliedTwice = boundsWidth - (Layout.unequalLeftInset * 2)
    #expect(
      abs(cell.width - minusLeftOnly) > Layout.frameSlop,
      "width ignored the right inset: cell=\(cell) bounds=\(boundsWidth)"
    )
    #expect(
      abs(cell.width - leftAppliedTwice) > Layout.frameSlop,
      "width mirrored the left inset: cell=\(cell) bounds=\(boundsWidth)"
    )
  }

  @Test
  func `host already inside the safe area does not shrink again`() throws {
    let host = try mountAlreadyInside()
    defer { host.window.isHidden = true }
    let found = try requireCollection(in: host.window, itemCount: host.rows.count)
    settle(host.window)

    let innerWidth = host.inner.view.bounds.width
    let outerWidth = host.outer.view.bounds.width
    let collection = found.collectionView
    let guide = collection.safeAreaLayoutGuide.layoutFrame
    let cell = try cellFrame(in: found)

    #expect(innerWidth > Layout.frameSlop, "inner host has no width")
    #expect(
      innerWidth + Layout.outerLeftInset + Layout.outerRightInset <= outerWidth + Layout.frameSlop,
      "inner host was not laid out inside the outer safe area: inner=\(innerWidth) outer=\(outerWidth)"
    )
    #expect(abs(collection.safeAreaInsets.left) <= Layout.frameSlop)
    #expect(abs(collection.safeAreaInsets.right) <= Layout.frameSlop)
    #expect(abs(guide.width - collection.bounds.width) <= Layout.frameSlop)
    #expect(
      abs(cell.width - innerWidth) <= Layout.frameSlop,
      "bubble \(cell.width) shrank inside host \(innerWidth)"
    )
    try expectCellMatchesGuide(found)
  }

  @Test
  func `horizontal safe-area change keeps the scrolled row in place`() throws {
    let host = try mountBleed(left: 0, right: 0, direction: .forceLeftToRight)
    defer { host.window.isHidden = true }
    var found = try requireColumn(host, left: 0, right: 0)
    settle(host.window)

    let anchorIndex = Layout.rowCount / 2
    found.collectionView.setContentOffset(
      CGPoint(
        x: found.collectionView.contentOffset.x,
        y: found.collectionView.contentOffset.y - Layout.scrollAwayPoints
      ),
      animated: false
    )
    settle(host.window)
    let offsetBefore = found.collectionView.contentOffset.y
    let screenYBefore = try screenMinY(in: found, item: anchorIndex)

    host.controller.additionalSafeAreaInsets.left = Layout.leftInset
    host.window.layoutIfNeeded()
    found = try requireColumn(host, left: Layout.leftInset, right: 0)

    let offsetAfter = found.collectionView.contentOffset.y
    let screenYAfter = try screenMinY(in: found, item: anchorIndex)
    #expect(
      abs(offsetAfter - offsetBefore) <= Layout.stayPutSlop,
      "offset jumped from \(offsetBefore) to \(offsetAfter)"
    )
    #expect(
      abs(screenYAfter - screenYBefore) <= Layout.stayPutSlop,
      "row moved from \(screenYBefore) to \(screenYAfter)"
    )
    try expectCellMatchesGuide(found)
  }

  // MARK: - Hosts

  private struct BleedHost {
    let window: UIWindow
    let controller: UIHostingController<Harness>
    let rows: [Row]
  }

  private struct InsideHost {
    let window: UIWindow
    let outer: UIViewController
    let inner: UIHostingController<Harness>
    let rows: [Row]
  }

  private func mountBleed(
    left: CGFloat,
    right: CGFloat,
    direction: UISemanticContentAttribute
  ) throws -> BleedHost {
    let rows = makeRows()
    let controller = UIHostingController(rootView: Harness(rows: rows))
    // SwiftUI would otherwise lay the host out inside the safe area, which
    // hides a column inset the collection itself has to honor.
    controller.safeAreaRegions = []
    controller.additionalSafeAreaInsets = UIEdgeInsets(top: 0, left: left, bottom: 0, right: right)
    controller.traitOverrides.layoutDirection = direction == .forceRightToLeft ? .rightToLeft : .leftToRight
    controller.view.semanticContentAttribute = direction
    let window = makeWindow(size: Layout.hostSize)
    window.semanticContentAttribute = direction
    window.rootViewController = controller
    window.makeKeyAndVisible()
    window.layoutIfNeeded()
    return BleedHost(window: window, controller: controller, rows: rows)
  }

  private func mountAlreadyInside() throws -> InsideHost {
    let rows = makeRows()
    let outer = UIViewController()
    outer.additionalSafeAreaInsets = UIEdgeInsets(
      top: 0,
      left: Layout.outerLeftInset,
      bottom: 0,
      right: Layout.outerRightInset
    )
    let inner = UIHostingController(rootView: Harness(rows: rows))
    inner.additionalSafeAreaInsets = .zero
    outer.addChild(inner)
    inner.view.translatesAutoresizingMaskIntoConstraints = false
    outer.view.addSubview(inner.view)
    NSLayoutConstraint.activate([
      inner.view.leadingAnchor.constraint(equalTo: outer.view.safeAreaLayoutGuide.leadingAnchor),
      inner.view.trailingAnchor.constraint(equalTo: outer.view.safeAreaLayoutGuide.trailingAnchor),
      inner.view.topAnchor.constraint(equalTo: outer.view.topAnchor),
      inner.view.bottomAnchor.constraint(equalTo: outer.view.bottomAnchor),
    ])
    inner.didMove(toParent: outer)

    let window = makeWindow(size: Layout.hostSize)
    window.rootViewController = outer
    window.makeKeyAndVisible()
    window.layoutIfNeeded()
    return InsideHost(window: window, outer: outer, inner: inner, rows: rows)
  }

  private func makeRows() -> [Row] {
    (0..<Layout.rowCount).map { _ in Row(id: UUID()) }
  }

  private func makeWindow(size: CGSize) -> UIWindow {
    let frame = CGRect(origin: .zero, size: size)
    let window: UIWindow
    if let scene = UIApplication.shared.connectedScenes
      .compactMap({ $0 as? UIWindowScene })
      .first {
      window = UIWindow(windowScene: scene)
      window.frame = frame
    } else {
      window = UIWindow(frame: frame)
    }
    return window
  }

  // MARK: - Assertions

  @discardableResult
  private func expectCellMatchesGuide(_ found: Found) throws -> CGRect {
    let collection = found.collectionView
    let guide = collection.safeAreaLayoutGuide.layoutFrame
    let cell = try cellFrame(in: found)
    let detail = "cell=\(cell) guide=\(guide) bounds=\(collection.bounds) insets=\(collection.safeAreaInsets)"
    #expect(abs(collection.contentOffset.x) <= Layout.frameSlop, "\(detail)")
    #expect(abs(cell.minX - guide.minX) <= Layout.frameSlop, "\(detail)")
    #expect(abs(cell.maxX - guide.maxX) <= Layout.frameSlop, "\(detail)")
    #expect(abs(cell.width - guide.width) <= Layout.frameSlop, "\(detail)")
    #expect(abs(collection.contentSize.width - collection.bounds.width) <= Layout.frameSlop, "\(detail)")
    #expect(collection.contentInsetAdjustmentBehavior == .never, "\(detail)")
    #expect(abs(collection.contentInset.left) <= Layout.frameSlop, "\(detail)")
    #expect(abs(collection.contentInset.right) <= Layout.frameSlop, "\(detail)")
    return cell
  }

  private func cellFrame(in found: Found) throws -> CGRect {
    try #require(found.collectionView.layoutAttributesForItem(
      at: IndexPath(item: 0, section: found.messagesSection)
    )).frame
  }

  private func screenMinY(in found: Found, item: Int) throws -> CGFloat {
    let frame = try #require(found.collectionView.layoutAttributesForItem(
      at: IndexPath(item: item, section: found.messagesSection)
    )).frame
    return frame.minY - found.collectionView.contentOffset.y
  }

  private func requireColumn(
    _ host: BleedHost,
    left: CGFloat,
    right: CGFloat
  ) throws -> Found {
    try #require(
      waitForColumn(in: host.window, itemCount: host.rows.count, left: left, right: right),
      "guide did not match left=\(left) right=\(right). \(describeCollections(in: host.window))"
    )
  }

  private func requireCollection(in window: UIWindow, itemCount: Int) throws -> Found {
    try #require(
      waitForCollection(in: window, itemCount: itemCount),
      "missing collection. \(describeCollections(in: window))"
    )
  }

  private func waitForColumn(
    in window: UIWindow,
    itemCount: Int,
    left: CGFloat,
    right: CGFloat
  ) -> Found? {
    let deadline = Date(timeIntervalSinceNow: Layout.waitTimeout)
    while Date() < deadline {
      RunLoop.main.run(until: Date(timeIntervalSinceNow: Layout.runLoopSlice))
      window.layoutIfNeeded()
      guard let found = placedCollection(in: window, itemCount: itemCount) else { continue }
      let guide = found.collectionView.safeAreaLayoutGuide.layoutFrame
      let bounds = found.collectionView.bounds
      let leftMatches = abs(guide.minX - left) <= Layout.frameSlop
      let rightEdge = bounds.width - guide.maxX
      let rightMatches = abs(rightEdge - right) <= Layout.frameSlop
      let wider = bounds.width > guide.width + Layout.frameSlop || (left == 0 && right == 0)
      if bounds.width > 0, leftMatches, rightMatches, wider {
        return found
      }
    }
    return nil
  }

  private func waitForCollection(in window: UIWindow, itemCount: Int) -> Found? {
    let deadline = Date(timeIntervalSinceNow: Layout.waitTimeout)
    while Date() < deadline {
      RunLoop.main.run(until: Date(timeIntervalSinceNow: Layout.runLoopSlice))
      window.layoutIfNeeded()
      if let found = placedCollection(in: window, itemCount: itemCount) {
        return found
      }
    }
    return nil
  }

  private func placedCollection(in window: UIWindow, itemCount: Int) -> Found? {
    guard let collectionView = findCollectionView(in: window) else { return nil }
    for section in 0..<collectionView.numberOfSections
      where collectionView.numberOfItems(inSection: section) == itemCount {
      guard collectionView.bounds.width > 0,
            collectionView.layoutAttributesForItem(at: IndexPath(item: 0, section: section)) != nil
      else { continue }
      return Found(collectionView: collectionView, messagesSection: section)
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

  private func settle(_ window: UIWindow) {
    window.layoutIfNeeded()
    RunLoop.main.run(until: Date(timeIntervalSinceNow: Layout.settle))
  }

  private func describeCollections(in view: UIView) -> String {
    var parts: [String] = []
    func walk(_ view: UIView) {
      if let collectionView = view as? UICollectionView {
        let guide = collectionView.safeAreaLayoutGuide.layoutFrame
        parts.append(
          "bounds=\(collectionView.bounds) guide=\(guide) insets=\(collectionView.safeAreaInsets) content=\(collectionView.contentSize)"
        )
      }
      view.subviews.forEach(walk)
    }
    walk(view)
    return parts.isEmpty ? "none" : parts.joined(separator: "; ")
  }
}
