import Foundation
@testable import MC1Services
import MeshCoreTestSupport
import Testing

@Suite("ConnectionManager reconnect presentation window")
@MainActor
struct ConnectionManagerReconnectWindowTests {
  enum NextAction: CaseIterable {
    case restart
    case sameRadioCycle
    case differentRadio
    case disconnect
  }

  @Test(.timeLimit(.minutes(1)), arguments: [false, true])
  func `same-radio handoff refreshes the whole presentation window`(forceReconnect: Bool) async throws {
    try await withFixture { fixture in
      let cycle = try #require(fixture.coordinator.currentCycle)
      let receipt = try #require(fixture.manager.currentLossReceipt)
      for _ in 0..<3 {
        try await fixture.advanceRearmingInterval()
      }
      fixture.clock.advance(by: .seconds(10))

      let result = try await fixture.manager.connect(
        to: fixture.deviceID, forceFullSync: true, forceReconnect: forceReconnect
      )
      guard case let .handoff(requestID) = result else {
        Issue.record("The connect must join the existing recovery request")
        return
      }
      #expect(requestID == fixture.request.id)
      try await waitUntil("the refreshed timeout must park") { fixture.clock.sleeperCount == 1 }
      fixture.clock.advance(by: .seconds(5))
      try await waitUntil("the old deadline must be handled or remain superseded") {
        fixture.clock.sleeperCount == 1 || !fixture.losses.isEmpty
      }
      try await waitUntil("state events must reach the current publication") {
        fixture.events.values.last == fixture.manager.connectionState
      }
      #expect(fixture.manager.connectionState == .connecting)
      #expect(fixture.losses.isEmpty)
      #expect(fixture.events.values == [.connecting])
      try #require(fixture.clock.sleeperCount == 1)
      #expect(fixture.manager.desiredRequest === fixture.request)
      #expect(fixture.coordinator.currentCycle == cycle)
      #expect(fixture.manager.currentLossReceipt?.id == receipt.id)
      #expect(fixture.manager.currentConnection == nil)
      #expect(fixture.manager.services == nil)
      #expect(fixture.manager.session == nil)
      #expect(await fixture.env.transport.connectInvocations.isEmpty)
      #expect(await fixture.env.transport.disconnectInvocations == 0)

      try await fixture.advanceRearmingInterval(by: .seconds(10))
      for _ in 0..<2 {
        try await fixture.advanceRearmingInterval()
      }
      fixture.clock.advance(by: .seconds(15))
      try await waitUntil("the refreshed window must eventually expire") { fixture.losses.count == 1 }
      try await waitUntil("expiry must publish its state event") { fixture.events.values.count == 2 }
      #expect(fixture.events.values == [.connecting, .disconnected])
      #expect(fixture.losses.map(\.id) == [receipt.id])
      #expect(fixture.manager.connectionState == .disconnected)
      #expect(fixture.manager.desiredRequest === fixture.request)
      #expect(fixture.coordinator.currentCycle == cycle)
      #expect(fixture.manager.connectionIntent.wantsConnection)
      #expect(fixture.clock.sleeperCount == 0)
      #expect(await fixture.env.stateMachine.isAutoReconnecting)
      #expect(await fixture.env.transport.connectInvocations.isEmpty)
      #expect(await fixture.env.transport.disconnectInvocations == 0)

      let abandoned = try await fixture.manager.connect(to: fixture.deviceID)
      #expect(fixture.manager.isCurrent(abandoned))
      #expect(fixture.manager.connectionState == .disconnected)
      #expect(fixture.manager.desiredRequest === fixture.request)
      #expect(fixture.coordinator.currentCycle == cycle)
      // Probe the elapsed window without beginning or restarting the recovery cycle.
      fixture.manager.setTestState(connectionState: .connecting)
      fixture.coordinator.armTimeout(cycle)
      try await waitUntil("the elapsed-window probe must park") { fixture.clock.sleeperCount == 1 }
      fixture.clock.advance(by: .seconds(15))
      try await waitUntil("the probe must expire or rearm") {
        fixture.losses.count == 2 || fixture.clock.sleeperCount == 1
      }
      try await waitUntil("the probe's state event must be recorded") {
        fixture.events.values.last == fixture.manager.connectionState
      }
      #expect(fixture.losses.map(\.id) == [receipt.id, receipt.id])
      #expect(fixture.events.values == [.connecting, .disconnected, .connecting, .disconnected])
      #expect(fixture.clock.sleeperCount == 0)
    }
  }

  @Test(.timeLimit(.minutes(1)), arguments: [false, true])
  func `same-radio handoff leaves an active rebuild without a presentation timer`(forceReconnect: Bool) async throws {
    try await withFixture { fixture in
      let cycle = try #require(fixture.coordinator.currentCycle)
      let gate = fixture.gate()
      var rebuilding = false
      fixture.coordinator.onRebuild = { _ in
        rebuilding = true
        await gate.wait()
        try Task.checkCancellation()
      }
      fixture.tasks.append(Task {
        await fixture.manager.handleBLELifecycleEvent(.reconnected(fixture.origin))
      })
      try await waitUntil("the real coordinator must enter its rebuild") { rebuilding }
      #expect(fixture.coordinator.isRebuilding(cycle))
      #expect(fixture.clock.sleeperCount == 0)

      let result = try await fixture.manager.connect(to: fixture.deviceID, forceReconnect: forceReconnect)
      guard case let .handoff(requestID) = result else {
        Issue.record("The connect must join the existing recovery request")
        return
      }
      #expect(requestID == fixture.request.id)
      fixture.clock.advance(by: .seconds(120))
      #expect(fixture.clock.sleeperCount == 0)
      #expect(fixture.coordinator.isRebuilding(cycle))
      #expect(fixture.coordinator.currentCycle == cycle)
      #expect(fixture.manager.desiredRequest === fixture.request)
      #expect(fixture.manager.connectionState == .connecting)
      #expect(fixture.events.values == [.connecting])
      #expect(fixture.losses.isEmpty)
      #expect(await fixture.env.transport.connectInvocations.isEmpty)
      #expect(await fixture.env.transport.disconnectInvocations == 0)
    }
  }

  @Test(.timeLimit(.minutes(1)), arguments: [false, true], NextAction.allCases)
  func `a suspended timeout query cannot affect a restarted or replaced owner`(oldResult: Bool, action: NextAction) async throws {
    try await withFixture { fixture in
      let oldCycle = try #require(fixture.coordinator.currentCycle)
      let gate = fixture.gate()
      let originalQuery = fixture.coordinator.isAutoReconnecting
      fixture.coordinator.isAutoReconnecting = {
        fixture.activeQueries += 1
        defer { fixture.activeQueries -= 1 }
        await gate.wait()
        return oldResult
      }
      fixture.clock.advance(by: .seconds(15))
      try await waitUntil("the old timeout must suspend in its transport query") { fixture.activeQueries == 1 }

      let expectedEventCount: Int
      switch action {
      case .restart:
        _ = try await fixture.manager.connect(to: fixture.deviceID, forceReconnect: true)
        expectedEventCount = 1
      case .sameRadioCycle:
        let nextOrigin = BLELifecycleOrigin(deviceID: fixture.deviceID, generation: 2, eventTime: fixture.request.admittedAt)
        await fixture.manager.enterBLERecovery(fixture.request, origin: nextOrigin)
        expectedEventCount = 2
      case .differentRadio:
        let nextID = UUID()
        fixture.env.accessorySetupKit.setPairedAccessories([
          ASAccessory(bluetoothIdentifier: nextID, displayName: "Next")
        ])
        _ = try await fixture.manager.requestConnection(
          targets: [.bluetooth(nextID)], origin: .explicit, forceFullSync: false, forceReconnect: true,
          operation: { request in
            let origin = BLELifecycleOrigin(deviceID: nextID, generation: 2, eventTime: request.admittedAt)
            await fixture.manager.enterBLERecovery(request, origin: origin)
            return .handoff(requestID: request.id)
          }
        )
        expectedEventCount = 3
      case .disconnect:
        await fixture.manager.disconnect()
        expectedEventCount = 2
      }
      if action != .disconnect {
        try await waitUntil("the current timeout must park") { fixture.clock.sleeperCount == 1 }
      }
      try await waitUntil("the action's state events must be recorded") { fixture.events.values.count == expectedEventCount }
      let request = try #require(fixture.manager.desiredRequest)
      let currentCycle = fixture.coordinator.currentCycle
      let receipt = try #require(fixture.manager.currentLossReceipt)
      let events = fixture.events.values
      let losses = fixture.losses.map(\.id)
      let disconnects = await fixture.env.transport.disconnectInvocations
      #expect((currentCycle == oldCycle) == (action == .restart))
      fixture.coordinator.isAutoReconnecting = originalQuery
      gate.open()
      try await waitUntil("the old transport query must settle") { fixture.activeQueries == 0 }

      if action == .disconnect {
        fixture.clock.advance(by: .seconds(120))
        #expect(fixture.clock.sleeperCount == 0)
        #expect(fixture.manager.connectionState == .disconnected)
        #expect(fixture.manager.connectionIntent.isUserDisconnected)
      } else {
        for _ in 0..<3 {
          try await fixture.advanceRearmingInterval()
        }
        fixture.clock.advance(by: .seconds(15))
        try await waitUntil("only the current window may publish loss") { fixture.losses.count > losses.count }
        try await waitUntil("the current expiry event must be recorded") { fixture.events.values.count > events.count }
        #expect(fixture.manager.connectionState == .disconnected)
        #expect(fixture.events.values == events + [.disconnected])
        #expect(fixture.losses.map(\.id) == losses + [receipt.id])
      }
      if action == .disconnect {
        #expect(fixture.events.values == events)
        #expect(fixture.losses.map(\.id) == losses)
      }
      #expect(fixture.manager.desiredRequest === request)
      #expect(fixture.coordinator.currentCycle == currentCycle)
      #expect(fixture.manager.currentLossReceipt?.id == receipt.id)
      #expect(fixture.manager.currentConnection == nil)
      #expect(fixture.manager.services == nil)
      #expect(fixture.manager.session == nil)
      #expect(await fixture.env.transport.connectInvocations.isEmpty)
      #expect(await fixture.env.transport.disconnectInvocations == disconnects)
    }
  }

  private func withFixture(_ body: @MainActor (Fixture) async throws -> Void) async throws {
    let fixture = try Fixture()
    do {
      try await fixture.start()
      try await body(fixture)
    } catch {
      await fixture.close()
      throw error
    }
    await fixture.close()
  }

  @MainActor
  private final class Fixture {
    let clock: TestClock
    let env: PairingTestEnvironment
    let deviceID: UUID
    let request: ConnectionRequest
    let events = ValueTracker<DeviceConnectionState>()
    var losses: [ConnectionLossReceipt] = []
    var tasks: [Task<Void, Never>] = []
    var activeQueries = 0
    private var gates: [BLEReconnectionTestHarness.Gate] = []
    private var observer: Task<Void, Never>?

    var manager: ConnectionManager {
      env.manager
    }

    var coordinator: BLEReconnectionCoordinator {
      manager.reconnectionCoordinator
    }

    var origin: BLELifecycleOrigin {
      BLELifecycleOrigin(deviceID: deviceID, generation: 1, eventTime: request.admittedAt)
    }

    init() throws {
      let clock = TestClock()
      self.clock = clock
      let env = try ConnectionManager.createForPairingTesting(reconnectionClock: clock)
      self.env = env
      let deviceID = UUID()
      self.deviceID = deviceID
      request = env.manager.seedBLERecoveryRequest(deviceID: deviceID)
    }

    func start() async throws {
      await manager.wireTransportHandlers()
      await env.stateMachine.setStubbedIsAutoReconnecting(true)
      manager.setTestState(connectionIntent: .wantsConnection())
      let stream = manager.connectionStateEvents.subscribe()
      observer = Task { [events] in for await state in stream {
        events.record(state)
      } }
      manager.onConnectionLost = { [weak self] receipt in self?.losses.append(receipt) }
      await manager.enterBLERecovery(request, origin: origin)
      try await waitUntil("the recovery timeout must park") { self.clock.sleeperCount == 1 }
      try await waitUntil("recovery must publish connecting") { self.events.values == [.connecting] }
    }

    func advanceRearmingInterval(by duration: Duration = .seconds(15)) async throws {
      try await waitUntil("the current timeout must be sleeping") { self.clock.sleeperCount == 1 }
      clock.advance(by: duration)
      try await waitUntil("active OS recovery must rearm") { self.clock.sleeperCount == 1 }
    }

    func gate() -> BLEReconnectionTestHarness.Gate {
      let gate = BLEReconnectionTestHarness.Gate()
      gates.append(gate)
      return gate
    }

    func close() async {
      manager.onConnectionLost = nil
      coordinator.clear()
      manager.wireReconnectPolicy()
      gates.forEach { $0.open() }
      tasks.forEach { $0.cancel() }
      for task in tasks {
        await task.value
      }
      await manager.disconnect()
      manager.connectionStateEvents.finish()
      await observer?.value
      do {
        try await waitUntil("released transport queries must settle") { self.activeQueries == 0 }
      } catch { Issue.record(error) }
      #expect(clock.sleeperCount == 0)
      env.cleanup()
    }
  }
}
