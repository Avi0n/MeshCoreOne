import Foundation
@testable import MC1
import MC1Services
import SwiftUI
import Testing
import UIKit

@Suite("Chat keyboard sheet dismissal", .serialized)
@MainActor
struct ChatKeyboardSheetDismissalTests {
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

  @Test
  func `keyboard stays dismissed after message actions sheet`() async throws {
    try await exerciseSheet(interruptingDismissal: false)
  }

  @Test
  func `channel info sheet dismissal keeps chat above visible keyboard`() async throws {
    let scene = try await foregroundScene()
    let window = try #require(scene.keyWindow)
    let originalController = window.rootViewController
    let appState = AppState()
    let presentation = InfoPresentation()
    let controller = UIHostingController(rootView: InfoSheetHarness(appState: appState, presentation: presentation))
    window.rootViewController = controller
    window.makeKeyAndVisible()
    let frames = KeyboardNotificationTop(window: window)
    frames.start()
    defer {
      window.endEditing(true)
      window.rootViewController = originalController
      window.makeKeyAndVisible()
      appState.shutdown()
    }

    let mounted = await waitUntil { self.findComposer(in: window) != nil }
    #expect(mounted, "The production composer did not mount")
    let composer = try #require(findComposer(in: window))
    try await Task.sleep(for: Metrics.settleInterval)
    let restingTop = window.bounds.maxY
    disconnectHardwareKeyboard()
    #expect(composer.becomeFirstResponder())
    let shown = await waitUntil {
      let notified = frames.top.map { $0 < restingTop - Metrics.minimumKeyboardHeight } ?? false
      let guide = self.keyboardTop(in: controller, window: window) < restingTop - Metrics.minimumKeyboardHeight
      let visual = self.visualKeyboardTop(in: scene, window: window)
        .map { $0 < restingTop - Metrics.minimumKeyboardHeight } ?? false
      return notified || guide || visual
    }
    #expect(shown, """
    The software keyboard must appear before the info sheet. \
    notification=\(String(describing: frames.top)) \
    guide=\(keyboardTop(in: controller, window: window)) \
    visual=\(String(describing: visualKeyboardTop(in: scene, window: window))) \
    firstResponder=\(composer.isFirstResponder) bounds=\(window.bounds)
    """)
    try await Task.sleep(for: Metrics.settleInterval)

    presentation.showInfo = true
    let presented = await waitUntil { self.presentedController(in: controller) != nil }
    #expect(presented, "The info sheet must present while the composer is first responder")
    try await Task.sleep(for: Metrics.settleInterval)
    presentation.showInfo = false
    let dismissed = await waitUntil { self.presentedController(in: controller) == nil }
    #expect(dismissed, "The info sheet must dismiss")

    let settled = await waitUntil(timeout: Metrics.timeout) {
      self.chatCoversKeyboard(composer: composer, scene: scene, window: window, notificationTop: frames.top) == nil
    }
    #expect(settled, Comment(rawValue: chatCoversKeyboard(
      composer: composer, scene: scene, window: window, notificationTop: frames.top
    ) ?? "The chat stayed under the keyboard after the info sheet dismissed"))
  }

  @Test
  func `keyboard stays dismissed after large message actions interrupts dismissal`() async throws {
    try await exerciseSheet(interruptingDismissal: true, usesLargeSheet: true)
  }

  private func exerciseSheet(interruptingDismissal: Bool, usesLargeSheet: Bool = false) async throws {
    var readyWindow: UIWindow?
    let hasScene = await waitUntil(timeout: Metrics.sceneTimeout) {
      readyWindow = ChatKeyboardLift.keyWindow()
      return readyWindow != nil
    }
    #expect(hasScene)
    let window = try #require(readyWindow)
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
    #expect(mounted)
    let composer = try #require(findComposer(in: window))
    try await Task.sleep(for: Metrics.settleInterval)
    let restingTop = keyboardTop(in: controller, window: window)
    #expect(composer.becomeFirstResponder())
    let shown = await waitUntil {
      self.keyboardTop(in: controller, window: window) < restingTop - Metrics.minimumKeyboardHeight
    }
    #expect(shown, "The software keyboard must appear before presenting the sheet")
    try await Task.sleep(for: Metrics.settleInterval)
    try assertTimelineReceivesTouches(in: window, phase: "before sheet")
    if interruptingDismissal {
      try dragToDismiss(in: window, keyboardTop: keyboardTop(in: controller, window: window))
    }
    ChatKeyboardLift.resignFirstResponder()
    presentation.message = MessageBubbleTestData.outgoingDM()
    let presented = await waitUntil { self.presentedController(in: controller) != nil }
    #expect(presented, "The production message actions sheet must present")
    try await Task.sleep(for: Metrics.settleInterval)
    presentation.message = nil
    let dismissed = await waitUntil { self.presentedController(in: controller) == nil }
    #expect(dismissed)
    try await Task.sleep(for: Metrics.settleInterval)

    let afterSheetTop = keyboardTop(in: controller, window: window)
    let afterSheetResponder = composer.isFirstResponder
    #expect(!afterSheetResponder, "Closing message actions must not refocus the composer")
    #expect(afterSheetTop >= restingTop - Metrics.tolerance, "The keyboard must stay dismissed after closing message actions")
    try assertComposerPosition(in: window, keyboardTop: afterSheetTop, restingTop: restingTop)
    try assertTimelineReceivesTouches(in: window, phase: "after sheet")
    #expect(composer.becomeFirstResponder())
    let reopened = await waitUntil {
      self.keyboardTop(in: controller, window: window) < restingTop - Metrics.minimumKeyboardHeight
    }
    #expect(reopened)
    try await Task.sleep(for: Metrics.settleInterval)
    try dragToDismiss(in: window, keyboardTop: keyboardTop(in: controller, window: window))
    let keyboardDismissed = await waitUntil {
      self.keyboardTop(in: controller, window: window) >= restingTop - Metrics.tolerance
    }
    #expect(keyboardDismissed, """
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
    let collection = try #require(findCollection(in: window))
    let composer = try #require(findComposer(in: window))
    let composerTop = composer.convert(composer.bounds, to: window).minY
    let viewport = collection.convert(collection.bounds, to: window).intersection(window.bounds)
    let point = CGPoint(
      x: viewport.midX,
      y: (max(viewport.minY, window.safeAreaInsets.top) + min(viewport.maxY, composerTop)) / 2
    )
    _ = try #require(collection.visibleCells.first { $0.convert($0.bounds, to: window).contains(point) })
    let hit = try #require(window.hitTest(point, with: nil), """
    No message hit during \(phase): point=\(point), window=\(window.bounds), \
    enabled=\(window.isUserInteractionEnabled), collection=\(collection.convert(collection.bounds, to: window)), \
    viewport=\(viewport), composerTop=\(composerTop), enabled=\(collection.isUserInteractionEnabled)
    """)
    #expect(hit.isDescendant(of: collection), "A sheet overlay must not intercept message touches after dismissal")
    #expect(collection.panGestureRecognizer.isEnabled)
    #expect(collection.gestureRecognizers?.contains {
      $0 is UIPanGestureRecognizer && $0 !== collection.panGestureRecognizer && $0.isEnabled && $0.view === collection
    } == true, "An installed timeline pan must remain attached and enabled")
  }

  private func assertComposerPosition(in window: UIWindow, keyboardTop: CGFloat, restingTop: CGFloat) throws {
    func findMarker(in view: UIView) -> UIView? {
      if view.accessibilityIdentifier == Metrics.composerIdentifier { return view }
      return view.subviews.compactMap { findMarker(in: $0) }.first
    }
    let marker = try #require(findMarker(in: window))
    let bottom = marker.convert(marker.bounds, to: window).maxY
    #expect(abs(bottom - min(keyboardTop, restingTop)) <= Metrics.tolerance, "The composer must follow the actual keyboard rather than retaining a raised position")
  }

  private func dragToDismiss(in window: UIWindow, keyboardTop: CGFloat) throws {
    let action = NSSelectorFromString("handleBottomSafeAreaPanGesture:")
    func findTarget(in view: UIView) -> UIView? {
      if view.responds(to: action) { return view }
      return view.subviews.compactMap { findTarget(in: $0) }.first
    }
    let target = try #require(findTarget(in: window), "The production bottom-area drag target must exist")
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

  /// Nil when the composer and the latest row sit above the visible keyboard.
  private func chatCoversKeyboard(
    composer: UIView, scene: UIWindowScene, window: UIWindow, notificationTop: CGFloat?
  ) -> String? {
    let visualTop = visualKeyboardTop(in: scene, window: window)
    let keyboardTop = visualTop ?? notificationTop ?? window.bounds.maxY
    guard keyboardTop < window.bounds.maxY - Metrics.minimumKeyboardHeight else { return nil }
    let composerFrame = composer.convert(composer.bounds, to: window)
    let guideTop = window.rootViewController?.view.keyboardLayoutGuide.layoutFrame.minY
    if composerFrame.maxY > keyboardTop + Metrics.tolerance {
      return """
      Composer maxY \(composerFrame.maxY) is under keyboard top \(keyboardTop) \
      (visual=\(String(describing: visualTop)), notification=\(String(describing: notificationTop)), \
      guide=\(String(describing: guideTop)), firstResponder=\(composer.isFirstResponder))
      """
    }
    if let collection = findCollection(in: window),
       let cell = collection.visibleCells.max(by: { $0.frame.maxY < $1.frame.maxY }) {
      let rowBottom = cell.convert(cell.bounds, to: window).maxY
      if rowBottom > keyboardTop + Metrics.tolerance {
        return "Row maxY \(rowBottom) is under keyboard top \(keyboardTop)"
      }
    }
    return nil
  }

  private func visualKeyboardTop(in scene: UIWindowScene, window: UIWindow) -> CGFloat? {
    var top: CGFloat?
    for candidate in scene.windows {
      visit(candidate) { view in
        let name = String(describing: type(of: view))
        guard name.contains("InputSetHost") || name.contains("UIKeyboard") else { return }
        let frame = window.convert(view.bounds, from: view)
        guard frame.height > Metrics.minimumKeyboardHeight,
              frame.maxY >= window.bounds.maxY - Metrics.tolerance,
              frame.minY < window.bounds.maxY - Metrics.minimumKeyboardHeight else { return }
        top = min(top ?? frame.minY, frame.minY)
      }
    }
    return top
  }

  private func visit(_ view: UIView, _ body: (UIView) -> Void) {
    body(view)
    for child in view.subviews {
      visit(child, body)
    }
  }

  private func findCollection(in view: UIView) -> UICollectionView? {
    if let collection = view as? UICollectionView { return collection }
    return view.subviews.compactMap { findCollection(in: $0) }.first
  }

  private func disconnectHardwareKeyboard() {
    let selector = NSSelectorFromString("setHardwareLayout:")
    for mode in UITextInputMode.activeInputModes where mode.responds(to: selector) {
      mode.perform(selector, with: nil)
    }
  }

  private func foregroundScene() async throws -> UIWindowScene {
    var readyScene: UIWindowScene?
    _ = await waitUntil(timeout: Metrics.sceneTimeout) {
      readyScene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
        .first { $0.activationState == .foregroundActive && $0.keyWindow != nil }
      return readyScene != nil
    }
    return try #require(readyScene)
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

@Observable
@MainActor
private final class InfoPresentation {
  var showInfo = false
}

private struct InfoSheetHarness: View {
  let appState: AppState
  @Bindable var presentation: InfoPresentation
  private let rows = (0..<ChatKeyboardSheetDismissalTests.RowCount.rowCount).map {
    ChatKeyboardSheetDismissalTests.InfoRow(index: $0)
  }

  @State private var text = ""
  @State private var isAtBottom = true
  @State private var unreadCount = 0
  @State private var columnVisibility = NavigationSplitViewVisibility.detailOnly
  @State private var preferredColumn = NavigationSplitViewColumn.detail
  @State private var selectedTab = 0

  var body: some View {
    TabView(selection: $selectedTab) {
      Tab("Chats", systemImage: "message.fill", value: 0) {
        NavigationSplitView(
          columnVisibility: $columnVisibility,
          preferredCompactColumn: $preferredColumn
        ) {
          Color.clear
        } detail: {
          NavigationStack {
            conversation
          }
        }
        .navigationSplitViewStyle(.balanced)
      }
    }
    .sectionSplitChrome(tabBarVisibility: .hidden)
    .environment(\.appState, appState)
    .environment(\.horizontalSizeClass, .compact)
    .environment(\.scenePhase, .active)
  }

  private var conversation: some View {
    ChatTiledView(
      items: rows,
      cellContent: { row in
        Color.blue.frame(height: ChatKeyboardSheetDismissalTests.RowCount.rowHeight)
          .overlay { Text("\(row.index)") }
      },
      isAtBottom: $isAtBottom, unreadCount: $unreadCount
    )
    .chatBottomChrome(canvas: Color(.systemBackground)) {
      ChatInputBar(
        text: $text, focusRequest: 0, placeholder: "Keyboard test",
        maxBytes: ProtocolLimits.maxDirectMessageLength, isEncrypted: false,
        onSend: { _ in }
      )
    }
    .navigationHeader(
      title: "Channel", subtitle: "Region", contentScrollsUnderBar: true,
      onTitleTap: { presentation.showInfo = true }
    )
    .sheet(isPresented: $presentation.showInfo) {
      NavigationStack {
        Form {
          ForEach(0..<12, id: \.self) { index in
            Text("Channel info \(index)")
          }
        }
        .navigationTitle("Info")
      }
    }
  }
}

extension ChatKeyboardSheetDismissalTests {
  struct InfoRow: Identifiable, Hashable, Sendable {
    let id = UUID()
    let index: Int
  }

  enum RowCount {
    static let rowCount = 40
    static let rowHeight: CGFloat = 44
  }
}

@MainActor
private final class KeyboardNotificationTop: NSObject {
  var top: CGFloat?
  private weak var window: UIWindow?

  init(window: UIWindow) {
    self.window = window
  }

  func start() {
    for name in [
      UIResponder.keyboardWillChangeFrameNotification, UIResponder.keyboardDidChangeFrameNotification,
      UIResponder.keyboardWillShowNotification, UIResponder.keyboardDidShowNotification,
      UIResponder.keyboardWillHideNotification, UIResponder.keyboardDidHideNotification,
    ] {
      NotificationCenter.default.addObserver(self, selector: #selector(changed), name: name, object: nil)
    }
  }

  deinit {
    NotificationCenter.default.removeObserver(self)
  }

  @objc private func changed(_ notification: Notification) {
    guard let window, let frame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else {
      return
    }
    let converted = window.convert(frame, from: nil)
    top = converted.minY
  }
}
