import MessagingUI
import SwiftUI
import Testing
import UIKit

@Suite("TiledView animated bottom inset", .serialized)
@MainActor
struct TiledViewAnimatedInsetTests {
  fileprivate enum Metrics {
    static let viewport = CGSize(width: 390, height: 844)
    static let rowCount = 60
    static let rowHeight: CGFloat = 48
    static let rowLabelPadding: CGFloat = 12
    static let historyIndex = 20
    static let restingInset: CGFloat = 80
    static let keyboardLift: CGFloat = 300
    static let scrollStep: CGFloat = 4
    static let animationDuration: TimeInterval = 0.4
    static let sampleDuration: TimeInterval = 0.6
    static let waitTimeout: TimeInterval = 5
    static let runLoopSlice: TimeInterval = 0.02
    static let tolerance: CGFloat = 2
    static let captureProgress: [CGFloat] = [0.25, 0.5, 0.75]
    static let reservationIdentifier = "tiled-animated-inset-reservation"
  }

  fileprivate enum Position: Equatable {
    case history
    case draggingHistory
    case deceleratingHistory
    case bottom
    case initialAnchor

    var hasUserScrollSession: Bool {
      self == .draggingHistory || self == .deceleratingHistory
    }

    var isHistory: Bool {
      self == .history || hasUserScrollSession
    }
  }

  fileprivate struct Row: Identifiable, Hashable {
    let id: Int
  }

  private struct RowCell: TiledCellContent {
    let item: Row

    func body(context: CellContext<Void>) -> some View {
      (item.id.isMultiple(of: 2) ? Color.blue : Color.purple)
        .frame(height: Metrics.rowHeight)
        .overlay(alignment: .leading) {
          Text("\(item.id)")
            .font(.caption)
            .foregroundStyle(.white)
            .padding(.leading, Metrics.rowLabelPadding)
        }
    }
  }

  @Observable
  @MainActor
  fileprivate final class Model {
    let rows = (0..<Metrics.rowCount).map { Row(id: $0) }
    let initialTargetID: Int?
    var bottomInset = Metrics.restingInset + Metrics.keyboardLift
    var position = TiledScrollPosition(
      autoScrollsToBottomOnAppend: false,
      scrollsToBottomOnReplace: true
    )

    init(position: Position) {
      initialTargetID = position == .initialAnchor ? Metrics.historyIndex : nil
    }
  }

  private struct Harness: View {
    @Bindable var model: Model

    var body: some View {
      TiledView(items: model.rows, scrollPosition: $model.position) { RowCell(item: $0) }
        .initialScrollTarget(id: model.initialTargetID.map { AnyHashable($0) }, anchor: .top)
        .additionalContentInset(EdgeInsets(top: 0, leading: 0, bottom: model.bottomInset, trailing: 0))
        .overlay(alignment: .bottom) {
          Reservation()
            .frame(height: model.bottomInset)
            .allowsHitTesting(false)
        }
        .ignoresSafeArea()
    }
  }

  private struct Reservation: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
      let view = UIView()
      view.backgroundColor = .gray
      view.accessibilityIdentifier = Metrics.reservationIdentifier
      return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
  }

  private struct Host {
    let window: UIWindow
    let collectionView: UICollectionView
    let layout: TiledCollectionViewLayout
    let reservation: UIView
    let messagesSection: Int
  }

  private struct Sample {
    let uncoveredHeight: CGFloat
    let unpaintedHeight: CGFloat
    let reservationHeight: CGFloat
    let horizontalOffset: CGFloat
    let diagnostic: String
  }

  @Test
  func `collapsing the bottom inset leaves idle history in place`() throws {
    try verifyCollapse(from: .history)
  }

  @Test
  func `collapsing the bottom inset preserves a dragging history session and its cells`() throws {
    try verifyCollapse(from: .draggingHistory)
  }

  @Test
  func `collapsing the bottom inset preserves a decelerating history session and its cells`() throws {
    try verifyCollapse(from: .deceleratingHistory)
  }

  @Test
  func `collapsing the bottom inset keeps the newest row above chrome`() throws {
    try verifyCollapse(from: .bottom)
  }

  @Test
  func `collapsing the bottom inset preserves the initial target`() throws {
    try verifyCollapse(from: .initialAnchor)
  }

  private func verifyCollapse(from position: Position) throws {
    let model = Model(position: position)
    let capture = try TiledInsetRenderedCapture()
    let host = try mount(model)
    let animationsEnabled = UIView.areAnimationsEnabled
    UIView.setAnimationsEnabled(true)
    defer {
      UIView.setAnimationsEnabled(animationsEnabled)
      host.window.rootViewController = nil
      host.window.isHidden = true
      host.window.windowScene = nil
    }

    if position.isHistory {
      model.position.scrollTo(id: Metrics.historyIndex, anchor: .top, animated: false)
      try #require(waitUntil {
        abs(rowTop(Metrics.historyIndex, in: host) - readableTop(in: host)) < Metrics.tolerance
      }, "history top \(rowTop(Metrics.historyIndex, in: host)), readable top \(readableTop(in: host))")
    }

    let offsetBefore = host.collectionView.contentOffset
    let anchorBefore = rowTop(Metrics.historyIndex, in: host)
    let initialReservationHeight = host.reservation.bounds.height
    capture.captureFrame(of: host.window, name: "resting")
    let resting = try sample(host, capture: capture)
    try #require(
      resting.uncoveredHeight < Metrics.tolerance,
      "resting blank height \(resting.uncoveredHeight); \(resting.diagnostic)"
    )
    try #require(
      resting.unpaintedHeight < Metrics.tolerance,
      "resting unpainted row height \(resting.unpaintedHeight); \(capture.directory.path)"
    )

    if position.hasUserScrollSession {
      host.collectionView.delegate?.scrollViewWillBeginDragging?(host.collectionView)
      if position == .deceleratingHistory {
        host.collectionView.delegate?.scrollViewDidEndDragging?(host.collectionView, willDecelerate: true)
      }
    }
    defer {
      if position == .draggingHistory {
        host.collectionView.delegate?.scrollViewDidEndDragging?(host.collectionView, willDecelerate: false)
      } else if position == .deceleratingHistory {
        host.collectionView.delegate?.scrollViewDidEndDecelerating?(host.collectionView)
      }
    }

    withAnimation(.linear(duration: Metrics.animationDuration)) {
      model.bottomInset = Metrics.restingInset
    }

    var samples: [Sample] = []
    var expectedOffsetY = offsetBefore.y
    var largestUserMotionError: CGFloat = 0
    var captureIndex = 0
    let motionDeadline = Date(timeIntervalSinceNow: Metrics.animationDuration)
    let deadline = Date(timeIntervalSinceNow: Metrics.sampleDuration)
    while Date() < deadline {
      if position.hasUserScrollSession, Date() < motionDeadline {
        // Model UIKit's offset writes during the delegate-owned scroll session.
        expectedOffsetY -= Metrics.scrollStep
        host.collectionView.setContentOffset(CGPoint(x: offsetBefore.x, y: expectedOffsetY), animated: false)
      }
      RunLoop.main.run(until: Date(timeIntervalSinceNow: Metrics.runLoopSlice))
      let current = try sample(host, capture: capture)
      samples.append(current)
      let progress = (initialReservationHeight - current.reservationHeight) / Metrics.keyboardLift
      if captureIndex < Metrics.captureProgress.count,
         progress >= Metrics.captureProgress[captureIndex] {
        capture.captureFrame(of: host.window, name: "transition-\(captureIndex)")
        captureIndex += 1
      }
      if position.hasUserScrollSession {
        largestUserMotionError = max(largestUserMotionError, abs(host.collectionView.contentOffset.y - expectedOffsetY))
      }
    }
    host.window.layoutIfNeeded()
    capture.captureFrame(of: host.window, name: "settled")
    try capture.writeFrames()

    let finalReservationHeight = host.reservation.bounds.height
    let intermediate = samples.filter {
      $0.reservationHeight < initialReservationHeight - Metrics.tolerance
        && $0.reservationHeight > finalReservationHeight + Metrics.tolerance
    }
    #expect(!intermediate.isEmpty, "the test must observe the reservation while it animates")
    #expect(captureIndex == Metrics.captureProgress.count, "missing rendered transition frames; \(capture.directory.path)")
    let worst = samples.max { $0.uncoveredHeight < $1.uncoveredHeight }
    #expect(
      (worst?.uncoveredHeight ?? .infinity) < Metrics.tolerance,
      "blank viewport height \(worst?.uncoveredHeight ?? -1); \(worst?.diagnostic ?? "no samples")"
    )
    let mostUnpainted = samples.map(\.unpaintedHeight).max() ?? .infinity
    #expect(mostUnpainted < Metrics.tolerance, "unpainted row height \(mostUnpainted); \(capture.directory.path)")
    #expect(samples.allSatisfy { abs($0.horizontalOffset - offsetBefore.x) < Metrics.tolerance })
    #expect(firstCollectionView(in: host.window) === host.collectionView)
    #expect(largestUserMotionError < Metrics.tolerance, "inset animation added \(largestUserMotionError)pt to user motion")

    switch position {
    case .history, .draggingHistory, .deceleratingHistory:
      #expect(
        abs(host.collectionView.contentOffset.y - expectedOffsetY) < Metrics.tolerance,
        "history offset \(host.collectionView.contentOffset.y), expected \(expectedOffsetY)"
      )
      let expectedAnchor = anchorBefore + offsetBefore.y - expectedOffsetY
      #expect(abs(rowTop(Metrics.historyIndex, in: host) - expectedAnchor) < Metrics.tolerance)
    case .bottom:
      let newestBottom = rowTop(Metrics.rowCount - 1, in: host) + Metrics.rowHeight
      let readableBottom = host.collectionView.bounds.height - host.layout.additionalContentInset.bottom
      #expect(abs(newestBottom - readableBottom) < Metrics.tolerance)
    case .initialAnchor:
      #expect(abs(rowTop(Metrics.historyIndex, in: host) - anchorBefore) < Metrics.tolerance)
    }
  }

  private func mount(_ model: Model) throws -> Host {
    let controller = UIHostingController(rootView: Harness(model: model))
    let frame = CGRect(origin: .zero, size: Metrics.viewport)
    let window = if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
      UIWindow(windowScene: scene)
    } else {
      UIWindow(frame: frame)
    }
    window.frame = frame
    window.rootViewController = controller
    controller.view.frame = window.bounds
    window.isHidden = false
    window.layoutIfNeeded()
    try #require(waitUntil {
      guard let collection = firstCollectionView(in: window) else { return false }
      return collection.contentSize.height > Metrics.viewport.height
        && collection.numberOfSections > 0
        && view(withIdentifier: Metrics.reservationIdentifier, in: window) != nil
        && !collection.visibleCells.isEmpty
    }, "the hosted timeline did not lay out")
    let collection = try #require(firstCollectionView(in: window))
    let layout = try #require(collection.collectionViewLayout as? TiledCollectionViewLayout)
    let section = try #require((0..<collection.numberOfSections).first {
      collection.numberOfItems(inSection: $0) == Metrics.rowCount
    })
    let reservation = try #require(view(withIdentifier: Metrics.reservationIdentifier, in: window))
    RunLoop.main.run(until: Date(timeIntervalSinceNow: Metrics.animationDuration))
    return Host(
      window: window,
      collectionView: collection,
      layout: layout,
      reservation: reservation,
      messagesSection: section
    )
  }

  private func sample(_ host: Host, capture: TiledInsetRenderedCapture) throws -> Sample {
    let collection = host.collectionView
    let viewport = presentedFrame(collection, in: host.window)
    let reservation = presentedFrame(host.reservation, in: host.window)
    let top = viewport.minY + host.layout.additionalContentInset.top
    let bottom = min(viewport.maxY, reservation.minY)
    let readableRect = CGRect(x: viewport.minX, y: top, width: viewport.width, height: max(0, bottom - top))
    // Inspect instantiated cells at their presentation positions; layout attributes
    // can cover a viewport even when UIKit has not created the cells to draw it.
    let cells = instantiatedCells(in: collection)
    let frames = cells.compactMap { cell -> CGRect? in
      guard !cell.isHidden, cell.alpha > 0, cell.contentConfiguration != nil else { return nil }
      let frame = presentedFrame(cell, in: host.window)
      return frame.minX <= viewport.midX && frame.maxX >= viewport.midX ? frame : nil
    }.sorted { $0.minY < $1.minY }
    var coveredUntil = top
    var uncoveredHeight: CGFloat = 0
    for frame in frames where frame.maxY > top && frame.minY < bottom {
      uncoveredHeight += max(0, min(frame.minY, bottom) - coveredUntil)
      coveredUntil = max(coveredUntil, min(frame.maxY, bottom))
    }
    uncoveredHeight += max(0, bottom - coveredUntil)
    return try Sample(
      uncoveredHeight: uncoveredHeight,
      unpaintedHeight: capture.unpaintedRowHeight(in: host.window, readableRect: readableRect),
      reservationHeight: reservation.height,
      horizontalOffset: collection.layer.presentation()?.bounds.minX ?? collection.bounds.minX,
      diagnostic: "viewport \(viewport), readable \(top)...\(bottom), cells \(cells.count), frames \(frames)"
    )
  }

  private func instantiatedCells(in view: UIView) -> [UICollectionViewCell] {
    if let cell = view as? UICollectionViewCell { return [cell] }
    return view.subviews.flatMap { instantiatedCells(in: $0) }
  }

  private func presentedFrame(_ view: UIView, in window: UIWindow) -> CGRect {
    if let layer = view.layer.presentation(), let windowLayer = window.layer.presentation() {
      return layer.convert(layer.bounds, to: windowLayer)
    }
    return view.convert(view.bounds, to: window)
  }

  private func rowTop(_ index: Int, in host: Host) -> CGFloat {
    let path = IndexPath(item: index, section: host.messagesSection)
    guard let frame = host.collectionView.layoutAttributesForItem(at: path)?.frame else { return .nan }
    return frame.minY - host.collectionView.contentOffset.y
  }

  private func readableTop(in host: Host) -> CGFloat {
    host.layout.additionalContentInset.top
  }

  private func waitUntil(_ condition: () -> Bool) -> Bool {
    let deadline = Date(timeIntervalSinceNow: Metrics.waitTimeout)
    while Date() < deadline {
      if condition() { return true }
      RunLoop.main.run(until: Date(timeIntervalSinceNow: Metrics.runLoopSlice))
    }
    return condition()
  }

  private func firstCollectionView(in view: UIView) -> UICollectionView? {
    if let collection = view as? UICollectionView { return collection }
    for child in view.subviews {
      if let collection = firstCollectionView(in: child) { return collection }
    }
    return nil
  }

  private func view(withIdentifier identifier: String, in root: UIView) -> UIView? {
    if root.accessibilityIdentifier == identifier { return root }
    for child in root.subviews {
      if let match = view(withIdentifier: identifier, in: child) { return match }
    }
    return nil
  }
}
