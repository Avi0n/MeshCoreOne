import Foundation
@testable import MessagingUI
import SwiftUI
import Testing
import UIKit

@Suite("Native viewport scroll preservation", .serialized)
@MainActor
final class NativeViewportScrollPreservationTests {
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
    static let rowGrowthTimeout: Duration = .seconds(5)
    static let rowGrowthPollInterval: Duration = .milliseconds(16)
    static let heightCycle: [CGFloat] = [744, 644, keyboardHeight, 644, 744, restingHeight]
    static let dismissalHeights: [CGFloat] = [592, 642, 692, 742, 792, restingHeight]
    static let userInteractions: [(isTracking: Bool, isDragging: Bool, isDecelerating: Bool)] = [
      (true, false, false), (true, true, false), (false, false, true),
    ]
  }

  private typealias HostView = TiledUIView<Message, MessageCell, Never, Never, Never, Never, Void>
  private var windows: [UIWindow] = []

  isolated deinit {
    for window in windows {
      window.isHidden = true
    }
  }

  @Test
  func `idle bottom stays at bottom across native viewport cycle`() throws {
    let view = mount()
    try assertLastRowMeetsChrome(view)

    for height in Metrics.heightCycle {
      resize(view, to: height)
      try assertLastRowMeetsChrome(view)
      assertVisibleCellCoverage(view)
    }
  }

  @Test
  func `idle bottom stays at bottom when layout is invalidated before viewport resize`() throws {
    let view = mount()
    let originalOffset = view.test_contentOffsetY
    try assertLastRowMeetsChrome(view)

    for height in [Metrics.keyboardHeight, Metrics.restingHeight] {
      view.test_collectionView.collectionViewLayout.invalidateLayout()
      resize(view, to: height)

      #expect(abs((view.test_contentOffsetY) - (originalOffset + Metrics.restingHeight - height)) <= Metrics.tolerance, "\(diagnostic(view))")
      try assertLastRowMeetsChrome(view)
      assertVisibleCellCoverage(view)
    }
  }

  @Test
  func `idle bottom stays at bottom when viewport shrinks and composer grows in one layout pass`() throws {
    for order in [GeometryUpdateOrder.viewportThenInset, .insetThenViewport] {
      let view = mount()
      try assertLastRowMeetsChrome(view)
      let originalOffset = view.test_contentOffsetY

      for height in [Metrics.keyboardHeight, Metrics.restingHeight] {
        resize(view, to: height, order: order)
        let inset = chromeInset(for: height, order: order)
        let collectionLayout = try #require(view.test_collectionView.collectionViewLayout as? TiledCollectionViewLayout)
        #expect(abs((collectionLayout.additionalContentInset.bottom) - inset) <= Metrics.tolerance)
        #expect(abs((view.test_contentOffsetY) - (originalOffset + Metrics.restingHeight - height + inset - Metrics.chromeInset)) <= Metrics.tolerance, "\(diagnostic(view))")
        try assertLastRowMeetsChrome(view)
        assertVisibleCellCoverage(view)
      }
    }
  }

  @Test
  func `idle history keeps lowest visible message above chrome across native viewport cycle`() throws {
    for order in GeometryUpdateOrder.allCases {
      let view = mount(targetID: Metrics.historyRow)
      let collection = view.test_collectionView
      let visibleItems = collection.indexPathsForVisibleItems
        .filter { $0.section == TiledCollectionViewLayout.DisplaySection.messages.rawValue }
        .filter { (view.test_messageFrame(at: $0.item)?.minY ?? .infinity) - view.test_contentOffsetY
          < view.bounds.height - Metrics.chromeInset
        }
        .map(\.item)
      let lowestRow = try #require(visibleItems.max())
      let originalBottom = try instantiatedFrame(of: lowestRow, in: view).maxY
      let originalOffset = view.test_contentOffsetY

      for height in Metrics.heightCycle {
        resize(view, to: height, order: order)
        #expect(abs((view.test_contentOffsetY) - (originalOffset + Metrics.restingHeight - height)) <= Metrics.tolerance, "\(diagnostic(view))")
        #expect(try abs((instantiatedFrame(of: lowestRow, in: view).maxY) - (originalBottom + height - Metrics.restingHeight)) <= Metrics.tolerance, "\(diagnostic(view))")
        assertVisibleCellCoverage(view)
      }
    }
  }

  @Test
  func `idle near bottom preserves its distance across native viewport cycle`() throws {
    for order in GeometryUpdateOrder.allCases {
      let view = mount()
      view.test_collectionView.contentOffset.y -= Metrics.bottomReadingGap
      #expect(abs((view.test_pointsFromBottom) - (Metrics.bottomReadingGap)) <= Metrics.tolerance)

      for height in Metrics.heightCycle {
        resize(view, to: height, order: order)
        #expect(abs((view.test_pointsFromBottom) - (Metrics.bottomReadingGap)) <= Metrics.tolerance, "\(diagnostic(view))")
        #expect(try abs((instantiatedFrame(of: Metrics.rowCount - 1, in: view).maxY) - (height - view.swiftUIWorldSafeAreaInset.bottom + Metrics.bottomReadingGap)) <= Metrics.tolerance, "\(diagnostic(view))")
        assertVisibleCellCoverage(view)
      }
    }
  }

  @Test
  func `idle history preserves reading position when row above viewport and composer grow`() async throws {
    let heightModel = MessageHeight(Metrics.rowHeight)
    let view = mount(targetID: Metrics.historyRow, heightModel: heightModel)
    let growingRow = Metrics.historyRow - 1
    let originalFrame = try instantiatedFrame(of: growingRow, in: view)
    let originalY = try instantiatedFrame(of: Metrics.historyRow, in: view).minY
    let originalOffset = view.test_contentOffsetY
    #expect(abs((originalFrame.maxY) - (Metrics.topInset)) <= Metrics.tolerance)
    #expect(!view.isInitialAnchorActive)

    heightModel.value += Metrics.rowHeightGrowth
    view.swiftUIWorldSafeAreaInset.bottom += Metrics.composerLineHeight
    let insetFrame = try instantiatedFrame(of: growingRow, in: view)
    let insetOffset = view.test_contentOffsetY
    #expect(try abs((instantiatedFrame(of: Metrics.historyRow, in: view).minY) - originalY) <= Metrics.tolerance, "\(diagnostic(view))")

    let clock = ContinuousClock()
    let deadline = clock.now.advanced(by: Metrics.rowGrowthTimeout)
    while clock.now < deadline {
      layout(view)
      #expect(try abs((instantiatedFrame(of: Metrics.historyRow, in: view).minY) - originalY) <= Metrics.tolerance, "\(diagnostic(view))")
      let frame = try instantiatedFrame(of: growingRow, in: view)
      if abs(frame.height - originalFrame.height - Metrics.rowHeightGrowth) <= Metrics.tolerance {
        break
      }
      try await Task.sleep(for: Metrics.rowGrowthPollInterval)
    }

    let finalFrame = try instantiatedFrame(of: growingRow, in: view)
    Attachment.record(
      "growthDuringInset=\(insetFrame.height - originalFrame.height), "
        + "offsetDuringInset=\(insetOffset - originalOffset), "
        + "finalGrowth=\(finalFrame.height - originalFrame.height), "
        + "finalOffset=\(view.test_contentOffsetY - originalOffset)",
      named: "Self-sizing and inset ordering"
    )
    #expect(abs((finalFrame.height - originalFrame.height) - (Metrics.rowHeightGrowth)) <= Metrics.tolerance)
    #expect(abs((view.test_contentOffsetY - originalOffset) - (Metrics.rowHeightGrowth)) <= Metrics.tolerance)
    assertVisibleCellCoverage(view)
  }

  @Test
  func `held initial anchor tracks native viewport center`() throws {
    for order in GeometryUpdateOrder.allCases {
      let view = mount(targetID: Metrics.historyRow, anchor: .center, holdAnchor: true)
      #expect(view.isInitialAnchorActive)

      for height in Metrics.heightCycle {
        resize(view, to: height, order: order)
        let expectedCenter = Metrics.topInset + (height - Metrics.topInset - view.swiftUIWorldSafeAreaInset.bottom) / 2
        #expect(view.isInitialAnchorActive)
        #expect(try abs((instantiatedFrame(of: Metrics.historyRow, in: view).midY) - expectedCenter) <= Metrics.tolerance, "\(diagnostic(view))")
        assertVisibleCellCoverage(view)
      }
    }
  }

  @Test
  func `active bottom remains covered while native viewport expands`() throws {
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
          #expect(abs((collection.contentOffset.y) - min(requestedOffset, maximumOffset)) <= Metrics.tolerance, "\(diagnostic(view))")
          assertVisibleCellCoverage(view)
        }
      }
    }
  }

  @Test
  func `active history preserves user motion while native viewport expands`() throws {
    for order in GeometryUpdateOrder.allCases {
      for interaction in Metrics.userInteractions {
        let view = mount(
          height: Metrics.keyboardHeight, chromeInset: chromeInset(for: Metrics.keyboardHeight, order: order),
          targetID: Metrics.historyRow, holdAnchor: interaction.isDragging || interaction.isDecelerating
        )
        beginInteraction(interaction, in: view)
        #expect(!view.isInitialAnchorActive)

        for height in Metrics.dismissalHeights {
          let collection = view.test_collectionView
          collection.contentOffset.y -= Metrics.scrollStep
          let requestedOffset = collection.contentOffset.y
          let expectedY = try instantiatedFrame(of: Metrics.historyRow, in: view).minY
          resize(view, to: height, order: order)
          #expect(abs((collection.contentOffset.y) - requestedOffset) <= Metrics.tolerance, "\(diagnostic(view))")
          #expect(try abs((instantiatedFrame(of: Metrics.historyRow, in: view).minY) - expectedY) <= Metrics.tolerance, "\(diagnostic(view))")
          assertVisibleCellCoverage(view)
        }
      }
    }
  }

  @Test
  func `explicit scroll between viewport capture and layout wins`() {
    let view = mount()
    let capturedOffset = view.test_contentOffsetY
    captureViewportChange(view, to: Metrics.keyboardHeight)

    var position = TiledScrollPosition()
    position.scrollTo(edge: .top, animated: false)
    view.applyScrollPosition(position)
    let requestedOffset = view.test_contentOffsetY
    #expect(requestedOffset != capturedOffset)

    resize(view, to: Metrics.keyboardHeight)

    #expect(abs((view.test_contentOffsetY) - requestedOffset) <= Metrics.tolerance, "\(diagnostic(view))")
    assertVisibleCellCoverage(view)
  }

  @Test
  func `drag beginning and ending between capture and layout is not replayed`() {
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
      #expect(!view.isInitialAnchorActive)

      resize(view, to: Metrics.keyboardHeight)

      #expect(abs((view.test_contentOffsetY) - requestedOffset) <= Metrics.tolerance, "\(diagnostic(view))")
      assertVisibleCellCoverage(view)
    }
  }

  @Test
  func `bounds only change reports final geometry with history anchor preserved`() throws {
    let view = mount(targetID: Metrics.historyRow)
    let initialOffset = view.test_contentOffsetY
    var reports: [TiledScrollGeometry] = []
    view.onTiledScrollGeometryChange = { reports.append($0) }

    for height in Metrics.heightCycle {
      reports.removeAll()
      resize(view, to: height)
      let expectedOffset = initialOffset + Metrics.restingHeight - height
      _ = try #require(reports.last, "Bounds-only changes must publish their new visible size")
      #expect(abs((view.test_contentOffsetY) - expectedOffset) <= Metrics.tolerance)
      for report in reports {
        #expect(abs((report.visibleSize.height) - height) <= Metrics.tolerance)
        #expect(abs((report.contentOffset.y) - expectedOffset) <= Metrics.tolerance)
        #expect(report.contentInset == view.test_collectionView.adjustedContentInset)
        #expect(report.contentSize == view.test_collectionView.contentSize)
      }
    }
  }

  @Test
  func `model offset already adjusted for viewport is not adjusted twice`() {
    let view = mount(height: Metrics.keyboardHeight)
    view.test_collectionView.contentOffset.y -= Metrics.bottomReadingGap
    #expect(abs((view.test_pointsFromBottom) - (Metrics.bottomReadingGap)) <= Metrics.tolerance)
    let originalOffset = view.test_contentOffsetY
    captureViewportChange(view, to: Metrics.restingHeight)

    // Emulate UIKit having applied the complete model offset before the parent's layout pass.
    let adjustedOffset = originalOffset - (Metrics.restingHeight - Metrics.keyboardHeight)
    view.test_collectionView.contentOffset.y = adjustedOffset
    resize(view, to: Metrics.restingHeight)

    #expect(abs((view.test_contentOffsetY) - adjustedOffset) <= Metrics.tolerance, "\(diagnostic(view))")
    #expect(abs((view.test_pointsFromBottom) - (Metrics.bottomReadingGap)) <= Metrics.tolerance)
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
    #expect(abs((view.test_collectionView.bounds.height) - height) <= Metrics.tolerance)
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
    let cell = try #require(view.test_collectionView.cellForItem(at: indexPath), "\(diagnostic(view))")
    #expect(!cell.isHidden)
    #expect(cell.alpha > 0)
    #expect(cell.contentView.alpha > 0)
    return cell.convert(cell.bounds, to: view)
  }

  private func assertLastRowMeetsChrome(
    _ view: HostView,
    sourceLocation: SourceLocation = SourceLocation(fileID: #fileID, filePath: #filePath, line: #line, column: #column)
  ) throws {
    #expect(try abs((instantiatedFrame(of: Metrics.rowCount - 1, in: view).maxY) - (view.bounds.height - view.swiftUIWorldSafeAreaInset.bottom)) <= Metrics.tolerance, "\(diagnostic(view))", sourceLocation: sourceLocation)
  }

  private func assertVisibleCellCoverage(
    _ view: HostView,
    sourceLocation: SourceLocation = SourceLocation(fileID: #fileID, filePath: #filePath, line: #line, column: #column)
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
      Attachment.record(image, named: diagnostic(view), as: .png)
    }
    #expect(uncovered <= Metrics.tolerance, "\(diagnostic(view))", sourceLocation: sourceLocation)
  }

  private func diagnostic(_ view: HostView) -> String {
    let collection = view.test_collectionView
    let contentBottom = view.test_messageFrame(at: Metrics.rowCount - 1)?.maxY ?? .nan
    let maximumOffset = collection.contentSize.height + collection.adjustedContentInset.bottom - collection.bounds.height
    return "height=\(view.bounds.height), offset=\(collection.contentOffset.y), max=\(maximumOffset), "
      + "lastRowBottom=\(contentBottom - collection.contentOffset.y), chromeTop=\(view.bounds.height - view.swiftUIWorldSafeAreaInset.bottom)"
  }
}
