import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Watchlist board lifetime")
@MainActor
struct WatchlistNavigationLifetimeTests {
  @Test
  func `a missing board child returns to the board and a live child stays`() async throws {
    let radioID = UUID()
    let appState = try await makeAppState(radioID: radioID)
    defer { appState.shutdown() }
    let services = try #require(appState.services)
    let repeater = makeRepeater(radioID: radioID, name: "Ridge")
    let routes = NodesNavigationState(presentation: .compact)
    appState.navigation.navigateToWatchlist(radioID: radioID)
    routes.openContact(repeater, in: appState.navigation)
    let root = try #require(NodesNavigationState.root(in: appState.navigation))

    await appState.invalidateMissingNodesContact(from: ObjectIdentifier(services)) { _ in true }
    #expect(appState.navigation.nodesSelection?.contact?.id == repeater.id)

    await appState.invalidateMissingNodesContact(from: ObjectIdentifier(services)) { _ in false }
    #expect(appState.navigation.nodesSelection?.contact == nil)
    #expect(routes.compactPath(in: appState.navigation) == [root])
  }

  private func makeAppState(radioID: UUID) async throws -> AppState {
    let appState = try AppState(
      modelContainer: PersistenceStore.createContainer(inMemory: true), isPlaceholder: true
    )
    appState.connectionManager.persistConnection(deviceID: UUID(), radioID: radioID, deviceName: "Fixture")
    let services = try await ServiceContainer.forTesting(
      session: MeshCoreSession(transport: MockTransport()), radioID: radioID
    )
    appState.connectionManager.setTestState(connectionState: .disconnected, services: services)
    return appState
  }

  private func makeRepeater(radioID: UUID, name: String) -> ContactDTO {
    ContactDTO(from: Contact(
      radioID: radioID,
      publicKey: Data(repeating: 0x44, count: ProtocolLimits.publicKeySize),
      name: name,
      typeRawValue: ContactType.repeater.rawValue,
      lastHeardTimestamp: 0,
      isMyNode: true
    ))
  }
}
