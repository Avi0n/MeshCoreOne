import Foundation
import MeshCore
import Testing

@testable import MC1
@testable import MC1Services

@Suite("RepeaterStatusViewModel Enrichment Tests")
@MainActor
struct RepeaterStatusViewModelTests {

    private let testPublicKey = Data(repeating: 0x42, count: 32)

    private func createTestService() async throws -> (NodeSnapshotService, PersistenceStore) {
        let container = try PersistenceStore.createContainer(inMemory: true)
        let store = PersistenceStore(modelContainer: container)
        let service = NodeSnapshotService(dataStore: store)
        return (service, store)
    }

    private func createTestSession() -> RemoteNodeSessionDTO {
        RemoteNodeSessionDTO(
            radioID: UUID(),
            publicKey: testPublicKey,
            name: "Test Repeater",
            role: .repeater,
            isConnected: true,
            permissionLevel: .admin
        )
    }

    private func createStatusResponse() -> RemoteNodeStatus {
        StatusResponse(
            publicKeyPrefix: testPublicKey.prefix(6),
            battery: 3850,
            txQueueLength: 0,
            noiseFloor: -120,
            lastRSSI: -87,
            packetsReceived: 1000,
            packetsSent: 500,
            airtime: 100,
            uptime: 3600,
            sentFlood: 0,
            sentDirect: 0,
            receivedFlood: 0,
            receivedDirect: 0,
            fullEvents: 0,
            lastSNR: 8.5,
            directDuplicates: 0,
            floodDuplicates: 0,
            rxAirtime: 100,
            receiveErrors: 0
        )
    }

    private func createTelemetryResponse() -> TelemetryResponse {
        var encoder = LPPEncoder()
        encoder.addTemperature(channel: 1, celsius: 22.5)
        return TelemetryResponse(
            publicKeyPrefix: testPublicKey.prefix(6),
            tag: nil,
            rawData: encoder.encode()
        )
    }

    private func createNeighboursResponse() -> NeighboursResponse {
        NeighboursResponse(
            publicKeyPrefix: testPublicKey.prefix(6),
            tag: Data([0x00, 0x00, 0x00, 0x01]),
            totalCount: 1,
            neighbours: [
                Neighbour(publicKeyPrefix: Data([0x01, 0x02, 0x03, 0x04, 0x05, 0x06]), secondsAgo: 30, snr: 5.5)
            ]
        )
    }

    // MARK: - Bug reproduction

    @Test("Enrichment lost when snapshot is throttled on refresh")
    func enrichmentLostOnThrottledRefresh() async throws {
        let (service, _) = try await createTestService()
        let session = createTestSession()

        let viewModel = RepeaterStatusViewModel()
        viewModel.helper.configure(contactService: nil, nodeSnapshotService: service)
        viewModel.helper.session = session

        // Visit 1: First status response — snapshot saved (not throttled)
        let status = createStatusResponse()
        await viewModel.helper.handleStatusResponse(
            status,
            rxAirtimeSeconds: status.repeaterRxAirtimeSeconds,
            receiveErrors: status.receiveErrors
        )
        let snapshots1 = await viewModel.helper.fetchHistory()
        #expect(snapshots1.count == 1, "First visit should save a snapshot")

        // Simulate refresh within 15 min — snapshot will be throttled
        await viewModel.helper.handleStatusResponse(
            status,
            rxAirtimeSeconds: status.repeaterRxAirtimeSeconds,
            receiveErrors: status.receiveErrors
        )
        let snapshots2 = await viewModel.helper.fetchHistory()
        #expect(snapshots2.count == 1, "Throttled save should not create a new snapshot")

        // User expands neighbors section — enrichment data arrives
        viewModel.handleNeighboursResponse(createNeighboursResponse())

        // Poll until enrichment completes (fire-and-forget Task) or timeout
        let deadline = ContinuousClock.now.advanced(by: .seconds(2))
        var enriched = false
        while ContinuousClock.now < deadline {
            let snapshots = await viewModel.helper.fetchHistory()
            if snapshots.first?.neighborSnapshots?.isEmpty == false {
                enriched = true
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(enriched, "Neighbor enrichment should persist even after throttled refresh")
    }

    // MARK: - Status lazy-load

    @Test("Status stays unloaded until a status response is applied")
    func statusUnloadedUntilResponse() async throws {
        let (service, _) = try await createTestService()
        let session = createTestSession()

        let viewModel = RepeaterStatusViewModel()
        viewModel.helper.configure(contactService: nil, nodeSnapshotService: service)
        viewModel.helper.session = session

        #expect(viewModel.helper.statusLoaded == false, "Status should start unloaded")
        #expect(viewModel.helper.statusExpanded == false, "Status should start collapsed")

        let status = createStatusResponse()
        await viewModel.helper.handleStatusResponse(
            status,
            rxAirtimeSeconds: status.repeaterRxAirtimeSeconds,
            receiveErrors: status.receiveErrors
        )

        #expect(viewModel.helper.statusLoaded == true, "Status should load after a response is applied")
    }

    // MARK: - Telemetry without status

    @Test("Telemetry without status persists a telemetry-only snapshot")
    func telemetryWithoutStatusPersists() async throws {
        let (service, _) = try await createTestService()
        let session = createTestSession()

        let viewModel = RepeaterStatusViewModel()
        viewModel.helper.configure(contactService: nil, nodeSnapshotService: service)
        viewModel.helper.session = session

        // No status response applied, so no snapshot exists yet.
        let before = await viewModel.helper.fetchHistory()
        #expect(before.isEmpty, "No snapshot should exist before any response")

        // Telemetry expanded without status: handler must persist immediately.
        viewModel.helper.handleTelemetryResponse(createTelemetryResponse())

        let deadline = ContinuousClock.now.advanced(by: .seconds(2))
        var persisted: NodeStatusSnapshotDTO?
        while ContinuousClock.now < deadline {
            let snapshots = await viewModel.helper.fetchHistory()
            if let snapshot = snapshots.first, snapshot.telemetryEntries?.isEmpty == false {
                persisted = snapshot
                break
            }
            try await Task.sleep(for: .milliseconds(10))
        }

        #expect(persisted != nil, "Telemetry-only snapshot should persist when no status snapshot exists")
        #expect(persisted?.telemetryEntries?.count == 1, "Snapshot should carry the telemetry entry")
    }
}
