import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@MainActor
final class ConnectionWiFiEstablishmentHarness {
  let manager: ConnectionManager
  let bluetooth: ConnectionTestRadioTransport
  let modeledWiFi: ConnectionTestRadioTransport
  let peer: ConnectionLoopbackRadioPeer?
  let saved: DeviceDTO
  private let suite: String
  private let selection = Gate()
  private var cancellations: [() -> Void] = []
  private var joins: [() async -> Void] = []
  private var candidates: [ConnectionResource] = []
  private(set) var selectedRequestID: UUID?

  var host: String {
    "127.0.0.1"
  }

  var port: UInt16 {
    guard case let .wifi(_, port, _) = saved.wifiConnectionMethod else { preconditionFailure("Missing test endpoint") }
    return port
  }

  private init(
    manager: ConnectionManager, bluetooth: ConnectionTestRadioTransport, modeledWiFi: ConnectionTestRadioTransport,
    peer: ConnectionLoopbackRadioPeer?, saved: DeviceDTO, suite: String
  ) {
    self.manager = manager
    self.bluetooth = bluetooth
    self.modeledWiFi = modeledWiFi
    self.peer = peer
    self.saved = saved
    self.suite = suite
  }

  static func make(realSocket: Bool, wifiLast: Bool = true) async throws -> ConnectionWiFiEstablishmentHarness {
    let suite = "test.wifi-establishment.\(UUID())"
    let defaults = try #require(UserDefaults(suiteName: suite))
    let container = try PersistenceStore.createContainer(inMemory: true)
    let publicKey = Data(repeating: 0xA1, count: ProtocolLimits.publicKeySize)
    let bluetooth = ConnectionTestRadioTransport(publicKey: publicKey)
    let modeledWiFi = ConnectionTestRadioTransport(publicKey: publicKey)
    let peer = realSocket ? try await ConnectionLoopbackRadioPeer.make(publicKey: publicKey) : nil
    do {
      let port: UInt16 = if let peer { try #require(await peer.port) } else { 4242 }
      let pairing = MockAccessorySetupKitService()
      pairing.isSessionActive = false
      let makeSocket: @Sendable () -> any WiFiMeshTransport = if realSocket { { WiFiTransport() } } else { { modeledWiFi } }
      let manager = ConnectionManager(
        modelContainer: container, defaults: defaults, stateMachine: MockBLEStateMachine(), transport: bluetooth,
        pairing: AccessorySetupPairingService(accessorySetupKit: pairing), makeWiFiTransport: makeSocket
      )
      let device = DeviceDTO.testDevice(publicKey: publicKey, isActive: false).copy {
        let ble = ConnectionMethod.bluetooth(peripheralUUID: $0.id, displayName: nil)
        let wifi = ConnectionMethod.wifi(host: "127.0.0.1", port: port, displayName: nil)
        $0.connectionMethods = wifiLast ? [ble, wifi] : [wifi, ble]
      }
      try await manager.persistenceStore.saveDevice(device)
      return ConnectionWiFiEstablishmentHarness(
        manager: manager, bluetooth: bluetooth, modeledWiFi: modeledWiFi, peer: peer, saved: device, suite: suite
      )
    } catch {
      await peer?.close()
      UserDefaults().removePersistentDomain(forName: suite)
      throw error
    }
  }

  func holdFirstSelection() {
    manager.testConnectionSelectionHandler = { [self] requestID in
      guard selectedRequestID == nil else { return }
      selectedRequestID = requestID
      await selection.wait()
    }
  }

  func selectedCandidate() async throws -> (request: ConnectionRequest, attempt: ConnectionAttempt) {
    try await waitUntil("actual session preparation must reach final selection") { self.selection.arrived }
    let request = try #require(manager.desiredRequest)
    let attempt = try #require(request.attempt)
    let resource = try #require(attempt.resource)
    candidates.append(resource)
    try #require(attempt.preparedServices != nil)
    try #require(request.id == selectedRequestID)
    try #require(attempt.binding.isActive)
    try #require(manager.currentConnection == nil)
    let responder = if attempt.target.transportType == .bluetooth { bluetooth } else { peer?.radio ?? modeledWiFi }
    let frames = await responder.sentData
    try #require(frames.contains { $0.first == CommandCode.appStart.rawValue })
    try #require(frames.contains { $0.first == CommandCode.deviceQuery.rawValue })
    try #require(frames.contains { $0.first == CommandCode.getAutoAddConfig.rawValue })
    return (request, attempt)
  }

  func endReceive(_ candidate: ConnectionAttempt, error: (any Error)? = MeshTransportError.notConnected) async throws {
    let socket = try #require(candidate.resource?.wifi)
    if let peer {
      try #require(await peer.acceptedCount == 1)
      try await peer.finishAcceptedConnection()
      try #require(await peer.finishedWriteCount == 1)
    } else {
      await modeledWiFi.simulateSocketReceiveLoss(error: error)
    }
    try await waitUntil("physical receive termination must disconnect the candidate and invoke captured manager revocation") {
      let disconnected = await !socket.isConnected
      return disconnected && !candidate.binding.isActive
    }
    if let peer {
      try await waitUntil("the closed client socket must also end the peer receive worker") { await peer.receiveEndedCount == 1 }
    }
    try #require(manager.currentConnection == nil)
    try #require(manager.desiredRequest?.attempt === candidate)
    try #require(manager.desiredRequest?.worker?.isCancelled == false)
  }

  func releaseSelection() {
    selection.open()
    manager.testConnectionSelectionHandler = nil
  }

  func proveLaterIO() async throws {
    releaseSelection()
    let result = try await manager.connectViaWiFi(host: host, port: port)
    #expect(manager.isCurrent(result))
    #expect(manager.connectionState == .ready)
    let session = try #require(manager.session)
    #expect(try await session.getTime().timeIntervalSince1970 > 0)
  }

  func track<Success: Sendable, Failure: Error>(_ task: Task<Success, Failure>) -> Task<Success, Failure> {
    cancellations.append { task.cancel() }
    joins.append { _ = await task.result }
    return task
  }

  func close() async {
    releaseSelection()
    manager.testBLELifecycleEventHandled = nil
    await bluetooth.releaseAllGates()
    await modeledWiFi.releaseAllGates()
    for cancel in cancellations {
      cancel()
    }
    await peer?.prepareToClose()
    _ = await manager.disconnect(reason: .userInitiated)
    for join in joins {
      await join()
    }
    await manager.retirementTask?.value
    await manager.metadataCommitTask?.value
    for resource in candidates {
      await resource.session.waitForQuiescence()
      #expect(await resource.session.subscriberCountForTest == 0)
    }
    await peer?.close()
    if let peer { #expect(await peer.stopped) }
    #expect(manager.currentConnection == nil)
    #expect(manager.connectionTeardownCount == 0)
    UserDefaults().removePersistentDomain(forName: suite)
  }

  static func withHarness(
    realSocket: Bool = true, wifiLast: Bool = true,
    _ body: (ConnectionWiFiEstablishmentHarness) async throws -> Void
  ) async throws {
    let harness = try await make(realSocket: realSocket, wifiLast: wifiLast)
    do { try await body(harness) } catch { await harness.close(); throw error }
    await harness.close()
  }

  @MainActor
  private final class Gate {
    private var continuation: CheckedContinuation<Void, Never>?
    private var openState = false
    private(set) var arrived = false

    func wait() async {
      arrived = true
      guard !openState else { return }
      await withCheckedContinuation { continuation = $0 }
    }

    func open() {
      openState = true
      continuation?.resume()
      continuation = nil
    }
  }
}
