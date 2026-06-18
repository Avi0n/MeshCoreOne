import Foundation

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
}
