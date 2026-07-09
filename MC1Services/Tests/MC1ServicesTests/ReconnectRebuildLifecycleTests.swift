import Foundation
@testable import MC1Services
import MeshCore
import Testing

/// Covers the iOS auto-reconnect rebuild window: the presentation state it
/// surfaces while the session is being rebuilt and its first sync runs, and the
/// guided pairing-failure recovery reaching the user when a bond turns out to be
/// invalidated mid-reconnect.
@Suite("Reconnect Rebuild Lifecycle")
@MainActor
struct ReconnectRebuildLifecycleTests {
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
      syncSucceeded: true,
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
}
