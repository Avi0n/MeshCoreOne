import Foundation
@testable import MC1Services
import Testing

@Suite("ConnectionManager Restoration Handoff")
@MainActor
struct ConnectionManagerRestorationHandoffTests {
  @Test
  func `restoration that claims the link during adoption is handed off without disconnecting`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()
    env.accessorySetupKit.isSessionActive = false
    env.manager.testLastConnectedDeviceID = deviceID
    await env.stateMachine.setStubbedIsDeviceConnectedToSystem(true)
    await env.stateMachine.claimRestorationOnNextAdoption()
    await env.transport.primePhysicalLink(deviceID: deviceID, generation: 1, recovering: true)
    try await waitUntil("lifecycle handler should be installed") { await env.transport.hasLifecycleHandler }

    let result = try await env.manager.requestConnection(
      targets: [.bluetooth(deviceID)], origin: .launch, forceFullSync: false, forceReconnect: false
    )

    guard case .handoff = result else {
      Issue.record("Launch must hand the restored link to recovery")
      return
    }
    #expect(env.manager.connectionState == .connecting)
    #expect(env.manager.reconnectionCoordinator.currentCycle?.deviceID == deviceID)
    #expect(env.manager.desiredRequest?.physicalOrigin?.generation == 1)
    #expect(!env.manager.isReconnectionWatchdogRunning)
    #expect(await env.stateMachine.startAdoptingSystemConnectedPeripheralCalls == [deviceID])
    #expect(await env.transport.connectInvocations.isEmpty)
    #expect(await env.transport.disconnectInvocations == 0)
    _ = await env.manager.disconnect(reason: .userInitiated)
  }

  @Test
  func `a connect rejected while restoration owns the link keeps the peripheral`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()
    env.accessorySetupKit.isSessionActive = false
    env.manager.testLastConnectedDeviceID = deviceID
    let stateMachine = env.stateMachine
    let transport = env.transport
    await transport.setConnectError(BLEError.connectionFailed("Already in operation: restoringState"))
    await transport.setBeforeConnect {
      await stateMachine.setStubbedIsAutoReconnecting(true)
      await stateMachine.setStubbedIsConnected(false)
      await stateMachine.setStubbedConnectedDeviceID(deviceID)
      await stateMachine.setStubbedPhase(.restoringState)
      await transport.primePhysicalLink(deviceID: deviceID, generation: 1, recovering: true)
    }
    try await waitUntil("lifecycle handler should be installed") { await env.transport.hasLifecycleHandler }

    let result = try await env.manager.requestConnection(
      targets: [.bluetooth(deviceID)], origin: .launch, forceFullSync: false, forceReconnect: false
    )

    guard case .handoff = result else {
      Issue.record("A rejected dial must hand off once restoration owns the peripheral")
      return
    }
    #expect(await env.transport.connectInvocations.map(\.deviceID) == [deviceID])
    #expect(await env.transport.disconnectInvocations == 0)
    #expect(env.manager.reconnectionCoordinator.currentCycle?.deviceID == deviceID)
    #expect(env.manager.desiredRequest?.handedOff == true)
    #expect(!env.manager.isReconnectionWatchdogRunning)
    await env.transport.setBeforeConnect(nil)
    _ = await env.manager.disconnect(reason: .userInitiated)
  }

  @Test
  func `a rejected dial whose restored phase ends before handoff closes the peripheral`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let deviceID = UUID()
    env.accessorySetupKit.isSessionActive = false
    env.manager.testLastConnectedDeviceID = deviceID
    let stateMachine = env.stateMachine
    let transport = env.transport
    await transport.setConnectError(BLEError.connectionFailed("Already in operation: restoringState"))
    await transport.setBeforeConnect {
      await stateMachine.setStubbedIsAutoReconnecting(true)
      await stateMachine.setStubbedIsConnected(false)
      await stateMachine.setStubbedConnectedDeviceID(deviceID)
      await stateMachine.setStubbedPhase(.restoringState)
      await stateMachine.releaseOwnershipAfterNextConnectedDeviceIDRead()
      await transport.primePhysicalLink(deviceID: deviceID, generation: 1, recovering: true)
    }
    try await waitUntil("lifecycle handler should be installed") { await env.transport.hasLifecycleHandler }

    await #expect(throws: ConnectionRequestFailure.self) {
      try await env.manager.requestConnection(
        targets: [.bluetooth(deviceID)], origin: .launch, forceFullSync: false, forceReconnect: false
      )
    }

    #expect(await env.transport.disconnectInvocations > 0)
    #expect(env.manager.connectionState == .disconnected)
    await env.transport.setBeforeConnect(nil)
  }
}
