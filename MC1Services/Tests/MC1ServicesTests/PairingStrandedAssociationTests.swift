import Foundation
@testable import MC1Services
import Testing

/// Fresh pairing registers a system association before the app-level GATT handshake
/// runs. When that handshake fails (a wrong PIN surfaces as `authenticationFailed`),
/// the connect arm no longer removes the association: removal stays on explicit user
/// intent so the system confirmation dialog never appears out of context. The stranded
/// association is instead swept at the start of the next pairing attempt, when the user
/// is actively pairing, and only when it maps to no saved device.
@Suite("Pairing defers auth-failure removal and heals stranded associations")
@MainActor
struct PairingStrandedAssociationTests {
  @Test
  func `fresh-pair authentication failure removes no association and reports connectionFailed`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let mockASK = env.accessorySetupKit
    let deviceID = UUID()

    // Register the association and persist a matching device row: the pre-picker
    // sweep treats it as a saved radio and leaves the accessory in place, so only a
    // reintroduced auth-arm cleanup could remove it. The count-zero assertion below
    // would then fail, guarding against that regression.
    let store = manager.createStandalonePersistenceStore()
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

    // The auth arm rethrows only; it never summons the system removal dialog.
    #expect(mockASK.removeAccessoryCallCount == 0)
  }

  /// A transient connect failure (radio briefly out of range) is likewise left for the
  /// explicit recovery path; the connect arm removes nothing on its own.
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
  func `pairing removes a stranded association with no device record before showing the picker`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let mockASK = env.accessorySetupKit
    let strandedID = UUID()

    mockASK.setPairedAccessories([ASAccessory(bluetoothIdentifier: strandedID, displayName: "stranded")])
    // Dismiss the picker so the assertion isolates the pre-picker sweep.
    mockASK.setPickerResult(.failure(AccessorySetupKitError.pickerDismissed))

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    await #expect(throws: DevicePairingError.self) {
      try await manager.pairNewDevice()
    }

    #expect(mockASK.removeAccessoryCallCount == 1)
    #expect(mockASK.lastRemovedDeviceID == strandedID)
  }

  @Test
  func `a stranded association whose removal is declined still lets pairing reach the picker`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let mockASK = env.accessorySetupKit
    let strandedID = UUID()

    mockASK.setPairedAccessories([ASAccessory(bluetoothIdentifier: strandedID, displayName: "stranded")])
    // The user declines the system removal confirmation, so the removal throws.
    mockASK.removeAccessoryError = AccessorySetupKitError.connectionFailed
    mockASK.setPickerResult(.failure(AccessorySetupKitError.pickerDismissed))

    manager.setTestState(
      connectionState: .disconnected,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    // A declined removal must not abort pairing: the flow still reaches the picker,
    // which here dismisses and surfaces cancellation as a DevicePairingError.
    await #expect(throws: DevicePairingError.self) {
      try await manager.pairNewDevice()
    }

    #expect(mockASK.removeAccessoryCallCount == 1)
  }

  @Test
  func `an association matching a saved device is never removed by the heal`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let mockASK = env.accessorySetupKit
    let savedID = UUID()

    let store = manager.createStandalonePersistenceStore()
    try await store.saveDevice(DeviceDTO.testDevice(id: savedID))

    mockASK.setPairedAccessories([ASAccessory(bluetoothIdentifier: savedID, displayName: "saved")])
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
  }
}
