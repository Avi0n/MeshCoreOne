import Foundation
@testable import MC1
import MC1Services
import SwiftUI
import Testing
import UIKit

@Suite("Chat keyboard transitions", .serialized)
@MainActor
struct ChatKeyboardTransitionTests {
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
            onSend: { _ in true }, onWillSend: {}
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

  @Test
  func `composer follows native keyboard guide throughout show and hide`() async throws {
    try await exerciseKeyboard(simulatingDragDuringHide: false, keyboardCycles: 2, verifiesKeyboardAlignment: true)
  }

  @Test
  func `tall row remains painted when draft restores at focus and keyboard hides`() async throws {
    try await exerciseKeyboard(
      simulatingDragDuringHide: true, hasTallPenultimateRow: true,
      initialComposerText: "First line", restoredComposerText: "First line\nSecond line",
      expandedComposerTexts: [
        "First line\nSecond line\nThird line",
        "First line\nSecond line\nThird line\nFourth line\nFifth line",
      ]
    )
  }

  @Test
  func `held initial anchor keeps rows painted during keyboard opening`() async throws {
    try await exerciseKeyboard(simulatingDragDuringHide: false, holdsInitialAnchor: true)
  }

  @Test
  func `lowest visible history message tracks composer throughout keyboard cycle`() async throws {
    try await exerciseKeyboard(
      simulatingDragDuringHide: false, readingHistory: true,
      initialComposerText: "First line\nSecond line\nThird line"
    )
  }

  @Test
  func `lowest visible message near bottom stays anchored through focus`() async throws {
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
    let scene = try #require(
      readyScene,
      "Timed out waiting for a foreground app scene with a key window"
    )
    let window = try #require(scene.keyWindow, "The app scene must have a key window")
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
      do {
        let directory = try recorder.writeArtifacts()
        let files = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
        for file in files {
          try Attachment.record(Data(contentsOf: file), named: file.lastPathComponent)
        }
      } catch {
        Issue.record(error, "Could not attach keyboard transition diagnostics")
      }
      window.endEditing(true)
      window.rootViewController = originalController
      window.makeKeyAndVisible()
      appState.shutdown()
    }

    let mounted = await waitUntil { self.findComposer(in: window) != nil }
    guard mounted else {
      let artifacts = try recorder.writeArtifacts()
      Issue.record("The production composer did not mount; \(artifacts.path)")
      return
    }
    try await Task.sleep(for: Metrics.settleInterval)
    _ = try #require(recorder.collection)
    var historyAnchor: (item: Int, gap: CGFloat)?
    if readingHistory {
      let collection = try #require(recorder.collection)
      collection.contentOffset.y -= historyDistance ?? CGFloat(Metrics.historyRows) * Metrics.rowHeight
      try await Task.sleep(for: Metrics.settleInterval)
      let baseline = try #require(recorder.captureCurrentCoverage())
      let lowest = try #require(baseline.cells.filter { $0.item != nil && $0.frame.minY < baseline.bottom }
        .max { $0.frame.maxY < $1.frame.maxY })
      historyAnchor = try (#require(lowest.item), baseline.bottom - lowest.frame.maxY)
    }
    func assertHistoryAnchor(_ coverage: Coverage, phase: String) throws {
      guard let historyAnchor else { return }
      let anchor = try #require(coverage.cells.first { $0.item == historyAnchor.item })
      #expect(abs((coverage.bottom - anchor.frame.maxY) - (historyAnchor.gap)) <= Metrics.alignmentTolerance, "Lowest history message moved relative to composer during \(phase)")
    }
    let startsAtBottom = !readingHistory && !holdsInitialAnchor
    recorder.start()
    for _ in 0..<keyboardCycles {
      recorder.phase = "show"
      let cycleStart = recorder.samples.count
      let composer = try #require(findComposer(in: window))
      let restingKeyboardTop = keyboardMarker.convert(keyboardMarker.bounds, to: window).maxY
      let beforeFocus = try #require(recorder.captureBeforeFocus())
      if let restoredComposerText { composerModel.text = restoredComposerText }
      #expect(composer.becomeFirstResponder(), "The production composer refused focus")
      let keyboardAppeared = await waitUntil {
        keyboardMarker.convert(keyboardMarker.bounds, to: window).maxY
          < restingKeyboardTop - Metrics.minimumKeyboardHeight
      }
      guard keyboardAppeared else {
        let artifacts = try recorder.writeArtifacts()
        Issue.record("""
        No docked software keyboard appeared. key=\(window.isKeyWindow), hidden=\(window.isHidden), \
        firstResponder=\(composer.isFirstResponder), scene=\(scene.activationState.rawValue), \
        guide=\(controller.view.keyboardLayoutGuide.layoutFrame), resting=\(restingKeyboardTop), \
        notifiedOverlap=\(recorder.keyboardNotifications.map(\.overlap)); \(artifacts.path)
        """)
        return
      }
      try await Task.sleep(for: Metrics.settleInterval)
      #expect(composer.text == (restoredComposerText ?? initialComposerText))
      if restoredComposerText != nil {
        try assertComposerExpanded(#require(recorder.samples.last), from: beforeFocus)
      }
      let shownKeyboardTop = keyboardMarker.convert(keyboardMarker.bounds, to: window).maxY
      #expect(try abs(#require(recorder.captureComposerBottom()) - shownKeyboardTop) <= Metrics.alignmentTolerance, "The settled composer must remain above the shown keyboard")
      for expandedComposerText in expandedComposerTexts {
        let previous = try #require(recorder.samples.last)
        composerModel.text = expandedComposerText
        try await Task.sleep(for: Metrics.settleInterval)
        #expect(composer.text == expandedComposerText)
        let expanded = try #require(recorder.samples.last)
        assertComposerExpanded(expanded, from: previous)
        #expect(abs((expanded.composerBottom) - shownKeyboardTop) <= Metrics.alignmentTolerance)
      }
      if readingHistory {
        try assertHistoryAnchor(#require(recorder.captureBaseline()), phase: "shown")
      }
      if simulatingDragDuringHide {
        let baseline = try #require(recorder.captureBaseline())
        #expect(try abs(#require(baseline.presentedContentBottom) - (baseline.bottom + Metrics.coverageTolerance)) <= Metrics.coverageTolerance, "The dismissal fixture must start at the true bottom")
        #expect(baseline.uncoveredHeight <= Metrics.coverageTolerance)
        #expect(baseline.longestNonRowRun <= Int(Metrics.coverageTolerance))
        #expect(baseline.bluePixels > 0 && baseline.indigoPixels > 0, "Both row colors must be captured")
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
        try assertHistoryAnchor(#require(recorder.captureCurrentCoverage()), phase: "dismissed")
      }
      #expect(try abs(#require(recorder.captureComposerBottom()) - restingKeyboardTop) <= Metrics.alignmentTolerance, "The settled composer must return above the home indicator")
      let artifacts = try recorder.writeArtifacts()
      #expect(recorder.missingPresentationFrames == 0, "A capture used model endpoints; \(artifacts.path)")
      #expect(keyboardDismissed, "Software keyboard did not dismiss; artifacts: \(artifacts.path)")
      #expect(recorder.missingMarkerFrames == 0, "A rendered frame lost the composer or keyboard marker; \(artifacts.path)")
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
        #expect(transition.count >= Metrics.minimumTransitionSamples, "No usable intermediate \(phase) frames (missing markers: \(recorder.missingMarkerFrames)); \(artifacts.path)")
        if verifiesKeyboardAlignment {
          let maximumGap = transition.map { abs($0.composerKeyboardGap) }.max() ?? .infinity
          #expect(maximumGap <= Metrics.alignmentTolerance, "Composer separated from native keyboard guide by \(maximumGap) points during \(phase); \(artifacts.path)")
        }
        if readingHistory {
          for sample in transition {
            try assertHistoryAnchor(#require(sample.coverage), phase: phase)
          }
        }
      }
      var coveragePhases = holdsInitialAnchor ? ["show", "hide"] : simulatingDragDuringHide ? ["hide"] : []
      if startsAtBottom { coveragePhases.insert("show", at: 0) }
      for phase in coveragePhases {
        let phaseSamples = recorder.samples.dropFirst(cycleStart).filter { $0.phase == phase }
        let coverage = phaseSamples.compactMap(\.coverage)
        #expect(coverage.count == phaseSamples.count, "Missing coverage samples during \(phase); \(artifacts.path)")
        #expect(coverage.count >= Metrics.minimumTransitionSamples, "\(artifacts.path)")
        #expect(coverage.contains { $0.bluePixels > 0 && $0.indigoPixels > 0 }, "Row palette was not observed")
        #expect((coverage.map(\.uncoveredHeight).max() ?? .infinity) <= Metrics.coverageTolerance, "Instantiated cells leave an interval above the composer during \(phase); \(artifacts.path)")
        #expect((coverage.map(\.longestNonRowRun).max() ?? .max) <= Int(Metrics.coverageTolerance), "Painted rows leave a slab above the composer during \(phase); \(artifacts.path)")
      }
    }
  }

  private func assertComposerExpanded(_ current: Sample, from previous: Sample) {
    #expect(current.composerBottom - current.composerTop > previous.composerBottom - previous.composerTop + Metrics.alignmentTolerance, "The multiline draft must expand the rendered composer")
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
