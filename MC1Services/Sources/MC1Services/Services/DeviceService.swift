import Foundation
import MeshCore
import OSLog

// MARK: - Device Service Errors

public enum DeviceServiceError: Error, LocalizedError, Sendable {
    case deviceNotFound
    case persistenceFailed(String)

    public var errorDescription: String? {
        switch self {
        case .deviceNotFound:
            return "Device not found"
        case .persistenceFailed(let reason):
            return "Failed to save device settings: \(reason)"
        }
    }
}

// MARK: - Device Service

/// Service for managing device-level data and settings persistence.
/// Handles local device configuration that doesn't require MeshCore communication.
public actor DeviceService {
    private let dataStore: PersistenceStore
    private let logger = PersistentLogger(subsystem: "com.mc1", category: "DeviceService")

    /// Callback invoked when device data is successfully updated.
    /// Used to refresh ConnectionManager.connectedDevice for UI updates.
    private var onDeviceUpdated: (@Sendable (DeviceDTO) async -> Void)?

    public init(dataStore: PersistenceStore) {
        self.dataStore = dataStore
    }

    /// Sets the callback for device updates.
    public func setDeviceUpdateCallback(
        _ callback: @escaping @Sendable (DeviceDTO) async -> Void
    ) {
        onDeviceUpdated = callback
    }

    // MARK: - OCV Settings

    /// Update OCV settings for the connected device.
    ///
    /// - Parameters:
    ///   - deviceID: The UUID of the device to update
    ///   - preset: The OCV preset name (e.g., "liIon", "liPo", "custom")
    ///   - customArray: Custom OCV array as comma-separated string (required if preset is "custom")
    /// - Throws: DeviceServiceError if device not found or persistence fails
    public func updateOCVSettings(
        deviceID: UUID,
        preset: String,
        customArray: String?
    ) async throws {
        logger.info("Updating OCV settings for device \(deviceID): preset=\(preset)")

        // Fetch current device
        guard var device = try await dataStore.fetchDevice(id: deviceID) else {
            logger.error("Device not found: \(deviceID)")
            throw DeviceServiceError.deviceNotFound
        }

        // Update OCV fields
        device = device.copy {
            $0.ocvPreset = preset
            $0.customOCVArrayString = customArray
        }

        // Save to persistence
        do {
            try await dataStore.saveDevice(device)
            logger.info("OCV settings saved successfully")
        } catch {
            logger.error("Failed to save OCV settings: \(error.localizedDescription)")
            throw DeviceServiceError.persistenceFailed(error.localizedDescription)
        }

        // Notify callback for UI refresh
        await onDeviceUpdated?(device)
    }
}
