#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation
@testable import MC1Services
import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("Connection manager sibling entry regressions")
@MainActor
struct ConnectionManagerSiblingEntryRegressionTests {
  @Test
  func `foreground retries admitted bluetooth despite saved wifi last`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    do {
      let seed = DeviceDTO.testDevice(id: env.deviceID, publicKey: env.ble.publicKey, isActive: false).copy {
        $0.connectionMethods = [
          .bluetooth(peripheralUUID: env.deviceID, displayName: nil),
          .wifi(host: "radio.test", port: 5123, displayName: nil)
        ]
      }
      try await env.manager.persistenceStore.saveDevice(seed)
      _ = try await env.connect(.wifi)
      await env.ble.setConnectError(BLEError.bluetoothUnavailable)
      _ = try? await env.connect(.bluetooth)
      let failedRequest = env.manager.connectionRequestID
      #expect(env.manager.desiredRequest?.target == .bluetooth(env.deviceID))
      #expect(try await env.manager.persistenceStore.fetchDevice(id: env.deviceID)?.lastUsedConnectionMethod?.isWiFi == true)
      #expect(env.manager.connectionState == .disconnected)
      let attempts = await env.ble.connectCount
      await env.ble.setConnectError(nil)
      env.manager.setCircuitBreakerOpenForTesting(since: .distantPast)
      #expect(env.manager.shouldAllowConnection(force: false))
      await env.manager.appDidBecomeActive()
      let connectCount = await env.ble.connectCount
      #expect(connectCount == attempts + 1, "foreground must honor the admitted Bluetooth target over saved method order")
      #expect(env.manager.connectionRequestID != failedRequest)
      #expect(env.manager.currentTransportType == .bluetooth)
      #expect(env.manager.connectionState == .ready)
      let probed = try await env.manager.session?.getTime() != nil
      #expect(probed)
    } catch { await env.close(); throw error }
    await env.close()
  }

  @Test(arguments: [false, true])
  func `opportunistic entries retry admitted failed bluetooth target instead of last selection`(activate: Bool) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    do {
      _ = try await env.connect(.bluetooth)
      let savedA = try #require(env.manager.connectedDevice)
      let idB = UUID()
      let keyB = Data(repeating: 2, count: ProtocolLimits.publicKeySize)
      await env.ble.setPublicKey(keyB, for: idB)
      try await env.manager.persistenceStore.saveDevice(savedA.copy {
        $0.id = idB
        $0.radioID = UUID()
        $0.publicKey = keyB
        $0.isActive = false
        $0.connectionMethods = [.bluetooth(peripheralUUID: idB, displayName: nil)]
      })
      env.pairing.setPairedAccessories([
        ASAccessory(bluetoothIdentifier: savedA.id, displayName: "A"), ASAccessory(bluetoothIdentifier: idB, displayName: "B")
      ])
      await env.ble.setConnectError(BLEError.bluetoothUnavailable)
      _ = try? await env.manager.connect(to: idB, forceFullSync: true, forceReconnect: true)
      #expect(env.manager.desiredRequest?.target == .bluetooth(idB))
      #expect(env.manager.lastConnectedDeviceID == savedA.id)
      #expect(env.manager.connectionState == .disconnected)
      await env.ble.setConnectError(nil)
      env.manager.setCircuitBreakerOpenForTesting(since: .distantPast)
      #expect(env.manager.shouldAllowConnection(force: false))
      if activate {
        await env.manager.activate()
      } else {
        let handled = env.manager.testBluetoothPoweredOnHandlerRuns
        await env.stateMachine.simulateBluetoothPoweredOn()
        try await waitUntil("installed power-on callback must complete its production request") {
          env.manager.testBluetoothPoweredOnHandlerRuns == handled + 1
        }
      }
      let dialedIDs = await env.ble.sessionDeviceIDs
      let target = env.manager.desiredRequest?.target
      #expect(target == .bluetooth(idB), "opportunistic entry must preserve admitted B")
      #expect(dialedIDs == [savedA.id, idB, idB], "opportunistic entry may retry only admitted B")
      #expect(env.manager.connectedDevice?.id == idB)
      #expect(env.manager.lastConnectedDeviceID == idB)
      #expect(try await env.manager.persistenceStore.fetchActiveDevice()?.id == idB)
      let probed = try await env.manager.session?.getTime() != nil
      #expect(probed)
    } catch { await env.close(); throw error }
    await env.close()
  }

  @Test(arguments: [false, true])
  func `power on stands down for wifi and explicit terminal requests`(disconnect: Bool) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    do {
      _ = try await env.connect(.bluetooth)
      if disconnect {
        _ = await env.manager.disconnect(reason: .userInitiated)
      } else {
        await env.wifi.setConnectError(WiFiTransportError.connectionTimeout)
        _ = try? await env.connect(.wifi)
      }
      let attempts = await env.ble.sessionDeviceIDs
      let requestID = env.manager.connectionRequestID
      let handled = env.manager.testBluetoothPoweredOnHandlerRuns
      await env.stateMachine.simulateBluetoothPoweredOn()
      try await waitUntil("installed power-on callback must settle") {
        env.manager.testBluetoothPoweredOnHandlerRuns == handled + 1
      }
      let dialedIDs = await env.ble.sessionDeviceIDs
      #expect(dialedIDs == attempts)
      #expect(env.manager.connectionRequestID == requestID)
      let published = env.manager.currentConnection != nil
      #expect(!published)
    } catch { await env.close(); throw error }
    await env.close()
  }

  @Test(arguments: [false, true])
  func `accessory removal revokes unpublished preparation before final metadata`(keepsWifi: Bool) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let radioID = UUID()
    let seed = DeviceDTO.testDevice(id: env.deviceID, radioID: radioID, publicKey: env.ble.publicKey, isActive: false).copy {
      $0.connectionMethods = [.bluetooth(peripheralUUID: env.deviceID, displayName: nil)]
      if keepsWifi { $0.connectionMethods.append(.wifi(host: "radio.test", port: 5123, displayName: nil)) }
    }
    try await env.manager.persistenceStore.saveDevice(seed)
    env.pairing.setPairedAccessories([ASAccessory(bluetoothIdentifier: env.deviceID, displayName: "Radio")])
    await env.ble.holdReplies(for: .getAutoAddConfig)
    let connect = Task { try await env.connect(.bluetooth) }
    do {
      try await waitUntil("actual unpublished preparation must await getAutoAddConfig") {
        await env.ble.sentData.contains { $0.first == CommandCode.getAutoAddConfig.rawValue }
      }
      #expect(env.manager.currentConnection == nil)
      env.pairing.simulateAccessoryRemoved(env.deviceID)
      let removalMetadata = env.manager.metadataCommitTask
      await env.ble.releaseReplies(for: .getAutoAddConfig)
      _ = await connect.result
      await removalMetadata?.value
      await env.manager.retirementTask?.value
      await env.manager.metadataCommitTask?.value
      let saved = try #require(try await env.manager.persistenceStore.fetchDevice(publicKey: seed.publicKey))
      #expect(saved.radioID == radioID)
      #expect(saved.bluetoothPeripheralID == nil, "late registration must not restore removed Bluetooth")
      #expect((saved.wifiConnectionMethod != nil) == keepsWifi)
      #expect(!saved.isActive)
      let activeID = try await env.manager.persistenceStore.fetchActiveDevice()?.id
      let published = env.manager.currentConnection != nil
      #expect(activeID == nil)
      #expect(!published, "removed unpublished target must never publish")
      #expect(env.manager.lastConnectedDeviceID == nil)
      #expect(env.manager.desiredRequest?.target == .disconnected)
    } catch {
      await env.ble.releaseAllGates()
      connect.cancel()
      _ = await connect.result
      await env.close()
      throw error
    }
    await env.close()
  }

  @Test
  func `accessory removal keeps installed wifi and its radio partition`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    do {
      let seed = DeviceDTO.testDevice(id: env.deviceID, radioID: UUID(), publicKey: env.ble.publicKey, isActive: false).copy {
        $0.connectionMethods = [.bluetooth(peripheralUUID: env.deviceID, displayName: nil), .wifi(host: "radio.test", port: 5123, displayName: nil)]
      }
      try await env.manager.persistenceStore.saveDevice(seed)
      env.pairing.setPairedAccessories([ASAccessory(bluetoothIdentifier: env.deviceID, displayName: "Radio")])
      _ = try await env.connect(.wifi)
      let old = try #require(env.manager.currentConnection)
      env.pairing.simulateAccessoryRemoved(env.deviceID)
      await env.manager.metadataCommitTask?.value
      let saved = try #require(try await env.manager.persistenceStore.fetchDevice(publicKey: seed.publicKey))
      #expect(saved.radioID == seed.radioID)
      #expect(saved.bluetoothPeripheralID == nil)
      #expect(saved.wifiConnectionMethod != nil)
      #expect(saved.isActive)
      #expect(env.manager.currentConnection?.generation == old.generation)
      #expect(env.manager.currentTransportType == .wifi)
      let probed = try await env.manager.session?.getTime() != nil
      #expect(probed)
    } catch { await env.close(); throw error }
    await env.close()
  }

  @Test(arguments: [TransportType.bluetooth, .wifi], [false, true])
  func `foreground recovers after actual resync exhaustion`(type: TransportType, explicitDisconnect: Bool) async throws {
    let clock = TestClock()
    let env = try ConnectionManagerRadioTestHarness(clock: clock)
    let radio = type == .bluetooth ? env.ble : env.wifi
    do {
      await radio.rejectNext(.getContacts, count: 4)
      _ = try await env.connect(type)
      let first = try #require(env.manager.currentConnection)
      try await waitUntilClockAdvances(clock, timeout: .seconds(2), "actual initial failure and three resync failures must retire the Link") {
        env.manager.connectionState == .disconnected && env.manager.connectionRequestID != first.requestID
      }
      await env.manager.retirementTask?.value
      await env.manager.metadataCommitTask?.value
      #expect(env.manager.connectionIntent.wantsConnection)
      #expect(await radio.sentData.filter { $0.first == CommandCode.getContacts.rawValue }.count == 4)
      let attempts = await radio.connectCount
      if explicitDisconnect { _ = await env.manager.disconnect(reason: .userInitiated) }
      await env.manager.checkWiFiConnectionHealth()
      await env.manager.appDidBecomeActive()
      if explicitDisconnect {
        #expect(await radio.connectCount == attempts)
        #expect(env.manager.currentConnection == nil)
        #expect(env.manager.connectionIntent.isUserDisconnected)
      } else {
        let connectCount = await radio.connectCount
        let handshakeCount = await radio.sentData.filter { $0.first == CommandCode.appStart.rawValue }.count
        #expect(connectCount == attempts + 1, "foreground must recover the retained admitted target")
        #expect(handshakeCount == 2)
        #expect(env.manager.connectionState == .ready)
        #expect(env.manager.currentConnection?.device.radioID == first.device.radioID)
        let probed = try await env.manager.session?.getTime() != nil
        #expect(probed, "recovered production session must exchange frames")
      }
    } catch { await env.close(); throw error }
    await env.close()
  }
}
