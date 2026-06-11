import Foundation
import Testing
import os
@testable import MeshCore

@Suite("MeshCoreSession connection state")
struct ConnectionStateTests {
    @Test("initial start emits connecting then connected")
    func initialStartEmitsConnectingThenConnected() async throws {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.2, clientIdentifier: "Test")
        )

        var iterator = await session.connectionState.makeAsyncIterator()
        #expect(await iterator.next() == .disconnected)

        let startTask = Task {
            try await session.start()
        }

        try await waitUntil("transport should have sent appStart") {
            await transport.sentData.count >= 1
        }

        await transport.simulateReceive(makeSelfInfoPacket())
        try await startTask.value

        #expect(await iterator.next() == .connecting)
        #expect(await iterator.next() == .connected)

        await session.stop()
    }

    @Test("reconnect start emits reconnecting then connected")
    func reconnectStartEmitsReconnectingThenConnected() async throws {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.2, clientIdentifier: "Test")
        )

        var iterator = await session.connectionState.makeAsyncIterator()
        #expect(await iterator.next() == .disconnected)

        let startTask = Task {
            try await session.start(reconnectingAttempt: 1)
        }

        try await waitUntil("transport should have sent appStart") {
            await transport.sentData.count >= 1
        }

        await transport.simulateReceive(makeSelfInfoPacket())
        try await startTask.value

        #expect(await iterator.next() == .reconnecting(attempt: 1))
        #expect(await iterator.next() == .connected)

        await session.stop()
    }

    @Test("failed appStart unwinds start so it can be retried")
    func failedAppStartUnwindsStart() async throws {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.05, clientIdentifier: "Test")
        )

        let observedStates = OSAllocatedUnfairLock<[ConnectionState]>(initialState: [])
        let observerTask = Task {
            for await state in await session.connectionState {
                observedStates.withLock { $0.append(state) }
            }
        }
        try await waitUntil("subscriber should see the initial state") {
            observedStates.withLock { $0.first == .disconnected }
        }

        // No selfInfo response arrives, so appStart times out.
        await #expect(throws: MeshCoreError.self) {
            try await session.start()
        }

        try await waitUntil("a failed appStart should publish .failed") {
            observedStates.withLock { states in
                if case .failed = states.last { return true }
                return false
            }
        }
        let states = observedStates.withLock { $0 }
        #expect(states.contains(.connecting))
        #expect(states.contains(.connected))
        #expect(await session.currentSelfInfo == nil)

        // A retry must attempt the handshake again rather than silently no-op.
        await #expect(throws: MeshCoreError.self) {
            try await session.start()
        }
        #expect(await transport.sentData.count == 2, "retry start() should send a fresh appStart")

        observerTask.cancel()
    }

    @Test("getContact rejects short public key before sending")
    func getContactRejectsShortPublicKey() async {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.2, clientIdentifier: "Test")
        )

        await #expect(throws: MeshCoreError.self) {
            _ = try await session.getContact(publicKey: Data(repeating: 0xAA, count: 31))
        }

        #expect(await transport.sentData.isEmpty)
    }

    @Test("requestStatus rejects short public key before sending")
    func requestStatusRejectsShortPublicKey() async {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.2, clientIdentifier: "Test")
        )

        await #expect(throws: MeshCoreError.self) {
            _ = try await session.requestStatus(from: Data(repeating: 0xBB, count: 31))
        }

        #expect(await transport.sentData.isEmpty)
    }

    @Test("setPathHashMode rejects reserved mode before sending")
    func setPathHashModeRejectsReservedMode() async {
        let transport = MockTransport()
        let session = MeshCoreSession(
            transport: transport,
            configuration: SessionConfiguration(defaultTimeout: 0.2, clientIdentifier: "Test")
        )

        await #expect(throws: MeshCoreError.self) {
            try await session.setPathHashMode(3)
        }

        #expect(await transport.sentData.isEmpty)
    }

    private func makeSelfInfoPacket(name: String = "TestNode") -> Data {
        var payload = Data()
        payload.append(0)
        payload.append(0)
        payload.append(0)
        payload.append(Data(repeating: 0x01, count: 32))
        payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
        payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
        payload.append(0)
        payload.append(0)
        payload.append(0)
        payload.append(0)
        payload.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
        payload.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
        payload.append(7)
        payload.append(5)
        payload.append(contentsOf: name.utf8)

        var packet = Data([ResponseCode.selfInfo.rawValue])
        packet.append(payload)
        return packet
    }
}
