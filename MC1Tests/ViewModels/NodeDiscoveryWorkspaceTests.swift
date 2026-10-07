import Foundation
@testable import MC1
@testable import MeshCore
import Testing

@MainActor
private final class NameResolutionGate {
  private(set) var isWaiting = false
  private var continuation: CheckedContinuation<Void, Never>?

  func wait() async {
    isWaiting = true
    await withCheckedContinuation { continuation = $0 }
  }

  func resume() {
    continuation?.resume()
    continuation = nil
  }
}

@Suite("Node Discovery workspace lifetime")
@MainActor
struct NodeDiscoveryWorkspaceTests {
  @Test
  func `expireScanIfDeadlinePassed keeps a future deadline scanning`() {
    let viewModel = NodeDiscoveryViewModel()
    viewModel.beginScanForTesting(deadline: Date().addingTimeInterval(15))
    viewModel.expireScanIfDeadlinePassed()

    #expect(viewModel.isScanning)
  }

  @Test
  func `expireScanIfDeadlinePassed keeps a nil deadline scanning`() {
    let viewModel = NodeDiscoveryViewModel()
    viewModel.beginScanForTesting(deadline: nil)
    viewModel.expireScanIfDeadlinePassed()

    #expect(viewModel.isScanning)
  }

  @Test
  func `reset stops the scan and clears results`() {
    let viewModel = NodeDiscoveryViewModel()
    viewModel.beginScanForTesting(deadline: Date().addingTimeInterval(15))
    viewModel.errorMessage = "scan failed"

    viewModel.reset()

    #expect(viewModel.isScanning == false)
    #expect(viewModel.results.isEmpty)
    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `foreground return finishes a scan whose deadline has passed`() {
    let viewModel = NodeDiscoveryViewModel()
    viewModel.beginScanForTesting(deadline: .distantPast)
    viewModel.expireScanIfDeadlinePassed()

    #expect(viewModel.isScanning == false)
  }

  @Test
  func `scan sends on the session it started with`() async throws {
    let prepared = try await prepareScan(seedName: nil)
    prepared.gate.resume()
    try await waitUntil(timeout: .seconds(1), "started session should be asked to send") {
      await prepared.transport.sentData.count == 1
    }

    #expect(prepared.viewModel.isScanning)
    prepared.viewModel.stopScan()
  }

  @Test
  func `nil session ends the scan and keeps results`() async throws {
    let prepared = try await prepareScan(seedName: "Tower")
    prepared.box.session = nil
    prepared.gate.resume()
    try await waitUntil(timeout: .seconds(1), "scan should end") {
      prepared.viewModel.isScanning == false
    }

    #expect(prepared.viewModel.results.map(\.name) == ["Tower"])
    #expect(await prepared.transport.sentData.isEmpty)
  }

  @Test
  func `replaced session ends the scan and keeps results`() async throws {
    let prepared = try await prepareScan(seedName: "Tower")
    let replacementTransport = MockTransport()
    try await replacementTransport.connect()
    prepared.box.session = MeshCoreSession(transport: replacementTransport)
    prepared.gate.resume()
    try await waitUntil(timeout: .seconds(1), "scan should end") {
      prepared.viewModel.isScanning == false
    }

    #expect(prepared.viewModel.results.map(\.name) == ["Tower"])
    #expect(await prepared.transport.sentData.isEmpty)
    #expect(await replacementTransport.sentData.isEmpty)
  }

  @Test
  func `cancelled scan ends and keeps results`() async throws {
    let prepared = try await prepareScan(seedName: "Tower")
    prepared.viewModel.stopScan()
    prepared.gate.resume()
    try await Task.sleep(for: .milliseconds(50))

    #expect(prepared.viewModel.isScanning == false)
    #expect(prepared.viewModel.results.map(\.name) == ["Tower"])
    #expect(await prepared.transport.sentData.isEmpty)
  }

  @Test
  func `successor scan survives the previous timeout resuming`() async throws {
    let live = try await makeLiveScan()
    defer { live.viewModel.stopScan() }
    let sentBefore = await live.transport.sentData.count
    live.viewModel.scan()
    try await acknowledgeDiscover(live, after: sentBefore)
    try await endDiscoverStream(live)

    let sentAfterEnd = await live.transport.sentData.count
    live.viewModel.scan()
    try await acknowledgeDiscover(live, after: sentAfterEnd)
    try await Task.sleep(for: .milliseconds(50))

    #expect(live.viewModel.isScanning)
    #expect(live.viewModel.isAwaitingDiscoverEventsForTesting)
    #expect(await live.transport.sentData.count > sentAfterEnd)
  }

  @Test
  func `ended scan cancels its timeout`() async throws {
    let live = try await makeLiveScan()
    defer { live.viewModel.stopScan() }
    live.viewModel.scanDurationForTesting = .seconds(1)
    let sentBefore = await live.transport.sentData.count
    live.viewModel.scan()
    try await acknowledgeDiscover(live, after: sentBefore)
    try await endDiscoverStream(live)

    live.viewModel.scanDurationForTesting = .seconds(30)
    let sentAfterEnd = await live.transport.sentData.count
    live.viewModel.scan()
    try await acknowledgeDiscover(live, after: sentAfterEnd)
    try await Task.sleep(for: .milliseconds(1200))

    #expect(live.viewModel.isScanning)
    #expect(live.viewModel.isAwaitingDiscoverEventsForTesting)
  }

  @Test
  func `superseded scan does not finish or clear results`() async throws {
    let transport = MockTransport()
    try await transport.connect()
    let box = SessionBox()
    box.session = MeshCoreSession(transport: transport)
    let viewModel = configuredViewModel(session: { box.session })
    let firstGate = NameResolutionGate()
    let secondGate = NameResolutionGate()
    viewModel.onNameResolutionFinishedForTesting = { await firstGate.wait() }
    viewModel.scan()
    try await waitUntil(timeout: .seconds(1), "first scan should reach the name gate") {
      firstGate.isWaiting
    }

    viewModel.onNameResolutionFinishedForTesting = { await secondGate.wait() }
    viewModel.scan()
    try await waitUntil(timeout: .seconds(1), "second scan should reach the name gate") {
      secondGate.isWaiting
    }

    viewModel.results = [makeResult(name: "Kept")]
    viewModel.errorMessage = "kept"
    firstGate.resume()
    try await Task.sleep(for: .milliseconds(50))

    #expect(viewModel.isScanning)
    #expect(viewModel.results.map(\.name) == ["Kept"])
    #expect(viewModel.errorMessage == "kept")
    viewModel.stopScan()
  }

  @Test
  func `superseded name load does not replace the current cache`() async throws {
    let transport = MockTransport()
    try await transport.connect()
    let box = SessionBox()
    box.session = MeshCoreSession(transport: transport)
    let viewModel = configuredViewModel(session: { box.session })
    let firstLoad = NameLoadGate(names: [:], added: [])
    let addedKey = Data(repeating: 0xAB, count: 32)
    viewModel.nameResolutionSupplierForTesting = { await firstLoad.load() }
    viewModel.scan()
    try await waitUntil(timeout: .seconds(1), "first name load should suspend") {
      firstLoad.isWaiting
    }

    viewModel.nameResolutionSupplierForTesting = {
      (names: [:], added: [addedKey])
    }
    viewModel.scan()
    try await waitUntil(timeout: .seconds(1), "second name load should publish its cache") {
      viewModel.addedPublicKeys == [addedKey]
    }

    firstLoad.resume()
    try await Task.sleep(for: .milliseconds(50))

    #expect(viewModel.addedPublicKeys == [addedKey])
    viewModel.stopScan()
  }

  private final class SessionBox {
    var session: MeshCoreSession?
  }

  private struct PreparedScan {
    let viewModel: NodeDiscoveryViewModel
    let transport: MockTransport
    let box: SessionBox
    let gate: NameResolutionGate
  }

  private func prepareScan(seedName: String?) async throws -> PreparedScan {
    let transport = MockTransport()
    try await transport.connect()
    let box = SessionBox()
    box.session = MeshCoreSession(transport: transport)
    let viewModel = NodeDiscoveryViewModel()
    viewModel.configure(dependencies: NodeDiscoveryViewModel.Dependencies(
      session: { box.session },
      dataStore: { nil },
      radioID: { UUID() },
      contactService: { nil },
      maxContacts: { nil }
    ))
    if let seedName {
      viewModel.results = [
        NodeDiscoveryResult(
          name: seedName,
          publicKey: Data(repeating: 0x11, count: 32),
          nodeType: 0x04,
          snr: 5,
          snrIn: 4,
          rssi: -80,
          scanFilter: .repeaters,
          receivedAt: Date()
        )
      ]
    }
    let gate = NameResolutionGate()
    viewModel.onNameResolutionFinishedForTesting = { await gate.wait() }
    viewModel.scan()
    try await waitUntil(timeout: .seconds(1), "name resolution should suspend") {
      gate.isWaiting
    }
    return PreparedScan(viewModel: viewModel, transport: transport, box: box, gate: gate)
  }

  private func configuredViewModel(
    session: @escaping @MainActor () -> MeshCoreSession?
  ) -> NodeDiscoveryViewModel {
    let viewModel = NodeDiscoveryViewModel()
    viewModel.configure(dependencies: NodeDiscoveryViewModel.Dependencies(
      session: session,
      dataStore: { nil },
      radioID: { UUID() },
      contactService: { nil },
      maxContacts: { nil }
    ))
    return viewModel
  }

  private func makeResult(name: String) -> NodeDiscoveryResult {
    NodeDiscoveryResult(
      name: name,
      publicKey: Data(repeating: 0x11, count: 32),
      nodeType: 0x04,
      snr: 5,
      snrIn: 4,
      rssi: -80,
      scanFilter: .repeaters,
      receivedAt: Date()
    )
  }

  private struct LiveScan {
    let viewModel: NodeDiscoveryViewModel
    let transport: MockTransport
    let session: MeshCoreSession
  }

  private func makeLiveScan() async throws -> LiveScan {
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)
    let start = Task { try await session.start() }
    try await waitUntil(timeout: .seconds(2), "session should send app start") {
      await transport.sentData.count >= 1
    }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await start.value
    await transport.clearSentData()
    let viewModel = configuredViewModel(session: { session })
    return LiveScan(viewModel: viewModel, transport: transport, session: session)
  }

  private func acknowledgeDiscover(_ live: LiveScan, after sentCount: Int) async throws {
    try await waitUntil(timeout: .seconds(2), "scan should send discover") {
      await live.transport.sentData.count > sentCount
    }
    await live.transport.simulateOK()
    try await waitUntil(timeout: .seconds(2), "scan should be listening") {
      live.viewModel.isAwaitingDiscoverEventsForTesting
    }
  }

  private func endDiscoverStream(_ live: LiveScan) async throws {
    await live.session.dispatcher.finishAllSubscriptions()
    try await waitUntil(timeout: .seconds(2), "scan should finish when the event stream ends") {
      live.viewModel.isScanning == false
    }
  }

  private func makeSelfInfoPacket() -> Data {
    var payload = Data()
    payload.append(1)
    payload.append(UInt8(bitPattern: 22))
    payload.append(UInt8(bitPattern: 22))
    payload.append(Data(repeating: 0x01, count: 32))
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
    payload.append(7)
    payload.append(5)
    payload.append(contentsOf: "Test".utf8)

    var packet = Data([ResponseCode.selfInfo.rawValue])
    packet.append(payload)
    return packet
  }
}

@MainActor
private final class NameLoadGate {
  private(set) var isWaiting = false
  private var continuation: CheckedContinuation<(names: [Data: String], added: Set<Data>), Never>?
  private let names: [Data: String]
  private let added: Set<Data>

  init(names: [Data: String], added: Set<Data>) {
    self.names = names
    self.added = added
  }

  func load() async -> (names: [Data: String], added: Set<Data>) {
    isWaiting = true
    return await withCheckedContinuation { continuation = $0 }
  }

  func resume() {
    continuation?.resume(returning: (names: names, added: added))
    continuation = nil
  }
}
