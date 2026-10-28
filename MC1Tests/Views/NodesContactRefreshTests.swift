import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import SwiftUI
import Testing
import UIKit

@Suite("Nodes contact payload refresh", .timeLimit(.minutes(1)))
@MainActor
struct NodesContactRefreshTests {
  private enum Layout {
    static let regular = CGSize(width: 1024, height: 768)
    static let compact = CGSize(width: 390, height: 844)
  }

  @Test
  func `open detail adopts reloaded favourites across compact and regular layouts`() async throws {
    let preferences = IsolatedUserDefaults()
    let appState = try AppState(
      modelContainer: PersistenceStore.createContainer(inMemory: true),
      isPlaceholder: true,
      defaults: preferences.defaults
    )
    let radioID = UUID()
    let store = appState.connectionManager.persistenceStore
    let device = DeviceDTO(from: Device(
      radioID: radioID,
      publicKey: Data(repeating: 1, count: ProtocolLimits.publicKeySize),
      nodeName: "Fixture"
    ))
    try await store.saveDevice(device)
    appState.connectionManager.persistConnection(deviceID: device.id, radioID: radioID, deviceName: device.nodeName)
    let session = MeshCoreSession(transport: MockTransport())
    let services = ServiceContainer(session: session, dataStore: store, radioID: radioID)
    appState.connectionManager.setTestState(connectionState: .ready, services: services, connectedDevice: device)
    let key = Data(repeating: 0x42, count: ProtocolLimits.publicKeySize)
    let inserted = try await store.saveContact(radioID: radioID, from: frame(key, favorite: false))
    let contact = try #require(try await store.fetchContact(id: inserted.id))
    appState.navigation.selectedTab = AppTab.nodes.rawValue
    appState.wireSyncDataEvents(services: services)
    appState.navigation.navigateToContactDetail(contact)
    let revision = appState.navigation.nodesPresentationRevision

    let controller = UIHostingController(rootView: ContactsListView().environment(\.appState, appState))
    let window = mount(controller)
    defer {
      window.isHidden = true
      window.rootViewController = nil
      window.windowScene = nil
      appState.shutdown()
      Task {
        await services.tearDown()
        await session.stop()
      }
    }

    for (regularWidth, favorite) in [(false, true), (true, false)] {
      resize(window, controller: controller, regularWidth: regularWidth)
      _ = try await store.batchSaveContacts(radioID: radioID, from: [frame(key, favorite: favorite)])
      services.syncCoordinator.notifyContactsChanged()

      try await waitUntil("Open detail kept the favourite from before the list reload") {
        appState.navigation.selectedContact?.isFavorite == favorite
      }
      #expect(appState.navigation.selectedContact?.id == contact.id)
      #expect(appState.navigation.nodesPresentationRevision == revision)
    }

    let listener = try #require(appState.syncDataEventsTask)
    services.syncCoordinator.finishDataEvents()
    await listener.value
  }

  private func mount(_ controller: UIViewController) -> UIWindow {
    controller.traitOverrides.horizontalSizeClass = .regular
    let window = if let scene = UIApplication.shared.connectedScenes.compactMap({ $0 as? UIWindowScene }).first {
      UIWindow(windowScene: scene)
    } else {
      UIWindow()
    }
    window.frame = CGRect(origin: .zero, size: Layout.regular)
    window.rootViewController = controller
    controller.view.frame = window.bounds
    window.makeKeyAndVisible()
    window.layoutIfNeeded()
    return window
  }

  private func resize(_ window: UIWindow, controller: UIViewController, regularWidth: Bool) {
    controller.traitOverrides.horizontalSizeClass = regularWidth ? .regular : .compact
    window.frame = CGRect(origin: .zero, size: regularWidth ? Layout.regular : Layout.compact)
    controller.view.frame = window.bounds
    window.layoutIfNeeded()
  }

  private func frame(_ key: Data, favorite: Bool) -> ContactFrame {
    ContactFrame(
      publicKey: key,
      type: .chat,
      flags: favorite ? ContactFlags.favorite.rawValue : 0,
      outPathLength: 0,
      outPath: Data(),
      name: "Alex",
      lastAdvertTimestamp: 1_700_000_000,
      latitude: 0,
      longitude: 0,
      lastModified: 1_700_000_000
    )
  }
}
