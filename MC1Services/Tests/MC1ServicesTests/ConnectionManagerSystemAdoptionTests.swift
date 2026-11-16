@preconcurrency import CoreBluetooth
import Foundation
@testable import MC1Services
import MeshCore
import Testing

@Suite("System adoption source capture boundary")
@MainActor
struct ConnectionManagerSystemAdoptionTests {
  @Test(arguments: [false, true], [false, true])
  func `actual completed adoption reconciles after its origin read resumes`(newerCycleWhileHeld: Bool, restoring: Bool) async throws {
    let harness = try await BLEAdoptionTestHarness.make()
    let manager = harness.manager
    let deviceID = harness.ble.peripheral.identifier
    var connection: Task<ConnectionResult, Error>?
    do {
      if restoring {
        await harness.ble.machine.handleRestoredPeripheral(harness.ble.peripheral)
        try await harness.awaitCondition {
          harness.handledEvents.contains { if case .autoReconnecting = $0 { return true }; return false }
        }
      }
      let connecting = Task { try await manager.connect(to: deviceID, forceFullSync: true, forceReconnect: false) }
      connection = connecting
      try await harness.awaitCondition { await harness.transport.originReadIsHeld }
      #expect(await harness.ble.machine.currentPhase.name == "autoReconnecting")
      #expect(manager.desiredRequest?.physicalOrigin == nil)
      let originalOrigin = try #require(await harness.transport.capturedOrigin)
      await harness.completeAdoptionDiscovery()
      try await harness.awaitCondition {
        harness.handledEvents.contains { event in
          if case .reconnected = event { return event.origin.generation == originalOrigin.generation }
          return false
        }
      }
      #expect(await harness.ble.machine.isConnected)
      #expect(manager.session == nil)
      #expect(await harness.firmware.sentData.isEmpty)

      if newerCycleWhileHeld {
        harness.ble.native.peripheralState = .disconnected
        await harness.ble.machine.handleDidDisconnect(
          harness.ble.peripheral, timestamp: CFAbsoluteTimeGetCurrent(), isReconnecting: true, error: nil
        )
        try await harness.awaitCondition {
          manager.desiredRequest?.physicalOrigin?.generation == originalOrigin.generation + 1
        }
        let newerCycle = manager.reconnectionCoordinator.currentCycle
        await harness.transport.releaseOrigin()
        _ = try await connecting.value
        let acceptedGeneration = manager.desiredRequest?.physicalOrigin?.generation
        let expectedGeneration = originalOrigin.generation + 1
        #expect(acceptedGeneration == expectedGeneration,
                "An older adoption origin read cannot replace the accepted newer generation")
        #expect(manager.reconnectionCoordinator.currentCycle == newerCycle)
        await manager.handleBLELifecycleEvent(.reconnected(originalOrigin))
        #expect(manager.session == nil)
        harness.ble.native.peripheralState = .connected
        await harness.ble.machine.handleDidConnect(harness.ble.peripheral)
        await harness.completeAdoptionDiscovery()
      } else {
        await harness.transport.releaseOrigin()
      }
      let outcome = try await connecting.value
      #expect(manager.isCurrent(outcome))
      let automaticallyReady = await harness.observedBeforeDeadline { manager.connectionState == .ready && manager.session != nil }
      #expect(automaticallyReady, "The current source cycle must automatically rebuild a session after origin capture")
      guard automaticallyReady else { throw BLESetupRetirementHarness.WaitError.expired }
      let session = try #require(manager.session)
      _ = try await session.getTime()
      #expect(await harness.firmware.timeReads > 0)
      #expect(harness.ble.native.connectCount == 0)
      #expect(manager.connectedDevice?.id == deviceID)
    } catch {
      await harness.transport.releaseOrigin()
      _ = await connection?.result
      await harness.close()
      throw error
    }
    await harness.close()
    #expect(await !harness.ble.machine.isRSSIKeepaliveActive)
  }
}
