import Foundation
import Network
import os

/// Errors that can occur during WiFi transport operations.
public enum WiFiTransportError: Error, Sendable, Equatable {
    case connectionFailed(String)
    case connectionTimeout
    case notConnected
    case sendFailed(String)
    case sendTimeout
    case invalidHost
    case invalidPort
    case notConfigured
}

/// TCP transport for connecting to MeshCore devices over WiFi.
///
/// Configure connection info before calling `connect()`:
/// ```swift
/// let transport = WiFiTransport()
/// await transport.setConnectionInfo(host: "192.168.1.50", port: 5000)
/// try await transport.connect()
/// ```
public actor WiFiTransport: MeshTransport {

    private let logger = Logger(subsystem: "MeshCore", category: "WiFiTransport")

    private var connection: NWConnection?
    private var frameDecoder = WiFiFrameDecoder()

    // AsyncStream created in init, matching BLETransport pattern
    private let dataStream: AsyncStream<Data>
    private let dataContinuation: AsyncStream<Data>.Continuation

    private var _isConnected = false

    // Connection configuration (set before connect())
    private var configuredHost: String?
    private var configuredPort: UInt16?

    // Thread-safe continuation state (protects against multiple resumes)
    private var connectContinuation: CheckedContinuation<Void, Error>?

    // Disconnection notification
    private var disconnectionHandler: (@Sendable (Error?) -> Void)?

    // MARK: - Configuration

    /// Connection timeout duration
    public static let connectionTimeout: Duration = .seconds(10)

    /// Write timeout duration
    public static let writeTimeout: Duration = .seconds(5)

    // MARK: - MeshTransport Protocol

    public var receivedData: AsyncStream<Data> {
        dataStream
    }

    public var isConnected: Bool {
        _isConnected
    }

    /// TCP streams pipeline natively: back-to-back sends queue in the socket buffer and the radio
    /// drains them at its loop cadence, so windowed channel reads avoid the per-round-trip stall
    /// that serial reads pay over WiFi. There is no ATT write characteristic, so
    /// `supportsWriteWithoutResponse` stays false and `sendWithoutResponse` routes to `send`.
    public var supportsPipelinedReads: Bool { true }

    public init() {
        // Create stream in init, matching BLETransport pattern
        let (stream, continuation) = AsyncStream<Data>.makeStream()
        self.dataStream = stream
        self.dataContinuation = continuation
    }

    /// Configures the connection target. Must be called before `connect()`.
    public func setConnectionInfo(host: String, port: UInt16) {
        self.configuredHost = host
        self.configuredPort = port
    }

    /// Sets a handler to be called when the connection is unexpectedly lost.
    public func setDisconnectionHandler(_ handler: @escaping @Sendable (Error?) -> Void) {
        self.disconnectionHandler = handler
    }

    /// Clears the disconnection handler (call before user-initiated disconnect)
    public func clearDisconnectionHandler() {
        self.disconnectionHandler = nil
    }

    /// Returns the configured connection info, if set.
    public var connectionInfo: (host: String, port: UInt16)? {
        guard let host = configuredHost, let port = configuredPort else { return nil }
        return (host, port)
    }

    /// Establishes a TCP connection to the configured host and port.
    /// Call `setConnectionInfo(host:port:)` first.
    public func connect() async throws {
        // Idempotent: skip if already connected. Without this guard,
        // session.start() orphans the TCP connection the caller already established.
        if _isConnected {
            logger.info("Already connected, skipping redundant connect()")
            return
        }

        guard let host = configuredHost, let port = configuredPort else {
            throw WiFiTransportError.notConfigured
        }

        logger.info("Connecting to \(host):\(port)")

        let hostEndpoint = NWEndpoint.Host(host)
        guard let portEndpoint = NWEndpoint.Port(rawValue: port) else {
            throw WiFiTransportError.invalidPort
        }

        let endpoint = NWEndpoint.hostPort(host: hostEndpoint, port: portEndpoint)

        // Disable Nagle's algorithm. The companion protocol is small-frame request-response
        // (each getChannel is one command and one reply), and Nagle interacting with the peer's
        // delayed-ACK injects a fixed per-round-trip stall that serial channel reads pay once per
        // channel. Streamed traffic such as contacts coalesces and hides it; ping-pong reads do not.
        let tcpOptions = NWProtocolTCP.Options()
        tcpOptions.noDelay = true
        let parameters = NWParameters(tls: nil, tcp: tcpOptions)

        let newConnection = NWConnection(to: endpoint, using: parameters)
        connection = newConnection

        // Race connection against timeout
        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask {
                try await self.performConnection(newConnection)
            }

            group.addTask {
                try await Task.sleep(for: Self.connectionTimeout)
                throw WiFiTransportError.connectionTimeout
            }

            // Wait for first task to complete or throw
            do {
                try await group.next()
                group.cancelAll()
            } catch {
                group.cancelAll()
                // On timeout, cancel the pending connection
                if let err = error as? WiFiTransportError, err == .connectionTimeout {
                    self.cancelPendingConnection()
                }
                throw error
            }
        }

        startReceiving()
    }

    /// Performs the actual connection wait via continuation.
    private func performConnection(_ newConnection: NWConnection) async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            self.connectContinuation = continuation

            newConnection.stateUpdateHandler = { [weak self] state in
                Task { [weak self] in
                    await self?.handleStateChange(state)
                }
            }

            newConnection.start(queue: .global(qos: .userInitiated))
        }
    }

    /// Cancels a pending connection attempt.
    private func cancelPendingConnection() {
        connection?.stateUpdateHandler = nil
        connection?.cancel()
        connection = nil
        _isConnected = false

        if let continuation = connectContinuation {
            connectContinuation = nil
            continuation.resume(throwing: WiFiTransportError.connectionTimeout)
        }
    }

    public func disconnect() async {
        logger.info("Disconnecting")
        disconnectionHandler = nil  // Clear BEFORE cancel to prevent spurious callback
        connection?.stateUpdateHandler = nil
        connection?.cancel()
        connection = nil
        _isConnected = false
        frameDecoder.reset()
        dataContinuation.finish()

        // Clear any pending continuation
        if let continuation = connectContinuation {
            continuation.resume(throwing: WiFiTransportError.connectionFailed("Disconnected"))
            connectContinuation = nil
        }
    }

    public func send(_ data: Data) async throws {
        guard let connection, _isConnected else {
            throw WiFiTransportError.notConnected
        }

        let frame = WiFiFrameCodec.encode(data)

        try await withThrowingTaskGroup(of: Void.self) { group in
            group.addTask {
                // Lock-protected continuation prevents double-resume when both
                // contentProcessed and onCancel fire (matches BLETransport pattern)
                let state = OSAllocatedUnfairLock<CheckedContinuation<Void, Error>?>(initialState: nil)

                try await withTaskCancellationHandler {
                    try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
                        let alreadyCancelled = state.withLock { stored -> Bool in
                            if Task.isCancelled {
                                return true
                            }
                            stored = continuation
                            return false
                        }
                        if alreadyCancelled {
                            continuation.resume(throwing: WiFiTransportError.sendTimeout)
                            return
                        }

                        connection.send(content: frame, completion: .contentProcessed { error in
                            if let cont = state.withLock({ let cont = $0; $0 = nil; return cont }) {
                                if let error {
                                    cont.resume(throwing: WiFiTransportError.sendFailed(error.localizedDescription))
                                } else {
                                    cont.resume()
                                }
                            }
                        })
                    }
                } onCancel: {
                    if let cont = state.withLock({ let cont = $0; $0 = nil; return cont }) {
                        cont.resume(throwing: WiFiTransportError.sendTimeout)
                    }
                }
            }

            group.addTask {
                try await Task.sleep(for: Self.writeTimeout)
                throw WiFiTransportError.sendTimeout
            }

            try await group.next()
            group.cancelAll()
        }
    }

    // MARK: - Private

    /// Handles NWConnection state changes with safe one-time continuation resume.
    private func handleStateChange(_ state: NWConnection.State) {
        switch state {
        case .ready:
            logger.info("Connection ready")
            _isConnected = true
            // Safe one-time resume: consume and nil the continuation
            if let continuation = connectContinuation {
                connectContinuation = nil
                continuation.resume()
            }

        case .failed(let error):
            logger.error("Connection failed: \(error.localizedDescription)")
            _isConnected = false
            // Safe one-time resume: consume and nil the continuation
            if let continuation = connectContinuation {
                connectContinuation = nil
                continuation.resume(throwing: WiFiTransportError.connectionFailed(error.localizedDescription))
            } else {
                // Connection was established then lost - notify handler
                disconnectionHandler?(error)
            }

        case .cancelled:
            logger.info("Connection cancelled")
            let wasConnected = _isConnected
            _isConnected = false
            // Only report if this wasn't during initial connection and wasn't user-initiated
            if wasConnected && connectContinuation == nil {
                disconnectionHandler?(nil)
            }

        case .waiting(let error):
            logger.warning("Connection waiting: \(error.localizedDescription)")
            // For invalid addresses, the connection goes to waiting state with an error
            // Treat this as a connection failure
            _isConnected = false
            if let continuation = connectContinuation {
                connectContinuation = nil
                continuation.resume(throwing: WiFiTransportError.connectionFailed(error.localizedDescription))
            }

        default:
            break
        }
    }

    private func startReceiving() {
        guard let connection else { return }

        connection.receive(minimumIncompleteLength: 1, maximumLength: 65536) { [weak self] content, _, isComplete, error in
            Task { [weak self] in
                guard let self else { return }

                if let data = content {
                    await self.processReceivedData(data)
                }

                if let error {
                    self.logger.error("Receive error: \(error.localizedDescription)")
                    return
                }

                if !isComplete {
                    guard await self._isConnected else { return }
                    await self.startReceiving()
                }
            }
        }
    }

    private func processReceivedData(_ data: Data) {
        let frames = frameDecoder.decode(data)
        for frame in frames {
            dataContinuation.yield(frame)
        }
    }
}
