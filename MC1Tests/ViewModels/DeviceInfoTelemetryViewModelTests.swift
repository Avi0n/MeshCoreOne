import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Device Info self telemetry")
@MainActor
struct DeviceInfoTelemetryViewModelTests {
  private let publicKey = Data(repeating: 0x01, count: ProtocolLimits.publicKeySize)
  private let latitude = 37.7749
  private let longitude = -122.4194

  @Test
  func `a valid reply shows voltage and location and writes the service current at apply`() async throws {
    let opened = try await openLink()
    let otherStore = SelfTelemetryStore()
    let task = Task { await opened.model.loadTelemetry() }
    try await waitUntil("self telemetry command") { await self.commandCount(opened.transport) == 1 }
    opened.box.snapshots = NodeSnapshotService(dataStore: otherStore)
    await opened.transport.simulateReceive(telemetryFrame())
    await task.value

    #expect(opened.model.cachedDataPoints.allSatisfy { $0.channel != 0 })
    #expect(opened.model.cachedDataPoints.contains { $0.channel == 1 && $0.type == .voltage })
    #expect(opened.model.currentLocationFix?.latitude == latitude)
    #expect(opened.model.currentLocationFix?.longitude == longitude)
    #expect(opened.model.telemetryLoaded)
    #expect(opened.model.telemetrySectionError == nil)
    #expect(await opened.store.rows().isEmpty)
    let saved = await otherStore.rows()
    #expect(saved.count == 1)
    #expect(saved.first?.latitude == latitude)
    #expect(saved.first?.telemetryEntries?.count == 1)
    #expect(saved.first?.telemetryEntries?.first?.channel == 1)
    #expect(saved.first?.selfRadioID == opened.box.device?.radioID)

    let points = opened.model.cachedDataPoints
    opened.box.device = opened.box.device?.copy {
      $0.nodeName = "Renamed"
      $0.ocvPreset = OCVPreset.liFePO4.rawValue
    }
    opened.model.synchronizePresentation()
    #expect(opened.model.cachedDataPoints == points)
    #expect(opened.model.displayName == "Renamed")
    #expect(opened.model.ocvValues == OCVPreset.liFePO4.ocvArray)
    #expect(opened.model.mapLabel == "Renamed")
  }

  @Test
  func `a held save keeps the spinner and the next read owns loading`() async throws {
    let opened = try await openLink()
    await opened.store.holdNextWrite()
    let first = Task { await opened.model.loadTelemetry() }
    try await waitUntil("first command") { await self.commandCount(opened.transport) == 1 }
    await opened.transport.simulateReceive(telemetryFrame())
    try await waitUntil("first save held") { await opened.store.isHoldingWrite }

    let sentWhileHeld = await opened.transport.sentData.count
    await opened.model.loadTelemetry()
    #expect(await opened.transport.sentData.count == sentWhileHeld)
    #expect(opened.model.isLoadingTelemetry)

    await opened.store.resumeWrite()
    await first.value
    #expect(opened.model.isLoadingTelemetry == false)
    #expect(await opened.store.rows().count == 1)

    await opened.store.holdNextWrite()
    let second = Task { await opened.model.loadTelemetry() }
    try await waitUntil("second command") { await self.commandCount(opened.transport) == 2 }
    await opened.transport.simulateReceive(telemetryFrame())
    try await waitUntil("second save held") { await opened.store.isHoldingWrite }
    #expect(opened.model.isLoadingTelemetry)
    await opened.store.resumeWrite()
    await second.value
    #expect(opened.model.isLoadingTelemetry == false)
    #expect(await opened.store.rows().count == 2)
  }

  @Test
  func `a reply after leaving ready does not apply and the next expansion can load`() async throws {
    let opened = try await openLink()
    try await deliverStale(opened) { $0.ready = false }
    opened.box.ready = true
    opened.model.synchronizePresentation()
    #expect(opened.model.telemetryExpanded == false)
    #expect(opened.model.telemetryLoaded == false)

    let task = Task { await opened.model.loadTelemetry() }
    try await waitUntil("reconnect command") { await self.commandCount(opened.transport) == 2 }
    await opened.transport.simulateReceive(telemetryFrame())
    await task.value
    #expect(opened.model.currentLocationFix?.latitude == latitude)
    #expect(await opened.store.rows().count == 1)
  }

  @Test
  func `a replaced ready service cannot clear the newer spinner or write the old store`() async throws {
    let opened = try await openLink()
    let first = Task { await opened.model.loadTelemetry() }
    try await waitUntil("original command") { await self.commandCount(opened.transport) == 1 }

    let replacement = try await startedService()
    let replacementStore = SelfTelemetryStore()
    opened.box.service = replacement.service
    opened.box.snapshots = NodeSnapshotService(dataStore: replacementStore)
    let second = Task { await opened.model.loadTelemetry() }
    try await waitUntil("replacement command") { await self.commandCount(replacement.transport) == 1 }

    await opened.transport.simulateReceive(telemetryFrame())
    await first.value
    #expect(opened.model.isLoadingTelemetry)
    #expect(opened.model.telemetry == nil)
    #expect(await opened.store.rows().isEmpty)

    await replacement.transport.simulateReceive(telemetryFrame())
    await second.value
    #expect(opened.model.currentLocationFix?.latitude == latitude)
    #expect(opened.model.isLoadingTelemetry == false)
    #expect(await replacementStore.rows().count == 1)
    #expect(await opened.store.rows().isEmpty)
  }

  @Test
  func `switching radios drops the in flight reply`() async throws {
    let opened = try await openLink()
    try await deliverStale(opened) { box in
      box.device = box.device?.copy {
        $0.id = UUID()
        $0.radioID = UUID()
      }
    }
  }

  @Test
  func `leaving and reopening device info drops the in flight reply`() async throws {
    let opened = try await openLink()
    try await deliverStale(opened) { $0.presentationRevision += 1 }
    try await deliverStale(opened) { $0.deviceInfoSelected = false }
  }

  @Test
  func `navigating to the selected device info root drops the in flight reply`() async throws {
    let opened = try await openLink()
    try await deliverStale(opened) { $0.rootGeneration += 1 }
  }

  @Test
  func `missing short and unready links send no command`() async throws {
    let opened = try await openLink()
    let sent = await opened.transport.sentData.count

    opened.box.device = nil
    await opened.model.loadTelemetry()
    opened.box.device = makeRadio(publicKey: Data([0x01]))
    await opened.model.loadTelemetry()
    opened.box.device = makeRadio(publicKey: publicKey)
    opened.box.ready = false
    await opened.model.loadTelemetry()
    #expect(await opened.transport.sentData.count == sent)

    opened.box.ready = true
    let task = Task { await opened.model.loadTelemetry() }
    try await waitUntil("in-flight command") { await self.commandCount(opened.transport) == 1 }
    let duringFlight = await opened.transport.sentData.count
    await opened.model.loadTelemetry()
    #expect(await opened.transport.sentData.count == duringFlight)
    #expect(duringFlight == sent + 1)
    await opened.transport.simulateReceive(telemetryFrame())
    await task.value
  }

  @Test
  func `first load and reload timeouts set the section error without a new snapshot`() async throws {
    let opened = try await openLink(timeout: 0.4)
    await opened.model.loadTelemetry()
    #expect(opened.model.telemetrySectionError == L10n.Localizable.Error.MeshCore.timeout)
    #expect(opened.model.telemetry == nil)
    #expect(opened.model.telemetryLoaded == false)
    #expect(await opened.store.rows().isEmpty)
    #expect(opened.model.isLoadingTelemetry == false)

    let task = Task { await opened.model.loadTelemetry() }
    try await waitUntil("timeout recovery command") { await self.commandCount(opened.transport) == 2 }
    await opened.transport.simulateReceive(telemetryFrame())
    await task.value
    let points = opened.model.cachedDataPoints
    #expect(points.isEmpty == false)

    await opened.model.loadTelemetry()
    #expect(opened.model.telemetrySectionError == L10n.Localizable.Error.MeshCore.timeout)
    #expect(opened.model.cachedDataPoints == points)
    #expect(opened.model.telemetryLoaded)
    #expect(await opened.store.rows().count == 1)
    #expect(opened.model.isLoadingTelemetry == false)
  }

  private func deliverStale(
    _ opened: OpenedLink,
    mutate: (LinkBox) -> Void
  ) async throws {
    let task = Task { await opened.model.loadTelemetry() }
    let expected = await commandCount(opened.transport) + 1
    try await waitUntil("stale command") { await self.commandCount(opened.transport) == expected }
    mutate(opened.box)
    await opened.transport.simulateReceive(telemetryFrame())
    await task.value
    #expect(opened.model.telemetry == nil)
    #expect(opened.model.isLoadingTelemetry)
    #expect(await opened.store.rows().isEmpty)
    opened.model.synchronizePresentation()
    #expect(opened.model.isLoadingTelemetry == false)
    #expect(opened.model.telemetryExpanded == false)
    #expect(opened.model.telemetrySectionError == nil)
    #expect(opened.model.cachedDataPoints.isEmpty)
  }

  private func openLink(timeout: TimeInterval = 2) async throws -> OpenedLink {
    let started = try await startedService(timeout: timeout)
    let store = SelfTelemetryStore()
    let box = LinkBox(
      device: makeRadio(publicKey: publicKey),
      service: started.service,
      snapshots: NodeSnapshotService(dataStore: store)
    )
    let model = DeviceInfoTelemetryViewModel()
    box.bind(model)
    return OpenedLink(transport: started.transport, store: store, model: model, box: box)
  }

  private func startedService(timeout: TimeInterval = 2) async throws -> StartedService {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: timeout)
    )
    let start = Task { try await session.start() }
    try await waitUntil("app start") { await transport.sentData.count == 1 }
    await transport.simulateReceive(selfInfoPacket())
    try await start.value
    let container = try await ServiceContainer.forTesting(session: session)
    return StartedService(transport: transport, service: container.binaryProtocolService)
  }

  private func telemetryFrame() -> Data {
    var frame = Data([ResponseCode.telemetryResponse.rawValue, 0x00])
    frame.append(publicKey.prefix(6))
    frame.append(sampleLPP())
    return frame
  }

  private func sampleLPP() -> Data {
    func int24BE(_ scaled: Int32) -> [UInt8] {
      [UInt8((scaled >> 16) & 0xFF), UInt8((scaled >> 8) & 0xFF), UInt8(scaled & 0xFF)]
    }
    var bytes: [UInt8] = [0x00, LPPSensorType.voltage.rawValue, 0x00, 0x64]
    bytes += [0x01, LPPSensorType.voltage.rawValue, 0x01, 0x90]
    bytes += [0x01, LPPSensorType.gps.rawValue]
    bytes += int24BE(Int32((latitude * 10000).rounded()))
    bytes += int24BE(Int32((longitude * 10000).rounded()))
    bytes += int24BE(0)
    return Data(bytes)
  }

  private func selfInfoPacket() -> Data {
    var payload = Data([ResponseCode.selfInfo.rawValue])
    payload.append(1)
    payload.append(UInt8(bitPattern: 22))
    payload.append(UInt8(bitPattern: 22))
    payload.append(publicKey)
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(869_525).littleEndian) { Array($0) })
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(250_000).littleEndian) { Array($0) })
    payload.append(11)
    payload.append(5)
    return payload
  }

  private func commandCount(_ transport: MockTransport) async -> Int {
    await transport.sentData.filter { $0.first == CommandCode.getSelfTelemetry.rawValue }.count
  }

  private func waitUntil(
    _ reason: String,
    timeout: Duration = .seconds(2),
    _ condition: @escaping @MainActor () async -> Bool
  ) async throws {
    let deadline = ContinuousClock.now.advanced(by: timeout)
    while ContinuousClock.now < deadline {
      if await condition() { return }
      try await Task.sleep(for: .milliseconds(20))
    }
    let matched = await condition()
    #expect(matched, "Timed out waiting until \(reason)")
  }

  private func makeRadio(publicKey: Data) -> DeviceDTO {
    DeviceDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: publicKey,
      nodeName: "Field",
      firmwareVersion: 11,
      firmwareVersionString: "1.15.0",
      manufacturerName: "Test",
      buildDate: "2025-01-01",
      maxContacts: 100,
      maxChannels: 8,
      frequency: 915_000,
      bandwidth: 250_000,
      spreadingFactor: 10,
      codingRate: 5,
      txPower: 20,
      maxTxPower: 20,
      latitude: 0,
      longitude: 0,
      blePin: 0,
      clientRepeat: false,
      manualAddContacts: false,
      multiAcks: 2,
      telemetryModeBase: 2,
      telemetryModeLoc: 0,
      telemetryModeEnv: 0,
      advertLocationPolicy: 0,
      lastConnected: Date(),
      lastContactSync: 0,
      isActive: true,
      ocvPreset: nil,
      customOCVArrayString: nil
    )
  }
}

private struct OpenedLink {
  let transport: MockTransport
  let store: SelfTelemetryStore
  let model: DeviceInfoTelemetryViewModel
  let box: LinkBox
}

private struct StartedService {
  let transport: MockTransport
  let service: BinaryProtocolService
}

@MainActor
private final class LinkBox {
  var device: DeviceDTO?
  var ready = true
  var deviceInfoSelected = true
  var presentationRevision = 0
  var rootGeneration = 0
  var service: BinaryProtocolService
  var snapshots: NodeSnapshotService

  init(device: DeviceDTO, service: BinaryProtocolService, snapshots: NodeSnapshotService) {
    self.device = device
    self.service = service
    self.snapshots = snapshots
  }

  func bind(_ model: DeviceInfoTelemetryViewModel) {
    model.configure(
      device: { self.device },
      readyScope: { self.scope() },
      nodeSnapshotService: { self.snapshots }
    )
    model.synchronizePresentation()
  }

  func scope() -> DeviceInfoTelemetryViewModel.ReadyScope? {
    guard ready, deviceInfoSelected else { return nil }
    return DeviceInfoTelemetryViewModel.ReadyScope(
      binaryProtocolService: service,
      presentationRevision: presentationRevision,
      rootNavigationGeneration: rootGeneration
    )
  }
}

private actor SelfTelemetryStore: NodeSnapshotPersisting {
  private var snapshots: [NodeStatusSnapshotDTO] = []
  private var holdNext = false
  private var continuation: CheckedContinuation<Void, Never>?
  private(set) var isHoldingWrite = false

  func holdNextWrite() {
    holdNext = true
  }

  func resumeWrite() {
    isHoldingWrite = false
    continuation?.resume()
    continuation = nil
  }

  func rows() -> [NodeStatusSnapshotDTO] {
    snapshots
  }

  func recordSelfTelemetrySnapshot(
    deviceID: UUID,
    radioID: UUID,
    nodePublicKey: Data,
    telemetry: [TelemetrySnapshotEntry]?,
    location: NodeLocationFix?
  ) async throws -> UUID? {
    if holdNext {
      holdNext = false
      isHoldingWrite = true
      await withCheckedContinuation { continuation = $0 }
    }
    let dto = NodeStatusSnapshotDTO(
      nodePublicKey: nodePublicKey,
      selfRadioID: radioID,
      telemetryEntries: telemetry,
      latitude: location?.latitude,
      longitude: location?.longitude,
      altitude: location?.altitude
    )
    snapshots.append(dto)
    _ = deviceID
    return dto.id
  }

  func fetchSelfTelemetrySnapshots(
    radioID: UUID,
    nodePublicKey: Data
  ) async throws -> [NodeStatusSnapshotDTO] {
    snapshots.filter { $0.selfRadioID == radioID && $0.nodePublicKey == nodePublicKey }
  }

  func recordNodeStatusSnapshot(
    nodePublicKey: Data,
    status: NodeStatusMetrics?,
    telemetry: [TelemetrySnapshotEntry]?,
    neighbors: [NeighborSnapshotEntry]?,
    location: NodeLocationFix?
  ) async throws -> UUID {
    UUID()
  }

  func fetchNodeStatusSnapshots(nodePublicKey: Data, since: Date?) async throws -> [NodeStatusSnapshotDTO] {
    []
  }

  func fetchLatestNodeStatusSnapshot(nodePublicKey: Data) async throws -> NodeStatusSnapshotDTO? {
    nil
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

  func saveTelemetryOnlySnapshot(
    nodePublicKey: Data,
    telemetryEntries: [TelemetrySnapshotEntry]
  ) async throws -> UUID {
    UUID()
  }

  func updateSnapshotNeighbors(id: UUID, neighbors: [NeighborSnapshotEntry]) async throws {}
  func updateSnapshotTelemetry(id: UUID, telemetry: [TelemetrySnapshotEntry]) async throws {}
  func deleteOldNodeStatusSnapshots(olderThan date: Date) async throws {}
}
