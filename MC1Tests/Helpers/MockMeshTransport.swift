import Foundation
@testable import MC1Services
@testable import MeshCore

/// In-memory transport for app-layer pairing tests. Mirrors the MC1Services test mock.
public actor MockMeshTransport: iOSMeshTransport {
  public struct ConnectInvocation: Sendable, Equatable {
    public let deviceID: UUID?
    public let timestamp: Date
  }

  public private(set) var connectInvocations: [ConnectInvocation] = []
  public private(set) var disconnectInvocations = 0
  private var currentDeviceID: UUID?
  private var disconnectionHandler: (@Sendable (UUID, Error?) -> Void)?
  private var reconnectionHandler: (@Sendable (UUID) -> Void)?
  private let dataStream: AsyncStream<Data>
  private let dataContinuation: AsyncStream<Data>.Continuation
  private var connected = false
  private var connectError: Error?

  public init() {
    var continuation: AsyncStream<Data>.Continuation!
    dataStream = AsyncStream { continuation = $0 }
    dataContinuation = continuation
  }

  public var receivedData: AsyncStream<Data> {
    dataStream
  }

  public var isConnected: Bool {
    connected
  }

  public func connect() async throws {
    connectInvocations.append(ConnectInvocation(deviceID: currentDeviceID, timestamp: Date()))
    if let connectError { throw connectError }
    connected = true
  }

  public func disconnect() async {
    disconnectInvocations += 1
    connected = false
  }

  public func send(_ data: Data) async throws {}

  public func setDeviceID(_ id: UUID) {
    currentDeviceID = id
  }

  public func switchDevice(to deviceID: UUID) async throws {
    connectInvocations.append(ConnectInvocation(deviceID: deviceID, timestamp: Date()))
    currentDeviceID = deviceID
  }

  public func setDisconnectionHandler(_ handler: @escaping @Sendable (UUID, Error?) -> Void) {
    disconnectionHandler = handler
  }

  public func setReconnectionHandler(_ handler: @escaping @Sendable (UUID) -> Void) {
    reconnectionHandler = handler
  }

  public func refreshDataStream() {}

  public func setConnectError(_ error: Error?) {
    connectError = error
  }
}
