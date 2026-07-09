import Foundation
@testable import MC1Services
import Testing

/// Tests for the auto-reconnect entry handler racing a manual `connect(to:)`.
/// Without coordination, the entry claims a reconnect cycle while the manual
/// retry loop's failure path disconnects the transport — cancelling the OS
/// pending connect the cycle waits on — and the stranded claim then makes the
/// watchdog, the foreground health check, and Bluetooth power-on recovery
/// early-return forever.
@Suite("ConnectionManager Auto-Reconnect Entry Tests")
@MainActor
struct ConnectionManagerAutoReconnectEntryTests {
  @Test
  func `entry during in-flight manual connect for the same device adopts the flow`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()

    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection(),
      connectingDeviceID: deviceID
    )

    try await waitUntil("auto-reconnect handler should be installed") {
      await env.stateMachine.hasAutoReconnectingHandler
    }

    await env.stateMachine.simulateAutoReconnecting(deviceID: deviceID)

    try await waitUntil("entry should claim the reconnect cycle") {
      env.manager.reconnectionCoordinator.reconnectingDeviceID == deviceID
    }

    // The manual claim is released so the retry loop bails without
    // disconnecting the transport (which would cancel the OS pending connect).
    #expect(env.manager.connectingDeviceID == nil)
    #expect(env.manager.connectionState == .connecting)
    #expect(await env.transport.disconnectInvocations == 0)
  }

  @Test
  func `entry stands down when a manual connect is in flight for a different device`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manualDeviceID = UUID()
    let droppedDeviceID = UUID()

    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection(),
      connectingDeviceID: manualDeviceID
    )

    try await waitUntil("auto-reconnect handler should be installed") {
      await env.stateMachine.hasAutoReconnectingHandler
    }

    await env.stateMachine.simulateAutoReconnecting(deviceID: droppedDeviceID)

    // The handler runs on a spawned main-actor task; give it time to land
    // before asserting nothing changed.
    await Task.yield()
    try await Task.sleep(for: .milliseconds(50))

    #expect(env.manager.reconnectionCoordinator.reconnectingDeviceID == nil)
    #expect(env.manager.connectingDeviceID == manualDeviceID)
    #expect(env.manager.connectionState == .connecting)
  }

  @Test
  func `connection loss after adoption clears the claim and arms the watchdog`() async throws {
    // Interleaving (a) of the stranded-claim finding: Bluetooth drops during the
    // adopted cycle. The loss path must clear the claim so the powered-on
    // handler and watchdog are free to recover, instead of early-returning
    // against a cycle that can never complete.
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()

    env.manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection(),
      connectingDeviceID: deviceID
    )

    try await waitUntil("auto-reconnect handler should be installed") {
      await env.stateMachine.hasAutoReconnectingHandler
    }

    await env.stateMachine.simulateAutoReconnecting(deviceID: deviceID)
    try await waitUntil("entry should claim the reconnect cycle") {
      env.manager.reconnectionCoordinator.reconnectingDeviceID == deviceID
    }

    await env.manager.handleConnectionLoss(deviceID: deviceID, error: nil)

    #expect(env.manager.reconnectionCoordinator.reconnectingDeviceID == nil)
    #expect(env.manager.connectionState == .disconnected)
    #expect(env.manager.isReconnectionWatchdogRunning)
  }
}
