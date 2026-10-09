@testable import MC1
import MC1Services
import SwiftUI
import UIKit
import XCTest

@MainActor
final class ChatKeyboardTransitionTests: XCTestCase {
  private struct Row: Identifiable, Hashable, Sendable {
    let id = UUID()
    let index: Int
  }

  private struct Harness: View {
    let appState: AppState
    let holdsInitialAnchor: Bool
    let hasTallPenultimateRow: Bool
    @Bindable var composerModel: ComposerModel
    let rows = (0..<Metrics.rowCount).map { Row(index: $0) }
    let channel = ChannelDTO(
      id: UUID(), radioID: UUID(), index: 1, name: "Keyboard test",
      secret: Data(repeating: 0x11, count: ProtocolLimits.channelSecretSize),
      isEnabled: true, lastMessageDate: nil, unreadCount: 0
    )
    @State private var isAtBottom = true
    @State private var unreadCount = 0
    @State private var focusRequest = 0
    @State private var scrollToBottomRequest = 0

    init(appState: AppState, holdsInitialAnchor: Bool, hasTallPenultimateRow: Bool, composerModel: ComposerModel) {
      self.appState = appState
      self.holdsInitialAnchor = holdsInitialAnchor
      self.hasTallPenultimateRow = hasTallPenultimateRow
      self.composerModel = composerModel
    }

    var body: some View {
      NavigationStack {
        ChatTiledView(
          items: rows,
          cellContent: { row in
            (row.index.isMultiple(of: 2) ? Color(red: 0, green: 0, blue: 1) : Color(red: 0.4, green: 0, blue: 1))
              .frame(height: hasTallPenultimateRow && row.index == Metrics.rowCount - 2
                ? Metrics.tallRowHeight : Metrics.rowHeight)
              .overlay(alignment: .leading) { Text("\(row.index)").foregroundStyle(.white) }
              .overlay(alignment: .bottom) {
                if row.index == Metrics.rowCount - 1 {
                  Color(red: 0, green: 1, blue: 1).frame(height: Metrics.latestRowStripeHeight)
                }
              }
          },
          contentBackground: .yellow,
          isAtBottom: $isAtBottom,
          unreadCount: $unreadCount,
          scrollToBottomRequest: scrollToBottomRequest,
          initialScrollTargetID: holdsInitialAnchor ? rows[Metrics.historyRows].id : nil
        )
        .chatBottomChrome(canvas: .white) {
          ChatConversationInputBar(
            conversationType: .channel(channel), composingText: $composerModel.text,
            focusRequest: $focusRequest, nodeNameByteCount: 0,
            onSend: { _ in }, onWillSend: {}
          )
          .overlay(alignment: .bottomLeading) {
            Color(red: 1, green: 0, blue: 0)
              .frame(width: Metrics.markerWidth, height: Metrics.markerHeight)
              .allowsHitTesting(false)
          }
          .overlay(alignment: .topLeading) {
            Color(red: 1, green: 0.5, blue: 0)
              .frame(width: Metrics.markerWidth, height: Metrics.markerHeight)
              .allowsHitTesting(false)
          }
          .background(ComposerBoundsMarker())
        }
        .background(Color(uiColor: .magenta))
        .navigationTitle("Keyboard coverage fixture")
        .navigationBarTitleDisplayMode(.inline)
      }
      .environment(\.appState, appState)
      .environment(\.horizontalSizeClass, .compact)
      // A standalone UIHostingController does not receive a SwiftUI Scene's active phase.
      .environment(\.scenePhase, .active)
    }
  }

  func testComposerFollowsNativeKeyboardGuideThroughoutShowAndHide() async throws {
    try await exerciseKeyboard(simulatingDragDuringHide: false, keyboardCycles: 2, verifiesKeyboardAlignment: true)
  }

  func testTallRowRemainsPaintedWhenDraftRestoresAtFocusAndKeyboardHides() async throws {
    try await exerciseKeyboard(
      simulatingDragDuringHide: true, hasTallPenultimateRow: true,
      initialComposerText: "First line", restoredComposerText: "First line\nSecond line",
      expandedComposerTexts: [
        "First line\nSecond line\nThird line",
        "First line\nSecond line\nThird line\nFourth line\nFifth line",
      ]
    )
  }

  func testHeldInitialAnchorKeepsRowsPaintedDuringKeyboardOpening() async throws {
    try await exerciseKeyboard(simulatingDragDuringHide: false, holdsInitialAnchor: true)
  }

  func testLowestVisibleHistoryMessageTracksComposerThroughoutKeyboardCycle() async throws {
    try await exerciseKeyboard(
      simulatingDragDuringHide: false, readingHistory: true,
      initialComposerText: "First line\nSecond line\nThird line"
    )
  }

  func testLowestVisibleMessageNearBottomStaysAnchoredThroughFocus() async throws {
    try await exerciseKeyboard(
      simulatingDragDuringHide: false, readingHistory: true, historyDistance: Metrics.nearBottomHistoryDistance
    )
  }

  private func exerciseKeyboard(
    simulatingDragDuringHide: Bool, readingHistory: Bool = false,
    keyboardCycles: Int = 1, verifiesKeyboardAlignment: Bool = false,
    historyDistance: CGFloat? = nil, holdsInitialAnchor: Bool = false, hasTallPenultimateRow: Bool = false,
    initialComposerText: String = "", restoredComposerText: String? = nil, expandedComposerTexts: [String] = []
  ) async throws {
    var readyScene: UIWindowScene?
    _ = await waitUntil(timeout: Metrics.sceneStartupTimeout) {
      readyScene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        .first { $0.activationState == .foregroundActive && $0.keyWindow != nil }
      return readyScene != nil
    }
    let scene = try XCTUnwrap(
      readyScene,
      "Timed out waiting for a foreground app scene with a key window"
    )
    let window = try XCTUnwrap(scene.keyWindow, "The app scene must have a key window")
    let originalController = window.rootViewController
    let appState = AppState()
    let composerModel = ComposerModel()
    composerModel.text = initialComposerText
    let controller = UIViewController()
    controller.overrideUserInterfaceStyle = .light
    let hostingController = UIHostingController(rootView: Harness(
      appState: appState,
      holdsInitialAnchor: holdsInitialAnchor, hasTallPenultimateRow: hasTallPenultimateRow,
      composerModel: composerModel
    ))
    controller.addChild(hostingController)
    controller.view.addSubview(hostingController.view)
    hostingController.view.translatesAutoresizingMaskIntoConstraints = false
    NSLayoutConstraint.activate([
      hostingController.view.leadingAnchor.constraint(equalTo: controller.view.leadingAnchor),
      hostingController.view.trailingAnchor.constraint(equalTo: controller.view.trailingAnchor),
      hostingController.view.topAnchor.constraint(equalTo: controller.view.topAnchor),
      hostingController.view.bottomAnchor.constraint(equalTo: controller.view.bottomAnchor),
    ])
    hostingController.didMove(toParent: controller)
    window.rootViewController = controller
    window.makeKeyAndVisible()
    let keyboardMarker = makeNativeKeyboardGuideMarker(in: controller.view)
    let recorder = Recorder(window: window)
    defer {
      recorder.stop()
      if let directory = try? recorder.writeArtifacts() {
        let attachment = XCTAttachment(contentsOfFile: directory.appending(path: "samples.json"))
        attachment.lifetime = .keepAlways
        add(attachment)
      }
      window.endEditing(true)
      window.rootViewController = originalController
      window.makeKeyAndVisible()
      appState.shutdown()
    }

    let mounted = await waitUntil { self.findComposer(in: window) != nil }
    guard mounted else {
      let artifacts = try recorder.writeArtifacts()
      XCTFail("The production composer did not mount; \(artifacts.path)")
      return
    }
    try await Task.sleep(for: Metrics.settleInterval)
    _ = try XCTUnwrap(recorder.collection)
    var historyAnchor: (item: Int, gap: CGFloat)?
    if readingHistory {
      let collection = try XCTUnwrap(recorder.collection)
      collection.contentOffset.y -= historyDistance ?? CGFloat(Metrics.historyRows) * Metrics.rowHeight
      try await Task.sleep(for: Metrics.settleInterval)
      let baseline = try XCTUnwrap(recorder.captureCurrentCoverage())
      let lowest = try XCTUnwrap(baseline.cells.filter { $0.item != nil && $0.frame.minY < baseline.bottom }
        .max { $0.frame.maxY < $1.frame.maxY })
      historyAnchor = try (XCTUnwrap(lowest.item), baseline.bottom - lowest.frame.maxY)
    }
    func assertHistoryAnchor(_ coverage: Coverage, phase: String) throws {
      guard let historyAnchor else { return }
      let anchor = try XCTUnwrap(coverage.cells.first { $0.item == historyAnchor.item })
      XCTAssertEqual(
        coverage.bottom - anchor.frame.maxY, historyAnchor.gap, accuracy: Metrics.alignmentTolerance,
        "Lowest history message moved relative to composer during \(phase)"
      )
    }
    let startsAtBottom = !readingHistory && !holdsInitialAnchor
    recorder.start()
    for _ in 0..<keyboardCycles {
      recorder.phase = "show"
      let cycleStart = recorder.samples.count
      let composer = try XCTUnwrap(findComposer(in: window))
      let restingKeyboardTop = keyboardMarker.convert(keyboardMarker.bounds, to: window).maxY
      let beforeFocus = try XCTUnwrap(recorder.captureBeforeFocus())
      if let restoredComposerText { composerModel.text = restoredComposerText }
      XCTAssertTrue(composer.becomeFirstResponder(), "The production composer refused focus")
      let keyboardAppeared = await waitUntil {
        keyboardMarker.convert(keyboardMarker.bounds, to: window).maxY
          < restingKeyboardTop - Metrics.minimumKeyboardHeight
      }
      guard keyboardAppeared else {
        let artifacts = try recorder.writeArtifacts()
        XCTFail("""
        No docked software keyboard appeared. key=\(window.isKeyWindow), hidden=\(window.isHidden), \
        firstResponder=\(composer.isFirstResponder), scene=\(scene.activationState.rawValue), \
        guide=\(controller.view.keyboardLayoutGuide.layoutFrame), resting=\(restingKeyboardTop), \
        notifiedOverlap=\(recorder.keyboardNotifications.map(\.overlap)); \(artifacts.path)
        """)
        return
      }
      try await Task.sleep(for: Metrics.settleInterval)
      XCTAssertEqual(composer.text, restoredComposerText ?? initialComposerText)
      if restoredComposerText != nil {
        try assertComposerExpanded(XCTUnwrap(recorder.samples.last), from: beforeFocus)
      }
      let shownKeyboardTop = keyboardMarker.convert(keyboardMarker.bounds, to: window).maxY
      XCTAssertEqual(
        try XCTUnwrap(recorder.captureComposerBottom()), shownKeyboardTop, accuracy: Metrics.alignmentTolerance,
        "The settled composer must remain above the shown keyboard"
      )
      for expandedComposerText in expandedComposerTexts {
        let previous = try XCTUnwrap(recorder.samples.last)
        composerModel.text = expandedComposerText
        try await Task.sleep(for: Metrics.settleInterval)
        XCTAssertEqual(composer.text, expandedComposerText)
        let expanded = try XCTUnwrap(recorder.samples.last)
        assertComposerExpanded(expanded, from: previous)
        XCTAssertEqual(expanded.composerBottom, shownKeyboardTop, accuracy: Metrics.alignmentTolerance)
      }
      if readingHistory {
        try assertHistoryAnchor(XCTUnwrap(recorder.captureBaseline()), phase: "shown")
      }
      if simulatingDragDuringHide {
        let baseline = try XCTUnwrap(recorder.captureBaseline())
        XCTAssertEqual(
          try XCTUnwrap(baseline.presentedContentBottom), baseline.bottom + Metrics.coverageTolerance,
          accuracy: Metrics.coverageTolerance, "The dismissal fixture must start at the true bottom"
        )
        XCTAssertLessThanOrEqual(baseline.uncoveredHeight, Metrics.coverageTolerance)
        XCTAssertLessThanOrEqual(baseline.longestNonRowRun, Int(Metrics.coverageTolerance))
        XCTAssertTrue(baseline.bluePixels > 0 && baseline.indigoPixels > 0, "Both row colors must be captured")
      }
      recorder.phase = "hide"
      if simulatingDragDuringHide { recorder.beginSimulatedDrag() }
      ChatKeyboardLift.resignFirstResponder()
      let keyboardDismissed = await waitUntil {
        keyboardMarker.convert(keyboardMarker.bounds, to: window).maxY
          >= restingKeyboardTop - Metrics.alignmentTolerance
      }
      try await Task.sleep(for: Metrics.settleInterval)
      if readingHistory {
        try assertHistoryAnchor(XCTUnwrap(recorder.captureCurrentCoverage()), phase: "dismissed")
      }
      XCTAssertEqual(
        try XCTUnwrap(recorder.captureComposerBottom()), restingKeyboardTop,
        accuracy: Metrics.alignmentTolerance, "The settled composer must return above the home indicator"
      )
      let artifacts = try recorder.writeArtifacts()
      XCTAssertEqual(recorder.missingPresentationFrames, 0, "A capture used model endpoints; \(artifacts.path)")
      XCTAssertTrue(keyboardDismissed, "Software keyboard did not dismiss; artifacts: \(artifacts.path)")
      XCTAssertEqual(recorder.missingMarkerFrames, 0, "A rendered frame lost the composer or keyboard marker; \(artifacts.path)")
      if startsAtBottom {
        try assertLatestRowDuringOpening(
          recorder.samples.dropFirst(cycleStart).filter { $0.phase == "show" }, baseline: beforeFocus
        )
      }

      for phase in ["show", "hide"] {
        let transition = recorder.samples.dropFirst(cycleStart).filter {
          $0.phase == phase && $0.keyboardTop > shownKeyboardTop + Metrics.alignmentTolerance
            && $0.keyboardTop < restingKeyboardTop - Metrics.alignmentTolerance
        }
        XCTAssertGreaterThanOrEqual(
          transition.count, Metrics.minimumTransitionSamples,
          "No usable intermediate \(phase) frames (missing markers: \(recorder.missingMarkerFrames)); \(artifacts.path)"
        )
        if verifiesKeyboardAlignment {
          let maximumGap = transition.map { abs($0.composerKeyboardGap) }.max() ?? .infinity
          XCTAssertLessThanOrEqual(
            maximumGap, Metrics.alignmentTolerance,
            "Composer separated from native keyboard guide by \(maximumGap) points during \(phase); \(artifacts.path)"
          )
        }
        if readingHistory {
          for sample in transition {
            try assertHistoryAnchor(XCTUnwrap(sample.coverage), phase: phase)
          }
        }
      }
      var coveragePhases = holdsInitialAnchor ? ["show", "hide"] : simulatingDragDuringHide ? ["hide"] : []
      if startsAtBottom { coveragePhases.insert("show", at: 0) }
      for phase in coveragePhases {
        let phaseSamples = recorder.samples.dropFirst(cycleStart).filter { $0.phase == phase }
        let coverage = phaseSamples.compactMap(\.coverage)
        XCTAssertEqual(coverage.count, phaseSamples.count, "Missing coverage samples during \(phase); \(artifacts.path)")
        XCTAssertGreaterThanOrEqual(coverage.count, Metrics.minimumTransitionSamples, artifacts.path)
        XCTAssertTrue(coverage.contains { $0.bluePixels > 0 && $0.indigoPixels > 0 }, "Row palette was not observed")
        XCTAssertLessThanOrEqual(
          coverage.map(\.uncoveredHeight).max() ?? .infinity, Metrics.coverageTolerance,
          "Instantiated cells leave an interval above the composer during \(phase); \(artifacts.path)"
        )
        XCTAssertLessThanOrEqual(
          coverage.map(\.longestNonRowRun).max() ?? .max, Int(Metrics.coverageTolerance),
          "Painted rows leave a slab above the composer during \(phase); \(artifacts.path)"
        )
      }
    }
    recorder.stop()
  }

  private func assertComposerExpanded(_ current: Sample, from previous: Sample) {
    XCTAssertGreaterThan(
      current.composerBottom - current.composerTop,
      previous.composerBottom - previous.composerTop + Metrics.alignmentTolerance,
      "The multiline draft must expand the rendered composer"
    )
  }

  private func waitUntil(timeout: TimeInterval = Metrics.timeout, _ condition: () -> Bool) async -> Bool {
    let deadline = Date(timeIntervalSinceNow: timeout)
    while Date() < deadline {
      if condition() { return true }
      try? await Task.sleep(for: Metrics.pollInterval)
    }
    return condition()
  }
}
