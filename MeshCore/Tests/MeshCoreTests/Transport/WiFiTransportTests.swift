import Testing
import Foundation
import Network
import os
import MeshCoreTestSupport
@testable import MeshCore

@Suite("WiFiTransport Tests")
struct WiFiTransportTests {

    @Test("Initial state is disconnected")
    func initialStateIsDisconnected() async {
        let transport = WiFiTransport()
        let isConnected = await transport.isConnected
        #expect(!isConnected)
    }

    @Test("Advertises pipelined reads without a Write-Without-Response characteristic")
    func advertisesPipelinedReadsButNotWriteWithoutResponse() async {
        let transport = WiFiTransport()
        let pipelined = await transport.supportsPipelinedReads
        let writeWithoutResponse = await transport.supportsWriteWithoutResponse
        #expect(pipelined)
        #expect(!writeWithoutResponse)
    }

    @Test("Connect without configuration throws notConfigured")
    func connectWithoutConfigurationThrows() async {
        let transport = WiFiTransport()

        await #expect(throws: WiFiTransportError.notConfigured) {
            try await transport.connect()
        }
    }

    @Test("Connection to invalid host fails")
    func connectionToInvalidHostFails() async {
        let transport = WiFiTransport()
        await transport.setConnectionInfo(host: "999.999.999.999", port: 5000)

        await #expect(throws: WiFiTransportError.self) {
            try await transport.connect()
        }
    }

    @Test("Send without connection throws notConnected")
    func sendWithoutConnectionThrows() async {
        let transport = WiFiTransport()

        await #expect(throws: WiFiTransportError.notConnected) {
            try await transport.send(Data([0x01, 0x02, 0x03]))
        }
    }

    @Test("Disconnect when not connected is safe")
    func disconnectWhenNotConnectedIsSafe() async {
        let transport = WiFiTransport()
        await transport.disconnect()
        // Should not throw or crash
        let isConnected = await transport.isConnected
        #expect(!isConnected)
    }

    @Test("Configuration can be updated before connect")
    func configurationCanBeUpdated() async {
        let transport = WiFiTransport()
        await transport.setConnectionInfo(host: "192.168.1.1", port: 4000)
        await transport.setConnectionInfo(host: "192.168.1.2", port: 5000)
        // No crash expected; configuration should be updated
        let isConnected = await transport.isConnected
        #expect(!isConnected)
    }

    @Test("Disconnection handler can be set and cleared")
    func disconnectionHandlerCanBeSetAndCleared() async {
        let transport = WiFiTransport()
        let callTracker = CallTracker()

        await transport.setDisconnectionHandler { _ in
            callTracker.markCalled()
        }

        // Handler is set but not called yet (no disconnection)
        #expect(!callTracker.wasCalled)

        // Clear handler should work without crash
        await transport.clearDisconnectionHandler()
    }

    @Test("connectionInfo returns configured host and port")
    func connectionInfoReturnsConfiguredValues() async {
        let transport = WiFiTransport()

        // Initially nil
        let initialInfo = await transport.connectionInfo
        #expect(initialInfo == nil)

        // After configuration
        await transport.setConnectionInfo(host: "192.168.1.50", port: 5000)
        let info = await transport.connectionInfo
        #expect(info?.host == "192.168.1.50")
        #expect(info?.port == 5000)
    }

    @Test("Disconnection handler not called on user-initiated disconnect")
    func disconnectionHandlerNotCalledOnUserDisconnect() async {
        let transport = WiFiTransport()
        let callTracker = CallTracker()

        await transport.setDisconnectionHandler { _ in
            callTracker.markCalled()
        }

        // User-initiated disconnect should NOT trigger handler
        await transport.disconnect()

        // Give any async callbacks time to fire
        try? await Task.sleep(for: .milliseconds(100))

        #expect(!callTracker.wasCalled, "Handler should not be called on user disconnect")
    }

    @Test("Disconnection handler not called on initial connect failure")
    func disconnectionHandlerNotCalledOnInitialConnectFailure() async {
        let transport = WiFiTransport()
        let callTracker = CallTracker()

        await transport.setDisconnectionHandler { _ in
            callTracker.markCalled()
        }

        // Configure to invalid host
        await transport.setConnectionInfo(host: "999.999.999.999", port: 5000)

        // Initial connect failure should NOT trigger disconnection handler
        do {
            try await transport.connect()
        } catch {
            // Expected to fail
        }

        try? await Task.sleep(for: .milliseconds(100))

        #expect(!callTracker.wasCalled, "Handler should not be called on initial connect failure")
    }

    @Test("connect() is idempotent — second call does not create new TCP connection")
    func connectIsIdempotent() async throws {
        let listener = try NWListener(using: .tcp)
        let acceptCount = OSAllocatedUnfairLock(initialState: 0)
        let acceptedConnections = OSAllocatedUnfairLock<[NWConnection]>(initialState: [])

        listener.newConnectionHandler = { conn in
            acceptCount.withLock { $0 += 1 }
            acceptedConnections.withLock { $0.append(conn) }
            conn.start(queue: .global())
        }

        let listenerReady = AsyncStream<NWListener.State>.makeStream()
        listener.stateUpdateHandler = { state in
            listenerReady.continuation.yield(state)
        }
        listener.start(queue: .global(qos: .userInitiated))

        for await state in listenerReady.stream {
            if state == .ready { break }
            if case .failed = state { Issue.record("Listener failed to start"); return }
        }
        listenerReady.continuation.finish()

        guard let port = listener.port?.rawValue else {
            Issue.record("Listener has no port")
            return
        }

        let transport = WiFiTransport()
        await transport.setConnectionInfo(host: "127.0.0.1", port: port)

        // First connect — should establish TCP connection
        try await transport.connect()
        let connected = await transport.isConnected
        #expect(connected, "Transport should be connected after first connect()")

        // Wait for the accept to register
        try await waitUntil(timeout: .seconds(2), "First connection should be accepted") {
            acceptCount.withLock { $0 } >= 1
        }

        // Second connect — should be a no-op
        try await transport.connect()
        let stillConnected = await transport.isConnected
        #expect(stillConnected, "Transport should still be connected after second connect()")

        // Give time for any spurious second accept to arrive.
        // A fixed sleep is correct here: we're waiting for something that should NOT
        // happen, so there's no condition to poll for.
        try await Task.sleep(for: .milliseconds(500))

        let finalCount = acceptCount.withLock { $0 }
        #expect(finalCount == 1, "Expected 1 TCP accept, got \(finalCount) — second connect() should be a no-op")

        // Cleanup
        await transport.disconnect()
        acceptedConnections.withLock { conns in
            for conn in conns { conn.cancel() }
        }
        listener.cancel()
    }

    @Test("Peer close finishes the stream and fires the disconnection handler")
    func peerCloseTearsDownConnection() async throws {
        let acceptedConnections = OSAllocatedUnfairLock<[NWConnection]>(initialState: [])
        let listener = try await startLocalListener { conn in
            acceptedConnections.withLock { $0.append(conn) }
        }
        defer {
            acceptedConnections.withLock { conns in
                for conn in conns { conn.cancel() }
            }
            listener.cancel()
        }

        guard let port = listener.port?.rawValue else {
            Issue.record("Listener has no port")
            return
        }

        let transport = WiFiTransport()
        let disconnectionTracker = CallTracker()
        await transport.setDisconnectionHandler { _ in
            disconnectionTracker.markCalled()
        }
        await transport.setConnectionInfo(host: "127.0.0.1", port: port)
        try await transport.connect()

        let streamEnded = CallTracker()
        let consumeTask = Task {
            for await _ in await transport.receivedData {}
            streamEnded.markCalled()
        }

        try await waitUntil(timeout: .seconds(2), "Server should accept the connection") {
            acceptedConnections.withLock { !$0.isEmpty }
        }

        // The radio closes the TCP stream cleanly (FIN).
        let serverSide = acceptedConnections.withLock { $0.first }
        serverSide?.send(
            content: nil,
            contentContext: .finalMessage,
            isComplete: true,
            completion: .contentProcessed { _ in }
        )

        try await waitUntil(timeout: .seconds(2), "Peer close should fire the disconnection handler") {
            disconnectionTracker.wasCalled
        }
        try await waitUntil(timeout: .seconds(2), "Peer close should finish the receivedData stream") {
            streamEnded.wasCalled
        }
        #expect(await transport.isConnected == false, "Peer close should mark the transport disconnected")

        consumeTask.cancel()
        await transport.disconnect()
    }

    @Test("Cancelling connect() does not leave it parked")
    func cancellingConnectReturnsPromptly() async throws {
        let transport = WiFiTransport()
        // TEST-NET-1 (RFC 5737) is unrouted: the SYN is dropped, so without
        // cancellation support connect() would park until the system TCP timeout.
        await transport.setConnectionInfo(host: "192.0.2.1", port: 9)

        let connectTask = Task { try await transport.connect() }
        try? await Task.sleep(for: .milliseconds(200))
        connectTask.cancel()

        let completed = CallTracker()
        Task {
            _ = await connectTask.result
            completed.markCalled()
        }
        try await waitUntil(timeout: .seconds(3), "connect() should return promptly after cancellation") {
            completed.wasCalled
        }

        await #expect(throws: (any Error).self) { try await connectTask.value }
        #expect(await transport.isConnected == false)
    }
}

/// Starts an `NWListener` on an ephemeral localhost port and waits until it is ready.
private func startLocalListener(
    onAccept: @escaping @Sendable (NWConnection) -> Void
) async throws -> NWListener {
    let listener = try NWListener(using: .tcp)
    listener.newConnectionHandler = { conn in
        onAccept(conn)
        conn.start(queue: .global())
    }

    let listenerReady = AsyncStream<NWListener.State>.makeStream()
    listener.stateUpdateHandler = { state in
        listenerReady.continuation.yield(state)
    }
    listener.start(queue: .global(qos: .userInitiated))

    for await state in listenerReady.stream {
        if state == .ready { break }
        if case .failed(let error) = state {
            listenerReady.continuation.finish()
            throw error
        }
    }
    listenerReady.continuation.finish()
    return listener
}
