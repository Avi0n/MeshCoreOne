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
            selfInfo: Self.makeSelfInfo(),
            capabilities: Self.testCapabilities,
            autoAddConfig: AutoAddConfig(bitmask: 0),
            existingDevice: existingDevice
        )

        #expect(device.id == newBLEUUID)
        #expect(device.radioID == existingRadioID)
    }

    @Test("createDevice generates new radioID when no existing device")
    @MainActor
    func createDeviceGeneratesNewRadioID() throws {
        let (cm, _) = try ConnectionManager.createForTesting()
        let bleUUID = UUID()

        let device = cm.createDevice(
            deviceID: bleUUID,
            selfInfo: Self.makeSelfInfo(),
            capabilities: Self.testCapabilities,
            autoAddConfig: AutoAddConfig(bitmask: 0)
        )

        #expect(device.id == bleUUID)
        #expect(device.radioID != bleUUID)
    }
}
