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
}
