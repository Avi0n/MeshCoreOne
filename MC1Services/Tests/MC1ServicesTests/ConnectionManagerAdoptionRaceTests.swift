import Foundation
@testable import MC1Services
import Testing

@Suite("ConnectionManager Adoption Races")
@MainActor
struct ConnectionManagerAdoptionRaceTests {
  @Test
  func `declined adoption clears its own reconnect cycle`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()
    env.manager.testLastConnectedDeviceID = deviceID
    env.manager.setTestState(connectionState: .disconnected, connectionIntent: .wantsConnection())
    await env.stateMachine.setStubbedIsDeviceConnectedToSystem(true)

    let started = await env.manager.startAdoptingLastSystemConnectedPeripheralIfAvailable(
      deviceID: deviceID, context: "test"
    )

    #expect(!started)
    #expect(env.manager.connectionState == .disconnected)
    #expect(env.manager.reconnectionCoordinator.reconnectingDeviceID == nil)
    #expect(await env.stateMachine.startAdoptingSystemConnectedPeripheralCalls == [deviceID])
  }

  @Test
  func `user disconnect during adoption entry prevents fallback or adoption`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let deviceID = UUID()
    manager.testLastConnectedDeviceID = deviceID
    manager.setTestState(connectionState: .disconnected, connectionIntent: .wantsConnection())
    await env.stateMachine.setStubbedIsDeviceConnectedToSystem(true)
    manager.onAutoReconnectStarted = {
      manager.reconnectionCoordinator.clearReconnectingDevice()
      manager.setTestState(connectionState: .disconnected, connectionIntent: .userDisconnected)
    }

    let handled = await manager.startAdoptingLastSystemConnectedPeripheralIfAvailable(
      deviceID: deviceID, context: "test"
    )

    #expect(handled)
    #expect(manager.connectionState == .disconnected)
    #expect(manager.connectionIntent == .userDisconnected)
    #expect(await env.stateMachine.startAdoptingSystemConnectedPeripheralCalls.isEmpty)
  }

  @Test
  func `adoption entry preserves a newer reconnect for a different device`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let deviceID = UUID()
    let replacementID = UUID()
    manager.testLastConnectedDeviceID = deviceID
    manager.setTestState(connectionState: .disconnected, connectionIntent: .wantsConnection())
    await env.stateMachine.setStubbedIsDeviceConnectedToSystem(true)
    manager.onAutoReconnectStarted = {
      manager.onAutoReconnectStarted = nil
      await manager.reconnectionCoordinator.handleEnteringAutoReconnect(deviceID: replacementID)
    }

    let handled = await manager.startAdoptingLastSystemConnectedPeripheralIfAvailable(
      deviceID: deviceID, context: "test"
    )

    #expect(handled)
    #expect(manager.reconnectionCoordinator.reconnectingDeviceID == replacementID)
    #expect(manager.connectionState == .connecting)
    #expect(await env.stateMachine.startAdoptingSystemConnectedPeripheralCalls.isEmpty)
    manager.reconnectionCoordinator.cancelTimeout()
  }
}
