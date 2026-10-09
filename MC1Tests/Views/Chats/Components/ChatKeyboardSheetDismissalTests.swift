@testable import MC1
import MC1Services
import SwiftUI
import UIKit
import XCTest

@MainActor
final class ChatKeyboardSheetDismissalTests: XCTestCase {
  private enum Metrics {
    static let rowCount = 80
    static let rowHeight: CGFloat = 44
    static let minimumKeyboardHeight: CGFloat = 100
    static let tolerance: CGFloat = 8
    static let timeout: TimeInterval = 5
    static let sceneTimeout: TimeInterval = 15
    static let pollInterval: Duration = .milliseconds(16)
    static let settleInterval: Duration = .milliseconds(500)
    static let composerIdentifier = "sheet-dismissal-composer-bounds"
  }

  @Observable
  @MainActor
  final class Presentation {
    var message: MessageDTO?
  }

  private struct Row: Identifiable, Hashable, Sendable {
    let id = UUID()
    let index: Int
  }

  private struct ComposerMarker: UIViewRepresentable {
    func makeUIView(context: Context) -> UIView {
      let view = UIView()
      view.accessibilityIdentifier = Metrics.composerIdentifier
      view.isUserInteractionEnabled = false
      return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {}
  }

  private final class BottomAreaPan: UIPanGestureRecognizer {
    let sourceWindow: UIWindow
    let point: CGPoint
    var simulatedState: UIGestureRecognizer.State = .changed

    init(window: UIWindow, point: CGPoint) {
      sourceWindow = window
      self.point = point
      super.init(target: nil, action: nil)
    }

    override var state: UIGestureRecognizer.State {
      get { simulatedState }
      set { simulatedState = newValue }
    }

    override func location(in view: UIView?) -> CGPoint {
      sourceWindow.convert(point, to: view)
    }
  }

  private struct Harness: View {
    let appState: AppState
    @Bindable var presentation: Presentation
    var usesLargeSheet = false
    let rows = (0..<Metrics.rowCount).map { Row(index: $0) }
    @State private var text = ""
    @State private var isAtBottom = true
    @State private var unreadCount = 0
    @State private var path = [true]

    var body: some View {
      NavigationStack(path: $path) {
        Color.clear.navigationDestination(for: Bool.self) { _ in
          conversation
        }
      }
      .environment(\.appState, appState)
      .environment(\.horizontalSizeClass, .compact)
      .environment(\.scenePhase, .active)
    }

    private var conversation: some View {
      ChatTiledView(
        items: rows,
        cellContent: { row in
          Color.blue.frame(height: Metrics.rowHeight)
            .overlay { Text("\(row.index)") }
            .onLongPressGesture {
              ChatKeyboardLift.resignFirstResponder()
              presentation.message = MessageBubbleTestData.outgoingDM()
            }
        },
        isAtBottom: $isAtBottom, unreadCount: $unreadCount
      )
      .chatBottomChrome(canvas: Color(.systemBackground)) {
        ChatInputBar(
          text: $text, focusRequest: 0, placeholder: "Keyboard test",
          maxBytes: ProtocolLimits.maxDirectMessageLength, isEncrypted: false,
          onSend: { _ in }
        )
        .background(ComposerMarker())
      }
      .navigationTitle("Sheet dismissal fixture")
      .navigationBarTitleDisplayMode(.inline)
      .sheet(item: $presentation.message) { message in
        MessageActionsSheet(
          message: message,
          senderResolution: NodeNameResolution(displayName: "Me", matchKind: .exact),
          recentEmojis: [], onAction: { _ in }
        )
        .environment(\.horizontalSizeClass, .compact)
        .presentationDetents(usesLargeSheet ? [.large] : [.medium, .large])
      }
    }
  }

  func testKeyboardStaysDismissedAfterMessageActionsSheet() async throws {
    try await exerciseSheet(interruptingDismissal: false)
  }

  func testKeyboardStaysDismissedAfterLargeMessageActionsInterruptsDismissal() async throws {
    try await exerciseSheet(interruptingDismissal: true, usesLargeSheet: true)
  }

  private func exerciseSheet(interruptingDismissal: Bool, usesLargeSheet: Bool = false) async throws {
    var readyWindow: UIWindow?
    let hasScene = await waitUntil(timeout: Metrics.sceneTimeout) {
      readyWindow = ChatKeyboardLift.keyWindow()
      return readyWindow != nil
    }
    XCTAssertTrue(hasScene)
    let window = try XCTUnwrap(readyWindow)
    let originalController = window.rootViewController
    let appState = AppState()
    let presentation = Presentation()
    let controller = UIHostingController(rootView: Harness(
      appState: appState, presentation: presentation, usesLargeSheet: usesLargeSheet
    ))
    window.rootViewController = controller
    window.makeKeyAndVisible()
    defer {
      window.endEditing(true)
      window.rootViewController = originalController
      window.makeKeyAndVisible()
      appState.shutdown()
    }
    let mounted = await waitUntil { self.findComposer(in: window) != nil }
    XCTAssertTrue(mounted)
    let composer = try XCTUnwrap(findComposer(in: window))
    try await Task.sleep(for: Metrics.settleInterval)
    let restingTop = keyboardTop(in: controller, window: window)
    XCTAssertTrue(composer.becomeFirstResponder())
    let shown = await waitUntil {
      self.keyboardTop(in: controller, window: window) < restingTop - Metrics.minimumKeyboardHeight
    }
    XCTAssertTrue(shown, "The software keyboard must appear before presenting the sheet")
    try await Task.sleep(for: Metrics.settleInterval)
    try assertTimelineReceivesTouches(in: window, phase: "before sheet")
    if interruptingDismissal {
      try dragToDismiss(in: window, keyboardTop: keyboardTop(in: controller, window: window))
    }
    ChatKeyboardLift.resignFirstResponder()
    presentation.message = MessageBubbleTestData.outgoingDM()
    let presented = await waitUntil { self.presentedController(in: controller) != nil }
    XCTAssertTrue(presented, "The production message actions sheet must present")
    try await Task.sleep(for: Metrics.settleInterval)
    presentation.message = nil
    let dismissed = await waitUntil { self.presentedController(in: controller) == nil }
    XCTAssertTrue(dismissed)
    try await Task.sleep(for: Metrics.settleInterval)

    let afterSheetTop = keyboardTop(in: controller, window: window)
    let afterSheetResponder = composer.isFirstResponder
    XCTAssertFalse(afterSheetResponder, "Closing message actions must not refocus the composer")
    XCTAssertGreaterThanOrEqual(afterSheetTop, restingTop - Metrics.tolerance,
                                "The keyboard must stay dismissed after closing message actions")
    try assertComposerPosition(in: window, keyboardTop: afterSheetTop, restingTop: restingTop)
    try assertTimelineReceivesTouches(in: window, phase: "after sheet")
    XCTAssertTrue(composer.becomeFirstResponder())
    let reopened = await waitUntil {
      self.keyboardTop(in: controller, window: window) < restingTop - Metrics.minimumKeyboardHeight
    }
    XCTAssertTrue(reopened)
    try await Task.sleep(for: Metrics.settleInterval)
    try dragToDismiss(in: window, keyboardTop: keyboardTop(in: controller, window: window))
    let keyboardDismissed = await waitUntil {
      self.keyboardTop(in: controller, window: window) >= restingTop - Metrics.tolerance
    }
    XCTAssertTrue(keyboardDismissed, """
    Keyboard stuck after message actions: firstResponder=\(afterSheetResponder), \
    afterSheetTop=\(afterSheetTop), currentTop=\(keyboardTop(in: controller, window: window)), \
    restingTop=\(restingTop), responderNow=\(composer.isFirstResponder)
    """)
    try await Task.sleep(for: Metrics.settleInterval)
    try assertComposerPosition(
      in: window, keyboardTop: keyboardTop(in: controller, window: window), restingTop: restingTop
    )
  }

  private func keyboardTop(in controller: UIViewController, window: UIWindow) -> CGFloat {
    controller.view.convert(controller.view.keyboardLayoutGuide.layoutFrame, to: window).minY
  }

  private func assertTimelineReceivesTouches(in window: UIWindow, phase: String) throws {
    func findCollection(in view: UIView) -> UICollectionView? {
      if let collection = view as? UICollectionView { return collection }
      return view.subviews.compactMap { findCollection(in: $0) }.first
    }
    let collection = try XCTUnwrap(findCollection(in: window))
    let composer = try XCTUnwrap(findComposer(in: window))
    let composerTop = composer.convert(composer.bounds, to: window).minY
    let viewport = collection.convert(collection.bounds, to: window).intersection(window.bounds)
    let point = CGPoint(
      x: viewport.midX,
      y: (max(viewport.minY, window.safeAreaInsets.top) + min(viewport.maxY, composerTop)) / 2
    )
    _ = try XCTUnwrap(collection.visibleCells.first { $0.convert($0.bounds, to: window).contains(point) })
    let hit = try XCTUnwrap(window.hitTest(point, with: nil), """
    No message hit during \(phase): point=\(point), window=\(window.bounds), \
    enabled=\(window.isUserInteractionEnabled), collection=\(collection.convert(collection.bounds, to: window)), \
    viewport=\(viewport), composerTop=\(composerTop), enabled=\(collection.isUserInteractionEnabled)
    """)
    XCTAssertTrue(hit.isDescendant(of: collection), "A sheet overlay must not intercept message touches after dismissal")
    XCTAssertTrue(collection.panGestureRecognizer.isEnabled)
    XCTAssertTrue(collection.panGestureRecognizer.view === collection)
    XCTAssertTrue(collection.gestureRecognizers?.contains {
      $0 is UIPanGestureRecognizer && $0 !== collection.panGestureRecognizer && $0.isEnabled && $0.view === collection
    } == true, "An installed timeline pan must remain attached and enabled")
  }

  private func assertComposerPosition(in window: UIWindow, keyboardTop: CGFloat, restingTop: CGFloat) throws {
    func findMarker(in view: UIView) -> UIView? {
      if view.accessibilityIdentifier == Metrics.composerIdentifier { return view }
      return view.subviews.compactMap { findMarker(in: $0) }.first
    }
    let marker = try XCTUnwrap(findMarker(in: window))
    let bottom = marker.convert(marker.bounds, to: window).maxY
    XCTAssertEqual(
      bottom, min(keyboardTop, restingTop), accuracy: Metrics.tolerance,
      "The composer must follow the actual keyboard rather than retaining a raised position"
    )
  }

  private func dragToDismiss(in window: UIWindow, keyboardTop: CGFloat) throws {
    let action = NSSelectorFromString("handleBottomSafeAreaPanGesture:")
    func findTarget(in view: UIView) -> UIView? {
      if view.responds(to: action) { return view }
      return view.subviews.compactMap { findTarget(in: $0) }.first
    }
    let target = try XCTUnwrap(findTarget(in: window), "The production bottom-area drag target must exist")
    let pan = BottomAreaPan(
      window: window, point: CGPoint(x: window.bounds.midX, y: keyboardTop - Metrics.tolerance)
    )
    target.perform(action, with: pan)
    pan.simulatedState = .ended
    target.perform(action, with: pan)
  }

  private func presentedController(in controller: UIViewController) -> UIViewController? {
    if let presented = controller.presentedViewController { return presented }
    return controller.children.compactMap { presentedController(in: $0) }.first
  }

  private func findComposer(in view: UIView) -> ChatComposerUITextView? {
    if let composer = view as? ChatComposerUITextView { return composer }
    return view.subviews.compactMap { findComposer(in: $0) }.first
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
