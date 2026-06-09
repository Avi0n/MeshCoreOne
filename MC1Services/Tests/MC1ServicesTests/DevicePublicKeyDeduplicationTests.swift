import Foundation
import SwiftData
import Testing
import MeshCore
@testable import MC1Services

@Suite("Device publicKey deduplication")
struct DevicePublicKeyDeduplicationTests {

    // MARK: - Test Helpers

    private func createTestStore() async throws -> PersistenceStore {
        let container = try PersistenceStore.createContainer(inMemory: true)
        return PersistenceStore(modelContainer: container)
    }

    private static let testPublicKey = Data(repeating: 0xAB, count: 32)

    private static func makeSelfInfo(publicKey: Data = testPublicKey) -> SelfInfo {
        SelfInfo(
            advertisementType: 0,
            txPower: 20,
            maxTxPower: 20,
            publicKey: publicKey,
            latitude: 0,
            longitude: 0,
            multiAcks: 2,
            advertisementLocationPolicy: 0,
            telemetryModeEnvironment: 0,
            telemetryModeLocation: 0,
            telemetryModeBase: 2,
            manualAddContacts: false,
            radioFrequency: 915.0,
            radioBandwidth: 250.0,
            radioSpreadingFactor: 10,
            radioCodingRate: 5,
            name: "TestNode"
        )
    }

    private static let testCapabilities = DeviceCapabilities(
        firmwareVersion: 9,
        maxContacts: 100,
        maxChannels: 8,
        blePin: 0,
        firmwareBuild: "01 Jan 2025",
        model: "T-Deck",
        version: "v1.13.0"
    )

    // MARK: - fetchDevice(publicKey:)

    @Test("fetchDevice(publicKey:) returns matching device")
    func fetchByPublicKeyHit() async throws {
        let store = try await createTestStore()
        let device = DeviceDTO.testDevice(publicKey: Self.testPublicKey)
        try await store.saveDevice(device)

        let fetched = try await store.fetchDevice(publicKey: Self.testPublicKey)
        #expect(fetched != nil)
        #expect(fetched?.id == device.id)
        #expect(fetched?.publicKey == Self.testPublicKey)
    }

    @Test("fetchDevice(publicKey:) returns nil for unknown key")
    func fetchByPublicKeyMiss() async throws {
        let store = try await createTestStore()
        let device = DeviceDTO.testDevice(publicKey: Data(repeating: 0x01, count: 32))
        try await store.saveDevice(device)

        let unknownKey = Data(repeating: 0xFF, count: 32)
        let fetched = try await store.fetchDevice(publicKey: unknownKey)
        #expect(fetched == nil)
    }

    // MARK: - radioID preservation via createDevice

    @Test("createDevice preserves radioID from existing device")
    @MainActor
    func createDevicePreservesRadioID() throws {
        let existingRadioID = UUID()
        let existingDevice = DeviceDTO.testDevice(
            radioID: existingRadioID,
            publicKey: Self.testPublicKey
        )

        let (cm, _) = try ConnectionManager.createForTesting()
        let newBLEUUID = UUID()

        let device = cm.createDevice(
            deviceID: newBLEUUID,
            radioID: existingRadioID,
            selfInfo: Self.makeSelfInfo(),
            capabilities: Self.testCapabilities,
            autoAddConfig: AutoAddConfig(bitmask: 0),
            existingDevice: existingDevice
        )

        #expect(device.id == newBLEUUID)
        #expect(device.radioID == existingRadioID)
    }

    @Test("createDevice uses the provided radioID for new pairings")
    @MainActor
    func createDeviceUsesProvidedRadioID() throws {
        let (cm, _) = try ConnectionManager.createForTesting()
        let bleUUID = UUID()
        let freshRadioID = UUID()

        let device = cm.createDevice(
            deviceID: bleUUID,
            radioID: freshRadioID,
            selfInfo: Self.makeSelfInfo(),
            capabilities: Self.testCapabilities,
            autoAddConfig: AutoAddConfig(bitmask: 0)
        )

        #expect(device.id == bleUUID)
        #expect(device.radioID == freshRadioID)
    }

    // MARK: - Bluetooth connection method persistence

    /// The BLE connect ceremony passes a `.bluetooth` method so the saved row is
    /// reachable on macOS, where there is no AccessorySetupKit registry to validate
    /// against and `DeviceSelectionFilter` treats the method itself as the signal.
    @Test("createDevice persists the Bluetooth method supplied by the BLE connect path")
    @MainActor
    func createDevicePersistsBluetoothMethod() throws {
        let (cm, _) = try ConnectionManager.createForTesting()
        let bleUUID = UUID()

        let device = cm.createDevice(
            deviceID: bleUUID,
            radioID: UUID(),
            selfInfo: Self.makeSelfInfo(),
            capabilities: Self.testCapabilities,
            autoAddConfig: AutoAddConfig(bitmask: 0),
            connectionMethods: [.bluetooth(peripheralUUID: bleUUID, displayName: nil)]
        )

        #expect(device.connectionMethods.contains { $0.isBluetooth })
    }

    /// A radio reachable over both transports must keep its WiFi method when it
    /// reconnects over BLE; the merge replaces by transport type rather than
    /// discarding the other transport.
    @Test("createDevice merges a Bluetooth method with an existing WiFi method")
    @MainActor
    func createDeviceMergesBluetoothWithExistingWiFi() throws {
        let (cm, _) = try ConnectionManager.createForTesting()
        let bleUUID = UUID()
        let existingRadioID = UUID()
        let existingDevice = DeviceDTO.testDevice(
            radioID: existingRadioID,
            publicKey: Self.testPublicKey
        ).copy {
            $0.connectionMethods = [.wifi(host: "10.0.0.2", port: 5000, displayName: nil)]
        }

        let device = cm.createDevice(
            deviceID: bleUUID,
            radioID: existingRadioID,
            selfInfo: Self.makeSelfInfo(),
            capabilities: Self.testCapabilities,
            autoAddConfig: AutoAddConfig(bitmask: 0),
            existingDevice: existingDevice,
            connectionMethods: [.bluetooth(peripheralUUID: bleUUID, displayName: nil)]
        )

        // Exactly one of each transport: the Bluetooth method is added without discarding
        // the WiFi method, and neither transport is duplicated.
        #expect(device.connectionMethods.filter(\.isWiFi).count == 1)
        #expect(device.connectionMethods.filter(\.isBluetooth).count == 1)
    }

    /// Reconnecting over BLE must replace the prior Bluetooth method, not append a second one.
    /// macOS reachability and the live connect handle both key off the stored peripheralUUID, so
    /// a stale UUID accumulating alongside the fresh one would strand or mis-route the device.
    /// This pins the merge loop's `removeAll { $0.isBluetooth }` so dropping it would fail here.
    @Test("createDevice replaces a stale Bluetooth peripheral UUID instead of accumulating")
    @MainActor
    func createDeviceReplacesStaleBluetoothMethod() throws {
        let (cm, _) = try ConnectionManager.createForTesting()
        let existingRadioID = UUID()
        let staleUUID = UUID()
        let existingDevice = DeviceDTO.testDevice(
            radioID: existingRadioID,
            publicKey: Self.testPublicKey
        ).copy {
            $0.connectionMethods = [.bluetooth(peripheralUUID: staleUUID, displayName: "Old")]
        }

        let freshUUID = UUID()
        let device = cm.createDevice(
            deviceID: freshUUID,
            radioID: existingRadioID,
            selfInfo: Self.makeSelfInfo(),
            capabilities: Self.testCapabilities,
            autoAddConfig: AutoAddConfig(bitmask: 0),
            existingDevice: existingDevice,
            connectionMethods: [.bluetooth(peripheralUUID: freshUUID, displayName: nil)]
        )

        let bluetoothMethods = device.connectionMethods.filter(\.isBluetooth)
        #expect(bluetoothMethods.count == 1)
        guard case .bluetooth(let uuid, _) = bluetoothMethods.first else {
            Issue.record("Expected exactly one surviving Bluetooth method")
            return
        }
        #expect(uuid == freshUUID)
    }
}
