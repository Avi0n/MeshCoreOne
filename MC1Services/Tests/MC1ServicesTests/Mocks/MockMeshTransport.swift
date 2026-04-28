import Foundation
@testable import MC1Services
@testable import MeshCore

/// In-memory iOS mesh transport used by the pairing-race integration suite.
/// Records `connect`/`switchDevice` invocations so tests can assert on what
/// the chokepoint actually dispatched. `connect()` deliberately stalls without
/// yielding session bytes — chokepoint-dispatch tests don't need a "ready"
/// session to fire their assertions.
public actor MockMeshTransport: iOSMeshTransport {
    public struct ConnectInvocation: Sendable, Equatable {
        public let deviceID: UUID?
        public let timestamp: Date
    }

    public private(set) var connectInvocations: [ConnectInvocation] = []
    private var currentDeviceID: UUID?
    private var disconnectionHandler: (@Sendable (UUID, Error?) -> Void)?
    private var reconnectionHandler: (@Sendable (UUID) -> Void)?
    private let dataStream: AsyncStream<Data>
    private let dataContinuation: AsyncStream<Data>.Continuation
    private var connected = false

    public init() {
        var continuation: AsyncStream<Data>.Continuation!
        self.dataStream = AsyncStream { continuation = $0 }
        self.dataContinuation = continuation
    }

    // MARK: - MeshTransport

    public var receivedData: AsyncStream<Data> { dataStream }
    public var isConnected: Bool { connected }

    public func connect() async throws {
        connectInvocations.append(ConnectInvocation(deviceID: currentDeviceID, timestamp: Date()))
        connected = true
    }

    public func disconnect() async {
        connected = false
    }

    public func send(_ data: Data) async throws {}

    // MARK: - iOSMeshTransport

    public func setDeviceID(_ id: UUID) { self.currentDeviceID = id }

    public func switchDevice(to deviceID: UUID) async throws {
        connectInvocations.append(ConnectInvocation(deviceID: deviceID, timestamp: Date()))
        self.currentDeviceID = deviceID
    }

    public func setDisconnectionHandler(_ handler: @escaping @Sendable (UUID, Error?) -> Void) {
        self.disconnectionHandler = handler
    }

    public func setReconnectionHandler(_ handler: @escaping @Sendable (UUID) -> Void) {
        self.reconnectionHandler = handler
    }
}
