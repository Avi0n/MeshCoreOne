import Foundation
@testable import MC1Services
import MeshCore
import MeshCoreTestSupport
import Observation
import Testing

@Suite("Connection activity and recovery controls")
@MainActor
struct ConnectionActivityTests {
  enum StopPoint: CaseIterable {
    case recovering, presentationExpired, watchdogWaiting
  }

  @Test(.timeLimit(.minutes(1)))
  func `initial connection remains cancellable when the same request becomes ready`() async throws {
    try await Fixture.withFixture { fixture in
      await fixture.ble.holdConnect()
      let connecting = Task { try await fixture.connect() }
      fixture.connections.append(connecting)
      try await waitUntil("the production transport connect must be held") { await fixture.ble.connectIsHeld }
      let activity = try #require(fixture.manager.connectionActivity)
      #expect(activity.phase == .connecting)
      #expect(activity.requestID == fixture.manager.connectionRequestID)

      await fixture.ble.releaseConnect()
      _ = try await connecting.value
      let link = try #require(fixture.manager.installedLink)
      #expect(link.requestID == activity.requestID)
      #expect(fixture.manager.connectionActivity?.phase == .ready)
      #expect(fixture.manager.connectionActivity?.targetName == link.device.nodeName)
      let receipt = await fixture.manager.disconnect(requestID: activity.requestID)
      #expect(receipt != nil)
      #expect(fixture.manager.connectionActivity == nil)
      #expect(!link.resource.binding.isActive)
      #expect(fixture.manager.connectionIntent.isUserDisconnected)
    }
  }

  @Test(.timeLimit(.minutes(1)))
  func `production BLE recovery remains visible after its actual presentation timeout`() async throws {
    try await Fixture.withFixture { fixture in
      _ = try await fixture.connect()
      let device = try #require(fixture.manager.connectedDevice)
      let request = try #require(fixture.manager.desiredRequest)
      try await fixture.startRecovery()
      let cycle = try #require(fixture.manager.reconnectionCoordinator.currentCycle)
      #expect(fixture.manager.connectionActivity?.phase == .reconnecting)
      #expect(fixture.manager.connectionActivity?.requestID == request.id)
      #expect(fixture.manager.connectionActivity?.targetName == device.nodeName)
      #expect(fixture.manager.connectedDevice == nil)

      try await fixture.expirePresentation()
      #expect(fixture.manager.connectionActivity?.phase == .waitingToReconnect)
      #expect(fixture.manager.connectionActivity?.requestID == request.id)
      #expect(fixture.manager.connectionActivity?.targetName == device.nodeName)
      #expect(fixture.manager.desiredRequest.map(ObjectIdentifier.init) == ObjectIdentifier(request))
      #expect(fixture.manager.reconnectionCoordinator.currentCycle == cycle)
      #expect(fixture.manager.connectionIntent.wantsConnection)
      #expect(fixture.manager.currentConnection == nil)
      #expect(fixture.clock.sleeperCount == 0)
      #expect(fixture.events.values.suffix(2) == [.connecting, .disconnected])
      #expect(await fixture.ble.connectCount == 1)
      #expect(await fixture.ble.disconnectCount == 0)
    }
  }

  @Test(.timeLimit(.minutes(1)), arguments: StopPoint.allCases)
  func `stop ends recovery intent and late callbacks cannot revive it`(point: StopPoint) async throws {
    try await Fixture.withFixture { fixture in
      _ = try await fixture.connect()
      let oldLink = try #require(fixture.manager.installedLink)
      let origin = try await fixture.startRecovery()
      if point == .presentationExpired { try await fixture.expirePresentation() }
      if point == .watchdogWaiting {
        await fixture.manager.handleBLELifecycleEvent(.disconnected(origin, error: nil))
        try await waitUntil("production loss must arm its watchdog") {
          fixture.manager.isReconnectionWatchdogRunning && fixture.clock.sleeperCount == 1
        }
        #expect(fixture.manager.connectionActivity?.phase == .waitingToReconnect)
      }
      let activity = try #require(fixture.manager.connectionActivity)
      try await fixture.drainCurrentState()
      let previousEvents = fixture.events.values
      let changes = ValueTracker<Bool>()
      withObservationTracking {
        _ = fixture.manager.connectionActivity
      } onChange: {
        changes.record(true)
      }
      let disconnects = await fixture.ble.disconnectCount
      let receipt = try #require(await fixture.manager.disconnect(requestID: activity.requestID))
      #expect(fixture.manager.isCurrent(receipt))
      #expect(fixture.manager.connectionActivity == nil)
      #expect(fixture.manager.connectionIntent.isUserDisconnected)
      #expect(ConnectionIntent.restored(from: fixture.defaults).isUserDisconnected)
      #expect(fixture.manager.reconnectionCoordinator.currentCycle == nil)
      #expect(!fixture.manager.isReconnectionWatchdogRunning)
      #expect(fixture.manager.currentConnection == nil)
      #expect(fixture.manager.services == nil)
      #expect(fixture.manager.session == nil)
      #expect(!oldLink.resource.binding.isActive)
      #expect(changes.values == [true])
      #expect(await fixture.ble.disconnectCount == disconnects + 1)
      #expect(try await fixture.manager.persistenceStore.fetchActiveDevice() == nil)
      #expect(try await fixture.manager.persistenceStore.fetchDevice(id: oldLink.device.id)?.radioID == oldLink.device.radioID)

      let connects = await fixture.ble.connectCount
      let commands = await fixture.ble.sentData
      let closed = await fixture.ble.disconnectCount
      await fixture.manager.handleBLELifecycleEvent(.reconnected(origin))
      await fixture.manager.handleBLELifecycleEvent(.disconnected(origin, error: .authenticationFailed))
      await fixture.manager.handleBLELifecycleEvent(.autoReconnecting(origin, errorInfo: "late loss"))
      await fixture.manager.appDidBecomeActive()
      fixture.clock.advance(by: .seconds(120))
      await fixture.finishEvents()
      #expect(fixture.events.values == previousEvents + [.disconnected])
      #expect(fixture.manager.isCurrent(receipt))
      #expect(fixture.manager.connectionActivity == nil)
      #expect(fixture.manager.connectionIntent.isUserDisconnected)
      #expect(fixture.manager.reconnectionCoordinator.currentCycle == nil)
      #expect(!fixture.manager.isReconnectionWatchdogRunning)
      #expect(fixture.clock.sleeperCount == 0)
      #expect(await fixture.ble.connectCount == connects)
      #expect(await fixture.ble.sentData == commands)
      #expect(await fixture.ble.disconnectCount == closed)
    }
  }

  @Test(.timeLimit(.minutes(1)), arguments: [false, true])
  func `stale stop leaves a successor request and all its resources untouched`(sameRadio: Bool) async throws {
    try await Fixture.withFixture { fixture in
      _ = try await fixture.connect()
      try await fixture.startRecovery()
      let oldRequestID = try #require(fixture.manager.connectionRequestID)
      try await fixture.expirePresentation()
      let nextID = sameRadio ? fixture.deviceID : UUID()
      if !sameRadio { await fixture.ble.setPublicKey(Data(repeating: 2, count: ProtocolLimits.publicKeySize), for: nextID) }
      await fixture.stateMachine.setStubbedIsAutoReconnecting(false)
      _ = try await fixture.manager.connect(to: nextID, forceFullSync: true, forceReconnect: true)
      let request = try #require(fixture.manager.desiredRequest)
      let link = try #require(fixture.manager.installedLink)
      #expect(request.id != oldRequestID)
      #expect(link.device.id == nextID)
      try await fixture.drainCurrentState()
      let states = fixture.events.values
      let commands = await fixture.ble.sentData
      let connects = await fixture.ble.connectCount
      let disconnects = await fixture.ble.disconnectCount
      let metadata = try #require(try await fixture.manager.persistenceStore.fetchActiveDevice())
      let activity = try #require(fixture.manager.connectionActivity)

      let receipt = await fixture.manager.disconnect(requestID: oldRequestID)
      #expect(receipt == nil)
      #expect(fixture.manager.desiredRequest.map(ObjectIdentifier.init) == ObjectIdentifier(request))
      #expect(fixture.manager.installedLink.map(ObjectIdentifier.init) == ObjectIdentifier(link))
      #expect(fixture.manager.session.map(ObjectIdentifier.init) == ObjectIdentifier(link.resource.session))
      #expect(fixture.manager.services.map(ObjectIdentifier.init) == ObjectIdentifier(link.services))
      #expect(link.resource.binding.isActive)
      #expect(fixture.manager.connectionIntent.wantsConnection)
      #expect(!ConnectionIntent.restored(from: fixture.defaults).isUserDisconnected)
      #expect(fixture.manager.connectionActivity?.requestID == activity.requestID)
      #expect(fixture.manager.connectionActivity?.phase == .ready)
      #expect(try await fixture.manager.persistenceStore.fetchActiveDevice()?.id == metadata.id)
      #expect(await fixture.ble.sentData == commands)
      #expect(await fixture.ble.connectCount == connects)
      #expect(await fixture.ble.disconnectCount == disconnects)
      await fixture.finishEvents()
      #expect(fixture.events.values == states)
      _ = try await link.resource.session.getTime()
    }
  }

  @Test(.timeLimit(.minutes(1)))
  func `a recovery stop still applies after the same request becomes ready`() async throws {
    try await Fixture.withFixture { fixture in
      _ = try await fixture.connect()
      try await fixture.startRecovery()
      let activity = try #require(fixture.manager.connectionActivity)
      await fixture.stateMachine.setStubbedIsAutoReconnecting(false)
      await fixture.ble.simulateReconnected()
      try await waitUntil("the real coordinator must rebuild and publish ready") {
        fixture.manager.connectionState == .ready && fixture.manager.reconnectionCoordinator.currentCycle == nil
      }
      let link = try #require(fixture.manager.installedLink)
      #expect(link.requestID == activity.requestID)
      let receipt = await fixture.manager.disconnect(requestID: activity.requestID)
      #expect(receipt != nil)
      #expect(!link.resource.binding.isActive)
      #expect(fixture.manager.connectionActivity == nil)
      #expect(fixture.manager.currentConnection == nil)
      #expect(fixture.manager.connectionIntent.isUserDisconnected)
    }
  }

  @Test(.timeLimit(.minutes(1)))
  func `WiFi loss projects recovery for its original explicit request`() async throws {
    try await Fixture.withFixture { fixture in
      _ = try await fixture.manager.connectViaWiFi(host: "radio.test", port: 5123, forceFullSync: true)
      let request = try #require(fixture.manager.desiredRequest)
      #expect(request.origin == .explicit)
      #expect(!request.handedOff)
      let gate = BLEReconnectionTestHarness.Gate()
      fixture.gates.append(gate)
      var notified = false
      fixture.manager.onAutoReconnectStarted = { _ in
        notified = true
        await gate.wait()
      }
      let loss = Task {
        await fixture.manager.handleWiFiDisconnection(error: MeshTransportError.notConnected, remoteLossAt: nil, source: fixture.wifi)
      }
      fixture.losses.append(loss)
      try await waitUntil("the production WiFi loss callback must remain held") { notified }
      #expect(fixture.manager.connectionActivity?.requestID == request.id)
      #expect(fixture.manager.connectionActivity?.phase == .reconnecting)
      #expect(fixture.manager.desiredRequest.map(ObjectIdentifier.init) == ObjectIdentifier(request))
      #expect(!request.handedOff)
      #expect(fixture.manager.connectedDevice == nil)
      let receipt = await fixture.manager.disconnect(requestID: request.id)
      #expect(receipt != nil)
      gate.open()
      await loss.value
      #expect(fixture.manager.connectionActivity == nil)
      #expect(fixture.manager.wifiReconnectTask == nil)
    }
  }

  @MainActor
  private final class Fixture {
    let clock = TestClock()
    let stateMachine = MockBLEStateMachine()
    let ble = ConnectionTestRadioTransport()
    let wifi = ConnectionTestRadioTransport()
    let deviceID = UUID()
    let defaults: UserDefaults
    let manager: ConnectionManager
    let events = ValueTracker<DeviceConnectionState>()
    var connections: [Task<ConnectionResult, Error>] = []
    var losses: [Task<Void, Never>] = []
    var gates: [BLEReconnectionTestHarness.Gate] = []
    private let suite = "test.connection-activity.\(UUID())"
    private var observer: Task<Void, Never>?

    init() throws {
      defaults = try #require(UserDefaults(suiteName: suite))
      let pairing = MockAccessorySetupKitService()
      pairing.isSessionActive = false
      let wifi = wifi
      manager = try ConnectionManager(
        modelContainer: PersistenceStore.createContainer(inMemory: true), defaults: defaults,
        stateMachine: stateMachine, transport: ble,
        pairing: AccessorySetupPairingService(accessorySetupKit: pairing),
        reconnectionClock: clock, makeWiFiTransport: { wifi }
      )
      let stream = manager.connectionStateEvents.subscribe()
      observer = Task { [events] in for await state in stream {
        events.record(state)
      } }
    }

    static func withFixture(_ body: @MainActor (Fixture) async throws -> Void) async throws {
      let fixture = try Fixture()
      do {
        await fixture.manager.wireTransportHandlers()
        try await body(fixture)
      } catch { await fixture.close(); throw error }
      await fixture.close()
    }

    func connect() async throws -> ConnectionResult {
      try await manager.connect(to: deviceID, forceFullSync: true, forceReconnect: true)
    }

    @discardableResult
    func startRecovery() async throws -> BLELifecycleOrigin {
      await stateMachine.setStubbedIsAutoReconnecting(true)
      await ble.simulateAutoReconnecting(deviceID: deviceID)
      try await waitUntil("the transport event must begin real recovery and park its timeout") {
        self.manager.connectionState == .connecting
          && self.manager.reconnectionCoordinator.currentCycle != nil && self.clock.sleeperCount == 1
      }
      try await drainCurrentState()
      return try #require(await ble.lifecycleOrigin)
    }

    func expirePresentation() async throws {
      for interval in 0..<4 {
        try await waitUntil("the current presentation timeout must be sleeping") { self.clock.sleeperCount == 1 }
        clock.advance(by: .seconds(15))
        if interval < 3 {
          try await waitUntil("OS auto recovery must rearm before the next interval") { self.clock.sleeperCount == 1 }
        }
      }
      try await waitUntil("the real coordinator must publish its expired presentation") {
        self.manager.connectionState == .disconnected
      }
      try await drainCurrentState()
    }

    func drainCurrentState() async throws {
      try await waitUntil("state events must reach the current production publication") {
        self.events.values.last == self.manager.connectionState
      }
    }

    func finishEvents() async {
      manager.connectionStateEvents.finish()
      await observer?.value
    }

    func close() async {
      gates.forEach { $0.open() }
      await ble.releaseAllGates()
      await wifi.releaseAllGates()
      for connection in connections {
        connection.cancel(); _ = await connection.result
      }
      for loss in losses {
        await loss.value
      }
      manager.onAutoReconnectStarted = nil
      _ = await manager.disconnect()
      await manager.retirementTask?.value
      await manager.metadataCommitTask?.value
      await finishEvents()
      #expect(clock.sleeperCount == 0)
      UserDefaults().removePersistentDomain(forName: suite)
    }
  }
}
