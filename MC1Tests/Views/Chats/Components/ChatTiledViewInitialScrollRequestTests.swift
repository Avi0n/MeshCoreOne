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
    let targetID: UUID?
    @State private var isAtBottom = true
    @State private var unreadCount = 0

    var body: some View {
      ChatTiledView(
        items: rows,
        cellContent: { _ in Color.blue.frame(height: Constants.rowHeight) },
        isAtBottom: $isAtBottom,
        unreadCount: $unreadCount,
        scrollToTargetRequest: targetID == nil ? 0 : 1,
        scrollTargetID: targetID
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
    let controller = UIHostingController(rootView: Harness(rows: rows, targetID: targetID))
    let window = UIWindow(frame: CGRect(origin: .zero, size: Constants.viewportSize))
    window.rootViewController = controller
    window.isHidden = false
    defer { window.isHidden = true }

    let expectedIndex = hasTarget ? Constants.targetIndex : Constants.rowCount - 1
    let deadline = Date(timeIntervalSinceNow: Constants.timeout)
    var lastFrame: CGRect?
    var viewportHeight: CGFloat?
    repeat {
      window.layoutIfNeeded()
      RunLoop.main.run(until: Date(timeIntervalSinceNow: Constants.runLoopSlice))
      guard let collection = findCollectionView(in: window),
            collection.contentSize.height > 0,
            let section = (0..<collection.numberOfSections).first(where: {
              collection.numberOfItems(inSection: $0) == rows.count
            }),
            let attributes = collection.layoutAttributesForItem(
              at: IndexPath(item: expectedIndex, section: section)
            ) else { continue }
      let frame = attributes.frame.offsetBy(dx: 0, dy: -collection.contentOffset.y)
      lastFrame = frame
      viewportHeight = collection.bounds.height
      if frame.minY >= -Constants.geometryTolerance,
         frame.maxY <= collection.bounds.height + Constants.geometryTolerance {
        break
      }
    } while Date() < deadline

    let frame = try #require(lastFrame, "The hosted list must lay out its rows")
    let height = try #require(viewportHeight)
    #expect(frame.minY >= -Constants.geometryTolerance,
            "The requested row must be visible when its request predates the list's first mount")
    #expect(frame.maxY <= height + Constants.geometryTolerance)
    if !hasTarget {
      #expect(abs(frame.maxY - height) <= Constants.geometryTolerance,
              "Without a notification target, the list must still open at the bottom")
    }
  }

  private func findCollectionView(in view: UIView) -> UICollectionView? {
    if let collection = view as? UICollectionView { return collection }
    for subview in view.subviews {
      if let collection = findCollectionView(in: subview) { return collection }
    }
    return nil
  }
}
