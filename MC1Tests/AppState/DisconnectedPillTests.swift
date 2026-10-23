import Foundation
@testable import MC1
@testable import MC1Services
import SwiftData
import Testing

@Suite("Disconnected Pill Tests")
@MainActor
struct DisconnectedPillTests {
  // MARK: - shouldSuppressDisconnectedPill Integration Tests

  private let defaults: UserDefaults

  init() {
    defaults = UserDefaults(suiteName: "test.\(UUID().uuidString)")!
  }

  private func makeTestManager() throws -> ConnectionManager {
    let schema = Schema([
      Device.self,
      Contact.self,
      Message.self,
      Channel.self,
      RemoteNodeSession.self,
      RoomMessage.self
    ])
    let config = ModelConfiguration(isStoredInMemoryOnly: true)
    let container = try ModelContainer(for: schema, configurations: [config])
    return ConnectionManager(modelContainer: container, defaults: defaults)
  }

  @Test
  func `shouldSuppressDisconnectedPill returns true when user explicitly disconnected`() throws {
    ConnectionIntent.userDisconnected.persist(to: defaults)

    let manager = try makeTestManager()
    #expect(manager.shouldSuppressDisconnectedPill == true)
  }

  @Test
  func `shouldSuppressDisconnectedPill returns false when user did not explicitly disconnect`() throws {
    let manager = try makeTestManager()
    #expect(manager.shouldSuppressDisconnectedPill == false)
  }

  // MARK: - updateDisconnectedPillState Tests (pass values directly)

  @Test
  func `disconnected pill not shown when user explicitly disconnected`() {
    let clock = TestClock()
    let state = ConnectionUIState(clock: clock)

    state.updateDisconnectedPillState(
      connectionState: .disconnected,
      lastConnectedDeviceID: UUID(),
      shouldSuppressDisconnectedPill: true
    )

    #expect(state.disconnectedPillVisible == false)
    #expect(clock.sleeperCount == 0)
    #expect(state.disconnectedPillTaskForTesting == nil)
  }

  @Test
  func `disconnected pill shown after unexpected disconnect`() async throws {
    let clock = TestClock()
    let state = ConnectionUIState(clock: clock)

    state.updateDisconnectedPillState(
      connectionState: .disconnected,
      lastConnectedDeviceID: UUID(),
      shouldSuppressDisconnectedPill: false
    )

    try await waitUntil("pill delay should park") { clock.sleeperCount == 1 }
    clock.advance(by: .milliseconds(999))
    try #require(clock.sleeperCount == 1)
    #expect(state.disconnectedPillVisible == false)
    clock.advance(by: .milliseconds(1))
    try #require(clock.sleeperCount == 0)
    try await waitUntil("pill should show at the deadline") { state.disconnectedPillVisible }
  }

  @Test
  func `disconnected pill not shown when no last connected device`() {
    let clock = TestClock()
    let state = ConnectionUIState(clock: clock)

    state.updateDisconnectedPillState(
      connectionState: .disconnected,
      lastConnectedDeviceID: nil,
      shouldSuppressDisconnectedPill: false
    )

    #expect(state.disconnectedPillVisible == false)
    #expect(clock.sleeperCount == 0)
    #expect(state.disconnectedPillTaskForTesting == nil)
  }

  @Test
  func `disconnected pill hidden when connection starts`() async throws {
    let clock = TestClock()
    let state = ConnectionUIState(clock: clock)

    state.updateDisconnectedPillState(
      connectionState: .disconnected,
      lastConnectedDeviceID: UUID(),
      shouldSuppressDisconnectedPill: false
    )
    try await waitUntil("pill delay should park") { clock.sleeperCount == 1 }
    clock.advance(by: .seconds(1))
    try await waitUntil("pill should show after the delay") { state.disconnectedPillVisible }

    state.hideDisconnectedPill()
    #expect(state.disconnectedPillVisible == false)
  }

  @Test
  func `disconnected pill delay prevents flash during brief reconnects`() async throws {
    let clock = TestClock()
    let state = ConnectionUIState(clock: clock)

    state.updateDisconnectedPillState(
      connectionState: .disconnected,
      lastConnectedDeviceID: UUID(),
      shouldSuppressDisconnectedPill: false
    )
    try await waitUntil("pill delay should park") { clock.sleeperCount == 1 }
    let pendingDelay = try #require(state.disconnectedPillTaskForTesting)
    #expect(state.disconnectedPillVisible == false)

    clock.advance(by: .milliseconds(500))
    try #require(clock.sleeperCount == 1)
    state.hideDisconnectedPill()
    try #require(clock.sleeperCount == 0)
    await pendingDelay.value

    clock.advance(by: .seconds(1))
    #expect(state.disconnectedPillVisible == false)
  }
}
