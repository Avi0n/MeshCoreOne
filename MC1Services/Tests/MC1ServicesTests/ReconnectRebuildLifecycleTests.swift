import CoreBluetooth
import Foundation
@testable import MC1Services
import MeshCore
import ObjectiveC
import Testing

/// Covers the iOS auto-reconnect rebuild window: the presentation state it
/// surfaces while the session is being rebuilt and its first sync runs, and the
/// guided pairing-failure recovery reaching the user when a bond turns out to be
/// invalidated mid-reconnect.
@Suite("Reconnect Rebuild Lifecycle")
@MainActor
struct ReconnectRebuildLifecycleTests {
  @Test
  func `restored session advances past handshake when startup adoption is superseded`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let suiteName = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { UserDefaults().removePersistentDomain(forName: suiteName) }
    let stateMachine = MockBLEStateMachine()
    let transport = RevendingHandshakeTransport()
    let manager = ConnectionManager(
      modelContainer: container, defaults: defaults, stateMachine: stateMachine, transport: transport
    )
    let deviceID = UUID()
    manager.testLastConnectedDeviceID = deviceID
    manager.setTestState(connectionState: .disconnected, connectionIntent: .wantsConnection())
    await stateMachine.setStubbedIsDeviceConnectedToSystem(true)
    let (releaseStream, release) = AsyncStream<Void>.makeStream()
    await transport.setBeforeNextAppStartReply {
      for await _ in releaseStream {
        break
      }
    }
    var rebuildTask: Task<Void, any Error>?
    manager.onAutoReconnectStarted = {
      manager.onAutoReconnectStarted = nil
      await manager.reconnectionCoordinator.handleEnteringAutoReconnect(deviceID: deviceID)
      rebuildTask = Task { try await manager.rebuildSession(deviceID: deviceID) }
      try? await waitUntil(timeout: .seconds(2), "restored session should start its handshake") {
        await transport.appStartReplyCount == 1
      }
    }

    let handled = await manager.startAdoptingLastSystemConnectedPeripheralIfAvailable(
      deviceID: deviceID, context: "test"
    )
    release.yield(())
    release.finish()
    let task = try #require(rebuildTask)
    _ = await task.result

    #expect(handled)
    #expect(await transport.appStartReplyCount == 1)
    #expect(await transport.deviceQueryCount == 1)
    manager.reconnectionCoordinator.cancelTimeout()
    await manager.session?.stop(disconnectTransport: false)
  }

  // MARK: - Presentation state during rebuild + sync

  /// Fresh connect shows `.connected` (link up, sync running) so the syncing pill
  /// is visible; the auto-reconnect rebuild must show the same rung instead of a
  /// prolonged `.connecting` that no UI window bounds. The transport pins the
  /// appStart handshake so the rebuild stays inside its setup+sync window while
  /// the surfaced state is observed.
  @Test
  func `reconnect rebuild surfaces connected while the sync window is still open`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let suiteName = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { UserDefaults().removePersistentDomain(forName: suiteName) }

    let transport = PinnedHandshakeTransport()
    let manager = ConnectionManager(
      modelContainer: container,
      defaults: defaults,
      stateMachine: MockBLEStateMachine(),
      transport: transport
    )

    let deviceID = UUID()
    // The coordinator sets .connecting immediately before invoking rebuildSession.
    manager.setTestState(
      connectionState: .connecting,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )

    let rebuildTask = Task { try? await manager.rebuildSession(deviceID: deviceID) }

    // The handshake is pinned in the transport, so the rebuild cannot advance to
    // promotion; the state observed here is the rung the reconnect window shows.
    try await waitUntil(timeout: .seconds(2), "rebuild should surface .connected during its sync window") {
      manager.connectionState == .connected
    }
    #expect(manager.connectionState == .connected)

    // Release the pinned handshake so the rebuild unwinds and the task finishes.
    await transport.releaseSend()
    _ = await rebuildTask.value
  }

  // MARK: - Bond loss mid-reconnect

  /// An established radio can lose its bond while iOS is auto-reconnecting: the
  /// rebuild's GATT discovery/subscribe surfaces `authenticationFailed` for the
  /// same device the coordinator is reconnecting. That failure must reach the
  /// guided recovery (not be swallowed as a stale-device disconnect), clear the
  /// reconnect claim so silent retries can't hide it, and leave the manager ready
  /// for a re-pair that returns to a working paired state.
  @Test
  func `bond loss during an active reconnect surfaces guided recovery and re-pairs`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let deviceID = UUID()
    var surfaced: [UUID] = []
    manager.onAuthenticationFailure = { surfaced.append($0) }

    manager.setTestState(connectionIntent: .wantsConnection())

    // iOS auto-reconnect claims the cycle: state → .connecting, the coordinator
    // now owns deviceID as the reconnecting device.
    await manager.reconnectionCoordinator.handleEnteringAutoReconnect(deviceID: deviceID)
    #expect(manager.connectionState == .connecting)
    #expect(manager.reconnectionCoordinator.reconnectingDeviceID == deviceID)

    // The rebuild finds the bond invalidated: an authenticationFailed disconnect
    // arrives for the same device mid-reconnect.
    await manager.handleConnectionLoss(deviceID: deviceID, error: BLEError.authenticationFailed)

    #expect(surfaced == [deviceID], "auth failure mid-reconnect must reach guided recovery")
    #expect(manager.connectionState == .disconnected)
    #expect(
      manager.reconnectionCoordinator.reconnectingDeviceID == nil,
      "the stale reconnect claim must be cleared so retries don't hide the recovery"
    )

    // Guided recovery leads the user through a re-pair; the fresh connection
    // promotes to .ready, returning the radio to a working paired state.
    let session = MeshCoreSession(transport: SimulatorMockTransport())
    let services = try await ServiceContainer.forTesting(session: session)
    manager.setTestState(
      connectionState: .connected,
      services: services,
      session: session,
      connectedDevice: DeviceDTO.testDevice(id: deviceID),
      connectionIntent: .wantsConnection()
    )
    let promoted = await manager.promoteToReady(
      syncResult: FullSyncResult(contacts: .clean, channels: .clean, messages: .clean),
      expectedServices: services,
      transportType: .bluetooth
    )
    #expect(promoted)
    #expect(manager.connectionState == .ready)

    manager.stopReconnectionWatchdog()
  }

  /// The mid-reconnect surfacing above must stay specific to the reconnecting
  /// device: a disconnect for a different peripheral (a stale link tearing down
  /// while iOS reconnects the current one) is ignored, and the active reconnect
  /// for the current device survives.
  @Test
  func `a stale-device disconnect during reconnect is ignored and preserves the cycle`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let manager = env.manager
    let deviceID = UUID()
    let staleDeviceID = UUID()
    var surfaced: [UUID] = []
    manager.onAuthenticationFailure = { surfaced.append($0) }

    manager.setTestState(connectionIntent: .wantsConnection())
    await manager.reconnectionCoordinator.handleEnteringAutoReconnect(deviceID: deviceID)

    await manager.handleConnectionLoss(deviceID: staleDeviceID, error: BLEError.authenticationFailed)

    #expect(surfaced.isEmpty, "a disconnect for a different device must not surface recovery")
    #expect(manager.connectionState == .connecting, "the active reconnect for the current device must survive")
    #expect(manager.reconnectionCoordinator.reconnectingDeviceID == deviceID)

    manager.reconnectionCoordinator.cancelTimeout()
  }

  // MARK: - Data stream renewal across rebuild

  /// The rebuild regression gate: stopping the predecessor session cancels its
  /// receive loop, and that cancellation terminates the vended stream's shared
  /// storage. A rebuild over the still-live link must consume a renewed stream,
  /// or its appStart handshake iterates a dead stream and times out. Pins the
  /// `refreshDataStream` call in `rebuildSession`.
  ///
  /// Intent is revoked during the handshake, so rebuild returns before query and sync.
  /// Abandon then clears the session; success is the refreshed handshake, not `manager.session`.
  @Test
  func `rebuild after a stopped predecessor completes its handshake over a refreshed stream`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let suiteName = "test.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    defer { UserDefaults().removePersistentDomain(forName: suiteName) }

    let transport = RevendingHandshakeTransport()
    let manager = ConnectionManager(
      modelContainer: container,
      defaults: defaults,
      stateMachine: MockBLEStateMachine(),
      transport: transport
    )

    // The predecessor completes a real handshake over the vended stream, so its
    // stop inside rebuildSession kills that stream's storage.
    let predecessor = MeshCoreSession(transport: transport)
    try await predecessor.start()
    #expect(await transport.appStartReplyCount == 1)
    #expect(await transport.refreshCount == 0)

    manager.setTestState(
      connectionState: .connected,
      session: predecessor,
      currentTransportType: .bluetooth,
      connectionIntent: .wantsConnection()
    )
    await transport.setBeforeNextAppStartReply { [weak manager] in
      manager?.setTestState(connectionIntent: ConnectionIntent.none)
    }

    // Non-throwing return means session.start completed (dead stream would time out).
    try await manager.rebuildSession(deviceID: UUID())

    #expect(
      await transport.refreshCount == 1,
      "rebuild must refresh the data stream after stopping the predecessor"
    )
    #expect(
      await transport.appStartReplyCount == 2,
      "rebuilt session must complete appStart on the refreshed stream"
    )
    #expect(manager.session == nil, "abandon must drop the session when intent does not want connection")
    #expect(manager.connectionState == .disconnected)
  }

  /// A renewal outside `.connected` is declined: the slot owner keeps its
  /// stream and the transport logs the declined phase instead of vending a
  /// stream no link feeds.
  @Test
  func `renewDataStream declines when the machine is not connected`() async {
    let sm = BLEStateMachine()

    let renewed = await sm.renewDataStream()

    #expect(renewed == nil)
    #expect(await sm.currentPhase.name == "idle")
  }

  /// Renewal keeps the phase `.connected` and must not tear down the RSSI
  /// keepalive the phase owns: a `transition(to:)`-based renewal would cancel
  /// it, since `cleanupPhaseResources` cancels the keepalive whenever it
  /// leaves `.connected`.
  @Test
  func `RSSI keepalive survives a data stream renewal`() async {
    let sm = BLEStateMachine()
    let peripheral = makeRebuildLeakedPeripheral()
    await sm.primeConnectedForRebuild(peripheral: peripheral)
    #expect(await sm.isRSSIKeepaliveActive)

    let renewed = await sm.renewDataStream()

    #expect(renewed != nil)
    #expect(await sm.isRSSIKeepaliveActive)
    #expect(await sm.currentPhase.name == "connected")

    await sm.shutdown()
  }

  /// The renewed stream is the one the `.connected` phase's continuation
  /// feeds, and data yielded after the swap arrives in yield order.
  @Test
  func `a renewed stream delivers subsequent data in order`() async throws {
    let sm = BLEStateMachine()
    let peripheral = makeRebuildLeakedPeripheral()
    await sm.primeConnectedForRebuild(peripheral: peripheral)

    let renewed = try #require(await sm.renewDataStream())
    guard case let .connected(_, _, _, continuation) = await sm.currentPhase else {
      Issue.record("expected .connected after renewal")
      return
    }
    continuation.yield(Data([1]))
    continuation.yield(Data([2]))
    continuation.yield(Data([3]))

    var received: [Data] = []
    for await chunk in renewed {
      received.append(chunk)
      if received.count == 3 { break }
    }
    #expect(received == [Data([1]), Data([2]), Data([3])])

    await sm.shutdown()
  }
}

/// Transport that lets the BLE link "connect" but pins the appStart handshake
/// send until the test releases it, holding a session rebuild inside its
/// setup+sync window so the surfaced connection state can be observed.
private actor PinnedHandshakeTransport: iOSMeshTransport {
  private let dataStream: AsyncStream<Data>
  private let dataContinuation: AsyncStream<Data>.Continuation
  private let releaseStream: AsyncStream<Void>
  private let releaseContinuation: AsyncStream<Void>.Continuation
  private var connected = false

  init() {
    var dataCont: AsyncStream<Data>.Continuation!
    dataStream = AsyncStream { dataCont = $0 }
    dataContinuation = dataCont
    var releaseCont: AsyncStream<Void>.Continuation!
    releaseStream = AsyncStream { releaseCont = $0 }
    releaseContinuation = releaseCont
  }

  var receivedData: AsyncStream<Data> {
    dataStream
  }

  var isConnected: Bool {
    connected
  }

  func connect() async throws {
    connected = true
  }

  func disconnect() async {
    connected = false
    dataContinuation.finish()
  }

  func send(_ data: Data) async throws {
    // Pin the appStart handshake until the test releases it; an AsyncStream gate
    // (rather than a checked continuation) can't crash on an unreleased leak.
    for await _ in releaseStream {
      break
    }
    throw MeshTransportError.notConnected
  }

  /// Unpins the handshake, failing the pending send so the rebuild unwinds
  /// through session.start's failure path.
  func releaseSend() {
    releaseContinuation.yield(())
    releaseContinuation.finish()
  }

  func setDeviceID(_ id: UUID) {}
  func switchDevice(to deviceID: UUID) async throws {}
  func setDisconnectionHandler(_ handler: @escaping @Sendable (UUID, Error?) -> Void) {}
  func setReconnectionHandler(_ handler: @escaping @Sendable (UUID) -> Void) {}

  /// Protocol stub only. The pinned handshake never completes, so a rebuilt
  /// session never consumes a renewed stream.
  func refreshDataStream() {}
}

/// Completes appStart over a single-slot stream so rebuilds must renew
/// a stream terminated by the previous session's receive loop.
private actor RevendingHandshakeTransport: iOSMeshTransport {
  private var slot: AsyncStream<Data>?
  private var slotContinuation: AsyncStream<Data>.Continuation?
  private var connected = false
  private(set) var refreshCount = 0
  private(set) var appStartReplyCount = 0
  private(set) var deviceQueryCount = 0
  private var beforeNextAppStartReply: (@MainActor @Sendable () async -> Void)?

  func setBeforeNextAppStartReply(_ handler: @escaping @MainActor @Sendable () async -> Void) {
    beforeNextAppStartReply = handler
  }

  var receivedData: AsyncStream<Data> {
    slot ?? AsyncStream { $0.finish() }
  }

  var isConnected: Bool {
    connected
  }

  func connect() async throws {
    connected = true
    if slot == nil {
      vendStream()
    }
  }

  func disconnect() async {
    connected = false
    slotContinuation?.finish()
    slot = nil
    slotContinuation = nil
  }

  func send(_ data: Data) async throws {
    // Complete the handshake, then fail deviceQuery so rebuild tests stop
    // before service setup and sync.
    if data.first == CommandCode.appStart.rawValue {
      appStartReplyCount += 1
      let beforeReply = beforeNextAppStartReply
      beforeNextAppStartReply = nil
      await beforeReply?()
      slotContinuation?.yield(Self.selfInfoPacket())
    } else if data.first == CommandCode.deviceQuery.rawValue {
      deviceQueryCount += 1
      throw MeshTransportError.notConnected
    }
  }

  func refreshDataStream() {
    refreshCount += 1
    vendStream()
  }

  func setDeviceID(_ id: UUID) {}
  func switchDevice(to deviceID: UUID) async throws {}
  func setDisconnectionHandler(_ handler: @escaping @Sendable (UUID, Error?) -> Void) {}
  func setReconnectionHandler(_ handler: @escaping @Sendable (UUID) -> Void) {}

  private func vendStream() {
    let (stream, continuation) = AsyncStream.makeStream(
      of: Data.self,
      bufferingPolicy: .bufferingOldest(512)
    )
    slot = stream
    slotContinuation = continuation
  }

  private static func selfInfoPacket() -> Data {
    var payload = Data([ResponseCode.selfInfo.rawValue])
    payload.append(1) // adv type
    payload.append(UInt8(bitPattern: 22)) // tx power
    payload.append(UInt8(bitPattern: 22)) // max tx power
    payload.append(Data(repeating: 0x01, count: 32)) // pubkey
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) }) // lat
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) }) // lon
    payload.append(0) // multi acks
    payload.append(0) // adv loc policy
    payload.append(0) // telemetry mode
    payload.append(0) // manual add
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(869_525).littleEndian) { Array($0) }) // freq
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(250_000).littleEndian) { Array($0) }) // bw
    payload.append(11) // sf
    payload.append(5) // cr
    return payload
  }
}

/// A `CBPeripheral` double with a stable identity and `.connected` state.
private final class RebuildConnectedPeripheral: CBPeripheral, @unchecked Sendable {
  static let uuid = UUID()
  override var identifier: UUID {
    Self.uuid
  }

  override var state: CBPeripheralState {
    .connected
  }
}

/// Keeps raw-allocated peripheral doubles alive for the process lifetime so
/// CoreBluetooth teardown never runs on an instance that skipped its
/// designated initializer.
private enum RebuildPeripheralStore {
  nonisolated(unsafe) static var retained: [CBPeripheral] = []
}

/// Allocates a mock peripheral without invoking `CBPeripheral`'s unavailable
/// initializer and keeps it alive so it is never deallocated.
private func makeRebuildLeakedPeripheral() -> CBPeripheral {
  // swiftlint:disable:next force_cast
  let peripheral = class_createInstance(RebuildConnectedPeripheral.self, 0) as! CBPeripheral
  RebuildPeripheralStore.retained.append(peripheral)
  return peripheral
}

/// Installs `.connected` with a live keepalive on the real actor, mirroring
/// the restoration suite's prime seam, so renewal behavior is exercised on
/// the code under test rather than a mock.
private extension BLEStateMachine {
  func primeConnectedForRebuild(peripheral: CBPeripheral) {
    let tx = CBMutableCharacteristic(
      type: CBUUID(string: BLEServiceUUID.txCharacteristic),
      properties: [.write],
      value: nil,
      permissions: [.writeable]
    )
    let rx = CBMutableCharacteristic(
      type: CBUUID(string: BLEServiceUUID.rxCharacteristic),
      properties: [.notify],
      value: nil,
      permissions: [.readable]
    )
    let (_, continuation) = AsyncStream.makeStream(of: Data.self)
    phase = .connected(peripheral: peripheral, tx: tx, rx: rx, dataContinuation: continuation)
    phaseStartTime = Date()
    startRSSIKeepalive(for: peripheral)
  }
}
