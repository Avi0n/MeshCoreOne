import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import SwiftUI
import Testing

@Suite("Nodes contact invalidation", .timeLimit(.minutes(1)))
@MainActor
struct NodesContactInvalidationTests {
  private enum ConfirmedSelection: CaseIterable, Sendable {
    case matching, reopened, replacement, discovery, emptyBatch
  }

  private enum LookupReplacement: CaseIterable, Sendable {
    case otherContact, clearAndReopen, radio, services, servicesVersion, cancelledSubscription
  }

  @Test(arguments: ConfirmedSelection.allCases)
  private func `confirmed removal clears only the matching root while Nodes is hidden`(
    _ selection: ConfirmedSelection
  ) async throws {
    let fixture = try makeFixture()
    defer { fixture.stop() }
    let contact = makeContact(radioID: fixture.radioID)
    let routes = NodesNavigationState(presentation: .compact)
    let oldBinding = try openHistory(contact, appState: fixture.appState, routes: routes)
    let oldPath = oldBinding.wrappedValue
    let navigation = fixture.appState.navigation
    navigation.selectedTab = AppTab.map.rawValue
    var expectedID: UUID? = contact.id
    switch selection {
    case .matching:
      expectedID = nil
    case .reopened:
      navigation.selectedContact = nil
      navigation.navigateToContactDetail(contact)
      navigation.selectedTab = AppTab.map.rawValue
      expectedID = nil
    case .replacement:
      let replacement = makeContact(radioID: fixture.radioID, byte: 0xBB)
      navigation.navigateToContactDetail(replacement)
      navigation.selectedTab = AppTab.map.rawValue
      expectedID = replacement.id
    case .discovery:
      navigation.navigateToDiscovery()
      navigation.selectedTab = AppTab.map.rawValue
      expectedID = nil
    case .emptyBatch:
      break
    }
    let revision = navigation.nodesPresentationRevision
    await fixture.appState.wireDeviceUpdateCallbacks(services: fixture.services)
    let listener = try #require(fixture.appState.advertisementEventsTask)
    fixture.services.advertisementService.eventBroadcaster.yield(
      .contactDeletedCleanup(contactIDs: selection == .emptyBatch ? [] : [contact.id])
    )
    fixture.services.advertisementService.finishEvents()
    await listener.value

    #expect(navigation.selectedContact?.id == expectedID)
    #expect(navigation.selectedTab == AppTab.map.rawValue)
    #expect(navigation.nodesShowingDiscovery == (selection == .discovery))
    #expect(routes.sidebarPath == [.blockedContacts])
    if selection == .matching || selection == .reopened {
      #expect(navigation.nodesPresentationRevision > revision)
      #expect(routes.detailPath(in: navigation).isEmpty)
      oldBinding.wrappedValue = oldPath
      #expect(navigation.selectedContact == nil)
      oldBinding.wrappedValue = []
      #expect(routes.compactPath(in: navigation) == [.blockedContacts])
    } else {
      #expect(navigation.nodesPresentationRevision == revision)
    }
  }

  @Test
  func `contactsChanged prunes a missing selected contact without a mounted Nodes view`() async throws {
    let fixture = try makeFixture()
    defer { fixture.stop() }
    let contact = makeContact(radioID: fixture.radioID)
    let store = fixture.appState.connectionManager.persistenceStore
    try await store.saveContact(contact)
    let routes = NodesNavigationState(presentation: .compact)
    let oldBinding = try openHistory(contact, appState: fixture.appState, routes: routes)
    let oldPath = oldBinding.wrappedValue
    fixture.appState.navigation.selectedTab = AppTab.map.rawValue
    fixture.appState.wireSyncDataEvents(services: fixture.services)
    let listener = try #require(fixture.appState.syncDataEventsTask)
    try await store.deleteContact(id: contact.id)

    fixture.services.syncCoordinator.notifyContactsChanged()
    fixture.services.syncCoordinator.finishDataEvents()
    await listener.value

    #expect(fixture.appState.contactsVersion == 1)
    #expect(fixture.appState.navigation.selectedContact == nil)
    #expect(routes.detailPath(in: fixture.appState.navigation).isEmpty)
    oldBinding.wrappedValue = oldPath
    #expect(fixture.appState.navigation.selectedContact == nil)
    oldBinding.wrappedValue = []
    #expect(routes.compactPath(in: fixture.appState.navigation) == [.blockedContacts])
  }

  @Test(arguments: [false, true])
  func `existing contact survives contact refresh while connected or manually disconnected`(
    disconnected: Bool
  ) async throws {
    let fixture = try makeFixture()
    defer { fixture.stop() }
    let contact = makeContact(radioID: fixture.radioID)
    try await fixture.appState.connectionManager.persistenceStore.saveContact(contact)
    fixture.appState.navigation.navigateToContactDetail(contact)
    fixture.appState.wireSyncDataEvents(services: fixture.services)
    let listener = try #require(fixture.appState.syncDataEventsTask)
    if disconnected {
      fixture.appState.connectionManager.setTestState(
        connectionState: .disconnected, services: .some(nil), connectedDevice: .some(nil)
      )
    }
    fixture.services.syncCoordinator.notifyContactsChanged()
    fixture.services.syncCoordinator.finishDataEvents()
    await listener.value
    #expect(fixture.appState.navigation.selectedContact?.id == contact.id)
  }

  @Test
  func `failed authoritative fetch does not invalidate a selected contact`() async throws {
    let fixture = try makeFixture()
    defer { fixture.stop() }
    let contact = makeContact(radioID: fixture.radioID)
    fixture.appState.navigation.navigateToContactDetail(contact)
    fixture.appState.wireSyncDataEvents(services: fixture.services, nodesContactExists: { _ in
      throw LookupError.unavailable
    })
    let listener = try #require(fixture.appState.syncDataEventsTask)
    fixture.services.syncCoordinator.notifyContactsChanged()
    fixture.services.syncCoordinator.finishDataEvents()
    await listener.value
    #expect(fixture.appState.navigation.selectedContact?.id == contact.id)
  }

  @Test(arguments: LookupReplacement.allCases)
  private func `suspended missing-contact fetch cannot invalidate a replacement owner`(
    _ replacement: LookupReplacement
  ) async throws {
    let fixture = try makeFixture()
    defer { fixture.stop() }
    let contact = makeContact(radioID: fixture.radioID)
    let appState = fixture.appState
    let navigation = appState.navigation
    navigation.navigateToContactDetail(contact)
    navigation.selectedTab = AppTab.map.rawValue
    let entered = Gate()
    let resume = Gate()
    let store = appState.connectionManager.persistenceStore
    appState.wireSyncDataEvents(services: fixture.services, nodesContactExists: { id in
      let exists = try await store.fetchContact(id: id) != nil
      await entered.open()
      await resume.wait()
      return exists
    })
    let listener = try #require(appState.syncDataEventsTask)
    fixture.services.syncCoordinator.notifyContactsChanged()
    await entered.wait()
    var expectedID = contact.id
    var replacementServices: ServiceContainer?
    switch replacement {
    case .otherContact:
      let other = makeContact(radioID: fixture.radioID, byte: 0xBB)
      navigation.navigateToContactDetail(other)
      expectedID = other.id
    case .clearAndReopen:
      navigation.selectedContact = nil
      navigation.navigateToContactDetail(contact)
    case .radio:
      appState.connectionManager.persistConnection(deviceID: UUID(), radioID: UUID(), deviceName: "Replacement")
      appState.connectionManager.setTestState(connectedDevice: .some(nil))
    case .services:
      let next = ServiceContainer(
        session: MeshCoreSession(transport: MockTransport()), dataStore: store, radioID: fixture.radioID
      )
      replacementServices = next
      appState.connectionManager.setTestState(services: next)
    case .servicesVersion:
      appState.bumpServicesVersion()
    case .cancelledSubscription:
      listener.cancel()
    }
    await resume.open()
    fixture.services.syncCoordinator.finishDataEvents()
    await listener.value
    #expect(navigation.selectedContact?.id == expectedID)
    if let replacementServices { await replacementServices.tearDown() }
  }

  private func openHistory(
    _ contact: ContactDTO, appState: AppState, routes: NodesNavigationState
  ) throws -> Binding<[NodesRoute]> {
    routes.compactBinding(in: appState.navigation).wrappedValue = [.blockedContacts]
    appState.navigation.navigateToContactDetail(contact)
    let root = try #require(NodesNavigationState.root(in: appState.navigation))
    let history = NodesRoute.savedHistory(.init(publicKey: contact.publicKey, radioID: contact.radioID))
    routes.compactBinding(in: appState.navigation).wrappedValue = [.blockedContacts, root, history]
    routes.openMap(presentationID: UUID(), in: appState.navigation)
    return routes.compactBinding(in: appState.navigation)
  }

  private func makeContact(radioID: UUID, byte: UInt8 = 0xAA) -> ContactDTO {
    ContactDTO(from: Contact(
      radioID: radioID, publicKey: Data(repeating: byte, count: ProtocolLimits.publicKeySize),
      name: "Alex", lastHeardTimestamp: 0
    ))
  }

  private func makeFixture() throws -> Fixture {
    let suite = "NodesContactInvalidationTests.\(UUID())"
    let defaults = try #require(UserDefaults(suiteName: suite))
    let appState = try AppState(
      modelContainer: PersistenceStore.createContainer(inMemory: true), isPlaceholder: true, defaults: defaults
    )
    let radioID = UUID()
    let device = DeviceDTO(from: Device(
      radioID: radioID, publicKey: Data(repeating: 1, count: ProtocolLimits.publicKeySize), nodeName: "Fixture"
    ))
    appState.connectionManager.persistConnection(deviceID: device.id, radioID: radioID, deviceName: device.nodeName)
    let services = ServiceContainer(
      session: MeshCoreSession(transport: MockTransport()),
      dataStore: appState.connectionManager.persistenceStore, radioID: radioID
    )
    appState.connectionManager.setTestState(connectionState: .ready, services: services, connectedDevice: device)
    return Fixture(appState: appState, services: services, radioID: radioID, defaults: defaults, suite: suite)
  }

  private struct Fixture {
    let appState: AppState
    let services: ServiceContainer
    let radioID: UUID
    let defaults: UserDefaults
    let suite: String

    @MainActor
    func stop() {
      appState.shutdown()
      defaults.removePersistentDomain(forName: suite)
      Task { await services.tearDown() }
    }
  }

  private enum LookupError: Error {
    case unavailable
  }

  private actor Gate {
    private var isOpen = false
    private var waiter: CheckedContinuation<Void, Never>?

    func wait() async {
      guard !isOpen else { return }
      await withCheckedContinuation { waiter = $0 }
    }

    func open() {
      isOpen = true
      waiter?.resume()
      waiter = nil
    }
  }
}
