import Testing
import Foundation
@testable import MC1Services

@Suite("Known-region persistence methods")
struct KnownRegionTests {

    private func createTestStore() async throws -> PersistenceStore {
        let container = try PersistenceStore.createContainer(inMemory: true)
        return PersistenceStore(modelContainer: container)
    }

    @Test("addDeviceKnownRegion finds device by radioID, not by id")
    func addRegionByRadioID() async throws {
        let store = try await createTestStore()

        let bleUUID = UUID()
        let radioID = UUID()
        let device = DeviceDTO.testDevice(id: bleUUID, radioID: radioID)
        try await store.saveDevice(device)

        try await store.addDeviceKnownRegion(radioID: radioID, region: "US915")

        let fetched = try await store.fetchDevice(id: bleUUID)
        #expect(fetched?.knownRegions.contains("US915") == true)
    }

    @Test("addDeviceKnownRegion does not duplicate existing region")
    func addRegionSkipsDuplicate() async throws {
        let store = try await createTestStore()

        let bleUUID = UUID()
        let radioID = UUID()
        var device = DeviceDTO.testDevice(id: bleUUID, radioID: radioID)
        device.knownRegions = ["US915"]
        try await store.saveDevice(device)

        try await store.addDeviceKnownRegion(radioID: radioID, region: "US915")

        let fetched = try await store.fetchDevice(id: bleUUID)
        #expect(fetched?.knownRegions == ["US915"])
    }

    @Test("addDeviceKnownRegion throws when device not found")
    func addRegionThrowsForMissingDevice() async throws {
        let store = try await createTestStore()

        await #expect(throws: PersistenceStoreError.self) {
            try await store.addDeviceKnownRegion(radioID: UUID(), region: "EU868")
        }
    }

    @Test("removeDeviceKnownRegion clears region and channel regionScope")
    func removeRegionClearsRegionScope() async throws {
        let store = try await createTestStore()

        let bleUUID = UUID()
        let radioID = UUID()
        var device = DeviceDTO.testDevice(id: bleUUID, radioID: radioID)
        device.knownRegions = ["US915", "EU868"]
        try await store.saveDevice(device)

        let channelDTO = ChannelDTO(
            id: UUID(),
            radioID: radioID,
            index: 1,
            name: "Test",
            secret: Data(repeating: 0, count: 16),
            isEnabled: true,
            lastMessageDate: nil,
            unreadCount: 0,
            floodScope: .region("US915")
        )
        try await store.saveChannel(channelDTO)

        try await store.removeDeviceKnownRegion(radioID: radioID, region: "US915")

        let fetchedDevice = try await store.fetchDevice(id: bleUUID)
        #expect(fetchedDevice?.knownRegions.contains("US915") == false)
        #expect(fetchedDevice?.knownRegions.contains("EU868") == true)

        let channels = try await store.fetchChannels(radioID: radioID)
        let channel = channels.first
        #expect(channel?.floodScope == .inherit)
    }

    @Test("removeDeviceKnownRegion leaves unrelated channel regionScope intact")
    func removeRegionLeavesUnrelatedChannels() async throws {
        let store = try await createTestStore()

        let bleUUID = UUID()
        let radioID = UUID()
        var device = DeviceDTO.testDevice(id: bleUUID, radioID: radioID)
        device.knownRegions = ["US915", "EU868"]
        try await store.saveDevice(device)

        let channel1 = ChannelDTO(
            id: UUID(),
            radioID: radioID,
            index: 1,
            name: "Chan1",
            secret: Data(repeating: 0, count: 16),
            isEnabled: true,
            lastMessageDate: nil,
            unreadCount: 0,
            floodScope: .region("US915")
        )
        let channel2 = ChannelDTO(
            id: UUID(),
            radioID: radioID,
            index: 2,
            name: "Chan2",
            secret: Data(repeating: 0, count: 16),
            isEnabled: true,
            lastMessageDate: nil,
            unreadCount: 0,
            floodScope: .region("EU868")
        )
        try await store.saveChannel(channel1)
        try await store.saveChannel(channel2)

        try await store.removeDeviceKnownRegion(radioID: radioID, region: "US915")

        let channels = try await store.fetchChannels(radioID: radioID)
        let scopes = channels.sorted(by: { $0.index < $1.index }).map(\.floodScope)
        #expect(scopes == [.inherit, .region("EU868")])
    }

    @Test("removeDeviceKnownRegion throws when device not found")
    func removeRegionThrowsForMissingDevice() async throws {
        let store = try await createTestStore()

        await #expect(throws: PersistenceStoreError.self) {
            try await store.removeDeviceKnownRegion(radioID: UUID(), region: "US915")
        }
    }

    /// knownRegions is app-only state the radio never reports, owned solely by
    /// the targeted add/remove methods. A full saveDevice carrying a stale or
    /// empty list (an in-flight updateDevice* Task, or a connect-time rebuild
    /// whose snapshot predates the add) must not overwrite it, or the user's
    /// discovered regions silently vanish.
    @Test("Full saveDevice does not clobber regions added via the targeted path")
    func staleSaveDeviceDoesNotClobberKnownRegions() async throws {
        let store = try await createTestStore()

        let bleUUID = UUID()
        let radioID = UUID()
        // The snapshot a long-lived caller holds, captured before any region
        // was added; its knownRegions is still empty.
        let staleSnapshot = DeviceDTO.testDevice(id: bleUUID, radioID: radioID)
        try await store.saveDevice(staleSnapshot)

        // Discovery adds regions through the targeted, list-owning path.
        try await store.addDeviceKnownRegion(radioID: radioID, region: "US915")
        try await store.addDeviceKnownRegion(radioID: radioID, region: "EU868")

        // The stale snapshot is written back as a full overwrite. The regions
        // added above must survive.
        try await store.saveDevice(staleSnapshot)

        let fetched = try await store.fetchDevice(id: bleUUID)
        #expect(fetched?.knownRegions == ["US915", "EU868"])
    }
}
