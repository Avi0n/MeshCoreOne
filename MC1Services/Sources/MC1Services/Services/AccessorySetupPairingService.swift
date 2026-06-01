#if canImport(UIKit)
import AccessorySetupKit
#endif
import Foundation
import os

/// iOS `DevicePairingService` backed by AccessorySetupKit.
///
/// Translates the UUID-based `DevicePairingService` surface onto AccessorySetupKit's
/// `ASAccessory`-based API, and republishes AccessorySetupKit delegate events through the
/// platform-neutral `DevicePairingDelegate`. `ConnectionManager` talks only to the protocol;
/// this adapter is the one place that knows AccessorySetupKit exists.
@MainActor
public final class AccessorySetupPairingService: DevicePairingService {
    private let accessorySetupKit: any AccessorySetupKitServicing
    private let logger = PersistentLogger(subsystem: "com.mc1", category: "AccessorySetupPairing")
    public weak var delegate: (any DevicePairingDelegate)?

    public init(accessorySetupKit: any AccessorySetupKitServicing) {
        self.accessorySetupKit = accessorySetupKit
        self.accessorySetupKit.delegate = self
    }

    public var isSessionActive: Bool { accessorySetupKit.isSessionActive }
    public var registeredDeviceCount: Int { accessorySetupKit.pairedAccessories.count }
    public var hasSystemPairingRegistry: Bool { true }
    public var supportsSystemRename: Bool { true }

    public func activate() async throws {
        try await accessorySetupKit.activateSession()
    }

    public func discoverDevice() async throws -> UUID {
        do {
            return try await accessorySetupKit.showPicker()
        } catch AccessorySetupKitError.pickerDismissed {
            throw DevicePairingError.cancelled
        } catch AccessorySetupKitError.pickerAlreadyActive {
            throw DevicePairingError.alreadyInProgress
        }
    }

    public func isDeviceConnectable(_ id: UUID) -> Bool {
        accessorySetupKit.accessory(for: id) != nil
    }

    public func registeredDeviceInfos() -> [(id: UUID, name: String)] {
        accessorySetupKit.pairedAccessories.compactMap { accessory in
            guard let id = accessory.bluetoothIdentifier else { return nil }
            return (id: id, name: accessory.displayName)
        }
    }

    public func removeDevice(_ id: UUID) async throws {
        guard let accessory = accessorySetupKit.accessory(for: id) else { return }
        try await accessorySetupKit.removeAccessory(accessory)
    }

    public func renameDevice(_ id: UUID) async throws {
        guard let accessory = accessorySetupKit.accessory(for: id) else { return }
        try await accessorySetupKit.renameAccessory(accessory)
    }

    public func clearStaleRegistrations() async {
        for accessory in accessorySetupKit.pairedAccessories {
            do {
                try await accessorySetupKit.removeAccessory(accessory)
            } catch {
                logger.warning("Failed to remove stale accessory \(accessory.displayName): \(error.localizedDescription)")
            }
        }
    }
}

extension AccessorySetupPairingService: AccessorySetupKitServiceDelegate {
    public func accessorySetupKitService(
        _ service: AccessorySetupKitService,
        didRemoveAccessoryWithID bluetoothID: UUID
    ) {
        delegate?.devicePairing(self, didRemoveDeviceWithID: bluetoothID)
    }

    public func accessorySetupKitService(
        _ service: AccessorySetupKitService,
        didFailPairingForAccessoryWithID bluetoothID: UUID
    ) {
        delegate?.devicePairing(self, didFailPairingForDeviceWithID: bluetoothID)
    }
}
