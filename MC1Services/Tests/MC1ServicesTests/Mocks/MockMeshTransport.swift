import Foundation
@testable import MC1Services
@testable import MeshCore

/// Records pairing dispatch and source lifecycle events; session bytes are injected explicitly.
public actor MockMeshTransport: iOSMeshTransport {
  public struct ConnectInvocation: Sendable, Equatable {
    public let deviceID: UUID?
    public let timestamp: Date
  }

  public private(set) var connectInvocations: [ConnectInvocation] = []
  public private(set) var disconnectInvocations = 0
  public private(set) var sentData: [Data] = []
  private var currentDeviceID: UUID?
  private var currentBinding: SessionTransportBinding?
  private var lifecycleHandler: (@Sendable (BLELifecycleEvent) -> Void)?
  private var lifecycleGeneration: UInt64 = 0
  private var lifecycleEventTime = CFAbsoluteTimeGetCurrent()
  private var recovering = false
  private var dataStream: AsyncStream<Data>
  /// Keeps the physical stream open until the test injects a reply or renews it.
  private var dataContinuation: AsyncStream<Data>.Continuation
  private var connected = false
  private var connectError: Error?

  public init() {
    var continuation: AsyncStream<Data>.Continuation!
    dataStream = AsyncStream { continuation = $0 }
    dataContinuation = continuation
  }

  // MARK: - MeshTransport

  public var receivedData: AsyncStream<Data> {
    dataStream
  }

  public var isConnected: Bool {
    connected
  }

  public func connect() async throws {
    connectInvocations.append(ConnectInvocation(deviceID: currentDeviceID, timestamp: Date()))
    if let connectError { throw connectError }
    lifecycleGeneration += 1
    lifecycleEventTime = CFAbsoluteTimeGetCurrent()
    recovering = false
    connected = true
  }

  public func disconnect() async {
    disconnectInvocations += 1
    connected = false
    recovering = false
  }

  public func makeSessionTransport(deviceID: UUID, binding: SessionTransportBinding) -> SessionBoundTransport {
    try? binding.performIfActive {
      if currentBinding !== binding { currentBinding?.revoke() }
      currentDeviceID = deviceID
      currentBinding = binding
    }
    return SessionBoundTransport(transport: self, binding: binding)
  }

  public func disconnect(sessionBinding: SessionTransportBinding) async {
    guard currentBinding === sessionBinding else { return }
    await disconnect()
  }

  // Connect completes in one actor turn, so this fixture has no pending setup to cancel.
  public func cancelPendingEstablishment(sessionBinding: SessionTransportBinding) {}
  public func cancelUnsubmittedWrites(sessionBinding: SessionTransportBinding) {}

  public func connect(sessionBinding: SessionTransportBinding) async throws {
    guard currentBinding === sessionBinding else { throw CancellationError() }
    try sessionBinding.performIfActive {
      connectInvocations.append(ConnectInvocation(deviceID: currentDeviceID, timestamp: Date()))
      if let connectError { throw connectError }
      lifecycleGeneration += 1
      lifecycleEventTime = CFAbsoluteTimeGetCurrent()
      recovering = false
      connected = true
    }
  }

  public func send(_ data: Data, sessionBinding: SessionTransportBinding) throws {
    guard currentBinding === sessionBinding else { throw CancellationError() }
    try sessionBinding.performIfActive { sentData.append(data) }
  }

  public func receivedData(sessionBinding: SessionTransportBinding) -> AsyncStream<Data> {
    guard currentBinding === sessionBinding, sessionBinding.isActive else { return AsyncStream { $0.finish() } }
    return dataStream
  }

  public func refreshDataStream(sessionBinding: SessionTransportBinding) {
    guard currentBinding === sessionBinding, sessionBinding.isActive else { return }
    refreshDataStream()
  }

  public var lifecycleOrigin: BLELifecycleOrigin? {
    guard let currentDeviceID, connected || recovering else { return nil }
    return BLELifecycleOrigin(deviceID: currentDeviceID, generation: lifecycleGeneration, eventTime: lifecycleEventTime)
  }

  public var hasLifecycleHandler: Bool {
    lifecycleHandler != nil
  }

  public func setLifecycleHandler(_ handler: @escaping @Sendable (BLELifecycleEvent) -> Void) {
    lifecycleHandler = handler
  }

  public func send(_ data: Data) async throws {
    sentData.append(data)
  }

  // MARK: - iOSMeshTransport

  public func setDeviceID(_ id: UUID) {
    currentDeviceID = id
  }

  public func refreshDataStream() {
    dataContinuation.finish()
    (dataStream, dataContinuation) = AsyncStream.makeStream(of: Data.self)
  }

  public func primePhysicalLink(deviceID: UUID, generation: UInt64 = 1, recovering: Bool = false) {
    currentDeviceID = deviceID
    lifecycleGeneration = generation
    lifecycleEventTime = CFAbsoluteTimeGetCurrent()
    self.recovering = recovering
    connected = !recovering
  }

  public func simulateReceive(_ data: Data) {
    dataContinuation.yield(data)
  }

  // MARK: - Test Helpers

  /// When set, `connect()` records the invocation then throws this error on every attempt,
  /// modeling a transport that never reaches a BLE link. Lets tests drive the retry budget
  /// without standing up a real session.
  public func setConnectError(_ error: Error?) {
    connectError = error
  }

  /// Emits a reconnected lifecycle event as if iOS auto-reconnect completed
  /// for `deviceID`, including synchronous renewed-stream capture.
  public func simulateReconnection(deviceID: UUID) {
    currentDeviceID = deviceID
    connected = true
    recovering = false
    refreshDataStream()
    let origin = BLELifecycleOrigin(deviceID: deviceID, generation: lifecycleGeneration, eventTime: CFAbsoluteTimeGetCurrent())
    lifecycleHandler?(.reconnected(origin))
  }

  public func simulateAutoReconnecting(deviceID: UUID, errorInfo: String = "none") {
    currentBinding?.revoke()
    currentDeviceID = deviceID
    lifecycleGeneration += 1
    lifecycleEventTime = CFAbsoluteTimeGetCurrent()
    connected = false
    recovering = true
    let origin = BLELifecycleOrigin(deviceID: deviceID, generation: lifecycleGeneration, eventTime: lifecycleEventTime)
    lifecycleHandler?(.autoReconnecting(origin, errorInfo: errorInfo))
  }

  public func simulateDisconnection(deviceID: UUID, error: BLEError? = nil) {
    currentBinding?.revoke()
    connected = false
    recovering = false
    let origin = BLELifecycleOrigin(deviceID: deviceID, generation: lifecycleGeneration, eventTime: CFAbsoluteTimeGetCurrent())
    lifecycleHandler?(.disconnected(origin, error: error))
  }
}
