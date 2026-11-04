import Foundation
@testable import MC1Services
import MeshCoreTestSupport
import os
import Testing

@MainActor
final class ConnectionManagerRadioTestHarness {
  let manager: ConnectionManager
  let ble: ConnectionTestRadioTransport
  let wifi: ConnectionTestRadioTransport
  let stateMachine = MockBLEStateMachine()
  let pairing = MockAccessorySetupKitService()
  let deviceID = UUID()
  /// Resync and reconnect delays. Command timeouts stay on `sessionClock`.
  let clock: TestClock?
  let sessionClock: TestClock?
  private let suite = "test.\(UUID().uuidString)"

  init(
    sameRadio: Bool = true,
    publicKey: Data = Data(repeating: 1, count: 32),
    clock: TestClock? = nil,
    sessionClock: TestClock? = nil
  ) throws {
    self.clock = clock
    self.sessionClock = sessionClock
    let store = try PersistenceStore.createContainer(inMemory: true)
    ble = ConnectionTestRadioTransport(publicKey: publicKey)
    let wifi = ConnectionTestRadioTransport(publicKey: sameRadio ? publicKey : Data(repeating: 2, count: 32))
    self.wifi = wifi
    pairing.isSessionActive = false
    let defaults = try #require(UserDefaults(suiteName: suite))
    manager = ConnectionManager(
      modelContainer: store, defaults: defaults, stateMachine: stateMachine, transport: ble,
      pairing: AccessorySetupPairingService(accessorySetupKit: pairing),
      reconnectionClock: clock, makeWiFiTransport: { wifi }
    )
    manager.sessionClockForTesting = sessionClock
  }

  /// Runs work whose command timeouts park on `sessionClock` and lets each parked timeout elapse.
  func resultAdvancingClock<T: Sendable>(
    _ work: @escaping @MainActor @Sendable () async throws -> T
  ) async throws -> T {
    guard let sessionClock else { return try await work() }
    let slot = OSAllocatedUnfairLock<Result<T, Error>?>(initialState: nil)
    let task = Task { @MainActor in
      let outcome: Result<T, Error>
      do { outcome = try await .success(work()) } catch { outcome = .failure(error) }
      slot.withLock { $0 = outcome }
    }
    try await waitUntilClockAdvances(sessionClock, timeout: .seconds(15), "clocked connection work should finish") {
      slot.withLock { $0 != nil }
    }
    task.cancel()
    return try slot.withLock { try $0!.get() }
  }

  func connect(_ type: TransportType) async throws -> ConnectionResult {
    if type == .bluetooth { return try await manager.connect(to: deviceID, forceFullSync: true, forceReconnect: true) }
    return try await manager.connectViaWiFi(host: "radio.test", port: 5123, forceFullSync: true)
  }

  func close() async {
    await ble.releaseAllGates()
    await wifi.releaseAllGates()
    _ = await manager.disconnect(reason: .userInitiated)
    await manager.retirementTask?.value
    await manager.metadataCommitTask?.value
    UserDefaults().removePersistentDomain(forName: suite)
  }
}
