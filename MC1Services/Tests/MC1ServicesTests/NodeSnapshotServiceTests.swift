import Foundation
@testable import MC1Services
import SwiftData
import Testing

@Suite("NodeSnapshotService Tests")
struct NodeSnapshotServiceTests {
  private let testPublicKey = Data(repeating: 0x42, count: 32)

  private func createTestService() async throws -> (NodeSnapshotService, PersistenceStore) {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let service = NodeSnapshotService(dataStore: store)
    return (service, store)
  }

  private func metrics(battery: UInt16, uptime: UInt32?) -> NodeStatusMetrics {
    NodeStatusMetrics(
      batteryMillivolts: battery,
      lastSNR: 8.5,
      lastRSSI: -87,
      noiseFloor: -120,
      uptimeSeconds: uptime,
      rxAirtimeSeconds: 100,
      packetsSent: 500,
      packetsReceived: 1000,
      receiveErrors: nil
    )
  }

  @Test
  func `Record returns an ID on first capture`() async throws {
    let (service, _) = try await createTestService()

    let id = await service.recordSnapshot(
      nodePublicKey: testPublicKey,
      status: metrics(battery: 3850, uptime: 3600)
    )

    #expect(id != nil)
  }

  @Test
  func `Second status within the window enriches the same row, not a new one`() async throws {
    let (service, _) = try await createTestService()

    let first = await service.recordSnapshot(
      nodePublicKey: testPublicKey,
      status: metrics(battery: 3850, uptime: 3600)
    )
    #expect(first != nil)

    let second = await service.recordSnapshot(
      nodePublicKey: testPublicKey,
      status: metrics(battery: 3900, uptime: 7200)
    )
    #expect(second == first, "An in-window status capture returns the existing row's ID")

    let snapshots = await service.fetchSnapshots(for: testPublicKey)
    #expect(snapshots.count == 1, "No second snapshot is created within the window")
    #expect(snapshots[0].batteryMillivolts == 3850, "A row already carrying status is not overwritten")
    #expect(snapshots[0].uptimeSeconds == 3600)
  }

  @Test
  func `Different nodes get independent snapshots`() async throws {
    let (service, _) = try await createTestService()
    let otherKey = Data(repeating: 0x99, count: 32)

    let first = await service.recordSnapshot(
      nodePublicKey: testPublicKey,
      status: metrics(battery: 3850, uptime: 3600)
    )
    let second = await service.recordSnapshot(
      nodePublicKey: otherKey,
      status: metrics(battery: 3700, uptime: 1800)
    )

    #expect(first != nil)
    #expect(second != nil)
    #expect(first != second, "Different nodes are not throttled against each other")
  }

  @Test
  func `Neighbors enrich the in-window snapshot`() async throws {
    let (service, store) = try await createTestService()

    let statusID = await service.recordSnapshot(
      nodePublicKey: testPublicKey,
      status: metrics(battery: 3850, uptime: 3600)
    )
    let neighbors = [
      NeighborSnapshotEntry(publicKeyPrefix: Data([0x01, 0x02, 0x03, 0x04]), snr: 5.5, secondsAgo: 30)
    ]
    let neighborID = await service.recordSnapshot(nodePublicKey: testPublicKey, neighbors: neighbors)
    #expect(neighborID == statusID, "Neighbors land on the existing in-window row")

    let latest = try await store.fetchLatestNodeStatusSnapshot(nodePublicKey: testPublicKey)
    #expect(latest?.neighborSnapshots?.count == 1)
    #expect(latest?.neighborSnapshots?.first?.snr == 5.5)
  }

  @Test
  func `Telemetry enriches the in-window snapshot`() async throws {
    let (service, store) = try await createTestService()

    let statusID = await service.recordSnapshot(
      nodePublicKey: testPublicKey,
      status: metrics(battery: 3850, uptime: 3600)
    )
    let telemetry = [
      TelemetrySnapshotEntry(channel: 0, type: "temperature", value: 32.5)
    ]
    let telemetryID = await service.recordSnapshot(nodePublicKey: testPublicKey, telemetry: telemetry)
    #expect(telemetryID == statusID, "Telemetry lands on the existing in-window row")

    let latest = try await store.fetchLatestNodeStatusSnapshot(nodePublicKey: testPublicKey)
    #expect(latest?.telemetryEntries?.count == 1)
    #expect(latest?.telemetryEntries?.first?.value == 32.5)
  }

  @Test
  func `Fetch snapshots returns ascending order`() async throws {
    let (service, store) = try await createTestService()
    let t1 = Date.now.addingTimeInterval(-20)
    let t2 = Date.now.addingTimeInterval(-10)

    _ = try await store.saveNodeStatusSnapshot(
      timestamp: t1,
      nodePublicKey: testPublicKey,
      batteryMillivolts: 3600,
      lastSNR: nil, lastRSSI: nil, noiseFloor: nil,
      uptimeSeconds: nil, rxAirtimeSeconds: nil,
      packetsSent: nil, packetsReceived: nil,
      receiveErrors: nil
    )
    _ = try await store.saveNodeStatusSnapshot(
      timestamp: t2,
      nodePublicKey: testPublicKey,
      batteryMillivolts: 3800,
      lastSNR: nil, lastRSSI: nil, noiseFloor: nil,
      uptimeSeconds: nil, rxAirtimeSeconds: nil,
      packetsSent: nil, packetsReceived: nil,
      receiveErrors: nil
    )

    let snapshots = await service.fetchSnapshots(for: testPublicKey)
    #expect(snapshots.count == 2)
    #expect(snapshots[0].batteryMillivolts == 3600)
    #expect(snapshots[1].batteryMillivolts == 3800)
  }

  @Test
  func `Prune only deletes snapshots older than cutoff`() async throws {
    let (service, store) = try await createTestService()
    let oldTime = Date.now.addingTimeInterval(-60)
    let cutoff = Date.now.addingTimeInterval(-30)
    let recentTime = Date.now.addingTimeInterval(-10)

    // Save an "old" snapshot by writing directly to the store (bypass throttle)
    _ = try await store.saveNodeStatusSnapshot(
      timestamp: oldTime,
      nodePublicKey: testPublicKey,
      batteryMillivolts: 3600,
      lastSNR: nil, lastRSSI: nil, noiseFloor: nil,
      uptimeSeconds: nil, rxAirtimeSeconds: nil,
      packetsSent: nil, packetsReceived: nil,
      receiveErrors: nil
    )

    // Save a "recent" snapshot
    let recentID = try await store.saveNodeStatusSnapshot(
      timestamp: recentTime,
      nodePublicKey: testPublicKey,
      batteryMillivolts: 3800,
      lastSNR: nil, lastRSSI: nil, noiseFloor: nil,
      uptimeSeconds: nil, rxAirtimeSeconds: nil,
      packetsSent: nil, packetsReceived: nil,
      receiveErrors: nil
    )
    await service.pruneOldSnapshots(olderThan: cutoff)

    let remaining = await service.fetchSnapshots(for: testPublicKey)
    #expect(remaining.count == 1, "Old snapshot should be pruned, recent should remain")
    #expect(remaining.first?.id == recentID)
  }

  @Test
  func `Prune with future cutoff does not delete recent snapshots`() async throws {
    let (service, store) = try await createTestService()

    _ = try await store.saveNodeStatusSnapshot(
      nodePublicKey: testPublicKey,
      batteryMillivolts: 3850,
      lastSNR: nil, lastRSSI: nil, noiseFloor: nil,
      uptimeSeconds: nil, rxAirtimeSeconds: nil,
      packetsSent: nil, packetsReceived: nil,
      receiveErrors: nil
    )

    // Prune with a cutoff 1 year ago — recent data should survive
    let oneYearAgo = try #require(Calendar.current.date(byAdding: .year, value: -1, to: .now))
    await service.pruneOldSnapshots(olderThan: oneYearAgo)

    let remaining = await service.fetchSnapshots(for: testPublicKey)
    #expect(remaining.count == 1, "Recent snapshot should not be pruned")
  }

  @Test
  func `Fetch snapshots with since filter`() async throws {
    let (service, store) = try await createTestService()
    let t1 = Date.now.addingTimeInterval(-30)
    let cutoff = Date.now.addingTimeInterval(-15)
    let t2 = Date.now.addingTimeInterval(-5)

    _ = try await store.saveNodeStatusSnapshot(
      timestamp: t1,
      nodePublicKey: testPublicKey,
      batteryMillivolts: 3600,
      lastSNR: nil, lastRSSI: nil, noiseFloor: nil,
      uptimeSeconds: nil, rxAirtimeSeconds: nil,
      packetsSent: nil, packetsReceived: nil,
      receiveErrors: nil
    )
    _ = try await store.saveNodeStatusSnapshot(
      timestamp: t2,
      nodePublicKey: testPublicKey,
      batteryMillivolts: 3800,
      lastSNR: nil, lastRSSI: nil, noiseFloor: nil,
      uptimeSeconds: nil, rxAirtimeSeconds: nil,
      packetsSent: nil, packetsReceived: nil,
      receiveErrors: nil
    )

    let snapshots = await service.fetchSnapshots(for: testPublicKey, since: cutoff)
    #expect(snapshots.count == 1)
    #expect(snapshots[0].batteryMillivolts == 3800)
  }

  // MARK: - Round-trip enrichment tests

  @Test
  func `Enrichment data survives save -> enrich -> fetchAll round-trip`() async throws {
    let (service, store) = try await createTestService()

    // Save two snapshots directly to the store (bypass the window)
    let t1 = Date.now.addingTimeInterval(-20)
    let t2 = Date.now.addingTimeInterval(-10)
    let id1 = try await store.saveNodeStatusSnapshot(
      timestamp: t1,
      nodePublicKey: testPublicKey,
      batteryMillivolts: 3600,
      lastSNR: 7.0, lastRSSI: -90, noiseFloor: -120,
      uptimeSeconds: nil, rxAirtimeSeconds: nil,
      packetsSent: nil, packetsReceived: nil,
      receiveErrors: nil
    )
    let id2 = try await store.saveNodeStatusSnapshot(
      timestamp: t2,
      nodePublicKey: testPublicKey,
      batteryMillivolts: 3800,
      lastSNR: 8.5, lastRSSI: -85, noiseFloor: -118,
      uptimeSeconds: nil, rxAirtimeSeconds: nil,
      packetsSent: nil, packetsReceived: nil,
      receiveErrors: nil
    )

    // Enrich both directly through the store, targeting specific rows
    let telemetry1 = [TelemetrySnapshotEntry(channel: 0, type: "temperature", value: 25.0)]
    let telemetry2 = [TelemetrySnapshotEntry(channel: 0, type: "temperature", value: 30.0)]
    try await store.updateSnapshotTelemetry(id: id1, telemetry: telemetry1)
    try await store.updateSnapshotTelemetry(id: id2, telemetry: telemetry2)

    let neighbors1 = [NeighborSnapshotEntry(publicKeyPrefix: Data([0x01, 0x02, 0x03, 0x04]), snr: 5.0, secondsAgo: 60)]
    let neighbors2 = [NeighborSnapshotEntry(publicKeyPrefix: Data([0x05, 0x06, 0x07, 0x08]), snr: 9.0, secondsAgo: 10)]
    try await store.updateSnapshotNeighbors(id: id1, neighbors: neighbors1)
    try await store.updateSnapshotNeighbors(id: id2, neighbors: neighbors2)

    // Fetch all via the method used by history views
    let snapshots = await service.fetchSnapshots(for: testPublicKey)
    #expect(snapshots.count == 2)

    #expect(snapshots[0].telemetryEntries?.count == 1, "Snapshot 1 telemetry should persist")
    #expect(snapshots[0].telemetryEntries?.first?.value == 25.0)
    #expect(snapshots[0].neighborSnapshots?.count == 1, "Snapshot 1 neighbors should persist")
    #expect(snapshots[0].neighborSnapshots?.first?.snr == 5.0)

    #expect(snapshots[1].telemetryEntries?.count == 1, "Snapshot 2 telemetry should persist")
    #expect(snapshots[1].telemetryEntries?.first?.value == 30.0)
    #expect(snapshots[1].neighborSnapshots?.count == 1, "Snapshot 2 neighbors should persist")
    #expect(snapshots[1].neighborSnapshots?.first?.snr == 9.0)
  }

  @Test
  func `Status + telemetry + neighbors in one window collapse onto a single row`() async throws {
    let (service, _) = try await createTestService()

    let statusID = await service.recordSnapshot(
      nodePublicKey: testPublicKey,
      status: metrics(battery: 3700, uptime: 3600)
    )
    guard let snapshotID = statusID else {
      Issue.record("First capture should return an ID")
      return
    }

    let telemetry = [
      TelemetrySnapshotEntry(channel: 0, type: "temperature", value: 28.5),
      TelemetrySnapshotEntry(channel: 1, type: "humidity", value: 65.0),
    ]
    let neighbors = [
      NeighborSnapshotEntry(publicKeyPrefix: Data([0xAA, 0xBB, 0xCC, 0xDD]), snr: 6.5, secondsAgo: 45),
    ]
    let enrichedID = await service.recordSnapshot(
      nodePublicKey: testPublicKey,
      telemetry: telemetry,
      neighbors: neighbors
    )
    #expect(enrichedID == snapshotID)

    let snapshots = await service.fetchSnapshots(for: testPublicKey)
    #expect(snapshots.count == 1)
    #expect(snapshots[0].telemetryEntries?.count == 2, "Both telemetry entries should persist")
    #expect(snapshots[0].neighborSnapshots?.count == 1, "Neighbor entry should persist")
  }

  // MARK: - Concurrent and out-of-order capture coverage

  @Test
  func `Telemetry-first then status-within-window backfills status onto the single snapshot`() async throws {
    let (service, _) = try await createTestService()

    // Telemetry expanded before status: a telemetry-only snapshot is created.
    let telemetry = [TelemetrySnapshotEntry(channel: 1, type: "temperature", value: 21.5)]
    let telemetryID = await service.recordSnapshot(nodePublicKey: testPublicKey, telemetry: telemetry)
    #expect(telemetryID != nil, "Telemetry-only capture should create a snapshot")

    // Status applied within the window backfills the telemetry-only row
    // rather than being dropped or creating a duplicate.
    let statusMetrics = NodeStatusMetrics(
      batteryMillivolts: 3900,
      lastSNR: 9.0, lastRSSI: -84, noiseFloor: -119,
      uptimeSeconds: 7200, rxAirtimeSeconds: 150,
      packetsSent: 600, packetsReceived: 1200,
      receiveErrors: 3
    )
    let statusID = await service.recordSnapshot(nodePublicKey: testPublicKey, status: statusMetrics)
    #expect(statusID == telemetryID, "Status enriches the telemetry-only row, no new snapshot")

    let snapshots = await service.fetchSnapshots(for: testPublicKey)
    #expect(snapshots.count == 1, "Should remain a single snapshot carrying both data sets")
    #expect(snapshots[0].telemetryEntries?.first?.value == 21.5, "Telemetry should be preserved")
    #expect(snapshots[0].uptimeSeconds == 7200, "Status counters should be backfilled")
    #expect(snapshots[0].batteryMillivolts == 3900)
    #expect(snapshots[0].receiveErrors == 3)
  }

  @Test
  func `Neighbors captured before any status persist on a fresh snapshot`() async throws {
    let (service, _) = try await createTestService()

    let neighbors = [
      NeighborSnapshotEntry(publicKeyPrefix: Data([0x0A, 0x0B, 0x0C, 0x0D]), snr: 4.0, secondsAgo: 90)
    ]
    let id = await service.recordSnapshot(nodePublicKey: testPublicKey, neighbors: neighbors)
    #expect(id != nil, "Neighbors expanded without status must still persist")

    let snapshots = await service.fetchSnapshots(for: testPublicKey)
    #expect(snapshots.count == 1)
    #expect(snapshots[0].neighborSnapshots?.count == 1, "Neighbor data should persist on a fresh row")
    #expect(snapshots[0].uptimeSeconds == nil, "A neighbor-only row carries no status fields yet")
  }

  // MARK: - Neighbor delta baseline

  @Test
  func `Previous neighbor snapshot skips a more recent status-only row`() async throws {
    let (service, store) = try await createTestService()
    let older = Date.now.addingTimeInterval(-3600)
    let recent = Date.now.addingTimeInterval(-1800)

    let neighborID = try await store.saveNodeStatusSnapshot(
      timestamp: older,
      nodePublicKey: testPublicKey,
      batteryMillivolts: 3700,
      lastSNR: 6.0, lastRSSI: -90, noiseFloor: -120,
      uptimeSeconds: 3600, rxAirtimeSeconds: nil,
      packetsSent: nil, packetsReceived: nil,
      receiveErrors: nil
    )
    try await store.updateSnapshotNeighbors(id: neighborID, neighbors: [
      NeighborSnapshotEntry(publicKeyPrefix: Data([0x01, 0x02, 0x03, 0x04]), snr: 6.0, secondsAgo: 30)
    ])
    _ = try await store.saveNodeStatusSnapshot(
      timestamp: recent,
      nodePublicKey: testPublicKey,
      batteryMillivolts: 3850,
      lastSNR: 8.0, lastRSSI: -85, noiseFloor: -118,
      uptimeSeconds: 7200, rxAirtimeSeconds: nil,
      packetsSent: nil, packetsReceived: nil,
      receiveErrors: nil
    )

    let previous = await service.previousNeighborSnapshot(for: testPublicKey)
    #expect(previous?.neighborSnapshots?.first?.snr == 6.0,
            "Returns the neighbor-bearing row, not the newer status-only row")
  }

  @Test
  func `Previous neighbor snapshot excludes the current in-window capture`() async throws {
    let (service, store) = try await createTestService()
    let older = Date.now.addingTimeInterval(-3600)

    let priorID = try await store.saveNodeStatusSnapshot(
      timestamp: older,
      nodePublicKey: testPublicKey,
      batteryMillivolts: 3700,
      lastSNR: 6.0, lastRSSI: -90, noiseFloor: -120,
      uptimeSeconds: 3600, rxAirtimeSeconds: nil,
      packetsSent: nil, packetsReceived: nil,
      receiveErrors: nil
    )
    try await store.updateSnapshotNeighbors(id: priorID, neighbors: [
      NeighborSnapshotEntry(publicKeyPrefix: Data([0x01, 0x02, 0x03, 0x04]), snr: 6.0, secondsAgo: 30)
    ])
    // The reading being viewed now lands on a fresh in-window row.
    _ = await service.recordSnapshot(nodePublicKey: testPublicKey, neighbors: [
      NeighborSnapshotEntry(publicKeyPrefix: Data([0x01, 0x02, 0x03, 0x04]), snr: 9.0, secondsAgo: 5)
    ])

    let previous = await service.previousNeighborSnapshot(for: testPublicKey)
    #expect(previous?.neighborSnapshots?.first?.snr == 6.0,
            "The current in-window row is excluded; baseline is the prior neighbor capture")
  }

  @Test
  func `Previous neighbor snapshot is nil when no prior neighbor data exists`() async throws {
    let (service, _) = try await createTestService()

    _ = await service.recordSnapshot(
      nodePublicKey: testPublicKey,
      status: metrics(battery: 3850, uptime: 3600)
    )

    let previous = await service.previousNeighborSnapshot(for: testPublicKey)
    #expect(previous == nil, "Status-only history yields no neighbor baseline")
  }

  @Test
  func `Previous neighbor snapshot returns an out-of-window neighbor row as the baseline`() async throws {
    let (service, store) = try await createTestService()
    let older = Date.now.addingTimeInterval(-3600)

    let priorID = try await store.saveNodeStatusSnapshot(
      timestamp: older,
      nodePublicKey: testPublicKey,
      batteryMillivolts: 3700,
      lastSNR: 6.0, lastRSSI: -90, noiseFloor: -120,
      uptimeSeconds: 3600, rxAirtimeSeconds: nil,
      packetsSent: nil, packetsReceived: nil,
      receiveErrors: nil
    )
    try await store.updateSnapshotNeighbors(id: priorID, neighbors: [
      NeighborSnapshotEntry(publicKeyPrefix: Data([0x01, 0x02, 0x03, 0x04]), snr: 6.0, secondsAgo: 30)
    ])

    let previous = await service.previousNeighborSnapshot(for: testPublicKey)
    #expect(previous?.neighborSnapshots?.first?.snr == 6.0,
            "With the latest row outside the in-window cutoff, it is the baseline")
  }

  @Test
  func `Previous neighbor snapshot is nil when the only neighbor row is the in-window capture`() async throws {
    let (service, _) = try await createTestService()

    // A single fresh in-window neighbor capture is its own reading, so it has no baseline.
    _ = await service.recordSnapshot(nodePublicKey: testPublicKey, neighbors: [
      NeighborSnapshotEntry(publicKeyPrefix: Data([0x01, 0x02, 0x03, 0x04]), snr: 9.0, secondsAgo: 5)
    ])

    let previous = await service.previousNeighborSnapshot(for: testPublicKey)
    #expect(previous == nil, "The in-window capture is excluded and there is no prior neighbor row")
  }

  // MARK: - Status delta baseline

  @Test
  func `Previous status snapshot skips a more recent neighbor-only row`() async throws {
    let (service, store) = try await createTestService()
    let older = Date.now.addingTimeInterval(-3600)

    _ = try await store.saveNodeStatusSnapshot(
      timestamp: older,
      nodePublicKey: testPublicKey,
      batteryMillivolts: 3700,
      lastSNR: 6.0, lastRSSI: -90, noiseFloor: -120,
      uptimeSeconds: 3600, rxAirtimeSeconds: nil,
      packetsSent: nil, packetsReceived: nil,
      receiveErrors: nil
    )
    // A newer neighbor-only capture carries no status fields.
    _ = await service.recordSnapshot(nodePublicKey: testPublicKey, neighbors: [
      NeighborSnapshotEntry(publicKeyPrefix: Data([0x01, 0x02, 0x03, 0x04]), snr: 9.0, secondsAgo: 5)
    ])

    let previous = await service.previousStatusSnapshot(for: testPublicKey, before: .now)
    #expect(previous?.uptimeSeconds == 3600,
            "Returns the status-bearing row, not the newer neighbor-only row")
  }

  @Test
  func `Previous status snapshot includes an in-window status capture`() async throws {
    let (service, _) = try await createTestService()

    // Unlike the neighbor baseline, an early in-window status reading is a valid
    // baseline: status capture is throttled and never overwrites itself.
    _ = await service.recordSnapshot(
      nodePublicKey: testPublicKey,
      status: metrics(battery: 3850, uptime: 3600)
    )

    let previous = await service.previousStatusSnapshot(for: testPublicKey, before: .now)
    #expect(previous?.uptimeSeconds == 3600, "The in-window status row is a usable baseline")
  }

  @Test
  func `Previous status snapshot is nil when only neighbor data exists`() async throws {
    let (service, _) = try await createTestService()

    _ = await service.recordSnapshot(nodePublicKey: testPublicKey, neighbors: [
      NeighborSnapshotEntry(publicKeyPrefix: Data([0x01, 0x02, 0x03, 0x04]), snr: 9.0, secondsAgo: 5)
    ])

    let previous = await service.previousStatusSnapshot(for: testPublicKey, before: .now)
    #expect(previous == nil, "Neighbor-only history yields no status baseline")
  }

  @Test
  func `Concurrent in-window captures never duplicate a snapshot`() async throws {
    let (service, _) = try await createTestService()

    // Status and telemetry captured concurrently must collapse onto one
    // in-window row; the atomic store serializes the read-modify-write.
    let telemetry = [TelemetrySnapshotEntry(channel: 0, type: "temperature", value: 19.0)]
    async let statusResult = service.recordSnapshot(
      nodePublicKey: testPublicKey,
      status: metrics(battery: 3850, uptime: 3600)
    )
    async let telemetryResult = service.recordSnapshot(
      nodePublicKey: testPublicKey,
      telemetry: telemetry
    )
    let (statusID, telemetryID) = await (statusResult, telemetryResult)
    #expect(statusID != nil)
    #expect(telemetryID != nil)
    #expect(statusID == telemetryID, "Concurrent captures resolve to the same in-window row")

    let snapshots = await service.fetchSnapshots(for: testPublicKey)
    #expect(snapshots.count == 1, "Atomic record collapses concurrent captures into one row")
  }
}
