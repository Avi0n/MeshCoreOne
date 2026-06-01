import Foundation

/// Provides a simulated transport for testing and local development.
///
/// `MockTransport` allows developers to test MeshCore functionality without requiring
/// physical hardware or Bluetooth availability. It maintains a history of sent data
/// and provides methods to inject simulated responses from the device.
///
/// ## Testing Strategy
///
/// Use `MockTransport` in unit tests to verify that commands are correctly formatted and
/// sent by the session, and that the session correctly handles various device responses.
///
/// ## Example
///
/// ```swift
/// let mock = MockTransport()
/// let session = MeshCoreSession(transport: mock)
/// try await session.start()
///
/// // Simulate a device response
/// await mock.simulateOK()
///
/// // Verify what was sent
/// let sent = await mock.sentData
/// XCTAssertFalse(sent.isEmpty)
/// ```
public actor MockTransport: MeshTransport {
    /// Stores the history of all data packets sent through this transport.
    public var sentData: [Data] = []

    private let dataStream: AsyncStream<Data>
    private let dataContinuation: AsyncStream<Data>.Continuation

    /// An asynchronous stream of raw data injected via simulation.
    public var receivedData: AsyncStream<Data> { dataStream }

    /// Indicates whether the mock transport is currently "connected".
    public private(set) var isConnected = false

    private var _supportsWriteWithoutResponse = false

    /// Reports the configured Write-Without-Response capability. Defaults to `false` so
    /// existing tests exercise the acknowledged path unless they opt in.
    public var supportsWriteWithoutResponse: Bool { _supportsWriteWithoutResponse }

    /// Opts this transport into advertising Write-Without-Response, so a session routes
    /// through its pipelined `getChannels` path instead of the serial fallback.
    public func setSupportsWriteWithoutResponse(_ supported: Bool) {
        _supportsWriteWithoutResponse = supported
    }

    /// 1-based index of the first send that should fail, simulating a mid-burst transport drop.
    private var failSendsFromIndex: Int?

    /// Makes the `index`-th (1-based) and all later sends throw, simulating a disconnect that
    /// strikes after some commands have already gone out.
    public func failSends(fromSendIndex index: Int) {
        failSendsFromIndex = index
    }

    /// Initializes a new mock transport in a disconnected state.
    public init() {
        let (stream, continuation) = AsyncStream.makeStream(of: Data.self)
        self.dataStream = stream
        self.dataContinuation = continuation
    }

    /// Sets the transport state to connected.
    public func connect() async throws {
        isConnected = true
    }

    /// Sets the transport state to disconnected and finishes the data stream.
    public func disconnect() async {
        isConnected = false
        dataContinuation.finish()
    }

    /// Records the sent data and ensures the transport is connected.
    ///
    /// - Parameter data: The raw bytes to be recorded.
    /// - Throws: ``MeshTransportError/notConnected`` if the transport is not connected.
    public func send(_ data: Data) async throws {
        guard isConnected else {
            throw MeshTransportError.notConnected
        }
        if let threshold = failSendsFromIndex, sentData.count + 1 >= threshold {
            throw MeshTransportError.sendFailed("simulated send failure")
        }
        sentData.append(data)
    }

    /// Injects raw data into the `receivedData` stream to simulate a device response.
    ///
    /// - Parameter data: The raw bytes to be received by the session.
    public func simulateReceive(_ data: Data) {
        dataContinuation.yield(data)
    }

    /// Injects a successful "OK" response into the stream.
    ///
    /// - Parameter value: An optional 32-bit value to include in the OK response (little-endian).
    public func simulateOK(value: UInt32? = nil) {
        var data = Data([ResponseCode.ok.rawValue])
        if let value = value {
            data.append(contentsOf: withUnsafeBytes(of: value.littleEndian) { Array($0) })
        }
        simulateReceive(data)
    }

    /// Injects an error response into the stream.
    ///
    /// - Parameter code: The raw error code byte.
    public func simulateError(code: UInt8) {
        simulateReceive(Data([ResponseCode.error.rawValue, code]))
    }

    /// Empties the `sentData` history.
    public func clearSentData() {
        sentData.removeAll()
    }
}
