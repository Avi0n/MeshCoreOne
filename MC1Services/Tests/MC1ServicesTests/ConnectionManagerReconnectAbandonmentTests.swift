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
}
