import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("Error Localization Tests")
struct ErrorLocalizationTests {
  // MARK: - MeshCoreError Tests

  @Test
  func `MeshCoreError.timeout produces human-readable description`() {
    let error: MeshCoreError = .timeout
    #expect(error.localizedDescription == "The operation timed out. Please try again.")
  }

  @Test(arguments: [
    (UInt8(0x01), "Command not supported by device firmware."),
    (UInt8(0x02), "Item not found on device."),
    (UInt8(0x03), "Device storage is full."),
    (UInt8(0x04), "Device is in an invalid state for this operation."),
    (UInt8(0x05), "Device file system error."),
    (UInt8(0x06), "Invalid parameter sent to device."),
  ])
  func `MeshCoreError.deviceError maps known firmware codes`(code: UInt8, expected: String) {
    let error: MeshCoreError = .deviceError(code: code)
    #expect(error.localizedDescription == expected, "Code \(code) should produce: \(expected)")
  }

  @Test
  func `MeshCoreError.deviceError falls back for unknown codes`() {
    let error: MeshCoreError = .deviceError(code: 10)
    #expect(error.localizedDescription == "Device error (code 10).")
  }

  @Test
  func `MeshCoreError.deviceError handles code zero`() {
    let error: MeshCoreError = .deviceError(code: 0)
    #expect(error.localizedDescription == "Device error (code 0).")
  }

  @Test
  func `MeshCoreError bluetooth errors produce readable descriptions`() {
    #expect(MeshCoreError.bluetoothUnavailable.localizedDescription == "Bluetooth is not available on this device.")
    #expect(MeshCoreError.bluetoothUnauthorized.localizedDescription == "Bluetooth permission is required. Please enable it in Settings.")
    #expect(MeshCoreError.bluetoothPoweredOff.localizedDescription == "Bluetooth is turned off. Please enable Bluetooth to connect.")
  }

  @Test
  func `MeshCoreError.connectionLost includes underlying error when present`() {
    let underlying = NSError(domain: "test", code: 1, userInfo: [NSLocalizedDescriptionKey: "link dropped"])
    let error: MeshCoreError = .connectionLost(underlying: underlying)
    #expect(error.localizedDescription.contains("Connection to device was lost"))
    #expect(error.localizedDescription.contains("link dropped"))
  }

  @Test
  func `MeshCoreError.connectionLost without underlying error`() {
    let error: MeshCoreError = .connectionLost(underlying: nil)
    #expect(error.localizedDescription == "Connection to device was lost.")
  }

  @Test
  func `MeshCoreError.featureDisabled produces readable description`() {
    #expect(MeshCoreError.featureDisabled.localizedDescription == "This feature is disabled on the device.")
  }

  @Test
  func `MeshCoreError.sessionNotStarted produces readable description`() {
    #expect(MeshCoreError.sessionNotStarted.localizedDescription == "Session has not been started.")
  }

  // MARK: - ProtocolError Tests

  @Test(arguments: [
    ProtocolError.unsupportedCommand, .notFound, .tableFull,
    .badState, .fileIOError, .illegalArgument,
  ])
  func `ProtocolError cases produce non-empty, readable descriptions`(protocolError: ProtocolError) {
    let description = protocolError.localizedDescription
    #expect(!description.isEmpty, "ProtocolError.\(protocolError) should have a description")
    #expect(!description.contains("ProtocolError"), "Should not contain raw type name")
  }

  // MARK: - Service Error Session Pass-Through Tests

  @Test
  func `MessageServiceError.sessionError passes through MeshCoreError description`() {
    let meshError: MeshCoreError = .deviceError(code: 0x03)
    let serviceError: MessageServiceError = .sessionError(meshError)
    #expect(serviceError.localizedDescription == "Device storage is full.")
  }

  @Test
  func `ChannelServiceError.sessionError passes through MeshCoreError description`() {
    let meshError: MeshCoreError = .timeout
    let serviceError: ChannelServiceError = .sessionError(meshError)
    #expect(serviceError.localizedDescription == "The operation timed out. Please try again.")
  }

  @Test
  func `SettingsServiceError.sessionError passes through without prefix`() {
    let meshError: MeshCoreError = .notConnected
    let serviceError: SettingsServiceError = .sessionError(meshError)
    #expect(!serviceError.localizedDescription.contains("Session error:"))
    #expect(serviceError.localizedDescription == "Not connected to device.")
  }

  @Test
  func `SettingsServiceError.deviceGPSVerificationFailed is human-readable`() {
    let serviceError: SettingsServiceError = .deviceGPSVerificationFailed(
      expectedEnabled: false,
      actualEnabled: true
    )
    #expect(
      serviceError.localizedDescription ==
        "Device GPS setting was not saved. Expected 'Off' but device reports 'On'."
    )
  }

  @Test
  func `RemoteNodeError.sessionError passes through without prefix`() {
    let meshError: MeshCoreError = .bluetoothPoweredOff
    let serviceError: RemoteNodeError = .sessionError(meshError)
    #expect(!serviceError.localizedDescription.contains("Session error:"))
    #expect(serviceError.localizedDescription == "Bluetooth is turned off. Please enable Bluetooth to connect.")
  }

  // MARK: - Service Error Spot Checks

  @Test
  func `AdvertisementError.notConnected produces readable description`() {
    #expect(AdvertisementError.notConnected.localizedDescription == "Not connected to device.")
  }

  @Test
  func `RoomServerError.permissionDenied produces readable description`() {
    #expect(RoomServerError.permissionDenied.localizedDescription == "Permission denied.")
  }

  @Test
  func `BinaryProtocolError.timeout produces readable description`() {
    #expect(BinaryProtocolError.timeout.localizedDescription == "Request timed out.")
  }

  @Test
  func `SyncCoordinatorError.alreadySyncing produces readable description`() {
    #expect(SyncCoordinatorError.alreadySyncing.localizedDescription == "A sync is already in progress.")
  }

  @Test
  func `PersistenceStoreError.contactNotFound produces readable description`() {
    #expect(PersistenceStoreError.contactNotFound.localizedDescription == "Contact not found.")
  }
}
