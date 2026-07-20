import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

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

  @Test
  func `Enrichment lost when snapshot is throttled on refresh`() async throws {
    let (service, _) = try await createTestService()
    let session = createTestSession()

    let viewModel = RepeaterStatusViewModel()
    viewModel.helper.configure(contactService: { nil }, nodeSnapshotService: { service })
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
    await viewModel.handleNeighboursResponse(createNeighboursResponse())

    let snapshots = await viewModel.helper.fetchHistory()
    #expect(snapshots.first?.neighborSnapshots?.isEmpty == false,
            "Neighbor enrichment should persist even after throttled refresh")
  }

  // MARK: - Status lazy-load

  @Test
  func `Status stays unloaded until a status response is applied`() async throws {
    let (service, _) = try await createTestService()
    let session = createTestSession()

    let viewModel = RepeaterStatusViewModel()
    viewModel.helper.configure(contactService: { nil }, nodeSnapshotService: { service })
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

  @Test
  func `Telemetry without status persists a telemetry-only snapshot`() async throws {
    let (service, _) = try await createTestService()
    let session = createTestSession()

    let viewModel = RepeaterStatusViewModel()
    viewModel.helper.configure(contactService: { nil }, nodeSnapshotService: { service })
    viewModel.helper.session = session

    // No status response applied, so no snapshot exists yet.
    let before = await viewModel.helper.fetchHistory()
    #expect(before.isEmpty, "No snapshot should exist before any response")

    // Telemetry expanded without status: handler must persist immediately.
    await viewModel.helper.handleTelemetryResponse(createTelemetryResponse())

    let snapshots = await viewModel.helper.fetchHistory()
    let persisted = snapshots.first
    #expect(persisted != nil, "Telemetry-only snapshot should persist when no status snapshot exists")
    #expect(persisted?.telemetryEntries?.count == 1, "Snapshot should carry the telemetry entry")
  }

  // MARK: - Neighbors without status

  @Test
  func `Neighbors expanded without status persist a neighbor-only snapshot`() async throws {
    let (service, _) = try await createTestService()
    let session = createTestSession()

    let viewModel = RepeaterStatusViewModel()
    viewModel.helper.configure(contactService: { nil }, nodeSnapshotService: { service })
    viewModel.helper.session = session

    let before = await viewModel.helper.fetchHistory()
    #expect(before.isEmpty, "No snapshot should exist before any response")

    // Neighbors expanded before status: enrichment must still persist
    // rather than being stranded in a buffer waiting for a status response.
    await viewModel.handleNeighboursResponse(createNeighboursResponse())

    let snapshots = await viewModel.helper.fetchHistory()
    let persisted = snapshots.first
    #expect(persisted != nil, "Neighbors-first should persist a neighbor-bearing snapshot")
    #expect(persisted?.neighborSnapshots?.count == 1)
    #expect(persisted?.uptimeSeconds == nil, "A neighbor-only row carries no status yet")
  }

  @Test
  func `Status applied after telemetry-first enriches the telemetry-only snapshot`() async throws {
    let (service, _) = try await createTestService()
    let session = createTestSession()

    let viewModel = RepeaterStatusViewModel()
    viewModel.helper.configure(contactService: { nil }, nodeSnapshotService: { service })
    viewModel.helper.session = session

    // Telemetry expanded before status: a telemetry-only snapshot is created
    // (no status fields yet) and becomes the current enrichment target.
    await viewModel.helper.handleTelemetryResponse(createTelemetryResponse())

    let telemetryOnly = await viewModel.helper.fetchHistory().first
    #expect(telemetryOnly != nil, "Telemetry-first should persist a telemetry-only snapshot")
    #expect(telemetryOnly?.uptimeSeconds == nil, "Telemetry-only snapshot should not carry status fields yet")

    // Status applied within the window enriches the telemetry-only row
    // atomically rather than dropping the status data point or duplicating it.
    let status = createStatusResponse()
    await viewModel.helper.handleStatusResponse(
      status,
      rxAirtimeSeconds: status.repeaterRxAirtimeSeconds,
      receiveErrors: status.receiveErrors
    )

    let snapshots = await viewModel.helper.fetchHistory()
    let enriched = snapshots.first
    #expect(snapshots.count == 1, "In-window status capture should not create a second snapshot")
    #expect(enriched?.uptimeSeconds != nil, "Telemetry-first snapshot should be enriched with status fields")
    #expect(enriched?.telemetryEntries?.count == 1, "Telemetry entry should be preserved")
    #expect(enriched?.uptimeSeconds == status.uptimeSeconds, "Status uptime should be backfilled")
    #expect(enriched?.batteryMillivolts == status.batteryMillivolts, "Status battery should be backfilled")
  }

  // MARK: - Owner info firmware capture

  @Test
  func `Owner info response exposes the firmware version`() {
    let viewModel = RepeaterStatusViewModel()

    viewModel.applyOwnerInfo(
      OwnerInfoResponse(firmwareVersion: "v1.16.0", nodeName: "Test Repeater", ownerInfo: "Hello")
    )

    #expect(viewModel.firmwareVersion == "v1.16.0", "Firmware version should be captured from owner info")
    #expect(viewModel.ownerInfo == "Hello", "Owner info text should still be captured")
  }

  @Test
  func `Empty firmware string maps to nil so the row stays hidden`() {
    let viewModel = RepeaterStatusViewModel()

    viewModel.applyOwnerInfo(
      OwnerInfoResponse(firmwareVersion: "", nodeName: "Test Repeater", ownerInfo: "")
    )

    #expect(viewModel.firmwareVersion == nil, "Nodes predating owner-info return an empty firmware string, which must map to nil")
  }
}
