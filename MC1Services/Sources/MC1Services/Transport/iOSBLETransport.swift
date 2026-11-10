@preconcurrency import CoreBluetooth
import Foundation
import MeshCore
import os

/// iOS BLE transport using the BLEStateMachine.
///
/// This is a thin wrapper around `BLEStateMachine` that conforms to `MeshTransport`.
/// The state machine handles all CoreBluetooth complexity including:
///
/// - **State restoration**: Background app relaunch via CoreBluetooth restoration
/// - **Auto-reconnect**: iOS 17+ automatic reconnection handling
/// - **Proper cleanup**: Continuation and resource management on all transitions
///
/// ## Usage
///
/// ```swift
/// // Basic usage with shared state machine
/// let stateMachine = BLEStateMachine()
/// let transport = iOSBLETransport(stateMachine: stateMachine)
/// await transport.setDeviceID(deviceUUID)
/// try await transport.connect()
///
/// ```
actor iOSBLETransport: MeshTransport {
  private let logger = PersistentLogger(subsystem: "com.mc1", category: "iOSBLETransport")

  private let stateMachine: BLEStateMachine
  private var deviceID: UUID?
  private var sessionBinding: SessionTransportBinding?
  private var physicalCloseTask: (id: UUID, task: Task<Void, Never>)?

  /// Lock-protected data stream storage.
  /// Using a lock allows synchronous access from BLEStateMachine callbacks without spawning Tasks.
  private let dataStreamLock = OSAllocatedUnfairLock<AsyncStream<Data>?>(initialState: nil)

  // MARK: - Initialization

  /// Creates an iOS BLE transport with an optional shared state machine.
  ///
  /// - Parameter stateMachine: The BLE state machine to use. If nil, creates a new one.
  init(stateMachine: BLEStateMachine? = nil) {
    self.stateMachine = stateMachine ?? BLEStateMachine()
  }

  func makeSessionTransport(deviceID: UUID, binding: SessionTransportBinding) async -> SessionBoundTransport {
    if let physicalCloseTask { await physicalCloseTask.task.value }
    do {
      try binding.performIfActive {
        if sessionBinding !== binding { sessionBinding?.revoke() }
        self.deviceID = deviceID
        sessionBinding = binding
      }
      await stateMachine.setSessionBinding(binding)
    } catch {
      return SessionBoundTransport(transport: self, binding: binding)
    }
    return SessionBoundTransport(transport: self, binding: binding)
  }

  var lifecycleOrigin: BLELifecycleOrigin? {
    get async { await stateMachine.lifecycleOrigin }
  }

  func setLifecycleHandler(_ handler: @escaping @Sendable (BLELifecycleEvent) -> Void) async {
    await stateMachine.setLifecycleEventHandler { [dataStreamLock] event, stream in
      if let stream { dataStreamLock.withLock { $0 = stream } }
      handler(event)
    }
  }

  // MARK: - Configuration

  /// Sets the device UUID to connect to.
  ///
  /// - Parameter id: The UUID of the BLE device.
  func setDeviceID(_ id: UUID) {
    deviceID = id
  }

  // MARK: - MeshTransport Protocol

  /// Whether the transport is currently connected to a device.
  var isConnected: Bool {
    get async { await stateMachine.isConnected }
  }

  /// Async stream of data received from the connected device.
  ///
  /// Returns an empty stream if not connected.
  var receivedData: AsyncStream<Data> {
    dataStreamLock.withLock { $0 } ?? AsyncStream { $0.finish() }
  }

  /// Connects to the configured radio; repeated connects to the same Device ID are no-ops.
  /// Connecting to a different Device ID requires disconnecting first.
  func connect() async throws {
    try await performConnect(sessionBinding: nil)
  }

  func connect(sessionBinding: SessionTransportBinding) async throws {
    guard self.sessionBinding === sessionBinding else { throw CancellationError() }
    try await performConnect(sessionBinding: sessionBinding)
  }

  private func performConnect(sessionBinding: SessionTransportBinding?) async throws {
    try sessionBinding?.checkActive()
    let configuredDeviceID = deviceID
    let connectedID = await stateMachine.connectedDeviceID
    let effectiveDeviceID = configuredDeviceID ?? connectedID

    guard let deviceID = effectiveDeviceID else {
      logger.warning("[BLE] connect() called with no device ID set")
      throw BLEError.deviceNotFound
    }

    // Already connected - check if it's the same device
    if await stateMachine.isConnected {
      try sessionBinding?.checkActive()
      if connectedID == deviceID {
        logger.info("Already connected to device: \(deviceID)")
        return
      } else {
        throw BLEError.connectionFailed("Already connected to different device: \(connectedID?.uuidString ?? "unknown"). Disconnect before connecting to a different device.")
      }
    }

    logger.info("Connecting to device: \(deviceID)")
    let stream = try await stateMachine.connect(to: deviceID, sessionBinding: sessionBinding)
    try sessionBinding?.checkActive()
    logger.info("[BLE] Stream captured for device: \(deviceID.uuidString.prefix(8))")
    dataStreamLock.withLock { $0 = stream }
  }

  /// Disconnects from the current device.
  func disconnect() async {
    await closePhysicalTransport()
  }

  func disconnect(sessionBinding: SessionTransportBinding) async {
    guard self.sessionBinding === sessionBinding else { return }
    await closePhysicalTransport()
  }

  func cancelPendingEstablishment(sessionBinding: SessionTransportBinding) async {
    guard self.sessionBinding === sessionBinding else { return }
    await stateMachine.cancelPendingEstablishment(sessionBinding: sessionBinding)
  }

  func cancelUnsubmittedWrites(sessionBinding: SessionTransportBinding) async {
    await stateMachine.cancelUnsubmittedWrites(sessionBinding: sessionBinding)
  }

  private func closePhysicalTransport() async {
    if let physicalCloseTask {
      await physicalCloseTask.task.value
      return
    }
    let id = UUID()
    let owner = sessionBinding
    owner?.revoke()
    let worker = Task {
      if let owner {
        await stateMachine.disconnect(sessionBinding: owner)
      } else {
        await stateMachine.disconnect()
      }
    }
    physicalCloseTask = (id, worker)
    await worker.value
    guard physicalCloseTask?.id == id else { return }
    if sessionBinding === owner {
      dataStreamLock.withLock { $0 = nil }
    }
    physicalCloseTask = nil
  }

  func receivedData(sessionBinding: SessionTransportBinding) -> AsyncStream<Data> {
    guard self.sessionBinding === sessionBinding, sessionBinding.isActive else {
      return AsyncStream { $0.finish() }
    }
    return receivedData
  }

  /// Renews the receive stream before reusing a connected link for a new session.
  /// Stopping the previous session cancelled the old stream's shared storage.
  func refreshDataStream() async {
    guard let stream = await stateMachine.renewDataStream() else {
      let phaseName = await stateMachine.currentPhase.name
      logger.warning("[BLE] Data stream refresh declined, phase: \(phaseName)")
      return
    }
    logger.info("[BLE] Data stream refreshed for session rebuild")
    dataStreamLock.withLock { $0 = stream }
  }

  func refreshDataStream(sessionBinding: SessionTransportBinding) async {
    guard self.sessionBinding === sessionBinding, sessionBinding.isActive,
          let stream = await stateMachine.renewDataStream(sessionBinding: sessionBinding),
          self.sessionBinding === sessionBinding, sessionBinding.isActive else { return }
    dataStreamLock.withLock { $0 = stream }
  }

  /// Sends data to the connected device.
  ///
  /// - Parameter data: The data to send.
  /// - Throws: `MeshTransportError.notConnected` before `writeValue`, or `BLEError.notConnected` after it.
  /// - Throws: `BLEError.writeError` if the write fails.
  func send(_ data: Data) async throws {
    try await stateMachine.send(data)
  }

  func send(_ data: Data, sessionBinding: SessionTransportBinding) async throws {
    guard self.sessionBinding === sessionBinding else { throw CancellationError() }
    try await stateMachine.send(data, sessionBinding: sessionBinding)
  }

  /// Whether the connected radio's write characteristic advertises Write-Without-Response.
  var supportsWriteWithoutResponse: Bool {
    get async { await stateMachine.supportsWriteWithoutResponse }
  }

  /// Sends data as an unacknowledged ATT Write Command, enabling request pipelining.
  ///
  /// - Parameter data: The data to send.
  /// - Throws: `MeshTransportError.notConnected` before `writeValue`, or `BLEError.notConnected` after it.
  func sendWithoutResponse(_ data: Data) async throws {
    try await stateMachine.sendWithoutResponse(data)
  }

  func sendWithoutResponse(_ data: Data, sessionBinding: SessionTransportBinding) async throws {
    guard self.sessionBinding === sessionBinding else { throw CancellationError() }
    try await stateMachine.sendWithoutResponse(data, sessionBinding: sessionBinding)
  }

  // MARK: - Extended API

  /// UUID of the currently connected device, or nil if not connected.
  var connectedDeviceID: UUID? {
    get async { await stateMachine.connectedDeviceID }
  }
}
