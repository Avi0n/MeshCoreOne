import Foundation
import MeshCore

/// Store operations for device rows.
public protocol DevicePersisting: Actor {
  /// Fetch a device by ID
  func fetchDevice(id: UUID) async throws -> DeviceDTO?

  /// Fetch a device by radio ID
  func fetchDevice(radioID: UUID) async throws -> DeviceDTO?

  /// Update the lastContactSync timestamp for a device, used to track
  /// incremental contact sync progress
  func updateDeviceLastContactSync(radioID: UUID, timestamp: UInt32) async throws

  /// Save or update a device
  func saveDevice(_ dto: DeviceDTO) async throws

  func updateDeviceNodeName(radioID: UUID, name: String) async throws
  func updateDeviceCoordinates(radioID: UUID, latitude: Double, longitude: Double) async throws
  func updateDeviceRadio(
    radioID: UUID,
    frequency: UInt32,
    bandwidth: UInt32,
    spreadingFactor: UInt8,
    codingRate: UInt8,
    appliedRadioPresetID: String?
  ) async throws
  func updateDeviceTxPower(radioID: UUID, txPower: Int8) async throws
  func updateDeviceOtherParams(radioID: UUID, intent: OtherParamsIntent) async throws
  /// Copies one refresh onto the existing row, preserving Device ID, Radio ID, and local metadata.
  /// A missing row throws and is not inserted.
  func updateDeviceObservation(radioID: UUID, mapped: DeviceDTO) async throws
  func updateDeviceClientRepeat(radioID: UUID, enabled: Bool) async throws
  func updateDevicePathHashMode(radioID: UUID, mode: UInt8) async throws
  func updateDeviceAutoAdd(radioID: UUID, config: UInt8, maxHops: UInt8) async throws
  func updateDeviceDefaultFloodScopeName(radioID: UUID, name: String?) async throws
}
