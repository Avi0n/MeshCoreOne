import Foundation
@testable import MC1
@testable import MC1Services
@testable import MeshCore
import Testing

@MainActor
private final class AsyncGate<Value: Sendable> {
  private(set) var started = false
  private var continuation: CheckedContinuation<Value, Error>?

  func run(_ body: () async throws -> Value) async throws -> Value {
    started = true
    return try await body()
  }

  func wait() async throws -> Value {
    started = true
    return try await withCheckedThrowingContinuation { continuation = $0 }
  }

  func succeed(_ value: Value) {
    continuation?.resume(returning: value)
    continuation = nil
  }

  func fail(_ error: Error) {
    continuation?.resume(throwing: error)
    continuation = nil
  }
}

@Suite("Node Discovery lifetime")
@MainActor
struct NodeDiscoveryLifetimeTests {
  private let radioID = UUID()

  private func makeViewModel(session: MeshCoreSession? = nil) -> (NodeDiscoveryViewModel, Box) {
    let box = Box(session: session)
    let viewModel = NodeDiscoveryViewModel()
    viewModel.configure(dependencies: NodeDiscoveryViewModel.Dependencies(
      session: { box.session },
      dataStore: { box.store },
      radioID: { box.radioID },
      contactService: { box.contactService },
      maxContacts: { nil }
    ))
    viewModel.sendDiscoverForTesting = { _ in box.discoverTag }
    return (viewModel, box)
  }

  private func discover(tag: UInt32, publicKey: Data) -> MeshEvent {
    .discoverResponse(DiscoverResponse(
      nodeType: ContactType.repeater.rawValue,
      snrIn: 4,
      snr: 6,
      rssi: -70,
      pathLength: 0,
      tag: withUnsafeBytes(of: tag.littleEndian) { Data($0) },
      publicKey: publicKey
    ))
  }

  private func result(publicKey: Data) -> NodeDiscoveryResult {
    NodeDiscoveryResult(
      name: "Repeater",
      publicKey: publicKey,
      nodeType: ContactType.repeater.rawValue,
      snr: 6,
      snrIn: 4,
      rssi: -70,
      scanFilter: .repeaters,
      receivedAt: Date()
    )
  }

  @Test
  func `hiding during load, send, and listen still appends the response`() async throws {
    let session = MeshCoreSession(transport: MockTransport())
    let (viewModel, box) = makeViewModel(session: session)
    let names = Pause()
    let send = AsyncGate<UInt32>()
    viewModel.loadNamesForTesting = { await names.pause() }
    viewModel.sendDiscoverForTesting = { _ in try await send.wait() }
    viewModel.scanDurationForTesting = 30
    let key = Data(repeating: 0xAB, count: 32)

    viewModel.scan()
    try await waitUntil(timeout: .seconds(1), "name load should start") { names.started }
    viewModel.setWorkspaceVisible(false)
    names.resume()

    try await waitUntil(timeout: .seconds(1), "send should start") { send.started }
    viewModel.setWorkspaceVisible(false)
    send.succeed(box.discoverTag)

    try await waitUntil(timeout: .seconds(1), "listen should start") { viewModel.isListeningForTesting }
    viewModel.setWorkspaceVisible(false)
    await session.dispatcher.dispatch(discover(tag: box.discoverTag, publicKey: key))

    try await waitUntil(timeout: .seconds(1), "hidden scan should append") {
      viewModel.results.contains { $0.publicKey == key }
    }
    #expect(viewModel.isScanning)
  }

  @Test
  func `filter change, stop, and a replacement scan ignore the old response`() async throws {
    let session = MeshCoreSession(transport: MockTransport())
    let (viewModel, box) = makeViewModel(session: session)
    viewModel.scanDurationForTesting = 30
    let key = Data(repeating: 0x11, count: 32)
    viewModel.scan()
    try await waitUntil(timeout: .seconds(1), "listen should start") { viewModel.isListeningForTesting }

    viewModel.filter = .sensors
    viewModel.stopScan()
    await session.dispatcher.dispatch(discover(tag: box.discoverTag, publicKey: key))
    try await Task.sleep(for: .milliseconds(40))
    #expect(viewModel.results.isEmpty)

    box.discoverTag = 9
    viewModel.filter = .repeaters
    viewModel.scan()
    try await waitUntil(timeout: .seconds(1), "replacement should listen") { viewModel.isListeningForTesting }
    await session.dispatcher.dispatch(discover(tag: 4, publicKey: key))
    try await Task.sleep(for: .milliseconds(40))
    #expect(viewModel.results.isEmpty)
    await session.dispatcher.dispatch(discover(tag: 9, publicKey: key))
    try await waitUntil(timeout: .seconds(1), "replacement tag should append") {
      viewModel.results.contains { $0.publicKey == key }
    }
  }

  @Test
  func `an old timeout does not cancel the replacement scan`() async throws {
    let session = MeshCoreSession(transport: MockTransport())
    let (viewModel, _) = makeViewModel(session: session)
    viewModel.scanDurationForTesting = 0.05
    viewModel.scan()
    try await waitUntil(timeout: .seconds(1), "first scan should listen") { viewModel.isListeningForTesting }

    viewModel.scanDurationForTesting = 30
    viewModel.scan()
    try await waitUntil(timeout: .seconds(1), "replacement should listen") { viewModel.isListeningForTesting }
    try await Task.sleep(for: .milliseconds(80))

    #expect(viewModel.isScanning)
  }

  @Test
  func `reset clears results and a late add does not publish`() async throws {
    let (viewModel, _) = makeViewModel()
    let gate = AsyncGate<Void>()
    viewModel.addContactForTesting = { _ in _ = try await gate.wait() }
    let key = Data(repeating: 0x22, count: 32)
    viewModel.results = [
      NodeDiscoveryResult(
        name: "Old",
        publicKey: key,
        nodeType: ContactType.repeater.rawValue,
        snr: 1,
        snrIn: 1,
        rssi: -90,
        scanFilter: .repeaters,
        receivedAt: Date()
      )
    ]
    viewModel.addNode(result(publicKey: key))
    try await waitUntil(timeout: .seconds(1), "add should start") { gate.started }

    viewModel.reset()
    gate.fail(MeshCoreError.timeout)
    try await Task.sleep(for: .milliseconds(40))

    #expect(viewModel.results.isEmpty)
    #expect(viewModel.addedPublicKeys.isEmpty)
    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `a scan snapshot keeps a current add and a second add stays current`() async throws {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let session = MeshCoreSession(transport: MockTransport())
    let (viewModel, box) = makeViewModel(session: session)
    box.store = store
    let first = AsyncGate<Void>()
    let second = AsyncGate<Void>()
    var adds = 0
    viewModel.addContactForTesting = { _ in
      adds += 1
      if adds == 1 {
        _ = try await first.wait()
      } else {
        _ = try await second.wait()
      }
    }
    let keyA = Data(repeating: 0x31, count: 32)
    let keyB = Data(repeating: 0x32, count: 32)
    viewModel.addNode(result(publicKey: keyA))
    try await waitUntil(timeout: .seconds(1), "first add should start") { first.started }

    viewModel.scanDurationForTesting = 30
    viewModel.scan()
    try await waitUntil(timeout: .seconds(1), "scan should pass the snapshot") {
      viewModel.isListeningForTesting
    }
    #expect(viewModel.addedPublicKeys.contains(keyA))

    viewModel.addNode(result(publicKey: keyB))
    try await waitUntil(timeout: .seconds(1), "second add should start") { second.started }
    viewModel.scan()
    try await waitUntil(timeout: .seconds(1), "replacement scan should listen") {
      viewModel.isListeningForTesting
    }
    second.succeed(())
    try await waitUntil(timeout: .seconds(1), "second add should publish") {
      viewModel.addedPublicKeys.contains(keyB)
    }
    #expect(viewModel.addedPublicKeys.contains(keyA))
  }

  @Test
  func `a passed deadline finishes on foreground return and a future one does not`() async throws {
    let session = MeshCoreSession(transport: MockTransport())
    let (viewModel, box) = makeViewModel(session: session)
    viewModel.scanDurationForTesting = 30
    let key = Data(repeating: 0x44, count: 32)
    viewModel.scan()
    try await waitUntil(timeout: .seconds(1), "listen should start") { viewModel.isListeningForTesting }
    await session.dispatcher.dispatch(discover(tag: box.discoverTag, publicKey: key))
    try await waitUntil(timeout: .seconds(1), "response should stay") {
      viewModel.results.contains { $0.publicKey == key }
    }

    viewModel.expireScanIfDeadlinePassed()
    #expect(viewModel.isScanning)

    viewModel.setScanDeadlineForTesting(Date().addingTimeInterval(-1))
    viewModel.expireScanIfDeadlinePassed()
    #expect(viewModel.isScanning == false)
    #expect(viewModel.results.contains { $0.publicKey == key })
  }

  @Test
  func `stream end does not finish the scan`() async throws {
    let session = MeshCoreSession(transport: MockTransport())
    let (viewModel, _) = makeViewModel(session: session)
    viewModel.scanDurationForTesting = 30
    viewModel.scan()
    try await waitUntil(timeout: .seconds(1), "listen should start") { viewModel.isListeningForTesting }

    await session.stop()
    try await waitUntil(timeout: .seconds(1), "stream end should wait") {
      viewModel.isWaitingForSessionForTesting
    }
    #expect(viewModel.isScanning)
    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `same-device ready reattaches and a loss reset does not`() async throws {
    let first = MeshCoreSession(transport: MockTransport())
    let (viewModel, box) = makeViewModel(session: first)
    viewModel.scanDurationForTesting = 30
    let key = Data(repeating: 0x55, count: 32)
    viewModel.scan()
    try await waitUntil(timeout: .seconds(1), "first listen should start") { viewModel.isListeningForTesting }

    let replacement = MeshCoreSession(transport: MockTransport())
    box.session = replacement
    await first.stop()
    viewModel.reattachIfWaiting()
    try await waitUntil(timeout: .seconds(1), "replacement session should be listening") {
      viewModel.isListeningForTesting
    }
    await replacement.dispatcher.dispatch(discover(tag: box.discoverTag, publicKey: key))
    try await waitUntil(timeout: .seconds(1), "reattached scan should append") {
      viewModel.results.contains { $0.publicKey == key }
    }

    viewModel.reset()
    let afterLoss = MeshCoreSession(transport: MockTransport())
    box.session = afterLoss
    viewModel.reattachIfWaiting()
    await afterLoss.dispatcher.dispatch(discover(tag: box.discoverTag, publicKey: key))
    try await Task.sleep(for: .milliseconds(40))
    #expect(viewModel.results.isEmpty)
    #expect(viewModel.isScanning == false)
    #expect(viewModel.errorMessage == nil)
  }

  @Test
  func `a non-discover burst does not resume the scan and a discover response is appended`() async throws {
    let first = MeshCoreSession(transport: MockTransport())
    let (viewModel, box) = makeViewModel(session: first)
    viewModel.scanDurationForTesting = 30
    viewModel.scan()
    try await waitUntil(timeout: .seconds(1), "listen should start") { viewModel.isListeningForTesting }

    let replacement = MeshCoreSession(transport: MockTransport())
    box.session = replacement
    await first.stop()
    viewModel.reattachIfWaiting()
    try await waitUntil(timeout: .seconds(1), "filtered listen should start") {
      viewModel.isListeningForTesting
    }
    let before = viewModel.listenLoopIterationsForTesting
    let key = Data(repeating: 0x66, count: 32)

    for _ in 0..<120 {
      await replacement.dispatcher.dispatch(.noMoreMessages)
    }
    await replacement.dispatcher.dispatch(discover(tag: box.discoverTag, publicKey: key))
    try await waitUntil(timeout: .seconds(1), "discover response should append") {
      viewModel.results.contains { $0.publicKey == key }
    }
    #expect(viewModel.listenLoopIterationsForTesting == before + 1)
  }

  @Test
  func `reconcileGhostIdentity during add stores the contact on the new radioID`() async throws {
    let store = try PersistenceStore(modelContainer: PersistenceStore.createContainer(inMemory: true))
    let currentRadio = UUID()
    let ghostRadio = UUID()
    let currentID = UUID()
    let ghostKey = Data(repeating: 0x22, count: 32)
    try await store.saveDevice(makeDevice(id: currentID, radioID: currentRadio, publicKey: Data(repeating: 0x11, count: 32), isActive: true))
    try await store.saveDevice(makeDevice(id: UUID(), radioID: ghostRadio, publicKey: ghostKey, isActive: false))

    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)
    try await startSession(session, transport: transport)
    let contactService = ContactService(
      session: session,
      dataStore: store,
      syncCoordinator: nil,
      cleanupCoordinator: nil
    )
    let box = Box(session: session)
    box.store = store
    box.radioID = currentRadio
    box.contactService = contactService
    let viewModel = NodeDiscoveryViewModel()
    viewModel.configure(dependencies: NodeDiscoveryViewModel.Dependencies(
      session: { box.session },
      dataStore: { box.store },
      radioID: { box.radioID },
      contactService: { box.contactService },
      maxContacts: { nil }
    ))

    let contactKey = Data(repeating: 0xAB, count: 32)
    let sentBefore = await transport.sentData.count
    viewModel.addNode(result(publicKey: contactKey))
    try await waitUntil(timeout: .seconds(1), "add should reach the radio") {
      await transport.sentData.count > sentBefore
    }

    let reconciled = try await store.reconcileGhostIdentity(currentDeviceID: currentID, newPublicKey: ghostKey)
    #expect(reconciled == ghostRadio)
    box.radioID = ghostRadio
    await transport.simulateOK()

    try await waitUntil(timeout: .seconds(1), "add should publish") {
      viewModel.addedPublicKeys.contains(contactKey)
    }
    let previous = try await store.fetchContacts(radioID: currentRadio)
    let current = try await store.fetchContacts(radioID: ghostRadio)
    #expect(previous.isEmpty)
    #expect(current.contains { $0.publicKey == contactKey })
    await session.stop()
  }

  private func makeDevice(id: UUID, radioID: UUID, publicKey: Data, isActive: Bool) -> DeviceDTO {
    DeviceDTO(
      id: id,
      radioID: radioID,
      publicKey: publicKey,
      nodeName: "Radio",
      firmwareVersion: 1,
      firmwareVersionString: "1.12.0",
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
      manualAddContacts: false,
      multiAcks: 2,
      telemetryModeBase: 2,
      telemetryModeLoc: 0,
      telemetryModeEnv: 0,
      advertLocationPolicy: 0,
      lastConnected: Date(),
      lastContactSync: 0,
      isActive: isActive,
      ocvPreset: nil,
      customOCVArrayString: nil
    )
  }

  private func startSession(_ session: MeshCoreSession, transport: MockTransport) async throws {
    let startTask = Task { try await session.start() }
    try await waitUntil(timeout: .seconds(1), "app start should send") {
      await transport.sentData.count == 1
    }
    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value
  }

  private func makeSelfInfoPacket() -> Data {
    var payload = Data([ResponseCode.selfInfo.rawValue])
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
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(869_525).littleEndian) { Array($0) })
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(250_000).littleEndian) { Array($0) })
    payload.append(11)
    payload.append(5)
    return payload
  }
}

@MainActor
private final class Pause {
  private(set) var started = false
  private var continuation: CheckedContinuation<Void, Never>?

  func pause() async {
    started = true
    await withCheckedContinuation { continuation = $0 }
  }

  func resume() {
    continuation?.resume()
    continuation = nil
  }
}

@MainActor
private final class Box {
  var session: MeshCoreSession?
  var store: PersistenceStore?
  var radioID: UUID?
  var contactService: ContactService?
  var discoverTag: UInt32 = 4

  init(session: MeshCoreSession?) {
    self.session = session
    radioID = UUID()
  }
}
