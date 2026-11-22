import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Contact details Watchlist membership")
@MainActor
struct ContactDetailWatchlistStateTests {
  @Test(arguments: [
    DeviceConnectionState.disconnected, .connecting, .connected, .syncing,
  ])
  func `membership does not start before the connection is ready`(
    connectionState: DeviceConnectionState
  ) throws {
    let radioID = UUID()
    let appState = try makeAppState(radioID: radioID, connectionState: connectionState)
    defer { appState.shutdown() }
    let state = makeState(appState: appState, radioID: radioID, isMyNode: false)
    defer { state.end() }

    state.requestWatchlistMembership(true)

    #expect(state.commandTaskForTesting(.watchlistMembership(true)) == nil)
    #expect(!state.currentContact.isMyNode)
    #expect(state.errorMessage == nil)
  }

  @Test
  func `membership waits for confirmation and ignores a repeat tap`() async throws {
    let radioID = UUID()
    let appState = try makeAppState(radioID: radioID, connectionState: .ready)
    defer { appState.shutdown() }
    let state = makeState(appState: appState, radioID: radioID, isMyNode: false)
    var response: CheckedContinuation<Void, Error>?
    var calls = 0
    defer {
      response?.resume(throwing: CancellationError())
      state.end()
    }
    state.beforeCommandForTesting = { operation in
      #expect(operation == .watchlistMembership)
      calls += 1
      try await withCheckedThrowingContinuation { response = $0 }
    }

    state.requestWatchlistMembership(true)
    let task = try #require(state.commandTaskForTesting(.watchlistMembership(true)))
    #expect(state.isSettingWatchlistMembership)
    try await waitUntil { response != nil }
    state.requestWatchlistMembership(true)
    #expect(calls == 1)
    response?.resume()
    response = nil
    await task.value

    #expect(state.currentContact.isMyNode)
    #expect(state.currentContact.isFavorite)
    #expect(state.errorMessage == nil)
    #expect(!state.isSettingWatchlistMembership)
  }

  @Test
  func `a failed membership leaves the mark off`() async throws {
    let radioID = UUID()
    let appState = try makeAppState(radioID: radioID, connectionState: .ready)
    defer { appState.shutdown() }
    let state = makeState(appState: appState, radioID: radioID, isMyNode: false)
    defer { state.end() }
    state.beforeCommandForTesting = { _ in throw ContactServiceError.invalidResponse }

    state.requestWatchlistMembership(true)
    await state.commandTaskForTesting(.watchlistMembership(true))?.value

    #expect(!state.currentContact.isMyNode)
    #expect(state.errorMessage != nil)
  }

  @Test
  func `ending the detail does not apply a membership that has not finished`() async throws {
    let radioID = UUID()
    let appState = try makeAppState(radioID: radioID, connectionState: .ready)
    defer { appState.shutdown() }
    let state = makeState(appState: appState, radioID: radioID, isMyNode: false)
    var response: CheckedContinuation<Void, Error>?
    state.beforeCommandForTesting = { _ in
      try await withCheckedThrowingContinuation { response = $0 }
    }
    state.requestWatchlistMembership(true)
    let task = try #require(state.commandTaskForTesting(.watchlistMembership(true)))
    try await waitUntil { response != nil }
    state.end()
    response?.resume()
    await task.value
    #expect(!state.currentContact.isMyNode)
  }

  @Test
  func `a chat contact and a different radio do not start membership`() throws {
    let radioID = UUID()
    let appState = try makeAppState(radioID: radioID, connectionState: .ready)
    defer { appState.shutdown() }
    let chat = makeState(appState: appState, radioID: radioID, isMyNode: false, type: .chat)
    defer { chat.end() }
    chat.requestWatchlistMembership(true)
    #expect(chat.commandTaskForTesting(.watchlistMembership(true)) == nil)

    let other = makeState(appState: appState, radioID: UUID(), isMyNode: false)
    defer { other.end() }
    other.requestWatchlistMembership(true)
    #expect(other.commandTaskForTesting(.watchlistMembership(true)) == nil)
  }

  private func makeAppState(radioID: UUID, connectionState: DeviceConnectionState) throws -> AppState {
    let appState = try AppState(modelContainer: PersistenceStore.createContainer(inMemory: true), isPlaceholder: true)
    let device = DeviceDTO(from: Device(
      radioID: radioID, publicKey: Data(repeating: 0x11, count: ProtocolLimits.publicKeySize), nodeName: "Radio"
    ))
    appState.connectionManager.setTestState(connectionState: connectionState, connectedDevice: device)
    return appState
  }

  private func makeState(
    appState: AppState,
    radioID: UUID,
    isMyNode: Bool,
    type: ContactType = .repeater
  ) -> ContactDetailState {
    let state = ContactDetailState(contact: ContactDTO(from: Contact(
      radioID: radioID,
      publicKey: Data(repeating: 0x44, count: ProtocolLimits.publicKeySize),
      name: "Ridge",
      typeRawValue: type.rawValue,
      lastHeardTimestamp: 0,
      isMyNode: isMyNode
    )))
    state.configure(appState: appState) {}
    return state
  }
}
