@preconcurrency import CoreBluetooth
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
    env.accessorySetupKit.isSessionActive = false
    env.manager.testLastConnectedDeviceID = deviceID
    await env.stateMachine.setStubbedIsDeviceConnectedToSystem(true)
    await env.transport.setConnectError(BLEError.bluetoothUnavailable)

    await #expect(throws: ConnectionRequestFailure.self) {
      try await env.manager.connect(to: deviceID, forceReconnect: true)
    }

    #expect(env.manager.connectionState == .disconnected)
    #expect(env.manager.reconnectionCoordinator.currentCycle == nil)
    #expect(await env.stateMachine.startAdoptingSystemConnectedPeripheralCalls == [deviceID])
    #expect(await env.transport.connectInvocations.map(\.deviceID) == [deviceID])
    _ = await env.manager.disconnect(reason: .userInitiated)
  }

  @Test
  func `user disconnect during adoption entry prevents fallback or completion`() async throws {
    let harness = try await BLEAdoptionTestHarness.make()
    let manager = harness.manager
    let deviceID = harness.ble.peripheral.identifier
    let adopting = Task { try await manager.connect(to: deviceID) }
    var disconnecting: Task<ConnectionLossReceipt, Never>?
    do {
      try await harness.awaitCondition { await harness.transport.originReadIsHeld }
      let oldRequestID = manager.connectionRequestID
      let disconnect = Task { await manager.disconnect(reason: .userInitiated) }
      disconnecting = disconnect
      try await harness.awaitCondition { manager.connectionRequestID != oldRequestID }
      await harness.transport.releaseOrigin()
      let result = try await adopting.value
      if case .superseded = result {} else { Issue.record("The disconnect must supersede adoption") }
      let receipt = await disconnect.value

      #expect(manager.isCurrent(receipt))
      #expect(manager.connectionState == .disconnected)
      #expect(manager.connectionIntent.isUserDisconnected)
      #expect(manager.reconnectionCoordinator.currentCycle == nil)
      #expect(harness.ble.native.connectCount == 0)
      #expect(await harness.firmware.sentData.isEmpty)
    } catch {
      await harness.transport.releaseOrigin()
      adopting.cancel()
      _ = await adopting.result
      _ = await disconnecting?.value
      await harness.close()
      throw error
    }
    await harness.close()
  }

  @Test
  func `adoption entry preserves an admitted reconnect for a different device`() async throws {
    let harness = try await BLEAdoptionTestHarness.make()
    let manager = harness.manager
    let deviceID = harness.ble.peripheral.identifier
    let replacementID = UUID()
    let adopting = Task { try await manager.connect(to: deviceID) }
    var replacing: Task<ConnectionResult, Error>?
    do {
      try await harness.awaitCondition { await harness.transport.originReadIsHeld }
      let next = Task {
        try await manager.requestConnection(
          targets: [.bluetooth(replacementID)], origin: .explicit, forceFullSync: false, forceReconnect: true,
          operation: { request in
            let origin = BLELifecycleOrigin(deviceID: replacementID, generation: 1, eventTime: CFAbsoluteTimeGetCurrent())
            await manager.enterBLERecovery(request, origin: origin)
            return .handoff(requestID: request.id)
          }
        )
      }
      replacing = next
      let replacement = try await next.value
      let cycle = try #require(manager.reconnectionCoordinator.currentCycle)
      await harness.transport.releaseOrigin()
      let oldResult = try await adopting.value

      if case .superseded = oldResult {} else { Issue.record("The newer request must supersede adoption") }
      #expect(manager.isCurrent(replacement))
      #expect(manager.desiredRequest?.target == .bluetooth(replacementID))
      #expect(manager.reconnectionCoordinator.currentCycle == cycle)
      #expect(cycle.deviceID == replacementID)
      #expect(manager.connectionState == .connecting)
      #expect(harness.ble.native.connectCount == 0)
      #expect(await harness.firmware.sentData.isEmpty)
    } catch {
      await harness.transport.releaseOrigin()
      adopting.cancel()
      replacing?.cancel()
      _ = await adopting.result
      _ = await replacing?.result
      await harness.close()
      throw error
    }
    await harness.close()
  }
}
