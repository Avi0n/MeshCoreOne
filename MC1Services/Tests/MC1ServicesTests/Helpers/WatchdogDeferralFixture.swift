import Foundation
@testable import MC1Services
import MeshCoreTestSupport
import Testing

@MainActor
final class WatchdogDeferralFixture {
  static let initialDelay: Duration = .seconds(30)
  static let laterTime: Duration = .seconds(600)
  static let observationTimeout: Duration = .seconds(3)

  let clock = TestClock()
  let pairing = BluetoothScanPairingService()
  let radio = ConnectionTestRadioTransport()
  let deviceID = UUID()
  let manager: ConnectionManager
  private let defaults: UserDefaults
  private let suite = "test.watchdog-deferral.\(UUID().uuidString)"
  private var discovery: Task<ConnectionResult, Error>?
  private var watchdogs: [Task<Void, Never>] = []
  private var lifecycleEvents: [BLELifecycleEvent] = []
  private var states: [DeviceConnectionState] = []
  private var stateObserver: Task<Void, Never>?

  init() throws {
    defaults = try #require(UserDefaults(suiteName: suite))
    manager = try ConnectionManager(
      modelContainer: PersistenceStore.createContainer(inMemory: true), defaults: defaults,
      stateMachine: MockBLEStateMachine(), transport: radio, pairing: pairing, reconnectionClock: clock
    )
    manager.testBLELifecycleEventHandled = { [weak self] event in self?.lifecycleEvents.append(event) }
  }

  func connect() async throws {
    await manager.wireTransportHandlers()
    let result = try await manager.connect(to: deviceID, forceFullSync: true, forceReconnect: true)
    try #require(manager.isCurrent(result))
    try #require(manager.connectionState == .ready)
    #expect(await radio.connectCount == 1)
    let stream = manager.connectionStateEvents.subscribe()
    stateObserver = Task { [weak self] in
      for await state in stream {
        self?.states.append(state)
      }
    }
  }

  func openDiscovery() async throws {
    discovery = Task { try await self.manager.pairNewDevice() }
    try await waitUntil(timeout: Self.observationTimeout, "the real picker must await a choice") {
      self.pairing.isPresenting
    }
    try #require(manager.isPairingInProgress)
  }

  func cancelDiscovery(cancelTask: Bool) async throws {
    let task = try #require(discovery)
    if cancelTask { task.cancel() } else { pairing.cancel() }
    do {
      _ = try await task.value
      Issue.record("Cancelled discovery must throw the platform cancellation")
    } catch DevicePairingError.cancelled {}
    discovery = nil
  }

  func loseConnection() async throws -> Task<Void, Never> {
    let origin = try #require(await radio.lifecycleOrigin)
    await radio.simulateRemoteLoss()
    try await waitUntil(timeout: Self.observationTimeout, "real link loss must arm and park recovery") {
      self.manager.connectionState == .disconnected && self.manager.connectionTeardownCount == 0
        && self.manager.isReconnectionWatchdogRunning && self.clock.sleeperCount == 1
    }
    let watchdog = try #require(manager.reconnectionWatchdogTask)
    watchdogs.append(watchdog)
    #expect(lifecycleEvents.contains { event in
      guard case .remoteLinkLost = event else { return false }
      return event.origin.deviceID == origin.deviceID && event.origin.generation == origin.generation
    })
    #expect(lifecycleEvents.contains { event in
      guard case .disconnected = event else { return false }
      return event.origin.deviceID == origin.deviceID && event.origin.generation == origin.generation
    })
    return watchdog
  }

  func requireReady() async throws {
    try await waitUntil(timeout: Self.observationTimeout, "a later retry must install a usable session") {
      self.manager.connectionState == .ready
    }
    try #require(manager.installedLink?.resource.binding.isActive == true)
  }

  func expectStates(_ expected: [DeviceConnectionState]) async throws {
    try await waitUntil(timeout: Self.observationTimeout, "state publications must be observed") {
      self.states.count >= expected.count
    }
    #expect(states == expected)
  }

  func finishAndExpectStates(_ expected: [DeviceConnectionState]) async {
    manager.connectionStateEvents.finish()
    await stateObserver?.value
    #expect(states == expected)
  }

  func close() async {
    pairing.cancel()
    discovery?.cancel()
    _ = await discovery?.result
    discovery = nil
    if let watchdog = manager.reconnectionWatchdogTask { watchdogs.append(watchdog) }
    await radio.releaseAllGates()
    let worker = manager.desiredRequest?.worker
    await manager.disconnect()
    _ = await worker?.result
    for watchdog in watchdogs {
      await watchdog.value
    }
    await manager.retirementTask?.value
    await manager.metadataCommitTask?.value
    manager.connectionStateEvents.finish()
    await stateObserver?.value
    manager.testBLELifecycleEventHandled = nil
    defaults.removePersistentDomain(forName: suite)
    #expect(clock.sleeperCount == 0)
  }
}

@MainActor
func withWatchdogFixture(_ body: @MainActor (WatchdogDeferralFixture) async throws -> Void) async throws {
  let fixture = try WatchdogDeferralFixture()
  do {
    try await fixture.connect()
    try await body(fixture)
  } catch {
    await fixture.close()
    throw error
  }
  await fixture.close()
}
