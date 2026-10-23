#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation
@testable import MC1Services
import Testing

/// `pairNewDevice` never removes ASK associations. Unsaved accessories are
/// queried by `systemAccessoriesMissingDeviceRecord` and removed only after user confirmation.
@Suite("Pairing defers auth-failure removal and does not sweep ASK accessories")
@MainActor
struct PairingStrandedAssociationTests {
  @Test
  func `fresh-pair authentication failure removes no association and reports connectionFailed`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let mockASK = env.accessorySetupKit
    let deviceID = UUID()

    // A saved ASK association must survive authenticationFailed; pairNewDevice never removes it.
    let store = manager.persistenceStore
    try await store.saveDevice(DeviceDTO.testDevice(id: deviceID))
    mockASK.setPairedAccessories([ASAccessory(bluetoothIdentifier: deviceID, displayName: "test")])

    mockASK.setPickerResult(.success(deviceID))
    // Reaching the transport requires the registry check to be skipped, as on the
    // macOS shape; the transport then fails auth the way a wrong PIN does.
    mockASK.isSessionActive = false
    await env.transport.setConnectError(BLEError.authenticationFailed)

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )
    manager.otherAppWaitStrategyOverride = { _ in false }

    try await #expect {
      try await manager.pairNewDevice()
    } throws: { error in
      guard let pairingError = error as? PairingError else { return false }
      return pairingError.isAuthenticationFailure && pairingError.deviceID == deviceID
    }

    #expect(mockASK.removeAccessoryCallCount == 0)
  }

  @Test
  func `transient connect failure during pairing removes no association`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let mockASK = env.accessorySetupKit
    let deviceID = UUID()

    mockASK.setPickerResult(.success(deviceID))
    mockASK.isSessionActive = false
    await env.transport.setConnectError(BLEError.connectionFailed("out of range"))

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )
    manager.otherAppWaitStrategyOverride = { _ in false }

    try await #expect {
      try await manager.pairNewDevice()
    } throws: { error in
      guard let pairingError = error as? PairingError else { return false }
      return !pairingError.isAuthenticationFailure && pairingError.deviceID == deviceID
    }

    #expect(mockASK.removeAccessoryCallCount == 0)
  }

  @Test
  func `pairNewDevice does not remove an unsaved ASK accessory`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let mockASK = env.accessorySetupKit
    let strandedID = UUID()

    mockASK.setPairedAccessories([ASAccessory(bluetoothIdentifier: strandedID, displayName: "stranded")])
    mockASK.setPickerResult(.failure(AccessorySetupKitError.pickerDismissed))

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    await #expect(throws: DevicePairingError.self) {
      try await manager.pairNewDevice()
    }

    #expect(mockASK.removeAccessoryCallCount == 0)
    #expect(mockASK.showPickerCallCount == 1)
  }

  @Test
  func `systemAccessoriesMissingDeviceRecord omits saved radios and reports unsaved ASK accessories`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let mockASK = env.accessorySetupKit
    let savedID = UUID()
    let unsavedID = UUID()

    try await manager.persistenceStore.saveDevice(DeviceDTO.testDevice(id: savedID))
    mockASK.setPairedAccessories([
      ASAccessory(bluetoothIdentifier: savedID, displayName: "saved"),
      ASAccessory(bluetoothIdentifier: unsavedID, displayName: "unsaved")
    ])

    let pending = try await manager.systemAccessoriesMissingDeviceRecord()

    #expect(pending.map(\.id) == [unsavedID])
    #expect(pending.first?.name == "unsaved")
    #expect(mockASK.removeAccessoryCallCount == 0)
    #expect(mockASK.showPickerCallCount == 0)
  }

  @Test
  func `systemAccessoriesMissingDeviceRecord skips an id when fetchDevice throws`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let mockASK = env.accessorySetupKit
    let savedID = UUID()

    try await manager.persistenceStore.saveDevice(DeviceDTO.testDevice(id: savedID))
    mockASK.setPairedAccessories([ASAccessory(bluetoothIdentifier: savedID, displayName: "saved")])
    await manager.persistenceStore.setFetchDeviceByIDFaultInjection {
      throw PersistenceStoreError.fetchFailed("test")
    }

    let pending = try await manager.systemAccessoriesMissingDeviceRecord()

    #expect(pending.isEmpty)
    try await manager.removeSystemAccessoriesMissingDeviceRecord([savedID])
    #expect(mockASK.removeAccessoryCallCount == 0)

    await manager.persistenceStore.setFetchDeviceByIDFaultInjection(nil)
  }

  @Test
  func `systemAccessoriesMissingDeviceRecord activates before enumerating`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let mockASK = env.accessorySetupKit
    let unsavedID = UUID()

    mockASK.isSessionActive = false
    mockASK.setPairedAccessories([ASAccessory(bluetoothIdentifier: unsavedID, displayName: "B")])

    let pending = try await manager.systemAccessoriesMissingDeviceRecord()

    #expect(mockASK.activateSessionCallCount >= 1)
    #expect(pending.map(\.id) == [unsavedID])
    #expect(mockASK.showPickerCallCount == 0)
  }

  @Test
  func `removeSystemAccessoriesMissingDeviceRecord removes only the requested unsaved ids`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let mockASK = env.accessorySetupKit
    let savedID = UUID()
    let unsavedID = UUID()

    try await manager.persistenceStore.saveDevice(DeviceDTO.testDevice(id: savedID))
    mockASK.setPairedAccessories([
      ASAccessory(bluetoothIdentifier: savedID, displayName: "saved"),
      ASAccessory(bluetoothIdentifier: unsavedID, displayName: "unsaved")
    ])

    try await manager.removeSystemAccessoriesMissingDeviceRecord([savedID, unsavedID])

    #expect(mockASK.removeAccessoryCallCount == 1)
    #expect(mockASK.lastRemovedDeviceID == unsavedID)
    #expect(mockASK.pairedAccessories.compactMap(\.bluetoothIdentifier) == [savedID])
  }

  #if canImport(UIKit)
    @Test
    func `removeDevice maps ASError.userCancelled to DevicePairingError.cancelled`() async throws {
      let env = try ConnectionManager.createForPairingTesting()
      defer { env.cleanup() }
      let mockASK = env.accessorySetupKit
      let unsavedID = UUID()

      mockASK.setPairedAccessories([ASAccessory(bluetoothIdentifier: unsavedID, displayName: "unsaved")])
      mockASK.removeAccessoryError = NSError(
        domain: ASError.errorDomain,
        code: ASError.Code.userCancelled.rawValue
      )

      let pairing = AccessorySetupPairingService(accessorySetupKit: mockASK)
      await #expect(throws: DevicePairingError.self) {
        try await pairing.removeDevice(unsavedID)
      }
      #expect(mockASK.accessory(for: unsavedID) != nil)
    }

    @Test
    func `discoverDevice maps pickerRestricted to DevicePairingError.pickerUnavailable`() async throws {
      let env = try ConnectionManager.createForPairingTesting()
      defer { env.cleanup() }
      let mockASK = env.accessorySetupKit
      mockASK.setPickerResult(.failure(AccessorySetupKitError.pickerRestricted))

      let pairing = AccessorySetupPairingService(accessorySetupKit: mockASK)
      try await pairing.activate()
      try await #expect {
        try await pairing.discoverDevice()
      } throws: { error in
        guard let pairingError = error as? DevicePairingError else { return false }
        if case .pickerUnavailable = pairingError { return true }
        return false
      }
    }
  #endif

  @Test
  func `systemAccessoriesMissingDeviceRecord omits a connected id with no Device row`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let mockASK = env.accessorySetupKit
    let liveID = UUID()

    mockASK.setPairedAccessories([ASAccessory(bluetoothIdentifier: liveID, displayName: "live")])
    manager.setTestState(connectedDevice: DeviceDTO.testDevice(id: liveID))

    let pending = try await manager.systemAccessoriesMissingDeviceRecord()

    #expect(pending.isEmpty)
    try await manager.removeSystemAccessoriesMissingDeviceRecord([liveID])
    #expect(mockASK.removeAccessoryCallCount == 0)
  }

  @Test
  func `systemAccessoriesMissingDeviceRecord omits an in-flight attempt id with no Device row`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let mockASK = env.accessorySetupKit
    let attemptID = UUID()

    mockASK.setPairedAccessories([ASAccessory(bluetoothIdentifier: attemptID, displayName: "attempt")])
    manager.setTestState(connectingDeviceID: attemptID)

    let pending = try await manager.systemAccessoriesMissingDeviceRecord()

    #expect(pending.isEmpty)
    try await manager.removeSystemAccessoriesMissingDeviceRecord([attemptID])
    #expect(mockASK.removeAccessoryCallCount == 0)
  }

  /// iOS can fire accessoryRemoved while ASK still lists the id. Ghosting that
  /// row would turn a still-authorized radio into a Set Up leftover.
  @Test
  func `settings-removal keeps the Device row when ASK still lists the accessory`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let mockASK = env.accessorySetupKit
    let deviceID = UUID()
    let device = DeviceDTO.testDevice(id: deviceID)

    try await manager.persistenceStore.saveDevice(device)
    manager.persistConnection(deviceID: deviceID, radioID: device.radioID, deviceName: device.nodeName)
    mockASK.setPairedAccessories([
      ASAccessory(bluetoothIdentifier: deviceID, displayName: "Radio")
    ])

    manager.devicePairing(manager.pairing, didRemoveDeviceWithID: deviceID)
    try await waitUntil("settings-removal should clear last-connected") {
      manager.lastConnectedDeviceID == nil
    }

    #expect(try await manager.persistenceStore.fetchDevice(id: deviceID) != nil)
    let pending = try await manager.systemAccessoriesMissingDeviceRecord()
    #expect(pending.isEmpty)
  }

  @Test
  func `settings-removal ghosts the Device row when ASK no longer lists the accessory`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let deviceID = UUID()
    let device = DeviceDTO.testDevice(id: deviceID)

    try await manager.persistenceStore.saveDevice(device)
    manager.persistConnection(deviceID: deviceID, radioID: device.radioID, deviceName: device.nodeName)

    manager.devicePairing(manager.pairing, didRemoveDeviceWithID: deviceID)
    try await waitUntil("settings-removal should ghost the original Device id") {
      let fetched = try? await manager.persistenceStore.fetchDevice(id: deviceID)
      return fetched == nil
    }

    let pending = try await manager.systemAccessoriesMissingDeviceRecord()
    #expect(pending.isEmpty)
  }

  /// After ASK pairing, CoreBluetooth can already report the radio as
  /// system-connected. Pairing adopts that link and waits until settle returns.
  @Test
  func `pairNewDevice does not treat a just-paired system-connected radio as another app`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let mockASK = env.accessorySetupKit
    let deviceID = UUID()

    mockASK.setPickerResult(.success(deviceID))
    mockASK.isSessionActive = false
    await env.stateMachine.setStubbedIsDeviceConnectedToSystem(true)
    await env.stateMachine.setStubbedDidStartAdoptingSystemConnectedPeripheral(true)

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .none
    )

    var settleCalls = 0
    manager.pairingAdoptionSettleStrategyOverride = {
      settleCalls += 1
    }

    try await manager.pairNewDevice()

    #expect(settleCalls == 1)
    let adoptCalls = await env.stateMachine.startAdoptingSystemConnectedPeripheralCalls
    #expect(adoptCalls == [deviceID])
  }

  @Test
  func `connect after last-connected cleared does not throw other-app for an ASK-owned radio`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let mockASK = env.accessorySetupKit
    let deviceID = UUID()

    mockASK.setPairedAccessories([
      ASAccessory(bluetoothIdentifier: deviceID, displayName: "Radio")
    ])
    mockASK.isSessionActive = false
    await env.stateMachine.setStubbedIsDeviceConnectedToSystem(true)
    await env.stateMachine.setStubbedDidStartAdoptingSystemConnectedPeripheral(true)

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .none
    )

    try await manager.connect(to: deviceID, forceFullSync: true, forceReconnect: true)

    let adoptCalls = await env.stateMachine.startAdoptingSystemConnectedPeripheralCalls
    #expect(adoptCalls == [deviceID])
    #expect(manager.connectionState == .connecting)
  }

  @Test
  func `waitForPairingAdoptionToSettle times out while still connected`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager

    manager.setTestState(
      connectionState: .connected,
      connectionIntent: .wantsConnection()
    )
    manager.testPairingAdoptionSettleTimeout = .milliseconds(80)

    try await #expect {
      try await manager.waitForPairingAdoptionToSettle()
    } throws: { error in
      if case let BLEError.connectionFailed(detail) = error {
        return detail == ConnectionManager.pairingAdoptionTimedOutDetail
      }
      return false
    }
  }

  @Test
  func `connect still throws other-app for a system-connected radio this app does not own`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let deviceID = UUID()

    env.accessorySetupKit.isSessionActive = false
    await env.stateMachine.setStubbedIsDeviceConnectedToSystem(true)

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .none
    )

    try await #expect {
      try await manager.connect(to: deviceID, forceFullSync: true, forceReconnect: true)
    } throws: { error in
      if case BLEError.deviceConnectedToOtherApp = error { return true }
      return false
    }
  }

  private func dualDevice(id: UUID, peripheralID: UUID, radioID: UUID) -> DeviceDTO {
    DeviceDTO.testDevice(id: id, radioID: radioID).copy {
      $0.connectionMethods = [
        .bluetooth(peripheralUUID: peripheralID, displayName: nil),
        .wifi(host: "10.0.0.4", port: 5000, displayName: nil)
      ]
    }
  }

  @Test
  func `settings removal during a wifi session keeps the row and the session`() async throws {
    let suiteName = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { defaults.removePersistentDomain(forName: suiteName) }
    let env = try ConnectionManager.createForPairingTesting(defaults: defaults)
    defer { env.cleanup() }
    let manager = env.manager
    let peripheralID = UUID()
    let radioID = UUID()
    let device = dualDevice(id: peripheralID, peripheralID: peripheralID, radioID: radioID)

    try await manager.persistenceStore.saveDevice(device)
    manager.persistConnection(deviceID: peripheralID, radioID: radioID, deviceName: device.nodeName)
    await manager.recordBondVerification(deviceID: peripheralID)
    var clearedLastConnection = false
    manager.onLastConnectedDeviceCleared = { clearedLastConnection = true }
    manager.wifiTransport = WiFiTransport()
    manager.setTestState(
      connectionState: .ready,
      connectedDevice: device,
      currentTransportType: .wifi,
      connectionIntent: .wantsConnection()
    )

    manager.devicePairing(manager.pairing, didRemoveDeviceWithID: peripheralID)
    try await waitUntil("settings removal should strip Bluetooth and keep WiFi") {
      let row = try? await manager.persistenceStore.fetchDevice(id: peripheralID)
      return row?.connectionMethods == [.wifi(host: "10.0.0.4", port: 5000, displayName: nil)]
    }

    #expect(manager.connectionState == .ready)
    #expect(manager.wifiTransport != nil)
    #expect(manager.lastConnectedDeviceID == peripheralID)
    #expect(clearedLastConnection == false)
    #expect(manager.connectedDevice?.bluetoothPeripheralID == nil)
    #expect(manager.connectedDevice?.id == peripheralID)
    #expect(LastConnectionStore(defaults: defaults).bondVerificationDate(for: peripheralID) == nil)
    #expect(await env.stateMachine.recordedBondVerifications[peripheralID] == nil)
    let rows = try await manager.persistenceStore.fetchDevices()
    #expect(rows.map(\.id) == [peripheralID])
    #expect(rows.first?.radioID == radioID)
  }

  @Test
  func `settings removal during a bluetooth session disconnects and keeps wifi`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let peripheralID = UUID()
    let radioID = UUID()
    let device = dualDevice(id: peripheralID, peripheralID: peripheralID, radioID: radioID)

    try await manager.persistenceStore.saveDevice(device)
    manager.persistConnection(deviceID: peripheralID, radioID: radioID, deviceName: device.nodeName)
    manager.setTestState(
      connectionState: .connected,
      connectedDevice: device,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )
    let disconnectsBefore = await env.transport.disconnectInvocations

    manager.devicePairing(manager.pairing, didRemoveDeviceWithID: peripheralID)
    try await waitUntil("settings removal should disconnect the Bluetooth session") {
      manager.connectionState == .disconnected
        && manager.connectedDevice == nil
        && manager.currentTransportType == nil
    }

    let row = try await manager.persistenceStore.fetchDevice(id: peripheralID)
    #expect(row?.connectionMethods == [.wifi(host: "10.0.0.4", port: 5000, displayName: nil)])
    #expect(row?.radioID == radioID)
    #expect(manager.lastConnectedDeviceID == peripheralID)
    #expect(await env.transport.disconnectInvocations > disconnectsBefore)
  }

  @Test
  func `settings removal after a wifi give-up disconnects the bluetooth transport`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let peripheralID = UUID()
    let radioID = UUID()
    let device = dualDevice(id: peripheralID, peripheralID: peripheralID, radioID: radioID)
    try await manager.persistenceStore.saveDevice(device)

    manager.testWiFiMaxReconnectDuration = .seconds(-1)
    manager.setTestState(
      connectionState: .ready,
      currentTransportType: .wifi,
      connectionIntent: .wantsConnection()
    )
    manager.wifiTransport = WiFiTransport()
    await manager.checkWiFiConnectionHealth()
    try await waitUntil("WiFi reconnect timeout should release the transport") {
      manager.wifiTransport == nil && manager.currentTransportType == nil
    }

    let session = MeshCoreSession(transport: SimulatorMockTransport())
    let services = try await ServiceContainer.forTesting(session: session, radioID: radioID)
    manager.setTestState(
      connectionState: .connected,
      services: services,
      session: session,
      connectedDevice: device,
      connectionIntent: .wantsConnection()
    )
    let promoted = await manager.promoteToReady(
      syncResult: FullSyncResult(contacts: .clean, channels: .clean, messages: .clean),
      expectedServices: services,
      transportType: .bluetooth
    )
    #expect(promoted)
    #expect(manager.connectionState == .ready)
    #expect(manager.wifiTransport == nil)

    let disconnectsBefore = await env.transport.disconnectInvocations
    manager.devicePairing(manager.pairing, didRemoveDeviceWithID: peripheralID)
    try await waitUntil("settings removal should disconnect Bluetooth") {
      manager.connectionState == .disconnected
        && manager.currentTransportType == nil
    }

    #expect(await env.transport.disconnectInvocations > disconnectsBefore)
    #expect(manager.wifiTransport == nil)
  }

  @Test
  func `settings removal of a legacy derived id keeps that id and wifi`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let peripheralID = UUID()
    let derivedID = UUID()
    let radioID = UUID()
    let device = dualDevice(id: derivedID, peripheralID: peripheralID, radioID: radioID)
    try await manager.persistenceStore.saveDevice(device)

    manager.devicePairing(manager.pairing, didRemoveDeviceWithID: peripheralID)
    try await waitUntil("settings removal should strip Bluetooth from the derived-id row") {
      let row = try? await manager.persistenceStore.fetchDevice(id: derivedID)
      return row?.bluetoothPeripheralID == nil && row?.wifiConnectionMethod != nil
    }

    let rows = try await manager.persistenceStore.fetchDevices()
    #expect(rows.map(\.id) == [derivedID])
    #expect(rows.first?.radioID == radioID)
    #expect(rows.first?.connectionMethods.allSatisfy(\.isWiFi) == true)
  }

  @Test
  func `pairing failure keeps a dual row and deletes a bluetooth-only row`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let dualPeripheral = UUID()
    let dualRadioID = UUID()
    let bluetoothOnlyID = UUID()
    try await manager.persistenceStore.saveDevice(
      dualDevice(id: dualPeripheral, peripheralID: dualPeripheral, radioID: dualRadioID)
    )
    try await manager.persistenceStore.saveDevice(
      DeviceDTO.testDevice(id: bluetoothOnlyID).copy {
        $0.connectionMethods = [.bluetooth(peripheralUUID: bluetoothOnlyID, displayName: nil)]
      }
    )

    manager.devicePairing(manager.pairing, didFailPairingForDeviceWithID: dualPeripheral)
    try await waitUntil("pairing failure should strip Bluetooth from the dual row") {
      let row = try? await manager.persistenceStore.fetchDevice(id: dualPeripheral)
      return row?.bluetoothPeripheralID == nil
    }
    let dual = try await manager.persistenceStore.fetchDevice(id: dualPeripheral)
    #expect(dual?.radioID == dualRadioID)
    #expect(dual?.connectionMethods.allSatisfy(\.isWiFi) == true)

    manager.devicePairing(manager.pairing, didFailPairingForDeviceWithID: bluetoothOnlyID)
    try await waitUntil("pairing failure should delete the Bluetooth-only row") {
      let row = try? await manager.persistenceStore.fetchDevice(id: bluetoothOnlyID)
      return row == nil
    }
  }

  @Test
  func `an accessory saved under the wifi id rule is not a missing device record`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let peripheralID = UUID()
    let device = dualDevice(id: peripheralID, peripheralID: peripheralID, radioID: UUID())
    try await manager.persistenceStore.saveDevice(device)
    env.accessorySetupKit.setPairedAccessories([
      ASAccessory(bluetoothIdentifier: peripheralID, displayName: "Radio")
    ])

    let pending = try await manager.systemAccessoriesMissingDeviceRecord()

    #expect(pending.isEmpty)
  }
}
