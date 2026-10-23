import Foundation
@testable import MC1
@testable import MC1Services
import SwiftData
import SwiftUI
import Testing
import UIKit

@Suite("Contact details favorite toggle", .serialized)
@MainActor
struct ContactDetailFavoriteToggleTests {
  private static let viewport = CGRect(x: 0, y: 0, width: 390, height: 844)

  @Test(arguments: [DeviceConnectionState.disconnected, .connecting, .connected, .syncing, .ready], [false, true])
  func `favorite is enabled only when ready and retains its value`(
    connectionState: DeviceConnectionState,
    isFavorite: Bool
  ) async throws {
    let appState = try makeAppState()
    appState.connectionManager.setTestState(connectionState: connectionState)
    let window = mount(appState: appState, isFavorite: isFavorite)
    defer {
      window.isHidden = true
      window.rootViewController = nil
      appState.shutdown()
    }

    let favoriteSwitch = try await waitForSwitch(in: window)
    #expect(favoriteSwitch.isEnabled == (connectionState == .ready))
    #expect(favoriteSwitch.isOn == isFavorite)
  }

  @Test(arguments: [false, true])
  func `favorite follows connection changes without leaving details`(isFavorite: Bool) async throws {
    let appState = try makeAppState()
    appState.connectionManager.setTestState(connectionState: .disconnected)
    let window = mount(appState: appState, isFavorite: isFavorite)
    defer {
      window.isHidden = true
      window.rootViewController = nil
      appState.shutdown()
    }

    let initialSwitch = try await waitForSwitch(in: window)
    #expect(!initialSwitch.isEnabled)
    #expect(initialSwitch.isOn == isFavorite)

    for state in [DeviceConnectionState.ready, .disconnected] {
      appState.connectionManager.setTestState(connectionState: state)
      try await waitUntil("Favorite did not update for \(state)") {
        window.layoutIfNeeded()
        let switches = findSwitches(in: window)
        return switches.count == 1 && switches.first?.isEnabled == (state == .ready)
      }

      let favoriteSwitch = try #require(findSwitches(in: window).first)
      #expect(favoriteSwitch.isOn == isFavorite)
    }
  }

  private func makeAppState() throws -> AppState {
    let configuration = ModelConfiguration(isStoredInMemoryOnly: true)
    let container = try ModelContainer(for: PersistenceStore.schema, configurations: [configuration])
    return AppState(modelContainer: container, isPlaceholder: true)
  }

  private func mount(appState: AppState, isFavorite: Bool) -> UIWindow {
    let contact = ContactDTO(from: Contact(
      radioID: UUID(),
      publicKey: Data(repeating: 0x42, count: ProtocolLimits.publicKeySize),
      name: "Test Contact",
      typeRawValue: ContactType.chat.rawValue,
      lastHeardTimestamp: 0,
      isFavorite: isFavorite
    ))
    let rootView = NavigationStack {
      ContactDetailView(contact: contact)
    }
    .environment(\.appState, appState)
    let controller = UIHostingController(rootView: rootView)
    let window = UIWindow(frame: Self.viewport)
    window.rootViewController = controller
    window.isHidden = false
    window.layoutIfNeeded()
    return window
  }

  private func waitForSwitch(in window: UIWindow) async throws -> UISwitch {
    try await waitUntil("Contact details Favorite switch never appeared") {
      window.layoutIfNeeded()
      return findSwitches(in: window).count == 1
    }
    return try #require(findSwitches(in: window).first)
  }

  private func findSwitches(in view: UIView) -> [UISwitch] {
    if let toggle = view as? UISwitch { return [toggle] }
    return view.subviews.flatMap { findSwitches(in: $0) }
  }
}
