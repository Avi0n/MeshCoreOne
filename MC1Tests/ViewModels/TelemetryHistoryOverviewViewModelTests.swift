import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("TelemetryHistoryOverviewViewModel Tests")
@MainActor
struct TelemetryHistoryOverviewViewModelTests {
  private let testPublicKey = Data(repeating: 0xAB, count: 32)
  private let testDeviceID = UUID()

  private func createStore() async throws -> PersistenceStore {
    let container = try PersistenceStore.createContainer(inMemory: true)
    return PersistenceStore(modelContainer: container)
  }

  private func createContactDTO(
    publicKey: Data? = nil,
    name: String = "Test Repeater",
    lastAdvertTimestamp: UInt32 = 0,
    ocvPreset: String? = nil
  ) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: testDeviceID,
      publicKey: publicKey ?? testPublicKey,
      name: name,
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: lastAdvertTimestamp,
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

  @Test
  func `loadData fetches snapshots from persistence store`() async throws {
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

  @Test
  func `loadData with no snapshots leaves empty array`() async throws {
    let store = try await createStore()

    let viewModel = TelemetryHistoryOverviewViewModel()
    await viewModel.loadData(
      dataStore: store, publicKey: testPublicKey, radioID: testDeviceID
    )

    #expect(viewModel.snapshots.isEmpty)
  }

  // MARK: - OCV Resolution

  @Test
  func `loadData resolves OCV from contact preset`() async throws {
    let store = try await createStore()

    let contact = createContactDTO(ocvPreset: OCVPreset.liFePO4.rawValue)
    try await store.saveContact(contact)

    let viewModel = TelemetryHistoryOverviewViewModel()
    await viewModel.loadData(
      dataStore: store, publicKey: testPublicKey, radioID: testDeviceID
    )

    #expect(viewModel.ocvArray == OCVPreset.liFePO4.ocvArray)
  }

  @Test
  func `loadData defaults to liIon when no contact found`() async throws {
    let store = try await createStore()

    let viewModel = TelemetryHistoryOverviewViewModel()
    await viewModel.loadData(
      dataStore: store, publicKey: testPublicKey, radioID: testDeviceID
    )

    #expect(viewModel.ocvArray == OCVPreset.liIon.ocvArray)
  }

  // MARK: - Filtering

  @Test
  func `filteredSnapshots returns all when timeRange is .all`() async throws {
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

  @Test
  func `filteredSnapshots excludes old snapshots for .week range`() async throws {
    let store = try await createStore()

    // Save an old snapshot (30 days ago)
    let thirtyDaysAgo = try #require(Calendar.current.date(byAdding: .day, value: -30, to: .now))
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

  @Test
  func `hasSnapshots reflects snapshot count`() async throws {
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

  @Test
  func `hasTelemetryData returns true when telemetry entries exist`() async throws {
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

  @Test
  func `hasNeighborData returns true when neighbor snapshots exist`() async throws {
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

  @Test
  func `resolveNeighborName disambiguates short contact prefixes by resolver policy`() async throws {
    let store = try await createStore()
    let older = createContactDTO(
      publicKey: Data([0xAB, 0xCD, 0x01] + Array(repeating: UInt8(0), count: 29)),
      name: "A Older Repeater",
      lastAdvertTimestamp: 100
    )
    let newer = createContactDTO(
      publicKey: Data([0xAB, 0xCD, 0x02] + Array(repeating: UInt8(0), count: 29)),
      name: "Z Newer Repeater",
      lastAdvertTimestamp: 200
    )
    try await store.saveContact(older)
    try await store.saveContact(newer)

    let viewModel = TelemetryHistoryOverviewViewModel()
    await viewModel.loadData(
      dataStore: store, publicKey: testPublicKey, radioID: testDeviceID
    )

    #expect(viewModel.resolveNeighborName(prefix: Data([0xAB, 0xCD])) == "Z Newer Repeater")
  }

  // MARK: - Channel Groups

  @Test
  func `channelGroups groups by channel and sorts by chartSortPriority then alphabetically`() async throws {
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
