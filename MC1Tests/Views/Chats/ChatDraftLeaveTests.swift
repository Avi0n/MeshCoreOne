@testable import MC1
import MC1Services
import SwiftUI
import Testing
import UIKit

@Suite("Chat draft leave", .serialized)
@MainActor
struct ChatDraftLeaveTests {
  private let typed = "meet at the bridge"
  private let pollInterval: Duration = .milliseconds(50)
  private let composerTimeout: Duration = .seconds(3)
  private let returnTimeout: Duration = .seconds(2)
  private let viewport = CGSize(width: 390, height: 844)

  enum Surface: CaseIterable, Sendable {
    case dm, channel
  }

  @Test(arguments: Surface.allCases)
  func `compact navigation restores the typed draft`(surface: Surface) async throws {
    let suiteName = "test.chat-draft-leave.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defaults.removePersistentDomain(forName: suiteName)
    let appState = AppState(defaults: defaults)
    let (route, id) = conversation(for: surface)
    appState.navigation.setChatsRoute(route)
    let window = mount(ChatsView().environment(\.appState, appState))
    defer {
      dismount(window)
      defaults.removePersistentDomain(forName: suiteName)
      appState.shutdown()
    }

    let field = try await waitForComposer(in: window)
    field.insertText(typed)
    #expect(field.text == typed)
    appState.navigation.setChatsRoute(nil)
    try await waitUntil(timeout: returnTimeout, "draft should persist after leaving") {
      self.findComposer(in: window) == nil && self.persisted(defaults, id) == self.typed
    }

    appState.navigation.setChatsRoute(route)
    let restored = await waitForText(typed, in: window)
    try #require(restored)
    #expect(persisted(defaults, id) == typed)
  }

  private func persisted(_ defaults: UserDefaults, _ id: ChatConversationID) -> String? {
    DraftStore(defaults: defaults).draft(for: id)
  }

  private func conversation(for surface: Surface) -> (ChatRoute, ChatConversationID) {
    let radioID = UUID()
    let id = UUID()
    switch surface {
    case .dm:
      let contact = ContactDTO(
        id: id,
        radioID: radioID,
        publicKey: Data(repeating: 0, count: ProtocolLimits.publicKeySize),
        name: "Riley",
        typeRawValue: ContactType.chat.rawValue,
        flags: 0,
        outPathLength: 0,
        outPath: Data(),
        lastAdvertTimestamp: 0,
        latitude: 0,
        longitude: 0,
        lastModified: 0,
        lastHeardTimestamp: nil,
        nickname: nil,
        isBlocked: false,
        isMuted: false,
        isFavorite: false,
        lastMessageDate: nil,
        unreadCount: 0
      )
      return (.direct(contact), .dm(radioID: radioID, contactID: id))
    case .channel:
      let channel = ChannelDTO(
        id: id,
        radioID: radioID,
        index: 0,
        name: "Mesh HQ",
        secret: Data(),
        isEnabled: true,
        lastMessageDate: nil,
        unreadCount: 0
      )
      return (.channel(channel), .channel(radioID: radioID, channelIndex: channel.index))
    }
  }

  private func mount(_ root: some View) -> UIWindow {
    let controller = UIHostingController(rootView: root)
    let window = if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
      UIWindow(windowScene: scene)
    } else {
      UIWindow(frame: CGRect(origin: .zero, size: viewport))
    }
    window.rootViewController = controller
    controller.traitOverrides.horizontalSizeClass = .compact
    controller.traitOverrides.verticalSizeClass = .regular
    window.frame = CGRect(origin: .zero, size: viewport)
    controller.view.frame = window.bounds
    window.makeKeyAndVisible()
    window.layoutIfNeeded()
    return window
  }

  private func dismount(_ window: UIWindow) {
    window.rootViewController = nil
    window.isHidden = true
    window.windowScene = nil
  }

  private func waitForComposer(in window: UIWindow) async throws -> ChatComposerUITextView {
    let deadline = ContinuousClock.now + composerTimeout
    while ContinuousClock.now < deadline {
      if let field = findComposer(in: window) { return field }
      try await Task.sleep(for: pollInterval)
    }
    return try #require(findComposer(in: window), "composer did not appear")
  }

  private func waitForText(_ text: String, in window: UIWindow) async -> Bool {
    let deadline = ContinuousClock.now + returnTimeout
    while ContinuousClock.now < deadline {
      if findComposer(in: window)?.text == text { return true }
      try? await Task.sleep(for: pollInterval)
    }
    return false
  }

  private func findComposer(in view: UIView) -> ChatComposerUITextView? {
    if let field = view as? ChatComposerUITextView { return field }
    for child in view.subviews {
      if let field = findComposer(in: child) { return field }
    }
    return nil
  }
}
