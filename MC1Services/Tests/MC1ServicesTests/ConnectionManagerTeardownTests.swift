import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("ConnectionManager teardown ownership")
@MainActor
struct ConnectionManagerTeardownTests {
  @Test(arguments: [false, true])
  func `stale sync schedulers leave current recovery workers intact`(channelRetry: Bool) async throws {
    try await withHarness { harness in
      let current = try #require(harness.manager.currentConnection)
      let stale = try await ServiceContainer.forTesting(
        session: MeshCoreSession(transport: MockTransport()), radioID: current.device.radioID
      )
      let resyncGate = harness.gate()
      let channelGate = harness.gate()
      let resync = harness.track(Task { await resyncGate.wait() })
      let channel = harness.track(Task { await channelGate.wait() })
      harness.manager.resyncTask = resync
      harness.manager.resyncAttemptCount = 2
      harness.manager.channelRetryTask = channel
      if channelRetry {
        harness.manager.scheduleChannelOnlyRetry(radioID: current.device.radioID, services: stale, indices: [0])
      } else {
        harness.manager.startResyncLoop(radioID: current.device.radioID, services: stale)
      }
      #expect(!resync.isCancelled && !channel.isCancelled)
      #expect(harness.manager.resyncAttemptCount == 2)
      resyncGate.open()
      channelGate.open()
      await stale.tearDown()
    }
  }

  @Test(arguments: [false, true])
  func `retirement joins old wiring and sync before a replacement can dial`(duringSync: Bool) async throws {
    try await withHarness { harness in
      _ = await harness.manager.disconnect()
      let ready = harness.gate()
      harness.manager.onConnectionReady = { snapshot in
        if duringSync {
          await snapshot.services.syncCoordinator.setSyncActivityCallbacks(
            onStarted: { await ready.wait() }, onEnded: { _ in }, onPhaseChanged: { _ in }
          )
        } else {
          await ready.wait()
        }
      }
      let firstID = UUID()
      let first = harness.track(Task { try await harness.manager.connectViaBluetooth(peripheralID: firstID) })
      try await waitUntil("old setup should enter its gate") { ready.isWaiting }
      let firstRequest = harness.manager.connectionRequestID
      let baseline = await harness.radio.connectCount
      let retiring = harness.track(Task { await harness.manager.disconnect(reason: .powerOff) })
      try await waitUntil("old publication should retire") { harness.manager.currentConnection == nil }
      let replacementID = UUID()
      let replacement = harness.track(Task { try await harness.manager.connect(to: replacementID, forceReconnect: true) })
      try await waitUntil("replacement should be admitted") {
        harness.manager.connectionRequestID != firstRequest && harness.manager.desiredRequest?.target.deviceID == replacementID
      }
      #expect(await harness.radio.connectCount == baseline)
      ready.open()
      _ = await first.result
      _ = await retiring.value
      let outcome = try await replacement.value
      if case .operational = outcome {} else { Issue.record("Replacement did not become operational") }
      #expect(harness.manager.connectedDevice?.id == replacementID)
      #expect(harness.manager.connectionState == .ready)
      #expect(await harness.radio.isConnected)
      _ = try await harness.manager.currentConnection?.services.session.getTime()
    }
  }

  @Test
  func `opportunistic health and an old rebuild cannot cross a terminal cleanup barrier`() async throws {
    try await withHarness { harness in
      let request = try #require(harness.manager.desiredRequest)
      let oldCycle = harness.manager.reconnectionCoordinator.begin(deviceID: harness.deviceID, requestID: request.id)
      let gate = harness.gate()
      harness.manager.testConnectionTeardownHandler = { await gate.wait() }
      let closing = harness.track(Task { await harness.manager.disconnect(reason: .resyncFailed) })
      try await waitUntil("cleanup should park") { gate.isWaiting }
      let baseline = await harness.radio.connectCount
      var healthFinished = false
      let health = harness.track(Task {
        await harness.manager.checkBLEConnectionHealth()
        healthFinished = true
      })
      try await waitUntil("health should return or join the physical retirement barrier") {
        healthFinished || harness.manager.desiredRequest?.target.deviceID == harness.deviceID
      }
      await #expect(throws: CancellationError.self) { try await harness.manager.rebuildBLEStack(oldCycle) }
      #expect(await harness.radio.connectCount == baseline)
      #expect(harness.manager.currentConnection == nil)
      gate.open()
      _ = await closing.value
      await health.value
    }
  }

  @Test
  func `cancelling a replacement behind retirement never submits a physical connect`() async throws {
    try await withHarness { harness in
      let gate = harness.gate()
      var losses: [ConnectionLossReceipt] = []
      harness.manager.onConnectionLost = { losses.append($0) }
      harness.manager.testConnectionTeardownHandler = { await gate.wait() }
      let closing = harness.track(Task { await harness.manager.disconnect() })
      try await waitUntil("cleanup should park") { gate.isWaiting }
      let baseline = await harness.radio.connectCount
      let target = UUID()
      let replacement = harness.track(Task { try await harness.manager.connectViaBluetooth(peripheralID: target) })
      try await waitUntil("replacement should be admitted") { harness.manager.desiredRequest?.target.deviceID == target }
      let admitted = harness.manager.connectionRequestID
      let admittedIntent = harness.manager.connectionIntent
      replacement.cancel()
      try await waitUntil("caller cancellation should cancel the admitted worker") {
        harness.manager.desiredRequest?.worker?.isCancelled == true
      }
      gate.open()
      _ = await closing.value
      let outcome = try await replacement.value
      if case .cancelled = outcome {} else { Issue.record("Cancelled caller returned another outcome") }
      #expect(await harness.radio.connectCount == baseline)
      #expect(harness.manager.connectionTeardownCount == 0)
      #expect(harness.manager.currentConnection == nil)
      #expect(harness.manager.connectionState == .disconnected)
      #expect(harness.manager.desiredRequest?.attempt == nil)
      #expect(harness.manager.desiredRequest?.target.deviceID == target)
      #expect(harness.manager.connectionIntent == admittedIntent)
      #expect(harness.manager.connectionIntent == .wantsConnection(forceFullSync: false))
      #expect(losses.map(\.requestID) == [admitted])
      harness.manager.testConnectionTeardownHandler = nil
      await harness.manager.checkBLEConnectionHealth()
      await harness.manager.checkWiFiConnectionHealth()
      #expect(harness.manager.connectionState != .connecting)
      if harness.manager.connectionState == .ready {
        #expect(try await harness.manager.session?.getTime() != nil)
      }
    }
  }

  @Test(arguments: [false, true])
  func `overlapping retirement callers join the old close before replacement setup`(wifi: Bool) async throws {
    try await withHarness { harness in
      let gate = harness.gate()
      harness.manager.testConnectionTeardownHandler = { await gate.wait() }
      let first = harness.track(Task { await harness.manager.disconnect() })
      try await waitUntil("first retirement should park") { gate.isWaiting }
      let firstTerminal = harness.manager.connectionRequestID
      let second = harness.track(Task { await harness.manager.disconnect() })
      try await waitUntil("second terminal request should be admitted") {
        harness.manager.connectionRequestID != firstTerminal
      }
      let baseline = await harness.radio.connectCount
      let replacement = harness.track(Task {
        if wifi { return try await harness.manager.connectViaWiFi(host: "radio.local", port: 4242) }
        return try await harness.manager.connectViaBluetooth(peripheralID: UUID())
      })
      try await waitUntil("replacement request should replace terminal request") {
        harness.manager.desiredRequest?.target != .disconnected
      }
      #expect(await harness.radio.connectCount == baseline)
      gate.open()
      _ = await first.value
      _ = await second.value
      _ = try await replacement.value
      #expect(harness.manager.connectionState == .ready)
      #expect(harness.manager.connectionTeardownCount == 0)
      #expect(await harness.radio.disconnectCount == 1)
    }
  }

  @Test
  func `power off retirement finishes before an admitted Bluetooth replacement dials`() async throws {
    try await withHarness { harness in
      let gate = harness.gate()
      harness.manager.testConnectionTeardownHandler = { await gate.wait() }
      let powerOff = harness.track(Task { try await harness.manager.powerOffCurrentLink() })
      try await waitUntil("power off cleanup should park") { gate.isWaiting }
      let baseline = await harness.radio.connectCount
      let replacement = harness.track(Task { try await harness.manager.connectViaBluetooth(peripheralID: UUID()) })
      try await waitUntil("replacement should be admitted") { harness.manager.connectionIntent.wantsConnection }
      #expect(await harness.radio.connectCount == baseline)
      gate.open()
      _ = await powerOff.result
      _ = try await replacement.value
      #expect(harness.manager.connectionState == .ready)
      #expect(await harness.radio.isConnected)
    }
  }

  @Test
  func `an admitted replacement revokes settled power off cleanup before session publication`() async throws {
    try await withHarness { harness in
      let token = try await harness.manager.powerOffCurrentLink()
      #expect(harness.manager.powerOffOwnsCleanup(token))
      await harness.radio.holdConnect()
      let replacement = harness.track(Task { try await harness.manager.connect(to: UUID(), forceReconnect: true) })
      try await waitUntil("replacement connect should park") { await harness.radio.connectIsHeld }
      #expect(harness.manager.session == nil)
      #expect(!harness.manager.powerOffOwnsCleanup(token))
      await harness.radio.releaseConnect()
      _ = try await replacement.value
      #expect(harness.manager.connectionState == .ready)
    }
  }

  @Test
  func `admitting a replacement revokes unsettled power off and drains its actual send first`() async throws {
    try await withHarness { harness in
      await harness.radio.holdNextSendCompletion()
      let powerOff = harness.track(Task { try await harness.manager.powerOffCurrentLink() })
      try await waitUntil("power off send completion should park") { await harness.radio.sendCompletionIsHeld }
      let previous = harness.manager.connectionRequestID
      let baseline = await harness.radio.connectCount
      let replacement = harness.track(Task { try await harness.manager.connect(to: UUID(), forceReconnect: true) })
      try await waitUntil("replacement should be admitted") { harness.manager.connectionRequestID != previous }
      #expect(await harness.radio.connectCount == baseline)
      await harness.radio.releaseSendCompletion()
      await #expect(throws: PowerOffSuperseded.self) { try await powerOff.value }
      _ = try await replacement.value
      #expect(harness.manager.connectionState == .ready)
      #expect(harness.manager.connectionIntent.wantsConnection)
    }
  }

  @Test
  func `socket loss retirement finishes before a manual Bluetooth replacement`() async throws {
    try await withHarness(wifi: true) { harness in
      let gate = harness.gate()
      harness.manager.testConnectionTeardownHandler = { await gate.wait() }
      let socket = try #require(harness.manager.installedLink?.resource.wifi)
      let loss = harness.track(Task {
        await harness.manager.handleWiFiDisconnection(error: WiFiTransportError.notConnected, remoteLossAt: nil, source: socket)
      })
      try await waitUntil("socket retirement should park") { gate.isWaiting }
      let baseline = await harness.radio.connectCount
      let replacement = harness.track(Task { try await harness.manager.connectViaBluetooth(peripheralID: UUID()) })
      try await waitUntil("Bluetooth replacement should be admitted") { harness.manager.desiredRequest?.target.transportType == .bluetooth }
      #expect(await harness.radio.connectCount == baseline)
      gate.open()
      await loss.value
      _ = try await replacement.value
      #expect(harness.manager.connectionState == .ready)
      #expect(harness.manager.wifiReconnectTask == nil)
    }
  }

  private func withHarness(wifi: Bool = false, _ body: (Harness) async throws -> Void) async throws {
    let harness = try await Harness.make(wifi: wifi)
    do { try await body(harness) } catch { await harness.finish(); throw error }
    await harness.finish()
  }

  @MainActor
  private final class Harness {
    let manager: ConnectionManager
    let radio: ConnectionTestRadioTransport
    let deviceID: UUID
    let suiteName: String
    private var gates: [Gate] = []
    private var cancellations: [() -> Void] = []
    private var joins: [() async -> Void] = []

    private init(manager: ConnectionManager, radio: ConnectionTestRadioTransport, deviceID: UUID, suiteName: String) {
      self.manager = manager
      self.radio = radio
      self.deviceID = deviceID
      self.suiteName = suiteName
    }

    static func make(wifi: Bool) async throws -> Harness {
      let suite = "test.teardown.\(UUID().uuidString)"
      let defaults = try #require(UserDefaults(suiteName: suite))
      let container = try PersistenceStore.createContainer(inMemory: true)
      let radio = ConnectionTestRadioTransport()
      let stateMachine = MockBLEStateMachine()
      let pairing = MockAccessorySetupKitService()
      pairing.isSessionActive = false
      let manager = ConnectionManager(
        modelContainer: container, defaults: defaults, stateMachine: stateMachine, transport: radio,
        pairing: AccessorySetupPairingService(accessorySetupKit: pairing), makeWiFiTransport: { radio }
      )
      let deviceID = UUID()
      let harness = Harness(manager: manager, radio: radio, deviceID: deviceID, suiteName: suite)
      do {
        if wifi {
          _ = try await manager.connectViaWiFi(host: "radio.local", port: 4242)
        } else {
          _ = try await manager.connect(to: deviceID, forceReconnect: true)
        }
        #expect(manager.connectionState == .ready)
        return harness
      } catch {
        await harness.finish()
        throw error
      }
    }

    func gate() -> Gate {
      let gate = Gate(); gates.append(gate); return gate
    }

    func track<Success: Sendable, Failure: Error>(_ task: Task<Success, Failure>) -> Task<Success, Failure> {
      cancellations.append { task.cancel() }
      joins.append { _ = await task.result }
      return task
    }

    func finish() async {
      manager.testConnectionTeardownHandler = nil
      for gate in gates {
        gate.open()
      }
      await radio.releaseAllGates()
      for cancel in cancellations {
        cancel()
      }
      _ = await manager.disconnect()
      for join in joins {
        await join()
      }
      UserDefaults().removePersistentDomain(forName: suiteName)
    }
  }

  @MainActor
  private final class Gate {
    private var waiters: [CheckedContinuation<Void, Never>] = []
    private var isOpen = false
    var isWaiting: Bool {
      !waiters.isEmpty
    }

    func wait() async {
      guard !isOpen else { return }
      await withCheckedContinuation { waiters.append($0) }
    }

    func open() {
      isOpen = true
      let pending = waiters
      waiters.removeAll()
      for waiter in pending {
        waiter.resume()
      }
    }
  }
}
