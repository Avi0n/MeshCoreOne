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
  // MARK: - handleReconnectionFailure

  @Test
  func `reconnection failure tears the transport down and restarts the watchdog`() async throws {
    // Rebuild failure is a full teardown even mid-auto-reconnect: the only
    // path reaching it with a live pending connect has already lost its
    // reconnect-cycle claim, so a preserved link could never complete.
    // Recovery is the watchdog's fresh connect instead.
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsAutoReconnecting(true)
    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    await env.manager.handleReconnectionFailure()

    #expect(await env.transport.disconnectInvocations == 1)
    #expect(env.manager.connectionState == .disconnected)
    #expect(env.manager.isReconnectionWatchdogRunning)
  }

  @Test
  func `reconnection failure without auto-reconnect still disconnects the transport`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsAutoReconnecting(false)
    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    await env.manager.handleReconnectionFailure()

    #expect(await env.transport.disconnectInvocations == 1)
    #expect(env.manager.connectionState == .disconnected)
    #expect(env.manager.isReconnectionWatchdogRunning)
  }

  @Test
  func `reconnection failure after user disconnect leaves the watchdog stopped`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }

    await env.stateMachine.setStubbedIsAutoReconnecting(false)
    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .userDisconnected
    )

    await env.manager.handleReconnectionFailure()

    #expect(!env.manager.isReconnectionWatchdogRunning)
  }

  // MARK: - notifyConnectionLost

  @Test
  func `connection-lost notification while wanting connection arms the watchdog`() async throws {
    let (manager, _) = try ConnectionManager.createForTesting()

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: TransportType?.none,
      connectionIntent: .wantsConnection()
    )

    await manager.notifyConnectionLost()

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

    await manager.notifyConnectionLost()

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

    await manager.notifyConnectionLost()

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

    env.manager.reconnectionCoordinator.restartTimeout(deviceID: deviceID)
    env.manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    // The mock pairing registry has no paired accessories, so the fresh attempt
    // fails fast with the real "device not found" error instead of hanging - proof
    // the call reached a genuine connect attempt rather than deferring.
    await #expect(throws: ConnectionError.self) {
      try await env.manager.connect(to: deviceID, forceReconnect: true)
    }

    #expect(await env.transport.disconnectInvocations == 1)
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
  func `forceReconnect connect while a session rebuild is in flight for the device defers instead of breaking glass`() async throws {
    // A live session rebuild is progress, not a stuck wait: tearing down the
    // transport under it would destroy a reconnect that is about to succeed.
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()

    env.manager.reconnectionCoordinator.restartTimeout(deviceID: deviceID)
    env.manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection(),
      sessionRebuildDeviceID: deviceID
    )

    try await env.manager.connect(to: deviceID, forceReconnect: true)

    #expect(await env.transport.disconnectInvocations == 0)
    #expect(env.manager.reconnectionCoordinator.reconnectingDeviceID == deviceID)
    #expect(env.manager.connectionState == .disconnected)
  }

  @Test
  func `forceReconnect connect while transport is stuck auto-reconnecting to the same device breaks glass`() async throws {
    // Covers the second deferral branch: the coordinator's cycle was already cleared
    // (e.g. by UI timeout) but the state machine itself is still mid auto-reconnect
    // for this same device.
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()

    await env.stateMachine.setStubbedIsAutoReconnecting(true)
    await env.stateMachine.setStubbedConnectedDeviceID(deviceID)
    env.manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    await #expect(throws: ConnectionError.self) {
      try await env.manager.connect(to: deviceID, forceReconnect: true)
    }

    #expect(await env.transport.disconnectInvocations == 1)
    #expect(env.manager.connectionState == .disconnected)
  }
}
