@testable import MC1
import MessagingUI
import SwiftUI
import UIKit
import XCTest

extension ChatKeyboardTransitionTests {
  @Observable
  @MainActor
  final class ComposerModel {
    var text = ""
  }

  enum Metrics {
    static let rowCount = 80
    static let rowHeight: CGFloat = 44
    static let tallRowHeight: CGFloat = 380
    static let markerWidth: CGFloat = 12
    static let markerHeight: CGFloat = 4
    static let latestRowStripeHeight: CGFloat = 4
    static let latestRowSampleColumn: CGFloat = 0.7
    static let minimumKeyboardHeight: CGFloat = 100
    static let alignmentTolerance: CGFloat = 8
    static let minimumTransitionSamples = 3
    static let scrollStep: CGFloat = 60
    static let historyRows = 16
    static let nearBottomHistoryDistance: CGFloat = 20
    static let coverageTolerance: CGFloat = 2
    static let topEffectClearance: CGFloat = 32
    static let sampleColumns: [CGFloat] = [0.35, 0.7]
    static let pixelTolerance = 8
    static let composerIdentifier = "native-keyboard-coverage-composer"
    static let sceneStartupTimeout: TimeInterval = 15
    static let timeout: TimeInterval = 5
    static let pollInterval: Duration = .milliseconds(16)
    static let settleInterval: Duration = .milliseconds(500)
  }

  struct ComposerBoundsMarker: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
      let view = UIView()
      view.isUserInteractionEnabled = false
      view.accessibilityIdentifier = Metrics.composerIdentifier
      return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
  }

  struct CellSample: Codable {
    let item: Int?
    let frame: CGRect
    let modelFrame: CGRect
    let opacity: Float
    let contentOpacity: Float
  }

  struct Coverage: Codable {
    let top: CGFloat
    let bottom: CGFloat
    let uncoveredHeight: CGFloat
    let longestNonRowRun: Int
    let nonRowPixels: Int
    let bluePixels: Int
    let indigoPixels: Int
    let modelHeight: CGFloat
    let presentationHeight: CGFloat
    let modelOffset: CGFloat
    let presentedOffset: CGFloat
    let presentedContentBottom: CGFloat?
    let bottomInset: CGFloat
    let cells: [CellSample]
  }

  struct Sample: Codable {
    let phase: String
    let timestamp: TimeInterval
    let composerBottom: CGFloat
    let composerTop: CGFloat
    let keyboardTop: CGFloat
    let latestRenderedBottom: CGFloat?
    let restingBottom: CGFloat
    let coverage: Coverage?

    var composerKeyboardGap: CGFloat {
      min(keyboardTop, restingBottom) - composerBottom
    }
  }

  struct KeyboardNotification: Codable {
    let name: String
    let timestamp: TimeInterval
    let overlap: CGFloat
    let frame: CGRect
  }

  @MainActor
  final class Recorder: NSObject {
    let window: UIWindow
    var phase = "show"
    var samples: [Sample] = []
    var keyboardNotifications: [KeyboardNotification] = []
    var missingMarkerFrames = 0
    var missingPresentationFrames = 0
    var collection: UICollectionView? {
      descendants(UICollectionView.self, in: window).first
    }

    private var isSimulatingDrag = false
    private var worstCoverage: CGFloat = 0
    private var worstCoverageFrame: UIImage?
    private var baselineFrame: UIImage?
    private var beforeFocusFrame: UIImage?
    private var displayLink: CADisplayLink?
    private var worstFrame: UIImage?
    private var worstGap: CGFloat = 0
    private let artifactID = UUID()

    init(window: UIWindow) {
      self.window = window
    }

    deinit {
      NotificationCenter.default.removeObserver(self)
    }

    func start() {
      for name in [
        UIResponder.keyboardWillShowNotification, UIResponder.keyboardWillChangeFrameNotification,
        UIResponder.keyboardDidShowNotification, UIResponder.keyboardWillHideNotification,
        UIResponder.keyboardDidHideNotification,
      ] {
        NotificationCenter.default.addObserver(self, selector: #selector(keyboardChanged), name: name, object: nil)
      }
      let link = CADisplayLink(target: self, selector: #selector(tick))
      link.add(to: .main, forMode: .common)
      displayLink = link
    }

    func beginSimulatedDrag() {
      guard let collection else { return }
      collection.delegate?.scrollViewWillBeginDragging?(collection)
      isSimulatingDrag = true
    }

    func captureBaseline() -> Coverage? {
      let image = renderFrame()
      baselineFrame = image
      return coverage(in: image)
    }

    func captureCurrentCoverage() -> Coverage? {
      coverage(in: renderFrame())
    }

    func captureComposerBottom() -> CGFloat? {
      markerPositions(in: renderFrame())?.composer
    }

    func captureBeforeFocus() -> Sample? {
      let image = renderFrame()
      beforeFocusFrame = image
      guard let sample = makeSample(in: image, timestamp: CACurrentMediaTime()) else { return nil }
      samples.append(sample)
      return sample
    }

    func stop() {
      if isSimulatingDrag, let collection {
        collection.delegate?.scrollViewDidEndDragging?(collection, willDecelerate: false)
      }
      isSimulatingDrag = false
      displayLink?.invalidate()
      displayLink = nil
    }

    @objc private func keyboardChanged(_ notification: Notification) {
      guard let frame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
      let intersection = window.bounds.intersection(window.convert(frame, from: nil))
      keyboardNotifications.append(KeyboardNotification(
        name: notification.name.rawValue, timestamp: CACurrentMediaTime(),
        overlap: intersection.isNull ? 0 : intersection.height, frame: frame
      ))
    }

    private func renderFrame() -> UIImage {
      let format = UIGraphicsImageRendererFormat()
      format.scale = 1
      format.preferredRange = .standard
      let presentation = window.layer.presentation()
      if presentation == nil { missingPresentationFrames += 1 }
      return UIGraphicsImageRenderer(bounds: window.bounds, format: format).image { context in
        (presentation ?? window.layer).render(in: context.cgContext)
      }
    }

    @objc private func tick(_ link: CADisplayLink) {
      if isSimulatingDrag, let collection {
        collection.contentOffset.y -= Metrics.scrollStep
      }
      let image = renderFrame()
      if worstFrame == nil { worstFrame = image }
      guard let sample = makeSample(in: image, timestamp: link.timestamp) else { return }
      samples.append(sample)
      let rawGap = abs(min(sample.keyboardTop, sample.restingBottom) - sample.composerBottom)
      if rawGap > worstGap {
        worstGap = rawGap
        worstFrame = image
      }
      if let coverage = sample.coverage {
        let error = max(coverage.uncoveredHeight, CGFloat(coverage.longestNonRowRun))
        if error > worstCoverage || worstCoverageFrame == nil {
          worstCoverage = error
          worstCoverageFrame = image
        }
      }
    }

    private func makeSample(in image: UIImage, timestamp: TimeInterval) -> Sample? {
      guard let positions = markerPositions(in: image) else {
        missingMarkerFrames += 1
        return nil
      }
      return Sample(
        phase: phase, timestamp: timestamp,
        composerBottom: positions.composer, composerTop: positions.composerTop,
        keyboardTop: positions.keyboard, latestRenderedBottom: positions.latest,
        restingBottom: window.bounds.maxY - window.safeAreaInsets.bottom,
        coverage: coverage(in: image, markers: positions)
      )
    }

    private func coverage(
      in image: UIImage, markers: (composer: CGFloat, composerTop: CGFloat, keyboard: CGFloat, latest: CGFloat?)? = nil
    ) -> Coverage? {
      guard let collection, let composerBounds = currentComposerBounds(in: window),
            let layout = collection.collectionViewLayout as? TiledCollectionViewLayout,
            let source = image.cgImage,
            let markers = markers ?? markerPositions(in: image) else { return nil }
      let viewport = presentedFrame(collection)
      let navigationBottom = descendants(UINavigationBar.self, in: window)
        .map { presentedFrame($0).maxY }.max() ?? 0
      let top = max(viewport.minY + layout.additionalContentInset.top, navigationBottom)
        + Metrics.topEffectClearance
      let bottom = min(window.bounds.maxY, presentedFrame(composerBounds).minY) - Metrics.coverageTolerance
      // Match the pixel boundary to this captured frame; presentation time advances while drawing.
      let rasterBottom = markers.composerTop - Metrics.coverageTolerance
      guard bottom > top + Metrics.rowHeight, rasterBottom > top + Metrics.rowHeight else { return nil }
      let cells = descendants(UICollectionViewCell.self, in: collection)
        .filter { !$0.isHidden && $0.contentConfiguration != nil }
        .map { cell in
          CellSample(
            item: collection.indexPath(for: cell)?.item,
            frame: presentedFrame(cell), modelFrame: cell.convert(cell.bounds, to: window),
            opacity: cell.layer.presentation()?.opacity ?? cell.layer.opacity,
            contentOpacity: cell.contentView.layer.presentation()?.opacity ?? cell.contentView.layer.opacity
          )
        }
      let bytesPerPixel = 4
      let bytesPerRow = source.width * bytesPerPixel
      var pixels = [UInt8](repeating: 0, count: bytesPerRow * source.height)
      let decoded = pixels.withUnsafeMutableBytes { buffer in
        guard let context = CGContext(
          data: buffer.baseAddress, width: source.width, height: source.height,
          bitsPerComponent: 8, bytesPerRow: bytesPerRow, space: CGColorSpaceCreateDeviceRGB(),
          bitmapInfo: CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
        ) else { return false }
        context.draw(source, in: CGRect(x: 0, y: 0, width: source.width, height: source.height))
        return true
      }
      guard decoded else { return nil }
      let presentedOffset = collection.layer.presentation()?.bounds.minY ?? collection.bounds.minY
      let messageSection = (0..<collection.numberOfSections).first {
        collection.numberOfItems(inSection: $0) == Metrics.rowCount
      }
      let contentBottom = messageSection.flatMap {
        layout.layoutAttributesForItem(at: IndexPath(item: Metrics.rowCount - 1, section: $0))?.frame.maxY
      }
      var maximumUncovered: CGFloat = 0
      var longestRun = 0, nonRowPixels = 0, bluePixels = 0, indigoPixels = 0
      for fraction in Metrics.sampleColumns {
        let column = viewport.minX + viewport.width * fraction
        let ordered = cells.filter {
          $0.opacity > 0 && $0.contentOpacity > 0 && $0.frame.minX <= column && $0.frame.maxX >= column
        }.sorted { $0.frame.minY < $1.frame.minY }
        var coveredThrough = top
        var uncovered: CGFloat = 0
        for cell in ordered where cell.frame.maxY > top && cell.frame.minY < bottom {
          uncovered += max(0, min(cell.frame.minY, bottom) - coveredThrough)
          coveredThrough = max(coveredThrough, min(cell.frame.maxY, bottom))
        }
        maximumUncovered = max(maximumUncovered, uncovered + max(0, bottom - coveredThrough))
        var run = 0
        let x = min(source.width - 1, max(0, Int(column)))
        for y in max(0, Int(ceil(top)))..<min(source.height, Int(floor(rasterBottom))) {
          let offset = y * bytesPerRow + x * bytesPerPixel
          let red = Int(pixels[offset]), green = Int(pixels[offset + 1]), blue = Int(pixels[offset + 2])
          let isBlue = red <= Metrics.pixelTolerance && green <= Metrics.pixelTolerance
            && blue >= 255 - Metrics.pixelTolerance
          let isIndigo = abs(red - 102) <= Metrics.pixelTolerance && green <= Metrics.pixelTolerance
            && blue >= 255 - Metrics.pixelTolerance
          let isCyan = red <= Metrics.pixelTolerance && green >= 255 - Metrics.pixelTolerance
            && blue >= 255 - Metrics.pixelTolerance
          if isBlue || isIndigo || isCyan {
            if isBlue { bluePixels += 1 } else if isIndigo { indigoPixels += 1 }
            run = 0
          } else {
            nonRowPixels += 1
            run += 1
            longestRun = max(longestRun, run)
          }
        }
      }
      return Coverage(
        top: top, bottom: bottom, uncoveredHeight: maximumUncovered,
        longestNonRowRun: longestRun, nonRowPixels: nonRowPixels,
        bluePixels: bluePixels, indigoPixels: indigoPixels,
        modelHeight: collection.bounds.height, presentationHeight: viewport.height,
        modelOffset: collection.contentOffset.y,
        presentedOffset: presentedOffset,
        presentedContentBottom: contentBottom.map { viewport.minY + $0 - presentedOffset },
        bottomInset: layout.additionalContentInset.bottom,
        cells: cells
      )
    }

    private func presentedFrame(_ view: UIView) -> CGRect {
      if let layer = view.layer.presentation(), let windowLayer = window.layer.presentation() {
        return layer.convert(layer.bounds, to: windowLayer)
      }
      return view.convert(view.bounds, to: window)
    }

    private func currentComposerBounds(in view: UIView) -> UIView? {
      if view.accessibilityIdentifier == Metrics.composerIdentifier { return view }
      for child in view.subviews {
        if let marker = currentComposerBounds(in: child) { return marker }
      }
      return nil
    }

    private func descendants<T: UIView>(_ type: T.Type, in view: UIView) -> [T] {
      if let match = view as? T { return [match] }
      return view.subviews.flatMap { descendants(type, in: $0) }
    }

    private func markerPositions(in image: UIImage) -> (composer: CGFloat, composerTop: CGFloat, keyboard: CGFloat, latest: CGFloat?)? {
      guard let source = image.cgImage else { return nil }
      let bytesPerPixel = 4
      let bytesPerRow = source.width * bytesPerPixel
      var pixels = [UInt8](repeating: 0, count: bytesPerRow * source.height)
      let bitmapInfo = CGBitmapInfo.byteOrder32Big.rawValue | CGImageAlphaInfo.premultipliedLast.rawValue
      return pixels.withUnsafeMutableBytes { buffer in
        guard let context = CGContext(
          data: buffer.baseAddress, width: source.width, height: source.height,
          bitsPerComponent: 8, bytesPerRow: bytesPerRow,
          space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: bitmapInfo
        ) else { return nil }
        context.draw(source, in: CGRect(x: 0, y: 0, width: source.width, height: source.height))
        let bytes = buffer.bindMemory(to: UInt8.self)
        let leftColumn = Int(Metrics.markerWidth / 2)
        let rightColumn = source.width - leftColumn
        let latestColumn = Int(CGFloat(source.width) * Metrics.latestRowSampleColumn)
        var composer: Int?
        var composerTop: Int?
        var keyboard: Int?
        var latest: Int?
        for row in 0..<source.height {
          let left = row * bytesPerRow + leftColumn * bytesPerPixel
          let right = row * bytesPerRow + rightColumn * bytesPerPixel
          let stripe = row * bytesPerRow + latestColumn * bytesPerPixel
          if bytes[left] > 220, bytes[left + 1] < 40, bytes[left + 2] < 40 {
            composer = row + 1
          }
          if composerTop == nil, bytes[left] > 220, bytes[left + 1] > 100, bytes[left + 1] < 160, bytes[left + 2] < 40 {
            composerTop = row
          }
          if bytes[right] < 40, bytes[right + 1] > 220, bytes[right + 2] < 40 {
            keyboard = row + 1
          }
          if Int(bytes[stripe]) <= Metrics.pixelTolerance,
             Int(bytes[stripe + 1]) >= 255 - Metrics.pixelTolerance,
             Int(bytes[stripe + 2]) >= 255 - Metrics.pixelTolerance {
            latest = row + 1
          }
        }
        guard let composer, let composerTop, let keyboard else { return nil }
        return (CGFloat(composer), CGFloat(composerTop), CGFloat(keyboard), latest.map(CGFloat.init))
      }
    }

    func writeArtifacts() throws -> URL {
      let directory = FileManager.default.temporaryDirectory
        .appending(path: "chat-keyboard-transition-\(artifactID.uuidString)", directoryHint: .isDirectory)
      try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
      let encoder = JSONEncoder()
      encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
      try encoder.encode(samples).write(to: directory.appending(path: "samples.json"))
      try encoder.encode(keyboardNotifications).write(to: directory.appending(path: "notifications.json"))
      try (worstFrame ?? renderFrame()).pngData()?.write(to: directory.appending(path: "worst-gap.png"))
      try worstCoverageFrame?.pngData()?.write(to: directory.appending(path: "worst-coverage.png"))
      try baselineFrame?.pngData()?.write(to: directory.appending(path: "focused-baseline.png"))
      try beforeFocusFrame?.pngData()?.write(to: directory.appending(path: "before-focus.png"))
      return directory
    }
  }

  func assertLatestRowDuringOpening(_ samples: [Sample], baseline: Sample) throws {
    let lastItem = Metrics.rowCount - 1
    let baselineCell = try XCTUnwrap(baseline.coverage?.cells.first { $0.item == lastItem })
    let baselineStripe = try XCTUnwrap(baseline.latestRenderedBottom, "The newest row stripe must be visible before focus")
    let cellGap = baseline.composerTop - baselineCell.frame.maxY
    let paintedGap = baseline.composerTop - baselineStripe
    XCTAssertEqual(cellGap, 0, accuracy: Metrics.coverageTolerance, "Opening must start at the true bottom")
    XCTAssertEqual(paintedGap, 0, accuracy: Metrics.coverageTolerance)
    for sample in samples {
      let cell = try XCTUnwrap(sample.coverage?.cells.first { $0.item == lastItem },
                               "The newest message was retired during opening at \(sample.timestamp)")
      XCTAssertGreaterThan(cell.opacity, 0)
      XCTAssertGreaterThan(cell.contentOpacity, 0)
      XCTAssertEqual(sample.composerTop - cell.frame.maxY, cellGap, accuracy: Metrics.alignmentTolerance)
      XCTAssertGreaterThanOrEqual(sample.composerTop - cell.frame.maxY, -Metrics.alignmentTolerance)
      let stripe = try XCTUnwrap(sample.latestRenderedBottom,
                                 "The newest message lost its painted stripe during opening at \(sample.timestamp)")
      XCTAssertEqual(sample.composerTop - stripe, paintedGap, accuracy: Metrics.coverageTolerance,
                     "The newest message moved relative to the rendered composer at \(sample.timestamp)")
      XCTAssertLessThanOrEqual(stripe, sample.composerTop + Metrics.coverageTolerance)
    }
  }

  func makeNativeKeyboardGuideMarker(in view: UIView) -> UIView {
    let marker = UIView()
    marker.backgroundColor = .green
    marker.isUserInteractionEnabled = false
    marker.translatesAutoresizingMaskIntoConstraints = false
    view.addSubview(marker)
    NSLayoutConstraint.activate([
      marker.trailingAnchor.constraint(equalTo: view.trailingAnchor),
      marker.bottomAnchor.constraint(equalTo: view.keyboardLayoutGuide.topAnchor),
      marker.widthAnchor.constraint(equalToConstant: Metrics.markerWidth),
      marker.heightAnchor.constraint(equalToConstant: Metrics.markerHeight),
    ])
    return marker
  }

  func findComposer(in view: UIView) -> ChatComposerUITextView? {
    if let composer = view as? ChatComposerUITextView { return composer }
    for child in view.subviews {
      if let composer = findComposer(in: child) { return composer }
    }
    return nil
  }
}
