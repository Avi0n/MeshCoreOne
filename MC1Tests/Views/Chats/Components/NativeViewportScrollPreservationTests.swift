@testable import MessagingUI
import SwiftUI
import UIKit
import XCTest

@MainActor
final class NativeViewportScrollPreservationTests: XCTestCase {
  private struct Message: Identifiable, Equatable {
    let id: Int
  }

  @Observable
  @MainActor
  final class MessageHeight {
    var value: CGFloat

    init(_ value: CGFloat) {
      self.value = value
    }
  }

  private struct MessageCell: View {
    let id: Int
    let heightModel: MessageHeight?

    var body: some View {
      Text("\(id)")
        .foregroundStyle(.white)
        .frame(maxWidth: .infinity)
        .frame(height: heightModel?.value ?? Metrics.rowHeight)
        .background(id.isMultiple(of: 2) ? Color.blue : Color.indigo)
    }
  }

  private enum GeometryUpdateOrder: CaseIterable {
    case viewportOnly
    case viewportThenInset
    case insetThenViewport
  }

  private enum Metrics {
    static let width: CGFloat = 390
    static let restingHeight: CGFloat = 844
    static let keyboardHeight: CGFloat = 542
    static let topInset: CGFloat = 88
    static let chromeInset: CGFloat = 90
    static let composerLineHeight: CGFloat = 22
    static let rowHeight: CGFloat = 100
    static let rowHeightGrowth: CGFloat = 60
    static let rowCount = 60
    static let historyRow = 20
    static let scrollStep: CGFloat = 24
    static let bottomReadingGap: CGFloat = 36
    static let tolerance: CGFloat = 1
    static let mountPasses = 6
    static let mountInterval: TimeInterval = 0.02
    static let heightCycle: [CGFloat] = [744, 644, keyboardHeight, 644, 744, restingHeight]
    static let dismissalHeights: [CGFloat] = [592, 642, 692, 742, 792, restingHeight]
    static let userInteractions: [(isTracking: Bool, isDragging: Bool, isDecelerating: Bool)] = [
      (true, false, false), (true, true, false), (false, false, true),
    ]
  }

  private typealias HostView = TiledUIView<Message, MessageCell, Never, Never, Never, Never, Void>
  private var windows: [UIWindow] = []

  override func tearDown() async throws {
    await MainActor.run {
      for window in windows {
        window.isHidden = true
      }
      windows.removeAll()
    }
    try await super.tearDown()
  }

  func test_idleBottomStaysAtBottomAcrossNativeViewportCycle() throws {
    let view = mount()
    try assertLastRowMeetsChrome(view)

    for height in Metrics.heightCycle {
      resize(view, to: height)
      try assertLastRowMeetsChrome(view)
      assertVisibleCellCoverage(view)
    }
  }

  func test_idleBottomStaysAtBottomWhenLayoutIsInvalidatedBeforeViewportResize() throws {
    let view = mount()
    let originalOffset = view.test_contentOffsetY
    try assertLastRowMeetsChrome(view)

    for height in [Metrics.keyboardHeight, Metrics.restingHeight] {
      view.test_collectionView.collectionViewLayout.invalidateLayout()
      resize(view, to: height)

      XCTAssertEqual(
        view.test_contentOffsetY, originalOffset + Metrics.restingHeight - height,
        accuracy: Metrics.tolerance, diagnostic(view)
      )
      try assertLastRowMeetsChrome(view)
      assertVisibleCellCoverage(view)
    }
  }

  func test_idleBottomStaysAtBottomWhenViewportShrinksAndComposerGrowsInOneLayoutPass() throws {
    for order in [GeometryUpdateOrder.viewportThenInset, .insetThenViewport] {
      let view = mount()
      try assertLastRowMeetsChrome(view)
      let originalOffset = view.test_contentOffsetY

      for height in [Metrics.keyboardHeight, Metrics.restingHeight] {
        resize(view, to: height, order: order)
        let inset = chromeInset(for: height, order: order)
        let collectionLayout = try XCTUnwrap(view.test_collectionView.collectionViewLayout as? TiledCollectionViewLayout)
        XCTAssertEqual(collectionLayout.additionalContentInset.bottom, inset, accuracy: Metrics.tolerance)
        XCTAssertEqual(
          view.test_contentOffsetY, originalOffset + Metrics.restingHeight - height + inset - Metrics.chromeInset,
          accuracy: Metrics.tolerance, diagnostic(view)
        )
        try assertLastRowMeetsChrome(view)
        assertVisibleCellCoverage(view)
      }
    }
  }

  func test_idleHistoryKeepsLowestVisibleMessageAboveChromeAcrossNativeViewportCycle() throws {
    for order in GeometryUpdateOrder.allCases {
      let view = mount(targetID: Metrics.historyRow)
      let collection = view.test_collectionView
      let lowestRow = try XCTUnwrap(collection.indexPathsForVisibleItems
        .filter { $0.section == TiledCollectionViewLayout.DisplaySection.messages.rawValue }
        .filter { (view.test_messageFrame(at: $0.item)?.minY ?? .infinity) - view.test_contentOffsetY
          < view.bounds.height - Metrics.chromeInset
        }
        .map(\.item).max())
      let originalBottom = try instantiatedFrame(of: lowestRow, in: view).maxY
      let originalOffset = view.test_contentOffsetY

      for height in Metrics.heightCycle {
        resize(view, to: height, order: order)
        XCTAssertEqual(
          view.test_contentOffsetY, originalOffset + Metrics.restingHeight - height,
          accuracy: Metrics.tolerance, diagnostic(view)
        )
        XCTAssertEqual(
          try instantiatedFrame(of: lowestRow, in: view).maxY, originalBottom + height - Metrics.restingHeight,
          accuracy: Metrics.tolerance, diagnostic(view)
        )
        assertVisibleCellCoverage(view)
      }
    }
  }

  func test_idleNearBottomPreservesItsDistanceAcrossNativeViewportCycle() throws {
    for order in GeometryUpdateOrder.allCases {
      let view = mount()
      view.test_collectionView.contentOffset.y -= Metrics.bottomReadingGap
      XCTAssertEqual(view.test_pointsFromBottom, Metrics.bottomReadingGap, accuracy: Metrics.tolerance)

      for height in Metrics.heightCycle {
        resize(view, to: height, order: order)
        XCTAssertEqual(
          view.test_pointsFromBottom, Metrics.bottomReadingGap,
          accuracy: Metrics.tolerance, diagnostic(view)
        )
        XCTAssertEqual(
          try instantiatedFrame(of: Metrics.rowCount - 1, in: view).maxY,
          height - view.swiftUIWorldSafeAreaInset.bottom + Metrics.bottomReadingGap,
          accuracy: Metrics.tolerance, diagnostic(view)
        )
        assertVisibleCellCoverage(view)
      }
    }
  }

  func test_idleHistoryPreservesReadingPositionWhenRowAboveViewportAndComposerGrow() throws {
    let heightModel = MessageHeight(Metrics.rowHeight)
    let view = mount(targetID: Metrics.historyRow, heightModel: heightModel)
    let growingRow = Metrics.historyRow - 1
    let originalFrame = try instantiatedFrame(of: growingRow, in: view)
    let originalY = try instantiatedFrame(of: Metrics.historyRow, in: view).minY
    let originalOffset = view.test_contentOffsetY
    XCTAssertEqual(originalFrame.maxY, Metrics.topInset, accuracy: Metrics.tolerance)
    XCTAssertFalse(view.isInitialAnchorActive)

    heightModel.value += Metrics.rowHeightGrowth
    view.swiftUIWorldSafeAreaInset.bottom += Metrics.composerLineHeight
    let insetFrame = try instantiatedFrame(of: growingRow, in: view)
    let insetOffset = view.test_contentOffsetY
    XCTAssertEqual(
      try instantiatedFrame(of: Metrics.historyRow, in: view).minY, originalY,
      accuracy: Metrics.tolerance, diagnostic(view)
    )

    for _ in 0..<Metrics.mountPasses {
      layout(view)
      RunLoop.current.run(until: Date().addingTimeInterval(Metrics.mountInterval))
      XCTAssertEqual(
        try instantiatedFrame(of: Metrics.historyRow, in: view).minY, originalY,
        accuracy: Metrics.tolerance, diagnostic(view)
      )
    }

    let finalFrame = try instantiatedFrame(of: growingRow, in: view)
    let evidence = XCTAttachment(string:
      "growthDuringInset=\(insetFrame.height - originalFrame.height), "
        + "offsetDuringInset=\(insetOffset - originalOffset), "
        + "finalGrowth=\(finalFrame.height - originalFrame.height), "
        + "finalOffset=\(view.test_contentOffsetY - originalOffset)")
    evidence.name = "Self-sizing and inset ordering"
    evidence.lifetime = .keepAlways
    add(evidence)
    XCTAssertEqual(finalFrame.height - originalFrame.height, Metrics.rowHeightGrowth, accuracy: Metrics.tolerance)
    XCTAssertEqual(view.test_contentOffsetY - originalOffset, Metrics.rowHeightGrowth, accuracy: Metrics.tolerance)
    XCTAssertEqual(view.bounds.height, Metrics.restingHeight, accuracy: Metrics.tolerance)
    assertVisibleCellCoverage(view)
  }

  func test_heldInitialAnchorTracksNativeViewportCenter() throws {
    for order in GeometryUpdateOrder.allCases {
      let view = mount(targetID: Metrics.historyRow, anchor: .center, holdAnchor: true)
      XCTAssertTrue(view.isInitialAnchorActive)

      for height in Metrics.heightCycle {
        resize(view, to: height, order: order)
        let expectedCenter = Metrics.topInset + (height - Metrics.topInset - view.swiftUIWorldSafeAreaInset.bottom) / 2
        XCTAssertTrue(view.isInitialAnchorActive)
        XCTAssertEqual(
          try instantiatedFrame(of: Metrics.historyRow, in: view).midY, expectedCenter,
          accuracy: Metrics.tolerance, diagnostic(view)
        )
        assertVisibleCellCoverage(view)
      }
    }
  }

  func test_activeBottomRemainsCoveredWhileNativeViewportExpands() throws {
    // Start at the focused geometry so an idle focus failure cannot obscure dismissal behavior.
    for order in GeometryUpdateOrder.allCases {
      for interaction in Metrics.userInteractions {
        let view = mount(
          height: Metrics.keyboardHeight, chromeInset: chromeInset(for: Metrics.keyboardHeight, order: order)
        )
        try assertLastRowMeetsChrome(view)
        beginInteraction(interaction, in: view)

        for height in Metrics.dismissalHeights {
          let collection = view.test_collectionView
          let requestedOffset = collection.contentOffset.y - Metrics.scrollStep
          collection.contentOffset.y = requestedOffset
          resize(view, to: height, order: order)
          let maximumOffset = collection.contentSize.height + collection.adjustedContentInset.bottom - height
          XCTAssertEqual(
            collection.contentOffset.y, min(requestedOffset, maximumOffset),
            accuracy: Metrics.tolerance, diagnostic(view)
          )
          assertVisibleCellCoverage(view)
        }
      }
    }
  }

  func test_activeHistoryPreservesUserMotionWhileNativeViewportExpands() throws {
    for order in GeometryUpdateOrder.allCases {
      for interaction in Metrics.userInteractions {
        let view = mount(
          height: Metrics.keyboardHeight, chromeInset: chromeInset(for: Metrics.keyboardHeight, order: order),
          targetID: Metrics.historyRow, holdAnchor: interaction.isDragging || interaction.isDecelerating
        )
        beginInteraction(interaction, in: view)
        XCTAssertFalse(view.isInitialAnchorActive)

        for height in Metrics.dismissalHeights {
          let collection = view.test_collectionView
          collection.contentOffset.y -= Metrics.scrollStep
          let requestedOffset = collection.contentOffset.y
          let expectedY = try instantiatedFrame(of: Metrics.historyRow, in: view).minY
          resize(view, to: height, order: order)
          XCTAssertEqual(collection.contentOffset.y, requestedOffset, accuracy: Metrics.tolerance, diagnostic(view))
          XCTAssertEqual(
            try instantiatedFrame(of: Metrics.historyRow, in: view).minY, expectedY,
            accuracy: Metrics.tolerance, diagnostic(view)
          )
          assertVisibleCellCoverage(view)
        }
      }
    }
  }

  func test_explicitScrollBetweenViewportCaptureAndLayoutWins() {
    let view = mount()
    let capturedOffset = view.test_contentOffsetY
    captureViewportChange(view, to: Metrics.keyboardHeight)

    var position = TiledScrollPosition()
    position.scrollTo(edge: .top, animated: false)
    view.applyScrollPosition(position)
    let requestedOffset = view.test_contentOffsetY
    XCTAssertNotEqual(requestedOffset, capturedOffset)

    resize(view, to: Metrics.keyboardHeight)

    XCTAssertEqual(
      view.test_contentOffsetY, requestedOffset,
      accuracy: Metrics.tolerance, diagnostic(view)
    )
    assertVisibleCellCoverage(view)
  }

  func test_dragBeginningAndEndingBetweenCaptureAndLayoutIsNotReplayed() {
    for decelerates in [false, true] {
      let view = mount(targetID: Metrics.historyRow, holdAnchor: true)
      captureViewportChange(view, to: Metrics.keyboardHeight)
      let requestedOffset = view.test_contentOffsetY - Metrics.scrollStep

      view.test_beginUserDrag()
      view.test_collectionView.contentOffset.y = requestedOffset
      view.scrollViewDidEndDragging(view.test_collectionView, willDecelerate: decelerates)
      if decelerates {
        view.scrollViewDidEndDecelerating(view.test_collectionView)
      }
      XCTAssertFalse(view.isInitialAnchorActive)

      resize(view, to: Metrics.keyboardHeight)

      XCTAssertEqual(
        view.test_contentOffsetY, requestedOffset,
        accuracy: Metrics.tolerance, diagnostic(view)
      )
      assertVisibleCellCoverage(view)
    }
  }

  func test_boundsOnlyChangeReportsFinalGeometryWithHistoryAnchorPreserved() throws {
    let view = mount(targetID: Metrics.historyRow)
    let initialOffset = view.test_contentOffsetY
    var reports: [TiledScrollGeometry] = []
    view.onTiledScrollGeometryChange = { reports.append($0) }

    for height in Metrics.heightCycle {
      reports.removeAll()
      resize(view, to: height)
      let expectedOffset = initialOffset + Metrics.restingHeight - height
      _ = try XCTUnwrap(reports.last, "Bounds-only changes must publish their new visible size")
      XCTAssertEqual(view.test_contentOffsetY, expectedOffset, accuracy: Metrics.tolerance)
      for report in reports {
        XCTAssertEqual(report.visibleSize.height, height, accuracy: Metrics.tolerance)
        XCTAssertEqual(report.contentOffset.y, expectedOffset, accuracy: Metrics.tolerance)
        XCTAssertEqual(report.contentInset, view.test_collectionView.adjustedContentInset)
        XCTAssertEqual(report.contentSize, view.test_collectionView.contentSize)
      }
    }
  }

  func test_modelOffsetAlreadyAdjustedForViewportIsNotAdjustedTwice() {
    let view = mount(height: Metrics.keyboardHeight)
    view.test_collectionView.contentOffset.y -= Metrics.bottomReadingGap
    XCTAssertEqual(view.test_pointsFromBottom, Metrics.bottomReadingGap, accuracy: Metrics.tolerance)
    let originalOffset = view.test_contentOffsetY
    captureViewportChange(view, to: Metrics.restingHeight)

    // Emulate UIKit having applied the complete model offset before the parent's layout pass.
    let adjustedOffset = originalOffset - (Metrics.restingHeight - Metrics.keyboardHeight)
    view.test_collectionView.contentOffset.y = adjustedOffset
    resize(view, to: Metrics.restingHeight)

    XCTAssertEqual(
      view.test_contentOffsetY, adjustedOffset,
      accuracy: Metrics.tolerance, diagnostic(view)
    )
    XCTAssertEqual(view.test_pointsFromBottom, Metrics.bottomReadingGap, accuracy: Metrics.tolerance)
    assertVisibleCellCoverage(view)
  }

  private func mount(
    height: CGFloat = Metrics.restingHeight,
    chromeInset: CGFloat = Metrics.chromeInset,
    targetID: Int? = nil,
    anchor: UnitPoint = .top,
    holdAnchor: Bool = false,
    heightModel: MessageHeight? = nil
  ) -> HostView {
    let view = HostView(
      makeInitialState: { _ in () },
      cellBuilder: { message, _, _ in
        MessageCell(id: message.id, heightModel: message.id == Metrics.historyRow - 1 ? heightModel : nil)
      }
    )
    let window = UIWindow(frame: CGRect(x: 0, y: 0, width: Metrics.width, height: Metrics.restingHeight))
    window.backgroundColor = .magenta
    view.backgroundColor = .yellow
    view.frame = CGRect(x: 0, y: 0, width: Metrics.width, height: height)
    window.addSubview(view)
    window.makeKeyAndVisible()
    windows.append(window)
    view.scrollsToBottomOnReplace = true
    view.swiftUIWorldSafeAreaInset = EdgeInsets(
      top: Metrics.topInset, leading: 0, bottom: chromeInset, trailing: 0
    )
    if let targetID {
      view.initialScrollTarget = TiledInitialScrollTarget(
        id: AnyHashable(targetID), anchor: anchor, holdUntilUserScroll: holdAnchor
      )
    }
    view.applyItems((0..<Metrics.rowCount).map { Message(id: $0) })
    for _ in 0..<Metrics.mountPasses {
      layout(view)
      RunLoop.current.run(until: Date().addingTimeInterval(Metrics.mountInterval))
    }
    return view
  }

  private func resize(_ view: HostView, to height: CGFloat, order: GeometryUpdateOrder = .viewportOnly) {
    let inset = chromeInset(for: height, order: order)
    UIView.performWithoutAnimation {
      if order == .insetThenViewport { view.swiftUIWorldSafeAreaInset.bottom = inset }
      view.frame.size.height = height
      if order == .viewportThenInset { view.swiftUIWorldSafeAreaInset.bottom = inset }
      layout(view)
    }
    XCTAssertEqual(view.test_collectionView.bounds.height, height, accuracy: Metrics.tolerance)
    XCTAssertEqual(view.swiftUIWorldSafeAreaInset.bottom, inset, accuracy: Metrics.tolerance)
  }

  private func chromeInset(for height: CGFloat, order: GeometryUpdateOrder) -> CGFloat {
    guard order != .viewportOnly else { return Metrics.chromeInset }
    let progress = (Metrics.restingHeight - height) / (Metrics.restingHeight - Metrics.keyboardHeight)
    return Metrics.chromeInset + Metrics.composerLineHeight * progress
  }

  private func beginInteraction(
    _ interaction: (isTracking: Bool, isDragging: Bool, isDecelerating: Bool), in view: HostView
  ) {
    if interaction.isDragging || interaction.isDecelerating {
      view.test_beginUserDrag()
      if interaction.isDecelerating {
        view.scrollViewDidEndDragging(view.test_collectionView, willDecelerate: true)
      }
    }
    view.test_scrollInteraction = interaction
  }

  private func captureViewportChange(_ view: HostView, to height: CGFloat) {
    let collection = view.test_collectionView
    var nextBounds = collection.bounds
    nextBounds.size.height = height
    collection.bounds = nextBounds
  }

  private func layout(_ view: HostView) {
    view.window?.setNeedsLayout()
    view.window?.layoutIfNeeded()
    view.setNeedsLayout()
    view.layoutIfNeeded()
    view.test_collectionView.layoutIfNeeded()
  }

  private func instantiatedFrame(of row: Int, in view: HostView) throws -> CGRect {
    let indexPath = TiledCollectionViewLayout.DisplaySection.messages.indexPath(item: row)
    let cell = try XCTUnwrap(view.test_collectionView.cellForItem(at: indexPath), diagnostic(view))
    XCTAssertFalse(cell.isHidden)
    XCTAssertGreaterThan(cell.alpha, 0)
    XCTAssertGreaterThan(cell.contentView.alpha, 0)
    return cell.convert(cell.bounds, to: view)
  }

  private func assertLastRowMeetsChrome(
    _ view: HostView,
    file: StaticString = #filePath,
    line: UInt = #line
  ) throws {
    XCTAssertEqual(
      try instantiatedFrame(of: Metrics.rowCount - 1, in: view).maxY,
      view.bounds.height - view.swiftUIWorldSafeAreaInset.bottom,
      accuracy: Metrics.tolerance, diagnostic(view), file: file, line: line
    )
  }

  private func assertVisibleCellCoverage(
    _ view: HostView,
    file: StaticString = #filePath,
    line: UInt = #line
  ) {
    let collection = view.test_collectionView
    let messageSection = TiledCollectionViewLayout.DisplaySection.messages.rawValue
    let frames = collection.indexPathsForVisibleItems
      .filter { $0.section == messageSection }
      .compactMap { collection.cellForItem(at: $0) }
      .filter { !$0.isHidden && $0.alpha > 0 && $0.contentView.alpha > 0 }
      .map { $0.convert($0.bounds, to: view) }
      .sorted { $0.minY < $1.minY }
    let visibleBottom = view.bounds.height - view.swiftUIWorldSafeAreaInset.bottom
    var coveredThrough = Metrics.topInset
    var uncovered: CGFloat = 0
    for frame in frames where frame.maxY > Metrics.topInset && frame.minY < visibleBottom {
      uncovered += max(0, frame.minY - coveredThrough)
      coveredThrough = max(coveredThrough, frame.maxY)
    }
    uncovered += max(0, visibleBottom - coveredThrough)
    if uncovered > Metrics.tolerance {
      let format = UIGraphicsImageRendererFormat()
      format.scale = 1
      let image = UIGraphicsImageRenderer(bounds: view.bounds, format: format).image { context in
        view.layer.render(in: context.cgContext)
      }
      let attachment = XCTAttachment(image: image)
      attachment.name = diagnostic(view)
      attachment.lifetime = .keepAlways
      add(attachment)
    }
    XCTAssertLessThanOrEqual(uncovered, Metrics.tolerance, diagnostic(view), file: file, line: line)
  }

  private func diagnostic(_ view: HostView) -> String {
    let collection = view.test_collectionView
    let contentBottom = view.test_messageFrame(at: Metrics.rowCount - 1)?.maxY ?? .nan
    let maximumOffset = collection.contentSize.height + collection.adjustedContentInset.bottom - collection.bounds.height
    return "height=\(view.bounds.height), offset=\(collection.contentOffset.y), max=\(maximumOffset), "
      + "lastRowBottom=\(contentBottom - collection.contentOffset.y), chromeTop=\(view.bounds.height - view.swiftUIWorldSafeAreaInset.bottom)"
  }
}
