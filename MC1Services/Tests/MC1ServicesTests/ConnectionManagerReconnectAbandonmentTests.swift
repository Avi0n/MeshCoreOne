import Foundation
@testable import MC1Services
import Testing

/// Tests for the reconnect-abandonment paths: while `connectionIntent.wantsConnection`
/// and the BLE state machine is auto-reconnecting, no teardown path may disconnect the
/// transport (that cancels the OS pending connect, which never expires on its own), and
/// every UI-level abandonment must leave the reconnection watchdog running.
@Suite("ConnectionManager Reconnect Abandonment Tests")
@MainActor
struct ConnectionManagerReconnectAbandonmentTests {
  // MARK: - Recovery failure policy

  @Test
  func `reconnection failure preserves a live link and keeps the watchdog armed`() async throws {
    // A rebuild failure is an app-layer failure. Severing a healthy link cancels
    // the OS pending connect and the live GATT subscription — the only wake
    // sources that survive process suspension. Preserve while budget remains.
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsConnected(true)
    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    await env.manager.finishSeededBLERecovery()

    #expect(await env.transport.disconnectInvocations == 0)
    #expect(env.manager.connectionState == .disconnected)
    #expect(env.manager.isReconnectionWatchdogRunning)
    #expect(env.manager.consecutiveRebuildFailures == 1)
    #expect(env.manager.reconnectionCoordinator.reconnectingDeviceID == nil)
  }

  @Test
  func `reconnection failure without a live link still disconnects the transport`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsAutoReconnecting(false)
    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    await env.manager.finishSeededBLERecovery()

    #expect(await env.transport.disconnectInvocations == 1)
    #expect(env.manager.connectionState == .disconnected)
    #expect(env.manager.isReconnectionWatchdogRunning)
  }

  @Test
  func `reconnection failure after user disconnect severs a held link and leaves the watchdog stopped`() async throws {
    // Intent gate is load-bearing: with holdsLink true and userDisconnected,
    // preserve must not leave the link up. Vacuous if holdsLink is false.
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsConnected(true)
    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .userDisconnected
    )

    await env.manager.finishSeededBLERecovery()

    #expect(await env.transport.disconnectInvocations == 1)
    #expect(!env.manager.isReconnectionWatchdogRunning)
    #expect(env.manager.consecutiveRebuildFailures == 0)
  }

  @Test
  func `preserve budget exhausts and severs the link`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsConnected(true)
    env.manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    env.manager.startReconnectionWatchdog()
    let armedGeneration = env.manager.reconnectionWatchdogGeneration
    #expect(env.manager.isReconnectionWatchdogRunning)

    env.manager.setTestState(connectionState: .connecting)

    let budget = ConnectionManager.maxRebuildFailuresPreservingLink
    for _ in 0..<budget {
      await env.manager.finishSeededBLERecovery()
      #expect(await env.transport.disconnectInvocations == 0)
    }

    await env.manager.finishSeededBLERecovery()
    #expect(await env.transport.disconnectInvocations == 1)
    #expect(env.manager.consecutiveRebuildFailures == budget + 1)
    #expect(env.manager.lastDisconnectDiagnostic?.contains("preserveBudgetExhausted") == true)
    #expect(env.manager.isReconnectionWatchdogRunning)
    // Exhaustion uses notifyConnectionLost → cancel+restart (generation advanced).
    #expect(env.manager.reconnectionWatchdogGeneration > armedGeneration)
  }

  @Test
  func `preserve with autoReconnecting alone keeps the link`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsConnected(false)
    await env.stateMachine.setStubbedIsAutoReconnecting(true)
    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    await env.manager.finishSeededBLERecovery()

    #expect(await env.transport.disconnectInvocations == 0)
    #expect(env.manager.isReconnectionWatchdogRunning)
  }

  @Test
  func `watchdog natural exit nils the task and preserve re-arms`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsConnected(true)
    env.manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )
    // Short first sleep so the intent/state guard can fire without a 30s wait.
    // Does not call stopReconnectionWatchdog — that is the cancel path.
    env.manager.testWatchdogInitialDelay = .milliseconds(30)
    env.manager.startReconnectionWatchdog()
    let armedGeneration = env.manager.reconnectionWatchdogGeneration
    #expect(env.manager.isReconnectionWatchdogRunning)

    // Guard-exit: intent no longer wants connection when the sleep completes.
    env.manager.setTestState(connectionIntent: .userDisconnected)
    // After testWatchdogInitialDelay the watchdog Task resumes on MainActor.
    // Wait until the natural-exit defer has niled reconnectionWatchdogTask.
    try await waitUntil(timeout: .seconds(2), "watchdog should nil after natural exit") {
      !env.manager.isReconnectionWatchdogRunning
    }

    // Natural-exit defer must nil the finished Task (not isCancelled).
    #expect(!env.manager.isReconnectionWatchdogRunning)
    #expect(env.manager.reconnectionWatchdogGeneration == armedGeneration)

    env.manager.testWatchdogInitialDelay = nil
    env.manager.setTestState(
      connectionState: .connecting,
      connectionIntent: .wantsConnection()
    )
    await env.manager.finishSeededBLERecovery()

    #expect(env.manager.isReconnectionWatchdogRunning)
    #expect(env.manager.reconnectionWatchdogGeneration > armedGeneration)
    #expect(await env.transport.disconnectInvocations == 0)
  }

  @Test
  func `user disconnect does not advance the preserve budget`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsConnected(true)
    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .userDisconnected
    )

    await env.manager.finishSeededBLERecovery()

    #expect(env.manager.consecutiveRebuildFailures == 0)
  }

  @Test
  func `recordConnectionSuccess refills the preserve budget`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsConnected(true)
    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    let budget = ConnectionManager.maxRebuildFailuresPreservingLink
    for _ in 0...budget {
      await env.manager.finishSeededBLERecovery()
    }
    #expect(await env.transport.disconnectInvocations == 1)

    // Reset via recordConnectionSuccess (must sit above the .closed early return).
    env.manager.recordConnectionSuccess()
    #expect(env.manager.consecutiveRebuildFailures == 0)

    // Further failure still preserves.
    await env.manager.finishSeededBLERecovery()
    #expect(await env.transport.disconnectInvocations == 1)
  }

  @Test
  func `preserve failure arms a live watchdog without cancel-restarting an existing one`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsConnected(true)
    env.manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    env.manager.startReconnectionWatchdog()
    let firstGeneration = env.manager.reconnectionWatchdogGeneration
    #expect(env.manager.isReconnectionWatchdogRunning)

    env.manager.setTestState(connectionState: .connecting)
    await env.manager.finishSeededBLERecovery()

    // Preserve must not cancel+restart (generation would advance via stop).
    #expect(env.manager.reconnectionWatchdogGeneration == firstGeneration)
    #expect(env.manager.isReconnectionWatchdogRunning)
  }

  // MARK: - Source loss policy

  @Test
  func `connection-lost notification while wanting connection arms the watchdog`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: TransportType?.none,
      connectionIntent: .wantsConnection()
    )

    await manager.acceptSeededBLELoss()

    #expect(manager.isReconnectionWatchdogRunning)
  }

  @Test
  func `connection-lost notification after user disconnect does not arm the watchdog`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: TransportType?.none,
      connectionIntent: .userDisconnected
    )

    await manager.acceptSeededBLELoss()

    #expect(!manager.isReconnectionWatchdogRunning)
  }

  @Test
  func `connection-lost notification on WiFi transport does not arm the BLE watchdog`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .wifi,
      connectionIntent: .wantsConnection()
    )

    manager.desiredRequest = ConnectionRequest(
      targets: [.wifi(host: "radio.test", port: 5123)], origin: .recovery,
      forceFullSync: false, forceReconnect: false, continuing: nil
    )
    await manager.acceptSeededBLELoss()

    #expect(!manager.isReconnectionWatchdogRunning)
  }

  // MARK: - connect(to:forceReconnect:) break-glass

  @Test
  func `forceReconnect connect for an abandoned reconnect cycle tears down the pending connect and attempts fresh`() async throws {
    // The pill already flipped to "Disconnected" (UI abandonment) but the coordinator's
    // reconnect cycle survives because the OS pending connect never resolved. A forced
    // tap must not re-defer into the same stuck wait.
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()
    env.accessorySetupKit.setPairedAccessories([ASAccessory(bluetoothIdentifier: deviceID, displayName: "Radio")])
    try await env.accessorySetupKit.activateSession()
    await env.transport.setConnectError(BLEError.deviceNotFound)

    let request = env.manager.seedBLERecoveryRequest(deviceID: deviceID)
    request.handedOff = true
    _ = env.manager.reconnectionCoordinator.begin(deviceID: deviceID, requestID: request.id)
    env.manager.reconnectionCoordinator.restartTimeout(deviceID: deviceID)
    env.manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    // The valid paired target reaches the transport, which rejects the fresh dial.
    // This distinguishes a new attempt from joining the abandoned OS cycle.
    await #expect(throws: ConnectionRequestFailure.self) {
      try await env.manager.connect(to: deviceID, forceReconnect: true)
    }

    #expect(await env.transport.connectInvocations.count == 1)
    #expect(await env.transport.disconnectInvocations == 2)
    #expect(env.manager.reconnectionCoordinator.reconnectingDeviceID == nil)
    #expect(env.manager.connectionState == .disconnected)
  }

  @Test
  func `forceReconnect connect while still connecting keeps deferring`() async throws {
    // The pill still shows "Connecting..." - the first tap case. Must keep restarting
    // the timeout rather than tearing down a reconnect the UI hasn't abandoned yet.
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()
    env.accessorySetupKit.setPairedAccessories([ASAccessory(bluetoothIdentifier: deviceID, displayName: "Radio")])
    try await env.accessorySetupKit.activateSession()
    await env.transport.setConnectError(BLEError.deviceNotFound)

    let request = env.manager.seedBLERecoveryRequest(deviceID: deviceID)
    request.handedOff = true
    _ = env.manager.reconnectionCoordinator.begin(deviceID: deviceID, requestID: request.id)
    env.manager.reconnectionCoordinator.restartTimeout(deviceID: deviceID)
    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    try await env.manager.connect(to: deviceID, forceReconnect: true)

    #expect(await env.transport.disconnectInvocations == 0)
    #expect(env.manager.reconnectionCoordinator.reconnectingDeviceID == deviceID)
    #expect(env.manager.connectionState == .connecting)
  }

  @Test
  func `non-force connect for an abandoned reconnect cycle still defers`() async throws {
    // Background/unattended reconnects must keep the full indefinite wait; only a
    // user-initiated (forceReconnect) tap gets the break-glass path.
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()
    env.accessorySetupKit.setPairedAccessories([ASAccessory(bluetoothIdentifier: deviceID, displayName: "Radio")])
    try await env.accessorySetupKit.activateSession()
    await env.transport.setConnectError(BLEError.deviceNotFound)

    let request = env.manager.seedBLERecoveryRequest(deviceID: deviceID)
    request.handedOff = true
    _ = env.manager.reconnectionCoordinator.begin(deviceID: deviceID, requestID: request.id)
    env.manager.reconnectionCoordinator.restartTimeout(deviceID: deviceID)
    env.manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    try await env.manager.connect(to: deviceID, forceReconnect: false)

    #expect(await env.transport.disconnectInvocations == 0)
    #expect(env.manager.reconnectionCoordinator.reconnectingDeviceID == deviceID)
    #expect(env.manager.connectionState == .disconnected)
  }

  @Test
  func `forceReconnect during an actual rebuild joins the cycle even after UI abandonment`() async throws {
    let harness = try ConnectionManagerRadioTestHarness()
    do {
      _ = try await harness.connect(.bluetooth)
      await harness.ble.holdReplies(for: .appStart)
      await harness.ble.simulateAutoReconnecting(deviceID: harness.deviceID)
      try await waitUntil("loss should claim recovery") { harness.manager.reconnectionCoordinator.reconnectingDeviceID == harness.deviceID }
      await harness.ble.simulateReconnected()
      try await waitUntil("a real rebuild should hold the handshake") {
        guard let cycle = harness.manager.reconnectionCoordinator.currentCycle else { return false }
        return harness.manager.reconnectionCoordinator.isRebuilding(cycle)
      }
      harness.manager.setTestState(connectionState: .disconnected)
      let owner = harness.manager.connectionRequestID
      let result = try await harness.manager.connect(to: harness.deviceID, forceReconnect: true)
      guard case let .handoff(requestID) = result else {
        Issue.record("forced connect should join the live rebuild")
        await harness.close()
        return
      }
      #expect(requestID == owner)
      #expect(await harness.ble.connectCount == 1)
      await harness.ble.releaseReplies(for: .appStart)
      try await waitUntil("the joined rebuild should become ready") { harness.manager.connectionState == .ready }
      _ = try await harness.manager.session?.getTime()
      await harness.close()
    } catch { await harness.close(); throw error }
  }

  @Test
  func `forceReconnect connect while transport is stuck auto-reconnecting to the same device breaks glass`() async throws {
    // Covers the second deferral branch: the coordinator's cycle was already cleared
    // (e.g. by UI timeout) but the state machine itself is still mid auto-reconnect
    // for this same device.
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()
    env.accessorySetupKit.setPairedAccessories([ASAccessory(bluetoothIdentifier: deviceID, displayName: "Radio")])
    try await env.accessorySetupKit.activateSession()
    await env.transport.setConnectError(BLEError.deviceNotFound)

    let request = env.manager.seedBLERecoveryRequest(deviceID: deviceID)
    request.handedOff = true
    await env.stateMachine.setStubbedIsAutoReconnecting(true)
    await env.stateMachine.setStubbedConnectedDeviceID(deviceID)
    env.manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    await #expect(throws: ConnectionRequestFailure.self) {
      try await env.manager.connect(to: deviceID, forceReconnect: true)
    }

    #expect(await env.transport.connectInvocations.count == 1)
    #expect(await env.transport.disconnectInvocations == 2)
    #expect(env.manager.connectionState == .disconnected)
  }

  // MARK: - Single-flight claims

  @Test
  func `overlapping health checks on a connected missing stack rebuild once`() async throws {
    let harness = try ConnectionManagerRadioTestHarness()
    do {
      await harness.ble.primePhysicalLink(deviceID: harness.deviceID)
      await harness.ble.holdReplies(for: .appStart)
      await harness.stateMachine.setStubbedIsConnected(true)
      await harness.stateMachine.setStubbedConnectedDeviceID(harness.deviceID)
      harness.manager.testLastConnectedDeviceID = harness.deviceID
      harness.manager.setTestState(connectionIntent: .wantsConnection())
      let first = Task { await harness.manager.checkBLEConnectionHealth() }
      let second = Task { await harness.manager.checkBLEConnectionHealth() }
      try await waitUntil("exactly one rebuild should submit the held handshake") {
        await harness.ble.sentData.filter { $0.first == CommandCode.appStart.rawValue }.count == 1
      }
      await harness.ble.releaseReplies(for: .appStart)
      await first.value
      await second.value
      #expect(harness.manager.connectionState == .ready)
      #expect(await harness.ble.sentData.filter { $0.first == CommandCode.appStart.rawValue }.count == 1)
      #expect(await harness.ble.connectCount == 0)
      _ = try await harness.manager.session?.getTime()
      await harness.close()
    } catch { await harness.close(); throw error }
  }

  @Test
  func `coordinator cycle blocks health rebuild until the OS reconnect completes`() async throws {
    let harness = try ConnectionManagerRadioTestHarness()
    do {
      _ = try await harness.connect(.bluetooth)
      await harness.ble.simulateAutoReconnecting(deviceID: harness.deviceID)
      try await waitUntil("source loss should retain its cycle") {
        harness.manager.reconnectionCoordinator.reconnectingDeviceID == harness.deviceID && harness.manager.connectionTeardownCount == 0
      }
      harness.manager.setTestState(connectionState: .disconnected)
      await harness.stateMachine.setStubbedIsConnected(true)
      await harness.stateMachine.setStubbedConnectedDeviceID(harness.deviceID)
      await harness.manager.checkBLEConnectionHealth()
      #expect(harness.manager.reconnectionCoordinator.reconnectingDeviceID == harness.deviceID)
      #expect(await harness.ble.sentData.filter { $0.first == CommandCode.appStart.rawValue }.count == 1)
      await harness.ble.simulateReconnected()
      try await waitUntil("the retained cycle should still rebuild") { harness.manager.connectionState == .ready }
      _ = try await harness.manager.session?.getTime()
      await harness.close()
    } catch { await harness.close(); throw error }
  }
}
