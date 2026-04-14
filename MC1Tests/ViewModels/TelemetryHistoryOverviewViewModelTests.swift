import Foundation
import MeshCore
import Testing

@testable import MC1
@testable import MC1Services

@Suite("TelemetryHistoryOverviewViewModel Tests")
@MainActor
struct TelemetryHistoryOverviewViewModelTests {

    private let testPublicKey = Data(repeating: 0xAB, count: 32)
    private let testDeviceID = UUID()

    private func createStore() async throws -> PersistenceStore {
        let container = try PersistenceStore.createContainer(inMemory: true)
        return PersistenceStore(modelContainer: container)
    }

    private func createContactDTO(ocvPreset: String? = nil) -> ContactDTO {
        ContactDTO(
            id: UUID(),
            radioID: testDeviceID,
            publicKey: testPublicKey,
            name: "Test Repeater",
            typeRawValue: ContactType.repeater.rawValue,
            flags: 0,
            outPathLength: 0,
            outPath: Data(),
            lastAdvertTimestamp: 0,
            latitude: 0,
            longitude: 0,
            lastModified: 0,
            nickname: nil,
            isBlocked: false,
            isMuted: false,
            isFavorite: false,
            lastMessageDate: nil,
            unreadCount: 0,
            ocvPreset: ocvPreset
        )
    }

    // MARK: - Loading

    @Test("loadData fetches snapshots from persistence store")
    func loadDataFetchesSnapshots() async throws {
        let store = try await createStore()

        _ = try await store.saveNodeStatusSnapshot(
            nodePublicKey: testPublicKey,
            batteryMillivolts: 3800, lastSNR: 8.0, lastRSSI: -90,
            noiseFloor: -120, uptimeSeconds: 3600, rxAirtimeSeconds: 100,
            packetsSent: 500, packetsReceived: 1000, receiveErrors: nil
        )
        _ = try await store.saveNodeStatusSnapshot(
            nodePublicKey: testPublicKey,
            batteryMillivolts: 3750, lastSNR: 7.5, lastRSSI: -92,
            noiseFloor: -118, uptimeSeconds: 7200, rxAirtimeSeconds: 200,
            packetsSent: 600, packetsReceived: 1100, receiveErrors: nil
        )

        let viewModel = TelemetryHistoryOverviewViewModel()
        await viewModel.loadData(
            dataStore: store, publicKey: testPublicKey, radioID: testDeviceID
        )

        #expect(viewModel.snapshots.count == 2)
    }

    @Test("loadData with no snapshots leaves empty array")
    func loadDataNoSnapshots() async throws {
        let store = try await createStore()

        let viewModel = TelemetryHistoryOverviewViewModel()
        await viewModel.loadData(
            dataStore: store, publicKey: testPublicKey, radioID: testDeviceID
        )

        #expect(viewModel.snapshots.isEmpty)
    }

    // MARK: - OCV Resolution

    @Test("loadData resolves OCV from contact preset")
    func loadDataResolvesOCVFromContact() async throws {
        let store = try await createStore()

        let contact = createContactDTO(ocvPreset: OCVPreset.liFePO4.rawValue)
        try await store.saveContact(contact)

        let viewModel = TelemetryHistoryOverviewViewModel()
        await viewModel.loadData(
            dataStore: store, publicKey: testPublicKey, radioID: testDeviceID
        )

        #expect(viewModel.ocvArray == OCVPreset.liFePO4.ocvArray)
    }

    @Test("loadData defaults to liIon when no contact found")
    func loadDataDefaultsToLiIon() async throws {
        let store = try await createStore()

        let viewModel = TelemetryHistoryOverviewViewModel()
        await viewModel.loadData(
            dataStore: store, publicKey: testPublicKey, radioID: testDeviceID
        )

        #expect(viewModel.ocvArray == OCVPreset.liIon.ocvArray)
    }

    // MARK: - Filtering

    @Test("filteredSnapshots returns all when timeRange is .all")
    func filteredSnapshotsAll() async throws {
        let store = try await createStore()

        _ = try await store.saveNodeStatusSnapshot(
            nodePublicKey: testPublicKey, batteryMillivolts: 3800,
            lastSNR: nil, lastRSSI: nil, noiseFloor: nil,
            uptimeSeconds: nil, rxAirtimeSeconds: nil,
            packetsSent: nil, packetsReceived: nil, receiveErrors: nil
        )

        let viewModel = TelemetryHistoryOverviewViewModel()
        await viewModel.loadData(
            dataStore: store, publicKey: testPublicKey, radioID: testDeviceID
        )
        viewModel.timeRange = .all

        #expect(viewModel.filteredSnapshots.count == 1)
    }

    @Test("filteredSnapshots excludes old snapshots for .week range")
    func filteredSnapshotsWeek() async throws {
        let store = try await createStore()

        // Save an old snapshot (30 days ago)
        let thirtyDaysAgo = Calendar.current.date(byAdding: .day, value: -30, to: .now)!
        _ = try await store.saveNodeStatusSnapshot(
            timestamp: thirtyDaysAgo,
            nodePublicKey: testPublicKey, batteryMillivolts: 3600,
            lastSNR: nil, lastRSSI: nil, noiseFloor: nil,
            uptimeSeconds: nil, rxAirtimeSeconds: nil,
            packetsSent: nil, packetsReceived: nil, receiveErrors: nil
        )

        // Save a recent snapshot
        _ = try await store.saveNodeStatusSnapshot(
            nodePublicKey: testPublicKey, batteryMillivolts: 3800,
            lastSNR: nil, lastRSSI: nil, noiseFloor: nil,
            uptimeSeconds: nil, rxAirtimeSeconds: nil,
            packetsSent: nil, packetsReceived: nil, receiveErrors: nil
        )

        let viewModel = TelemetryHistoryOverviewViewModel()
        await viewModel.loadData(
            dataStore: store, publicKey: testPublicKey, radioID: testDeviceID
        )
        viewModel.timeRange = .week

        #expect(viewModel.filteredSnapshots.count == 1)
        #expect(viewModel.filteredSnapshots.first?.batteryMillivolts == 3800)
    }

    // MARK: - Computed Properties

    @Test("hasSnapshots reflects snapshot count")
    func hasSnapshots() async throws {
        let viewModel = TelemetryHistoryOverviewViewModel()
        #expect(!viewModel.hasSnapshots)

        let store = try await createStore()
        _ = try await store.saveNodeStatusSnapshot(
            nodePublicKey: testPublicKey, batteryMillivolts: 3800,
            lastSNR: nil, lastRSSI: nil, noiseFloor: nil,
            uptimeSeconds: nil, rxAirtimeSeconds: nil,
            packetsSent: nil, packetsReceived: nil, receiveErrors: nil
        )

        await viewModel.loadData(
            dataStore: store, publicKey: testPublicKey, radioID: testDeviceID
        )
        #expect(viewModel.hasSnapshots)
    }

    @Test("hasTelemetryData returns true when telemetry entries exist")
    func hasTelemetryData() async throws {
        let store = try await createStore()

        // Snapshot without telemetry
        let idNoTelemetry = try await store.saveNodeStatusSnapshot(
            nodePublicKey: testPublicKey, batteryMillivolts: 3800,
            lastSNR: nil, lastRSSI: nil, noiseFloor: nil,
            uptimeSeconds: nil, rxAirtimeSeconds: nil,
            packetsSent: nil, packetsReceived: nil, receiveErrors: nil
        )

        let viewModel = TelemetryHistoryOverviewViewModel()
        await viewModel.loadData(
            dataStore: store, publicKey: testPublicKey, radioID: testDeviceID
        )
        #expect(!viewModel.hasTelemetryData, "Should be false with no telemetry entries")

        // Add telemetry to the snapshot
        try await store.updateSnapshotTelemetry(
            id: idNoTelemetry,
            telemetry: [TelemetrySnapshotEntry(channel: 0, type: "Voltage", value: 3.8)]
        )

        await viewModel.loadData(
            dataStore: store, publicKey: testPublicKey, radioID: testDeviceID
        )
        #expect(viewModel.hasTelemetryData, "Should be true after adding telemetry entries")
    }

    @Test("hasNeighborData returns true when neighbor snapshots exist")
    func hasNeighborData() async throws {
        let store = try await createStore()

        // Snapshot without neighbors
        let id = try await store.saveNodeStatusSnapshot(
            nodePublicKey: testPublicKey, batteryMillivolts: 3800,
            lastSNR: nil, lastRSSI: nil, noiseFloor: nil,
            uptimeSeconds: nil, rxAirtimeSeconds: nil,
            packetsSent: nil, packetsReceived: nil, receiveErrors: nil
        )

        let viewModel = TelemetryHistoryOverviewViewModel()
        await viewModel.loadData(
            dataStore: store, publicKey: testPublicKey, radioID: testDeviceID
        )
        #expect(!viewModel.hasNeighborData, "Should be false with no neighbor snapshots")

        // Add neighbors to the snapshot
        try await store.updateSnapshotNeighbors(
            id: id,
            neighbors: [NeighborSnapshotEntry(publicKeyPrefix: Data([0x01, 0x02]), snr: 6.5, secondsAgo: 30)]
        )

        await viewModel.loadData(
            dataStore: store, publicKey: testPublicKey, radioID: testDeviceID
        )
        #expect(viewModel.hasNeighborData, "Should be true after adding neighbor snapshots")
    }

    // MARK: - Channel Groups

    @Test("channelGroups groups by channel and sorts by chartSortPriority then alphabetically")
    func channelGroupsGrouping() async throws {
        let store = try await createStore()

        let snapshotID = try await store.saveNodeStatusSnapshot(
            nodePublicKey: testPublicKey, batteryMillivolts: 3800,
            lastSNR: nil, lastRSSI: nil, noiseFloor: nil,
            uptimeSeconds: nil, rxAirtimeSeconds: nil,
            packetsSent: nil, packetsReceived: nil, receiveErrors: nil
        )

        // Channel 0: Voltage (priority 0) and Temperature (priority 1)
        // Channel 2: Humidity (priority 1) and Voltage (priority 0)
        try await store.updateSnapshotTelemetry(
            id: snapshotID,
            telemetry: [
                TelemetrySnapshotEntry(channel: 0, type: "Voltage", value: 3.8),
                TelemetrySnapshotEntry(channel: 0, type: "Temperature", value: 22.5),
                TelemetrySnapshotEntry(channel: 2, type: "Humidity", value: 55.0),
                TelemetrySnapshotEntry(channel: 2, type: "Voltage", value: 4.1),
            ]
        )

        let viewModel = TelemetryHistoryOverviewViewModel()
        await viewModel.loadData(
            dataStore: store, publicKey: testPublicKey, radioID: testDeviceID
        )

        let groups = viewModel.channelGroups

        // Two channel groups, sorted by channel number
        #expect(groups.count == 2, "Should have 2 channel groups")
        #expect(groups[0].channel == 0, "First group should be channel 0")
        #expect(groups[1].channel == 2, "Second group should be channel 2")

        // Channel 0: Voltage (priority 0) before Temperature (priority 1)
        #expect(groups[0].charts.count == 2, "Channel 0 should have 2 charts")
        #expect(groups[0].charts[0].title == "Voltage", "Voltage should sort first (priority 0)")
        #expect(groups[0].charts[1].title == "Temperature", "Temperature should sort second (priority 1)")

        // Channel 2: Voltage (priority 0) before Humidity (priority 1)
        #expect(groups[1].charts.count == 2, "Channel 2 should have 2 charts")
        #expect(groups[1].charts[0].title == "Voltage", "Voltage should sort first (priority 0)")
        #expect(groups[1].charts[1].title == "Humidity", "Humidity should sort second (priority 1)")
    }
}
