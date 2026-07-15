import CoreLocation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Node location capture")
@MainActor
struct NodeLocationCaptureTests {
  private let publicKey = Data(repeating: 0x42, count: 32)

  /// Builds a raw LPP frame carrying one GPS point on a non-zero channel, and
  /// confirms it decodes as expected before feeding it to the view model, so the
  /// fixture is validated by the real decoder rather than trusted blindly.
  private func gpsOnlyResponse(lat: Double, lon: Double, alt: Double = 0) -> TelemetryResponse {
    func int24BE(_ scaled: Int32) -> [UInt8] {
      [UInt8((scaled >> 16) & 0xFF), UInt8((scaled >> 8) & 0xFF), UInt8(scaled & 0xFF)]
    }
    var bytes: [UInt8] = [0x01, LPPSensorType.gps.rawValue]
    bytes += int24BE(Int32((lat * 10000).rounded()))
    bytes += int24BE(Int32((lon * 10000).rounded()))
    // Altitude on the wire is 0.01 m fixed point; the decoder returns meters.
    bytes += int24BE(Int32((alt * 100).rounded()))
    let raw = Data(bytes)
    let decoded = LPPDecoder.decode(raw)
    #expect(decoded.contains { if case .gps = $0.value { true } else { false } })
    return TelemetryResponse(publicKeyPrefix: publicKey.prefix(6), tag: nil, rawData: raw)
  }

  @Test
  func `A GPS-only response persists the fix and exposes it live`() async {
    let store = StoringSnapshotPersister()
    let service = NodeSnapshotService(dataStore: store)
    let viewModel = NodeStatusViewModel()
    viewModel.configureForDirectTelemetry(publicKey: publicKey)
    viewModel.configure(contactService: { nil }, nodeSnapshotService: { service })

    await viewModel.handleTelemetryResponse(gpsOnlyResponse(lat: 37.7749, lon: -122.4194))

    #expect(viewModel.currentLocationFix?.latitude == 37.7749)
    let snapshots = await service.fetchSnapshots(for: publicKey)
    #expect(snapshots.count == 1, "A GPS-only response still captures a snapshot")
    #expect(snapshots.first?.latitude == 37.7749)
  }

  @Test
  func `A (0,0) response captures no fix`() async {
    let store = StoringSnapshotPersister()
    let service = NodeSnapshotService(dataStore: store)
    let viewModel = NodeStatusViewModel()
    viewModel.configureForDirectTelemetry(publicKey: publicKey)
    viewModel.configure(contactService: { nil }, nodeSnapshotService: { service })

    await viewModel.handleTelemetryResponse(gpsOnlyResponse(lat: 0, lon: 0))

    #expect(viewModel.currentLocationFix == nil)
    let snapshots = await service.fetchSnapshots(for: publicKey)
    #expect(snapshots.isEmpty, "A no-fix, no-telemetry response writes nothing")
  }

  @Test
  func `A plausible altitude is captured and persisted alongside the fix`() async {
    let store = StoringSnapshotPersister()
    let service = NodeSnapshotService(dataStore: store)
    let viewModel = NodeStatusViewModel()
    viewModel.configureForDirectTelemetry(publicKey: publicKey)
    viewModel.configure(contactService: { nil }, nodeSnapshotService: { service })

    await viewModel.handleTelemetryResponse(gpsOnlyResponse(lat: 37.7749, lon: -122.4194, alt: 42))

    #expect(viewModel.currentLocationFix?.altitude == 42)
    let snapshots = await service.fetchSnapshots(for: publicKey)
    #expect(snapshots.first?.altitude == 42)
  }

  @Test
  func `A sea-level altitude is retained, not dropped like null-island`() async {
    let store = StoringSnapshotPersister()
    let service = NodeSnapshotService(dataStore: store)
    let viewModel = NodeStatusViewModel()
    viewModel.configureForDirectTelemetry(publicKey: publicKey)
    viewModel.configure(contactService: { nil }, nodeSnapshotService: { service })

    await viewModel.handleTelemetryResponse(gpsOnlyResponse(lat: 37.7749, lon: -122.4194, alt: 0))

    #expect(viewModel.currentLocationFix?.altitude == 0)
  }

  @Test
  func `An implausible altitude is dropped but the fix survives`() async {
    let store = StoringSnapshotPersister()
    let service = NodeSnapshotService(dataStore: store)
    let viewModel = NodeStatusViewModel()
    viewModel.configureForDirectTelemetry(publicKey: publicKey)
    viewModel.configure(contactService: { nil }, nodeSnapshotService: { service })

    await viewModel.handleTelemetryResponse(gpsOnlyResponse(lat: 37.7749, lon: -122.4194, alt: 50000))

    #expect(viewModel.currentLocationFix?.latitude == 37.7749)
    #expect(viewModel.currentLocationFix?.altitude == nil, "Out-of-range altitude is dropped, not the fix")
  }
}

/// Minimal in-memory `NodeSnapshotPersisting` double. The app test target's shared
/// `MockPersistenceStore` stubs snapshot storage out, so the capture path needs a
/// double that actually retains rows to prove the fix is persisted.
private actor StoringSnapshotPersister: NodeSnapshotPersisting {
  private var snapshots: [NodeStatusSnapshotDTO] = []

  func recordNodeStatusSnapshot(
    nodePublicKey: Data,
    status: NodeStatusMetrics?,
    telemetry: [TelemetrySnapshotEntry]?,
    neighbors: [NeighborSnapshotEntry]?,
    location: NodeLocationFix?
  ) async throws -> UUID {
    let dto = NodeStatusSnapshotDTO(
      nodePublicKey: nodePublicKey,
      uptimeSeconds: status?.uptimeSeconds,
      neighborSnapshots: neighbors,
      telemetryEntries: telemetry,
      latitude: location?.latitude,
      longitude: location?.longitude,
      altitude: location?.altitude
    )
    snapshots.append(dto)
    return dto.id
  }

  func fetchNodeStatusSnapshots(nodePublicKey: Data, since: Date?) async throws -> [NodeStatusSnapshotDTO] {
    snapshots
      .filter { $0.nodePublicKey == nodePublicKey && (since == nil || $0.timestamp >= since!) }
      .sorted { $0.timestamp < $1.timestamp }
  }

  func fetchLatestNodeStatusSnapshot(nodePublicKey: Data) async throws -> NodeStatusSnapshotDTO? {
    snapshots.filter { $0.nodePublicKey == nodePublicKey }.max { $0.timestamp < $1.timestamp }
  }

  // swiftlint:disable:next function_parameter_count
  func saveNodeStatusSnapshot(
    nodePublicKey: Data,
    batteryMillivolts: UInt16?,
    lastSNR: Double?,
    lastRSSI: Int16?,
    noiseFloor: Int16?,
    uptimeSeconds: UInt32?,
    rxAirtimeSeconds: UInt32?,
    packetsSent: UInt32?,
    packetsReceived: UInt32?,
    receiveErrors: UInt32?,
    postedCount: UInt16?,
    postPushCount: UInt16?
  ) async throws -> UUID {
    UUID()
  }

  func saveTelemetryOnlySnapshot(nodePublicKey: Data, telemetryEntries: [TelemetrySnapshotEntry]) async throws -> UUID {
    UUID()
  }

  func updateSnapshotNeighbors(id: UUID, neighbors: [NeighborSnapshotEntry]) async throws {}
  func updateSnapshotTelemetry(id: UUID, telemetry: [TelemetrySnapshotEntry]) async throws {}
  func deleteOldNodeStatusSnapshots(olderThan date: Date) async throws {}
}
