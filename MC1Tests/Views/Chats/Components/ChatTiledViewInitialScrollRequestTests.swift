@testable import MC1
import SwiftUI
import Testing
import UIKit

@Suite("ChatTiledView initial scroll request", .serialized)
@MainActor
struct ChatTiledViewInitialScrollRequestTests {
  private enum Constants {
    static let rowCount = 60
    static let targetIndex = 20
    static let notificationIndex = 8
    static let competingInitialIndex = 45
    static let rowHeight: CGFloat = 44
    static let viewportSize = CGSize(width: 390, height: 600)
    static let timeout: TimeInterval = 5
    static let runLoopSlice: TimeInterval = 0.05
    static let geometryTolerance: CGFloat = 1
  }

  private struct Row: Identifiable, Hashable, Sendable {
    let id = UUID()
  }

  private struct Harness: View {
    let rows: [Row]
    let scrollTargetID: UUID?
    let initialScrollTargetID: UUID?
    let appendsRowBeforeOpeningGeometry: Bool
    @State private var displayedRows: [Row]
    @State private var isAtBottom = true
    @State private var unreadCount = 0

    init(
      rows: [Row],
      scrollTargetID: UUID?,
      initialScrollTargetID: UUID? = nil,
      appendsRowBeforeOpeningGeometry: Bool = false
    ) {
      self.rows = rows
      self.scrollTargetID = scrollTargetID
      self.initialScrollTargetID = initialScrollTargetID
      self.appendsRowBeforeOpeningGeometry = appendsRowBeforeOpeningGeometry
      _displayedRows = State(initialValue: rows)
    }

    var body: some View {
      ChatTiledView(
        items: displayedRows,
        cellContent: { _ in Color.blue.frame(height: Constants.rowHeight) },
        isAtBottom: $isAtBottom,
        unreadCount: $unreadCount,
        scrollToTargetRequest: scrollTargetID == nil ? 0 : 1,
        scrollTargetID: scrollTargetID,
        initialScrollTargetID: initialScrollTargetID,
        onInitialTargetConsumed: appendsRowBeforeOpeningGeometry ? {
          guard displayedRows.count == rows.count else { return }
          displayedRows.append(Row())
        } : nil
      )
      .ignoresSafeArea()
    }
  }

  @Test(arguments: [false, true])
  func `a notification target delivered before the list mounts becomes visible`(
    hasTarget: Bool
  ) throws {
    let rows = (0..<Constants.rowCount).map { _ in Row() }
    let targetID = hasTarget ? rows[Constants.targetIndex].id : nil
    let window = mount(rows: rows, scrollTargetID: targetID)
    defer { window.isHidden = true }

    let expectedIndex = hasTarget ? Constants.targetIndex : Constants.rowCount - 1
    let frame = try #require(
      waitForFrame(in: window, itemCount: rows.count, index: expectedIndex, visible: true),
      "The hosted list must lay out its rows"
    )
    #expect(frame.frame.minY >= -Constants.geometryTolerance,
            "The requested row must be visible when its request predates the list's first mount")
    #expect(frame.frame.maxY <= frame.viewportHeight + Constants.geometryTolerance)
    if !hasTarget {
      #expect(abs(frame.frame.maxY - frame.viewportHeight) <= Constants.geometryTolerance,
              "Without a notification target, the list must still open at the bottom")
    }
  }

  @Test
  func `a notification target wins over the initial scroll target`() throws {
    let rows = (0..<Constants.rowCount).map { _ in Row() }
    let window = mount(
      rows: rows,
      scrollTargetID: rows[Constants.notificationIndex].id,
      initialScrollTargetID: rows[Constants.competingInitialIndex].id
    )
    defer { window.isHidden = true }

    let notification = try #require(waitForFrame(
      in: window, itemCount: rows.count, index: Constants.notificationIndex, visible: true
    ))
    #expect(notification.frame.minY >= -Constants.geometryTolerance)
    #expect(notification.frame.maxY <= notification.viewportHeight + Constants.geometryTolerance)
    let initial = try #require(waitForFrame(
      in: window, itemCount: rows.count, index: Constants.competingInitialIndex, visible: false
    ))
    let initialVisible = initial.frame.maxY > 0 && initial.frame.minY < initial.viewportHeight
    #expect(!initialVisible)
  }

  @Test
  func `an initial scroll target opens that row on screen`() throws {
    let rows = (0..<Constants.rowCount).map { _ in Row() }
    let window = mount(rows: rows, scrollTargetID: nil, initialScrollTargetID: rows[Constants.targetIndex].id)
    defer { window.isHidden = true }

    let frame = try #require(waitForFrame(
      in: window, itemCount: rows.count, index: Constants.targetIndex, visible: true
    ))
    #expect(frame.frame.minY >= -Constants.geometryTolerance)
    #expect(frame.frame.maxY <= frame.viewportHeight + Constants.geometryTolerance)
  }

  @Test
  func `a row appended before opening geometry is consumed leaves the notification row on screen`() throws {
    let rows = (0..<Constants.rowCount).map { _ in Row() }
    let window = mount(
      rows: rows,
      scrollTargetID: rows[Constants.targetIndex].id,
      appendsRowBeforeOpeningGeometry: true
    )
    defer { window.isHidden = true }

    let frame = try #require(waitForFrame(
      in: window,
      itemCount: rows.count + 1,
      index: Constants.targetIndex,
      visible: true
    ))
    #expect(frame.frame.minY >= -Constants.geometryTolerance)
    #expect(frame.frame.maxY <= frame.viewportHeight + Constants.geometryTolerance)
  }

  private func mount(
    rows: [Row],
    scrollTargetID: UUID?,
    initialScrollTargetID: UUID? = nil,
    appendsRowBeforeOpeningGeometry: Bool = false
  ) -> UIWindow {
    let controller = UIHostingController(
      rootView: Harness(
        rows: rows,
        scrollTargetID: scrollTargetID,
        initialScrollTargetID: initialScrollTargetID,
        appendsRowBeforeOpeningGeometry: appendsRowBeforeOpeningGeometry
      )
    )
    let window = UIWindow(frame: CGRect(origin: .zero, size: Constants.viewportSize))
    window.rootViewController = controller
    window.isHidden = false
    return window
  }

  private func waitForFrame(
    in window: UIWindow,
    itemCount: Int,
    index: Int,
    visible: Bool
  ) -> (frame: CGRect, viewportHeight: CGFloat)? {
    let deadline = Date(timeIntervalSinceNow: Constants.timeout)
    var last: (frame: CGRect, viewportHeight: CGFloat)?
    while Date() < deadline {
      window.layoutIfNeeded()
      RunLoop.main.run(until: Date(timeIntervalSinceNow: Constants.runLoopSlice))
      guard let collection = findCollectionView(in: window),
            collection.contentSize.height > 0,
            let section = (0..<collection.numberOfSections).first(where: {
              collection.numberOfItems(inSection: $0) == itemCount
            }),
            let attributes = collection.layoutAttributesForItem(
              at: IndexPath(item: index, section: section)
            ) else { continue }
      let frame = attributes.frame.offsetBy(dx: 0, dy: -collection.contentOffset.y)
      last = (frame, collection.bounds.height)
      let isVisible = frame.minY >= -Constants.geometryTolerance
        && frame.maxY <= collection.bounds.height + Constants.geometryTolerance
      if isVisible == visible { break }
    }
    return last
  }

  private func findCollectionView(in view: UIView) -> UICollectionView? {
    if let collection = view as? UICollectionView { return collection }
    for subview in view.subviews {
      if let collection = findCollectionView(in: subview) { return collection }
    }
    return nil
  }
}
