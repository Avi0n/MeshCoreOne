import Foundation
@testable import MC1
@testable import MC1Services
import SwiftData
import SwiftUI
import Testing
import UIKit

@Suite("Channel info presentation", .serialized)
@MainActor
struct ChatChannelInfoPresentationTests {
  private static let viewport = CGRect(x: 0, y: 0, width: 390, height: 844)

  @Test(arguments: [false, true])
  func `channel info reads the saved pin before opening and after offline reopen`(initialPin: Bool) async throws {
    let host = try Host(initialPin: initialPin)
    defer { host.tearDown() }
    let store = host.appState.connectionManager.persistenceStore
    try await store.saveChannel(host.channel)
    host.mount()
    try await host.waitForTitle()

    try await store.setChannelPinned(host.channel.id, isPinned: !initialPin)
    try await host.openInfo()
    try await host.expectPin(!initialPin)

    let pinSwitch = try #require(host.switches.first)
    pinSwitch.setOn(initialPin, animated: false)
    pinSwitch.sendActions(for: .valueChanged)
    try await waitUntil("Channel pin was not saved") {
      await (try? store.fetchChannel(id: host.channel.id))?.isPinned == initialPin
    }
    try await host.closeInfo()
    host.appState.connectionManager.setTestState(connectionState: .disconnected, connectedDevice: .some(nil))
    try await host.openInfo()
    try await host.expectPin(initialPin)
  }

  @MainActor
  private final class Host {
    let preferences = IsolatedUserDefaults()
    let appState: AppState
    let channel: ChannelDTO
    let window: UIWindow
    private(set) var controller: UIViewController = .init()

    init(initialPin: Bool) throws {
      let container = try PersistenceStore.createContainer(inMemory: true)
      appState = AppState(modelContainer: container, isPlaceholder: true, defaults: preferences.defaults)
      let device = DeviceDTO(from: Device(publicKey: Data(repeating: 1, count: ProtocolLimits.publicKeySize), nodeName: "Radio"))
      appState.connectionManager.setTestState(connectionState: .ready, connectedDevice: .some(device))
      appState.connectionManager.persistConnection(deviceID: device.id, radioID: device.radioID, deviceName: device.nodeName)
      channel = ChannelDTO(from: Channel(
        radioID: device.radioID, index: 1, name: "General",
        secret: Data(repeating: 1, count: ProtocolLimits.channelSecretSize), isPinned: initialPin
      ))
      let scene = try #require(UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first)
      window = UIWindow(windowScene: scene)
      window.frame = ChatChannelInfoPresentationTests.viewport
    }

    func mount() {
      let conversation = ChatConversationType.channel(channel)
      let root = NavigationStack {
        ChatConversationView(
          conversationType: conversation,
          draftEditor: appState.chatDraftEditor(for: conversation.draftConversationID)
        )
      }
      .environment(\.appState, appState)
      .environment(\.scenePhase, .active)
      controller = UIHostingController(rootView: root)
      window.rootViewController = controller
      window.makeKeyAndVisible()
      window.layoutIfNeeded()
    }

    func tearDown() {
      window.isHidden = true
      window.rootViewController = nil
      window.windowScene = nil
      appState.shutdown()
    }

    var switches: [UISwitch] {
      descendants(in: window).compactMap { $0 as? UISwitch }
    }

    func waitForTitle() async throws {
      try await waitUntil("The channel title did not appear") {
        self.window.layoutIfNeeded()
        return self.descendants(in: self.window).contains { object in
          object.accessibilityTraits.contains(.button) && object.accessibilityLabel?.contains(self.channel.name) == true
        }
      }
    }

    func openInfo() async throws {
      try await waitForTitle()
      try #require(activateButton(named: channel.name), "The channel title could not be activated")
      try await waitUntil("Channel info did not present") { self.switches.count == 1 }
    }

    func closeInfo() async throws {
      try #require(activateButton(named: L10n.Chats.Chats.Common.done), "Done could not be activated")
      try await waitUntil("Channel info did not dismiss") { self.controller.presentedViewController == nil }
    }

    func expectPin(_ expected: Bool) async throws {
      try await waitUntil("Channel info displayed the wrong pin") { self.switches.first?.isOn == expected }
    }

    func activateButton(named title: String) -> Bool {
      for object in descendants(in: window) where object.accessibilityTraits.contains(.button) {
        guard object.accessibilityLabel?.contains(title) == true else { continue }
        if let button = object as? UIButton {
          if button.allControlEvents.contains(.primaryActionTriggered) {
            button.performPrimaryAction()
          } else {
            button.sendActions(for: .touchUpInside)
          }
          return true
        }
        if object.accessibilityActivate() { return true }
      }
      return false
    }

    private func descendants(in root: NSObject) -> [NSObject] {
      var visited: Set<ObjectIdentifier> = []
      var result: [NSObject] = []
      func walk(_ object: NSObject) {
        guard visited.insert(ObjectIdentifier(object)).inserted else { return }
        result.append(object)
        if let view = object as? UIView {
          view.subviews.forEach(walk)
        }
        let count = object.accessibilityElementCount()
        if count != NSNotFound, count > 0 {
          for index in 0..<count {
            if let child = object.accessibilityElement(at: index) as? NSObject {
              walk(child)
            }
          }
        }
      }
      walk(root)
      return result
    }
  }
}
