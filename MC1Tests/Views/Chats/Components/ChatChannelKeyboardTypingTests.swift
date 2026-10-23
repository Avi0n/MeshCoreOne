import Foundation
@testable import MC1
@testable import MC1Services
import SwiftUI
import Testing
import UIKit

@Suite("Channel keyboard typing", .serialized)
@MainActor
struct ChatChannelKeyboardTypingTests {
  private enum Metrics {
    static let timeout: TimeInterval = 5
    static let sceneTimeout: TimeInterval = 15
    static let minimumKeyboardHeight: CGFloat = 100
    static let tolerance: CGFloat = 8
    static let pollInterval: Duration = .milliseconds(16)
    static let settleInterval: Duration = .milliseconds(500)
    static let typingSampleDuration: TimeInterval = 0.6
    static let channelIndex: UInt8 = 1
  }

  @Test
  func `channel composer stays above keyboard when tabs refresh without refocusing`() async throws {
    let suiteName = "test.channel-keyboard.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    let appState = AppState(defaults: defaults)
    let radioID = UUID()
    let device = DeviceDTO(from: Device(
      radioID: radioID,
      publicKey: Data(repeating: 1, count: ProtocolLimits.publicKeySize), nodeName: "Test radio"
    ))
    let channel = ChannelDTO(
      id: UUID(), radioID: radioID, index: Metrics.channelIndex, name: "Keyboard test",
      secret: Data(repeating: 1, count: ProtocolLimits.channelSecretSize),
      isEnabled: true, lastMessageDate: nil, unreadCount: 0
    )
    appState.connectionManager.testLastConnectedDeviceID = device.id
    appState.connectionManager.setTestState(connectionState: .ready, connectedDevice: device)
    try await appState.connectionManager.persistenceStore.saveChannel(channel)
    try await appState.connectionManager.persistenceStore.saveMessage(MessageDTO(from: Message(
      radioID: radioID, channelIndex: channel.index, text: "Latest channel message",
      directionRawValue: MessageDirection.incoming.rawValue,
      statusRawValue: MessageStatus.sent.rawValue, senderNodeName: "Test sender", isRead: true
    )))
    appState.navigation.selectedTab = AppTab.chats.rawValue
    let conversationID = ChatConversationID.channel(radioID: radioID, channelIndex: channel.index)
    let editor = appState.chatDraftEditor(for: conversationID)
    let window = try await foregroundWindow()
    let originalController = window.rootViewController
    let controller = UIHostingController(rootView: rootView(appState: appState, locale: Locale(identifier: "en_US")))
    controller.traitOverrides.horizontalSizeClass = .compact
    let keyboard = KeyboardFrames(window: window)
    window.rootViewController = controller
    window.makeKeyAndVisible()
    defer {
      window.endEditing(true)
      window.rootViewController = originalController
      window.makeKeyAndVisible()
      appState.shutdown()
      defaults.removePersistentDomain(forName: suiteName)
    }

    let sidebarMounted = await waitUntil {
      self.descendants(UITabBar.self, in: window).contains { !$0.isHidden && $0.alpha > 0 }
        && self.descendants(ChatComposerUITextView.self, in: window).isEmpty
    }
    #expect(sidebarMounted, "The compact sidebar and bottom tabs must appear before selecting the channel")

    appState.navigation.setChatsRoute(.channel(channel))
    let composerMounted = await waitUntil {
      !self.descendants(ChatComposerUITextView.self, in: window).isEmpty
    }
    #expect(composerMounted, "Selecting the channel must mount the production composer")
    let composer = try #require(descendants(ChatComposerUITextView.self, in: window).first)
    let chrome = try #require(chromeController(for: composer))
    let timelineLoaded = await waitUntil {
      self.descendants(UICollectionView.self, in: chrome.view).contains { !$0.visibleCells.isEmpty }
    }
    #expect(timelineLoaded, "The persisted channel message must appear in the production timeline")
    try await Task.sleep(for: Metrics.settleInterval)
    disconnectHardwareKeyboard()
    #expect(composer.becomeFirstResponder())
    let shown = await waitUntil { keyboard.visibleTop != nil }
    #expect(shown, "A docked software keyboard must appear independently of the root layout guide")
    try await Task.sleep(for: Metrics.settleInterval)
    try assertChatAboveKeyboard(composer: composer, chrome: chrome, keyboard: keyboard, phase: "before typing")

    controller.rootView = rootView(appState: appState, locale: Locale(identifier: "en_GB"))
    composer.insertText("K")
    let draftUpdated = await waitUntil { editor.text == "K" }
    #expect(draftUpdated, "Native typing must update the channel Draft after the tab refresh")
    try await sampleTyping(composer: composer, chrome: chrome, keyboard: keyboard, phase: "tab refresh and typing")
    #expect(composer === descendants(ChatComposerUITextView.self, in: window).first)
    #expect(composer.isFirstResponder, "Refreshing tabs must retain the focused channel composer")
  }

  private func rootView(appState: AppState, locale: Locale) -> some View {
    MainTabView()
      .environment(\.appState, appState)
      .environment(\.scenePhase, .active)
      .environment(\.locale, locale)
  }

  private func sampleTyping(
    composer: ChatComposerUITextView, chrome: UIViewController, keyboard: KeyboardFrames, phase: String
  ) async throws {
    let deadline = Date(timeIntervalSinceNow: Metrics.typingSampleDuration)
    repeat {
      try assertChatAboveKeyboard(composer: composer, chrome: chrome, keyboard: keyboard, phase: phase)
      try await Task.sleep(for: Metrics.pollInterval)
    } while Date() < deadline
  }

  private func assertChatAboveKeyboard(
    composer: ChatComposerUITextView, chrome: UIViewController, keyboard: KeyboardFrames, phase: String
  ) throws {
    let window = try #require(composer.window)
    let top = try #require(keyboard.visibleTop, "The keyboard must remain visible during \(phase)")
    let modelFrame = composer.convert(composer.bounds, to: window)
    let frame = presentedFrame(of: composer, in: window)
    let guide = window.rootViewController?.view.keyboardLayoutGuide.layoutFrame
    #expect(frame.minY >= window.bounds.minY, "The composer must stay visible during \(phase)")
    #expect(frame.maxY <= top + Metrics.tolerance, """
    Composer moved under the keyboard during \(phase): rendered=\(frame), model=\(modelFrame), \
    keyboardNotificationTop=\(top), guide=\(String(describing: guide)), firstResponder=\(composer.isFirstResponder)
    """)
    let collection = try #require(descendants(UICollectionView.self, in: chrome.view).first)
    let latestCell = try #require(collection.visibleCells.max { $0.frame.maxY < $1.frame.maxY })
    #expect(presentedFrame(of: latestCell, in: window).maxY <= top + Metrics.tolerance, "The latest channel message moved under the keyboard during \(phase)")
  }

  private func presentedFrame(of view: UIView, in window: UIWindow) -> CGRect {
    guard let layer = view.layer.presentation(), let root = window.layer.presentation() else {
      return view.convert(view.bounds, to: window)
    }
    return layer.convert(layer.bounds, to: root)
  }

  private func chromeController(for composer: UIView) -> UIViewController? {
    var responder: UIResponder? = composer
    while let current = responder {
      if let controller = current as? UIViewController,
         controller.view.subviews.contains(where: { $0 is ChatKeyboardLayoutObserver }) { return controller }
      responder = current.next
    }
    return nil
  }

  private func descendants<T: UIView>(_ type: T.Type, in view: UIView) -> [T] {
    (view as? T).map { [$0] } ?? view.subviews.flatMap { descendants(type, in: $0) }
  }

  private func disconnectHardwareKeyboard() {
    let selector = NSSelectorFromString("setHardwareLayout:")
    for mode in UITextInputMode.activeInputModes where mode.responds(to: selector) {
      mode.perform(selector, with: nil)
    }
  }

  private func foregroundWindow() async throws -> UIWindow {
    var window: UIWindow?
    _ = await waitUntil(timeout: Metrics.sceneTimeout) {
      window = ChatKeyboardLift.keyWindow()
      return window != nil
    }
    return try #require(window, "A foreground app window is required")
  }

  private func waitUntil(timeout: TimeInterval = Metrics.timeout, _ condition: () -> Bool) async -> Bool {
    let deadline = Date(timeIntervalSinceNow: timeout)
    while Date() < deadline {
      if condition() { return true }
      try? await Task.sleep(for: Metrics.pollInterval)
    }
    return condition()
  }

  @MainActor
  private final class KeyboardFrames: NSObject {
    private weak var window: UIWindow?
    private var notificationTop: CGFloat?

    init(window: UIWindow) {
      self.window = window
      super.init()
      for name in [UIResponder.keyboardDidChangeFrameNotification, UIResponder.keyboardDidShowNotification,
                   UIResponder.keyboardDidHideNotification] {
        NotificationCenter.default.addObserver(self, selector: #selector(changed), name: name, object: nil)
      }
    }

    deinit { NotificationCenter.default.removeObserver(self) }

    var visibleTop: CGFloat? {
      guard let window else { return nil }
      let top = notificationTop ?? window.bounds.maxY
      return top < window.bounds.maxY - Metrics.minimumKeyboardHeight ? top : nil
    }

    @objc private func changed(_ notification: Notification) {
      guard let window,
            let frame = notification.userInfo?[UIResponder.keyboardFrameEndUserInfoKey] as? CGRect else { return }
      notificationTop = window.convert(frame, from: nil).minY
    }
  }
}
